package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.MergeRecursiveClazz;

/**
 * Recursion through merged hosts under the full pipeline, ensuring the
 * intra-host self/mutual calls survive dispatch funnelling and flow obfuscation.
 */
public class CombinedRecursiveTest extends SkidTest {

    @Override
    public Class<? extends TestRun> getMainClass() {
        return MergeRecursiveClazz.class;
    }

    @Override
    public Class<?>[] getClasses() {
        return new Class[]{
                MergeRecursiveClazz.class
        };
    }

    @Override
    public String getConfigPath() {
        return "/config/claude/combined.hocon";
    }
}
