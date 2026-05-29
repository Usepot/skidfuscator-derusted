package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.MergeInstanceClazz;

/**
 * Same target as {@link MethodMergeInstanceTest} but with maxPerHost = 2, so the
 * four-member int bucket is split across multiple hosts.
 */
public class MethodMergeInstanceSplitTest extends SkidTest {

    @Override
    public Class<? extends TestRun> getMainClass() {
        return MergeInstanceClazz.class;
    }

    @Override
    public Class<?>[] getClasses() {
        return new Class[]{
                MergeInstanceClazz.class
        };
    }

    @Override
    public String getConfigPath() {
        return "/config/claude/merge_split.hocon";
    }
}
