package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.DispatchSignaturesClazz;

public class MethodDispatchSignaturesAppTest extends SkidTest {

    @Override
    public Class<? extends TestRun> getMainClass() {
        return DispatchSignaturesClazz.class;
    }

    @Override
    public Class<?>[] getClasses() {
        return new Class[]{
                DispatchSignaturesClazz.class
        };
    }

    @Override
    public String getConfigPath() {
        return "/config/claude/dispatch_app.hocon";
    }
}
