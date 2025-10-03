package io.github.jbellis.brokk.gui.tests;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Read-only test runner output panel.
 *
 * Displays test output in a non-editable text area embedded in a scroll pane.
 * - setOutput(String) replaces the entire content
 * - appendOutput(String) appends text and auto-scrolls to the bottom
 * - clearOutput() clears all content
 * - applyTheme(GuiTheme) updates colors to match the current UI theme
 *
 * Thread-safety: public mutating methods marshal updates to the EDT.
 * This panel is display-only; execution of tests should be handled elsewhere,
 * piping logs to this component.
 */
public class TestRunnerPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(TestRunnerPanel.class);

    private final JTextArea outputArea;
    private final JScrollPane scrollPane;
    private final DisplayOnlyDocument document;

    public TestRunnerPanel() {
        super(new BorderLayout(0, 0));

        outputArea = new JTextArea();
        document = new DisplayOnlyDocument();
        outputArea.setDocument(document);
        outputArea.setEditable(false);
        outputArea.setLineWrap(false); // Preserve test output formatting
        outputArea.setWrapStyleWord(false);
        outputArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        // Use a monospaced font for test logs, sized similar to default text area
        Font base = UIManager.getFont("TextArea.font");
        if (base == null) base = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, base.getSize());
        outputArea.setFont(mono);

        scrollPane = new JScrollPane(outputArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        add(scrollPane, BorderLayout.CENTER);

        // Apply initial theme from current LAF values
        applyThemeColorsFromUIManager();
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /**
     * Replace the entire output text (EDT-safe).
     */
    public void setOutput(@org.jetbrains.annotations.Nullable String text) {
        final String safe = (text == null) ? "" : text;
        runOnEdt(() -> {
            try {
                document.withWritePermission(() -> {
                    outputArea.setText(safe);
                    scrollToBottom();
                });
            } catch (RuntimeException ex) {
                logger.warn("Failed to set test output", ex);
            }
        });
    }

    /**
     * Append text to the output (EDT-safe). Does not force a newline.
     */
    public void appendOutput(@org.jetbrains.annotations.Nullable String text) {
        final String safe = (text == null) ? "" : text;
        if (safe.isEmpty()) return;
        runOnEdt(() -> {
            try {
                document.withWritePermission(() -> {
                    outputArea.append(safe);
                    scrollToBottom();
                });
            } catch (RuntimeException ex) {
                logger.warn("Failed to append test output", ex);
            }
        });
    }

    /**
     * Clear the output (EDT-safe).
     */
    public void clearOutput() {
        runOnEdt(() -> {
            try {
                document.withWritePermission(() -> outputArea.setText(""));
            } catch (RuntimeException ex) {
                logger.warn("Failed to clear test output", ex);
            }
        });
    }

    /**
     * Apply the current theme. Uses UI defaults with dark/light fallbacks.
     */
    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Prefer UIManager colors; provide sane fallbacks for both themes
        Color bg = UIManager.getColor("TextArea.background");
        Color fg = UIManager.getColor("TextArea.foreground");

        if (bg == null) {
            bg = guiTheme.isDarkTheme() ? new Color(32, 32, 32) : Color.WHITE;
        }
        if (fg == null) {
            fg = guiTheme.isDarkTheme() ? new Color(221, 221, 221) : Color.BLACK;
        }

        final Color bgFinal = bg;
        final Color fgFinal = fg;

        if (SwingUtilities.isEventDispatchThread()) {
            applyColors(bgFinal, fgFinal);
        } else {
            SwingUtilities.invokeLater(() -> applyColors(bgFinal, fgFinal));
        }
    }

    private void applyThemeColorsFromUIManager() {
        Color bg = UIManager.getColor("TextArea.background");
        Color fg = UIManager.getColor("TextArea.foreground");
        if (bg == null) bg = Color.WHITE;
        if (fg == null) fg = Color.BLACK;
        applyColors(bg, fg);
    }

    private void applyColors(Color bg, Color fg) {
        outputArea.setBackground(bg);
        outputArea.setForeground(fg);
        // Ensure caret is visible with the foreground color even if non-editable
        outputArea.setCaretColor(fg);
        revalidate();
        repaint();
    }

    private void scrollToBottom() {
        // Move caret to end; this will auto-scroll the viewport
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }

    /**
     * Display-only document that rejects user edits but allows controlled programmatic updates
     * via withWritePermission.
     */
    private static final class DisplayOnlyDocument extends PlainDocument {
        private boolean allowWrite = false;

        void withWritePermission(Runnable r) {
            boolean prev = allowWrite;
            allowWrite = true;
            try {
                r.run();
            } finally {
                allowWrite = prev;
            }
        }

        @Override
        public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
            if (!allowWrite) {
                return; // ignore any user-initiated insert
            }
            super.insertString(offs, str, a);
        }

        @Override
        public void remove(int offs, int len) throws BadLocationException {
            if (!allowWrite) {
                return; // ignore any user-initiated remove
            }
            super.remove(offs, len);
        }

        @Override
        public void replace(int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            if (!allowWrite) {
                return; // ignore any user-initiated replace
            }
            super.replace(offset, length, text, attrs);
        }
    }
}
