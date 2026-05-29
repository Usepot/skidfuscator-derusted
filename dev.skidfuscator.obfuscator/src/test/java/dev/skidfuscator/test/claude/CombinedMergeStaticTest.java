package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.MergeStaticClazz;

/**
 * Runs the merge-heavy static target through the full pipeline. The merged hosts
 * are then themselves subject to dispatch funnelling and flow obfuscation.
 */
public class CombinedMergeStaticTest extends SkidTest {

    @Override
    public Class<? extends TestRun> getMainClass() {
        return MergeStaticClazz.class;
    }

    @Override
    public Class<?>[] getClasses() {
        return new Class[]{
                MergeStaticClazz.class
        };
    }

    @Override
    public String getConfigPath() {
        return "/config/claude/combined.hocon";
    }
}
