package dev.skidfuscator.obfuscator.transform.impl.annotation;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import org.mapleir.asm.ClassNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeAnnotationNode;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.util.List;

/**
 * Rewrites scalar {@code int} annotation element values to deterministic
 * obfuscated constants and rewrites transformed application call sites that read
 * those values to xor-decrypt the result.
 *
 * <p>Java annotation element values are stored in the class file as constants;
 * the JVM does not permit runtime expressions or method calls inside annotation
 * metadata. Because of that constraint this transformer cannot make the value
 * itself runtime-computed in the annotation table. The safe supported form is a
 * deterministic remap of scalar {@code int} values plus call-site decryption for
 * application bytecode that invokes the matching annotation accessor. External
 * frameworks or untransformed code that inspect the annotation directly will see
 * the remapped integer, so those annotations/classes should be exempted.</p>
 */
public class IntAnnotationEncryptionTransformer extends AbstractTransformer {
    private static final int FNV_OFFSET = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;
    private static final int SALT = 0x5A1D_F00D;

    public IntAnnotationEncryptionTransformer(final Skidfuscator skidfuscator) {
        super(skidfuscator, "Int Annotation Encryption");
    }

    public void apply() {
        if (skidfuscator.getJarContents() == null) {
            return;
        }

        for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
            final ClassNode wrapper = classData.getClassNode();
            if (wrapper == null || wrapper.node == null) {
                continue;
            }
            if (isClassExempt(wrapper)) {
                skip();
                continue;
            }

            processClassAnnotations(wrapper.node);
            processAnnotationDefaults(wrapper.node);
            processMethodAndFieldAnnotations(wrapper);
            rewriteAnnotationAccessorCalls(wrapper, wrapper.node);
        }
    }

    private void processClassAnnotations(final org.objectweb.asm.tree.ClassNode classNode) {
        processAnnotations(classNode.visibleAnnotations);
        processAnnotations(classNode.invisibleAnnotations);
        processTypeAnnotations(classNode.visibleTypeAnnotations);
        processTypeAnnotations(classNode.invisibleTypeAnnotations);
    }

    private void processMethodAndFieldAnnotations(final ClassNode wrapper) {
        for (MethodNode method : wrapper.node.methods) {
            if (isMethodExempt(wrapper, method)) {
                skip();
                continue;
            }
            processAnnotations(method.visibleAnnotations);
            processAnnotations(method.invisibleAnnotations);
            processTypeAnnotations(method.visibleTypeAnnotations);
            processTypeAnnotations(method.invisibleTypeAnnotations);
            processParameterAnnotations(method.visibleParameterAnnotations);
            processParameterAnnotations(method.invisibleParameterAnnotations);
            processLocalVariableAnnotations(method.visibleLocalVariableAnnotations);
            processLocalVariableAnnotations(method.invisibleLocalVariableAnnotations);
        }

        for (FieldNode field : wrapper.node.fields) {
            processAnnotations(field.visibleAnnotations);
            processAnnotations(field.invisibleAnnotations);
            processTypeAnnotations(field.visibleTypeAnnotations);
            processTypeAnnotations(field.invisibleTypeAnnotations);
        }
    }

    private void processAnnotationDefaults(final org.objectweb.asm.tree.ClassNode classNode) {
        if ((classNode.access & Opcodes.ACC_ANNOTATION) == 0) {
            return;
        }

        final String annotationDesc = 'L' + classNode.name + ';';
        for (MethodNode method : classNode.methods) {
            if (!"()I".equals(method.desc) || !(method.annotationDefault instanceof Integer)) {
                continue;
            }
            method.annotationDefault = remap((Integer) method.annotationDefault, annotationDesc, method.name);
            success();
        }
    }

    private void processAnnotations(final List<AnnotationNode> annotations) {
        if (annotations == null) {
            return;
        }
        for (AnnotationNode annotation : annotations) {
            processAnnotation(annotation);
        }
    }

    private void processTypeAnnotations(final List<TypeAnnotationNode> annotations) {
        if (annotations == null) {
            return;
        }
        for (TypeAnnotationNode annotation : annotations) {
            processAnnotation(annotation);
        }
    }

    private void processLocalVariableAnnotations(final List<LocalVariableAnnotationNode> annotations) {
        if (annotations == null) {
            return;
        }
        for (LocalVariableAnnotationNode annotation : annotations) {
            processAnnotation(annotation);
        }
    }

    private void processParameterAnnotations(final List<AnnotationNode>[] annotations) {
        if (annotations == null) {
            return;
        }
        for (List<AnnotationNode> annotationList : annotations) {
            processAnnotations(annotationList);
        }
    }

    private void processAnnotation(final AnnotationNode annotation) {
        if (annotation == null || annotation.values == null) {
            return;
        }

        // Annotations read straight from the class file by an external loader
        // (Forge/FML mod discovery, Mixin) never reach the decryptor call-sites
        // this transformer injects, so remapping their values breaks loading.
        if (FrameworkAnnotations.isStructural(annotation.desc)) {
            return;
        }

        for (int i = 0; i < annotation.values.size() - 1; i += 2) {
            final Object rawName = annotation.values.get(i);
            if (!(rawName instanceof String)) {
                continue;
            }

            final String elementName = (String) rawName;
            final Object value = annotation.values.get(i + 1);
            if (value instanceof Integer && isScalarIntElement(annotation.desc, elementName)) {
                annotation.values.set(i + 1, remap((Integer) value, annotation.desc, elementName));
                success();
            } else {
                processNestedAnnotationValues(value);
            }
        }
    }

    private void processNestedAnnotationValues(final Object value) {
        if (value instanceof AnnotationNode) {
            processAnnotation((AnnotationNode) value);
            return;
        }

        if (!(value instanceof List)) {
            return;
        }

        for (Object entry : (List<?>) value) {
            if (entry instanceof AnnotationNode) {
                processAnnotation((AnnotationNode) entry);
            }
        }
    }

    private boolean isScalarIntElement(final String annotationDesc, final String elementName) {
        final MethodNode method = resolveAnnotationElement(annotationDesc, elementName);
        return method != null && "()I".equals(method.desc);
    }

    private MethodNode resolveAnnotationElement(final String annotationDesc, final String elementName) {
        final org.objectweb.asm.tree.ClassNode annotationClass = resolveAnnotationClass(annotationDesc);
        if (annotationClass == null || (annotationClass.access & Opcodes.ACC_ANNOTATION) == 0) {
            return null;
        }

        for (MethodNode method : annotationClass.methods) {
            if (method.name.equals(elementName) && method.desc.startsWith("()")) {
                return method;
            }
        }
        return null;
    }

    private org.objectweb.asm.tree.ClassNode resolveAnnotationClass(final String annotationDesc) {
        if (annotationDesc == null) {
            return null;
        }
        final Type type = Type.getType(annotationDesc);
        if (type.getSort() != Type.OBJECT) {
            return null;
        }
        final ClassNode resolved = skidfuscator.getClassSource() == null
                ? null
                : skidfuscator.getClassSource().findClassNode(type.getInternalName());
        if (resolved != null) {
            return resolved.node;
        }

        if (skidfuscator.getJarContents() != null) {
            for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
                final ClassNode classNode = classData.getClassNode();
                if (classNode != null && classNode.node != null && type.getInternalName().equals(classNode.node.name)) {
                    return classNode.node;
                }
            }
        }
        return null;
    }

    private void rewriteAnnotationAccessorCalls(final ClassNode wrapper,
                                                final org.objectweb.asm.tree.ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            if (isMethodExempt(wrapper, method)) {
                skip();
                continue;
            }

            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }

                final MethodInsnNode methodInsn = (MethodInsnNode) insn;
                if (!"()I".equals(methodInsn.desc) || !isAnnotationOwner(methodInsn.owner, methodInsn.name)) {
                    continue;
                }

                final InsnList decrypt = new InsnList();
                pushInt(decrypt, key('L' + methodInsn.owner + ';', methodInsn.name));
                decrypt.add(new InsnNode(Opcodes.IXOR));
                method.instructions.insert(methodInsn, decrypt);
                method.maxStack = Math.max(method.maxStack, 2);
                success();
            }
        }
    }

    private boolean isAnnotationOwner(final String owner, final String elementName) {
        if (owner == null || owner.startsWith("[")) {
            return false;
        }
        final MethodNode element = resolveAnnotationElement('L' + owner + ';', elementName);
        return element != null && "()I".equals(element.desc);
    }

    private int remap(final int value, final String annotationDesc, final String elementName) {
        return value ^ key(annotationDesc, elementName);
    }

    private int key(final String annotationDesc, final String elementName) {
        int hash = FNV_OFFSET;
        hash = fnv(hash, annotationDesc);
        hash = fnv(hash, "#");
        hash = fnv(hash, elementName);
        hash ^= SALT;
        hash ^= (hash >>> 16);
        hash *= 0x7feb352d;
        hash ^= (hash >>> 15);
        hash *= 0x846ca68b;
        hash ^= (hash >>> 16);
        return hash == 0 ? SALT : hash;
    }

    private int fnv(int hash, final String value) {
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
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
