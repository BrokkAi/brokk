package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.issues.IssueProviderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Main Git panel that hosts tabs for commit actions and various histories/logs.
 * Public API remains the same as before, but the commit tab is now handled by GitCommitTab,
 * and file-history is handled by GitHistoryTab.
 */
public class GitPanel extends JPanel
{
    private static final Logger logger = LogManager.getLogger(GitPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;

    // The “Changes” tab, now delegated to GitCommitTab
    private final GitCommitTab commitTab;

    // The “Log” tab
    private final GitLogTab gitLogTab;

    // The "Pull Requests" tab - conditionally added
    @Nullable 
    private GitPullRequestsTab pullRequestsTab;

    // The "Issues" tab - conditionally added
    @Nullable
    private GitIssuesTab issuesTab;

    // Worktrees tab
    @Nullable
    private GitWorktreeTab gitWorktreeTab;

    // Tracks open file-history tabs by file path
    private final Map<String, GitHistoryTab> fileHistoryTabs = new HashMap<>();

    /**
     * Constructs the GitPanel containing the Commit tab, Log tab, etc.
     */
    public GitPanel(Chrome chrome, ContextManager contextManager)
    {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Git ▼",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Size / layout
        int rows = 15;
        int rowHeight = 18;
        int overhead = 100;
        int totalHeight = rows * rowHeight + overhead;
        int preferredWidth = 1000;
        Dimension panelSize = new Dimension(preferredWidth, totalHeight);
        setPreferredSize(panelSize);

        // Collapse/expand if clicked on the title area
        this.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                // Approximate top border area
                if (e.getY() < 20) {
                    chrome.toggleGitPanel();
                }
            }
        });

        // Tabbed pane for commit, log, and file-history tabs
        tabbedPane = new JTabbedPane();
        add(tabbedPane, BorderLayout.CENTER);

        // 1) Changes tab (displays uncommitted changes, uses GitCommitTab internally)
        commitTab = new GitCommitTab(chrome, contextManager, this);
        tabbedPane.addTab("Changes", commitTab);

        // 2) Log tab (moved to GitLogTab)
        gitLogTab = new GitLogTab(chrome, contextManager);
        tabbedPane.addTab("Log", gitLogTab);

        // Get project for GitHub specific tabs
        var project = contextManager.getProject();

        // 3) Worktrees tab (always added for Git repositories)
        if (project.hasGit()) {
            gitWorktreeTab = new GitWorktreeTab(chrome, contextManager, this);
            tabbedPane.addTab("Worktrees", gitWorktreeTab);
        }

        // 4) Pull Requests tab (conditionally added)
        if (project.isGitHubRepo()) {
            pullRequestsTab = new GitPullRequestsTab(chrome, contextManager, this);
            tabbedPane.addTab("Pull Requests", pullRequestsTab);
        }

        // 5) Issues tab (conditionally added)
        if (project.getIssuesProvider().type() != IssueProviderType.NONE) {
            issuesTab = new GitIssuesTab(chrome, contextManager, this);
            tabbedPane.addTab("Issues", issuesTab);
        }

        updateBorderTitle(); // Set initial title with branch name
    }

    /**
     * Recreates the Issues tab, typically after a change in issue provider settings.
     * This ensures the tab uses the correct IssueService and reflects the new provider.
     */
    public void recreateIssuesTab() {
        SwingUtilities.invokeLater(() -> {
            var project = contextManager.getProject();
            int issuesTabIndex = -1;
            if (issuesTab != null) {
                issuesTabIndex = tabbedPane.indexOfComponent(issuesTab);
                if (issuesTabIndex != -1) {
                    tabbedPane.remove(issuesTabIndex);
                }
                MainProject.removeSettingsChangeListener(issuesTab); // Unregister old tab
                issuesTab = null; // Clear the reference
            }

            // Re-evaluate condition for showing the issues tab
            if (project.getIssuesProvider().type() != IssueProviderType.NONE)
            {
                issuesTab = new GitIssuesTab(chrome, contextManager, this); // New tab will register itself as listener
                if (issuesTabIndex != -1) {
                    tabbedPane.insertTab("Issues", null, issuesTab, "GitHub/Jira Issues", issuesTabIndex);
                    tabbedPane.setSelectedIndex(issuesTabIndex);
                } else {
                    tabbedPane.addTab("Issues", issuesTab);
                    tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
                }
                logger.info("Recreated Issues tab for provider type: {}", project.getIssuesProvider().type());
            } else {
                logger.info("Issues tab not recreated as conditions are not met (provider: {}).",
                            project.getIssuesProvider().type());
            }
            // No need to call updateIssueList() here, the new GitIssuesTab constructor does it.
        });
    }

    private String getCurrentBranchName() {
        try {
            var project = contextManager.getProject();
            IGitRepo repo = project.getRepo();
            return ((GitRepo) repo).getCurrentBranch();
        } catch (Exception e) {
            logger.warn("Could not get current branch name", e);
        }
        return "";
    }

    private void updateBorderTitle() {
        var branchName = getCurrentBranchName();
        SwingUtilities.invokeLater(() -> {
            var border = getBorder();
            if (border instanceof TitledBorder titledBorder) {
                String newTitle = !branchName.isBlank()
                                  ? "Git (" + branchName + ") ▼"
                                  : "Git ▼";
                titledBorder.setTitle(newTitle);
                revalidate();
                repaint();
            }
        });
    }

    /**
     * Updates the panel to reflect the current repo state.
     * Refreshes both the log tab and the commit tab.
     */
    public void updateRepo()
    {
        // Refresh commit state and stashes
        commitTab.updateCommitPanel();

        // Refresh log UI (branches, commits, stashes)
        gitLogTab.update();

        // Refresh worktrees if the tab exists
        if (gitWorktreeTab != null) {
            gitWorktreeTab.refresh();
        }
        updateBorderTitle(); // Refresh title on repo update
    }

    /**
     * Public API to re-populate the commit table etc. (delegated to GitCommitTab now).
     */
    public void updateCommitPanel() {
        commitTab.updateCommitPanel();
    }

    /**
     * For GitCommitTab or external code to re-populate the Log tab.
     */
    void updateLogTab()
    {
        gitLogTab.update();
    }

    /**
     * For GitCommitTab or external code to select the current branch in the Log tab.
     */
    void selectCurrentBranchInLogTab()
    {
        gitLogTab.selectCurrentBranch();
    }

    /**
     * Switches to the Log tab and highlights the specified commit.
     * Called by GitHistoryTab or external code.
     */
    void showCommitInLogTab(String commitId)
    {
        // Switch to "Log" tab if not visible
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if ("Log".equals(tabbedPane.getTitleAt(i))) {
                tabbedPane.setSelectedIndex(i);
                break;
            }
        }
        // And select the commit in the log
        gitLogTab.selectCommitById(commitId);
    }

    /**
     * Adds a new tab showing the commit-history of a specific file.
     * If already opened, just selects it.
     */
    public void addFileHistoryTab(ProjectFile file)
    {
        String filePath = file.toString();
        if (fileHistoryTabs.containsKey(filePath)) {
            // Already open; bring it to front
            selectExistingFileHistoryTab(filePath);
            return;
        }

        // Create a new GitHistoryTab
        GitHistoryTab historyTab = new GitHistoryTab(chrome, contextManager, this, file);

        // Build a custom tab header with close button
        JPanel tabHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabHeader.setOpaque(false);

        JLabel titleLabel = new JLabel(getFileTabName(filePath));
        titleLabel.setOpaque(false);

        JButton closeButton = new JButton("×");
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        closeButton.setPreferredSize(new Dimension(24, 24));
        closeButton.setMargin(new Insets(0, 0, 0, 0));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setToolTipText("Close");

        closeButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                closeButton.setForeground(Color.RED);
                closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                closeButton.setForeground(null);
                closeButton.setCursor(Cursor.getDefaultCursor());
            }
        });
        closeButton.addActionListener(e -> {
            int idx = tabbedPane.indexOfComponent(historyTab);
            if (idx >= 0) {
                tabbedPane.remove(idx);
                fileHistoryTabs.remove(filePath);
            }
        });

        tabHeader.add(titleLabel);
        tabHeader.add(closeButton);

        tabbedPane.addTab(getFileTabName(filePath), historyTab);
        int newIndex = tabbedPane.indexOfComponent(historyTab);
        tabbedPane.setTabComponentAt(newIndex, tabHeader);
        tabbedPane.setSelectedIndex(newIndex);

        fileHistoryTabs.put(filePath, historyTab);
    }

    /**
     * Helper to switch to a previously opened file-history tab by file path.
     */
    private void selectExistingFileHistoryTab(String filePath)
    {
        GitHistoryTab existing = fileHistoryTabs.get(filePath);
        if (existing == null) {
            return; // safety check
        }
        int count = tabbedPane.getTabCount();
        for (int i = 0; i < count; i++) {
            if (tabbedPane.getComponentAt(i) == existing) {
                tabbedPane.setSelectedIndex(i);
                break;
            }
        }
    }

    public GitCommitTab getCommitTab()
    {
        return commitTab;
    }

    /**
     * Helper to return a short filename for tab titles, e.g. "Main.java" from "src/foo/Main.java".
     */
    String getFileTabName(String filePath)
    {
        int lastSlash = filePath.lastIndexOf('/');
        return (lastSlash >= 0) ? filePath.substring(lastSlash + 1) : filePath;
    }

    // This method needs to be accessible by GitPullRequestsTab
    public String formatCommitDate(Date date, java.time.LocalDate today) {
        try {
            var zonedDateTime = date.toInstant().atZone(java.time.ZoneId.systemDefault());
            var commitDate = zonedDateTime.toLocalDate();

            if (commitDate.equals(today)) {
                return "Today " + zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            } else if (commitDate.equals(today.minusDays(1))) {
                return "Yesterday " + zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            } else if (commitDate.getYear() == today.getYear()) {
                return zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd HH:mm"));
            } else {
                return zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy MMM dd"));
            }
        } catch (Exception e) {
            // Log or handle potential timezone/conversion errors if necessary
            logger.warn("Error formatting commit date: {}", date, e);
            return date.toInstant().toString(); // Fallback
        }
    }
}
