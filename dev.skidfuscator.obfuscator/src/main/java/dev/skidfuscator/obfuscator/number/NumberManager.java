package dev.skidfuscator.obfuscator.number;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.number.encrypt.LongNumberTransformer;
import dev.skidfuscator.obfuscator.number.encrypt.NumberTransformer;
import dev.skidfuscator.obfuscator.number.encrypt.impl.AddSubNumberTransformer;
import dev.skidfuscator.obfuscator.number.encrypt.impl.FeistelNumberTransformer;
import dev.skidfuscator.obfuscator.number.encrypt.impl.MultiplyNumberTransformer;
import dev.skidfuscator.obfuscator.number.encrypt.impl.RotateNumberTransformer;
import dev.skidfuscator.obfuscator.number.encrypt.impl.XorNumberTransformer;
import dev.skidfuscator.obfuscator.number.hash.HashTransformer;
import dev.skidfuscator.obfuscator.number.hash.SkiddedHash;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;

/**
 * @author Ghast
 * @since 09/03/2021
 * SkidfuscatorV2 © 2021
 */
public class NumberManager {
    private static final NumberTransformer[] TRANSFORMERS = {
            //new DebugNumberTransformer(),
            new XorNumberTransformer(),
            new AddSubNumberTransformer(),
            new RotateNumberTransformer(),
            new MultiplyNumberTransformer(),
            new FeistelNumberTransformer(),
            //new RandomShiftNumberTransformer()
    };

    private static final LongNumberTransformer[] LONG_TRANSFORMERS = {
            new XorNumberTransformer(),
            new AddSubNumberTransformer(),
            new RotateNumberTransformer(),
            new MultiplyNumberTransformer(),
            new FeistelNumberTransformer()
    };

    public static Expr encrypt(final int outcome, final int starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        return TRANSFORMERS[RandomUtil.nextInt(TRANSFORMERS.length)]
                .getNumber(outcome, starting, vertex, startingExpr);
    }

    public static Expr encryptLong(final long outcome, final long starting, final BasicBlock vertex, final PredicateFlowGetter startingExpr) {
        return LONG_TRANSFORMERS[RandomUtil.nextInt(LONG_TRANSFORMERS.length)]
                .getNumberLong(outcome, starting, vertex, startingExpr);
    }

    public static SkiddedHash hash(final Skidfuscator skidfuscator, final int starting, final BasicBlock vertex, final PredicateFlowGetter local) {
        return randomHasher(skidfuscator).hash(starting, vertex, local);
    }

    public static HashTransformer randomHasher(final Skidfuscator skidfuscator) {
        if (skidfuscator.getVmHasher() != null) {
            return skidfuscator.getVmHasher();
        }
        if (skidfuscator.getBitwiseHasher() != null) {
            return skidfuscator.getBitwiseHasher();
        }
        throw new IllegalStateException("No hash transformer has been initialized");
    }
}
