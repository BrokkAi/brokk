package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.git.GitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHUser;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class GitIssuesTab extends JPanel {
    private static final Logger logger = LogManager.getLogger(GitIssuesTab.class);

    // Issue Table Column Indices
    private static final int ISSUE_COL_NUMBER    = 0;
    private static final int ISSUE_COL_TITLE     = 1;
    private static final int ISSUE_COL_AUTHOR    = 2;
    private static final int ISSUE_COL_UPDATED   = 3;
    private static final int ISSUE_COL_LABELS    = 4;
    private static final int ISSUE_COL_ASSIGNEES = 5;
    private static final int ISSUE_COL_STATUS    = 6;

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel;

    private JTable issueTable;
    private DefaultTableModel issueTableModel;
    private JTextPane issueBodyTextPane;
    private JButton copyIssueDescriptionButton;
    private JButton openInBrowserButton;

    private FilterBox statusFilter;
    private FilterBox authorFilter;
    private FilterBox labelFilter;
    private FilterBox assigneeFilter;

    private List<GHIssue> allIssuesFromApi = new ArrayList<>();
    private List<GHIssue> displayedIssues = new ArrayList<>();

    // Store default options for static filters to easily reset them
    private static final List<String> STATUS_FILTER_OPTIONS = List.of("Open", "Closed"); // "All" is null selection

    // Lists to hold choices for dynamic filters
    private List<String> authorChoices = new ArrayList<>();
    private List<String> labelChoices = new ArrayList<>();
    private List<String> assigneeChoices = new ArrayList<>();

    private GitHubAuth gitHubAuth;
    private final GfmRenderer gfmRenderer;


    public GitIssuesTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.gitPanel = gitPanel;
        this.gfmRenderer = new GfmRenderer();

        // Split panel with Issues on left (larger) and issue details on right (smaller)
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.6); // 60% for Issue list, 40% for details

        // --- Left side - Issues table and filters ---
        // This mainIssueAreaPanel will hold filters on WEST and table+buttons on CENTER
        JPanel mainIssueAreaPanel = new JPanel(new BorderLayout(Constants.H_GAP, 0));
        mainIssueAreaPanel.setBorder(BorderFactory.createTitledBorder("Issues"));

        // Vertical Filter Panel
        JPanel verticalFilterPanel = new JPanel();
        verticalFilterPanel.setLayout(new BoxLayout(verticalFilterPanel, BoxLayout.Y_AXIS));

        JLabel filterLabel = new JLabel("Filter:");
        filterLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        verticalFilterPanel.add(filterLabel);
        verticalFilterPanel.add(Box.createVerticalStrut(Constants.V_GAP)); // Space after label

        statusFilter = new FilterBox(this.chrome, "Status", () -> STATUS_FILTER_OPTIONS, "Open");
        statusFilter.setToolTipText("Filter by issue status");
        statusFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusFilter.addPropertyChangeListener("value", e -> {
            // Status filter change triggers a new API fetch
            updateIssueList();
        });
        verticalFilterPanel.add(statusFilter);

        authorFilter = new FilterBox(this.chrome, "Author", this::getAuthorFilterOptions);
        authorFilter.setToolTipText("Filter by author");
        authorFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        authorFilter.addPropertyChangeListener("value", e -> filterAndDisplayIssues());
        verticalFilterPanel.add(authorFilter);

        labelFilter = new FilterBox(this.chrome, "Label", this::getLabelFilterOptions);
        labelFilter.setToolTipText("Filter by label");
        labelFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        labelFilter.addPropertyChangeListener("value", e -> filterAndDisplayIssues());
        verticalFilterPanel.add(labelFilter);

        assigneeFilter = new FilterBox(this.chrome, "Assignee", this::getAssigneeFilterOptions);
        assigneeFilter.setToolTipText("Filter by assignee");
        assigneeFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        assigneeFilter.addPropertyChangeListener("value", e -> filterAndDisplayIssues());
        verticalFilterPanel.add(assigneeFilter);

        verticalFilterPanel.add(Box.createVerticalGlue()); // Pushes filters to the top

        mainIssueAreaPanel.add(verticalFilterPanel, BorderLayout.WEST);

        // Panel for Issue Table (CENTER) and Issue Buttons (SOUTH)
        JPanel issueTableAndButtonsPanel = new JPanel(new BorderLayout());

        // Issue Table
        issueTableModel = new DefaultTableModel(
                new Object[]{"#", "Title", "Author", "Updated", "Labels", "Assignees", "Status"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        issueTable = new JTable(issueTableModel);
        issueTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        issueTable.setRowHeight(18);
        issueTable.getColumnModel().getColumn(ISSUE_COL_NUMBER).setPreferredWidth(50);    // #
        issueTable.getColumnModel().getColumn(ISSUE_COL_TITLE).setPreferredWidth(350);   // Title
        issueTable.getColumnModel().getColumn(ISSUE_COL_AUTHOR).setPreferredWidth(100);   // Author
        issueTable.getColumnModel().getColumn(ISSUE_COL_UPDATED).setPreferredWidth(120); // Updated
        issueTable.getColumnModel().getColumn(ISSUE_COL_LABELS).setPreferredWidth(150);    // Labels
        issueTable.getColumnModel().getColumn(ISSUE_COL_ASSIGNEES).setPreferredWidth(150); // Assignees
        issueTable.getColumnModel().getColumn(ISSUE_COL_STATUS).setPreferredWidth(70);    // Status

        // Ensure tooltips are enabled for the table
        ToolTipManager.sharedInstance().registerComponent(issueTable);

        issueTableAndButtonsPanel.add(new JScrollPane(issueTable), BorderLayout.CENTER);

        // Button panel for Issues
        JPanel issueButtonPanel = new JPanel();
        issueButtonPanel.setBorder(BorderFactory.createEmptyBorder(new Constants().V_GLUE, 0, 0, 0));
        issueButtonPanel.setLayout(new BoxLayout(issueButtonPanel, BoxLayout.X_AXIS));

        copyIssueDescriptionButton = new JButton("Copy Description");
        copyIssueDescriptionButton.setToolTipText("Copy the selected issue's description to the clipboard");
        copyIssueDescriptionButton.setEnabled(false);
        copyIssueDescriptionButton.addActionListener(e -> copySelectedIssueDescription());
        issueButtonPanel.add(copyIssueDescriptionButton);
        issueButtonPanel.add(Box.createHorizontalStrut(new Constants().H_GAP));

        openInBrowserButton = new JButton("Open in Browser");
        openInBrowserButton.setToolTipText("Open the selected issue in your web browser");
        openInBrowserButton.setEnabled(false);
        openInBrowserButton.addActionListener(e -> openSelectedIssueInBrowser());
        issueButtonPanel.add(openInBrowserButton);

        issueButtonPanel.add(Box.createHorizontalGlue()); // Pushes refresh button to the right

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> updateIssueList());
        issueButtonPanel.add(refreshButton);

        issueTableAndButtonsPanel.add(issueButtonPanel, BorderLayout.SOUTH);
        mainIssueAreaPanel.add(issueTableAndButtonsPanel, BorderLayout.CENTER);

        // Right side - Details of the selected issue
        JPanel issueDetailPanel = new JPanel(new BorderLayout());
        issueDetailPanel.setBorder(BorderFactory.createTitledBorder("Issue Details"));

        issueBodyTextPane = new JTextPane();
        issueBodyTextPane.setEditorKit(new AutoScalingHtmlPane.ScalingHTMLEditorKit());
        issueBodyTextPane.setEditable(false);
        issueBodyTextPane.setContentType("text/html"); // For rendering HTML
        // JTextPane handles line wrapping and word wrapping by default with HTML.
        // Basic HTML like <p> and <br> will control flow.
        // For plain text, setContentType("text/plain") would make setLineWrap relevant if it were a JTextArea.
        JScrollPane issueBodyScrollPane = new JScrollPane(issueBodyTextPane);
        issueBodyScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        issueDetailPanel.add(issueBodyScrollPane, BorderLayout.CENTER);

        // Add the panels to the split pane
        splitPane.setLeftComponent(mainIssueAreaPanel);
        splitPane.setRightComponent(issueDetailPanel);

        add(splitPane, BorderLayout.CENTER);

        // Listen for Issue selection changes
        issueTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && issueTable.getSelectedRow() != -1) {
                int viewRow = issueTable.getSelectedRow();
                if (viewRow != -1) {
                    int modelRow = issueTable.convertRowIndexToModel(viewRow);
                    if (modelRow < 0 || modelRow >= displayedIssues.size()) {
                        logger.warn("Selected row {} (model {}) is out of bounds for displayed issues size {}", viewRow, modelRow, displayedIssues.size());
                        disableIssueActionsAndClearDetails();
                        return;
                    }
                    GHIssue selectedIssue = displayedIssues.get(modelRow);
                    loadAndRenderIssueBody(selectedIssue);
                    copyIssueDescriptionButton.setEnabled(true);
                    openInBrowserButton.setEnabled(true);
                } else { // No selection or invalid row
                    disableIssueActionsAndClearDetails();
                }
            } else if (issueTable.getSelectedRow() == -1) { // if selection is explicitly cleared
                disableIssueActionsAndClearDetails();
            }
        });

        updateIssueList(); // async
    }

    private void disableIssueActionsAndClearDetails() {
        copyIssueDescriptionButton.setEnabled(false);
        openInBrowserButton.setEnabled(false);
        issueBodyTextPane.setContentType("text/html");
        issueBodyTextPane.setText("");
    }

    private void loadAndRenderIssueBody(GHIssue issue) {
        assert SwingUtilities.isEventDispatchThread();

        if (issue == null) {
            issueBodyTextPane.setContentType("text/html");
            issueBodyTextPane.setText("");
            return;
        }

        String rawMarkdownBody = issue.getBody();

        if (rawMarkdownBody == null || rawMarkdownBody.isBlank()) {
            issueBodyTextPane.setContentType("text/html");
            issueBodyTextPane.setText("<html><body><p>No description provided.</p></body></html>");
            return;
        }

        // Show loading message
        issueBodyTextPane.setContentType("text/html");
        issueBodyTextPane.setText("<html><body><p><i>Loading description...</i></p></body></html>");

        contextManager.submitBackgroundTask("Rendering Issue Description", () -> {
            try {
                String htmlBody = this.gfmRenderer.render(rawMarkdownBody);
                SwingUtilities.invokeLater(() -> {
                    issueBodyTextPane.setContentType("text/html");
                    issueBodyTextPane.setText(htmlBody);
                    issueBodyTextPane.setCaretPosition(0); // Scroll to top
                });
            } catch (Exception e) {
                logger.error("Failed to render markdown for issue #{}: {}", issue.getNumber(), e.getMessage(), e);
                SwingUtilities.invokeLater(() -> {
                    // Display raw markdown with an error message
                    issueBodyTextPane.setContentType("text/plain"); // Switch to plain text for raw markdown
                    issueBodyTextPane.setText("Failed to render description. Showing raw markdown:\n\n" + rawMarkdownBody);
                    issueBodyTextPane.setCaretPosition(0);
                });
            }
            return null;
        });
    }

    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }

    /**
     * Holds a parsed "owner" and "repo" from a Git remote URL
     */
    private record OwnerRepo(String owner, String repo) {
    }

    /**
     * Parse a Git remote URL of form:
     * - https://github.com/OWNER/REPO.git
     * - git@github.com:OWNER/REPO.git
     * - ssh://github.com/OWNER/REPO
     * - or any variant that ends with OWNER/REPO(.git)
     * This attempts to extract the last two path segments
     * as "owner" and "repo". Returns null if it cannot.
     */
    private OwnerRepo parseOwnerRepoFromUrl(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            logger.warn("Remote URL is blank or null");
            return null;
        }

        // Strip trailing ".git" if present
        String cleaned = remoteUrl.endsWith(".git")
                         ? remoteUrl.substring(0, remoteUrl.length() - 4)
                         : remoteUrl;
        logger.debug("Cleaned repo url is {}", cleaned);

        cleaned = cleaned.replace('\\', '/');

        int protocolIndex = cleaned.indexOf("://");
        if (protocolIndex >= 0) {
            cleaned = cleaned.substring(protocolIndex + 3);
        }

        int atIndex = cleaned.indexOf('@');
        if (atIndex >= 0) {
            cleaned = cleaned.substring(atIndex + 1);
        }

        var segments = cleaned.split("[/:]+");

        if (segments.length < 2) {
            logger.warn("Unable to parse owner/repo from remote URL: {}", remoteUrl);
            return null;
        }

        String repo = segments[segments.length - 1];
        String owner = segments[segments.length - 2];
        logger.debug("Parsed repo as {} owned by {}", repo, owner);

        if (owner.isBlank() || repo.isBlank()) {
            logger.warn("Parsed blank owner/repo from remote URL: {}", remoteUrl);
            return null;
        }

        return new OwnerRepo(owner, repo);
    }

    private synchronized GitHubAuth getGitHubAuthInstance() throws IOException {
        var repo = getRepo(); // GitRepo instance
        if (repo == null) {
            throw new IOException("Git repository not available.");
        }
        var remoteUrl = repo.getRemoteUrl();
        var ownerRepo = parseOwnerRepoFromUrl(remoteUrl);
        if (ownerRepo == null) {
            throw new IOException("Could not parse 'owner/repo' from remote: " + remoteUrl);
        }

        if (this.gitHubAuth == null ||
            !this.gitHubAuth.getOwner().equals(ownerRepo.owner()) ||
            !this.gitHubAuth.getRepoName().equals(ownerRepo.repo())) {
            logger.info("Creating or updating GitHubAuth instance for {}/{}", ownerRepo.owner(), ownerRepo.repo());
            this.gitHubAuth = new GitHubAuth(contextManager.getProject(), ownerRepo.owner(), ownerRepo.repo());
        }
        return this.gitHubAuth;
    }

    /**
     * Fetches open GitHub issues and populates the issue table.
     */
    private void updateIssueList() {
        contextManager.submitBackgroundTask("Fetching GitHub Issues", () -> {
            List<GHIssue> fetchedIssues;
            try {
                GitHubAuth auth = getGitHubAuthInstance();

                String selectedStatusOption = statusFilter.getSelected();
                GHIssueState apiState;
                if ("Open".equals(selectedStatusOption)) {
                    apiState = GHIssueState.OPEN;
                } else if ("Closed".equals(selectedStatusOption)) {
                    apiState = GHIssueState.CLOSED;
                } else { // null or any other string implies ALL for safety, though options are limited
                    apiState = GHIssueState.ALL;
                }

                // This method needs to be added to GitHubAuth.java
                fetchedIssues = auth.listIssues(apiState)
                        .stream()
                        .filter(issue -> !issue.isPullRequest())
                        .toList();
                logger.debug("Fetched {} issues", fetchedIssues.size());
            } catch (Exception ex) {
                logger.error("Failed to fetch issues", ex);
                SwingUtilities.invokeLater(() -> {
                    allIssuesFromApi.clear();
                    displayedIssues.clear();
                    issueTableModel.setRowCount(0);
                    issueTableModel.addRow(new Object[]{
                            "", "Error fetching issues: " + ex.getMessage(), "", "", "", "", ""
                    });
                    disableIssueActionsAndClearDetails();
                    authorChoices.clear();
                    labelChoices.clear();
                    assigneeChoices.clear();
                });
                return null;
            }

            // Process fetched issues on EDT
            List<GHIssue> finalFetchedIssues = fetchedIssues;
            SwingUtilities.invokeLater(() -> {
                allIssuesFromApi = new ArrayList<>(finalFetchedIssues);
                populateDynamicFilterChoices(allIssuesFromApi);
                filterAndDisplayIssues(); // Apply current filters
            });
            return null;
        });
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

    private void populateDynamicFilterChoices(List<GHIssue> issues) {
        // Author Filter
        Map<String, Integer> authorCounts = new HashMap<>();
        for (var issue : issues) {
            try {
                GHUser user = issue.getUser();
                if (user != null) {
                    String login = user.getLogin();
                    if (login != null && !login.isBlank()) {
                        authorCounts.merge(login, 1, Integer::sum);
                    }
                }
            } catch (IOException e) {
                logger.warn("Could not get user or login for issue #{}", issue.getNumber(), e);
            }
        }
        authorChoices = generateFilterOptionsList(authorCounts);

        // Label Filter
        Map<String, Integer> labelCounts = new HashMap<>();
        for (var issue : issues) {
            for (GHLabel label : issue.getLabels()) {
                if (!label.getName().isBlank()) {
                    labelCounts.merge(label.getName(), 1, Integer::sum);
                }
            }
        }
        labelChoices = generateFilterOptionsList(labelCounts);

        // Assignee Filter
        Map<String, Integer> assigneeCounts = new HashMap<>();
        for (var issue : issues) {
            for (GHUser assignee : issue.getAssignees()) {
                String login = assignee.getLogin(); // Does not throw IOException
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

    private void filterAndDisplayIssues() {
        assert SwingUtilities.isEventDispatchThread();
        displayedIssues.clear();
        String selectedAuthorDisplay = authorFilter.getSelected();
        String selectedLabelDisplay = labelFilter.getSelected();
        String selectedAssigneeDisplay = assigneeFilter.getSelected();

        String selectedAuthorActual = getBaseFilterValue(selectedAuthorDisplay);
        String selectedLabelActual = getBaseFilterValue(selectedLabelDisplay);
        String selectedAssigneeActual = getBaseFilterValue(selectedAssigneeDisplay);

        for (var issue : allIssuesFromApi) {
            boolean matches = true;
            try {
                // Author filter
                if (selectedAuthorActual != null && (issue.getUser() == null || !selectedAuthorActual.equals(issue.getUser().getLogin()))) {
                    matches = false;
                }
                // Label filter
                if (matches && selectedLabelActual != null) {
                    matches = issue.getLabels().stream().anyMatch(l -> selectedLabelActual.equals(l.getName()));
                }
                // Assignee filter
                if (matches && selectedAssigneeActual != null) {
                    boolean assigneeMatch = false;
                    for (GHUser assignee : issue.getAssignees()) {
                        String login = assignee.getLogin(); // Can throw IOException
                        if (selectedAssigneeActual.equals(login)) {
                            assigneeMatch = true;
                            break;
                        }
                    }
                    matches = assigneeMatch;
                }
            } catch (IOException e) {
                logger.warn("Error accessing issue data during filtering for issue #{}", issue.getNumber(), e);
                matches = false; // Skip issue if data can't be accessed
            }

            if (matches) {
                displayedIssues.add(issue);
            }
        }

        // Update table model
        issueTableModel.setRowCount(0);
        var today = LocalDate.now();
        if (displayedIssues.isEmpty()) {
            issueTableModel.addRow(new Object[]{"", "No matching issues found", "", "", "", "", ""});
            disableIssueActions();
        } else {
            // Sort issues by update date, newest first
            displayedIssues.sort(Comparator.comparing(issue -> {
                try {
                    return issue.getUpdatedAt();
                } catch (IOException e) {
                    return new Date(0); // Oldest on error
                }
            }, Comparator.nullsLast(Comparator.reverseOrder())));

            for (var issue : displayedIssues) {
                String author = "";
                String formattedUpdated = "";
                String labels = "";
                String assignees = "";
                String statusValue = "";
                try {
                    if (issue.getUser() != null) author = issue.getUser().getLogin();
                    if (issue.getUpdatedAt() != null) {
                        formattedUpdated = gitPanel.formatCommitDate(issue.getUpdatedAt(), today);
                    }
                    labels = issue.getLabels().stream().map(GHLabel::getName).collect(Collectors.joining(", "));
                    assignees = issue.getAssignees().stream()
                                     .map(GHUser::getLogin) // GHUser.getLogin() does not throw IOException
                                     .filter(login -> login != null && !login.isBlank())
                                     .collect(Collectors.joining(", "));
                    statusValue = issue.getState().toString();
                } catch (IOException ex) { // This catch is for outer operations like issue.getUser(), issue.getUpdatedAt() etc.
                    logger.warn("Could not get metadata for issue #{}", issue.getNumber(), ex);
                }

                issueTableModel.addRow(new Object[]{
                        "#" + issue.getNumber(), issue.getTitle(), author, formattedUpdated,
                        labels, assignees, statusValue
                });
            }
        }
        // Buttons state will be managed by selection listener or if selection is empty
        if (issueTable.getSelectedRow() == -1) {
            disableIssueActions();
        } else {
            // Trigger selection listener to update button states correctly for the (potentially new) selection
            issueTable.getSelectionModel().setValueIsAdjusting(true); // force re-evaluation
            issueTable.getSelectionModel().setValueIsAdjusting(false);
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

    private void disableIssueActions() {
        copyIssueDescriptionButton.setEnabled(false);
        openInBrowserButton.setEnabled(false);
    }

    private void copySelectedIssueDescription() {
        int selectedRow = issueTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedIssues.size()) {
            return;
        }
        GHIssue issue = displayedIssues.get(selectedRow);
        String body = issue.getBody();
        if (body != null && !body.isBlank()) {
            StringSelection stringSelection = new StringSelection(body);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, null);
            chrome.systemOutput("Issue #" + issue.getNumber() + " description copied to clipboard.");
        } else {
            chrome.systemOutput("Issue #" + issue.getNumber() + " has no description to copy.");
        }
    }

    private void openSelectedIssueInBrowser() {
        int selectedRow = issueTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedIssues.size()) {
            return;
        }
        GHIssue issue = displayedIssues.get(selectedRow);
        try {
            String url = issue.getHtmlUrl().toString();
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI(url));
            } else {
                chrome.toolError("Cannot open browser. Desktop API not supported.");
                logger.warn("Desktop.Action.BROWSE not supported, cannot open issue URL: {}", url);
            }
        } catch (Exception e) {
            chrome.toolErrorRaw("Error opening issue in browser: " + e.getMessage());
        }
    }
}
