package io.github.jbellis.brokk.gui.tests;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Test runner output panel with split view.
 *
 * Displays a list of tests on the left and the selected test's output on the right.
 * - addTest(TestEntry) adds a new test to the list
 * - appendToTest(TestEntry, String) appends output to a specific test
 * - updateTestStatus(TestEntry, Status) updates the test's status
 * - clearTests() removes all tests
 * - Legacy methods (setOutput, appendOutput, clearOutput) operate on a global output buffer
 * - applyTheme(GuiTheme) updates colors to match the current UI theme
 *
 * Thread-safety: public mutating methods marshal updates to the EDT.
 */
public class TestRunnerPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(TestRunnerPanel.class);

    private final DefaultListModel<TestEntry> testListModel;
    private final JList<TestEntry> testList;
    private final JScrollPane testListScrollPane;
    private final JTextArea outputArea;
    private final JScrollPane outputScrollPane;
    private final DisplayOnlyDocument document;
    private final JSplitPane splitPane;

    public TestRunnerPanel() {
        super(new BorderLayout(0, 0));

        testListModel = new DefaultListModel<>();
        testList = new JList<>(testListModel);
        testList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        testList.setCellRenderer(new TestEntryRenderer());
        testList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateOutputForSelectedTest();
            }
        });

        testListScrollPane = new JScrollPane(testList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        testListScrollPane.setBorder(BorderFactory.createEmptyBorder());

        outputArea = new JTextArea();
        document = new DisplayOnlyDocument();
        outputArea.setDocument(document);
        outputArea.setEditable(false);
        outputArea.setLineWrap(false);
        outputArea.setWrapStyleWord(false);
        outputArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        Font base = UIManager.getFont("TextArea.font");
        if (base == null) base = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, base.getSize());
        outputArea.setFont(mono);

        outputScrollPane = new JScrollPane(outputArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        outputScrollPane.setBorder(BorderFactory.createEmptyBorder());

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, testListScrollPane, outputScrollPane);
        splitPane.setDividerLocation(250);
        splitPane.setResizeWeight(0.3);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        add(splitPane, BorderLayout.CENTER);

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
     * Add a test to the list (EDT-safe).
     */
    public void addTest(TestEntry test) {
        runOnEdt(() -> {
            testListModel.addElement(test);
            if (testListModel.getSize() == 1) {
                testList.setSelectedIndex(0);
            }
        });
    }

    /**
     * Append output to a specific test (EDT-safe).
     */
    public void appendToTest(TestEntry test, @org.jetbrains.annotations.Nullable String text) {
        final String safe = (text == null) ? "" : text;
        if (safe.isEmpty()) return;
        
        test.appendOutput(safe);
        
        runOnEdt(() -> {
            if (testList.getSelectedValue() == test) {
                updateOutputForSelectedTest();
            }
            testList.repaint();
        });
    }

    /**
     * Update the status of a test (EDT-safe).
     */
    public void updateTestStatus(TestEntry test, TestEntry.Status status) {
        test.setStatus(status);
        runOnEdt(() -> testList.repaint());
    }

    /**
     * Clear all tests (EDT-safe).
     */
    public void clearTests() {
        runOnEdt(() -> {
            testListModel.clear();
            try {
                document.withWritePermission(() -> outputArea.setText(""));
            } catch (RuntimeException ex) {
                logger.warn("Failed to clear output", ex);
            }
        });
    }

    private void updateOutputForSelectedTest() {
        assert SwingUtilities.isEventDispatchThread();
        
        TestEntry selected = testList.getSelectedValue();
        if (selected == null) {
            try {
                document.withWritePermission(() -> outputArea.setText(""));
            } catch (RuntimeException ex) {
                logger.warn("Failed to clear output", ex);
            }
            return;
        }
        
        try {
            document.withWritePermission(() -> {
                outputArea.setText(selected.getOutput());
                scrollToBottom();
            });
        } catch (RuntimeException ex) {
            logger.warn("Failed to update output", ex);
        }
    }

    /**
     * Replace the entire output text (EDT-safe).
     * Legacy method for backward compatibility.
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
     * Append text to the output (EDT-safe).
     * Legacy method for backward compatibility.
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
     * Legacy method for backward compatibility.
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

    @Override
    public void applyTheme(GuiTheme guiTheme) {
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
        outputArea.setCaretColor(fg);
        
        testList.setBackground(bg);
        testList.setForeground(fg);
        
        revalidate();
        repaint();
    }

    private void scrollToBottom() {
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }

    private static class TestEntryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                     boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            
            if (value instanceof TestEntry test) {
                String statusIcon = switch (test.getStatus()) {
                    case RUNNING -> "⟳ ";
                    case PASSED -> "✓ ";
                    case FAILED -> "✗ ";
                    case ERROR -> "⚠ ";
                };
                label.setText(statusIcon + test.getDisplayName());
                
                if (!isSelected) {
                    Color statusColor = switch (test.getStatus()) {
                        case RUNNING -> new Color(100, 150, 255);
                        case PASSED -> new Color(100, 200, 100);
                        case FAILED -> new Color(255, 100, 100);
                        case ERROR -> new Color(255, 180, 50);
                    };
                    label.setForeground(statusColor);
                }
            }
            
            return label;
        }
    }

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
                return;
            }
            super.insertString(offs, str, a);
        }

        @Override
        public void remove(int offs, int len) throws BadLocationException {
            if (!allowWrite) {
                return;
            }
            super.remove(offs, len);
        }

        @Override
        public void replace(int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            if (!allowWrite) {
                return;
            }
            super.replace(offset, length, text, attrs);
        }
    }
}
