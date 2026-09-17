package dev.skidfuscator.nativetoolchain;

import java.net.URI;
import java.util.Objects;

/** Classpath layout contract used by bundled manifests, archives, and pinned release keys. */
public final class ToolchainResourcePaths {
    public static final String ROOT = "META-INF/skidfuscator/native/toolchains/";
    public static final String KEY_INDEX = "META-INF/skidfuscator/native/toolchain-keys.properties";
    public static final String KEY_ROOT = "META-INF/skidfuscator/native/keys/";

    private ToolchainResourcePaths() {
    }

    public static String manifest(final String version) {
        return ROOT + safeSegment(version, "version") + "/" + ExternalToolchainLocator.MANIFEST_FILE_NAME;
    }

    public static String archive(
            final String version,
            final NativeTarget host,
            final ToolchainArchive metadata
    ) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(metadata, "metadata");
        return ROOT + safeSegment(version, "version") + "/" + host.id() + "/"
                + archiveFileName(metadata.uri());
    }

    static String archiveFileName(final URI uri) {
        Objects.requireNonNull(uri, "uri");
        final String path = uri.getPath();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Toolchain archive URI has no file name");
        }
        final int slash = path.lastIndexOf('/');
        final String name = slash < 0 ? path : path.substring(slash + 1);
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
                || !(name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".tar.gz"))) {
            throw new IllegalArgumentException("Unsupported or unsafe toolchain archive name: " + name);
        }
        return name;
    }

    private static String safeSegment(final String value, final String description) {
        Objects.requireNonNull(value, description);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Unsafe toolchain " + description + ": " + value);
        }
        return value;
    }
}
