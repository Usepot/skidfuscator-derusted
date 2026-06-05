package dev.skidfuscator.obfuscator.number.hash;

import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.CastExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.locals.Local;
import org.objectweb.asm.Type;

public interface HashTransformer {
    SkiddedHash hash(final int starting, final BasicBlock vertex, final PredicateFlowGetter caller);

    int hash (final int starting);

    Expr hash(final BasicBlock vertex, final PredicateFlowGetter expr);

    /**
     * Freeze the current hash function so every subsequent call — build-time
     * {@link #hash(int)} and runtime {@link #hash(BasicBlock, PredicateFlowGetter)}
     * alike — resolves through the SAME underlying function until {@link #rotate()}.
     *
     * <p>Required by any scheme that compares a value computed at build time
     * against a hash evaluated at runtime (e.g. a lossy masked comparison): if the
     * function were allowed to change between the two, the branch would compare
     * outputs of two different functions and mis-route. No-op by default.</p>
     */
    default void pin() {}

    /**
     * Release a {@link #pin()} and advance to a fresh hash function, so the next
     * call site uses a different one. This is what stops an analyst from reversing
     * one function and reusing it everywhere. No-op by default.
     */
    default void rotate() {}

    /**
     * @return {@code true} only if {@link #pin()}/{@link #rotate()} give a real
     * guarantee. Callers whose correctness depends on pinning MUST check this and
     * fall back to an injective scheme when it is {@code false}.
     */
    default boolean supportsPinnedHashing() {
        return false;
    }

    /**
     * Compressing wide hash for {@code seed.wide}. Folds the 64-bit seed down to 32
     * bits <i>before</i> the (injective) hash, so the baked constant {@code K} no
     * longer inverts to a unique seed: the fold has ~2^32 preimages, so reading one
     * guard constant yields ~2^32 candidate 64-bit seeds instead of exactly one.
     *
     * <p>The existing injective hash is reused unchanged — build-time/runtime match,
     * pinning and rotation all still hold — so this works for every implementation
     * without touching their candidate machinery.</p>
     *
     * @param salt when {@code true}, fold with the {@code H}-keyed multiplier
     *             {@code (L ^ H) * (H | 1)} — the high word {@code H} keys an odd,
     *             invertible multiplier applied to the low word {@code L} — instead of
     *             the linear xor-fold. Because integer multiply does not distribute
     *             over XOR, the output's differential is key-dependent: an analyst who
     *             knows {@code L} (the recoverable low 32 bits) cannot predict how the
     *             guard moves under a perturbation without also knowing the secret
     *             {@code H}. Still exactly 2^32 preimages (a bijection of {@code L} for
     *             each fixed {@code H}). Both folds are deterministic (build == runtime).
     */
    default SkiddedHash hashWide(final long starting, final BasicBlock vertex,
                                 final PredicateFlowGetter caller, final boolean salt) {
        return hash(
                HashTransformer.foldWide(starting, salt),
                vertex,
                v -> HashTransformer.foldWideExpr(caller, v, salt)
        );
    }

    /** Build-time 64-&gt;32 fold; must match {@link #foldWideExpr} bit-for-bit. */
    static int foldWide(final long seed, final boolean salt) {
        if (salt) {
            // H-keyed multiplier (mirror of foldWideExpr): out = (L ^ H) * (H | 1).
            // (H | 1) is odd => invertible mod 2^32, so out is a bijection of L for
            // each fixed H (still exactly 2^32 preimages over (L, H)). Multiply does
            // not distribute over XOR, so the differential depends on the secret H.
            final int low = (int) seed;            // L
            final int high = (int) (seed >>> 32);  // H
            return (low ^ high) * (high | 1);
        }
        // linear: xor the high and low 32-bit words (exactly 2^32 preimages)
        return (int) (seed ^ (seed >>> 32));
    }

    /** {@code (int)(seed >>> 32)} — the high 32-bit word, as a fresh runtime expression. */
    private static Expr highWordExpr(final PredicateFlowGetter caller, final BasicBlock vertex) {
        // seed >>> 32 resolves to LONG (resolveBinOpType(LONG, INT)), then L2I to the
        // high word. A fresh tree each call: nodes can't be shared across parents.
        return new CastExpr(
                new ArithmeticExpr(
                        new ConstantExpr(32, Type.INT_TYPE),
                        caller.getWide(vertex),
                        ArithmeticExpr.Operator.USHR
                ),
                Type.INT_TYPE
        );
    }

    /** Runtime 64-&gt;32 fold expression; reads the wide seed via {@link PredicateFlowGetter#getWide}. */
    static Expr foldWideExpr(final PredicateFlowGetter caller, final BasicBlock vertex, final boolean salt) {
        if (salt) {
            // H-keyed multiplier: out = (L ^ H) * (H | 1), with L = (int) seed and
            // H = (int)(seed >>> 32). (H | 1) is odd => bijection of L for each fixed
            // H. Multiply does not distribute over XOR, so an analyst who knows L
            // cannot predict the output's response to a perturbation without H.
            final Expr low = new CastExpr(caller.getWide(vertex), Type.INT_TYPE); // L
            final Expr xored = new ArithmeticExpr(            // L ^ H
                    highWordExpr(caller, vertex),
                    low,
                    ArithmeticExpr.Operator.XOR
            );
            final Expr oddKey = new ArithmeticExpr(          // H | 1
                    new ConstantExpr(1, Type.INT_TYPE),
                    highWordExpr(caller, vertex),
                    ArithmeticExpr.Operator.OR
            );
            return new ArithmeticExpr(                        // (L ^ H) * (H | 1)
                    oddKey,
                    xored,
                    ArithmeticExpr.Operator.MUL
            );
        }

        // (seed ^ (seed >>> 32)), then L2I
        final Expr hi = new ArithmeticExpr(
                new ConstantExpr(32, Type.INT_TYPE),
                caller.getWide(vertex),
                ArithmeticExpr.Operator.USHR
        );
        final Expr folded = new ArithmeticExpr(
                hi,
                caller.getWide(vertex),
                ArithmeticExpr.Operator.XOR
        );
        return new CastExpr(folded, Type.INT_TYPE);
    }
}
