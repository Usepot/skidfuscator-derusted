package dev.skidfuscator.obfuscator.nativebackend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativePipelineLifecycleTest {
    @Test
    void resolvesOnlyVersionConfinedHttpsReleaseManifests() {
        assertEquals(
                "https://github.com/skidfuscatordev/SkidLLVM/releases/download/v1.0.0-alpha.1/"
                        + "skidllvm-toolchain.manifest",
                NativePipeline.releaseManifestUris().resolve("1.0.0-alpha.1").toString());
        assertThrows(IllegalArgumentException.class,
                () -> NativePipeline.releaseManifestUris().resolve("../untrusted"));
        assertThrows(IllegalArgumentException.class,
                () -> NativePipeline.releaseManifestUris().resolve("release?redirect=evil"));
    }
}
