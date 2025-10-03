package io.github.jbellis.brokk.gui.tests;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

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

    private final JTextArea outputArea;
    private final JScrollPane scrollPane;

    public TestRunnerPanel() {
        super(new BorderLayout(0, 0));

        outputArea = new JTextArea();
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

    /**
     * Replace the entire output text (EDT-safe).
     */
    public void setOutput(String text) {
        final String safe = text == null ? "" : text;
        if (SwingUtilities.isEventDispatchThread()) {
            outputArea.setText(safe);
            scrollToBottom();
        } else {
            SwingUtilities.invokeLater(() -> {
                outputArea.setText(safe);
                scrollToBottom();
            });
        }
    }

    /**
     * Append text to the output (EDT-safe). Does not force a newline.
     */
    public void appendOutput(String text) {
        if (text == null || text.isEmpty()) return;
        if (SwingUtilities.isEventDispatchThread()) {
            outputArea.append(text);
            scrollToBottom();
        } else {
            SwingUtilities.invokeLater(() -> {
                outputArea.append(text);
                scrollToBottom();
            });
        }
    }

    /**
     * Clear the output (EDT-safe).
     */
    public void clearOutput() {
        if (SwingUtilities.isEventDispatchThread()) {
            outputArea.setText("");
        } else {
            SwingUtilities.invokeLater(() -> outputArea.setText(""));
        }
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
}
