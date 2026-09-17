package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeIrVersions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompilerManifestCodecTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test
    void roundTripsWithCanonicalStableBytes() {
        final NativeArtifact windows = new NativeArtifact(
                NativeTarget.WINDOWS_X86_64,
                "META-INF/skidfuscator/native/build-7/windows-x86_64/skid.dll",
                SHA_B,
                42
        );
        final CompilerManifest original = new CompilerManifest(
                CompilerManifest.CURRENT_SCHEMA,
                NativeIrVersions.CURRENT_ABI,
                "build-7",
                SHA_A,
                Map.of(NativeTarget.WINDOWS_X86_64, windows)
        );
        final CompilerManifestCodec codec = new CompilerManifestCodec();

        final byte[] first = codec.encode(original);
        final CompilerManifest decoded = codec.decode(first);

        assertEquals(original, decoded);
        assertArrayEquals(first, codec.encode(decoded));
    }

    @Test
    void rejectsTraversalResourcePaths() {
        final CompilerManifest manifest = new CompilerManifest(
                CompilerManifest.CURRENT_SCHEMA,
                NativeIrVersions.CURRENT_ABI,
                "build-7",
                SHA_A,
                Map.of(NativeTarget.LINUX_X86_64, new NativeArtifact(
                        NativeTarget.LINUX_X86_64,
                        "META-INF/skidfuscator/native/build-7/linux-x86_64/../evil.so",
                        SHA_B,
                        1
                ))
        );

        assertThrows(ManifestValidationException.class, () -> new CompilerManifestCodec().encode(manifest));
    }

    @Test
    void rejectsUnboundedNativeArtifacts() {
        final CompilerManifest manifest = new CompilerManifest(
                CompilerManifest.CURRENT_SCHEMA, NativeIrVersions.CURRENT_ABI, "build-7", SHA_A,
                Map.of(NativeTarget.LINUX_X86_64, new NativeArtifact(
                        NativeTarget.LINUX_X86_64,
                        "META-INF/skidfuscator/native/build-7/linux-x86_64/libskid.so",
                        SHA_B, CompilerManifestValidator.MAX_ARTIFACT_BYTES + 1)));

        assertThrows(ManifestValidationException.class, () -> new CompilerManifestCodec().encode(manifest));
    }
}
