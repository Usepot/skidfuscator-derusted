package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.PrimitiveNegationClazz;

public class PrimitiveNegationTest extends SkidTest {
    @Override public Class<? extends TestRun> getMainClass() { return PrimitiveNegationClazz.class; }
    @Override public Class<?>[] getClasses() { return new Class[]{PrimitiveNegationClazz.class}; }
    @Override public String getConfigPath() { return "/config/claude/wide_flow_dispatch.hocon"; }
}
