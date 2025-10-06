package io.github.jbellis.brokk.gui.tests;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Run-centric Test Runner panel.
 *
 * Left: list of runs with status, start time, file count, duration.
 * Right: raw output for the selected run (live for active run).
 *
 * Thread-safety: public mutating methods marshal updates to the EDT.
 */
public class TestRunnerPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(TestRunnerPanel.class);

    // Model
    private final DefaultListModel<RunEntry> runListModel;
    private final JList<RunEntry> runList;
    private final JScrollPane runListScrollPane;

    // Output
    private final JTextArea outputArea;
    private final DisplayOnlyDocument document;
    private final JScrollPane outputScrollPane;

    private final JSplitPane splitPane;

    // Runs by id
    private final Map<String, RunEntry> runsById;

    // Current active run (where live output goes)
    private volatile @Nullable String currentActiveRunId;

    // Maximum number of runs to retain
    private int maxRuns = 50;

    // Optional persistence store for runs
    private volatile @Nullable TestRunsStore runsStore = null;

    public TestRunnerPanel() {
        super(new BorderLayout(0, 0));

        runListModel = new DefaultListModel<>();
        runsById = new ConcurrentHashMap<>();

        runList = new JList<>(runListModel);
        runList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        runList.setCellRenderer(new RunEntryRenderer());
        runList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateOutputForSelectedRun();
            }
        });

        runListScrollPane = new JScrollPane(runList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        runListScrollPane.setBorder(BorderFactory.createEmptyBorder());

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

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, runListScrollPane, outputScrollPane);
        splitPane.setDividerLocation(300);
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
     * Inject an optional persistence store for test runs. On set, attempts to load the runs
     * off the EDT and restores up to maxRuns into the UI, preserving order.
     */
    public void injectTestRunsStore(@Nullable TestRunsStore store) {
        this.runsStore = store;
        if (this.runsStore == null) {
            return;
        }
        var loader = new Thread(() -> {
            List<RunRecord> records;
            try {
                var rs = this.runsStore;
                if (rs == null) return;
                records = rs.load();
            } catch (Exception e) {
                logger.warn("Failed to load test runs from store: {}", e.getMessage(), e);
                return;
            }
            restoreRuns(records);
        }, "TestRunnerPanel-LoadRuns");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * Restore runs into the UI. Preserves order (oldest -> newest),
     * truncates to maxRuns most recent, rebuilds state, selects newest,
     * and updates the output area accordingly.
     */
    private void restoreRuns(List<RunRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        int start = Math.max(0, records.size() - maxRuns);
        List<RunRecord> slice = records.subList(start, records.size());

        runOnEdt(() -> {
            runsById.clear();
            runListModel.clear();

            String lastRunningId = null;
            for (RunRecord r : slice) {
                var run = new RunEntry(r.id(), r.fileCount(), r.command(), r.startedAt());
                String out = r.output();
                if (!out.isEmpty()) {
                    run.appendOutput(out);
                }
                if (r.completedAt() != null) {
                    run.complete(r.exitCode(), r.completedAt());
                }
                runsById.put(r.id(), run);
                runListModel.addElement(run);
                if (run.isRunning()) {
                    lastRunningId = r.id();
                }
            }

            if (runListModel.getSize() > 0) {
                runList.setSelectedIndex(runListModel.getSize() - 1);
                updateOutputForSelectedRun();
            } else {
                try {
                    document.withWritePermission(() -> outputArea.setText(""));
                } catch (RuntimeException ex) {
                    logger.warn("Failed to clear output during restore", ex);
                }
            }

            currentActiveRunId = lastRunningId;
        });
    }

    /**
     * Begin a new test run.
     *
     * @param fileCount number of files included in the run (may be 0/unknown)
     * @param command the command used to launch the run
     * @param startedAt start timestamp
     * @return run id
     */
    public String beginRun(int fileCount, String command, Instant startedAt) {
        String id = UUID.randomUUID().toString();
        var run = new RunEntry(id, fileCount, command, startedAt);
        runsById.put(id, run);
        currentActiveRunId = id;

        runOnEdt(() -> {
            runListModel.addElement(run);
            // Enforce retention cap
            while (runListModel.getSize() > maxRuns) {
                RunEntry removed = runListModel.remove(0);
                runsById.remove(removed.id);
                // If the removed run was selected, we'll reselect the most recent below
            }
            // Always select the most recent run
            runList.setSelectedIndex(runListModel.getSize() - 1);
            try {
                document.withWritePermission(() -> {
                    outputArea.setText(""); // new run => clear output view
                    scrollToBottom();
                });
            } catch (RuntimeException ex) {
                logger.warn("Failed to initialize output area for new run", ex);
            }
        });
        return id;
    }

    /**
     * Append output to a specific run.
     */
    public void appendToRun(String runId, String text) {
        if (text.isEmpty()) return;
        var run = runsById.get(runId);
        if (run == null) {
            logger.warn("appendToRun: unknown runId {}", runId);
            return;
        }
        run.appendOutput(text);

        runOnEdt(() -> {
            var selected = runList.getSelectedValue();
            if (selected != null && selected.id.equals(runId)) {
                try {
                    document.withWritePermission(() -> {
                        outputArea.append(text);
                        scrollToBottom();
                    });
                } catch (RuntimeException ex) {
                    logger.warn("Failed to append run output", ex);
                }
            }
        });
    }

    /**
     * Append output to the active run. If no active run exists, creates a generic one.
     */
    public void appendToActiveRun(String text) {
        if (text.isEmpty()) return;

        String runId = currentActiveRunId;
        if (runId == null || !runsById.containsKey(runId)) {
            // Create a generic "General Output" run
            runId = beginRun(0, "General Output", Instant.now());
        }
        appendToRun(runId, text);
    }

    /**
     * Complete the run, setting exit code and completion time.
     */
    public void completeRun(String runId, int exitCode, Instant completedAt) {
        var run = runsById.get(runId);
        if (run == null) {
            logger.warn("completeRun: unknown runId {}", runId);
            return;
        }
        run.complete(exitCode, completedAt);
        runOnEdt(() -> runList.repaint());
    }

    /**
     * Sets the maximum number of runs to retain in the list.
     */
    public void setMaxRuns(int maxRuns) {
        this.maxRuns = Math.max(1, maxRuns);
    }

    /**
     * Clear all runs and output.
     */
    public void clearAllRuns() {
        runOnEdt(() -> {
            runsById.clear();
            currentActiveRunId = null;
            runListModel.clear();
            try {
                document.withWritePermission(() -> outputArea.setText(""));
            } catch (RuntimeException ex) {
                logger.warn("Failed to clear output", ex);
            }
        });
    }

    private void updateOutputForSelectedRun() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::updateOutputForSelectedRun);
            return;
        }
        RunEntry selected = runList.getSelectedValue();
        if (selected == null) {
            try {
                document.withWritePermission(() -> outputArea.setText(""));
            } catch (RuntimeException ex) {
                logger.warn("Failed to clear output", ex);
            }
            return;
        }

        String text = selected.getOutput();
        try {
            document.withWritePermission(() -> {
                outputArea.setText(text);
                scrollToBottom();
            });
        } catch (RuntimeException ex) {
            logger.warn("Failed to update output", ex);
        }
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

        runList.setBackground(bg);
        runList.setForeground(fg);

        revalidate();
        repaint();
    }

    private void scrollToBottom() {
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }

    /**
     * Compatibility API for legacy tests: update a TestEntry's status and timestamps.
     * This panel is now run-centric, but tests still validate timestamp behavior on TestEntry.
     */
    public void updateTestStatus(TestEntry entry, TestEntry.Status status) {
        Instant now = Instant.now();
        switch (status) {
            case RUNNING -> entry.setStartedAtIfAbsent(now);
            case PASSED, FAILED, ERROR -> {
                entry.setStartedAtIfAbsent(now);
                entry.setCompletedAtIfAbsent(now);
            }
        }
        entry.setStatus(status);
        runOnEdt(() -> {
            revalidate();
            repaint();
        });
    }

    /**
     * Factory for tests: ensures TestEntryRenderer is referenced by production code
     * so Error Prone does not flag it as UnusedNestedClass.
     */
    public static DefaultListCellRenderer newTestEntryRendererForTests() {
        return new TestEntryRenderer();
    }

    // ==========================
    // Internal model and classes
    // ==========================

    private static final class RunEntry {
        private static final int EXIT_CODE_UNKNOWN = Integer.MIN_VALUE;

        private final String id;
        private final int fileCount;
        private final String command;
        private final Instant startedAt;
        private volatile @Nullable Instant completedAt;
        private volatile int exitCode = EXIT_CODE_UNKNOWN;

        private final StringBuilder output = new StringBuilder();

        RunEntry(String id, int fileCount, String command, Instant startedAt) {
            this.id = id;
            this.fileCount = fileCount;
            this.command = command;
            this.startedAt = startedAt;
        }

        void appendOutput(String text) {
            synchronized (output) {
                output.append(text);
            }
        }

        String getOutput() {
            synchronized (output) {
                return output.toString();
            }
        }

        void complete(int exitCode, Instant completedAt) {
            if (this.completedAt == null) {
                this.completedAt = completedAt;
            }
            this.exitCode = exitCode;
        }

        boolean isRunning() {
            return completedAt == null;
        }

        boolean isSuccess() {
            return !isRunning() && exitCode == 0;
        }


        long getDurationSeconds() {
            Instant end = (completedAt != null) ? completedAt : Instant.now();
            return Math.max(0L, Duration.between(startedAt, end).toSeconds());
        }
    }

    private static class RunEntryRenderer extends DefaultListCellRenderer {
        private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                     boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (!(value instanceof RunEntry run)) {
                return label;
            }

            String icon = run.isRunning() ? "⟳ " : (run.isSuccess() ? "✓ " : "✗ ");
            // Start time HH:mm:ss (local tz)
            String timeText = TIME_FORMAT.format(run.startedAt.atZone(ZoneId.systemDefault()));
            // Files
            String filesText = run.fileCount == 1 ? "1 file" : (run.fileCount + " files");
            // Duration mm:ss
            long secs = run.getDurationSeconds();
            String dur = "%02d:%02d".formatted(secs / 60, secs % 60);

            label.setText(icon + timeText + " • " + filesText + " • " + dur);
            label.setToolTipText(run.command);

            if (!isSelected) {
                Color statusColor = run.isRunning()
                        ? new Color(100, 150, 255)
                        : (run.isSuccess() ? new Color(100, 200, 100) : new Color(255, 100, 100));
                label.setForeground(statusColor);
            }

            return label;
        }
    }

    /**
     * Compatibility renderer for legacy tests that expect TestRunnerPanel$TestEntryRenderer.
     * Renders TestEntry display name with a timestamp suffix and sets tooltip to ISO-8601 instant.
     * Prefers completedAt; falls back to startedAt; omits time if both are null.
     */
    private static class TestEntryRenderer extends DefaultListCellRenderer {
        private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (!(value instanceof TestEntry te)) {
                return label;
            }

            Instant ts = te.getCompletedAt() != null ? te.getCompletedAt() : te.getStartedAt();
            StringBuilder text = new StringBuilder(te.getDisplayName());
            if (ts != null) {
                String timeText = TIME_FORMAT.format(ts.atZone(ZoneId.systemDefault()));
                text.append(" ").append(timeText);
                label.setToolTipText(DateTimeFormatter.ISO_INSTANT.format(ts));
            } else {
                label.setToolTipText(null);
            }
            label.setText(text.toString());
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
