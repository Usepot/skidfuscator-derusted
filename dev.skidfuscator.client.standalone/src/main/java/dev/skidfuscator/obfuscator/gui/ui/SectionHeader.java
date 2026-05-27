package dev.skidfuscator.obfuscator.gui.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Font;

/**
 * Title + subtitle stacked vertically with consistent spacing. Replaces the
 * Swing TitledBorder which always looks dated.
 */
public class SectionHeader extends JPanel {

    private final JLabel titleLabel;
    private final JLabel subtitleLabel;

    public SectionHeader(String title, String subtitle) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, UiTheme.PAD_M, 0));

        titleLabel = new JLabel(title);
        titleLabel.setFont(UiTheme.font(Font.BOLD, 20f));
        titleLabel.setForeground(UiTheme.TEXT_PRIMARY);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(titleLabel);

        subtitleLabel = new JLabel(subtitle);
        subtitleLabel.setFont(UiTheme.font(Font.PLAIN, 12f));
        subtitleLabel.setForeground(UiTheme.TEXT_SECONDARY);
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitleLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        add(subtitleLabel);
    }

    public void setTitleText(String title) { titleLabel.setText(title); }
    public void setSubtitleText(String subtitle) { subtitleLabel.setText(subtitle); }
}
