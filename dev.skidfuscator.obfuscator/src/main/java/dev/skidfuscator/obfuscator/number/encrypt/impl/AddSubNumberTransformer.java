package dev.skidfuscator.obfuscator.number.encrypt.impl;

import dev.skidfuscator.obfuscator.number.encrypt.LongNumberTransformer;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;

public class AddSubNumberTransformer implements LongNumberTransformer {
    @Override
    public Expr getNumber(final int outcome, final int starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        final int addend = RandomUtil.nextInt();
        final int mask = RandomUtil.nextInt();
        final int subtrahend = RandomUtil.nextInt();
        final int transformed = ((starting + addend) ^ mask) - subtrahend;

        final Expr mixed = NumberExprs.sub(
                NumberExprs.xor(
                        NumberExprs.add(startingExpr.get(vertex), NumberExprs.intConst(addend)),
                        NumberExprs.intConst(mask)
                ),
                NumberExprs.intConst(subtrahend)
        );

        return NumberExprs.add(mixed, NumberExprs.intConst(outcome - transformed));
    }

    @Override
    public Expr getNumberLong(final long outcome, final long starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        final long addend = RandomUtil.nextLong();
        final long mask = RandomUtil.nextLong();
        final long subtrahend = RandomUtil.nextLong();
        final long transformed = ((starting + addend) ^ mask) - subtrahend;

        final Expr mixed = NumberExprs.sub(
                NumberExprs.xor(
                        NumberExprs.add(startingExpr.getWide(vertex), NumberExprs.longConst(addend)),
                        NumberExprs.longConst(mask)
                ),
                NumberExprs.longConst(subtrahend)
        );

        return NumberExprs.add(mixed, NumberExprs.longConst(outcome - transformed));
    }
}
