package dev.skidfuscator.gradle.task;

import dev.skidfuscator.gradle.SkidfuscatorExtension;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Extracts the bundled visual configurator (a single self-contained HTML file)
 * to {@code build/skidfuscator/config-ui.html} and opens it in the default
 * browser. The page is dependency-free and runs offline straight off
 * {@code file://}, so it works on a developer machine with no network.
 *
 * <p>The configurator lets you toggle transformers and runtime flags and copies
 * out a ready-to-paste {@code build.gradle} block plus the matching
 * {@code skidfuscator.hocon}. It never edits the project itself, so re-running it
 * is always safe. On a headless build the task just prints the extracted path
 * instead of launching a browser.</p>
 */
public class SkidfuscatorConfigUiTask extends DefaultTask {
    private static final String RESOURCE = "/skidfuscator/config-ui.html";
    private static final String CLASSTREE_PLACEHOLDER = "__SKIDFUSCATOR_CLASSTREE__";
    private static final int MAX_CLASSES = 40000;

    private File outputFile;
    private SkidfuscatorExtension extension;
    private FileCollection classSources;

    public SkidfuscatorConfigUiTask() {
        // Launching a browser is a side effect, not a cacheable transform. Without this,
        // the extracted HTML counts as an up-to-date output and Gradle skips the action
        // (and the browser launch) on every run after the first.
        getOutputs().upToDateWhen(task -> false);
    }

    @Internal
    public File getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

    @Internal
    public SkidfuscatorExtension getExtension() {
        return extension;
    }

    public void setExtension(SkidfuscatorExtension extension) {
        this.extension = extension;
    }

    /** Jars and class-output directories scanned to populate the exemption tree. */
    @Internal
    public FileCollection getClassSources() {
        return classSources;
    }

    public void setClassSources(FileCollection classSources) {
        this.classSources = classSources;
    }

    @TaskAction
    public void open() {
        final File output = getOutputFile();
        extract(output);

        getLogger().lifecycle("Skidfuscator configurator: {}", output.getAbsolutePath());

        if (!shouldOpen()) {
            getLogger().lifecycle("Browser launch suppressed (skidfuscator.openConfigUi=false). Open it manually: {}",
                    output.toURI());
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            getLogger().lifecycle("Headless environment - open it manually: {}", output.toURI());
            return;
        }
        if (!launch(output)) {
            getLogger().lifecycle("Could not launch a browser automatically. Open it manually: {}", output.toURI());
        }
    }

    private void extract(File output) {
        final InputStream in = SkidfuscatorConfigUiTask.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new GradleException("Bundled configurator " + RESOURCE + " is missing from the plugin jar.");
        }
        try {
            final String template = new String(readAll(in), StandardCharsets.UTF_8);
            final String html = template.replace(CLASSTREE_PLACEHOLDER, buildClassTreeJson());
            if (output.getParentFile() != null) {
                Files.createDirectories(output.getParentFile().toPath());
            }
            Files.write(output.toPath(), html.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GradleException("Failed to extract the Skidfuscator configurator to " + output, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // Exemption class tree
    //
    // Scans the configured class sources (the input jar plus the source set's
    // class output) for .class entries, collapses inner classes onto their
    // top-level file, and serialises a nested {"d": dirs, "c": classes} tree as
    // JSON. That JSON is injected into the page so the Exemptions section can
    // render a checkbox tree instead of free-text matchers. Returns the literal
    // "null" (left in the page) when nothing is found, which keeps the picker in
    // manual mode.
    // ------------------------------------------------------------------

    private String buildClassTreeJson() {
        if (classSources == null) {
            return "null";
        }
        final TreeSet<String> names = new TreeSet<String>();
        for (File source : classSources.getFiles()) {
            collectClassNames(source, names);
        }
        if (names.isEmpty()) {
            getLogger().lifecycle("Config tree: no compiled classes found yet; the exemption picker opens in manual mode "
                    + "(build the project once, then re-run).");
            return "null";
        }
        getLogger().lifecycle("Config tree: indexed {} classes for the exemption picker.", names.size());

        final Node root = new Node();
        for (String name : names) {
            addToTree(root, name);
        }
        final StringBuilder sb = new StringBuilder(names.size() * 24);
        writeNode(sb, root);
        return sb.toString();
    }

    private void collectClassNames(File source, Set<String> out) {
        if (source == null || !source.exists() || out.size() >= MAX_CLASSES) {
            return;
        }
        if (source.isDirectory()) {
            final Path root = source.toPath();
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile).forEach(p ->
                        addClassName(root.relativize(p).toString().replace(File.separatorChar, '/'), out));
            } catch (IOException e) {
                getLogger().info("Config tree: skipping directory {} ({})", source, e.getMessage());
            }
        } else if (source.getName().toLowerCase().endsWith(".jar")) {
            try (JarFile jar = new JarFile(source)) {
                final Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements() && out.size() < MAX_CLASSES) {
                    addClassName(entries.nextElement().getName(), out);
                }
            } catch (IOException e) {
                getLogger().info("Config tree: skipping jar {} ({})", source, e.getMessage());
            }
        }
    }

    private void addClassName(String entry, Set<String> out) {
        if (entry == null || !entry.endsWith(".class") || out.size() >= MAX_CLASSES) {
            return;
        }
        String name = entry.substring(0, entry.length() - ".class".length());
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.startsWith("META-INF/") || name.endsWith("module-info") || name.endsWith("package-info")) {
            return;
        }
        final int slash = name.lastIndexOf('/');
        final String simple = slash >= 0 ? name.substring(slash + 1) : name;
        final int dollar = simple.indexOf('$');
        if (dollar >= 0) {                                  // collapse inner classes onto the top-level file
            name = (slash >= 0 ? name.substring(0, slash + 1) : "") + simple.substring(0, dollar);
        }
        if (!name.isEmpty()) {
            out.add(name);
        }
    }

    private void addToTree(Node root, String name) {
        Node current = root;
        String rest = name;
        int slash;
        while ((slash = rest.indexOf('/')) >= 0) {
            final String segment = rest.substring(0, slash);
            Node child = current.dirs.get(segment);
            if (child == null) {
                child = new Node();
                current.dirs.put(segment, child);
            }
            current = child;
            rest = rest.substring(slash + 1);
        }
        current.classes.add(rest);
    }

    private void writeNode(StringBuilder sb, Node node) {
        sb.append('{');
        boolean wrote = false;
        if (!node.dirs.isEmpty()) {
            sb.append("\"d\":{");
            boolean first = true;
            for (Map.Entry<String, Node> entry : node.dirs.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                jsonString(sb, entry.getKey()).append(':');
                writeNode(sb, entry.getValue());
            }
            sb.append('}');
            wrote = true;
        }
        if (!node.classes.isEmpty()) {
            if (wrote) {
                sb.append(',');
            }
            sb.append("\"c\":[");
            boolean first = true;
            for (String name : node.classes) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                jsonString(sb, name);
            }
            sb.append(']');
        }
        sb.append('}');
    }

    private StringBuilder jsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"');
    }

    private static final class Node {
        final TreeMap<String, Node> dirs = new TreeMap<String, Node>();
        final TreeSet<String> classes = new TreeSet<String>();
    }

    /**
     * Whether to launch a browser. Set {@code -Pskidfuscator.openConfigUi=false}
     * (or the matching system property) to only extract the file - handy in CI or
     * when you just want the HTML written to disk.
     */
    private boolean shouldOpen() {
        final Object property = getProject().findProperty("skidfuscator.openConfigUi");
        final String value = property == null
                ? System.getProperty("skidfuscator.openConfigUi")
                : String.valueOf(property);
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    /** Opens {@code output} in a browser; returns false if no opener was available. */
    private boolean launch(File output) {
        try {
            if (Desktop.isDesktopSupported()) {
                final Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(output.toURI());
                    return true;
                }
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(output);
                    return true;
                }
            }
        } catch (Throwable t) {
            getLogger().info("Desktop browse failed, falling back to platform opener", t);
        }
        return openWithPlatformCommand(output);
    }

    /** Last-resort opener for environments where AWT Desktop is unavailable. */
    private boolean openWithPlatformCommand(File output) {
        final String os = System.getProperty("os.name", "").toLowerCase();
        final String path = output.getAbsolutePath();
        final String[] command;
        if (os.contains("win")) {
            command = new String[]{"rundll32", "url.dll,FileProtocolHandler", path};
        } else if (os.contains("mac")) {
            command = new String[]{"open", path};
        } else {
            command = new String[]{"xdg-open", path};
        }
        try {
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException e) {
            getLogger().info("Platform opener {} failed", command[0], e);
            return false;
        }
    }
}
