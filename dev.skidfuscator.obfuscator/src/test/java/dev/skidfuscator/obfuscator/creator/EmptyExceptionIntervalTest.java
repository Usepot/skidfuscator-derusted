package dev.skidfuscator.obfuscator.creator;

import org.junit.jupiter.api.Test;
import org.mapleir.ir.codegen.ExceptionTableEmitter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.MethodNode;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class EmptyExceptionIntervalTest {
    @Test void dropsOnlyEmptyIntervalsAndPreservesFirstMatchingHandler() throws Exception {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "(Ljava/lang/Throwable;)I", null, null);
        Label empty = new Label(), start = new Label(), end = new Label();
        Label first = new Label(), middle = new Label(), last = new Label();
        method.visitCode();
        method.visitLabel(empty);
        method.visitLineNumber(10, empty); // Metadata occupies no bytecode.
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        method.visitLabel(start);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(end);
        method.visitLabel(first);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(middle);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(last);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ICONST_3);
        method.visitInsn(Opcodes.IRETURN);
        emit(method, empty, start, first, Throwable.class);
        emit(method, start, end, first, IllegalArgumentException.class);
        emit(method, empty, start, last, Exception.class);
        emit(method, start, end, middle, RuntimeException.class);
        emit(method, start, end, first, ArithmeticException.class);
        emit(method, end, end, last, Throwable.class);
        assertEquals(3, method.tryCatchBlocks.size());
        assertEquals("java/lang/IllegalArgumentException", method.tryCatchBlocks.get(0).type);
        assertEquals("java/lang/RuntimeException", method.tryCatchBlocks.get(1).type);
        assertEquals("java/lang/ArithmeticException", method.tryCatchBlocks.get(2).type);
        method.visitMaxs(1, 1);
        method.visitEnd();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/EmptyIntervals", null, "java/lang/Object", null);
        method.accept(writer);
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        assertEquals(1, type.getMethod("test", Throwable.class).invoke(null, new IllegalArgumentException()));
        assertEquals(2, type.getMethod("test", Throwable.class).invoke(null, new ArithmeticException()));
    }

    @Test void aNopIsNotAnEmptyInterval() {
        MethodNode method = new MethodNode();
        Label start = new Label(), end = new Label();
        method.visitLabel(start);
        method.visitInsn(Opcodes.NOP);
        method.visitLabel(end);
        emit(method, start, end, end, Exception.class);
        assertEquals(1, method.tryCatchBlocks.size());
    }

    private static void emit(MethodNode method, Label start, Label end, Label handler, Class<?> type) {
        ExceptionTableEmitter.emit(method, start, end, handler, Collections.singleton(Type.getType(type)));
    }
}
