package dev.skidfuscator.obfuscator.number.encrypt;

import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;

public interface LongNumberTransformer extends NumberTransformer {
    Expr getNumberLong(final long outcome, final long starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr);
}
