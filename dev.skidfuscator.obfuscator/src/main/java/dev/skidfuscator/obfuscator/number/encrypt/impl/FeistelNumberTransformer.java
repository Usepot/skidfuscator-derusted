package dev.skidfuscator.obfuscator.number.encrypt.impl;

import dev.skidfuscator.obfuscator.number.encrypt.LongNumberTransformer;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;

public class FeistelNumberTransformer implements LongNumberTransformer {
    private static final int LOW_INT_MASK = 0xFFFF;
    private static final long LOW_LONG_MASK = 0xFFFFFFFFL;

    @Override
    public Expr getNumber(final int outcome, final int starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        final int multiplier = RandomUtil.nextInt() | 1;
        final int key = RandomUtil.nextInt();
        final int shift = RandomUtil.nextInt(15) + 1;
        final int left = starting >>> 16;
        final int right = starting & LOW_INT_MASK;
        final int round = ((right * multiplier) + key) ^ (right >>> shift);
        final int packed = (right << 16) | ((left ^ round) & LOW_INT_MASK);

        final Expr seed = startingExpr.get(vertex);
        final Expr rightForRound = NumberExprs.and(seed.copy(), NumberExprs.intConst(LOW_INT_MASK));
        final Expr roundExpr = NumberExprs.xor(
                NumberExprs.add(
                        NumberExprs.mul(rightForRound, NumberExprs.intConst(multiplier)),
                        NumberExprs.intConst(key)
                ),
                NumberExprs.ushr(rightForRound.copy(), shift)
        );
        final Expr packedExpr = NumberExprs.or(
                NumberExprs.shl(NumberExprs.and(seed.copy(), NumberExprs.intConst(LOW_INT_MASK)), 16),
                NumberExprs.and(
                        NumberExprs.xor(NumberExprs.ushr(seed, 16), roundExpr),
                        NumberExprs.intConst(LOW_INT_MASK)
                )
        );

        return NumberExprs.add(packedExpr, NumberExprs.intConst(outcome - packed));
    }

    @Override
    public Expr getNumberLong(final long outcome, final long starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        final long multiplier = RandomUtil.nextLong() | 1L;
        final long key = RandomUtil.nextLong();
        final int shift = RandomUtil.nextInt(31) + 1;
        final long left = starting >>> 32;
        final long right = starting & LOW_LONG_MASK;
        final long round = ((right * multiplier) + key) ^ (right >>> shift);
        final long packed = (right << 32) | ((left ^ round) & LOW_LONG_MASK);

        final Expr seed = startingExpr.getWide(vertex);
        final Expr rightForRound = NumberExprs.and(seed.copy(), NumberExprs.longConst(LOW_LONG_MASK));
        final Expr roundExpr = NumberExprs.xor(
                NumberExprs.add(
                        NumberExprs.mul(rightForRound, NumberExprs.longConst(multiplier)),
                        NumberExprs.longConst(key)
                ),
                NumberExprs.ushr(rightForRound.copy(), shift)
        );
        final Expr packedExpr = NumberExprs.or(
                NumberExprs.shl(NumberExprs.and(seed.copy(), NumberExprs.longConst(LOW_LONG_MASK)), 32),
                NumberExprs.and(
                        NumberExprs.xor(NumberExprs.ushr(seed, 32), roundExpr),
                        NumberExprs.longConst(LOW_LONG_MASK)
                )
        );

        return NumberExprs.add(packedExpr, NumberExprs.longConst(outcome - packed));
    }
}
