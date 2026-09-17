package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

final class ToolchainManifestFiles {
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private ToolchainManifestFiles() {
    }

    static SignedToolchainManifest read(
            final Path path,
            final ToolchainManifestCodec codec
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("SkidLLVM manifest is missing or is not a regular file: " + path);
        }
        final long size = Files.size(path);
        if (size <= 0 || size > MAX_MANIFEST_BYTES) {
            throw new IOException("SkidLLVM manifest has an invalid size: " + path);
        }
        try (InputStream input = Files.newInputStream(path)) {
            return decode(readBounded(input, MAX_MANIFEST_BYTES, "SkidLLVM manifest"), codec);
        }
    }

    static SignedToolchainManifest read(
            final InputStream input,
            final ToolchainManifestCodec codec
    ) throws IOException {
        return decode(readBounded(input, MAX_MANIFEST_BYTES, "SkidLLVM manifest"), codec);
    }

    static byte[] readBounded(final InputStream input, final long maximum, final String description)
            throws IOException {
        Objects.requireNonNull(input, "input");
        if (maximum <= 0 || maximum > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("Invalid in-memory size bound");
        }
        final byte[] bytes = input.readNBytes((int) maximum + 1);
        if (bytes.length == 0) {
            throw new IOException(description + " is empty");
        }
        if (bytes.length > maximum) {
            throw new IOException(description + " exceeds the size limit");
        }
        return bytes;
    }

    private static SignedToolchainManifest decode(
            final byte[] bytes,
            final ToolchainManifestCodec codec
    ) throws IOException {
        try {
            return Objects.requireNonNull(codec, "codec").decodeSigned(bytes);
        } catch (final IllegalArgumentException exception) {
            throw new IOException("Invalid signed SkidLLVM manifest", exception);
        }
    }
}
