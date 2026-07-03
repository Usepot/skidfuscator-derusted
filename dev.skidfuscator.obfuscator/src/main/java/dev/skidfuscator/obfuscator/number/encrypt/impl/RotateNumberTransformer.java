package dev.skidfuscator.obfuscator.number.encrypt.impl;

import dev.skidfuscator.obfuscator.number.encrypt.LongNumberTransformer;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;

public class RotateNumberTransformer implements LongNumberTransformer {
    @Override
    public Expr getNumber(final int outcome, final int starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        final int addend = RandomUtil.nextInt();
        final int mask = RandomUtil.nextInt();
        final int shift = RandomUtil.nextInt(31) + 1;
        final int transformed = Integer.rotateLeft(starting + addend, shift) ^ mask;

        final Expr mixed = NumberExprs.xor(
                NumberExprs.rotateLeftInt(
                        NumberExprs.add(startingExpr.get(vertex), NumberExprs.intConst(addend)),
                        shift
                ),
                NumberExprs.intConst(mask)
        );

        return NumberExprs.add(mixed, NumberExprs.intConst(outcome - transformed));
    }

    @Override
    public Expr getNumberLong(final long outcome, final long starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        final long addend = RandomUtil.nextLong();
        final long mask = RandomUtil.nextLong();
        final int shift = RandomUtil.nextInt(63) + 1;
        final long transformed = Long.rotateLeft(starting + addend, shift) ^ mask;

        final Expr mixed = NumberExprs.xor(
                NumberExprs.rotateLeftLong(
                        NumberExprs.add(startingExpr.getWide(vertex), NumberExprs.longConst(addend)),
                        shift
                ),
                NumberExprs.longConst(mask)
        );

        return NumberExprs.add(mixed, NumberExprs.longConst(outcome - transformed));
    }
}
