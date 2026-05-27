package dev.skidfuscator.obfuscator.gui.exempt;

import dev.skidfuscator.obfuscator.gui.ui.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Renders a tristate checkbox + an icon + the node label for the exemption
 * tree. Partially-selected packages get a filled square; fully-selected get a
 * check; unchecked get an empty box.
 */
public class CheckBoxTreeRenderer extends JPanel implements TreeCellRenderer {

    private final JCheckBox checkBox = new JCheckBox();
    private final JLabel iconLabel = new JLabel();
    private final JLabel textLabel = new JLabel();

    public CheckBoxTreeRenderer() {
        super(new BorderLayout(4, 0));
        setOpaque(false);
        checkBox.setOpaque(false);
        checkBox.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        textLabel.setForeground(UiTheme.TEXT_PRIMARY);
        textLabel.setFont(UiTheme.font(Font.PLAIN, 12f));
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        add(checkBox, BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout(4, 0));
        right.setOpaque(false);
        right.add(iconLabel, BorderLayout.WEST);
        right.add(textLabel, BorderLayout.CENTER);
        add(right, BorderLayout.CENTER);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                  boolean selected, boolean expanded,
                                                  boolean leaf, int row, boolean hasFocus) {
        if (!(value instanceof DefaultMutableTreeNode)
                || !(((DefaultMutableTreeNode) value).getUserObject() instanceof JarClassNode)) {
            textLabel.setText(String.valueOf(value));
            checkBox.setSelected(false);
            iconLabel.setIcon(null);
            return this;
        }
        JarClassNode node = (JarClassNode) ((DefaultMutableTreeNode) value).getUserObject();
        textLabel.setText(node.getLabel());
        textLabel.setForeground(selected ? Color.WHITE : UiTheme.TEXT_PRIMARY);
        textLabel.setFont(UiTheme.font(node.isClassLeaf() ? Font.PLAIN : Font.BOLD, 12f));
        iconLabel.setIcon(iconFor(node));
        applyState(node.getState());
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        return this;
    }

    private void applyState(JarClassNode.State state) {
        switch (state) {
            case CHECKED:
                checkBox.setSelected(true);
                checkBox.setIcon(null);
                break;
            case PARTIAL:
                checkBox.setSelected(false);
                checkBox.setIcon(new PartialIcon());
                break;
            default:
                checkBox.setSelected(false);
                checkBox.setIcon(null);
        }
    }

    private static Icon iconFor(JarClassNode node) {
        return new PackageOrClassIcon(node.isClassLeaf());
    }

    // ------------------------------------------------------------------

    private static class PartialIcon implements Icon {
        @Override public int getIconWidth()  { return 14; }
        @Override public int getIconHeight() { return 14; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0xFFFFFF));
                g2.drawRoundRect(x, y, 13, 13, 4, 4);
                g2.setColor(UiTheme.ACCENT);
                g2.fillRoundRect(x + 3, y + 3, 8, 8, 2, 2);
            } finally { g2.dispose(); }
        }
    }

    private static class PackageOrClassIcon implements Icon {
        private final boolean leaf;
        PackageOrClassIcon(boolean leaf) { this.leaf = leaf; }
        @Override public int getIconWidth()  { return 14; }
        @Override public int getIconHeight() { return 14; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (leaf) {
                    g2.setColor(new Color(0x6FAF73));
                    g2.fillRoundRect(x, y + 1, 12, 12, 4, 4);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
                    g2.drawString("C", x + 3, y + 10);
                } else {
                    g2.setColor(new Color(0xC79A40));
                    int[] xs = {x, x + 5, x + 6, x + 13, x + 13, x};
                    int[] ys = {y + 3, y + 3, y + 4, y + 4, y + 12, y + 12};
                    g2.fillPolygon(xs, ys, xs.length);
                }
            } finally { g2.dispose(); }
        }
    }
}
