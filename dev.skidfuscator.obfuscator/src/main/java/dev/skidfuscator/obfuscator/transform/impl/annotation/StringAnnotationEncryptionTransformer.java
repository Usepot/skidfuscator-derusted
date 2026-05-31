package dev.skidfuscator.obfuscator.transform.impl.annotation;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.FieldNode;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeAnnotationNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encrypts string constants stored in annotation attributes and restores them at
 * direct annotation accessor call-sites compiled into transformed application
 * classes.
 *
 * JVM annotations can only store constants; they cannot execute decryptor calls
 * inside the annotation attribute itself. Because of that, unsupported runtime
 * reflection cases deliberately remain encrypted: reflection performed outside
 * the transformed application classes, reflective Method.invoke calls against
 * annotation element methods, and Annotation#toString/equals/hashCode on the JVM
 * proxy. Direct calls such as annotation.value() and annotation.values() inside
 * transformed methods are guarded and preserve plaintext/non-transformed values.
 */
public class StringAnnotationEncryptionTransformer extends AbstractTransformer {
    private static final String MAGIC = "\u0000SKID:SAN:";
    private static final int MULTIPLIER = 1103515245;
    private static final int ADDEND = 12345;

    private final Set<String> encryptedAnnotationOwners = new HashSet<>();
    private final Map<String, String> stringHelperNames = new HashMap<>();
    private final Map<String, String> stringArrayHelperNames = new HashMap<>();

    public StringAnnotationEncryptionTransformer(Skidfuscator skidfuscator) {
        super(skidfuscator, "String Annotation Encryption");
    }

    public void apply() {
        for (ClassNode classNode : skidfuscator.getClassSource().iterate()) {
            if (shouldSkipClass(classNode)) {
                skip();
                continue;
            }

            transformClassAnnotations(classNode);
            rewriteAnnotationAccessors(classNode);
        }
    }

    private boolean shouldSkipClass(final ClassNode classNode) {
        return classNode == null
                || classNode.isAnnoyingVersion()
                || skidfuscator.getExemptAnalysis().isExempt(classNode)
                || skidfuscator.getExemptAnalysis().isExempt(this.getClass(), classNode);
    }

    private boolean shouldSkipMethod(final org.mapleir.asm.MethodNode methodNode) {
        return methodNode == null
                || methodNode.isAbstract()
                || methodNode.isNative()
                || shouldSkipMethodMetadata(methodNode);
    }

    private boolean shouldSkipMethodMetadata(final org.mapleir.asm.MethodNode methodNode) {
        return methodNode == null
                || skidfuscator.getExemptAnalysis().isExempt(methodNode)
                || skidfuscator.getExemptAnalysis().isExempt(this.getClass(), methodNode);
    }

    private void transformClassAnnotations(final ClassNode classNode) {
        transformAnnotations(classNode.node.visibleAnnotations);
        transformAnnotations(classNode.node.invisibleAnnotations);
        transformTypeAnnotations(classNode.node.visibleTypeAnnotations);
        transformTypeAnnotations(classNode.node.invisibleTypeAnnotations);

        for (FieldNode field : classNode.getFields()) {
            transformAnnotations(field.node.visibleAnnotations);
            transformAnnotations(field.node.invisibleAnnotations);
            transformTypeAnnotations(field.node.visibleTypeAnnotations);
            transformTypeAnnotations(field.node.invisibleTypeAnnotations);
        }

        for (org.mapleir.asm.MethodNode method : classNode.getMethods()) {
            if (shouldSkipMethodMetadata(method)) {
                continue;
            }

            if (method.node.annotationDefault != null) {
                method.node.annotationDefault = transformValue(method.node.annotationDefault, null);
            }


            if (shouldSkipMethod(method)) {
                continue;
            }

            transformAnnotations(method.node.visibleAnnotations);
            transformAnnotations(method.node.invisibleAnnotations);
            transformTypeAnnotations(method.node.visibleTypeAnnotations);
            transformTypeAnnotations(method.node.invisibleTypeAnnotations);
            transformParameterAnnotations(method.node.visibleParameterAnnotations);
            transformParameterAnnotations(method.node.invisibleParameterAnnotations);
            transformLocalVariableAnnotations(method.node.visibleLocalVariableAnnotations);
            transformLocalVariableAnnotations(method.node.invisibleLocalVariableAnnotations);
        }
    }

    private void transformAnnotations(final List<AnnotationNode> annotations) {
        if (annotations == null) {
            return;
        }

        for (AnnotationNode annotation : annotations) {
            transformAnnotation(annotation);
        }
    }

    private void transformTypeAnnotations(final List<TypeAnnotationNode> annotations) {
        if (annotations == null) {
            return;
        }

        for (TypeAnnotationNode annotation : annotations) {
            transformAnnotation(annotation);
        }
    }

    private void transformLocalVariableAnnotations(final List<LocalVariableAnnotationNode> annotations) {
        if (annotations == null) {
            return;
        }

        for (LocalVariableAnnotationNode annotation : annotations) {
            transformAnnotation(annotation);
        }
    }

    private void transformParameterAnnotations(final List<AnnotationNode>[] annotations) {
        if (annotations == null) {
            return;
        }

        for (List<AnnotationNode> annotationList : annotations) {
            transformAnnotations(annotationList);
        }
    }

    private void transformAnnotation(final AnnotationNode annotation) {
        if (annotation == null || annotation.values == null) {
            return;
        }

        boolean changed = false;
        for (int i = 1; i < annotation.values.size(); i += 2) {
            final Object original = annotation.values.get(i);
            final Object transformed = transformValue(original, annotation.desc);

            if (transformed != original) {
                annotation.values.set(i, transformed);
                changed = true;
            }
        }

        if (changed) {
            encryptedAnnotationOwners.add(ownerFromDescriptor(annotation.desc));
        }
    }

    private Object transformValue(final Object value, final String annotationDescriptor) {
        if (value instanceof String) {
            final String string = (String) value;
            if (string.isEmpty() || string.startsWith(MAGIC)) {
                return value;
            }

            if (annotationDescriptor != null) {
                encryptedAnnotationOwners.add(ownerFromDescriptor(annotationDescriptor));
            }
            success();
            return encrypt(string);
        }

        if (value instanceof AnnotationNode) {
            transformAnnotation((AnnotationNode) value);
            return value;
        }

        if (value instanceof List) {
            boolean changed = false;
            final List values = (List) value;

            for (int i = 0; i < values.size(); i++) {
                final Object original = values.get(i);
                final Object transformed = transformValue(original, annotationDescriptor);

                if (transformed != original) {
                    values.set(i, transformed);
                    changed = true;
                }
            }

            if (changed && annotationDescriptor != null) {
                encryptedAnnotationOwners.add(ownerFromDescriptor(annotationDescriptor));
            }
            return value;
        }

        return value;
    }

    private String encrypt(final String value) {
        int key = RandomUtil.nextInt(Integer.MAX_VALUE - 1) + 1;
        int state = key;
        final byte[] data = value.getBytes(StandardCharsets.UTF_8);
        final byte[] encrypted = new byte[data.length];

        for (int i = 0; i < data.length; i++) {
            state = state * MULTIPLIER + ADDEND;
            encrypted[i] = (byte) (data[i] ^ state);
        }

        return MAGIC + Integer.toHexString(key) + ":" + Base64.getEncoder().encodeToString(encrypted);
    }

    private void rewriteAnnotationAccessors(final ClassNode classNode) {
        for (org.mapleir.asm.MethodNode method : classNode.getMethods()) {
            if (shouldSkipMethod(method)) {
                continue;
            }

            final InsnList instructions = method.node.instructions;
            if (instructions == null || instructions.size() == 0) {
                continue;
            }

            for (AbstractInsnNode insn = instructions.getFirst(); insn != null; ) {
                final AbstractInsnNode next = insn.getNext();

                if (insn instanceof MethodInsnNode) {
                    final MethodInsnNode call = (MethodInsnNode) insn;
                    if (isAnnotationAccessor(call)) {
                        if ("()Ljava/lang/String;".equals(call.desc)) {
                            instructions.insert(call, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    classNode.getName(),
                                    ensureStringHelper(classNode),
                                    "(Ljava/lang/String;)Ljava/lang/String;",
                                    false
                            ));
                            success();
                        } else if ("()[Ljava/lang/String;".equals(call.desc)) {
                            instructions.insert(call, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    classNode.getName(),
                                    ensureStringArrayHelper(classNode),
                                    "([Ljava/lang/String;)[Ljava/lang/String;",
                                    false
                            ));
                            success();
                        }
                    }
                }

                insn = next;
            }
        }
    }

    private boolean isAnnotationAccessor(final MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKEINTERFACE && call.getOpcode() != Opcodes.INVOKEVIRTUAL) {
            return false;
        }

        if (!"()Ljava/lang/String;".equals(call.desc) && !"()[Ljava/lang/String;".equals(call.desc)) {
            return false;
        }

        if (encryptedAnnotationOwners.contains(call.owner)) {
            return true;
        }

        final ClassNode owner = skidfuscator.getClassSource().findClassNode(call.owner);
        return owner != null && owner.isAnnotation();
    }

    private String ensureStringHelper(final ClassNode classNode) {
        return stringHelperNames.computeIfAbsent(classNode.getName(), ignored -> {
            final String name = nextHelperName(classNode, "skid$annotationString$");
            classNode.node.methods.add(createStringDecryptMethod(classNode, name));
            return name;
        });
    }

    private String ensureStringArrayHelper(final ClassNode classNode) {
        return stringArrayHelperNames.computeIfAbsent(classNode.getName(), ignored -> {
            final String stringHelper = ensureStringHelper(classNode);
            final String name = nextHelperName(classNode, "skid$annotationStringArray$");
            classNode.node.methods.add(createStringArrayDecryptMethod(classNode, name, stringHelper));
            return name;
        });
    }

    private String nextHelperName(final ClassNode classNode, final String prefix) {
        String name;
        do {
            name = prefix + RandomUtil.randomAlphabeticalString(10);
        } while (hasMethod(classNode, name));
        return name;
    }

    private boolean hasMethod(final ClassNode classNode, final String name) {
        for (org.objectweb.asm.tree.MethodNode method : classNode.node.methods) {
            if (method.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private org.objectweb.asm.tree.MethodNode createStringDecryptMethod(final ClassNode classNode, final String name) {
        final int access = helperAccess(classNode);
        final org.objectweb.asm.tree.MethodNode method = new org.objectweb.asm.tree.MethodNode(
                access,
                name,
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null
        );

        final Label start = new Label();
        final Label end = new Label();
        final Label handler = new Label();
        final Label nonNull = new Label();
        final Label hasMagic = new Label();
        final Label hasSeparator = new Label();
        final Label loopCheck = new Label();
        final Label loopBody = new Label();

        method.visitCode();
        method.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        method.visitLabel(start);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);

        method.visitLabel(nonNull);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn(MAGIC);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false);
        method.visitJumpInsn(Opcodes.IFNE, hasMagic);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);

        method.visitLabel(hasMagic);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitIntInsn(Opcodes.BIPUSH, ':');
        method.visitLdcInsn(MAGIC.length());
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
        method.visitVarInsn(Opcodes.ISTORE, 1);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitInsn(Opcodes.ICONST_M1);
        method.visitJumpInsn(Opcodes.IF_ICMPNE, hasSeparator);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);

        method.visitLabel(hasSeparator);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn(MAGIC.length());
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        method.visitIntInsn(Opcodes.BIPUSH, 16);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "parseLong", "(Ljava/lang/String;I)J", false);
        method.visitInsn(Opcodes.L2I);
        method.visitVarInsn(Opcodes.ISTORE, 2);

        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false);
        method.visitVarInsn(Opcodes.ASTORE, 3);

        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitVarInsn(Opcodes.ISTORE, 4);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ISTORE, 5);
        method.visitJumpInsn(Opcodes.GOTO, loopCheck);

        method.visitLabel(loopBody);
        method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitLdcInsn(MULTIPLIER);
        method.visitInsn(Opcodes.IMUL);
        method.visitLdcInsn(ADDEND);
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, 4);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitVarInsn(Opcodes.ILOAD, 5);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitVarInsn(Opcodes.ILOAD, 5);
        method.visitInsn(Opcodes.BALOAD);
        method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitInsn(Opcodes.IXOR);
        method.visitInsn(Opcodes.I2B);
        method.visitInsn(Opcodes.BASTORE);
        method.visitIincInsn(5, 1);

        method.visitLabel(loopCheck);
        method.visitVarInsn(Opcodes.ILOAD, 5);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitInsn(Opcodes.ARRAYLENGTH);
        method.visitJumpInsn(Opcodes.IF_ICMPLT, loopBody);

        method.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false);
        method.visitLabel(end);
        method.visitInsn(Opcodes.ARETURN);

        method.visitLabel(handler);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(5, 6);
        method.visitEnd();
        return method;
    }

    private org.objectweb.asm.tree.MethodNode createStringArrayDecryptMethod(
            final ClassNode classNode,
            final String name,
            final String stringHelper
    ) {
        final int access = helperAccess(classNode);
        final org.objectweb.asm.tree.MethodNode method = new org.objectweb.asm.tree.MethodNode(
                access,
                name,
                "([Ljava/lang/String;)[Ljava/lang/String;",
                null,
                null
        );

        final Label nonNull = new Label();
        final Label loopCheck = new Label();
        final Label loopBody = new Label();

        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);

        method.visitLabel(nonNull);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARRAYLENGTH);
        method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ISTORE, 2);
        method.visitJumpInsn(Opcodes.GOTO, loopCheck);

        method.visitLabel(loopBody);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitInsn(Opcodes.AALOAD);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                classNode.getName(),
                stringHelper,
                "(Ljava/lang/String;)Ljava/lang/String;",
                false
        );
        method.visitInsn(Opcodes.AASTORE);
        method.visitIincInsn(2, 1);

        method.visitLabel(loopCheck);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARRAYLENGTH);
        method.visitJumpInsn(Opcodes.IF_ICMPLT, loopBody);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(4, 3);
        method.visitEnd();
        return method;
    }

    private int helperAccess(final ClassNode classNode) {
        if (classNode.isInterface()) {
            return Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        }

        return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
    }

    private String ownerFromDescriptor(final String descriptor) {
        if (descriptor == null || descriptor.length() < 3) {
            return descriptor;
        }

        return descriptor.substring(1, descriptor.length() - 1);
    }
}
