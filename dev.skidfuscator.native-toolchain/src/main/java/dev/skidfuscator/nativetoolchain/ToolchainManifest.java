package dev.skidfuscator.nativetoolchain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The signed release contract for one SkidLLVM version. */
public record ToolchainManifest(
        int schemaVersion,
        String toolchainVersion,
        int nativeIrAbi,
        Set<NativeTarget> supportedTargets,
        Map<NativeTarget, ToolchainArchive> hostArchives,
        Map<NativeTarget, String> hostDriverSha256,
        Map<NativeTarget, String> hostTreeSha256
) {
    public static final int CURRENT_SCHEMA = 2;
    public static final String PINNED_VERSION = "1.0.0-alpha.1";

    public ToolchainManifest {
        Objects.requireNonNull(toolchainVersion, "toolchainVersion");
        supportedTargets = Set.copyOf(Objects.requireNonNull(supportedTargets, "supportedTargets"));
        hostArchives = Map.copyOf(Objects.requireNonNull(hostArchives, "hostArchives"));
        hostDriverSha256 = Map.copyOf(Objects.requireNonNull(hostDriverSha256, "hostDriverSha256"));
        hostTreeSha256 = Map.copyOf(Objects.requireNonNull(hostTreeSha256, "hostTreeSha256"));
    }
}
