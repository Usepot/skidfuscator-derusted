package dev.skidfuscator.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkidfuscatorPluginFunctionalTest {
    @TempDir
    Path projectDir;

    @Test
    void registersPluginTasksAndExtension() throws IOException {
        write("settings.gradle", "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }");
        write("build.gradle", ""
                + "plugins { id 'java'; id 'dev.skidfuscator' }\n"
                + "skidfuscator { exempt('class{example/**}') }\n");

        final BuildResult result = run("tasks", "--all");

        assertTrue(result.getOutput().contains("skidfuscatorConfigUi"));
        assertTrue(result.getOutput().contains("skidfuscatorGenerateConfig"));
        assertTrue(result.getOutput().contains("skidfuscatorCollectLibraries"));
        assertTrue(result.getOutput().contains("obfuscateJar"));
    }

    @Test
    void configUiExtractsBundledHtml() throws IOException {
        write("settings.gradle", "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }");
        write("build.gradle", "plugins { id 'java'; id 'dev.skidfuscator' }\n");

        final BuildResult result = run("skidfuscatorConfigUi", "-Pskidfuscator.openConfigUi=false");

        assertEquals(SUCCESS, result.task(":skidfuscatorConfigUi").getOutcome());
        final Path html = projectDir.resolve("build/skidfuscator/config-ui.html");
        assertTrue(Files.isRegularFile(html));
        final String contents = Files.readString(html);
        assertTrue(contents.contains("Skidfuscator"));
        assertTrue(contents.contains("skidfuscator.hocon"));

        // Re-running must execute again, not skip as UP-TO-DATE: opening the configurator
        // is a side effect, so the extracted HTML output must never gate the action.
        final BuildResult second = run("skidfuscatorConfigUi", "-Pskidfuscator.openConfigUi=false");
        assertEquals(SUCCESS, second.task(":skidfuscatorConfigUi").getOutcome());
    }

    @Test
    void configUiInjectsCompiledClassTree() throws IOException {
        write("settings.gradle", "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }");
        write("build.gradle", "plugins { id 'java'; id 'dev.skidfuscator' }\n");
        write("src/main/java/com/example/demo/Widget.java", "package com.example.demo; public class Widget {}\n");

        final BuildResult result = run("classes", "skidfuscatorConfigUi", "-Pskidfuscator.openConfigUi=false");

        assertEquals(SUCCESS, result.task(":skidfuscatorConfigUi").getOutcome());
        final String html = Files.readString(projectDir.resolve("build/skidfuscator/config-ui.html"));
        assertFalse(html.contains("__SKIDFUSCATOR_CLASSTREE__"));   // placeholder was replaced
        assertTrue(html.contains("\"com\""));                        // package nesting present
        assertTrue(html.contains("\"Widget\""));                     // class leaf present
    }

    @Test
    void generatesEffectiveConfigFromFileAndDsl() throws IOException {
        write("settings.gradle", "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }");
        write("build.gradle", ""
                + "plugins { id 'java'; id 'dev.skidfuscator' }\n"
                + "skidfuscator {\n"
                + "  configFile = layout.projectDirectory.file('skid.hocon')\n"
                + "  exempt('class{example/**}')\n"
                + "  disable('stringEncryption')\n"
                + "  transformer('numberEncryption', false)\n"
                + "}\n");
        write("skid.hocon", "driver { enabled = false }\n");

        final BuildResult result = run("skidfuscatorGenerateConfig");

        assertTrue(result.task(":skidfuscatorGenerateConfig").getOutcome() == SUCCESS);
        final String config = Files.readString(projectDir.resolve("build/skidfuscator/effective-config.hocon"));
        assertTrue(config.contains("driver { enabled = false }"));
        assertTrue(config.contains("exempt += ["));
        assertTrue(config.contains("\"class{example/**}\""));
        assertTrue(config.contains("stringEncryption {"));
        assertTrue(config.contains("numberEncryption {"));
        assertTrue(config.contains("enabled = false"));
    }

    @Test
    void collectsRuntimeClasspathJarsAndDirectoryLibraries() throws IOException {
        write("settings.gradle", "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }");
        write("build.gradle", ""
                + "plugins { id 'java'; id 'dev.skidfuscator' }\n"
                + "def generatedClasses = layout.buildDirectory.dir('generated-library-classes')\n"
                + "tasks.register('writeGeneratedClass') {\n"
                + "  outputs.dir(generatedClasses)\n"
                + "  doLast {\n"
                + "    def file = generatedClasses.get().file('fixture/Fake.class').asFile\n"
                + "    file.parentFile.mkdirs()\n"
                + "    file.bytes = [0, 1, 2, 3] as byte[]\n"
                + "  }\n"
                + "}\n"
                + "tasks.register('makeExternalJar', Jar) {\n"
                + "  archiveFileName = 'external-lib.jar'\n"
                + "  destinationDirectory = layout.buildDirectory.dir('fixture-jars')\n"
                + "  from('fixture-lib')\n"
                + "}\n"
                + "dependencies {\n"
                + "  runtimeOnly files(tasks.named('makeExternalJar'))\n"
                + "  runtimeOnly files(generatedClasses)\n"
                + "}\n"
                + "tasks.named('skidfuscatorCollectLibraries') { dependsOn tasks.named('writeGeneratedClass') }\n");
        write("src/main/java/example/App.java", "package example; public class App {}\n");
        write("fixture-lib/fixture/resource.txt", "resource\n");

        final BuildResult result = run("skidfuscatorCollectLibraries");

        assertTrue(result.task(":skidfuscatorCollectLibraries").getOutcome() == SUCCESS);
        final List<String> names = libraryNames(projectDir.resolve("build/skidfuscator/libs"));
        assertTrue(names.stream().anyMatch(name -> name.startsWith("external-lib-") && name.endsWith(".jar")));
        assertTrue(names.stream().anyMatch(name -> name.startsWith("generated-library-classes-") && name.endsWith(".jar")));
    }

    @Test
    void minimizeDependenciesFailsClearlyInLegacyRunner() throws IOException {
        write("settings.gradle", ""
                + "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n"
                + "include 'app', 'needed'\n");
        write("build.gradle", ""
                + "plugins { id 'dev.skidfuscator' apply false }\n"
                + "subprojects { apply plugin: 'java' }\n"
                + "project(':app') {\n"
                + "  apply plugin: 'dev.skidfuscator'\n"
                + "  dependencies {\n"
                + "    implementation project(':needed')\n"
                + "  }\n"
                + "  skidfuscator { minimizeDependencies = true }\n"
                + "}\n");
        write("needed/src/main/java/needed/Base.java", "package needed; public class Base {}\n");
        write("app/src/main/java/app/App.java", "package app; public class App extends needed.Base {}\n");

        final BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(":app:skidfuscatorCollectLibraries", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        assertTrue(result.getOutput().contains("minimizeDependencies is not available"));
    }

    @Test
    void customInputOutputExtraLibrariesAndRuntimePathAreHonored() throws IOException {
        final String fakeRuntime = projectDir.resolve("fake-runtime").toAbsolutePath().toString().replace("\\", "\\\\");
        write("settings.gradle", "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }");
        write("build.gradle", ""
                + "plugins { id 'java'; id 'dev.skidfuscator' }\n"
                + "def customInput = file(\"$buildDir/custom/custom-input.jar\")\n"
                + "def customOutput = file(\"$buildDir/custom/custom-output.jar\")\n"
                + "tasks.register('makeCustomInput', Jar) {\n"
                + "  archiveFileName = 'custom-input.jar'\n"
                + "  destinationDirectory = layout.buildDirectory.dir('custom')\n"
                + "  from('custom-input')\n"
                + "}\n"
                + "tasks.register('makeExtraJar', Jar) {\n"
                + "  archiveFileName = 'extra-lib.jar'\n"
                + "  destinationDirectory = layout.buildDirectory.dir('extra')\n"
                + "  from('extra-lib')\n"
                + "}\n"
                + "skidfuscator {\n"
                + "  inputJar = customInput\n"
                + "  outputJar = customOutput\n"
                + "  runtimePath('" + fakeRuntime + "')\n"
                + "  library(tasks.named('makeExtraJar'))\n"
                + "}\n"
                + "tasks.named('skidfuscatorCollectLibraries') { dependsOn tasks.named('makeCustomInput') }\n"
                + "tasks.register('assertSkidfuscatorConfiguration') {\n"
                + "  dependsOn tasks.named('skidfuscatorCollectLibraries')\n"
                + "  doLast {\n"
                + "    def task = tasks.named('obfuscateJar').get()\n"
                + "    assert task.inputJar.name == 'custom-input.jar'\n"
                + "    assert task.outputJar.name == 'custom-output.jar'\n"
                + "    assert task.runtimePath.endsWith('fake-runtime')\n"
                + "  }\n"
                + "}\n");
        write("custom-input/app/App.class", "fake\n");
        write("extra-lib/extra/resource.txt", "extra\n");

        final BuildResult result = run("assertSkidfuscatorConfiguration");

        assertTrue(result.task(":assertSkidfuscatorConfiguration").getOutcome() == SUCCESS);
        final List<String> names = libraryNames(projectDir.resolve("build/skidfuscator/libs"));
        assertTrue(names.stream().anyMatch(name -> name.startsWith("extra-lib-")));
    }

    @Test
    void obfuscateJarProducesClassifierJar() throws IOException {
        final Path fakeTool = writeFakeSkidfuscatorJar();
        final String fakeToolPath = fakeTool.toAbsolutePath().toString().replace("\\", "\\\\");
        write("settings.gradle", "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }");
        write("build.gradle", ""
                + "plugins { id 'java'; id 'dev.skidfuscator' }\n"
                + "version = ''\n"
                + "skidfuscator {\n"
                + "  skidfuscatorJar = file('" + fakeToolPath + "')\n"
                + disabledTransformerDsl()
                + "}\n");
        write("src/main/java/app/App.java", ""
                + "package app;\n"
                + "public class App {\n"
                + "  public static void main(String[] args) { System.out.println(\"ok\"); }\n"
                + "}\n");

        final BuildResult result = run("obfuscateJar", "--stacktrace");

        assertTrue(result.task(":obfuscateJar").getOutcome() == SUCCESS);
        final boolean hasObfuscatedJar;
        try (Stream<Path> paths = Files.list(projectDir.resolve("build/libs"))) {
            hasObfuscatedJar = paths.anyMatch(path -> path.getFileName().toString().endsWith("-obfuscated.jar"));
        }
        assertTrue(hasObfuscatedJar);
    }

    @Test
    void obfuscateJarPassesConfiguredRuntimeAndFlagsToStandaloneJar() throws IOException {
        final Path fakeTool = writeFakeSkidfuscatorJar();
        final Path fakeRuntime = projectDir.resolve("fake-runtime/lib/rt.jar");
        Files.createDirectories(fakeRuntime.getParent());
        Files.write(fakeRuntime, new byte[]{0});

        write("settings.gradle", "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }");
        write("build.gradle", ""
                + "plugins { id 'java'; id 'dev.skidfuscator' }\n"
                + "skidfuscator {\n"
                + "  skidfuscatorJar = file('" + fakeTool.toAbsolutePath().toString().replace("\\", "\\\\") + "')\n"
                + "  runtimePath = file('fake-runtime')\n"
                + "  phantom = true\n"
                + "  debug = true\n"
                + "  fuckit = true\n"
                + "  analytics = false\n"
                + "}\n");
        write("src/main/java/app/App.java", "package app; public class App {}\n");

        final BuildResult result = run("obfuscateJar", "--stacktrace");

        assertEquals(SUCCESS, result.task(":obfuscateJar").getOutcome());
        final String args = new String(Files.readAllBytes(projectDir.resolve("build/fake-skidfuscator-args.txt")), StandardCharsets.UTF_8);
        assertTrue(args.contains("obfuscate"));
        assertTrue(args.contains("-rt"));
        assertTrue(args.contains("fake-runtime") && args.contains("lib" + java.io.File.separator + "rt.jar"), args);
        assertTrue(args.contains("-ph"));
        assertTrue(args.contains("-dbg"));
        assertTrue(args.contains("-fuckit"));
        assertTrue(args.contains("-notrack"));
    }

    private String disabledTransformerDsl() {
        final String[] names = {
                "stringEncryption",
                "numberEncryption",
                "intAnnotationEncryption",
                "stringAnnotationEncryption",
                "exceptionReturn",
                "flowCondition",
                "flowException",
                "flowRange",
                "flowFactoryMaker",
                "flowSwitch",
                "outliner",
                "ahegao",
                "native",
                "driver",
                "reference",
                "tamperProtection",
                "proprietaryNotice",
                "fileCrasher",
                "classRenamer",
                "methodRenamer",
                "fieldRenamer",
                "methodCallObfuscation",
                "signatureObfuscation",
                "sdk",
                "blockSimplifier",
                "pureEncryption",
                "loopCondition",
                "negation",
                "objectDefinalizer"
        };

        final StringBuilder builder = new StringBuilder();
        for (String name : names) {
            builder.append("  disable('").append(name).append("')\n");
        }
        return builder.toString();
    }

    private BuildResult run(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .withPluginClasspath()
                .forwardOutput()
                .build();
    }

    private void write(String relativePath, String contents) throws IOException {
        final Path path = projectDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents, StandardCharsets.UTF_8);
    }

    private Path writeFakeSkidfuscatorJar() throws IOException {
        final Path source = projectDir.resolve("fake-tool-src/FakeSkidfuscator.java");
        final Path classes = projectDir.resolve("fake-tool-classes");
        final Path jar = projectDir.resolve("fake-skidfuscator.jar");
        write("fake-tool-src/FakeSkidfuscator.java", ""
                + "import java.io.*;\n"
                + "import java.nio.charset.StandardCharsets;\n"
                + "import java.nio.file.*;\n"
                + "public class FakeSkidfuscator {\n"
                + "  public static void main(String[] args) throws Exception {\n"
                + "    File input = null;\n"
                + "    File output = null;\n"
                + "    for (int i = 0; i < args.length; i++) {\n"
                + "      if (\"obfuscate\".equals(args[i]) && i + 1 < args.length) input = new File(args[++i]);\n"
                + "      else if (\"-o\".equals(args[i]) && i + 1 < args.length) output = new File(args[++i]);\n"
                + "    }\n"
                + "    if (input == null || output == null) throw new IllegalArgumentException(\"missing input/output\");\n"
                + "    if (output.getParentFile() != null) output.getParentFile().mkdirs();\n"
                + "    Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);\n"
                + "    Files.write(Paths.get(\"build\", \"fake-skidfuscator-args.txt\"), String.join(\"\\n\", args).getBytes(StandardCharsets.UTF_8));\n"
                + "  }\n"
                + "}\n");
        Files.createDirectories(classes);

        final javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Tests require a JDK with javac");
        }
        final int result = compiler.run(null, null, null, "-d", classes.toString(), source.toString());
        if (result != 0) {
            throw new IllegalStateException("Failed to compile fake Skidfuscator tool");
        }

        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FakeSkidfuscator");

        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output, manifest)) {
            Files.walkFileTree(classes, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    final String entryName = classes.relativize(file).toString().replace('\\', '/');
                    jarOutput.putNextEntry(new JarEntry(entryName));
                    Files.copy(file, jarOutput);
                    jarOutput.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        return jar;
    }

    private List<String> libraryNames(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
        }
    }
}
