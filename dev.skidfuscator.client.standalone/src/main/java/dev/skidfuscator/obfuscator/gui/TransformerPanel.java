package dev.skidfuscator.obfuscator.gui;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import dev.skidfuscator.obfuscator.config.SkidfuscatorConfig;
import dev.skidfuscator.obfuscator.gui.transformer.TransformerOptionDefinition;
import dev.skidfuscator.obfuscator.gui.transformer.TransformerOptionType;
import dev.skidfuscator.obfuscator.gui.ui.Card;
import dev.skidfuscator.obfuscator.gui.ui.SecondaryButton;
import dev.skidfuscator.obfuscator.gui.ui.SectionHeader;
import dev.skidfuscator.obfuscator.gui.ui.StatusBadge;
import dev.skidfuscator.obfuscator.gui.ui.ToggleSwitch;
import dev.skidfuscator.obfuscator.gui.ui.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TransformerPanel extends JPanel {

    private static final List<String> DEFAULT_GLOBAL_EXEMPTIONS = Arrays.asList(
            "class{^jghost\\/}",
            "class{Dump}",
            "class{^org\\/jnativehook\\/}",
            "class{^com\\/sun\\/jna\\/}");

    private final Map<String, TransformerCard> sections = new LinkedHashMap<>();
    private final File defaultConfigFile = new File("skidfuscator-config.conf");
    private boolean loading;

    public TransformerPanel() {
        setLayout(new BorderLayout());
        setOpaque(false);

        add(new SectionHeader(
                "Transformers",
                "Toggle individual passes. Expand a card to tweak its options."),
                BorderLayout.NORTH);

        JPanel activeList = createListPanel();
        JPanel unusedList = createListPanel();
        initSections(activeList, unusedList);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setOpaque(false);
        tabs.addTab("Active", scrollFor(activeList));
        tabs.addTab("Unused / Unwired", scrollFor(unusedList));
        add(tabs, BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTheme.PAD_S, 0));
        toolbar.setOpaque(false);
        JButton load = new SecondaryButton("Load Config");
        JButton save = new SecondaryButton("Save Config");
        load.addActionListener(e -> loadConfiguration());
        save.addActionListener(e -> saveConfiguration());
        toolbar.add(load);
        toolbar.add(save);
        add(toolbar, BorderLayout.SOUTH);

        loadConfiguration(false);
    }

    private JPanel createListPanel() {
        JPanel list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(0, 0, UiTheme.PAD_L, 0));
        return list;
    }

    private JScrollPane scrollFor(JPanel list) {
        JScrollPane scroll = new JScrollPane(list);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void initSections(JPanel host, JPanel unusedHost) {
        // ---------------- Strings ----------------
        addCategory(host, "Strings", "Encrypt or obfuscate literal strings.");

        addSection(host, "stringEncryption", "String Encryption",
                "Encrypts string constants. STANDARD is the only published mode today.",
                true, null,
                Collections.singletonList(TransformerOptionDefinition.builder()
                        .key("type").label("Encryption type")
                        .type(TransformerOptionType.ENUM)
                        .enumValues(Collections.singletonList("STANDARD"))
                        .defaultValue("STANDARD")
                        .description("Type of string encryption to apply")
                        .build()));

        addCategory(unusedHost, "Strings", "Legacy/default config entries that are not registered in the current runtime.");

        addSection(unusedHost, "stringAnnotationEncryption", "String Annotation Encryption",
                "Encrypts string values stored in Java annotations at runtime.",
                true, "Unwired", Collections.emptyList());

        addSection(unusedHost, "intAnnotationEncryption", "Int Annotation Encryption",
                "Encrypts integer values stored in Java annotations.",
                true, "Unwired", Collections.emptyList());

        // ---------------- Numbers / Hashes ----------------
        addCategory(host, "Numbers & Hashing", "Polymorphic math, hashed comparisons and lookups.");

        addSection(host, "numberEncryption", "Number Encryption",
                "Encrypts numeric literals using mathematical transformations and the seeded hash table.",
                true, null, Collections.emptyList());

        addSection(host, "pureEncryption", "Pure Encryption",
                "Replaces pure-function calls with seeded hashed equivalents to harden return values.",
                true, null, Collections.emptyList());

        addSection(host, "stringEqualsHash", "String Equals Hash",
                "Rewrites String.equals comparisons against constants into hashed checks.",
                true, null, Collections.emptyList());

        addSection(host, "stringEqIgCaseHash", "String EqualsIgnoreCase Hash",
                "Rewrites String.equalsIgnoreCase comparisons into hashed checks.",
                true, null, Collections.emptyList());

        addSection(host, "typeCheck", "Type Check (instanceof)",
                "Replaces direct instanceof / Class.isInstance checks with hashed lookups.",
                true, null, Collections.emptyList());

        addCategory(unusedHost, "Numbers & Hashing", "Keys present in older configs but not consumed by the current transformer list.");

        addSection(unusedHost, "reference", "Reference Hardening",
                "Hardens method / field reference resolution. Off by default.",
                false, "Unwired", Collections.emptyList());

        // ---------------- Control Flow ----------------
        addCategory(host, "Control Flow", "Twist, split and re-route the method CFG.");

        addSection(host, "flowCondition", "Flow Condition",
                "Adds bogus conditions and opaque predicates to control flow branches.",
                true, null, Collections.emptyList());

        addSection(host, "flowException", "Flow Exception",
                "Wraps control flow in fake exception handlers to confuse decompilers.",
                true, null,
                Collections.singletonList(TransformerOptionDefinition.builder()
                        .key("strength").label("Strength")
                        .type(TransformerOptionType.ENUM)
                        .enumValues(Arrays.asList("WEAK", "GOOD", "AGGRESSIVE"))
                        .defaultValue("AGGRESSIVE")
                        .description("Flow exception transformation strength")
                        .build()));

        addSection(host, "flowRange", "Flow Range",
                "Splits loops and iterative structures to break decompiler heuristics.",
                true, null, Collections.emptyList());

        addSection(host, "flowSwitch", "Flow Switch",
                "Rewrites tableswitch / lookupswitch blocks with seeded keys.",
                true, null, Collections.emptyList());

        addCategory(unusedHost, "Control Flow", "Control-flow config entries without a registered transformer in this build.");

        addSection(unusedHost, "flowFactoryMaker", "Flow Factory Maker",
                "Materialises seed factories for inter-procedural flow obfuscation.",
                true, "Unwired", Collections.emptyList());

        addSection(unusedHost, "exceptionReturn", "Exception Return",
                "Replaces ordinary returns with exception-driven control flow exits.",
                true, "Unwired", Collections.emptyList());

        addSection(unusedHost, "outliner", "Outliner",
                "Outlines instruction blocks into synthetic helper methods.",
                true, "Unwired", Collections.emptyList());

        // ---------------- Inter-procedural ----------------
        addCategory(host, "Inter-procedural", "Cross-method seed plumbing (cannot be disabled in classic mode).");

        addSection(host, "interprocedural", "Interprocedural",
                "Threads obfuscation seeds through the call graph. Required by other passes.",
                true, null, Collections.emptyList());

        addSection(host, "interproceduralHarden", "Interprocedural Harden",
                "Hardens the seed plumbing with extra randomisation per call-site.",
                true, null, Collections.emptyList());

        addSection(host, "interproceduralPredicate", "Predicate Seed Storage",
                "Controls the base predicate renderer. Dynamic class predicates restore field-backed class/static seeds.",
                true, null,
                Collections.singletonList(TransformerOptionDefinition.builder()
                        .key("dynamicClassPredicates").label("Dynamic class predicates")
                        .type(TransformerOptionType.BOOLEAN)
                        .defaultValue(false)
                        .description("Store class/static predicates in private static fields instead of inlining constants")
                        .build()));

        // ---------------- Pre-processing ----------------
        addCategory(host, "Pre-processing", "Optional passes that prepare the input jar before Skidfuscator runs.");

        addSection(host, "proGuard", "ProGuard Class Renaming",
                "Runs ProGuard before Skidfuscator to rename classes, then feeds the renamed jar into Skidfuscator.",
                false, "Pre",
                Arrays.asList(
                        TransformerOptionDefinition.builder()
                                .key("dontshrink").label("Disable shrinking")
                                .type(TransformerOptionType.BOOLEAN)
                                .defaultValue(true)
                                .description("Adds -dontshrink")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("dontoptimize").label("Disable optimization")
                                .type(TransformerOptionType.BOOLEAN)
                                .defaultValue(true)
                                .description("Adds -dontoptimize")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("dontpreverify").label("Disable preverification")
                                .type(TransformerOptionType.BOOLEAN)
                                .defaultValue(true)
                                .description("Adds -dontpreverify")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("ignorewarnings").label("Ignore warnings")
                                .type(TransformerOptionType.BOOLEAN)
                                .defaultValue(true)
                                .description("Adds -ignorewarnings")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("overloadaggressively").label("Overload aggressively")
                                .type(TransformerOptionType.BOOLEAN)
                                .defaultValue(true)
                                .description("Adds -overloadaggressively")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("adaptclassstrings").label("Adapt class strings")
                                .type(TransformerOptionType.BOOLEAN)
                                .defaultValue(true)
                                .description("Adds -adaptclassstrings")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("adaptresourcefilenames").label("Adapt resource file names")
                                .type(TransformerOptionType.BOOLEAN)
                                .defaultValue(true)
                                .description("Adds -adaptresourcefilenames using the resource filter below")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("adaptresourcefilecontents").label("Adapt resource contents")
                                .type(TransformerOptionType.BOOLEAN)
                                .defaultValue(true)
                                .description("Adds -adaptresourcefilecontents using the resource filter below")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("keepmain").label("Keep main methods")
                                .type(TransformerOptionType.BOOLEAN)
                                .defaultValue(true)
                                .description("Keeps public static main methods while still allowing class obfuscation")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("keepattributes").label("Keep attributes")
                                .type(TransformerOptionType.STRING)
                                .defaultValue("Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions")
                                .description("Value for -keepattributes; leave blank to omit")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("resourcefilter").label("Resource filter")
                                .type(TransformerOptionType.STRING)
                                .defaultValue("**.properties,**.xml,**.yml,**.yaml,**.json,META-INF/MANIFEST.MF")
                                .description("Filter used by resource filename/content adaptation")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("extrarules").label("Extra ProGuard rules")
                                .type(TransformerOptionType.TEXT)
                                .defaultValue("")
                                .description("Raw ProGuard rules appended to the generated config, one per line")
                                .build()));

        // ---------------- Renaming ----------------
        addCategory(unusedHost, "Renaming", "These GUI options are not wired to the obfuscator runtime; use the ProGuard renaming option instead.");

        addSection(unusedHost, "classRenamer", "Class Renamer",
                "Renames classes using the chosen scheme. May break reflection.",
                false, "Unwired",
                Arrays.asList(
                        TransformerOptionDefinition.builder()
                                .key("type").label("Scheme")
                                .type(TransformerOptionType.ENUM)
                                .enumValues(Arrays.asList("ALPHABETICAL", "CUSTOM"))
                                .defaultValue("CUSTOM")
                                .description("Naming scheme")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("prefix").label("Package prefix")
                                .type(TransformerOptionType.STRING)
                                .defaultValue("skido/")
                                .description("Internal-name prefix for renamed classes")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("depth").label("Depth")
                                .type(TransformerOptionType.INTEGER)
                                .defaultValue(3)
                                .description("Generated name depth")
                                .build()));

        addSection(unusedHost, "methodRenamer", "Method Renamer",
                "Renames methods using the chosen scheme. May break reflection.",
                false, "Unwired",
                Arrays.asList(
                        TransformerOptionDefinition.builder()
                                .key("type").label("Scheme")
                                .type(TransformerOptionType.ENUM)
                                .enumValues(Arrays.asList("ALPHABETICAL", "CUSTOM"))
                                .defaultValue("CUSTOM")
                                .description("Naming scheme")
                                .build(),
                        TransformerOptionDefinition.builder()
                                .key("depth").label("Depth")
                                .type(TransformerOptionType.INTEGER)
                                .defaultValue(3)
                                .description("Generated name depth")
                                .build()));

        addSection(unusedHost, "fieldRenamer", "Field Renamer",
                "Renames fields using the chosen scheme. May break reflection.",
                false, "Unwired",
                Collections.singletonList(TransformerOptionDefinition.builder()
                        .key("type").label("Scheme")
                        .type(TransformerOptionType.ENUM)
                        .enumValues(Arrays.asList("ALPHABETICAL", "CUSTOM"))
                        .defaultValue("ALPHABETICAL")
                        .description("Naming scheme")
                        .build()));

        // ---------------- Misc / Advanced ----------------
        addCategory(host, "Advanced", "SDK and runtime-output toggles.");

        addSection(host, "sdk", "SDK Injector",
                "Injects the Skidfuscator runtime SDK into the output jar. Required by SDK-dependent passes.",
                true, null, Collections.emptyList());

        addSection(host, "methodCallObfuscation", "Method Call Obfuscation",
                "Rewrites eligible method calls into invokedynamic callsites with per-class bootstrap resolvers. Off by default.",
                false, "Risky", Collections.emptyList());

        addSection(host, "signatureObfuscation", "Signature Obfuscation",
                "Rewrites internally-called method signatures to byte[] plus Object[] carrier arguments. Off by default.",
                false, "Risky", Collections.emptyList());

        addSection(host, "constructorObfuscation", "Constructor Obfuscation",
                "Allows eligible transformers to process constructor bodies after super()/this() initialization. Off by default.",
                false, "Risky", Collections.emptyList());

        addCategory(unusedHost, "Advanced", "Experimental or enterprise config entries not consumed by this build.");

        addSection(unusedHost, "driver", "Driver",
                "Wraps the program with a launch driver. Off by default.",
                false, "Unwired",
                Collections.singletonList(TransformerOptionDefinition.builder()
                        .key("path").label("Driver path")
                        .type(TransformerOptionType.STRING)
                        .defaultValue("skid/Driver")
                        .description("Internal name of the generated driver class")
                        .build()));

        addSection(host, "ahegao", "Ahegao",
                "Cosmetic / fingerprinting transformer. Mostly harmless.",
                true, null, Collections.emptyList());

        addSection(host, "fileCrasher", "File Crasher",
                "Inserts malformed metadata that breaks naive decompilers. Off by default - some JVMs reject it.",
                false, "Risky", Collections.emptyList());

        addSection(unusedHost, "native", "Native",
                "Converts selected methods to native implementations.",
                false, "Unwired", Collections.emptyList());
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    private void addCategory(JPanel host, String label, String hint) {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, 4, UiTheme.PAD_S, 4));

        JLabel title = new JLabel(label.toUpperCase());
        title.setForeground(UiTheme.TEXT_MUTED);
        title.setFont(UiTheme.font(Font.BOLD, 11f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);

        if (hint != null && !hint.isEmpty()) {
            JLabel sub = new JLabel(hint);
            sub.setForeground(UiTheme.TEXT_MUTED);
            sub.setFont(UiTheme.font(Font.PLAIN, 11f));
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);
            sub.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
            header.add(sub);
        }
        host.add(header);
    }

    private void addSection(JPanel host, String id, String name, String description,
                            boolean defaultEnabled, String tag,
                            List<TransformerOptionDefinition> options) {
        TransformerCard card = new TransformerCard(id, name, description, defaultEnabled, tag, options, this::schedulePersist);
        sections.put(id, card);
        host.add(card);
        host.add(Box.createVerticalStrut(UiTheme.PAD_S));
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public void saveConfiguration() {
        try {
            writeConfiguration(defaultConfigFile, Collections.emptyList());
            JOptionPane.showMessageDialog(this, "Configuration saved.", "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Error saving configuration: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void loadConfiguration() {
        loadConfiguration(true);
    }

    public String renderConfiguration(List<String> globalExclusions) {
        return buildConfiguration(globalExclusions).renderConfig();
    }

    public boolean isProGuardClassRenamingEnabled() {
        TransformerCard card = sections.get("proGuard");
        return card != null && card.isToggled();
    }

    public Map<String, Object> getProGuardOptions() {
        TransformerCard card = sections.get("proGuard");
        return card == null ? Collections.emptyMap() : card.optionValues();
    }

    private SkidfuscatorConfig buildConfiguration(List<String> globalExclusions) {
        SkidfuscatorConfig config = new SkidfuscatorConfig();
        config.setGlobalExemptions(DEFAULT_GLOBAL_EXEMPTIONS);
        config.setGlobalExclusions(globalExclusions);

        for (TransformerCard card : sections.values()) {
            config.addTransformer(card.id, card.isToggled(), card.optionValues(), Collections.emptyList());
        }

        return config;
    }

    private void writeConfiguration(File file, List<String> globalExclusions) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(renderConfiguration(globalExclusions));
        }
    }

    private void schedulePersist() {
        if (loading) return;
        SwingUtilities.invokeLater(this::persistConfiguration);
    }

    private void persistConfiguration() {
        if (loading) return;
        try {
            writeConfiguration(defaultConfigFile, Collections.emptyList());
        } catch (IOException ignored) {
        }
    }

    private void loadConfiguration(boolean userInitiated) {
        if (!defaultConfigFile.exists()) {
            if (userInitiated) {
                JOptionPane.showMessageDialog(this,
                        "No configuration file found at " + defaultConfigFile.getAbsolutePath(),
                        "Warning", JOptionPane.WARNING_MESSAGE);
            }
            return;
        }

        loading = true;
        try {
            Config config = ConfigFactory.parseFile(defaultConfigFile);
            for (TransformerCard card : sections.values()) {
                if (config.hasPath(card.id)) {
                    Config tc = config.getConfig(card.id);
                    if (tc.hasPath("enabled")) {
                        card.setToggled(tc.getBoolean("enabled"));
                    }
                    for (Map.Entry<String, ConfigValue> entry : tc.entrySet()) {
                        if ("enabled".equals(entry.getKey())
                                || "exempt".equals(entry.getKey())
                                || "exclude".equals(entry.getKey())) continue;
                        card.setOption(entry.getKey(), entry.getValue().unwrapped());
                    }
                }
            }
            if (userInitiated) {
                JOptionPane.showMessageDialog(this, "Configuration loaded.", "Loaded", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            if (userInitiated) {
                JOptionPane.showMessageDialog(this, "Error loading configuration: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        } finally {
            loading = false;
        }
    }

    // ------------------------------------------------------------------
    // Card component
    // ------------------------------------------------------------------

    private static class TransformerCard extends Card {

        final String id;
        private final ToggleSwitch toggle;
        private final List<TransformerOptionDefinition> options;
        private final Map<String, JComponent> optionInputs = new HashMap<>();
        private final JPanel optionsPanel;
        private final JLabel chevron;
        private final Runnable onChange;
        private boolean expanded;

        TransformerCard(String id, String name, String description, boolean enabled, String tag,
                        List<TransformerOptionDefinition> options, Runnable onChange) {
            super(new BorderLayout(UiTheme.PAD_M, UiTheme.PAD_S));
            this.id = id;
            this.options = options;
            this.onChange = onChange == null ? () -> {} : onChange;
            setHoverable(true);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            // Header row
            JPanel header = new JPanel(new BorderLayout(UiTheme.PAD_M, 0));
            header.setOpaque(false);

            toggle = new ToggleSwitch(enabled);
            toggle.addChangeListener(v -> this.onChange.run());
            JPanel toggleWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            toggleWrap.setOpaque(false);
            toggleWrap.add(toggle);
            header.add(toggleWrap, BorderLayout.WEST);

            JPanel titleStack = new JPanel();
            titleStack.setOpaque(false);
            titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));

            JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTheme.PAD_S, 0));
            titleRow.setOpaque(false);
            titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel title = new JLabel(name);
            title.setFont(UiTheme.font(Font.BOLD, 13f));
            title.setForeground(UiTheme.TEXT_PRIMARY);
            titleRow.add(title);

            if (tag != null && !tag.isEmpty()) {
                StatusBadge.Kind kind;
                switch (tag.toLowerCase()) {
                    case "enterprise": kind = StatusBadge.Kind.INFO; break;
                    case "risky":      kind = StatusBadge.Kind.DANGER; break;
                    case "beta":       kind = StatusBadge.Kind.WARNING; break;
                    default:           kind = StatusBadge.Kind.NEUTRAL;
                }
                titleRow.add(new StatusBadge(kind, tag));
            }
            titleStack.add(titleRow);

            JLabel desc = new JLabel("<html><div style='width:520px'>" + description + "</div></html>");
            desc.setFont(UiTheme.font(Font.PLAIN, 11f));
            desc.setForeground(UiTheme.TEXT_SECONDARY);
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            titleStack.add(desc);
            header.add(titleStack, BorderLayout.CENTER);

            chevron = new JLabel(options.isEmpty() ? " " : "▾");
            chevron.setForeground(UiTheme.TEXT_MUTED);
            chevron.setFont(UiTheme.font(Font.PLAIN, 14f));
            chevron.setCursor(new Cursor(options.isEmpty() ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
            chevron.setBorder(BorderFactory.createEmptyBorder(0, UiTheme.PAD_M, 0, UiTheme.PAD_S));
            header.add(chevron, BorderLayout.EAST);
            if (!options.isEmpty()) {
                chevron.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) { toggleExpand(); }
                });
            }

            add(header, BorderLayout.NORTH);

            if (options.isEmpty()) {
                optionsPanel = null;
            } else {
                optionsPanel = new JPanel(new GridBagLayout());
                optionsPanel.setOpaque(false);
                optionsPanel.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, 60, 0, 0));
                buildOptions();
                optionsPanel.setVisible(false);
                add(optionsPanel, BorderLayout.CENTER);
            }

            installHoverTracking();
        }

        private void installHoverTracking() {
            MouseAdapter h = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { setHovered(true); }
                @Override public void mouseExited (MouseEvent e) { setHovered(false); }
            };
            addMouseListener(h);
        }

        private void buildOptions() {
            GridBagConstraints g = new GridBagConstraints();
            g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(4, 0, 4, 8);
            int row = 0;
            for (TransformerOptionDefinition option : options) {
                JLabel label = new JLabel(option.getLabel() + ":");
                label.setFont(UiTheme.font(Font.PLAIN, 12f));
                label.setForeground(UiTheme.TEXT_SECONDARY);

                JComponent input = inputFor(option);
                if (option.getDescription() != null) {
                    label.setToolTipText(option.getDescription());
                    input.setToolTipText(option.getDescription());
                }
                optionInputs.put(option.getKey(), input);
                installChangePersistence(input);

                g.gridx = 0; g.gridy = row; g.weightx = 0;
                optionsPanel.add(label, g);
                g.gridx = 1; g.weightx = 1;
                optionsPanel.add(input, g);
                row++;
            }
        }

        private void installChangePersistence(JComponent input) {
            if (input instanceof JComboBox) {
                ((JComboBox<?>) input).addActionListener(e -> onChange.run());
            } else if (input instanceof JSpinner) {
                ((JSpinner) input).addChangeListener(e -> onChange.run());
            } else if (input instanceof JCheckBox) {
                ((JCheckBox) input).addActionListener(e -> onChange.run());
            } else if (input instanceof JTextField) {
                ((JTextField) input).getDocument().addDocumentListener(new DocumentListener() {
                    @Override public void insertUpdate(DocumentEvent e) { onChange.run(); }
                    @Override public void removeUpdate(DocumentEvent e) { onChange.run(); }
                    @Override public void changedUpdate(DocumentEvent e) { onChange.run(); }
                });
            } else if (input instanceof JTextArea) {
                ((JTextArea) input).getDocument().addDocumentListener(new DocumentListener() {
                    @Override public void insertUpdate(DocumentEvent e) { onChange.run(); }
                    @Override public void removeUpdate(DocumentEvent e) { onChange.run(); }
                    @Override public void changedUpdate(DocumentEvent e) { onChange.run(); }
                });
            }
        }

        private JComponent inputFor(TransformerOptionDefinition option) {
            switch (option.getType()) {
                case ENUM: {
                    JComboBox<String> combo = new JComboBox<>(option.getEnumValues().toArray(new String[0]));
                    combo.setSelectedItem(option.getDefaultValue());
                    return combo;
                }
                case INTEGER: {
                    Object def = option.getDefaultValue();
                    double v = (def instanceof Number) ? ((Number) def).doubleValue() : 0d;
                    return new JSpinner(new SpinnerNumberModel(v, 0, 100, 1));
                }
                case BOOLEAN: {
                    JCheckBox cb = new JCheckBox();
                    cb.setSelected(Boolean.TRUE.equals(option.getDefaultValue()));
                    cb.setOpaque(false);
                    return cb;
                }
                case TEXT: {
                    JTextArea area = new JTextArea(String.valueOf(option.getDefaultValue()), 5, 28);
                    area.setLineWrap(true);
                    area.setWrapStyleWord(true);
                    area.setFont(UiTheme.font(Font.PLAIN, 12f));
                    area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                    area.setPreferredSize(new Dimension(420, 110));
                    return area;
                }
                case STRING:
                default: {
                    return new JTextField(String.valueOf(option.getDefaultValue()), 15);
                }
            }
        }

        private void toggleExpand() {
            if (optionsPanel == null) return;
            expanded = !expanded;
            optionsPanel.setVisible(expanded);
            chevron.setText(expanded ? "▴" : "▾");
            revalidate();
            repaint();
            if (getParent() != null) {
                getParent().revalidate();
                getParent().repaint();
            }
        }

        boolean isToggled() { return toggle.isSelected(); }
        void setToggled(boolean v) { toggle.setSelected(v); }

        Map<String, Object> optionValues() {
            Map<String, Object> values = new HashMap<>();
            for (TransformerOptionDefinition opt : options) {
                JComponent c = optionInputs.get(opt.getKey());
                if      (c instanceof JComboBox)  values.put(opt.getKey(), ((JComboBox<?>) c).getSelectedItem());
                else if (c instanceof JSpinner)   values.put(opt.getKey(), ((JSpinner) c).getValue());
                else if (c instanceof JCheckBox)  values.put(opt.getKey(), ((JCheckBox) c).isSelected());
                else if (c instanceof JTextArea)  values.put(opt.getKey(), ((JTextArea) c).getText());
                else if (c instanceof JTextField) values.put(opt.getKey(), ((JTextField) c).getText());
            }
            return values;
        }

        void setOption(String key, Object value) {
            JComponent c = optionInputs.get(key);
            if      (c instanceof JComboBox)  ((JComboBox<?>) c).setSelectedItem(value);
            else if (c instanceof JSpinner)   ((JSpinner) c).setValue(value);
            else if (c instanceof JCheckBox)  ((JCheckBox) c).setSelected(Boolean.parseBoolean(String.valueOf(value)));
            else if (c instanceof JTextArea)  ((JTextArea) c).setText(String.valueOf(value));
            else if (c instanceof JTextField) ((JTextField) c).setText(String.valueOf(value));
        }
    }
}
