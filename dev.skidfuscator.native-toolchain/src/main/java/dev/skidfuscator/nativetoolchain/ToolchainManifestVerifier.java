package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Authenticates and validates SkidLLVM release metadata and archives. */
public final class ToolchainManifestVerifier {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ALLOWED_SIGNATURE_ALGORITHMS = Set.of("Ed25519", "SHA256withRSA");
    private final KeyResolver keyResolver;
    private final ToolchainManifestCodec codec;

    public ToolchainManifestVerifier(final KeyResolver keyResolver) {
        this(keyResolver, new ToolchainManifestCodec());
    }

    public ToolchainManifestVerifier(final KeyResolver keyResolver, final ToolchainManifestCodec codec) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public void verify(
            final SignedToolchainManifest signedManifest,
            final String expectedVersion,
            final int expectedNativeIrAbi,
            final Set<NativeTarget> requiredTargets,
            final NativeTarget currentHost
    ) {
        Objects.requireNonNull(signedManifest, "signedManifest");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        Objects.requireNonNull(requiredTargets, "requiredTargets");
        Objects.requireNonNull(currentHost, "currentHost");
        final ToolchainManifest manifest = signedManifest.manifest();
        final List<String> violations = new ArrayList<>();
        if (manifest.schemaVersion() != ToolchainManifest.CURRENT_SCHEMA) {
            violations.add("Unsupported toolchain manifest schema: " + manifest.schemaVersion());
        }
        if (!manifest.toolchainVersion().equals(expectedVersion)) {
            violations.add("Toolchain version " + manifest.toolchainVersion() + " does not match " + expectedVersion);
        }
        if (manifest.nativeIrAbi() != expectedNativeIrAbi) {
            violations.add("Native IR ABI " + manifest.nativeIrAbi() + " does not match " + expectedNativeIrAbi);
        }
        if (!manifest.supportedTargets().containsAll(requiredTargets)) {
            violations.add("Toolchain does not support all requested targets");
        }
        if (!manifest.hostArchives().containsKey(currentHost)) {
            violations.add("Toolchain has no archive for current host " + currentHost.id());
        }
        if (!manifest.hostDriverSha256().containsKey(currentHost)) {
            violations.add("Toolchain has no driver digest for current host " + currentHost.id());
        }
        if (manifest.supportedTargets().isEmpty()) {
            violations.add("Toolchain target list is empty");
        }
        for (final var entry : manifest.hostArchives().entrySet()) {
            if (entry.getKey() != entry.getValue().host()) {
                violations.add("Host archive key mismatch for " + entry.getKey().id());
            }
            if (!SHA_256.matcher(entry.getValue().sha256()).matches()) {
                violations.add("Invalid archive SHA-256 for " + entry.getKey().id());
            }
            final String scheme = entry.getValue().uri().getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("file"))) {
                violations.add("Archive URI must use https or file for " + entry.getKey().id());
            }
        }
        if (!manifest.hostDriverSha256().keySet().equals(manifest.hostArchives().keySet())) {
            violations.add("Toolchain host archive and driver digest sets do not match");
        }
        for (final var entry : manifest.hostDriverSha256().entrySet()) {
            if (!SHA_256.matcher(entry.getValue()).matches()) {
                violations.add("Invalid driver SHA-256 for " + entry.getKey().id());
            }
        }
        if (!ALLOWED_SIGNATURE_ALGORITHMS.contains(signedManifest.signatureAlgorithm())) {
            violations.add("Disallowed signature algorithm: " + signedManifest.signatureAlgorithm());
        }
        final Optional<PublicKey> key = keyResolver.resolve(signedManifest.keyId());
        if (key.isEmpty()) {
            violations.add("Unknown signing key: " + signedManifest.keyId());
        } else if (violations.stream().noneMatch(value -> value.startsWith("Disallowed signature"))) {
            try {
                final Signature verifier = Signature.getInstance(signedManifest.signatureAlgorithm());
                verifier.initVerify(key.get());
                verifier.update(codec.canonicalBytes(manifest));
                if (!verifier.verify(signedManifest.signature())) {
                    violations.add("Toolchain manifest signature is invalid");
                }
            } catch (final GeneralSecurityException exception) {
                violations.add("Unable to verify toolchain signature: " + exception.getMessage());
            }
        }
        if (!violations.isEmpty()) {
            throw new ManifestValidationException(violations);
        }
    }

    public void verifyArchive(final Path archive, final ToolchainArchive metadata) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(metadata, "metadata");
        if (Files.size(archive) != metadata.size()) {
            throw new ManifestValidationException(List.of("Toolchain archive size does not match manifest"));
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archive)) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            final String actual = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    metadata.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new ManifestValidationException(List.of("Toolchain archive SHA-256 does not match manifest"));
            }
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Verifies the installed executable described by the already authenticated manifest. */
    public static void verifyInstalledDriver(
            final Path toolchainHome,
            final ToolchainManifest manifest,
            final NativeTarget currentHost
    ) throws IOException {
        Objects.requireNonNull(toolchainHome, "toolchainHome");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(currentHost, "currentHost");
        final String expected = manifest.hostDriverSha256().get(currentHost);
        if (expected == null || !SHA_256.matcher(expected).matches()) {
            throw new ManifestValidationException(List.of(
                    "Toolchain has no valid driver digest for current host " + currentHost.id()
            ));
        }
        final Path executable = ProcessNativeCompiler.executable(toolchainHome, currentHost);
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(executable) || Files.size(executable) == 0) {
            throw new ManifestValidationException(List.of("SkidLLVM driver is missing or empty: " + executable));
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(executable)) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            final byte[] expectedBytes = expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            final byte[] actualBytes = HexFormat.of().formatHex(digest.digest())
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
                throw new ManifestValidationException(List.of(
                        "Installed SkidLLVM driver SHA-256 does not match signed manifest"
                ));
            }
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @FunctionalInterface
    public interface KeyResolver {
        Optional<PublicKey> resolve(String keyId);
    }
}
