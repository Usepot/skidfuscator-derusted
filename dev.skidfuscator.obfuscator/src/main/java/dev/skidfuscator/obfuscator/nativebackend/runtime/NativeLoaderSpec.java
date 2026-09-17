package dev.skidfuscator.obfuscator.nativebackend.runtime;

import dev.skidfuscator.nativetoolchain.CompilerManifest;
import dev.skidfuscator.nativetoolchain.CompilerManifestCodec;
import dev.skidfuscator.nativetoolchain.NativeArtifact;
import dev.skidfuscator.nativetoolchain.NativeTarget;
import dev.skidfuscator.obfuscator.nativebackend.artifact.NativeCompilationInstaller;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, fully authenticated input to the generated Java native loader. */
public record NativeLoaderSpec(
        String internalName,
        String buildId,
        String manifestResourcePath,
        String manifestSha256,
        int manifestSize,
        Map<NativeTarget, Library> libraries
) {
    private static final Pattern INTERNAL_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern BUILD_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public NativeLoaderSpec {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(buildId, "buildId");
        Objects.requireNonNull(manifestResourcePath, "manifestResourcePath");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        libraries = Map.copyOf(Objects.requireNonNull(libraries, "libraries"));
        if (!INTERNAL_NAME.matcher(internalName).matches()) {
            throw new IllegalArgumentException("Invalid loader internal name: " + internalName);
        }
        if (!BUILD_ID.matcher(buildId).matches()) {
            throw new IllegalArgumentException("Invalid native build id: " + buildId);
        }
        final String expectedManifest = NativeCompilationInstaller.manifestResourcePath(buildId);
        if (!expectedManifest.equals(manifestResourcePath)) {
            throw new IllegalArgumentException("Non-canonical compiler manifest path: " + manifestResourcePath);
        }
        requireDigest(manifestSha256, "manifestSha256");
        requireSize(manifestSize, "manifestSize");
        if (libraries.isEmpty()) {
            throw new IllegalArgumentException("At least one native library is required");
        }
        for (final Map.Entry<NativeTarget, Library> entry : libraries.entrySet()) {
            if (entry.getKey() != entry.getValue().target()) {
                throw new IllegalArgumentException("Native library map key does not match its target");
            }
            final String prefix = NativeCompilationInstaller.NATIVE_RESOURCE_PREFIX
                    + buildId + "/" + entry.getKey().id() + "/";
            if (!entry.getValue().resourcePath().startsWith(prefix)) {
                throw new IllegalArgumentException("Non-canonical native library path: "
                        + entry.getValue().resourcePath());
            }
        }
    }

    public static NativeLoaderSpec fromManifest(
            final String internalName,
            final CompilerManifest manifest
    ) {
        Objects.requireNonNull(manifest, "manifest");
        final byte[] encoded = new CompilerManifestCodec().encode(manifest);
        final Map<NativeTarget, Library> libraries = new EnumMap<>(NativeTarget.class);
        for (final Map.Entry<NativeTarget, NativeArtifact> entry : manifest.artifacts().entrySet()) {
            final String path = entry.getValue().resourcePath();
            final int separator = path.lastIndexOf('/');
            if (separator < 0 || separator == path.length() - 1) {
                throw new IllegalArgumentException("Native artifact has no file name: " + path);
            }
            libraries.put(entry.getKey(), new Library(
                    entry.getKey(), path, entry.getValue().sha256(), path.substring(separator + 1),
                    Math.toIntExact(entry.getValue().size())));
        }
        return new NativeLoaderSpec(
                internalName,
                manifest.buildId(),
                NativeCompilationInstaller.manifestResourcePath(manifest.buildId()),
                sha256(encoded),
                encoded.length,
                libraries
        );
    }

    private static void requireDigest(final String digest, final String label) {
        if (!SHA_256.matcher(digest).matches()) {
            throw new IllegalArgumentException("Invalid SHA-256 for " + label + ": " + digest);
        }
    }

    private static void requireSize(final long size, final String label) {
        if (size <= 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " must be between 1 and " + Integer.MAX_VALUE);
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Library(NativeTarget target, String resourcePath, String sha256, String fileName, int size) {
        public Library {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(resourcePath, "resourcePath");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(fileName, "fileName");
            requireDigest(sha256, target.id());
            requireSize(size, target.id() + " size");
            if (resourcePath.startsWith("/") || resourcePath.contains("..") || resourcePath.contains("\\")) {
                throw new IllegalArgumentException("Invalid native resource path: " + resourcePath);
            }
            if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\")) {
                throw new IllegalArgumentException("Invalid native library file name: " + fileName);
            }
            final String expectedExtension = "." + target.libraryExtension();
            if (!fileName.endsWith(expectedExtension)) {
                throw new IllegalArgumentException("Native library extension does not match " + target.id());
            }
        }
    }
}
