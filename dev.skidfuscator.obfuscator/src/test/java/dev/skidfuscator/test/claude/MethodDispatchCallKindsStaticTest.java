package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.DispatchCallKindsClazz;

public class MethodDispatchCallKindsStaticTest extends SkidTest {

    @Override
    public Class<? extends TestRun> getMainClass() {
        return DispatchCallKindsClazz.class;
    }

    @Override
    public Class<?>[] getClasses() {
        return new Class[]{
                DispatchCallKindsClazz.class,
                DispatchCallKindsClazz.Greeter.class,
                DispatchCallKindsClazz.Base.class,
                DispatchCallKindsClazz.Derived.class
        };
    }

    @Override
    public String getConfigPath() {
        return "/config/claude/dispatch_static.hocon";
    }
}
