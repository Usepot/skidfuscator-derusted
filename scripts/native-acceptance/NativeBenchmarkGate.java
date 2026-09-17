import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Evaluates the plan's AOT/VM median and p95 computational budgets. */
public final class NativeBenchmarkGate {
    private NativeBenchmarkGate() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[2].equals("AOT") || args[2].equals("VM"))) {
            throw new IllegalArgumentException(
                    "Usage: NativeBenchmarkGate <original.jar> <protected.jar> <AOT|VM>");
        }
        Samples original = run(Paths.get(args[0]));
        Samples candidate = run(Paths.get(args[1]));
        if (original.checksum != candidate.checksum) {
            throw new AssertionError("Benchmark checksum differs: "
                    + original.checksum + " != " + candidate.checksum);
        }
        double medianRatio = (double) median(candidate.nanos) / median(original.nanos);
        double p95Ratio = (double) percentile95(candidate.nanos) / percentile95(original.nanos);
        double medianLimit = args[2].equals("AOT") ? 1.25 : 4.0;
        double p95Limit = args[2].equals("AOT") ? 1.25 : 8.0;
        System.out.printf("%s median ratio %.3fx (limit %.2fx), p95 ratio %.3fx (limit %.2fx)%n",
                args[2], medianRatio, medianLimit, p95Ratio, p95Limit);
        if (medianRatio > medianLimit || p95Ratio > p95Limit) {
            throw new AssertionError("Native performance budget exceeded");
        }
    }

    private static Samples run(Path jar) throws Exception {
        Path normalized = jar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IOException("Missing benchmark JAR: " + normalized);
        String java = Paths.get(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java, "-jar", normalized.toString()).redirectErrorStream(true).start();
        byte[] output;
        try (InputStream input = process.getInputStream()) {
            output = readAll(input, 1024 * 1024);
        }
        int exit = process.waitFor();
        String text = new String(output, StandardCharsets.UTF_8).trim();
        if (exit != 0) throw new IOException("Benchmark failed with exit " + exit + ": " + text);
        Integer checksum = null;
        long[] nanos = null;
        for (String line : text.split("\\R")) {
            if (line.startsWith("checksum=")) checksum = Integer.valueOf(line.substring(9));
            if (line.startsWith("nanos=")) {
                String[] values = line.substring(6).split(",");
                nanos = new long[values.length];
                for (int index = 0; index < values.length; index++) nanos[index] = Long.parseLong(values[index]);
            }
        }
        if (checksum == null || nanos == null || nanos.length < 20) {
            throw new IOException("Malformed benchmark output: " + text);
        }
        return new Samples(checksum.intValue(), nanos);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long percentile95(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * 0.95) - 1];
    }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("Benchmark output exceeds 1 MiB");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static final class Samples {
        private final int checksum;
        private final long[] nanos;

        private Samples(int checksum, long[] nanos) {
            this.checksum = checksum;
            this.nanos = nanos;
        }
    }
}
