package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.number.pure.VmHashTargetPolicy;
import org.junit.jupiter.api.Test;
import org.mapleir.asm.ClassHelper;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class VmHashTargetPolicyTest implements Opcodes {
    private static MethodNode method(String owner, int ownerAccess, String name, String desc, int access) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ownerAccess, owner, null, "java/lang/Object", null);
        writer.visitMethod(access, name, desc, null, null).visitEnd();
        writer.visitEnd();
        return ClassHelper.create(writer.toByteArray(), 0).getMethods().get(0);
    }

    @Test void permitsStablePublicBootstrapPermutation() {
        assertTrue(VmHashTargetPolicy.permits(method("java/lang/Integer", ACC_PUBLIC,
                "reverse", "(I)I", ACC_PUBLIC | ACC_STATIC)));
        assertTrue(VmHashTargetPolicy.permits(method("java/lang/Integer", ACC_PUBLIC,
                "sum", "(II)I", ACC_PUBLIC | ACC_STATIC)));
    }

    @Test void rejectsApplicationAndChildLoaderDependencies() {
        assertFalse(VmHashTargetPolicy.permits(method("client/KeyBindSave", ACC_PUBLIC,
                "resolveMouseBindingKey", "(I)I", ACC_PUBLIC | ACC_STATIC)));
        assertFalse(VmHashTargetPolicy.permits(method("com/google/common/primitives/Ints", ACC_PUBLIC,
                "compare", "(II)I", ACC_PUBLIC | ACC_STATIC)));
        assertFalse(VmHashTargetPolicy.permits(method("okio/Util", 0,
                "reverseBytesInt", "(I)I", ACC_PUBLIC | ACC_STATIC)));
    }

    @Test void rejectsInaccessibleOrUnexpectedBootstrapMembers() {
        assertFalse(VmHashTargetPolicy.permits(method("java/lang/Integer", 0,
                "reverse", "(I)I", ACC_PUBLIC | ACC_STATIC)));
        assertFalse(VmHashTargetPolicy.permits(method("java/lang/Integer", ACC_PUBLIC,
                "reverse", "(I)I", ACC_PRIVATE | ACC_STATIC)));
        assertFalse(VmHashTargetPolicy.permits(method("java/lang/Integer", ACC_PUBLIC,
                "reverse", "(I)I", ACC_PUBLIC | ACC_STATIC | ACC_NATIVE)));
        assertFalse(VmHashTargetPolicy.permits(method("java/lang/Integer", ACC_PUBLIC,
                "reverse", "(II)I", ACC_PUBLIC | ACC_STATIC)));
    }

    @Test void rotationDistanceMustRemainConstant() {
        MethodNode rotate = method("java/lang/Integer", ACC_PUBLIC,
                "rotateLeft", "(II)I", ACC_PUBLIC | ACC_STATIC);
        assertTrue(VmHashTargetPolicy.permitsParameter(rotate, 0));
        assertFalse(VmHashTargetPolicy.permitsParameter(rotate, 1));
        MethodNode sum = method("java/lang/Integer", ACC_PUBLIC,
                "sum", "(II)I", ACC_PUBLIC | ACC_STATIC);
        assertTrue(VmHashTargetPolicy.permitsParameter(sum, 0));
        assertTrue(VmHashTargetPolicy.permitsParameter(sum, 1));
        assertFalse(VmHashTargetPolicy.permitsParameter(sum, 2));
    }
}
