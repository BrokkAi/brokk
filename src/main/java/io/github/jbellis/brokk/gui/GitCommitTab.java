package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Panel for the "Commit" tab in the Git Panel.
 * Handles uncommitted changes, staging, committing, and stashing.
 */
public class GitCommitTab extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitCommitTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel; // Reference to parent GitPanel

    // Commit tab UI
    private JTable uncommittedFilesTable;
    private JButton suggestMessageButton;
    private JTextArea commitMessageArea;
    private JButton commitButton;
    private JButton stashButton;
    private final Map<ProjectFile, String> fileStatusMap = new HashMap<>();
    private ProjectFile rightClickedFile = null; // Store the file that was right-clicked

    public GitCommitTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.gitPanel = gitPanel; // Store reference to parent
        buildCommitTabUI();
    }

    /**
     * Builds the Commit tab UI elements.
     */
    private void buildCommitTabUI()
    {
        // Table for uncommitted files
        // Add a hidden column to store ProjectFile objects
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Filename", "Path", "ProjectFile"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 2) {
                    return ProjectFile.class;
                }
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        uncommittedFilesTable = new JTable(model);
        uncommittedFilesTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    javax.swing.JTable table, Object value,
                    boolean isSelected, boolean hasFocus,
                    int row, int column)
            {
                var cell = (javax.swing.table.DefaultTableCellRenderer)
                        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                var projectFile = (ProjectFile) table.getModel().getValueAt(row, 2); // Get ProjectFile from hidden column
                String status = fileStatusMap.get(projectFile);
                boolean darkTheme = com.google.common.base.Ascii.toLowerCase(UIManager.getLookAndFeel().getName()).contains("dark");

                if (isSelected) {
                    cell.setForeground(table.getSelectionForeground());
                } else {
                    var newColor = ThemeColors.getColor(darkTheme, "git_status_new");
                    var modifiedColor = ThemeColors.getColor(darkTheme, "git_status_modified");
                    var deletedColor = ThemeColors.getColor(darkTheme, "git_status_deleted");

                    switch (status) {
                        case "new" -> cell.setForeground(newColor);
                        case "deleted" -> cell.setForeground(deletedColor);
                        case "modified" -> cell.setForeground(modifiedColor);
                        default -> cell.setForeground(table.getForeground());
                    }
                }
                return cell;
            }
        });
        uncommittedFilesTable.setRowHeight(18);
        uncommittedFilesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        uncommittedFilesTable.getColumnModel().getColumn(1).setPreferredWidth(450);
        // Hide the ProjectFile column
        var projectFileColumn = uncommittedFilesTable.getColumnModel().getColumn(2);
        projectFileColumn.setMinWidth(0);
        projectFileColumn.setMaxWidth(0);
        projectFileColumn.setWidth(0);
        projectFileColumn.setPreferredWidth(0);
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
                    updateUncommittedContextMenuState(captureDiffItem, viewDiffItem, editFileItem, viewHistoryItem);
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });

        // Context menu actions:
        viewDiffItem.addActionListener(e -> {
            // Use the stored right-clicked file as priority
            openDiffForAllUncommittedFiles(rightClickedFile);
        });

        captureDiffItem.addActionListener(e -> {
            // Unified call:
            var selectedFiles = getSelectedFilesFromTable();
            GitUiUtil.captureUncommittedDiff(contextManager, chrome, selectedFiles);
        });

        editFileItem.addActionListener(e -> {
            var selectedProjectFiles = getSelectedFilesFromTable();
            GitUiUtil.editFiles(contextManager, selectedProjectFiles);
        });

        // Add action listener for the view history item
        viewHistoryItem.addActionListener(e -> {
            int row = uncommittedFilesTable.getSelectedRow();
            if (row >= 0) {
                var projectFile = (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
                gitPanel.addFileHistoryTab(projectFile);
            }
        });

        // Selection => update context menu item states
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateUncommittedContextMenuState(captureDiffItem, viewDiffItem, editFileItem, viewHistoryItem);
            }
        });

        JScrollPane uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        add(uncommittedScrollPane, BorderLayout.CENTER);

        // Commit message + bottom panel
        JPanel commitBottomPanel = new JPanel(new BorderLayout());
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.add(new JLabel("Commit/Stash Description:"), BorderLayout.NORTH);

        commitMessageArea = new JTextArea(2, 50);
        commitMessageArea.setLineWrap(true);
        commitMessageArea.setWrapStyleWord(true);
        messagePanel.add(new JScrollPane(commitMessageArea), BorderLayout.CENTER);

        commitBottomPanel.add(messagePanel, BorderLayout.CENTER);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        suggestMessageButton = new JButton("Suggest Message");
        suggestMessageButton.setToolTipText("Suggest a commit message for the selected files");
        suggestMessageButton.setEnabled(false);
        suggestMessageButton.addActionListener(e -> {
            List<ProjectFile> selectedFiles = getSelectedFilesFromTable();
            // Execute the suggestion task and handle the result on the EDT
            Future<String> suggestionFuture = suggestMessageAsync(selectedFiles);
            contextManager.submitContextTask("Handling suggestion result", () -> {
                try {
                    String suggestedMessage = suggestionFuture.get();
                    setCommitMessageText(suggestedMessage);
                } catch (InterruptedException | ExecutionException ex) {
                    throw new RuntimeException(ex);
                }
            });
        });
        buttonPanel.add(suggestMessageButton);

        stashButton = new JButton("Stash All");
        stashButton.setToolTipText("Save your changes to the stash");
        stashButton.setEnabled(false);
        stashButton.addActionListener(e -> {
            String userMessage = commitMessageArea.getText().trim();
            List<ProjectFile> selectedFiles = getSelectedFilesFromTable();

            if (userMessage.isEmpty()) {
                // No message provided, suggest one and stash
                contextManager.submitUserTask("Suggesting message and stashing", () -> {
                    try {
                        Future<String> suggestionFuture = suggestMessageAsync(selectedFiles);
                        String suggestedMessage = suggestionFuture.get(); // Wait for suggestion
                        if (suggestedMessage == null || suggestedMessage.isBlank()) {
                            logger.warn("No suggested commit message found");
                            suggestedMessage = "Stash created by Brokk"; // Fallback
                        }
                        String finalStashDescription = suggestedMessage;
                        // Perform stash with suggested message
                        performStash(selectedFiles, finalStashDescription);
                    } catch (GitAPIException ex) {
                        logger.error("Error stashing changes:", ex);
                        SwingUtilities.invokeLater(() -> chrome.toolError("Error stashing changes: " + ex.getMessage()));
                    } catch (ExecutionException | InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            } else {
                // Message provided, use it
                String stashDescription = java.util.Arrays.stream(userMessage.split("\n"))
                        .filter(line -> !line.trim().startsWith("#"))
                        .collect(Collectors.joining("\n"))
                        .trim();
                contextManager.submitUserTask("Stashing changes", () -> {
                    try {
                        performStash(selectedFiles, stashDescription.isEmpty() ? "Stash created by Brokk" : stashDescription);
                    } catch (GitAPIException ex) {
                        logger.error("Error stashing changes:", ex);
                        SwingUtilities.invokeLater(() -> chrome.toolError("Error stashing changes: " + ex.getMessage()));
                    }
                });
            }
        });
        buttonPanel.add(stashButton);

        commitButton = new JButton("Commit All");
        commitButton.setToolTipText("Commit files with the message");
        commitButton.setEnabled(false);
        commitButton.addActionListener(e -> {
            List<ProjectFile> selectedFiles = getSelectedFilesFromTable();
            String msg = commitMessageArea.getText().trim();
            if (msg.isEmpty()) {
                return;
            }
            contextManager.submitUserTask("Committing files", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        var allDirtyFileStatuses = getRepo().getModifiedFiles();
                        var allDirtyFiles = allDirtyFileStatuses.stream()
                                .map(GitRepo.ModifiedFile::file)
                                .collect(Collectors.toList());
                        getRepo().commitFiles(allDirtyFiles, msg);
                    } else {
                        getRepo().commitFiles(selectedFiles, msg);
                    }
                    SwingUtilities.invokeLater(() -> {
                        try {
                            String shortHash = getRepo().getCurrentCommitId().substring(0, 7);
                            String firstLine = msg.contains("\n") ? msg.substring(0, msg.indexOf('\n')) : msg;
                            chrome.systemOutput("Committed " + shortHash + ": " + firstLine);
                        } catch (Exception ex) {
                            chrome.systemOutput("Changes committed successfully");
                        }
                        commitMessageArea.setText("");
                        updateCommitPanel();
                        gitPanel.updateLogTab();
                        gitPanel.selectCurrentBranchInLogTab();
                    });
                } catch (Exception ex) {
                    logger.error("Error committing files:", ex);
                    SwingUtilities.invokeLater(() -> chrome.toolError("Error committing files: " + ex.getMessage()));
                }
            });
        });
        buttonPanel.add(commitButton);

        // Commit message area => enable/disable commit/stash buttons
        commitMessageArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateCommitButtonState();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateCommitButtonState();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateCommitButtonState();
            }

            private void updateCommitButtonState() {
                // Enablement depends on whether there are changes (indicated by suggest button)
                boolean hasChanges = suggestMessageButton.isEnabled();
                stashButton.setEnabled(hasChanges);

                // Commit button still requires a message
                String text = commitMessageArea.getText().trim();
                boolean hasNonCommentText = Arrays.stream(text.split("\n"))
                        .anyMatch(line -> !line.trim().isEmpty()
                                && !line.trim().startsWith("#"));
                commitButton.setEnabled(hasNonCommentText && hasChanges);
            }
        });

        // Table selection => update commit button text
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateCommitButtonText();
        });

        commitBottomPanel.add(buttonPanel, BorderLayout.SOUTH);
        add(commitBottomPanel, BorderLayout.SOUTH);
    }

    /**
     * Returns the current GitRepo from ContextManager.
     */
    private GitRepo getRepo() {
        var repo = contextManager.getProject().getRepo();
        assert repo != null;
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
                    fileStatusMap.clear();
                    // Convert Set to List to maintain an order for adding to table and restoring selection by index
                    var uncommittedFilesList = new ArrayList<>(uncommittedFileStatuses);
                    for (var modifiedFileStatus : uncommittedFilesList) {
                        fileStatusMap.put(modifiedFileStatus.file(), modifiedFileStatus.status());
                    }

                    var model = (DefaultTableModel) uncommittedFilesTable.getModel();
                    model.setRowCount(0);

                    if (uncommittedFilesList.isEmpty()) {
                        logger.trace("No uncommitted files found");
                        suggestMessageButton.setEnabled(false);
                        commitButton.setEnabled(false);
                        stashButton.setEnabled(false);
                    } else {
                        logger.trace("Found {} uncommitted files to display", uncommittedFilesList.size());
                        List<Integer> rowsToSelect = new ArrayList<>();

                        for (int i = 0; i < uncommittedFilesList.size(); i++) {
                            var modifiedFileStatus = uncommittedFilesList.get(i);
                            var projectFile = modifiedFileStatus.file();
                            model.addRow(new Object[]{projectFile.getFileName(), projectFile.getParent().toString(), projectFile});
                            logger.trace("Added file to table: {} with status {}", projectFile, modifiedFileStatus.status());

                            if (previouslySelectedFiles.contains(projectFile)) {
                                rowsToSelect.add(i);
                            }
                        }

                        if (!rowsToSelect.isEmpty()) {
                            for (int row : rowsToSelect) {
                                uncommittedFilesTable.addRowSelectionInterval(row, row);
                            }
                            logger.trace("Restored selection for {} rows", rowsToSelect.size());
                        }

                        suggestMessageButton.setEnabled(true);

                        var text = commitMessageArea.getText().trim();
                        var hasNonCommentText = Arrays.stream(text.split("\n"))
                                .anyMatch(line -> !line.trim().isEmpty()
                                        && !line.trim().startsWith("#"));
                        commitButton.setEnabled(hasNonCommentText);
                        stashButton.setEnabled(true);
                    }
                    updateCommitButtonText();
                });
            } catch (Exception e) {
                logger.error("Error fetching uncommitted files:", e);
                SwingUtilities.invokeLater(() -> {
                    logger.debug("Disabling commit buttons due to error");
                    suggestMessageButton.setEnabled(false);
                    commitButton.setEnabled(false);
                    stashButton.setEnabled(false); // Also disable stash on error
                    ((DefaultTableModel) uncommittedFilesTable.getModel()).setRowCount(0); // Clear table on error
                });
            }
            return null;
        });
    }

    /**
     * Adjusts the commit/stash button label/text depending on selected vs all.
     */
    private void updateCommitButtonText() {
        int[] selectedRows = uncommittedFilesTable.getSelectedRows();
        if (selectedRows.length > 0) {
            commitButton.setText("Commit Selected");
            commitButton.setToolTipText("Commit the selected files with the message");
            stashButton.setText("Stash Selected");
            stashButton.setToolTipText("Save your selected changes to the stash");
        } else {
            commitButton.setText("Commit All");
            commitButton.setToolTipText("Commit all files with the message");
            stashButton.setText("Stash All");
            stashButton.setToolTipText("Save all your changes to the stash");
        }
    }

    /**
     * Updates the enabled state of context menu items for the uncommitted files table
     * based on the current selection.
     */
    private void updateUncommittedContextMenuState(JMenuItem captureDiffItem, JMenuItem viewDiffItem, JMenuItem editFileItem, JMenuItem viewHistoryItem) {
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
        } else if (selectionCount == 1) {
            // Exactly one file selected
            captureDiffItem.setEnabled(true);
            captureDiffItem.setToolTipText("Capture diff of selected file to context");
            viewDiffItem.setEnabled(true);
            viewDiffItem.setToolTipText("View diff of selected file");

            // Get file details for conditional enablement
            int row = selectedRows[0];
            ProjectFile projectFile = (ProjectFile) uncommittedFilesTable.getModel().getValueAt(row, 2);
            String status = fileStatusMap.get(projectFile);

            // Enable Edit File
            editFileItem.setEnabled(true);
            editFileItem.setToolTipText("Edit this file");

            // Conditionally enable View History (disable for new files)
            boolean isNew = "new".equals(status);
            viewHistoryItem.setEnabled(!isNew);
            viewHistoryItem.setToolTipText(isNew ?
                                           "Cannot view history for a new file" :
                                           "View commit history for this file");
        } else { // More than one file selected
            captureDiffItem.setEnabled(true);
            captureDiffItem.setToolTipText("Capture diff of selected files to context");
            viewDiffItem.setEnabled(true); // Enable View Diff for multiple files
            viewDiffItem.setToolTipText("View diff of selected files in multi-file viewer");

            editFileItem.setEnabled(true);
            editFileItem.setToolTipText("Edit selected file(s)");

            viewHistoryItem.setEnabled(false); // Disable View History for multiple files
            viewHistoryItem.setToolTipText("Select a single existing file to view its history");
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
    private void openDiffForAllUncommittedFiles(ProjectFile priorityFile) {
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
                        if (repo != null) {
                            headContent = repo.getFileContent("HEAD", file);
                        }
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
     * Performs the actual stash operation and updates the UI.
     */
    private void performStash(List<ProjectFile> selectedFiles, String stashDescription) throws GitAPIException {
        assert !SwingUtilities.isEventDispatchThread();

        if (selectedFiles.isEmpty()) {
            getRepo().createStash(stashDescription);
        } else {
            getRepo().createPartialStash(stashDescription, selectedFiles);
        }
        SwingUtilities.invokeLater(() -> {
            if (selectedFiles.isEmpty()) {
                chrome.systemOutput("All changes stashed successfully: " + stashDescription);
            } else {
                String fileList = selectedFiles.size() <= 3
                                  ? selectedFiles.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", "))
                                  : selectedFiles.size() + " files";
                chrome.systemOutput("Stashed " + fileList + ": " + stashDescription);
            }
            commitMessageArea.setText("");
            updateCommitPanel();
            gitPanel.updateLogTab();
        });
    }

    /**
     * Asynchronously suggests a commit message based on selected files or all changes.
     *
     * @param selectedFiles List of files to diff, or empty to diff all changes.
     * @return A Future containing the suggested message, or null if no changes or error.
     */
    private Future<String> suggestMessageAsync(List<ProjectFile> selectedFiles) {
        // Submit the Callable to a background task executor managed by ContextManager
        // We use submitBackgroundTask to ensure it runs off the EDT and provides user feedback
        return contextManager.submitBackgroundTask("Suggesting commit message", () -> {
            String diff = selectedFiles.isEmpty()
                          ? getRepo().diff()
                          : getRepo().diffFiles(selectedFiles);

            if (diff.isEmpty()) {
                SwingUtilities.invokeLater(() -> chrome.systemOutput("No changes detected"));
                return null; // Indicate no changes
            }
            // Call the LLM logic
            return inferCommitMessage(contextManager.getProject(), diff);
        });
    }

    /**
     * Infers a commit message based on the provided diff text using the quickest model.
     * This method performs the synchronous LLM call.
     *
     * @param project  The current project, used to get configuration like commit format.
     * @param diffText The text difference to analyze for the commit message.
     * @return The inferred commit message string, or null if no message could be generated or an error occurred.
     */
    private String inferCommitMessage(IProject project, String diffText) {
        var messages = CommitPrompts.instance.collectMessages(project, diffText);
        if (messages.isEmpty()) {
            SwingUtilities.invokeLater(() -> chrome.systemOutput("Nothing to commit for suggestion"));
            return null;
        }

        // Use quickest model for commit messages via ContextManager
        Llm.StreamingResult result;
        try {
            result = contextManager.getLlm(contextManager.getService().quickestModel(), "Infer commit message").sendRequest(messages);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (result.error() != null) {
            SwingUtilities.invokeLater(() -> chrome.systemOutput("LLM error during commit message suggestion: " + result.error().getMessage()));
            logger.warn("LLM error during commit message suggestion: {}", result.error().getMessage());
            return null;
        }
        if (result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            SwingUtilities.invokeLater(() -> chrome.systemOutput("LLM did not provide a commit message or is unavailable."));
            return null;
        }

        String commitMsg = result.chatResponse().aiMessage().text();

        if (commitMsg == null || commitMsg.isBlank()) {
            SwingUtilities.invokeLater(() -> chrome.systemOutput("LLM did not provide a commit message."));
            return null;
        }

        // Escape quotes in the commit message
        commitMsg = commitMsg.replace("\"", "\\\"");

        return commitMsg; // Return the raw message; setting text is handled by the caller
    }

    /**
     * Sets the text in the commit message area (used by LLM suggestions).
     */
    public void setCommitMessageText(String message) {
        SwingUtilities.invokeLater(() -> commitMessageArea.setText(message));
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
