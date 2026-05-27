package dev.skidfuscator.obfuscator.gui;

import dev.skidfuscator.obfuscator.gui.config.SkidfuscatorConfig;
import dev.skidfuscator.obfuscator.gui.exempt.CheckBoxTreeRenderer;
import dev.skidfuscator.obfuscator.gui.exempt.ExemptionDialog;
import dev.skidfuscator.obfuscator.gui.exempt.ExemptionRule;
import dev.skidfuscator.obfuscator.gui.exempt.JarClassNode;
import dev.skidfuscator.obfuscator.gui.exempt.JarTreeLoader;
import dev.skidfuscator.obfuscator.gui.ui.PrimaryButton;
import dev.skidfuscator.obfuscator.gui.ui.SecondaryButton;
import dev.skidfuscator.obfuscator.gui.ui.SectionHeader;
import dev.skidfuscator.obfuscator.gui.ui.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Primary exemption UI: shows the input jar as a checkbox tree. Ticking a
 * package marks every class beneath it; ticking a leaf marks just that class.
 * Apply commits the selection as {@code @class …} rules into the GUI config.
 */
public class ExemptionPanel extends JPanel implements SkidPanel {

    private final SkidfuscatorConfig config;
    private final ConfigPanel configPanel;

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("(no jar loaded)")));
    private final JTextField sourceField = new JTextField();
    private final JTextField filterField = new JTextField();
    private final JLabel statusLabel = new JLabel();

    // raw rules the user typed via the advanced dialog. Kept separate from the
    // tree selection so toggling a class in the tree never wipes a hand-written
    // rule.
    private final List<String> advancedRules = new ArrayList<>();

    private File loadedJar;
    private DefaultTreeModel model;

    public ExemptionPanel(SkidfuscatorConfig config, ConfigPanel configPanel) {
        this.config = config;
        this.configPanel = configPanel;

        setLayout(new BorderLayout());
        setOpaque(false);

        add(new SectionHeader("Exemptions",
                        "Tick classes or packages to skip during obfuscation."),
                BorderLayout.NORTH);

        add(buildBody(), BorderLayout.CENTER);

        restoreExisting();
        tryAutoLoad();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout(UiTheme.PAD_M, UiTheme.PAD_M));
        body.setOpaque(false);

        body.add(buildSourceBar(), BorderLayout.NORTH);
        body.add(buildTreePane(),   BorderLayout.CENTER);
        body.add(buildFooter(),     BorderLayout.SOUTH);
        return body;
    }

    private JPanel buildSourceBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(0, 0, 4, UiTheme.PAD_S);
        g.fill = GridBagConstraints.HORIZONTAL;

        JLabel src = new JLabel("Source jar");
        src.setForeground(UiTheme.TEXT_SECONDARY);
        src.setFont(UiTheme.font(Font.BOLD, 12f));
        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        bar.add(src, g);

        sourceField.setEditable(false);
        sourceField.putClientProperty("JTextField.placeholderText", "Set Input JAR in the Configuration tab.");
        g.gridx = 1; g.weightx = 1;
        bar.add(sourceField, g);

        JButton browse = new SecondaryButton("Browse…");
        browse.addActionListener(e -> pickJar());
        g.gridx = 2; g.weightx = 0;
        bar.add(browse, g);

        JButton reload = new SecondaryButton("Reload");
        reload.addActionListener(e -> tryAutoLoad());
        g.gridx = 3;
        bar.add(reload, g);

        // Filter row
        JLabel f = new JLabel("Filter");
        f.setForeground(UiTheme.TEXT_SECONDARY);
        f.setFont(UiTheme.font(Font.BOLD, 12f));
        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        bar.add(f, g);

        filterField.putClientProperty("JTextField.placeholderText", "Filter packages / classes…");
        filterField.getDocument().addDocumentListener(simple(this::applyFilter));
        g.gridx = 1; g.weightx = 1;
        bar.add(filterField, g);

        JButton expand = new SecondaryButton("Expand all");
        expand.addActionListener(e -> setAllExpanded(true));
        g.gridx = 2;
        bar.add(expand, g);

        JButton collapse = new SecondaryButton("Collapse all");
        collapse.addActionListener(e -> setAllExpanded(false));
        g.gridx = 3;
        bar.add(collapse, g);

        return bar;
    }

    private JScrollPane buildTreePane() {
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        tree.setBackground(UiTheme.CARD_BG);
        tree.setForeground(UiTheme.TEXT_PRIMARY);
        tree.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_S, UiTheme.PAD_S, UiTheme.PAD_S, UiTheme.PAD_S));
        tree.setCellRenderer(new CheckBoxTreeRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                Rectangle bounds = tree.getPathBounds(path);
                if (bounds == null) return;
                // Treat the leading ~22px (icon column) as the toggle hit-zone.
                if (e.getX() - bounds.x < 28) {
                    toggle((DefaultMutableTreeNode) path.getLastPathComponent());
                }
            }
        });
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createLineBorder(UiTheme.SUBTLE_BORDER, 1, true));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(UiTheme.PAD_M, UiTheme.PAD_M));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, 0, 0, 0));

        statusLabel.setForeground(UiTheme.TEXT_MUTED);
        statusLabel.setFont(UiTheme.font(Font.PLAIN, 12f));
        footer.add(statusLabel, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTheme.PAD_S, 0));
        right.setOpaque(false);

        JButton clear = new SecondaryButton("Clear selection");
        clear.addActionListener(e -> clearSelection());
        right.add(clear);

        JButton advanced = new SecondaryButton("Advanced rules…");
        advanced.addActionListener(e -> openAdvanced());
        right.add(advanced);

        JButton apply = new PrimaryButton("Apply");
        apply.addActionListener(e -> applyAndPersist(true));
        right.add(apply);

        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    @Override
    public void open() { tryAutoLoad(); }

    private void tryAutoLoad() {
        String input = configPanel == null ? null : configPanel.getInputPath();
        File candidate = input == null || input.isBlank() ? null : new File(input);
        if (candidate == null || !candidate.isFile()) {
            statusLabel.setText("No input jar yet — set one in Configuration or click Browse.");
            return;
        }
        if (candidate.equals(loadedJar)) return;
        loadJar(candidate);
    }

    private void pickJar() {
        Frame parent = (Frame) SwingUtilities.getWindowAncestor(this);
        FileDialog dlg = new FileDialog(parent, "Choose jar", FileDialog.LOAD);
        dlg.setFile("*.jar;*.apk;*.dex");
        dlg.setVisible(true);
        if (dlg.getFile() == null) return;
        loadJar(new File(dlg.getDirectory(), dlg.getFile()));
    }

    private void loadJar(File jar) {
        sourceField.setText(jar.getAbsolutePath());
        statusLabel.setText("Loading " + jar.getName() + "…");
        new SwingWorker<DefaultTreeModel, Void>() {
            @Override protected DefaultTreeModel doInBackground() throws IOException {
                return JarTreeLoader.loadJar(jar);
            }
            @Override protected void done() {
                try {
                    model = get();
                    loadedJar = jar;
                    tree.setModel(model);
                    restoreSelectionFromConfig();
                    recomputeStatesFromRoot();
                    refreshStatus();
                    setAllExpanded(false);
                    tree.expandRow(0);
                } catch (Exception e) {
                    statusLabel.setText("Failed to load jar: " + e.getMessage());
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    // Selection state
    // ------------------------------------------------------------------

    private void toggle(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof JarClassNode)) return;
        JarClassNode data = (JarClassNode) node.getUserObject();
        JarClassNode.State next = (data.getState() == JarClassNode.State.CHECKED)
                ? JarClassNode.State.UNCHECKED
                : JarClassNode.State.CHECKED;
        setSubtree(node, next);
        recomputeParents(node);
        tree.repaint();
        refreshStatus();
        applyAndPersist(false);
    }

    @SuppressWarnings("unchecked")
    private void setSubtree(DefaultMutableTreeNode node, JarClassNode.State state) {
        Object u = node.getUserObject();
        if (u instanceof JarClassNode) ((JarClassNode) u).setState(state);
        for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
            setSubtree((DefaultMutableTreeNode) e.nextElement(), state);
        }
    }

    @SuppressWarnings("unchecked")
    private void recomputeParents(DefaultMutableTreeNode node) {
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
        while (parent != null) {
            JarClassNode pdata = parent.getUserObject() instanceof JarClassNode
                    ? (JarClassNode) parent.getUserObject() : null;
            if (pdata == null) break;
            int checked = 0, total = 0;
            boolean anyPartial = false;
            for (Enumeration<?> e = parent.children(); e.hasMoreElements(); ) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) e.nextElement();
                JarClassNode cd = (JarClassNode) child.getUserObject();
                total++;
                if (cd.getState() == JarClassNode.State.CHECKED) checked++;
                else if (cd.getState() == JarClassNode.State.PARTIAL) anyPartial = true;
            }
            if (checked == total && !anyPartial) pdata.setState(JarClassNode.State.CHECKED);
            else if (checked == 0 && !anyPartial) pdata.setState(JarClassNode.State.UNCHECKED);
            else pdata.setState(JarClassNode.State.PARTIAL);
            parent = (DefaultMutableTreeNode) parent.getParent();
        }
    }

    private void recomputeStatesFromRoot() {
        if (model == null) return;
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        rebuildBottomUp(root);
    }

    @SuppressWarnings("unchecked")
    private void rebuildBottomUp(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof JarClassNode)) return;
        if (node.getChildCount() == 0) return;
        int checked = 0, total = 0;
        boolean anyPartial = false;
        for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) e.nextElement();
            rebuildBottomUp(child);
            JarClassNode cd = (JarClassNode) child.getUserObject();
            total++;
            if (cd.getState() == JarClassNode.State.CHECKED) checked++;
            else if (cd.getState() == JarClassNode.State.PARTIAL) anyPartial = true;
        }
        JarClassNode data = (JarClassNode) node.getUserObject();
        if (checked == total && total > 0 && !anyPartial) data.setState(JarClassNode.State.CHECKED);
        else if (checked == 0 && !anyPartial) data.setState(JarClassNode.State.UNCHECKED);
        else data.setState(JarClassNode.State.PARTIAL);
    }

    private void clearSelection() {
        if (model == null) return;
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        setSubtree(root, JarClassNode.State.UNCHECKED);
        tree.repaint();
        refreshStatus();
        applyAndPersist(false);
    }

    // ------------------------------------------------------------------
    // Rendering selection → @class rules
    // ------------------------------------------------------------------

    private List<String> renderSelection() {
        if (model == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        collect(root, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void collect(DefaultMutableTreeNode node, List<String> out) {
        if (!(node.getUserObject() instanceof JarClassNode)) {
            for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
                collect((DefaultMutableTreeNode) e.nextElement(), out);
            }
            return;
        }
        JarClassNode data = (JarClassNode) node.getUserObject();
        if (data.getState() == JarClassNode.State.CHECKED) {
            if (data.isClassLeaf()) {
                out.add("@class " + data.getPath().replace('/', '.'));
            } else if (!data.getPath().isEmpty()) {
                out.add("@class " + data.getPath().replace('/', '.') + ".**");
            } else {
                // root fully selected — emit everything as a single wildcard
                out.add("@class **");
            }
            return; // do not descend; the wildcard covers descendants
        }
        if (data.getState() == JarClassNode.State.PARTIAL) {
            for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
                collect((DefaultMutableTreeNode) e.nextElement(), out);
            }
        }
    }

    private void refreshStatus() {
        int leaves = countCheckedLeaves(model == null ? null : (DefaultMutableTreeNode) model.getRoot());
        statusLabel.setText(leaves == 0
                ? (loadedJar == null ? "No jar loaded." : "0 classes selected — nothing will be exempted.")
                : leaves + " class" + (leaves == 1 ? "" : "es") + " selected.");
    }

    @SuppressWarnings("unchecked")
    private int countCheckedLeaves(DefaultMutableTreeNode node) {
        if (node == null) return 0;
        int n = 0;
        if (node.getUserObject() instanceof JarClassNode) {
            JarClassNode d = (JarClassNode) node.getUserObject();
            if (d.isClassLeaf() && d.getState() == JarClassNode.State.CHECKED) n++;
        }
        for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
            n += countCheckedLeaves((DefaultMutableTreeNode) e.nextElement());
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Filter (visual only — collapses non-matching subtrees)
    // ------------------------------------------------------------------

    private void applyFilter() {
        String needle = filterField.getText().trim().toLowerCase();
        if (model == null) return;
        if (needle.isEmpty()) {
            setAllExpanded(false);
            tree.expandRow(0);
            return;
        }
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        expandMatching(new TreePath(root), needle);
    }

    @SuppressWarnings("unchecked")
    private boolean expandMatching(TreePath path, String needle) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        boolean anyChildMatches = false;
        for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) e.nextElement();
            if (expandMatching(path.pathByAddingChild(child), needle)) anyChildMatches = true;
        }
        boolean selfMatches = node.getUserObject() instanceof JarClassNode
                && ((JarClassNode) node.getUserObject()).getLabel().toLowerCase().contains(needle);
        if (anyChildMatches || selfMatches) {
            tree.expandPath(path);
            return true;
        }
        tree.collapsePath(path);
        return false;
    }

    private void setAllExpanded(boolean expand) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            if (expand) tree.expandRow(i);
            else tree.collapseRow(i);
        }
    }

    // ------------------------------------------------------------------
    // Advanced (raw) dialog passthrough
    // ------------------------------------------------------------------

    private void openAdvanced() {
        AdvancedRulesPanel.show(this, advancedRules, () -> applyAndPersist(true));
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void restoreExisting() {
        advancedRules.clear();
        List<String> existing = config.getExemptions();
        if (existing != null) advancedRules.addAll(existing);
    }

    /**
     * Try to re-tick the tree by parsing the saved {@code @class …} rules.
     * Anything we can't map to a node stays in {@link #advancedRules}.
     */
    private void restoreSelectionFromConfig() {
        if (model == null) return;
        Set<String> remaining = new LinkedHashSet<>(advancedRules);
        Set<String> matched = new HashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        for (String rule : new ArrayList<>(remaining)) {
            String body = rule.trim();
            if (!body.startsWith("@class ") || body.contains("\n") || body.contains("{")) continue;
            String target = body.substring("@class ".length()).trim();
            boolean wildcard = target.endsWith(".**");
            if (wildcard) target = target.substring(0, target.length() - ".**".length());
            String path = target.replace('.', '/');
            DefaultMutableTreeNode found = findByPath(root, path, !wildcard);
            if (found != null) {
                setSubtree(found, JarClassNode.State.CHECKED);
                recomputeParents(found);
                matched.add(rule);
            }
        }
        remaining.removeAll(matched);
        advancedRules.clear();
        advancedRules.addAll(remaining);
        tree.repaint();
    }

    @SuppressWarnings("unchecked")
    private DefaultMutableTreeNode findByPath(DefaultMutableTreeNode node, String path, boolean wantLeaf) {
        if (node.getUserObject() instanceof JarClassNode) {
            JarClassNode d = (JarClassNode) node.getUserObject();
            if (d.getPath().equals(path) && d.isClassLeaf() == wantLeaf) return node;
        }
        for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
            DefaultMutableTreeNode m = findByPath((DefaultMutableTreeNode) e.nextElement(), path, wantLeaf);
            if (m != null) return m;
        }
        return null;
    }

    private void applyAndPersist(boolean userInitiated) {
        List<String> combined = new ArrayList<>();
        combined.addAll(renderSelection());
        combined.addAll(advancedRules);
        config.setExemptions(combined);
        SwingUtilities.invokeLater(config::save);
        if (userInitiated) {
            JOptionPane.showMessageDialog(this,
                    combined.size() + " rule" + (combined.size() == 1 ? "" : "s") + " saved.",
                    "Exemptions applied", JOptionPane.INFORMATION_MESSAGE);
        }
        refreshStatus();
    }

    /** Used by MainFrame at obfuscation launch time. */
    public List<String> getRenderedExemptions() {
        List<String> combined = new ArrayList<>();
        combined.addAll(renderSelection());
        combined.addAll(advancedRules);
        return combined;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static DocumentListener simple(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { SwingUtilities.invokeLater(r); }
            @Override public void removeUpdate(DocumentEvent e) { SwingUtilities.invokeLater(r); }
            @Override public void changedUpdate(DocumentEvent e) { SwingUtilities.invokeLater(r); }
        };
    }

    // ------------------------------------------------------------------
    // Compact "Advanced rules" management dialog
    // ------------------------------------------------------------------

    private static final class AdvancedRulesPanel {
        static void show(Component owner, List<String> rules, Runnable onChange) {
            JFrame parent = (JFrame) SwingUtilities.getWindowAncestor(owner);
            javax.swing.JDialog dlg = new javax.swing.JDialog(parent, "Advanced exemption rules", true);
            dlg.setLayout(new BorderLayout(UiTheme.PAD_M, UiTheme.PAD_M));
            ((javax.swing.JComponent) dlg.getContentPane()).setBorder(
                    BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L));
            dlg.getContentPane().setBackground(UiTheme.CONTENT_BG);

            javax.swing.DefaultListModel<String> model = new javax.swing.DefaultListModel<>();
            rules.forEach(model::addElement);
            javax.swing.JList<String> list = new javax.swing.JList<>(model);
            list.setVisibleRowCount(10);
            list.setFont(UiTheme.mono(Font.PLAIN, 12f));
            list.setBackground(UiTheme.CARD_BG);
            list.setForeground(UiTheme.TEXT_PRIMARY);
            list.setCellRenderer((l, value, idx, sel, focus) -> {
                JLabel j = new JLabel(value.replace("\n", " ⏎ "));
                j.setOpaque(true);
                j.setBackground(sel ? UiTheme.ACCENT.darker() : UiTheme.CARD_BG);
                j.setForeground(sel ? java.awt.Color.WHITE : UiTheme.TEXT_PRIMARY);
                j.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                return j;
            });
            dlg.add(new JScrollPane(list), BorderLayout.CENTER);

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTheme.PAD_S, 0));
            left.setOpaque(false);
            JButton add  = new SecondaryButton("Add");
            JButton edit = new SecondaryButton("Edit");
            JButton del  = new SecondaryButton("Remove");
            add.addActionListener(e -> {
                ExemptionRule r = new ExemptionRule();
                ExemptionDialog d = new ExemptionDialog(parent, r);
                d.setVisible(true);
                if (d.isAccepted()) {
                    model.addElement(d.getResult().render());
                    rules.clear();
                    java.util.Collections.addAll(rules, modelArray(model));
                    onChange.run();
                }
            });
            edit.addActionListener(e -> {
                int i = list.getSelectedIndex();
                if (i < 0) return;
                ExemptionRule seed = new ExemptionRule();
                seed.setRawMode(true);
                seed.setRaw(model.get(i));
                ExemptionDialog d = new ExemptionDialog(parent, seed);
                d.setVisible(true);
                if (d.isAccepted()) {
                    model.set(i, d.getResult().render());
                    rules.clear();
                    java.util.Collections.addAll(rules, modelArray(model));
                    onChange.run();
                }
            });
            del.addActionListener(e -> {
                int i = list.getSelectedIndex();
                if (i < 0) return;
                model.remove(i);
                rules.clear();
                java.util.Collections.addAll(rules, modelArray(model));
                onChange.run();
            });
            left.add(add); left.add(edit); left.add(del);
            dlg.add(left, BorderLayout.NORTH);

            JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTheme.PAD_S, 0));
            bar.setOpaque(false);
            JButton close = new PrimaryButton("Done");
            close.addActionListener(e -> dlg.dispose());
            bar.add(close);
            dlg.add(bar, BorderLayout.SOUTH);

            dlg.pack();
            dlg.setMinimumSize(new java.awt.Dimension(640, 420));
            dlg.setLocationRelativeTo(parent);
            dlg.setVisible(true);
        }

        private static String[] modelArray(javax.swing.DefaultListModel<String> m) {
            String[] out = new String[m.size()];
            for (int i = 0; i < out.length; i++) out[i] = m.get(i);
            return out;
        }
    }
}
