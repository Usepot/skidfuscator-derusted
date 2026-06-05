package dev.skidfuscator.gradle.task;

import dev.skidfuscator.gradle.SkidfuscatorExtension;
import dev.skidfuscator.gradle.SkidfuscatorPlugin;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ObfuscateJarTask extends DefaultTask {
    private SkidfuscatorExtension extension;
    private File librariesDirectory;
    private File generatedConfigFile;

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

    @OutputFile
    public File getOutputJar() {
        return resolveFile(extension.getOutputJar(), SkidfuscatorPlugin.defaultOutputJar(getProject()));
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public File getLibrariesDirectory() {
        return librariesDirectory;
    }

    public void setLibrariesDirectory(File librariesDirectory) {
        this.librariesDirectory = librariesDirectory;
    }

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public File getConfigFile() {
        return resolveNullableFile(extension.getConfigFile());
    }

    @Internal
    public File getGeneratedConfigFile() {
        return generatedConfigFile;
    }

    public void setGeneratedConfigFile(File generatedConfigFile) {
        this.generatedConfigFile = generatedConfigFile;
    }

    @Input
    public List<String> getExemptions() {
        return extension == null ? Collections.<String>emptyList() : extension.getExemptions();
    }

    @Input
    public Map<String, Boolean> getTransformers() {
        return extension == null ? Collections.<String, Boolean>emptyMap() : extension.getTransformers();
    }

    @Input
    public boolean isPhantom() {
        return extension != null && extension.isPhantom();
    }

    @Input
    public boolean isDebug() {
        return extension != null && extension.isDebug();
    }

    @Input
    public boolean isFuckit() {
        return extension != null && extension.isFuckit();
    }

    @Input
    public boolean isAnalytics() {
        return extension != null && extension.isAnalytics();
    }

    @Input
    public String getRuntimePath() {
        return getRuntimePathFile().getAbsolutePath();
    }

    @Internal
    public File getRuntimePathFile() {
        return normalizeRuntimePath(resolveFile(extension.getRuntimePath(), SkidfuscatorPlugin.defaultRuntimePath()));
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public File getSkidfuscatorJar() {
        return SkidfuscatorPlugin.resolveSkidfuscatorJar(getProject(), extension);
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public File getJavaExecutable() {
        return resolveFile(extension.getJavaExecutable(), SkidfuscatorPlugin.defaultJavaExecutable());
    }

    @TaskAction
    public void obfuscate() {
        final File inputJar = getInputJar();
        final File outputJar = getOutputJar();
        final File libraries = getLibrariesDirectory();
        final File runtime = getRuntimePathFile();
        final File skidfuscatorJar = getSkidfuscatorJar();
        final File javaExecutable = getJavaExecutable();

        requireFile(inputJar, "Skidfuscator input jar");
        requireFile(skidfuscatorJar, "Skidfuscator standalone jar");
        requireFile(javaExecutable, "Skidfuscator Java executable");
        requireExists(runtime, "Skidfuscator runtime path");

        if (outputJar.getParentFile() != null && !outputJar.getParentFile().isDirectory()
                && !outputJar.getParentFile().mkdirs()) {
            throw new GradleException("Failed to create Skidfuscator output directory: " + outputJar.getParentFile());
        }

        final List<String> command = new ArrayList<String>();
        command.add(javaExecutable.getAbsolutePath());
        command.add("-jar");
        command.add(skidfuscatorJar.getAbsolutePath());
        command.add("obfuscate");
        command.add(inputJar.getAbsolutePath());
        command.add("-o");
        command.add(outputJar.getAbsolutePath());
        command.add("-rt");
        command.add(runtime.getAbsolutePath());

        if (libraries != null && libraries.isDirectory()) {
            command.add("-li");
            command.add(libraries.getAbsolutePath());
        }

        final File config = selectConfigFile();
        if (config != null) {
            command.add("-cfg");
            command.add(config.getAbsolutePath());
        }

        if (isPhantom()) {
            command.add("-ph");
        }
        if (isFuckit()) {
            command.add("-fuckit");
        }
        if (isDebug()) {
            command.add("-dbg");
        }
        if (!isAnalytics()) {
            command.add("-notrack");
        }

        getLogger().lifecycle("Running Skidfuscator with {}", javaExecutable);
        run(command);

        if (!outputJar.isFile()) {
            throw new GradleException("Skidfuscator did not produce output jar: " + outputJar);
        }
    }

    private void run(List<String> command) {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(getProject().getProjectDir());
        processBuilder.redirectErrorStream(true);

        try {
            final Process process = processBuilder.start();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    getLogger().lifecycle(line);
                }
            } finally {
                reader.close();
            }

            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new GradleException("Skidfuscator exited with code " + exitCode);
            }
        } catch (IOException e) {
            throw new GradleException("Failed to run Skidfuscator", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while running Skidfuscator", e);
        }
    }

    private File selectConfigFile() {
        if (hasDslOverlay()) {
            return generatedConfigFile != null && generatedConfigFile.isFile() ? generatedConfigFile : null;
        }
        return getConfigFile();
    }

    private boolean hasDslOverlay() {
        return !getExemptions().isEmpty() || !getTransformers().isEmpty();
    }

    private File normalizeRuntimePath(File runtimePath) {
        if (runtimePath.isDirectory()) {
            final File jmods = new File(runtimePath, "jmods");
            if (jmods.isDirectory()) {
                return jmods;
            }
            final File jreRt = new File(runtimePath, "jre/lib/rt.jar");
            if (jreRt.isFile()) {
                return jreRt;
            }
            final File rt = new File(runtimePath, "lib/rt.jar");
            if (rt.isFile()) {
                return rt;
            }
        }
        return runtimePath;
    }

    private File resolveFile(Object value, File defaultValue) {
        final File resolved = resolveNullableFile(value);
        return resolved == null ? defaultValue : resolved;
    }

    private File resolveNullableFile(Object value) {
        if (value == null) {
            return null;
        }
        return getProject().file(value);
    }

    private void requireFile(File file, String label) {
        if (file == null || !file.isFile()) {
            throw new GradleException(label + " does not exist: " + file);
        }
    }

    private void requireExists(File file, String label) {
        if (file == null || !file.exists()) {
            throw new GradleException(label + " does not exist: " + file);
        }
    }
}
