package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** Loads pinned X.509 public keys from the application classpath. No private key is accepted. */
public final class ClasspathPublicKeyResolver implements ToolchainManifestVerifier.KeyResolver {
    private static final int MAX_KEY_BYTES = 16 * 1024;
    private final Map<String, PublicKey> keys;

    public ClasspathPublicKeyResolver(final ClassLoader resources) throws IOException {
        Objects.requireNonNull(resources, "resources");
        final Properties index = new Properties();
        try (InputStream input = resources.getResourceAsStream(ToolchainResourcePaths.KEY_INDEX)) {
            if (input == null) {
                this.keys = Map.of();
                return;
            }
            index.load(new ByteArrayInputStream(ToolchainManifestFiles.readBounded(
                    input, ToolchainManifestFiles.MAX_MANIFEST_BYTES, "Signing key index")));
        }
        final Map<String, PublicKey> loaded = new HashMap<>();
        for (final String keyId : index.stringPropertyNames()) {
            if (!keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IOException("Invalid SkidLLVM signing key id: " + keyId);
            }
            final String descriptor = index.getProperty(keyId).trim();
            final int separator = descriptor.indexOf(':');
            if (separator <= 0 || separator == descriptor.length() - 1) {
                throw new IOException("Invalid signing key descriptor for " + keyId);
            }
            final String algorithm = descriptor.substring(0, separator);
            final String resource = descriptor.substring(separator + 1);
            if (!(algorithm.equals("Ed25519") || algorithm.equals("RSA"))) {
                throw new IOException("Unsupported signing key algorithm for " + keyId);
            }
            if (!resource.startsWith(ToolchainResourcePaths.KEY_ROOT)
                    || resource.contains("..") || resource.startsWith("/")) {
                throw new IOException("Signing key resource escapes the pinned key directory");
            }
            final byte[] encoded;
            try (InputStream input = resources.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("Pinned signing key resource is missing: " + resource);
                }
                encoded = ToolchainManifestFiles.readBounded(input, MAX_KEY_BYTES, "Pinned signing key");
            }
            try {
                loaded.put(keyId, KeyFactory.getInstance(algorithm)
                        .generatePublic(new X509EncodedKeySpec(encoded)));
            } catch (final GeneralSecurityException exception) {
                throw new IOException("Invalid pinned signing key: " + keyId, exception);
            }
        }
        this.keys = Map.copyOf(loaded);
    }

    @Override
    public Optional<PublicKey> resolve(final String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }
}
