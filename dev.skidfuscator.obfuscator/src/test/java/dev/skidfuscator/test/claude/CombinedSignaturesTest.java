package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.DispatchSignaturesClazz;

/**
 * Runs the signature-heavy dispatch target through the full pipeline (merge +
 * dispatch + flow + encryption) to catch pass-ordering interactions.
 */
public class CombinedSignaturesTest extends SkidTest {

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
        return "/config/claude/combined.hocon";
    }
}
