package dev.skidfuscator.nativetoolchain;

import java.nio.file.Path;
import java.util.Objects;

/** A locator result that still requires manifest authentication. */
public record ToolchainCandidate(
        Path home,
        ToolchainSource source,
        SignedToolchainManifest signedManifest
) {
    public ToolchainCandidate {
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(signedManifest, "signedManifest");
    }
}
