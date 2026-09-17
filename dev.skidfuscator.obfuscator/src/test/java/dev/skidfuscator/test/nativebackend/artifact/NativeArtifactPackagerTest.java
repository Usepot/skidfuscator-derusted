package dev.skidfuscator.test.nativebackend.artifact;

import dev.skidfuscator.config.nativeobfuscation.NativeTarget;
import dev.skidfuscator.obfuscator.nativebackend.artifact.NativeArtifactPackager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeArtifactPackagerTest {
    private static final String PREFIX = NativeArtifactPackager.NATIVE_RESOURCE_PREFIX;
    private static final long ENTRY_TIME = 1_700_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsPlatformJarsWithOnlyTheMatchingNativeResources() throws IOException {
        Path universal = temporaryDirectory.resolve("protected-app.jar");
        Map<String, byte[]> contents = universalContents();
        writeJar(universal, contents);

        Path artifactDirectory = temporaryDirectory.resolve("protected-app-native");
        Map<NativeTarget, Path> artifacts = NativeArtifactPackager.packagePlatformJars(
                universal,
                artifactDirectory,
                Arrays.asList(NativeTarget.LINUX_AARCH64, NativeTarget.WINDOWS_X86_64)
        );

        assertEquals(
                Arrays.asList(NativeTarget.WINDOWS_X86_64, NativeTarget.LINUX_AARCH64),
                Arrays.asList(artifacts.keySet().toArray(new NativeTarget[0]))
        );
        Path windowsJar = artifactDirectory.resolve("protected-app-windows-x86_64.jar");
        Path linuxJar = artifactDirectory.resolve("protected-app-linux-aarch64.jar");
        assertEquals(windowsJar.toAbsolutePath(), artifacts.get(NativeTarget.WINDOWS_X86_64));
        assertEquals(linuxJar.toAbsolutePath(), artifacts.get(NativeTarget.LINUX_AARCH64));

        Set<String> commonEntries = new TreeSet<>(Arrays.asList(
                "META-INF/MANIFEST.MF",
                "assets/config.bin",
                "demo/Main.class"
        ));
        Set<String> expectedWindows = new TreeSet<>(commonEntries);
        expectedWindows.add(PREFIX + "build-123/manifest.properties");
        expectedWindows.add(PREFIX + "build-123/windows-x86_64/skid.dll");
        expectedWindows.add(PREFIX + "build-456/manifest.properties");
        expectedWindows.add(PREFIX + "build-456/windows-x86_64/secondary.dll");

        Set<String> expectedLinux = new TreeSet<>(commonEntries);
        expectedLinux.add(PREFIX + "build-123/manifest.properties");
        expectedLinux.add(PREFIX + "build-123/linux-aarch64/libskid.so");

        assertEquals(expectedWindows, entryNames(windowsJar));
        assertEquals(expectedLinux, entryNames(linuxJar));
        assertArrayEquals(contents.get("demo/Main.class"), readEntry(windowsJar, "demo/Main.class"));
        assertArrayEquals(
                contents.get(PREFIX + "build-123/linux-aarch64/libskid.so"),
                readEntry(linuxJar, PREFIX + "build-123/linux-aarch64/libskid.so")
        );
        assertTrue(Files.isRegularFile(universal), "The universal jar must remain untouched");
    }

    @Test
    void createsOneStrictlyFilteredArtifactForEverySupportedTarget() throws IOException {
        final Path universal = temporaryDirectory.resolve("six-targets.jar");
        writeJar(universal, universalContents());

        final Map<NativeTarget, Path> artifacts = NativeArtifactPackager.packagePlatformJars(
                universal, temporaryDirectory.resolve("six-targets-native"), EnumSet.allOf(NativeTarget.class));

        assertEquals(EnumSet.allOf(NativeTarget.class), artifacts.keySet());
        for (final NativeTarget target : NativeTarget.values()) {
            final Set<String> nativeLibraries = entryNames(artifacts.get(target)).stream()
                    .filter(name -> name.startsWith(PREFIX) && !name.endsWith("manifest.properties"))
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(nativeLibraries.contains(
                    PREFIX + "build-123/" + target.getId() + "/" + libraryName(target)));
            assertTrue(nativeLibraries.stream().allMatch(
                    name -> name.contains("/" + target.getId() + "/")),
                    () -> "foreign native contents in artifact for " + target.getId() + ": " + nativeLibraries);
        }
    }

    @Test
    void outputIsDeterministicAndPreservesSafeZipMetadata() throws IOException {
        Path universal = temporaryDirectory.resolve("metadata.jar");
        writeJar(universal, universalContents());

        Path first = NativeArtifactPackager.packagePlatformJars(
                universal,
                temporaryDirectory.resolve("first"),
                Collections.singleton(NativeTarget.WINDOWS_X86_64)
        ).get(NativeTarget.WINDOWS_X86_64);
        Path second = NativeArtifactPackager.packagePlatformJars(
                universal,
                temporaryDirectory.resolve("second"),
                Collections.singleton(NativeTarget.WINDOWS_X86_64)
        ).get(NativeTarget.WINDOWS_X86_64);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));

        try (JarFile input = new JarFile(universal.toFile());
             JarFile output = new JarFile(first.toFile())) {
            JarEntry sourceEntry = input.getJarEntry("assets/config.bin");
            JarEntry outputEntry = output.getJarEntry("assets/config.bin");
            assertEquals(sourceEntry.getMethod(), outputEntry.getMethod());
            assertEquals(sourceEntry.getSize(), outputEntry.getSize());
            assertEquals(sourceEntry.getCrc(), outputEntry.getCrc());
            assertEquals(sourceEntry.getTime(), outputEntry.getTime());
            assertEquals(sourceEntry.getComment(), outputEntry.getComment());
        }
    }

    @Test
    void validatesEveryRequestedTargetBeforeCreatingArtifacts() throws IOException {
        Path universal = temporaryDirectory.resolve("windows-only.jar");
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("demo/Main.class", bytes("class"));
        contents.put(PREFIX + "build/windows-x86_64/skid.dll", bytes("windows"));
        writeJar(universal, contents);

        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        IOException failure = assertThrows(
                IOException.class,
                () -> NativeArtifactPackager.packagePlatformJars(
                        universal,
                        artifactDirectory,
                        EnumSet.of(NativeTarget.WINDOWS_X86_64, NativeTarget.LINUX_X86_64)
                )
        );

        assertTrue(failure.getMessage().contains("linux-x86_64"));
        assertFalse(Files.exists(artifactDirectory), "Validation failure must not leave partial output");
    }

    @Test
    void failedPackagingDoesNotReplaceAnExistingArtifactOrLeaveTemporaryFiles() throws IOException {
        Path invalidUniversal = temporaryDirectory.resolve("invalid.jar");
        Files.write(invalidUniversal, bytes("not a jar"));
        Path artifactDirectory = temporaryDirectory.resolve("artifacts");
        Files.createDirectories(artifactDirectory);
        Path existing = NativeArtifactPackager.platformJarPath(
                invalidUniversal,
                artifactDirectory,
                NativeTarget.WINDOWS_X86_64
        );
        Files.write(existing, bytes("existing"));

        assertThrows(
                IOException.class,
                () -> NativeArtifactPackager.packagePlatformJars(
                        invalidUniversal,
                        artifactDirectory,
                        Collections.singleton(NativeTarget.WINDOWS_X86_64)
                )
        );

        assertArrayEquals(bytes("existing"), Files.readAllBytes(existing));
        try (java.util.stream.Stream<Path> files = Files.list(artifactDirectory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void rejectsEmptyTargetsAndNonJarNames() throws IOException {
        Path universal = temporaryDirectory.resolve("app.jar");
        writeJar(universal, universalContents());

        assertThrows(
                IllegalArgumentException.class,
                () -> NativeArtifactPackager.packagePlatformJars(
                        universal,
                        temporaryDirectory.resolve("artifacts"),
                        Collections.emptyList()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeArtifactPackager.platformJarPath(
                        temporaryDirectory.resolve("app.zip"),
                        temporaryDirectory.resolve("artifacts"),
                        NativeTarget.WINDOWS_X86_64
                )
        );
    }

    private static Map<String, byte[]> universalContents() {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("demo/Main.class", bytes("class bytes"));
        contents.put("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\n\r\n"));
        contents.put("assets/config.bin", bytes("configuration"));
        contents.put(PREFIX + "build-123/linux-aarch64/libskid.so", bytes("linux"));
        contents.put(PREFIX + "build-123/windows-x86_64/skid.dll", bytes("windows"));
        contents.put(PREFIX + "build-123/windows-aarch64/skid.dll", bytes("windows arm"));
        contents.put(PREFIX + "build-123/linux-x86_64/libskid.so", bytes("linux x64"));
        contents.put(PREFIX + "build-123/macos-x86_64/libskid.dylib", bytes("mac x64"));
        contents.put(PREFIX + "build-123/macos-aarch64/libskid.dylib", bytes("mac arm"));
        contents.put(PREFIX + "build-123/manifest.properties", bytes("digest=abc"));
        contents.put(PREFIX + "build-456/windows-x86_64/secondary.dll", bytes("secondary"));
        contents.put(PREFIX + "build-456/manifest.properties", bytes("digest=def"));
        // Native-root metadata is intentionally not copied: platform jars contain only their target subtree.
        contents.put(PREFIX + "native-index.json", bytes("universal only"));
        return contents;
    }

    private static String libraryName(final NativeTarget target) {
        return switch (target) {
            case WINDOWS_X86_64, WINDOWS_AARCH64 -> "skid.dll";
            case LINUX_X86_64, LINUX_AARCH64 -> "libskid.so";
            case MACOS_X86_64, MACOS_AARCH64 -> "libskid.dylib";
        };
    }

    private static void writeJar(final Path jar, final Map<String, byte[]> contents) throws IOException {
        try (OutputStream fileOutput = Files.newOutputStream(jar);
             JarOutputStream output = new JarOutputStream(fileOutput)) {
            for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(ENTRY_TIME);
                if (entry.getKey().equals("assets/config.bin")) {
                    CRC32 crc = new CRC32();
                    crc.update(entry.getValue());
                    jarEntry.setMethod(ZipEntry.STORED);
                    jarEntry.setSize(entry.getValue().length);
                    jarEntry.setCompressedSize(entry.getValue().length);
                    jarEntry.setCrc(crc.getValue());
                    jarEntry.setComment("preserved comment");
                }
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static Set<String> entryNames(final Path jar) throws IOException {
        Set<String> names = new TreeSet<>();
        try (JarFile input = new JarFile(jar.toFile())) {
            input.stream().forEach(entry -> names.add(entry.getName()));
        }
        return names;
    }

    private static byte[] readEntry(final Path jar, final String entryName) throws IOException {
        try (JarFile input = new JarFile(jar.toFile())) {
            JarEntry entry = input.getJarEntry(entryName);
            try (InputStream stream = input.getInputStream(entry)) {
                return readAll(stream);
            }
        }
    }

    private static byte[] readAll(final InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
