package io.github.jbellis.brokk.gui.tests;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.ExecutorConfig;
import io.github.jbellis.brokk.util.SerialByKeyExecutor;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Insets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>Left: list of runs with status, start time, file count, duration. Right: raw output for the selected run (live for
 * active run).
 *
 * <p>Thread-safety: public mutating methods marshal updates to the EDT.
 */
public class TestRunnerPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(TestRunnerPanel.class);

    private final @Nullable Chrome chrome;
    private final MaterialButton runAllButton = new MaterialButton();
    private final MaterialButton stopButton = new MaterialButton();
    private final AtomicBoolean testProcessRunning = new AtomicBoolean(false);
    private volatile @Nullable Process activeTestProcess;

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

    // FIFO of queued runIds; only accessed on EDT
    private final Deque<String> runQueue;

    // Current active run (where live output goes)
    private volatile @Nullable String currentActiveRunId;

    // Maximum number of runs to retain
    private int maxRuns = 50;
    private final TestRunsStore runsStore;
    private final ExecutorService sessionExecutor = Executors.newFixedThreadPool(2);
    private final SerialByKeyExecutor saveExecutor = new SerialByKeyExecutor(sessionExecutor);

    // Limit stored output size to avoid unbounded JSON growth
    private static final int MAX_SNAPSHOT_OUTPUT_CHARS = 200_000;

    public TestRunnerPanel(TestRunsStore runsStore) {
        this(null, runsStore);
    }

    public TestRunnerPanel(@Nullable Chrome chrome, TestRunsStore runsStore) {
        super(new BorderLayout(0, 0));
        this.chrome = chrome;
        this.runsStore = runsStore;
        runListModel = new DefaultListModel<>();
        runsById = new ConcurrentHashMap<>();

        runQueue = new ArrayDeque<>();

        runList = new JList<>(runListModel);
        runList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        runList.setCellRenderer(new RunEntryRenderer());
        runList.setVisibleRowCount(5);
        runList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateOutputForSelectedRun();
            }
        });

        runListScrollPane = new JScrollPane(
                runList, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        runListScrollPane.setBorder(BorderFactory.createEmptyBorder());
        runListScrollPane.setMinimumSize(new java.awt.Dimension(100, 60));
        runListScrollPane.setPreferredSize(new java.awt.Dimension(100, 150));

        // Title and toolbar (similar to TaskListPanel)
        setBorder(BorderFactory.createTitledBorder("Tests"));
        var border = getBorder();
        if (border != null) {
            var insets = border.getBorderInsets(this);
            setMinimumSize(new java.awt.Dimension(100, insets.top + insets.bottom));
        }
        var topToolbar = new JPanel(new java.awt.BorderLayout());
        topToolbar.setOpaque(false);

        var leftToolbar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        leftToolbar.setOpaque(false);
        runAllButton.setIcon(Icons.FAST_FORWARD);
        runAllButton.setMargin(new Insets(0, 0, 0, 0));
        runAllButton.setToolTipText(
                "<html><body style='width:300px'>Run all tests using your build settings.<br>Output is streamed to this panel.</body></html>");
        runAllButton.addActionListener(e -> runAllTests());
        leftToolbar.add(runAllButton);

        stopButton.setIcon(Icons.STOP);
        stopButton.setMargin(new Insets(0, 0, 0, 0));
        stopButton.setToolTipText("Stop the currently running test process.");
        stopButton.addActionListener(e -> stopTests());
        stopButton.setEnabled(false);
        stopButton.setVisible(false);
        leftToolbar.add(stopButton);

        topToolbar.add(leftToolbar, BorderLayout.WEST);

        add(topToolbar, BorderLayout.NORTH);

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

        outputScrollPane = new JScrollPane(
                outputArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        outputScrollPane.setBorder(BorderFactory.createEmptyBorder());
        outputScrollPane.setMinimumSize(new java.awt.Dimension(100, 60));
        outputScrollPane.setPreferredSize(new java.awt.Dimension(100, 200));
        outputScrollPane.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 200));

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, runListScrollPane, outputScrollPane);
        splitPane.setResizeWeight(0.3);
        splitPane.setDividerLocation(150);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setMinimumSize(new java.awt.Dimension(100, 200));

        setMinimumSize(new java.awt.Dimension(100, 200));

        add(splitPane, BorderLayout.CENTER);

        applyThemeColorsFromUIManager();

        // Load persisted runs (per-project) if available
        try {
            List<RunRecord> records = runsStore.load();
            if (!records.isEmpty()) {
                restoreRuns(records);
            }
        } catch (Exception e) {
            logger.warn("Failed to load persisted test runs: {}", e.getMessage(), e);
        }

        // Enable/disable Run All based on current build details availability
        if (chrome != null) {
            try {
                var details = chrome.getProject().awaitBuildDetails();
                runAllButton.setEnabled(!details.equals(BuildAgent.BuildDetails.EMPTY)
                        && !details.testAllCommand().isBlank());
            } catch (Exception ex) {
                runAllButton.setEnabled(false);
            }
        } else {
            runAllButton.setEnabled(false);
        }
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /**
     * Snapshot the most recent runs as RunRecord objects in display order. Returns up to 'limit' runs from the top of
     * the list (newest -> oldest). EDT safety: reads the Swing model on the EDT; if called off-EDT, blocks on
     * invokeAndWait.
     */
    public List<RunRecord> snapshotRuns(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return snapshotRunsFromModel(limit);
        }
        var ref = new AtomicReference<List<RunRecord>>(List.of());
        try {
            SwingUtilities.invokeAndWait(() -> ref.set(snapshotRunsFromModel(limit)));
        } catch (Exception e) {
            logger.warn("Failed to snapshot runs on EDT: {}", e.getMessage(), e);
        }
        return requireNonNull(ref.get());
    }

    private List<RunRecord> snapshotRunsFromModel(int limit) {
        int size = runListModel.getSize();
        if (size == 0) {
            return List.of();
        }
        int count = Math.min(limit, size);
        var out = new ArrayList<RunRecord>(count);
        for (int i = 0; i < count; i++) {
            var run = runListModel.get(i);
            String output = run.getOutput();
            if (output.length() > MAX_SNAPSHOT_OUTPUT_CHARS) {
                int keep = Math.max(0, MAX_SNAPSHOT_OUTPUT_CHARS - 3);
                output = output.substring(0, keep) + "...";
            }
            out.add(new RunRecord(
                    run.id,
                    run.fileCount,
                    run.command,
                    run.startedAt.toEpochMilli(),
                    run.completedAt != null ? run.completedAt.toEpochMilli() : null,
                    run.exitCode,
                    output));
        }
        return out;
    }

    /**
     * Trigger a background save of the current runs snapshot if a store is present. Snapshots on the EDT, performs I/O
     * in a daemon thread, and logs exceptions.
     */
    private void triggerSave() {
        var store = runsStore;
        Runnable snapshotAndSaveTask = () -> {
            List<RunRecord> snapshot;
            try {
                snapshot = snapshotRunsFromModel(maxRuns);
            } catch (Exception e) {
                logger.warn("Failed to snapshot test runs for saving: {}", e.getMessage(), e);
                return;
            }

            saveExecutor.submit("test_runs_save", () -> {
                try {
                    store.save(snapshot);
                } catch (Exception e) {
                    logger.warn("Failed to save test runs: {}", e.getMessage(), e);
                }
            });
        };

        if (SwingUtilities.isEventDispatchThread()) {
            snapshotAndSaveTask.run();
        } else {
            SwingUtilities.invokeLater(snapshotAndSaveTask);
        }
    }

    /**
     * Restore runs into the UI. Preserves order (oldest -> newest), truncates to maxRuns most recent, rebuilds state,
     * selects newest, and updates the output area accordingly. EDT safety: uses runOnEdt to mutate Swing state.
     */
    public void restoreRuns(List<RunRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            doRestore(records);
        } else {
            try {
                SwingUtilities.invokeAndWait(() -> doRestore(records));
            } catch (Exception e) {
                logger.warn("Failed to restore runs on EDT: {}", e.getMessage(), e);
            }
        }
    }

    private void doRestore(List<RunRecord> records) {
        // Records are newest-to-oldest. We want to keep up to maxRuns of the newest.
        int count = Math.min(records.size(), maxRuns);
        List<RunRecord> slice = records.subList(0, count);

        runsById.clear();
        runListModel.clear();

        String lastRunningId = null;
        // To build a newest-to-oldest model, we process the slice from newest to oldest and add to the model.
        for (var r : slice) {
            var run = new RunEntry(r.id(), r.fileCount(), r.command(), Instant.ofEpochMilli(r.startedAtMillis()));
            String out = r.output();
            if (!out.isEmpty()) {
                run.appendOutput(out);
            }
            if (r.completedAtMillis() != null) {
                run.complete(r.exitCode(), Instant.ofEpochMilli(requireNonNull(r.completedAtMillis())));
            }
            runsById.put(r.id(), run);
            runListModel.addElement(run);
            if (run.isRunning()) {
                lastRunningId = r.id();
            }
        }

        if (runListModel.getSize() > 0) {
            runList.setSelectedIndex(0);
            updateOutputForSelectedRun();
        } else {
            try {
                document.withWritePermission(() -> outputArea.setText(""));
            } catch (RuntimeException ex) {
                logger.warn("Failed to clear output during restore", ex);
            }
        }

        currentActiveRunId = lastRunningId;
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

        runOnEdt(() -> {
            boolean hasActive = false;
            if (currentActiveRunId != null) {
                var active = runsById.get(currentActiveRunId);
                hasActive = active != null && active.isRunning();
            }

            if (hasActive) {
                // Queue this run
                run.markQueued();
                runQueue.addLast(id);
                // Insert newest at top
                runListModel.add(0, run);
                // Enforce retention cap (drop oldest from bottom)
                while (runListModel.getSize() > maxRuns) {
                    int last = runListModel.getSize() - 1;
                    RunEntry removed = runListModel.remove(last);
                    runsById.remove(removed.id);
                }
                // Keep selection on the active run for clarity, unless it was just dropped
                if (currentActiveRunId != null) {
                    int idx = -1;
                    for (int i = 0; i < runListModel.size(); i++) {
                        if (runListModel.get(i).id.equals(currentActiveRunId)) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx >= 0) {
                        runList.setSelectedIndex(idx);
                    } else {
                        // The active run was dropped by retention
                        currentActiveRunId = null;
                        runList.setSelectedIndex(0); // Select the newest run instead
                    }
                }
            } else {
                // No active run -> start immediately
                currentActiveRunId = id;
                run.markRunning();
                // Insert newest at top
                runListModel.add(0, run);
                // Enforce retention cap (drop oldest from bottom)
                while (runListModel.getSize() > maxRuns) {
                    int last = runListModel.getSize() - 1;
                    RunEntry removed = runListModel.remove(last);
                    runsById.remove(removed.id);
                }
                // Select the active run and clear the output view
                runList.setSelectedIndex(0);
                try {
                    document.withWritePermission(() -> {
                        outputArea.setText("");
                        scrollToBottom();
                    });
                } catch (RuntimeException ex) {
                    logger.warn("Failed to initialize output area for new run", ex);
                }
            }

            // Persist after updating the UI/model
            triggerSave();
        });
        return id;
    }

    /** Append output to a specific run. */
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
                // Persist only when appending to the currently selected run to avoid excessive writes
                triggerSave();
            }
        });
    }

    /** Append output to the active run. If no active run exists, creates a generic one. */
    public void appendToActiveRun(String text) {
        if (text.isEmpty()) return;

        String runId = currentActiveRunId;
        if (runId == null || !runsById.containsKey(runId)) {
            // Create a generic "General Output" run
            runId = beginRun(0, "General Output", Instant.now());
        }
        appendToRun(runId, text);
    }

    /** Complete the run, setting exit code and completion time. */
    public void completeRun(String runId, int exitCode, Instant completedAt) {
        runOnEdt(() -> {
            var run = runsById.get(runId);
            if (run == null) {
                logger.warn("completeRun: unknown runId {}", runId);
                return;
            }

            run.complete(exitCode, completedAt);
            runList.repaint();

            // If we just completed the active run, promote the next queued run (if any)
            if (runId.equals(currentActiveRunId)) {
                currentActiveRunId = null;
                if (!runQueue.isEmpty()) {
                    String nextId = runQueue.removeFirst();
                    var next = runsById.get(nextId);
                    if (next != null) {
                        currentActiveRunId = nextId;
                        next.markRunning();
                        // Move selection to the newly active run and display its buffered output
                        int idx = -1;
                        for (int i = 0; i < runListModel.size(); i++) {
                            if (runListModel.get(i).id.equals(nextId)) {
                                idx = i;
                                break;
                            }
                        }
                        if (idx >= 0) {
                            runList.setSelectedIndex(idx);
                        }
                        try {
                            String text = next.getOutput();
                            document.withWritePermission(() -> {
                                outputArea.setText(text);
                                scrollToBottom();
                            });
                        } catch (RuntimeException ex) {
                            logger.warn("Failed to display output for promoted run {}", nextId, ex);
                        }
                    }
                }
            }

            // Persist updated completion state
            triggerSave();
        });
    }

    /**
     * Sets the maximum number of runs to retain in the list. If the new cap is lower than the current number of runs,
     * drops the oldest ones. Also triggers a save so persistence reflects the new cap.
     */
    public void setMaxRuns(int maxRuns) {
        int newCap = Math.max(1, maxRuns);
        if (this.maxRuns == newCap) {
            return;
        }
        this.maxRuns = newCap;

        runOnEdt(() -> {
            // Enforce retention cap immediately in memory (remove from bottom)
            while (runListModel.getSize() > this.maxRuns) {
                int last = runListModel.getSize() - 1;
                RunEntry removed = runListModel.remove(last);
                runsById.remove(removed.id);
            }

            // Keep selection on newest (top) if any runs remain; update output area
            if (runListModel.getSize() > 0) {
                runList.setSelectedIndex(0);
                updateOutputForSelectedRun();
            } else {
                try {
                    document.withWritePermission(() -> outputArea.setText(""));
                } catch (RuntimeException ex) {
                    logger.warn("Failed to clear output after maxRuns change", ex);
                }
            }

            // Persist after updating the UI/model
            triggerSave();
        });
    }

    public CompletableFuture<Void> awaitPersistenceCompletion() {
        return saveExecutor.awaitCompletion("test_runs_save");
    }

    /** Clear all runs and output. */
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
            // Persist cleared state
            triggerSave();
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

    private void runAllTests() {
        if (chrome == null) {
            logger.debug("Run All Tests clicked without Chrome context; ignoring.");
            return;
        }
        // Guard basic configuration
        var project = chrome.getProject();
        BuildAgent.BuildDetails details = project.awaitBuildDetails();
        if (details.equals(BuildAgent.BuildDetails.EMPTY)
                || details.testAllCommand().isBlank()) {
            chrome.toolError(
                    "No 'Test All Command' configured. Open Settings ▸ Build to configure it.", "Run All Tests");
            return;
        }

        String command = details.testAllCommand();
        executeTests(command, -1, details.environmentVariables());
    }

    public void runTests(Set<ProjectFile> testFiles) {
        if (chrome == null) {
            logger.debug("Run Tests clicked without Chrome context; ignoring.");
            return;
        }
        // Guard basic configuration
        var project = chrome.getProject();
        BuildAgent.BuildDetails details = project.awaitBuildDetails();
        if (details.equals(BuildAgent.BuildDetails.EMPTY)) {
            chrome.toolError("No build details configured. Open Settings ▸ Build to configure it.", "Run Tests");
            return;
        }

        String command = BuildAgent.getBuildLintSomeCommand(chrome.getContextManager(), details, testFiles);
        if (command.isBlank()) {
            chrome.toolError("Could not determine test command for the selected files.", "Run Tests");
            return;
        }

        executeTests(command, testFiles.size(), details.environmentVariables());
    }

    private void stopTests() {
        var process = activeTestProcess;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            appendToActiveRun("\n--- TEST EXECUTION CANCELLED BY USER ---\n");
        }
    }

    private void executeTests(String command, int fileCount, java.util.Map<String, String> environment) {
        if (chrome == null) {
            logger.warn("executeTests called without Chrome context; ignoring.");
            return;
        }
        if (!testProcessRunning.compareAndSet(false, true)) {
            chrome.toolError("A test process is already running.", "Test Runner");
            return;
        }
        runOnEdt(() -> {
            stopButton.setVisible(true);
            stopButton.setEnabled(true);
            Color stopColor = ThemeColors.getColor(false, ThemeColors.GIT_BADGE_BACKGROUND);
            stopButton.setBackground(stopColor);
        });

        String runId = beginRun(fileCount, command, Instant.now());
        var project = chrome.getProject();
        var cm = chrome.getContextManager();
        cm.submitBackgroundTask("Running tests", () -> {
            try {
                ExecutorConfig execCfg = ExecutorConfig.fromProject(project);

                Environment.instance.runShellCommand(
                        command,
                        project.getRoot(),
                        line -> appendToRun(runId, line + "\n"),
                        Environment.UNLIMITED_TIMEOUT,
                        execCfg,
                        environment,
                        process -> activeTestProcess = process);
                completeRun(runId, 0, Instant.now());
            } catch (Environment.SubprocessException e) {
                appendToRun(runId, "\n" + e.getMessage() + "\n" + e.getOutput() + "\n");
                completeRun(runId, -1, Instant.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Retain interrupted status
                appendToRun(runId, "\n--- TEST EXECUTION INTERRUPTED ---\n");
                completeRun(runId, -1, Instant.now());
            } catch (Exception e) {
                appendToRun(runId, "\nError: " + e + "\n");
                completeRun(runId, -1, Instant.now());
            } finally {
                testProcessRunning.set(false);
                activeTestProcess = null;
                runOnEdt(() -> {
                    stopButton.setEnabled(false);
                    stopButton.setVisible(false);
                    stopButton.setBackground(UIManager.getColor("Button.background"));
                });
            }
            return null;
        });
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
     * Compatibility API for legacy tests: update a TestEntry's status and timestamps. This panel is now run-centric,
     * but tests still validate timestamp behavior on TestEntry.
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
     * Factory for tests: ensures TestEntryRenderer is referenced by production code so Error Prone does not flag it as
     * UnusedNestedClass.
     */
    public static DefaultListCellRenderer newTestEntryRendererForTests() {
        return new TestEntryRenderer();
    }

    // ==========================
    // Internal model and classes
    // ==========================

    private static final class RunEntry {
        private static final int EXIT_CODE_UNKNOWN = Integer.MIN_VALUE;

        private enum RunState {
            QUEUED,
            RUNNING,
            COMPLETED
        }

        private final String id;
        private final int fileCount;
        private final String command;
        private final Instant startedAt;
        private volatile @Nullable Instant completedAt;
        private volatile int exitCode = EXIT_CODE_UNKNOWN;
        private volatile RunState state = RunState.RUNNING;

        private final StringBuilder output = new StringBuilder();

        RunEntry(String id, int fileCount, String command, Instant startedAt) {
            this.id = id;
            this.fileCount = fileCount;
            this.command = command;
            this.startedAt = startedAt;
        }

        void markQueued() {
            this.state = RunState.QUEUED;
        }

        void markRunning() {
            this.state = RunState.RUNNING;
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
            this.state = RunState.COMPLETED;
        }

        boolean isQueued() {
            return state == RunState.QUEUED;
        }

        boolean isRunning() {
            return state == RunState.RUNNING;
        }

        boolean isSuccess() {
            return state == RunState.COMPLETED && exitCode == 0;
        }

        long getDurationSeconds() {
            if (state == RunState.QUEUED) {
                return 0L;
            }
            Instant end = (completedAt != null) ? completedAt : Instant.now();
            return Math.max(0L, Duration.between(startedAt, end).toSeconds());
        }
    }

    private static class RunEntryRenderer extends DefaultListCellRenderer {
        private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (!(value instanceof RunEntry run)) {
                return label;
            }

            String icon = run.isQueued() ? "... " : (run.isRunning() ? "⟳ " : (run.isSuccess() ? "✓ " : "✗ "));
            // Start time HH:mm:ss (local tz)
            String timeText = TIME_FORMAT.format(run.startedAt.atZone(ZoneId.systemDefault()));
            // Files
            String filesText;
            if (run.fileCount < 0) {
                filesText = "all files";
            } else {
                filesText = run.fileCount == 1 ? "1 file" : (run.fileCount + " files");
            }
            // Duration mm:ss
            long secs = run.getDurationSeconds();
            String dur = "%02d:%02d".formatted(secs / 60, secs % 60);

            label.setText(icon + timeText + " • " + filesText + " • " + dur);
            label.setToolTipText(run.command);

            if (!isSelected) {
                Color statusColor = run.isQueued()
                        ? new Color(170, 170, 170)
                        : (run.isRunning()
                                ? new Color(100, 150, 255)
                                : (run.isSuccess() ? new Color(100, 200, 100) : new Color(255, 100, 100)));
                label.setForeground(statusColor);
            }

            return label;
        }
    }

    /**
     * Compatibility renderer for legacy tests that expect TestRunnerPanel$TestEntryRenderer. Renders TestEntry display
     * name with a timestamp suffix and sets tooltip to ISO-8601 instant. Prefers completedAt; falls back to startedAt;
     * omits time if both are null.
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
