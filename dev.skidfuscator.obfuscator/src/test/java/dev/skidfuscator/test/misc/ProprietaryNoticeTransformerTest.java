package dev.skidfuscator.test.misc;

import dev.skidfuscator.core.TestSkidfuscator;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import dev.skidfuscator.obfuscator.transform.Transformer;
import dev.skidfuscator.obfuscator.transform.impl.misc.ProprietaryNoticeTransformer;
import dev.skidfuscator.testclasses.norename.NoRename;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProprietaryNoticeTransformerTest {
    private static final String NOTICE =
            "CUSTOM PROPRIETARY NOTICE: cracking this bytecode is cracking proprietary software.";

    @Test
    public void testTransformerIsDisabledWhenConfigIsAbsent() {
        final ConfigOnlySkidfuscator skidfuscator = new ConfigOnlySkidfuscator("/config/proprietary_notice_disabled.hocon");
        skidfuscator._importConfig();

        assertFalse(skidfuscator.getTransformers()
                .stream()
                .anyMatch(ProprietaryNoticeTransformer.class::isInstance));
    }

    @Test
    public void testTransformerIsWiredIntoDefaultListWhenEnabled() {
        final ConfigOnlySkidfuscator skidfuscator = new ConfigOnlySkidfuscator("/config/proprietary_notice.hocon");
        skidfuscator._importConfig();

        assertTrue(skidfuscator.getTransformers()
                .stream()
                .anyMatch(ProprietaryNoticeTransformer.class::isInstance));
    }

    @Test
    public void testNoticeIsWrittenToEveryOutputClass() {
        final AtomicReference<List<Map.Entry<String, byte[]>>> output = new AtomicReference<>();
        final Skidfuscator skidfuscator = new ProprietaryNoticeOnlySkidfuscator(
                new Class<?>[] { NoRename.class },
                output::set
        );

        skidfuscator.run();

        final List<Map.Entry<String, byte[]>> classes = output.get();
        assertNotNull(classes);
        assertFalse(classes.isEmpty());

        for (Map.Entry<String, byte[]> entry : classes) {
            final org.objectweb.asm.tree.ClassNode classNode = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(entry.getValue()).accept(classNode, 0);

            assertNotNull(classNode.sourceDebug, entry.getKey());
            assertTrue(classNode.sourceDebug.contains(NOTICE), entry.getKey());
        }
    }

    private static final class ProprietaryNoticeOnlySkidfuscator extends TestSkidfuscator {
        private ProprietaryNoticeOnlySkidfuscator(
                final Class<?>[] test,
                final java.util.function.Consumer<List<Map.Entry<String, byte[]>>> callback
        ) {
            super(test, callback, "/config/proprietary_notice.hocon");
        }

        @Override
        public List<Transformer> getTransformers() {
            return Collections.singletonList(new ProprietaryNoticeTransformer(this));
        }
    }

    private static final class ConfigOnlySkidfuscator extends Skidfuscator {
        private ConfigOnlySkidfuscator(final String config) {
            super(SkidfuscatorSession.builder()
                    .config(new File(ProprietaryNoticeTransformerTest.class
                            .getResource(config)
                            .getFile()))
                    .build());
        }
    }
}
