package io.github.jbellis.brokk.gui;

import com.google.common.base.Ascii;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.ICommitInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Panel that contains the "Log" tab UI and related functionality:
 * - Branch tables (local & remote)
 * - Commits table
 * - Tree of changed files (per commit)
 * - Search functionality
 */
public class GitLogTab extends JPanel {

    // Methods to expose to GitPanel for finding and selecting commits by ID

    private static final Logger logger = LogManager.getLogger(GitLogTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;

    // Branches
    private JTable branchTable;
    private DefaultTableModel branchTableModel;
    private JTable remoteBranchTable;
    private DefaultTableModel remoteBranchTableModel;

    // Branch-specific UI
    private JMenuItem captureDiffVsBranchItem;
    // createBranchFromCommitItem is managed by GitCommitBrowserPanel if needed, or was removed by prior refactoring.

    private GitCommitBrowserPanel gitCommitBrowserPanel;

    /**
     * Constructor. Builds and arranges the UI components for the Log tab.
     */
    public GitLogTab(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        var project = contextManager.getProject();
        // Determine if the "Create PR" button should be shown, mirroring logic in GitPanel for the PR tab.
        var showCreatePrButton = project.isGitHubRepo();
        var panelOptions = new GitCommitBrowserPanel.Options(true, true, showCreatePrButton);

        this.gitCommitBrowserPanel = new GitCommitBrowserPanel(chrome, contextManager, this::reloadCurrentBranchOrContext, panelOptions);
        buildLogTabUI();
    }

    /**
     * Creates and arranges all the "Log" tab sub-panels:
     * - Branches (local + remote)
     * - Commits
     * - Changes tree
     * - Simple text search
     */
    private void buildLogTabUI()
    {
        // Main container uses GridBagLayout
        JPanel logPanel = new JPanel(new GridBagLayout());
        add(logPanel, BorderLayout.CENTER);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weighty = 1.0;  // always fill vertically

        // ============ Branches Panel (left ~20%) ============
        JPanel branchesPanel = new JPanel(new BorderLayout());
        branchesPanel.setBorder(BorderFactory.createTitledBorder("Branches"));

        JTabbedPane branchTabbedPane = new JTabbedPane();

        // Local branches panel
        JPanel localBranchPanel = new JPanel(new BorderLayout());
        branchTableModel = new DefaultTableModel(new Object[]{"", "Branch"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        branchTable = new JTable(branchTableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (column == 1 && row >= 0 && row < getRowCount()) {
                    String branchName = (String) getValueAt(row, 1);
                    if ("stashes".equals(branchName)) {
                        c.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 13));
                    } else {
                        c.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                    }
                }
                return c;
            }

            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int col = columnAtPoint(e.getPoint());
                if (row >= 0 && col == 1) { // Branch name column
                    return (String) getValueAt(row, col);
                }
                return super.getToolTipText(e);
            }
        };
        branchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        branchTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        branchTable.setRowHeight(18);
        var branchTableCol0 = branchTable.getColumnModel().getColumn(0);
        branchTableCol0.setMinWidth(20);
        branchTableCol0.setMaxWidth(20);
        branchTableCol0.setPreferredWidth(20);
        localBranchPanel.add(new JScrollPane(branchTable), BorderLayout.CENTER);

        // Remote branches panel
        JPanel remoteBranchPanel = new JPanel(new BorderLayout());
        remoteBranchTableModel = new DefaultTableModel(new Object[]{"Branch"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        remoteBranchTable = new JTable(remoteBranchTableModel);
        remoteBranchTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        remoteBranchTable.setRowHeight(18);
        remoteBranchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        remoteBranchPanel.add(new JScrollPane(remoteBranchTable), BorderLayout.CENTER);

        branchTabbedPane.addTab("Local", localBranchPanel);
        branchTabbedPane.addTab("Remote", remoteBranchPanel);
        branchesPanel.add(branchTabbedPane, BorderLayout.CENTER);

        JPanel branchButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshBranchesButton = new JButton("Refresh");
        refreshBranchesButton.addActionListener(e -> update());
        branchButtonPanel.add(refreshBranchesButton);
        branchesPanel.add(branchButtonPanel, BorderLayout.SOUTH);

        // The GitCommitBrowserPanel (this.gitCommitBrowserPanel) now handles commits, search, changes tree, and related actions.
        // The old code for "Commits Panel" and "Changes Panel" that was here is removed.

        // ============ Add sub-panels to logPanel with GridBag ============
        Dimension nominalPreferredSize = new Dimension(1, 1);
        branchesPanel.setPreferredSize(nominalPreferredSize);
        gitCommitBrowserPanel.setPreferredSize(nominalPreferredSize);

        constraints.gridx = 0; // branches
        constraints.weightx = 0.20; // Adjusted weight for branches
        logPanel.add(branchesPanel, constraints);

        constraints.gridx = 1; // commit browser (commits + changes)
        constraints.weightx = 0.80; // Adjusted weight for commit browser
        logPanel.add(gitCommitBrowserPanel, constraints);

        // Listeners for branch table
        branchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && branchTable.getSelectedRow() != -1) {
                String branchName = (String) branchTableModel.getValueAt(branchTable.getSelectedRow(), 1);
                remoteBranchTable.clearSelection();
                updateCommitsForBranch(branchName);
                if (gitCommitBrowserPanel != null) gitCommitBrowserPanel.clearSearchField(); // Clear search in panel
            }
        });
        remoteBranchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && remoteBranchTable.getSelectedRow() != -1) {
                String branchName = (String) remoteBranchTableModel.getValueAt(remoteBranchTable.getSelectedRow(), 0);
                branchTable.clearSelection();
                updateCommitsForBranch(branchName);
                if (gitCommitBrowserPanel != null) gitCommitBrowserPanel.clearSearchField(); // Clear search in panel
            }
        });

        // Local branch context menu
        JPopupMenu branchContextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(branchContextMenu);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(branchContextMenu);
                }
            });
        }
        JMenuItem checkoutItem = new JMenuItem("Checkout");
        JMenuItem newBranchItem = new JMenuItem("New Branch From This");
        JMenuItem mergeItem = new JMenuItem("Merge into HEAD");
        JMenuItem renameItem = new JMenuItem("Rename");
        JMenuItem deleteItem = new JMenuItem("Delete");
        captureDiffVsBranchItem = new JMenuItem("Capture Diff vs Branch");

        branchContextMenu.add(checkoutItem);
        branchContextMenu.add(newBranchItem);
        branchContextMenu.add(mergeItem);
        branchContextMenu.add(captureDiffVsBranchItem);
        branchContextMenu.add(renameItem);
        branchContextMenu.add(deleteItem);

        branchTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleBranchPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleBranchPopup(e);
            }

            private void handleBranchPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = branchTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        if (!branchTable.isRowSelected(row)) {
                            branchTable.setRowSelectionInterval(row, row);
                        }
                    }
                    checkBranchContextMenuState(branchContextMenu);
                    // Use invokeLater to ensure selection updates and menu item state changes
                    // are processed before showing menu.
                    SwingUtilities.invokeLater(() -> {
                        branchContextMenu.show(branchTable, e.getX(), e.getY());
                    });
                }
            }
        });

        checkoutItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                checkoutBranch(branchDisplay);
            }
        });
        newBranchItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String sourceBranch = (String) branchTableModel.getValueAt(selectedRow, 1);
                createNewBranchFrom(sourceBranch);
            }
        });
        mergeItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                mergeBranchIntoHead(branchDisplay);
            }
        });
        captureDiffVsBranchItem.addActionListener(e -> {
            int row = branchTable.getSelectedRow();
            if (row != -1) {
                String selectedBranch = (String) branchTableModel.getValueAt(row, 1);
                if ("stashes".equals(selectedBranch)) return;

                String currentActualBranch;
                try {
                    currentActualBranch = getRepo().getCurrentBranch();
                } catch (Exception ex) {
                    logger.error("Could not get current branch for diff operation", ex);
                    chrome.toolError("Failed to determine current branch. Cannot perform diff. Error: " + ex.getMessage(), "Error");
                    return;
                }
                if (selectedBranch.equals(currentActualBranch)) return;

                GitUiUtil.captureDiffBetweenBranches(contextManager, chrome, currentActualBranch, selectedBranch);
            }
        });
        renameItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                renameBranch(branchDisplay);
            }
        });
        deleteItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                var branchName = (String) branchTableModel.getValueAt(selectedRow, 1);
                deleteBranch(branchName);
            }
        });

        // Remote branch context menu
        JPopupMenu remoteBranchContextMenu = new JPopupMenu();
        SwingUtilities.invokeLater(() -> {
            if (chrome.themeManager != null) {
                chrome.themeManager.registerPopupMenu(remoteBranchContextMenu);
            }
        });
        JMenuItem remoteCheckoutItem = new JMenuItem("Checkout");
        JMenuItem remoteNewBranchItem = new JMenuItem("New Branch From This");
        JMenuItem remoteDiffItem = new JMenuItem("Capture Diff vs Branch");

        remoteBranchContextMenu.add(remoteCheckoutItem);
        remoteBranchContextMenu.add(remoteNewBranchItem);
        remoteBranchContextMenu.add(remoteDiffItem);

        remoteBranchTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleRemoteBranchPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleRemoteBranchPopup(e);
            }

            private void handleRemoteBranchPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = remoteBranchTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && !remoteBranchTable.isRowSelected(row)) {
                        remoteBranchTable.setRowSelectionInterval(row, row);
                    }
                    SwingUtilities.invokeLater(() -> {
                        remoteBranchContextMenu.show(remoteBranchTable, e.getX(), e.getY());
                    });
                }
            }
        });

        remoteCheckoutItem.addActionListener(e -> performRemoteBranchAction(this::checkoutBranch));
        remoteNewBranchItem.addActionListener(e -> performRemoteBranchAction(this::createNewBranchFrom));
        remoteDiffItem.addActionListener(e -> performRemoteBranchAction(this::captureDiffVsRemoteBranch));
    }

    // Methods getFilePathFromTreePath, isFileNode, hasFileNodesSelected, getSelectedFilePaths
    // were related to the changesTree that was part of GitLogTab.
    // This tree and its logic are now encapsulated within GitCommitBrowserPanel.
    // Thus, these specific helper methods are removed from GitLogTab.

    /**
     * Update the branch list (local + remote), attempting to preserve the selection.
     * If the previously selected branch no longer exists, it selects the current git branch.
     */
    public void update() {
        // Use invokeAndWait with a Runnable and an external holder for the result
        var previouslySelectedBranch = SwingUtil.runOnEdt(() -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                // Ensure the row index is still valid before accessing model
                if (selectedRow < branchTableModel.getRowCount()) {
                    return (String) branchTableModel.getValueAt(selectedRow, 1);
                }
            }
            return "";
        }, "");


        contextManager.submitBackgroundTask("Fetching git branches", () -> {
            try {
                String currentGitBranch = getRepo().getCurrentBranch(); // Get current branch from Git
                List<String> localBranches = getRepo().listLocalBranches();
                List<String> remoteBranches = getRepo().listRemoteBranches();

                // Prepare data rows off the EDT
                List<Object[]> localBranchRows = new ArrayList<>();
                int targetSelectionIndex = -1; // Index to select after update
                String targetBranchToSelect = previouslySelectedBranch; // Prioritize previous selection

                // Add virtual "stashes" entry first if stashes exist
                boolean hasStashes = false;
                try {
                    // Just check if the list is non-empty, avoid fetching full info yet
                    hasStashes = !getRepo().listStashes().isEmpty();
                    if (hasStashes) {
                        localBranchRows.add(new Object[]{"", "stashes"});
                        if ("stashes".equals(targetBranchToSelect)) {
                            targetSelectionIndex = 0; // If 'stashes' was selected, mark its index
                        }
                    }
                } catch (GitAPIException e) {
                    logger.warn("Could not check for stashes existence", e);
                }

                // Get branches checked out in worktrees
                Set<String> branchesInWorktrees = getRepo().getBranchesInWorktrees();

                // Process actual local branches
                for (String branch : localBranches) {
                    String checkmark;
                    if (branch.equals(currentGitBranch)) {
                        checkmark = "✓";
                    } else if (branchesInWorktrees.contains(branch)) {
                        checkmark = "+";
                    } else {
                        checkmark = "";
                    }
                    localBranchRows.add(new Object[]{checkmark, branch});
                    // If this branch was the target (previously selected) and we haven't found it yet
                    if (branch.equals(targetBranchToSelect) && targetSelectionIndex == -1) {
                        targetSelectionIndex = localBranchRows.size() - 1;
                    }
                }

                // If the previously selected branch wasn't found (or nothing was selected),
                // fall back to the current Git branch.
                if (targetSelectionIndex == -1) {
                    targetBranchToSelect = currentGitBranch; // Update target
                    // Find the index of the current Git branch
                    for (int i = 0; i < localBranchRows.size(); i++) {
                        // Compare with the branch name in column 1
                        if (localBranchRows.get(i)[1].equals(targetBranchToSelect)) {
                            targetSelectionIndex = i;
                            break;
                        }
                    }
                }

                List<Object[]> remoteBranchRows = remoteBranches.stream()
                        .map(branch -> new Object[]{branch})
                        .toList();

                final int finalTargetSelectionIndex = targetSelectionIndex;
                final String finalSelectedBranchName = targetBranchToSelect; // The branch name we actually selected/targeted

                SwingUtilities.invokeLater(() -> {
                    // gitCommitBrowserPanel.clearCommitView(); // This will be handled by updateCommitsForBranch or if no branch selected

                    branchTableModel.setRowCount(0);
                    remoteBranchTableModel.setRowCount(0);

                    for (var rowData : localBranchRows) {
                        branchTableModel.addRow(rowData);
                    }
                    for (var rowData : remoteBranchRows) {
                        remoteBranchTableModel.addRow(rowData);
                    }

                    if (finalTargetSelectionIndex >= 0 && finalSelectedBranchName != null) {
                        branchTable.setRowSelectionInterval(finalTargetSelectionIndex, finalTargetSelectionIndex);
                        // Ensure selected branch is visible by scrolling to it
                        branchTable.scrollRectToVisible(branchTable.getCellRect(finalTargetSelectionIndex, 0, true));
                        updateCommitsForBranch(finalSelectedBranchName); // Load commits for the branch we ended up selecting
                    } else {
                        // This case might happen if the repo becomes empty or only has remote branches initially
                        logger.warn("Could not select any local branch (target: {}, current git: {}). Clearing commits.",
                                    previouslySelectedBranch, currentGitBranch);
                        gitCommitBrowserPanel.clearCommitView();
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching branches", e);
                SwingUtilities.invokeLater(() -> {
                    branchTableModel.setRowCount(0);
                    branchTableModel.addRow(new Object[]{"", "Error fetching branches: " + e.getMessage()});
                    remoteBranchTableModel.setRowCount(0);
                    gitCommitBrowserPanel.clearCommitView();
                });
            }
        });
    }

    /**
     * Update commits list for the given branch and highlights unpushed commits if applicable.
     */
    private void updateCommitsForBranch(String branchName) {
        contextManager.submitBackgroundTask("Fetching commits for " + branchName, () -> {
            try {
                List<CommitInfo> commits;
                Set<String> unpushedCommitIds = new HashSet<>();
                boolean canPush = false;
                boolean canPull = false;

                // Special handling for stashes virtual branch
                if ("stashes".equals(branchName)) {
                    try {
                        // Directly call listStashes which now returns List<CommitInfo>
                        commits = getRepo().listStashes();
                    } catch (GitAPIException e) {
                        logger.error("Error fetching stashes", e);
                        commits = List.of(); // Ensure commits is initialized
                    }
                } else {
                    // Normal branch handling
                    commits = getRepo().listCommitsDetailed(branchName);
                    var localBranches = getRepo().listLocalBranches();
                    var isLocalBranch = localBranches.contains(branchName);
                    if (isLocalBranch) {
                        boolean hasUpstream = getRepo().hasUpstreamBranch(branchName);
                        canPull = hasUpstream; // canPull is true if there's an upstream
                        if (hasUpstream) {
                            try {
                                unpushedCommitIds.addAll(getRepo().getUnpushedCommitIds(branchName));
                                canPush = !unpushedCommitIds.isEmpty(); // Push if unpushed commits and upstream exists
                            } catch (GitAPIException e) {
                                logger.warn("Could not check for unpushed commits for branch {}: {}", branchName, e.getMessage());
                            }
                        } else {
                            // No upstream, so we can "push" to create it.
                            // The act of pushing will effectively push all local commits of this branch.
                            canPush = true;
                        }
                    }
                }

                boolean finalCanPush = canPush;
                boolean finalCanPull = canPull;

                // Pass data to the GitCommitBrowserPanel
                gitCommitBrowserPanel.setCommits(commits, unpushedCommitIds, finalCanPush, finalCanPull, branchName);

            } catch (Exception e) {
                logger.error("Error fetching commits for branch: " + branchName, e);
                // Display error in the panel if possible, or clear it
                SwingUtilities.invokeLater(() -> {
                    var errorCommit = List.of(
                            new ICommitInfo.CommitInfoStub("Error fetching commits: " + e.getMessage())
                    );
                    gitCommitBrowserPanel.setCommits(errorCommit, Collections.emptySet(), false, false, branchName);
                });
            }
        });
    }

    /**
     * Check out a branch (local or remote).
     */
    private void checkoutBranch(String branchName) {
        contextManager.submitUserTask("Checking out branch: " + branchName, () -> {
            try {
                if (!getRepo().listLocalBranches().contains(branchName)) {
                    // If it's not a known local branch, assume it's remote or needs tracking.
                    getRepo().checkoutRemoteBranch(branchName);
                    chrome.systemOutput("Created local tracking branch for " + branchName);
                } else {
                    getRepo().checkout(branchName);
                }
                update();
            } catch (GitAPIException e) {
                logger.error("Error checking out branch: {}", branchName, e);
                chrome.toolError(Objects.toString(e.getMessage(), "Unknown error during checkout."));
            }
        });
    }

    /**
     * Merge a branch into HEAD.
     */
    private void mergeBranchIntoHead(String branchName) {
        contextManager.submitUserTask("Merging branch: " + branchName, () -> {
            try {
                MergeResult mergeResult = getRepo().mergeIntoHead(branchName);
                MergeResult.MergeStatus status = mergeResult.getMergeStatus();

                if (status.isSuccessful()) {
                    if (status == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                        chrome.systemOutput("Branch '" + branchName + "' is already up-to-date with HEAD.");
                    } else {
                        chrome.systemOutput("Branch '" + branchName + "' was successfully merged into HEAD.");
                    }
                } else if (status == MergeResult.MergeStatus.CONFLICTING) {
                    String conflictingFiles = mergeResult.getConflicts().keySet().stream()
                            .map(s -> "  - " + s)
                            .collect(Collectors.joining("\n"));
                    chrome.toolError("Merge conflicts detected for branch '" + branchName + "'.\n" +
                                        "Please resolve conflicts manually and then commit.\n" +
                                        "Conflicting files:\n" + conflictingFiles, "Merge Conflict");
                } else {
                    // For other non-successful statuses like FAILED, ABORTED etc.
                    chrome.toolError("Merge of branch '" + branchName + "' failed with error: " + status, "Merge Error");
                }
                update(); // Refresh UI to reflect new state (merged, conflicting, or failed)
            } catch (GitAPIException e) {
                logger.error("Error merging branch: {}", branchName, e);
                chrome.toolError("Error merging branch '" + branchName + "': " + e.getMessage(), "Merge Error");
                update(); // Refresh UI to show current state after error
            }
        });
    }

    /**
     * Creates a new branch from an existing one and checks it out.
     */
    private void createNewBranchFrom(String sourceBranch) {
        var newName = JOptionPane.showInputDialog(
                this,
                "Enter name for new branch from '" + sourceBranch + "':",
                "Create New Branch",
                JOptionPane.QUESTION_MESSAGE
        );
        if (newName != null && !newName.trim().isEmpty()) {
            contextManager.submitUserTask("Creating new branch: " + newName + " from " + sourceBranch, () -> {
                try {
                    getRepo().createAndCheckoutBranch(newName, sourceBranch);
                    update();
                    chrome.systemOutput("Created and checked out new branch '" + newName + "' from '" + sourceBranch + "'");
                } catch (GitAPIException e) {
                    logger.error("Error creating new branch from {}: {}", sourceBranch, e);
                    chrome.toolError("Error creating new branch: " + e.getMessage(), "Branch Error");
                }
            });
        }
    }

    /**
     * Rename a local branch.
     */
    private void renameBranch(String branchName) {
        var newName = (String) JOptionPane.showInputDialog(
                this,
                "Enter new name for branch '" + branchName + "':",
                "Rename Branch",
                JOptionPane.QUESTION_MESSAGE,
                null, // icon
                null, // selectionValues
                branchName // initialSelectionValue
        );
        if (newName != null && !newName.trim().isEmpty()) {
            contextManager.submitUserTask("Renaming branch: " + branchName, () -> {
                try {
                    getRepo().renameBranch(branchName, newName);
                    SwingUtilities.invokeLater(this::update);
                    chrome.systemOutput("Branch '" + branchName + "' renamed to '" + newName + "' successfully.");
                } catch (GitAPIException e) {
                    logger.error("Error renaming branch: {}", branchName, e);
                    chrome.toolError("Error renaming branch: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Delete a local branch (with checks for merges).
     */
    private void deleteBranch(String branchName) {
        contextManager.submitUserTask("Checking branch merge status", () -> {
            try {
                boolean isMerged = getRepo().isBranchMerged(branchName);
                SwingUtilities.invokeLater(() -> {
                    if (isMerged) {
                        int result = JOptionPane.showConfirmDialog(
                                this,
                                "Are you sure you want to delete branch '" + branchName + "'?",
                                "Delete Branch",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE
                        );
                        if (result == JOptionPane.YES_OPTION) {
                            performBranchDeletion(branchName, false);
                        }
                    } else {
                        Object[] options = {"Force Delete", "Cancel"};
                        int result = JOptionPane.showOptionDialog(
                                this,
                                "Branch '" + branchName + "' is not fully merged.\n" +
                                        "Changes on this branch will be lost if deleted.\n" +
                                        "Do you want to force delete it?",
                                "Unmerged Branch",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE,
                                null,
                                options,
                                options[1]
                        );
                        if (result == 0) { // Force Delete
                            performBranchDeletion(branchName, true);
                        }
                    }
                });
            } catch (GitAPIException e) {
                logger.error("Error checking branch merge status: {}", branchName, e);
                chrome.toolError("Error checking branch status: " + e.getMessage());
            }
        });
    }

    /**
     * Perform the actual branch deletion with or without force.
     */
    private void performBranchDeletion(String branchName, boolean force) {
        contextManager.submitUserTask("Deleting branch: " + branchName, () -> {
            try {
                logger.debug("Initiating {} deletion for branch: {}",
                             force ? "force" : "normal", branchName);

                // Check if branch exists before trying to delete
                List<String> localBranches = getRepo().listLocalBranches();
                if (!localBranches.contains(branchName)) {
                    chrome.toolError("Cannot delete branch '" + branchName + "' - it doesn't exist locally");
                    return;
                }

                // Check if it's the current branch
                var currentBranch = getRepo().getCurrentBranch();
                if (branchName.equals(currentBranch)) {
                    logger.warn("Cannot delete branch '{}' - it is the currently checked out branch", branchName);
                    chrome.toolError("Cannot delete the current branch. Please checkout a different branch first.");
                    return;
                }

                if (force) {
                    getRepo().forceDeleteBranch(branchName);
                } else {
                    getRepo().deleteBranch(branchName);
                }

                SwingUtilities.invokeLater(this::update);
                chrome.systemOutput("Branch '" + branchName + "' " + (force ? "force " : "") + "deleted successfully.");
            } catch (GitAPIException e) {
                chrome.toolError("Error deleting branch '" + branchName + "': " + e.getMessage());
            }
        });
    }

    // ==================================================================
    // Helper Methods
    // ==================================================================

    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }

    // expandAllNodes was for the local changesTree, now handled by GitCommitBrowserPanel.

    /**
     * Format commit date to show e.g. "HH:MM:SS today" if it is today's date.
     */
    static String formatCommitDate(java.time.Instant commitInstant, java.time.LocalDate today) {
        try {
            java.time.ZonedDateTime commitZonedDateTime = commitInstant.atZone(java.time.ZoneId.systemDefault());
            java.time.LocalDate commitDate = commitZonedDateTime.toLocalDate();

            java.time.format.DateTimeFormatter timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
            String timeStr = commitZonedDateTime.format(timeFormatter);

            if (commitDate.equals(today)) {
                // If it's today's date, just show the time with "today"
                return "Today " + timeStr;
            } else if (commitDate.equals(today.minusDays(1))) {
                // If it's yesterday
                return "Yesterday " + timeStr;
            } else if (commitDate.isAfter(today.minusDays(7))) {
                // If within the last week, show day of week
                String dayName = commitDate.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault());
                // Ensure proper capitalization (e.g., "Monday" not "MONDAY")
                dayName = Ascii.toUpperCase(dayName.substring(0, 1)) + Ascii.toLowerCase(dayName.substring(1));
                return dayName + " " + timeStr;
            }

            // Otherwise, show the standard date format
            java.time.format.DateTimeFormatter dateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return commitZonedDateTime.format(dateTimeFormatter);
        } catch (Exception e) {
            logger.debug("Could not format date: {}", commitInstant, e);
            return commitInstant.toString();
        }
    }

    // Methods hasFileNodesSelected, getSelectedFilePaths, popStash, applyStash, dropStash
    // were related to UI elements or actions now managed by GitCommitBrowserPanel.

    /**
     * Enables/Disables items in the local-branch context menu based on selection.
     */
    private void checkBranchContextMenuState(JPopupMenu menu) {
        int selectedRow = branchTable.getSelectedRow();
        boolean isAnyItemSelected = (selectedRow != -1);

        // Check if the selected branch is the current branch
        boolean isCurrentBranch = false;
        String selectedBranchName = null;
        if (isAnyItemSelected) {
            // Ensure row is valid before accessing model
            if (selectedRow < branchTableModel.getRowCount()) {
                String checkmark = (String) branchTableModel.getValueAt(selectedRow, 0);
                isCurrentBranch = "✓".equals(checkmark);
                selectedBranchName = (String) branchTableModel.getValueAt(selectedRow, 1);
            } else {
                isAnyItemSelected = false; // Treat as no item selected if row is invalid
            }
        }

        // renameItem and deleteItem checks
        boolean isStashesSelected = "stashes".equals(selectedBranchName);
        menu.getComponent(4).setEnabled(isAnyItemSelected && !isStashesSelected); // renameItem
        menu.getComponent(5).setEnabled(isAnyItemSelected && !isCurrentBranch && !isStashesSelected); // deleteItem


        if (isAnyItemSelected && selectedBranchName != null && !isStashesSelected) {
            captureDiffVsBranchItem.setText("Capture Diff vs " + selectedBranchName);
            captureDiffVsBranchItem.setEnabled(!isCurrentBranch);
        } else {
            captureDiffVsBranchItem.setText("Capture Diff vs Branch");
            captureDiffVsBranchItem.setEnabled(false);
        }
    }

    /**
     * Reloads the commits for the currently selected branch or context in the GitCommitBrowserPanel.
     * This is typically called when clearing a search to restore the previous view.
     */
    private void performRemoteBranchAction(java.util.function.Consumer<String> action) {
        int selectedRow = remoteBranchTable.getSelectedRow();
        if (selectedRow != -1) {
            String remoteBranchName = (String) remoteBranchTableModel.getValueAt(selectedRow, 0);
            action.accept(remoteBranchName);
        }
    }

    private void captureDiffVsRemoteBranch(String selectedRemoteBranch) {
        try {
            String currentActualBranch = getRepo().getCurrentBranch();
            GitUiUtil.captureDiffBetweenBranches(contextManager, chrome, currentActualBranch, selectedRemoteBranch);
        } catch (Exception ex) {
            logger.error("Could not get current branch for diff operation", ex);
            chrome.toolError("Failed to determine current branch. Cannot perform diff. Error: " + ex.getMessage(), "Error");
        }
    }

    private void reloadCurrentBranchOrContext() {
        SwingUtil.runOnEdt(() -> {
            int localSelectedRow = branchTable.getSelectedRow();
            if (localSelectedRow != -1) {
                String branchName = (String) branchTableModel.getValueAt(localSelectedRow, 1);
                updateCommitsForBranch(branchName);
                return;
            }

            int remoteSelectedRow = remoteBranchTable.getSelectedRow();
            if (remoteSelectedRow != -1) {
                String branchName = (String) remoteBranchTableModel.getValueAt(remoteSelectedRow, 0);
                updateCommitsForBranch(branchName);
                return;
            }

            // If neither local nor remote branch is selected, clear the commit view.
            logger.warn("reloadCurrentBranchOrContext called but no branch selected. Clearing commit view.");
            gitCommitBrowserPanel.clearCommitView();
        });
    }

    /**
     * Selects the current branch in the branch table.
     * Called after PR checkout to highlight the new branch.
     */
    public void selectCurrentBranch() {
        contextManager.submitBackgroundTask("Finding current branch", () -> {
            try {
                var currentBranch = getRepo().getCurrentBranch();
                SwingUtilities.invokeLater(() -> {
                    // Find and select the current branch in the branches table
                    for (int i = 0; i < branchTableModel.getRowCount(); i++) {
                        String branchName = (String) branchTableModel.getValueAt(i, 1);
                        if (branchName.equals(currentBranch)) {
                            branchTable.setRowSelectionInterval(i, i);
                            branchTable.scrollRectToVisible(branchTable.getCellRect(i, 0, true));
                            updateCommitsForBranch(currentBranch);
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("Error selecting current branch", e);
            }
            return null;
        });
    }

    /**
     * Selects a commit in the commits table by its ID.
     */
    public void selectCommitById(String commitId) {
        gitCommitBrowserPanel.selectCommitById(commitId);
    }

    // The orphaned line "chrome.systemOutput(...)" and extra brace were removed from here.
}
