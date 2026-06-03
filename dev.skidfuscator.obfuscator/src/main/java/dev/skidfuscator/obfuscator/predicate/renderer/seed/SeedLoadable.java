package dev.skidfuscator.obfuscator.predicate.renderer.seed;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.number.NumberManager;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowSetter;
import dev.skidfuscator.obfuscator.predicate.opaque.BlockOpaquePredicate;
import dev.skidfuscator.obfuscator.predicate.renderer.seed.impl.InternalSeedLoaderRenderer;
import dev.skidfuscator.obfuscator.predicate.renderer.seed.impl.StaticSeedLoaderRenderer;
import dev.skidfuscator.obfuscator.predicate.renderer.seed.impl.SwitchSeedLoaderRenderer;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Stmt;

public interface SeedLoadable {

    SeedLoaderRenderer[] RENDERERS = new SeedLoaderRenderer[] {
            new StaticSeedLoaderRenderer(),
            new InternalSeedLoaderRenderer(),
            new SwitchSeedLoaderRenderer()
    };

    default void addSeedLoader(
            final SkidMethodNode methodNode,
            final SkidBlock block,
            final SkidBlock targetBlock,
            final int index,
            final BlockOpaquePredicate predicate,
            final int value,
            final String type
    ) {
        block.setFlag(SkidBlock.FLAG_BRIDGE, true);
        RENDERERS[RandomUtil.nextInt(RENDERERS.length)]
                .addSeedLoader(
                        methodNode,
                        block,
                        targetBlock,
                        index,
                        predicate,
                        value,
                        type
                );
    }

    /**
     * 64-bit counterpart of {@link #addSeedLoader} used on the {@code seed.wide} path.
     *
     * <p>The structural int loaders (static/switch/internal) only thread the low 32
     * bits — they leave the long flow local's high word as sign-extension — which is
     * fine for the int-projection guard but breaks the compressing guard (it folds
     * all 64 bits). Here we emit a uniform full-width transition
     * {@code flowLocal = (getLong(target) ^ value) ^ flowLocal} via the wide getter
     * so the live seed equals {@code getLong(currentBlock)} after every edge. {@code value}
     * must be the SOURCE block's 64-bit predicate (its high word matters now).</p>
     */
    default void addSeedLoaderWide(
            final SkidMethodNode methodNode,
            final SkidBlock block,
            final SkidBlock targetBlock,
            final int index,
            final BlockOpaquePredicate predicate,
            final long value,
            final String type
    ) {
        block.setFlag(SkidBlock.FLAG_BRIDGE, true);
        final PredicateFlowGetter getter = predicate.getGetter();
        final PredicateFlowSetter setter = predicate.getSetter();
        final Expr load = NumberManager.encryptLong(
                predicate.getLong(targetBlock),
                value,
                block,
                getter
        );
        final Stmt set = setter.apply(load);
        block.add(index < 0 ? block.size() : index, set);
    }
}
