package dev.skidfuscator.obfuscator.gui.ui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

/**
 * Rounded background panel used for grouped content (transformer rows, form
 * sections, etc.). Honors {@link UiTheme} colours and supports a subtle hover.
 */
public class Card extends JPanel {

    private boolean hoverable = false;
    private boolean hovered = false;

    public Card() {
        this(null);
    }

    public Card(LayoutManager layout) {
        if (layout != null) setLayout(layout);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, UiTheme.PAD_M, UiTheme.PAD_M, UiTheme.PAD_M));
    }

    public void setHoverable(boolean hoverable) {
        this.hoverable = hoverable;
    }

    public void setHovered(boolean hovered) {
        if (this.hovered != hovered) {
            this.hovered = hovered;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color bg = (hoverable && hovered) ? UiTheme.CARD_BG_HOVER : UiTheme.CARD_BG;
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), UiTheme.RADIUS, UiTheme.RADIUS);

            g2.setColor(UiTheme.SUBTLE_BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, UiTheme.RADIUS, UiTheme.RADIUS);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
