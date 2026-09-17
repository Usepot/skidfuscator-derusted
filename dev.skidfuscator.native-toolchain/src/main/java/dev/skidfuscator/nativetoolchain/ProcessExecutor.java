package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Injectable process boundary used by the SkidLLVM compiler driver. */
@FunctionalInterface
public interface ProcessExecutor {
    Result execute(List<String> command, Path workingDirectory, Duration timeout)
            throws IOException, InterruptedException;

    record Result(int exitCode, boolean timedOut, String output) {
        public Result {
            output = output == null ? "" : output;
        }
    }
}
