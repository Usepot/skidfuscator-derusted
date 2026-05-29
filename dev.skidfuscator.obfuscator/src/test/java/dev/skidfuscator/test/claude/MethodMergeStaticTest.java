package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.MergeStaticClazz;

public class MethodMergeStaticTest extends SkidTest {

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
        return "/config/claude/merge.hocon";
    }
}
