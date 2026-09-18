package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.*;

public class LambdaSamDispatchTest extends SkidTest {
    @Override public Class<? extends TestRun> getMainClass() { return LambdaSamClazz.class; }
    @Override public Class<?>[] getClasses() {
        return new Class[]{LambdaSamClazz.class, LambdaKeyListener.class, InheritedLambdaKeyListener.class};
    }
    @Override public String getConfigPath() { return "/config/claude/wide_flow_dispatch.hocon"; }
}
