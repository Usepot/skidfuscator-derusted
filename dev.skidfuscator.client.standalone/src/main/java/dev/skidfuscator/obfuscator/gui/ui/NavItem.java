package dev.skidfuscator.obfuscator.gui.ui;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Single row in the left-hand navigation. Renders an icon glyph + label with
 * hover / selected states keyed off the accent colour.
 */
public class NavItem extends JComponent {

    private final String glyph;
    private final String title;
    private final char mnemonic;
    private final Consumer<NavItem> onClick;

    private boolean selected;
    private boolean hovered;
    private boolean enabled = true;

    public NavItem(String glyph, String title, char mnemonic, Consumer<NavItem> onClick) {
        this.glyph = glyph;
        this.title = title;
        this.mnemonic = mnemonic;
        this.onClick = onClick;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(UiTheme.font(Font.PLAIN, 13f));
        setToolTipText("Alt+" + Character.toUpperCase(mnemonic) + "  " + title);

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
            @Override public void mouseExited (MouseEvent e) { hovered = false; repaint(); }
            @Override public void mouseClicked(MouseEvent e) {
                if (enabled && SwingUtilities.isLeftMouseButton(e) && onClick != null) {
                    onClick.accept(NavItem.this);
                }
            }
        });
    }

    public void setSelected(boolean selected) {
        if (this.selected == selected) return;
        this.selected = selected;
        repaint();
    }

    public boolean isSelected() { return selected; }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        super.setEnabled(enabled);
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(180, 40);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, 40);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int padX = UiTheme.PAD_M;

            // Background
            if (selected) {
                g2.setColor(new Color(UiTheme.ACCENT.getRed(), UiTheme.ACCENT.getGreen(), UiTheme.ACCENT.getBlue(), 70));
                g2.fillRoundRect(padX / 2, 4, w - padX, h - 8, UiTheme.RADIUS, UiTheme.RADIUS);
            } else if (hovered && enabled) {
                g2.setColor(new Color(0xFF, 0xFF, 0xFF, 18));
                g2.fillRoundRect(padX / 2, 4, w - padX, h - 8, UiTheme.RADIUS, UiTheme.RADIUS);
            }

            // Accent bar when selected
            if (selected) {
                g2.setColor(UiTheme.ACCENT);
                g2.fillRoundRect(2, 10, 3, h - 20, 3, 3);
            }

            // Icon glyph
            Font glyphFont = UiTheme.font(Font.PLAIN, 16f);
            g2.setFont(glyphFont);
            FontMetrics fm = g2.getFontMetrics();
            Color fg = !enabled
                    ? UiTheme.TEXT_MUTED
                    : (selected ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY);
            g2.setColor(fg);
            int glyphY = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(glyph, padX + 4, glyphY);

            // Title
            g2.setFont(getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN));
            FontMetrics fm2 = g2.getFontMetrics();
            g2.drawString(title, padX + 4 + 24, (h + fm2.getAscent() - fm2.getDescent()) / 2);
        } finally {
            g2.dispose();
        }
    }

    public char getMnemonic() { return mnemonic; }
}
