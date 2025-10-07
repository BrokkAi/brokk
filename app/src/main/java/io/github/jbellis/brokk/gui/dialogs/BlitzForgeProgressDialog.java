package io.github.jbellis.brokk.gui.dialogs;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.BlitzForge;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.cli.MemoryConsole;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.ContentDiffUtils;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * UI-only progress dialog for BlitzForge runs. Implements BlitzForge.Listener and renders progress.
 * No execution logic lives here; the engine invokes these callbacks from worker threads.
 *
 * New UI:
 * - Queued table (1 column: File)
 * - In-progress table (2 columns: File, Progress (per-file progress bar))
 * - Completed table (2 columns: File, Lines changed)
 */
public final class BlitzForgeProgressDialog extends JDialog implements BlitzForge.Listener {

    public enum PostProcessingOption {
        NONE,
        ASK,
        ARCHITECT
    }

    private static final Logger logger = LogManager.getLogger(BlitzForgeProgressDialog.class);

    private final Chrome chrome;
    private final Runnable cancelCallback;
    private final AtomicBoolean done = new AtomicBoolean(false);

    // UI components: two tables (In Progress and Completed)
    private final JTable inProgressTable;
    private final JTable completedTable;

    private final InProgressTableModel inProgressModel = new InProgressTableModel();
    private final CompletedTableModel completedModel = new CompletedTableModel();

    // Dynamic titled borders and panels for updating counts
    private final TitledBorder inProgressBorder;
    private final TitledBorder completedBorder;
    private final JPanel inProgressPanel;
    private final JPanel completedPanel;

    // Totals
    private int totalFiles = 0;

    // Per-file console instances and original content to compute diffs
    private final Map<ProjectFile, DialogConsoleIO> consolesByFile = new ConcurrentHashMap<>();
    private final Map<ProjectFile, String> originalContentByFile = new ConcurrentHashMap<>();
    // Snapshots captured at completion time for accurate diff previews
    private final Map<ProjectFile, String> completedOriginalByFile = new ConcurrentHashMap<>();
    private final Map<ProjectFile, String> completedUpdatedByFile = new ConcurrentHashMap<>();


    public BlitzForgeProgressDialog(Chrome chrome, Runnable cancelCallback) {
        super(chrome.getFrame(), "BlitzForge Progress", false);
        assert SwingUtilities.isEventDispatchThread() : "Must construct dialog on EDT";

        this.chrome = chrome;
        this.cancelCallback = cancelCallback;

        chrome.disableActionButtons();

        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(900, 600));

        // In-progress table with progress bar renderer
        inProgressTable = new JTable(inProgressModel);
        inProgressTable.setFillsViewportHeight(true);
        inProgressTable.getColumnModel()
                .getColumn(1)
                .setCellRenderer(new ProgressBarRenderer());

        // Completed table
        completedTable = new JTable(completedModel);
        completedTable.setFillsViewportHeight(true);
        completedTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 2) return;
                int viewRow = completedTable.rowAtPoint(e.getPoint());
                if (viewRow < 0) return;
                openDiffForRow(viewRow);
            }
        });

        // Panels with dynamic titled borders
        var centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

        inProgressPanel = new JPanel(new BorderLayout());
        inProgressBorder = BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "In Progress (0/0)",
                TitledBorder.LEFT,
                TitledBorder.TOP
        );
        inProgressPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(10, 10, 0, 10), inProgressBorder));
        inProgressPanel.add(new JScrollPane(inProgressTable), BorderLayout.CENTER);

        completedPanel = new JPanel(new BorderLayout());
        completedBorder = BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Completed (0/0)",
                TitledBorder.LEFT,
                TitledBorder.TOP
        );
        completedPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(10, 10, 0, 10), completedBorder));
        completedPanel.add(new JScrollPane(completedTable), BorderLayout.CENTER);

        centerPanel.add(inProgressPanel);
        centerPanel.add(Box.createVerticalStrut(8));
        centerPanel.add(completedPanel);

        add(centerPanel, BorderLayout.CENTER);

        // Buttons
        var cancelButton = new MaterialButton("Cancel");
        cancelButton.addActionListener(e -> {
            if (!done.get()) {
                try {
                    this.cancelCallback.run();
                } catch (Exception ex) {
                    logger.warn("Cancel callback threw", ex);
                }
            } else {
                setVisible(false);
            }
        });

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        add(buttonPanel, BorderLayout.SOUTH);

        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                if (!done.get()) {
                    int choice = chrome.showConfirmDialog(
                            BlitzForgeProgressDialog.this,
                            "Are you sure you want to cancel the upgrade process?",
                            "Confirm Cancel",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    if (choice == JOptionPane.YES_OPTION) {
                        try {
                            BlitzForgeProgressDialog.this.cancelCallback.run();
                        } catch (Exception ex) {
                            logger.warn("Cancel callback threw", ex);
                        }
                    }
                } else {
                    setVisible(false);
                }
            }
        });

        pack();
        setLocationRelativeTo(chrome.getFrame());
    }


    private static int countLines(String text) {
        if (text.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0, n = text.length(); i < n; i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    

    @Override
    public void onStart(int total) {
        SwingUtilities.invokeLater(() -> {
            totalFiles = total;
            updateTitles();
        });
    }

    @Override
    public void onQueued(List<ProjectFile> queued) {
        // No queued table; nothing to render here.
    }

    @Override
    public void onFileStart(ProjectFile file) {
        // Initialize per-file totals and add to in-progress
        var original = file.read().orElse("");
        int totalLoc = Math.max(1, countLines(original)); // avoid division by zero
        originalContentByFile.put(file, original);

        SwingUtilities.invokeLater(() -> {
            inProgressModel.add(file, totalLoc);
            updateTitles();
        });
    }

    @Override
    public DialogConsoleIO getConsoleIO(ProjectFile file) {
        // Provide a per-file console that increments per-file progress on newline tokens
        return consolesByFile.computeIfAbsent(file, DialogConsoleIO::new);
    }

    @Override
    public void onFileResult(ProjectFile file, boolean edited, @Nullable String errorMessage, String llmOutput) {
        // Move from in-progress to completed and compute added/deleted using ContentDiffUtils
        int added = 0;
        int deleted = 0;
        try {
            var original = originalContentByFile.getOrDefault(file, "");
            var after = file.read().orElse("");
            // Snapshot the contents used for the Completed table diff preview
            completedOriginalByFile.put(file, original);
            completedUpdatedByFile.put(file, after);

            var pathDisplay = file.toString();
            var diffResult = ContentDiffUtils.computeDiffResult(
                    original, after,
                    pathDisplay + " (before)",
                    pathDisplay + " (after)"
            );
            added = diffResult.added();
            deleted = diffResult.deleted();
        } catch (Exception e) {
            logger.warn("Failed to compute added/deleted for {}", file, e);
            added = 0;
            deleted = 0;
        } finally {
            originalContentByFile.remove(file);
        }

        final int addedFinal = added;
        final int deletedFinal = deleted;
        SwingUtilities.invokeLater(() -> {
            inProgressModel.remove(file);
            completedModel.add(file, addedFinal, deletedFinal);
            updateTitles();
        });

        if (errorMessage != null && !errorMessage.isBlank()) {
            chrome.toolError(errorMessage, "Processing Error");
        }
    }

    @Override
    public void onComplete(TaskResult result) {
        SwingUtilities.invokeLater(() -> {
            try {
                done.set(true);
                updateTitles();
            } finally {
                chrome.enableActionButtons();
            }
        });
    }

    private void openDiffForRow(int viewRow) {
        int modelRow = completedTable.convertRowIndexToModel(viewRow);
        var file = completedModel.getFileAt(modelRow);
        openDiffForFile(file);
    }

    private void openDiffForFile(ProjectFile file) {
        try {
            var left = completedOriginalByFile.get(file);
            var right = completedUpdatedByFile.get(file);
            if (left == null && right == null) {
                // Fall back to current state if snapshots are missing
                left = "";
                right = file.read().orElse("");
            }
            String pathDisplay = file.toString();

            var cm = chrome.getContextManager();
            var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), cm)
                    .setMultipleCommitsContext(false)
                    .setRootTitle("Diff: " + pathDisplay)
                    .setInitialFileIndex(0);

            var leftSrc = new BufferSource.StringSource(left == null ? "" : left, "Previous", pathDisplay);
            var rightSrc = new BufferSource.StringSource(right == null ? "" : right, "Current", pathDisplay);
            builder.addComparison(leftSrc, rightSrc);

            var panel = builder.build();
            panel.showInFrame("Diff: " + pathDisplay);
        } catch (Exception ex) {
            logger.warn("Failed to open diff for {}", file, ex);
            SwingUtilities.invokeLater(() ->
                    chrome.toolError("Failed to open diff: " + ex.getMessage(), "Diff Error"));
        }
    }

    private void updateTitles() {
        assert SwingUtilities.isEventDispatchThread();
        int completedCount = completedModel.size();
        int inProgressCount = inProgressModel.size();
        int notComplete = Math.max(0, totalFiles - completedCount);

        inProgressBorder.setTitle("In Progress (" + inProgressCount + "/" + notComplete + ")");
        completedBorder.setTitle("Completed (" + completedCount + "/" + totalFiles + ")");

        inProgressPanel.revalidate();
        inProgressPanel.repaint();
        completedPanel.revalidate();
        completedPanel.repaint();
    }

    // Console used by the processing engine to stream LLM output per file
    public class DialogConsoleIO extends MemoryConsole {
        private final ProjectFile file;

        private DialogConsoleIO(ProjectFile file) {
            this.file = file;
        }

        public String getLlmOutput() {
            return getLlmRawMessages().stream().map(Messages::getText).collect(Collectors.joining("\n"));
        }

        @Override
        public void toolError(String message, String title) {
            var msg = "[%s] %s: %s".formatted(file, title, message);
            logger.error(msg);
            SwingUtilities.invokeLater(() -> chrome.toolError(message, title));
        }

        @Override
        public void llmOutput(String token, ChatMessageType type, boolean explicitNewMessage, boolean isReasoning) {
            super.llmOutput(token, type, explicitNewMessage, isReasoning);
            long newLines = token.chars().filter(c -> c == '\n').count();
            if (newLines > 0) {
                // Increment per-file progress
                SwingUtilities.invokeLater(() -> inProgressModel.incrementReceived(file, (int) newLines));
            }
        }

        @Override
        public void systemNotify(String message, String title, int messageType) {
            // Route system notifications through the main chrome
            SwingUtilities.invokeLater(() -> chrome.systemNotify(message, title, messageType));
        }
    }

    // Models and renderers


    private static final class InProgressRow {
        final ProjectFile file;
        final int totalLines;
        int receivedLines;

        InProgressRow(ProjectFile file, int totalLines) {
            this.file = file;
            this.totalLines = totalLines;
            this.receivedLines = 0;
        }
    }

    private static final class InProgressTableModel extends AbstractTableModel {
        private final List<InProgressRow> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "File";
                case 1 -> "Progress";
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 1 ? InProgressRow.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.file.toString();
                case 1 -> row;
                default -> "";
            };
        }

        void add(ProjectFile file, int totalLines) {
            var row = new InProgressRow(file, totalLines);
            int idx = rows.size();
            rows.add(row);
            fireTableRowsInserted(idx, idx);
        }

        void incrementReceived(ProjectFile file, int delta) {
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                if (row.file.equals(file)) {
                    row.receivedLines = Math.min(row.totalLines, row.receivedLines + Math.max(0, delta));
                    fireTableCellUpdated(i, 1);
                    return;
                }
            }
        }

        int size() {
            return rows.size();
        }

        void remove(ProjectFile file) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).file.equals(file)) {
                    rows.remove(i);
                    fireTableRowsDeleted(i, i);
                    return;
                }
            }
        }
    }

    private static final class ProgressBarRenderer extends JProgressBar implements TableCellRenderer {
        ProgressBarRenderer() {
            super(0, 100);
            setStringPainted(false);
            setBorderPainted(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof InProgressRow r) {
                int total = Math.max(1, r.totalLines);
                int received = Math.max(0, Math.min(r.receivedLines, total));
                int percent = (int) Math.round(received * 100.0 / total);
                setMinimum(0);
                setMaximum(100);
                setValue(percent);
            } else {
                setMinimum(0);
                setMaximum(100);
                setValue(0);
            }
            if (isSelected) {
            setBackground(table.getSelectionBackground());
        } else {
            setBackground(table.getBackground());
        }
        // Shrink the painted bar to 50% of the row height, centered vertically
        int rowHeight = table.getRowHeight(row);
        int desiredHeight = Math.max(4, rowHeight / 2);
        int pad = Math.max(0, (rowHeight - desiredHeight) / 2);
        setBorder(BorderFactory.createEmptyBorder(pad, 0, pad, 0));
        return this;
        }
    }

    private static final class CompletedRow {
        final ProjectFile file;
        final int added;
        final int deleted;

        CompletedRow(ProjectFile file, int added, int deleted) {
            this.file = file;
            this.added = Math.max(0, added);
            this.deleted = Math.max(0, deleted);
        }
    }

    private static final class CompletedTableModel extends AbstractTableModel {
        private final List<CompletedRow> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "File";
                case 1 -> "Lines changed";
                default -> "";
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.file.toString();
                case 1 -> "+%d -%d".formatted(row.added, row.deleted);
                default -> "";
            };
        }

        ProjectFile getFileAt(int rowIndex) {
            return rows.get(rowIndex).file;
        }

        void add(ProjectFile file, int added, int deleted) {
            var row = new CompletedRow(file, added, deleted);
            int idx = rows.size();
            rows.add(row);
            fireTableRowsInserted(idx, idx);
        }

        int size() {
            return rows.size();
        }
    }
}
