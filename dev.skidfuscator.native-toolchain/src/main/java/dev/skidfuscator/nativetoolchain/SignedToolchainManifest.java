package dev.skidfuscator.nativetoolchain;

import java.util.Arrays;
import java.util.Objects;

/** A manifest plus a detached signature over its canonical byte encoding. */
public record SignedToolchainManifest(
        ToolchainManifest manifest,
        String signatureAlgorithm,
        String keyId,
        byte[] signature
) {
    public SignedToolchainManifest {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(signatureAlgorithm, "signatureAlgorithm");
        Objects.requireNonNull(keyId, "keyId");
        signature = Arrays.copyOf(Objects.requireNonNull(signature, "signature"), signature.length);
    }

    @Override
    public byte[] signature() {
        return Arrays.copyOf(signature, signature.length);
    }
}
