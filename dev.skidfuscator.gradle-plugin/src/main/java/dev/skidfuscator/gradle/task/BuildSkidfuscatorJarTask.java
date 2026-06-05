package dev.skidfuscator.gradle.task;

import dev.skidfuscator.gradle.SkidfuscatorExtension;
import dev.skidfuscator.gradle.SkidfuscatorPlugin;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the Skidfuscator standalone jar (the artifact {@link ObfuscateJarTask}
 * launches) from source before each obfuscation run, so a code change in the obfuscator
 * can never be silently ignored because a stale jar was reused.
 *
 * <p>The standalone jar lives in a separate Gradle build (the Skidfuscator repo), so
 * this task shells out to that build's wrapper rather than wiring a cross-build task
 * dependency. The inner build does its own up-to-date checking, so when nothing changed
 * the rebuild costs only Gradle startup. Disable with {@code skidfuscator.autoBuildSkidfuscator = false}.</p>
 */
public class BuildSkidfuscatorJarTask extends DefaultTask {

    // JDK 16+ on some Windows images cannot open the AF_UNIX selector pipe Gradle uses for
    // its client<->daemon handshake (fails with "Unable to establish loopback connection").
    // Pointing the AF_UNIX auto-bind tmpdir at a path longer than the 108-char sun_path
    // limit forces bind() to fail, which trips the JVM's TCP-loopback fallback. Harmless
    // where AF_UNIX works. The repo's `gradlew` (sh) bakes this in already; we inject it
    // into the environment so the Windows `gradlew.bat` path inherits it too.
    private static final String AF_UNIX_TCP_FALLBACK =
            "-Djdk.net.unixdomain.tmpdir=C:/skidfuscator-force-tcp-loopback-selector-pipe-padding-"
                    + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private SkidfuscatorExtension extension;

    @Internal
    public SkidfuscatorExtension getExtension() {
        return extension;
    }

    public void setExtension(SkidfuscatorExtension extension) {
        this.extension = extension;
    }

    @TaskAction
    public void build() {
        if (extension != null && !extension.isAutoBuildSkidfuscator()) {
            getLogger().info("Skidfuscator auto-build disabled (autoBuildSkidfuscator = false); using existing jar.");
            return;
        }

        final File jar = SkidfuscatorPlugin.resolveSkidfuscatorJar(getProject(), extension);
        final File projectDir = SkidfuscatorPlugin.resolveSkidfuscatorProjectDir(getProject(), extension, jar);
        if (projectDir == null) {
            getLogger().warn("Skidfuscator auto-build skipped: could not locate the Skidfuscator Gradle build for "
                    + "jar {}. Set skidfuscator.skidfuscatorProjectDir to enable it, or autoBuildSkidfuscator = false "
                    + "to silence this warning.", jar);
            return;
        }

        final String task = SkidfuscatorPlugin.resolveSkidfuscatorBuildTask(extension, jar);
        getLogger().lifecycle("Rebuilding Skidfuscator standalone jar via {} {}", projectDir, task);
        run(gradleCommand(projectDir, task), projectDir);
    }

    private List<String> gradleCommand(File projectDir, String task) {
        final List<String> command = new ArrayList<String>();
        if (isWindows()) {
            // CreateProcess cannot launch a .bat directly; route through cmd.exe.
            final File wrapper = new File(projectDir, "gradlew.bat");
            command.add("cmd.exe");
            command.add("/c");
            command.add(wrapper.isFile() ? wrapper.getAbsolutePath() : "gradlew.bat");
        } else {
            final File wrapper = new File(projectDir, "gradlew");
            command.add(wrapper.isFile() ? wrapper.getAbsolutePath() : "./gradlew");
        }
        command.add(task);
        return command;
    }

    private void run(List<String> command, File workingDir) {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDir);
        processBuilder.redirectErrorStream(true);

        if (isWindows()) {
            final String existing = processBuilder.environment().get("JDK_JAVA_OPTIONS");
            processBuilder.environment().put("JDK_JAVA_OPTIONS",
                    existing == null || existing.trim().length() == 0
                            ? AF_UNIX_TCP_FALLBACK
                            : existing + " " + AF_UNIX_TCP_FALLBACK);
        }

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
                throw new GradleException("Skidfuscator standalone build failed (exit " + exitCode + "): "
                        + command.get(command.size() - 1) + " in " + workingDir);
            }
        } catch (IOException e) {
            throw new GradleException("Failed to rebuild Skidfuscator standalone jar", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while rebuilding Skidfuscator standalone jar", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
