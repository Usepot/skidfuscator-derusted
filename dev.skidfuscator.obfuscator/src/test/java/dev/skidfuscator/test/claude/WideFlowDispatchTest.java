package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.WideFlowDispatchClazz;

/** Regression for wide interprocedural seed + flow + dispatch + indy. */
public class WideFlowDispatchTest extends SkidTest {
    @Override public Class<? extends TestRun> getMainClass() { return WideFlowDispatchClazz.class; }
    @Override public Class<?>[] getClasses() { return new Class[]{WideFlowDispatchClazz.class}; }
    @Override public String getConfigPath() { return "/config/claude/wide_flow_dispatch.hocon"; }
}
