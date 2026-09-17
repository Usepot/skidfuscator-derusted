package dev.skidfuscator.nativetoolchain;

import java.net.URI;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Canonical toolchain-manifest and signed-envelope serialization. */
public final class ToolchainManifestCodec {
    public byte[] canonicalBytes(final ToolchainManifest manifest) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("kind", "skidllvm-toolchain-manifest");
        values.put("schema", Integer.toString(manifest.schemaVersion()));
        values.put("version", manifest.toolchainVersion());
        values.put("nativeIrAbi", Integer.toString(manifest.nativeIrAbi()));
        values.put("targets", manifest.supportedTargets().stream()
                .sorted()
                .map(NativeTarget::id)
                .collect(Collectors.joining(",")));
        values.put("archive.count", Integer.toString(manifest.hostArchives().size()));
        int index = 0;
        for (final NativeTarget target : NativeTarget.values()) {
            final ToolchainArchive archive = manifest.hostArchives().get(target);
            if (archive == null) {
                continue;
            }
            final String prefix = "archive." + index++ + ".";
            values.put(prefix + "host", target.id());
            values.put(prefix + "uri", archive.uri().toString());
            values.put(prefix + "sha256", archive.sha256());
            values.put(prefix + "size", Long.toString(archive.size()));
        }
        values.put("driver.count", Integer.toString(manifest.hostDriverSha256().size()));
        index = 0;
        for (final NativeTarget target : NativeTarget.values()) {
            final String digest = manifest.hostDriverSha256().get(target);
            if (digest == null) {
                continue;
            }
            final String prefix = "driver." + index++ + ".";
            values.put(prefix + "host", target.id());
            values.put(prefix + "sha256", digest);
        }
        for (final NativeTarget target : NativeTarget.values()) {
            final String digest = manifest.hostTreeSha256().get(target);
            if (digest != null) {
                values.put("tree." + target.id() + ".sha256", digest);
            }
        }
        return CanonicalProperties.encode(values);
    }

    public ToolchainManifest decodeCanonical(final byte[] bytes) {
        final Map<String, String> values = CanonicalProperties.decode(bytes);
        CompilerManifestCodec.require(values, "kind", "skidllvm-toolchain-manifest");
        final Set<NativeTarget> targets = EnumSet.noneOf(NativeTarget.class);
        final String targetValue = CompilerManifestCodec.required(values, "targets");
        if (!targetValue.isBlank()) {
            for (final String target : targetValue.split(",")) {
                targets.add(NativeTarget.parse(target));
            }
        }
        final int count = CompilerManifestCodec.integer(values, "archive.count");
        if (count < 0 || count > NativeTarget.values().length) {
            throw new IllegalArgumentException("Invalid host archive count: " + count);
        }
        final Map<NativeTarget, ToolchainArchive> archives = new EnumMap<>(NativeTarget.class);
        for (int index = 0; index < count; index++) {
            final String prefix = "archive." + index + ".";
            final NativeTarget host = NativeTarget.parse(CompilerManifestCodec.required(values, prefix + "host"));
            final ToolchainArchive archive = new ToolchainArchive(
                    host,
                    URI.create(CompilerManifestCodec.required(values, prefix + "uri")),
                    CompilerManifestCodec.required(values, prefix + "sha256"),
                    CompilerManifestCodec.longValue(values, prefix + "size")
            );
            if (archives.put(host, archive) != null) {
                throw new IllegalArgumentException("Duplicate host archive: " + host.id());
            }
        }
        final int driverCount = CompilerManifestCodec.integer(values, "driver.count");
        if (driverCount < 0 || driverCount > NativeTarget.values().length) {
            throw new IllegalArgumentException("Invalid driver count: " + driverCount);
        }
        final Map<NativeTarget, String> driverDigests = new EnumMap<>(NativeTarget.class);
        for (int index = 0; index < driverCount; index++) {
            final String prefix = "driver." + index + ".";
            final NativeTarget host = NativeTarget.parse(CompilerManifestCodec.required(values, prefix + "host"));
            if (driverDigests.put(host, CompilerManifestCodec.required(values, prefix + "sha256")) != null) {
                throw new IllegalArgumentException("Duplicate host driver: " + host.id());
            }
        }
        final Map<NativeTarget, String> treeDigests = new EnumMap<>(NativeTarget.class);
        for (final NativeTarget host : NativeTarget.values()) {
            final String key = "tree." + host.id() + ".sha256";
            final String digest = values.get(key);
            if (digest != null) {
                treeDigests.put(host, digest);
            }
        }
        return new ToolchainManifest(
                CompilerManifestCodec.integer(values, "schema"),
                CompilerManifestCodec.required(values, "version"),
                CompilerManifestCodec.integer(values, "nativeIrAbi"),
                targets,
                archives,
                driverDigests,
                treeDigests
        );
    }

    public byte[] encodeSigned(final SignedToolchainManifest signedManifest) {
        final Map<String, String> envelope = new LinkedHashMap<>();
        envelope.put("kind", "skidllvm-signed-manifest");
        envelope.put("algorithm", signedManifest.signatureAlgorithm());
        envelope.put("keyId", signedManifest.keyId());
        envelope.put("manifest", Base64.getEncoder().encodeToString(canonicalBytes(signedManifest.manifest())));
        envelope.put("signature", Base64.getEncoder().encodeToString(signedManifest.signature()));
        return CanonicalProperties.encode(envelope);
    }

    public SignedToolchainManifest decodeSigned(final byte[] bytes) {
        final Map<String, String> envelope = CanonicalProperties.decode(bytes);
        CompilerManifestCodec.require(envelope, "kind", "skidllvm-signed-manifest");
        try {
            final byte[] manifestBytes = Base64.getDecoder().decode(
                    CompilerManifestCodec.required(envelope, "manifest"));
            final byte[] signature = Base64.getDecoder().decode(
                    CompilerManifestCodec.required(envelope, "signature"));
            return new SignedToolchainManifest(
                    decodeCanonical(manifestBytes),
                    CompilerManifestCodec.required(envelope, "algorithm"),
                    CompilerManifestCodec.required(envelope, "keyId"),
                    signature
            );
        } catch (final IllegalArgumentException exception) {
            throw new IllegalArgumentException("Malformed signed toolchain manifest", exception);
        }
    }
}
