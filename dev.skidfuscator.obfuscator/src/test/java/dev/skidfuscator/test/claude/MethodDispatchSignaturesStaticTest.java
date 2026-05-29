package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.DispatchSignaturesClazz;

/**
 * STATIC_ONLY scope with maxPerDispatcher = 4 over the signature-heavy target's
 * ~14 static methods, so distinct targets spill across several dispatcher
 * methods (exercises {@code assignDispatchers} bucketing + per-bucket keys).
 */
public class MethodDispatchSignaturesStaticTest extends SkidTest {

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
        return "/config/claude/dispatch_static.hocon";
    }
}
