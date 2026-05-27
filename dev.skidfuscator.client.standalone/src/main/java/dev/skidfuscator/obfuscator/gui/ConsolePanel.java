package dev.skidfuscator.obfuscator.gui;

import dev.skidfuscator.obfuscator.gui.ansi.AnsiParser;
import dev.skidfuscator.obfuscator.gui.ui.SecondaryButton;
import dev.skidfuscator.obfuscator.gui.ui.SectionHeader;
import dev.skidfuscator.obfuscator.gui.ui.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.FileDialog;
import java.awt.Frame;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import java.awt.Toolkit;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConsolePanel extends JPanel implements SkidPanel {

    private final JTextPane consoleOutput;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private final StyledDocument doc;
    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final Style baseStyle;
    private final Map<String, Style> cachedStyles = new HashMap<>();
    private final JCheckBox autoScroll = new JCheckBox("Auto-scroll", true);

    public ConsolePanel() {
        setLayout(new BorderLayout());
        setOpaque(false);

        add(new SectionHeader(
                "Console",
                "Live obfuscation log. Use the toolbar to clear, copy or save the buffer."),
                BorderLayout.NORTH);

        consoleOutput = new JTextPane();
        consoleOutput.setEditable(false);
        consoleOutput.setBackground(UiTheme.CONSOLE_BG);
        consoleOutput.setForeground(UiTheme.CONSOLE_FG);
        consoleOutput.setFont(UiTheme.mono(Font.PLAIN, 12f));
        consoleOutput.setMargin(new java.awt.Insets(UiTheme.PAD_M, UiTheme.PAD_M, UiTheme.PAD_M, UiTheme.PAD_M));
        consoleOutput.setCaretColor(UiTheme.CONSOLE_FG);

        JScrollPane scroll = new JScrollPane(consoleOutput);
        scroll.setBorder(BorderFactory.createLineBorder(UiTheme.SUBTLE_BORDER, 1, true));
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        add(scroll, BorderLayout.CENTER);

        doc = consoleOutput.getStyledDocument();
        baseStyle = initBaseStyle();
        initSemanticStyles();

        originalOut = System.out;
        originalErr = System.err;
        redirectSystemStreams();

        add(buildToolbar(), BorderLayout.SOUTH);
    }

    private JComponent buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setOpaque(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(UiTheme.PAD_M, 0, 0, 0));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTheme.PAD_S, 0));
        left.setOpaque(false);
        autoScroll.setOpaque(false);
        autoScroll.setForeground(UiTheme.TEXT_SECONDARY);
        autoScroll.setFont(UiTheme.font(Font.PLAIN, 12f));
        left.add(autoScroll);
        toolbar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTheme.PAD_S, 0));
        right.setOpaque(false);

        JButton copy  = new SecondaryButton("Copy");
        JButton save  = new SecondaryButton("Save…");
        JButton clear = new SecondaryButton("Clear");
        copy .addActionListener(e -> copyToClipboard());
        save .addActionListener(e -> saveToFile());
        clear.addActionListener(e -> clearConsole());
        right.add(copy);
        right.add(save);
        right.add(clear);
        toolbar.add(right, BorderLayout.EAST);

        return toolbar;
    }

    private Style initBaseStyle() {
        Style style = consoleOutput.addStyle("base", null);
        StyleConstants.setFontFamily(style, consoleOutput.getFont().getFamily());
        StyleConstants.setFontSize(style, consoleOutput.getFont().getSize());
        StyleConstants.setForeground(style, UiTheme.CONSOLE_FG);
        return style;
    }

    private void initSemanticStyles() {
        addStyle("progress", new Color(0x5EE2C7));
        addStyle("success",  UiTheme.SUCCESS);
        addStyle("error",    UiTheme.DANGER);
        addStyle("warning",  UiTheme.WARNING);
        addStyle("box",      new Color(0x7A7484));
        addStyle("stats",    UiTheme.TEXT_PRIMARY);
    }

    private void addStyle(String name, Color color) {
        Style style = consoleOutput.addStyle(name, baseStyle);
        StyleConstants.setForeground(style, color);
        cachedStyles.put(name, style);
    }

    private class ConsoleOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean pendingCR = false;
        private boolean lastLineTransient = false;

        @Override public void write(int b) {
            if (b == '\r') {
                // Defer: \r might be the front half of a CRLF line ending (Windows)
                // or a real cursor reset for a progress bar. Decide on next byte.
                pendingCR = true;
                return;
            }
            if (b == '\n') {
                // Either LF or CRLF — either way, commit the buffered line as permanent.
                flush(false);
                pendingCR = false;
                return;
            }
            if (pendingCR) {
                // Standalone \r followed by something else: the buffered content was an
                // in-place progress update. Commit it as transient so the next progress
                // line replaces it instead of stacking.
                flush(true);
                pendingCR = false;
            }
            buffer.write(b);
        }

        private void flush(boolean transientLine) {
            final String content;
            if (buffer.size() == 0) {
                if (transientLine) return;  // bare \r — nothing to render
                content = "";
            } else {
                content = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                buffer.reset();
            }
            final boolean replacePrev = lastLineTransient;
            lastLineTransient = transientLine;
            SwingUtilities.invokeLater(() -> appendLine(content, replacePrev));
        }

        private void appendLine(String content, boolean replacePrev) {
            try {
                if (replacePrev) removeLastLine();
                Style time = consoleOutput.addStyle("time-" + System.nanoTime(), baseStyle);
                StyleConstants.setForeground(time, UiTheme.TEXT_MUTED);
                doc.insertString(doc.getLength(), "[" + timeFormat.format(new Date()) + "] ", time);

                List<AnsiParser.AnsiSegment> segments = AnsiParser.parse(content);
                for (AnsiParser.AnsiSegment seg : segments) {
                    Style s = consoleOutput.addStyle("segment-" + System.nanoTime(), baseStyle);
                    if (seg.getForeground() != null) StyleConstants.setForeground(s, seg.getForeground());
                    if (seg.getBackground() != null) StyleConstants.setBackground(s, seg.getBackground());
                    StyleConstants.setBold(s,         seg.isBold());
                    StyleConstants.setItalic(s,       seg.isItalic());
                    StyleConstants.setUnderline(s,    seg.isUnderline());
                    StyleConstants.setStrikeThrough(s,seg.isStrikethrough());
                    doc.insertString(doc.getLength(), seg.getText(), s);
                }
                doc.insertString(doc.getLength(), "\n", baseStyle);
                if (autoScroll.isSelected()) consoleOutput.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) { e.printStackTrace(originalErr); }
        }

        private void removeLastLine() throws BadLocationException {
            Element root = doc.getDefaultRootElement();
            int count = root.getElementCount();
            if (count == 0) return;
            // The trailing element is the empty line created by the previous '\n'.
            int idx = count - 1;
            Element trailing = root.getElement(idx);
            if (trailing.getEndOffset() - trailing.getStartOffset() <= 1 && idx > 0) {
                idx--;
            }
            Element line = root.getElement(idx);
            int start = line.getStartOffset();
            int end = Math.min(line.getEndOffset(), doc.getLength());
            if (end > start) doc.remove(start, end - start);
        }
    }

    private void redirectSystemStreams() {
        ConsoleOutputStream out = new ConsoleOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    public void clearConsole() {
        SwingUtilities.invokeLater(() -> {
            try { doc.remove(0, doc.getLength()); }
            catch (BadLocationException e) { e.printStackTrace(originalErr); }
        });
    }

    private void copyToClipboard() {
        try {
            String text = doc.getText(0, doc.getLength());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (BadLocationException e) { e.printStackTrace(originalErr); }
    }

    private void saveToFile() {
        Frame parent = (Frame) SwingUtilities.getWindowAncestor(this);
        FileDialog dialog = new FileDialog(parent, "Save console log", FileDialog.SAVE);
        dialog.setFile("skidfuscator-log.txt");
        dialog.setVisible(true);
        if (dialog.getFile() == null) return;
        java.io.File target = new java.io.File(dialog.getDirectory(), dialog.getFile());
        try (FileWriter w = new FileWriter(target)) {
            w.write(doc.getText(0, doc.getLength()));
        } catch (IOException | BadLocationException e) {
            JOptionPane.showMessageDialog(this, "Failed to save log: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void restoreSystemStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }
}
