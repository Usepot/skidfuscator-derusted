package dev.skidfuscator.nativetoolchain;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Signed-manifest metadata for one downloadable SkidLLVM host archive. */
public record ToolchainArchive(NativeTarget host, URI uri, String sha256, long size) {
    public ToolchainArchive {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(sha256, "sha256");
        sha256 = sha256.toLowerCase(Locale.ROOT);
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (size > SecureToolchainArchiveExtractor.DEFAULT_MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("size exceeds the compressed archive limit");
        }
    }
}
