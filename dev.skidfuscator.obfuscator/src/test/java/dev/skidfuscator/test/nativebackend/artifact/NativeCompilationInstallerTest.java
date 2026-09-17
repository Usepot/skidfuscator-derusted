package dev.skidfuscator.test.nativebackend.artifact;

import dev.skidfuscator.nativeir.NativeIrVersions;
import dev.skidfuscator.nativetoolchain.CompilerManifest;
import dev.skidfuscator.nativetoolchain.CompilerManifestCodec;
import dev.skidfuscator.nativetoolchain.ManifestValidationException;
import dev.skidfuscator.nativetoolchain.NativeArtifact;
import dev.skidfuscator.nativetoolchain.NativeCompilationResult;
import dev.skidfuscator.nativetoolchain.NativeTarget;
import dev.skidfuscator.obfuscator.nativebackend.artifact.NativeCompilationInstaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.topdank.byteengineer.commons.data.JarContents;
import org.topdank.byteengineer.commons.data.JarResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCompilationInstallerTest {
    private static final String BUILD_ID = "build-42";
    private static final byte[] MODULE_BYTES = bytes("; canonical llvm ir\n");

    @TempDir
    Path temporaryDirectory;

    @Test
    void installsVerifiedLibrariesAndBuildLevelManifest() throws IOException {
        final NativeCompilationResult result = compilation(
                Map.of(
                        NativeTarget.WINDOWS_X86_64, bytes("windows library"),
                        NativeTarget.LINUX_AARCH64, bytes("linux library")
                ),
                null
        );
        final JarContents contents = contentsWithSentinel();

        final List<String> installed = new NativeCompilationInstaller().install(
                contents,
                result,
                Arrays.asList(NativeTarget.LINUX_AARCH64, NativeTarget.WINDOWS_X86_64)
        );

        assertEquals(List.of(
                NativeCompilationInstaller.manifestResourcePath(BUILD_ID),
                resourcePath(NativeTarget.WINDOWS_X86_64),
                resourcePath(NativeTarget.LINUX_AARCH64)
        ), installed);
        assertEquals(4, contents.getResourceContents().size());
        assertArrayEquals(bytes("sentinel"), resource(contents, "assets/sentinel.bin").getData());
        assertArrayEquals(bytes("windows library"),
                resource(contents, resourcePath(NativeTarget.WINDOWS_X86_64)).getData());
        assertArrayEquals(bytes("linux library"),
                resource(contents, resourcePath(NativeTarget.LINUX_AARCH64)).getData());

        final byte[] encodedManifest = resource(contents,
                NativeCompilationInstaller.manifestResourcePath(BUILD_ID)).getData();
        assertEquals(result.manifest(), new CompilerManifestCodec().decode(encodedManifest));
    }

    @Test
    void rejectsRequestedTargetMismatchWithoutChangingDestination() throws IOException {
        final NativeCompilationResult result = compilation(
                Map.of(NativeTarget.WINDOWS_X86_64, bytes("library")),
                null
        );
        final JarContents contents = contentsWithSentinel();

        assertThrows(IllegalArgumentException.class, () -> new NativeCompilationInstaller().install(
                contents,
                result,
                List.of(NativeTarget.WINDOWS_X86_64, NativeTarget.LINUX_X86_64)
        ));

        assertUnchanged(contents);
    }

    @Test
    void rejectsInvalidManifestBeforeChangingDestination() throws IOException {
        final NativeCompilationResult result = compilation(
                Map.of(NativeTarget.WINDOWS_X86_64, bytes("library")),
                builder -> builder.schemaVersion = CompilerManifest.CURRENT_SCHEMA + 1
        );
        final JarContents contents = contentsWithSentinel();

        assertThrows(ManifestValidationException.class, () -> new NativeCompilationInstaller().install(
                contents,
                result,
                List.of(NativeTarget.WINDOWS_X86_64)
        ));

        assertUnchanged(contents);
    }

    @Test
    void rejectsLibrarySizeAndDigestMismatchWithoutChangingDestination() throws IOException {
        final NativeCompilationResult wrongSize = compilation(
                Map.of(NativeTarget.WINDOWS_X86_64, bytes("library")),
                builder -> builder.sizeDelta = 1
        );
        final JarContents sizeContents = contentsWithSentinel();
        final IOException sizeFailure = assertThrows(IOException.class,
                () -> new NativeCompilationInstaller().install(
                        sizeContents, wrongSize, List.of(NativeTarget.WINDOWS_X86_64)));
        assertTrue(sizeFailure.getMessage().contains("size")
                && sizeFailure.getMessage().contains("match"), sizeFailure::getMessage);
        assertUnchanged(sizeContents);

        final NativeCompilationResult wrongDigest = compilation(
                Map.of(NativeTarget.WINDOWS_X86_64, bytes("library")),
                builder -> builder.digestOverride = "b".repeat(64)
        );
        final JarContents digestContents = contentsWithSentinel();
        final IOException digestFailure = assertThrows(IOException.class,
                () -> new NativeCompilationInstaller().install(
                        digestContents, wrongDigest, List.of(NativeTarget.WINDOWS_X86_64)));
        assertTrue(digestFailure.getMessage().contains("SHA-256 mismatch"), digestFailure::getMessage);
        assertUnchanged(digestContents);
    }

    @Test
    void rejectsDestinationCollisionWithoutChangingDestination() throws IOException {
        final NativeCompilationResult result = compilation(
                Map.of(NativeTarget.WINDOWS_X86_64, bytes("library")),
                null
        );
        final JarContents contents = contentsWithSentinel();
        contents.getResourceContents().add(new JarResource(
                resourcePath(NativeTarget.WINDOWS_X86_64), bytes("existing")));

        final IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new NativeCompilationInstaller().install(
                        contents, result, List.of(NativeTarget.WINDOWS_X86_64)));

        assertTrue(failure.getMessage().contains("collides"));
        assertEquals(2, contents.getResourceContents().size());
        assertArrayEquals(bytes("existing"),
                resource(contents, resourcePath(NativeTarget.WINDOWS_X86_64)).getData());
    }

    @Test
    void readFailureDoesNotLeaveAnInstalledManifest() throws IOException {
        final NativeCompilationResult result = compilation(
                Map.of(NativeTarget.WINDOWS_X86_64, bytes("library")),
                null
        );
        Files.delete(result.libraries().get(NativeTarget.WINDOWS_X86_64));
        final JarContents contents = contentsWithSentinel();

        assertThrows(IOException.class, () -> new NativeCompilationInstaller().install(
                contents,
                result,
                List.of(NativeTarget.WINDOWS_X86_64)
        ));

        assertUnchanged(contents);
    }

    @Test
    void rejectsCanonicalModuleDigestMismatchWithoutChangingDestination() throws IOException {
        final NativeCompilationResult result = compilation(
                Map.of(NativeTarget.WINDOWS_X86_64, bytes("library")),
                builder -> builder.moduleDigestOverride = "c".repeat(64)
        );
        final JarContents contents = contentsWithSentinel();

        assertThrows(IOException.class, () -> new NativeCompilationInstaller().install(
                contents,
                result,
                List.of(NativeTarget.WINDOWS_X86_64)
        ));

        assertUnchanged(contents);
    }

    @Test
    void rejectsUnsafeBuildIdsInPublicResourcePathHelper() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeCompilationInstaller.manifestResourcePath("../escape")
        );
    }

    private NativeCompilationResult compilation(
            final Map<NativeTarget, byte[]> libraryContents,
            final java.util.function.Consumer<ManifestBuilder> customization
    ) throws IOException {
        final Map<NativeTarget, Path> libraries = new EnumMap<>(NativeTarget.class);
        final Map<NativeTarget, NativeArtifact> artifacts = new EnumMap<>(NativeTarget.class);
        final ManifestBuilder builder = new ManifestBuilder();
        if (customization != null) {
            customization.accept(builder);
        }

        for (final NativeTarget target : NativeTarget.values()) {
            final byte[] bytes = libraryContents.get(target);
            if (bytes == null) {
                continue;
            }
            final Path library = temporaryDirectory.resolve(target.id()).resolve(target.libraryFileName("skid-" + BUILD_ID));
            Files.createDirectories(library.getParent());
            Files.write(library, bytes);
            libraries.put(target, library);
            artifacts.put(target, new NativeArtifact(
                    target,
                    resourcePath(target),
                    builder.digestOverride == null ? sha256(bytes) : builder.digestOverride,
                    bytes.length + builder.sizeDelta
            ));
        }

        final Path canonicalLlvmIr = temporaryDirectory.resolve(BUILD_ID + ".ll");
        Files.write(canonicalLlvmIr, MODULE_BYTES);
        final CompilerManifest manifest = new CompilerManifest(
                builder.schemaVersion,
                NativeIrVersions.CURRENT_ABI,
                BUILD_ID,
                builder.moduleDigestOverride == null ? sha256(MODULE_BYTES) : builder.moduleDigestOverride,
                artifacts
        );
        return new NativeCompilationResult(
                manifest,
                canonicalLlvmIr,
                libraries
        );
    }

    private static JarContents contentsWithSentinel() {
        final JarContents contents = new JarContents();
        contents.getResourceContents().add(new JarResource("assets/sentinel.bin", bytes("sentinel")));
        return contents;
    }

    private static void assertUnchanged(final JarContents contents) {
        assertEquals(1, contents.getResourceContents().size());
        assertArrayEquals(bytes("sentinel"), contents.getResourceContents().get(0).getData());
    }

    private static JarResource resource(final JarContents contents, final String path) {
        final JarResource resource = contents.getResourceContents().namedMap().get(path);
        if (resource == null) {
            throw new AssertionError("Missing resource: " + path);
        }
        return resource;
    }

    private static String resourcePath(final NativeTarget target) {
        return NativeCompilationInstaller.NATIVE_RESOURCE_PREFIX + BUILD_ID + "/" + target.id()
                + "/" + target.libraryFileName("skid-" + BUILD_ID);
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(final byte[] bytes) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder value = new StringBuilder(digest.length * 2);
            for (final byte item : digest) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class ManifestBuilder {
        private int schemaVersion = CompilerManifest.CURRENT_SCHEMA;
        private int sizeDelta;
        private String digestOverride;
        private String moduleDigestOverride;
    }
}
