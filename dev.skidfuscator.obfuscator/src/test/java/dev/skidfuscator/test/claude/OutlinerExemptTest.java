package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.OutlinerRuntimeClazz;
import org.junit.jupiter.api.Assertions;

import java.util.List;
import java.util.Map;

public class OutlinerExemptTest extends SkidTest {
    @Override
    public Class<? extends TestRun> getMainClass() {
        return OutlinerRuntimeClazz.class;
    }

    @Override
    public Class<?>[] getClasses() {
        return new Class[]{
                OutlinerRuntimeClazz.class
        };
    }

    @Override
    public String getConfigPath() {
        return "/config/claude/outliner_exempt.hocon";
    }

    @Override
    public void receiveAndExecute(final List<Map.Entry<String, byte[]>> output) {
        super.receiveAndExecute(output);
        Assertions.assertFalse(OutlinerRuntimeTest.hasSyntheticOutlineHelper(output, OutlinerRuntimeClazz.class),
                "outliner.exempt should prevent synthetic helper emission for the fixture class");
    }
}
