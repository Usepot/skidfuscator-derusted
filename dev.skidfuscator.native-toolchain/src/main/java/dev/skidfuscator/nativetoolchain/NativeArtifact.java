package dev.skidfuscator.nativetoolchain;

import java.util.Locale;
import java.util.Objects;

/** One native library embedded in a universal or platform-specific output jar. */
public record NativeArtifact(NativeTarget target, String resourcePath, String sha256, long size) {
    public NativeArtifact {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(sha256, "sha256");
        sha256 = sha256.toLowerCase(Locale.ROOT);
        if (size < 0) {
            throw new IllegalArgumentException("size cannot be negative");
        }
    }
}
