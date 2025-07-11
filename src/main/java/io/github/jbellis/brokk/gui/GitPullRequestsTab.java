package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.context.ContextFragment.StringFragment;
import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.ICommitInfo;
import io.github.jbellis.brokk.gui.components.GitHubTokenMissingPanel;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.RefSpec;
import io.github.jbellis.brokk.util.SyntaxDetector;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHUser;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.ArrayList;

import static java.util.Objects.requireNonNull;

public class GitPullRequestsTab extends JPanel implements SettingsChangeListener {
    private static final Logger logger = LogManager.getLogger(GitPullRequestsTab.class);
    private static final int MAX_TOOLTIP_FILES = 15;

    // PR Table Column Indices
    private static final int PR_COL_NUMBER  = 0;
    private static final int PR_COL_TITLE   = 1;
    private static final int PR_COL_AUTHOR  = 2;
    private static final int PR_COL_UPDATED = 3;
    private static final int PR_COL_BASE    = 4;
    private static final int PR_COL_FORK    = 5;
    private static final int PR_COL_STATUS  = 6;

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel;

    private final Set<Future<?>> futuresToBeCancelledOnGutHubTokenChange = ConcurrentHashMap.newKeySet();

    private JTable prTable;
    private DefaultTableModel prTableModel;
    private JTable prCommitsTable;
    private DefaultTableModel prCommitsTableModel;
    private JButton viewPrDiffButton; 

    // Context Menu Items for prTable
    private JMenuItem checkoutPrMenuItem;
    private JMenuItem diffPrVsBaseMenuItem;
    private JMenuItem viewPrDiffMenuItem;
    private JMenuItem capturePrDiffMenuItemContextMenu; // Renamed to avoid clash
    private JMenuItem openPrInBrowserMenuItem;

    // Context Menu Items for prCommitsTable
    private JMenuItem capturePrCommitDiffMenuItem;
    private JMenuItem viewPrCommitDiffMenuItem;
    private JMenuItem comparePrCommitToLocalMenuItem;


    private FilterBox statusFilter;
    private FilterBox authorFilter;
    private FilterBox labelFilter;
    private FilterBox assigneeFilter;
    private FilterBox reviewFilter;
    private JButton refreshPrButton;

    private final GitHubTokenMissingPanel gitHubTokenMissingPanel;

    private List<org.kohsuke.github.GHPullRequest> allPrsFromApi = new ArrayList<>();
    private List<org.kohsuke.github.GHPullRequest> displayedPrs = new ArrayList<>();
    private final Map<Integer, String> ciStatusCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<ICommitInfo>> prCommitsCache = new ConcurrentHashMap<>();
    private List<ICommitInfo> currentPrCommitDetailsList = new ArrayList<>();
    private JTable prFilesTable;
    private DefaultTableModel prFilesTableModel;

    @Nullable
    private SwingWorker<Map<Integer, String>, Void> activeCiFetcher;
    @Nullable
    private SwingWorker<Map<Integer, List<String>>, Void> activePrFilesFetcher;

    /**
     * Checks if a commit's data is fully available and parsable in the local repository.
     *
     * @param repo The GitRepo instance.
     * @param sha  The commit SHA to check.
     * @return true if the commit is resolvable and its object data is parsable, false otherwise.
     */
    private static boolean isCommitLocallyAvailable(GitRepo repo, String sha) {
        org.eclipse.jgit.lib.ObjectId objectId = null;
        try {
            objectId = repo.resolve(sha);
            // Try to parse the commit to ensure its data is present
            try (org.eclipse.jgit.revwalk.RevWalk revWalk = new org.eclipse.jgit.revwalk.RevWalk(repo.getGit().getRepository())) {
                revWalk.parseCommit(objectId);
                return true; // Resolvable and parsable
            }
        } catch (org.eclipse.jgit.errors.MissingObjectException e) {
            logger.debug("Commit object for SHA {} (resolved to {}) is missing locally.", GitUiUtil.shortenCommitId(sha), objectId.name(), e);
            return false; // Resolvable but data is missing
        } catch (IOException | GitAPIException e) { // GitAPIException from repo.resolve, IOException from parseCommit (other than MissingObjectException)
            logger.warn("Error checking local availability of commit {}: {}", GitUiUtil.shortenCommitId(sha), e.getMessage(), e);
            return false; // Error during check, assume not available
        }
    }


    /**
     * Ensure a commit SHA is available locally and is fully parsable, fetching the specified refSpec from the remote if necessary.
     *
     * @param sha         The commit SHA that must be present and parsable locally.
     * @param repo        GitRepo to operate on (non-null).
     * @param refSpec     The refSpec to fetch if the SHA is missing or not parsable (e.g. "+refs/pull/123/head:refs/remotes/origin/pr/123/head").
     * @param remoteName  Which remote to fetch from (e.g. "origin").
     * @return true if the SHA is now locally available and parsable, false otherwise.
     */
    private static boolean ensureShaIsLocal(GitRepo repo, String sha, String refSpec, String remoteName) {
        if (isCommitLocallyAvailable(repo, sha)) {
            return true;
        }

        // If not available or missing, try to fetch
        logger.debug("SHA {} not fully available locally - fetching {} from {}", GitUiUtil.shortenCommitId(sha), refSpec, remoteName);
        try {
            repo.getGit().fetch()
                    .setRemote(remoteName)
                    .setRefSpecs(new RefSpec(refSpec))
                    .call();
            // After fetch, verify again
            if (isCommitLocallyAvailable(repo, sha)) {
                logger.debug("Successfully fetched and verified SHA {}", GitUiUtil.shortenCommitId(sha));
                return true;
            } else {
                logger.warn("Failed to make SHA {} fully available locally even after fetching {} from {}", GitUiUtil.shortenCommitId(sha), refSpec, remoteName);
                return false;
            }
        } catch (Exception e) {
            // Includes GitAPIException, IOException, etc.
            logger.warn("Error during fetch operation in ensureShaIsLocal for SHA {}: {}", GitUiUtil.shortenCommitId(sha), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Logs an error and shows a toolErrorRaw message to the user.
     */
    private void reportBackgroundError(String contextMessage, Throwable e) {
        chrome.toolError(contextMessage + (e.getMessage() != null ? ": " + e.getMessage() : ""));
    }


    // Store default options for static filters to easily reset them
    private static final List<String> STATUS_FILTER_OPTIONS = List.of("Open", "Closed"); // "All" is null selection
    private static final List<String> REVIEW_FILTER_OPTIONS = List.of(
            "No reviews", "Required", "Approved", "Changes requested",
            "Reviewed by you", "Not reviewed by you", "Awaiting review from you"
    ); // "All" is null selection

    // Lists to hold choices for dynamic filters
    private List<String> authorChoices = new ArrayList<>();
    private List<String> labelChoices = new ArrayList<>();
    private List<String> assigneeChoices = new ArrayList<>();


    public GitPullRequestsTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel)
    {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.gitPanel = gitPanel;

        // Split panel with PRs on left (larger) and commits on right (smaller)
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.6); // 60% for PR list, 40% for commits

        // --- Left side - Pull Requests table and filters ---
        JPanel mainPrAreaPanel = new JPanel(new BorderLayout(0, Constants.V_GAP)); // Use BorderLayout for overall structure
        mainPrAreaPanel.setBorder(BorderFactory.createTitledBorder("Pull Requests"));

        // Panel for missing token message
        gitHubTokenMissingPanel = new GitHubTokenMissingPanel(chrome);
        JPanel tokenPanelWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        tokenPanelWrapper.add(gitHubTokenMissingPanel);
        mainPrAreaPanel.add(tokenPanelWrapper, BorderLayout.NORTH);

        // Panel to hold filters (WEST) and table+buttons (CENTER)
        JPanel centerContentPanel = new JPanel(new BorderLayout(Constants.H_GAP, 0));

        // Vertical Filter Panel
        JPanel verticalFilterPanel = new JPanel(new BorderLayout());
        JPanel filtersContainer = new JPanel();
        filtersContainer.setLayout(new BoxLayout(filtersContainer, BoxLayout.Y_AXIS));
        filtersContainer.setBorder(BorderFactory.createEmptyBorder(0, Constants.H_PAD, 0, Constants.H_PAD));

        JLabel filterLabel = new JLabel("Filter:");
        filterLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        filtersContainer.add(filterLabel);
        filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP)); // Space after label

        statusFilter = new FilterBox(this.chrome, "Status", () -> STATUS_FILTER_OPTIONS, "Open");
        statusFilter.setToolTipText("Filter by PR status");
        statusFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusFilter.addPropertyChangeListener("value", e -> {
            // Status filter change triggers a new API fetch
            updatePrList();
        });
        filtersContainer.add(statusFilter);

        authorFilter = new FilterBox(this.chrome, "Author", this::getAuthorFilterOptions);
        authorFilter.setToolTipText("Filter by author");
        authorFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        authorFilter.addPropertyChangeListener("value", e -> filterAndDisplayPrs());
        filtersContainer.add(authorFilter);

        labelFilter = new FilterBox(this.chrome, "Label", this::getLabelFilterOptions);
        labelFilter.setToolTipText("Filter by label");
        labelFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        labelFilter.addPropertyChangeListener("value", e -> filterAndDisplayPrs());
        filtersContainer.add(labelFilter);

        assigneeFilter = new FilterBox(this.chrome, "Assignee", this::getAssigneeFilterOptions);
        assigneeFilter.setToolTipText("Filter by assignee");
        assigneeFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        assigneeFilter.addPropertyChangeListener("value", e -> filterAndDisplayPrs());
        filtersContainer.add(assigneeFilter);

        reviewFilter = new FilterBox(this.chrome, "Review", () -> REVIEW_FILTER_OPTIONS);
        reviewFilter.setToolTipText("Filter by review status (Note: Some options may be placeholders)");
        reviewFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        reviewFilter.addPropertyChangeListener("value", e -> filterAndDisplayPrs());
        filtersContainer.add(reviewFilter);

        verticalFilterPanel.add(filtersContainer, BorderLayout.NORTH);
        centerContentPanel.add(verticalFilterPanel, BorderLayout.WEST);

        // Panel for PR Table (CENTER) and PR Buttons (SOUTH)
        JPanel prTableAndButtonsPanel = new JPanel(new BorderLayout());

        // PR Table
        prTableModel = new DefaultTableModel(new Object[]{"#", "Title", "Author", "Updated", "Base", "Fork", "Status"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        prTable = new JTable(prTableModel);
        prTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        prTable.setRowHeight(18);
        prTable.getColumnModel().getColumn(PR_COL_NUMBER).setPreferredWidth(50);  // #
        prTable.getColumnModel().getColumn(PR_COL_TITLE).setPreferredWidth(400); // Title
        prTable.getColumnModel().getColumn(PR_COL_AUTHOR).setPreferredWidth(120); // Author
        prTable.getColumnModel().getColumn(PR_COL_UPDATED).setPreferredWidth(120); // Updated
        prTable.getColumnModel().getColumn(PR_COL_BASE).setPreferredWidth(120); // Base
        prTable.getColumnModel().getColumn(PR_COL_FORK).setPreferredWidth(120); // Fork
        prTable.getColumnModel().getColumn(PR_COL_STATUS).setPreferredWidth(70);  // Status


        prTableAndButtonsPanel.add(new JScrollPane(prTable), BorderLayout.CENTER);

        // Button panel for PRs
        JPanel prButtonPanel = new JPanel();
        prButtonPanel.setBorder(BorderFactory.createEmptyBorder(Constants.V_GLUE, 0, 0, 0));
        prButtonPanel.setLayout(new BoxLayout(prButtonPanel, BoxLayout.X_AXIS));

        viewPrDiffButton = new JButton("View Diff"); // This button remains
        viewPrDiffButton.setToolTipText("View all changes in this PR in a diff viewer");
        viewPrDiffButton.setEnabled(false);
        viewPrDiffButton.addActionListener(e -> viewFullPrDiff());
        prButtonPanel.add(viewPrDiffButton);

        prButtonPanel.add(Box.createHorizontalGlue()); // Pushes refresh button to the right

        refreshPrButton = new JButton("Refresh");
        refreshPrButton.addActionListener(e -> updatePrList());
        prButtonPanel.add(refreshPrButton);

        prTableAndButtonsPanel.add(prButtonPanel, BorderLayout.SOUTH);
        setupPrTableContextMenu();
        setupPrTableDoubleClick();
        centerContentPanel.add(prTableAndButtonsPanel, BorderLayout.CENTER); // Add to centerContentPanel
        mainPrAreaPanel.add(centerContentPanel, BorderLayout.CENTER); // Add centerContentPanel to main panel


        // Right side - Commits and Files in the selected PR
        JPanel prCommitsAndFilesPanel = new JPanel(new BorderLayout());
        prCommitsAndFilesPanel.setBorder(BorderFactory.createTitledBorder("Pull Request Details"));

        // Create vertical split pane for commits (top) and files (bottom)
        JSplitPane rightSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplitPane.setResizeWeight(0.5); // 50% for commits, 50% for files

        // Commits panel
        JPanel prCommitsPanel = new JPanel(new BorderLayout());
        prCommitsPanel.setBorder(BorderFactory.createTitledBorder("Commits"));

        prCommitsTableModel = new DefaultTableModel(new Object[]{"Message"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        prCommitsTable = new JTable(prCommitsTableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                Point p = event.getPoint();
                int viewRow = rowAtPoint(p);
                if (viewRow >= 0 && viewRow < getRowCount()) {
                    int modelRow = convertRowIndexToModel(viewRow);
                    if (modelRow >= 0 && modelRow < prCommitsTableModel.getRowCount()) {
                        if (modelRow < currentPrCommitDetailsList.size()) {
                            var commitInfo = currentPrCommitDetailsList.get(modelRow);
                            try {
                                var projectFiles = commitInfo.changedFiles();
                                // projectFiles from ICommitInfo.changedFiles -> GitRepo.listFilesChangedInCommit
                                // is guaranteed to return at least List.of(), not null.
                                // So, a null check on projectFiles is not strictly necessary here based on current impl.
                                List<String> files = projectFiles.stream()
                                        .map(Object::toString)
                                        .collect(Collectors.toList());

                                if (files.isEmpty()) {
                                    return "No files changed in this commit.";
                                }
                                var tooltipBuilder = new StringBuilder("<html>Changed files:<br>");
                                List<String> filesToShow = files;
                                if (files.size() > MAX_TOOLTIP_FILES) {
                                    filesToShow = files.subList(0, MAX_TOOLTIP_FILES);
                                    tooltipBuilder.append(String.join("<br>", filesToShow));
                                    tooltipBuilder.append("<br>...and ").append(files.size() - MAX_TOOLTIP_FILES).append(" more files.");
                                } else {
                                    tooltipBuilder.append(String.join("<br>", filesToShow));
                                }
                                tooltipBuilder.append("</html>");
                                return tooltipBuilder.toString();
                            } catch (GitAPIException e) {
                                logger.warn("Could not get changed files for PR commit tooltip ({}): {}", commitInfo.id(), e.getMessage());
                                return "Error loading changed files for this commit.";
                            }
                        } else {
                            Object cellValue = prCommitsTableModel.getValueAt(modelRow, 0); // Get message from table itself
                            if (cellValue instanceof String s) {
                                return s; // E.g., "Loading..." or "Error..."
                            }
                            logger.trace("Tooltip: modelRow {} out of sync with currentPrCommitDetailsList size {} for prCommitsTable",
                                         modelRow, currentPrCommitDetailsList.size());
                        }
                    }
                }
                return super.getToolTipText(event); // Or null
            }
        };
        prCommitsTable.setTableHeader(null);
        prCommitsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        prCommitsTable.setRowHeight(18);

        // Message column should take up all available width
        prCommitsTable.getColumnModel().getColumn(0).setPreferredWidth(300); // message

        // Ensure tooltips are enabled for the table
        ToolTipManager.sharedInstance().registerComponent(prCommitsTable);

        prCommitsPanel.add(new JScrollPane(prCommitsTable), BorderLayout.CENTER);

        // Files panel
        JPanel prFilesPanel = new JPanel(new BorderLayout());
        prFilesPanel.setBorder(BorderFactory.createTitledBorder("Changed Files"));

        prFilesTableModel = new DefaultTableModel(new Object[]{"File"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        prFilesTable = new JTable(prFilesTableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                Point p = event.getPoint();
                int viewRow = rowAtPoint(p);
                if (viewRow >= 0 && viewRow < getRowCount()) {
                    int modelRow = convertRowIndexToModel(viewRow);
                    if (modelRow >= 0 && modelRow < prFilesTableModel.getRowCount()) {
                        Object cellValue = prFilesTableModel.getValueAt(modelRow, 0);
                        if (cellValue instanceof String s) {
                            return s;
                        }
                    }
                }
                return super.getToolTipText(event);
            }
        };
        prFilesTable.setTableHeader(null);
        prFilesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        prFilesTable.setRowHeight(18);
        prFilesTable.getColumnModel().getColumn(0).setPreferredWidth(400); // File

        ToolTipManager.sharedInstance().registerComponent(prFilesTable);

        prFilesPanel.add(new JScrollPane(prFilesTable), BorderLayout.CENTER);

        // Add panels to split pane
        rightSplitPane.setTopComponent(prCommitsPanel);
        rightSplitPane.setBottomComponent(prFilesPanel);

        prCommitsAndFilesPanel.add(rightSplitPane, BorderLayout.CENTER);

        // Add the panels to the split pane
        splitPane.setLeftComponent(mainPrAreaPanel);
        splitPane.setRightComponent(prCommitsAndFilesPanel);

        add(splitPane, BorderLayout.CENTER);

        // Listen for PR selection changes
        prTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int viewRow = prTable.getSelectedRow();
                if (viewRow != -1) {
                    int modelRow = prTable.convertRowIndexToModel(viewRow);
                    if (modelRow < 0 || modelRow >= displayedPrs.size()) {
                        logger.warn("Selected row {} (model {}) is out of bounds for displayed PRs size {}", viewRow, modelRow, displayedPrs.size());
                        disablePrButtonsAndClearCommitsAndMenus(); // Updated call
                        return;
                    }
                    GHPullRequest selectedPr = displayedPrs.get(modelRow);
                    int prNumber = selectedPr.getNumber();
                    updateCommitsForPullRequest(selectedPr);
                    updateFilesForPullRequest(selectedPr);

                    // Button state updates
                    boolean singlePrSelected = prTable.getSelectedRowCount() == 1;
                    viewPrDiffButton.setEnabled(singlePrSelected);
                    updatePrTableContextMenuState();

                    this.contextManager.submitBackgroundTask("Updating PR #" + prNumber + " local status", () -> {
                        SwingUtilities.invokeLater(() -> {
                            int currentRow = prTable.getSelectedRow();
                            if (currentRow != -1 && currentRow < displayedPrs.size() && displayedPrs.get(currentRow).getNumber() == prNumber) {
                                updatePrTableContextMenuState(); // This will re-evaluate based on current selection count
                                viewPrDiffButton.setEnabled(prTable.getSelectedRowCount() == 1);
                            }
                        });
                        return null;
                    });
                } else { // No selection or invalid row (viewRow == -1)
                    disablePrButtonsAndClearCommitsAndMenus(); // Updated call
                }
            }
        });

        // Add commit selection listener to update files table
        prCommitsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateFilesForSelectedCommits();
                updatePrCommitsContextMenuState();
            }
        });
        setupPrCommitsTableContextMenu();
        setupPrCommitsTableDoubleClick();

        MainProject.addSettingsChangeListener(this);
        updatePrList(); // async
    }

    private class PrTableDoubleClickAdapter extends MouseAdapter {
        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
                if (prTable.getSelectedRowCount() == 1) {
                    viewFullPrDiff();
                }
            }
        }
    }

    private void setupPrTableDoubleClick() {
        prTable.addMouseListener(new PrTableDoubleClickAdapter());
    }

    private void setupPrTableContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();
        chrome.themeManager.registerPopupMenu(contextMenu);

        capturePrDiffMenuItemContextMenu = new JMenuItem("Capture Diff");
        capturePrDiffMenuItemContextMenu.addActionListener(e -> captureSelectedPrDiff());
        contextMenu.add(capturePrDiffMenuItemContextMenu);

        viewPrDiffMenuItem = new JMenuItem("View Diff");
        viewPrDiffMenuItem.addActionListener(e -> viewFullPrDiff());
        contextMenu.add(viewPrDiffMenuItem);

        diffPrVsBaseMenuItem = new JMenuItem("Diff vs Base");
        diffPrVsBaseMenuItem.addActionListener(e -> diffSelectedPr());
        contextMenu.add(diffPrVsBaseMenuItem);

        checkoutPrMenuItem = new JMenuItem("Check Out");
        checkoutPrMenuItem.addActionListener(e -> checkoutSelectedPr());
        contextMenu.add(checkoutPrMenuItem);

        openPrInBrowserMenuItem = new JMenuItem("Open in Browser");
        openPrInBrowserMenuItem.addActionListener(e -> openSelectedPrInBrowser());
        contextMenu.add(openPrInBrowserMenuItem);

        prTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }
            private void handlePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = prTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        if (!prTable.isRowSelected(row)) {
                            prTable.setRowSelectionInterval(row, row);
                        }
                        updatePrTableContextMenuState(); // Update menu items based on selection
                        contextMenu.show(prTable, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    private void updatePrTableContextMenuState() {
        boolean singlePrSelected = prTable.getSelectedRowCount() == 1;
        boolean anyPrSelected = prTable.getSelectedRowCount() > 0;

        checkoutPrMenuItem.setEnabled(singlePrSelected);
        diffPrVsBaseMenuItem.setEnabled(singlePrSelected); // Assuming this also implies single selection from context
        capturePrDiffMenuItemContextMenu.setEnabled(singlePrSelected); // Assuming this also implies single selection
        viewPrDiffMenuItem.setEnabled(singlePrSelected);
        openPrInBrowserMenuItem.setEnabled(anyPrSelected); // Open in browser can work for multiple if needed, but typically single
    }


    private void setupPrCommitsTableContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();
        chrome.themeManager.registerPopupMenu(contextMenu);

        capturePrCommitDiffMenuItem = new JMenuItem("Capture Diff");
        capturePrCommitDiffMenuItem.addActionListener(e -> {
            int[] selectedViewRows = prCommitsTable.getSelectedRows();
            if (selectedViewRows.length == 0) return;

            int[] selectedModelRows = Arrays.stream(selectedViewRows)
                    .map(prCommitsTable::convertRowIndexToModel)
                    .filter(modelRow -> modelRow >= 0 && modelRow < currentPrCommitDetailsList.size())
                    .sorted()
                    .toArray();

            if (selectedModelRows.length == 0) return;

            var contiguousModelRowGroups = GitUiUtil.groupContiguous(selectedModelRows);

            for (var group : contiguousModelRowGroups) {
                if (group.isEmpty()) continue;

                // currentPrCommitDetailsList is sorted oldest first
                ICommitInfo oldestCommitInGroup = currentPrCommitDetailsList.get(group.getFirst());
                ICommitInfo newestCommitInGroup = currentPrCommitDetailsList.get(group.getLast());

                GitUiUtil.addCommitRangeToContext(contextManager, chrome, newestCommitInGroup, oldestCommitInGroup);
            }
        });
        contextMenu.add(capturePrCommitDiffMenuItem);

        viewPrCommitDiffMenuItem = new JMenuItem("View Diff");
        viewPrCommitDiffMenuItem.addActionListener(e -> {
            if (prCommitsTable.getSelectedRowCount() == 1) {
                int modelRow = prCommitsTable.convertRowIndexToModel(prCommitsTable.getSelectedRow());
                 if (modelRow >= 0 && modelRow < currentPrCommitDetailsList.size()) {
                    ICommitInfo commitInfo = currentPrCommitDetailsList.get(modelRow);
                    GitUiUtil.openCommitDiffPanel(contextManager, chrome, commitInfo);
                }
            }
        });
        contextMenu.add(viewPrCommitDiffMenuItem);

        comparePrCommitToLocalMenuItem = new JMenuItem("Compare to Local");
        comparePrCommitToLocalMenuItem.addActionListener(e -> {
            if (prCommitsTable.getSelectedRowCount() == 1) {
                int modelRow = prCommitsTable.convertRowIndexToModel(prCommitsTable.getSelectedRow());
                if (modelRow >= 0 && modelRow < currentPrCommitDetailsList.size()) {
                    ICommitInfo commitInfo = currentPrCommitDetailsList.get(modelRow);
                    GitUiUtil.compareCommitToLocal(contextManager, chrome, commitInfo);
                }
            }
        });
        contextMenu.add(comparePrCommitToLocalMenuItem);

        prCommitsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }
            private void handlePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = prCommitsTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        if (!prCommitsTable.isRowSelected(row)) {
                            prCommitsTable.setRowSelectionInterval(row, row);
                        }
                        updatePrCommitsContextMenuState();
                        contextMenu.show(prCommitsTable, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    private void updatePrCommitsContextMenuState() {
        int selectedRowCount = prCommitsTable.getSelectedRowCount();
        boolean anyCommitSelected = selectedRowCount > 0;
        boolean singleCommitSelected = selectedRowCount == 1;

        capturePrCommitDiffMenuItem.setEnabled(anyCommitSelected);
        viewPrCommitDiffMenuItem.setEnabled(singleCommitSelected);
        comparePrCommitToLocalMenuItem.setEnabled(singleCommitSelected);
    }

    private void setupPrCommitsTableDoubleClick() {
        prCommitsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = prCommitsTable.rowAtPoint(e.getPoint());
                    if (row != -1) {
                        int modelRow = prCommitsTable.convertRowIndexToModel(row);
                        if (modelRow >=0 && modelRow < currentPrCommitDetailsList.size()) {
                            ICommitInfo commitInfo = currentPrCommitDetailsList.get(modelRow);
                            GitUiUtil.openCommitDiffPanel(contextManager, chrome, commitInfo);
                        }
                    }
                }
            }
        });
    }


    /**
     * Tracks a Future that might contain calls to GitHub API, so that it can be cancelled if GitHub access token changes.
     */
    private void trackCancellableFuture(Future<?> future) {
        futuresToBeCancelledOnGutHubTokenChange.removeIf(Future::isDone);
        futuresToBeCancelledOnGutHubTokenChange.add(future);
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        MainProject.removeSettingsChangeListener(this);
    }

    @Override
    public void gitHubTokenChanged() {
        SwingUtilities.invokeLater(() -> {
            logger.debug("GitHub token changed. Initiating cancellation of active tasks and scheduling refresh.");

            List<Future<?>> futuresToCancelAndAwait = new ArrayList<>();

            if (activeCiFetcher != null && !activeCiFetcher.isDone()) {
                futuresToCancelAndAwait.add(activeCiFetcher);
            }
            if (activePrFilesFetcher != null && !activePrFilesFetcher.isDone()) {
                futuresToCancelAndAwait.add(activePrFilesFetcher);
            }

            futuresToCancelAndAwait.addAll(futuresToBeCancelledOnGutHubTokenChange);

            logger.debug("Attempting to cancel {} futures.", futuresToCancelAndAwait.size());
            for (Future<?> f : futuresToCancelAndAwait) {
                if (!f.isDone()) {
                    f.cancel(true);
                    logger.trace("Requested cancellation for future: {}", f.toString());
                }
            }

            if (futuresToCancelAndAwait.isEmpty()) {
                logger.debug("No active tasks to wait for. Proceeding with PR list refresh directly.");
                updatePrList();
                return;
            }

            // Wait for the futures to complete or be cancelled to avoid potential race conditions
            contextManager.submitBackgroundTask("Finalizing cancellations and refreshing PR data", () -> {
                logger.debug("Waiting for {} futures to complete cancellation.", futuresToCancelAndAwait.size());
                for (Future<?> f : futuresToCancelAndAwait) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        logger.trace("Task cancellation confirmed for: {}", f.toString());
                    }
                }
                logger.debug("All identified tasks have completed cancellation. Scheduling PR list refresh.");
                SwingUtilities.invokeLater(this::updatePrList);
                return null;
            });
        });
    }

    private void disablePrButtonsAndClearCommitsAndMenus() {
        // Panel buttons
        viewPrDiffButton.setEnabled(false);

        // Context menu items for prTable (if initialized)
        checkoutPrMenuItem.setEnabled(false);
        diffPrVsBaseMenuItem.setEnabled(false);
        viewPrDiffMenuItem.setEnabled(false);
        capturePrDiffMenuItemContextMenu.setEnabled(false);
        openPrInBrowserMenuItem.setEnabled(false);

        // Context menu items for prCommitsTable (if initialized)
        capturePrCommitDiffMenuItem.setEnabled(false);
        viewPrCommitDiffMenuItem.setEnabled(false);
        comparePrCommitToLocalMenuItem.setEnabled(false);

        prCommitsTableModel.setRowCount(0);
        prFilesTableModel.setRowCount(0);
        currentPrCommitDetailsList.clear();
    }


    /**
     * Enable or disable every widget that can trigger a new reload.
     * Must be called on the EDT.
     */
    private void setReloadUiEnabled(boolean enabled)
    {
        refreshPrButton.setEnabled(enabled);

        statusFilter.setEnabled(enabled);
        authorFilter.setEnabled(enabled);
        labelFilter.setEnabled(enabled);
        assigneeFilter.setEnabled(enabled);
        reviewFilter.setEnabled(enabled);
    }

    /**
     * Determines the expected local branch name for a PR based on whether it's from the same repository or a fork.
     */
    private String getExpectedLocalBranchName(GHPullRequest pr) {
        var prHead = pr.getHead();
        String prBranchName = prHead.getRef();
        if (prBranchName.startsWith("refs/heads/")) {
            prBranchName = prBranchName.substring("refs/heads/".length());
        }
        
        String repoFullName = prHead.getRepository().getFullName();
        
        try {
            var repo = getRepo();
            var remoteUrl = repo.getRemoteUrl();
            GitUiUtil.OwnerRepo ownerRepo = GitUiUtil.parseOwnerRepoFromUrl(Objects.requireNonNullElse(remoteUrl, ""));
            
            if (ownerRepo != null && repoFullName.equals(ownerRepo.owner() + "/" + ownerRepo.repo())) {
                // PR is from the same repository - use the actual branch name
                return prBranchName;
            } else {
                // PR is from a fork - use username/branchname format
                return prHead.getRepository().getOwnerName() + "/" + prBranchName;
            }
        } catch (Exception e) {
            logger.warn("Error determining repository info for PR #{}, defaulting to fork format: {}", pr.getNumber(), e.getMessage());
            // Default to fork format if we can't determine the repo info
            return prHead.getRepository().getOwnerName() + "/" + prBranchName;
        }
    }

    private Optional<String> existsLocalPrBranch(GHPullRequest pr) {
        try {
            var repo = getRepo();
            List<String> localBranches = repo.listLocalBranches();
            
            String expectedLocalBranchName = getExpectedLocalBranchName(pr);
            for (String branchName : localBranches) {
                if (branchName.equals(expectedLocalBranchName)) {
                    return Optional.of(branchName);
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error checking for existing local PR branch for PR #{}: {}", pr.getNumber(), e.getMessage(), e);
        }
        return Optional.empty();
    }

    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }

    /**
     * Fetches open GitHub pull requests and populates the PR table.
     * Also fetches CI statuses for these PRs.
     */
    private void updatePrList() {
        assert SwingUtilities.isEventDispatchThread();
        setReloadUiEnabled(false);

        var future = contextManager.submitBackgroundTask("Fetching GitHub Pull Requests", () -> {
            List<org.kohsuke.github.GHPullRequest> fetchedPrs;
            try {
                var project = contextManager.getProject();
                GitHubAuth auth = GitHubAuth.getOrCreateInstance(project);

                String selectedStatusOption = statusFilter.getSelected();
                GHIssueState apiState;
                if ("Open".equals(selectedStatusOption)) {
                    apiState = GHIssueState.OPEN;
                } else if ("Closed".equals(selectedStatusOption)) {
                    apiState = GHIssueState.CLOSED;
                } else { // null or any other string implies ALL for safety, though options are limited
                    apiState = GHIssueState.ALL;
                }

                fetchedPrs = auth.listOpenPullRequests(apiState);
                logger.debug("Fetched {} PRs", fetchedPrs.size());
            } catch (Exception ex) {
                logger.error("Failed to fetch pull requests", ex);
                SwingUtilities.invokeLater(() -> {
                    allPrsFromApi.clear();
                    displayedPrs.clear();
                    ciStatusCache.clear();
                    prCommitsCache.clear();
                    prTableModel.setRowCount(0);
                    prTableModel.addRow(new Object[]{
                            "", "Error fetching PRs: " + ex.getMessage(), "", "", "", "", ""
                    });
                    disablePrButtonsAndClearCommitsAndMenus();
                    authorChoices.clear();
                    labelChoices.clear();
                    assigneeChoices.clear();
                    setReloadUiEnabled(true);
                });
                return null;
            }

            // Process fetched PRs on EDT
            List<org.kohsuke.github.GHPullRequest> finalFetchedPrs = fetchedPrs;
            SwingUtilities.invokeLater(() -> {
                allPrsFromApi = new ArrayList<>(finalFetchedPrs);
                prCommitsCache.clear(); // Clear commits cache for new PR list
                // ciStatusCache is updated incrementally, not fully cleared here unless error
                populateDynamicFilterChoices(allPrsFromApi);
                filterAndDisplayPrs(); // Apply current filters, which will also trigger CI fetching
                setReloadUiEnabled(true);
            });
            return null;
        });
        trackCancellableFuture(future);
    }

    private List<String> getAuthorFilterOptions() {
        return authorChoices;
    }

    private List<String> getLabelFilterOptions() {
        return labelChoices;
    }

    private List<String> getAssigneeFilterOptions() {
        return assigneeChoices;
    }

    private void populateDynamicFilterChoices(List<org.kohsuke.github.GHPullRequest> prs) {
        // Author Filter
        Map<String, Integer> authorCounts = new HashMap<>();
        for (var pr : prs) {
            try {
                GHUser user = pr.getUser();
                if (user != null) {
                    String login = user.getLogin();
                    if (login != null && !login.isBlank()) {
                        authorCounts.merge(login, 1, Integer::sum);
                    }
                }
            } catch (IOException e) {
                logger.warn("Could not get user or login for PR #{}", pr.getNumber(), e);
            }
        }
        authorChoices = generateFilterOptionsList(authorCounts);

        // Label Filter
        Map<String, Integer> labelCounts = new HashMap<>();
        for (var pr : prs) {
            for (GHLabel label : pr.getLabels()) {
                if (!label.getName().isBlank()) {
                    labelCounts.merge(label.getName(), 1, Integer::sum);
                }
            }
        }
        labelChoices = generateFilterOptionsList(labelCounts);

        // Assignee Filter
        Map<String, Integer> assigneeCounts = new HashMap<>();
        for (var pr : prs) {
            for (GHUser assignee : pr.getAssignees()) {
                String login = assignee.getLogin();
                if (login != null && !login.isBlank()) {
                    assigneeCounts.merge(login, 1, Integer::sum);
                }
            }
        }
        assigneeChoices = generateFilterOptionsList(assigneeCounts);
    }

    private List<String> generateFilterOptionsList(Map<String, Integer> counts) {
        List<String> options = new ArrayList<>();
        List<String> sortedItems = new ArrayList<>(counts.keySet());
        Collections.sort(sortedItems, String.CASE_INSENSITIVE_ORDER);

        for (String item : sortedItems) {
            options.add(String.format("%s (%d)", item, counts.get(item)));
        }
        return options;
    }

    private void filterAndDisplayPrs() {
        assert SwingUtilities.isEventDispatchThread();
        displayedPrs.clear();
        String selectedAuthorDisplay = authorFilter.getSelected(); // e.g., "John Doe (5)" or null
        String selectedLabelDisplay = labelFilter.getSelected();   // e.g., "bug (2)" or null
        String selectedAssigneeDisplay = assigneeFilter.getSelected(); // e.g., "Jane Roe (1)" or null
        String selectedReviewStatusActual = reviewFilter.getSelected(); // Review options are direct strings, e.g., "Approved"

        String selectedAuthorActual = getBaseFilterValue(selectedAuthorDisplay);
        String selectedLabelActual = getBaseFilterValue(selectedLabelDisplay);
        String selectedAssigneeActual = getBaseFilterValue(selectedAssigneeDisplay);

        for (var pr : allPrsFromApi) {
            boolean matches = true;
            try {
                // Author filter
                if (selectedAuthorActual != null && (pr.getUser() == null || !selectedAuthorActual.equals(pr.getUser().getLogin()))) {
                    matches = false;
                }
                // Label filter
                if (matches && selectedLabelActual != null) {
                    matches = pr.getLabels().stream().anyMatch(l -> selectedLabelActual.equals(l.getName()));
                }
                // Assignee filter
                if (matches && selectedAssigneeActual != null) {
                    boolean assigneeMatch = false;
                    for (GHUser assignee : pr.getAssignees()) {
                        String login = assignee.getLogin();
                        if (selectedAssigneeActual.equals(login)) {
                            assigneeMatch = true;
                            break;
                        }
                    }
                    matches = assigneeMatch;
                }
                // Review filter
                if (matches && selectedReviewStatusActual != null) {
                    // Basic placeholder logic: log and don't filter out for now for unimplemented options
                    logger.info("Review filter selected: '{}'. Full client-side filtering for this option is not yet implemented or may be slow.", selectedReviewStatusActual);
                }

            } catch (IOException e) {
                logger.warn("Error accessing PR data during filtering for PR #{}", pr.getNumber(), e);
                matches = false; // Skip PR if data can't be accessed
            }

            if (matches) {
                displayedPrs.add(pr);
            }
        }

        // Update table model
        prTableModel.setRowCount(0);
        var today = LocalDate.now(java.time.ZoneId.systemDefault());
        if (displayedPrs.isEmpty()) {
            prTableModel.addRow(new Object[]{"", "No matching PRs found", "", "", "", "", ""});
            disablePrButtonsAndClearCommitsAndMenus(); // Clear menus too
        } else {
            // Sort PRs by update date, newest first
            displayedPrs.sort(Comparator.comparing(pr -> {
                try {
                    return pr.getUpdatedAt();
                } catch (IOException e) {
                    return Date.from(java.time.Instant.EPOCH); // Oldest on error
                }
            }, Comparator.nullsLast(Comparator.reverseOrder())));

            for (var pr : displayedPrs) {
                String author = "";
                String formattedUpdated = "";
                String forkInfo = "";
                try {
                    if (pr.getUser() != null) author = pr.getUser().getLogin();
                    if (pr.getUpdatedAt() != null) {
                        Date date = pr.getUpdatedAt();
                        formattedUpdated = GitUiUtil.formatRelativeDate(date.toInstant(), today);
                    }
                    var headRepo = pr.getHead().getRepository();
                    if (headRepo != null && headRepo.isFork()) forkInfo = headRepo.getFullName();
                } catch (IOException ex) {
                    logger.warn("Could not get metadata for PR #{}", pr.getNumber(), ex);
                }

                String statusValue = ciStatusCache.getOrDefault(pr.getNumber(), "?");

                prTableModel.addRow(new Object[]{
                        "#" + pr.getNumber(), pr.getTitle(), author, formattedUpdated,
                        pr.getBase().getRef(), forkInfo, statusValue
                });
            }
        }
        // Buttons state will be managed by selection listener or if selection is empty
            if (prTable.getSelectedRow() == -1) {
                disablePrButtonsAndClearCommitsAndMenus(); // Clear menus too
            } else {
                // Trigger selection listener to update button states correctly for the (potentially new) selection
                prTable.getSelectionModel().setValueIsAdjusting(true); // force re-evaluation
                prTable.getSelectionModel().setValueIsAdjusting(false);
                // The selection listener will call updatePrTableContextMenuState and set viewPrDiffButton state.
            }

            // Asynchronously fetch CI statuses for the displayed PRs
            fetchCiStatusesForDisplayedPrs();
    }

    private class CiStatusFetcherWorker extends SwingWorker<Map<Integer, String>, Void> {
        private final List<GHPullRequest> prsToFetch;

        public CiStatusFetcherWorker(List<GHPullRequest> prsToFetch) {
            this.prsToFetch = prsToFetch;
        }

        @Override
        protected Map<Integer, String> doInBackground() {
            Map<Integer, String> fetchedStatuses = new HashMap<>();
            for (GHPullRequest prToFetch : prsToFetch) {
                if (isCancelled()) {
                    break;
                }
                try {
                    if (prToFetch.isMerged()) { // This can throw IOException
                        fetchedStatuses.put(prToFetch.getNumber(), "Merged");
                    } else {
                        String status = getCiStatus(prToFetch); // This can throw IOException
                        fetchedStatuses.put(prToFetch.getNumber(), status);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to get status (merged/CI) for PR #{} due to IOException: {}", prToFetch.getNumber(), e);
                    fetchedStatuses.put(prToFetch.getNumber(), "Err");
                }
            }
            return fetchedStatuses;
        }

        @Override
        protected void done() {
            if (isCancelled()) {
                logger.debug("CI status fetch worker cancelled.");
                return;
            }
            try {
                Map<Integer, String> newStatuses = get();
                ciStatusCache.putAll(newStatuses);

                // Update the table model with new CI statuses
                for (int i = 0; i < prTableModel.getRowCount(); i++) {
                    Object prNumCellObj = prTableModel.getValueAt(i, PR_COL_NUMBER);
                    if (prNumCellObj instanceof String prNumCell && prNumCell.startsWith("#")) {
                        try {
                            int prNumberInTable = Integer.parseInt(prNumCell.substring(1));
                            if (newStatuses.containsKey(prNumberInTable)) {
                                prTableModel.setValueAt(newStatuses.get(prNumberInTable), i, PR_COL_STATUS);
                            }
                        } catch (NumberFormatException nfe) {
                            logger.trace("Skipping Status update for non-PR row in table: {}", prNumCell);
                        }
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("CI status fetch worker interrupted", e);
                Thread.currentThread().interrupt();
            } catch (CancellationException e) {
                 logger.debug("CI status fetch worker task was cancelled.");
            } catch (ExecutionException e) {
                 reportBackgroundError("Error executing CI status fetch worker task", requireNonNull(e.getCause()));
            } catch (Exception e) {
                reportBackgroundError("Error processing CI status fetch results", e);
            }
        }
    }

    private void fetchCiStatusesForDisplayedPrs() {
        assert SwingUtilities.isEventDispatchThread();

        List<GHPullRequest> prsRequiringCiFetch = new ArrayList<>();
        for (GHPullRequest pr : displayedPrs) {
            String currentStatusInCache = ciStatusCache.get(pr.getNumber());
            if (currentStatusInCache == null || "?".equals(currentStatusInCache) || "...".equals(currentStatusInCache) ||
                "Error".equals(currentStatusInCache) || "Err".equals(currentStatusInCache)) {
                prsRequiringCiFetch.add(pr);
            }
        }

        if (prsRequiringCiFetch.isEmpty()) return;

        if (activeCiFetcher != null && !activeCiFetcher.isDone()) {
            activeCiFetcher.cancel(true);
        }

        activeCiFetcher = new CiStatusFetcherWorker(prsRequiringCiFetch);
        contextManager.getBackgroundTasks().submit(activeCiFetcher);
    }

    private class PrFilesFetcherWorker extends SwingWorker<Map<Integer, List<String>>, Void> {
        private final List<GHPullRequest> prsToFetchFilesFor;
        private final IProject project;

        public PrFilesFetcherWorker(List<GHPullRequest> prsToFetchFilesFor, IProject project) {
            this.prsToFetchFilesFor = prsToFetchFilesFor;
            this.project = project;
        }

        @Override
        protected Map<Integer, List<String>> doInBackground() {
            Map<Integer, List<String>> fetchedFilesMap = new HashMap<>();
            var repo = getRepo();

            for (GHPullRequest pr : prsToFetchFilesFor) {
                if (isCancelled()) break;
                try {
                    String headSha = pr.getHead().getSha();
                    String baseSha = pr.getBase().getSha();
                    int prNumber = pr.getNumber();
                    boolean headLocallyAvailable;
                    boolean baseLocallyAvailable;

                    GitHubAuth auth;
                    try {
                        auth = GitHubAuth.getOrCreateInstance(this.project);
                    } catch (IOException e) {
                        logger.warn("Failed to get GitHubAuth instance in PrFilesFetcherWorker for PR #{}: {}", prNumber, e.getMessage(), e);
                        throw new RuntimeException("Failed to initialize GitHubAuth for PR #" + prNumber + ": " + e.getMessage(), e);
                    }

                    // Ensure head SHA is available
                    String headFetchRefSpec = String.format("+refs/pull/%d/head:refs/remotes/origin/pr/%d/head", prNumber, prNumber);
                    headLocallyAvailable = ensureShaIsLocal(repo, headSha, headFetchRefSpec, "origin");

                    if (!headLocallyAvailable) {
                        // If direct PR head fetch fails, try fetching the source branch if it's from origin
                        if (pr.getHead().getRepository().getFullName().equals(auth.getOwner() + "/" + auth.getRepoName())) {
                            String headBranchName = pr.getHead().getRef(); // e.g., "feature/my-branch" or "refs/heads/feature/my-branch"
                            if (headBranchName.startsWith("refs/heads/")) headBranchName = headBranchName.substring("refs/heads/".length());
                            String headBranchFetchRefSpec = String.format("+refs/heads/%s:refs/remotes/origin/%s", headBranchName, headBranchName);
                            headLocallyAvailable = ensureShaIsLocal(repo, headSha, headBranchFetchRefSpec, "origin");
                        } else {
                             logger.warn("PR #{} head {} is from a fork. Initial fetch failed and advanced fork fetching not yet implemented in PrFilesFetcherWorker.", prNumber, GitUiUtil.shortenCommitId(headSha));
                             // headLocallyAvailable remains the result of the first ensureShaIsLocal attempt
                        }
                    }

                    // Ensure base SHA is available
                    String baseBranchName = pr.getBase().getRef(); // e.g. "main"
                    String baseFetchRefSpec = String.format("+refs/heads/%s:refs/remotes/origin/%s", baseBranchName, baseBranchName);
                    baseLocallyAvailable = ensureShaIsLocal(repo, baseSha, baseFetchRefSpec, "origin");

                    if (headLocallyAvailable && baseLocallyAvailable) {
                        List<String> changedFiles;
                        try {
                            // Always use the PR's base SHA for diffing, which represents the exact
                            // commit the PR was created from. This ensures we only show files that
                            // actually changed in the PR, not additional changes in the target branch.
                            String mergeBase = repo.getMergeBase(headSha, baseSha);
                            
                            if (mergeBase != null) {
                                changedFiles = repo.listFilesChangedBetweenCommits(headSha, mergeBase)
                                        .stream()
                                        .map(projFile -> projFile.toString())
                                        .collect(Collectors.toList());
                            } else {
                                // Fallback to direct diff if merge base calculation fails
                                changedFiles = repo.listFilesChangedBetweenCommits(headSha, baseSha)
                                        .stream()
                                        .map(projFile -> projFile.toString())
                                        .collect(Collectors.toList());
                            }
                        } catch (Exception e) {
                            logger.warn("Error calculating changed files for PR #{}, using fallback diff: {}", prNumber, e.getMessage());
                            changedFiles = repo.listFilesChangedBetweenCommits(headSha, baseSha)
                                    .stream()
                                    .map(projFile -> projFile.toString())
                                    .collect(Collectors.toList());
                        }
                        fetchedFilesMap.put(prNumber, changedFiles);
                    } else {
                        String missingShasMsg = "";
                        if (!headLocallyAvailable) missingShasMsg += "head (" + GitUiUtil.shortenCommitId(headSha) + ")";
                        if (!baseLocallyAvailable) {
                            if (!missingShasMsg.isEmpty()) missingShasMsg += " and ";
                            missingShasMsg += "base (" + GitUiUtil.shortenCommitId(baseSha) + ")";
                        }
                        logger.warn("Could not make {} SHA(s) for PR #{} fully available locally to list files.", missingShasMsg, prNumber);
                        fetchedFilesMap.put(prNumber, List.of("Error: Could not make required SHAs locally available. Ensure refs are fetched."));
                    }
                } catch (Exception e) {
                    logger.error("Error fetching or processing files for PR #{}", pr.getNumber(), e);
                    fetchedFilesMap.put(pr.getNumber(), List.of("Error fetching files: " + e.getMessage()));
                }
            }
            return fetchedFilesMap;
        }

        @Override
        protected void done() {
            if (isCancelled()) {
                logger.debug("PR files fetcher worker cancelled.");
                return;
            }
            try {
                get(); // Consume the result to complete the future properly
                // Files are now loaded on demand, no caching
            } catch (InterruptedException e) {
                logger.warn("PR files fetcher worker interrupted", e);
                Thread.currentThread().interrupt();
            } catch (CancellationException e) {
                 logger.debug("PR files fetcher worker task was cancelled.");
            } catch (ExecutionException e) {
                 reportBackgroundError("Error executing PR files fetcher worker task", requireNonNull(e.getCause()));
            } catch (Exception e) {
                reportBackgroundError("Error processing PR files fetcher results", e);
            }
        }
    }



    @Nullable
    private String getBaseFilterValue(@Nullable String displayOptionWithCount) {
        if (displayOptionWithCount == null) {
            return null; // This is the "All" case (FilterBox name shown)
        }
        // For dynamic items like "John Doe (5)"
        int parenthesisIndex = displayOptionWithCount.lastIndexOf(" (");
        if (parenthesisIndex != -1) {
            // Ensure the part in parenthesis is a number to avoid stripping part of a name
            String countPart = displayOptionWithCount.substring(parenthesisIndex + 2, displayOptionWithCount.length() - 1);
            if (countPart.matches("\\d+")) {
                return displayOptionWithCount.substring(0, parenthesisIndex);
            }
        }
        return displayOptionWithCount; // For simple string options like "Open", "Closed", or names without counts
    }

    // This method is now part of disablePrButtonsAndClearCommitsAndMenus
    // private void disablePrButtons() { ... }


    private void captureSelectedPrDiff() {
        int selectedRow = prTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedPrs.size()) {
            return;
        }
        GHPullRequest pr = displayedPrs.get(selectedRow);
        logger.info("Capturing diff for PR #{}", pr.getNumber());

        // Check if instructions are empty and populate with PR title + review prompt if needed
        SwingUtilities.invokeLater(() -> {
            String currentInstructions = chrome.getInstructionsPanel().getInstructions();
            if (currentInstructions.trim().isEmpty()) {
                String reviewGuide = contextManager.getProject().getReviewGuide();
                String reviewPrompt = String.format("Review PR #%d: %s\n\n%s", pr.getNumber(), pr.getTitle(), reviewGuide);
                chrome.getInstructionsPanel().populateInstructionsArea(reviewPrompt);
            }
        });

        contextManager.submitContextTask("Capture PR Diff #" + pr.getNumber(), () -> {
            try {
                var repo = getRepo();

                String prHeadSha = pr.getHead().getSha();
                String prBaseSha = pr.getBase().getSha();
                String prTitle = pr.getTitle();
                int prNumber = pr.getNumber();

                // Ensure SHAs are local
                String prHeadFetchRef = String.format("+refs/pull/%d/head:refs/remotes/origin/pr/%d/head", prNumber, prNumber);
                String prBaseBranchName = pr.getBase().getRef();
                String prBaseFetchRef = String.format("+refs/heads/%s:refs/remotes/origin/%s", prBaseBranchName, prBaseBranchName);

                if (!ensureShaIsLocal(repo, prHeadSha, prHeadFetchRef, "origin")) {
                    chrome.toolError("Could not make PR head commit " + GitUiUtil.shortenCommitId(prHeadSha) + " available locally.", "Capture Diff Error");
                    return;
                }
                // It's less critical for baseSha to be at the exact tip of the remote base branch for diffing,
                // as long as the prBaseSha commit itself is available. Fetching the branch helps ensure this.
                ensureShaIsLocal(repo, prBaseSha, prBaseFetchRef, "origin");


                GitUiUtil.capturePrDiffToContext(contextManager, chrome, prTitle, prNumber, prHeadSha, prBaseSha, repo);

            } catch (Exception ex) {
                logger.error("Error capturing diff for PR #{}", pr.getNumber(), ex);
                chrome.toolError("Unable to capture diff for PR #" + pr.getNumber() + ": " + ex.getMessage(), "Capture Diff Error");
            }
        });
    }

    private void viewFullPrDiff() {
        int selectedRow = prTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedPrs.size()) {
            return;
        }
        GHPullRequest pr = displayedPrs.get(selectedRow);
        logger.info("Opening full diff viewer for PR #{}", pr.getNumber());

        contextManager.submitUserTask("Show PR Diff", () -> {
            try {
                var repo = getRepo();

                String prHeadSha = pr.getHead().getSha();
                String prBaseSha = pr.getBase().getSha();
                String prHeadFetchRef = String.format("+refs/pull/%d/head:refs/remotes/origin/pr/%d/head", pr.getNumber(), pr.getNumber());
                String prBaseBranchName = pr.getBase().getRef(); // e.g., "main"
                String prBaseFetchRef = String.format("+refs/heads/%s:refs/remotes/origin/%s", prBaseBranchName, prBaseBranchName);

                if (!ensureShaIsLocal(repo, prHeadSha, prHeadFetchRef, "origin")) {
                    chrome.toolError("Could not make PR head commit " + GitUiUtil.shortenCommitId(prHeadSha) + " available locally.", "Diff Error");
                    return null;
                }
                if (!ensureShaIsLocal(repo, prBaseSha, prBaseFetchRef, "origin")) {
                    // This is a warning because prBaseSha might be an old commit not on the current tip of the base branch.
                    // listFilesChangedBetweenBranches might still work if it's an ancestor.
                    logger.warn("PR base commit {} might not be available locally after fetching {}. Diff might be based on a different merge-base.", GitUiUtil.shortenCommitId(prBaseSha), prBaseFetchRef);
                }

                List<GitRepo.ModifiedFile> modifiedFiles = repo.listFilesChangedBetweenBranches(prHeadSha, prBaseSha);

                if (modifiedFiles.isEmpty()) {
                    chrome.systemNotify("No changes found in PR #" + pr.getNumber(), "Diff Info", JOptionPane.INFORMATION_MESSAGE);
                    return null;
                }

                var builder = new io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel.Builder(chrome.getTheme(), contextManager)
                        .setMultipleCommitsContext(true); // Indicate this is a multiple commits context

                for (var mf : modifiedFiles) {
                    var projectFile = mf.file();
                    var status = mf.status();
                    io.github.jbellis.brokk.difftool.ui.BufferSource leftSource, rightSource;

                    if ("deleted".equals(status)) {
                        leftSource = new io.github.jbellis.brokk.difftool.ui.BufferSource.StringSource(repo.getFileContent(prBaseSha, projectFile), prBaseSha, projectFile.getFileName());
                        rightSource = new io.github.jbellis.brokk.difftool.ui.BufferSource.StringSource("", prHeadSha + " (Deleted)", projectFile.getFileName());
                    } else if ("new".equals(status)) {
                        leftSource = new io.github.jbellis.brokk.difftool.ui.BufferSource.StringSource("", prBaseSha + " (New)", projectFile.getFileName());
                        rightSource = new io.github.jbellis.brokk.difftool.ui.BufferSource.StringSource(repo.getFileContent(prHeadSha, projectFile), prHeadSha, projectFile.getFileName());
                    } else { // modified
                        leftSource = new io.github.jbellis.brokk.difftool.ui.BufferSource.StringSource(repo.getFileContent(prBaseSha, projectFile), prBaseSha, projectFile.getFileName());
                        rightSource = new io.github.jbellis.brokk.difftool.ui.BufferSource.StringSource(repo.getFileContent(prHeadSha, projectFile), prHeadSha, projectFile.getFileName());
                    }
                    builder.addComparison(leftSource, rightSource);
                }
                SwingUtilities.invokeLater(() -> builder.build().showInFrame("PR #" + pr.getNumber() + " Diff: " + pr.getTitle()));

            } catch (Exception ex) {
                logger.error("Error opening PR diff viewer for PR #{}", pr.getNumber(), ex);
                chrome.toolError("Unable to open diff for PR #" + pr.getNumber() + ": " + ex.getMessage(), "Diff Error");
            }
            return null;
        });
    }

    // Unused method removed
    // private String getLocalSyncStatus(GHPullRequest pr, String prHeadSha) { ... }

    private String getCiStatus(org.kohsuke.github.GHPullRequest pr) throws IOException {
        // This method is now called from a background thread within updatePrList.
        // The IOException is declared because pr.getMergeableState() can throw it.
        var state = pr.getMergeableState(); // returns String like "clean", "dirty", "blocked", "unknown", "draft" etc.
        if (state == null) return "";
        return switch (com.google.common.base.Ascii.toLowerCase(state)) {
            case "clean" -> "clean";
            case "blocked", "dirty" -> "blocked";
            case "unstable", "behind" -> "unstable";
            default -> com.google.common.base.Ascii.toLowerCase(state); // return other states like "draft", "unknown" as is
        };
    }

    /**
     * Loads commits for the given pull request.
     */
    private void updateCommitsForPullRequest(GHPullRequest selectedPr) {
        int prNumber = selectedPr.getNumber();

        // Check cache first
        var cachedCommits = prCommitsCache.get(prNumber);
        if (cachedCommits != null) {
            // Check if the cached entry is an error marker or actual data
            boolean isErrorMarker = cachedCommits.size() == 1 && "Error fetching commits:".startsWith(cachedCommits.getFirst().message().substring(0, Math.min(20, cachedCommits.getFirst().message().length())));
            if (!isErrorMarker) {
                logger.debug("Using cached commits for PR #{}", prNumber);
                currentPrCommitDetailsList = new ArrayList<>(cachedCommits); // Use a copy
                prCommitsTableModel.setRowCount(0);
                if (currentPrCommitDetailsList.isEmpty()) {
                    prCommitsTableModel.addRow(new Object[]{"No commits found for PR #" + prNumber});
                } else {
                    for (var commit : currentPrCommitDetailsList) {
                        prCommitsTableModel.addRow(new Object[]{commit.message()});
                    }
                }
                return; // Commits loaded from cache
            } else {
                logger.debug("Cached entry for PR #{} is an error marker, re-fetching.", prNumber);
            }
        }

        // Show loading message immediately
        currentPrCommitDetailsList.clear();
        prCommitsTableModel.setRowCount(0);
        prCommitsTableModel.addRow(new Object[]{"Loading commits..."});

        var future = contextManager.submitBackgroundTask("Fetching commits for PR #" + prNumber, () -> {
            List<ICommitInfo> newCommitList = new ArrayList<>();
            try {
                var project = contextManager.getProject();
                GitHubAuth auth = GitHubAuth.getOrCreateInstance(project);
                var ghCommitDetails = auth.listPullRequestCommits(prNumber); // Fetches from GitHub API

                var repo = getRepo();

                // Ensure PR head is fetched so commits are likely local
                String prHeadFetchRef = String.format("+refs/pull/%d/head:refs/remotes/origin/pr/%d/head", prNumber, prNumber);
                try {
                    repo.getGit().fetch()
                        .setRemote("origin") // Assuming origin, might need fork logic if complex setup
                        .setRefSpecs(new RefSpec(prHeadFetchRef))
                        .call();
                    logger.debug("Fetched PR head {} for commit analysis of PR #{}", prHeadFetchRef, prNumber);
                } catch (Exception fetchEx) {
                    logger.warn("Failed to fetch PR head {} for commit analysis of PR #{}: {}", prHeadFetchRef, prNumber, fetchEx.getMessage());
                    // Continue, listFilesChangedInCommit might still work if commits are already local
                }

                for (var prCommitDetail : ghCommitDetails) { // prCommitDetail is GHPullRequestCommitDetail
                    String sha = prCommitDetail.getSha();
                    newCommitList.add(repo.getLocalCommitInfo(sha).orElseThrow());
                }

                prCommitsCache.put(prNumber, new ArrayList<>(newCommitList)); // Cache the successfully fetched commits (use a copy)
                SwingUtilities.invokeLater(() -> {
                        currentPrCommitDetailsList = newCommitList; // Assign the new list on EDT
                        prCommitsTableModel.setRowCount(0);
                        if (currentPrCommitDetailsList.isEmpty()) {
                            prCommitsTableModel.addRow(new Object[]{"No commits found for PR #" + prNumber});
                            return;
                        }
                        for (var commit : currentPrCommitDetailsList) {
                            prCommitsTableModel.addRow(new Object[]{commit.message()});
                        }
                        // Update files table since commits changed
                        updateFilesForSelectedCommits();
                    });
            } catch (Exception e) {
                logger.error("Error fetching commits for PR #{}", prNumber, e);
                // Cache the error indication
                String errorMsg = "Error fetching commits: " + e.getMessage();
                var errorEntry = List.<ICommitInfo>of(new ICommitInfo.CommitInfoStub(errorMsg));
                prCommitsCache.put(prNumber, errorEntry);

                SwingUtilities.invokeLater(() -> {
                    currentPrCommitDetailsList.clear(); // Clear on EDT in case of error
                    prCommitsTableModel.setRowCount(0);
                    prCommitsTableModel.addRow(new Object[]{errorMsg});
                    prFilesTableModel.setRowCount(0); // Clear files on error too
                });
            }
            return null;
        });
        trackCancellableFuture(future);
    }

    private void diffSelectedPr() {
        int selectedRow = prTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedPrs.size()) {
            return;
        }
        org.kohsuke.github.GHPullRequest pr = displayedPrs.get(selectedRow);
        logger.info("Generating diff for PR #{} vs base", pr.getNumber());
        contextManager.submitContextTask("Diff PR #" + pr.getNumber() + " vs base", () -> {
            try {
                var repo = getRepo();
                String baseBranchName = pr.getBase().getRef();
                String prBaseSha = pr.getBase().getSha();
                String prHeadSha = pr.getHead().getSha();

                // Ensure PR head ref is fetched
                String headFetchRef = String.format("+refs/pull/%d/head:refs/remotes/origin/pr/%d/head", pr.getNumber(), pr.getNumber());
                if (!ensureShaIsLocal(repo, prHeadSha, headFetchRef, "origin")) {
                    throw new IOException("Failed to fetch PR head " + GitUiUtil.shortenCommitId(prHeadSha) + " for PR #" + pr.getNumber());
                }

                // Ensure base branch ref is fetched (to make prBaseSha available)
                String baseRemoteRef = "origin/" + baseBranchName; // Assumes base is on origin
                String baseLocalFetchSpec = String.format("+refs/heads/%s:refs/remotes/origin/%s", baseBranchName, baseBranchName);
                if (!ensureShaIsLocal(repo, prBaseSha, baseLocalFetchSpec, "origin")) {
                     // This can happen if prBaseSha is an old commit not on the tip of the fetched base branch.
                     // For diffing, having the exact prBaseSha is crucial.
                     // A more robust solution might fetch the SHA directly if `refs/pull/N/base` was available or by depth.
                     logger.warn("PR #{} base SHA {} still not found locally after fetching branch {}. Diff might be incorrect.",
                                 pr.getNumber(), GitUiUtil.shortenCommitId(prBaseSha), baseRemoteRef);
                     // Attempting to proceed, showDiff might handle it or use a less accurate base.
                }

                String diff = repo.showDiff(prHeadSha, prBaseSha);
                if (diff.isEmpty()) {
                    chrome.systemOutput("No differences found between PR #" + pr.getNumber() + " (head: " + GitUiUtil.shortenCommitId(prHeadSha) +
                                                ") and its base " + baseBranchName + "@(" + GitUiUtil.shortenCommitId(prBaseSha) + ")");
                    return;
                }

                String description = "PR #" + pr.getNumber() + " (" + pr.getTitle() + ") diff vs " + baseBranchName + "@{" + GitUiUtil.shortenCommitId(prBaseSha) + "}";

                // Determine syntax style from changed files in the PR
                String syntaxStyle = SyntaxConstants.SYNTAX_STYLE_NONE;
                try {
                    var changedFiles = repo.listFilesChangedBetweenCommits(prHeadSha, prBaseSha);
                    if (!changedFiles.isEmpty()) {
                        syntaxStyle = SyntaxDetector.fromExtension(changedFiles.getFirst().extension());
                    }
                } catch (Exception e) {
                    logger.warn("Could not determine syntax style for PR diff: {}", e.getMessage());
                }

                var fragment = new StringFragment(contextManager, diff, description, syntaxStyle);
                SwingUtilities.invokeLater(() -> chrome.openFragmentPreview(fragment));
                chrome.systemOutput("Opened diff for PR #" + pr.getNumber() + " in preview panel");
            } catch (Exception ex) {
                chrome.toolError("Error generating diff for PR #" + pr.getNumber() + ": " + ex.getMessage());
            }
        });
    }

    /**
     * Checkout the currently selected PR branch.
     * This delegates to helper methods based on whether a local branch for the PR already exists.
     */
    private void checkoutSelectedPr() {
        int selectedRow = prTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedPrs.size()) {
            return;
        }

        org.kohsuke.github.GHPullRequest selectedPrObject = displayedPrs.get(selectedRow);
        final int prNumber = selectedPrObject.getNumber();

        Optional<String> existingLocalBranchOpt = existsLocalPrBranch(selectedPrObject);

        if (existingLocalBranchOpt.isPresent()) {
            updateExistingLocalPrBranch(prNumber, existingLocalBranchOpt.get());
        } else {
            checkoutPrAsNewBranch(selectedPrObject);
        }
    }

    /**
     * Updates an existing local branch for the given PR number.
     * Checks out the branch and pulls changes from its upstream.
     */
    private void updateExistingLocalPrBranch(int prNumber, String localBranchName) {
        logger.info("Updating existing local branch {} for PR #{}", localBranchName, prNumber);
        contextManager.submitUserTask("Updating local branch " + localBranchName + " for PR #" + prNumber, () -> {
            try {
                var repo = getRepo();
                repo.checkout(localBranchName);
                repo.pull(); // Pull changes from its tracked upstream

                SwingUtilities.invokeLater(() -> {
                    gitPanel.updateLogTab();
                    gitPanel.selectCurrentBranchInLogTab();
                });
                chrome.systemOutput("Updated local branch " + localBranchName + " for PR #" + prNumber);
                logger.info("Successfully updated local branch {} for PR #{}", localBranchName, prNumber);
            } catch (Exception e) {
                chrome.toolError("Error updating local branch " + localBranchName + ": " + e.getMessage());
            }
        });
    }

    /**
     * Checks out a PR as a new local branch.
     * Handles PRs from the main repository or forks, adding remotes if necessary.
     */
    private void checkoutPrAsNewBranch(org.kohsuke.github.GHPullRequest pr) {
        final int prNumber = pr.getNumber();
        logger.info("Starting checkout of PR #{} as a new local branch", prNumber);
        contextManager.submitUserTask("Checking out PR #" + prNumber, () -> {
            try {
                var remoteUrl = getRepo().getRemoteUrl(); // Can be null
                GitUiUtil.OwnerRepo ownerRepo = GitUiUtil.parseOwnerRepoFromUrl(Objects.requireNonNullElse(remoteUrl, ""));
                if (ownerRepo == null) {
                    throw new IOException("Could not parse 'owner/repo' from remote: " + remoteUrl);
                }

                var prHead = pr.getHead(); // This is a GHCommitPointer
                String prBranchName = prHead.getRef();
                String remoteName = "origin";

                if (prBranchName.startsWith("refs/heads/")) {
                    prBranchName = prBranchName.substring("refs/heads/".length());
                }

                String repoFullName = prHead.getRepository().getFullName();
                String remoteBranchRef;

                if (repoFullName.equals(ownerRepo.owner() + "/" + ownerRepo.repo())) {
                    // PR is from the same repository
                    remoteBranchRef = remoteName + "/" + prBranchName;
                } else {
                    // PR is from a fork
                    remoteName = "pr-" + prNumber + "-" + prHead.getRepository().getOwnerName(); // Make remote name more unique
                    String prRepoUrl = prHead.getRepository().getHtmlUrl().toString();

                    try {
                        getRepo().getGit().remoteAdd()
                                .setName(remoteName)
                                .setUri(new org.eclipse.jgit.transport.URIish(prRepoUrl + ".git"))
                                .call();
                        logger.info("Added remote '{}' for URL '{}'", remoteName, prRepoUrl);
                    } catch (org.eclipse.jgit.api.errors.TransportException e) {
                        if (e.getMessage() != null && e.getMessage().contains("remote " + remoteName + " already exists")) {
                            logger.info("Remote {} already exists.", remoteName);
                        } else {
                            logger.error("Failed to add remote '{}': {}", remoteName, e.getMessage(), e);
                            throw new IOException("Error adding remote for PR fork: " + e.getMessage(), e);
                        }
                    }

                    var refSpec = new org.eclipse.jgit.transport.RefSpec(
                            "+refs/heads/" + prBranchName + ":refs/remotes/" + remoteName + "/" + prBranchName);
                    logger.info("Fetching from remote '{}' with refspec '{}'", remoteName, refSpec);
                    getRepo().getGit().fetch()
                            .setRemote(remoteName) // Use the (potentially newly added) remote name
                            .setRefSpecs(refSpec)
                            .call();
                    remoteBranchRef = remoteName + "/" + prBranchName;
                }

                String localBranchName = getExpectedLocalBranchName(pr);

                getRepo().checkoutRemoteBranch(remoteBranchRef, localBranchName);

                SwingUtilities.invokeLater(() -> {
                    gitPanel.updateLogTab(); // Updates branches in Log tab
                    // Switch to the Log tab
                    JTabbedPane mainGitPanelTabs = (JTabbedPane) GitPullRequestsTab.this.getParent();
                    for (int i = 0; i < mainGitPanelTabs.getTabCount(); i++) {
                        if (mainGitPanelTabs.getTitleAt(i).equals("Log")) {
                            mainGitPanelTabs.setSelectedIndex(i);
                            break;
                        }
                    }
                    gitPanel.selectCurrentBranchInLogTab(); // Highlights the newly checked out branch
                });

                chrome.systemOutput("Checked out PR #" + prNumber + " as local branch " + localBranchName);
                logger.info("Successfully checked out PR #{}", prNumber);
                // Update button states on EDT
                SwingUtilities.invokeLater(() -> {
                    // Re-trigger selection listener to update button states correctly
                    // after potential branch change or PR list refresh.
                    int currentlySelectedRowInTable = prTable.getSelectedRow();
                    if (currentlySelectedRowInTable != -1) {
                        // Check if the same PR is still selected
                        // Use pr.getNumber() from the parameter of checkoutPrAsNewBranch
                        if (currentlySelectedRowInTable < displayedPrs.size() && displayedPrs.get(currentlySelectedRowInTable).getNumber() == pr.getNumber()) {
                            // Force re-evaluation of button states by toggling isAdjusting
                            prTable.getSelectionModel().setValueIsAdjusting(true);
                            prTable.getSelectionModel().setValueIsAdjusting(false);
                        }
                    }
                });
            } catch (Exception e) {
                chrome.toolError("Error checking out PR: " + e.getMessage());
            }
        });
    }

    private void openSelectedPrInBrowser() {
        int selectedRow = prTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedPrs.size()) {
            return;
        }
        org.kohsuke.github.GHPullRequest pr = displayedPrs.get(selectedRow);
        String url = pr.getHtmlUrl().toString();
        Environment.openInBrowser(url, SwingUtilities.getWindowAncestor(chrome.getFrame()));
    }

    /**
     * Updates the files table for the currently selected PR.
     */
    private void updateFilesForPullRequest(GHPullRequest selectedPr) {
        prFilesTableModel.setRowCount(0);
        prFilesTableModel.addRow(new Object[]{"Loading changed files..."});
        // Always fetch files on demand
        fetchChangedFilesForSpecificPr(selectedPr);
    }

    /**
     * Fetches changed files for a specific PR on demand.
     */
    private void fetchChangedFilesForSpecificPr(GHPullRequest pr) {
        assert SwingUtilities.isEventDispatchThread();

        if (activePrFilesFetcher != null && !activePrFilesFetcher.isDone()) {
            activePrFilesFetcher.cancel(true);
        }
        
        activePrFilesFetcher = new PrFilesFetcherWorker(List.of(pr), contextManager.getProject()) {
            @Override
            protected void done() {
                if (isCancelled()) {
                    logger.debug("PR files fetcher worker cancelled.");
                    return;
                }
                try {
                    Map<Integer, List<String>> newFilesData = get();
                    // Update the files table directly for the fetched PR
                    List<String> files = newFilesData.get(pr.getNumber());
                    SwingUtilities.invokeLater(() -> {
                        // Only update if this PR is still selected
                        int selectedRow = prTable.getSelectedRow();
                        if (selectedRow >= 0 && selectedRow < displayedPrs.size() && 
                            displayedPrs.get(selectedRow).getNumber() == pr.getNumber()) {
                            prFilesTableModel.setRowCount(0);
                            if (files == null || files.isEmpty()) {
                                prFilesTableModel.addRow(new Object[]{"No files changed in this PR"});
                            } else {
                                for (String file : files) {
                                    if (file.startsWith("Error:")) {
                                        prFilesTableModel.addRow(new Object[]{file});
                                    } else {
                                        // Format as "<file name> - full file path"
                                        String fileName = file.substring(file.lastIndexOf('/') + 1);
                                        String displayText = fileName + " - " + file;
                                        prFilesTableModel.addRow(new Object[]{displayText});
                                    }
                                }
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    logger.warn("PR files fetcher worker interrupted", e);
                    Thread.currentThread().interrupt();
                } catch (CancellationException e) {
                     logger.debug("PR files fetcher worker task was cancelled.");
                } catch (ExecutionException e) {
                    var cause = requireNonNull(e.getCause());
                    reportBackgroundError("Error executing PR files fetcher worker task", cause);
                } catch (Exception e) {
                    reportBackgroundError("Error processing PR files fetcher results", e);
                }
            }
        };
        contextManager.getBackgroundTasks().submit(activePrFilesFetcher);
    }

    /**
     * Updates the files table based on currently selected commits.
     * If no commits are selected, shows all files in the PR.
     * If commits are selected, shows only files changed in those commits.
     */
    private void updateFilesForSelectedCommits() {
        int[] selectedCommitRows = prCommitsTable.getSelectedRows();
        
        prFilesTableModel.setRowCount(0);
        
        if (selectedCommitRows.length == 0) {
            // No commits selected - show all PR files
            int selectedPrRow = prTable.getSelectedRow();
            if (selectedPrRow >= 0 && selectedPrRow < displayedPrs.size()) {
                GHPullRequest selectedPr = displayedPrs.get(selectedPrRow);
                updateFilesForPullRequest(selectedPr);
            }
        } else {
            // Commits selected - show files from those commits
            Set<String> allChangedFiles = new HashSet<>();
            for (int row : selectedCommitRows) {
                if (row < currentPrCommitDetailsList.size()) {
                    ICommitInfo commitInfo = currentPrCommitDetailsList.get(row);
                    try {
                        var projectFiles = commitInfo.changedFiles();
                        for (var file : projectFiles) {
                            allChangedFiles.add(file.toString());
                        }
                    } catch (GitAPIException e) {
                        logger.warn("Could not get changed files for commit {}: {}", commitInfo.id(), e.getMessage());
                        allChangedFiles.add("Error loading files for commit " + commitInfo.id());
                    }
                }
            }
            
            if (allChangedFiles.isEmpty()) {
                prFilesTableModel.addRow(new Object[]{"No files changed in selected commits"});
            } else {
                var sortedFiles = new ArrayList<>(allChangedFiles);
                Collections.sort(sortedFiles);
                for (String file : sortedFiles) {
                    if (file.startsWith("Error")) {
                        prFilesTableModel.addRow(new Object[]{file});
                    } else {
                        // Format as "<file name> - full file path"
                        String fileName = file.substring(file.lastIndexOf('/') + 1);
                        String displayText = fileName + " - " + file;
                        prFilesTableModel.addRow(new Object[]{displayText});
                    }
                }
            }
        }
    }
}
