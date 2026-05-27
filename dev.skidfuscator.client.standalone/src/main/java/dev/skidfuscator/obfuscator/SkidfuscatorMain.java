package dev.skidfuscator.obfuscator;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.intellijthemes.FlatDarkPurpleIJTheme;
import dev.skidfuscator.obfuscator.command.HelpCommand;
import dev.skidfuscator.obfuscator.command.MappingsCommand;
import dev.skidfuscator.obfuscator.command.ObfuscateCommand;
import dev.skidfuscator.obfuscator.gui.MainFrame;
import dev.skidfuscator.obfuscator.gui.ui.UiTheme;
import dev.skidfuscator.obfuscator.util.LogoUtil;
import lombok.SneakyThrows;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.io.File;

public class SkidfuscatorMain {

    @SneakyThrows
    public static void main(String[] args) {

        if (args.length == 0) {
            // MacOS menu bar polish
            System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Skidfuscator");
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.appearance", "system");
            System.setProperty("flatlaf.useWindowDecorations", "true");
            System.setProperty("flatlaf.menuBarEmbedded", "true");

            SwingUtilities.invokeLater(() -> {
                installLookAndFeel();
                new MainFrame().setVisible(true);
            });
            return;
        }

        LogoUtil.printLogo();

        if (args.length == 1 && args[0].equalsIgnoreCase("cli")) {
            final LineReader reader = LineReaderBuilder
                    .builder()
                    .terminal(TerminalBuilder.terminal())
                    .appName("Skidfuscator")
                    .parser(new DefaultParser())
                    .build();

            while (true) {
                String line;

                try {
                    line = reader.readLine("> ");
                } catch (UserInterruptException e) {
                    break;
                } catch (EndOfFileException e) {
                    return;
                }

                if (line == null)
                    continue;

                if (line.contains("obfuscate")) {
                    final String input = line.split(" ")[1];
                    final String output = line.split(" ")[2];

                    final SkidfuscatorSession session = new SkidfuscatorSession(
                            new File(input),
                            new File(output),
                            null,
                            null,
                            null,
                            null,
                            new File(System.getProperty("java.home"), "lib/rt.jar"),
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false
                    );

                    final Skidfuscator skidfuscator = new Skidfuscator(session);
                    skidfuscator.run();
                }
            }

        } else {
            new CommandLine(new HelpCommand())
                    .addSubcommand("obfuscate", new ObfuscateCommand())
                    .addSubcommand("mappings", new MappingsCommand())
                    .execute(args);
        }
    }

    private static void installLookAndFeel() {
        // System UIManager defaults applied before the LAF is installed so the
        // theme picks them up consistently.
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("MenuItem.selectionType", "underline");
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("TextComponent.selectAllOnFocusPolicy", "never");
        UIManager.put("CheckBox.icon.style", "filled");
        UIManager.put("CheckBox.arc", 4);
        UIManager.put("Component.accentColor", UiTheme.ACCENT);

        try {
            FlatDarkPurpleIJTheme.setup();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Force defaults that depend on the active LAF being installed.
        UIManager.put("TitlePane.background", UiTheme.SIDEBAR_BG);
        UIManager.put("TitlePane.inactiveBackground", UiTheme.SIDEBAR_BG);
        UIManager.put("MenuBar.background", UiTheme.SIDEBAR_BG);
        UIManager.put("Panel.background", UiTheme.CONTENT_BG);
        UIManager.put("OptionPane.background", UiTheme.CONTENT_BG);
        UIManager.put("Separator.foreground", UiTheme.SUBTLE_BORDER);
        FlatLaf.updateUI();
    }

    /**
     * Hook used by MainFrame so the licensing notice fires after first paint
     * rather than blocking startup with a modal dialog.
     */
    public static void showWelcomeNotice(java.awt.Component parent) {
        // Intentionally a no-op now — MainFrame surfaces the same info in the
        // sidebar / status bar. Kept as an extension point.
        Color bg = (Color) UIManager.get("Panel.background");
        if (bg == null) {
            UIManager.put("Panel.background", UiTheme.CONTENT_BG);
        }
    }
}
