package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Authenticates every regular file in a SkidLLVM install tree. */
public final class ToolchainInstallTreeVerifier {
    private static final String EXCLUDED_SIGNED_ENVELOPE = ExternalToolchainLocator.MANIFEST_FILE_NAME;
    private static final byte[] NUL = {0};
    private static final byte[] NEWLINE = {'\n'};

    private ToolchainInstallTreeVerifier() {
    }

    /** Computes the schema-2 install-tree digest. Intended for release tooling and tests. */
    public static String digest(final Path toolchainHome) throws IOException {
        Objects.requireNonNull(toolchainHome, "toolchainHome");
        final Path root = toolchainHome.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IOException("SkidLLVM install root must be a real directory: " + root);
        }

        final List<TreeEntry> files = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        final long[] totalBytes = {0};
        final int[] entries = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(root)) {
                    countEntry(entries);
                    validateRelativePath(root, directory, names);
                }
                if (!attributes.isDirectory() || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(directory)) {
                    throw new IOException("SkidLLVM install tree contains an unsafe directory: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes)
                    throws IOException {
                countEntry(entries);
                final RelativeName relative = validateRelativePath(root, file, names);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    throw new IOException("SkidLLVM install tree contains a link or special file: " + relative.text());
                }
                if (relative.text().equals(EXCLUDED_SIGNED_ENVELOPE)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.size() < 0
                        || totalBytes[0] > SecureToolchainArchiveExtractor.DEFAULT_MAX_EXPANDED_BYTES
                        - attributes.size()) {
                    throw new IOException("SkidLLVM install tree exceeds the expanded-size limit");
                }
                totalBytes[0] += attributes.size();
                files.add(new TreeEntry(relative.bytes(), attributes.size(), hashStableFile(file, attributes)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException failure) throws IOException {
                throw new IOException("Unable to inspect SkidLLVM install-tree entry: " + file, failure);
            }
        });

        files.sort((left, right) -> compareUnsigned(left.relativePath(), right.relativePath()));
        final MessageDigest tree = sha256();
        for (final TreeEntry entry : files) {
            tree.update(entry.relativePath());
            tree.update(NUL);
            tree.update(Long.toString(entry.size()).getBytes(StandardCharsets.US_ASCII));
            tree.update(NUL);
            tree.update(entry.sha256().getBytes(StandardCharsets.US_ASCII));
            tree.update(NEWLINE);
        }
        return HexFormat.of().formatHex(tree.digest());
    }

    public static void verify(
            final Path toolchainHome,
            final ToolchainManifest manifest,
            final NativeTarget currentHost
    ) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(currentHost, "currentHost");
        final String expected = manifest.hostTreeSha256().get(currentHost);
        if (expected == null || !ToolchainManifestVerifier.isSha256(expected)) {
            throw new ManifestValidationException(List.of(
                    "Toolchain has no valid install-tree digest for current host " + currentHost.id()
            ));
        }
        final String actual = digest(toolchainHome);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new ManifestValidationException(List.of(
                    "Installed SkidLLVM tree SHA-256 does not match signed manifest"
            ));
        }
    }

    private static String hashStableFile(final Path file, final BasicFileAttributes before) throws IOException {
        final MessageDigest digest = sha256();
        try (SeekableByteChannel channel = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             InputStream input = Channels.newInputStream(channel)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        final BasicFileAttributes after = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.isSymbolicLink() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !sameFileKey(before.fileKey(), after.fileKey())) {
            throw new IOException("SkidLLVM install-tree entry changed while it was authenticated: " + file);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean sameFileKey(final Object before, final Object after) {
        return before == null || after == null || before.equals(after);
    }

    private static RelativeName validateRelativePath(
            final Path root,
            final Path entry,
            final Set<String> names
    ) throws IOException {
        if (!entry.startsWith(root)) {
            throw new IOException("SkidLLVM install-tree entry escapes its root: " + entry);
        }
        final Path relative = root.relativize(entry);
        if (relative.isAbsolute() || relative.getNameCount() == 0) {
            throw new IOException("Invalid SkidLLVM install-tree path: " + relative);
        }
        final StringBuilder name = new StringBuilder();
        for (final Path componentPath : relative) {
            final String component = componentPath.toString();
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                    || component.indexOf('\\') >= 0 || component.indexOf('\0') >= 0) {
                throw new IOException("Invalid SkidLLVM install-tree path: " + relative);
            }
            if (!name.isEmpty()) {
                name.append('/');
            }
            name.append(component);
        }
        final String text = name.toString();
        final byte[] utf8 = strictUtf8(text);
        if (!names.add(text)) {
            throw new IOException("Duplicate SkidLLVM install-tree path: " + text);
        }
        return new RelativeName(text, utf8);
    }

    private static byte[] strictUtf8(final String value) throws IOException {
        try {
            final ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            final byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (final CharacterCodingException invalid) {
            throw new IOException("SkidLLVM install-tree path is not valid UTF-8", invalid);
        }
    }

    private static void countEntry(final int[] entries) throws IOException {
        if (++entries[0] > SecureToolchainArchiveExtractor.DEFAULT_MAX_ENTRIES) {
            throw new IOException("SkidLLVM install tree exceeds the entry-count limit");
        }
    }

    private static int compareUnsigned(final byte[] left, final byte[] right) {
        final int common = Math.min(left.length, right.length);
        for (int index = 0; index < common; index++) {
            final int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RelativeName(String text, byte[] bytes) {
        private RelativeName {
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }

    private record TreeEntry(byte[] relativePath, long size, String sha256) {
        private TreeEntry {
            relativePath = Arrays.copyOf(relativePath, relativePath.length);
        }

        @Override
        public byte[] relativePath() {
            return Arrays.copyOf(relativePath, relativePath.length);
        }
    }
}
