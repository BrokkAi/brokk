package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflowService;
import io.github.jbellis.brokk.gui.widgets.FileStatusTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Panel for the "Changes" tab (formerly "Commit" tab) in the Git Panel.
 * Handles displaying uncommitted changes, staging, committing, and stashing.
 */
public class GitCommitTab extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitCommitTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel; // Reference to parent GitPanel
    private final GitWorkflowService workflowService;

    // Commit tab UI
    private JTable uncommittedFilesTable; // Initialized via fileStatusPane
    private FileStatusTable fileStatusPane;
    private JButton commitButton;
    private JButton stashButton;
    @Nullable
    private ProjectFile rightClickedFile = null; // Store the file that was right-clicked

    public GitCommitTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.gitPanel = gitPanel; // Store reference to parent
        this.workflowService = new GitWorkflowService(contextManager);
        buildCommitTabUI();
    }

    /**
     * Builds the Changes tab UI elements.
     */
    private void buildCommitTabUI()
    {
        // Table for uncommitted files
        fileStatusPane = new FileStatusTable();
        uncommittedFilesTable = fileStatusPane.getTable();
        // JTable already obtained above from FileStatusTable
        // Renderer, row height, and column widths are now handled by FileStatusTable.
        uncommittedFilesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Double-click => show diff
        uncommittedFilesTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = uncommittedFilesTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        // keep the visual feedback
                        uncommittedFilesTable.setRowSelectionInterval(row, row);
                        var clickedFile = (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
                        openDiffForAllUncommittedFiles(clickedFile);
                    }
                }
            }
        });

        // Popup menu
        var uncommittedContextMenu = new JPopupMenu();
        uncommittedFilesTable.setComponentPopupMenu(uncommittedContextMenu);

        var captureDiffItem = new JMenuItem("Capture Diff");
        uncommittedContextMenu.add(captureDiffItem);

        var viewDiffItem = new JMenuItem("View Diff (All Files)");
        uncommittedContextMenu.add(viewDiffItem);

        var editFileItem = new JMenuItem("Edit File(s)");
        uncommittedContextMenu.add(editFileItem);

        // Add "View History" item
        var viewHistoryItem = new JMenuItem("View History"); // Declare the variable
        uncommittedContextMenu.add(viewHistoryItem);

        // Add "Rollback Changes" item
        var rollbackChangesItem = new JMenuItem("Rollback Changes");
        uncommittedContextMenu.add(rollbackChangesItem);

        // Select row under right-click
        uncommittedContextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    var point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, uncommittedFilesTable);
                    int row = uncommittedFilesTable.rowAtPoint(point);
                    if (row >= 0) {
                        // Store the right-clicked file for later use
                        rightClickedFile = (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
                        if (!uncommittedFilesTable.isRowSelected(row)) {
                            uncommittedFilesTable.setRowSelectionInterval(row, row);
                        }
                    } else {
                        rightClickedFile = null;
                    }
                    // Update menu items
                    updateUncommittedContextMenuState(captureDiffItem, viewDiffItem, editFileItem, viewHistoryItem, rollbackChangesItem);
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                 // Reset rightClickedFile if menu is cancelled
                rightClickedFile = null;
            }
        });

        // Context menu actions:
        viewDiffItem.addActionListener(e -> {
            // Use the stored right-clicked file as priority
            openDiffForAllUncommittedFiles(rightClickedFile);
            rightClickedFile = null; // Clear after use
        });

        captureDiffItem.addActionListener(e -> {
            // Unified call:
            var selectedFiles = getSelectedFilesFromTable();
            GitUiUtil.captureUncommittedDiff(contextManager, chrome, selectedFiles);
        });

        editFileItem.addActionListener(e -> {
            var selectedProjectFiles = getSelectedFilesFromTable();
            contextManager.editFiles(selectedProjectFiles);
            rightClickedFile = null; // Clear after use
        });

        // Add action listener for the view history item
        viewHistoryItem.addActionListener(e -> {
            // int row = uncommittedFilesTable.getSelectedRow(); // Using rightClickedFile instead
            if (rightClickedFile != null) {
                gitPanel.addFileHistoryTab(rightClickedFile);
            } else { // Fallback to selection if rightClickedFile is somehow null
                int row = uncommittedFilesTable.getSelectedRow();
                if (row >= 0) {
                    var projectFile = (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
                    gitPanel.addFileHistoryTab(projectFile);
                }
            }
            rightClickedFile = null; // Clear after use
        });

        // Add action listener for the rollback changes item
        rollbackChangesItem.addActionListener(e -> {
            var selectedFiles = getSelectedFilesFromTable();
            rollbackChangesWithUndo(selectedFiles);
        });

        // Selection => update context menu item states
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateUncommittedContextMenuState(captureDiffItem, viewDiffItem, editFileItem, viewHistoryItem, rollbackChangesItem);
            }
        });

        // FileStatusTable is itself a JScrollPane
        add(fileStatusPane, BorderLayout.CENTER);

        // Bottom panel for buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        // Stash Button
        stashButton = new JButton("Stash All"); // Default label
        stashButton.setToolTipText("Save your changes to the stash");
        stashButton.setEnabled(false);
        stashButton.addActionListener(e -> {
            List<ProjectFile> selectedFiles = getSelectedFilesFromTable();
            // Stash without asking for a message, using a default one.
            String stashMessage = "Stash created by Brokk";
            contextManager.submitUserTask("Stashing changes", () -> {
                try {
                    performStash(selectedFiles, stashMessage);
                } catch (GitAPIException ex) {
                    logger.error("Error stashing changes:", ex);
                    SwingUtilities.invokeLater(() -> chrome.toolError("Error stashing changes: " + ex.getMessage(), "Stash Error"));
                }
            });
        });
        buttonPanel.add(stashButton);

        // Commit Button
        commitButton = new JButton("Commit All..."); // Default label with ellipsis
        commitButton.setToolTipText("Commit files...");
        commitButton.setEnabled(false);
        commitButton.addActionListener(e -> {
            List<ProjectFile> filesToCommit;
            if (uncommittedFilesTable.getSelectedRowCount() > 0) {
                filesToCommit = getSelectedFilesFromTable();
            } else {
                filesToCommit = getAllFilesFromTable();
            }

            if (filesToCommit.isEmpty()) {
                chrome.toolError("No files to commit.", "Commit Error");
                return;
            }

            CommitDialog dialog = new CommitDialog(
                    (Frame) SwingUtilities.getWindowAncestor(this),
                    chrome,
                    contextManager,
                    workflowService,
                    filesToCommit,
                    commitResult -> { // This is the onCommitSuccessCallback
                        chrome.systemOutput("Committed "
                                + GitUiUtil.shortenCommitId(commitResult.commitId())
                                + ": " + commitResult.firstLine());
                        updateCommitPanel(); // Refresh file list
                        gitPanel.updateLogTab();
                        gitPanel.selectCurrentBranchInLogTab();
                    }
            );
            dialog.setVisible(true);
        });
        buttonPanel.add(commitButton);


        // Table selection => update commit button text and enable/disable buttons
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateCommitButtonText(); // Updates commit button label
                updateButtonEnablement(); // Updates general button enablement
            }
        });

        add(buttonPanel, BorderLayout.SOUTH);
    }

    /**
     * Updates the enabled state of commit and stash buttons based on file changes.
     */
    private void updateButtonEnablement() {
        // Enablement depends on whether there are changes in the table
        boolean hasChanges = uncommittedFilesTable.getRowCount() > 0;
        commitButton.setEnabled(hasChanges);
        stashButton.setEnabled(hasChanges);
    }


    /**
     * Returns the current GitRepo from ContextManager.
     */
    private GitRepo getRepo() {
        var repo = contextManager.getProject().getRepo();
        return (GitRepo) repo;
    }

    /**
     * Populates the uncommitted files table and enables/disables commit-related buttons.
     */
    public void updateCommitPanel() {
        logger.trace("Starting updateCommitPanel");
        // Store currently selected rows before updating
        int[] selectedRowsIndices = uncommittedFilesTable.getSelectedRows();
        List<ProjectFile> previouslySelectedFiles = new ArrayList<>();

        // Store the ProjectFile objects of selected rows to restore selection later
        if (uncommittedFilesTable.getModel().getRowCount() > 0) { // Check if table has rows before accessing
            for (int rowIndex : selectedRowsIndices) {
                if (rowIndex < uncommittedFilesTable.getModel().getRowCount()) { // Bounds check
                    ProjectFile pf = (ProjectFile) uncommittedFilesTable.getModel().getValueAt(rowIndex, 2);
                    if (pf != null) { // getValueAt can return null if model is being cleared
                        previouslySelectedFiles.add(pf);
                    }
                }
            }
        }

        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            try {
                var uncommittedFileStatuses = getRepo().getModifiedFiles();
                logger.trace("Found uncommitted files with statuses: {}", uncommittedFileStatuses.size());

                SwingUtilities.invokeLater(() -> {
                    // Convert Set to List to maintain an order for adding to table and restoring selection by index
                    var uncommittedFilesList = new ArrayList<>(uncommittedFileStatuses);

                    // Populate the table via the reusable FileStatusTable widget
                    // This also populates the statusMap within FileStatusTable
                    fileStatusPane.setFiles(uncommittedFilesList);

                    // Restore selection
                    List<Integer> rowsToSelect = new ArrayList<>();
                    var model = (DefaultTableModel) uncommittedFilesTable.getModel();
                    for (int i = 0; i < model.getRowCount(); i++) {
                        if (previouslySelectedFiles.contains(model.getValueAt(i, 2))) {
                            rowsToSelect.add(i);
                        }
                    }

                    if (!rowsToSelect.isEmpty()) {
                        for (int rowIndex : rowsToSelect) {
                            uncommittedFilesTable.addRowSelectionInterval(rowIndex, rowIndex);
                        }
                    }

                    updateButtonEnablement(); // General button enablement based on table content
                    updateCommitButtonText(); // Updates commit button label specifically
                });
            } catch (Exception e) {
                logger.error("Error fetching uncommitted files:", e);
                SwingUtilities.invokeLater(() -> {
                    logger.debug("Disabling commit/stash buttons due to error");
                    commitButton.setEnabled(false);
                    stashButton.setEnabled(false);
                    if (uncommittedFilesTable.getModel() instanceof DefaultTableModel dtm) {
                        dtm.setRowCount(0); // Clear table on error
                    }
                });
            }
            return null;
        });
    }

    /**
     * Adjusts the commit and stash button labels depending on file selection.
     */
    private void updateCommitButtonText() {
        int selectedRowCount = uncommittedFilesTable.getSelectedRowCount();
        if (selectedRowCount > 0) {
            commitButton.setText("Commit Selected...");
            commitButton.setToolTipText("Commit the selected files...");
            stashButton.setText("Stash Selected");
            stashButton.setToolTipText("Save selected changes to the stash");
        } else {
            commitButton.setText("Commit All...");
            commitButton.setToolTipText("Commit all files...");
            stashButton.setText("Stash All");
            stashButton.setToolTipText("Save all changes to the stash");
        }
    }

    /**
     * Updates the enabled state of context menu items for the uncommitted files table
     * based on the current selection.
     */
    private void updateUncommittedContextMenuState(JMenuItem captureDiffItem, JMenuItem viewDiffItem, JMenuItem editFileItem, JMenuItem viewHistoryItem, JMenuItem rollbackChangesItem) {
        int[] selectedRows = uncommittedFilesTable.getSelectedRows();
        int selectionCount = selectedRows.length;

        if (selectionCount == 0) {
            // No files selected: disable everything
            captureDiffItem.setEnabled(false);
            captureDiffItem.setToolTipText("Select file(s) to capture diff");
            viewDiffItem.setEnabled(false);
            viewDiffItem.setToolTipText("Select file(s) to view diff");
            editFileItem.setEnabled(false);
            editFileItem.setToolTipText("Select file(s) to edit");
            viewHistoryItem.setEnabled(false);
            viewHistoryItem.setToolTipText("Select a single existing file to view its history");
            rollbackChangesItem.setEnabled(false);
            rollbackChangesItem.setToolTipText("Select file(s) to rollback changes");
        } else if (selectionCount == 1) {
            // Exactly one file selected
            captureDiffItem.setEnabled(true);
            captureDiffItem.setToolTipText("Capture diff of selected file to context");
            viewDiffItem.setEnabled(true);
            viewDiffItem.setToolTipText("View diff of selected file");

            // Get file details for conditional enablement
            int row = selectedRows[0];
            ProjectFile projectFile = (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
            String status = fileStatusPane.statusFor(projectFile);

            // Enable Edit File
            editFileItem.setEnabled(true);
            editFileItem.setToolTipText("Edit this file");

            // Conditionally enable View History (disable for new files)
            boolean isNew = "new".equals(status);
            viewHistoryItem.setEnabled(!isNew);
            viewHistoryItem.setToolTipText(isNew ?
                                           "Cannot view history for a new file" :
                                           "View commit history for this file");

            // Enable Rollback Changes for all files
            rollbackChangesItem.setEnabled(true);
            rollbackChangesItem.setToolTipText("Rollback changes to HEAD state");
        } else { // More than one file selected
            captureDiffItem.setEnabled(true);
            captureDiffItem.setToolTipText("Capture diff of selected files to context");
            viewDiffItem.setEnabled(true); // Enable View Diff for multiple files
            viewDiffItem.setToolTipText("View diff of selected files in multi-file viewer");

            editFileItem.setEnabled(true);
            editFileItem.setToolTipText("Edit selected file(s)");

            viewHistoryItem.setEnabled(false); // Disable View History for multiple files
            viewHistoryItem.setToolTipText("Select a single existing file to view its history");

            rollbackChangesItem.setEnabled(true); // Enable Rollback Changes for multiple files
            rollbackChangesItem.setToolTipText("Rollback changes to HEAD state for selected files");
        }
    }

    /**
     * Helper to get a list of selected files from the uncommittedFilesTable.
     */
    private List<ProjectFile> getSelectedFilesFromTable()
    {
        var model = (DefaultTableModel) uncommittedFilesTable.getModel();
        var selectedRows = uncommittedFilesTable.getSelectedRows();
        var files = new ArrayList<ProjectFile>();

        for (var row : selectedRows) {
            // Retrieve ProjectFile directly from the hidden column
            ProjectFile projectFile = (ProjectFile) model.getValueAt(row, 2);
            files.add(projectFile);
        }
        return files;
    }

    /**
     * Helper to get a list of all files from the uncommittedFilesTable.
     */
    private List<ProjectFile> getAllFilesFromTable()
    {
        var model = (DefaultTableModel) uncommittedFilesTable.getModel();
        var files = new ArrayList<ProjectFile>();
        int rowCount = model.getRowCount();

        for (int i = 0; i < rowCount; i++) {
            // Retrieve ProjectFile directly from the hidden column
            ProjectFile projectFile = (ProjectFile) model.getValueAt(i, 2);
            files.add(projectFile);
        }
        return files;
    }

    /**
     * Opens a diff view for all uncommitted files in the table.
     * @param priorityFile File to show first (e.g., the double-clicked file), or null for default ordering
     */
    private void openDiffForAllUncommittedFiles(@Nullable ProjectFile priorityFile) {
        var allFiles = getAllFilesFromTable();
        if (allFiles.isEmpty()) {
            return; // nothing to diff
        }

        // Reorder files based on priority
        var orderedFiles = new ArrayList<ProjectFile>();

        if (priorityFile != null && allFiles.contains(priorityFile)) {
            // Priority file goes first
            orderedFiles.add(priorityFile);
            // Then selected files (excluding the priority file if it's already selected)
            var selectedFiles = getSelectedFilesFromTable();
            for (var file : selectedFiles) {
                if (!file.equals(priorityFile)) {
                    orderedFiles.add(file);
                }
            }
            // Finally, all other files
            for (var file : allFiles) {
                if (!file.equals(priorityFile) && !selectedFiles.contains(file)) {
                    orderedFiles.add(file);
                }
            }
        } else {
            // No priority file, use selected files first, then the rest
            var selectedFiles = getSelectedFilesFromTable();
            orderedFiles.addAll(selectedFiles);
            for (var file : allFiles) {
                if (!selectedFiles.contains(file)) {
                    orderedFiles.add(file);
                }
            }
        }

        contextManager.submitUserTask("show-uncomitted-files", () -> {
            try {
                var builder = new BrokkDiffPanel.Builder(chrome.themeManager, contextManager);

                for (var file : orderedFiles) {
                    var rightSource = new BufferSource.FileSource(
                            file.absPath().toFile(), file.getFileName()
                    );

                    String headContent = "";
                    try {
                        var repo = contextManager.getProject().getRepo();
                        headContent = repo.getFileContent("HEAD", file);
                    } catch (Exception ex) {
                        // new file or retrieval error – treat as empty
                        headContent = "";
                    }

                    var leftSource = new BufferSource.StringSource(
                            headContent, "HEAD", file.getFileName()
                    );
                    builder.addComparison(leftSource, rightSource);
                }

                SwingUtilities.invokeLater(() -> {
                    var panel = builder.build();
                    panel.showInFrame("Uncommitted Changes Diff");
                });
            } catch (Exception ex) {
                chrome.toolError("Error opening diff for all uncommitted files: " + ex.getMessage());
            }
        });
    }

    /**
     * Rollback selected files to their HEAD state with undo support via ContextHistory.
     * Snapshots the workspace before rollback to enable undo.
     */
    private void rollbackChangesWithUndo(List<ProjectFile> selectedFiles) {
        if (selectedFiles.isEmpty()) {
            chrome.toolError("No files selected for rollback");
return;
        }

        contextManager.submitUserTask("Rolling back files", () -> {
            try {
                // Add files to context first (this ensures they can be restored)
                contextManager.editFiles(selectedFiles);

                // Take a snapshot of the current state before rollback
                var frozen = contextManager.liveContext().freezeAndCleanup();
                contextManager.getContextHistory().addFrozenContextAndClearRedo(frozen.frozenContext());

                // Perform the actual rollback
                logger.debug("Rolling back {} files to HEAD", selectedFiles.size());
                getRepo().checkoutFilesFromCommit("HEAD", selectedFiles);

                // Create a task result for the activity history
                String fileList = GitUiUtil.formatFileList(selectedFiles);
                var rollbackDescription = "Rollback " + fileList + " to HEAD";
                var taskResult = new TaskResult(
                    rollbackDescription,
                    new ContextFragment.TaskFragment(contextManager, List.of(), rollbackDescription),
                    new HashSet<>(selectedFiles),
                    new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS)
                );
                contextManager.addToHistory(taskResult, false);

                // Update UI on EDT
                SwingUtilities.invokeLater(() -> {
                    String successMessage = "Rolled back " + fileList + " to HEAD state. Use Ctrl+Z to undo.";
                    chrome.systemOutput(successMessage);
                    updateCommitPanel();
                    gitPanel.updateLogTab();
                });

            } catch (Exception ex) {
                logger.error("Error rolling back files:", ex);
                SwingUtilities.invokeLater(() -> chrome.toolError("Error rolling back files: " + ex.getMessage()));
            }
        });
    }

    /**
     * Performs the actual stash operation and updates the UI.
     */
    private void performStash(List<ProjectFile> selectedFiles, String stashDescription) throws GitAPIException {
        assert !SwingUtilities.isEventDispatchThread();

        // Take a snapshot before mutating the working tree so the user can undo the stash
        var frozen = contextManager.liveContext().freezeAndCleanup();
        contextManager.getContextHistory().addFrozenContextAndClearRedo(frozen.frozenContext());

        if (selectedFiles.isEmpty()) {
            getRepo().createStash(stashDescription);
        } else {
            getRepo().createPartialStash(stashDescription, selectedFiles);
        }
        SwingUtilities.invokeLater(() -> {
            if (selectedFiles.isEmpty()) {
                chrome.systemOutput("All changes stashed successfully: " + stashDescription);
            } else {
                String fileList = GitUiUtil.formatFileList(selectedFiles);
                chrome.systemOutput("Stashed " + fileList + ": " + stashDescription);
            }
            updateCommitPanel(); // Refresh file list
            gitPanel.updateLogTab(); // Refresh log
        });
    }

    public void disableButtons() {
        SwingUtilities.invokeLater(() -> {
            stashButton.setEnabled(false);
            commitButton.setEnabled(false);
        });
    }

    public void enableButtons() {
        SwingUtilities.invokeLater(() -> {
            stashButton.setEnabled(true);
            commitButton.setEnabled(true);
        });
    }
}
