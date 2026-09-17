package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeModule;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Complete, target-independent input to a native compiler implementation. */
public record NativeCompilationRequest(
        NativeModule module,
        ResolvedToolchain toolchain,
        Path outputDirectory,
        Set<NativeTarget> targets,
        NativeTarget currentHost,
        String buildId,
        VmProtectionSettings vmProtection
) {
    private static final Pattern BUILD_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public NativeCompilationRequest {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(toolchain, "toolchain");
        outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        targets = Set.copyOf(Objects.requireNonNull(targets, "targets"));
        Objects.requireNonNull(currentHost, "currentHost");
        Objects.requireNonNull(buildId, "buildId");
        Objects.requireNonNull(vmProtection, "vmProtection");
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("At least one compilation target is required");
        }
        if (!BUILD_ID.matcher(buildId).matches()) {
            throw new IllegalArgumentException("buildId must contain only letters, digits, '.', '_' or '-'");
        }
        if (!toolchain.manifest().supportedTargets().containsAll(targets)) {
            throw new IllegalArgumentException("Resolved toolchain does not support all compilation targets");
        }
        if (module.abiVersion() != toolchain.manifest().nativeIrAbi()) {
            throw new IllegalArgumentException("Native module ABI does not match the resolved toolchain");
        }
    }

    public NativeCompilationRequest(
            final NativeModule module,
            final ResolvedToolchain toolchain,
            final Path outputDirectory,
            final Set<NativeTarget> targets,
            final NativeTarget currentHost,
            final String buildId
    ) {
        this(module, toolchain, outputDirectory, targets, currentHost, buildId,
                VmProtectionSettings.aggressive());
    }
}
