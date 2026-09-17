package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeIrVerifier;
import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativetoolchain.vm.VmBytecodeCompiler;
import dev.skidfuscator.nativetoolchain.vm.VmProgramCodec;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Emits canonical LLVM IR and invokes the pinned SkidLLVM driver once per target. */
public final class ProcessNativeCompiler implements NativeCompiler {
    public static final String DRIVER_BASE_NAME = "skidllvm";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final LlvmIrEmitter emitter;
    private final ProcessExecutor executor;
    private final Duration timeout;
    private final NativeIrVerifier verifier;
    private final NativeLibraryValidator libraryValidator;

    public ProcessNativeCompiler(final LlvmIrEmitter emitter) {
        this(emitter, new SystemProcessExecutor(), DEFAULT_TIMEOUT, new NativeIrVerifier(),
                new NativeLibraryValidator());
    }

    public ProcessNativeCompiler(
            final LlvmIrEmitter emitter,
            final ProcessExecutor executor,
            final Duration timeout,
            final NativeIrVerifier verifier
    ) {
        this(emitter, executor, timeout, verifier, new NativeLibraryValidator());
    }

    ProcessNativeCompiler(
            final LlvmIrEmitter emitter,
            final ProcessExecutor executor,
            final Duration timeout,
            final NativeIrVerifier verifier,
            final NativeLibraryValidator libraryValidator
    ) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.libraryValidator = Objects.requireNonNull(libraryValidator, "libraryValidator");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public NativeCompilationResult compile(final NativeCompilationRequest request)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        verifier.verifyOrThrow(request.module());
        Files.createDirectories(request.outputDirectory());

        final byte[] llvmIr = Objects.requireNonNull(emitter.emit(request.module()), "emitter result");
        if (llvmIr.length == 0) {
            throw new NativeCompilationException("LLVM emitter produced an empty module");
        }
        final Path llvmPath = request.outputDirectory().resolve(request.buildId() + ".ll");
        writeAtomically(llvmPath, llvmIr);
        final boolean hasVmFunctions = request.module().functions().stream()
                .anyMatch(function -> function.backend() == NativeBackend.VM);
        final Path vmPayload = hasVmFunctions
                ? request.outputDirectory().resolve(request.buildId() + ".skvm.partial") : null;
        if (vmPayload != null) {
            final VmProgramCodec vmCodec = new VmProgramCodec();
            final byte[] encodedVm = vmCodec.encode(new VmBytecodeCompiler().compile(
                    request.module(), request.buildId(), new SecureRandom(), request.vmProtection()));
            try {
                vmCodec.decode(encodedVm);
                Files.write(vmPayload, encodedVm);
            } finally {
                java.util.Arrays.fill(encodedVm, (byte) 0);
            }
        }
        try {
            final String moduleDigest = sha256(llvmPath);
            final Path driver = executable(request.toolchain().home(), request.currentHost());
            ToolchainManifestVerifier.verifyInstalledDriver(
                    request.toolchain().home(), request.toolchain().manifest(), request.currentHost());

            final Map<NativeTarget, Path> libraries = new EnumMap<>(NativeTarget.class);
            final Map<NativeTarget, NativeArtifact> artifacts = new EnumMap<>(NativeTarget.class);
            final List<Path> publishedLibraries = new ArrayList<>();
            boolean compilationComplete = false;
            try {
                for (final NativeTarget target : NativeTarget.values()) {
                    if (!request.targets().contains(target)) {
                        continue;
                    }
                    final Path targetDirectory = request.outputDirectory().resolve(target.id());
                    Files.createDirectories(targetDirectory);
                    final String libraryName = target.libraryFileName("skid-" + request.buildId());
                    final Path finalLibrary = targetDirectory.resolve(libraryName);
                    final Path temporaryLibrary = targetDirectory.resolve(libraryName + ".partial");
                    Files.deleteIfExists(temporaryLibrary);

                    final List<String> command = new ArrayList<>();
                    command.add(driver.toString());
                    command.add("compile");
                    command.add("--input");
                    command.add(llvmPath.toString());
                    command.add("--output");
                    command.add(temporaryLibrary.toString());
                    command.add("--target");
                    command.add(target.id());
                    command.add("--native-ir-abi");
                    command.add(Integer.toString(request.module().abiVersion()));
                    command.add("--build-id");
                    command.add(request.buildId());
                    if (vmPayload != null) {
                        command.add("--vm-payload");
                        command.add(vmPayload.toString());
                        command.add("--vm-profile");
                        command.add(request.vmProtection().profile().name());
                        command.add("--vm-response");
                        command.add(request.vmProtection().response().name());
                        command.add("--vm-integrity=" + request.vmProtection().integrity());
                        command.add("--vm-anti-debug=" + request.vmProtection().antiDebug());
                        command.add("--vm-anti-instrumentation=" + request.vmProtection().antiInstrumentation());
                        command.add("--vm-timing-checks=" + request.vmProtection().timingChecks());
                        command.add("--vm-diversified-dispatch=" + request.vmProtection().diversifiedDispatch());
                        command.add("--vm-handler-clones=" + request.vmProtection().handlerCloneCount());
                        command.add("--vm-superinstructions=" + request.vmProtection().superinstructionBudget());
                        command.add("--vm-block-cache=" + request.vmProtection().decodedBlockCacheEntries());
                        command.add("--vm-delay-ms=" + request.vmProtection().delayedHaltMinimumMillis());
                    }

                    final ProcessExecutor.Result result = executor.execute(command, request.outputDirectory(), timeout);
                    if (result.timedOut()) {
                        Files.deleteIfExists(temporaryLibrary);
                        throw new NativeCompilationException("SkidLLVM timed out compiling " + target.id());
                    }
                    if (result.exitCode() != 0) {
                        Files.deleteIfExists(temporaryLibrary);
                        throw new NativeCompilationException("SkidLLVM failed for " + target.id() + " (exit "
                                + result.exitCode() + "): " + bounded(result.output()));
                    }
                    if (!Files.isRegularFile(temporaryLibrary) || Files.size(temporaryLibrary) == 0) {
                        Files.deleteIfExists(temporaryLibrary);
                        throw new NativeCompilationException("SkidLLVM produced no library for " + target.id());
                    }
                    try {
                        libraryValidator.validate(temporaryLibrary, target);
                    } catch (IOException validationFailure) {
                        Files.deleteIfExists(temporaryLibrary);
                        throw validationFailure;
                    }
                    moveAtomically(temporaryLibrary, finalLibrary);
                    publishedLibraries.add(finalLibrary);
                    libraries.put(target, finalLibrary);
                    final String resourcePath = "META-INF/skidfuscator/native/" + request.buildId() + "/"
                            + target.id() + "/" + libraryName;
                    artifacts.put(target, new NativeArtifact(
                            target, resourcePath, sha256(finalLibrary), Files.size(finalLibrary)));
                }

                final CompilerManifest manifest = new CompilerManifest(
                        CompilerManifest.CURRENT_SCHEMA,
                        request.module().abiVersion(),
                        request.buildId(),
                        moduleDigest,
                        artifacts
                );
                new CompilerManifestValidator().validateOrThrow(manifest);
                compilationComplete = true;
                return new NativeCompilationResult(manifest, llvmPath, libraries);
            } finally {
                if (!compilationComplete) {
                    for (final Path publishedLibrary : publishedLibraries) {
                        Files.deleteIfExists(publishedLibrary);
                    }
                }
            }
        } finally {
            if (vmPayload != null) {
                clearAndDelete(vmPayload);
            }
        }
    }

    public static Path executable(final Path toolchainHome, final NativeTarget host) {
        final boolean windows = host == NativeTarget.WINDOWS_X86_64 || host == NativeTarget.WINDOWS_AARCH64;
        return toolchainHome.resolve("bin").resolve(DRIVER_BASE_NAME + (windows ? ".exe" : ""));
    }

    private static String sha256(final Path path) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeAtomically(final Path target, final byte[] contents) throws IOException {
        final Path temporary = target.resolveSibling(target.getFileName() + ".partial");
        Files.write(temporary, contents);
        moveAtomically(temporary, target);
    }

    private static void clearAndDelete(final Path path) throws IOException {
        if (!Files.exists(path)) return;
        final long size = Files.size(path);
        try (var channel = java.nio.channels.FileChannel.open(path,
                java.nio.file.StandardOpenOption.WRITE)) {
            final java.nio.ByteBuffer zeros = java.nio.ByteBuffer.allocate(8192);
            long remaining = size;
            while (remaining > 0) {
                zeros.clear();
                zeros.limit((int) Math.min(zeros.capacity(), remaining));
                while (zeros.hasRemaining()) channel.write(zeros);
                remaining -= zeros.limit();
            }
            channel.force(true);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void moveAtomically(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String bounded(final String output) {
        final String normalized = output == null ? "" : output.strip();
        return normalized.length() <= 4_096 ? normalized : normalized.substring(0, 4_096) + "…";
    }
}
