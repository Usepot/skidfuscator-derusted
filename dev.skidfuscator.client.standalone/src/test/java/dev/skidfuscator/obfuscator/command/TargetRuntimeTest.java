package dev.skidfuscator.obfuscator.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TargetRuntimeTest {
    @TempDir Path directory;

    @Test void jarTargetDoesNotInheritTheHostRuntimeFormat() throws Exception {
        Path runtime = Files.createFile(directory.resolve("rt.jar"));
        assertFalse(ObfuscateCommand.isJmodRuntime(runtime.toFile()));
    }

    @Test void modularTargetIsDetectedFromItsContents() throws Exception {
        Path runtime = Files.createDirectory(directory.resolve("jmods"));
        Files.createFile(runtime.resolve("java.base.jmod"));
        assertTrue(ObfuscateCommand.isJmodRuntime(runtime.toFile()));
    }

    @Test void rejectsMissingOrEmptyTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> ObfuscateCommand.isJmodRuntime(directory.resolve("missing.jar").toFile()));
        assertThrows(IllegalArgumentException.class,
                () -> ObfuscateCommand.isJmodRuntime(directory.toFile()));
    }

    @Test void rejectsASingleJmodFileRatherThanSilentlyTreatingItAsAJar() throws Exception {
        Path runtime = Files.createFile(directory.resolve("java.base.jmod"));
        assertThrows(IllegalArgumentException.class,
                () -> ObfuscateCommand.isJmodRuntime(runtime.toFile()));
    }
}
