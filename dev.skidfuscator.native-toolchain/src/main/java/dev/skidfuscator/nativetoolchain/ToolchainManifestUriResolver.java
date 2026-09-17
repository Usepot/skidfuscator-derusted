package dev.skidfuscator.nativetoolchain;

import java.net.URI;
import java.util.Objects;

@FunctionalInterface
public interface ToolchainManifestUriResolver {
    URI resolve(String version);

    /** Creates the release contract {@code <base>/<version>/skidllvm-toolchain.manifest}. */
    static ToolchainManifestUriResolver versioned(final URI releaseBase) {
        Objects.requireNonNull(releaseBase, "releaseBase");
        HttpsToolchainTransport.validateUri(releaseBase);
        final String base = releaseBase.toString().endsWith("/")
                ? releaseBase.toString()
                : releaseBase + "/";
        return version -> URI.create(base + safeVersion(version) + "/"
                + ExternalToolchainLocator.MANIFEST_FILE_NAME);
    }

    private static String safeVersion(final String version) {
        Objects.requireNonNull(version, "version");
        if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Unsafe toolchain version: " + version);
        }
        return version;
    }
}
