package dev.skidfuscator.obfuscator.command;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObfuscateCommandOptionsTest {
    @Test
    void parsesNativeBuildOverrides() {
        final ObfuscateCommand command = new ObfuscateCommand();

        new CommandLine(command).parseArgs(
                "input.jar",
                "--native-toolchain-path", "toolchain",
                "--native-toolchain-delivery", "EXTERNAL",
                "--native-targets", "windows-x86_64,linux-aarch64",
                "--native-artifact-dir", "native-output"
        );

        assertEquals(new File("toolchain"), command.nativeToolchainPath);
        assertEquals("EXTERNAL", command.nativeToolchainDelivery);
        assertArrayEquals(new String[]{"windows-x86_64", "linux-aarch64"}, command.nativeTargets);
        assertEquals(new File("native-output"), command.nativeArtifactDirectory);
    }

    @Test
    void rejectsUnsupportedNativeTargetBeforeStartingObfuscation() {
        final ObfuscateCommand command = new ObfuscateCommand();
        command.input = new File("input.jar");
        command.nativeTargets = new String[]{"solaris-sparc"};

        assertThrows(CommandLine.ParameterException.class, command::call);
    }
}
