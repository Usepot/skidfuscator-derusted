package dev.skidfuscator.test.nativebackend;

import dev.skidfuscator.obfuscator.nativebackend.NativeEligibility;
import org.junit.jupiter.api.Test;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeEligibilityTest {
    @Test
    void acceptsOrdinaryMethodsForDirectConversion() {
        NativeEligibility.Result result = NativeEligibility.check(method(0, "run", Opcodes.RETURN));
        assertTrue(result.isSupported());
        assertEquals(NativeEligibility.Conversion.DIRECT, result.conversion());
    }

    @Test
    void requiresWrappersAtJvmRestrictedSites() {
        assertEquals(
                NativeEligibility.Conversion.WRAPPER_REQUIRED,
                NativeEligibility.check(method(0, "<init>", Opcodes.RETURN)).conversion()
        );
        assertEquals(
                NativeEligibility.Conversion.WRAPPER_REQUIRED,
                NativeEligibility.check(method(Opcodes.ACC_INTERFACE, "run", Opcodes.RETURN)).conversion()
        );
    }

    @Test
    void rejectsMethodsWithoutAStableJavaBody() {
        assertFalse(NativeEligibility.check(method(0, "empty", -1)).isSupported());
        assertFalse(NativeEligibility.check(method(0, "nativeMethod", Opcodes.ACC_NATIVE, -1)).isSupported());
        assertFalse(NativeEligibility.check(method(0, "bridge", Opcodes.ACC_BRIDGE, Opcodes.RETURN)).isSupported());
    }

    private static MethodNode method(final int ownerAccess, final String name, final int instruction) {
        return method(ownerAccess, name, 0, instruction);
    }

    private static MethodNode method(
            final int ownerAccess,
            final String name,
            final int methodAccess,
            final int instruction
    ) {
        ClassNode owner = new ClassNode();
        owner.node.name = "example/Owner";
        owner.node.access = ownerAccess;
        org.objectweb.asm.tree.MethodNode raw = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ACC_PUBLIC | methodAccess,
                name,
                "()V",
                null,
                null
        );
        if (instruction >= 0) {
            raw.instructions.add(new InsnNode(instruction));
        }
        MethodNode method = new MethodNode(raw, owner);
        owner.addMethod(method);
        return method;
    }
}
