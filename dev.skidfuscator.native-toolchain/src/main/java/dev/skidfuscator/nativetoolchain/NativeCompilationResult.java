package dev.skidfuscator.nativetoolchain;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Paths and authenticated metadata produced by a compiler invocation. */
public record NativeCompilationResult(
        CompilerManifest manifest,
        Path canonicalLlvmIr,
        Map<NativeTarget, Path> libraries
) {
    public NativeCompilationResult {
        Objects.requireNonNull(manifest, "manifest");
        canonicalLlvmIr = Objects.requireNonNull(canonicalLlvmIr, "canonicalLlvmIr")
                .toAbsolutePath().normalize();
        Objects.requireNonNull(libraries, "libraries");
        final Map<NativeTarget, Path> normalized = new EnumMap<>(NativeTarget.class);
        libraries.forEach((target, path) -> normalized.put(
                Objects.requireNonNull(target, "library target"),
                Objects.requireNonNull(path, "library path").toAbsolutePath().normalize()
        ));
        libraries = Map.copyOf(normalized);
        if (!libraries.keySet().equals(manifest.artifacts().keySet())) {
            throw new IllegalArgumentException("Library paths must exactly match compiler manifest targets");
        }
    }
}
