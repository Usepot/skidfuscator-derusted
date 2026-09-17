package dev.skidfuscator.test.nativebackend.runtime;

import dev.skidfuscator.nativeir.NativeIrVersions;
import dev.skidfuscator.nativetoolchain.CompilerManifest;
import dev.skidfuscator.nativetoolchain.CompilerManifestCodec;
import dev.skidfuscator.nativetoolchain.NativeArtifact;
import dev.skidfuscator.nativetoolchain.NativeTarget;
import dev.skidfuscator.obfuscator.nativebackend.artifact.NativeCompilationInstaller;
import dev.skidfuscator.obfuscator.nativebackend.runtime.NativeLoaderGenerator;
import dev.skidfuscator.obfuscator.nativebackend.runtime.NativeLoaderSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLoaderGeneratorTest {
    private static final String BUILD_ID = "loader-test";
    private static final String LOADER_NAME = "hidden/runtime/Loader42";

    @Test
    void emitsJava8LoaderAndNormalizesAllSupportedAliases() throws Exception {
        final Fixture fixture = fixture();
        final NativeLoaderGenerator.GeneratedLoader generated =
                new NativeLoaderGenerator().generate(fixture.spec());
        final org.objectweb.asm.tree.ClassNode parsed = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(generated.bytecode()).accept(parsed, 0);
        assertEquals(Opcodes.V1_8, parsed.version);
        final org.objectweb.asm.tree.MethodNode ensureLoaded = parsed.methods.stream()
                .filter(method -> method.name.equals("ensureLoaded"))
                .findFirst().orElseThrow();
        assertTrue(ensureLoaded.instructions.iterator().hasNext());
        assertTrue(java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(ensureLoaded.instructions.iterator(), 0), false)
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.owner.equals("java/lang/System") && call.name.equals("load")));
        assertTrue(java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(ensureLoaded.instructions.iterator(), 0), false)
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.owner.equals("java/nio/file/Files")
                        && call.name.equals("createTempDirectory")),
                "each generated loader must use a collision-safe class-loader-specific directory");

        final Class<?> type = new ResourceClassLoader(Map.of()).define(
                LOADER_NAME.replace('/', '.'), generated.bytecode());
        final Method normalize = type.getDeclaredMethod("normalizePlatform", String.class, String.class);
        normalize.setAccessible(true);
        assertEquals("windows-x86_64", normalize.invoke(null, "Windows 11", "amd64"));
        assertEquals("windows-aarch64", normalize.invoke(null, "Windows 11", "ARM64"));
        assertEquals("linux-x86_64", normalize.invoke(null, "Linux", "x64"));
        assertEquals("linux-aarch64", normalize.invoke(null, "Linux", "aarch64"));
        assertEquals("macos-x86_64", normalize.invoke(null, "Mac OS X", "x86_64"));
        assertEquals("macos-aarch64", normalize.invoke(null, "Darwin", "arm64"));
        assertEquals(null, normalize.invoke(null, "Solaris", "sparcv9"));
    }

    @Test
    @ResourceLock("system-properties")
    void authenticatesManifestBeforeReadingOrExtractingLibrary() throws Exception {
        final Fixture fixture = fixture();
        final NativeLoaderGenerator.GeneratedLoader generated =
                new NativeLoaderGenerator().generate(fixture.spec());
        final Map<String, byte[]> resources = new HashMap<>(fixture.resources());
        final byte[] tampered = resources.get(fixture.spec().manifestResourcePath()).clone();
        tampered[tampered.length / 2] ^= 1;
        resources.put(fixture.spec().manifestResourcePath(), tampered);
        final ResourceClassLoader classLoader = new ResourceClassLoader(resources);
        final Class<?> type = classLoader.define(LOADER_NAME.replace('/', '.'), generated.bytecode());
        final String oldOs = System.getProperty("os.name");
        final String oldArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            final InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> type.getMethod("ensureLoaded").invoke(null));
            final UnsatisfiedLinkError outer = assertInstanceOf(UnsatisfiedLinkError.class, thrown.getCause());
            final UnsatisfiedLinkError cause = assertInstanceOf(UnsatisfiedLinkError.class, outer.getCause());
            assertTrue(cause.getMessage().contains("manifest digest mismatch"));
            assertEquals(1, classLoader.openedResources());
        } finally {
            restore("os.name", oldOs);
            restore("os.arch", oldArch);
        }
    }

    @Test
    @ResourceLock("system-properties")
    void rejectsManifestBytesBelowAuthenticatedSize() throws Exception {
        final Fixture fixture = fixture();
        final byte[] original = fixture.resources().get(fixture.spec().manifestResourcePath());
        final Map<String, byte[]> resources = new HashMap<>(fixture.resources());
        resources.put(fixture.spec().manifestResourcePath(), java.util.Arrays.copyOf(original, original.length - 1));
        final Class<?> type = new ResourceClassLoader(resources).define(
                LOADER_NAME.replace('/', '.'), new NativeLoaderGenerator().generate(fixture.spec()).bytecode());
        final String oldOs = System.getProperty("os.name");
        final String oldArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            final InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> type.getMethod("ensureLoaded").invoke(null));
            final UnsatisfiedLinkError outer = assertInstanceOf(UnsatisfiedLinkError.class, thrown.getCause());
            final java.io.IOException cause = assertInstanceOf(java.io.IOException.class, outer.getCause());
            assertTrue(cause.getMessage().contains("truncated"));
        } finally {
            restore("os.name", oldOs);
            restore("os.arch", oldArch);
        }
    }

    @Test
    @ResourceLock("system-properties")
    void rejectsManifestBytesBeyondAuthenticatedSize() throws Exception {
        final Fixture fixture = fixture();
        final byte[] original = fixture.resources().get(fixture.spec().manifestResourcePath());
        final byte[] oversized = java.util.Arrays.copyOf(original, original.length + 1);
        final Map<String, byte[]> resources = new HashMap<>(fixture.resources());
        resources.put(fixture.spec().manifestResourcePath(), oversized);
        final Class<?> type = new ResourceClassLoader(resources).define(
                LOADER_NAME.replace('/', '.'), new NativeLoaderGenerator().generate(fixture.spec()).bytecode());
        final String oldOs = System.getProperty("os.name");
        final String oldArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            final InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> type.getMethod("ensureLoaded").invoke(null));
            final UnsatisfiedLinkError outer = assertInstanceOf(UnsatisfiedLinkError.class, thrown.getCause());
            final java.io.IOException cause = assertInstanceOf(java.io.IOException.class, outer.getCause());
            assertTrue(cause.getMessage().contains("authenticated size"));
        } finally {
            restore("os.name", oldOs);
            restore("os.arch", oldArch);
        }
    }

    @Test
    void rejectsNonCanonicalArtifactPathsBeforeGeneratingBytecode() {
        final NativeLoaderSpec.Library library = new NativeLoaderSpec.Library(
                NativeTarget.LINUX_X86_64,
                "META-INF/skidfuscator/native/wrong/linux-x86_64/libx.so",
                "a".repeat(64),
                "libx.so",
                1);
        assertThrows(IllegalArgumentException.class, () -> new NativeLoaderSpec(
                LOADER_NAME,
                BUILD_ID,
                NativeCompilationInstaller.manifestResourcePath(BUILD_ID),
                "b".repeat(64),
                1,
                Map.of(NativeTarget.LINUX_X86_64, library)));
    }

    private static Fixture fixture() throws Exception {
        final Map<NativeTarget, NativeArtifact> artifacts = new EnumMap<>(NativeTarget.class);
        final Map<String, byte[]> resources = new HashMap<>();
        for (final NativeTarget target : NativeTarget.values()) {
            final byte[] bytes = ("fake-" + target.id()).getBytes(StandardCharsets.UTF_8);
            final String fileName = target.libraryFileName("protected");
            final String path = "META-INF/skidfuscator/native/" + BUILD_ID + "/"
                    + target.id() + "/" + fileName;
            artifacts.put(target, new NativeArtifact(target, path, sha256(bytes), bytes.length));
            resources.put(path, bytes);
        }
        final CompilerManifest manifest = new CompilerManifest(
                CompilerManifest.CURRENT_SCHEMA,
                NativeIrVersions.CURRENT_ABI,
                BUILD_ID,
                "c".repeat(64),
                artifacts);
        final byte[] manifestBytes = new CompilerManifestCodec().encode(manifest);
        resources.put(NativeCompilationInstaller.manifestResourcePath(BUILD_ID), manifestBytes);
        return new Fixture(NativeLoaderSpec.fromManifest(LOADER_NAME, manifest), resources);
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void restore(final String name, final String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private record Fixture(NativeLoaderSpec spec, Map<String, byte[]> resources) {
    }

    private static final class ResourceClassLoader extends ClassLoader {
        private final Map<String, byte[]> resources;
        private int openedResources;

        private ResourceClassLoader(final Map<String, byte[]> resources) {
            super(NativeLoaderGeneratorTest.class.getClassLoader());
            this.resources = resources;
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        public InputStream getResourceAsStream(final String name) {
            final byte[] bytes = resources.get(name);
            if (bytes != null) {
                openedResources++;
                return new ByteArrayInputStream(bytes);
            }
            return super.getResourceAsStream(name);
        }

        private int openedResources() {
            return openedResources;
        }
    }
}
