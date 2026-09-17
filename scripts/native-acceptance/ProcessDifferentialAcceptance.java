import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Executes two Java 8 JARs and compares all observable process output. */
public final class ProcessDifferentialAcceptance {
    private ProcessDifferentialAcceptance() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <original.jar> <protected.jar>");
        Result original = run(Paths.get(args[0]));
        Result candidate = run(Paths.get(args[1]));
        require(original.exit == candidate.exit, "exit code differs");
        require(Arrays.equals(original.stdout, candidate.stdout), "stdout differs");
        require(Arrays.equals(original.stderr, candidate.stderr), "stderr differs");
        System.out.println("PASS: process output and side-effect digest are identical");
    }

    private static Result run(Path jar) throws Exception {
        Path normalized = jar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IOException("Missing JAR: " + normalized);
        String executable = Paths.get(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-jar", normalized.toString()).start();
        Collector stdout = new Collector(process.getInputStream());
        Collector stderr = new Collector(process.getErrorStream());
        Thread outThread = new Thread(stdout, "differential-stdout");
        Thread errThread = new Thread(stderr, "differential-stderr");
        outThread.start();
        errThread.start();
        int exit = process.waitFor();
        outThread.join();
        errThread.join();
        stdout.rethrow();
        stderr.rethrow();
        return new Result(exit, stdout.bytes, stderr.bytes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Result {
        private final int exit;
        private final byte[] stdout;
        private final byte[] stderr;

        private Result(int exit, byte[] stdout, byte[] stderr) {
            this.exit = exit;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static final class Collector implements Runnable {
        private final InputStream input;
        private byte[] bytes = new byte[0];
        private IOException failure;

        private Collector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    total += count;
                    if (total > 16 * 1024 * 1024) throw new IOException("Process output exceeds 16 MiB");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            } catch (IOException exception) {
                failure = exception;
            }
        }

        private void rethrow() throws IOException {
            if (failure != null) throw failure;
        }
    }
}
