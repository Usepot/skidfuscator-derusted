package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Constructs the standard no-PATH resolver chain used by native builds. */
public final class AuthenticatedToolchainResolvers {
    private AuthenticatedToolchainResolvers() {
    }

    public static DefaultToolchainResolver create(
            final Path cacheRoot,
            final ClassLoader resources,
            final ToolchainManifestUriResolver downloadManifestUris
    ) throws IOException {
        Objects.requireNonNull(resources, "resources");
        final ToolchainManifestVerifier verifier = new ToolchainManifestVerifier(
                new ClasspathPublicKeyResolver(resources));
        return create(cacheRoot, resources, downloadManifestUris, verifier);
    }

    public static DefaultToolchainResolver create(
            final Path cacheRoot,
            final ClassLoader resources,
            final ToolchainManifestUriResolver downloadManifestUris,
            final ToolchainManifestVerifier verifier
    ) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(downloadManifestUris, "downloadManifestUris");
        Objects.requireNonNull(verifier, "verifier");
        return new DefaultToolchainResolver(List.of(
                new ExternalToolchainLocator(),
                new BundledToolchainLocator(resources, cacheRoot, verifier),
                new CacheToolchainLocator(cacheRoot, verifier),
                new DownloadToolchainLocator(cacheRoot, downloadManifestUris, verifier)
        ), verifier);
    }
}
