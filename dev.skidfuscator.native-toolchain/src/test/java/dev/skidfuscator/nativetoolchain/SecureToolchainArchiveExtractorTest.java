package dev.skidfuscator.nativetoolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureToolchainArchiveExtractorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsZipTraversalWithoutWritingOutsideTheDestination() throws Exception {
        final Path archive = temporaryDirectory.resolve("escape.zip");
        Files.write(archive, zip("../escaped", new byte[]{1}));
        final Path destination = temporaryDirectory.resolve("output");

        assertThrows(IOException.class,
                () -> new SecureToolchainArchiveExtractor().extract(archive, destination));

        assertFalse(Files.exists(temporaryDirectory.resolve("escaped")));
    }

    @Test
    void enforcesExpandedSizeLimitForZipEntries() throws Exception {
        final Path archive = temporaryDirectory.resolve("large.zip");
        Files.write(archive, zip("bin/skidllvm", new byte[33]));

        assertThrows(IOException.class, () -> new SecureToolchainArchiveExtractor(10, 32)
                .extract(archive, temporaryDirectory.resolve("limited")));
    }

    @Test
    void extractsRegularTarEntriesAndRejectsLinks() throws Exception {
        final Path regularArchive = temporaryDirectory.resolve("regular.tar");
        Files.write(regularArchive, tar("bin/skidllvm", '0', new byte[]{1, 2, 3}));
        final Path destination = temporaryDirectory.resolve("tar-output");

        new SecureToolchainArchiveExtractor().extract(regularArchive, destination);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(destination.resolve("bin/skidllvm")));

        final Path linkArchive = temporaryDirectory.resolve("link.tar");
        Files.write(linkArchive, tar("bin/skidllvm", '2', new byte[0]));
        assertThrows(IOException.class, () -> new SecureToolchainArchiveExtractor()
                .extract(linkArchive, temporaryDirectory.resolve("link-output")));
    }

    private static byte[] zip(final String name, final byte[] contents) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(contents);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] tar(final String name, final char type, final byte[] contents) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] header = new byte[512];
        putAscii(header, 0, 100, name);
        putOctal(header, 100, 8, 0755);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, contents.length);
        putOctal(header, 136, 12, 0);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = (byte) type;
        putAscii(header, 257, 6, "ustar");
        long checksum = 0;
        for (final byte value : header) {
            checksum += value & 0xff;
        }
        putOctal(header, 148, 8, checksum);
        output.write(header);
        output.write(contents);
        final int padding = (512 - contents.length % 512) % 512;
        output.write(new byte[padding]);
        output.write(new byte[1024]);
        return output.toByteArray();
    }

    private static void putAscii(
            final byte[] destination,
            final int offset,
            final int length,
            final String value
    ) {
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, destination, offset, Math.min(length, bytes.length));
    }

    private static void putOctal(
            final byte[] destination,
            final int offset,
            final int length,
            final long value
    ) {
        final String octal = Long.toOctalString(value);
        final int start = offset + length - octal.length() - 1;
        for (int index = offset; index < start; index++) {
            destination[index] = '0';
        }
        putAscii(destination, start, octal.length(), octal);
        destination[offset + length - 1] = 0;
    }
}
