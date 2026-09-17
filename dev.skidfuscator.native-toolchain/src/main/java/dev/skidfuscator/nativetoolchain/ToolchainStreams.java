package dev.skidfuscator.nativetoolchain;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class ToolchainStreams {
    private ToolchainStreams() {
    }

    static void copyExact(final InputStream input, final Path destination, final long expectedSize)
            throws IOException {
        if (expectedSize <= 0) {
            throw new IllegalArgumentException("Expected archive size must be positive");
        }
        try (OutputStream output = Files.newOutputStream(destination,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            final byte[] buffer = new byte[64 * 1024];
            long remaining = expectedSize;
            while (remaining > 0) {
                final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new EOFException("Toolchain archive is shorter than its signed size");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            if (input.read() != -1) {
                throw new IOException("Toolchain archive is larger than its signed size");
            }
        } catch (final IOException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }
    }
}
