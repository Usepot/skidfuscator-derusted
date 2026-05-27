package dev.skidfuscator.obfuscator.gui;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import dev.skidfuscator.obfuscator.gui.proguard.ProGuardClassRenamer;
import dev.skidfuscator.obfuscator.gui.ui.NavItem;
import dev.skidfuscator.obfuscator.gui.ui.PrimaryButton;
import dev.skidfuscator.obfuscator.gui.ui.SecondaryButton;
import dev.skidfuscator.obfuscator.gui.ui.StatusBadge;
import dev.skidfuscator.obfuscator.gui.ui.UiTheme;
import dev.skidfuscator.obfuscator.util.JdkDownloader;
import lombok.Getter;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class MainFrame extends JFrame {

    private static final String VIEW_CONFIG       = "config";
    private static final String VIEW_LIBRARIES    = "libraries";
    private static final String VIEW_TRANSFORMERS = "transformers";
    private static final String VIEW_EXEMPTIONS   = "exemptions";
    private static final String VIEW_CONSOLE      = "console";

    private ConfigPanel configPanel;
    private TransformerPanel transformerPanel;
    private ExemptionPanel exemptionPanel;
    private ConsolePanel consolePanel;
    private LibrariesPanel librariesPanel;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final Map<String, NavItem> navItems = new LinkedHashMap<>();

    private PrimaryButton startButton;
    private SecondaryButton enterpriseButton;
    private SecondaryButton discordButton;
    private JProgressBar statusProgress;
    private JLabel statusLabel;
    private StatusBadge statusBadge;

    private String activeView = VIEW_CONFIG;

    public MainFrame() {
        setTitle("Skidfuscator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setPreferredSize(new Dimension(1180, 760));
        setMinimumSize(new Dimension(960, 640));
        setLayout(new BorderLayout());

        loadIcon();
        getContentPane().setBackground(UiTheme.CONTENT_BG);

        configPanel      = new ConfigPanel();
        transformerPanel = new TransformerPanel();
        exemptionPanel   = new ExemptionPanel(configPanel.getConfig(), configPanel);
        consolePanel     = new ConsolePanel();
        librariesPanel   = new LibrariesPanel(configPanel, null);

        add(buildSidebar(), BorderLayout.WEST);
        add(buildContent(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        // Initial selection
        showView(VIEW_CONFIG);

        // Validation wiring → enable/disable the start button
        configPanel.getConfig().getValidInput().addObserver(v -> refreshStartButton());
        configPanel.getConfig().getValidOutput().addObserver(v -> refreshStartButton());
        configPanel.getRuntimeInstalled().addObserver(v -> refreshStartButton());
        refreshStartButton();

        installKeyboardShortcuts();

        pack();
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------
    // Sidebar
    // ------------------------------------------------------------------

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(UiTheme.SIDEBAR_BG);
        sidebar.setPreferredSize(new Dimension(220, 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_M, UiTheme.PAD_L, UiTheme.PAD_M));

        sidebar.add(buildBrandHeader(),  BorderLayout.NORTH);
        sidebar.add(buildNav(),          BorderLayout.CENTER);
        sidebar.add(buildSidebarFooter(),BorderLayout.SOUTH);
        return sidebar;
    }

    private JComponent buildBrandHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 6, UiTheme.PAD_L, 6));

        JLabel logo = new JLabel();
        logo.setAlignmentX(Component.LEFT_ALIGNMENT);
        try {
            InputStream stream = getClass().getResourceAsStream("/images/logo.png");
            if (stream != null) {
                Image img = ImageIO.read(stream).getScaledInstance(140, 140, Image.SCALE_SMOOTH);
                logo.setIcon(new ImageIcon(img));
            }
        } catch (IOException ignored) {}
        header.add(logo);

        JLabel name = new JLabel("Skidfuscator");
        name.setForeground(UiTheme.TEXT_PRIMARY);
        name.setFont(UiTheme.font(Font.BOLD, 16f));
        name.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, 0, 2, 0));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(name);

        JPanel meta = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        meta.setOpaque(false);
        meta.setAlignmentX(Component.LEFT_ALIGNMENT);
        meta.add(new StatusBadge(StatusBadge.Kind.INFO, "Community"));
        JLabel version = new JLabel("v" + Skidfuscator.VERSION);
        version.setFont(UiTheme.font(Font.PLAIN, 11f));
        version.setForeground(UiTheme.TEXT_MUTED);
        meta.add(version);
        header.add(meta);

        return header;
    }

    private JComponent buildNav() {
        JPanel nav = new JPanel();
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setOpaque(false);

        addNavItem(nav, VIEW_CONFIG,       "⚙",  "Configuration", KeyEvent.VK_C);
        addNavItem(nav, VIEW_LIBRARIES,    "📚", "Libraries",     KeyEvent.VK_L);
        addNavItem(nav, VIEW_TRANSFORMERS, "🧬", "Transformers",  KeyEvent.VK_T);
        addNavItem(nav, VIEW_EXEMPTIONS,   "✚",  "Exemptions",    KeyEvent.VK_E);
        addNavItem(nav, VIEW_CONSOLE,      "▣",  "Console",       KeyEvent.VK_O);

        nav.add(Box.createVerticalGlue());
        return nav;
    }

    private void addNavItem(JPanel nav, String id, String glyph, String label, int mnemonic) {
        NavItem item = new NavItem(glyph, label, (char) mnemonic, n -> showView(id));
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        navItems.put(id, item);
        nav.add(item);
        nav.add(Box.createVerticalStrut(2));
    }

    private JComponent buildSidebarFooter() {
        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, 4, 0, 4));

        JSeparator sep = new JSeparator();
        sep.setForeground(UiTheme.SUBTLE_BORDER);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        footer.add(sep);
        footer.add(Box.createVerticalStrut(UiTheme.PAD_M));

        JLabel copyright = new JLabel("© 2025 Skidfuscator");
        copyright.setFont(UiTheme.font(Font.BOLD, 10f));
        copyright.setForeground(UiTheme.TEXT_MUTED);
        copyright.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(copyright);

        JLabel website = new JLabel("skidfuscator.dev ↗");
        website.setFont(UiTheme.font(Font.PLAIN, 11f));
        website.setForeground(UiTheme.ACCENT);
        website.setCursor(new Cursor(Cursor.HAND_CURSOR));
        website.setAlignmentX(Component.LEFT_ALIGNMENT);
        website.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        website.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { openUrl("https://skidfuscator.dev"); }
        });
        footer.add(website);

        return footer;
    }

    // ------------------------------------------------------------------
    // Content area
    // ------------------------------------------------------------------

    private JComponent buildContent() {
        cardPanel.setBackground(UiTheme.CONTENT_BG);
        cardPanel.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L));
        cardPanel.add(configPanel,      VIEW_CONFIG);
        cardPanel.add(librariesPanel,   VIEW_LIBRARIES);
        cardPanel.add(transformerPanel, VIEW_TRANSFORMERS);
        cardPanel.add(exemptionPanel,   VIEW_EXEMPTIONS);
        cardPanel.add(consolePanel,     VIEW_CONSOLE);
        return cardPanel;
    }

    private void showView(String id) {
        if (VIEW_LIBRARIES.equals(id) && !JdkDownloader.isJdkDownloaded()) {
            JOptionPane.showMessageDialog(this,
                    "Please install the JDK runtime first (Configuration tab).",
                    "Runtime missing",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        activeView = id;
        cards.show(cardPanel, id);
        navItems.forEach((key, nav) -> nav.setSelected(key.equals(id)));

        SkidPanel panel = panelForView(id);
        if (panel != null) panel.open();
    }

    private SkidPanel panelForView(String id) {
        switch (id) {
            case VIEW_CONFIG:       return configPanel;
            case VIEW_LIBRARIES:    return librariesPanel;
            case VIEW_TRANSFORMERS: return null;
            case VIEW_EXEMPTIONS:   return exemptionPanel;
            case VIEW_CONSOLE:      return consolePanel;
            default: return null;
        }
    }

    // ------------------------------------------------------------------
    // Status bar
    // ------------------------------------------------------------------

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(UiTheme.PAD_M, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(UiTheme.SIDEBAR_BG);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(UiTheme.SUBTLE_BORDER);
                    g2.drawLine(0, 0, getWidth(), 0);
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, UiTheme.PAD_L, UiTheme.PAD_M, UiTheme.PAD_L));
        bar.setPreferredSize(new Dimension(0, 70));

        // Left: state badge + label + progress
        JPanel state = new JPanel();
        state.setLayout(new BoxLayout(state, BoxLayout.X_AXIS));
        state.setOpaque(false);

        statusBadge = new StatusBadge(StatusBadge.Kind.NEUTRAL, "Idle");
        state.add(statusBadge);
        state.add(Box.createHorizontalStrut(UiTheme.PAD_M));

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));

        statusLabel = new JLabel("Ready when you are.");
        statusLabel.setFont(UiTheme.font(Font.PLAIN, 12f));
        statusLabel.setForeground(UiTheme.TEXT_SECONDARY);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.add(statusLabel);

        statusProgress = new JProgressBar();
        statusProgress.setIndeterminate(false);
        statusProgress.setStringPainted(false);
        statusProgress.setMaximumSize(new Dimension(360, 6));
        statusProgress.setPreferredSize(new Dimension(360, 6));
        statusProgress.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusProgress.setVisible(false);
        info.add(Box.createVerticalStrut(4));
        info.add(statusProgress);

        state.add(info);
        bar.add(state, BorderLayout.WEST);

        // Right: action buttons
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTheme.PAD_S, 0));
        actions.setOpaque(false);

        discordButton = new SecondaryButton("Discord");
        discordButton.addActionListener(e -> openUrl("https://discord.gg/QJC9g8fBU9"));
        actions.add(discordButton);

        enterpriseButton = new SecondaryButton("Buy Enterprise");
        enterpriseButton.addActionListener(e -> openUrl("https://skidfuscator.dev/pricing"));
        actions.add(enterpriseButton);

        startButton = new PrimaryButton("Start Obfuscation");
        startButton.addActionListener(e -> startObfuscation());
        actions.add(startButton);

        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------------
    // Keyboard shortcuts
    // ------------------------------------------------------------------

    private void installKeyboardShortcuts() {
        int meta = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        KeyStroke ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, meta);
        getRootPane().registerKeyboardAction(
                e -> startObfuscation(),
                ctrlEnter,
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        navItems.forEach((id, item) -> {
            KeyStroke ks = KeyStroke.getKeyStroke(item.getMnemonic(), java.awt.event.InputEvent.ALT_DOWN_MASK);
            getRootPane().registerKeyboardAction(e -> showView(id), ks, JComponent.WHEN_IN_FOCUSED_WINDOW);
        });
    }

    // ------------------------------------------------------------------
    // Status helpers / obfuscation worker
    // ------------------------------------------------------------------

    public void setStatus(StatusBadge.Kind kind, String badge, String message, boolean showProgress, Integer percent) {
        SwingUtilities.invokeLater(() -> {
            statusBadge.set(kind, badge);
            statusLabel.setText(message);
            statusProgress.setVisible(showProgress);
            if (percent != null) {
                statusProgress.setIndeterminate(false);
                statusProgress.setValue(Math.max(0, Math.min(100, percent)));
            } else if (showProgress) {
                statusProgress.setIndeterminate(true);
            }
        });
    }

    private void refreshStartButton() {
        boolean ready = configPanel.getConfig().isValid() && configPanel.getRuntimeInstalled().get();
        startButton.setEnabled(ready);
        if (ready) {
            setStatus(StatusBadge.Kind.SUCCESS, "Ready", "All checks passed. Press " + shortcutLabel() + " to obfuscate.", false, null);
        } else {
            setStatus(StatusBadge.Kind.WARNING, "Setup",
                    "Resolve the highlighted fields in the Configuration tab to start.", false, null);
        }
    }

    private static String shortcutLabel() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac") ? "⌘↩" : "Ctrl+Enter";
    }

    private void openUrl(String url) {
        try { Desktop.getDesktop().browse(new URI(url)); } catch (Exception ignored) {}
    }

    private void loadIcon() {
        try {
            InputStream iconStream = getClass().getResourceAsStream("/images/logo.png");
            if (iconStream != null) setIconImage(ImageIO.read(iconStream));
        } catch (Exception ignored) {}
    }

    public ConfigPanel getConfigPanel() { return configPanel; }
    public TransformerPanel getTransformerPanel() { return transformerPanel; }
    public ExemptionPanel getExemptionPanel() { return exemptionPanel; }

    /**
     * Build a HOCON config file that bakes in the user's exemption rules so the
     * obfuscator picks them up via {@code session.config(...)}. Returns null if
     * there are no rules — Skidfuscator's defaults handle that case.
     */
    private File writeRuntimeConfig() {
        java.util.List<String> exemptions = exemptionPanel.getRenderedExemptions();
        try {
            Path tmp = Files.createTempFile("skidfuscator-runtime-", ".conf");
            tmp.toFile().deleteOnExit();
            Files.writeString(tmp, transformerPanel.renderConfiguration(exemptions));
            return tmp.toFile();
        } catch (IOException e) {
            Skidfuscator.LOGGER.error("Failed to write runtime config", e);
            return null;
        }
    }

    private void deleteTemporaryFile(File file, String label) {
        if (file == null) {
            return;
        }

        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            file.deleteOnExit();
            Skidfuscator.LOGGER.warn("Could not delete temporary " + label + "; it will be removed on JVM exit: " + e.getMessage());
        }
    }

    public void startObfuscation() {
        if (!startButton.isEnabled()) return;

        ConfigPanel config = getConfigPanel();
        if (config.getInputPath().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select an input JAR file", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        showView(VIEW_CONSOLE);

        Path libraryFolder;
        String configLibPath = config.getLibraryPath();
        if (configLibPath != null && !configLibPath.isEmpty()) {
            libraryFolder = Paths.get(configLibPath);
        } else {
            libraryFolder = Paths.get(System.getProperty("user.home"), ".ssvm", "libs");
        }
        try { Files.createDirectories(libraryFolder); }
        catch (IOException e) { Skidfuscator.LOGGER.error("Failed to create library folder", e); }

        File[] libs = config.getLibsPath().isEmpty()
                ? libraryFolder.toFile().listFiles()
                : new File(config.getLibsPath()).listFiles();

        File runtimeConfig = writeRuntimeConfig();
        File selectedInput = new File(config.getInputPath());
        File selectedOutput = new File(config.getOutputPath());
        File skidInput = selectedInput;
        File proGuardOutput = null;
        boolean proGuardClassRenaming = transformerPanel.isProGuardClassRenamingEnabled();
        if (proGuardClassRenaming) {
            try {
                Path tempRenamedOutput = Files.createTempFile("skidfuscator-proguard-input-", ".jar");
                skidInput = tempRenamedOutput.toFile();
                proGuardOutput = skidInput;
                proGuardOutput.deleteOnExit();
            } catch (IOException e) {
                Skidfuscator.LOGGER.error("Failed to create ProGuard staging jar", e);
                JOptionPane.showMessageDialog(this,
                        "Could not create a temporary ProGuard input jar: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        SkidfuscatorSession.SkidfuscatorSessionBuilder sessionBuilder = SkidfuscatorSession.builder()
                .input(skidInput)
                .output(selectedOutput)
                .libs(libs)
                .jmod(config.getRuntimePath().contains("jmods"))
                .debug(config.isDebugEnabled());
        if (runtimeConfig != null) sessionBuilder.config(runtimeConfig);
        SkidfuscatorSession session = sessionBuilder.build();

        startButton.setEnabled(false);
        enterpriseButton.setEnabled(true);
        setStatus(StatusBadge.Kind.INFO, "Running", "Obfuscating " + new File(config.getInputPath()).getName() + "…", true, null);

        File finalSelectedInput = selectedInput;
        File finalProGuardOutput = proGuardOutput;
        boolean finalProGuardClassRenaming = proGuardClassRenaming;
        Map<String, Object> finalProGuardOptions = transformerPanel.getProGuardOptions();

        SwingWorker<Void, String> worker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                try {
                    if (finalProGuardClassRenaming) {
                        SwingUtilities.invokeLater(() -> setStatus(StatusBadge.Kind.INFO, "Renaming",
                                "Running ProGuard class renaming before Skidfuscator…", true, null));
                        ProGuardClassRenamer.renameClasses(
                                finalSelectedInput,
                                finalProGuardOutput,
                                new File(config.getRuntimePath()),
                                exemptionPanel.getRenderedExemptions(),
                                finalProGuardOptions
                        );
                        SwingUtilities.invokeLater(() -> setStatus(StatusBadge.Kind.INFO, "Running",
                                "Obfuscating ProGuard-renamed jar…", true, null));
                    }

                    new Skidfuscator(session).run();

                    deleteTemporaryFile(finalProGuardOutput, "ProGuard input jar");
                } catch (Exception e) {
                    SwingUtilities.invokeLater(e::printStackTrace);
                    throw new RuntimeException(e);
                }
                return null;
            }

            @Override
            protected void done() {
                startButton.setEnabled(true);
                boolean failed = isCancelled();
                try { get(); }
                catch (Exception e) { failed = true; }

                if (failed) {
                    setStatus(StatusBadge.Kind.DANGER, "Failed",
                            "Obfuscation failed — see the console for details.", false, null);
                    return;
                }

                setStatus(StatusBadge.Kind.SUCCESS, "Done",
                        "Obfuscation completed successfully.", false, null);

                int option = JOptionPane.showOptionDialog(
                        MainFrame.this,
                        "Obfuscation completed successfully!",
                        "Success",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.INFORMATION_MESSAGE,
                        UIManager.getIcon("OptionPane.informationIcon"),
                        new Object[]{"OK", "Open Output Folder"},
                        "OK");

                if (option == 1) {
                    try {
                        File outputFile = new File(configPanel.getOutputPath());
                        Desktop.getDesktop().open(outputFile.getParentFile());
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(MainFrame.this,
                                "Could not open output folder: " + ex.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        };
        worker.execute();
    }
}
