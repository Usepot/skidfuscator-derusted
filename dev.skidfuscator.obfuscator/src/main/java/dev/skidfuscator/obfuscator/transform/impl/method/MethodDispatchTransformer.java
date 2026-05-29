package dev.skidfuscator.obfuscator.transform.impl.method;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import org.mapleir.asm.ClassNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Late bytecode transformer that funnels method calls through a single per-class
 * dispatcher. Every eligible call site is rewritten to invoke a synthetic
 * {@code (byte[], Object[]) -> Object} method on the caller's own class. The
 * first four bytes of the byte array carry a random 32-bit <i>signature key</i>;
 * the dispatcher reassembles that key, scrambles it with a bijective
 * {@code lowbias32} hash, and {@code lookupswitch}es on the result to a case that
 * directly re-invokes the original target. Primitive arguments are packed into
 * the byte array (after the 4-byte key), references and the receiver into the
 * object array; the original return value is boxed/null-wrapped and re-cast at
 * the call site.
 *
 * <p>The dispatcher lives in the <b>caller's</b> class so it inherits that
 * class's exact access context, letting the case bodies emit the original
 * {@code invokestatic/virtual/interface/special} verbatim. Because every emitted
 * reference uses pre-rename names, the write-time {@code ClassRemapper} and
 * {@code COMPUTE_FRAMES} fix up names and stack-map frames.</p>
 */
public class MethodDispatchTransformer extends AbstractTransformer {
    private static final String OBJECT = "java/lang/Object";
    private static final String DISPATCH_DESC = "([B[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String DISPATCH_PREFIX = "skid$dispatch$";

    private static final int LOWBIAS_C1 = 0x7feb352d;
    private static final int LOWBIAS_C2 = 0x846ca68b;

    /** App, non-exempt, non-native-sensitive classes — the only dispatcher hosts. */
    private Map<String, org.objectweb.asm.tree.ClassNode> appClasses;
    /** Every class in the jar (incl. exempt), used for resolution / subtype checks. */
    private Map<String, org.objectweb.asm.tree.ClassNode> universe;

    private transient int nextScratchLocal;

    public MethodDispatchTransformer(final Skidfuscator skidfuscator) {
        super(skidfuscator, "Method Dispatch");
    }

    @Override
    public boolean isEnabled() {
        return getConfig().getBoolean("enabled", false);
    }

    private enum Scope { APP_ONLY, INCLUDE_LIBRARY, STATIC_ONLY }

    public void apply() {
        this.appClasses = loadApplicationClasses();
        this.universe = loadAllClasses();
        if (appClasses.isEmpty()) {
            return;
        }

        final Scope scope = parseScope(getConfig().getString("scope", "APP_ONLY"));
        final int maxPerDispatcher = Math.max(1, getConfig().getInt("maxPerDispatcher", 64));

        for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
            final ClassNode wrapper = classData.getClassNode();
            if (wrapper == null || wrapper.node == null) {
                continue;
            }
            if (wrapper.isVirtual() || isNativeSensitiveClass(wrapper.node.name) || isClassExempt(wrapper)) {
                skip();
                continue;
            }
            if (!appClasses.containsKey(wrapper.node.name)) {
                continue;
            }
            processClass(wrapper, wrapper.node, scope, maxPerDispatcher);
        }
    }

    private Scope parseScope(final String raw) {
        if (raw == null) {
            return Scope.APP_ONLY;
        }
        try {
            return Scope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Scope.APP_ONLY;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Per-class processing                                                */
    /* ------------------------------------------------------------------ */

    private void processClass(final ClassNode wrapper,
                              final org.objectweb.asm.tree.ClassNode classNode,
                              final Scope scope,
                              final int maxPerDispatcher) {
        final Map<String, Target> distinct = new LinkedHashMap<>();
        final List<Site> sites = new ArrayList<>();

        for (MethodNode method : classNode.methods) {
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            if ("<init>".equals(method.name)) {
                // Funnelling a call on an uninitialised `this` (before super())
                // would store uninitialisedThis into the carrier array and fail
                // verification; skip constructors wholesale.
                continue;
            }
            if (isMethodExempt(wrapper, method)) {
                continue;
            }

            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                final MethodInsnNode methodInsn = (MethodInsnNode) insn;
                final Target target = resolveEligibleTarget(classNode.name, methodInsn, scope);
                if (target == null) {
                    skip();
                    continue;
                }
                final String dedupKey = dedupKey(methodInsn);
                distinct.putIfAbsent(dedupKey, target);
                sites.add(new Site(method, methodInsn, dedupKey));
            }
        }

        if (distinct.isEmpty()) {
            return;
        }

        final List<MethodNode> dispatchers = assignDispatchers(classNode, distinct, maxPerDispatcher);
        rewriteSites(classNode, distinct, sites);
        classNode.methods.addAll(dispatchers);
    }

    /**
     * Buckets the distinct targets into dispatcher methods of at most
     * {@code maxPerDispatcher} cases, assigns each target a unique signature key
     * and its owning dispatcher name, and builds the dispatcher bodies.
     */
    private List<MethodNode> assignDispatchers(final org.objectweb.asm.tree.ClassNode classNode,
                                               final Map<String, Target> distinct,
                                               final int maxPerDispatcher) {
        final List<Target> all = new ArrayList<>(distinct.values());
        final List<MethodNode> dispatchers = new ArrayList<>();

        for (int start = 0; start < all.size(); start += maxPerDispatcher) {
            final List<Target> bucket = all.subList(start, Math.min(start + maxPerDispatcher, all.size()));
            final String name = uniqueMethodName(classNode, DISPATCH_PREFIX, DISPATCH_DESC);

            final Set<Integer> usedKeys = new HashSet<>();
            for (Target target : bucket) {
                int key;
                do {
                    key = ThreadLocalRandom.current().nextInt();
                } while (!usedKeys.add(key));
                target.sigKey = key;
                target.dispatcherName = name;
            }

            dispatchers.add(buildDispatcher(name, new ArrayList<>(bucket)));
        }
        return dispatchers;
    }

    private void rewriteSites(final org.objectweb.asm.tree.ClassNode classNode,
                              final Map<String, Target> distinct,
                              final List<Site> sites) {
        final Map<MethodNode, List<Site>> byMethod = new LinkedHashMap<>();
        for (Site site : sites) {
            byMethod.computeIfAbsent(site.method, ignored -> new ArrayList<>()).add(site);
        }

        for (Map.Entry<MethodNode, List<Site>> entry : byMethod.entrySet()) {
            final MethodNode method = entry.getKey();
            method.maxLocals = Math.max(method.maxLocals, computeLocalLimit(method));
            nextScratchLocal = method.maxLocals;

            for (Site site : entry.getValue()) {
                final MethodInsnNode methodInsn = site.insn;
                final Target target = distinct.get(site.dedupKey);
                final Type[] args = Type.getArgumentTypes(methodInsn.desc);
                final Type ret = Type.getReturnType(methodInsn.desc);
                final boolean isInstance = methodInsn.getOpcode() != Opcodes.INVOKESTATIC;

                final InsnList pre = packForDispatch(args, isInstance, target.sigKey);
                final InsnList post = returnAdapt(ret);

                method.instructions.insertBefore(methodInsn, pre);
                if (post.size() > 0) {
                    method.instructions.insert(methodInsn, post);
                }

                methodInsn.setOpcode(Opcodes.INVOKESTATIC);
                methodInsn.owner = classNode.name;
                methodInsn.name = target.dispatcherName;
                methodInsn.desc = DISPATCH_DESC;
                methodInsn.itf = false;
                success();
            }

            method.maxLocals = Math.max(method.maxLocals, nextScratchLocal);
            method.maxStack = Math.max(method.maxStack, 8);
            method.localVariables = null;
            method.signature = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Eligibility                                                         */
    /* ------------------------------------------------------------------ */

    private Target resolveEligibleTarget(final String callerClass,
                                         final MethodInsnNode methodInsn,
                                         final Scope scope) {
        final int opcode = methodInsn.getOpcode();
        if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKEVIRTUAL
                && opcode != Opcodes.INVOKEINTERFACE && opcode != Opcodes.INVOKESPECIAL) {
            return null;
        }
        if ("<init>".equals(methodInsn.name) || "<clinit>".equals(methodInsn.name)) {
            return null;
        }
        final String owner = methodInsn.owner;
        if (owner == null || owner.startsWith("[")) {
            return null;
        }
        if (isSignaturePolymorphic(owner)) {
            return null;
        }

        final boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        final boolean appResolves = resolveMethod(appClasses, owner, methodInsn.name, methodInsn.desc) != null;

        switch (scope) {
            case STATIC_ONLY:
                if (!isStatic) {
                    return null;
                }
                break;
            case APP_ONLY:
                if (!appResolves) {
                    return null;
                }
                break;
            case INCLUDE_LIBRARY:
            default:
                break;
        }

        final MethodNode resolved = resolveMethodNode(owner, methodInsn.name, methodInsn.desc);
        // A non-static call needs the resolved declaration to pick a verifier-safe
        // receiver cast; if we cannot find it we leave the call untouched.
        if (!isStatic && resolved == null) {
            return null;
        }

        if (opcode == Opcodes.INVOKESPECIAL) {
            final boolean superOrSelf = owner.equals(callerClass)
                    || isSubtypeOf(callerClass, owner, new HashSet<>());
            if (!superOrSelf) {
                return null;
            }
        }

        final String castType = receiverCastType(callerClass, methodInsn, resolved);
        return new Target(owner, methodInsn.name, methodInsn.desc, opcode, methodInsn.itf, isStatic, castType);
    }

    /**
     * Picks the type the receiver is cast to inside the dispatcher case.
     *
     * <ul>
     *   <li>{@code invokespecial} (private / super calls): the receiver is always
     *       conceptually {@code this}, so casting to the caller class satisfies
     *       the verifier's "objectref assignable to current class" rule.</li>
     *   <li>{@code invokevirtual/interface} of a {@code protected} method declared
     *       in another package: the verifier requires the receiver to be assignable
     *       to the caller class, so cast to it (the caller is necessarily a subtype
     *       of the owner here).</li>
     *   <li>otherwise: cast to the declared owner.</li>
     * </ul>
     */
    private String receiverCastType(final String callerClass,
                                    final MethodInsnNode methodInsn,
                                    final MethodNode resolved) {
        if (methodInsn.getOpcode() == Opcodes.INVOKESPECIAL) {
            return callerClass;
        }
        if (resolved != null
                && (resolved.access & Opcodes.ACC_PROTECTED) != 0
                && !samePackage(methodInsn.owner, callerClass)) {
            return callerClass;
        }
        return methodInsn.owner;
    }

    private boolean isSignaturePolymorphic(final String owner) {
        return "java/lang/invoke/MethodHandle".equals(owner)
                || "java/lang/invoke/VarHandle".equals(owner);
    }

    private boolean samePackage(final String a, final String b) {
        return packageOf(a).equals(packageOf(b));
    }

    private String packageOf(final String internalName) {
        final int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    /* ------------------------------------------------------------------ */
    /* Dispatcher construction                                             */
    /* ------------------------------------------------------------------ */

    private MethodNode buildDispatcher(final String name, final List<Target> targets) {
        final MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, DISPATCH_DESC, null, null);
        final InsnList out = method.instructions;

        final int byteArrayLocal = 0;
        final int objectArrayLocal = 1;

        targets.sort((a, b) -> Integer.compare(lowbias32(a.sigKey), lowbias32(b.sigKey)));
        final int[] keys = new int[targets.size()];
        final LabelNode[] labels = new LabelNode[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            keys[i] = lowbias32(targets.get(i).sigKey);
            labels[i] = new LabelNode();
        }
        final LabelNode defaultLabel = new LabelNode();

        emitLoadInt(out, byteArrayLocal, 0, 4);
        emitLowbias32(out);
        out.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));

        int maxStack = 6;
        for (int i = 0; i < targets.size(); i++) {
            out.add(labels[i]);
            final Target target = targets.get(i);
            final Type[] args = Type.getArgumentTypes(target.desc);

            if (!target.isStatic) {
                out.add(new VarInsnNode(Opcodes.ALOAD, objectArrayLocal));
                out.add(new InsnNode(Opcodes.ICONST_0));
                out.add(new InsnNode(Opcodes.AALOAD));
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, target.castType));
            }

            int byteOffset = 4;
            int objectIndex = target.isStatic ? 0 : 1;
            for (final Type arg : args) {
                if (isPrimitive(arg)) {
                    byteOffset = emitPrimitiveUnpack(out, byteArrayLocal, byteOffset, arg);
                } else {
                    out.add(new VarInsnNode(Opcodes.ALOAD, objectArrayLocal));
                    pushInt(out, objectIndex++);
                    out.add(new InsnNode(Opcodes.AALOAD));
                    emitCheckCast(out, arg);
                }
            }

            out.add(new MethodInsnNode(target.opcode, target.owner, target.name, target.desc, target.itf));

            final Type ret = Type.getReturnType(target.desc);
            if (ret.getSort() == Type.VOID) {
                out.add(new InsnNode(Opcodes.ACONST_NULL));
            } else if (isPrimitive(ret)) {
                emitBox(out, ret);
            }
            out.add(new InsnNode(Opcodes.ARETURN));

            final int slots = (target.isStatic ? 0 : 1) + slotCount(args);
            maxStack = Math.max(maxStack, slots + 4);
        }

        out.add(defaultLabel);
        out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        out.add(new InsnNode(Opcodes.DUP));
        out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        out.add(new InsnNode(Opcodes.ATHROW));

        method.maxLocals = 2;
        method.maxStack = maxStack;
        return method;
    }

    private void emitLowbias32(final InsnList out) {
        // x ^= x >>> 16
        out.add(new InsnNode(Opcodes.DUP));
        pushInt(out, 16);
        out.add(new InsnNode(Opcodes.IUSHR));
        out.add(new InsnNode(Opcodes.IXOR));
        // x *= 0x7feb352d
        out.add(new LdcInsnNode(LOWBIAS_C1));
        out.add(new InsnNode(Opcodes.IMUL));
        // x ^= x >>> 15
        out.add(new InsnNode(Opcodes.DUP));
        pushInt(out, 15);
        out.add(new InsnNode(Opcodes.IUSHR));
        out.add(new InsnNode(Opcodes.IXOR));
        // x *= 0x846ca68b
        out.add(new LdcInsnNode(LOWBIAS_C2));
        out.add(new InsnNode(Opcodes.IMUL));
        // x ^= x >>> 16
        out.add(new InsnNode(Opcodes.DUP));
        pushInt(out, 16);
        out.add(new InsnNode(Opcodes.IUSHR));
        out.add(new InsnNode(Opcodes.IXOR));
    }

    private static int lowbias32(int x) {
        x ^= x >>> 16;
        x *= LOWBIAS_C1;
        x ^= x >>> 15;
        x *= LOWBIAS_C2;
        x ^= x >>> 16;
        return x;
    }

    /* ------------------------------------------------------------------ */
    /* Call-site packing                                                   */
    /* ------------------------------------------------------------------ */

    private InsnList packForDispatch(final Type[] args, final boolean isInstance, final int sigKey) {
        final InsnList insns = new InsnList();
        final int[] argLocals = new int[args.length];
        int next = nextScratchLocal;

        for (int i = args.length - 1; i >= 0; i--) {
            argLocals[i] = next;
            next += args[i].getSize();
            insns.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
        }

        int receiverLocal = -1;
        if (isInstance) {
            receiverLocal = next++;
            insns.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        }

        final int primitiveSize = primitiveByteSize(args);
        final int referenceCount = referenceCount(args);
        final int byteArrayLocal = next++;
        final int objectArrayLocal = next++;
        nextScratchLocal = Math.max(nextScratchLocal, next);

        pushInt(insns, 4 + primitiveSize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, byteArrayLocal));

        for (int i = 0; i < 4; i++) {
            final int keyByte = (sigKey >>> ((3 - i) * 8)) & 0xFF;
            emitByteStore(insns, byteArrayLocal, i, () -> pushInt(insns, keyByte));
        }

        pushInt(insns, referenceCount + (isInstance ? 1 : 0));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT));
        insns.add(new VarInsnNode(Opcodes.ASTORE, objectArrayLocal));

        if (isInstance) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, objectArrayLocal));
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
            insns.add(new InsnNode(Opcodes.AASTORE));
        }

        int byteOffset = 4;
        int objectIndex = isInstance ? 1 : 0;
        for (int i = 0; i < args.length; i++) {
            final Type type = args[i];
            if (isPrimitive(type)) {
                byteOffset = emitPrimitivePack(insns, byteArrayLocal, byteOffset, type, argLocals[i]);
            } else {
                insns.add(new VarInsnNode(Opcodes.ALOAD, objectArrayLocal));
                pushInt(insns, objectIndex++);
                insns.add(new VarInsnNode(Opcodes.ALOAD, argLocals[i]));
                insns.add(new InsnNode(Opcodes.AASTORE));
            }
        }

        insns.add(new VarInsnNode(Opcodes.ALOAD, byteArrayLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, objectArrayLocal));
        return insns;
    }

    private InsnList returnAdapt(final Type ret) {
        final InsnList insns = new InsnList();
        if (ret.getSort() == Type.VOID) {
            insns.add(new InsnNode(Opcodes.POP));
        } else if (isPrimitive(ret)) {
            emitUnbox(insns, ret);
        } else if (!(ret.getSort() == Type.OBJECT && OBJECT.equals(ret.getInternalName()))) {
            emitCheckCast(insns, ret);
        }
        return insns;
    }

    /* ------------------------------------------------------------------ */
    /* Boxing helpers                                                      */
    /* ------------------------------------------------------------------ */

    private void emitBox(final InsnList insns, final Type type) {
        final String wrapper = wrapperType(type);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                "(" + type.getDescriptor() + ")L" + wrapper + ";", false));
    }

    private void emitUnbox(final InsnList insns, final Type type) {
        final String wrapper = wrapperType(type);
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper,
                unboxMethod(type), "()" + type.getDescriptor(), false));
    }

    private String wrapperType(final Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN: return "java/lang/Boolean";
            case Type.BYTE:    return "java/lang/Byte";
            case Type.CHAR:    return "java/lang/Character";
            case Type.SHORT:   return "java/lang/Short";
            case Type.INT:     return "java/lang/Integer";
            case Type.FLOAT:   return "java/lang/Float";
            case Type.LONG:    return "java/lang/Long";
            case Type.DOUBLE:  return "java/lang/Double";
            default: throw new IllegalArgumentException("Not a primitive: " + type);
        }
    }

    private String unboxMethod(final Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN: return "booleanValue";
            case Type.BYTE:    return "byteValue";
            case Type.CHAR:    return "charValue";
            case Type.SHORT:   return "shortValue";
            case Type.INT:     return "intValue";
            case Type.FLOAT:   return "floatValue";
            case Type.LONG:    return "longValue";
            case Type.DOUBLE:  return "doubleValue";
            default: throw new IllegalArgumentException("Not a primitive: " + type);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Class loading / indexing                                            */
    /* ------------------------------------------------------------------ */

    private Map<String, org.objectweb.asm.tree.ClassNode> loadApplicationClasses() {
        final Map<String, org.objectweb.asm.tree.ClassNode> classes = new LinkedHashMap<>();
        for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
            final ClassNode classNode = classData.getClassNode();
            if (classNode == null || classNode.node == null || isNativeSensitiveClass(classNode.node.name)) {
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

    private boolean isNativeSensitiveClass(final String name) {
        return name != null
                && (name.startsWith("org/jnativehook/") || name.startsWith("com/sun/jna/"));
    }

    private boolean isClassExempt(final ClassNode classNode) {
        return skidfuscator.getExemptAnalysis().isExempt(classNode)
                || skidfuscator.getExemptAnalysis().isExempt(getClass(), classNode);
    }

    private boolean isMethodExempt(final ClassNode classNode, final MethodNode method) {
        final org.mapleir.asm.MethodNode wrapped = findWrappedMethod(classNode, method);
        return wrapped != null
                && (skidfuscator.getExemptAnalysis().isExempt(wrapped)
                || skidfuscator.getExemptAnalysis().isExempt(getClass(), wrapped));
    }

    private org.mapleir.asm.MethodNode findWrappedMethod(final ClassNode classNode, final MethodNode method) {
        for (org.mapleir.asm.MethodNode candidate : classNode.getMethods()) {
            if (candidate.node == method) {
                return candidate;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Resolution / hierarchy                                              */
    /* ------------------------------------------------------------------ */

    private MethodKey resolveMethod(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                    final String owner, final String name, final String desc) {
        return resolveMethod(classes, owner, name, desc, new HashSet<>());
    }

    private MethodKey resolveMethod(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                    final String owner, final String name, final String desc,
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

    /** Resolves the declaring {@link MethodNode}, searching app classes then libraries. */
    private MethodNode resolveMethodNode(final String owner, final String name, final String desc) {
        return resolveMethodNode(owner, name, desc, new HashSet<>());
    }

    private MethodNode resolveMethodNode(final String owner, final String name, final String desc,
                                         final Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return null;
        }
        final org.objectweb.asm.tree.ClassNode classNode = resolveNode(owner);
        if (classNode == null) {
            return null;
        }
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        for (String itf : classNode.interfaces) {
            final MethodNode resolved = resolveMethodNode(itf, name, desc, visited);
            if (resolved != null) {
                return resolved;
            }
        }
        return resolveMethodNode(classNode.superName, name, desc, visited);
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

    private String uniqueMethodName(final org.objectweb.asm.tree.ClassNode classNode,
                                    final String prefix, final String desc) {
        String name;
        do {
            name = prefix + UUID.randomUUID().toString().replace("-", "");
        } while (hasMethod(classNode, name, desc));
        return name;
    }

    private boolean hasMethod(final org.objectweb.asm.tree.ClassNode classNode,
                              final String name, final String desc) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private String dedupKey(final MethodInsnNode methodInsn) {
        return methodInsn.owner + '|' + methodInsn.name + '|' + methodInsn.desc
                + '|' + methodInsn.getOpcode() + '|' + methodInsn.itf;
    }

    /* ------------------------------------------------------------------ */
    /* Primitive pack / unpack (mirrors SignatureObfuscationTransformer)   */
    /* ------------------------------------------------------------------ */

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

    private int emitPrimitivePack(final InsnList insns, final int byteArrayLocal,
                                  final int offset, final Type type, final int local) {
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

    private int emitPrimitiveUnpack(final InsnList insns, final int byteArrayLocal,
                                    final int offset, final Type type) {
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

    private void emitByteStore(final InsnList insns, final int arrayLocal,
                               final int index, final Runnable valueEmitter) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        pushInt(insns, index);
        valueEmitter.run();
        insns.add(new InsnNode(Opcodes.BASTORE));
    }

    private void emitIntByteStores(final InsnList insns, final int arrayLocal,
                                   final int offset, final int local, final int width) {
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

    private void emitLongByteStores(final InsnList insns, final int arrayLocal,
                                    final int offset, final int local) {
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

    private void emitLoadMaskedByte(final InsnList insns, final int arrayLocal,
                                    final int index, final boolean mask) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.BALOAD));
        if (mask) {
            pushInt(insns, 0xFF);
            insns.add(new InsnNode(Opcodes.IAND));
        }
    }

    private void emitLoadInt(final InsnList insns, final int arrayLocal,
                             final int offset, final int width) {
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

    private void emitLoadLong(final InsnList insns, final int arrayLocal, final int offset) {
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

    private int slotCount(final Type[] types) {
        int slots = 0;
        for (Type type : types) {
            slots += type.getSize();
        }
        return slots;
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

    /* ------------------------------------------------------------------ */
    /* Data holders                                                        */
    /* ------------------------------------------------------------------ */

    private static final class Target {
        private final String owner;
        private final String name;
        private final String desc;
        private final int opcode;
        private final boolean itf;
        private final boolean isStatic;
        private final String castType;
        private int sigKey;
        private String dispatcherName;

        private Target(final String owner, final String name, final String desc,
                       final int opcode, final boolean itf, final boolean isStatic,
                       final String castType) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.opcode = opcode;
            this.itf = itf;
            this.isStatic = isStatic;
            this.castType = castType;
        }
    }

    private static final class Site {
        private final MethodNode method;
        private final MethodInsnNode insn;
        private final String dedupKey;

        private Site(final MethodNode method, final MethodInsnNode insn, final String dedupKey) {
            this.method = method;
            this.insn = insn;
            this.dedupKey = dedupKey;
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
    }
}
