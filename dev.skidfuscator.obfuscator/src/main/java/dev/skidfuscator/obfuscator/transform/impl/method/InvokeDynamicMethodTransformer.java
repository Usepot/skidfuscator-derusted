package dev.skidfuscator.obfuscator.transform.impl.method;

import dev.skidfuscator.obfuscator.Skidfuscator;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class InvokeDynamicMethodTransformer extends AbstractTransformer {
    private static final String BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    /*
     * Seed-bound bootstrap. Unlike BOOTSTRAP_DESC there is no trailing int key:
     * the threaded opaque-predicate seed is a live input to salted key derivation
     * at the call site (the last parameter of every threaded
     * method). A bootstrap cannot observe dynamic arguments, so this returns a
     * MutableCallSite whose initial target is the relink helper; the helper
     * reads the seed off the argument array on first invocation, decrypts, and
     * relinks to the direct handle.
     */
    private static final String BOOTSTRAP_SEED_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    private static final String RELINK_DESC = "(Ljava/lang/invoke/MutableCallSite;Ljava/lang/invoke/MethodHandles$Lookup;ILjava/lang/String;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String DECRYPT_DESC = "(Ljava/lang/String;I)Ljava/lang/String;";
    private static final String DERIVE_KEY_DESC = "(II)I";
    /*
     * seed.wide variants: the decryption key is the full 64-bit threaded seed,
     * so decrypt takes a long and the key derivation produces a long. Under
     * seed.wide the int variants above would throw away the high 32 bits of seed
     * entropy at the decrypt site, leaving the name decryptable from the low 32
     * bits alone — exactly the leak seed.wide exists to close.
     */
    private static final String DECRYPT_DESC_WIDE = "(Ljava/lang/String;J)Ljava/lang/String;";
    private static final String DERIVE_KEY_DESC_WIDE = "(JI)J";
    private static final String CALL_PREFIX = "skid$";
    private static final int SALT_HEX_LENGTH = 8;
    private static final int KEY_MIX_CONSTANT = 0x9E3779B9;
    // 64-bit mixing constants for the seed.wide key schedule. Both are odd, so
    // the multiply is invertible; the >>> 32 fold mixes high and low halves.
    private static final long KEY_MIX_CONSTANT_LONG = 0x9E3779B97F4A7C15L;
    private static final long KEY_MUL_CONSTANT_LONG = 0xFF51AFD7ED558CCDL;
    private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";
    private static final String METHOD_HANDLE = "java/lang/invoke/MethodHandle";
    private static final String METHOD_HANDLES = "java/lang/invoke/MethodHandles";
    private static final String METHOD_TYPE = "java/lang/invoke/MethodType";
    private static final String MUTABLE_CALL_SITE = "java/lang/invoke/MutableCallSite";
    private static final String OBJECT_ARRAY = "[Ljava/lang/Object;";
    private static final String STANDARD_CHARSETS = "java/nio/charset/StandardCharsets";

    public InvokeDynamicMethodTransformer(final Skidfuscator skidfuscator) {
        super(skidfuscator, "Method Call Obfuscation");
    }

    @Override
    public boolean isEnabled() {
        return getConfig().getBoolean("enabled", false);
    }

    public void apply() {
        final Map<String, org.objectweb.asm.tree.ClassNode> classes = loadApplicationClasses();
        final Map<String, SkidGroup> threadedGroups = buildThreadedGroupMap();

        for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
            final ClassNode wrapper = classData.getClassNode();
            if (wrapper == null || wrapper.node == null) {
                continue;
            }

            if (wrapper.isVirtual() || isNativeSensitiveClass(wrapper.node.name) || isClassExempt(wrapper)) {
                skip();
                continue;
            }

            final org.objectweb.asm.tree.ClassNode classNode = wrapper.node;
            final Integer[] keys = createKeys();
            final boolean wide = skidfuscator.getConfig().isSeedWide();
            final String decryptDesc = wide ? DECRYPT_DESC_WIDE : DECRYPT_DESC;
            final String deriveKeyDesc = wide ? DERIVE_KEY_DESC_WIDE : DERIVE_KEY_DESC;
            final String decryptName = uniqueMethodName(classNode, "skid$decrypt$", decryptDesc);
            final String deriveKeyName = uniqueMethodName(classNode, "skid$key$", deriveKeyDesc);

            final String bootstrapName = uniqueMethodName(classNode, "skid$bootstrap$", BOOTSTRAP_DESC);
            final Handle bootstrapHandle = new Handle(
                    Opcodes.H_INVOKESTATIC,
                    classNode.name,
                    bootstrapName,
                    BOOTSTRAP_DESC,
                    false
            );

            final String seedBootstrapName = uniqueMethodName(classNode, "skid$bootseed$", BOOTSTRAP_SEED_DESC);
            final String relinkName = uniqueMethodName(classNode, "skid$relink$", RELINK_DESC);
            final Handle seedBootstrapHandle = new Handle(
                    Opcodes.H_INVOKESTATIC,
                    classNode.name,
                    seedBootstrapName,
                    BOOTSTRAP_SEED_DESC,
                    false
            );

            boolean usedStatic = false;
            boolean usedSeed = false;
            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (isMethodExempt(wrapper, method) || method.instructions == null || method.instructions.size() == 0) {
                    skip();
                    continue;
                }

                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    skip();
                    continue;
                }

                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                    final AbstractInsnNode next = insn.getNext();

                    if (!(insn instanceof MethodInsnNode)) {
                        insn = next;
                        continue;
                    }

                    final MethodInsnNode methodInsn = (MethodInsnNode) insn;
                    if (!isEligible(methodInsn)) {
                        skip();
                        insn = next;
                        continue;
                    }

                    if (!resolvesMethod(classes, methodInsn.owner, methodInsn.name, methodInsn.desc)) {
                        skip();
                        insn = next;
                        continue;
                    }

                    /*
                     * Resolve the owner to its FINAL (post-rename) internal name
                     * before encrypting it. The write-time ClassRemapper would
                     * remap a Class constant for us, but it cannot touch an
                     * encrypted string, so we bake the renamed name here.
                     */
                    final String mappedOwner = skidfuscator.getClassRemapper().map(methodInsn.owner);
                    final String ownerBinaryName =
                            (mappedOwner != null ? mappedOwner : methodInsn.owner).replace('/', '.');

                    /*
                     * If this call targets a threaded method, the opaque-predicate
                     * seed is already pushed as the trailing argument (an int, or a
                     * long under seed.wide; see InterproceduralTransformer). Bind
                     * the name/owner decryption to a salted derivation of that live
                     * seed instead of embedding a raw decryption key. Under
                     * seed.wide we carry the full 64-bit getPublicLong() so the key
                     * depends on all 64 bits, not just the low 32. Any call we
                     * cannot prove is threaded falls back to a salted static scheme
                     * with no bootstrap key (key 0).
                     */
                    final SkidGroup threaded = threadedGroups.get(
                            methodInsn.owner + "." + methodInsn.name + methodInsn.desc
                    );
                    final InvokeDynamicInsnNode indy;
                    if (threaded != null && descEndsWithSeed(methodInsn.desc)) {
                        final long seed = wide
                                ? threaded.getPredicate().getPublicLong()
                                : threaded.getPredicate().getPublic();
                        indy = new InvokeDynamicInsnNode(
                                encryptName(methodInsn.name, seed, keys, wide),
                                buildCallSiteDesc(methodInsn),
                                seedBootstrapHandle,
                                methodInsn.getOpcode(),
                                encryptName(ownerBinaryName, seed, keys, wide),
                                Type.getMethodType(methodInsn.desc)
                        );
                        usedSeed = true;
                    } else {
                        indy = new InvokeDynamicInsnNode(
                                encryptName(methodInsn.name, 0L, keys, wide),
                                buildCallSiteDesc(methodInsn),
                                bootstrapHandle,
                                methodInsn.getOpcode(),
                                encryptName(ownerBinaryName, 0L, keys, wide),
                                Type.getMethodType(methodInsn.desc)
                        );
                        usedStatic = true;
                    }
                    method.instructions.set(methodInsn, indy);
                    success();
                    insn = next;
                }
            }

            if (usedStatic || usedSeed) {
                if (classNode.version < Opcodes.V1_7) {
                    classNode.version = Opcodes.V1_7;
                }
                classNode.methods.add(createDeriveKeyMethod(deriveKeyName, keys, wide));
                classNode.methods.add(createDecryptMethod(classNode.name, decryptName, deriveKeyName, keys, wide));
                if (usedStatic) {
                    classNode.methods.add(createBootstrapMethod(classNode.name, bootstrapName, decryptName, wide));
                }
                if (usedSeed) {
                    classNode.methods.add(createSeedBootstrapMethod(classNode.name, seedBootstrapName, relinkName));
                    classNode.methods.add(createRelinkMethod(classNode.name, relinkName, decryptName, wide));
                }
            }
        }
    }

    private Map<String, SkidGroup> buildThreadedGroupMap() {
        final Map<String, SkidGroup> map = new HashMap<>();
        for (SkidGroup group : skidfuscator.getHierarchy().getGroups()) {
            if (!group.isInjectedMethodPredicate() || group.getPredicate() == null) {
                continue;
            }

            /*
             * setName/setDesc propagate the post-injection name and descriptor
             * to every member MethodNode, so each member's owner+name+desc is
             * exactly what a preserved callsite still encodes at this late
             * stage. Key on the member owners (a group spans every declaring
             * class in the hierarchy chain) so inherited calls resolve too.
             */
            for (org.mapleir.asm.MethodNode methodNode : group.getMethodNodeList()) {
                map.put(
                        methodNode.owner.getName() + "." + methodNode.getName() + methodNode.getDesc(),
                        group
                );
            }
        }
        return map;
    }

    /*
     * The threaded opaque-predicate seed is always the trailing parameter of a
     * threaded callee (InterproceduralTransformer inserts it at args.size()):
     * an int normally, or a long under seed.wide. Accept either sort, otherwise
     * the entire live-seed path is silently disabled under seed.wide and every
     * call collapses to the static key=0 bootstrap. The relink helper reads the
     * boxed seed as getPublic() (narrow, via intValue) or the full 64-bit
     * getPublicLong() (wide, via longValue) to match the build-time key.
     */
    private boolean descEndsWithSeed(final String desc) {
        final Type[] args = Type.getArgumentTypes(desc);
        if (args.length == 0) {
            return false;
        }
        final int sort = args[args.length - 1].getSort();
        return sort == Type.INT || sort == Type.LONG;
    }

    private Map<String, org.objectweb.asm.tree.ClassNode> loadApplicationClasses() {
        final Map<String, org.objectweb.asm.tree.ClassNode> classes = new LinkedHashMap<>();
        for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
            final ClassNode classNode = classData.getClassNode();
            if (classNode != null && classNode.node != null && !isNativeSensitiveClass(classNode.node.name)) {
                classes.put(classNode.node.name, classNode.node);
            }
        }
        return classes;
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

    private boolean isEligible(final MethodInsnNode methodInsn) {
        switch (methodInsn.getOpcode()) {
            case Opcodes.INVOKESTATIC:
            case Opcodes.INVOKEVIRTUAL:
            case Opcodes.INVOKEINTERFACE:
                return true;
            case Opcodes.INVOKESPECIAL:
                return !"<init>".equals(methodInsn.name);
            default:
                return false;
        }
    }

    private boolean resolvesMethod(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                   final String owner,
                                   final String name,
                                   final String desc) {
        if (owner == null || owner.startsWith("[")) {
            return true;
        }

        return resolvesMethod(classes, owner, name, desc, new HashSet<>());
    }

    private boolean resolvesMethod(final Map<String, org.objectweb.asm.tree.ClassNode> classes,
                                   final String owner,
                                   final String name,
                                   final String desc,
                                   final Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return false;
        }

        org.objectweb.asm.tree.ClassNode classNode = classes.get(owner);
        if (classNode == null) {
            final ClassNode libraryNode = skidfuscator.getClassSource().findClassNode(owner);
            if (libraryNode != null) {
                classNode = libraryNode.node;
            }
        }

        if (classNode == null) {
            return false;
        }

        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }

        for (String itf : classNode.interfaces) {
            if (resolvesMethod(classes, itf, name, desc, visited)) {
                return true;
            }
        }

        return resolvesMethod(classes, classNode.superName, name, desc, visited);
    }

    private String buildCallSiteDesc(final MethodInsnNode methodInsn) {
        if (methodInsn.getOpcode() == Opcodes.INVOKESTATIC) {
            return methodInsn.desc;
        }

        /*
         * The receiver becomes the first call-site argument. We declare it as
         * java/lang/Object instead of the real owner so the owner type never
         * leaks into the indy descriptor; the bootstrap's MethodHandle.asType
         * inserts the cast back to the resolved owner at link time.
         */
        final Type[] args = Type.getArgumentTypes(methodInsn.desc);
        final Type[] indyArgs = new Type[args.length + 1];
        indyArgs[0] = Type.getObjectType("java/lang/Object");
        System.arraycopy(args, 0, indyArgs, 1, args.length);
        return Type.getMethodDescriptor(Type.getReturnType(methodInsn.desc), indyArgs);
    }

    private MethodNode createBootstrapMethod(final String owner, final String name, final String decryptName, final boolean wide) {
        final MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                BOOTSTRAP_DESC,
                null,
                new String[] {"java/lang/Throwable"}
        );

        final LabelNode staticLabel = new LabelNode();
        final LabelNode specialLabel = new LabelNode();
        final LabelNode virtualLabel = new LabelNode();
        final LabelNode defaultLabel = new LabelNode();
        final LabelNode doneLabel = new LabelNode();
        final InsnList insns = method.instructions;

        // Static fallback has no live seed: the decryption key is the constant 0,
        // pushed as a long under seed.wide so it matches the wide decrypt(String,J).
        final String decryptDesc = wide ? DECRYPT_DESC_WIDE : DECRYPT_DESC;

        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(wide ? Opcodes.LCONST_0 : Opcodes.ICONST_0));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, decryptName, decryptDesc, false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 7));

        // Decrypt the owner's binary name and resolve it through the caller's
        // class loader, so the target class never appears as a Class constant.
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new InsnNode(wide ? Opcodes.LCONST_0 : Opcodes.ICONST_0));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, decryptName, decryptDesc, false));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass", "()Ljava/lang/Class;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 9));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pushInt(insns, Opcodes.INVOKESTATIC);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, staticLabel));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pushInt(insns, Opcodes.INVOKESPECIAL);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, specialLabel));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pushInt(insns, Opcodes.INVOKEVIRTUAL);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, virtualLabel));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pushInt(insns, Opcodes.INVOKEINTERFACE);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, virtualLabel));
        insns.add(new JumpInsnNode(Opcodes.GOTO, defaultLabel));

        insns.add(staticLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 8));
        insns.add(new JumpInsnNode(Opcodes.GOTO, doneLabel));

        insns.add(specialLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass", "()Ljava/lang/Class;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "findSpecial", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 8));
        insns.add(new JumpInsnNode(Opcodes.GOTO, doneLabel));

        insns.add(virtualLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 8));
        insns.add(new JumpInsnNode(Opcodes.GOTO, doneLabel));

        insns.add(defaultLabel);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/BootstrapMethodError"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode("Unsupported invocation opcode"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/BootstrapMethodError", "<init>", "(Ljava/lang/String;)V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));

        insns.add(doneLabel);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/invoke/ConstantCallSite"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 8));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        method.maxLocals = 10;
        method.maxStack = 6;
        return method;
    }

    /*
     * Seed-bound bootstrap. The threaded seed is a dynamic argument, invisible
     * to a bootstrap, so we install a MutableCallSite whose initial target is
     * the relink helper. asCollector funnels every call argument (including the
     * trailing seed) into an Object[] the helper can inspect on first call.
     */
    private MethodNode createSeedBootstrapMethod(final String owner, final String name, final String relinkName) {
        final MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                BOOTSTRAP_SEED_DESC,
                null,
                new String[] {"java/lang/Throwable"}
        );

        final InsnList insns = method.instructions;

        // MutableCallSite site = new MutableCallSite(type);
        insns.add(new TypeInsnNode(Opcodes.NEW, MUTABLE_CALL_SITE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MUTABLE_CALL_SITE, "<init>", "(Ljava/lang/invoke/MethodType;)V", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 6));

        // MethodHandle relink = caller.findStatic(caller.lookupClass(), relinkName, <relink type>);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass", "()Ljava/lang/Class;", false));
        insns.add(new LdcInsnNode(relinkName));
        insns.add(new LdcInsnNode(Type.getMethodType(RELINK_DESC)));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));

        // relink = MethodHandles.insertArguments(relink, 0, site, caller, opcode, encOwner, encName, realType);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        pushInt(insns, 6);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_5));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "insertArguments", "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false));

        // relink = relink.asCollector(Object[].class, type.parameterCount());
        insns.add(new LdcInsnNode(Type.getType(OBJECT_ARRAY)));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_TYPE, "parameterCount", "()I", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "asCollector", "(Ljava/lang/Class;I)Ljava/lang/invoke/MethodHandle;", false));

        // relink = relink.asType(type);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 7));

        // site.setTarget(relink);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MUTABLE_CALL_SITE, "setTarget", "(Ljava/lang/invoke/MethodHandle;)V", false));

        // return site;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new InsnNode(Opcodes.ARETURN));

        method.maxLocals = 8;
        method.maxStack = 7;
        return method;
    }

    /*
     * First-call relink helper. Reads the threaded seed off the tail of the
     * argument array, decrypts the target name + owner with it, resolves the
     * direct handle, points the call site at it (so later calls skip decryption)
     * and forwards this first invocation. A tampered seed yields a garbage name
     * and resolution throws.
     */
    private MethodNode createRelinkMethod(final String owner, final String name, final String decryptName, final boolean wide) {
        final MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                RELINK_DESC,
                null,
                new String[] {"java/lang/Throwable"}
        );

        final LabelNode staticLabel = new LabelNode();
        final LabelNode specialLabel = new LabelNode();
        final LabelNode virtualLabel = new LabelNode();
        final LabelNode defaultLabel = new LabelNode();
        final LabelNode doneLabel = new LabelNode();
        final InsnList insns = method.instructions;

        // seed = ((Number) args[args.length - 1]).<int|long>Value();
        // Number, not Integer: the boxed trailing arg is an Integer (narrow) or a
        // Long (wide). Narrow takes intValue() == getPublic(); wide takes the full
        // 64-bit longValue() == getPublicLong(), so the key uses all 64 bits. The
        // wide long seed is parked in slots 12/13 to avoid renumbering 8-11.
        final String decryptDesc = wide ? DECRYPT_DESC_WIDE : DECRYPT_DESC;
        final int seedSlot = wide ? 12 : 7;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
        if (wide) {
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false));
            insns.add(new VarInsnNode(Opcodes.LSTORE, seedSlot));
        } else {
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
            insns.add(new VarInsnNode(Opcodes.ISTORE, seedSlot));
        }

        // String decName = decrypt(encName, seed);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new VarInsnNode(wide ? Opcodes.LLOAD : Opcodes.ILOAD, seedSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, decryptName, decryptDesc, false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 8));

        // String decOwner = decrypt(encOwner, seed);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new VarInsnNode(wide ? Opcodes.LLOAD : Opcodes.ILOAD, seedSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, decryptName, decryptDesc, false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 9));

        // Class<?> target = Class.forName(decOwner, false, caller.lookupClass().getClassLoader());
        insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass", "()Ljava/lang/Class;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 10));

        // switch (opcode)
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pushInt(insns, Opcodes.INVOKESTATIC);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, staticLabel));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pushInt(insns, Opcodes.INVOKESPECIAL);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, specialLabel));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pushInt(insns, Opcodes.INVOKEVIRTUAL);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, virtualLabel));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pushInt(insns, Opcodes.INVOKEINTERFACE);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, virtualLabel));
        insns.add(new JumpInsnNode(Opcodes.GOTO, defaultLabel));

        insns.add(staticLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 10));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 8));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 11));
        insns.add(new JumpInsnNode(Opcodes.GOTO, doneLabel));

        insns.add(specialLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 10));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 8));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass", "()Ljava/lang/Class;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "findSpecial", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 11));
        insns.add(new JumpInsnNode(Opcodes.GOTO, doneLabel));

        insns.add(virtualLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 10));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 8));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 11));
        insns.add(new JumpInsnNode(Opcodes.GOTO, doneLabel));

        insns.add(defaultLabel);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/BootstrapMethodError"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode("Unsupported invocation opcode"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/BootstrapMethodError", "<init>", "(Ljava/lang/String;)V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));

        insns.add(doneLabel);
        // site.setTarget(handle.asType(site.type()));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 11));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MUTABLE_CALL_SITE, "type", "()Ljava/lang/invoke/MethodType;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MUTABLE_CALL_SITE, "setTarget", "(Ljava/lang/invoke/MethodHandle;)V", false));

        // return handle.invokeWithArguments(args);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 11));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "invokeWithArguments", "([Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        // Wide parks the long seed in slots 12/13 (see seedSlot above).
        method.maxLocals = wide ? 14 : 12;
        method.maxStack = 6;
        return method;
    }

    private MethodNode createDeriveKeyMethod(final String name, final Integer[] keys, final boolean wide) {
        if (wide) {
            return createDeriveKeyMethodWide(name, keys);
        }
        final MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                DERIVE_KEY_DESC,
                null,
                null
        );

        final LabelNode loop = new LabelNode();
        final LabelNode end = new LabelNode();
        final InsnList insns = method.instructions;

        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.IXOR));
        pushInt(insns, KEY_MIX_CONSTANT);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));

        pushInt(insns, keys.length);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        for (int i = 0; i < keys.length; i++) {
            insns.add(new InsnNode(Opcodes.DUP));
            pushInt(insns, i);
            pushInt(insns, keys[i]);
            insns.add(new InsnNode(Opcodes.BASTORE));
        }
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));

        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 4));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new InsnNode(Opcodes.BALOAD));
        pushInt(insns, 0xFF);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pushInt(insns, 0x45D9F3B);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insns.add(new IincInsnNode(4, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));

        insns.add(end);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.IRETURN));

        method.maxLocals = 5;
        method.maxStack = Math.max(5, keys.length == 0 ? 5 : 8);
        return method;
    }

    /*
     * seed.wide key schedule, desc (JI)J. Mirrors deriveNameKeyLong exactly:
     *   long mixed = key ^ (salt & 0xFFFFFFFFL) ^ KEY_MIX_CONSTANT_LONG;
     *   for (b : keys) { mixed ^= b & 0xFF; mixed *= MUL; mixed ^= mixed >>> 32; }
     * Locals: key J@0, salt I@2, mixed J@3, keys[] @5, i @6.
     */
    private MethodNode createDeriveKeyMethodWide(final String name, final Integer[] keys) {
        final MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                DERIVE_KEY_DESC_WIDE,
                null,
                null
        );

        final LabelNode loop = new LabelNode();
        final LabelNode end = new LabelNode();
        final InsnList insns = method.instructions;

        // long mixed = key ^ ((long) salt & 0xFFFFFFFFL) ^ KEY_MIX_CONSTANT_LONG;
        insns.add(new VarInsnNode(Opcodes.LLOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new LdcInsnNode(0xFFFFFFFFL));
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new LdcInsnNode(KEY_MIX_CONSTANT_LONG));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, 3));

        pushInt(insns, keys.length);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        for (int i = 0; i < keys.length; i++) {
            insns.add(new InsnNode(Opcodes.DUP));
            pushInt(insns, i);
            pushInt(insns, keys[i]);
            insns.add(new InsnNode(Opcodes.BASTORE));
        }
        insns.add(new VarInsnNode(Opcodes.ASTORE, 5));

        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 6));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));

        // mixed ^= keys[i] & 0xFF;
        insns.add(new VarInsnNode(Opcodes.LLOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new InsnNode(Opcodes.BALOAD));
        pushInt(insns, 0xFF);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, 3));

        // mixed *= KEY_MUL_CONSTANT_LONG;
        insns.add(new VarInsnNode(Opcodes.LLOAD, 3));
        insns.add(new LdcInsnNode(KEY_MUL_CONSTANT_LONG));
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new VarInsnNode(Opcodes.LSTORE, 3));

        // mixed ^= mixed >>> 32;
        insns.add(new VarInsnNode(Opcodes.LLOAD, 3));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 3));
        pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, 3));

        insns.add(new IincInsnNode(6, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));

        insns.add(end);
        insns.add(new VarInsnNode(Opcodes.LLOAD, 3));
        insns.add(new InsnNode(Opcodes.LRETURN));

        method.maxLocals = 7;
        method.maxStack = 6;
        return method;
    }

    private MethodNode createDecryptMethod(final String owner,
                                           final String name,
                                           final String deriveKeyName,
                                           final Integer[] keys,
                                           final boolean wide) {
        if (wide) {
            return createDecryptMethodWide(owner, name, deriveKeyName, keys);
        }
        final MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                DECRYPT_DESC,
                null,
                null
        );

        final LabelNode decodeLoop = new LabelNode();
        final LabelNode decodeEnd = new LabelNode();
        final LabelNode xorLoop = new LabelNode();
        final LabelNode xorEnd = new LabelNode();
        final InsnList insns = method.instructions;

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pushInt(insns, CALL_PREFIX.length());
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 0));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        pushInt(insns, SALT_HEX_LENGTH);
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false));
        pushInt(insns, 16);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "parseLong", "(Ljava/lang/String;I)J", false));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 7));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pushInt(insns, SALT_HEX_LENGTH);
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 0));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IDIV));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));

        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 3));
        insns.add(decodeLoop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, decodeEnd));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IMUL));
        pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false));
        pushInt(insns, 16);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;I)I", false));
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new IincInsnNode(3, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, decodeLoop));

        insns.add(decodeEnd);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 7));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, deriveKeyName, DERIVE_KEY_DESC, false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, STANDARD_CHARSETS, "UTF_8", "Ljava/nio/charset/Charset;"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 4));

        pushInt(insns, keys.length);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        for (int i = 0; i < keys.length; i++) {
            insns.add(new InsnNode(Opcodes.DUP));
            pushInt(insns, i);
            pushInt(insns, keys[i]);
            insns.add(new InsnNode(Opcodes.BASTORE));
        }
        insns.add(new VarInsnNode(Opcodes.ASTORE, 5));

        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 6));
        insns.add(xorLoop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, xorEnd));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.BALOAD));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new InsnNode(Opcodes.IREM));
        insns.add(new InsnNode(Opcodes.BALOAD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2B));
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.BALOAD));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new InsnNode(Opcodes.IREM));
        insns.add(new InsnNode(Opcodes.BALOAD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2B));
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new IincInsnNode(6, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, xorLoop));

        insns.add(xorEnd);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, STANDARD_CHARSETS, "UTF_8", "Ljava/nio/charset/Charset;"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        method.maxLocals = 8;
        method.maxStack = Math.max(8, keys.length == 0 ? 8 : 10);
        return method;
    }

    /*
     * seed.wide decrypt, desc (Ljava/lang/String;J)Ljava/lang/String;. Identical
     * to createDecryptMethod except the key is a long: it occupies slots 1/2, so
     * every body local shifts up by one (data 3, decodeIdx 4, keyBytes 5, keys 6,
     * xorIdx 7, salt 8), the key is loaded with LLOAD and the derived key is
     * stringified with Long.toString so all 64 bits feed the XOR keystream.
     */
    private MethodNode createDecryptMethodWide(final String owner,
                                               final String name,
                                               final String deriveKeyName,
                                               final Integer[] keys) {
        final MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                DECRYPT_DESC_WIDE,
                null,
                null
        );

        final LabelNode decodeLoop = new LabelNode();
        final LabelNode decodeEnd = new LabelNode();
        final LabelNode xorLoop = new LabelNode();
        final LabelNode xorEnd = new LabelNode();
        final InsnList insns = method.instructions;

        // enc = enc.substring(CALL_PREFIX.length());
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pushInt(insns, CALL_PREFIX.length());
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 0));

        // int salt = (int) Long.parseLong(enc.substring(0, SALT_HEX_LENGTH), 16); -> slot 8
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        pushInt(insns, SALT_HEX_LENGTH);
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false));
        pushInt(insns, 16);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "parseLong", "(Ljava/lang/String;I)J", false));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 8));

        // enc = enc.substring(SALT_HEX_LENGTH);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pushInt(insns, SALT_HEX_LENGTH);
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 0));

        // byte[] data = new byte[enc.length() / 2]; -> slot 3
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IDIV));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));

        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 4));
        insns.add(decodeLoop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, decodeEnd));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IMUL));
        pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false));
        pushInt(insns, 16);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;I)I", false));
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new IincInsnNode(4, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, decodeLoop));

        insns.add(decodeEnd);
        // byte[] keyBytes = Long.toString(deriveKey(key, salt)).getBytes(UTF_8); -> slot 5
        insns.add(new VarInsnNode(Opcodes.LLOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, deriveKeyName, DERIVE_KEY_DESC_WIDE, false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "toString", "(J)Ljava/lang/String;", false));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, STANDARD_CHARSETS, "UTF_8", "Ljava/nio/charset/Charset;"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 5));

        pushInt(insns, keys.length);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        for (int i = 0; i < keys.length; i++) {
            insns.add(new InsnNode(Opcodes.DUP));
            pushInt(insns, i);
            pushInt(insns, keys[i]);
            insns.add(new InsnNode(Opcodes.BASTORE));
        }
        insns.add(new VarInsnNode(Opcodes.ASTORE, 6));

        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 7));
        insns.add(xorLoop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 7));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, xorEnd));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 7));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.BALOAD));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 7));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new InsnNode(Opcodes.IREM));
        insns.add(new InsnNode(Opcodes.BALOAD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2B));
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 7));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.BALOAD));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 7));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new InsnNode(Opcodes.IREM));
        insns.add(new InsnNode(Opcodes.BALOAD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2B));
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new IincInsnNode(7, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, xorLoop));

        insns.add(xorEnd);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, STANDARD_CHARSETS, "UTF_8", "Ljava/nio/charset/Charset;"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        method.maxLocals = 9;
        method.maxStack = Math.max(8, keys.length == 0 ? 8 : 10);
        return method;
    }

    private String uniqueMethodName(final org.objectweb.asm.tree.ClassNode classNode, final String prefix, final String desc) {
        String name;
        do {
            name = randomName(prefix);
        } while (hasMethod(classNode, name, desc));
        return name;
    }

    private boolean hasMethod(final org.objectweb.asm.tree.ClassNode classNode, final String name, final String desc) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private Integer[] createKeys() {
        final int size = ThreadLocalRandom.current().nextInt(1, 128);
        final Integer[] keys = new Integer[size];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = ThreadLocalRandom.current().nextInt(1, 128);
        }
        return keys;
    }

    private int deriveNameKey(final int key, final int salt, final Integer[] keys) {
        int mixed = key ^ salt ^ KEY_MIX_CONSTANT;
        for (int value : keys) {
            mixed ^= value & 0xFF;
            mixed *= 0x45D9F3B;
            mixed ^= mixed >>> 16;
        }
        return mixed;
    }

    /*
     * Build-time twin of createDeriveKeyMethodWide (skid$key, desc (JI)J). MUST
     * stay bit-identical to that bytecode or the runtime decrypt produces a
     * different keystream and resolution fails.
     */
    private long deriveNameKeyLong(final long key, final int salt, final Integer[] keys) {
        long mixed = key ^ (salt & 0xFFFFFFFFL) ^ KEY_MIX_CONSTANT_LONG;
        for (int value : keys) {
            mixed ^= value & 0xFF;
            mixed *= KEY_MUL_CONSTANT_LONG;
            mixed ^= mixed >>> 32;
        }
        return mixed;
    }

    /*
     * Narrow (wide=false): the int key path, byte-for-byte the original scheme
     * (key is the int seed / 0). Wide: the full 64-bit key path, so an attacker
     * must recover all 64 bits of the threaded seed, not just the low 32. The
     * salt stays 32-bit on the wire (it is embedded plaintext and not secret).
     */
    private String encryptName(final String name, final long key, final Integer[] keys, final boolean wide) {
        final int salt = ThreadLocalRandom.current().nextInt();
        final byte[] keyBytes = (wide
                ? Long.toString(deriveNameKeyLong(key, salt, keys))
                : Integer.toString(deriveNameKey((int) key, salt, keys)))
                .getBytes(StandardCharsets.UTF_8);
        final byte[] encrypted = name.getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
            encrypted[i] ^= keys[i % keys.length];
        }

        final StringBuilder builder = new StringBuilder(CALL_PREFIX);
        appendFixedHex(builder, salt);
        for (byte value : encrypted) {
            appendByteHex(builder, value);
        }
        return builder.toString();
    }

    private void appendFixedHex(final StringBuilder builder, final int value) {
        final String hex = Integer.toHexString(value);
        for (int i = hex.length(); i < SALT_HEX_LENGTH; i++) {
            builder.append('0');
        }
        builder.append(hex);
    }

    private void appendByteHex(final StringBuilder builder, final byte value) {
        final String hex = Integer.toHexString(value & 0xFF);
        if (hex.length() == 1) {
            builder.append('0');
        }
        builder.append(hex);
    }

    private String randomName(final String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
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
}
