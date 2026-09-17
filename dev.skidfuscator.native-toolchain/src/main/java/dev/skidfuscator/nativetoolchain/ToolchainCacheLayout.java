package dev.skidfuscator.nativetoolchain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable, traversal-safe layout for authenticated toolchain cache entries. */
public final class ToolchainCacheLayout {
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path root;

    public ToolchainCacheLayout(final Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path versionDirectory(final String version) {
        validateVersion(version);
        return confined(root.resolve(version));
    }

    public Path home(final String version, final NativeTarget host) {
        Objects.requireNonNull(host, "host");
        return confined(versionDirectory(version).resolve(host.id()).resolve("toolchain"));
    }

    public Path lockFile(final String version, final NativeTarget host) {
        Objects.requireNonNull(host, "host");
        return confined(versionDirectory(version).resolve(host.id()).resolve(".install.lock"));
    }

    private Path confined(final Path candidate) {
        final Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Toolchain cache path escapes its configured root");
        }
        return normalized;
    }

    private static void validateVersion(final String version) {
        Objects.requireNonNull(version, "version");
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid toolchain version for cache layout: " + version);
        }
    }
}
