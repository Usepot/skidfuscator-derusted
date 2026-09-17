package dev.skidfuscator.nativetoolchain;

import java.util.Map;
import java.util.Objects;

/** Versioned manifest embedded alongside generated native libraries. */
public record CompilerManifest(
        int schemaVersion,
        int nativeIrAbi,
        String buildId,
        String moduleSha256,
        Map<NativeTarget, NativeArtifact> artifacts
) {
    public static final int CURRENT_SCHEMA = 1;

    public CompilerManifest {
        Objects.requireNonNull(buildId, "buildId");
        Objects.requireNonNull(moduleSha256, "moduleSha256");
        artifacts = Map.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
    }
}
