package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeIrVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolchainDeliveryLocatorsTest {
    private static final NativeTarget HOST = NativeTarget.WINDOWS_X86_64;

    @TempDir
    Path temporaryDirectory;

    @Test
    void installsBundleThenSurfacesOnlyAnAuthenticatedCacheEntry() throws Exception {
        final Fixture fixture = fixture("bundle.zip", zip("bin/skidllvm.exe", new byte[]{1}));
        final Path resources = temporaryDirectory.resolve("resources");
        final String manifestResource = ToolchainResourcePaths.manifest(ToolchainManifest.PINNED_VERSION);
        final String archiveResource = ToolchainResourcePaths.archive(
                ToolchainManifest.PINNED_VERSION, HOST, fixture.signed().manifest().hostArchives().get(HOST));
        writeResource(resources, manifestResource, new ToolchainManifestCodec().encodeSigned(fixture.signed()));
        writeResource(resources, archiveResource, fixture.archiveBytes());
        final Path cache = temporaryDirectory.resolve("cache");

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, null)) {
            final ToolchainCandidate bundled = new BundledToolchainLocator(loader, cache, fixture.verifier())
                    .locate(request()).orElseThrow();
            assertEquals(ToolchainSource.BUNDLED, bundled.source());

            final Optional<ToolchainCandidate> cached = new CacheToolchainLocator(cache, fixture.verifier())
                    .locate(request());
            assertTrue(cached.isPresent());
            assertEquals(ToolchainSource.CACHE, cached.get().source());

            Files.write(ProcessNativeCompiler.executable(cached.get().home(), HOST), new byte[]{2});
            assertTrue(new CacheToolchainLocator(cache, fixture.verifier()).locate(request()).isEmpty());
        }
    }

    @Test
    void downloadReplacesATamperedCacheOnlyAfterCompleteVerification() throws Exception {
        final byte[] archiveBytes = zip("bin/skidllvm.exe", new byte[]{1});
        final Fixture fixture = fixture("download.zip", archiveBytes);
        final Path repository = temporaryDirectory.resolve("repository");
        Files.createDirectories(repository);
        final Path archive = repository.resolve("download.zip");
        Files.write(archive, archiveBytes);
        final SignedToolchainManifest downloadable = withArchiveUri(fixture, archive.toUri());
        final Path manifest = repository.resolve(ExternalToolchainLocator.MANIFEST_FILE_NAME);
        Files.write(manifest, new ToolchainManifestCodec().encodeSigned(downloadable));
        final Path cache = temporaryDirectory.resolve("download-cache");
        final ToolchainManifestVerifier verifier = new ToolchainManifestVerifier(
                id -> Optional.of(fixture.keyPair().getPublic()));
        final DownloadToolchainLocator locator = new DownloadToolchainLocator(
                cache, version -> manifest.toUri(), verifier);

        final ToolchainCandidate first = locator.locate(request()).orElseThrow();
        Files.write(ProcessNativeCompiler.executable(first.home(), HOST), new byte[]{9});
        assertTrue(new CacheToolchainLocator(cache, verifier).locate(request()).isEmpty());

        final ToolchainCandidate repaired = locator.locate(request()).orElseThrow();
        assertEquals(ToolchainSource.DOWNLOAD, repaired.source());
        assertTrue(new CacheToolchainLocator(cache, verifier).locate(request()).isPresent());
    }

    @Test
    void unsafeArchiveNeverPublishesAPartialCacheEntry() throws Exception {
        final Fixture fixture = fixture("unsafe.zip", zip("../outside", new byte[]{1}));
        final Path archive = temporaryDirectory.resolve("unsafe.zip");
        Files.write(archive, fixture.archiveBytes());
        final Path cache = temporaryDirectory.resolve("unsafe-cache");

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> new AuthenticatedToolchainInstaller(cache, fixture.verifier())
                        .install(archive, fixture.signed(), request()));

        final Path home = new ToolchainCacheLayout(cache)
                .home(ToolchainManifest.PINNED_VERSION, HOST);
        assertFalse(Files.exists(home));
        assertFalse(Files.exists(temporaryDirectory.resolve("outside")));
    }

    private Fixture fixture(final String archiveName, final byte[] archiveBytes) throws Exception {
        final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final String archiveDigest = sha256(archiveBytes);
        final ToolchainManifest manifest = new ToolchainManifest(
                ToolchainManifest.CURRENT_SCHEMA,
                ToolchainManifest.PINNED_VERSION,
                NativeIrVersions.CURRENT_ABI,
                Set.of(NativeTarget.values()),
                Map.of(HOST, new ToolchainArchive(HOST,
                        URI.create("https://example.invalid/" + archiveName), archiveDigest, archiveBytes.length)),
                Map.of(HOST, ToolchainManifestVerifierTest.driverDigest())
        );
        final SignedToolchainManifest signed = ToolchainManifestVerifierTest.sign(manifest, keyPair);
        return new Fixture(archiveBytes, keyPair, signed,
                new ToolchainManifestVerifier(id -> Optional.of(keyPair.getPublic())));
    }

    private SignedToolchainManifest withArchiveUri(final Fixture fixture, final URI uri) throws Exception {
        final ToolchainManifest original = fixture.signed().manifest();
        final ToolchainArchive archive = original.hostArchives().get(HOST);
        final ToolchainManifest changed = new ToolchainManifest(
                original.schemaVersion(), original.toolchainVersion(), original.nativeIrAbi(),
                original.supportedTargets(),
                Map.of(HOST, new ToolchainArchive(HOST, uri, archive.sha256(), archive.size())),
                original.hostDriverSha256());
        return ToolchainManifestVerifierTest.sign(changed, fixture.keyPair());
    }

    private ToolchainRequest request() {
        return new ToolchainRequest(
                ToolchainDelivery.AUTO,
                Optional.empty(),
                ToolchainManifest.PINNED_VERSION,
                NativeIrVersions.CURRENT_ABI,
                Set.of(NativeTarget.LINUX_X86_64),
                HOST
        );
    }

    private static void writeResource(final Path root, final String name, final byte[] contents) throws Exception {
        final Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, contents);
    }

    private static byte[] zip(final String name, final byte[] contents) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(contents);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(
            byte[] archiveBytes,
            KeyPair keyPair,
            SignedToolchainManifest signed,
            ToolchainManifestVerifier verifier
    ) {
    }
}
