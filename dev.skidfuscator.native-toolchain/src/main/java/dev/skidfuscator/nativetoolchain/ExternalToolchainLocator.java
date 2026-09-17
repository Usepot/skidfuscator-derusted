package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Resolves an explicitly configured toolchain without consulting PATH. */
public final class ExternalToolchainLocator implements ToolchainLocator {
    public static final String MANIFEST_FILE_NAME = "skidllvm-toolchain.manifest";
    private final ToolchainManifestCodec codec;

    public ExternalToolchainLocator() {
        this(new ToolchainManifestCodec());
    }

    public ExternalToolchainLocator(final ToolchainManifestCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public ToolchainSource source() {
        return ToolchainSource.EXTERNAL;
    }

    @Override
    public Optional<ToolchainCandidate> locate(final ToolchainRequest request) throws IOException {
        if (request.explicitPath().isEmpty()) {
            return Optional.empty();
        }
        final Path home = request.explicitPath().get();
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(home)) {
            return Optional.empty();
        }
        final Path manifestPath = home.resolve(MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(manifestPath)) {
            return Optional.empty();
        }
        final SignedToolchainManifest signedManifest = ToolchainManifestFiles.read(manifestPath, codec);
        final Path executable = ProcessNativeCompiler.executable(home, request.currentHost());
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(executable)) {
            throw new ToolchainResolutionException("SkidLLVM driver is missing: " + executable);
        }
        return Optional.of(new ToolchainCandidate(home, ToolchainSource.EXTERNAL, signedManifest));
    }
}
