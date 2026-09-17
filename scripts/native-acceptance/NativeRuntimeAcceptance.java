import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Dependency-free Java 8 runtime acceptance harness used after native artifacts
 * have been produced. It compares observable behavior and scans every
 * decompressed protected-JAR entry for the fixture secret.
 */
public final class NativeRuntimeAcceptance {
    private static final String FLAG = "SKID{maple_ir_obfuscation_verified}";

    private NativeRuntimeAcceptance() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: NativeRuntimeAcceptance <original.jar> <protected.jar>");
        }
        Path original = Paths.get(args[0]).toAbsolutePath().normalize();
        Path protectedJar = Paths.get(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(original) || !Files.isRegularFile(protectedJar)) {
            throw new IOException("Both original and protected JARs must exist");
        }

        Case[] cases = {
                new Case("valid credentials", "maple\nRedMaple!2026\n", 0, true),
                new Case("wrong password", "maple\nwrong\n", 1, false),
                new Case("case-sensitive username", "Maple\nRedMaple!2026\n", 1, false),
                new Case("end of input", "", 2, false)
        };
        for (Case item : cases) {
            Result baseline = run(original, item.input);
            Result candidate = run(protectedJar, item.input);
            require(baseline.exitCode == item.exitCode,
                    item.name + ": unexpected baseline exit code " + baseline.exitCode);
            require(candidate.exitCode == item.exitCode,
                    item.name + ": unexpected native exit code " + candidate.exitCode);
            require(Arrays.equals(baseline.stdout, candidate.stdout), item.name + ": stdout differs");
            require(Arrays.equals(baseline.stderr, candidate.stderr), item.name + ": stderr differs");
            boolean exposed = new String(candidate.stdout, StandardCharsets.UTF_8).contains(FLAG);
            require(exposed == item.containsFlag, item.name + ": unexpected flag visibility");
            System.out.println("PASS: " + item.name);
        }
        inspectProtectedArtifact(protectedJar);
        scanForSecret(protectedJar);
        System.out.println("All native runtime differential checks passed on "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " / Java " + System.getProperty("java.version"));
    }

    private static void inspectProtectedArtifact(Path jar) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry targetClass = archive.getJarEntry("ctf/login/LoginChallenge.class");
            require(targetClass != null, "Protected fixture class is absent");
            byte[] classBytes;
            try (InputStream input = archive.getInputStream(targetClass)) {
                classBytes = readAll(input, 16 * 1024 * 1024);
            }
            inspectNativeMethod(classBytes, "revealFlag", "()Ljava/lang/String;");

            JarEntry manifest = null;
            Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/skidfuscator/native/")
                        && entry.getName().endsWith("/manifest.properties")) {
                    require(manifest == null, "Expected exactly one compiler manifest");
                    manifest = entry;
                }
            }
            require(manifest != null, "Compiler manifest is absent");
            byte[] manifestBytes;
            try (InputStream input = archive.getInputStream(manifest)) {
                manifestBytes = readAll(input, 1024 * 1024);
            }
            Map<String, String> values = decodeCanonicalProperties(manifestBytes);
            require("skidfuscator-compiler-manifest".equals(values.get("kind")),
                    "Unexpected compiler manifest kind");
            require("1".equals(values.get("artifact.count")),
                    "Single-target acceptance must contain exactly one native artifact");
            String path = values.get("artifact.0.path");
            String expectedDigest = values.get("artifact.0.sha256");
            long expectedSize = Long.parseLong(values.get("artifact.0.size"));
            JarEntry library = archive.getJarEntry(path);
            require(library != null && !library.isDirectory(), "Manifest native library is absent: " + path);
            byte[] libraryBytes;
            try (InputStream input = archive.getInputStream(library)) {
                libraryBytes = readAll(input, 256 * 1024 * 1024);
            }
            require(libraryBytes.length == expectedSize, "Embedded native library size differs from manifest");
            String digest = hex(java.security.MessageDigest.getInstance("SHA-256").digest(libraryBytes));
            require(digest.equals(expectedDigest), "Embedded native library digest differs from manifest");
        }
        System.out.println("PASS: native method body, compiler manifest, size, and digest inspection");
    }

    private static void inspectNativeMethod(byte[] bytes, String expectedName, String expectedDescriptor)
            throws IOException {
        DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        require(input.readInt() == 0xCAFEBABE, "Invalid protected class file");
        input.readUnsignedShort();
        input.readUnsignedShort();
        String[] utf8 = new String[input.readUnsignedShort()];
        for (int index = 1; index < utf8.length; index++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1:
                    utf8[index] = input.readUTF();
                    break;
                case 3:
                case 4:
                    input.readInt();
                    break;
                case 5:
                case 6:
                    input.readLong();
                    index++;
                    break;
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    input.readUnsignedShort();
                    break;
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    input.readUnsignedShort();
                    input.readUnsignedShort();
                    break;
                case 15:
                    input.readUnsignedByte();
                    input.readUnsignedShort();
                    break;
                default:
                    throw new IOException("Unsupported constant-pool tag " + tag);
            }
        }
        input.readUnsignedShort();
        input.readUnsignedShort();
        input.readUnsignedShort();
        int interfaces = input.readUnsignedShort();
        for (int index = 0; index < interfaces; index++) input.readUnsignedShort();
        skipMembers(input, utf8, input.readUnsignedShort());
        int methods = input.readUnsignedShort();
        boolean found = false;
        for (int methodIndex = 0; methodIndex < methods; methodIndex++) {
            int access = input.readUnsignedShort();
            String name = utf8[input.readUnsignedShort()];
            String descriptor = utf8[input.readUnsignedShort()];
            int attributes = input.readUnsignedShort();
            boolean hasCode = false;
            for (int attributeIndex = 0; attributeIndex < attributes; attributeIndex++) {
                String attributeName = utf8[input.readUnsignedShort()];
                int length = input.readInt();
                require(length >= 0, "Negative class attribute length");
                if ("Code".equals(attributeName)) hasCode = true;
                skipExactly(input, length);
            }
            if (expectedName.equals(name) && expectedDescriptor.equals(descriptor)) {
                require((access & 0x0100) != 0, "Selected method is not ACC_NATIVE");
                require(!hasCode, "Selected native method still has a Code attribute");
                found = true;
            }
        }
        require(found, "Selected method was not found in the protected fixture");
    }

    private static void skipMembers(DataInputStream input, String[] utf8, int count) throws IOException {
        for (int member = 0; member < count; member++) {
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readUnsignedShort();
            int attributes = input.readUnsignedShort();
            for (int attribute = 0; attribute < attributes; attribute++) {
                input.readUnsignedShort();
                int length = input.readInt();
                require(length >= 0, "Negative class attribute length");
                skipExactly(input, length);
            }
        }
    }

    private static void skipExactly(DataInputStream input, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int skipped = input.skipBytes(remaining);
            if (skipped <= 0) throw new IOException("Truncated class attribute");
            remaining -= skipped;
        }
    }

    private static Map<String, String> decodeCanonicalProperties(byte[] encoded) {
        Map<String, String> values = new HashMap<String, String>();
        String text = new String(encoded, StandardCharsets.UTF_8);
        for (String line : text.split("\\n")) {
            if (line.length() == 0) continue;
            int separator = line.indexOf('=');
            require(separator > 0, "Malformed compiler manifest");
            String key = line.substring(0, separator);
            String value = new String(Base64.getUrlDecoder().decode(line.substring(separator + 1)),
                    StandardCharsets.UTF_8);
            require(values.put(key, value) == null, "Duplicate compiler manifest property " + key);
        }
        return values;
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 15];
        }
        return new String(result);
    }

    private static Result run(Path jar, String input) throws Exception {
        String executable = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-jar", jar.toString()).start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        Thread stdoutThread = new Thread(stdout, "native-acceptance-stdout");
        Thread stderrThread = new Thread(stderr, "native-acceptance-stderr");
        stdoutThread.start();
        stderrThread.start();
        int exit = process.waitFor();
        stdoutThread.join();
        stderrThread.join();
        stdout.rethrow();
        stderr.rethrow();
        return new Result(exit, stdout.bytes(), stderr.bytes());
    }

    private static void scanForSecret(Path jar) throws Exception {
        byte[][] patterns = {
                FLAG.getBytes(StandardCharsets.UTF_8),
                FLAG.getBytes(Charset.forName("UTF-16LE")),
                FLAG.getBytes(Charset.forName("UTF-16BE"))
        };
        try (JarFile archive = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                byte[] bytes;
                try (InputStream input = archive.getInputStream(entry)) {
                    bytes = readAll(input, 256 * 1024 * 1024);
                }
                for (byte[] pattern : patterns) {
                    if (indexOf(bytes, pattern) >= 0) {
                        throw new AssertionError("Fixture secret leaked in decompressed entry " + entry.getName());
                    }
                }
            }
        }
        System.out.println("PASS: fixture secret is absent from decompressed protected-JAR entries");
    }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("Acceptance input exceeds the 256 MiB bound");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int offset = 0; offset <= data.length - pattern.length; offset++) {
            for (int index = 0; index < pattern.length; index++) {
                if (data[offset + index] != pattern[index]) continue outer;
            }
            return offset;
        }
        return -1;
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Case {
        private final String name;
        private final String input;
        private final int exitCode;
        private final boolean containsFlag;

        private Case(String name, String input, int exitCode, boolean containsFlag) {
            this.name = name;
            this.input = input;
            this.exitCode = exitCode;
            this.containsFlag = containsFlag;
        }
    }

    private static final class Result {
        private final int exitCode;
        private final byte[] stdout;
        private final byte[] stderr;

        private Result(int exitCode, byte[] stdout, byte[] stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream input;
        private byte[] bytes;
        private IOException failure;

        private StreamCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try (InputStream stream = input) {
                bytes = readAll(stream, 16 * 1024 * 1024);
            } catch (IOException exception) {
                failure = exception;
            }
        }

        private byte[] bytes() {
            return bytes == null ? new byte[0] : bytes;
        }

        private void rethrow() throws IOException {
            if (failure != null) throw failure;
        }
    }
}
