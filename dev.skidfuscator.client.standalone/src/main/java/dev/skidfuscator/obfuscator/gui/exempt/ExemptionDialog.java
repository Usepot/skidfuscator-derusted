package dev.skidfuscator.obfuscator.gui.exempt;

import dev.skidfuscator.obfuscator.gui.ui.Card;
import dev.skidfuscator.obfuscator.gui.ui.PrimaryButton;
import dev.skidfuscator.obfuscator.gui.ui.SecondaryButton;
import dev.skidfuscator.obfuscator.gui.ui.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modal builder for an {@link ExemptionRule}. Form tab drives every field with
 * a live preview; Raw tab lets advanced users paste a pattern verbatim.
 */
public class ExemptionDialog extends JDialog {

    private static final String[] MODIFIERS = {
            "public", "private", "protected", "static", "final", "abstract", "native"
    };
    private static final String[] FIELD_MODIFIERS = {
            "public", "private", "protected", "static", "final", "transient", "volatile"
    };

    private final ExemptionRule working;
    private boolean accepted = false;

    // Form widgets
    private final JComboBox<ExemptionRule.ClassKind> kindCombo =
            new JComboBox<>(ExemptionRule.ClassKind.values());
    private final Map<String, JCheckBox> modifierBoxes = new LinkedHashMap<>();
    private final JTextField nameField = new JTextField();
    private final JTextField extendsField = new JTextField();
    private final JTextField implementsField = new JTextField();

    private final DefaultListModel<ExemptionMember> memberModel = new DefaultListModel<>();
    private final JList<ExemptionMember> memberList = new JList<>(memberModel);

    private final JTextArea rawArea = new JTextArea(10, 60);
    private final JTextArea preview = new JTextArea(10, 60);

    private final JTabbedPane tabs = new JTabbedPane();

    public ExemptionDialog(JFrame owner, ExemptionRule rule) {
        super(owner, "Exemption rule", true);
        this.working = copyOf(rule);

        setLayout(new BorderLayout(UiTheme.PAD_M, UiTheme.PAD_M));
        ((JComponent) getContentPane()).setBorder(
                BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L));
        getContentPane().setBackground(UiTheme.CONTENT_BG);

        tabs.addTab("Form",  buildFormTab());
        tabs.addTab("Raw",   buildRawTab());
        tabs.addTab("Preview", buildPreviewTab());
        add(tabs, BorderLayout.CENTER);
        add(buildButtonBar(), BorderLayout.SOUTH);

        loadFromRule();
        installLiveUpdate();
        refreshPreview();

        pack();
        setMinimumSize(new Dimension(720, 560));
        setLocationRelativeTo(owner);
    }

    private static ExemptionRule copyOf(ExemptionRule src) {
        ExemptionRule copy = new ExemptionRule();
        if (src == null) return copy;
        copy.setRawMode(src.isRawMode());
        copy.setRaw(src.getRaw());
        copy.setClassKind(src.getClassKind());
        copy.getModifiers().addAll(src.getModifiers());
        copy.setName(src.getName());
        copy.setExtendsClass(src.getExtendsClass());
        copy.getImplementsList().addAll(src.getImplementsList());
        for (ExemptionMember m : src.getMembers()) {
            copy.getMembers().add(new ExemptionMember(
                    m.getKind(),
                    new ArrayList<>(m.getModifiers()),
                    m.getReturnType(),
                    m.getName(),
                    m.getParameters()));
        }
        return copy;
    }

    public boolean isAccepted() { return accepted; }
    public ExemptionRule getResult() { return working; }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private JComponent buildFormTab() {
        Card form = new Card(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L));

        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(6, 6, 6, 6);
        g.weightx = 1;
        int row = 0;

        g.gridx = 0; g.gridy = row; g.weightx = 0;
        form.add(label("Kind"), g);
        g.gridx = 1; g.weightx = 1;
        form.add(kindCombo, g);
        row++;

        JPanel modifiers = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        modifiers.setOpaque(false);
        for (String m : MODIFIERS) {
            JCheckBox cb = new JCheckBox(m);
            cb.setOpaque(false);
            cb.setForeground(UiTheme.TEXT_PRIMARY);
            modifierBoxes.put(m, cb);
            modifiers.add(cb);
        }
        g.gridx = 0; g.gridy = row; g.weightx = 0;
        form.add(label("Modifiers"), g);
        g.gridx = 1; g.weightx = 1;
        form.add(modifiers, g);
        row++;

        addField(form, g, row++, "Name",       nameField,       "e.g. com.example.* or com.example.MyClass");
        addField(form, g, row++, "Extends",    extendsField,    "Optional. Single super-class.");
        addField(form, g, row++, "Implements", implementsField, "Optional. Comma-separated interfaces.");

        g.gridx = 0; g.gridy = row; g.weightx = 0; g.gridwidth = 1;
        form.add(label("Members"), g);
        g.gridx = 1; g.weightx = 1;
        form.add(buildMembersPane(), g);
        row++;

        return wrap(form);
    }

    private JComponent buildMembersPane() {
        JPanel host = new JPanel(new BorderLayout(UiTheme.PAD_S, UiTheme.PAD_S));
        host.setOpaque(false);

        memberList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        memberList.setVisibleRowCount(6);
        memberList.setBackground(UiTheme.CARD_BG);
        memberList.setForeground(UiTheme.TEXT_PRIMARY);
        memberList.setFont(UiTheme.mono(Font.PLAIN, 12f));
        memberList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel l = new JLabel(value == null ? "" : value.summary());
            l.setOpaque(true);
            l.setBackground(isSelected ? UiTheme.ACCENT.darker() : UiTheme.CARD_BG);
            l.setForeground(isSelected ? Color.WHITE : UiTheme.TEXT_PRIMARY);
            l.setFont(UiTheme.mono(Font.PLAIN, 12f));
            l.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            return l;
        });

        JScrollPane scroll = new JScrollPane(memberList);
        scroll.setBorder(BorderFactory.createLineBorder(UiTheme.SUBTLE_BORDER, 1, true));
        scroll.setPreferredSize(new Dimension(0, 140));
        host.add(scroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTheme.PAD_S, 0));
        actions.setOpaque(false);
        JButton addMethod = new SecondaryButton("+ Method");
        JButton addField  = new SecondaryButton("+ Field");
        JButton edit      = new SecondaryButton("Edit");
        JButton remove    = new SecondaryButton("Remove");
        addMethod.addActionListener(e -> openMemberDialog(new ExemptionMember(ExemptionMember.Kind.METHOD, null, "", "", "")));
        addField .addActionListener(e -> openMemberDialog(new ExemptionMember(ExemptionMember.Kind.FIELD,  null, "", "", "")));
        edit     .addActionListener(e -> {
            int i = memberList.getSelectedIndex();
            if (i >= 0) openMemberDialog(memberModel.get(i));
        });
        remove   .addActionListener(e -> {
            int i = memberList.getSelectedIndex();
            if (i >= 0) { memberModel.remove(i); refreshPreview(); }
        });
        actions.add(addMethod);
        actions.add(addField);
        actions.add(edit);
        actions.add(remove);
        host.add(actions, BorderLayout.SOUTH);
        return host;
    }

    private void openMemberDialog(ExemptionMember seed) {
        boolean isNew = !memberModel.contains(seed);
        ExemptionMember edited = MemberEditor.show(this, seed);
        if (edited == null) return;
        if (isNew) memberModel.addElement(edited);
        else memberList.repaint();
        refreshPreview();
    }

    private JComponent buildRawTab() {
        Card card = new Card(new BorderLayout(UiTheme.PAD_S, UiTheme.PAD_S));
        card.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L));
        JLabel hint = new JLabel("<html>Paste a raw exclusion pattern. The Form tab is ignored when this tab is active.</html>");
        hint.setForeground(UiTheme.TEXT_SECONDARY);
        card.add(hint, BorderLayout.NORTH);

        rawArea.setFont(UiTheme.mono(Font.PLAIN, 12f));
        rawArea.setBackground(UiTheme.CARD_BG);
        rawArea.setForeground(UiTheme.TEXT_PRIMARY);
        rawArea.setCaretColor(UiTheme.TEXT_PRIMARY);
        rawArea.setTabSize(4);
        JScrollPane scroll = new JScrollPane(rawArea);
        scroll.setBorder(BorderFactory.createLineBorder(UiTheme.SUBTLE_BORDER, 1, true));
        card.add(scroll, BorderLayout.CENTER);
        return wrap(card);
    }

    private JComponent buildPreviewTab() {
        Card card = new Card(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L));
        preview.setEditable(false);
        preview.setFont(UiTheme.mono(Font.PLAIN, 12f));
        preview.setBackground(UiTheme.CONSOLE_BG);
        preview.setForeground(UiTheme.CONSOLE_FG);
        preview.setTabSize(4);
        JScrollPane scroll = new JScrollPane(preview);
        scroll.setBorder(BorderFactory.createLineBorder(UiTheme.SUBTLE_BORDER, 1, true));
        card.add(scroll, BorderLayout.CENTER);
        return wrap(card);
    }

    private JComponent buildButtonBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTheme.PAD_S, 0));
        bar.setOpaque(false);
        JButton cancel = new SecondaryButton("Cancel");
        JButton ok     = new PrimaryButton("Save rule");
        cancel.addActionListener(e -> { accepted = false; dispose(); });
        ok.addActionListener(e -> {
            commitFromUi();
            accepted = true;
            dispose();
        });
        bar.add(cancel);
        bar.add(ok);
        return bar;
    }

    // ------------------------------------------------------------------
    // State <-> widgets
    // ------------------------------------------------------------------

    private void loadFromRule() {
        kindCombo.setSelectedItem(working.getClassKind());
        modifierBoxes.values().forEach(cb -> cb.setSelected(false));
        for (String m : working.getModifiers()) {
            JCheckBox cb = modifierBoxes.get(m);
            if (cb != null) cb.setSelected(true);
        }
        nameField.setText(working.getName());
        extendsField.setText(working.getExtendsClass());
        implementsField.setText(String.join(", ", working.getImplementsList()));
        memberModel.clear();
        working.getMembers().forEach(memberModel::addElement);

        rawArea.setText(working.getRaw());
        tabs.setSelectedIndex(working.isRawMode() ? 1 : 0);
    }

    private void installLiveUpdate() {
        kindCombo.addActionListener(e -> refreshPreview());
        modifierBoxes.values().forEach(cb -> cb.addActionListener(e -> refreshPreview()));
        DocumentListener dl = simple(this::refreshPreview);
        nameField.getDocument().addDocumentListener(dl);
        extendsField.getDocument().addDocumentListener(dl);
        implementsField.getDocument().addDocumentListener(dl);
        rawArea.getDocument().addDocumentListener(dl);
        tabs.addChangeListener(e -> refreshPreview());
    }

    private void commitFromUi() {
        working.setRawMode(tabs.getSelectedIndex() == 1);
        working.setRaw(rawArea.getText());

        working.setClassKind((ExemptionRule.ClassKind) kindCombo.getSelectedItem());
        working.getModifiers().clear();
        for (Map.Entry<String, JCheckBox> e : modifierBoxes.entrySet()) {
            if (e.getValue().isSelected()) working.getModifiers().add(e.getKey());
        }
        working.setName(nameField.getText());
        working.setExtendsClass(extendsField.getText());
        working.getImplementsList().clear();
        for (String s : implementsField.getText().split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) working.getImplementsList().add(t);
        }
        working.getMembers().clear();
        for (int i = 0; i < memberModel.size(); i++) working.getMembers().add(memberModel.get(i));
    }

    private void refreshPreview() {
        commitFromUi();
        preview.setText(working.render());
        preview.setCaretPosition(0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JLabel label(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(UiTheme.TEXT_SECONDARY);
        l.setFont(UiTheme.font(Font.BOLD, 12f));
        return l;
    }

    private static JComponent wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private void addField(JPanel form, GridBagConstraints g, int row, String name, JTextField f, String placeholder) {
        f.putClientProperty("JTextField.placeholderText", placeholder);
        g.gridx = 0; g.gridy = row; g.weightx = 0;
        form.add(label(name), g);
        g.gridx = 1; g.weightx = 1;
        form.add(f, g);
    }

    private static DocumentListener simple(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { SwingUtilities.invokeLater(r); }
            @Override public void removeUpdate(DocumentEvent e) { SwingUtilities.invokeLater(r); }
            @Override public void changedUpdate(DocumentEvent e) { SwingUtilities.invokeLater(r); }
        };
    }

    // ------------------------------------------------------------------
    // Member editor (small inline modal)
    // ------------------------------------------------------------------

    private static final class MemberEditor {
        static ExemptionMember show(JDialog parent, ExemptionMember seed) {
            JDialog dlg = new JDialog(parent, seed.getKind() == ExemptionMember.Kind.FIELD ? "Field" : "Method", true);
            dlg.setLayout(new BorderLayout(UiTheme.PAD_M, UiTheme.PAD_M));
            ((JComponent) dlg.getContentPane()).setBorder(
                    BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L));
            dlg.getContentPane().setBackground(UiTheme.CONTENT_BG);

            JComboBox<ExemptionMember.Kind> kind = new JComboBox<>(ExemptionMember.Kind.values());
            kind.setSelectedItem(seed.getKind());

            JPanel modPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            modPanel.setOpaque(false);
            Map<String, JCheckBox> mods = new LinkedHashMap<>();
            Runnable rebuildMods = () -> {
                modPanel.removeAll();
                mods.clear();
                String[] src = kind.getSelectedItem() == ExemptionMember.Kind.FIELD ? FIELD_MODIFIERS : MODIFIERS;
                for (String m : src) {
                    JCheckBox cb = new JCheckBox(m);
                    cb.setOpaque(false);
                    cb.setForeground(UiTheme.TEXT_PRIMARY);
                    cb.setSelected(seed.getModifiers().contains(m));
                    mods.put(m, cb);
                    modPanel.add(cb);
                }
                modPanel.revalidate();
                modPanel.repaint();
            };
            rebuildMods.run();
            kind.addActionListener(e -> rebuildMods.run());

            JTextField type = new JTextField(seed.getReturnType());
            JTextField name = new JTextField(seed.getName());
            JTextField params = new JTextField(seed.getParameters());

            type.putClientProperty("JTextField.placeholderText", "e.g. void or List<String> (no # prefix needed)");
            name.putClientProperty("JTextField.placeholderText", "e.g. run or get* or COUNT");
            params.putClientProperty("JTextField.placeholderText", "e.g. String, int (comma-separated, no parens)");

            Card form = new Card(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L, UiTheme.PAD_L));
            GridBagConstraints g = new GridBagConstraints();
            g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(6, 6, 6, 6);
            int row = 0;
            for (Object[] r : Arrays.asList(
                    new Object[]{"Kind", kind},
                    new Object[]{"Modifiers", modPanel},
                    new Object[]{"Type", type},
                    new Object[]{"Name", name},
                    new Object[]{"Parameters", params})) {
                if ("Parameters".equals(r[0]) && kind.getSelectedItem() == ExemptionMember.Kind.FIELD) continue;
                g.gridx = 0; g.gridy = row; g.weightx = 0;
                form.add(label((String) r[0]), g);
                g.gridx = 1; g.weightx = 1;
                form.add((JComponent) r[1], g);
                row++;
            }
            dlg.add(form, BorderLayout.CENTER);

            final ExemptionMember[] result = { null };
            JButton ok = new PrimaryButton("Save");
            JButton cancel = new SecondaryButton("Cancel");
            ActionListener save = e -> {
                ExemptionMember m = new ExemptionMember();
                m.setKind((ExemptionMember.Kind) kind.getSelectedItem());
                m.getModifiers().clear();
                for (Map.Entry<String, JCheckBox> e2 : mods.entrySet()) {
                    if (e2.getValue().isSelected()) m.getModifiers().add(e2.getKey());
                }
                m.setReturnType(type.getText());
                m.setName(name.getText());
                m.setParameters(params.getText());
                // Mutate the seed in place so the calling list sees the edits.
                seed.setKind(m.getKind());
                seed.getModifiers().clear();
                seed.getModifiers().addAll(m.getModifiers());
                seed.setReturnType(m.getReturnType());
                seed.setName(m.getName());
                seed.setParameters(m.getParameters());
                result[0] = seed;
                dlg.dispose();
            };
            ok.addActionListener(save);
            cancel.addActionListener(e -> dlg.dispose());

            JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTheme.PAD_S, 0));
            bar.setOpaque(false);
            bar.add(cancel); bar.add(ok);
            dlg.add(bar, BorderLayout.SOUTH);

            dlg.pack();
            dlg.setMinimumSize(new Dimension(520, 360));
            dlg.setLocationRelativeTo(parent);
            dlg.setVisible(true);
            return result[0];
        }
    }
}
