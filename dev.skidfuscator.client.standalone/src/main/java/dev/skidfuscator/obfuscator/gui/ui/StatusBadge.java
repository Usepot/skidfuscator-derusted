package dev.skidfuscator.obfuscator.gui.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Rounded pill that conveys field/operation state with colour + text.
 */
public class StatusBadge extends JComponent {

    public enum Kind { SUCCESS, WARNING, DANGER, INFO, NEUTRAL }

    private String text = "";
    private Kind kind = Kind.NEUTRAL;

    public StatusBadge() {
        setOpaque(false);
        setFont(UiTheme.font(Font.BOLD, 11f));
    }

    public StatusBadge(Kind kind, String text) {
        this();
        set(kind, text);
    }

    public void set(Kind kind, String text) {
        this.kind = kind;
        this.text = text;
        revalidate();
        repaint();
    }

    public Kind kind() { return kind; }
    public String text() { return text; }

    private Color fill() {
        switch (kind) {
            case SUCCESS: return new Color(0x33, 0xB1, 0x5C, 60);
            case WARNING: return new Color(0xF5, 0x9E, 0x0B, 60);
            case DANGER:  return new Color(0xEF, 0x44, 0x44, 60);
            case INFO:    return new Color(0x3B, 0x82, 0xF6, 60);
            default:      return new Color(0xFF, 0xFF, 0xFF, 30);
        }
    }

    private Color foreground() {
        switch (kind) {
            case SUCCESS: return UiTheme.SUCCESS;
            case WARNING: return UiTheme.WARNING;
            case DANGER:  return UiTheme.DANGER;
            case INFO:    return UiTheme.INFO;
            default:      return UiTheme.TEXT_MUTED;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int w = fm.stringWidth(text) + 18;
        int h = fm.getHeight() + 6;
        return new Dimension(Math.max(w, 24), Math.max(h, 22));
    }

    @Override
    public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int h = getHeight();
            int w = getWidth();

            g2.setColor(fill());
            g2.fillRoundRect(0, 0, w, h, h, h);

            g2.setColor(new Color(foreground().getRed(), foreground().getGreen(), foreground().getBlue(), 120));
            g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);

            g2.setFont(getFont());
            g2.setColor(foreground());
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, tx, ty);
        } finally {
            g2.dispose();
        }
    }
}
