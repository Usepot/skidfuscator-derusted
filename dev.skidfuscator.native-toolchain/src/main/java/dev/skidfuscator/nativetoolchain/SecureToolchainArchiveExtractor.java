package dev.skidfuscator.nativetoolchain;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts the deliberately small ZIP/TAR toolchain archive contract without following links. */
public final class SecureToolchainArchiveExtractor {
    public static final int DEFAULT_MAX_ENTRIES = 65_536;
    /** Bound downloads separately from the expanded installation budget. */
    public static final long DEFAULT_MAX_COMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long DEFAULT_MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final int maxEntries;
    private final long maxExpandedBytes;

    public SecureToolchainArchiveExtractor() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_EXPANDED_BYTES);
    }

    SecureToolchainArchiveExtractor(final int maxEntries, final long maxExpandedBytes) {
        if (maxEntries <= 0 || maxExpandedBytes <= 0) {
            throw new IllegalArgumentException("Archive extraction limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxExpandedBytes = maxExpandedBytes;
    }

    public void extract(final Path archive, final Path destination) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(destination, "destination");
        Files.createDirectories(destination);
        if (Files.isSymbolicLink(destination)) {
            throw new IOException("Toolchain extraction destination must not be a symbolic link");
        }
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(archive))) {
            input.mark(4);
            final int first = input.read();
            final int second = input.read();
            input.reset();
            if (first == 'P' && second == 'K') {
                extractZip(input, destination);
            } else if (first == 0x1f && second == 0x8b) {
                try (GZIPInputStream gzip = new GZIPInputStream(input)) {
                    extractTar(gzip, destination);
                }
            } else {
                extractTar(input, destination);
            }
        }
    }

    private void extractZip(final InputStream source, final Path destination) throws IOException {
        final ExtractionBudget budget = new ExtractionBudget();
        final Set<Path> entries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(source, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                budget.entry();
                final Path output = resolveEntry(destination, entry.getName());
                unique(entries, output);
                if (entry.isDirectory()) {
                    createDirectoriesSafely(destination, output);
                } else {
                    createParentSafely(destination, output);
                    copyEntry(zip, output, entry.getSize(), budget);
                }
                zip.closeEntry();
            }
        }
    }

    private void extractTar(final InputStream source, final Path destination) throws IOException {
        final ExtractionBudget budget = new ExtractionBudget();
        final Set<Path> entries = new HashSet<>();
        final byte[] header = new byte[512];
        while (true) {
            readFully(source, header);
            if (allZero(header)) {
                return;
            }
            verifyTarChecksum(header);
            budget.entry();
            final String name = tarName(header);
            final long size = parseOctal(header, 124, 12, "size");
            final int type = header[156] & 0xff;
            final Path output = resolveEntry(destination, name);
            unique(entries, output);
            if (type == '5') {
                if (size != 0) {
                    throw new IOException("TAR directory has a non-zero payload: " + name);
                }
                createDirectoriesSafely(destination, output);
            } else if (type == 0 || type == '0') {
                createParentSafely(destination, output);
                copyExactly(source, output, size, budget);
            } else {
                throw new IOException("Unsupported or unsafe TAR entry type " + type + ": " + name);
            }
            skipFully(source, padding(size));
        }
    }

    private static Path resolveEntry(final Path destination, final String name) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || name.startsWith("/") || hasControlCharacter(name)) {
            throw new IOException("Unsafe toolchain archive entry name: " + name);
        }
        for (final String component : name.split("/")) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Unsafe toolchain archive path component: " + name);
            }
        }
        final Path output = destination.resolve(name).normalize();
        if (output.equals(destination) || !output.startsWith(destination)) {
            throw new IOException("Toolchain archive entry escapes extraction root: " + name);
        }
        return output;
    }

    private static boolean hasControlCharacter(final String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static void unique(final Set<Path> entries, final Path output) throws IOException {
        if (!entries.add(output)) {
            throw new IOException("Duplicate toolchain archive entry: " + output.getFileName());
        }
    }

    private static void createParentSafely(final Path root, final Path output) throws IOException {
        final Path parent = output.getParent();
        if (parent == null) {
            throw new IOException("Archive entry has no parent");
        }
        createDirectoriesSafely(root, parent);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Archive entry would overwrite an existing path: " + output);
        }
    }

    private static void createDirectoriesSafely(final Path root, final Path directory) throws IOException {
        Path cursor = root;
        final Path relative = root.relativize(directory);
        for (final Path component : relative) {
            cursor = cursor.resolve(component);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Archive path traverses a non-directory: " + cursor);
                }
            } else {
                Files.createDirectory(cursor);
            }
        }
    }

    private static void copyEntry(
            final InputStream input,
            final Path output,
            final long declaredSize,
            final ExtractionBudget budget
    ) throws IOException {
        if (declaredSize > budget.remaining()) {
            throw new IOException("Toolchain archive exceeds the expanded-size limit");
        }
        try (var target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            final byte[] buffer = new byte[COPY_BUFFER_SIZE];
            long written = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                budget.bytes(read);
                target.write(buffer, 0, read);
                written += read;
            }
            if (declaredSize >= 0 && written != declaredSize) {
                throw new IOException("ZIP entry size does not match its header");
            }
        }
    }

    private static void copyExactly(
            final InputStream input,
            final Path output,
            final long size,
            final ExtractionBudget budget
    ) throws IOException {
        if (size > budget.remaining()) {
            throw new IOException("Toolchain archive exceeds the expanded-size limit");
        }
        try (var target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            final byte[] buffer = new byte[COPY_BUFFER_SIZE];
            long remaining = size;
            while (remaining > 0) {
                final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new EOFException("Truncated TAR entry");
                }
                budget.bytes(read);
                target.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static void readFully(final InputStream input, final byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            final int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                if (offset == 0) {
                    throw new EOFException("TAR archive is missing its end marker");
                }
                throw new EOFException("Truncated TAR header");
            }
            offset += read;
        }
    }

    private static void skipFully(final InputStream input, final long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            final long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (input.read() >= 0) {
                remaining--;
            } else {
                throw new EOFException("Truncated TAR padding");
            }
        }
    }

    private static long padding(final long size) {
        return (512 - (size % 512)) % 512;
    }

    private static String tarName(final byte[] header) throws IOException {
        final String name = ascii(header, 0, 100);
        final String prefix = ascii(header, 345, 155);
        final String combined = prefix.isEmpty() ? name : prefix + "/" + name;
        if (combined.isEmpty()) {
            throw new IOException("TAR entry has no name");
        }
        return combined;
    }

    private static String ascii(final byte[] bytes, final int offset, final int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static long parseOctal(
            final byte[] bytes,
            final int offset,
            final int length,
            final String field
    ) throws IOException {
        long value = 0;
        boolean found = false;
        for (int index = offset; index < offset + length; index++) {
            final int character = bytes[index] & 0xff;
            if (character == 0 || character == ' ') {
                if (found) {
                    break;
                }
                continue;
            }
            if (character < '0' || character > '7') {
                throw new IOException("Invalid TAR " + field);
            }
            found = true;
            if (value > (Long.MAX_VALUE - (character - '0')) / 8) {
                throw new IOException("TAR " + field + " overflows");
            }
            value = value * 8 + character - '0';
        }
        return value;
    }

    private static void verifyTarChecksum(final byte[] header) throws IOException {
        final long expected = parseOctal(header, 148, 8, "checksum");
        long actual = 0;
        for (int index = 0; index < header.length; index++) {
            actual += index >= 148 && index < 156 ? ' ' : header[index] & 0xff;
        }
        if (actual != expected) {
            throw new IOException("Invalid TAR header checksum");
        }
    }

    private static boolean allZero(final byte[] bytes) {
        for (final byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private final class ExtractionBudget {
        private int entries;
        private long bytes;

        void entry() throws IOException {
            if (++entries > maxEntries) {
                throw new IOException("Toolchain archive exceeds the entry-count limit");
            }
        }

        void bytes(final long count) throws IOException {
            if (count < 0 || bytes > maxExpandedBytes - count) {
                throw new IOException("Toolchain archive exceeds the expanded-size limit");
            }
            bytes += count;
        }

        long remaining() {
            return maxExpandedBytes - bytes;
        }
    }
}
