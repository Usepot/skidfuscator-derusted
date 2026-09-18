package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.number.encrypt.LongNumberTransformer;
import dev.skidfuscator.obfuscator.number.encrypt.impl.*;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import org.junit.jupiter.api.Test;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.objectweb.asm.*;

import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runtime semantics for every int/long number transform, not just formula tests. */
public class WideNumberTransformerExecutionTest {
    @Test void everyTransformerRoundTripsArbitraryStartingSeedsInEmittedBytecode() throws Exception {
        LongNumberTransformer[] transformers = {
                new XorNumberTransformer(), new AddSubNumberTransformer(), new RotateNumberTransformer(),
                new MultiplyNumberTransformer(), new FeistelNumberTransformer()
        };
        Random values = new Random(0x5EEDBEEFL);
        int index = 0;
        for (LongNumberTransformer transformer : transformers) {
            for (int trial = 0; trial < 128; trial++) {
                long starting = values.nextLong(), outcome = values.nextLong();
                PredicateFlowGetter getter = new PredicateFlowGetter() {
                    @Override public Expr get(org.mapleir.ir.cfg.BasicBlock ignored) {
                        return new ConstantExpr((int) starting, Type.INT_TYPE);
                    }
                    @Override public Expr getWide(org.mapleir.ir.cfg.BasicBlock ignored) {
                        return new ConstantExpr(starting, Type.LONG_TYPE);
                    }
                };
                Expr expression = transformer.getNumberLong(outcome, starting, null, getter);
                assertEquals(outcome, executeLong(expression, index++), transformer.getClass().getSimpleName());
            }
        }
    }

    private static long executeLong(Expr expression, int index) throws Exception {
        String name = "fixture/WideNumber" + index;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()J", null, null);
        method.visitCode();
        expression.toCode(method, null);
        method.visitInsn(Opcodes.LRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(WideNumberTransformerExecutionTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        Method emitted = type.getMethod("value");
        return ((Number) emitted.invoke(null)).longValue();
    }
}
