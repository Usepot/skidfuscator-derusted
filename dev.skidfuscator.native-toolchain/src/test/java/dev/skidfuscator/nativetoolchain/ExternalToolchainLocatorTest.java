package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeIrVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalToolchainLocatorTest {
    @TempDir
    Path toolchainHome;

    @Test
    void readsOnlyTheManifestAndDriverUnderExplicitHome() throws Exception {
        final KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final SignedToolchainManifest signed = ToolchainManifestVerifierTest.sign(
                ToolchainManifestVerifierTest.manifest(), pair);
        Files.write(toolchainHome.resolve(ExternalToolchainLocator.MANIFEST_FILE_NAME),
                new ToolchainManifestCodec().encodeSigned(signed));
        final Path executable = ProcessNativeCompiler.executable(toolchainHome, NativeTarget.WINDOWS_X86_64);
        Files.createDirectories(executable.getParent());
        Files.write(executable, new byte[]{1});
        final ToolchainRequest request = new ToolchainRequest(
                ToolchainDelivery.EXTERNAL,
                Optional.of(toolchainHome),
                ToolchainManifest.PINNED_VERSION,
                NativeIrVersions.CURRENT_ABI,
                Set.of(NativeTarget.LINUX_X86_64),
                NativeTarget.WINDOWS_X86_64
        );

        final Optional<ToolchainCandidate> candidate = new ExternalToolchainLocator().locate(request);

        assertTrue(candidate.isPresent());
        assertEquals(toolchainHome.toAbsolutePath().normalize(), candidate.get().home());
        assertEquals(ToolchainSource.EXTERNAL, candidate.get().source());
    }
}
