package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.gui.git.GitCommitTab;
import ai.brokk.gui.git.GitIssuesTab;
import ai.brokk.gui.git.GitLogTab;
import ai.brokk.gui.git.GitPullRequestsTab;
import ai.brokk.gui.git.GitWorktreeTab;
import ai.brokk.gui.tests.TestRunnerPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.BadgedIcon;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.issues.IssueProviderType;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.prefs.Preferences;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates the left vertical sidebar containing Project Files, Tests, and Git tabs.
 * Manages tab state, badges, and collapse/expand behavior.
 */
public class ToolsPane extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(ToolsPane.class);

    private static final int SIDEBAR_COLLAPSED_THRESHOLD = 50;
    private static final int MIN_SIDEBAR_WIDTH_PX = 220;
    private static final long TAB_TOGGLE_DEBOUNCE_MS = 150;

    private static final String PREFS_ROOT = "io.github.jbellis.brokk";
    private static final String PREFS_PROJECTS = "projects";
    private static final String PREF_KEY_SIDEBAR_OPEN = "sidebarOpen";
    private static final String PREF_KEY_SIDEBAR_OPEN_GLOBAL = "sidebarOpenGlobal";

    private final Chrome chrome;
    private final JTabbedPane toolsPane;
    private final ProjectFilesPanel projectFilesPanel;
    private final TestRunnerPanel testRunnerPanel;

    @Nullable
    private GitCommitTab gitCommitTab;

    @Nullable
    private GitLogTab gitLogTab;

    @Nullable
    private GitWorktreeTab gitWorktreeTab;

    @Nullable
    private GitPullRequestsTab pullRequestsPanel;

    @Nullable
    private GitIssuesTab issuesPanel;

    @Nullable
    private BadgedIcon gitTabBadgedIcon;

    @Nullable
    private JLabel gitTabLabel;

    @Nullable
    private BadgedIcon projectFilesTabBadgedIcon;

    @Nullable
    private JLabel projectFilesTabLabel;

    private boolean sidebarCollapsed = false;
    private int lastExpandedSidebarLocation = -1;
    private long lastTabToggleTime = 0;

    public ToolsPane(
            Chrome chrome,
            ContextManager contextManager,
            ProjectFilesPanel projectFilesPanel,
            TestRunnerPanel testRunnerPanel) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.projectFilesPanel = projectFilesPanel;
        this.testRunnerPanel = testRunnerPanel;

        toolsPane = new JTabbedPane(JTabbedPane.LEFT);
        toolsPane.setMinimumSize(new Dimension(MIN_SIDEBAR_WIDTH_PX, 0));
        toolsPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        setupTabs(contextManager);
        add(toolsPane, BorderLayout.CENTER);
    }

    private void setupTabs(ContextManager contextManager) {
        // Project Files
        projectFilesTabBadgedIcon = new BadgedIcon(Icons.FOLDER_CODE, chrome.getTheme());
        toolsPane.addTab(null, projectFilesTabBadgedIcon, projectFilesPanel);
        int projectTabIdx = toolsPane.indexOfComponent(projectFilesPanel);
        String projectShortcut =
                KeyboardShortcutUtil.formatKeyStroke(KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_1));
        projectFilesTabLabel =
                createSquareTabLabel(projectFilesTabBadgedIcon, "Project Files (" + projectShortcut + ")");
        toolsPane.setTabComponentAt(projectTabIdx, projectFilesTabLabel);
        projectFilesTabLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleTabToggle(projectTabIdx);
            }
        });

        // Tests
        toolsPane.addTab(null, Icons.SCIENCE, testRunnerPanel);
        int testsTabIdx = toolsPane.indexOfComponent(testRunnerPanel);
        String testsShortcut =
                KeyboardShortcutUtil.formatKeyStroke(KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_2));
        JLabel testsTabLabel = createSquareTabLabel(Icons.SCIENCE, "Tests (" + testsShortcut + ")");
        toolsPane.setTabComponentAt(testsTabIdx, testsTabLabel);
        testsTabLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleTabToggle(testsTabIdx);
            }
        });

        if (chrome.getProject().hasGit()) {
            gitCommitTab = new GitCommitTab(chrome, contextManager);
            gitLogTab = new GitLogTab(chrome, contextManager);
            gitWorktreeTab = new GitWorktreeTab(chrome, contextManager);
        }

        if (chrome.getProject().isGitHubRepo() && gitLogTab != null) {
            pullRequestsPanel = new GitPullRequestsTab(chrome, contextManager, gitLogTab);
        }

        if (chrome.getProject().getIssuesProvider().type() != IssueProviderType.NONE) {
            issuesPanel = new GitIssuesTab(chrome, contextManager);
        }

        updateProjectFilesTabBadge(chrome.getProject().getLiveDependencies().size());
    }

    private void handleTabToggle(int tabIndex) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTabToggleTime < TAB_TOGGLE_DEBOUNCE_MS) return;
        lastTabToggleTime = currentTime;

        JSplitPane horizontalSplit = chrome.getHorizontalSplitPane();
        if (!sidebarCollapsed && toolsPane.getSelectedIndex() == tabIndex) {
            int currentLocation = horizontalSplit.getDividerLocation();
            if (currentLocation >= SIDEBAR_COLLAPSED_THRESHOLD) {
                lastExpandedSidebarLocation = currentLocation;
            }
            chrome.getLeftVerticalSplitPane().setMinimumSize(new Dimension(0, 0));
            toolsPane.setMinimumSize(new Dimension(0, 0));
            toolsPane.setSelectedIndex(0);
            horizontalSplit.setDividerSize(0);
            sidebarCollapsed = true;
            horizontalSplit.setDividerLocation(40);
            saveSidebarOpenSetting(false);
        } else {
            toolsPane.setSelectedIndex(tabIndex);
            if (sidebarCollapsed) {
                horizontalSplit.setDividerSize(chrome.getOriginalBottomDividerSize());
                int target = (lastExpandedSidebarLocation > 0)
                        ? lastExpandedSidebarLocation
                        : chrome.computeInitialSidebarWidth() + horizontalSplit.getDividerSize();
                horizontalSplit.setDividerLocation(target);
                sidebarCollapsed = false;
                int minPx = chrome.computeMinSidebarWidthPx();
                chrome.getLeftVerticalSplitPane().setMinimumSize(new Dimension(minPx, 0));
                toolsPane.setMinimumSize(new Dimension(minPx, 0));
                saveSidebarOpenSetting(true);
            }
            if (toolsPane.getComponentAt(tabIndex) == projectFilesPanel) {
                updateProjectFilesTabBadge(
                        chrome.getProject().getLiveDependencies().size());
            }
        }
    }

    public void applyAdvancedModeVisibility() {
        boolean advanced = GlobalUiSettings.isAdvancedMode();
        if (!advanced) {
            removeAdvancedTabs();
        } else {
            readdAdvancedTabs();
        }
        toolsPane.revalidate();
        toolsPane.repaint();
    }

    private void removeAdvancedTabs() {
        if (gitCommitTab != null) removeTab(gitCommitTab);
        if (gitLogTab != null) removeTab(gitLogTab);
        if (gitWorktreeTab != null) removeTab(gitWorktreeTab);
        if (pullRequestsPanel != null) removeTab(pullRequestsPanel);
        if (issuesPanel != null) removeTab(issuesPanel);
        if (toolsPane.getTabCount() > 0) {
            int sel = toolsPane.getSelectedIndex();
            if (sel < 0 || sel >= toolsPane.getTabCount()) toolsPane.setSelectedIndex(0);
        }
    }

    private void removeTab(Component comp) {
        int idx = toolsPane.indexOfComponent(comp);
        if (idx != -1) toolsPane.removeTabAt(idx);
    }

    private void readdAdvancedTabs() {
        if (gitCommitTab != null && toolsPane.indexOfComponent(gitCommitTab) == -1) {
            gitTabBadgedIcon = new BadgedIcon(Icons.COMMIT, chrome.getTheme());
            toolsPane.addTab(null, gitTabBadgedIcon, gitCommitTab);
            int idx = toolsPane.indexOfComponent(gitCommitTab);
            KeyStroke ks = GlobalUiSettings.getKeybinding(
                    "panel.switchToChanges", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_3));
            gitTabLabel = createSquareTabLabel(
                    gitTabBadgedIcon, "Changes (" + KeyboardShortcutUtil.formatKeyStroke(ks) + ")");
            toolsPane.setTabComponentAt(idx, gitTabLabel);
            gitTabLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(idx);
                }
            });
        }
        if (gitLogTab != null && toolsPane.indexOfComponent(gitLogTab) == -1) {
            toolsPane.addTab(null, Icons.FLOWSHEET, gitLogTab);
            int idx = toolsPane.indexOfComponent(gitLogTab);
            KeyStroke ks = GlobalUiSettings.getKeybinding(
                    "panel.switchToLog", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_4));
            JLabel label =
                    createSquareTabLabel(Icons.FLOWSHEET, "Log (" + KeyboardShortcutUtil.formatKeyStroke(ks) + ")");
            toolsPane.setTabComponentAt(idx, label);
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(idx);
                }
            });
        }
        if (gitWorktreeTab != null && toolsPane.indexOfComponent(gitWorktreeTab) == -1) {
            toolsPane.addTab(null, Icons.FLOWCHART, gitWorktreeTab);
            int idx = toolsPane.indexOfComponent(gitWorktreeTab);
            KeyStroke ks = GlobalUiSettings.getKeybinding(
                    "panel.switchToWorktrees", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_5));
            JLabel label = createSquareTabLabel(
                    Icons.FLOWCHART, "Worktrees (" + KeyboardShortcutUtil.formatKeyStroke(ks) + ")");
            toolsPane.setTabComponentAt(idx, label);
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(idx);
                }
            });
        }
        if (pullRequestsPanel != null
                && chrome.getProject().isGitHubRepo()
                && gitLogTab != null
                && toolsPane.indexOfComponent(pullRequestsPanel) == -1) {
            toolsPane.addTab(null, Icons.PULL_REQUEST, pullRequestsPanel);
            int idx = toolsPane.indexOfComponent(pullRequestsPanel);
            KeyStroke ks = GlobalUiSettings.getKeybinding(
                    "panel.switchToPullRequests", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_6));
            JLabel label = createSquareTabLabel(
                    Icons.PULL_REQUEST, "Pull Requests (" + KeyboardShortcutUtil.formatKeyStroke(ks) + ")");
            toolsPane.setTabComponentAt(idx, label);
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(idx);
                }
            });
        }
        if (issuesPanel != null
                && chrome.getProject().getIssuesProvider().type() != IssueProviderType.NONE
                && toolsPane.indexOfComponent(issuesPanel) == -1) {
            toolsPane.addTab(null, Icons.ADJUST, issuesPanel);
            int idx = toolsPane.indexOfComponent(issuesPanel);
            KeyStroke ks = GlobalUiSettings.getKeybinding(
                    "panel.switchToIssues", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_7));
            JLabel label =
                    createSquareTabLabel(Icons.ADJUST, "Issues (" + KeyboardShortcutUtil.formatKeyStroke(ks) + ")");
            toolsPane.setTabComponentAt(idx, label);
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(idx);
                }
            });
        }
    }

    public void updateGitTabBadge(int count) {
        if (gitTabBadgedIcon != null) {
            gitTabBadgedIcon.setCount(count, toolsPane);
            if (gitTabLabel != null) {
                KeyStroke ks = GlobalUiSettings.getKeybinding(
                        "panel.switchToChanges", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_3));
                String shortcut = KeyboardShortcutUtil.formatKeyStroke(ks);
                gitTabLabel.setToolTipText(
                        count > 0
                                ? String.format(
                                        "Changes (%d modified file%s) (%s)", count, count == 1 ? "" : "s", shortcut)
                                : "Changes (" + shortcut + ")");
                gitTabLabel.repaint();
            }
        }
    }

    public void updateProjectFilesTabBadge(int count) {
        if (projectFilesTabBadgedIcon != null) {
            projectFilesTabBadgedIcon.setCount(count, toolsPane);
            if (projectFilesTabLabel != null) {
                KeyStroke ks = GlobalUiSettings.getKeybinding(
                        "panel.switchToProjectFiles", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_1));
                String shortcut = KeyboardShortcutUtil.formatKeyStroke(ks);
                projectFilesTabLabel.setToolTipText(
                        count > 0
                                ? String.format(
                                        "Project Files (%d dependenc%s) (%s)",
                                        count, count == 1 ? "y" : "ies", shortcut)
                                : "Project Files (" + shortcut + ")");
                projectFilesTabLabel.repaint();
            }
            projectFilesPanel.updateBorderTitle();
        }
    }

    private JLabel createSquareTabLabel(Icon icon, String tooltip) {
        JLabel label = new JLabel(icon);
        int size = Math.max(icon.getIconWidth(), icon.getIconHeight());
        label.setPreferredSize(new Dimension(size, size + 8));
        label.setMinimumSize(label.getPreferredSize());
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setToolTipText(tooltip);
        if (icon instanceof SwingUtil.ThemedIcon themedIcon) {
            try {
                themedIcon.ensureResolved();
            } catch (Exception ex) {
                logger.debug("Failed to resolve themed icon", ex);
            }
        }
        label.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && label.isShowing()) {
                SwingUtilities.invokeLater(() -> {
                    label.revalidate();
                    label.repaint();
                });
            }
        });
        return label;
    }

    public void recreateIssuesPanel(ContextManager cm) {
        removeTab(issuesPanel);
        issuesPanel = new GitIssuesTab(chrome, cm);
        readdAdvancedTabs();
        int idx = toolsPane.indexOfComponent(issuesPanel);
        if (idx != -1) toolsPane.setSelectedIndex(idx);
    }

    private void saveSidebarOpenSetting(boolean open) {
        try {
            Preferences p = projectPrefsNode();
            p.putBoolean(PREF_KEY_SIDEBAR_OPEN, open);
            p.flush();
        } catch (Exception ignored) {
        }
        try {
            Preferences g = prefsRoot();
            g.putBoolean(PREF_KEY_SIDEBAR_OPEN_GLOBAL, open);
            g.flush();
        } catch (Exception ignored) {
        }
    }

    public @Nullable Boolean getSavedSidebarOpenPreference() {
        try {
            Preferences p = projectPrefsNode();
            if (p.get(PREF_KEY_SIDEBAR_OPEN, null) != null) return p.getBoolean(PREF_KEY_SIDEBAR_OPEN, true);
        } catch (Exception e) {
            logger.error("Failed to read project sidebar open preference", e);
        }
        try {
            Preferences g = prefsRoot();
            if (g.get(PREF_KEY_SIDEBAR_OPEN_GLOBAL, null) != null)
                return g.getBoolean(PREF_KEY_SIDEBAR_OPEN_GLOBAL, true);
        } catch (Exception e) {
            logger.error("Failed to read global sidebar open preference", e);
        }
        return null;
    }

    public Preferences prefsRoot() {
        return Preferences.userRoot().node(PREFS_ROOT);
    }

    public Preferences projectPrefsNode() {
        String projKey = chrome.getProject()
                .getRoot()
                .toString()
                .replace('/', '_')
                .replace('\\', '_')
                .replace(':', '_');
        return prefsRoot().node(PREFS_PROJECTS).node(projKey);
    }

    public void setSidebarCollapsed(boolean collapsed) {
        this.sidebarCollapsed = collapsed;
    }

    public boolean isSidebarCollapsed() {
        return sidebarCollapsed;
    }

    public void setLastExpandedSidebarLocation(int loc) {
        this.lastExpandedSidebarLocation = loc;
    }

    public int getLastExpandedSidebarLocation() {
        return lastExpandedSidebarLocation;
    }

    public JTabbedPane getToolsPane() {
        return toolsPane;
    }

    public @Nullable GitCommitTab getGitCommitTab() {
        return gitCommitTab;
    }

    public @Nullable GitLogTab getGitLogTab() {
        return gitLogTab;
    }

    public @Nullable GitWorktreeTab getGitWorktreeTab() {
        return gitWorktreeTab;
    }

    public @Nullable GitPullRequestsTab getPullRequestsPanel() {
        return pullRequestsPanel;
    }

    public @Nullable GitIssuesTab getIssuesPanel() {
        return issuesPanel;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.updateComponentTreeUI(this);
    }
}
