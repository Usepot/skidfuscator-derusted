package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeIrVersions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolchainManifestVerifierTest {
    @Test
    void authenticatesCanonicalManifestAndContract() throws Exception {
        final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final ToolchainManifest manifest = manifest();
        final SignedToolchainManifest signed = sign(manifest, keyPair);
        final ToolchainManifestVerifier verifier = new ToolchainManifestVerifier(
                keyId -> "release-key".equals(keyId) ? Optional.of(keyPair.getPublic()) : Optional.empty());

        assertDoesNotThrow(() -> verifier.verify(
                signed,
                ToolchainManifest.PINNED_VERSION,
                NativeIrVersions.CURRENT_ABI,
                Set.of(NativeTarget.LINUX_AARCH64),
                NativeTarget.WINDOWS_X86_64
        ));
    }

    @Test
    void rejectsTamperedManifest() throws Exception {
        final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final SignedToolchainManifest signed = sign(manifest(), keyPair);
        final ToolchainManifest tampered = new ToolchainManifest(
                signed.manifest().schemaVersion(),
                "different-version",
                signed.manifest().nativeIrAbi(),
                signed.manifest().supportedTargets(),
                signed.manifest().hostArchives(),
                signed.manifest().hostDriverSha256()
        );
        final SignedToolchainManifest tamperedEnvelope = new SignedToolchainManifest(
                tampered, signed.signatureAlgorithm(), signed.keyId(), signed.signature());
        final ToolchainManifestVerifier verifier = new ToolchainManifestVerifier(
                keyId -> Optional.of(keyPair.getPublic()));

        assertThrows(ManifestValidationException.class, () -> verifier.verify(
                tamperedEnvelope,
                "different-version",
                NativeIrVersions.CURRENT_ABI,
                Set.of(NativeTarget.LINUX_AARCH64),
                NativeTarget.WINDOWS_X86_64
        ));
    }

    static ToolchainManifest manifest() {
        return new ToolchainManifest(
                ToolchainManifest.CURRENT_SCHEMA,
                ToolchainManifest.PINNED_VERSION,
                NativeIrVersions.CURRENT_ABI,
                Set.of(NativeTarget.values()),
                Map.of(NativeTarget.WINDOWS_X86_64, new ToolchainArchive(
                        NativeTarget.WINDOWS_X86_64,
                        URI.create("https://example.invalid/skidllvm-windows-x86_64.zip"),
                        "c".repeat(64),
                        100
                )),
                Map.of(NativeTarget.WINDOWS_X86_64, driverDigest())
        );
    }

    static String driverDigest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1}));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static SignedToolchainManifest sign(final ToolchainManifest manifest, final KeyPair keyPair) throws Exception {
        final Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(new ToolchainManifestCodec().canonicalBytes(manifest));
        return new SignedToolchainManifest(manifest, "Ed25519", "release-key", signature.sign());
    }
}
