package dev.skidfuscator.obfuscator.number.encrypt.impl;

import dev.skidfuscator.obfuscator.number.encrypt.LongNumberTransformer;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;

public class MultiplyNumberTransformer implements LongNumberTransformer {
    @Override
    public Expr getNumber(final int outcome, final int starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        final int multiplier = RandomUtil.nextInt() | 1;
        final int inverse = NumberExprs.invertOddInt(multiplier);
        final int addend = RandomUtil.nextInt();
        final int mask = RandomUtil.nextInt();
        final int addendProduct = addend * inverse;
        final int transformed = (((starting ^ mask) * multiplier + addend) * inverse) - addendProduct;

        final Expr mixed = NumberExprs.sub(
                NumberExprs.mul(
                        NumberExprs.add(
                                NumberExprs.mul(
                                        NumberExprs.xor(startingExpr.get(vertex), NumberExprs.intConst(mask)),
                                        NumberExprs.intConst(multiplier)
                                ),
                                NumberExprs.intConst(addend)
                        ),
                        NumberExprs.intConst(inverse)
                ),
                NumberExprs.intConst(addendProduct)
        );

        return NumberExprs.add(mixed, NumberExprs.intConst(outcome - transformed));
    }

    @Override
    public Expr getNumberLong(final long outcome, final long starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        final long multiplier = RandomUtil.nextLong() | 1L;
        final long inverse = NumberExprs.invertOddLong(multiplier);
        final long addend = RandomUtil.nextLong();
        final long mask = RandomUtil.nextLong();
        final long addendProduct = addend * inverse;
        final long transformed = (((starting ^ mask) * multiplier + addend) * inverse) - addendProduct;

        final Expr mixed = NumberExprs.sub(
                NumberExprs.mul(
                        NumberExprs.add(
                                NumberExprs.mul(
                                        NumberExprs.xor(startingExpr.getWide(vertex), NumberExprs.longConst(mask)),
                                        NumberExprs.longConst(multiplier)
                                ),
                                NumberExprs.longConst(addend)
                        ),
                        NumberExprs.longConst(inverse)
                ),
                NumberExprs.longConst(addendProduct)
        );

        return NumberExprs.add(mixed, NumberExprs.longConst(outcome - transformed));
    }
}
