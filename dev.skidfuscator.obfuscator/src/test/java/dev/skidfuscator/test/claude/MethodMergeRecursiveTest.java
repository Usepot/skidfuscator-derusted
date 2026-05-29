package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.MergeRecursiveClazz;

public class MethodMergeRecursiveTest extends SkidTest {

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
        return "/config/claude/merge.hocon";
    }
}
