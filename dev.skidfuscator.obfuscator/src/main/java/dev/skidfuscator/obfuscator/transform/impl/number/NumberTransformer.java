package dev.skidfuscator.obfuscator.transform.impl.number;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.EventPriority;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.method.PostMethodTransformEvent;
import dev.skidfuscator.obfuscator.number.encrypt.impl.XorNumberTransformer;
import dev.skidfuscator.obfuscator.number.hash.HashTransformer;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.predicate.opaque.BlockOpaquePredicate;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidControlFlowGraph;
import dev.skidfuscator.obfuscator.skidasm.expr.SkidConstantExpr;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeConditionalJumpStmt;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.transform.Transformer;
import org.mapleir.ir.TypeUtils;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.CodeUnit;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ComparisonExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.stmt.ConditionalJumpStmt;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Number encryption + integer-condition hashing, fused into a single pass.
 *
 * <p>Both halves consume the same block flow-seed and both rewrite operands, so
 * running them as separate passes (in different phases) meant condition hashing
 * fired on the <i>clean</i> conditions and the seed-weaving that produces the
 * readable {@code K ^ seed} forms happened afterwards, outside the hash. Doing
 * them together, here in {@link PostMethodTransformEvent}, guarantees the hash
 * always wraps the seed-woven form.</p>
 *
 * <p>Conflict rule: a statement is either <b>hashed</b> (equality comparisons)
 * or has its constants <b>reseeded</b> (everything else) — never both — so the
 * two halves can't fight over the same expression.</p>
 *
 * <p>NOTE: the hash here is the injective VM hasher, so this hardens and
 * consolidates obfuscation but does not make the seed unrecoverable: it stays
 * invertible (injective by construction) and the live seed is still materialised
 * in cleartext wherever it is used as key material (e.g. string decryption). True
 * "must trace from main" secrecy requires decoupling the cipher key from the
 * routing seed, which this pass does not attempt.</p>
 */
public class NumberTransformer extends AbstractTransformer {
    public NumberTransformer(Skidfuscator skidfuscator) {
        this(skidfuscator, Collections.emptyList());
    }

    public NumberTransformer(Skidfuscator skidfuscator, List<Transformer> children) {
        super(skidfuscator, "Number Encryption", children);
    }

    private static final Set<Type> TYPES = new HashSet<>(Arrays.asList(
            Type.INT_TYPE,
            Type.SHORT_TYPE,
            Type.BYTE_TYPE,
            Type.CHAR_TYPE
    ));

    @Listen(EventPriority.LOW)
    void handle(final PostMethodTransformEvent event) {
        final SkidMethodNode methodNode = event.getMethodNode();

        if (shouldSkipMethod(methodNode)) {
            this.skip();
            return;
        }

        if (methodNode.node.instructions.size() > 10000) {
            this.fail();
            return;
        }

        final SkidControlFlowGraph cfg = methodNode.getCfg();

        if (cfg == null) {
            this.fail();
            return;
        }

        // May be null very early / in degenerate setups; if so we silently fall
        // back to reseed-only so we never emit a half-hashed comparison.
        final HashTransformer hasher = skidfuscator.getVmHasher();
        final boolean seedWide = skidfuscator.getConfig().isSeedWide();

        for (BasicBlock vertex : new HashSet<>(cfg.vertices())) {
            if (vertex.isFlagSet(SkidBlock.FLAG_NO_OPAQUE))
                continue;

            if (methodNode.isClinit() && this.heuristicSizeSkip(methodNode, 8.f)) {
                continue;
            }

            final SkidBlock skidBlock = (SkidBlock) vertex;
            final BlockOpaquePredicate flowPredicate = methodNode.getFlowPredicate();
            final int predicate = flowPredicate.get(skidBlock);
            final PredicateFlowGetter getter = flowPredicate.getGetter();

            for (Stmt stmt : new HashSet<>(vertex)) {
                /*
                 * (1) Equality comparison -> hash both sides, salted with the live
                 *     block seed. This wraps the seed-woven value in the hash
                 *     instead of leaving a bare `K ^ seed` operand for step (2) to
                 *     produce. We then skip (2) for this statement (the `continue`)
                 *     so the two halves never touch the same expression.
                 */
                if (hasher != null && getter != null && isHashableComparison(stmt)) {
                    hashComparison(hasher, skidBlock, (ConditionalJumpStmt) stmt, getter, predicate);
                    continue;
                }

                /*
                 * (2) Everything else -> reseed each int-category constant with the
                 *     block seed (the original Number Encryption behaviour).
                 */
                for (Expr expr : stmt.enumerateOnlyChildren()) {
                    if (!(expr instanceof SkidConstantExpr))
                        continue;

                    final ConstantExpr constantExpr = (ConstantExpr) expr;

                    if (TYPES.contains(constantExpr.getType())) {
                        final CodeUnit parent = constantExpr.getParent();
                        final int value = ((Number) constantExpr.getConstant()).intValue();
                        final Expr modified = new XorNumberTransformer().getNumber(
                                value,
                                predicate,
                                skidBlock,
                                getter
                        );

                        parent.overwrite(constantExpr, modified);
                    } else if (seedWide && constantExpr.getType() == Type.LONG_TYPE) {
                        /*
                         * Wide path: the interprocedural call-site seed argument is a
                         * long SkidConstantExpr. Reseed it with the 64-bit block seed
                         * (getWide()) so the public seed is never left bare at the
                         * call site — a bare long constant would leak the high entropy
                         * and defeat seed.wide. Evaluates back to the original value.
                         */
                        final CodeUnit parent = constantExpr.getParent();
                        final long value = ((Number) constantExpr.getConstant()).longValue();
                        final Expr modified = new XorNumberTransformer().getNumberLong(
                                value,
                                flowPredicate.getLong(skidBlock),
                                skidBlock,
                                getter
                        );

                        parent.overwrite(constantExpr, modified);
                    }
                }
            }
        }

        this.success();
    }

    /**
     * Hash both sides of one equality comparison. A single hash function is pinned
     * for the whole site (so a lossy mask computed at build time matches the
     * runtime expression) and rotated afterwards (so no function is reused across
     * sites — an analyst who reverses one learns nothing about the next).
     *
     * <p>{0,1}-domain comparisons (booleans, the {@code isValid()} checks) get a
     * lossy masked hash; everything else gets the plain injective hash. Any lossy
     * uncertainty falls back to injective, so the emitted branch is always
     * correct.</p>
     */
    private void hashComparison(final HashTransformer hasher,
                                final SkidBlock block,
                                final ConditionalJumpStmt jump,
                                final PredicateFlowGetter getter,
                                final int predicate) {
        final boolean canPin = hasher.supportsPinnedHashing();

        if (canPin) {
            hasher.pin();
        }

        try {
            if (canPin
                    && isBit0Domain(jump.getLeft())
                    && isBit0Domain(jump.getRight())
                    && tryLossy(hasher, block, jump, getter, predicate)) {
                return;
            }

            jump.setLeft(hashOperand(hasher, block, jump.getLeft(), getter));
            jump.setRight(hashOperand(hasher, block, jump.getRight(), getter));
        } finally {
            // Always release the pin (even on the fallback path) so later consumers
            // and the next site never inherit a frozen hasher.
            if (canPin) {
                hasher.rotate();
            }
        }
    }

    /**
     * Lossy masked hash for a {0,1}-domain comparison. Both sides become
     * {@code hash(operand ^ seed) & mask}, where {@code mask} is the single lowest
     * bit on which the two possible seeded values ({@code predicate} and
     * {@code predicate ^ 1}) disagree under the pinned function. That one bit
     * decides the branch (so it stays correct), while the remaining 31 bits of the
     * hash are discarded — so the surviving constant no longer inverts to the seed.
     *
     * @return {@code false} without touching the jump if it cannot prove a safe
     *         mask, so the caller falls back to the injective path.
     */
    private boolean tryLossy(final HashTransformer hasher,
                             final SkidBlock block,
                             final ConditionalJumpStmt jump,
                             final PredicateFlowGetter getter,
                             final int predicate) {
        final int mask;
        try {
            final int hLo = hasher.hash(predicate);
            final int hHi = hasher.hash(predicate ^ 1);
            mask = Integer.lowestOneBit(hLo ^ hHi);
        } catch (RuntimeException pinnedFailure) {
            // Pinned function misbehaved on these inputs -> injective fallback.
            return false;
        }

        // hLo == hHi (mask 0) means the function failed to separate the two seeded
        // values; never emit an ambiguous branch.
        if (mask == 0) {
            return false;
        }

        jump.setLeft(lossyHashOperand(hasher, block, jump.getLeft(), getter, mask));
        jump.setRight(lossyHashOperand(hasher, block, jump.getRight(), getter, mask));
        return true;
    }

    /** True if {@code expr} is provably in {0, 1} (boolean-typed, or a 0/1 constant). */
    private boolean isBit0Domain(final Expr expr) {
        if (expr.getType() == Type.BOOLEAN_TYPE) {
            return true;
        }
        if (expr instanceof ConstantExpr && ((ConstantExpr) expr).getConstant() instanceof Number) {
            final int value = ((Number) ((ConstantExpr) expr).getConstant()).intValue();
            return value == 0 || value == 1;
        }
        return false;
    }

    private Expr lossyHashOperand(final HashTransformer hasher,
                                  final BasicBlock block,
                                  final Expr operand,
                                  final PredicateFlowGetter getter,
                                  final int mask) {
        final Expr salted = new ArithmeticExpr(
                copyOperand(operand),
                getter.get(block),
                ArithmeticExpr.Operator.XOR
        );
        final Expr hashed = hasher.hash(block, vertex -> salted.copy());
        return new ArithmeticExpr(
                hashed,
                new ConstantExpr(mask, Type.INT_TYPE),
                ArithmeticExpr.Operator.AND
        );
    }

    /**
     * Only int-stack equality comparisons are hashable. {@code resolveBinOpType}
     * already collapses boolean/byte/char/short to INT, so booleans are included
     * (that is intentional — the {@code isValid()} style checks are the ones worth
     * salting). Fake (already-synthetic) jumps and nested comparison operands are
     * left alone.
     */
    private boolean isHashableComparison(final Stmt stmt) {
        if (!(stmt instanceof ConditionalJumpStmt) || stmt instanceof FakeConditionalJumpStmt) {
            return false;
        }

        final ConditionalJumpStmt jump = (ConditionalJumpStmt) stmt;
        final ConditionalJumpStmt.ComparisonType type = jump.getComparisonType();

        if (type != ConditionalJumpStmt.ComparisonType.EQ
                && type != ConditionalJumpStmt.ComparisonType.NE) {
            return false;
        }

        if (TypeUtils.resolveBinOpType(jump.getLeft().getType(), jump.getRight().getType()) != Type.INT_TYPE) {
            return false;
        }

        return !(jump.getLeft() instanceof ComparisonExpr) && !(jump.getRight() instanceof ComparisonExpr);
    }

    /**
     * {@code hash((operand ^ seed))}. Injective, so {@code hash(a) == hash(b)} iff
     * {@code a == b} and the branch semantics are preserved for any operand value.
     * The operand is read exactly once (single copy into the salt) so this is safe
     * even when the operand is a side-effecting call such as {@code isValid()}.
     */
    private Expr hashOperand(final HashTransformer hasher,
                             final BasicBlock block,
                             final Expr operand,
                             final PredicateFlowGetter getter) {
        final Expr salted = new ArithmeticExpr(
                copyOperand(operand),
                getter.get(block),
                ArithmeticExpr.Operator.XOR
        );

        return hasher.hash(block, vertex -> salted.copy());
    }

    private Expr copyOperand(final Expr expr) {
        if (expr instanceof ConstantExpr && ((ConstantExpr) expr).getConstant() instanceof Number) {
            return new SkidConstantExpr(((Number) ((ConstantExpr) expr).getConstant()).intValue(), Type.INT_TYPE);
        }

        return expr.copy();
    }
}
