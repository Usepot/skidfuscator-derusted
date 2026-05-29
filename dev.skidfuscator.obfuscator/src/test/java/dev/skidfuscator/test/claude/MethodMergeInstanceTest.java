package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.MergeInstanceClazz;

public class MethodMergeInstanceTest extends SkidTest {

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
        return "/config/claude/merge.hocon";
    }
}
