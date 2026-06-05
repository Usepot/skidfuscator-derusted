package dev.skidfuscator.gradle.task;

import dev.skidfuscator.gradle.SkidfuscatorExtension;
import dev.skidfuscator.gradle.SkidfuscatorPlugin;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

public class SkidfuscatorCollectLibrariesTask extends DefaultTask {
    private SkidfuscatorExtension extension;
    private FileCollection runtimeClasspath = getProject().files();
    private File outputDirectory;

    @Internal
    public SkidfuscatorExtension getExtension() {
        return extension;
    }

    public void setExtension(SkidfuscatorExtension extension) {
        this.extension = extension;
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public File getInputJar() {
        return resolveFile(extension.getInputJar(), SkidfuscatorPlugin.defaultInputJar(getProject()));
    }

    @Classpath
    public FileCollection getRuntimeClasspath() {
        return runtimeClasspath;
    }

    public void setRuntimeClasspath(FileCollection runtimeClasspath) {
        this.runtimeClasspath = runtimeClasspath == null ? getProject().files() : runtimeClasspath;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public ConfigurableFileCollection getExtraLibraries() {
        return extension == null ? getProject().files() : extension.getExtraLibraries();
    }

    @Input
    public boolean isMinimizeDependencies() {
        return extension != null && extension.isMinimizeDependencies();
    }

    @OutputDirectory
    public File getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    @TaskAction
    public void collect() {
        if (isMinimizeDependencies()) {
            throw new GradleException("minimizeDependencies is not available in the legacy-compatible Gradle plugin runner yet.");
        }

        final File output = getOutputDirectory();
        final Set<String> seen = new LinkedHashSet<String>();

        try {
            cleanDirectory(output.toPath());
            Files.createDirectories(output.toPath());

            final File inputJar = getInputJar();
            for (File file : getRuntimeClasspath().getFiles()) {
                materialize(file, output, seen, inputJar);
            }
            for (File file : getExtraLibraries().getFiles()) {
                materialize(file, output, seen, inputJar);
            }
        } catch (IOException e) {
            throw new GradleException("Failed to collect Skidfuscator libraries", e);
        }
    }

    private void materialize(File file, File outputDirectory, Set<String> seen, File inputJar) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isFile()) {
            if (isJar(file)) {
                copyJar(file, outputDirectory, seen, inputJar);
            }
            return;
        }

        final Path root = file.toPath();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isJar(path.toFile()))
                    .forEach(path -> copyJarUnchecked(path.toFile(), outputDirectory, seen, inputJar));
        }

        if (containsClassFile(root)) {
            final String key = file.getCanonicalPath();
            if (seen.add("dir:" + key)) {
                final File target = new File(outputDirectory, uniqueName(file, "jar"));
                jarDirectory(root, target.toPath());
            }
        }
    }

    private void copyJarUnchecked(File file, File outputDirectory, Set<String> seen, File inputJar) {
        try {
            copyJar(file, outputDirectory, seen, inputJar);
        } catch (IOException e) {
            throw new GradleException("Failed to copy Skidfuscator library " + file, e);
        }
    }

    private void copyJar(File file, File outputDirectory, Set<String> seen, File inputJar) throws IOException {
        final String key = file.getCanonicalPath();
        if (inputJar != null && inputJar.exists() && key.equals(inputJar.getCanonicalPath())) {
            return;
        }
        if (!seen.add("jar:" + key)) {
            return;
        }

        final File target = new File(outputDirectory, uniqueName(file, "jar"));
        Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean containsClassFile(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.anyMatch(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"));
        }
    }

    private void jarDirectory(Path sourceDirectory, Path targetJar) throws IOException {
        Files.createDirectories(targetJar.getParent());
        final Set<String> entries = new LinkedHashSet<String>();
        final JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(targetJar));
        try {
            try (Stream<Path> paths = Files.walk(sourceDirectory)) {
                paths.filter(Files::isRegularFile)
                        .forEach(path -> writeEntry(sourceDirectory, path, jarOutputStream, entries));
            }
        } finally {
            jarOutputStream.close();
        }
    }

    private void writeEntry(Path sourceDirectory, Path file, JarOutputStream jarOutputStream, Set<String> entries) {
        final String entryName = sourceDirectory.relativize(file).toString().replace(File.separatorChar, '/');
        if (!entries.add(entryName)) {
            return;
        }

        try {
            jarOutputStream.putNextEntry(new JarEntry(entryName));
            copy(file, jarOutputStream);
            jarOutputStream.closeEntry();
        } catch (IOException e) {
            throw new GradleException("Failed to write library directory entry " + file, e);
        }
    }

    private void copy(Path file, OutputStream outputStream) throws IOException {
        final InputStream inputStream = new FileInputStream(file.toFile());
        try {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
        } finally {
            inputStream.close();
        }
    }

    private boolean isJar(File file) {
        return file.isFile() && file.getName().toLowerCase().endsWith(".jar");
    }

    private String uniqueName(File file, String extension) throws IOException {
        final String name = file.getName();
        final int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        return sanitize(base) + "-" + shortHash(file.getCanonicalPath()) + "." + extension;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String shortHash(String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 6 && i < bytes.length; i++) {
                builder.append(String.format("%02x", Integer.valueOf(bytes[i] & 0xff)));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new GradleException("SHA-256 is unavailable", e);
        }
    }

    private File resolveFile(Object value, File defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return getProject().file(value);
    }

    private void cleanDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new GradleException("Failed to clean Skidfuscator library directory " + path, e);
                        }
                    });
        }
    }
}
