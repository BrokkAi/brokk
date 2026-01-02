package ai.brokk.gui.tests;

import static java.util.Objects.requireNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragments;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.InstructionsPanel;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.util.Environment;
import ai.brokk.util.ExecutorConfig;
import ai.brokk.util.SerialByKeyExecutor;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
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

    private final Chrome chrome;
    private final MaterialButton runAllButton = new MaterialButton();
    private final MaterialButton stopButton = new MaterialButton();
    private final MaterialButton clearAllButton = new MaterialButton();
    private final AtomicBoolean testProcessRunning = new AtomicBoolean(false);
    private volatile @Nullable Process activeTestProcess;

    // Model
    private final DefaultListModel<CompletedEntry> runListModel;
    private final JList<CompletedEntry> runList;
    private final JScrollPane runListScrollPane;

    // Output
    private final JTextArea streamingOutputArea;
    private final JScrollPane streamingOutputScrollPane;
    private final JLabel runningLabel = new JLabel();

    // Current active run (where live output goes)
    private volatile @Nullable RunEntry currentRun;

    // Maximum number of runs to retain
    private int maxRuns = 50;
    private final TestRunsStore runsStore;
    private final ExecutorService sessionExecutor = Executors.newFixedThreadPool(2);
    private final SerialByKeyExecutor saveExecutor = new SerialByKeyExecutor(sessionExecutor);

    // Limit stored output size to avoid unbounded JSON growth
    private static final int MAX_SNAPSHOT_OUTPUT_CHARS = 200_000;

    // Fix button width in the run list renderer
    private static final int FIX_BUTTON_WIDTH_PX = 30;

    // Session name truncation constants
    private static final int MAX_COMMAND_LABEL_LEN = 40; // max length for command segment before ellipsis
    private static final int ELLIPSIS_LEN = 3;

    public TestRunnerPanel(Chrome chrome, TestRunsStore runsStore) {
        super(new BorderLayout(0, 0));
        this.chrome = chrome;
        this.runsStore = runsStore;
        runListModel = new DefaultListModel<>();

        runList = new JList<>(runListModel) {
            @Override
            public @Nullable String getToolTipText(java.awt.event.MouseEvent e) {
                int index = locationToIndex(e.getPoint());
                if (index < 0) return null;
                java.awt.Rectangle cellBounds = getCellBounds(index, index);
                if (cellBounds == null) return null;

                CompletedEntry run = runListModel.get(index);
                if (run.isFailed()) {
                    int buttonX = cellBounds.x + cellBounds.width - FIX_BUTTON_WIDTH_PX;
                    if (e.getX() >= buttonX) {
                        return "Fix this failing test with Lutz Mode";
                    }
                }
                return run.command;
            }
        };
        runList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        runList.setCellRenderer(new CompletedEntryRenderer());
        runList.setVisibleRowCount(5);
        runList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                CompletedEntry selected = runList.getSelectedValue();
                if (selected != null) {
                    chrome.getPreviewManager().openFragmentPreview(selected.output());
                }
            }
        });

        runList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = runList.locationToIndex(e.getPoint());
                if (index < 0) return;

                CompletedEntry run = runListModel.get(index);
                if (!run.isFailed()) return;

                java.awt.Rectangle cellBounds = runList.getCellBounds(index, index);
                if (cellBounds == null) return;

                int buttonX = cellBounds.x + cellBounds.width - FIX_BUTTON_WIDTH_PX;
                if (e.getX() >= buttonX) {
                    fixFailedRun(run);
                }
            }
        });

        javax.swing.ToolTipManager.sharedInstance().registerComponent(runList);

        runListScrollPane = new JScrollPane(
                runList, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        runListScrollPane.setBorder(BorderFactory.createEmptyBorder());
        runListScrollPane.setMinimumSize(new Dimension(100, 60));
        runListScrollPane.setPreferredSize(new Dimension(100, 150));

        // Title and toolbar (similar to TaskListPanel)
        setBorder(BorderFactory.createTitledBorder("Tests"));
        var border = getBorder();
        if (border != null) {
            var insets = border.getBorderInsets(this);
            setMinimumSize(new Dimension(100, insets.top + insets.bottom));
        }
        var topToolbar = new JPanel(new BorderLayout());
        topToolbar.setOpaque(false);

        var leftToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftToolbar.setOpaque(false);
        runAllButton.setIcon(Icons.PLAY);
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

        clearAllButton.setIcon(Icons.CLEAR_ALL);
        clearAllButton.setMargin(new Insets(0, 0, 0, 0));
        clearAllButton.setToolTipText("Clear all test runs.");
        clearAllButton.addActionListener(e -> clearAllRuns());
        leftToolbar.add(clearAllButton);

        topToolbar.add(leftToolbar, BorderLayout.WEST);

        add(topToolbar, BorderLayout.NORTH);

        streamingOutputArea = new JTextArea();
        streamingOutputArea.setEditable(false);
        streamingOutputArea.setLineWrap(true);
        streamingOutputArea.setWrapStyleWord(true);
        streamingOutputArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        Font base = UIManager.getFont("TextArea.font");
        if (base == null) base = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, base.getSize());
        streamingOutputArea.setFont(mono);

        streamingOutputScrollPane = new JScrollPane(
                streamingOutputArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        streamingOutputScrollPane.setBorder(BorderFactory.createEmptyBorder());
        streamingOutputScrollPane.setPreferredSize(new Dimension(100, 150));
        streamingOutputScrollPane.setVisible(false);

        runningLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        runningLabel.setVisible(false);

        JPanel centerPanel = new JPanel(new BorderLayout());
        JPanel topCenter = new JPanel(new BorderLayout());
        topCenter.add(runningLabel, BorderLayout.NORTH);
        topCenter.add(streamingOutputScrollPane, BorderLayout.CENTER);

        centerPanel.add(topCenter, BorderLayout.NORTH);
        centerPanel.add(runListScrollPane, BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);

        applyThemeColorsFromUIManager();

        // Load persisted runs (per-project) if available
        try {
            List<TestRunsStore.Run> records = runsStore.load();
            if (!records.isEmpty()) {
                restoreRuns(records);
            }
        } catch (Exception e) {
            logger.warn("Failed to load persisted test runs: {}", e.getMessage(), e);
        }

        // Initialize Run All button state and enable asynchronously once build details are available
        runAllButton.setEnabled(false);
        // Enable/disable Run All based on current build details availability
        chrome.getProject()
                .getBuildDetailsFuture()
                .thenAccept((details) -> {
                    SwingUtilities.invokeLater(() -> {
                        boolean validDetails = !details.equals(BuildAgent.BuildDetails.EMPTY)
                                && !details.testAllCommand().isBlank();
                        runAllButton.setEnabled(validDetails);
                    });
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> runAllButton.setEnabled(false));
                    logger.error("Failed to load build details for Run All button: {}", ex.getMessage(), ex);
                    return null;
                });
        updateClearButtonTooltip();
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
    public List<TestRunsStore.Run> snapshotRuns(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return snapshotRunsFromModel(limit);
        }
        var ref = new AtomicReference<List<TestRunsStore.Run>>(List.of());
        try {
            SwingUtilities.invokeAndWait(() -> ref.set(snapshotRunsFromModel(limit)));
        } catch (Exception e) {
            logger.warn("Failed to snapshot runs on EDT: {}", e.getMessage(), e);
        }
        return requireNonNull(ref.get());
    }

    private List<TestRunsStore.Run> snapshotRunsFromModel(int limit) {
        int size = runListModel.getSize();
        if (size == 0) {
            return List.of();
        }
        int count = Math.min(limit, size);
        var out = new ArrayList<TestRunsStore.Run>(count);
        for (int i = 0; i < count; i++) {
            var entry = runListModel.get(i);
            String output = entry.output().previewText();
            if (output.length() > MAX_SNAPSHOT_OUTPUT_CHARS) {
                int keep = Math.max(0, MAX_SNAPSHOT_OUTPUT_CHARS - 3);
                output = output.substring(0, keep) + "...";
            }
            out.add(new TestRunsStore.Run(
                    UUID.randomUUID().toString(),
                    entry.fileCount(),
                    entry.command(),
                    entry.startedAt().toEpochMilli(),
                    entry.completedAt().toEpochMilli(),
                    entry.exitCode(),
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
            List<TestRunsStore.Run> snapshot;
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
    public void restoreRuns(List<TestRunsStore.Run> records) {
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

    private void doRestore(List<TestRunsStore.Run> records) {
        int count = Math.min(records.size(), maxRuns);
        List<TestRunsStore.Run> slice = records.subList(0, count);

        runListModel.clear();

        IContextManager cm = chrome.getContextManager();

        for (var r : slice) {
            var fragment = new ContextFragments.StringFragment(
                    cm, r.output(), "Test Output", SyntaxConstants.SYNTAX_STYLE_NONE);
            var entry = new CompletedEntry(
                    r.fileCount(),
                    r.command(),
                    Instant.ofEpochMilli(r.startedAtMillis()),
                    Instant.ofEpochMilli(requireNonNull(r.completedAtMillis())),
                    r.exitCode(),
                    fragment);
            runListModel.addElement(entry);
        }

        if (runListModel.getSize() > 0) {
            runList.setSelectedIndex(0);
        }
        updateClearButtonTooltip();
    }

    public void beginRun(int fileCount, String command, Instant startedAt) {
        var run = new RunEntry(fileCount, command, startedAt);
        currentRun = run;

        runOnEdt(() -> {
            runningLabel.setText("Running tests: " + withEllipsis(command, 50));
            runningLabel.setVisible(true);
            streamingOutputArea.setText("");
            streamingOutputScrollPane.setVisible(true);
            updateClearButtonTooltip();
        });
    }

    public void appendToActiveRun(String text) {
        if (text.isEmpty()) return;
        var run = currentRun;
        if (run == null) {
            beginRun(0, "General Output", Instant.now());
            run = currentRun;
        }
        if (run != null) {
            run.appendOutput(text);
        }

        runOnEdt(() -> {
            streamingOutputArea.append(text);
            streamingOutputArea.setCaretPosition(
                    streamingOutputArea.getDocument().getLength());
        });
    }

    public void completeRun(int exitCode, Instant completedAt) {
        runOnEdt(() -> {
            var run = currentRun;
            if (run == null) return;
            currentRun = null;

            runningLabel.setVisible(false);
            streamingOutputScrollPane.setVisible(false);

            ContextFragments.StringFragment fragment = new ContextFragments.StringFragment(
                    chrome.getContextManager(),
                    run.getOutput(),
                    "Test Output",
                    SyntaxConstants.SYNTAX_STYLE_NONE,
                    Set.of());
            var entry = new CompletedEntry(run.fileCount, run.command, run.startedAt, completedAt, exitCode, fragment);

            runListModel.add(0, entry);
            while (runListModel.getSize() > maxRuns) {
                runListModel.remove(runListModel.getSize() - 1);
            }
            runList.setSelectedIndex(0);

            chrome.getPreviewManager().openFragmentPreview(fragment);

            triggerSave();
            updateClearButtonTooltip();
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
            while (runListModel.getSize() > this.maxRuns) {
                runListModel.remove(runListModel.getSize() - 1);
            }
            if (runListModel.getSize() > 0) {
                runList.setSelectedIndex(0);
            }

            // Persist after updating the UI/model
            triggerSave();
        });
    }

    public CompletableFuture<Void> awaitPersistenceCompletion() {
        return saveExecutor.awaitCompletion("test_runs_save");
    }

    /** Clear runs. */
    public void clearAllRuns() {
        runOnEdt(() -> {
            runListModel.clear();
            triggerSave();
            updateClearButtonTooltip();
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

    private void runAllTests() {
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

    public void runTests(Set<ProjectFile> testFiles) throws InterruptedException {
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

    private void executeTests(String command, int fileCount, Map<String, String> environment) {
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

        beginRun(fileCount, command, Instant.now());
        var project = chrome.getProject();
        var cm = chrome.getContextManager();
        cm.submitBackgroundTask("Running tests", () -> {
            int exitCode = -1;
            try {
                ExecutorConfig execCfg = ExecutorConfig.fromProject(project);

                Environment.instance.runShellCommand(
                        command,
                        project.getRoot(),
                        line -> appendToActiveRun(line + "\n"),
                        Environment.UNLIMITED_TIMEOUT,
                        execCfg,
                        environment,
                        process -> activeTestProcess = process);
                exitCode = 0;
            } catch (Environment.SubprocessException e) {
                appendToActiveRun("\n" + e.getMessage() + "\n" + e.getOutput() + "\n");
                exitCode = -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                appendToActiveRun("\n--- TEST EXECUTION INTERRUPTED ---\n");
                exitCode = -1;
            } catch (Exception e) {
                appendToActiveRun("\nError: " + e + "\n");
                exitCode = -1;
            } finally {
                var runOutput = currentRun != null ? currentRun.getOutput() : "";
                boolean success = (exitCode == 0);
                completeRun(exitCode, Instant.now());
                cm.pushContext(ctx -> ctx.withBuildResult(success, runOutput));

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
        streamingOutputArea.setBackground(bg);
        streamingOutputArea.setForeground(fg);
        streamingOutputArea.setCaretColor(fg);

        runList.setBackground(bg);
        runList.setForeground(fg);

        revalidate();
        repaint();
    }

    private void updateClearButtonTooltip() {
        runOnEdt(() -> {
            clearAllButton.setToolTipText("Clear all test runs.");
        });
    }

    private void fixFailedRun(CompletedEntry run) {
        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Starting fix for failed tests...");

        String output = run.output().previewText();
        String sessionName = "Fix: " + withEllipsis(run.command(), MAX_COMMAND_LABEL_LEN);

        var cm = chrome.getContextManager();

        cm.createSessionAsync(sessionName)
                .thenRun(() -> {
                    SwingUtilities.invokeLater(() -> {
                        JTabbedPane rightTabs = chrome.getCommandPane();
                        int idx = rightTabs.indexOfTab("Instructions");
                        if (idx != -1) {
                            rightTabs.setSelectedIndex(idx);
                        }

                        cm.addPastedTextFragment(output);

                        InstructionsPanel instructionsPanel = chrome.getInstructionsPanel();
                        String instruction =
                                "Analyze the failing test output and fix the issue. The problem may be in the test code or the tested code. Explain what's wrong before making changes.";
                        instructionsPanel.populateInstructionsArea(
                                instruction, () -> instructionsPanel.runSearchCommand());
                    });
                })
                .exceptionally(ex -> {
                    logger.error("Failed to create session for fix workflow", ex);
                    return null;
                });
    }

    private static String withEllipsis(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        int cut = Math.max(0, maxLen - ELLIPSIS_LEN);
        return s.substring(0, cut) + "...";
    }

    private record CompletedEntry(
            int fileCount,
            String command,
            Instant startedAt,
            Instant completedAt,
            int exitCode,
            ContextFragments.StringFragment output) {
        boolean isSuccess() {
            return exitCode == 0;
        }

        boolean isFailed() {
            return exitCode != 0;
        }

        long getDurationSeconds() {
            return Math.max(0L, Duration.between(startedAt, completedAt).toSeconds());
        }
    }

    private static final class RunEntry {
        final int fileCount;
        final String command;
        final Instant startedAt;
        private final StringBuilder output = new StringBuilder();

        RunEntry(int fileCount, String command, Instant startedAt) {
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
    }

    private class CompletedEntryRenderer extends DefaultListCellRenderer {
        private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (!(value instanceof CompletedEntry run)) {
                return label;
            }

            String icon = run.isSuccess() ? "✓ " : "✗ ";
            String timeText = TIME_FORMAT.format(run.startedAt().atZone(ZoneId.systemDefault()));
            String filesText = run.fileCount() < 0
                    ? "all files"
                    : (run.fileCount() == 1 ? "1 file" : (run.fileCount() + " files"));
            long secs = run.getDurationSeconds();
            String dur = "%02d:%02d".formatted(secs / 60, secs % 60);

            label.setText(icon + timeText + " • " + filesText + " • " + dur);
            label.setToolTipText(run.command());

            if (!isSelected) {
                Color statusColor = run.isSuccess() ? new Color(100, 200, 100) : new Color(255, 100, 100);
                label.setForeground(statusColor);
            }

            if (run.isFailed()) {
                JPanel panel = new JPanel(new BorderLayout(4, 0));
                panel.setOpaque(true);
                panel.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
                label.setOpaque(false);
                panel.add(label, BorderLayout.CENTER);

                JButton fixButton = new JButton(Icons.WAND);
                fixButton.setToolTipText("Fix this failing test with Lutz Mode");
                fixButton.setMargin(new Insets(0, 2, 0, 2));
                fixButton.setBorderPainted(false);
                fixButton.setContentAreaFilled(false);
                fixButton.setFocusPainted(false);
                fixButton.setOpaque(false);
                panel.add(fixButton, BorderLayout.EAST);
                return panel;
            }

            return label;
        }
    }
}
