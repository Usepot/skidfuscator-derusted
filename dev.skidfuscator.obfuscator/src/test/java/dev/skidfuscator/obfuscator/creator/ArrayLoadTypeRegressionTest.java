package dev.skidfuscator.obfuscator.creator;

import org.junit.jupiter.api.Test;
import org.mapleir.ir.TypeUtils;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ArrayLoadExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.locals.impl.StaticMethodLocalsPool;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ArrayLoadTypeRegressionTest {
    @Test void removesExactlyOneDimensionForEveryPrimitiveAndReferenceComponent() {
        for (String component : Arrays.asList("B", "Z", "C", "S", "I", "F", "J", "D", "Ljava/lang/String;")) {
            for (int dimensions = 1; dimensions <= 4; dimensions++) {
                String descriptor = "[".repeat(dimensions) + component;
                Type expected = Type.getType(descriptor.substring(1));
                Expr array = new VarExpr(new StaticMethodLocalsPool().get(0), Type.getType(descriptor));
                ArrayLoadExpr load = load(array, expected.getOpcode(Opcodes.IALOAD));
                assertEquals(expected, load.getType(), descriptor);
                assertEquals(expected, load.copy().getType(), descriptor + " copy");
            }
        }
    }

    @Test void nestedDoubleArrayLoadsStayReferencesUntilTheFinalDimension() {
        Expr array = new VarExpr(new StaticMethodLocalsPool().get(0), Type.getType("[[[D"));
        ArrayLoadExpr first = load(array, Opcodes.AALOAD);
        ArrayLoadExpr second = load(first, Opcodes.AALOAD);
        ArrayLoadExpr third = load(second, Opcodes.DALOAD);
        assertEquals(Type.getType("[[D"), first.getType());
        assertEquals(Type.getType("[D"), second.getType());
        assertEquals(Type.DOUBLE_TYPE, third.getType());
    }

    private static ArrayLoadExpr load(Expr array, int opcode) {
        TypeUtils.ArrayType access = Arrays.stream(TypeUtils.ArrayType.values())
                .filter(type -> type.getLoadOpcode() == opcode).findFirst().orElseThrow();
        return new ArrayLoadExpr(array, new ConstantExpr(0), access);
    }
}
