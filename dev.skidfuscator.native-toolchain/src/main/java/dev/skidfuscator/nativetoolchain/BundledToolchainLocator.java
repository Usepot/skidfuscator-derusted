package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Installs an authenticated SkidLLVM archive embedded in the application classpath. */
public final class BundledToolchainLocator implements ToolchainLocator {
    private final ClassLoader resources;
    private final ToolchainManifestVerifier verifier;
    private final AuthenticatedToolchainInstaller installer;
    private final ToolchainManifestCodec codec;
    private final Path temporaryDirectory;

    public BundledToolchainLocator(
            final ClassLoader resources,
            final Path cacheRoot,
            final ToolchainManifestVerifier verifier
    ) {
        this(resources, cacheRoot, verifier, new ToolchainManifestCodec());
    }

    BundledToolchainLocator(
            final ClassLoader resources,
            final Path cacheRoot,
            final ToolchainManifestVerifier verifier,
            final ToolchainManifestCodec codec
    ) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.installer = new AuthenticatedToolchainInstaller(cacheRoot, verifier);
        this.codec = Objects.requireNonNull(codec, "codec");
        this.temporaryDirectory = new ToolchainCacheLayout(cacheRoot).root().resolve(".downloads");
    }

    @Override
    public ToolchainSource source() {
        return ToolchainSource.BUNDLED;
    }

    @Override
    public Optional<ToolchainCandidate> locate(final ToolchainRequest request) throws IOException {
        final String manifestResource = ToolchainResourcePaths.manifest(request.version());
        final SignedToolchainManifest signed;
        try (InputStream input = resources.getResourceAsStream(manifestResource)) {
            if (input == null) {
                return Optional.empty();
            }
            signed = ToolchainManifestFiles.read(input, codec);
        }
        verifier.verify(signed, request.version(), request.nativeIrAbi(),
                request.targets(), request.currentHost());
        final ToolchainArchive metadata = signed.manifest().hostArchives().get(request.currentHost());
        final String archiveResource = ToolchainResourcePaths.archive(
                request.version(), request.currentHost(), metadata);
        Files.createDirectories(temporaryDirectory);
        final Path archive = Files.createTempFile(temporaryDirectory, "bundled-", ".archive");
        Files.delete(archive);
        try {
            try (InputStream input = resources.getResourceAsStream(archiveResource)) {
                if (input == null) {
                    throw new ToolchainResolutionException(
                            "Bundled SkidLLVM manifest exists but archive is missing: " + archiveResource);
                }
                ToolchainStreams.copyExact(input, archive, metadata.size());
            }
            final Path home = installer.install(archive, signed, request);
            return Optional.of(new ToolchainCandidate(home, ToolchainSource.BUNDLED, signed));
        } finally {
            Files.deleteIfExists(archive);
        }
    }
}
