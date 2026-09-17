package dev.skidfuscator.config.nativeobfuscation;

import com.typesafe.config.ConfigFactory;
import dev.skidfuscator.config.DefaultSkidConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeConfigTest {
    @Test
    void bundledConfigurationContainsNativeDefaults() throws IOException {
        try (InputStream resource = getClass().getResourceAsStream("/defaultConfig.hocon")) {
            final String template = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("%%INJECT%%", "");
            final NativeConfig config = new DefaultSkidConfig(
                    ConfigFactory.parseString(template).resolve(),
                    ""
            ).getNativeConfig();

            assertFalse(config.isEnabled());
            assertEquals("1.0.0-alpha.1", config.getToolchainConfig().getVersion());
            assertEquals(Arrays.asList(NativeTarget.values()), config.getTargets());
        }
    }

    @Test
    void usesSafeNativeDefaults() {
        final NativeConfig config = new DefaultSkidConfig(ConfigFactory.empty(), "").getNativeConfig();

        assertFalse(config.isEnabled());
        assertEquals(NativeMode.AOT, config.getDefaultMode());
        assertEquals(Arrays.asList(NativeTarget.values()), config.getTargets());
        assertEquals(NativeArtifactMode.BOTH, config.getArtifactMode());
        assertEquals(NativeToolchainDelivery.AUTO, config.getToolchainConfig().getDelivery());
        assertEquals("1.0.0-alpha.1", config.getToolchainConfig().getVersion());
        assertEquals(1, config.getToolchainConfig().getNativeIrAbi());
        assertEquals(NativeVmProfile.AGGRESSIVE, config.getVmConfig().getProfile());
        assertEquals(NativeVmResponse.DELAYED_HALT, config.getVmConfig().getResponse());
        assertTrue(config.getVmConfig().isIntegrityEnabled());
    }

    @Test
    void parsesTargetsAndOrderedRules() {
        final NativeConfig config = new DefaultSkidConfig(ConfigFactory.parseString(
                "native {\n" +
                        "  enabled = true\n" +
                        "  targets = [linux-x86_64, windows-aarch64, linux-x86_64]\n" +
                        "  rules = [\n" +
                        "    { match = \"method{alpha}\", mode = AOT },\n" +
                        "    { match = \"method{beta}\", mode = VM }\n" +
                        "  ]\n" +
                        "}\n"
        ), "").getNativeConfig();

        assertTrue(config.isEnabled());
        assertEquals(
                Arrays.asList(NativeTarget.LINUX_X86_64, NativeTarget.WINDOWS_AARCH64),
                config.getTargets()
        );
        assertEquals(NativeMode.AOT, config.getRules().get(0).getMode());
        assertEquals(NativeMode.VM, config.findLastMatchingRule(expression -> true).orElseThrow().getMode());
    }

    @Test
    void rejectsUnknownTargets() {
        final NativeConfig config = new DefaultSkidConfig(
                ConfigFactory.parseString("native.targets = [plan9-mips]"),
                ""
        ).getNativeConfig();

        assertThrows(IllegalArgumentException.class, config::getTargets);
    }

    @Test
    void rejectsEmptyTargetsAndRecursiveDefaultMode() {
        final NativeConfig emptyTargets = new DefaultSkidConfig(
                ConfigFactory.parseString("native.targets = []"),
                ""
        ).getNativeConfig();
        final NativeConfig defaultMode = new DefaultSkidConfig(
                ConfigFactory.parseString("native.defaultMode = DEFAULT"),
                ""
        ).getNativeConfig();

        assertThrows(IllegalArgumentException.class, emptyTargets::getTargets);
        assertThrows(IllegalArgumentException.class, defaultMode::getDefaultMode);
    }
}
