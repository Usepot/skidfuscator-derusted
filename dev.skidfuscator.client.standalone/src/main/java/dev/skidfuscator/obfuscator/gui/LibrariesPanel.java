package dev.skidfuscator.obfuscator.gui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import dev.skidfuscator.obfuscator.creator.SkidApplicationClassSource;
import dev.skidfuscator.obfuscator.gui.ui.Card;
import dev.skidfuscator.obfuscator.gui.ui.SecondaryButton;
import dev.skidfuscator.obfuscator.gui.ui.SectionHeader;
import dev.skidfuscator.obfuscator.gui.ui.StatusBadge;
import dev.skidfuscator.obfuscator.gui.ui.UiTheme;
import org.mapleir.app.service.ApplicationClassSource;
import org.mapleir.app.service.LibraryClassSource;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class LibrariesPanel extends JPanel implements SkidPanel {

    private final ConfigPanel configPanel;
    private final Path libraryFolder;
    private final Gson gson = new Gson();

    private final DefaultListModel<String> libraryModel        = new DefaultListModel<>();
    private final DefaultListModel<String> missingClassesModel = new DefaultListModel<>();
    private final JList<String> libraryList        = new JList<>(libraryModel);
    private final JList<String> missingClassesList = new JList<>(missingClassesModel);

    private final JButton addButton    = new SecondaryButton("Add jar…");
    private final JButton removeButton = new SecondaryButton("Remove");
    private final JButton rescanButton = new SecondaryButton("Rescan");
    private final JButton fetchButton  = new SecondaryButton("Find on Maven");

    private final JProgressBar progressBar = new JProgressBar();
    private final StatusBadge statusBadge  = new StatusBadge(StatusBadge.Kind.NEUTRAL, "Idle");
    private final JLabel statusLabel       = new JLabel(" ");

    private SkidApplicationClassSource classSource;

    public LibrariesPanel(ConfigPanel configPanel, SkidApplicationClassSource classSource) {
        this.configPanel = configPanel;
        this.classSource = classSource;

        String configLibPath = configPanel.getLibraryPath();
        if (configLibPath != null && !configLibPath.isEmpty()) {
            this.libraryFolder = Paths.get(configLibPath);
        } else {
            this.libraryFolder = Paths.get(System.getProperty("user.home"), ".ssvm", "libs");
        }
        try { Files.createDirectories(libraryFolder); }
        catch (IOException e) { Skidfuscator.LOGGER.error("Failed to create library folder", e); }

        setLayout(new BorderLayout());
        setOpaque(false);

        add(new SectionHeader(
                "Libraries",
                "Detected dependency jars and the classes Skidfuscator still cannot resolve."),
                BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                wrapList("Detected libraries", libraryList, librariesToolbar()),
                wrapList("Missing classes",   missingClassesList, missingToolbar()));
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        split.setDividerSize(6);

        add(split, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        wireSelection();
    }

    // ------------------------------------------------------------------
    // Sub-components
    // ------------------------------------------------------------------

    private Card wrapList(String title, JList<String> list, JComponent toolbar) {
        Card card = new Card(new BorderLayout(0, UiTheme.PAD_S));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setForeground(UiTheme.TEXT_PRIMARY);
        label.setFont(UiTheme.font(Font.BOLD, 13f));
        header.add(label, BorderLayout.WEST);
        header.add(toolbar, BorderLayout.EAST);
        card.add(header, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(UiTheme.CONTENT_BG);
        list.setForeground(UiTheme.TEXT_PRIMARY);
        list.setFont(UiTheme.font(Font.PLAIN, 12f));
        list.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                                     boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                c.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                c.setBackground(isSelected ? new Color(UiTheme.ACCENT.getRed(), UiTheme.ACCENT.getGreen(), UiTheme.ACCENT.getBlue(), 80)
                        : UiTheme.CONTENT_BG);
                c.setForeground(UiTheme.TEXT_PRIMARY);
                return c;
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JComponent librariesToolbar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTheme.PAD_S, 0));
        p.setOpaque(false);
        addButton.addActionListener(e -> addManualLibrary());
        removeButton.addActionListener(e -> removeManualLibrary());
        removeButton.setEnabled(false);
        p.add(addButton);
        p.add(removeButton);
        return p;
    }

    private JComponent missingToolbar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTheme.PAD_S, 0));
        p.setOpaque(false);
        fetchButton.setEnabled(false);
        fetchButton.addActionListener(e -> {
            String cls = missingClassesList.getSelectedValue();
            if (cls != null) searchMavenCentral(cls);
        });
        rescanButton.addActionListener(e -> analyzeConfigJar());
        p.add(fetchButton);
        p.add(rescanButton);
        return p;
    }

    private void wireSelection() {
        libraryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) removeButton.setEnabled(libraryList.getSelectedValue() != null);
        });
        missingClassesList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) fetchButton.setEnabled(missingClassesList.getSelectedValue() != null);
        });
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(UiTheme.PAD_M, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, 0, 0, 0));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(statusBadge);
        left.add(Box.createHorizontalStrut(UiTheme.PAD_M));
        statusLabel.setForeground(UiTheme.TEXT_SECONDARY);
        statusLabel.setFont(UiTheme.font(Font.PLAIN, 12f));
        left.add(statusLabel);
        bar.add(left, BorderLayout.WEST);

        progressBar.setStringPainted(false);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(240, 6));
        bar.add(progressBar, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void open() {
        SwingUtilities.invokeLater(this::analyzeConfigJar);
    }

    private void analyzeConfigJar() {
        String inputPath = configPanel.getInputPath();
        if (inputPath != null && !inputPath.isEmpty()) {
            File f = new File(inputPath);
            if (f.exists()) refreshInput(f);
        }
    }

    private void setStatus(StatusBadge.Kind kind, String badge, String message) {
        statusBadge.set(kind, badge);
        statusLabel.setText(message);
        statusLabel.setForeground(kind == StatusBadge.Kind.DANGER ? UiTheme.DANGER : UiTheme.TEXT_SECONDARY);
        Skidfuscator.LOGGER.log(message);
    }

    private void refreshMissingClassesList() {
        missingClassesModel.clear();
        try { classSource.getClassTree().verify(); } catch (Exception ignored) {}
        classSource.getMissingClassNames().forEach(missingClassesModel::addElement);
    }

    private void refreshLibraryList() {
        libraryModel.clear();
        classSource.getLibraries().stream()
                .map(LibraryClassSource::getParent)
                .map(ApplicationClassSource::getName)
                .filter(n -> !n.endsWith(".jmod") && !n.equalsIgnoreCase("rt.jar"))
                .forEach(libraryModel::addElement);
    }

    private void refreshInput(final File input) {
        rescanButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        setStatus(StatusBadge.Kind.INFO, "Scanning", "Scanning jar…");

        SwingWorker<List<String>, String> worker = new SwingWorker<List<String>, String>() {
            @Override protected void done() {
                try {
                    List<String> missing = get();
                    missingClassesModel.clear();
                    missing.forEach(missingClassesModel::addElement);
                    setStatus(missing.isEmpty() ? StatusBadge.Kind.SUCCESS : StatusBadge.Kind.WARNING,
                            missing.isEmpty() ? "Resolved" : missing.size() + " missing",
                            missing.isEmpty()
                                    ? "All references resolved."
                                    : "Found " + missing.size() + " missing classes.");
                } catch (Exception ex) {
                    setStatus(StatusBadge.Kind.DANGER, "Error", "Error scanning jar: " + ex.getMessage());
                } finally {
                    rescanButton.setEnabled(true);
                    progressBar.setVisible(false);
                }
            }

            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override protected List<String> doInBackground() throws Exception {
                publish("Initialising Skidfuscator…");
                Skidfuscator skid = new Skidfuscator(SkidfuscatorSession.builder()
                        .input(input)
                        .libs(libraryFolder.toFile().listFiles((dir, name) -> name.endsWith(".jar")))
                        .build());
                publish("Importing classpath…");
                skid._importConfig();
                classSource = skid._importClasspath();
                publish("Importing JVM modules…");
                classSource.addLibraries(skid._importJvm().toArray(new LibraryClassSource[0]));
                refreshLibraryList();
                refreshMissingClassesList();
                publish("Verifying class tree…");
                try { classSource.getClassTree().verify(); } catch (Exception ignored) {}
                return classSource.getClassTree().getMissingClasses();
            }
        };
        worker.execute();
    }

    // ------------------------------------------------------------------
    // Maven search / download
    // ------------------------------------------------------------------

    private void searchMavenCentral(String className) {
        fetchButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setValue(0);
        progressBar.setIndeterminate(false);
        setStatus(StatusBadge.Kind.INFO, "Searching", "Searching Maven Central for " + className + "…");

        Timer timer = new Timer(120, null);
        final long start = System.currentTimeMillis();
        final Random rand = new Random();
        final AtomicInteger cur = new AtomicInteger(0);
        timer.addActionListener(e -> {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= 15000) { timer.stop(); return; }
            int target = (int) (elapsed * 90.0 / 15000.0);
            int now = cur.get();
            if (now < target) {
                int next = Math.min(now + rand.nextInt(3) + 1, target);
                cur.set(next);
                progressBar.setValue(next);
            }
        });

        SwingWorker<List<MavenArtifact>, String> worker = new SwingWorker<List<MavenArtifact>, String>() {
            @Override protected List<MavenArtifact> doInBackground() throws Exception {
                String url = "https://search.maven.org/solrsearch/select?q=fc:" + URLEncoder.encode(className, StandardCharsets.UTF_8)
                        + "&rows=20&wt=json&core=gav";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "Skidfuscator Library Manager");
                conn.setRequestProperty("Accept", "application/json");

                SwingUtilities.invokeLater(timer::start);

                CompletableFuture<JsonObject> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        conn.connect();
                        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
                            throw new IOException("HTTP " + conn.getResponseCode());
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            return gson.fromJson(br, JsonObject.class);
                        }
                    } catch (Exception e) { throw new CompletionException(e); }
                });
                JsonObject resp;
                try { resp = future.get(15, TimeUnit.SECONDS); }
                catch (TimeoutException e) { throw new IOException("Connection timed out"); }
                SwingUtilities.invokeLater(timer::stop);

                JsonArray docs = resp.getAsJsonObject("response").getAsJsonArray("docs");
                List<MavenArtifact> out = new ArrayList<>();
                for (int i = 0; i < docs.size(); i++) {
                    JsonObject a = docs.get(i).getAsJsonObject();
                    out.add(new MavenArtifact(a.get("g").getAsString(), a.get("a").getAsString(), a.get("v").getAsString()));
                }
                progressBar.setValue(100);
                return out;
            }

            @Override protected void done() {
                timer.stop();
                try {
                    List<MavenArtifact> arts = get();
                    if (arts.isEmpty()) {
                        setStatus(StatusBadge.Kind.WARNING, "0 results", "No artifacts found for " + className);
                    } else {
                        setStatus(StatusBadge.Kind.SUCCESS, arts.size() + " hits", "Pick an artifact to import.");
                        MavenArtifact chosen = pickArtifact(arts);
                        if (chosen != null) downloadLibrary(chosen);
                    }
                } catch (Exception e) {
                    setStatus(StatusBadge.Kind.DANGER, "Error",
                            e.getCause() instanceof java.net.SocketTimeoutException
                                    ? "Maven Central timed out. Try again."
                                    : "Search failed: " + e.getMessage());
                } finally {
                    fetchButton.setEnabled(true);
                    progressBar.setVisible(false);
                }
            }
        };
        worker.execute();
    }

    private MavenArtifact pickArtifact(List<MavenArtifact> artifacts) {
        JList<MavenArtifact> list = new JList<>(artifacts.toArray(new MavenArtifact[0]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(10);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(440, 220));
        int r = JOptionPane.showConfirmDialog(this, scroll, "Select library to download",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return r == JOptionPane.OK_OPTION ? list.getSelectedValue() : null;
    }

    private void downloadLibrary(MavenArtifact artifact) {
        setStatus(StatusBadge.Kind.INFO, "Downloading", "Downloading " + artifact + "…");
        progressBar.setVisible(true);
        progressBar.setValue(0);
        progressBar.setIndeterminate(false);

        SwingWorker<File, Integer> worker = new SwingWorker<File, Integer>() {
            @Override protected File doInBackground() throws Exception {
                String mavenUrl = String.format(
                        "https://repo1.maven.org/maven2/%s/%s/%s/%s-%s.jar",
                        artifact.groupId.replace('.', '/'),
                        artifact.artifactId, artifact.version,
                        artifact.artifactId, artifact.version);
                HttpURLConnection conn = (HttpURLConnection) new URL(mavenUrl).openConnection();
                int size = conn.getContentLength();
                File out = libraryFolder.resolve(artifact.artifactId + "-" + artifact.version + ".jar").toFile();
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int read; long total = 0;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        total += read;
                        if (size > 0) publish((int) (total * 100 / size));
                    }
                }
                return out;
            }

            @Override protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) progressBar.setValue(chunks.get(chunks.size() - 1));
            }

            @Override protected void done() {
                try {
                    File downloaded = get();
                    setStatus(StatusBadge.Kind.INFO, "Importing", "Importing " + artifact + "…");
                    try {
                        classSource.importLibrary(downloaded);
                        setStatus(StatusBadge.Kind.SUCCESS, "Imported", "Imported " + artifact);
                        refreshLibraryList();
                        refreshMissingClassesList();
                    } catch (IOException e) {
                        setStatus(StatusBadge.Kind.DANGER, "Error", "Failed to import: " + e.getMessage());
                        if (!downloaded.delete()) downloaded.deleteOnExit();
                    }
                } catch (Exception e) {
                    setStatus(StatusBadge.Kind.DANGER, "Error", "Download failed: " + e.getMessage());
                } finally {
                    progressBar.setVisible(false);
                }
            }
        };
        worker.execute();
    }

    private void addManualLibrary() {
        FileDialog fd = new FileDialog((Frame) null);
        fd.setMode(FileDialog.LOAD);
        fd.setFilenameFilter((f, name) -> f.isDirectory() || name.toLowerCase().endsWith(".jar"));
        fd.setVisible(true);
        String picked = fd.getFile();
        if (picked == null) return;
        File selected = new File(fd.getDirectory(), picked);

        if (!selected.getParentFile().equals(libraryFolder.toFile())) {
            int r = JOptionPane.showConfirmDialog(this,
                    "The selected library lives outside the library folder.\nCopy it in?",
                    "Copy library", JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) {
                try {
                    File dest = libraryFolder.resolve(selected.getName()).toFile();
                    Files.copy(selected.toPath(), dest.toPath());
                    selected = dest;
                    setStatus(StatusBadge.Kind.SUCCESS, "Copied", "Library copied to library folder.");
                } catch (IOException e) {
                    setStatus(StatusBadge.Kind.DANGER, "Error", "Copy failed: " + e.getMessage());
                    return;
                }
            }
        }
        try {
            classSource.importLibrary(selected);
            refreshLibraryList();
            refreshMissingClassesList();
            setStatus(StatusBadge.Kind.SUCCESS, "Imported", "Imported " + selected.getName());
        } catch (IOException e) {
            setStatus(StatusBadge.Kind.DANGER, "Error", "Import failed: " + e.getMessage());
        }
    }

    private void removeManualLibrary() {
        String selected = libraryList.getSelectedValue();
        if (selected == null) return;
        File f = libraryFolder.resolve(selected).toFile();
        if (!f.exists()) return;
        int r = JOptionPane.showConfirmDialog(this,
                "Remove this library?\nThe file will be deleted from the library folder.",
                "Remove library", JOptionPane.YES_NO_OPTION);
        if (r != JOptionPane.YES_OPTION) return;
        if (f.delete()) {
            setStatus(StatusBadge.Kind.SUCCESS, "Removed", "Removed " + selected);
            classSource.getLibraries().removeIf(l -> l.getParent().getName().equals(selected));
            refreshLibraryList();
            refreshMissingClassesList();
        } else {
            setStatus(StatusBadge.Kind.DANGER, "Error", "Failed to remove file.");
        }
    }

    private static class MavenArtifact {
        final String groupId, artifactId, version;
        MavenArtifact(String g, String a, String v) { groupId = g; artifactId = a; version = v; }
        @Override public String toString() { return groupId + ":" + artifactId + ":" + version; }
    }
}
