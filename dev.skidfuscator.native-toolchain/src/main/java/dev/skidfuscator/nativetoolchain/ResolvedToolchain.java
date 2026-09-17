package dev.skidfuscator.nativetoolchain;

import java.nio.file.Path;
import java.util.Objects;

/** An authenticated SkidLLVM installation safe to invoke. */
public record ResolvedToolchain(Path home, ToolchainSource source, ToolchainManifest manifest) {
    public ResolvedToolchain {
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(manifest, "manifest");
    }
}
