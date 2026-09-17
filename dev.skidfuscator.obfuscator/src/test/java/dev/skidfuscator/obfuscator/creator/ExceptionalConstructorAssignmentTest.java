package dev.skidfuscator.obfuscator.creator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapleir.asm.ClassHelper;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.mapleir.ir.algorithms.BoissinotDestructor;
import org.mapleir.ir.algorithms.LocalsReallocator;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.cfg.builder.ControlFlowGraphBuilder;
import org.mapleir.ir.codegen.ControlFlowGraphDumper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** A failed invokespecial must not destroy the pre-assignment resource local. */
class ExceptionalConstructorAssignmentTest implements Opcodes {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedConstructorPreservesPreviousValue(boolean skidDumper) throws Exception {
        byte[] original = fixture();
        check(define(original));
        ClassNode owner = ClassHelper.create(original, 0);
        for (MethodNode method : owner.getMethods()) {
            ControlFlowGraph graph = ControlFlowGraphBuilder.build(method);
            BoissinotDestructor.leaveSSA(graph);
            LocalsReallocator.realloc(graph);
            if (skidDumper) new SkidFlowGraphDumper(null, graph, method).dump();
            else new ControlFlowGraphDumper(graph, method).dump();
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.node.accept(writer);
        check(define(writer.toByteArray()));
    }

    private static void check(Class<?> type) throws Exception {
        Method method = type.getMethod("construct", Object.class, int.class);
        Object sentinel = new Object();
        assertSame(sentinel, method.invoke(null, sentinel, -1));
        assertNull(method.invoke(null, null, -1));
        assertInstanceOf(StringBuilder.class, method.invoke(null, sentinel, 0));
        assertInstanceOf(StringBuilder.class, method.invoke(null, null, 8));
    }

    private static Class<?> define(byte[] bytes) {
        return new ClassLoader(ExceptionalConstructorAssignmentTest.class.getClassLoader()) {
            Class<?> load() { return defineClass(null, bytes, 0, bytes.length); }
        }.load();
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, "fixture/ExceptionalConstructor", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "construct",
                "(Ljava/lang/Object;I)Ljava/lang/Object;", null, null);
        method.visitCode();
        Label start = new Label(), end = new Label(), handler = new Label(), join = new Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ASTORE, 2);
        method.visitLabel(start);
        method.visitTypeInsn(NEW, "java/lang/StringBuilder");
        method.visitInsn(DUP);
        method.visitVarInsn(ILOAD, 1);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(I)V", false);
        method.visitVarInsn(ASTORE, 2);
        method.visitLabel(new Label());
        // The handler sees the old value during construction, but the new
        // value if a later protected operation throws (as in resource cleanup).
        method.visitVarInsn(ALOAD, 2);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        method.visitInsn(POP);
        method.visitLabel(end);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(handler);
        method.visitVarInsn(ASTORE, 3);
        method.visitLabel(join);
        method.visitVarInsn(ALOAD, 2);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
