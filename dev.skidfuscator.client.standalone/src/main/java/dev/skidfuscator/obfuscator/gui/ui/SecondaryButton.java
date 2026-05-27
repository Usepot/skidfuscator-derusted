package dev.skidfuscator.obfuscator.gui.ui;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Outlined button for secondary actions (Browse, Buy Enterprise, etc.). Uses
 * the same rounded shape as {@link PrimaryButton} but transparent fill.
 */
public class SecondaryButton extends JButton {

    public SecondaryButton(String text) {
        super(text);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(UiTheme.font(Font.PLAIN, 12f));
        setForeground(UiTheme.TEXT_PRIMARY);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width + 16, 92), 32);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color bg;
            if (!isEnabled()) {
                bg = new Color(0xFF, 0xFF, 0xFF, 8);
            } else if (getModel().isPressed()) {
                bg = new Color(0xFF, 0xFF, 0xFF, 40);
            } else if (getModel().isRollover()) {
                bg = new Color(0xFF, 0xFF, 0xFF, 26);
            } else {
                bg = new Color(0xFF, 0xFF, 0xFF, 14);
            }
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), UiTheme.RADIUS, UiTheme.RADIUS);

            g2.setColor(UiTheme.SUBTLE_BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, UiTheme.RADIUS, UiTheme.RADIUS);

            g2.setColor(isEnabled() ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED);
            g2.setFont(getFont());
            java.awt.FontMetrics fm = g2.getFontMetrics();
            String text = getText() == null ? "" : getText();
            int tx = (getWidth() - fm.stringWidth(text)) / 2;
            int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, tx, ty);
        } finally {
            g2.dispose();
        }
    }
}
