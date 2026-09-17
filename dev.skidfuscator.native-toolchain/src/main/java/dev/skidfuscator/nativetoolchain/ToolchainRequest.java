package dev.skidfuscator.nativetoolchain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable inputs to toolchain discovery and authentication. */
public record ToolchainRequest(
        ToolchainDelivery delivery,
        Optional<Path> explicitPath,
        String version,
        int nativeIrAbi,
        Set<NativeTarget> targets,
        NativeTarget currentHost
) {
    public ToolchainRequest {
        Objects.requireNonNull(delivery, "delivery");
        explicitPath = Objects.requireNonNull(explicitPath, "explicitPath").map(Path::toAbsolutePath).map(Path::normalize);
        Objects.requireNonNull(version, "version");
        targets = Set.copyOf(Objects.requireNonNull(targets, "targets"));
        Objects.requireNonNull(currentHost, "currentHost");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version cannot be blank");
        }
        if (nativeIrAbi <= 0) {
            throw new IllegalArgumentException("nativeIrAbi must be positive");
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("At least one target is required");
        }
        if (delivery == ToolchainDelivery.EXTERNAL && explicitPath.isEmpty()) {
            throw new IllegalArgumentException("EXTERNAL delivery requires an explicit path");
        }
    }
}
