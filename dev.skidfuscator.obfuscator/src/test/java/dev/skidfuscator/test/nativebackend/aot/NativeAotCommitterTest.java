package dev.skidfuscator.test.nativebackend.aot;

import dev.skidfuscator.nativeir.NativeIrVersions;
import dev.skidfuscator.nativetoolchain.CompilerManifest;
import dev.skidfuscator.nativetoolchain.CompilerManifestCodec;
import dev.skidfuscator.nativetoolchain.NativeArtifact;
import dev.skidfuscator.nativetoolchain.NativeCompilationResult;
import dev.skidfuscator.nativetoolchain.NativeTarget;
import dev.skidfuscator.obfuscator.nativebackend.aot.NativeAotCommitter;
import dev.skidfuscator.obfuscator.nativebackend.aot.WindowsX64NativeLoaderGenerator;
import dev.skidfuscator.obfuscator.nativebackend.artifact.NativeCompilationInstaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapleir.asm.ClassHelper;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.topdank.byteengineer.commons.data.JarClassData;
import org.topdank.byteengineer.commons.data.JarContents;
import org.topdank.byteengineer.commons.data.JarResource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeAotCommitterTest {
    private static final String BUILD_ID = "first-slice";
    private static final String LOADER_NAME = "protected/runtime/NativeLoader42";

    @TempDir
    Path temporaryDirectory;

    @Test
    void commitsArtifactLoaderInitializerAndNativeMethodInThatOrder() throws IOException {
        final Target target = staticTarget();
        final JarContents contents = contents(target.owner());
        final byte[] library = bytes("test dll bytes");
        final NativeCompilationResult compilation = compilation(library, sha256(library));

        final NativeAotCommitter.CommitResult committed = new NativeAotCommitter().commit(
                contents, target.method(), compilation, LOADER_NAME);

        assertEquals(LOADER_NAME, committed.loaderInternalName());
        assertEquals(List.of(
                NativeCompilationInstaller.manifestResourcePath(BUILD_ID),
                libraryResourcePath()
        ), committed.installedResources());
        assertEquals(2, contents.getResourceContents().size());
        assertArrayEquals(library, contents.getResourceContents().namedMap().get(libraryResourcePath()).getData());

        assertEquals(2, contents.getClassContents().size());
        final JarClassData loader = contents.getClassContents().get(1);
        assertEquals(LOADER_NAME, loader.getClassNode().getName());
        assertEquals(LOADER_NAME + ".class", loader.getName());
        assertEquals(Opcodes.V1_8, new ClassReader(loader.getData()).readShort(6));

        assertTrue((target.method().node.access & Opcodes.ACC_NATIVE) != 0);
        assertTrue((target.method().node.access & Opcodes.ACC_STATIC) != 0);
        assertEquals(0, target.method().node.instructions.size());
        assertTrue(target.method().node.tryCatchBlocks.isEmpty());
        assertEquals(0, target.method().node.maxStack);
        assertEquals(0, target.method().node.maxLocals);

        final MethodNode initializer = target.owner().getMethods().stream()
                .filter(method -> method.isClinit())
                .findFirst()
                .orElseThrow();
        final AbstractInsnNode first = initializer.node.instructions.getFirst();
        final MethodInsnNode loadCall = assertInstanceOf(MethodInsnNode.class, first);
        assertEquals(Opcodes.INVOKESTATIC, loadCall.getOpcode());
        assertEquals(LOADER_NAME, loadCall.owner);
        assertEquals("ensureLoaded", loadCall.name);
        assertEquals("()V", loadCall.desc);

        // The final owner class is structurally writable: its native method has no Code attribute,
        // while class initialization performs the loader call before any static invocation can run.
        assertTrue(ClassHelper.toByteArray(target.owner(), ClassWriter.COMPUTE_MAXS).length > 0);
    }

    @Test
    void generatedLoaderIsJava8AndFailsClosedBeforeSystemLoadWhenResourceIsUnavailable() throws Exception {
        final byte[] library = bytes("dll");
        final byte[] manifest = encodedManifest(library);
        final WindowsX64NativeLoaderGenerator.GeneratedLoader loader =
                new WindowsX64NativeLoaderGenerator().generate(
                        LOADER_NAME,
                        manifestResourcePath(),
                        sha256(manifest),
                        manifest.length,
                        libraryResourcePath(),
                        sha256(library),
                        library.length,
                        "skid-first-slice.dll"
                );

        final org.objectweb.asm.tree.ClassNode parsed = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(loader.bytecode()).accept(parsed, 0);
        assertEquals(Opcodes.V1_8, parsed.version);
        final org.objectweb.asm.tree.MethodNode ensureLoaded = parsed.methods.stream()
                .filter(method -> method.name.equals("ensureLoaded"))
                .findFirst()
                .orElseThrow();
        assertTrue((ensureLoaded.access & Opcodes.ACC_SYNCHRONIZED) != 0);
        assertTrue(containsInvocation(ensureLoaded, "java/lang/System", "load", "(Ljava/lang/String;)V"));
        assertTrue(containsInvocation(ensureLoaded, "java/nio/file/Files", "move",
                "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;"));
        assertTrue(containsInvocation(ensureLoaded, "java/io/File", "getAbsolutePath", "()Ljava/lang/String;"));

        final Class<?> loaderClass = new ByteArrayClassLoader().define(LOADER_NAME.replace('/', '.'), loader.bytecode());
        final Method entrypoint = loaderClass.getMethod("ensureLoaded");
        final InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> entrypoint.invoke(null));
        assertInstanceOf(UnsatisfiedLinkError.class, failure.getCause());
    }

    @Test
    void matchingEmbeddedResourceIsExtractedBeforeTheAbsoluteSystemLoadAttempt() throws Exception {
        final byte[] fakeLibrary = bytes("not a real DLL, but authenticated extraction input");
        final byte[] manifest = encodedManifest(fakeLibrary);
        final String digest = sha256(fakeLibrary);
        final String loaderName = LOADER_NAME + "Extract";
        final WindowsX64NativeLoaderGenerator.GeneratedLoader loader =
                new WindowsX64NativeLoaderGenerator().generate(
                        loaderName,
                        manifestResourcePath(),
                        sha256(manifest),
                        manifest.length,
                        libraryResourcePath(),
                        digest,
                        fakeLibrary.length,
                        "skid-first-slice.dll"
                );
        final ByteArrayClassLoader classLoader = new ByteArrayClassLoader(
                Map.of(manifestResourcePath(), manifest, libraryResourcePath(), fakeLibrary));
        final Class<?> loaderClass = classLoader.define(loaderName.replace('/', '.'), loader.bytecode());
        final Path extractionDirectory = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("skidfuscator-" + digest.substring(0, 12) + "-"
                        + Integer.toHexString(System.identityHashCode(loaderClass.getClassLoader())))
                .toAbsolutePath();
        final Path extractedLibrary = extractionDirectory.resolve("skid-first-slice.dll");
        final String originalOsName = System.getProperty("os.name");
        final String originalOsArch = System.getProperty("os.arch");

        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            final InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> loaderClass.getMethod("ensureLoaded").invoke(null));
            assertInstanceOf(UnsatisfiedLinkError.class, failure.getCause());
            assertTrue(extractedLibrary.isAbsolute());
            assertArrayEquals(fakeLibrary, Files.readAllBytes(extractedLibrary));
        } finally {
            restoreProperty("os.name", originalOsName);
            restoreProperty("os.arch", originalOsArch);
            extractedLibrary.toFile().setWritable(true, true);
            Files.deleteIfExists(extractedLibrary);
            if (Files.isDirectory(extractionDirectory)) {
                try (java.util.stream.Stream<Path> children = Files.list(extractionDirectory)) {
                    for (final Path child : children.toList()) {
                        Files.deleteIfExists(child);
                    }
                }
                Files.deleteIfExists(extractionDirectory);
            }
        }
    }

    @Test
    void tamperedManifestFailsClosedBeforeNativeExtraction() throws Exception {
        final byte[] fakeLibrary = bytes("authenticated library");
        final byte[] manifest = encodedManifest(fakeLibrary);
        final byte[] tamperedManifest = manifest.clone();
        tamperedManifest[tamperedManifest.length / 2] ^= 1;
        final String loaderName = LOADER_NAME + "ManifestTamper";
        final WindowsX64NativeLoaderGenerator.GeneratedLoader loader =
                new WindowsX64NativeLoaderGenerator().generate(
                        loaderName,
                        manifestResourcePath(),
                        sha256(manifest),
                        manifest.length,
                        libraryResourcePath(),
                        sha256(fakeLibrary),
                        fakeLibrary.length,
                        "skid-first-slice.dll"
                );
        final ByteArrayClassLoader classLoader = new ByteArrayClassLoader(Map.of(
                manifestResourcePath(), tamperedManifest,
                libraryResourcePath(), fakeLibrary
        ));
        final Class<?> loaderClass = classLoader.define(loaderName.replace('/', '.'), loader.bytecode());
        final String originalOsName = System.getProperty("os.name");
        final String originalOsArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            final InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> loaderClass.getMethod("ensureLoaded").invoke(null));
            final UnsatisfiedLinkError wrapped = assertInstanceOf(UnsatisfiedLinkError.class, failure.getCause());
            assertEquals("Embedded compiler manifest digest mismatch", wrapped.getCause().getMessage());
        } finally {
            restoreProperty("os.name", originalOsName);
            restoreProperty("os.arch", originalOsArch);
        }
    }

    @Test
    void oversizedAndTruncatedNativeResourcesFailBeforeExtraction() throws Exception {
        final byte[] declaredLibrary = bytes("declared library bytes");
        final byte[] manifest = encodedManifest(declaredLibrary);
        final byte[][] invalidLibraries = {
                java.util.Arrays.copyOf(declaredLibrary, declaredLibrary.length - 1),
                java.util.Arrays.copyOf(declaredLibrary, declaredLibrary.length + 1)
        };
        final String originalOsName = System.getProperty("os.name");
        final String originalOsArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            for (int index = 0; index < invalidLibraries.length; index++) {
                final String loaderName = LOADER_NAME + "Size" + index;
                final WindowsX64NativeLoaderGenerator.GeneratedLoader loader =
                        new WindowsX64NativeLoaderGenerator().generate(
                                loaderName,
                                manifestResourcePath(),
                                sha256(manifest),
                                manifest.length,
                                libraryResourcePath(),
                                sha256(declaredLibrary),
                                declaredLibrary.length,
                                "skid-first-slice.dll"
                        );
                final ByteArrayClassLoader classLoader = new ByteArrayClassLoader(Map.of(
                        manifestResourcePath(), manifest,
                        libraryResourcePath(), invalidLibraries[index]
                ));
                final Class<?> loaderClass = classLoader.define(loaderName.replace('/', '.'), loader.bytecode());
                final InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                        () -> loaderClass.getMethod("ensureLoaded").invoke(null));
                final UnsatisfiedLinkError wrapped = assertInstanceOf(UnsatisfiedLinkError.class, failure.getCause());
                assertInstanceOf(IOException.class, wrapped.getCause());
                assertTrue(wrapped.getCause().getMessage().contains("declared size"));
            }
        } finally {
            restoreProperty("os.name", originalOsName);
            restoreProperty("os.arch", originalOsArch);
        }
    }

    @Test
    void artifactFailureLeavesMethodLoaderAndInitializerUnchanged() throws IOException {
        final Target target = staticTarget();
        final JarContents contents = contents(target.owner());
        contents.getResourceContents().add(new JarResource("sentinel.bin", bytes("sentinel")));
        final int originalAccess = target.method().node.access;
        final int originalInstructions = target.method().node.instructions.size();
        final NativeCompilationResult compilation = compilation(bytes("dll"), "b".repeat(64));

        assertThrows(IOException.class, () -> new NativeAotCommitter().commit(
                contents, target.method(), compilation, LOADER_NAME));

        assertEquals(originalAccess, target.method().node.access);
        assertEquals(originalInstructions, target.method().node.instructions.size());
        assertFalse(target.owner().getMethods().stream().anyMatch(MethodNode::isClinit));
        assertEquals(1, contents.getClassContents().size());
        assertEquals(1, contents.getResourceContents().size());
        assertEquals("sentinel.bin", contents.getResourceContents().get(0).getName());
    }

    @Test
    void prefixesAsmOnlyInitializerInsteadOfCreatingADuplicate() throws IOException {
        final Target target = staticTarget();
        final org.objectweb.asm.tree.MethodNode asmOnlyInitializer =
                new org.objectweb.asm.tree.MethodNode(
                        Opcodes.ASM9,
                        Opcodes.ACC_STATIC,
                        "<clinit>",
                        "()V",
                        null,
                        null
                );
        asmOnlyInitializer.instructions.add(new InsnNode(Opcodes.RETURN));
        target.owner().node.methods.add(asmOnlyInitializer);
        assertFalse(target.owner().getMethods().stream().anyMatch(MethodNode::isClinit));

        final JarContents contents = contents(target.owner());
        final byte[] library = bytes("test dll bytes");
        new NativeAotCommitter().commit(
                contents,
                target.method(),
                compilation(library, sha256(library)),
                LOADER_NAME
        );

        final List<org.objectweb.asm.tree.MethodNode> initializers = target.owner().node.methods.stream()
                .filter(method -> "<clinit>".equals(method.name) && "()V".equals(method.desc))
                .toList();
        assertEquals(1, initializers.size());
        final MethodInsnNode loadCall = assertInstanceOf(
                MethodInsnNode.class,
                initializers.get(0).instructions.getFirst()
        );
        assertEquals(LOADER_NAME, loadCall.owner);
        assertEquals("ensureLoaded", loadCall.name);
        assertTrue(ClassHelper.toByteArray(target.owner(), ClassWriter.COMPUTE_MAXS).length > 0);
    }

    @Test
    void classCollisionAndNonStaticMethodAreRejectedBeforeArtifactStaging() throws IOException {
        final Target target = staticTarget();
        final JarContents collisionContents = contents(target.owner());
        collisionContents.getResourceContents().add(new JarResource(LOADER_NAME + ".class", bytes("collision")));
        final byte[] library = bytes("dll");
        final NativeCompilationResult compilation = compilation(library, sha256(library));

        assertThrows(IllegalStateException.class, () -> new NativeAotCommitter().commit(
                collisionContents, target.method(), compilation, LOADER_NAME));
        assertFalse(target.method().isNative());
        assertEquals(1, collisionContents.getClassContents().size());
        assertEquals(1, collisionContents.getResourceContents().size());

        final Target instanceTarget = instanceTarget();
        final JarContents instanceContents = contents(instanceTarget.owner());
        assertThrows(IllegalArgumentException.class, () -> new NativeAotCommitter().commit(
                instanceContents, instanceTarget.method(), compilation, LOADER_NAME));
        assertFalse(instanceTarget.method().isNative());
        assertEquals(0, instanceContents.getResourceContents().size());
    }

    private Target staticTarget() {
        final org.objectweb.asm.tree.ClassNode asmClass = baseClass("example/StaticTarget");
        final org.objectweb.asm.tree.MethodNode asmMethod = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "add",
                "(II)I",
                null,
                null
        );
        asmMethod.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        asmMethod.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        asmMethod.instructions.add(new InsnNode(Opcodes.IADD));
        asmMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        asmMethod.maxStack = 2;
        asmMethod.maxLocals = 2;
        asmClass.methods.add(asmMethod);
        final ClassNode owner = ClassHelper.create(asmClass);
        return new Target(owner, owner.getMethods().get(0));
    }

    private Target instanceTarget() {
        final org.objectweb.asm.tree.ClassNode asmClass = baseClass("example/InstanceTarget");
        final org.objectweb.asm.tree.MethodNode asmMethod = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC,
                "value",
                "()I",
                null,
                null
        );
        asmMethod.instructions.add(new InsnNode(Opcodes.ICONST_1));
        asmMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        asmMethod.maxStack = 1;
        asmMethod.maxLocals = 1;
        asmClass.methods.add(asmMethod);
        final ClassNode owner = ClassHelper.create(asmClass);
        return new Target(owner, owner.getMethods().get(0));
    }

    private static org.objectweb.asm.tree.ClassNode baseClass(final String name) {
        final org.objectweb.asm.tree.ClassNode owner = new org.objectweb.asm.tree.ClassNode();
        owner.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        return owner;
    }

    private static JarContents contents(final ClassNode owner) {
        final JarContents contents = new JarContents();
        contents.getClassContents().add(new JarClassData(
                owner.getName() + ".class",
                ClassHelper.toByteArray(owner, ClassWriter.COMPUTE_MAXS),
                owner
        ));
        return contents;
    }

    private NativeCompilationResult compilation(final byte[] library, final String declaredDigest) throws IOException {
        final Path libraryPath = temporaryDirectory.resolve("skid-first-slice.dll");
        Files.write(libraryPath, library);
        final byte[] canonicalModule = bytes("; canonical first-slice module\n");
        final Path canonicalModulePath = temporaryDirectory.resolve(BUILD_ID + ".ll");
        Files.write(canonicalModulePath, canonicalModule);
        final NativeArtifact artifact = new NativeArtifact(
                NativeTarget.WINDOWS_X86_64,
                libraryResourcePath(),
                declaredDigest,
                library.length
        );
        final CompilerManifest manifest = new CompilerManifest(
                CompilerManifest.CURRENT_SCHEMA,
                NativeIrVersions.CURRENT_ABI,
                BUILD_ID,
                sha256(canonicalModule),
                Map.of(NativeTarget.WINDOWS_X86_64, artifact)
        );
        return new NativeCompilationResult(
                manifest,
                canonicalModulePath,
                Map.of(NativeTarget.WINDOWS_X86_64, libraryPath)
        );
    }

    private static String libraryResourcePath() {
        return "META-INF/skidfuscator/native/" + BUILD_ID
                + "/windows-x86_64/skid-first-slice.dll";
    }

    private static String manifestResourcePath() {
        return NativeCompilationInstaller.manifestResourcePath(BUILD_ID);
    }

    private byte[] encodedManifest(final byte[] library) throws IOException {
        return new CompilerManifestCodec().encode(compilation(library, sha256(library)).manifest());
    }

    private static boolean containsInvocation(
            final org.objectweb.asm.tree.MethodNode method,
            final String owner,
            final String name,
            final String descriptor
    ) {
        for (final AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invocation
                    && owner.equals(invocation.owner)
                    && name.equals(invocation.name)
                    && descriptor.equals(invocation.desc)) {
                return true;
            }
        }
        return false;
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

    private static void restoreProperty(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private record Target(ClassNode owner, MethodNode method) {
        private Target {
            assertNotNull(owner);
            assertNotNull(method);
        }
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> resources;

        private ByteArrayClassLoader() {
            this(Map.of());
        }

        private ByteArrayClassLoader(final Map<String, byte[]> resources) {
            super(NativeAotCommitterTest.class.getClassLoader());
            this.resources = Map.copyOf(resources);
        }

        private Class<?> define(final String name, final byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }

        @Override
        public InputStream getResourceAsStream(final String name) {
            final byte[] resource = resources.get(name);
            return resource == null
                    ? super.getResourceAsStream(name)
                    : new java.io.ByteArrayInputStream(resource);
        }
    }
}
