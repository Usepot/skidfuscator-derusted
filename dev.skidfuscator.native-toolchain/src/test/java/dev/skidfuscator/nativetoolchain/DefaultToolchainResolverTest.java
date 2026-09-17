package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeIrVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultToolchainResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void autoUsesExplicitThenBundledAndStopsAfterAuthenticatedCandidate() throws Exception {
        final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final SignedToolchainManifest signed = ToolchainManifestVerifierTest.sign(
                ToolchainManifestVerifierTest.manifest(), keyPair);
        final List<ToolchainSource> calls = new ArrayList<>();
        final ToolchainLocator external = locator(ToolchainSource.EXTERNAL, calls, Optional.empty());
        final ToolchainCandidate bundledCandidate = new ToolchainCandidate(
                temporaryDirectory.resolve("bundled"), ToolchainSource.BUNDLED, signed);
        final Path bundledDriver = ProcessNativeCompiler.executable(
                bundledCandidate.home(), NativeTarget.WINDOWS_X86_64);
        Files.createDirectories(bundledDriver.getParent());
        Files.write(bundledDriver, new byte[]{1});
        final ToolchainLocator bundled = locator(ToolchainSource.BUNDLED, calls, Optional.of(bundledCandidate));
        final ToolchainLocator cache = locator(ToolchainSource.CACHE, calls, Optional.of(new ToolchainCandidate(
                temporaryDirectory.resolve("cache"), ToolchainSource.CACHE, signed)));
        final ToolchainManifestVerifier verifier = new ToolchainManifestVerifier(
                keyId -> Optional.of(keyPair.getPublic()));
        final DefaultToolchainResolver resolver = new DefaultToolchainResolver(
                List.of(cache, bundled, external), verifier);
        final ToolchainRequest request = new ToolchainRequest(
                ToolchainDelivery.AUTO,
                Optional.of(temporaryDirectory.resolve("explicit")),
                ToolchainManifest.PINNED_VERSION,
                NativeIrVersions.CURRENT_ABI,
                Set.of(NativeTarget.LINUX_AARCH64),
                NativeTarget.WINDOWS_X86_64
        );

        final ResolvedToolchain resolved = resolver.resolve(request);

        assertEquals(ToolchainSource.BUNDLED, resolved.source());
        assertEquals(List.of(ToolchainSource.EXTERNAL, ToolchainSource.BUNDLED), calls);
    }

    @Test
    void rejectsAnInstalledDriverWhichDoesNotMatchTheSignedDigest() throws Exception {
        final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final SignedToolchainManifest signed = ToolchainManifestVerifierTest.sign(
                ToolchainManifestVerifierTest.manifest(), keyPair);
        final Path home = temporaryDirectory.resolve("tampered");
        final Path driver = ProcessNativeCompiler.executable(home, NativeTarget.WINDOWS_X86_64);
        Files.createDirectories(driver.getParent());
        Files.write(driver, new byte[]{2});
        final ToolchainLocator external = locator(
                ToolchainSource.EXTERNAL,
                new ArrayList<>(),
                Optional.of(new ToolchainCandidate(home, ToolchainSource.EXTERNAL, signed))
        );
        final DefaultToolchainResolver resolver = new DefaultToolchainResolver(
                List.of(external),
                new ToolchainManifestVerifier(keyId -> Optional.of(keyPair.getPublic()))
        );
        final ToolchainRequest request = new ToolchainRequest(
                ToolchainDelivery.EXTERNAL,
                Optional.of(home),
                ToolchainManifest.PINNED_VERSION,
                NativeIrVersions.CURRENT_ABI,
                Set.of(NativeTarget.LINUX_AARCH64),
                NativeTarget.WINDOWS_X86_64
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                ManifestValidationException.class,
                () -> resolver.resolve(request)
        );
    }

    @Test
    void autoAlwaysUsesExplicitBundleVerifiedCacheThenDownloadOrder() throws Exception {
        final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final SignedToolchainManifest signed = ToolchainManifestVerifierTest.sign(
                ToolchainManifestVerifierTest.manifest(), keyPair);
        final List<ToolchainSource> calls = new ArrayList<>();
        final Path downloadedHome = temporaryDirectory.resolve("downloaded");
        final Path driver = ProcessNativeCompiler.executable(downloadedHome, NativeTarget.WINDOWS_X86_64);
        Files.createDirectories(driver.getParent());
        Files.write(driver, new byte[]{1});
        final DefaultToolchainResolver resolver = new DefaultToolchainResolver(List.of(
                locator(ToolchainSource.DOWNLOAD, calls, Optional.of(
                        new ToolchainCandidate(downloadedHome, ToolchainSource.DOWNLOAD, signed))),
                locator(ToolchainSource.CACHE, calls, Optional.empty()),
                locator(ToolchainSource.EXTERNAL, calls, Optional.empty()),
                locator(ToolchainSource.BUNDLED, calls, Optional.empty())
        ), new ToolchainManifestVerifier(keyId -> Optional.of(keyPair.getPublic())));

        final ResolvedToolchain resolved = resolver.resolve(new ToolchainRequest(
                ToolchainDelivery.AUTO,
                Optional.of(temporaryDirectory.resolve("explicit")),
                ToolchainManifest.PINNED_VERSION,
                NativeIrVersions.CURRENT_ABI,
                Set.of(NativeTarget.LINUX_X86_64),
                NativeTarget.WINDOWS_X86_64
        ));

        assertEquals(ToolchainSource.DOWNLOAD, resolved.source());
        assertEquals(List.of(ToolchainSource.EXTERNAL, ToolchainSource.BUNDLED,
                ToolchainSource.CACHE, ToolchainSource.DOWNLOAD), calls);
    }

    private ToolchainLocator locator(
            final ToolchainSource source,
            final List<ToolchainSource> calls,
            final Optional<ToolchainCandidate> result
    ) {
        return new ToolchainLocator() {
            @Override
            public ToolchainSource source() {
                return source;
            }

            @Override
            public Optional<ToolchainCandidate> locate(final ToolchainRequest request) {
                calls.add(source);
                return result;
            }
        };
    }
}
