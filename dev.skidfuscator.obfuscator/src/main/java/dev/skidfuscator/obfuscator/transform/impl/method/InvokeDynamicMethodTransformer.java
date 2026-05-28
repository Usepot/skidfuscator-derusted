package dev.skidfuscator.obfuscator.transform.impl.method;

import dev.skidfuscator.obfuscator.Skidfuscator;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class InvokeDynamicMethodTransformer extends AbstractTransformer {
    private static final String BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/Class;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;";
    private static final String DECRYPT_DESC = "(Ljava/lang/String;I)Ljava/lang/String;";
    private static final String CALL_PREFIX = "skid$";
    private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";
    private static final String METHOD_HANDLE = "java/lang/invoke/MethodHandle";
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
            final String bootstrapName = uniqueMethodName(classNode, "skid$bootstrap$", BOOTSTRAP_DESC);
            final String decryptName = uniqueMethodName(classNode, "skid$decrypt$", DECRYPT_DESC);
            final Integer[] keys = createKeys();
            final Handle bootstrapHandle = new Handle(
                    Opcodes.H_INVOKESTATIC,
                    classNode.name,
                    bootstrapName,
                    BOOTSTRAP_DESC,
                    false
            );

            boolean changed = false;
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

                    final int key = ThreadLocalRandom.current().nextInt();
                    final InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                            encryptName(methodInsn.name, key, keys),
                            buildCallSiteDesc(methodInsn),
                            bootstrapHandle,
                            methodInsn.getOpcode(),
                            ownerType(methodInsn.owner),
                            Type.getMethodType(methodInsn.desc),
                            key
                    );
                    method.instructions.set(methodInsn, indy);
                    changed = true;
                    success();
                    insn = next;
                }
            }

            if (changed) {
                if (classNode.version < Opcodes.V1_7) {
                    classNode.version = Opcodes.V1_7;
                }
                classNode.methods.add(createBootstrapMethod(classNode.name, bootstrapName, decryptName));
                classNode.methods.add(createDecryptMethod(decryptName, keys));
            }
        }
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

        final Type[] args = Type.getArgumentTypes(methodInsn.desc);
        final Type[] indyArgs = new Type[args.length + 1];
        indyArgs[0] = ownerType(methodInsn.owner);
        System.arraycopy(args, 0, indyArgs, 1, args.length);
        return Type.getMethodDescriptor(Type.getReturnType(methodInsn.desc), indyArgs);
    }

    private Type ownerType(final String owner) {
        return owner.startsWith("[") ? Type.getType(owner) : Type.getObjectType(owner);
    }

    private MethodNode createBootstrapMethod(final String owner, final String name, final String decryptName) {
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

        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, decryptName, DECRYPT_DESC, false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 7));

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
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 8));
        insns.add(new JumpInsnNode(Opcodes.GOTO, doneLabel));

        insns.add(specialLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass", "()Ljava/lang/Class;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "findSpecial", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 8));
        insns.add(new JumpInsnNode(Opcodes.GOTO, doneLabel));

        insns.add(virtualLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
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

        method.maxLocals = 9;
        method.maxStack = 6;
        return method;
    }

    private MethodNode createDecryptMethod(final String name, final Integer[] keys) {
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

        method.maxLocals = 7;
        method.maxStack = Math.max(6, keys.length == 0 ? 6 : 8);
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

    private String encryptName(final String name, final int key, final Integer[] keys) {
        final byte[] encrypted = name.getBytes(StandardCharsets.UTF_8);
        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
            encrypted[i] ^= keys[i % keys.length];
        }

        final StringBuilder builder = new StringBuilder(CALL_PREFIX);
        for (byte value : encrypted) {
            final String hex = Integer.toHexString(value & 0xFF);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
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
