package dev.skidfuscator.obfuscator.predicate.opaque.impl;

import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowSetter;
import dev.skidfuscator.obfuscator.predicate.opaque.BlockOpaquePredicate;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.asm.MethodNode;
import org.mapleir.ir.cfg.BasicBlock;

import java.util.HashMap;
import java.util.Map;

public class IntegerBlockOpaquePredicate implements BlockOpaquePredicate {
    private final MethodNode methodNode;
    private final Map<BasicBlock, Integer> predicateMap;
    private final Map<BasicBlock, Long> predicateLongMap;
    private PredicateFlowGetter getter;
    private PredicateFlowSetter setter;

    public IntegerBlockOpaquePredicate(MethodNode methodNode, PredicateFlowGetter getter) {
        this.methodNode = methodNode;
        this.predicateMap = new HashMap<>();
        this.predicateLongMap = new HashMap<>();
        this.getter = getter;
    }

    public IntegerBlockOpaquePredicate(final MethodNode methodNode) {
        this(methodNode, null);
    }

    @Override
    public PredicateFlowGetter getGetter() {
        return getter;
    }

    @Override
    public void setGetter(PredicateFlowGetter getter) {
        this.getter = getter;
    }

    @Override
    public PredicateFlowSetter getSetter() {
        return setter;
    }

    @Override
    public void setSetter(PredicateFlowSetter setter) {
        this.setter = setter;
    }

    @Override
    public int get(SkidBlock block) {
        return predicateMap.computeIfAbsent(block, e -> RandomUtil.nextInt());
    }

    @Override
    public long getLong(SkidBlock block) {
        // Low 32 bits == the existing int predicate (so the (int) projection is
        // unchanged for legacy consumers); high 32 bits are fresh entropy. Stable
        // per block via the cache so build-time constants match the runtime value.
        return predicateLongMap.computeIfAbsent(block,
                e -> (RandomUtil.nextLong() << 32) | (get(block) & 0xFFFFFFFFL));
    }

    @Override
    public void set(SkidBlock skidBlock, int value) {
        predicateMap.put(skidBlock, value);
    }
}
