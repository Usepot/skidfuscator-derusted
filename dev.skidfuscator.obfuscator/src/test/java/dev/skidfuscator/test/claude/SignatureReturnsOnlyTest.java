package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.DispatchSignaturesClazz;

/**
 * Drives return wrapping in isolation (arguments left intact): only the return
 * value is wrapped into a {@code byte[]}/{@code Object[]} carrier and the
 * threaded seed appended. This covers the path where the descriptor keeps its
 * original parameters and the seed is read straight from the incoming trailing
 * int, with no argument-unpack prologue. The signature target round-trips every
 * primitive width, references, arrays and a void method.
 */
public class SignatureReturnsOnlyTest extends SkidTest {

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
        return "/config/claude/signature_returns_only.hocon";
    }
}
