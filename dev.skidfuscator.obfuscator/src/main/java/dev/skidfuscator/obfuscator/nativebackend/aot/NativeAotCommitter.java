package dev.skidfuscator.obfuscator.nativebackend.aot;

import dev.skidfuscator.nativetoolchain.NativeArtifact;
import dev.skidfuscator.nativetoolchain.CompilerManifestCodec;
import dev.skidfuscator.nativetoolchain.NativeCompilationResult;
import dev.skidfuscator.nativetoolchain.NativeTarget;
import dev.skidfuscator.obfuscator.nativebackend.NativeEligibility;
import dev.skidfuscator.obfuscator.nativebackend.artifact.NativeCompilationInstaller;
import org.mapleir.asm.ClassHelper;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.topdank.byteengineer.commons.data.JarClassData;
import org.topdank.byteengineer.commons.data.JarContents;
import org.topdank.byteengineer.commons.data.JarResource;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Transactional commit primitive for the first, Windows x86-64 AOT vertical slice.
 *
 * <p>This component deliberately does not compile or register a native function. It commits
 * an already compiled and authenticated DLL, installs a generated loader, places a loader
 * call at the beginning of the owner class initializer, and finally removes the selected
 * Java Code attribute by converting the method to {@code ACC_NATIVE}.</p>
 */
public final class NativeAotCommitter {
    private static final List<NativeTarget> BOUNDED_TARGETS = List.of(NativeTarget.WINDOWS_X86_64);

    private final NativeCompilationInstaller installer;
    private final WindowsX64NativeLoaderGenerator loaderGenerator;

    public NativeAotCommitter() {
        this(new NativeCompilationInstaller(), new WindowsX64NativeLoaderGenerator());
    }

    NativeAotCommitter(
            final NativeCompilationInstaller installer,
            final WindowsX64NativeLoaderGenerator loaderGenerator
    ) {
        this.installer = Objects.requireNonNull(installer, "installer");
        this.loaderGenerator = Objects.requireNonNull(loaderGenerator, "loaderGenerator");
    }

    /**
     * Commits one eligible ordinary static method and one Windows x86-64 compilation.
     *
     * <p>The method remains bytecode-backed if any artifact validation, loader generation,
     * collision check, class-initializer preflight, or staging operation fails.</p>
     */
    public CommitResult commit(
            final JarContents contents,
            final MethodNode method,
            final NativeCompilationResult compilation,
            final String loaderInternalName
    ) throws IOException {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(compilation, "compilation");
        Objects.requireNonNull(loaderInternalName, "loaderInternalName");

        validateMethod(contents, method);
        validateBoundedCompilation(compilation);
        validateLoaderCollision(contents, loaderInternalName);

        final NativeArtifact artifact = compilation.manifest().artifacts().get(NativeTarget.WINDOWS_X86_64);
        final Path libraryPath = compilation.libraries().get(NativeTarget.WINDOWS_X86_64);
        final String libraryFileName = libraryPath.getFileName().toString();
        final byte[] encodedManifest = new CompilerManifestCodec().encode(compilation.manifest());
        final WindowsX64NativeLoaderGenerator.GeneratedLoader generatedLoader = loaderGenerator.generate(
                loaderInternalName,
                NativeCompilationInstaller.manifestResourcePath(compilation.manifest().buildId()),
                sha256(encodedManifest),
                encodedManifest.length,
                artifact.resourcePath(),
                artifact.sha256(),
                artifact.size(),
                libraryFileName
        );
        final byte[] loaderBytecode = generatedLoader.bytecode();
        final ClassNode loaderClass = ClassHelper.create(loaderBytecode);
        if (!loaderInternalName.equals(loaderClass.getName())) {
            throw new IllegalStateException("Generated loader name does not match its class file");
        }
        final JarClassData loaderData = new JarClassData(
                loaderInternalName + ".class",
                loaderBytecode,
                loaderClass
        );
        final ClassInitializationPlan initialization = preflightClassInitialization(method.owner, loaderInternalName);

        final int initialResourceCount = contents.getResourceContents().size();
        final int initialClassCount = contents.getClassContents().size();
        boolean initializationApplied = false;
        try {
            final List<String> installedResources = installer.install(contents, compilation, BOUNDED_TARGETS);
            contents.getClassContents().add(loaderData);
            initializationApplied = true;
            initialization.apply();

            // This is intentionally the final mutation: no artifact, loader, or initializer
            // failure can leave a selected method without its Java implementation.
            convertToNative(method);
            return new CommitResult(loaderInternalName, installedResources);
        } catch (final IOException | RuntimeException | Error failure) {
            if (initializationApplied) {
                initialization.rollback();
            }
            truncate(contents.getClassContents(), initialClassCount);
            truncate(contents.getResourceContents(), initialResourceCount);
            throw failure;
        }
    }

    private static void validateMethod(final JarContents contents, final MethodNode method) {
        final NativeEligibility.Result eligibility = NativeEligibility.check(method);
        if (eligibility.conversion() != NativeEligibility.Conversion.DIRECT) {
            throw new IllegalArgumentException("Method is not eligible for direct native conversion: "
                    + eligibility.reason());
        }
        if (!method.isStatic()) {
            throw new IllegalArgumentException("First-slice AOT commit requires an ordinary static method");
        }
        if (!method.owner.getMethods().contains(method) || !method.owner.node.methods.contains(method.node)) {
            throw new IllegalArgumentException("Method is not attached to its declared owner");
        }
        final boolean ownerPresent = contents.getClassContents().stream()
                .anyMatch(data -> data.getClassNode() == method.owner);
        if (!ownerPresent) {
            throw new IllegalArgumentException("Method owner is not present in destination jar contents");
        }
    }

    private static void validateBoundedCompilation(final NativeCompilationResult compilation) {
        if (!compilation.manifest().artifacts().keySet().equals(java.util.Set.of(NativeTarget.WINDOWS_X86_64))
                || !compilation.libraries().keySet().equals(java.util.Set.of(NativeTarget.WINDOWS_X86_64))) {
            throw new IllegalArgumentException("First-slice AOT commit accepts exactly windows-x86_64");
        }
    }

    private static void validateLoaderCollision(final JarContents contents, final String loaderInternalName) {
        final String classPath = loaderInternalName + ".class";
        for (final JarClassData classData : contents.getClassContents()) {
            if (loaderInternalName.equals(classData.getClassNode().getName())
                    || classPath.equals(classData.getName())) {
                throw new IllegalStateException("Generated native loader collides with class: " + classPath);
            }
        }
        for (final JarResource resource : contents.getResourceContents()) {
            if (classPath.equals(resource.getName())) {
                throw new IllegalStateException("Generated native loader collides with resource: " + classPath);
            }
        }
    }

    private static ClassInitializationPlan preflightClassInitialization(
            final ClassNode owner,
            final String loaderInternalName
    ) {
        // Class-level transforms may append an ASM method without rebuilding MapleIR's
        // wrapper list. The ASM class is what is ultimately written, so it is the
        // authoritative source for collision detection and prefix insertion here.
        final List<org.objectweb.asm.tree.MethodNode> initializers = owner.node.methods.stream()
                .filter(method -> "<clinit>".equals(method.name) && "()V".equals(method.desc))
                .toList();
        if (initializers.size() > 1) {
            throw new IllegalStateException("Owner class contains duplicate class initializers");
        }
        if (initializers.isEmpty()) {
            return ClassInitializationPlan.create(owner, loaderInternalName);
        }

        final org.objectweb.asm.tree.MethodNode initializer = initializers.get(0);
        if ((initializer.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0
                || initializer.instructions == null) {
            throw new IllegalStateException("Owner class initializer cannot host the native loader call");
        }
        return ClassInitializationPlan.prefix(owner, initializer, loaderInternalName);
    }

    private static void convertToNative(final MethodNode method) {
        final int originalAccess = method.node.access;
        final int originalMaxStack = method.node.maxStack;
        final int originalMaxLocals = method.node.maxLocals;
        final AbstractInsnNode[] originalInstructions = method.node.instructions.toArray();
        final List<org.objectweb.asm.tree.TryCatchBlockNode> originalTryCatchBlocks =
                method.node.tryCatchBlocks == null ? null : new ArrayList<>(method.node.tryCatchBlocks);
        final List<org.objectweb.asm.tree.LocalVariableNode> originalLocalVariables = method.node.localVariables;
        final List<org.objectweb.asm.tree.LocalVariableAnnotationNode> originalVisibleLocalAnnotations =
                method.node.visibleLocalVariableAnnotations;
        final List<org.objectweb.asm.tree.LocalVariableAnnotationNode> originalInvisibleLocalAnnotations =
                method.node.invisibleLocalVariableAnnotations;
        final List<org.objectweb.asm.Attribute> originalAttributes = method.node.attrs;
        try {
            method.node.instructions.clear();
            if (method.node.tryCatchBlocks != null) {
                method.node.tryCatchBlocks.clear();
            }
            method.node.localVariables = null;
            method.node.visibleLocalVariableAnnotations = null;
            method.node.invisibleLocalVariableAnnotations = null;
            if (method.node.attrs != null) {
                method.node.attrs = method.node.attrs.stream()
                        .filter(attribute -> !attribute.isCodeAttribute())
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            }
            method.node.maxStack = 0;
            method.node.maxLocals = 0;
            method.node.access = originalAccess | Opcodes.ACC_NATIVE;
        } catch (final RuntimeException | Error failure) {
            method.node.access = originalAccess;
            method.node.instructions.clear();
            for (final AbstractInsnNode instruction : originalInstructions) {
                method.node.instructions.add(instruction);
            }
            method.node.tryCatchBlocks = originalTryCatchBlocks;
            method.node.localVariables = originalLocalVariables;
            method.node.visibleLocalVariableAnnotations = originalVisibleLocalAnnotations;
            method.node.invisibleLocalVariableAnnotations = originalInvisibleLocalAnnotations;
            method.node.attrs = originalAttributes;
            method.node.maxStack = originalMaxStack;
            method.node.maxLocals = originalMaxLocals;
            throw failure;
        }
    }

    private static void truncate(final List<?> values, final int size) {
        if (values.size() > size) {
            values.subList(size, values.size()).clear();
        }
    }

    private static String sha256(final byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CommitResult(String loaderInternalName, List<String> installedResources) {
        public CommitResult {
            Objects.requireNonNull(loaderInternalName, "loaderInternalName");
            installedResources = List.copyOf(Objects.requireNonNull(installedResources, "installedResources"));
        }
    }

    private static final class ClassInitializationPlan {
        private final ClassNode owner;
        private final org.objectweb.asm.tree.MethodNode existing;
        private final MethodNode created;
        private final AbstractInsnNode[] prefix;

        private ClassInitializationPlan(
                final ClassNode owner,
                final org.objectweb.asm.tree.MethodNode existing,
                final MethodNode created,
                final AbstractInsnNode[] prefix
        ) {
            this.owner = owner;
            this.existing = existing;
            this.created = created;
            this.prefix = prefix;
        }

        private static ClassInitializationPlan create(
                final ClassNode owner,
                final String loaderInternalName
        ) {
            final org.objectweb.asm.tree.MethodNode asmMethod = new org.objectweb.asm.tree.MethodNode(
                    Opcodes.ASM9,
                    Opcodes.ACC_STATIC,
                    "<clinit>",
                    "()V",
                    null,
                    null
            );
            asmMethod.instructions.add(loaderCall(loaderInternalName));
            asmMethod.instructions.add(new InsnNode(Opcodes.RETURN));
            final MethodNode created = new MethodNode(asmMethod, owner);
            return new ClassInitializationPlan(owner, null, created, new AbstractInsnNode[0]);
        }

        private static ClassInitializationPlan prefix(
                final ClassNode owner,
                final org.objectweb.asm.tree.MethodNode initializer,
                final String loaderInternalName
        ) {
            final InsnList instructions = new InsnList();
            instructions.add(loaderCall(loaderInternalName));
            return new ClassInitializationPlan(
                    owner,
                    initializer,
                    null,
                    instructions.toArray()
            );
        }

        private void apply() {
            if (created != null) {
                owner.addMethod(created);
                return;
            }
            final InsnList instructions = new InsnList();
            for (final AbstractInsnNode instruction : prefix) {
                instructions.add(instruction);
            }
            existing.instructions.insert(instructions);
        }

        private void rollback() {
            if (created != null) {
                owner.getMethods().remove(created);
                owner.node.methods.remove(created.node);
                return;
            }
            for (final AbstractInsnNode instruction : prefix) {
                if (instruction.getPrevious() != null
                        || instruction.getNext() != null
                        || existing.instructions.getFirst() == instruction) {
                    existing.instructions.remove(instruction);
                }
            }
        }

        private static MethodInsnNode loaderCall(final String loaderInternalName) {
            return new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    loaderInternalName,
                    "ensureLoaded",
                    "()V",
                    false
            );
        }
    }
}
