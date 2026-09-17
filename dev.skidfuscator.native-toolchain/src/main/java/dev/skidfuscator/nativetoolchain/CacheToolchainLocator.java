package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Returns cache entries only after authenticating the manifest and installed driver. */
public final class CacheToolchainLocator implements ToolchainLocator {
    private final ToolchainCacheLayout layout;
    private final ToolchainManifestVerifier verifier;
    private final ToolchainManifestCodec codec;

    public CacheToolchainLocator(final Path cacheRoot, final ToolchainManifestVerifier verifier) {
        this(cacheRoot, verifier, new ToolchainManifestCodec());
    }

    CacheToolchainLocator(
            final Path cacheRoot,
            final ToolchainManifestVerifier verifier,
            final ToolchainManifestCodec codec
    ) {
        this.layout = new ToolchainCacheLayout(cacheRoot);
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public ToolchainSource source() {
        return ToolchainSource.CACHE;
    }

    @Override
    public Optional<ToolchainCandidate> locate(final ToolchainRequest request) throws IOException {
        final Path home = layout.home(request.version(), request.currentHost());
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(home)) {
            return Optional.empty();
        }
        try {
            final SignedToolchainManifest signed = ToolchainManifestFiles.read(
                    home.resolve(ExternalToolchainLocator.MANIFEST_FILE_NAME), codec);
            verifier.verify(signed, request.version(), request.nativeIrAbi(),
                    request.targets(), request.currentHost());
            ToolchainManifestVerifier.verifyInstalledDriver(home, signed.manifest(), request.currentHost());
            return Optional.of(new ToolchainCandidate(home, ToolchainSource.CACHE, signed));
        } catch (final IllegalArgumentException invalid) {
            return Optional.empty();
        } catch (final IOException unreadableOrMalformed) {
            return Optional.empty();
        }
    }
}
