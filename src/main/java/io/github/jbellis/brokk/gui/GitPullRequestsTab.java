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
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHUser;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
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
    private JButton checkoutPrButton;
    private JButton diffPrButton;
    private JButton openInBrowserButton;

    private FilterBox statusFilter;
    private FilterBox authorFilter;
    private FilterBox labelFilter;
    private FilterBox assigneeFilter;
    private FilterBox reviewFilter;

    private final GitHubTokenMissingPanel gitHubTokenMissingPanel;

    private List<org.kohsuke.github.GHPullRequest> allPrsFromApi = new ArrayList<>();
    private List<org.kohsuke.github.GHPullRequest> displayedPrs = new ArrayList<>();
    private final Map<Integer, String> ciStatusCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<ICommitInfo>> prCommitsCache = new ConcurrentHashMap<>();
    private List<ICommitInfo> currentPrCommitDetailsList = new ArrayList<>();
    private JTable prFilesTable;
    private DefaultTableModel prFilesTableModel;

    private SwingWorker<Map<Integer, String>, Void> activeCiFetcher;
    private SwingWorker<Map<Integer, List<String>>, Void> activePrFilesFetcher;

    /**
     * Ensure a commit SHA is available locally, fetching the specified refSpec from the remote if necessary.
     *
     * @param sha         The object id that must be present locally
     * @param repo        GitRepo to operate on (non-null)
     * @param refSpec     The refSpec to fetch if the SHA is missing (e.g. "+refs/pull/123/head:refs/remotes/origin/pr/123/head")
     * @param remoteName  Which remote to fetch from (e.g. "origin")
     * @return true if the SHA is now resolvable locally, false otherwise
     */
    private static boolean ensureShaIsLocal(GitRepo repo, String sha, String refSpec, String remoteName) {
        try {
            if (repo.resolve(sha) != null) {
                return true; // already present
            }
            logger.debug("SHA {} not found locally - fetching {} from {}", GitUiUtil.shortenCommitId(sha), refSpec, remoteName);
            repo.getGit().fetch()
                    .setRemote(remoteName)
                    .setRefSpecs(new RefSpec(refSpec))
                    .call();
            boolean resolved = repo.resolve(sha) != null;
            if (!resolved) {
                logger.warn("Failed to resolve SHA {} even after fetching {} from {}", GitUiUtil.shortenCommitId(sha), refSpec, remoteName);
            } else {
                logger.debug("Successfully fetched SHA {}", GitUiUtil.shortenCommitId(sha));
            }
            return resolved;
        } catch (Exception e) {
            logger.warn("Error fetching ref '{}' for SHA {}: {}", refSpec, GitUiUtil.shortenCommitId(sha), e.getMessage());
            return false;
        }
    }

    /**
     * Logs an error and shows a toolErrorRaw message to the user.
     */
    private void reportBackgroundError(String contextMessage, Exception e) {
        chrome.toolError(contextMessage + (e != null && e.getMessage() != null ? ": " + e.getMessage() : ""));
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

        checkoutPrButton = new JButton("Check Out");
        checkoutPrButton.setToolTipText("Check out this PR branch locally");
        checkoutPrButton.setEnabled(false);
        checkoutPrButton.addActionListener(e -> checkoutSelectedPr());
        prButtonPanel.add(checkoutPrButton);
        prButtonPanel.add(Box.createHorizontalStrut(Constants.H_GAP));

        diffPrButton = new JButton("Diff vs Base");
        diffPrButton.setToolTipText("Add diff of PR against its base branch to context");
        diffPrButton.setEnabled(false);
        diffPrButton.addActionListener(e -> diffSelectedPr());
        prButtonPanel.add(diffPrButton);
        prButtonPanel.add(Box.createHorizontalStrut(Constants.H_GAP));

        openInBrowserButton = new JButton("Open in Browser");
        openInBrowserButton.setToolTipText("Open the selected PR in your web browser");
        openInBrowserButton.setEnabled(false);
        openInBrowserButton.addActionListener(e -> openSelectedPrInBrowser());
        prButtonPanel.add(openInBrowserButton);

        prButtonPanel.add(Box.createHorizontalGlue()); // Pushes refresh button to the right

        JButton refreshPrButton = new JButton("Refresh");
        refreshPrButton.addActionListener(e -> updatePrList());
        prButtonPanel.add(refreshPrButton);

        prTableAndButtonsPanel.add(prButtonPanel, BorderLayout.SOUTH);
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
                            if (commitInfo != null) {
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
                                } catch (GitAPIException e) { // CommitInfo.changedFiles can throw GitAPIException (and its subclass GitRepoException)
                                    logger.warn("Could not get changed files for PR commit tooltip ({}): {}", commitInfo.id(), e.getMessage());
                                    return "Error loading changed files for this commit.";
                                }
                            } else { // commitInfo is null
                                return "Commit details not available.";
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
        prFilesTable = new JTable(prFilesTableModel);
        prFilesTable.setTableHeader(null);
        prFilesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        prFilesTable.setRowHeight(18);
        prFilesTable.getColumnModel().getColumn(0).setPreferredWidth(400); // File

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
            if (!e.getValueIsAdjusting() && prTable.getSelectedRow() != -1) {
                int viewRow = prTable.getSelectedRow();
                if (viewRow != -1) {
                    int modelRow = prTable.convertRowIndexToModel(viewRow); // In case of sorting/filtering on JTable later
                    // Ensure modelRow is valid for displayedPrs
                    if (modelRow < 0 || modelRow >= displayedPrs.size()) {
                         logger.warn("Selected row {} (model {}) is out of bounds for displayed PRs size {}", viewRow, modelRow, displayedPrs.size());
                         disablePrButtonsAndClearCommits();
                         return;
                    }
                    GHPullRequest selectedPr = displayedPrs.get(modelRow);
                    int prNumber = selectedPr.getNumber();
                    updateCommitsForPullRequest(selectedPr);
                    updateFilesForPullRequest(selectedPr);

                    // Temporarily set button state while loading
                    checkoutPrButton.setText("Loading...");
                    checkoutPrButton.setEnabled(false);
                    diffPrButton.setEnabled(true);
                    openInBrowserButton.setEnabled(true);

                    this.contextManager.submitBackgroundTask("Updating PR #" + prNumber + " local status", () -> {
                        String prHeadSha = selectedPr.getHead().getSha(); // This call might throw a RTE from the library on failure
                        // If getHead() or getSha() fails internally (e.g. network issue leading to RTE in library),
                        // the whole background task will fail and be logged by submitBackgroundTask's wrapper.
                        // The UI won't update checkoutPrButton in that specific scenario, but that's acceptable for now.

                        Optional<String> existingBranchOpt = existsLocalPrBranch(prNumber); // I/O
                        String syncStatus = getLocalSyncStatus(prNumber, prHeadSha); // I/O

                        SwingUtilities.invokeLater(() -> {
                            // Check if the selection is still the same
                            int currentRow = prTable.getSelectedRow();
                            if (currentRow != -1 && currentRow < displayedPrs.size() && displayedPrs.get(currentRow).getNumber() == prNumber) {
                                if (existingBranchOpt.isPresent()) {
                                    String existingBranchName = existingBranchOpt.get();
                                    if ("behind".equals(syncStatus)) {
                                        checkoutPrButton.setText("Update Local Branch");
                                        checkoutPrButton.setEnabled(true);
                                    } else { // "ok" or other status
                                        try {
                                            if (getRepo() != null && getRepo().getCurrentBranch().equals(existingBranchName)) {
                                                checkoutPrButton.setText("Current Branch");
                                                checkoutPrButton.setEnabled(false);
                                            } else {
                                                checkoutPrButton.setText("Check Out");
                                                checkoutPrButton.setEnabled(true);
                                            }
                                        } catch (Exception ex) {
                                            logger.warn("Error getting current branch, defaulting checkout button state", ex);
                                            checkoutPrButton.setText("Check Out");
                                            checkoutPrButton.setEnabled(true);
                                        }
                                    }
                                } else { // No local branch
                                    checkoutPrButton.setText("Check Out");
                                    checkoutPrButton.setEnabled(true);
                                }
                            }
                        });
                        return null;
                    });
                } else { // No selection or invalid row
                    checkoutPrButton.setText("Check Out");
                    checkoutPrButton.setEnabled(false);
                    diffPrButton.setEnabled(false);
                    openInBrowserButton.setEnabled(false);
                    prCommitsTableModel.setRowCount(0); // Clear commits table
                    prFilesTableModel.setRowCount(0); // Clear files table
                    currentPrCommitDetailsList.clear();
                }
            } else if (prTable.getSelectedRow() == -1) { // if selection is explicitly cleared
                 disablePrButtonsAndClearCommits();
            }
        });

        // Add commit selection listener to update files table
        prCommitsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateFilesForSelectedCommits();
            }
        });

        MainProject.addSettingsChangeListener(this);
        updatePrList(); // async
    }

    /**
     * Tracks a Future that might contain calls to GitHub API, so that it can be cancelled if GitHub access token changes.
     */
    private void trackCancellableFuture(Future<?> future) {
        futuresToBeCancelledOnGutHubTokenChange.removeIf(Future::isDone);
        if (future != null) {
            futuresToBeCancelledOnGutHubTokenChange.add(future);
        }
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

    private void disablePrButtonsAndClearCommits() {
        checkoutPrButton.setText("Check Out");
        checkoutPrButton.setEnabled(false);
        diffPrButton.setEnabled(false);
        openInBrowserButton.setEnabled(false);
        prCommitsTableModel.setRowCount(0);
        prFilesTableModel.setRowCount(0);
        currentPrCommitDetailsList.clear();
    }


    private Optional<String> existsLocalPrBranch(int prNumber) {
        try {
            var repo = getRepo();
            if (repo == null) {
                return Optional.empty();
            }
            String prefix = "pr-" + prNumber;
            List<String> localBranches = repo.listLocalBranches();
            for (String branchName : localBranches) {
                // Matches "pr-<number>" or "pr-<number>-<digits>"
                if (branchName.equals(prefix) ||
                        (branchName.startsWith(prefix + "-") && branchName.substring(prefix.length() + 1).matches("\\d+"))) {
                    return Optional.of(branchName);
                }
            }
        } catch (Exception e) {
            logger.warn("Error checking for existing local PR branch for PR #{}: {}", prNumber, e.getMessage(), e);
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
                    disablePrButtonsAndClearCommits();
                    authorChoices.clear();
                    labelChoices.clear();
                    assigneeChoices.clear();
                });
                return null;
            }

            for (var pr: fetchedPrs) {
                pr.isMerged(); // pre-fetch this before we go back to EDT
            }

            // Process fetched PRs on EDT
            List<org.kohsuke.github.GHPullRequest> finalFetchedPrs = fetchedPrs;
            SwingUtilities.invokeLater(() -> {
                allPrsFromApi = new ArrayList<>(finalFetchedPrs);
                prCommitsCache.clear(); // Clear commits cache for new PR list
                // ciStatusCache is updated incrementally, not fully cleared here unless error
                populateDynamicFilterChoices(allPrsFromApi);
                filterAndDisplayPrs(); // Apply current filters, which will also trigger CI fetching
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
            disablePrButtons();
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
                    if (pr.getUpdatedAt() != null)
                        formattedUpdated = gitPanel.formatCommitDate(pr.getUpdatedAt(), today);
                    var headRepo = pr.getHead().getRepository();
                    if (headRepo != null && headRepo.isFork()) forkInfo = headRepo.getFullName();
                } catch (IOException ex) {
                    logger.warn("Could not get metadata for PR #{}", pr.getNumber(), ex);
                }

                String statusValue;
                try {
                    if (pr.isMerged()) {
                        statusValue = "Merged";
                    } else {
                        statusValue = ciStatusCache.getOrDefault(pr.getNumber(), "?");
                    }
                } catch (IOException ex) {
                    logger.warn("Error checking if PR #{} is merged: {}", pr.getNumber(), ex.getMessage());
                    statusValue = ciStatusCache.getOrDefault(pr.getNumber(), "Err"); // Fallback
                }

                prTableModel.addRow(new Object[]{
                        "#" + pr.getNumber(), pr.getTitle(), author, formattedUpdated,
                        pr.getBase().getRef(), forkInfo, statusValue
                });
            }
        }
        // Buttons state will be managed by selection listener or if selection is empty
        if (prTable.getSelectedRow() == -1) {
            disablePrButtons();
        } else {
            // Trigger selection listener to update button states correctly for the (potentially new) selection
            prTable.getSelectionModel().setValueIsAdjusting(true); // force re-evaluation
            prTable.getSelectionModel().setValueIsAdjusting(false);
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
                    String status = getCiStatus(prToFetch);
                    fetchedStatuses.put(prToFetch.getNumber(), status);
                } catch (IOException e) {
                    logger.warn("Failed to get CI status for PR #{} during targeted fetch", prToFetch.getNumber(), e);
                    // Store a generic error marker if needed, or let it be absent from results
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
                 reportBackgroundError("Error executing CI status fetch worker task", (Exception) e.getCause());
            } catch (Exception e) {
                reportBackgroundError("Error processing CI status fetch results", e);
            }
        }
    }

    private void fetchCiStatusesForDisplayedPrs() {
        assert SwingUtilities.isEventDispatchThread();

        List<GHPullRequest> prsRequiringCiFetch = new ArrayList<>();
        for (GHPullRequest pr : displayedPrs) {
            try {
                if (pr.isMerged()) {
                    continue;
                }
            } catch (IOException e) {
                logger.warn("Could not determine if PR #{} is merged. Skipping status fetch. Error: {}", pr.getNumber(), e.getMessage());
                continue;
            }

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
        protected Map<Integer, List<String>> doInBackground() throws Exception {
            Map<Integer, List<String>> fetchedFilesMap = new HashMap<>();
            var repo = getRepo();
            if (repo == null) {
                logger.warn("GitRepo not available, cannot fetch PR files.");
                return fetchedFilesMap;
            }

            for (GHPullRequest pr : prsToFetchFilesFor) {
                if (isCancelled()) break;
                try {
                    String headSha = pr.getHead().getSha();
                    String baseSha = pr.getBase().getSha();
                    int prNumber = pr.getNumber();

                    // Ensure head SHA is available
                    String headFetchRef = String.format("+refs/pull/%d/head:refs/remotes/origin/pr/%d/head", prNumber, prNumber);
                    // Assuming 'origin' is the primary remote. Fork logic might be needed if this fails.

                    GitHubAuth auth;
                    try {
                        auth = GitHubAuth.getOrCreateInstance(this.project);
                    } catch (IOException e) {
                        logger.warn("Failed to get GitHubAuth instance in PrFilesFetcherWorker for PR #{}: {}", prNumber, e.getMessage(), e);
                        throw new RuntimeException("Failed to initialize GitHubAuth for PR #" + prNumber + ": " + e.getMessage(), e);
                    }

                    if (!ensureShaIsLocal(repo, headSha, headFetchRef, "origin")) {
                        // If direct PR head fetch fails, try fetching the source branch if it's from origin
                        if (pr.getHead().getRepository().getFullName().equals(auth.getOwner() + "/" + auth.getRepoName())) {
                            String headBranchName = pr.getHead().getRef(); // e.g. "feature/my-branch" or "refs/heads/feature/my-branch"
                            if (headBranchName.startsWith("refs/heads/")) headBranchName = headBranchName.substring("refs/heads/".length());
                            String headBranchFetchRef = String.format("+refs/heads/%s:refs/remotes/origin/%s", headBranchName, headBranchName);
                            ensureShaIsLocal(repo, headSha, headBranchFetchRef, "origin");
                        } else {
                             logger.warn("PR #{} head {} is from a fork, advanced fork fetching not yet implemented in PrFilesFetcherWorker.", prNumber, GitUiUtil.shortenCommitId(headSha));
                             // For simplicity, we are not handling complex fork remote setups here.
                             // Checkout logic has more complete fork handling.
                        }
                    }


                    // Ensure base SHA is available
                    String baseBranchName = pr.getBase().getRef(); // e.g. "main"
                    String baseFetchRef = String.format("+refs/heads/%s:refs/remotes/origin/%s", baseBranchName, baseBranchName);
                    ensureShaIsLocal(repo, baseSha, baseFetchRef, "origin");


                    if (repo.resolve(headSha) != null && repo.resolve(baseSha) != null) {
                        List<String> changedFiles;
                        try {
                            String mergeBase;
                            if (pr.isMerged()) {
                                // For merged PRs, use the original base SHA to get historical diff
                                mergeBase = repo.getMergeBase(headSha, baseSha);
                            } else {
                                // For open PRs, use current tip of target branch to see current diff
                                String currentTargetTip = repo.resolve(pr.getBase().getRef()).getName();
                                mergeBase = repo.getMergeBase(headSha, currentTargetTip);
                            }

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
                        } catch (IOException e) {
                            logger.warn("Could not determine PR #{} merge status, using fallback diff calculation: {}", prNumber, e.getMessage());
                            changedFiles = repo.listFilesChangedBetweenCommits(headSha, baseSha)
                                    .stream()
                                    .map(projFile -> projFile.toString())
                                    .collect(Collectors.toList());
                        }
                        fetchedFilesMap.put(prNumber, changedFiles);
                    } else {
                        logger.warn("Could not resolve head ({}) or base ({}) SHA for PR #{} to list files.", GitUiUtil.shortenCommitId(headSha), GitUiUtil.shortenCommitId(baseSha), prNumber);
                        fetchedFilesMap.put(prNumber, List.of("Error: Could not resolve SHAs locally."));
                    }
                } catch (Exception e) {
                    logger.error("Error fetching/processing files for PR #{}", pr.getNumber(), e);
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
                 reportBackgroundError("Error executing PR files fetcher worker task", (Exception) e.getCause());
            } catch (Exception e) {
                reportBackgroundError("Error processing PR files fetcher results", e);
            }
        }
    }



    private String getBaseFilterValue(String displayOptionWithCount) {
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

    private void disablePrButtons() {
        checkoutPrButton.setText("Check Out");
        checkoutPrButton.setEnabled(false);
        diffPrButton.setEnabled(false);
        openInBrowserButton.setEnabled(false);
    }


    private String getLocalSyncStatus(int prNumber, String prHeadSha) {
        try {
            var repo = getRepo();
            if (repo == null) return "";
            var localBranchOpt = existsLocalPrBranch(prNumber);
            if (localBranchOpt.isEmpty()) return ""; // no local branch
            var localBranch = localBranchOpt.get();
            var localHeadObj = repo.resolve(localBranch);
            if (localHeadObj == null) return ""; // should not happen
            var localHeadSha = localHeadObj.getName();
            if (localHeadSha.equals(prHeadSha)) return "ok"; // up to date
            return "behind";
        } catch (Exception e) {
            logger.warn("Error determining sync status for PR #{}: {}", prNumber, e.getMessage());
            return "";
        }
    }

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
                if (repo == null) {
                    throw new IOException("Git repository not available for local commit analysis.");
                }

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

        Optional<String> existingLocalBranchOpt = existsLocalPrBranch(prNumber);

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
                var remoteUrl = getRepo().getRemoteUrl();
                GitUiUtil.OwnerRepo ownerRepo = GitUiUtil.parseOwnerRepoFromUrl(remoteUrl);
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

                String baseBranchNameSuffix = "pr-" + prNumber;
                String localBranchName = baseBranchNameSuffix;
                List<String> localBranches = getRepo().listLocalBranches();
                int suffix = 1;
                while (localBranches.contains(localBranchName)) {
                    localBranchName = baseBranchNameSuffix + "-" + suffix++;
                }

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
                     reportBackgroundError("Error executing PR files fetcher worker task", (Exception) e.getCause());
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
