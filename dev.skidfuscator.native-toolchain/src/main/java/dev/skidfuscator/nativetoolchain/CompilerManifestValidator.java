package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeIrVersions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Validates an embedded compiler manifest before a native library is extracted. */
public final class CompilerManifestValidator {
    public static final long MAX_ARTIFACT_BYTES = 1024L * 1024L * 1024L;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BUILD_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public List<String> validate(final CompilerManifest manifest) {
        final List<String> violations = new ArrayList<>();
        if (manifest.schemaVersion() != CompilerManifest.CURRENT_SCHEMA) {
            violations.add("Unsupported compiler manifest schema: " + manifest.schemaVersion());
        }
        if (manifest.nativeIrAbi() != NativeIrVersions.CURRENT_ABI) {
            violations.add("Unsupported native IR ABI: " + manifest.nativeIrAbi());
        }
        if (!BUILD_ID.matcher(manifest.buildId()).matches()) {
            violations.add("Invalid build id");
        }
        if (!SHA_256.matcher(manifest.moduleSha256()).matches()) {
            violations.add("Invalid module SHA-256");
        }
        if (manifest.artifacts().isEmpty()) {
            violations.add("At least one native artifact is required");
        }
        for (final Map.Entry<NativeTarget, NativeArtifact> entry : manifest.artifacts().entrySet()) {
            final NativeArtifact artifact = entry.getValue();
            if (entry.getKey() != artifact.target()) {
                violations.add("Artifact map key does not match artifact target: " + entry.getKey().id());
            }
            final String expectedPrefix = "META-INF/skidfuscator/native/" + manifest.buildId() + "/"
                    + artifact.target().id() + "/";
            if (!artifact.resourcePath().startsWith(expectedPrefix)
                    || artifact.resourcePath().contains("..")
                    || artifact.resourcePath().startsWith("/")
                    || artifact.resourcePath().contains("\\")) {
                violations.add("Unsafe or non-canonical artifact resource path: " + artifact.resourcePath());
            }
            if (!SHA_256.matcher(artifact.sha256()).matches()) {
                violations.add("Invalid artifact SHA-256 for " + artifact.target().id());
            }
            if (artifact.size() <= 0 || artifact.size() > MAX_ARTIFACT_BYTES) {
                violations.add("Artifact size must be in 1.." + MAX_ARTIFACT_BYTES
                        + " bytes for " + artifact.target().id());
            }
        }
        return List.copyOf(violations);
    }

    public void validateOrThrow(final CompilerManifest manifest) {
        final List<String> violations = validate(manifest);
        if (!violations.isEmpty()) {
            throw new ManifestValidationException(violations);
        }
    }
}
