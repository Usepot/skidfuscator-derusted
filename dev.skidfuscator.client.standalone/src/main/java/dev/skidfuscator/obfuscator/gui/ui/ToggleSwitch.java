package dev.skidfuscator.obfuscator.gui.ui;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Compact iOS-style toggle. Used to enable/disable transformers and other
 * binary settings — replaces the default Swing JCheckBox where a switch reads
 * better visually.
 */
public class ToggleSwitch extends JComponent {

    private boolean selected;
    private final List<Consumer<Boolean>> listeners = new ArrayList<>();

    public ToggleSwitch(boolean selected) {
        this.selected = selected;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (!isEnabled()) return;
                if (SwingUtilities.isLeftMouseButton(e)) {
                    setSelected(!ToggleSwitch.this.selected);
                }
            }
        });
    }

    public boolean isSelected() { return selected; }

    public void setSelected(boolean selected) {
        if (this.selected == selected) return;
        this.selected = selected;
        repaint();
        for (Consumer<Boolean> l : listeners) l.accept(selected);
    }

    public void addChangeListener(Consumer<Boolean> listener) {
        listeners.add(listener);
    }

    @Override
    public Dimension getPreferredSize() { return new Dimension(44, 24); }

    @Override
    public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override
    public Dimension getMaximumSize() { return getPreferredSize(); }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            Color track = selected
                    ? UiTheme.ACCENT
                    : new Color(0xFF, 0xFF, 0xFF, 40);
            if (!isEnabled()) {
                track = new Color(track.getRed(), track.getGreen(), track.getBlue(), 60);
            }
            g2.setColor(track);
            g2.fillRoundRect(0, 0, w, h, h, h);

            int knob = h - 4;
            int kx = selected ? w - knob - 2 : 2;
            g2.setColor(isEnabled() ? Color.WHITE : new Color(0xCCCCCC));
            g2.fillOval(kx, 2, knob, knob);
        } finally {
            g2.dispose();
        }
    }
}
