package dev.skidfuscator.gradle.task;

import com.typesafe.config.ConfigFactory;
import dev.skidfuscator.gradle.SkidfuscatorExtension;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SkidfuscatorGenerateConfigTask extends DefaultTask {
    private SkidfuscatorExtension extension;
    private File outputConfig;

    @Internal
    public SkidfuscatorExtension getExtension() {
        return extension;
    }

    public void setExtension(SkidfuscatorExtension extension) {
        this.extension = extension;
    }

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public File getConfigFile() {
        if (extension == null || extension.getConfigFile() == null) {
            return null;
        }
        return getProject().file(extension.getConfigFile());
    }

    @Input
    public List<String> getExemptions() {
        return extension == null ? Collections.<String>emptyList() : extension.getExemptions();
    }

    @Input
    public Map<String, Boolean> getTransformers() {
        return extension == null ? Collections.<String, Boolean>emptyMap() : extension.getTransformers();
    }

    @OutputFile
    public File getOutputConfig() {
        return outputConfig;
    }

    public void setOutputConfig(File outputConfig) {
        this.outputConfig = outputConfig;
    }

    @TaskAction
    public void generate() {
        final File output = getOutputConfig();
        try {
            if (output.getParentFile() != null) {
                Files.createDirectories(output.getParentFile().toPath());
            }
            final String config = buildConfigText();
            Files.write(output.toPath(), config.getBytes(StandardCharsets.UTF_8));
            ConfigFactory.parseFile(output).resolve();
        } catch (IOException e) {
            throw new GradleException("Failed to write Skidfuscator generated config", e);
        } catch (RuntimeException e) {
            throw new GradleException("Generated Skidfuscator config is invalid: " + output, e);
        }
    }

    private String buildConfigText() throws IOException {
        final StringBuilder builder = new StringBuilder();

        final File configFile = getConfigFile();
        if (configFile != null) {
            if (!configFile.isFile()) {
                throw new GradleException("Skidfuscator config file does not exist: " + configFile);
            }
            builder.append(new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8));
            builder.append(System.lineSeparator()).append(System.lineSeparator());
        }

        final List<String> exemptions = getExemptions();
        if (!exemptions.isEmpty()) {
            builder.append("exempt += [").append(System.lineSeparator());
            for (String exemption : exemptions) {
                builder.append("  ").append(quote(exemption)).append(System.lineSeparator());
            }
            builder.append("]").append(System.lineSeparator()).append(System.lineSeparator());
        }

        final Map<String, Boolean> transformers = getTransformers();
        for (Map.Entry<String, Boolean> entry : transformers.entrySet()) {
            builder.append(entry.getKey()).append(" {").append(System.lineSeparator());
            builder.append("  enabled = ").append(entry.getValue()).append(System.lineSeparator());
            builder.append("}").append(System.lineSeparator()).append(System.lineSeparator());
        }

        return builder.toString();
    }

    private String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                + "\"";
    }
}
