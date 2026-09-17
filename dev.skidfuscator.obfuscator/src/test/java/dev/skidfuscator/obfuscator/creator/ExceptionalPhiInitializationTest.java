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
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual SSA construction, destruction, allocation and JVM execution. */
class ExceptionalPhiInitializationTest implements Opcodes {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nestedReflectionFallbackPreservesNullAndNonNullIncomingValues(boolean skidDumper) throws Exception {
        byte[] original = fixture();
        assertBehavior(define(original));
        assertBehavior(define(roundTrip(original, skidDumper)));
    }

    private static void assertBehavior(Class<?> type) throws Exception {
        assertNull(type.getField("MISSING").get(null), "both failed lookups must retain null");
        assertEquals(String.class.getDeclaredField("CASE_INSENSITIVE_ORDER"), type.getField("PRIMARY").get(null));
        assertEquals(String.class.getDeclaredField("CASE_INSENSITIVE_ORDER"), type.getField("FALLBACK").get(null));
        Method resolve = type.getMethod("resolve", Class.class, String.class, String.class, Object.class);
        Object sentinel = new Object();
        assertSame(sentinel, resolve.invoke(null, String.class, "missingOne", "missingTwo", sentinel));
        assertNull(resolve.invoke(null, String.class, "missingOne", "missingTwo", null));
        assertEquals(String.class.getDeclaredField("CASE_INSENSITIVE_ORDER"),
                resolve.invoke(null, String.class, "CASE_INSENSITIVE_ORDER", "missingTwo", sentinel));
        assertEquals(String.class.getDeclaredField("CASE_INSENSITIVE_ORDER"),
                resolve.invoke(null, String.class, "missingOne", "CASE_INSENSITIVE_ORDER", sentinel));
        assertSame(sentinel, resolve.invoke(null, null, "unused", "unused", sentinel));

        Method changes = type.getMethod("changes", Object.class, Object.class, int.class);
        Object replacement = new Object();
        assertSame(sentinel, changes.invoke(null, sentinel, replacement, 0));
        assertSame(replacement, changes.invoke(null, sentinel, replacement, 1));
        assertNull(changes.invoke(null, sentinel, replacement, 2));
        assertSame(replacement, changes.invoke(null, sentinel, replacement, 3));

        Method loop = type.getMethod("loop", Object.class, Object[].class, int.class);
        Object[] updates = {new Object(), null, new Object(), "last"};
        for (int stop = 0; stop <= updates.length; stop++) {
            Object expected = stop == 0 ? sentinel : updates[stop - 1];
            assertSame(expected, loop.invoke(null, sentinel, updates, stop), "exceptional loop exit " + stop);
        }
        assertSame(sentinel, loop.invoke(null, sentinel, null, -1));
        assertSame(updates[3], loop.invoke(null, sentinel, updates, -1));
    }

    private static byte[] roundTrip(byte[] bytes, boolean skidDumper) {
        ClassNode owner = ClassHelper.create(bytes, 0);
        for (MethodNode method : owner.getMethods()) {
            ControlFlowGraph graph = ControlFlowGraphBuilder.build(method);
            if (Boolean.getBoolean("skid.debug.testGraph")) System.out.println("SSA " + method.getName() + "\n" + graph);
            BoissinotDestructor.leaveSSA(graph);
            if (Boolean.getBoolean("skid.debug.testGraph")) System.out.println("LOWERED " + method.getName() + "\n" + graph);
            LocalsReallocator.realloc(graph);
            if (skidDumper) new SkidFlowGraphDumper(null, graph, method).dump();
            else new ControlFlowGraphDumper(graph, method).dump();
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.node.accept(writer);
        return writer.toByteArray();
    }

    private static Class<?> define(byte[] bytes) {
        return new ClassLoader(ExceptionalPhiInitializationTest.class.getClassLoader()) {
            Class<?> load() { return defineClass(null, bytes, 0, bytes.length); }
        }.load();
    }

    private static MethodVisitor method(ClassWriter writer, String name, String descriptor) {
        MethodVisitor visitor = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
        visitor.visitCode();
        return visitor;
    }

    private static void end(MethodVisitor method) {
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void throwRuntime(MethodVisitor method) {
        method.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
        method.visitInsn(ATHROW);
    }

    private static void reflectionCall(MethodVisitor method) {
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
    }

    private static void clinitLookup(MethodVisitor method, String primaryName, String fallbackName, String field) {
        Label start = new Label(), end = new Label(), fallback = new Label();
        Label fallbackEnd = new Label(), failed = new Label(), join = new Label();
        method.visitTryCatchBlock(start, end, fallback, "java/lang/Throwable");
        method.visitTryCatchBlock(fallback, fallbackEnd, failed, "java/lang/Throwable");
        method.visitInsn(ACONST_NULL);
        method.visitVarInsn(ASTORE, 0);
        method.visitLabel(start);
        method.visitLdcInsn(Type.getType(String.class));
        method.visitLdcInsn(primaryName);
        reflectionCall(method);
        method.visitVarInsn(ASTORE, 0);
        method.visitLabel(end);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(fallback);
        method.visitVarInsn(ASTORE, 1);
        method.visitLdcInsn(Type.getType(String.class));
        method.visitLdcInsn(fallbackName);
        reflectionCall(method);
        method.visitVarInsn(ASTORE, 0);
        method.visitLabel(fallbackEnd);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(failed);
        method.visitVarInsn(ASTORE, 2);
        method.visitLabel(join);
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(PUTSTATIC, "fixture/ExceptionalPhi", field, "Ljava/lang/reflect/Field;");
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, "fixture/ExceptionalPhi", null, "java/lang/Object", null);
        for (String name : new String[]{"MISSING", "PRIMARY", "FALLBACK"})
            writer.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, name, "Ljava/lang/reflect/Field;", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        clinitLookup(init, "missingPrimary", "missingFallback", "MISSING");
        clinitLookup(init, "CASE_INSENSITIVE_ORDER", "missingFallback", "PRIMARY");
        clinitLookup(init, "missingPrimary", "CASE_INSENSITIVE_ORDER", "FALLBACK");
        init.visitInsn(RETURN);
        end(init);

        MethodVisitor resolve = method(writer, "resolve",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
        Label first = new Label(), firstEnd = new Label(), second = new Label();
        Label secondEnd = new Label(), secondFail = new Label(), join = new Label();
        resolve.visitTryCatchBlock(first, firstEnd, second, "java/lang/Throwable");
        resolve.visitTryCatchBlock(second, secondEnd, secondFail, "java/lang/Throwable");
        resolve.visitVarInsn(ALOAD, 3);
        resolve.visitVarInsn(ASTORE, 4);
        resolve.visitLabel(first);
        resolve.visitVarInsn(ALOAD, 0);
        resolve.visitVarInsn(ALOAD, 1);
        reflectionCall(resolve);
        resolve.visitVarInsn(ASTORE, 4);
        resolve.visitLabel(firstEnd);
        resolve.visitJumpInsn(GOTO, join);
        resolve.visitLabel(second);
        resolve.visitVarInsn(ASTORE, 5);
        resolve.visitVarInsn(ALOAD, 0);
        resolve.visitVarInsn(ALOAD, 2);
        reflectionCall(resolve);
        resolve.visitVarInsn(ASTORE, 4);
        resolve.visitLabel(secondEnd);
        resolve.visitJumpInsn(GOTO, join);
        resolve.visitLabel(secondFail);
        resolve.visitVarInsn(ASTORE, 6);
        resolve.visitLabel(join);
        resolve.visitVarInsn(ALOAD, 4);
        resolve.visitInsn(ARETURN);
        end(resolve);

        MethodVisitor changes = method(writer, "changes", "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;");
        Label start = new Label(), finish = new Label(), caught = new Label(), assign = new Label();
        Label clear = new Label(), normal = new Label();
        changes.visitTryCatchBlock(start, finish, caught, "java/lang/Throwable");
        changes.visitVarInsn(ALOAD, 0);
        changes.visitVarInsn(ASTORE, 3);
        changes.visitLabel(start);
        changes.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        changes.visitInsn(POP2);
        changes.visitVarInsn(ILOAD, 2);
        changes.visitJumpInsn(IFNE, assign);
        throwRuntime(changes);
        changes.visitLabel(assign);
        changes.visitVarInsn(ALOAD, 1);
        changes.visitVarInsn(ASTORE, 3);
        changes.visitVarInsn(ILOAD, 2);
        changes.visitInsn(ICONST_1);
        changes.visitJumpInsn(IF_ICMPNE, clear);
        throwRuntime(changes);
        changes.visitLabel(clear);
        changes.visitVarInsn(ILOAD, 2);
        changes.visitInsn(ICONST_2);
        changes.visitJumpInsn(IF_ICMPNE, normal);
        changes.visitInsn(ACONST_NULL);
        changes.visitVarInsn(ASTORE, 3);
        throwRuntime(changes);
        changes.visitLabel(finish);
        changes.visitLabel(normal);
        changes.visitVarInsn(ALOAD, 3);
        changes.visitInsn(ARETURN);
        changes.visitLabel(caught);
        changes.visitVarInsn(ASTORE, 4);
        changes.visitVarInsn(ALOAD, 3);
        changes.visitInsn(ARETURN);
        end(changes);

        MethodVisitor loop = method(writer, "loop", "(Ljava/lang/Object;[Ljava/lang/Object;I)Ljava/lang/Object;");
        Label tryStart = new Label(), tryEnd = new Label(), handler = new Label();
        Label condition = new Label(), update = new Label(), done = new Label();
        loop.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        loop.visitVarInsn(ALOAD, 0);
        loop.visitVarInsn(ASTORE, 3);
        loop.visitInsn(ICONST_0);
        loop.visitVarInsn(ISTORE, 4);
        loop.visitLabel(tryStart);
        loop.visitLabel(condition);
        loop.visitVarInsn(ILOAD, 4);
        loop.visitVarInsn(ALOAD, 1);
        loop.visitInsn(ARRAYLENGTH);
        loop.visitJumpInsn(IF_ICMPGE, done);
        loop.visitVarInsn(ILOAD, 4);
        loop.visitVarInsn(ILOAD, 2);
        loop.visitJumpInsn(IF_ICMPNE, update);
        throwRuntime(loop);
        loop.visitLabel(update);
        loop.visitVarInsn(ALOAD, 1);
        loop.visitVarInsn(ILOAD, 4);
        loop.visitInsn(AALOAD);
        loop.visitVarInsn(ASTORE, 3);
        loop.visitIincInsn(4, 1);
        loop.visitJumpInsn(GOTO, condition);
        loop.visitLabel(tryEnd);
        loop.visitLabel(done);
        loop.visitVarInsn(ALOAD, 3);
        loop.visitInsn(ARETURN);
        loop.visitLabel(handler);
        loop.visitVarInsn(ASTORE, 5);
        loop.visitVarInsn(ALOAD, 3);
        loop.visitInsn(ARETURN);
        end(loop);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
