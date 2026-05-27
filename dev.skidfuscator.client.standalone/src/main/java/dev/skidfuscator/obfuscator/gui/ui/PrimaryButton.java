package dev.skidfuscator.obfuscator.gui.ui;

import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Filled button painted with the accent colour. Used for primary actions like
 * "Start Obfuscation".
 */
public class PrimaryButton extends JButton {

    public PrimaryButton(String text) {
        super(text);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setForeground(Color.WHITE);
        setFont(UiTheme.font(Font.BOLD, 13f));
        putClientProperty("JButton.buttonType", "roundRect");
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width + 24, 160), 38);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color bg;
            if (!isEnabled()) {
                bg = new Color(0x55, 0x4E, 0x68);
            } else if (getModel().isPressed()) {
                bg = UiTheme.ACCENT_DOWN;
            } else if (getModel().isRollover()) {
                bg = UiTheme.ACCENT_HOV;
            } else {
                bg = UiTheme.ACCENT;
            }
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), UiTheme.RADIUS, UiTheme.RADIUS);

            g2.setColor(isEnabled() ? Color.WHITE : new Color(0xC0, 0xBC, 0xCC));
            g2.setFont(getFont());
            java.awt.FontMetrics fm = g2.getFontMetrics();
            String text = getText() == null ? "" : getText();
            int tx = (getWidth() - fm.stringWidth(text)) / 2;
            int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            if (getIcon() != null) {
                int iconX = tx - getIcon().getIconWidth() - 6;
                int iconY = (getHeight() - getIcon().getIconHeight()) / 2;
                getIcon().paintIcon(this, g2, iconX, iconY);
            }
            g2.drawString(text, tx, ty);
        } finally {
            g2.dispose();
        }
    }
}
