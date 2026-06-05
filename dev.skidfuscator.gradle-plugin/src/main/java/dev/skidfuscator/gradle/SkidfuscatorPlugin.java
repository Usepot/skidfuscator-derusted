package dev.skidfuscator.gradle;

import dev.skidfuscator.gradle.task.BuildSkidfuscatorJarTask;
import dev.skidfuscator.gradle.task.ObfuscateJarTask;
import dev.skidfuscator.gradle.task.SkidfuscatorCollectLibrariesTask;
import dev.skidfuscator.gradle.task.SkidfuscatorConfigUiTask;
import dev.skidfuscator.gradle.task.SkidfuscatorGenerateConfigTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.bundling.Jar;

import java.io.File;
import java.util.concurrent.Callable;

public class SkidfuscatorPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "skidfuscator";
    public static final String CONFIG_UI_TASK_NAME = "skidfuscatorConfigUi";
    public static final String GENERATE_CONFIG_TASK_NAME = "skidfuscatorGenerateConfig";
    public static final String COLLECT_LIBRARIES_TASK_NAME = "skidfuscatorCollectLibraries";
    public static final String BUILD_STANDALONE_TASK_NAME = "buildSkidfuscatorStandalone";
    public static final String OBFUSCATE_TASK_NAME = "obfuscateJar";

    @Override
    public void apply(final Project project) {
        project.getPlugins().apply(JavaPlugin.class);

        final SkidfuscatorExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, SkidfuscatorExtension.class, project);

        final SkidfuscatorConfigUiTask configUi = project.getTasks()
                .create(CONFIG_UI_TASK_NAME, SkidfuscatorConfigUiTask.class);
        configUi.setGroup("skidfuscator");
        configUi.setDescription("Opens the visual configurator to build a skidfuscator.hocon + build.gradle block.");
        configUi.setOutputFile(new File(project.getBuildDir(), "skidfuscator/config-ui.html"));
        configUi.setExtension(extension);
        // Feed the exemption tree from the input jar (if built) plus the source set's class
        // output, resolved lazily so the task stays runnable before a full build exists.
        configUi.setClassSources(project.files(new Callable<Object>() {
            @Override
            public Object call() {
                final java.util.List<Object> sources = new java.util.ArrayList<Object>();
                final Object configuredInput = extension.getInputJar();
                sources.add(configuredInput != null ? project.file(configuredInput) : defaultInputJar(project));
                try {
                    final SourceSet sourceSet = sourceSet(project, extension.getSourceSet());
                    sources.add(sourceSet.getOutput().getClassesDirs());
                } catch (RuntimeException ignored) {
                    // No matching Java source set yet; the input jar alone still feeds the tree.
                }
                return sources;
            }
        }));

        final SkidfuscatorGenerateConfigTask generateConfig = project.getTasks()
                .create(GENERATE_CONFIG_TASK_NAME, SkidfuscatorGenerateConfigTask.class);
        generateConfig.setGroup("skidfuscator");
        generateConfig.setDescription("Generates the effective Skidfuscator HOCON config.");
        generateConfig.setExtension(extension);
        generateConfig.setOutputConfig(new File(project.getBuildDir(), "skidfuscator/effective-config.hocon"));

        final SkidfuscatorCollectLibrariesTask collectLibraries = project.getTasks()
                .create(COLLECT_LIBRARIES_TASK_NAME, SkidfuscatorCollectLibrariesTask.class);
        collectLibraries.setGroup("skidfuscator");
        collectLibraries.setDescription("Collects runtime dependency jars for Skidfuscator.");
        collectLibraries.setExtension(extension);
        collectLibraries.setOutputDirectory(new File(project.getBuildDir(), "skidfuscator/libs"));
        collectLibraries.setRuntimeClasspath(project.files(new Callable<FileCollection>() {
            @Override
            public FileCollection call() {
                SourceSet sourceSet = sourceSet(project, extension.getSourceSet());
                return sourceSet.getRuntimeClasspath().minus(sourceSet.getOutput());
            }
        }));
        collectLibraries.dependsOn(JavaPlugin.JAR_TASK_NAME);

        final BuildSkidfuscatorJarTask buildStandalone = project.getTasks()
                .create(BUILD_STANDALONE_TASK_NAME, BuildSkidfuscatorJarTask.class);
        buildStandalone.setGroup("skidfuscator");
        buildStandalone.setDescription("Rebuilds the Skidfuscator standalone jar from source before obfuscating.");
        buildStandalone.setExtension(extension);

        final ObfuscateJarTask obfuscate = project.getTasks()
                .create(OBFUSCATE_TASK_NAME, ObfuscateJarTask.class);
        obfuscate.setGroup("skidfuscator");
        obfuscate.setDescription("Produces an obfuscated classifier jar with Skidfuscator.");
        obfuscate.setExtension(extension);
        obfuscate.setLibrariesDirectory(collectLibraries.getOutputDirectory());
        obfuscate.setGeneratedConfigFile(generateConfig.getOutputConfig());
        // buildStandalone runs first so the @InputFile snapshot of the Skidfuscator jar
        // below picks up the freshly rebuilt artifact instead of a stale one.
        obfuscate.dependsOn(JavaPlugin.JAR_TASK_NAME, collectLibraries, generateConfig, buildStandalone);

        final Task assemble = project.getTasks().findByName("assemble");
        if (assemble != null) {
            assemble.dependsOn(obfuscate);
        }
    }

    public static File defaultInputJar(Project project) {
        final Task task = project.getTasks().findByName(JavaPlugin.JAR_TASK_NAME);
        if (task instanceof Jar) {
            return ((Jar) task).getArchivePath();
        }
        return new File(project.getBuildDir(), "libs/" + project.getName() + ".jar");
    }

    public static File defaultOutputJar(Project project) {
        final Task task = project.getTasks().findByName(JavaPlugin.JAR_TASK_NAME);
        if (task instanceof Jar) {
            final File archive = ((Jar) task).getArchivePath();
            return new File(archive.getParentFile(), obfuscatedName(archive.getName()));
        }

        final Object version = project.getVersion();
        final String versionText = version == null || "unspecified".equals(String.valueOf(version))
                ? ""
                : "-" + version;
        return new File(project.getBuildDir(), "libs/" + project.getName() + versionText + "-obfuscated.jar");
    }

    /**
     * Resolves the Skidfuscator standalone jar the obfuscate task launches: the
     * {@code skidfuscatorJar} DSL value, else the {@code skidfuscator.jar} project/system
     * property, else the default {@code client-standalone-all.jar} under the root project.
     */
    public static File resolveSkidfuscatorJar(Project project, SkidfuscatorExtension extension) {
        final Object configured = extension == null ? null : extension.getSkidfuscatorJar();
        if (configured != null) {
            return project.file(configured);
        }
        final Object property = project.findProperty("skidfuscator.jar");
        final String value = property == null ? System.getProperty("skidfuscator.jar") : String.valueOf(property);
        if (value != null && value.length() > 0) {
            return project.file(value);
        }
        return new File(project.getRootProject().getProjectDir(),
                "dev.skidfuscator.client.standalone/build/libs/client-standalone-all.jar");
    }

    /**
     * Locates the Skidfuscator Gradle build that produces {@code skidfuscatorJar}: the
     * explicit {@code skidfuscatorProjectDir} DSL value, else the nearest ancestor of the
     * jar that contains a {@code settings.gradle[.kts]}. Returns {@code null} when neither
     * is found (the jar lives outside a recognizable Gradle build).
     */
    public static File resolveSkidfuscatorProjectDir(Project project, SkidfuscatorExtension extension, File skidfuscatorJar) {
        final Object configured = extension == null ? null : extension.getSkidfuscatorProjectDir();
        if (configured != null) {
            return project.file(configured);
        }
        File dir = skidfuscatorJar == null ? null : skidfuscatorJar.getParentFile();
        while (dir != null) {
            if (new File(dir, "settings.gradle").isFile() || new File(dir, "settings.gradle.kts").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * Picks the Skidfuscator build task that produces {@code skidfuscatorJar}: the explicit
     * {@code skidfuscatorBuildTask} DSL value, else inferred from the jar's filename
     * ({@code *-downgraded-shaded} -> shadeDowngradedApi, {@code *-downgraded} -> downgradeJar,
     * otherwise the plain shadowJar).
     */
    public static String resolveSkidfuscatorBuildTask(SkidfuscatorExtension extension, File skidfuscatorJar) {
        if (extension != null && extension.getSkidfuscatorBuildTask() != null
                && extension.getSkidfuscatorBuildTask().length() > 0) {
            return extension.getSkidfuscatorBuildTask();
        }
        final String name = skidfuscatorJar == null ? "" : skidfuscatorJar.getName().toLowerCase();
        if (name.contains("downgraded-shaded")) {
            return ":client-standalone:shadeDowngradedApi";
        }
        if (name.contains("downgraded")) {
            return ":client-standalone:downgradeJar";
        }
        return ":client-standalone:shadowJar";
    }

    public static File defaultRuntimePath() {
        return defaultRuntimeForJavaHome(new File(System.getProperty("java.home")));
    }

    public static File defaultRuntimeForJavaHome(File javaHome) {
        final File jmods = new File(javaHome, "jmods");
        if (jmods.isDirectory()) {
            return jmods;
        }
        final File jreRt = new File(javaHome, "jre/lib/rt.jar");
        if (jreRt.isFile()) {
            return jreRt;
        }
        return new File(javaHome, "lib/rt.jar");
    }

    public static File defaultJavaExecutable() {
        final String executable = isWindows() ? "java.exe" : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), executable);
    }

    private SourceSet sourceSet(Project project, String name) {
        final SourceSetContainer sourceSets = (SourceSetContainer) project.getExtensions()
                .getByName("sourceSets");
        final SourceSet sourceSet = sourceSets.findByName(name);
        if (sourceSet == null) {
            throw new IllegalArgumentException("No Java source set named '" + name + "' exists in " + project.getPath());
        }
        return sourceSet;
    }

    private static String obfuscatedName(String fileName) {
        final int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName + "-obfuscated";
        }
        return fileName.substring(0, dot) + "-obfuscated" + fileName.substring(dot);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
