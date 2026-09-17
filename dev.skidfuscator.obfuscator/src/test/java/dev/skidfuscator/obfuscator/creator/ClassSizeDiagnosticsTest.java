package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.util.ClassSizeDiagnostics;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

class ClassSizeDiagnosticsTest {
    @Test void measuresWideBranchesWithStaleFramesBeforeFinalFrameComputation() throws Exception {
        ClassNode node = new ClassNode();
        node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/WideBranchDiagnostics", null,
                "java/lang/Object", null);
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        LabelNode target = new LabelNode();
        method.instructions.add(new InsnNode(Opcodes.ICONST_5));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, target));
        for (int i = 0; i < 40000; i++) method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(target);
        // Models the intermediate state after a late pass changes stack flow.
        // Final COMPUTE_FRAMES will replace this obsolete frame.
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        method.maxLocals = 1;
        node.methods.add(method);

        ClassWriter premature = new ClassWriter(0);
        node.accept(premature);
        assertThrows(ArrayIndexOutOfBoundsException.class, premature::toByteArray);
        AbstractInsnNode[] original = method.instructions.toArray();
        ClassSizeDiagnostics.SizeEstimate size = ClassSizeDiagnostics.measure(node);
        assertTrue(size.constantPoolEstimate() > 0);
        assertTrue(size.largestMethodBytesUpperBound() > Short.MAX_VALUE);
        assertEquals("value()I", size.largestMethod());
        assertArrayEquals(original, method.instructions.toArray());

        ClassWriter finalWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(finalWriter);
        byte[] bytes = finalWriter.toByteArray();
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadFixture() { return defineClass(null, bytes, 0, bytes.length); }
        }.loadFixture();
        assertEquals(5, type.getMethod("value").invoke(null));
    }

    @Test void reportsOversizedIntermediateMethodsWithoutSerializingThem() {
        ClassNode node = new ClassNode();
        node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/IntermediateSize", null,
                "java/lang/Object", null);
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "large", "()V", null, null);
        for (int i = 0; i < 66000; i++) method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        assertTrue(ClassSizeDiagnostics.measure(node).largestMethodBytesUpperBound() > 65535);
    }
}
