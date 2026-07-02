package dev.skidfuscator.obfuscator.transform.impl.signature;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.hierarchy.Hierarchy;
import dev.skidfuscator.obfuscator.skidasm.SkidGroup;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import org.mapleir.asm.ClassNode;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Late bytecode transformer that hides internal method parameter descriptors.
 *
 * <p>Eligible methods keep their return type, but their argument list becomes
 * {@code (byte[], Object[])}. Primitive arguments are packed into the byte array;
 * reference and array arguments are packed into the object array. Only app-to-app
 * callsites are rewritten.</p>
 */
public class SignatureObfuscationTransformer extends AbstractTransformer {
    private static final String BYTE_ARRAY_DESC = "[B";
    private static final String OBJECT_ARRAY_DESC = "[Ljava/lang/Object;";
    private static final int MAX_REWRITE_METHOD_INSNS = 3000;
    private static final int MAX_REWRITE_SITES_PER_METHOD = 32;

    /** Every class in the jar (incl. exempt), used to detect in-jar overrides. */
    private Map<String, org.objectweb.asm.tree.ClassNode> universe;
    /** name+desc -> classes that declare it non-private, for fast override lookup. */
    private Map<String, List<org.objectweb.asm.tree.ClassNode>> declarers;

    public SignatureObfuscationTransformer(final Skidfuscator skidfuscator) {
        super(skidfuscator, "Signature Obfuscation");
    }

    @Override
    public boolean isEnabled() {
        return getConfig().getBoolean("enabled", false);
    }

    public void apply() {
        final Map<String, org.objectweb.asm.tree.ClassNode> classes = loadApplicationClasses();
        this.universe = loadAllClasses();
        this.declarers = indexDeclarers(this.universe);
        final Map<MethodKey, MethodNode> methods = indexMethods(classes);
        final Set<MethodKey> handleReferences = collectHandleReferences(classes);
        final Map<MethodKey, Integer> internalCallCounts = collectInternalCalls(classes);
        final Set<MethodKey> threadedSeedMethods = collectThreadedSeedMethods();

        /*
         * Two orthogonal, independently-toggleable rewrites share the same
         * candidate analysis, collision/size guards and callsite pass:
         *   - arguments: pack the parameter list into (byte[], Object[]).
         *   - returns:   wrap the return value into byte[] (primitive returns)
         *                or Object[] (reference/array returns).
         * With returnThreadKey, the method's threaded flow seed (the trailing
         * int parameter added by interprocedural threading) is appended as the
         * LAST array slot. Threaded callers fold that returned key into their own
         * threaded seed local with XOR before reconstructing the real value.
         */
        final boolean obfuscateArgs = getConfig().getBoolean("arguments", true);
        final boolean obfuscateReturns = getConfig().getBoolean("returns", false);
        final boolean threadReturnKey = obfuscateReturns && getConfig().getBoolean("returnThreadKey", false);

        if (!obfuscateArgs && !obfuscateReturns) {
            return;
        }

        Map<MethodKey, Candidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<MethodKey, MethodNode> entry : methods.entrySet()) {
            final MethodKey key = entry.getKey();
            final MethodNode method = entry.getValue();

            final Candidate candidate = buildCandidate(key, method, classes, handleReferences,
                    internalCallCounts, threadedSeedMethods, obfuscateArgs, obfuscateReturns, threadReturnKey);
            if (candidate == null) {
                skip();
                continue;
            }

            candidates.put(key, candidate);
        }

        removeDescriptorCollisions(classes, candidates);
        removeSizeRiskyCallers(classes, candidates);

        if (candidates.isEmpty()) {
            return;
        }

        rewriteCallsites(classes, candidates, threadedSeedMethods);

        for (Map.Entry<MethodKey, Candidate> entry : candidates.entrySet()) {
            final MethodNode method = methods.get(entry.getKey());
            final Candidate candidate = entry.getValue();
            final String oldDesc = method.desc;

            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                rewriteMethodBody(method, oldDesc, candidate);
            }

            method.desc = candidate.newDesc;
            method.signature = null;
            method.parameters = null;
            method.visibleParameterAnnotations = null;
            method.invisibleParameterAnnotations = null;
            method.localVariables = null;
            success();
        }
    }

    /**
     * Rewrites the body of a concrete candidate to match its new descriptor:
     * restores packed arguments (if {@code rewriteArgs}), captures the threaded
     * seed (if {@code threadKey}) and converts every value-return into an array
     * carrier (if {@code rewriteReturn}). Return sites are rewritten before the
     * entry prologue is prepended so the entry insertion never disturbs them.
     */
    private void rewriteMethodBody(final MethodNode method, final String oldDesc, final Candidate candidate) {
        final boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        final int base = isStatic ? 0 : 1;
        method.maxLocals = Math.max(method.maxLocals, computeLocalLimit(method));
        nextScratchLocal = method.maxLocals;
        /*
         * When arguments are packed the new descriptor's (byte[], Object[])
         * parameters occupy base..base+1. If the original body used fewer slots
         * than that (e.g. a single int arg), scratch must still start above the
         * new parameters so the prologue does not clobber the Object[] carrier
         * before it is copied out.
         */
        if (candidate.rewriteArgs) {
            nextScratchLocal = Math.max(nextScratchLocal, base + 2);
        }

        int seedLocal = -1;
        if (candidate.threadKey) {
            seedLocal = nextScratchLocal++;
        }

        if (candidate.rewriteReturn) {
            emitReturnRewrites(method, candidate.returnType, candidate.threadKey, seedLocal);
        }

        final InsnList entry = new InsnList();
        if (candidate.rewriteArgs) {
            appendUnpackPrologue(entry, oldDesc, isStatic);
        }
        if (candidate.threadKey) {
            final int seedParam = seedParamLocal(oldDesc, isStatic);
            entry.add(new VarInsnNode(Opcodes.ILOAD, seedParam));
            entry.add(new VarInsnNode(Opcodes.ISTORE, seedLocal));
        }
        if (entry.size() > 0) {
            method.instructions.insert(entry);
        }

        method.maxLocals = Math.max(method.maxLocals, nextScratchLocal);
        method.maxStack = Math.max(method.maxStack, 8);
    }

    private Map<String, org.objectweb.asm.tree.ClassNode> loadApplicationClasses() {
        final Map<String, org.objectweb.asm.tree.ClassNode> classes = new LinkedHashMap<>();
        for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
            final ClassNode classNode = classData.getClassNode();
            if (classNode == null || classNode.node == null) {
                continue;
            }

            if (isNativeSensitiveClass(classNode.node.name)) {
                continue;
            }

            if (skidfuscator.getExemptAnalysis().isExempt(classNode)
                    || skidfuscator.getExemptAnalysis().isExempt(getClass(), classNode)) {
                continue;
            }

            classes.put(classNode.node.name, classNode.node);
        }
        return classes;
    }

    private boolean isNativeSensitiveClass(final String name) {
        return name != null
                && (name.startsWith("org/jnativehook/") || name.startsWith("com/sun/jna/"));
    }

    /**
     * Every class in the jar, including exempt ones. Exempt classes are NOT
     * candidates themselves, but they may still <i>override</i> an app method,
     * so they must be visible when deciding whether a candidate is safe.
     */
    private Map<String, org.objectweb.asm.tree.ClassNode> loadAllClasses() {
        final Map<String, org.objectweb.asm.tree.ClassNode> all = new LinkedHashMap<>();
        for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
            final ClassNode classNode = classData.getClassNode();
            if (classNode == null || classNode.node == null || isNativeSensitiveClass(classNode.node.name)) {
                continue;
            }
            all.put(classNode.node.name, classNode.node);
        }
        return all;
    }

    private Map<String, List<org.objectweb.asm.tree.ClassNode>> indexDeclarers(
            final Map<String, org.objectweb.asm.tree.ClassNode> all) {
        final Map<String, List<org.objectweb.asm.tree.ClassNode>> index = new HashMap<>();
        for (org.objectweb.asm.tree.ClassNode classNode : all.values()) {
            for (MethodNode method : classNode.methods) {
                if ((method.access & Opcodes.ACC_PRIVATE) != 0) {
                    continue;
                }
                index.computeIfAbsent(method.name + method.desc, ignored -> new ArrayList<>()).add(classNode);
            }
        }
        return index;
    }

    /**
     * True if any class in the jar (including exempt ones) is a subtype of the
     * candidate's owner and redeclares the same name+desc — i.e. the candidate
     * is the base of a virtual-dispatch family. Transforming only the base would
     * silently bypass the override, so such methods are rejected.
     */
    private boolean isOverriddenInHierarchy(final MethodKey key) {
        final List<org.objectweb.asm.tree.ClassNode> list = declarers.get(key.name + key.desc);
        if (list == null) {
            return false;
        }
        for (org.objectweb.asm.tree.ClassNode candidate : list) {
            if (candidate.name.equals(key.owner)) {
                continue;
            }
            if (isSubtypeOf(candidate.name, key.owner, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSubtypeOf(final String child, final String ancestor, final Set<String> visited) {
        if (child == null || !visited.add(child)) {
            return false;
        }
        if (child.equals(ancestor)) {
            return true;
        }
        final org.objectweb.asm.tree.ClassNode node = resolveNode(child);
        if (node == null) {
            return false;
        }
        if (node.superName != null && isSubtypeOf(node.superName, ancestor, visited)) {
            return true;
        }
        for (String itf : node.interfaces) {
            if (isSubtypeOf(itf, ancestor, visited)) {
                return true;
            }
        }
        return false;
    }

    private org.objectweb.asm.tree.ClassNode resolveNode(final String name) {
        final org.objectweb.asm.tree.ClassNode appNode = universe.get(name);
        if (appNode != null) {
            return appNode;
        }
        final ClassNode library = skidfuscator.getClassSource().findClassNode(name);
        return library == null ? null : library.node;
    }

    private Map<MethodKey, MethodNode> indexMethods(final Map<String, org.objectweb.asm.tree.ClassNode> classes) {
        final Map<MethodKey, MethodNode> methods = new LinkedHashMap<>();
        for (org.objectweb.asm.tree.ClassNode classNode : classes.values()) {
            for (MethodNode method : classNode.methods) {
                methods.put(new MethodKey(classNode.name, method.name, method.desc), method);
            }
        }
        return methods;
    }

    private Map<MethodKey, Integer> collectInternalCalls(final Map<String, org.objectweb.asm.tree.ClassNode> classes) {
        final Map<MethodKey, Integer> counts = new HashMap<>();
        for (org.objectweb.asm.tree.ClassNode classNode : classes.values()) {
            for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode)) {
                        continue;
                    }

                    final MethodInsnNode methodInsn = (MethodInsnNode) insn;
                    if (!classes.containsKey(methodInsn.owner)) {
                        continue;
                    }

                    final MethodKey resolved = resolveMethod(classes, methodInsn.owner, methodInsn.name, methodInsn.desc);
                    if (resolved != null) {
                        counts.put(resolved, counts.getOrDefault(resolved, 0) + 1);
                    }
                }
            }
        }
        return counts;
    }

    private Set<MethodKey> collectHandleReferences(final Map<String, org.objectweb.asm.tree.ClassNode> classes) {
        final Set<MethodKey> references = new HashSet<>();
        for (org.objectweb.asm.tree.ClassNode classNode : classes.values()) {
            for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode) {
                        final InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                        addHandleReference(classes, references, indy.bsm);
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle) {
                                addHandleReference(classes, references, (Handle) arg);
                            }
                        }
                    } else if (insn instanceof LdcInsnNode) {
                        final Object cst = ((LdcInsnNode) insn).cst;
                        if (cst instanceof Handle) {
                            addHandleReference(classes, references, (Handle) cst);
                        }
                    }
                }
            }
        }
        return references;
    }

    private void addHandleReference(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                    final Set<MethodKey> references,
                                    final Handle handle) {
        if (handle == null || !classes.containsKey(handle.getOwner())) {
            return;
        }

        final MethodKey resolved = resolveMethod(classes, handle.getOwner(), handle.getName(), handle.getDesc());
        if (resolved != null) {
            references.add(resolved);
        }
    }

    /**
     * Builds the {@link Candidate} for a method, or {@code null} if it is not
     * eligible for either rewrite. Argument packing needs at least one argument;
     * return wrapping needs a non-void return that is not already {@code Object[]}
     * (which would be indistinguishable from an unwrapped value at the callsite).
     */
    private Candidate buildCandidate(final MethodKey key,
                                     final MethodNode method,
                                     final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                     final Set<MethodKey> handleReferences,
                                     final Map<MethodKey, Integer> internalCallCounts,
                                     final Set<MethodKey> threadedSeedMethods,
                                     final boolean obfuscateArgs,
                                     final boolean obfuscateReturns,
                                     final boolean threadReturnKey) {
        if (!internalCallCounts.containsKey(key)) {
            return null;
        }

        if (handleReferences.contains(key)) {
            return null;
        }

        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
            return null;
        }

        if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)
                && (method.access & Opcodes.ACC_STATIC) != 0) {
            return null;
        }

        if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_ABSTRACT)) != 0) {
            return null;
        }

        if (isLargeRewriteMethod(method)) {
            return null;
        }

        final Type[] argumentTypes = Type.getArgumentTypes(method.desc);
        final Type returnType = Type.getReturnType(method.desc);

        final boolean rewriteArgs = obfuscateArgs && argumentTypes.length > 0;
        final boolean rewriteReturn = obfuscateReturns && isWrappableReturn(returnType);
        if (!rewriteArgs && !rewriteReturn) {
            return null;
        }

        final String newDesc = buildNewDesc(method.desc, rewriteArgs, rewriteReturn);
        if (method.desc.equals(newDesc)) {
            return null;
        }

        /*
         * private/static methods are invoked by exact reference (invokespecial/
         * invokestatic), so they have no virtual-dispatch family and are always
         * safe. Any other (overridable) method is rejected if the jar — app or
         * exempt — already overrides it, because the override would keep the
         * original descriptor and dispatch would diverge.
         */
        final boolean overridable = (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0;
        if (overridable && isOverriddenInHierarchy(key)) {
            return null;
        }

        if (overridesExternalContract(classes, key, method)) {
            return null;
        }

        /*
         * The threaded flow seed is the trailing int parameter that
         * interprocedural threading appends to a threaded method (the same
         * value MethodMergeTransformer reads as the public seed). Only such
         * methods can echo a key; the caller is carry-only so an inexact value
         * never affects runtime, but the parameter must genuinely be an int so
         * the entry-capture ILOAD stays verifier-valid.
         */
        final boolean threadKey = rewriteReturn && threadReturnKey
                && threadedSeedMethods.contains(key);

        return new Candidate(newDesc, rewriteArgs, rewriteReturn, returnType, threadKey);
    }

    /**
     * A return type can be wrapped unless it is void (nothing to carry) or
     * already exactly {@code Object[]} (the wrapped descriptor would equal the
     * original, so a callsite could not tell a wrapped value from an unwrapped
     * one). Every other reference/array type maps to {@code Object[]} and every
     * primitive maps to {@code byte[]}, both distinct from the original.
     */
    private boolean isWrappableReturn(final Type returnType) {
        if (returnType.getSort() == Type.VOID) {
            return false;
        }
        return !OBJECT_ARRAY_DESC.equals(returnType.getDescriptor());
    }

    private boolean overridesExternalContract(final Map<String, org.objectweb.asm.tree.ClassNode> appClasses,
                                              final MethodKey key,
                                              final MethodNode method) {
        if ((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) {
            return false;
        }

        if (isObjectContract(key.name, key.desc)) {
            return true;
        }

        final org.objectweb.asm.tree.ClassNode owner = appClasses.get(key.owner);
        if (owner == null) {
            return true;
        }

        final Queue<String> pending = new ArrayDeque<>();
        if (owner.superName != null) {
            pending.add(owner.superName);
        }
        pending.addAll(owner.interfaces);

        final Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            final String name = pending.remove();
            if (name == null || !visited.add(name) || "java/lang/Object".equals(name)) {
                continue;
            }

            final org.objectweb.asm.tree.ClassNode appNode = appClasses.get(name);
            if (appNode != null) {
                if (declaresMethod(appNode, key.name, key.desc)) {
                    return true;
                }

                if (appNode.superName != null) {
                    pending.add(appNode.superName);
                }
                pending.addAll(appNode.interfaces);
                continue;
            }

            final ClassNode externalNode = skidfuscator.getClassSource().findClassNode(name);
            if (externalNode == null || externalNode.node == null) {
                return true;
            }

            if (declaresMethod(externalNode.node, key.name, key.desc)) {
                return true;
            }

            if (externalNode.node.superName != null) {
                pending.add(externalNode.node.superName);
            }
            pending.addAll(externalNode.node.interfaces);
        }

        return false;
    }

    private boolean declaresMethod(final org.objectweb.asm.tree.ClassNode classNode,
                                   final String name,
                                   final String desc) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)
                    && (method.access & Opcodes.ACC_PRIVATE) == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isObjectContract(final String name, final String desc) {
        return ("toString".equals(name) && "()Ljava/lang/String;".equals(desc))
                || ("hashCode".equals(name) && "()I".equals(desc))
                || ("equals".equals(name) && "(Ljava/lang/Object;)Z".equals(desc))
                || ("clone".equals(name) && "()Ljava/lang/Object;".equals(desc))
                || ("finalize".equals(name) && "()V".equals(desc));
    }

    private void removeDescriptorCollisions(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                            final Map<MethodKey, Candidate> candidates) {
        final Set<MethodKey> rejected = new HashSet<>();

        for (org.objectweb.asm.tree.ClassNode classNode : classes.values()) {
            final Map<String, List<MethodKey>> transformedSlots = new HashMap<>();
            final Set<String> existingSlots = new HashSet<>();

            for (MethodNode method : classNode.methods) {
                final MethodKey key = new MethodKey(classNode.name, method.name, method.desc);
                final Candidate candidate = candidates.get(key);
                if (candidate == null) {
                    existingSlots.add(method.name + method.desc);
                } else {
                    final String slot = method.name + candidate.newDesc;
                    transformedSlots.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(key);
                }
            }

            for (Map.Entry<String, List<MethodKey>> entry : transformedSlots.entrySet()) {
                if (existingSlots.contains(entry.getKey()) || entry.getValue().size() > 1) {
                    rejected.addAll(entry.getValue());
                }
            }
        }

        for (MethodKey key : rejected) {
            candidates.remove(key);
            fail();
        }
    }

    private void removeSizeRiskyCallers(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                        final Map<MethodKey, Candidate> candidates) {
        final Set<MethodKey> rejected = new HashSet<>();

        for (org.objectweb.asm.tree.ClassNode classNode : classes.values()) {
            for (MethodNode method : classNode.methods) {
                int sites = 0;
                final Set<MethodKey> calledCandidates = new HashSet<>();

                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode)) {
                        continue;
                    }

                    final MethodInsnNode methodInsn = (MethodInsnNode) insn;
                    if (!classes.containsKey(methodInsn.owner)) {
                        continue;
                    }

                    final MethodKey resolved = resolveMethod(classes, methodInsn.owner, methodInsn.name, methodInsn.desc);
                    if (resolved == null || !candidates.containsKey(resolved)) {
                        continue;
                    }

                    sites++;
                    calledCandidates.add(resolved);
                }

                if (sites > 0 && isRewriteSizeRisk(method, sites)) {
                    rejected.addAll(calledCandidates);
                }
            }
        }

        for (MethodKey key : rejected) {
            if (candidates.remove(key) != null) {
                skip();
            }
        }
    }

    private boolean isRewriteSizeRisk(final MethodNode method, final int siteCount) {
        return isLargeRewriteMethod(method)
                || siteCount > MAX_REWRITE_SITES_PER_METHOD;
    }

    private boolean isLargeRewriteMethod(final MethodNode method) {
        return method.instructions != null
                && method.instructions.size() >= MAX_REWRITE_METHOD_INSNS;
    }

    private void rewriteCallsites(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                  final Map<MethodKey, Candidate> candidates,
                                  final Set<MethodKey> threadedSeedMethods) {
        for (org.objectweb.asm.tree.ClassNode classNode : classes.values()) {
            for (MethodNode method : classNode.methods) {
                boolean changed = false;
                method.maxLocals = Math.max(method.maxLocals, computeLocalLimit(method));
                nextScratchLocal = method.maxLocals;
                final boolean callerStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                final MethodKey callerKey = new MethodKey(classNode.name, method.name, method.desc);
                final int callerSeedLocal = threadedSeedMethods.contains(callerKey)
                        ? seedParamLocal(method.desc, callerStatic)
                        : -1;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode)) {
                        continue;
                    }

                    final MethodInsnNode methodInsn = (MethodInsnNode) insn;
                    if (!classes.containsKey(methodInsn.owner)) {
                        continue;
                    }

                    final MethodKey resolved = resolveMethod(classes, methodInsn.owner, methodInsn.name, methodInsn.desc);
                    final Candidate candidate = resolved == null ? null : candidates.get(resolved);
                    if (candidate == null) {
                        continue;
                    }

                    final Type[] argumentTypes = Type.getArgumentTypes(methodInsn.desc);
                    final Type returnType = Type.getReturnType(methodInsn.desc);
                    final boolean isStatic = methodInsn.getOpcode() == Opcodes.INVOKESTATIC;

                    if (candidate.rewriteArgs) {
                        method.instructions.insertBefore(methodInsn, packArguments(argumentTypes, isStatic));
                    }
                    methodInsn.desc = candidate.newDesc;
                    if (candidate.rewriteReturn) {
                        method.instructions.insert(methodInsn,
                                unpackReturnValue(returnType, candidate.threadKey, callerSeedLocal));
                    }
                    changed = true;
                }

                if (changed) {
                    method.maxLocals = Math.max(method.maxLocals, nextScratchLocal);
                    method.maxStack = Math.max(method.maxStack, 8);
                    method.localVariables = null;
                    method.signature = null;
                }
            }
        }
    }

    private Set<MethodKey> collectThreadedSeedMethods() {
        final Set<MethodKey> threaded = new HashSet<>();
        final Hierarchy hierarchy = skidfuscator.getHierarchy();
        if (hierarchy == null || hierarchy.getGroups() == null) {
            return threaded;
        }

        for (SkidGroup group : hierarchy.getGroups()) {
            if (group == null || !group.isInjectedMethodPredicate() || !hasTrailingIntSeed(group.getDesc())) {
                continue;
            }

            for (org.mapleir.asm.MethodNode methodNode : group.getMethodNodeList()) {
                if (methodNode == null || methodNode.owner == null || methodNode.node == null) {
                    continue;
                }
                threaded.add(new MethodKey(methodNode.owner.getName(), methodNode.getName(), methodNode.node.desc));
            }
        }

        return threaded;
    }

    private MethodKey resolveMethod(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                    final String owner,
                                    final String name,
                                    final String desc) {
        final Set<String> visited = new HashSet<>();
        return resolveMethod(classes, owner, name, desc, visited);
    }

    private MethodKey resolveMethod(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                    final String owner,
                                    final String name,
                                    final String desc,
                                    final Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return null;
        }

        final org.objectweb.asm.tree.ClassNode classNode = classes.get(owner);
        if (classNode == null) {
            return null;
        }

        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return new MethodKey(owner, name, desc);
            }
        }

        for (String itf : classNode.interfaces) {
            final MethodKey resolved = resolveMethod(classes, itf, name, desc, visited);
            if (resolved != null) {
                return resolved;
            }
        }

        return resolveMethod(classes, classNode.superName, name, desc, visited);
    }

    /**
     * Builds the rewritten descriptor. When {@code rewriteArgs} the parameter
     * list collapses to {@code (byte[], Object[])}; when {@code rewriteReturn}
     * the return type becomes {@code byte[]} (primitive) or {@code Object[]}
     * (reference/array). Either flag may be set independently.
     */
    private String buildNewDesc(final String desc, final boolean rewriteArgs, final boolean rewriteReturn) {
        final Type returnType = Type.getReturnType(desc);
        final Type newReturn = rewriteReturn
                ? Type.getType(mappedReturnDesc(returnType))
                : returnType;
        if (rewriteArgs) {
            return Type.getMethodDescriptor(newReturn,
                    Type.getType(BYTE_ARRAY_DESC),
                    Type.getType(OBJECT_ARRAY_DESC));
        }
        return Type.getMethodDescriptor(newReturn, Type.getArgumentTypes(desc));
    }

    private String mappedReturnDesc(final Type returnType) {
        return isPrimitive(returnType) ? BYTE_ARRAY_DESC : OBJECT_ARRAY_DESC;
    }

    private InsnList packArguments(final Type[] argumentTypes, final boolean isStatic) {
        final InsnList insns = new InsnList();
        final int[] argLocals = new int[argumentTypes.length];
        int next = nextScratchLocal;

        for (int i = argumentTypes.length - 1; i >= 0; i--) {
            argLocals[i] = next;
            next += argumentTypes[i].getSize();
            insns.add(new VarInsnNode(argumentTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
        }

        int receiverLocal = -1;
        if (!isStatic) {
            receiverLocal = next++;
            insns.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        }

        final int primitiveSize = primitiveByteSize(argumentTypes);
        final int referenceCount = referenceCount(argumentTypes);
        final int byteArrayLocal = next++;
        final int objectArrayLocal = next++;
        nextScratchLocal = Math.max(nextScratchLocal, next);

        pushInt(insns, primitiveSize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, byteArrayLocal));

        pushInt(insns, referenceCount);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, objectArrayLocal));

        int byteOffset = 0;
        int objectOffset = 0;
        for (int i = 0; i < argumentTypes.length; i++) {
            final Type type = argumentTypes[i];
            if (isPrimitive(type)) {
                byteOffset = emitPrimitivePack(insns, byteArrayLocal, byteOffset, type, argLocals[i]);
            } else {
                insns.add(new VarInsnNode(Opcodes.ALOAD, objectArrayLocal));
                pushInt(insns, objectOffset++);
                insns.add(new VarInsnNode(Opcodes.ALOAD, argLocals[i]));
                insns.add(new InsnNode(Opcodes.AASTORE));
            }
        }

        if (!isStatic) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        }
        insns.add(new VarInsnNode(Opcodes.ALOAD, byteArrayLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, objectArrayLocal));

        return insns;
    }

    private transient int nextScratchLocal;

    /**
     * Appends the argument-unpack prologue to {@code prologue}: it copies the
     * incoming {@code (byte[], Object[])} carriers into scratch locals and
     * restores every original argument into its original local slot, so the
     * untouched method body keeps working against the original layout. Scratch
     * locals are drawn from {@link #nextScratchLocal}.
     */
    private void appendUnpackPrologue(final InsnList prologue, final String oldDesc, final boolean isStatic) {
        final Type[] argumentTypes = Type.getArgumentTypes(oldDesc);
        final int base = isStatic ? 0 : 1;
        final int byteArrayLocal = nextScratchLocal++;
        final int objectArrayLocal = nextScratchLocal++;

        prologue.add(new VarInsnNode(Opcodes.ALOAD, base));
        prologue.add(new VarInsnNode(Opcodes.ASTORE, byteArrayLocal));
        prologue.add(new VarInsnNode(Opcodes.ALOAD, base + 1));
        prologue.add(new VarInsnNode(Opcodes.ASTORE, objectArrayLocal));

        int byteOffset = 0;
        int objectOffset = 0;
        int targetLocal = base;
        for (Type type : argumentTypes) {
            if (isPrimitive(type)) {
                byteOffset = emitPrimitiveUnpack(prologue, byteArrayLocal, byteOffset, type);
                prologue.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), targetLocal));
            } else {
                prologue.add(new VarInsnNode(Opcodes.ALOAD, objectArrayLocal));
                pushInt(prologue, objectOffset++);
                prologue.add(new InsnNode(Opcodes.AALOAD));
                emitCheckCast(prologue, type);
                prologue.add(new VarInsnNode(Opcodes.ASTORE, targetLocal));
            }
            targetLocal += type.getSize();
        }
    }

    /**
     * Converts every value-return in the body into an array carrier. The
     * returned value is packed into a fresh {@code byte[]} (primitive returns,
     * big-endian) or {@code Object[]} (reference returns, slot 0); when
     * {@code threadKey} the captured seed is appended as the trailing 4 bytes /
     * the boxed last slot. A single pair of scratch locals (value + array) is
     * shared across all return sites. Scratch locals are drawn from
     * {@link #nextScratchLocal}.
     */
    private void emitReturnRewrites(final MethodNode method, final Type returnType,
                                    final boolean threadKey, final int seedLocal) {
        final boolean primitive = isPrimitive(returnType);
        final int valueLocal = nextScratchLocal;
        nextScratchLocal += returnType.getSize();
        final int arrayLocal = nextScratchLocal++;

        final List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (isValueReturn(insn.getOpcode())) {
                returns.add(insn);
            }
        }

        for (AbstractInsnNode ret : returns) {
            final InsnList pack = new InsnList();
            if (primitive) {
                final int primSize = primitiveByteSize(new Type[]{returnType});
                pack.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), valueLocal));
                pushInt(pack, primSize + (threadKey ? 4 : 0));
                pack.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
                pack.add(new VarInsnNode(Opcodes.ASTORE, arrayLocal));
                emitPrimitivePack(pack, arrayLocal, 0, returnType, valueLocal);
                if (threadKey) {
                    emitIntByteStores(pack, arrayLocal, primSize, seedLocal, 4);
                }
                pack.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
            } else {
                pack.add(new VarInsnNode(Opcodes.ASTORE, valueLocal));
                pushInt(pack, threadKey ? 2 : 1);
                pack.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
                pack.add(new VarInsnNode(Opcodes.ASTORE, arrayLocal));
                pack.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
                pack.add(new InsnNode(Opcodes.ICONST_0));
                pack.add(new VarInsnNode(Opcodes.ALOAD, valueLocal));
                pack.add(new InsnNode(Opcodes.AASTORE));
                if (threadKey) {
                    pack.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
                    pack.add(new InsnNode(Opcodes.ICONST_1));
                    pack.add(new VarInsnNode(Opcodes.ILOAD, seedLocal));
                    pack.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                            "(I)Ljava/lang/Integer;", false));
                    pack.add(new InsnNode(Opcodes.AASTORE));
                }
                pack.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
            }
            method.instructions.insertBefore(ret, pack);
            method.instructions.set(ret, new InsnNode(Opcodes.ARETURN));
        }
    }

    /**
     * Inserted directly after a rewritten callsite whose target now returns an
     * array carrier: reconstructs the original return value from the FRONT of
     * the array (bytes {@code [0, size)} for {@code byte[]}, slot 0 for
     * {@code Object[]}), leaving exactly the original type on the stack. When
     * both caller and callee are threaded, the returned key slot is XORed into
     * the caller's live threaded seed local before the value is reconstructed.
     * Scratch locals are drawn from {@link #nextScratchLocal}.
     */
    private InsnList unpackReturnValue(final Type returnType,
                                       final boolean threadKey,
                                       final int callerSeedLocal) {
        final InsnList post = new InsnList();
        final int arrayLocal = nextScratchLocal++;
        post.add(new VarInsnNode(Opcodes.ASTORE, arrayLocal));
        if (threadKey && callerSeedLocal >= 0) {
            emitReturnedKeyFold(post, returnType, arrayLocal, callerSeedLocal);
        }
        if (isPrimitive(returnType)) {
            emitPrimitiveUnpack(post, arrayLocal, 0, returnType);
        } else {
            post.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
            post.add(new InsnNode(Opcodes.ICONST_0));
            post.add(new InsnNode(Opcodes.AALOAD));
            emitCheckCast(post, returnType);
        }
        return post;
    }

    private void emitReturnedKeyFold(final InsnList insns,
                                     final Type returnType,
                                     final int arrayLocal,
                                     final int callerSeedLocal) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, callerSeedLocal));
        if (isPrimitive(returnType)) {
            emitLoadInt(insns, arrayLocal, primitiveByteSize(new Type[]{returnType}), 4);
        } else {
            insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new InsnNode(Opcodes.AALOAD));
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
        }
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, callerSeedLocal));
    }

    /**
     * Local slot of the trailing int (the threaded seed) under the original
     * descriptor. The arg-unpack prologue restores the seed into this exact
     * slot, so it is valid whether or not arguments are also packed.
     */
    private int seedParamLocal(final String oldDesc, final boolean isStatic) {
        final Type[] args = Type.getArgumentTypes(oldDesc);
        int local = isStatic ? 0 : 1;
        for (int i = 0; i < args.length - 1; i++) {
            local += args[i].getSize();
        }
        return local;
    }

    private boolean hasTrailingIntSeed(final String desc) {
        if (desc == null) {
            return false;
        }
        final Type[] args = Type.getArgumentTypes(desc);
        return args.length > 0 && args[args.length - 1].getSort() == Type.INT;
    }

    private boolean isValueReturn(final int opcode) {
        return opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN
                || opcode == Opcodes.ARETURN;
    }

    private int computeLocalLimit(final MethodNode method) {
        int limit = argumentLocalLimit(method);
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode) {
                final VarInsnNode varInsn = (VarInsnNode) insn;
                limit = Math.max(limit, varInsn.var + localSize(varInsn.getOpcode()));
            } else if (insn instanceof IincInsnNode) {
                final IincInsnNode iincInsn = (IincInsnNode) insn;
                limit = Math.max(limit, iincInsn.var + 1);
            }
        }
        return limit;
    }

    private int argumentLocalLimit(final MethodNode method) {
        int limit = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : Type.getArgumentTypes(method.desc)) {
            limit += type.getSize();
        }
        return limit;
    }

    private int localSize(final int opcode) {
        switch (opcode) {
            case Opcodes.LLOAD:
            case Opcodes.LSTORE:
            case Opcodes.DLOAD:
            case Opcodes.DSTORE:
                return 2;
            default:
                return 1;
        }
    }

    private int emitPrimitivePack(final InsnList insns,
                                  final int byteArrayLocal,
                                  final int offset,
                                  final Type type,
                                  final int local) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.BYTE:
                emitByteStore(insns, byteArrayLocal, offset, () -> insns.add(new VarInsnNode(Opcodes.ILOAD, local)));
                return offset + 1;
            case Type.CHAR:
            case Type.SHORT:
                emitIntByteStores(insns, byteArrayLocal, offset, local, 2);
                return offset + 2;
            case Type.INT:
                emitIntByteStores(insns, byteArrayLocal, offset, local, 4);
                return offset + 4;
            case Type.FLOAT: {
                final int temp = nextScratchLocal++;
                insns.add(new VarInsnNode(Opcodes.FLOAD, local));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToIntBits", "(F)I", false));
                insns.add(new VarInsnNode(Opcodes.ISTORE, temp));
                emitIntByteStores(insns, byteArrayLocal, offset, temp, 4);
                return offset + 4;
            }
            case Type.LONG:
                emitLongByteStores(insns, byteArrayLocal, offset, local);
                return offset + 8;
            case Type.DOUBLE: {
                final int temp = nextScratchLocal;
                nextScratchLocal += 2;
                insns.add(new VarInsnNode(Opcodes.DLOAD, local));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "doubleToLongBits", "(D)J", false));
                insns.add(new VarInsnNode(Opcodes.LSTORE, temp));
                emitLongByteStores(insns, byteArrayLocal, offset, temp);
                return offset + 8;
            }
            default:
                throw new IllegalArgumentException("Unsupported primitive type: " + type);
        }
    }

    private int emitPrimitiveUnpack(final InsnList insns,
                                    final int byteArrayLocal,
                                    final int offset,
                                    final Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.BYTE:
                emitLoadMaskedByte(insns, byteArrayLocal, offset, false);
                if (type.getSort() == Type.BYTE) {
                    insns.add(new InsnNode(Opcodes.I2B));
                }
                return offset + 1;
            case Type.CHAR:
                emitLoadInt(insns, byteArrayLocal, offset, 2);
                insns.add(new InsnNode(Opcodes.I2C));
                return offset + 2;
            case Type.SHORT:
                emitLoadInt(insns, byteArrayLocal, offset, 2);
                insns.add(new InsnNode(Opcodes.I2S));
                return offset + 2;
            case Type.INT:
                emitLoadInt(insns, byteArrayLocal, offset, 4);
                return offset + 4;
            case Type.FLOAT:
                emitLoadInt(insns, byteArrayLocal, offset, 4);
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false));
                return offset + 4;
            case Type.LONG:
                emitLoadLong(insns, byteArrayLocal, offset);
                return offset + 8;
            case Type.DOUBLE:
                emitLoadLong(insns, byteArrayLocal, offset);
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false));
                return offset + 8;
            default:
                throw new IllegalArgumentException("Unsupported primitive type: " + type);
        }
    }

    private void emitByteStore(final InsnList insns,
                               final int arrayLocal,
                               final int index,
                               final Runnable valueEmitter) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        pushInt(insns, index);
        valueEmitter.run();
        insns.add(new InsnNode(Opcodes.BASTORE));
    }

    private void emitIntByteStores(final InsnList insns,
                                   final int arrayLocal,
                                   final int offset,
                                   final int local,
                                   final int width) {
        for (int i = 0; i < width; i++) {
            final int shift = (width - 1 - i) * 8;
            emitByteStore(insns, arrayLocal, offset + i, () -> {
                insns.add(new VarInsnNode(Opcodes.ILOAD, local));
                if (shift != 0) {
                    pushInt(insns, shift);
                    insns.add(new InsnNode(Opcodes.IUSHR));
                }
            });
        }
    }

    private void emitLongByteStores(final InsnList insns,
                                    final int arrayLocal,
                                    final int offset,
                                    final int local) {
        for (int i = 0; i < 8; i++) {
            final int shift = (7 - i) * 8;
            emitByteStore(insns, arrayLocal, offset + i, () -> {
                insns.add(new VarInsnNode(Opcodes.LLOAD, local));
                if (shift != 0) {
                    pushInt(insns, shift);
                    insns.add(new InsnNode(Opcodes.LUSHR));
                }
                insns.add(new InsnNode(Opcodes.L2I));
            });
        }
    }

    private void emitLoadMaskedByte(final InsnList insns,
                                    final int arrayLocal,
                                    final int index,
                                    final boolean mask) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.BALOAD));
        if (mask) {
            pushInt(insns, 0xFF);
            insns.add(new InsnNode(Opcodes.IAND));
        }
    }

    private void emitLoadInt(final InsnList insns,
                             final int arrayLocal,
                             final int offset,
                             final int width) {
        for (int i = 0; i < width; i++) {
            emitLoadMaskedByte(insns, arrayLocal, offset + i, true);
            final int shift = (width - 1 - i) * 8;
            if (shift != 0) {
                pushInt(insns, shift);
                insns.add(new InsnNode(Opcodes.ISHL));
            }
            if (i != 0) {
                insns.add(new InsnNode(Opcodes.IOR));
            }
        }
    }

    private void emitLoadLong(final InsnList insns,
                              final int arrayLocal,
                              final int offset) {
        for (int i = 0; i < 8; i++) {
            emitLoadMaskedByte(insns, arrayLocal, offset + i, true);
            insns.add(new InsnNode(Opcodes.I2L));
            final int shift = (7 - i) * 8;
            if (shift != 0) {
                pushInt(insns, shift);
                insns.add(new InsnNode(Opcodes.LSHL));
            }
            if (i != 0) {
                insns.add(new InsnNode(Opcodes.LOR));
            }
        }
    }

    private void emitCheckCast(final InsnList insns, final Type type) {
        if (type.getSort() == Type.OBJECT) {
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
        } else if (type.getSort() == Type.ARRAY) {
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getDescriptor()));
        }
    }

    private boolean isPrimitive(final Type type) {
        final int sort = type.getSort();
        return sort >= Type.BOOLEAN && sort <= Type.DOUBLE;
    }

    private int primitiveByteSize(final Type[] types) {
        int size = 0;
        for (Type type : types) {
            switch (type.getSort()) {
                case Type.BOOLEAN:
                case Type.BYTE:
                    size += 1;
                    break;
                case Type.CHAR:
                case Type.SHORT:
                    size += 2;
                    break;
                case Type.INT:
                case Type.FLOAT:
                    size += 4;
                    break;
                case Type.LONG:
                case Type.DOUBLE:
                    size += 8;
                    break;
                default:
                    break;
            }
        }
        return size;
    }

    private int referenceCount(final Type[] types) {
        int count = 0;
        for (Type type : types) {
            if (!isPrimitive(type)) {
                count++;
            }
        }
        return count;
    }

    private void pushInt(final InsnList insns, final int value) {
        if (value >= -1 && value <= 5) {
            insns.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    /** What to do to a single eligible method, computed once during analysis. */
    private static final class Candidate {
        private final String newDesc;
        private final boolean rewriteArgs;
        private final boolean rewriteReturn;
        private final Type returnType;
        private final boolean threadKey;

        private Candidate(final String newDesc, final boolean rewriteArgs, final boolean rewriteReturn,
                          final Type returnType, final boolean threadKey) {
            this.newDesc = newDesc;
            this.rewriteArgs = rewriteArgs;
            this.rewriteReturn = rewriteReturn;
            this.returnType = returnType;
            this.threadKey = threadKey;
        }
    }

    private static final class MethodKey {
        private final String owner;
        private final String name;
        private final String desc;

        private MethodKey(final String owner, final String name, final String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof MethodKey)) return false;
            final MethodKey methodKey = (MethodKey) o;
            return Objects.equals(owner, methodKey.owner)
                    && Objects.equals(name, methodKey.name)
                    && Objects.equals(desc, methodKey.desc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, name, desc);
        }
    }
}
