package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Production process runner with bounded execution and merged diagnostic output. */
public final class SystemProcessExecutor implements ProcessExecutor {
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 1024 * 1024;

    @Override
    public Result execute(final List<String> command, final Path workingDirectory, final Duration timeout)
            throws IOException, InterruptedException {
        final Path outputFile = Files.createTempFile(workingDirectory, "skidllvm-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
            final boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateTree(process, false);
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    terminateTree(process, true);
                    process.waitFor();
                }
            }
            final String output = readBoundedOutput(outputFile);
            return new Result(finished ? process.exitValue() : -1, !finished, output);
        } catch (final InterruptedException interrupted) {
            if (process != null) {
                terminateTree(process, true);
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private static String readBoundedOutput(final Path outputFile) throws IOException {
        final byte[] bytes;
        try (InputStream input = Files.newInputStream(outputFile)) {
            bytes = input.readNBytes(MAX_CAPTURED_OUTPUT_BYTES + 1);
        }
        if (bytes.length <= MAX_CAPTURED_OUTPUT_BYTES) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, 0, MAX_CAPTURED_OUTPUT_BYTES, StandardCharsets.UTF_8)
                + "\n[SkidLLVM output truncated]";
    }

    private static void terminateTree(final Process process, final boolean forcibly) {
        // Clang/LLD may be child processes of the driver. Terminate descendants
        // first so a timed-out compiler cannot continue writing staged output.
        final List<ProcessHandle> descendants = process.descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            if (forcibly) {
                descendants.get(index).destroyForcibly();
            } else {
                descendants.get(index).destroy();
            }
        }
        if (forcibly) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
    }
}
