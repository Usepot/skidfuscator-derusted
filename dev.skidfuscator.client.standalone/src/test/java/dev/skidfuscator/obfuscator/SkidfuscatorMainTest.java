package dev.skidfuscator.obfuscator;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SkidfuscatorMainTest {
    @Test
    void propagatesPicocliFailuresToTheProcessExitHandler() {
        int commandExitCode = SkidfuscatorMain.executeCommand(
                new String[]{"--definitely-not-a-real-option"}
        );
        assertNotEquals(0, commandExitCode);

        AtomicInteger processExitCode = new AtomicInteger(Integer.MIN_VALUE);
        SkidfuscatorMain.exitOnFailure(commandExitCode, processExitCode::set);
        assertEquals(commandExitCode, processExitCode.get());
    }

    @Test
    void doesNotExitForSuccessfulCommands() {
        AtomicInteger processExitCode = new AtomicInteger(Integer.MIN_VALUE);
        SkidfuscatorMain.exitOnFailure(0, processExitCode::set);
        assertEquals(Integer.MIN_VALUE, processExitCode.get());
    }
}
