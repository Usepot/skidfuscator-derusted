package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Downloads only metadata-authenticated archives and publishes them through the verified cache. */
public final class DownloadToolchainLocator implements ToolchainLocator {
    private final ToolchainManifestUriResolver manifestUris;
    private final ToolchainTransport transport;
    private final ToolchainManifestVerifier verifier;
    private final AuthenticatedToolchainInstaller installer;
    private final ToolchainManifestCodec codec;
    private final Path temporaryDirectory;

    public DownloadToolchainLocator(
            final Path cacheRoot,
            final ToolchainManifestUriResolver manifestUris,
            final ToolchainManifestVerifier verifier
    ) {
        this(cacheRoot, manifestUris, new HttpsToolchainTransport(), verifier, new ToolchainManifestCodec());
    }

    DownloadToolchainLocator(
            final Path cacheRoot,
            final ToolchainManifestUriResolver manifestUris,
            final ToolchainTransport transport,
            final ToolchainManifestVerifier verifier,
            final ToolchainManifestCodec codec
    ) {
        this.manifestUris = Objects.requireNonNull(manifestUris, "manifestUris");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.installer = new AuthenticatedToolchainInstaller(cacheRoot, verifier);
        this.codec = Objects.requireNonNull(codec, "codec");
        this.temporaryDirectory = new ToolchainCacheLayout(cacheRoot).root().resolve(".downloads");
    }

    @Override
    public ToolchainSource source() {
        return ToolchainSource.DOWNLOAD;
    }

    @Override
    public Optional<ToolchainCandidate> locate(final ToolchainRequest request) throws IOException {
        final URI manifestUri = Objects.requireNonNull(
                manifestUris.resolve(request.version()), "manifest URI");
        HttpsToolchainTransport.validateUri(manifestUri);
        final SignedToolchainManifest signed;
        try (InputStream input = transport.open(manifestUri, ToolchainManifestFiles.MAX_MANIFEST_BYTES)) {
            signed = ToolchainManifestFiles.read(input, codec);
        }
        verifier.verify(signed, request.version(), request.nativeIrAbi(),
                request.targets(), request.currentHost());
        final ToolchainArchive metadata = signed.manifest().hostArchives().get(request.currentHost());
        HttpsToolchainTransport.validateUri(metadata.uri());

        Files.createDirectories(temporaryDirectory);
        final Path archive = Files.createTempFile(temporaryDirectory, "download-", ".archive");
        Files.delete(archive);
        try {
            try (InputStream input = transport.open(metadata.uri(), metadata.size())) {
                ToolchainStreams.copyExact(input, archive, metadata.size());
            }
            final Path home = installer.install(archive, signed, request);
            return Optional.of(new ToolchainCandidate(home, ToolchainSource.DOWNLOAD, signed));
        } finally {
            Files.deleteIfExists(archive);
        }
    }
}
