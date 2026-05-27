package dev.skidfuscator.obfuscator.gui.ui;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * Single source of truth for colors, fonts, spacing and radius used by the
 * modernised Skidfuscator UI. Values are intentionally derived from FlatLaf
 * UIManager defaults whenever possible so the GUI follows the active theme.
 */
public final class UiTheme {

    public static final int RADIUS = 10;
    public static final int PAD_S = 6;
    public static final int PAD_M = 12;
    public static final int PAD_L = 18;
    public static final int PAD_XL = 24;

    public static final Color ACCENT      = new Color(0x8E7CC3);
    public static final Color ACCENT_HOV  = new Color(0xA293D6);
    public static final Color ACCENT_DOWN = new Color(0x7466A8);

    public static final Color SUCCESS = new Color(0x4CAF50);
    public static final Color WARNING = new Color(0xF59E0B);
    public static final Color DANGER  = new Color(0xEF4444);
    public static final Color INFO    = new Color(0x3B82F6);

    public static final Color SIDEBAR_BG    = new Color(0x1E1B26);
    public static final Color CONTENT_BG    = new Color(0x26222E);
    public static final Color CARD_BG       = new Color(0x2E2A36);
    public static final Color CARD_BG_HOVER = new Color(0x35303E);
    public static final Color SUBTLE_BORDER = new Color(0x3A3543);

    public static final Color TEXT_PRIMARY   = new Color(0xECE6F2);
    public static final Color TEXT_SECONDARY = new Color(0xA39DAE);
    public static final Color TEXT_MUTED     = new Color(0x7A7484);

    public static final Color CONSOLE_BG = new Color(0x16131C);
    public static final Color CONSOLE_FG = new Color(0xCFC6DA);

    private UiTheme() {}

    public static Font font(int style, float size) {
        Font base = UIManager.getFont("defaultFont");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        return base.deriveFont(style, size);
    }

    public static Font mono(int style, float size) {
        Font base = new Font("JetBrains Mono", style, (int) size);
        if (base.getFamily().equals(Font.DIALOG)) {
            base = new Font("Consolas", style, (int) size);
        }
        if (base.getFamily().equals(Font.DIALOG)) {
            base = new Font(Font.MONOSPACED, style, (int) size);
        }
        return base.deriveFont(style, size);
    }
}
