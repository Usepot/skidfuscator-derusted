package dev.skidfuscator.nativetoolchain;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic, dependency-free compiler manifest serialization. */
public final class CompilerManifestCodec {
    public byte[] encode(final CompilerManifest manifest) {
        new CompilerManifestValidator().validateOrThrow(manifest);
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("kind", "skidfuscator-compiler-manifest");
        values.put("schema", Integer.toString(manifest.schemaVersion()));
        values.put("nativeIrAbi", Integer.toString(manifest.nativeIrAbi()));
        values.put("buildId", manifest.buildId());
        values.put("moduleSha256", manifest.moduleSha256());
        values.put("artifact.count", Integer.toString(manifest.artifacts().size()));
        int index = 0;
        for (final NativeTarget target : NativeTarget.values()) {
            final NativeArtifact artifact = manifest.artifacts().get(target);
            if (artifact == null) {
                continue;
            }
            final String prefix = "artifact." + index++ + ".";
            values.put(prefix + "target", target.id());
            values.put(prefix + "path", artifact.resourcePath());
            values.put(prefix + "sha256", artifact.sha256());
            values.put(prefix + "size", Long.toString(artifact.size()));
        }
        return CanonicalProperties.encode(values);
    }

    public CompilerManifest decode(final byte[] bytes) {
        final Map<String, String> values = CanonicalProperties.decode(bytes);
        require(values, "kind", "skidfuscator-compiler-manifest");
        final int count = integer(values, "artifact.count");
        if (count < 0 || count > NativeTarget.values().length) {
            throw new IllegalArgumentException("Invalid artifact count: " + count);
        }
        final Map<NativeTarget, NativeArtifact> artifacts = new EnumMap<>(NativeTarget.class);
        for (int index = 0; index < count; index++) {
            final String prefix = "artifact." + index + ".";
            final NativeTarget target = NativeTarget.parse(required(values, prefix + "target"));
            final NativeArtifact artifact = new NativeArtifact(
                    target,
                    required(values, prefix + "path"),
                    required(values, prefix + "sha256"),
                    longValue(values, prefix + "size")
            );
            if (artifacts.put(target, artifact) != null) {
                throw new IllegalArgumentException("Duplicate artifact target: " + target.id());
            }
        }
        final CompilerManifest manifest = new CompilerManifest(
                integer(values, "schema"),
                integer(values, "nativeIrAbi"),
                required(values, "buildId"),
                required(values, "moduleSha256"),
                artifacts
        );
        new CompilerManifestValidator().validateOrThrow(manifest);
        return manifest;
    }

    static String required(final Map<String, String> values, final String key) {
        final String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing manifest property: " + key);
        }
        return value;
    }

    static void require(final Map<String, String> values, final String key, final String expected) {
        if (!expected.equals(required(values, key))) {
            throw new IllegalArgumentException("Unexpected value for manifest property: " + key);
        }
    }

    static int integer(final Map<String, String> values, final String key) {
        try {
            return Integer.parseInt(required(values, key));
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer manifest property: " + key, exception);
        }
    }

    static long longValue(final Map<String, String> values, final String key) {
        try {
            return Long.parseLong(required(values, key));
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid long manifest property: " + key, exception);
        }
    }
}
