package dev.skidfuscator.obfuscator.nativebackend.artifact;

import dev.skidfuscator.nativetoolchain.CompilerManifest;
import dev.skidfuscator.nativetoolchain.CompilerManifestCodec;
import dev.skidfuscator.nativetoolchain.CompilerManifestValidator;
import dev.skidfuscator.nativetoolchain.NativeArtifact;
import dev.skidfuscator.nativetoolchain.NativeCompilationResult;
import dev.skidfuscator.nativetoolchain.NativeTarget;
import org.topdank.byteengineer.commons.data.JarContents;
import org.topdank.byteengineer.commons.data.JarResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates and installs the output of one native compilation into a universal jar model.
 *
 * <p>The installation is staged completely in memory. The destination is modified only
 * after the compiler manifest, target set, resource names, library sizes, digests, input
 * reads, and destination collisions have all been checked.</p>
 */
public final class NativeCompilationInstaller {
    public static final String NATIVE_RESOURCE_PREFIX = "META-INF/skidfuscator/native/";
    public static final String MANIFEST_FILE_NAME = "manifest.properties";
    private static final Pattern BUILD_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final CompilerManifestValidator manifestValidator;
    private final CompilerManifestCodec manifestCodec;

    public NativeCompilationInstaller() {
        this(new CompilerManifestValidator(), new CompilerManifestCodec());
    }

    NativeCompilationInstaller(
            final CompilerManifestValidator manifestValidator,
            final CompilerManifestCodec manifestCodec
    ) {
        this.manifestValidator = Objects.requireNonNull(manifestValidator, "manifestValidator");
        this.manifestCodec = Objects.requireNonNull(manifestCodec, "manifestCodec");
    }

    /**
     * Installs the native libraries and one build-level compiler manifest.
     *
     * @param contents destination jar contents
     * @param compilation compilation output to validate and install
     * @param requestedTargets targets the caller requested from the compiler
     * @return the resource paths installed, in supported-target declaration order
     * @throws IOException if a library cannot be read or its contents do not match the manifest
     */
    public List<String> install(
            final JarContents contents,
            final NativeCompilationResult compilation,
            final Collection<NativeTarget> requestedTargets
    ) throws IOException {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(compilation, "compilation");

        final EnumSet<NativeTarget> requested = normalizeTargets(requestedTargets);
        final CompilerManifest manifest = compilation.manifest();
        manifestValidator.validateOrThrow(manifest);
        validateExactTargets(requested, manifest, compilation);
        validateCanonicalModule(manifest, compilation.canonicalLlvmIr());

        final byte[] encodedManifest = manifestCodec.encode(manifest);
        final List<JarResource> staged = new ArrayList<>(requested.size() + 1);
        final List<String> stagedPaths = new ArrayList<>(requested.size() + 1);
        final Set<String> uniqueStagedPaths = new HashSet<>();
        stage(staged, stagedPaths, uniqueStagedPaths,
                manifestResourcePath(manifest.buildId()), encodedManifest);

        for (final NativeTarget target : NativeTarget.values()) {
            if (!requested.contains(target)) {
                continue;
            }

            final NativeArtifact artifact = manifest.artifacts().get(target);
            final Path library = compilation.libraries().get(target);
            if (!Files.isRegularFile(library) || Files.size(library) != artifact.size()) {
                throw new IOException("Native library size does not match manifest before read: " + target.id());
            }
            final byte[] libraryBytes = Files.readAllBytes(library);
            validateLibrary(target, artifact, library, libraryBytes);
            stage(staged, stagedPaths, uniqueStagedPaths,
                    artifact.resourcePath(), libraryBytes);
        }

        validateNoDestinationCollisions(contents, uniqueStagedPaths);
        contents.getResourceContents().addAll(staged);
        return List.copyOf(stagedPaths);
    }

    public static String manifestResourcePath(final String buildId) {
        Objects.requireNonNull(buildId, "buildId");
        if (!BUILD_ID.matcher(buildId).matches()) {
            throw new IllegalArgumentException("Invalid native build id: " + buildId);
        }
        return NATIVE_RESOURCE_PREFIX + buildId + "/" + MANIFEST_FILE_NAME;
    }

    private static EnumSet<NativeTarget> normalizeTargets(final Collection<NativeTarget> targets) {
        Objects.requireNonNull(targets, "requestedTargets");
        final EnumSet<NativeTarget> normalized = EnumSet.noneOf(NativeTarget.class);
        for (final NativeTarget target : targets) {
            if (!normalized.add(Objects.requireNonNull(target, "requestedTargets contains null"))) {
                throw new IllegalArgumentException("Duplicate requested native target: " + target.id());
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one requested native target is required");
        }
        return normalized;
    }

    private static void validateExactTargets(
            final Set<NativeTarget> requested,
            final CompilerManifest manifest,
            final NativeCompilationResult compilation
    ) {
        if (!manifest.artifacts().keySet().equals(requested)) {
            throw new IllegalArgumentException(
                    "Compiler manifest targets must exactly match requested targets: requested="
                            + requested + ", manifest=" + manifest.artifacts().keySet()
            );
        }
        if (!compilation.libraries().keySet().equals(requested)) {
            throw new IllegalArgumentException(
                    "Compiled library targets must exactly match requested targets: requested="
                            + requested + ", libraries=" + compilation.libraries().keySet()
            );
        }
    }

    private static void validateLibrary(
            final NativeTarget target,
            final NativeArtifact artifact,
            final Path library,
            final byte[] bytes
    ) throws IOException {
        if (bytes.length != artifact.size()) {
            throw new IOException("Native library size mismatch for " + target.id()
                    + ": expected " + artifact.size() + " bytes but read " + bytes.length
                    + " from " + library);
        }
        final String actualDigest = sha256(bytes);
        if (!MessageDigest.isEqual(
                artifact.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actualDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        )) {
            throw new IOException("Native library SHA-256 mismatch for " + target.id()
                    + ": expected " + artifact.sha256() + " but was " + actualDigest);
        }
    }

    private static void validateCanonicalModule(
            final CompilerManifest manifest,
            final Path canonicalLlvmIr
    ) throws IOException {
        if (!Files.isRegularFile(canonicalLlvmIr) || Files.size(canonicalLlvmIr) == 0) {
            throw new IOException("Canonical LLVM IR is missing or empty: " + canonicalLlvmIr);
        }
        final String actualDigest = sha256(canonicalLlvmIr);
        if (!MessageDigest.isEqual(
                manifest.moduleSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actualDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        )) {
            throw new IOException("Canonical LLVM IR SHA-256 mismatch: expected "
                    + manifest.moduleSha256() + " but was " + actualDigest);
        }
    }

    private static void stage(
            final List<JarResource> resources,
            final List<String> paths,
            final Set<String> uniquePaths,
            final String path,
            final byte[] contents
    ) {
        if (!uniquePaths.add(path)) {
            throw new IllegalArgumentException("Native compilation contains a duplicate resource path: " + path);
        }
        resources.add(new JarResource(path, contents));
        paths.add(path);
    }

    private static void validateNoDestinationCollisions(
            final JarContents contents,
            final Set<String> stagedPaths
    ) {
        final Set<String> existingPaths = new HashSet<>();
        for (final JarResource resource : contents.getResourceContents()) {
            if (!existingPaths.add(resource.getName())) {
                throw new IllegalStateException("Destination jar already contains duplicate resource: "
                        + resource.getName());
            }
            if (stagedPaths.contains(resource.getName())) {
                throw new IllegalStateException("Native resource collides with existing jar entry: "
                        + resource.getName());
            }
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(final Path path) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
