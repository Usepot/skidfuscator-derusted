package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;

/** Verifies, extracts, and atomically publishes an immutable cache entry. */
public final class AuthenticatedToolchainInstaller {
    private final ToolchainCacheLayout cacheLayout;
    private final ToolchainManifestVerifier verifier;
    private final ToolchainManifestCodec codec;
    private final SecureToolchainArchiveExtractor extractor;

    public AuthenticatedToolchainInstaller(
            final Path cacheRoot,
            final ToolchainManifestVerifier verifier
    ) {
        this(cacheRoot, verifier, new ToolchainManifestCodec(), new SecureToolchainArchiveExtractor());
    }

    AuthenticatedToolchainInstaller(
            final Path cacheRoot,
            final ToolchainManifestVerifier verifier,
            final ToolchainManifestCodec codec,
            final SecureToolchainArchiveExtractor extractor
    ) {
        this.cacheLayout = new ToolchainCacheLayout(cacheRoot);
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    public Path install(
            final Path archive,
            final SignedToolchainManifest signedManifest,
            final ToolchainRequest request
    ) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(signedManifest, "signedManifest");
        Objects.requireNonNull(request, "request");
        verifier.verify(signedManifest, request.version(), request.nativeIrAbi(),
                request.targets(), request.currentHost());
        final ToolchainArchive metadata = signedManifest.manifest().hostArchives().get(request.currentHost());
        if (metadata == null) {
            throw new ToolchainResolutionException("Signed manifest has no archive for " + request.currentHost().id());
        }
        verifier.verifyArchive(archive, metadata);

        final Path home = cacheLayout.home(request.version(), request.currentHost());
        final Path hostDirectory = home.getParent();
        Files.createDirectories(cacheLayout.root());
        createSafeDirectory(cacheLayout.root(), "cache root");
        createSafeDirectory(cacheLayout.versionDirectory(request.version()), "version cache");
        createSafeDirectory(hostDirectory, "host cache");

        final Path staging = Files.createTempDirectory(hostDirectory, ".install-");
        boolean published = false;
        try {
            extractor.extract(archive, staging);
            final Path embeddedManifest = staging.resolve(ExternalToolchainLocator.MANIFEST_FILE_NAME);
            if (Files.exists(embeddedManifest, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Toolchain archive must not replace its authenticated manifest");
            }
            Files.write(embeddedManifest, codec.encodeSigned(signedManifest),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            final Path driver = ProcessNativeCompiler.executable(staging, request.currentHost());
            if (!Files.isRegularFile(driver, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(driver)) {
                throw new IOException("Extracted SkidLLVM driver is missing or unsafe: " + driver);
            }
            if (!driver.toFile().setExecutable(true, true) && !Files.isExecutable(driver)) {
                throw new IOException("Unable to make the SkidLLVM driver executable: " + driver);
            }
            ToolchainManifestVerifier.verifyInstalledDriver(
                    staging, signedManifest.manifest(), request.currentHost());

            final Path lockPath = cacheLayout.lockFile(request.version(), request.currentHost());
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                if (isCurrentValidEntry(home, signedManifest, request)) {
                    return home;
                }
                if (Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(home);
                }
                moveAtomically(staging, home);
                published = true;
            }
            return home;
        } finally {
            if (!published && Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(staging);
            }
        }
    }

    private boolean isCurrentValidEntry(
            final Path home,
            final SignedToolchainManifest expected,
            final ToolchainRequest request
    ) {
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(home)) {
            return false;
        }
        try {
            final SignedToolchainManifest actual = ToolchainManifestFiles.read(
                    home.resolve(ExternalToolchainLocator.MANIFEST_FILE_NAME), codec);
            if (!Arrays.equals(codec.encodeSigned(actual), codec.encodeSigned(expected))) {
                return false;
            }
            verifier.verify(actual, request.version(), request.nativeIrAbi(),
                    request.targets(), request.currentHost());
            ToolchainManifestVerifier.verifyInstalledDriver(home, actual.manifest(), request.currentHost());
            return true;
        } catch (final IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void createSafeDirectory(final Path path, final String description) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Toolchain " + description + " must be a real directory: " + path);
            }
        } else {
            Files.createDirectory(path);
        }
    }

    private static void moveAtomically(final Path source, final Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    static void deleteTree(final Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path directory, final IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
