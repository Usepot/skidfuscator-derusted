package dev.skidfuscator.obfuscator.gui;

import dev.skidfuscator.jvm.Jvm;
import dev.skidfuscator.obfuscator.gui.autosave.AutoSaveDocumentListener;
import dev.skidfuscator.obfuscator.gui.config.SkidfuscatorConfig;
import dev.skidfuscator.obfuscator.gui.ui.Card;
import dev.skidfuscator.obfuscator.gui.ui.SecondaryButton;
import dev.skidfuscator.obfuscator.gui.ui.SectionHeader;
import dev.skidfuscator.obfuscator.gui.ui.StatusBadge;
import dev.skidfuscator.obfuscator.gui.ui.UiTheme;
import dev.skidfuscator.obfuscator.util.JdkDownloader;
import dev.skidfuscator.obfuscator.util.Observable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.List;

public class ConfigPanel extends JPanel implements SkidPanel {

    private final JTextField inputField;
    private final JTextField outputField;
    private final JTextField libsField;
    private final JTextField runtimeField;
    private final JCheckBox debugBox;
    private final SkidfuscatorConfig config;

    private final StatusBadge inputBadge   = new StatusBadge();
    private final StatusBadge outputBadge  = new StatusBadge();
    private final StatusBadge libsBadge    = new StatusBadge();
    private final StatusBadge runtimeBadge = new StatusBadge();

    private final Observable<Boolean> runtimeInstalled = new Observable.SimpleObservable<>(
            JdkDownloader.isJdkDownloaded()
    );

    public ConfigPanel() {
        setLayout(new BorderLayout());
        setOpaque(false);

        config = SkidfuscatorConfig.load();

        // Fields must exist before we build the cards that reference them.
        inputField   = new JTextField();
        outputField  = new JTextField();
        libsField    = new JTextField();
        runtimeField = new JTextField();
        debugBox     = new JCheckBox("Debug mode");
        debugBox.setOpaque(false);
        debugBox.setForeground(UiTheme.TEXT_PRIMARY);
        debugBox.setFont(UiTheme.font(Font.PLAIN, 12f));
        debugBox.setSelected(config.isDebugEnabled());
        SectionHeader header = new SectionHeader(
                "Configuration",
                "Point Skidfuscator at the jar you want to obfuscate, then pick where the output goes.");
        add(header, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        body.add(buildFormCard());
        body.add(Box.createVerticalStrut(UiTheme.PAD_M));
        body.add(buildOptionsCard());
        body.add(Box.createVerticalStrut(UiTheme.PAD_M));
        body.add(buildLegendCard());
        body.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(body);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        installFormRows();
        installListeners();
        loadFromConfig();
        setupAutoSave();
    }

    // ------------------------------------------------------------------
    // Card layout
    // ------------------------------------------------------------------

    private Card formCard;
    private GridBagConstraints rowGbc;
    private int rowIndex = 0;

    private Card buildFormCard() {
        formCard = new Card(new GridBagLayout());
        formCard.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L));
        return formCard;
    }

    private void installFormRows() {
        rowGbc = new GridBagConstraints();
        rowGbc.fill = GridBagConstraints.HORIZONTAL;
        rowGbc.insets = new Insets(8, 8, 8, 8);

        addFormRow("Input JAR",   "Drag a jar here or click Browse.",        inputField,   inputBadge,   browseFile(inputField, PickerKind.INPUT_JAR));
        addFormRow("Output JAR",  ".jar / .apk / .dex destination for the result.", outputField,  outputBadge,  browseFile(outputField, PickerKind.OUTPUT_JAR));
        addFormRow("Libraries",   "Optional folder of dependency jars.",        libsField,    libsBadge,    browseFile(libsField, PickerKind.DIRECTORY));
        addRuntimeRow();

        installFileDrop(inputField,  this::handleInputDrop);
        installFileDrop(outputField, this::handleOutputDrop);
        installFileDrop(libsField,   this::handleLibsDrop);
        installFileDrop(formCard,    this::handleSmartDrop);
        installFileDrop(this,        this::handleSmartDrop);
    }

    private void addFormRow(String label, String helper, JTextField field, StatusBadge badge, JButton browse) {
        // Label + helper
        JPanel labelPanel = new JPanel();
        labelPanel.setOpaque(false);
        labelPanel.setLayout(new BoxLayout(labelPanel, BoxLayout.Y_AXIS));
        JLabel l = new JLabel(label);
        l.setForeground(UiTheme.TEXT_PRIMARY);
        l.setFont(UiTheme.font(Font.BOLD, 12f));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        labelPanel.add(l);
        JLabel help = new JLabel(helper);
        help.setForeground(UiTheme.TEXT_MUTED);
        help.setFont(UiTheme.font(Font.PLAIN, 11f));
        help.setAlignmentX(Component.LEFT_ALIGNMENT);
        labelPanel.add(help);

        field.putClientProperty("JTextField.placeholderText", "Browse or paste a path…");
        field.setFont(UiTheme.font(Font.PLAIN, 13f));
        field.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        rowGbc.gridy = rowIndex;
        rowGbc.gridx = 0;
        rowGbc.weightx = 0;
        rowGbc.gridwidth = 1;
        formCard.add(labelPanel, rowGbc);

        rowGbc.gridx = 1;
        rowGbc.weightx = 1;
        formCard.add(field, rowGbc);

        rowGbc.gridx = 2;
        rowGbc.weightx = 0;
        formCard.add(browse, rowGbc);

        rowGbc.gridx = 3;
        formCard.add(badge, rowGbc);

        rowIndex++;
    }

    private void addRuntimeRow() {
        JPanel labelPanel = new JPanel();
        labelPanel.setOpaque(false);
        labelPanel.setLayout(new BoxLayout(labelPanel, BoxLayout.Y_AXIS));
        JLabel l = new JLabel("Runtime");
        l.setForeground(UiTheme.TEXT_PRIMARY);
        l.setFont(UiTheme.font(Font.BOLD, 12f));
        labelPanel.add(l);
        JLabel help = new JLabel("JDK modules used during analysis.");
        help.setForeground(UiTheme.TEXT_MUTED);
        help.setFont(UiTheme.font(Font.PLAIN, 11f));
        labelPanel.add(help);

        runtimeField.putClientProperty("JTextField.placeholderText", "Auto-detected after install");
        runtimeField.setFont(UiTheme.font(Font.PLAIN, 13f));
        runtimeField.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JButton install = new SecondaryButton("Install");
        install.addActionListener(e -> performInstall(install));

        rowGbc.gridy = rowIndex;
        rowGbc.gridx = 0;
        rowGbc.weightx = 0;
        formCard.add(labelPanel, rowGbc);

        rowGbc.gridx = 1;
        rowGbc.weightx = 1;
        formCard.add(runtimeField, rowGbc);

        rowGbc.gridx = 2;
        rowGbc.weightx = 0;
        formCard.add(install, rowGbc);

        rowGbc.gridx = 3;
        formCard.add(runtimeBadge, rowGbc);

        if (JdkDownloader.isJdkDownloaded()) {
            install.setText("Installed");
            install.setEnabled(false);
        }
        rowIndex++;
    }

    private Card buildOptionsCard() {
        Card card = new Card(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, UiTheme.PAD_L, UiTheme.PAD_M, UiTheme.PAD_L));

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTheme.PAD_M, 0));
        options.setOpaque(false);
        options.add(debugBox);
        card.add(options, BorderLayout.WEST);

        JButton save = new SecondaryButton("Save settings");
        save.addActionListener(e -> {
            saveConfiguration();
            JOptionPane.showMessageDialog(this, "Settings saved.", "Saved", JOptionPane.INFORMATION_MESSAGE);
        });
        card.add(save, BorderLayout.EAST);
        return card;
    }

    private Card buildLegendCard() {
        Card card = new Card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, UiTheme.PAD_L, UiTheme.PAD_M, UiTheme.PAD_L));

        JLabel title = new JLabel("Status legend");
        title.setForeground(UiTheme.TEXT_PRIMARY);
        title.setFont(UiTheme.font(Font.BOLD, 12f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(title);
        card.add(Box.createVerticalStrut(6));

        card.add(legendRow(new StatusBadge(StatusBadge.Kind.SUCCESS, "OK"),     "Field is valid."));
        card.add(legendRow(new StatusBadge(StatusBadge.Kind.WARNING, "Optional"),"Field is optional, leave blank to skip."));
        card.add(legendRow(new StatusBadge(StatusBadge.Kind.DANGER,  "Action"), "Resolve before starting."));
        return card;
    }

    private JPanel legendRow(StatusBadge badge, String text) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTheme.PAD_M, 4));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(badge);
        JLabel label = new JLabel(text);
        label.setForeground(UiTheme.TEXT_SECONDARY);
        label.setFont(UiTheme.font(Font.PLAIN, 12f));
        row.add(label);
        return row;
    }

    // ------------------------------------------------------------------
    // Validation listeners
    // ------------------------------------------------------------------

    private void installListeners() {
        inputField.getDocument().addDocumentListener(simple(() -> {
            boolean valid = !inputField.getText().isEmpty() && new File(inputField.getText()).exists();
            config.getValidInput().set(valid);
            if (inputField.getText().isEmpty()) inputBadge.set(StatusBadge.Kind.DANGER, "Required");
            else inputBadge.set(valid ? StatusBadge.Kind.SUCCESS : StatusBadge.Kind.DANGER,
                    valid ? "OK" : "Missing");
            if (valid && outputField.getText().trim().isEmpty()) {
                String suggested = suggestOutputName(inputField.getText());
                if (suggested != null) outputField.setText(suggested);
            }
        }));

        outputField.getDocument().addDocumentListener(simple(() -> {
            String text = outputField.getText();
            File parent = text.isEmpty() ? null : new File(text).getParentFile();
            boolean okEnd = text.endsWith(".jar") || text.endsWith(".apk") || text.endsWith(".dex");
            boolean diff  = !text.equals(inputField.getText());
            boolean valid = parent != null && parent.exists() && okEnd && diff && !text.isEmpty();
            config.getValidOutput().set(valid);
            if (text.isEmpty())             outputBadge.set(StatusBadge.Kind.DANGER, "Required");
            else if (!okEnd)                outputBadge.set(StatusBadge.Kind.DANGER, ".jar/.apk/.dex");
            else if (!diff)                 outputBadge.set(StatusBadge.Kind.DANGER, "Same as input");
            else if (parent == null || !parent.exists())
                outputBadge.set(StatusBadge.Kind.DANGER, "Folder?");
            else                            outputBadge.set(StatusBadge.Kind.SUCCESS, "OK");
        }));

        libsField.getDocument().addDocumentListener(simple(() -> {
            String text = libsField.getText();
            if (text.isEmpty()) {
                libsBadge.set(StatusBadge.Kind.WARNING, "Optional");
            } else {
                File dir = new File(text);
                libsBadge.set(dir.exists() && dir.isDirectory() ? StatusBadge.Kind.SUCCESS : StatusBadge.Kind.DANGER,
                        dir.exists() ? "OK" : "Missing");
            }
        }));

        runtimeField.getDocument().addDocumentListener(simple(this::refreshRuntimeBadge));
    }

    private void loadFromConfig() {
        if (config.getLastInputPath() != null)  inputField.setText(config.getLastInputPath());
        if (config.getLastOutputPath() != null) outputField.setText(config.getLastOutputPath());
        else if (!inputField.getText().isEmpty()) outputField.setText(inputField.getText().replace(".jar", "-obf.jar"));
        if (config.getLastLibsPath() != null)   libsField.setText(config.getLastLibsPath());

        try {
            String jmodPath = JdkDownloader.getCachedJmodPath();
            runtimeField.setText(jmodPath);
            runtimeField.setEnabled(!JdkDownloader.isJdkDownloaded());
            runtimeInstalled.set(JdkDownloader.isJdkDownloaded());
        } catch (IOException e) {
            if (config.getLastRuntimePath() != null) {
                if (config.getLastRuntimePath().isEmpty()) {
                    runtimeField.setText(Jvm.getLibsPath());
                    runtimeField.setEnabled(false);
                    runtimeInstalled.set(true);
                } else {
                    runtimeField.setText(config.getLastRuntimePath());
                }
            }
        }
        refreshRuntimeBadge();
    }

    private void refreshRuntimeBadge() {
        if (JdkDownloader.isJdkDownloaded()) {
            runtimeBadge.set(StatusBadge.Kind.SUCCESS, "Installed");
        } else if (runtimeField.getText().isEmpty()) {
            runtimeBadge.set(StatusBadge.Kind.DANGER, "Install");
        } else {
            runtimeBadge.set(StatusBadge.Kind.WARNING, "Manual");
        }
    }

    private void performInstall(JButton install) {
        install.setEnabled(false);
        install.setText("Downloading…");
        runtimeBadge.set(StatusBadge.Kind.INFO, "Downloading…");

        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return JdkDownloader.getJmodPath();
            }
            @Override protected void done() {
                try {
                    String path = get();
                    runtimeField.setText(path);
                    runtimeField.setEnabled(false);
                    install.setText("Installed");
                    install.setEnabled(false);
                    runtimeInstalled.set(true);
                    runtimeBadge.set(StatusBadge.Kind.SUCCESS, "Installed");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(ConfigPanel.this,
                            "Failed to download JDK: " + ex.getMessage(),
                            "Download error", JOptionPane.ERROR_MESSAGE);
                    install.setText("Install");
                    install.setEnabled(true);
                    runtimeInstalled.set(false);
                    runtimeBadge.set(StatusBadge.Kind.DANGER, "Failed");
                }
            }
        };
        worker.execute();
    }

    // ------------------------------------------------------------------
    // Browse / persistence
    // ------------------------------------------------------------------

    private enum PickerKind { INPUT_JAR, OUTPUT_JAR, DIRECTORY }

    private JButton browseFile(JTextField field, PickerKind kind) {
        JButton btn = new SecondaryButton("Browse");
        btn.addActionListener(e -> openNativePicker(field, kind));
        return btn;
    }

    private void openNativePicker(JTextField field, PickerKind kind) {
        Frame parent = (Frame) SwingUtilities.getWindowAncestor(this);

        String startPath = (field.getText() == null || field.getText().isEmpty())
                ? config.getLastDirectory()
                : field.getText();
        File start = startPath == null ? null : new File(startPath);

        String title;
        int mode;
        switch (kind) {
            case OUTPUT_JAR: title = "Choose output destination"; mode = FileDialog.SAVE; break;
            case DIRECTORY:  title = "Pick any file in the libraries folder"; mode = FileDialog.LOAD; break;
            default:         title = "Choose input jar"; mode = FileDialog.LOAD;
        }

        // FileDialog uses the native Windows common dialog on Win32.
        FileDialog dialog = new FileDialog(parent, title, mode);
        if (start != null) {
            if (start.isDirectory()) {
                dialog.setDirectory(start.getAbsolutePath());
            } else if (start.getParentFile() != null) {
                dialog.setDirectory(start.getParentFile().getAbsolutePath());
                if (kind == PickerKind.OUTPUT_JAR || kind == PickerKind.INPUT_JAR) {
                    dialog.setFile(start.getName());
                }
            }
        }
        if (kind != PickerKind.DIRECTORY) {
            dialog.setFile("*.jar;*.apk;*.dex");
            dialog.setFilenameFilter(jarFilter());
        }
        if (kind == PickerKind.OUTPUT_JAR && (dialog.getFile() == null || dialog.getFile().isEmpty())) {
            String suggestion = suggestOutputName(inputField.getText());
            if (suggestion != null) dialog.setFile(new File(suggestion).getName());
        }

        dialog.setVisible(true);
        if (dialog.getFile() == null) return;

        File picked = new File(dialog.getDirectory(), dialog.getFile());
        File finalPath = (kind == PickerKind.DIRECTORY) ? picked.getParentFile() : picked;
        if (finalPath == null) return;

        field.setText(finalPath.getAbsolutePath());
        config.setLastDirectory(dialog.getDirectory());
        saveConfiguration();
    }

    private static FilenameFilter jarFilter() {
        return (dir, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".dex");
        };
    }

    // ------------------------------------------------------------------
    // Drag & drop
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface DropConsumer {
        void accept(List<File> files);
    }

    private void installFileDrop(JComponent target, DropConsumer onDrop) {
        target.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (files != null && !files.isEmpty()) {
                        onDrop.accept(files);
                        return true;
                    }
                } catch (Exception ignored) { }
                return false;
            }
        });
    }

    private void handleInputDrop(List<File> files) {
        File f = files.get(0);
        if (f.isDirectory()) {
            libsField.setText(f.getAbsolutePath());
        } else {
            inputField.setText(f.getAbsolutePath());
            maybeFillOutputFrom(f);
        }
    }

    private void handleOutputDrop(List<File> files) {
        File f = files.get(0);
        if (f.isDirectory()) {
            // Drop a folder onto output → suggest <input-name>-obf.jar inside it.
            String suggested = suggestOutputName(inputField.getText());
            if (suggested != null) {
                outputField.setText(new File(f, new File(suggested).getName()).getAbsolutePath());
            } else {
                outputField.setText(f.getAbsolutePath());
            }
        } else {
            outputField.setText(f.getAbsolutePath());
        }
    }

    private void handleLibsDrop(List<File> files) {
        File f = files.get(0);
        libsField.setText(f.isDirectory() ? f.getAbsolutePath()
                : (f.getParentFile() == null ? f.getAbsolutePath() : f.getParentFile().getAbsolutePath()));
    }

    private void handleSmartDrop(List<File> files) {
        File f = files.get(0);
        if (f.isDirectory()) {
            libsField.setText(f.getAbsolutePath());
        } else {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".jar") || name.endsWith(".apk") || name.endsWith(".dex")) {
                inputField.setText(f.getAbsolutePath());
                maybeFillOutputFrom(f);
            }
        }
    }

    private void maybeFillOutputFrom(File input) {
        if (!outputField.getText().trim().isEmpty()) return;
        String suggested = suggestOutputName(input.getAbsolutePath());
        if (suggested != null) outputField.setText(suggested);
    }

    private static String suggestOutputName(String inputPath) {
        if (inputPath == null || inputPath.trim().isEmpty()) return null;
        File in = new File(inputPath.trim());
        String name = in.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "jar" : name.substring(dot + 1);
        File parent = in.getParentFile();
        File suggested = new File(parent != null ? parent : new File("."), stem + "-obf." + ext);
        return suggested.getAbsolutePath();
    }

    private void setupAutoSave() {
        inputField  .getDocument().addDocumentListener(new AutoSaveDocumentListener(this::saveConfiguration));
        outputField .getDocument().addDocumentListener(new AutoSaveDocumentListener(this::saveConfiguration));
        libsField   .getDocument().addDocumentListener(new AutoSaveDocumentListener(this::saveConfiguration));
        runtimeField.getDocument().addDocumentListener(new AutoSaveDocumentListener(this::saveConfiguration));
        debugBox.addActionListener(e -> saveConfiguration());
    }

    private void saveConfiguration() {
        SwingUtilities.invokeLater(() -> new SkidfuscatorConfig.Builder()
                .setLastInputPath(inputField.getText())
                .setLastOutputPath(outputField.getText())
                .setLastLibsPath(libsField.getText())
                .setLastRuntimePath(runtimeField.getText())
                .setDebugEnabled(debugBox.isSelected())
                .setLastDirectory(config.getLastDirectory())
                .build()
                .save());
    }

    private static DocumentListener simple(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    // ------------------------------------------------------------------
    // Accessors used by MainFrame
    // ------------------------------------------------------------------

    public String getInputPath()   { return inputField.getText(); }
    public String getOutputPath()  { return outputField.getText(); }
    public String getLibsPath()    { return libsField.getText(); }
    public String getRuntimePath() { return runtimeField.getText(); }
    public boolean isDebugEnabled(){ return debugBox.isSelected(); }
    public String getLibraryPath() { return null; }
    public Observable<Boolean> getRuntimeInstalled() { return runtimeInstalled; }
    public SkidfuscatorConfig getConfig() { return config; }
}
