package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.DispatchSignaturesClazz;

/**
 * Drives the return-wrapping mode of {@code SignatureObfuscationTransformer}:
 * eligible methods have both their argument list collapsed into
 * {@code (byte[], Object[])} and their return value wrapped into a
 * {@code byte[]}/{@code Object[]} carrier, with the threaded flow seed appended
 * as the trailing slot. The signature target round-trips every primitive width,
 * references, arrays and a void method; a verifier error in the rewritten body
 * or a divergent unpacked value throws and fails the test.
 */
public class SignatureReturnsTest extends SkidTest {

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
        return "/config/claude/signature_returns.hocon";
    }
}
