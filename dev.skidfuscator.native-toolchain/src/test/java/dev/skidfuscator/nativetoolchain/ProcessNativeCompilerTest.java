package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeBlock;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeIrVerifier;
import dev.skidfuscator.nativeir.NativeModule;
import dev.skidfuscator.nativeir.NativeTerminator;
import dev.skidfuscator.nativeir.NativeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessNativeCompilerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void invokesDriverAndBuildsDigestedManifest() throws Exception {
        final Path toolchainHome = temporaryDirectory.resolve("toolchain");
        final Path executable = ProcessNativeCompiler.executable(toolchainHome, NativeTarget.WINDOWS_X86_64);
        Files.createDirectories(executable.getParent());
        Files.write(executable, new byte[]{1});
        final ResolvedToolchain toolchain = new ResolvedToolchain(
                toolchainHome, ToolchainSource.EXTERNAL, ToolchainManifestVerifierTest.manifest());
        final NativeCompilationRequest request = new NativeCompilationRequest(
                module(),
                toolchain,
                temporaryDirectory.resolve("output"),
                Set.of(NativeTarget.LINUX_X86_64),
                NativeTarget.WINDOWS_X86_64,
                "build-9"
        );
        final ProcessExecutor executor = (command, workingDirectory, timeout) -> {
            final int outputIndex = command.indexOf("--output") + 1;
            Files.write(Path.of(command.get(outputIndex)), NativeLibraryValidatorTest.elf(62, true));
            return new ProcessExecutor.Result(0, false, "ok");
        };
        final ProcessNativeCompiler compiler = new ProcessNativeCompiler(
                ignored -> "; canonical llvm\n".getBytes(StandardCharsets.UTF_8),
                executor,
                Duration.ofSeconds(1),
                new NativeIrVerifier()
        );

        final NativeCompilationResult result = compiler.compile(request);

        assertEquals(Set.of(NativeTarget.LINUX_X86_64), result.libraries().keySet());
        assertTrue(Files.isRegularFile(result.canonicalLlvmIr()));
        assertEquals(NativeLibraryValidatorTest.elf(62, true).length,
                result.manifest().artifacts().get(NativeTarget.LINUX_X86_64).size());
        assertEquals("build-9", result.manifest().buildId());
    }

    @Test
    void suppliesEncryptedVmPayloadAndDeletesPlainBuildInput() throws Exception {
        final Path toolchainHome = temporaryDirectory.resolve("vm-toolchain");
        final Path executable = ProcessNativeCompiler.executable(toolchainHome, NativeTarget.WINDOWS_X86_64);
        Files.createDirectories(executable.getParent());
        Files.write(executable, new byte[]{1});
        final ResolvedToolchain toolchain = new ResolvedToolchain(
                toolchainHome, ToolchainSource.EXTERNAL, ToolchainManifestVerifierTest.manifest());
        final NativeFunction function = new NativeFunction(
                "skid_vm", "example/Test", "vm", "()V", NativeType.Primitive.VOID,
                List.of(), "entry", NativeBackend.VM, false, Map.of())
                .addBlock(new NativeBlock("entry").terminate(NativeTerminator.Return.voidReturn()));
        final NativeCompilationRequest request = new NativeCompilationRequest(
                new NativeModule("vm").addFunction(function), toolchain,
                temporaryDirectory.resolve("vm-output"), Set.of(NativeTarget.LINUX_X86_64),
                NativeTarget.WINDOWS_X86_64, "vm-build");
        final Path[] payload = new Path[1];
        final ProcessExecutor executor = (command, workingDirectory, timeout) -> {
            final int payloadIndex = command.indexOf("--vm-payload") + 1;
            assertTrue(payloadIndex > 0);
            payload[0] = Path.of(command.get(payloadIndex));
            assertTrue(Files.size(payload[0]) > 64);
            assertTrue(command.contains("--vm-profile"));
            assertTrue(command.contains("AGGRESSIVE"));
            assertTrue(command.contains("--vm-response"));
            assertTrue(command.contains("DELAYED_HALT"));
            assertTrue(command.contains("--vm-integrity=true"));
            assertTrue(command.contains("--vm-handler-clones=4"));
            assertTrue(command.contains("--vm-superinstructions=48"));
            assertTrue(command.contains("--vm-block-cache=64"));
            Files.write(Path.of(command.get(command.indexOf("--output") + 1)),
                    NativeLibraryValidatorTest.elf(62, true));
            return new ProcessExecutor.Result(0, false, "ok");
        };
        new ProcessNativeCompiler(ignored -> "; vm llvm\n".getBytes(StandardCharsets.UTF_8), executor,
                Duration.ofSeconds(1), new NativeIrVerifier()).compile(request);

        assertTrue(payload[0] != null && Files.notExists(payload[0]));
    }

    @Test
    void removesAlreadyPublishedTargetsWhenALaterTargetFails() throws Exception {
        final Path toolchainHome = temporaryDirectory.resolve("rollback-toolchain");
        final Path executable = ProcessNativeCompiler.executable(toolchainHome, NativeTarget.WINDOWS_X86_64);
        Files.createDirectories(executable.getParent());
        Files.write(executable, new byte[]{1});
        final NativeCompilationRequest request = new NativeCompilationRequest(
                module(),
                new ResolvedToolchain(toolchainHome, ToolchainSource.EXTERNAL,
                        ToolchainManifestVerifierTest.manifest()),
                temporaryDirectory.resolve("rollback-output"),
                Set.of(NativeTarget.LINUX_X86_64, NativeTarget.LINUX_AARCH64),
                NativeTarget.WINDOWS_X86_64,
                "rollback-build"
        );
        final AtomicInteger invocation = new AtomicInteger();
        final ProcessExecutor executor = (command, workingDirectory, timeout) -> {
            if (invocation.getAndIncrement() == 0) {
                Files.write(Path.of(command.get(command.indexOf("--output") + 1)),
                        NativeLibraryValidatorTest.elf(62, true));
                return new ProcessExecutor.Result(0, false, "ok");
            }
            return new ProcessExecutor.Result(7, false, "intentional failure");
        };

        assertThrows(NativeCompilationException.class, () -> new ProcessNativeCompiler(
                ignored -> "; canonical llvm\n".getBytes(StandardCharsets.UTF_8), executor,
                Duration.ofSeconds(1), new NativeIrVerifier()).compile(request));

        final Path firstLibrary = request.outputDirectory().resolve(NativeTarget.LINUX_X86_64.id())
                .resolve(NativeTarget.LINUX_X86_64.libraryFileName("skid-" + request.buildId()));
        assertTrue(Files.notExists(firstLibrary), "failed multi-target builds must not leave prior libraries");
    }

    private NativeModule module() {
        final NativeFunction function = new NativeFunction(
                "skid_noop", "example/Test", "noop", "()V", NativeType.Primitive.VOID,
                List.of(), "entry", NativeBackend.AOT, false, Map.of())
                .addBlock(new NativeBlock("entry").terminate(NativeTerminator.Return.voidReturn()));
        return new NativeModule("test").addFunction(function);
    }
}
