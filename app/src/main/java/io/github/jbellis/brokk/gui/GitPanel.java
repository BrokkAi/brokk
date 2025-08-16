package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.components.VerticalLabel;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * Main Git panel that hosts tabs for commit actions and various histories/logs. Public API remains the same as before,
 * but the commit tab is now handled by GitCommitTab, and file-history is handled by GitHistoryTab.
 */
public class GitPanel extends JPanel {

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;

    // The “Changes” tab, now delegated to GitCommitTab
    private final GitCommitTab commitTab;

    // The “Log” tab
    private final GitLogTab gitLogTab;

    // Worktrees tab
    @Nullable
    private GitWorktreeTab gitWorktreeTab;

    // Tracks open file-history tabs by file path
    private final Map<String, GitHistoryTab> fileHistoryTabs = new HashMap<>();

    /** Constructs the GitPanel containing the Commit tab, Log tab, etc. */
    public GitPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Git",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

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

        // Tabbed pane for commit, log, and file-history tabs (placed on the LEFT)
        tabbedPane = new JTabbedPane(JTabbedPane.LEFT);
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

        // Rotate tab captions vertically after all tabs are present
        VerticalLabel.applyVerticalTabLabels(tabbedPane);

        updateBorderTitle(); // Set initial title with branch name
    }

    /**
     * Called by GitIssuesTab when the provider changes – delegates to Chrome so the top-level Issues panel can be
     * recreated.
     */
    public void recreateIssuesTab() {
        chrome.recreateIssuesPanel();
    }

    private String getCurrentBranchName() {
        return GitUiUtil.getCurrentBranchName(contextManager.getProject());
    }

    private void updateBorderTitle() {
        var branchName = getCurrentBranchName();
        GitUiUtil.updatePanelBorderWithBranch(this, "Git", branchName);
    }

    /** Updates the panel to reflect the current repo state. Refreshes both the log tab and the commit tab. */
    public void updateRepo() {
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

    /** Public API to re-populate the commit table etc. (delegated to GitCommitTab now). */
    public void updateCommitPanel() {
        commitTab.updateCommitPanel();
    }

    /** For GitCommitTab or external code to re-populate the Log tab. */
    void updateLogTab() {
        gitLogTab.update();
    }

    /** For GitCommitTab or external code to select the current branch in the Log tab. */
    void selectCurrentBranchInLogTab() {
        gitLogTab.selectCurrentBranch();
    }

    /** Switches to the Log tab and highlights the specified commit. Called by GitHistoryTab or external code. */
    void showCommitInLogTab(String commitId) {
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

    /** Adds a new tab showing the commit-history of a specific file. If already opened, just selects it. */
    public void addFileHistoryTab(ProjectFile file) {
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

    /** Helper to switch to a previously opened file-history tab by file path. */
    private void selectExistingFileHistoryTab(String filePath) {
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

    public GitCommitTab getCommitTab() {
        return commitTab;
    }

    /** Helper to return a short filename for tab titles, e.g. "Main.java" from "src/foo/Main.java". */
    String getFileTabName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        return (lastSlash >= 0) ? filePath.substring(lastSlash + 1) : filePath;
    }
}
