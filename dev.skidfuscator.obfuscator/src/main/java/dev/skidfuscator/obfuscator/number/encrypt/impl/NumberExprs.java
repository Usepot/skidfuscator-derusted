package dev.skidfuscator.obfuscator.number.encrypt.impl;

import dev.skidfuscator.obfuscator.skidasm.fake.FakeArithmeticExpr;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.objectweb.asm.Type;

final class NumberExprs {
    private NumberExprs() {
    }

    static Expr intConst(final int value) {
        return new ConstantExpr(value, Type.INT_TYPE);
    }

    static Expr longConst(final long value) {
        return new ConstantExpr(value, Type.LONG_TYPE);
    }

    static Expr add(final Expr left, final Expr right) {
        return new FakeArithmeticExpr(left, right, ArithmeticExpr.Operator.ADD);
    }

    static Expr sub(final Expr left, final Expr right) {
        return new FakeArithmeticExpr(left, right, ArithmeticExpr.Operator.SUB);
    }

    static Expr mul(final Expr left, final Expr right) {
        return new FakeArithmeticExpr(left, right, ArithmeticExpr.Operator.MUL);
    }

    static Expr xor(final Expr left, final Expr right) {
        return new FakeArithmeticExpr(left, right, ArithmeticExpr.Operator.XOR);
    }

    static Expr and(final Expr left, final Expr right) {
        return new FakeArithmeticExpr(left, right, ArithmeticExpr.Operator.AND);
    }

    static Expr or(final Expr left, final Expr right) {
        return new FakeArithmeticExpr(left, right, ArithmeticExpr.Operator.OR);
    }

    static Expr shl(final Expr left, final int shift) {
        return new FakeArithmeticExpr(left, intConst(shift), ArithmeticExpr.Operator.SHL);
    }

    static Expr ushr(final Expr left, final int shift) {
        return new FakeArithmeticExpr(left, intConst(shift), ArithmeticExpr.Operator.USHR);
    }

    static Expr rotateLeftInt(final Expr value, final int shift) {
        return or(
                shl(value, shift),
                ushr(value.copy(), 32 - shift)
        );
    }

    static Expr rotateLeftLong(final Expr value, final int shift) {
        return or(
                shl(value, shift),
                ushr(value.copy(), 64 - shift)
        );
    }

    static int invertOddInt(final int value) {
        int inverse = value;
        for (int i = 0; i < 5; i++) {
            inverse *= 2 - value * inverse;
        }
        return inverse;
    }

    static long invertOddLong(final long value) {
        long inverse = value;
        for (int i = 0; i < 6; i++) {
            inverse *= 2L - value * inverse;
        }
        return inverse;
    }
}
