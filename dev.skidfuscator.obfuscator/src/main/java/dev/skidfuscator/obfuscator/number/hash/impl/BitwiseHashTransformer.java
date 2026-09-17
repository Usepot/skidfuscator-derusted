package dev.skidfuscator.obfuscator.number.hash.impl;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.number.hash.HashTransformer;
import dev.skidfuscator.obfuscator.number.hash.SkiddedHash;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.builder.SkidMethodNodeBuilder;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidControlFlowGraph;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeArithmeticExpr;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.stmt.ReturnStmt;
import org.mapleir.ir.locals.Local;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class BitwiseHashTransformer implements HashTransformer {
    private final SkidMethodNode methodNode;

    public BitwiseHashTransformer(Skidfuscator skidfuscator) {
        this.methodNode = new SkidMethodNodeBuilder(skidfuscator, skidfuscator.getFactoryNode())
                .desc("(I)I")
                .name(RandomUtil.randomAlphabeticalString(16))
                .access(Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)
                .phantom(true)
                .build();

        final SkidControlFlowGraph cfg = methodNode.getCfg();

        // Rotate left by three: the logical shift preserves the upper bits
        // without sign extension, making this a permutation of all int values.
        final Expr shiftedHashTto29 = new FakeArithmeticExpr(
                new VarExpr(cfg.getLocals().get(0), Type.INT_TYPE),
                new ConstantExpr(29, Type.INT_TYPE),
                ArithmeticExpr.Operator.USHR
        );

        // (starting << 3)
        final Expr const3 = new ConstantExpr(3, Type.INT_TYPE);
        final Expr shiftedStartTo3 = new FakeArithmeticExpr(
                new VarExpr(cfg.getLocals().get(0), Type.INT_TYPE),
                const3,
                ArithmeticExpr.Operator.SHL
        );

        // (starting >>> 29) | (starting << 3);
        final Expr hash = new FakeArithmeticExpr(shiftedHashTto29, shiftedStartTo3, ArithmeticExpr.Operator.OR);

        methodNode.getCfg().getEntry().add(new ReturnStmt(
                Type.INT_TYPE,
                hash
        ));
        methodNode.node.maxLocals = 1;
    }

    @Override
    public SkiddedHash hash(int starting, final BasicBlock vertex, PredicateFlowGetter caller) {
        final int hashed = hash(starting);
        final Expr hash = hash(vertex, caller);
        return new SkiddedHash(hash, hashed);
    }

    @Override
    public int hash(int starting) {
        return Integer.rotateLeft(starting, 3);
    }

    @Override
    public Expr hash(final BasicBlock vertex, PredicateFlowGetter caller) {
        return new StaticInvocationExpr(
                new Expr[] {caller.get(vertex)},
                methodNode.getOwner(),
                methodNode.getName(),
                methodNode.getDesc()
        );
    }
}
