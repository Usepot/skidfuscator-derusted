package dev.skidfuscator.obfuscator.predicate.opaque;

import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowSetter;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;

public interface BlockOpaquePredicate extends OpaquePredicate<SkidMethodNode> {
    int get(final SkidBlock t);

    /**
     * 64-bit projection of the per-block predicate. The low 32 bits equal
     * {@link #get(SkidBlock)} so int consumers (condition hashing, the (int)
     * projection getter) are unchanged; the high 32 bits carry the extra entropy
     * that is only threaded when {@code seed.wide} is enabled.
     */
    long getLong(final SkidBlock t);

    void set(final SkidBlock skidBlock, int value);

    @Override
    PredicateFlowSetter getSetter();
}
