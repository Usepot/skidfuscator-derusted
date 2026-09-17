package dev.skidfuscator.nativetoolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathPublicKeyResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsOnlyPinnedPublicKeyResourcesFromTheDeclaredIndex() throws Exception {
        final KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Path index = temporaryDirectory.resolve(ToolchainResourcePaths.KEY_INDEX);
        final Path key = temporaryDirectory.resolve(ToolchainResourcePaths.KEY_ROOT + "release.der");
        Files.createDirectories(index.getParent());
        Files.createDirectories(key.getParent());
        Files.writeString(index, "release-key=Ed25519:"
                + ToolchainResourcePaths.KEY_ROOT + "release.der\n", StandardCharsets.ISO_8859_1);
        Files.write(key, pair.getPublic().getEncoded());

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{temporaryDirectory.toUri().toURL()}, null)) {
            final ClasspathPublicKeyResolver resolver = new ClasspathPublicKeyResolver(loader);
            assertArrayEquals(pair.getPublic().getEncoded(), resolver.resolve("release-key").orElseThrow().getEncoded());
            assertTrue(resolver.resolve("unknown").isEmpty());
        }
    }
}
