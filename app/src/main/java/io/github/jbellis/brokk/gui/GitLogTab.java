package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitRepo.MergeMode;
import io.github.jbellis.brokk.git.ICommitInfo;
import io.github.jbellis.brokk.gui.components.LoadingButton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ProgressMonitor;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.Duration;

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
    private static final String STASHES_VIRTUAL_BRANCH = "stashes";

    private final Chrome chrome;
    private final ContextManager contextManager;

    /**
     * Schedules a refresh shortly after midnight so relative dates stay correct.
     */
    private final ScheduledExecutorService midnightRefreshScheduler;

    // Branches
    private JTable branchTable;
    private AbstractTableModel branchTableModel;
    private JTable remoteBranchTable;
    private DefaultTableModel remoteBranchTableModel;

    // Tags
    private JTable tagsTable;
    private DefaultTableModel tagsTableModel;

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

        this.midnightRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GitLogTab-MidnightRefresh");
            t.setDaemon(true);
            return t;
        });
        scheduleMidnightRefresh();
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
        branchTableModel = new LocalBranchTableModel();
        branchTable = new JTable(branchTableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (column == 1 && row >= 0 && row < getRowCount()) {
                    String branchName = (String) getValueAt(row, 1);
                    if (STASHES_VIRTUAL_BRANCH.equals(branchName)) {
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
        branchTable.setSurrendersFocusOnKeystroke(true);
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

        // Tags panel
        JPanel tagsPanel = new JPanel(new BorderLayout());
        tagsTableModel = new DefaultTableModel(new Object[]{"Tag"}, 0) {
            @Override public boolean isCellEditable(int r,int c){return false;}
            @Override public Class<?> getColumnClass(int i){return String.class;}
        };
        tagsTable = new JTable(tagsTableModel);
        tagsTable.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
        tagsTable.setRowHeight(18);
        tagsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tagsPanel.add(new JScrollPane(tagsTable), BorderLayout.CENTER);

        branchTabbedPane.addTab("Tags", tagsPanel);
        branchesPanel.add(branchTabbedPane, BorderLayout.CENTER);

        JPanel branchButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var fetchButton = new LoadingButton("Fetch",
                                            null, // no idle icon
                                            chrome,
                                            null); // ActionListener added below
        branchButtonPanel.add(fetchButton);
        branchesPanel.add(branchButtonPanel, BorderLayout.SOUTH);

        fetchButton.addActionListener(e -> {
            // Immediate visual feedback
            fetchButton.setLoading(true, "Fetching…");

            contextManager.submitBackgroundTask("Fetching all remotes", () -> {
                try {
                    var pm = new IoProgressMonitor(contextManager.getIo());
                    getRepo().fetchAll(pm); // network call
                    contextManager.getIo().systemOutput("Fetch finished");
                } catch (GitAPIException ex) {
                    contextManager.getIo().systemOutput("Fetch failed: " + ex.getMessage());
                    logger.warn("Fetch failed", ex);
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        fetchButton.setLoading(false, null); // restore label + enable
                        update();                            // local rescan
                    });
                }
                return null;       // submitBackgroundTask expects a Callable result
            });
        });

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
                gitCommitBrowserPanel.clearSearchField(); // Clear search in panel
            }
        });
        remoteBranchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && remoteBranchTable.getSelectedRow() != -1) {
                String branchName = (String) remoteBranchTableModel.getValueAt(remoteBranchTable.getSelectedRow(), 0);
                branchTable.clearSelection();
                updateCommitsForBranch(branchName);
                gitCommitBrowserPanel.clearSearchField(); // Clear search in panel
            }
        });

        // Local branch context menu
        JPopupMenu branchContextMenu = new JPopupMenu();
        chrome.themeManager.registerPopupMenu(branchContextMenu);
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
                String branchToMerge = (String) branchTableModel.getValueAt(selectedRow, 1);
                if (STASHES_VIRTUAL_BRANCH.equals(branchToMerge)) {
                    chrome.toolError("Cannot merge the '" + STASHES_VIRTUAL_BRANCH + "' virtual entry.", "Merge Error");
                    return;
                }
                showMergeDialog(branchToMerge);
            }
        });
        captureDiffVsBranchItem.addActionListener(e -> {
            int row = branchTable.getSelectedRow();
            if (row != -1) {
                String selectedBranch = (String) branchTableModel.getValueAt(row, 1);
                if (STASHES_VIRTUAL_BRANCH.equals(selectedBranch)) return;

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
            if (selectedRow != -1 && branchTable.isCellEditable(selectedRow, 1)) {
                branchTable.editCellAt(selectedRow, 1);
                var editor = branchTable.getEditorComponent();
                if (editor != null) {
                    editor.requestFocusInWindow();
                }
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
            chrome.themeManager.registerPopupMenu(remoteBranchContextMenu);
        });
        JMenuItem remoteCheckoutItem = new JMenuItem("Checkout");
        JMenuItem remoteNewBranchItem = new JMenuItem("New Branch From This");
        JMenuItem remoteMergeItem = new JMenuItem(); // text set dynamically
        JMenuItem remoteDiffItem = new JMenuItem("Capture Diff vs Branch");

        remoteBranchContextMenu.add(remoteCheckoutItem);
        remoteBranchContextMenu.add(remoteNewBranchItem);
        remoteBranchContextMenu.add(remoteMergeItem);
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
                    String currentBranch = null;
                    try {
                        currentBranch = getRepo().getCurrentBranch();
                    } catch (Exception ex) {
                        logger.error("Could not get current branch for remote context menu", ex);
                        // currentBranch remains null
                    }
                    if (row >= 0 && currentBranch != null) {
                        remoteMergeItem.setText("Merge into " + currentBranch);
                        remoteMergeItem.setEnabled(true);
                    } else {
                        remoteMergeItem.setText("Merge into...");
                        remoteMergeItem.setEnabled(false);
                    }
                    SwingUtilities.invokeLater(() -> {
                        remoteBranchContextMenu.show(remoteBranchTable, e.getX(), e.getY());
                    });
                }
            }
        });

        remoteCheckoutItem.addActionListener(e -> performRemoteBranchAction(this::checkoutBranch));
        remoteNewBranchItem.addActionListener(e -> performRemoteBranchAction(this::createNewBranchFrom));
        remoteMergeItem.addActionListener(e -> performRemoteBranchAction(this::showMergeDialog));
        remoteDiffItem.addActionListener(e -> performRemoteBranchAction(this::captureDiffVsRemoteBranch));

        // Tag selection
        tagsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && tagsTable.getSelectedRow() != -1) {
                String tag = (String) tagsTableModel.getValueAt(tagsTable.getSelectedRow(), 0);
                // Clear any branch selections so the UI reflects only the tag context
                branchTable.clearSelection();
                remoteBranchTable.clearSelection();
                updateCommitsForBranch(tag);
                gitCommitBrowserPanel.clearSearchField(); // Clear search in panel
            }
        });

        // Tags context menu
        JPopupMenu tagContextMenu = new JPopupMenu();
        chrome.themeManager.registerPopupMenu(tagContextMenu);
        JMenuItem tagNewBranchItem = new JMenuItem("New Branch From This");
        tagContextMenu.add(tagNewBranchItem);

        tagsTable.addMouseListener(new MouseAdapter(){
            private void handle(MouseEvent e){
                if(e.isPopupTrigger()){
                    int r=tagsTable.rowAtPoint(e.getPoint());
                    if(r>=0&&!tagsTable.isRowSelected(r))
                        tagsTable.setRowSelectionInterval(r,r);
                    SwingUtilities.invokeLater(
                            ()->tagContextMenu.show(tagsTable,e.getX(),e.getY()));
                }
            }
            @Override public void mousePressed(MouseEvent e){handle(e);}
            @Override public void mouseReleased(MouseEvent e){handle(e);}
        });
        tagNewBranchItem.addActionListener(e->{
            int r=tagsTable.getSelectedRow();
            if(r!=-1){
                String tag=(String)tagsTableModel.getValueAt(r,0);
                createNewBranchFrom(tag);
            }
        });
    }

    /**
     * Schedule a daily refresh right after local midnight so that formatted
     * dates like "Today" and "Yesterday" stay current.
     */
    private void scheduleMidnightRefresh() {
        var now = ZonedDateTime.now(ZoneId.systemDefault());
        var nextMidnight = now.plusDays(1).truncatedTo(ChronoUnit.DAYS);
        long initialDelayMs = Duration.between(now, nextMidnight).toMillis() + 1;

        midnightRefreshScheduler.scheduleAtFixedRate(this::reloadCurrentBranchOrContext,
                                                     initialDelayMs,
                                                     TimeUnit.DAYS.toMillis(1),
                                                     TimeUnit.MILLISECONDS);
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
                List<String> tags = getRepo().listTags();

                // Prepare data rows off the EDT
                List<Object[]> localBranchRows = new ArrayList<>();
                int targetSelectionIndex = -1; // Index to select after update
                String targetBranchToSelect = previouslySelectedBranch; // Prioritize previous selection

                // Add virtual stashes entry first if stashes exist
                boolean hasStashes = false;
                try {
                    // Just check if the list is non-empty, avoid fetching full info yet
                    hasStashes = !getRepo().listStashes().isEmpty();
                    if (hasStashes) {
                        localBranchRows.add(new Object[]{"", STASHES_VIRTUAL_BRANCH});
                        if (STASHES_VIRTUAL_BRANCH.equals(targetBranchToSelect)) {
                            targetSelectionIndex = 0; // If stashes was selected, mark its index
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

                List<Object[]> tagRows = tags.stream()
                        .map(t -> new Object[]{t})
                        .toList();

                var branchRows = localBranchRows.stream()
                        .map(arr -> new LocalBranchTableModel.BranchRow((String) arr[0], (String) arr[1]))
                        .toList();

                final int finalTargetSelectionIndex = targetSelectionIndex;
                final String finalSelectedBranchName = targetBranchToSelect; // The branch name we actually selected/targeted

                SwingUtilities.invokeLater(() -> {
                    // gitCommitBrowserPanel.clearCommitView(); // This will be handled by updateCommitsForBranch or if no branch selected

                    ((LocalBranchTableModel) branchTableModel).setRows(branchRows);
                    remoteBranchTableModel.setRowCount(0);
                    tagsTableModel.setRowCount(0);

                    for (var rowData : remoteBranchRows) {
                        remoteBranchTableModel.addRow(rowData);
                    }
                    for (var rowData : tagRows) {
                        tagsTableModel.addRow(rowData);
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
                    var errorRow = new LocalBranchTableModel.BranchRow("", "Error fetching branches: " + e.getMessage());
                    ((LocalBranchTableModel) branchTableModel).setRows(List.of(errorRow));
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
                if (STASHES_VIRTUAL_BRANCH.equals(branchName)) {
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

    private void showMergeDialog(String branchToMerge) {
        String currentBranch;
        try {
            currentBranch = getRepo().getCurrentBranch();
        } catch (GitAPIException e) {
            logger.error("Could not get current branch for merge dialog", e);
            chrome.toolError("Could not determine current branch: " + e.getMessage(), "Merge Error");
            return;
        }

        if (branchToMerge.equals(currentBranch)) {
            chrome.systemNotify("Cannot merge '" + branchToMerge + "' into itself.", "Merge Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        var parentFrame = (Frame) SwingUtilities.getWindowAncestor(this);
        var dialogPanel = new MergeBranchDialogPanel(parentFrame, branchToMerge, currentBranch);
        var result = dialogPanel.showDialog(getRepo(), contextManager);

        if (result.confirmed()) {
            if (!result.hasConflicts()) {
                mergeBranchIntoHead(branchToMerge, result.mergeMode());
            } else {
                chrome.toolError("Merge cancelled due to conflicts or error: " + result.conflictMessage(), "Merge Cancelled");
            }
        }
    }

    /**
     * Merge a branch into HEAD using the specified mode.
     */
    private void mergeBranchIntoHead(String branchName, MergeMode mode) {
        contextManager.submitUserTask("Merging branch: " + branchName + " (" + mode + ")", () -> {
            var repo = getRepo();

            try {
                String targetBranch = repo.getCurrentBranch();
                var mergeResult = repo.performMerge(branchName, mode);
                var status = mergeResult.getMergeStatus();

                if (GitRepo.isMergeSuccessful(mergeResult, mode)) {
                    String modeDescription = switch (mode) {
                        case MERGE_COMMIT -> "merged";
                        case SQUASH_COMMIT -> "squash merged";
                        case REBASE_MERGE -> "rebase-merged";
                    };
                    chrome.systemOutput("Branch '" + branchName + "' successfully " + modeDescription + " into '" + targetBranch + "'.");
                } else if (status == MergeResult.MergeStatus.CONFLICTING) {
                    String conflictingFiles = Objects.requireNonNull(mergeResult.getConflicts()).keySet().stream()
                            .map(s -> "  - " + s)
                            .collect(Collectors.joining("\n"));
                    chrome.toolError("Merge conflicts for '" + branchName + "' into '" + targetBranch + "'.\n" +
                                     "Resolve manually and commit.\nConflicting files:\n" + conflictingFiles, "Merge Conflict");
                } else {
                    chrome.toolError("Merge of '" + branchName + "' into '" + targetBranch + "' failed: " + status, "Merge Error");
                }

                update(); // Refresh UI to reflect new state
            } catch (GitAPIException e) {
                logger.error("Error merging branch '{}' with mode {}: {}", branchName, mode, e.getMessage(), e);
                chrome.toolError("Error merging branch: " + e.getMessage(), "Merge Error");
                update(); // Refresh UI
            }
            return null;
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
        boolean isStashesSelected = STASHES_VIRTUAL_BRANCH.equals(selectedBranchName);
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

            int tagSelectedRow = tagsTable.getSelectedRow();
            if (tagSelectedRow != -1) {
                String tagName = (String) tagsTableModel.getValueAt(tagSelectedRow, 0);
                updateCommitsForBranch(tagName);
                return;
            }

            // If neither local nor remote branch nor tag is selected, clear the commit view.
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

    /**
     * A JGit ProgressMonitor that sends updates to an IConsoleIO systemOutput.
     * This class is nested here as it's closely tied to UI feedback via IConsoleIO,
     * often provided by UI components like Chrome.
     */
    public static final class IoProgressMonitor implements ProgressMonitor {
        private final IConsoleIO io;

        /**
         * Constructs a new IoProgressMonitor.
         * @param io The IConsoleIO instance to output progress messages to. Must not be null.
         */
        public IoProgressMonitor(IConsoleIO io) {
            this.io = Objects.requireNonNull(io, "IConsoleIO cannot be null");
        }

        @Override
        public void start(int totalTasks) {
            // This basic monitor does not use this information.
        }

        @Override
        public void beginTask(String title, int totalWork) {
            io.systemOutput("Fetch: " + title);
        }

        @Override
        public void update(int completed) {
            // This basic monitor does not report granular updates.
        }

        @Override
        public void endTask() {
            // This basic monitor does not use this information.
        }

        @Override
        public boolean isCancelled() {
            return false; // This monitor does not support cancellation.
        }

        @Override
        public void showDuration(boolean enabled) {
            // This basic monitor does not use this information.
        }
    }

    /**
     * A custom table model for the local branches table that allows for inline renaming of branches.
     */
    private class LocalBranchTableModel extends AbstractTableModel {
        private static final int COL_MARK = 0;
        private static final int COL_NAME = 1;

        record BranchRow(String mark, String name) {}
        private List<BranchRow> rows = List.of();

        public void setRows(List<BranchRow> newRows) {
            this.rows = List.copyOf(newRows);
            fireTableDataChanged();
        }

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
            return column == COL_MARK ? "" : "Branch";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var row = rows.get(rowIndex);
            return columnIndex == COL_MARK ? row.mark() : row.name();
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            if (column == COL_NAME) {
                String branchName = rows.get(row).name();
                return !STASHES_VIRTUAL_BRANCH.equals(branchName);
            }
            return false;
        }

        @Override
        public void setValueAt(Object aValue, int row, int column) {
            if (column != COL_NAME) {
                return;
            }

            String oldName = rows.get(row).name();
            String newName = ((String) aValue).trim();

            if (newName.isEmpty() || newName.equals(oldName)) {
                return; // No change, do nothing.
            }

            contextManager.submitUserTask("Renaming branch: " + oldName, () -> {
                try {
                    getRepo().renameBranch(oldName, newName);
                    SwingUtilities.invokeLater(() -> {
                        chrome.systemOutput("Branch '" + oldName + "' renamed to '" + newName + "' successfully.");
                        // On success, a full update ensures UI consistency (e.g., current branch checkmark moves).
                        update();
                    });
                } catch (GitAPIException e) {
                    SwingUtilities.invokeLater(() -> {
                        logger.error("Error renaming branch '{}' to '{}'", oldName, newName, e);
                        chrome.toolError("Error renaming branch: " + e.getMessage());
                        // On failure, update() will restore the correct state from git.
                        update();
                    });
                }
            });
        }
    }
}
