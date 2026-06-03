package dev.skidfuscator.obfuscator.predicate.factory;

import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;

import java.util.function.Supplier;

public interface PredicateFlowGetter {
    Expr get(final BasicBlock vertex);

    /**
     * Wide (64-bit / {@code long}) projection of the flow value. Only meaningful
     * when the seed is threaded as a {@code long} ({@code seed.wide}); the default
     * delegates to {@link #get(BasicBlock)} so existing int-only getters are
     * unaffected and keep emitting a 32-bit value.
     */
    default Expr getWide(final BasicBlock vertex) {
        return get(vertex);
    }
}
