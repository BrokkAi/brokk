package io.github.jbellis.brokk.gui;

import static io.github.jbellis.brokk.gui.Constants.*;
import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import com.formdev.flatlaf.util.SystemInfo;
import com.formdev.flatlaf.util.UIScale;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.agents.BlitzForge;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.FrozenFragment;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.dependencies.DependenciesDrawerPanel;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import io.github.jbellis.brokk.gui.dialogs.BlitzForgeProgressDialog;
import io.github.jbellis.brokk.gui.dialogs.PreviewImagePanel;
import io.github.jbellis.brokk.gui.dialogs.PreviewTextPanel;
import io.github.jbellis.brokk.gui.git.*;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPool;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.search.GenericSearchBar;
import io.github.jbellis.brokk.gui.search.MarkdownSearchableComponent;
import io.github.jbellis.brokk.gui.terminal.TerminalDrawerPanel;
import io.github.jbellis.brokk.gui.util.BadgedIcon;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.issues.IssueProviderType;
import io.github.jbellis.brokk.util.CloneOperationTracker;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.GlobalUiSettings;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

public class Chrome implements AutoCloseable, IConsoleIO, IContextManager.ContextListener {
    private static final Logger logger = LogManager.getLogger(Chrome.class);

    // Track open Chrome instances for window cascading
    private static final Set<Chrome> openInstances = ConcurrentHashMap.newKeySet();

    // Default layout proportions - can be overridden by saved preferences
    private static final double DEFAULT_WORKSPACE_INSTRUCTIONS_SPLIT = 0.583; // 58.3% workspace, 41.7% instructions
    private static final double DEFAULT_OUTPUT_MAIN_SPLIT = 0.4; // 40% output, 60% main content
    private static final double MIN_SIDEBAR_WIDTH_FRACTION = 0.10; // 10% minimum sidebar width
    private static final double MAX_SIDEBAR_WIDTH_FRACTION = 0.40; // 40% maximum sidebar width (normal screens)
    private static final double MAX_SIDEBAR_WIDTH_FRACTION_WIDE = 0.25; // 25% maximum sidebar width (wide screens)
    private static final int WIDE_SCREEN_THRESHOLD = 2000; // Screen width threshold for wide screen layout
    private static final int SIDEBAR_COLLAPSED_THRESHOLD = 50;

    // Used as the default text for the background tasks label
    private final String BGTASK_EMPTY = "No background tasks";

    // is a setContext updating the MOP?
    private boolean skipNextUpdateOutputPanelOnContextChange = false;

    // Track active preview windows for reuse
    private final Map<String, JFrame> activePreviewWindows = new ConcurrentHashMap<>();
    private @Nullable Rectangle dependenciesDialogBounds = null;

    /**
     * Gets whether updates to the output panel are skipped on context changes.
     *
     * @return true if updates are skipped, false otherwise
     */
    public boolean isSkipNextUpdateOutputPanelOnContextChange() {
        return skipNextUpdateOutputPanelOnContextChange;
    }

    /**
     * Sets whether updates to the output panel should be skipped on context changes.
     *
     * @param skipNextUpdateOutputPanelOnContextChangeFoo true to skip updates, false otherwise
     */
    public void setSkipNextUpdateOutputPanelOnContextChange(boolean skipNextUpdateOutputPanelOnContextChangeFoo) {
        this.skipNextUpdateOutputPanelOnContextChange = skipNextUpdateOutputPanelOnContextChangeFoo;
    }

    // Dependencies:
    final ContextManager contextManager;
    private Context activeContext; // Track the currently displayed context

    // Global Undo/Redo Actions
    private final GlobalUndoAction globalUndoAction;
    private final GlobalRedoAction globalRedoAction;
    // Global Copy/Paste Actions
    private final GlobalCopyAction globalCopyAction;
    private final GlobalPasteAction globalPasteAction;
    // Global Toggle Mic Action
    private final ToggleMicAction globalToggleMicAction;
    // necessary for undo/redo because clicking on menubar takes focus from whatever had it
    @Nullable
    private Component lastRelevantFocusOwner = null;

    // Debouncing for tab toggle to prevent duplicate events
    private long lastTabToggleTime = 0;
    private static final long TAB_TOGGLE_DEBOUNCE_MS = 150;

    /**
     * Handles tab toggle behavior - minimizes panel if tab is already selected, otherwise selects the tab and restores
     * panel if it was minimized.
     */
    private void handleTabToggle(int tabIndex) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTabToggleTime < TAB_TOGGLE_DEBOUNCE_MS) {
            return; // Ignore rapid successive clicks
        }
        lastTabToggleTime = currentTime;

        if (!sidebarCollapsed && leftTabbedPanel.getSelectedIndex() == tabIndex) {
            // Tab already selected: capture current expanded width (if not already minimized), then minimize
            int currentLocation = bottomSplitPane.getDividerLocation();
            if (currentLocation >= SIDEBAR_COLLAPSED_THRESHOLD) {
                lastExpandedSidebarLocation = currentLocation;
            }
            leftTabbedPanel.setSelectedIndex(0); // Always show Project Files when collapsed
            bottomSplitPane.setDividerSize(0);
            bottomSplitPane.setDividerLocation(40);
            sidebarCollapsed = true;
        } else {
            leftTabbedPanel.setSelectedIndex(tabIndex);
            // Restore panel if it was minimized
            if (sidebarCollapsed) {
                bottomSplitPane.setDividerSize(originalBottomDividerSize);
                int target = (lastExpandedSidebarLocation > 0)
                        ? lastExpandedSidebarLocation
                        : computeInitialSidebarWidth() + bottomSplitPane.getDividerSize();
                bottomSplitPane.setDividerLocation(target);
                sidebarCollapsed = false;
            }
        }
    }

    // Store original divider size for hiding/showing divider
    private int originalBottomDividerSize;

    // Remember the last non-minimized divider location of the left sidebar
    // Used to restore the previous width when re-expanding after a minimize
    private int lastExpandedSidebarLocation = -1;
    private boolean sidebarCollapsed = false;

    // Workspace collapse state (toggled by clicking token/cost label)
    private boolean workspaceCollapsed = false;

    // Pin exact Instructions height (in px) during collapse so only Output resizes
    private int pinnedInstructionsHeightPx = -1;

    // Save the Workspace↔Instructions divider as a proportion (0..1) to restore Workspace height precisely
    private double savedTopSplitProportion = -1.0;

    // Guard to prevent recursion when clamping the Output↔Bottom divider
    private boolean adjustingMainDivider = false;

    // Swing components:
    final JFrame frame;
    private JLabel backgroundStatusLabel;
    private final JPanel bottomPanel;

    private final JSplitPane topSplitPane; // Instructions | Workspace
    private final JSplitPane mainVerticalSplitPane; // (Instructions+Workspace) | Tabbed bottom

    private final JTabbedPane leftTabbedPanel; // ProjectFiles, Git tabs
    private final JSplitPane leftVerticalSplitPane; // Left: tabs (top) + file history (bottom)
    private final JTabbedPane historyTabbedPane; // Bottom area for file history
    private int originalLeftVerticalDividerSize;
    private final HistoryOutputPanel historyOutputPanel;
    /** Horizontal split between left tab stack and right output stack */
    private JSplitPane bottomSplitPane;

    @SuppressWarnings("NullAway.Init") // Initialized in constructor
    private JPanel workspaceTopContainer;

    // Panels:
    private final WorkspacePanel workspacePanel;
    private final ProjectFilesPanel projectFilesPanel; // New panel for project files
    private final DependenciesPanel dependenciesPanel;

    // Git
    @Nullable
    private final GitCommitTab gitCommitTab;

    @Nullable
    private final GitLogTab gitLogTab;

    @Nullable
    private final GitWorktreeTab gitWorktreeTab;

    // For GitHistoryTab instances opened as top-level tabs
    private final Map<String, GitHistoryTab> fileHistoryTabs = new HashMap<>();

    @Nullable
    private GitPullRequestsTab pullRequestsPanel;

    @Nullable
    private GitIssuesTab issuesPanel;

    // Git tab badge components
    @Nullable
    private JLabel gitTabLabel;

    @Nullable
    private BadgedIcon gitTabBadgedIcon;

    // Caches the last branch string we applied to InstructionsPanel to avoid redundant UI refreshes
    @Nullable
    private String lastDisplayedBranchLabel = null;

    // Reference to Tools ▸ BlitzForge… menu item so we can enable/disable it
    @SuppressWarnings("NullAway.Init") // Initialized by MenuBar after constructor
    private JMenuItem blitzForgeMenuItem;

    // Command input panel is now encapsulated in InstructionsPanel.
    private final InstructionsPanel instructionsPanel;

    // Right-hand drawer (tools) - split and content
    private DrawerSplitPanel instructionsDrawerSplit;
    private TerminalDrawerPanel terminalDrawer;

    /** Default constructor sets up the UI. */
    @SuppressWarnings("NullAway.Init") // For complex Swing initialization patterns
    public Chrome(ContextManager contextManager) {
        assert SwingUtilities.isEventDispatchThread() : "Chrome constructor must run on EDT";
        this.contextManager = contextManager;
        this.activeContext = Context.EMPTY; // Initialize activeContext

        // 2) Build main window
        frame = newFrame("Brokk: Code Intelligence for AI", false);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        // Install centralized application-level QuitHandler so Cmd+Q and platform quit can be intercepted
        AppQuitHandler.install();
        frame.setSize(800, 1200); // Taller than wide
        frame.setLayout(new BorderLayout());

        // 3) Main panel (top area + bottom area)
        var mainPanel = new JPanel(new BorderLayout());

        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 2, 2, 2);

        // Create instructions panel and history/output panel
        instructionsPanel = new InstructionsPanel(this);
        historyOutputPanel = new HistoryOutputPanel(this, this.contextManager);

        // Bottom Area: Context/Git + Status
        bottomPanel = new JPanel(new BorderLayout());
        // Status labels at the very bottom
        // System message label (left side)

        // Background status label (right side)
        backgroundStatusLabel = new JLabel(BGTASK_EMPTY);
        backgroundStatusLabel.setBorder(new EmptyBorder(V_GLUE, H_GAP, V_GLUE, H_PAD));

        // Panel to hold both labels
        var statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(backgroundStatusLabel, BorderLayout.EAST);

        var statusLabels = (JComponent) statusPanel;
        bottomPanel.add(statusLabels, BorderLayout.SOUTH);
        // Center of bottomPanel will be filled in onComplete based on git presence

        gbc.weighty = 1.0;
        gbc.gridy = 0;
        contentPanel.add(bottomPanel, gbc);

        mainPanel.add(contentPanel, BorderLayout.CENTER);
        frame.add(mainPanel, BorderLayout.CENTER); // instructionsPanel is created here

        // Initialize global undo/redo actions now that instructionsPanel is available
        // contextManager is also available (passed in constructor)
        // contextPanel and historyOutputPanel will be null until onComplete
        this.globalUndoAction = new GlobalUndoAction("Undo");
        this.globalRedoAction = new GlobalRedoAction("Redo");
        this.globalCopyAction = new GlobalCopyAction("Copy");
        this.globalPasteAction = new GlobalPasteAction("Paste");
        this.globalToggleMicAction = new ToggleMicAction("Toggle Microphone");

        initializeThemeManager();
        // Defer restoring window size and divider positions until after
        // all split panes are fully constructed.
        frame.setTitle("Brokk: " + getProject().getRoot());

        // Show initial system message
        showNotification(
                NotificationRole.INFO, "Opening project at " + getProject().getRoot());

        // Create workspace panel and project files panel
        workspacePanel = new WorkspacePanel(this, contextManager);
        projectFilesPanel = new ProjectFilesPanel(this, contextManager);
        dependenciesPanel = new DependenciesPanel(this);

        // Create left vertical-tabbed pane for ProjectFiles and Git with vertical tab placement
        leftTabbedPanel = new JTabbedPane(JTabbedPane.LEFT);
        // Allow the divider to move further left by reducing the minimum width
        leftTabbedPanel.setMinimumSize(new Dimension(120, 0));
        var projectIcon = Icons.FOLDER_CODE;
        leftTabbedPanel.addTab(null, projectIcon, projectFilesPanel);
        var projectTabIdx = leftTabbedPanel.indexOfComponent(projectFilesPanel);
        var projectTabLabel = createSquareTabLabel(projectIcon, "Project Files");
        leftTabbedPanel.setTabComponentAt(projectTabIdx, projectTabLabel);
        projectTabLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleTabToggle(projectTabIdx);
            }
        });

        // Add Dependencies tab
        var dependenciesIcon = Icons.MANAGE_DEPENDENCIES;
        leftTabbedPanel.addTab(null, dependenciesIcon, dependenciesPanel);
        var dependenciesTabIdx = leftTabbedPanel.indexOfComponent(dependenciesPanel);
        var dependenciesTabLabel = createSquareTabLabel(dependenciesIcon, "Dependencies");
        leftTabbedPanel.setTabComponentAt(dependenciesTabIdx, dependenciesTabLabel);
        dependenciesTabLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleTabToggle(dependenciesTabIdx);
            }
        });

        // Add Git tabs (Changes, Worktrees, Log) if available
        if (getProject().hasGit()) {
            gitCommitTab = new GitCommitTab(this, contextManager);
            gitWorktreeTab = new GitWorktreeTab(this, contextManager);
            gitLogTab = new GitLogTab(this, contextManager);

            // Changes tab (with badge)
            var commitIcon = Icons.COMMIT;
            gitTabBadgedIcon = new BadgedIcon(commitIcon, themeManager);
            leftTabbedPanel.addTab(null, gitTabBadgedIcon, gitCommitTab);
            var commitTabIdx = leftTabbedPanel.indexOfComponent(gitCommitTab);
            gitTabLabel = createSquareTabLabel(gitTabBadgedIcon, "Changes");
            leftTabbedPanel.setTabComponentAt(commitTabIdx, gitTabLabel);
            gitTabLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(commitTabIdx);
                }
            });

            // Worktrees tab
            var worktreeIcon = Icons.FLOWCHART;
            leftTabbedPanel.addTab(null, worktreeIcon, gitWorktreeTab);
            var worktreeTabIdx = leftTabbedPanel.indexOfComponent(gitWorktreeTab);
            var worktreeTabLabel = createSquareTabLabel(worktreeIcon, "Worktrees");
            leftTabbedPanel.setTabComponentAt(worktreeTabIdx, worktreeTabLabel);
            worktreeTabLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(worktreeTabIdx);
                }
            });

            // Log tab
            var logIcon = Icons.FLOWSHEET;
            leftTabbedPanel.addTab(null, logIcon, gitLogTab);
            var logTabIdx = leftTabbedPanel.indexOfComponent(gitLogTab);
            var logTabLabel = createSquareTabLabel(logIcon, "Log");
            leftTabbedPanel.setTabComponentAt(logTabIdx, logTabLabel);
            logTabLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(logTabIdx);
                }
            });

            // Initial refreshes
            updateGitRepo();
            projectFilesPanel.updatePanel();
        } else {
            gitCommitTab = null;
            gitLogTab = null;
            gitWorktreeTab = null;
        }

        // --- New top-level Pull-Requests panel ---------------------------------
        if (getProject().isGitHubRepo() && gitLogTab != null) {
            pullRequestsPanel = new GitPullRequestsTab(this, contextManager, gitLogTab);
            var prIcon = Icons.PULL_REQUEST;
            leftTabbedPanel.addTab(null, prIcon, pullRequestsPanel);
            var prIdx = leftTabbedPanel.indexOfComponent(pullRequestsPanel);
            var prLabel = createSquareTabLabel(prIcon, "Pull Requests");
            leftTabbedPanel.setTabComponentAt(prIdx, prLabel);
            prLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(prIdx);
                }
            });
        }

        // --- New top-level Issues panel ----------------------------------------
        if (getProject().getIssuesProvider().type() != IssueProviderType.NONE) {
            issuesPanel = new GitIssuesTab(this, contextManager);
            var issIcon = Icons.ADJUST;
            leftTabbedPanel.addTab(null, issIcon, issuesPanel);
            var issIdx = leftTabbedPanel.indexOfComponent(issuesPanel);
            var issLabel = createSquareTabLabel(issIcon, "Issues");
            leftTabbedPanel.setTabComponentAt(issIdx, issLabel);
            issLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleTabToggle(issIdx);
                }
            });
        }

        /*
         * Desired layout (left→right, top→bottom):
         * ┌────────────────────────────┬──────────────────────────────┐
         * │ Vert-tabbed (Project/Git)  │  Output (top)               │
         * │                            │  Workspace (middle)         │
         * │                            │  Instructions (bottom)      │
         * └────────────────────────────┴──────────────────────────────┘
         */

        // 1) Nested split for Workspace (top) / Instructions (bottom)
        JSplitPane workspaceInstructionsSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

        workspaceTopContainer = new JPanel(new BorderLayout());
        workspaceTopContainer.add(workspacePanel, BorderLayout.CENTER);

        // Create terminal drawer panel
        instructionsDrawerSplit = new DrawerSplitPanel();
        // Ensure bottom area doesn't get squeezed to near-zero height on first layout after swaps
        // This is the minimum height for the Instructions+Drawer when the workspace is hidden.
        instructionsDrawerSplit.setMinimumSize(new Dimension(200, 325));
        terminalDrawer = new TerminalDrawerPanel(this, instructionsDrawerSplit);

        // Attach instructions (left) and drawer (right)
        instructionsDrawerSplit.setParentComponent(instructionsPanel);
        instructionsDrawerSplit.setDrawerComponent(terminalDrawer);

        // Attach the combined instructions+drawer split as the bottom component
        workspaceInstructionsSplit.setTopComponent(workspaceTopContainer);
        workspaceInstructionsSplit.setBottomComponent(instructionsDrawerSplit);
        workspaceInstructionsSplit.setResizeWeight(0.583); // ~35 % Workspace / 25 % Instructions
        // Ensure the bottom area of the Output↔Bottom split (when workspace is visible) never collapses
        workspaceInstructionsSplit.setMinimumSize(new Dimension(200, 325));

        // Keep reference so existing persistence logic still works
        topSplitPane = workspaceInstructionsSplit;

        // 2) Split for Output (top) / (Workspace+Instructions) (bottom)
        JSplitPane outputStackSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        outputStackSplit.setTopComponent(historyOutputPanel);
        outputStackSplit.setBottomComponent(workspaceInstructionsSplit);
        outputStackSplit.setResizeWeight(0.4); // ~40 % to Output

        // Keep reference so existing persistence logic still works
        mainVerticalSplitPane = outputStackSplit;

        // 3) Final horizontal split: left tabs | right stack
        bottomSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        // Create a vertical split on the left: top = regular tabs, bottom = per-file history tabs
        historyTabbedPane = new JTabbedPane();
        historyTabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT); // keep single row; scroll horizontally
        historyTabbedPane.setVisible(false); // hidden until a history tab is added

        leftVerticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        leftVerticalSplitPane.setTopComponent(leftTabbedPanel);
        leftVerticalSplitPane.setBottomComponent(historyTabbedPane);
        leftVerticalSplitPane.setResizeWeight(0.7); // top gets most space by default
        originalLeftVerticalDividerSize = leftVerticalSplitPane.getDividerSize();
        leftVerticalSplitPane.setDividerSize(0); // hide divider when no history is shown

        bottomSplitPane.setLeftComponent(leftVerticalSplitPane);
        bottomSplitPane.setRightComponent(outputStackSplit);
        // Ensure the right stack can shrink enough so the sidebar can grow
        outputStackSplit.setMinimumSize(new Dimension(200, 0));
        // Left panel keeps its preferred width; right panel takes the remaining space
        bottomSplitPane.setResizeWeight(0.0);
        int tempDividerLocation = 300; // Reasonable default that will be recalculated
        bottomSplitPane.setDividerLocation(tempDividerLocation);
        // Initialize the remembered expanded location (will be updated later)
        lastExpandedSidebarLocation = tempDividerLocation;

        // Store original divider size
        originalBottomDividerSize = bottomSplitPane.getDividerSize();

        bottomPanel.add(bottomSplitPane, BorderLayout.CENTER);

        // Force layout update for the bottom panel
        bottomPanel.revalidate();
        bottomPanel.repaint();

        // Set initial enabled state for global actions after all components are ready
        this.globalUndoAction.updateEnabledState();
        this.globalRedoAction.updateEnabledState();
        this.globalCopyAction.updateEnabledState();
        this.globalPasteAction.updateEnabledState();

        // Listen for focus changes to update action states and track relevant focus
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", evt -> {
            Component newFocusOwner = (Component) evt.getNewValue();
            // Update lastRelevantFocusOwner only if the new focus owner is one of our primary targets
            if (newFocusOwner != null) {
                historyOutputPanel.getLlmStreamArea();
                if (historyOutputPanel.getHistoryTable() != null) {
                    if (newFocusOwner == instructionsPanel.getInstructionsArea()
                            || SwingUtilities.isDescendingFrom(newFocusOwner, workspacePanel)
                            || SwingUtilities.isDescendingFrom(newFocusOwner, historyOutputPanel.getHistoryTable())
                            || SwingUtilities.isDescendingFrom(
                                    newFocusOwner, historyOutputPanel.getLlmStreamArea())) // Check for LLM area
                    {
                        this.lastRelevantFocusOwner = newFocusOwner;
                    }
                    // else: lastRelevantFocusOwner remains unchanged if focus moves to a menu or irrelevant component
                }
            }

            globalUndoAction.updateEnabledState();
            globalRedoAction.updateEnabledState();
            globalCopyAction.updateEnabledState();
            globalPasteAction.updateEnabledState();
            globalToggleMicAction.updateEnabledState();
        });

        // Listen for context changes (Chrome already implements IContextManager.ContextListener)
        contextManager.addContextListener(this);

        // Build menu (now that everything else is ready)
        frame.setJMenuBar(MenuBar.buildMenuBar(this));

        // Register global keyboard shortcuts now that actions are fully initialized
        registerGlobalKeyboardShortcuts();

        // Complete all layout operations synchronously before showing window
        completeLayoutSynchronously();

        // Final validation and repaint before making window visible
        frame.validate();
        frame.repaint();
        // Now show the window with complete layout
        frame.setVisible(true);

        // Possibly check if .gitignore is set
        if (getProject().hasGit()) {
            contextManager.submitBackgroundTask("Checking .gitignore", () -> {
                if (!getProject().isGitIgnoreSet()) {
                    SwingUtilities.invokeLater(() -> {
                        int result = showConfirmDialog(
                                "Update .gitignore and add .brokk project files to git?",
                                "Git Configuration",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE);
                        if (result == JOptionPane.YES_OPTION) {
                            setupGitIgnore();
                        }
                    });
                }
                return null;
            });
        }

        SwingUtilities.invokeLater(() -> MarkdownOutputPool.instance());

        // Clean up any orphaned clone operations from previous sessions
        if (getProject() instanceof MainProject) {
            Path dependenciesRoot =
                    getProject().getRoot().resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR);
            CloneOperationTracker.cleanupOrphanedClones(dependenciesRoot);
        }

        // Register this instance for window tracking
        openInstances.add(this);
    }

    /**
     * Sends a desktop notification if the main window is not active.
     *
     * @param notification Notification
     */
    public void notifyActionComplete(String notification) {
        SwingUtilities.invokeLater(() -> {
            // 'frame' is the JFrame member of Chrome
            if (frame.isShowing() && !frame.isActive()) {
                Environment.instance.sendNotificationAsync(notification);
            }
        });
    }

    public AbstractProject getProject() {
        return contextManager.getProject();
    }

    /** Sets up .gitignore entries and adds .brokk project files to git */
    private void setupGitIgnore() {
        // If project does not have git, nothing to do.
        if (!getProject().hasGit()) {
            logger.debug("setupGitIgnore called but project has no git repository; skipping.");
            return;
        }
        contextManager.submitBackgroundTask("Updating .gitignore", () -> {
            try {
                var project = getProject();
                var repo = project.getRepo();
                if (!(repo instanceof GitRepo gitRepo)) {
                    // Defensive: project claims to have git but repo isn't a GitRepo instance.
                    logger.warn(
                            "setupGitIgnore: project {} reports git but repo is not a GitRepo instance. Skipping.",
                            project.getRoot());
                    return;
                }
                var gitTopLevel = project.getMasterRootPathForConfig(); // Shared .gitignore lives at the true top level

                // Update .gitignore (located at gitTopLevel)
                var gitignorePath = gitTopLevel.resolve(".gitignore");
                String content = "";

                if (Files.exists(gitignorePath)) {
                    content = Files.readString(gitignorePath);
                    if (!content.endsWith("\n")) {
                        content += "\n";
                    }
                }

                // Add entries to .gitignore if they don't exist
                // These paths are relative to the .gitignore file (i.e., relative to gitTopLevel)
                if (!content.contains(".brokk/**") && !content.contains(".brokk/")) {
                    content += "\n### BROKK'S CONFIGURATION ###\n";
                    content += ".brokk/**\n"; // Ignore .brokk dir in sub-projects (worktrees)
                    content +=
                            "/.brokk/workspace.properties\n"; // Ignore workspace properties in main repo and worktrees
                    content += "/.brokk/sessions/\n"; // Ignore sessions dir in main repo
                    content += "/.brokk/dependencies/\n"; // Ignore dependencies dir in main repo
                    content += "/.brokk/history.zip\n"; // Ignore legacy history zip
                    content += "!.brokk/style.md\n"; // DO track style.md (which lives in masterRoot/.brokk)
                    content += "!.brokk/review.md\n"; // DO track review.md (which lives in masterRoot/.brokk)
                    content += "!.brokk/project.properties\n"; // DO track project.properties (masterRoot/.brokk)

                    Files.writeString(gitignorePath, content);
                    showNotification(NotificationRole.INFO, "Updated .gitignore with .brokk entries");

                    // Add .gitignore itself to git if it's not already in the index
                    // The path for 'add' should be relative to the git repo's CWD, or absolute.
                    // gitRepo.add() handles paths relative to its own root, or absolute paths.
                    // Here, gitignorePath is absolute.
                    gitRepo.add(gitignorePath);
                }

                // Create .brokk directory at gitTopLevel if it doesn't exist (for shared files)
                var sharedBrokkDir = gitTopLevel.resolve(".brokk");
                Files.createDirectories(sharedBrokkDir);

                // Add specific shared files to git
                var styleMdPath = sharedBrokkDir.resolve("style.md");
                var reviewMdPath = sharedBrokkDir.resolve("review.md");
                var projectPropsPath = sharedBrokkDir.resolve("project.properties");

                // Create shared files if they don't exist (empty files)
                if (!Files.exists(styleMdPath)) {
                    Files.writeString(styleMdPath, "# Style Guide\n");
                }
                if (!Files.exists(reviewMdPath)) {
                    Files.writeString(reviewMdPath, MainProject.DEFAULT_REVIEW_GUIDE);
                }
                if (!Files.exists(projectPropsPath)) {
                    Files.writeString(projectPropsPath, "# Brokk project configuration\n");
                }

                // Add shared files to git. ProjectFile needs the root relative to which the path is specified.
                // Here, paths are relative to gitTopLevel.
                var filesToAdd = new ArrayList<ProjectFile>();
                filesToAdd.add(new ProjectFile(gitTopLevel, ".brokk/style.md"));
                filesToAdd.add(new ProjectFile(gitTopLevel, ".brokk/review.md"));
                filesToAdd.add(new ProjectFile(gitTopLevel, ".brokk/project.properties"));

                // gitRepo.add takes ProjectFile instances, which resolve to absolute paths.
                // The GitRepo instance is for the current project (which could be a worktree),
                // but 'add' operations apply to the whole repository.
                gitRepo.add(filesToAdd);
                showNotification(
                        NotificationRole.INFO,
                        "Added shared .brokk project files (style.md, review.md, project.properties) to git");

                // Refresh the commit panel to show the new files
                updateCommitPanel();
            } catch (Exception e) {
                logger.error(e);
                toolError("Error setting up .gitignore: " + e.getMessage(), "Error");
            }
        });
    }

    private void initializeThemeManager() {

        logger.trace("Initializing theme manager");
        // JMHighlightPainter.initializePainters(); // Removed: Painters are now created dynamically with theme colors
        // Initialize theme manager now that all components are created
        // and contextManager should be properly set
        themeManager = new GuiTheme(frame, historyOutputPanel.getLlmScrollPane(), this);

        // Apply current theme and wrap mode based on global settings
        String currentTheme = MainProject.getTheme();
        logger.trace("Applying theme from project settings: {}", currentTheme);
        boolean isDark = GuiTheme.THEME_DARK.equalsIgnoreCase(currentTheme);
        boolean wrapMode = MainProject.getCodeBlockWrapMode();
        switchThemeAndWrapMode(isDark, wrapMode);
    }

    /**
     * Lightweight method to preview a context without updating history Only updates the LLM text area and context panel
     * display
     */
    public void setContext(Context ctx) {
        assert !ctx.containsDynamicFragments();

        logger.debug("Loading context.  active={}, new={}", activeContext, ctx);
        // If skipUpdateOutputPanelOnContextChange is true it is not updating the MOP => end of runSessions should not
        // scroll MOP away

        final boolean updateOutput = (!activeContext.equals(ctx) && !isSkipNextUpdateOutputPanelOnContextChange());
        setSkipNextUpdateOutputPanelOnContextChange(false);
        activeContext = ctx;

        SwingUtilities.invokeLater(() -> {
            workspacePanel.populateContextTable(ctx);
            // Determine if the current context (ctx) is the latest one in the history
            boolean isEditable;
            Context latestContext = contextManager.getContextHistory().topContext();
            isEditable = latestContext.equals(ctx);
            // workspacePanel is a final field initialized in the constructor, so it won't be null here.
            workspacePanel.setWorkspaceEditable(isEditable);
            if (updateOutput) {
                var taskHistory = ctx.getTaskHistory();
                if (taskHistory.isEmpty()) {
                    historyOutputPanel.clearLlmOutput();
                } else {
                    var historyTasks = taskHistory.subList(0, taskHistory.size() - 1);
                    var mainTask = taskHistory.getLast();
                    historyOutputPanel.setLlmAndHistoryOutput(historyTasks, mainTask);
                }
            }
            updateCaptureButtons();
        });
    }

    // Theme manager and constants
    GuiTheme themeManager;

    public void switchTheme(boolean isDark) {
        themeManager.applyTheme(isDark);
    }

    public void switchThemeAndWrapMode(boolean isDark, boolean wordWrap) {
        themeManager.applyTheme(isDark, wordWrap);
    }

    public GuiTheme getTheme() {
        return themeManager;
    }

    @Override
    public List<ChatMessage> getLlmRawMessages() {
        if (SwingUtilities.isEventDispatchThread()) {
            return historyOutputPanel.getLlmRawMessages();
        }

        // this can get interrupted at the end of a Code or Ask action, but we don't want to just throw
        // InterruptedException
        // because at this point we're basically done with the action and all that's left is reporting the result. So if
        // we're
        // unlucky enough to be interrupted at exactly the wrong time, we retry instead.
        while (true) {
            try {
                final CompletableFuture<List<ChatMessage>> future = new CompletableFuture<>();
                SwingUtilities.invokeAndWait(() -> future.complete(historyOutputPanel.getLlmRawMessages()));
                return future.get();
            } catch (InterruptedException e) {
                // retry
            } catch (ExecutionException | InvocationTargetException e) {
                logger.error(e);
                showNotification(NotificationRole.INFO, "Error retrieving LLM messages");
                return List.of();
            }
        }
    }

    /** Retrieves the current text from the command input. */
    public String getInputText() {
        return instructionsPanel.getInstructions();
    }

    @Override
    public void disableActionButtons() {
        SwingUtil.runOnEdt(() -> {
            disableHistoryPanel();
            instructionsPanel.disableButtons();
            terminalDrawer.disablePlay();
            if (gitCommitTab != null) {
                gitCommitTab.disableButtons();
            }
            blitzForgeMenuItem.setEnabled(false);
            blitzForgeMenuItem.setToolTipText("Waiting for current action to complete");
        });
    }

    @Override
    public void enableActionButtons() {
        SwingUtil.runOnEdt(() -> {
            instructionsPanel.enableButtons();
            terminalDrawer.enablePlay();
            if (gitCommitTab != null) {
                gitCommitTab.enableButtons();
            }
            blitzForgeMenuItem.setEnabled(true);
            blitzForgeMenuItem.setToolTipText(null);
        });
    }

    @Override
    public void updateCommitPanel() {
        if (gitCommitTab != null) {
            gitCommitTab.updateCommitPanel();
        }
    }

    @Override
    public void updateGitRepo() {
        logger.trace("updateGitRepo invoked");

        // Determine current branch (if available) and update InstructionsPanel on EDT
        String branchToDisplay = null;
        boolean hasGit = getProject().hasGit();
        try {
            if (hasGit) {
                var currentBranch = getProject().getRepo().getCurrentBranch();
                logger.trace("updateGitRepo: current branch='{}'", currentBranch);
                if (!currentBranch.isBlank()) {
                    branchToDisplay = currentBranch;
                }
            } else {
                logger.trace("updateGitRepo: project has no Git repository");
            }
        } catch (Exception e) {
            // Detached HEAD without resolvable HEAD or empty repo can land here
            logger.warn("updateGitRepo: unable to determine current branch: {}", e.getMessage());
        }

        // Fallback to a safe label for UI to avoid stale/missing branch display
        if (hasGit) {
            if (branchToDisplay == null || branchToDisplay.isBlank()) {
                branchToDisplay = "(no branch)";
                logger.trace("updateGitRepo: using fallback branch label '{}'", branchToDisplay);
            }
            final String display = branchToDisplay;
            SwingUtilities.invokeLater(() -> {
                try {
                    // Redundancy guard: only refresh if the displayed branch text actually changed
                    if (lastDisplayedBranchLabel != null && lastDisplayedBranchLabel.equals(display)) {
                        logger.trace(
                                "updateGitRepo: branch unchanged ({}), skipping InstructionsPanel refresh", display);
                        return;
                    }
                    instructionsPanel.refreshBranchUi(display);
                    lastDisplayedBranchLabel = display;
                } catch (Exception ex) {
                    logger.warn("updateGitRepo: failed to refresh InstructionsPanel branch UI: {}", ex.getMessage());
                }
            });
        }

        // Update individual Git-related panels and log what is being updated
        if (gitCommitTab != null) {
            logger.trace("updateGitRepo: updating GitCommitTab");
            gitCommitTab.updateCommitPanel();
        } else {
            logger.trace("updateGitRepo: GitCommitTab not present (skipping)");
        }

        if (gitLogTab != null) {
            logger.trace("updateGitRepo: updating GitLogTab");
            gitLogTab.update();
        } else {
            logger.trace("updateGitRepo: GitLogTab not present (skipping)");
        }

        if (gitWorktreeTab != null) {
            logger.trace("updateGitRepo: refreshing GitWorktreeTab");
            gitWorktreeTab.refresh();
        } else {
            logger.trace("updateGitRepo: GitWorktreeTab not present (skipping)");
        }

        logger.trace("updateGitRepo: updating ProjectFilesPanel");
        projectFilesPanel.updatePanel();
        logger.trace("updateGitRepo: finished");
    }

    /** Recreate the top-level Issues panel (e.g. after provider change). */
    public void recreateIssuesPanel() {
        SwingUtilities.invokeLater(() -> {
            if (issuesPanel != null) {
                var idx = leftTabbedPanel.indexOfComponent(issuesPanel);
                if (idx != -1) leftTabbedPanel.remove(idx);
            }
            issuesPanel = new GitIssuesTab(this, contextManager);
            var icon = Icons.ASSIGNMENT;
            leftTabbedPanel.addTab(null, icon, issuesPanel);
            var tabIdx = leftTabbedPanel.indexOfComponent(issuesPanel);
            var label = createSquareTabLabel(icon, "Issues");
            leftTabbedPanel.setTabComponentAt(tabIdx, label);
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    leftTabbedPanel.setSelectedIndex(tabIdx);
                }
            });
            leftTabbedPanel.setSelectedIndex(tabIdx);
        });
    }

    private void registerGlobalKeyboardShortcuts() {
        var rootPane = frame.getRootPane();

        // Cmd/Ctrl+Z => undo (configurable)
        KeyStroke undoKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "global.undo",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, undoKeyStroke, "globalUndo");
        rootPane.getActionMap().put("globalUndo", globalUndoAction);

        // Cmd/Ctrl+Shift+Z (or Cmd/Ctrl+Y) => redo
        KeyStroke redoKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "global.redo",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_Z,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        // For Windows/Linux, Ctrl+Y is also common for redo
        KeyStroke redoYKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "global.redoY",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));

        bindKey(rootPane, redoKeyStroke, "globalRedo");
        bindKey(rootPane, redoYKeyStroke, "globalRedo");
        rootPane.getActionMap().put("globalRedo", globalRedoAction);

        // Cmd/Ctrl+C => global copy
        KeyStroke copyKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "global.copy",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, copyKeyStroke, "globalCopy");
        rootPane.getActionMap().put("globalCopy", globalCopyAction);

        // Cmd/Ctrl+V => global paste
        KeyStroke pasteKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "global.paste",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, pasteKeyStroke, "globalPaste");
        rootPane.getActionMap().put("globalPaste", globalPasteAction);

        // Cmd/Ctrl+L => toggle microphone
        KeyStroke toggleMicKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "global.toggleMicrophone",
                io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_L));
        bindKey(rootPane, toggleMicKeyStroke, "globalToggleMic");
        rootPane.getActionMap().put("globalToggleMic", globalToggleMicAction);

        // Submit action (configurable; default Cmd/Ctrl+Enter) - only when instructions area is focused
        KeyStroke submitKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "instructions.submit",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        // Bind directly to instructions area instead of globally to avoid interfering with other components
        instructionsPanel
                .getInstructionsArea()
                .getInputMap(JComponent.WHEN_FOCUSED)
                .put(submitKeyStroke, "submitAction");
        instructionsPanel.getInstructionsArea().getActionMap().put("submitAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        instructionsPanel.onActionButtonPressed();
                    } catch (Exception ex) {
                        logger.error("Error executing submit action", ex);
                    }
                });
            }
        });

        // Cmd/Ctrl+M => toggle Code/Answer mode (configurable)
        KeyStroke toggleModeKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "instructions.toggleMode",
                io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_M));
        bindKey(rootPane, toggleModeKeyStroke, "toggleCodeAnswer");
        rootPane.getActionMap().put("toggleCodeAnswer", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        instructionsPanel.toggleCodeAnswerMode();
                        showNotification(NotificationRole.INFO, "Toggled Code/Ask mode");
                    } catch (Exception ex) {
                        logger.warn("Error toggling Code/Answer mode via shortcut", ex);
                    }
                });
            }
        });

        // Open Settings (configurable; default Cmd/Ctrl+,)
        KeyStroke openSettingsKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "global.openSettings",
                io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_COMMA));
        bindKey(rootPane, openSettingsKeyStroke, "openSettings");
        rootPane.getActionMap().put("openSettings", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(() -> MenuBar.openSettingsDialog(Chrome.this));
            }
        });

        // Close Window (configurable; default Cmd/Ctrl+W; never allow bare ESC)
        KeyStroke closeWindowKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "global.closeWindow",
                io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_W));
        if (closeWindowKeyStroke.getKeyCode() == KeyEvent.VK_ESCAPE && closeWindowKeyStroke.getModifiers() == 0) {
            closeWindowKeyStroke =
                    io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_W);
        }
        bindKey(rootPane, closeWindowKeyStroke, "closeMainWindow");
        rootPane.getActionMap().put("closeMainWindow", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            }
        });

        // Register IntelliJ-style shortcuts for switching sidebar panels
        // Determine the modifier based on platform (Cmd on Mac, Alt on Windows/Linux)
        int modifier =
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac")
                        ? KeyEvent.META_DOWN_MASK
                        : KeyEvent.ALT_DOWN_MASK;

        // Alt/Cmd+1 for Project Files
        KeyStroke switchToProjectFiles = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "panel.switchToProjectFiles", KeyStroke.getKeyStroke(KeyEvent.VK_1, modifier));
        bindKey(rootPane, switchToProjectFiles, "switchToProjectFiles");
        rootPane.getActionMap().put("switchToProjectFiles", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                leftTabbedPanel.setSelectedIndex(0); // Project Files is always at index 0
            }
        });

        // Alt/Cmd+2 for Changes (GitCommitTab)
        if (gitCommitTab != null) {
            KeyStroke switchToChanges = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                    "panel.switchToChanges", KeyStroke.getKeyStroke(KeyEvent.VK_2, modifier));
            bindKey(rootPane, switchToChanges, "switchToChanges");
            rootPane.getActionMap().put("switchToChanges", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var idx = leftTabbedPanel.indexOfComponent(gitCommitTab);
                    if (idx != -1) leftTabbedPanel.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+3 for Worktrees
        if (gitWorktreeTab != null) {
            KeyStroke switchToWorktrees = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                    "panel.switchToWorktrees", KeyStroke.getKeyStroke(KeyEvent.VK_3, modifier));
            bindKey(rootPane, switchToWorktrees, "switchToWorktrees");
            rootPane.getActionMap().put("switchToWorktrees", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var idx = leftTabbedPanel.indexOfComponent(gitWorktreeTab);
                    if (idx != -1) leftTabbedPanel.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+4 for Log
        if (gitLogTab != null) {
            KeyStroke switchToLog = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                    "panel.switchToLog", KeyStroke.getKeyStroke(KeyEvent.VK_4, modifier));
            bindKey(rootPane, switchToLog, "switchToLog");
            rootPane.getActionMap().put("switchToLog", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var idx = leftTabbedPanel.indexOfComponent(gitLogTab);
                    if (idx != -1) leftTabbedPanel.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+5 for Pull Requests panel (if available)
        if (pullRequestsPanel != null) {
            KeyStroke switchToPR = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                    "panel.switchToPullRequests", KeyStroke.getKeyStroke(KeyEvent.VK_5, modifier));
            bindKey(rootPane, switchToPR, "switchToPullRequests");
            rootPane.getActionMap().put("switchToPullRequests", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var idx = leftTabbedPanel.indexOfComponent(pullRequestsPanel);
                    if (idx != -1) leftTabbedPanel.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+6 for Issues panel (if available)
        if (issuesPanel != null) {
            KeyStroke switchToIssues = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                    "panel.switchToIssues", KeyStroke.getKeyStroke(KeyEvent.VK_6, modifier));
            bindKey(rootPane, switchToIssues, "switchToIssues");
            rootPane.getActionMap().put("switchToIssues", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var idx = leftTabbedPanel.indexOfComponent(issuesPanel);
                    if (idx != -1) leftTabbedPanel.setSelectedIndex(idx);
                }
            });
        }

        // Drawer navigation shortcuts
        // Cmd/Ctrl+Shift+T => toggle terminal drawer
        KeyStroke toggleTerminalDrawerKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "drawer.toggleTerminal",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_T,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        bindKey(rootPane, toggleTerminalDrawerKeyStroke, "toggleTerminalDrawer");
        rootPane.getActionMap().put("toggleTerminalDrawer", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                terminalDrawer.openTerminal();
            }
        });

        // Cmd/Ctrl+Shift+D => toggle dependencies tab
        KeyStroke toggleDependenciesTabKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "drawer.toggleDependencies",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_D,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        bindKey(rootPane, toggleDependenciesTabKeyStroke, "toggleDependenciesTab");
        rootPane.getActionMap().put("toggleDependenciesTab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showDependenciesTab();
            }
        });

        // Cmd/Ctrl+T => switch to terminal tab
        KeyStroke switchToTerminalTabKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "drawer.switchToTerminal",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, switchToTerminalTabKeyStroke, "switchToTerminalTab");
        rootPane.getActionMap().put("switchToTerminalTab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                terminalDrawer.openTerminal();
            }
        });

        // Cmd/Ctrl+K => switch to tasks tab
        KeyStroke switchToTasksTabKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "drawer.switchToTasks",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_K, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, switchToTasksTabKeyStroke, "switchToTasksTab");
        rootPane.getActionMap().put("switchToTasksTab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                terminalDrawer.openTaskList();
            }
        });

        // Zoom shortcuts: read from global settings (defaults preserved)
        KeyStroke zoomInKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "view.zoomIn",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_PLUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        KeyStroke zoomInEqualsKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "view.zoomInAlt",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_EQUALS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        KeyStroke zoomOutKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "view.zoomOut",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_MINUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        KeyStroke resetZoomKeyStroke = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "view.resetZoom",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_0, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));

        bindKey(rootPane, zoomInKeyStroke, "zoomIn");
        bindKey(rootPane, zoomInEqualsKeyStroke, "zoomIn");
        bindKey(rootPane, zoomOutKeyStroke, "zoomOut");
        bindKey(rootPane, resetZoomKeyStroke, "resetZoom");

        rootPane.getActionMap().put("zoomIn", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Use MOP webview zoom for global zoom functionality
                historyOutputPanel.getLlmStreamArea().zoomIn();
            }
        });

        rootPane.getActionMap().put("zoomOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Use MOP webview zoom for global zoom functionality
                historyOutputPanel.getLlmStreamArea().zoomOut();
            }
        });

        rootPane.getActionMap().put("resetZoom", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Use MOP webview zoom for global zoom functionality
                historyOutputPanel.getLlmStreamArea().resetZoom();
            }
        });
    }

    private static void bindKey(JRootPane rootPane, KeyStroke stroke, String actionKey) {
        // Remove any previous stroke bound to this actionKey to avoid duplicates
        var im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        // Remove all existing inputs mapping to actionKey
        for (KeyStroke ks : im.allKeys() == null ? new KeyStroke[0] : im.allKeys()) {
            Object val = im.get(ks);
            if (actionKey.equals(val)) {
                im.remove(ks);
            }
        }
        im.put(stroke, actionKey);
    }

    /** Re-registers global keyboard shortcuts from current GlobalUiSettings. */
    public void refreshKeybindings() {
        // Unregister and re-register by rebuilding the maps for the keys we manage
        var rootPane = frame.getRootPane();
        var im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = rootPane.getActionMap();

        // Remove old mappings for the keys we control (best-effort)
        // Then call the standard registration method to repopulate from settings
        im.clear();
        am.clear();
        registerGlobalKeyboardShortcuts();
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
        SwingUtilities.invokeLater(() -> historyOutputPanel.appendLlmOutput(token, type, isNewMessage, isReasoning));
    }

    /**
     * Resets the Output to history + main, and clears internal message buffers. After this, getLlmRawMessages will
     * return only the messages from `main`. Typically this use called with a single UserMessage in `main` to reset the
     * output to a fresh state for a new task.
     *
     * <p>You should probably call ContextManager::beginTask instead of calling this directly.
     */
    @Override
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry main) {
        SwingUtilities.invokeLater(() -> historyOutputPanel.setLlmAndHistoryOutput(history, main));
    }

    @Override
    public void toolError(String msg, String title) {
        logger.warn("%s: %s".formatted(msg, title));
        SwingUtilities.invokeLater(() -> systemNotify(msg, title, JOptionPane.ERROR_MESSAGE));
    }

    @Override
    public void backgroundOutput(String message) {
        backgroundOutput(message, null);
    }

    @Override
    public void backgroundOutput(String message, @Nullable String tooltip) {
        SwingUtilities.invokeLater(() -> {
            if (message.isEmpty()) {
                backgroundStatusLabel.setText(BGTASK_EMPTY);
                backgroundStatusLabel.setToolTipText(null);
            } else {
                backgroundStatusLabel.setText(message);
                backgroundStatusLabel.setToolTipText(tooltip);
            }
        });
    }

    @Override
    public void close() {
        logger.info("Closing Chrome UI");

        contextManager.close();
        frame.dispose();
        // Unregister this instance
        openInstances.remove(this);
    }

    @Override
    public void contextChanged(Context newCtx) {
        SwingUtilities.invokeLater(() -> {
            // This method is called by ContextManager when its history might have changed
            // (e.g., after an undo/redo operation affecting context or any pushContext).
            // We need to ensure the global action states are updated even if focus didn't change.
            globalUndoAction.updateEnabledState();
            globalRedoAction.updateEnabledState();
            globalCopyAction.updateEnabledState();
            globalPasteAction.updateEnabledState();
            globalToggleMicAction.updateEnabledState();

            // Also update HistoryOutputPanel's local buttons
            historyOutputPanel.updateUndoRedoButtonStates();
            setContext(newCtx); // Handles contextPanel update and historyOutputPanel.resetLlmOutput
            updateContextHistoryTable(newCtx); // Handles historyOutputPanel.updateHistoryTable
        });
    }

    /**
     * Creates a searchable content panel with a MarkdownOutputPanel and integrated search bar. This is shared
     * functionality used by both preview windows and detached output windows.
     *
     * @param markdownPanels List of MarkdownOutputPanel instances to make searchable
     * @param toolbarPanel Optional panel to add to the right of the search bar
     * @return A JPanel containing the search bar, optional toolbar, and content
     */
    public static JPanel createSearchableContentPanel(
            List<MarkdownOutputPanel> markdownPanels, @Nullable JPanel toolbarPanel) {
        return createSearchableContentPanel(markdownPanels, toolbarPanel, true);
    }

    public static JPanel createSearchableContentPanel(
            List<MarkdownOutputPanel> markdownPanels, @Nullable JPanel toolbarPanel, boolean wrapInScrollPane) {
        if (markdownPanels.isEmpty()) {
            return new JPanel(); // Return empty panel if no content
        }

        // If single panel, create a scroll pane for it if requested
        JComponent contentComponent;
        var componentsWithChatBackground = new ArrayList<JComponent>();
        if (markdownPanels.size() == 1) {
            if (wrapInScrollPane) {
                var scrollPane = new JScrollPane(markdownPanels.getFirst());
                scrollPane.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
                scrollPane.getVerticalScrollBar().setUnitIncrement(16);
                contentComponent = scrollPane;
            } else {
                contentComponent = markdownPanels.getFirst();
            }
        } else {
            // Multiple panels - create container with BoxLayout
            var messagesContainer = new JPanel();
            messagesContainer.setLayout(new BoxLayout(messagesContainer, BoxLayout.Y_AXIS));
            componentsWithChatBackground.add(messagesContainer);

            for (MarkdownOutputPanel panel : markdownPanels) {
                messagesContainer.add(panel);
            }

            if (wrapInScrollPane) {
                var scrollPane = new JScrollPane(messagesContainer);
                scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                scrollPane.getVerticalScrollBar().setUnitIncrement(16);
                contentComponent = scrollPane;
            } else {
                contentComponent = messagesContainer;
            }
        }

        // Create main content panel to hold search bar and content
        var contentPanel = new SearchableContentPanel(componentsWithChatBackground, markdownPanels);
        componentsWithChatBackground.add(contentPanel);

        // Create searchable component adapter and generic search bar
        var searchableComponent = new MarkdownSearchableComponent(markdownPanels);
        var searchBar = new GenericSearchBar(searchableComponent);
        componentsWithChatBackground.add(searchBar);

        // Create top panel with search bar and optional toolbar
        JPanel topPanel;
        if (toolbarPanel != null) {
            topPanel = new JPanel(new BorderLayout());
            topPanel.add(searchBar, BorderLayout.CENTER);
            topPanel.add(toolbarPanel, BorderLayout.EAST);
            toolbarPanel.setBackground(markdownPanels.getFirst().getBackground());
            componentsWithChatBackground.add(toolbarPanel);
            topPanel.setBackground(markdownPanels.getFirst().getBackground());
            componentsWithChatBackground.add(topPanel);
        } else {
            topPanel = searchBar;
        }

        componentsWithChatBackground.forEach(
                c -> c.setBackground(markdownPanels.getFirst().getBackground()));

        // Add 5px gap below the top panel
        topPanel.setBorder(new EmptyBorder(0, 0, 5, 0));

        // Add components to content panel
        contentPanel.add(topPanel, BorderLayout.NORTH);
        contentPanel.add(contentComponent, BorderLayout.CENTER);

        // Register Ctrl/Cmd+F to focus search field
        searchBar.registerGlobalShortcuts(contentPanel);

        return contentPanel;
    }

    /**
     * Creates and shows a standard preview JFrame for a given component. Handles title, default close operation,
     * loading/saving bounds using the "preview" key, and visibility. Reuses existing preview windows when possible to
     * avoid cluttering the desktop.
     *
     * @param contextManager The context manager for accessing project settings.
     * @param title The title for the JFrame.
     * @param contentComponent The JComponent to display within the frame.
     */
    public void showPreviewFrame(ContextManager contextManager, String title, JComponent contentComponent) {
        // Generate a key for window reuse based on the content type and title
        String windowKey = generatePreviewWindowKey(title, contentComponent);

        // Check if we have an existing window for this content
        JFrame previewFrame = activePreviewWindows.get(windowKey);
        boolean isNewWindow = false;

        if (previewFrame == null || !previewFrame.isDisplayable()) {
            // Create new window if none exists or existing one was disposed
            previewFrame = newFrame(title);
            activePreviewWindows.put(windowKey, previewFrame);
            isNewWindow = true;

            // Set up new window configuration
            if (SystemInfo.isMacOS && SystemInfo.isMacFullWindowContentSupported) {
                var titleBar = new JPanel(new BorderLayout());
                titleBar.setBorder(new EmptyBorder(4, 80, 4, 0)); // Padding for window controls
                var label = new JLabel(title, SwingConstants.CENTER);
                titleBar.add(label, BorderLayout.CENTER);
                previewFrame.add(titleBar, BorderLayout.NORTH);
            }
            previewFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            previewFrame.setBackground(
                    themeManager.isDarkTheme() ? UIManager.getColor("chat_background") : Color.WHITE);

            var project = contextManager.getProject();
            boolean isDependencies = contentComponent instanceof DependenciesDrawerPanel;

            if (isDependencies) {
                if (dependenciesDialogBounds != null
                        && dependenciesDialogBounds.width > 0
                        && dependenciesDialogBounds.height > 0) {
                    previewFrame.setBounds(dependenciesDialogBounds);
                    if (!isPositionOnScreen(dependenciesDialogBounds.x, dependenciesDialogBounds.y)) {
                        previewFrame.setLocationRelativeTo(frame); // Center if off-screen
                    }
                } else {
                    previewFrame.setSize(800, 500);
                    previewFrame.setLocationRelativeTo(frame);
                }
            } else {
                var storedBounds = project.getPreviewWindowBounds(); // Use preview bounds
                if (storedBounds.width > 0 && storedBounds.height > 0) {
                    previewFrame.setBounds(storedBounds);
                    if (!isPositionOnScreen(storedBounds.x, storedBounds.y)) {
                        previewFrame.setLocationRelativeTo(frame); // Center if off-screen
                    }
                } else {
                    previewFrame.setSize(800, 600); // Default size if no bounds saved
                    previewFrame.setLocationRelativeTo(frame); // Center relative to main window
                }
            }

            // Set a minimum width for preview windows to ensure search controls work properly
            previewFrame.setMinimumSize(new Dimension(400, 200));

            // Add listener to save bounds using the "preview" key
            final JFrame finalFrameForBounds = previewFrame;
            previewFrame.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    if (isDependencies) {
                        dependenciesDialogBounds = finalFrameForBounds.getBounds();
                    } else {
                        project.savePreviewWindowBounds(finalFrameForBounds); // Save JFrame bounds
                    }
                }

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (isDependencies) {
                        dependenciesDialogBounds = finalFrameForBounds.getBounds();
                    } else {
                        project.savePreviewWindowBounds(finalFrameForBounds); // Save JFrame bounds
                    }
                }
            });
        } else {
            // Reuse existing window - update title and content
            previewFrame.setTitle(title);
            // Only remove the CENTER component to preserve title bar and other layout components
            var contentPane = previewFrame.getContentPane();
            Component centerComponent =
                    ((BorderLayout) contentPane.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            if (centerComponent != null) {
                contentPane.remove(centerComponent);
            }

            // Update title bar label on macOS if it exists
            if (SystemInfo.isMacOS && SystemInfo.isMacFullWindowContentSupported) {
                Component northComponent =
                        ((BorderLayout) contentPane.getLayout()).getLayoutComponent(BorderLayout.NORTH);
                if (northComponent instanceof JPanel titleBar) {
                    Component centerInTitleBar =
                            ((BorderLayout) titleBar.getLayout()).getLayoutComponent(BorderLayout.CENTER);
                    if (centerInTitleBar instanceof JLabel label) {
                        label.setText(title);
                    }
                }
            }
        }

        // Add content component (for both new and reused windows)
        previewFrame.add(contentComponent, BorderLayout.CENTER);

        // Only use DO_NOTHING_ON_CLOSE for PreviewTextPanel (which has its own confirmation dialog)
        // Other preview types should use DISPOSE_ON_CLOSE for normal close behavior
        if (contentComponent instanceof PreviewTextPanel) {
            previewFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        }

        if (contentComponent instanceof SearchableContentPanel scp) {
            var panels = scp.getMarkdownPanels();
            if (!panels.isEmpty()) {
                previewFrame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent e) {
                        for (var panel : scp.getMarkdownPanels()) {
                            MarkdownOutputPool.instance().giveBack(panel);
                        }
                    }
                });
            }
        }

        // Add window cleanup listener to remove from tracking map when window is disposed
        final String finalWindowKey = windowKey;
        final JFrame finalPreviewFrame = previewFrame;
        if (isNewWindow) {
            previewFrame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    // Remove from tracking map when window is closed
                    activePreviewWindows.remove(finalWindowKey, finalPreviewFrame);
                }
            });
        }

        // Add ESC key binding to close the window (delegates to windowClosing)
        final JFrame finalFrameForESC = previewFrame;
        var rootPane = previewFrame.getRootPane();
        var actionMap = rootPane.getActionMap();
        var inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeWindow");
        actionMap.put("closeWindow", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // Simulate window closing event to trigger the WindowListener logic
                finalFrameForESC.dispatchEvent(new WindowEvent(finalFrameForESC, WindowEvent.WINDOW_CLOSING));
            }
        });

        // Bring window to front and make visible
        previewFrame.toFront();
        previewFrame.setVisible(true);
    }

    /**
     * Generates a key for identifying and reusing preview windows based on content type and context. For file previews,
     * uses the file path. For other content, uses the title.
     */
    private String generatePreviewWindowKey(String title, JComponent contentComponent) {
        if (contentComponent instanceof PreviewTextPanel) {
            // For file previews, extract file path from title or use title as fallback
            if (title.startsWith("Preview: ")) {
                return "file:" + title.substring(9); // Remove "Preview: " prefix
            } else {
                return "file:" + title;
            }
        } else {
            // For other types of previews, use a generic key based on class and title
            return "preview:" + contentComponent.getClass().getSimpleName() + ":" + title;
        }
    }

    /** Shows the dependencies tab. If the tab is already visible, it collapses the sidebar. */
    public void showDependenciesTab() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        int dependenciesTabIndex = leftTabbedPanel.indexOfComponent(dependenciesPanel);
        if (dependenciesTabIndex != -1) {
            handleTabToggle(dependenciesTabIndex);
        }
    }

    /** Closes all active preview windows and clears the tracking map. Useful for cleanup or when switching projects. */
    public void closeAllPreviewWindows() {
        for (JFrame frame : activePreviewWindows.values()) {
            if (frame.isDisplayable()) {
                frame.dispose();
            }
        }
        activePreviewWindows.clear();
    }

    /**
     * Centralized method to open a preview for a specific ProjectFile. Reads the file, determines syntax, creates
     * PreviewTextPanel, and shows the frame.
     *
     * @param pf The ProjectFile to preview.
     */
    public void previewFile(ProjectFile pf) {
        previewFile(pf, -1);
    }

    /**
     * Centralized method to open a preview for a specific ProjectFile at a specified line position.
     *
     * @param pf The ProjectFile to preview.
     * @param startLine The line number (0-based) to position the caret at, or -1 to use default positioning.
     */
    public void previewFile(ProjectFile pf, int startLine) {
        assert SwingUtilities.isEventDispatchThread() : "Preview must be initiated on EDT";

        try {
            // 1. Read file content
            var content = pf.read();
            if (content.isEmpty()) {
                toolError("Unable to read file for preview");
                return;
            }

            // 2. Deduce syntax style
            var syntax = pf.getSyntaxStyle();

            // 3. Build the PTP with custom positioning
            var panel = new PreviewTextPanel(contextManager, pf, content.get(), syntax, themeManager, null);

            // 4. Show in frame first
            showPreviewFrame(contextManager, "Preview: " + pf, panel);

            // 5. Position the caret at the specified line if provided, after showing the frame
            if (startLine >= 0) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        // Convert line number to character offset
                        var lines = content.get().split("\\r?\\n", -1); // -1 to include trailing empty lines
                        if (startLine < lines.length) {
                            var charOffset = 0;
                            for (var i = 0; i < startLine; i++) {
                                charOffset += lines[i].length() + 1; // +1 for line separator
                            }
                            panel.setCaretPositionAndCenter(charOffset);
                        } else {
                            logger.warn(
                                    "Start line {} exceeds file length {} for {}",
                                    startLine,
                                    lines.length,
                                    pf.absPath());
                        }
                    } catch (Exception e) {
                        logger.warn(
                                "Failed to position caret at line {} for {}: {}",
                                startLine,
                                pf.absPath(),
                                e.getMessage());
                        // Fall back to default positioning (beginning of file)
                    }
                });
            }

        } catch (Exception ex) {
            toolError("Error opening file preview: " + ex.getMessage());
            logger.error("Unexpected error opening preview for file {}", pf.absPath(), ex);
        }
    }

    /**
     * Opens an in-place preview of a context fragment.
     *
     * <ul>
     *   <li>If the fragment lives in the <em>current</em> context (i.e. the latest context in the Context-History
     *       stack) and represents a live file on disk, we surface the <b>editable</b> version so the user can save
     *       changes, capture usages, etc.
     *   <li>If the fragment comes from an older (historical) context, or if it is a snapshot frozen by
     *       {@code FrozenFragment}, we instead show the point-in-time content that was captured when the context was
     *       created.
     * </ul>
     *
     * <p>This logic allows Chrome to run entirely on <em>frozen</em> contexts while still giving the user a live
     * editing experience for the active one.
     */
    public void openFragmentPreview(ContextFragment fragment) {

        try {
            // 1. Figure out whether this fragment belongs to the *current* (latest) context
            var latestCtx = contextManager.getContextHistory().topContext();
            boolean isCurrentContext = latestCtx.allFragments().anyMatch(f -> f.id().equals(fragment.id()));

            // If it is current *and* is a frozen PathFragment, unfreeze so we can work on
            // a true PathFragment instance (gives us access to BrokkFile, etc.).
            ContextFragment workingFragment;
            if (isCurrentContext && fragment.getType().isPath() && fragment instanceof FrozenFragment frozen) {
                workingFragment = frozen.unfreeze(contextManager);
            } else {
                workingFragment = fragment;
            }

            // Everything below operates on workingFragment
            var title = "Preview: " + workingFragment.description();

            // 2. Output-only fragments (Task / History / Search)
            if (workingFragment.getType().isOutput()) {
                var outputFragment = (ContextFragment.OutputFragment) workingFragment;
                // var escapeHtml = outputFragment.isEscapeHtml();
                var combinedMessages = new ArrayList<ChatMessage>();

                for (TaskEntry entry : outputFragment.entries()) {
                    if (entry.isCompressed()) {

                        combinedMessages.add(
                                Messages.customSystem(Objects.toString(entry.summary(), "Summary not available")));
                    } else {
                        combinedMessages.addAll(castNonNull(entry.log()).messages());
                    }
                }

                var markdownPanel = MarkdownOutputPool.instance().borrow();
                markdownPanel.withContextForLookups(contextManager, this);
                markdownPanel.setText(combinedMessages);

                // Use shared utility method to create searchable content panel without scroll pane
                JPanel previewContentPanel = createSearchableContentPanel(List.of(markdownPanel), null, false);

                showPreviewFrame(contextManager, title, previewContentPanel);
                return;
            }

            // 3. Image fragments (clipboard image or image file)
            if (!workingFragment.isText()) {
                if (workingFragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE) {
                    var pif = (ContextFragment.AnonymousImageFragment) workingFragment;
                    var imagePanel = new PreviewImagePanel(null);
                    imagePanel.setImage(pif.image());
                    showPreviewFrame(contextManager, title, imagePanel);
                    return;
                }
                if (workingFragment.getType() == ContextFragment.FragmentType.IMAGE_FILE) {
                    var iff = (ContextFragment.ImageFileFragment) workingFragment;
                    PreviewImagePanel.showInFrame(frame, contextManager, iff.file());
                    return;
                }
            }

            // 4. Specific handling for Git-history snapshots
            if (workingFragment.getType() == ContextFragment.FragmentType.GIT_FILE) {
                var ghf = (ContextFragment.GitFileFragment) workingFragment;
                // pass the actual ProjectFile so dynamic menu items can be built
                var previewPanel = new PreviewTextPanel(
                        contextManager, ghf.file(), ghf.text(), ghf.syntaxStyle(), themeManager, ghf);
                showPreviewFrame(contextManager, title, previewPanel);
                return;
            }

            // 5. Path fragments (files on disk) – live vs. snapshot decision
            if (workingFragment.getType().isPath()) {
                // If we were able to unfreeze to a real PathFragment AND it belongs to the
                // current context, show the live file so the user can edit/save.
                if (isCurrentContext && workingFragment instanceof ContextFragment.PathFragment pf) {
                    var brokkFile = pf.file();
                    if (brokkFile instanceof ProjectFile projectFile) {
                        // Live ProjectFile – delegate to helper that sets up edit/save UI.
                        if (!SwingUtilities.isEventDispatchThread()) {
                            SwingUtilities.invokeLater(() -> previewFile(projectFile));
                        } else {
                            previewFile(projectFile);
                        }
                        return;
                    } else if (brokkFile instanceof ExternalFile externalFile) {
                        // External file on disk – read it live.
                        Runnable task = () -> {
                            var panel = new PreviewTextPanel(
                                    contextManager,
                                    null,
                                    externalFile.read().orElse(""),
                                    externalFile.getSyntaxStyle(),
                                    themeManager,
                                    workingFragment);
                            showPreviewFrame(contextManager, "Preview: " + externalFile, panel);
                        };
                        if (!SwingUtilities.isEventDispatchThread()) {
                            SwingUtilities.invokeLater(task);
                        } else {
                            task.run();
                        }
                        return;
                    }
                }

                // Otherwise – fall back to showing the frozen snapshot.
                ProjectFile srcFile = null;
                if (workingFragment instanceof ContextFragment.PathFragment pfFrag
                        && pfFrag.file() instanceof ProjectFile p) {
                    srcFile = p; // supply the ProjectFile if we have one
                }
                var snapshotPanel = new PreviewTextPanel(
                        contextManager,
                        srcFile,
                        workingFragment.text(),
                        workingFragment.syntaxStyle(),
                        themeManager,
                        workingFragment);
                showPreviewFrame(contextManager, title, snapshotPanel);
                return;
            }

            // 6. Everything else (virtual fragments, skeletons, etc.)
            if (workingFragment.isText()
                    && workingFragment.syntaxStyle().equals(SyntaxConstants.SYNTAX_STYLE_MARKDOWN)) {
                var markdownPanel = MarkdownOutputPool.instance().borrow();
                markdownPanel.updateTheme(themeManager.isDarkTheme());
                markdownPanel.setText(List.of(Messages.customSystem(workingFragment.text())));

                // Use shared utility method to create searchable content panel without scroll pane
                JPanel previewContentPanel = createSearchableContentPanel(List.of(markdownPanel), null, false);

                showPreviewFrame(contextManager, title, previewContentPanel);
            } else {
                var previewPanel = new PreviewTextPanel(
                        contextManager,
                        null,
                        workingFragment.text(),
                        workingFragment.syntaxStyle(),
                        themeManager,
                        workingFragment);
                showPreviewFrame(contextManager, title, previewPanel);
            }
        } catch (IOException ex) {
            toolError("Error reading fragment content: " + ex.getMessage());
            logger.error("Error reading fragment content for preview", ex);
        } catch (Exception ex) {
            logger.debug("Error opening preview", ex);
            toolError("Error opening preview: " + ex.getMessage());
        }
    }

    private void loadWindowSizeAndPosition() {
        boolean persistPerProject = GlobalUiSettings.isPersistPerProjectBounds();

        // Per-project first (only if enabled)
        var boundsOpt = persistPerProject ? getProject().getMainWindowBounds() : java.util.Optional.<Rectangle>empty();
        if (boundsOpt.isPresent()) {
            var bounds = boundsOpt.get();
            frame.setSize(bounds.width, bounds.height);
            if (isPositionOnScreen(bounds.x, bounds.y)) {
                frame.setLocation(bounds.x, bounds.y);
                logger.debug("Restoring main window position from project settings.");
            } else {
                // Saved position is off-screen, center instead
                frame.setLocationRelativeTo(null);
                logger.debug("Project window position is off-screen, centering window.");
            }
        } else {
            // No (or disabled) project bounds, try global bounds with cascading offset
            var globalBounds = GlobalUiSettings.getMainWindowBounds();
            if (globalBounds.width > 0 && globalBounds.height > 0) {
                // Calculate progressive DPI-aware offset based on number of open instances
                int instanceCount = Math.max(0, openInstances.size()); // this instance not yet added
                int step = UIScale.scale(20); // gentle, DPI-aware cascade step
                int offsetX = globalBounds.x + (step * instanceCount);
                int offsetY = globalBounds.y + (step * instanceCount);

                frame.setSize(globalBounds.width, globalBounds.height);
                if (isPositionOnScreen(offsetX, offsetY)) {
                    frame.setLocation(offsetX, offsetY);
                    logger.debug("Using global window position with cascading offset ({}) as fallback.", instanceCount);
                } else {
                    // Offset position is off-screen, center instead
                    frame.setLocationRelativeTo(null);
                    logger.debug("Global window position with offset is off-screen, centering window.");
                }
            } else {
                // No valid saved bounds anywhere, apply default placement logic
                logger.info("No UI bounds found, using default window layout");
                GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                GraphicsDevice defaultScreen = ge.getDefaultScreenDevice();
                Rectangle screenBounds = defaultScreen.getDefaultConfiguration().getBounds();

                // Default to 1920x1080 or screen size, whichever is smaller, and center.
                int defaultWidth = Math.min(1920, screenBounds.width);
                int defaultHeight = Math.min(1080, screenBounds.height);

                int x = screenBounds.x + (screenBounds.width - defaultWidth) / 2;
                int y = screenBounds.y + (screenBounds.height - defaultHeight) / 2;

                frame.setBounds(x, y, defaultWidth, defaultHeight);
                logger.debug(
                        "Applying default window placement: {}x{} at ({},{}), centered on screen.",
                        defaultWidth,
                        defaultHeight,
                        x,
                        y);
            }
        }

        // Listener to save bounds on move/resize:
        // - always save globally (for cascade fallback)
        // - save per-project only if enabled
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                GlobalUiSettings.saveMainWindowBounds(frame);
                if (GlobalUiSettings.isPersistPerProjectBounds()) {
                    getProject().saveMainWindowBounds(frame);
                }
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                GlobalUiSettings.saveMainWindowBounds(frame);
                if (GlobalUiSettings.isPersistPerProjectBounds()) {
                    getProject().saveMainWindowBounds(frame);
                }
            }
        });
    }

    /**
     * Completes all window and split pane layout operations synchronously. This ensures the window has proper layout
     * before becoming visible.
     *
     * <p>CRITICAL FIX: Uses frame.pack() to force proper component sizing, then restores intended window size. This
     * resolves the issue where components had zero size when workspace.properties is missing, causing empty gray window
     * on startup.
     */
    private void completeLayoutSynchronously() {
        // First, set up window size and position
        loadWindowSizeAndPosition();

        // Then complete split pane layout synchronously
        var project = getProject();

        // Force a layout pass so split panes have proper sizes before setting divider locations
        frame.validate();

        // Set horizontal (sidebar) split pane divider now - it depends on frame width which is already known
        // Global-first for horizontal split
        int globalHorizontalPos = GlobalUiSettings.getHorizontalSplitPosition();
        int properDividerLocation;
        if (globalHorizontalPos > 0) {
            properDividerLocation = Math.min(globalHorizontalPos, Math.max(50, frame.getWidth() - 200));
        } else {
            // No saved global position, calculate based on current frame size
            int computedWidth = computeInitialSidebarWidth();
            properDividerLocation = computedWidth + bottomSplitPane.getDividerSize();
        }

        bottomSplitPane.setDividerLocation(properDividerLocation);

        if (properDividerLocation < SIDEBAR_COLLAPSED_THRESHOLD) {
            bottomSplitPane.setDividerSize(0);
            leftTabbedPanel.setSelectedIndex(0); // Show Project Files when collapsed
            sidebarCollapsed = true;
        } else {
            lastExpandedSidebarLocation = properDividerLocation;
        }

        // Add property change listeners for future updates (also persist globally)
        addSplitPaneListeners(project);

        // Apply title bar now that layout is complete
        applyTitleBar(frame, frame.getTitle());

        // Force a complete layout validation
        frame.revalidate();

        // Fix zero-sized components by forcing layout calculation with pack()
        // Remember the intended size before pack changes it
        int intendedWidth = frame.getWidth();
        int intendedHeight = frame.getHeight();

        frame.pack(); // This forces proper component sizing
        frame.setSize(intendedWidth, intendedHeight); // Restore intended window size
        frame.validate();

        // NOW calculate vertical split pane dividers with proper component heights

        // Global-first for top split (Workspace | Instructions)
        int topSplitPos = GlobalUiSettings.getLeftVerticalSplitPosition();
        if (topSplitPos > 0) {
            topSplitPane.setDividerLocation(topSplitPos);
        } else {
            // Calculate absolute position with proper component height
            int topSplitHeight = topSplitPane.getHeight();
            int defaultTopSplitPos = (int) (topSplitHeight * DEFAULT_WORKSPACE_INSTRUCTIONS_SPLIT);
            topSplitPane.setDividerLocation(defaultTopSplitPos);
        }

        // Global-first for main vertical split (Output | Main)
        int mainVerticalPos = GlobalUiSettings.getRightVerticalSplitPosition();
        if (mainVerticalPos > 0) {
            mainVerticalSplitPane.setDividerLocation(mainVerticalPos);
        } else {
            // Calculate absolute position with proper component height
            int mainVerticalHeight = mainVerticalSplitPane.getHeight();
            int defaultMainVerticalPos = (int) (mainVerticalHeight * DEFAULT_OUTPUT_MAIN_SPLIT);
            mainVerticalSplitPane.setDividerLocation(defaultMainVerticalPos);
        }

        // Restore drawer states from global settings
        restoreDrawersFromGlobalSettings();

        // Restore Workspace collapsed/expanded state: prefer per-project, fallback to global default
        try {
            Boolean projCollapsed = readProjectWorkspaceCollapsed();
            boolean collapsed = (projCollapsed != null) ? projCollapsed : readGlobalWorkspaceCollapsed();
            // Only apply if different from current to avoid redundant relayout
            if (collapsed != this.workspaceCollapsed) {
                setWorkspaceCollapsed(collapsed);
            }
        } catch (Exception ignored) {
            // Defensive: do not let preference errors interrupt UI construction
        }
    }

    /**
     * Restore drawer (dependencies) state from global settings after layout sizing is known. Terminal drawer restore is
     * handled by TerminalDrawerPanel itself to respect per-project settings.
     */
    private void restoreDrawersFromGlobalSettings() {
        // Do not restore Terminal drawer here.
        // TerminalDrawerPanel.restoreInitialState() handles per-project-first, then global fallback.
    }

    // --- Workspace collapsed persistence (per-project with global fallback) ---

    private static final String PREFS_ROOT = "io.github.jbellis.brokk";
    private static final String PREFS_PROJECTS = "projects";
    private static final String PREF_KEY_WORKSPACE_COLLAPSED = "workspaceCollapsed";
    private static final String PREF_KEY_WORKSPACE_COLLAPSED_GLOBAL = "workspaceCollapsedGlobal";

    private static Preferences prefsRoot() {
        return Preferences.userRoot().node(PREFS_ROOT);
    }

    private static String sanitizeNodeName(String s) {
        // Preferences node names may not contain '/'.
        return s.replace('/', '_').replace('\\', '_').replace(':', '_');
    }

    private Preferences projectPrefsNode() {
        String projKey = sanitizeNodeName(getProject().getRoot().toString());
        return prefsRoot().node(PREFS_PROJECTS).node(projKey);
    }

    /** Save the current workspace collapsed state both per-project and as a global default. */
    private void saveWorkspaceCollapsedSetting(boolean collapsed) {
        try {
            // Per-project
            var p = projectPrefsNode();
            p.putBoolean(PREF_KEY_WORKSPACE_COLLAPSED, collapsed);
            p.flush();
        } catch (Exception ignored) {
            // Non-fatal persistence failure
        }
        try {
            // Global default
            var g = prefsRoot();
            g.putBoolean(PREF_KEY_WORKSPACE_COLLAPSED_GLOBAL, collapsed);
            g.flush();
        } catch (Exception ignored) {
            // Non-fatal persistence failure
        }
    }

    /** Returns the per-project collapsed setting if present; otherwise returns null. */
    private @Nullable Boolean readProjectWorkspaceCollapsed() {
        try {
            var p = projectPrefsNode();
            String raw = p.get(PREF_KEY_WORKSPACE_COLLAPSED, null);
            return (raw == null) ? null : Boolean.valueOf(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Returns the global default collapsed setting, defaulting to false if unset. */
    private boolean readGlobalWorkspaceCollapsed() {
        try {
            var g = prefsRoot();
            return g.getBoolean(PREF_KEY_WORKSPACE_COLLAPSED_GLOBAL, false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Adds property change listeners to split panes for saving positions (global-first). */
    private void addSplitPaneListeners(AbstractProject project) {
        topSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (topSplitPane.isShowing()) {
                var newPos = topSplitPane.getDividerLocation();
                if (newPos > 0) {
                    // Keep backward-compat but persist globally as the source of truth
                    project.saveLeftVerticalSplitPosition(newPos);
                    GlobalUiSettings.saveLeftVerticalSplitPosition(newPos);
                }
            }
        });

        mainVerticalSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (!mainVerticalSplitPane.isShowing()) {
                return;
            }

            int newPos = mainVerticalSplitPane.getDividerLocation();

            // Clamp so the bottom (Instructions area) never shrinks below its minimum height.
            int total = mainVerticalSplitPane.getHeight();
            if (total > 0) {
                int dividerSize = mainVerticalSplitPane.getDividerSize();
                Component bottom = mainVerticalSplitPane.getBottomComponent();
                int minBottom = (bottom != null) ? Math.max(0, bottom.getMinimumSize().height) : 0;
                int maxLocation = Math.max(0, total - dividerSize - minBottom);

                if (newPos > maxLocation) {
                    if (!adjustingMainDivider) {
                        adjustingMainDivider = true;
                        SwingUtilities.invokeLater(() -> {
                            try {
                                mainVerticalSplitPane.setDividerLocation(maxLocation);
                            } finally {
                                adjustingMainDivider = false;
                            }
                        });
                    }
                    // Do not persist out-of-bounds positions
                    return;
                }
            }

            if (newPos > 0) {
                // Keep backward-compat but persist globally as the source of truth
                project.saveRightVerticalSplitPosition(newPos);
                GlobalUiSettings.saveRightVerticalSplitPosition(newPos);
            }
        });

        bottomSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (bottomSplitPane.isShowing()) {
                var newPos = bottomSplitPane.getDividerLocation();
                if (newPos > 0) {
                    // Keep backward-compat but persist globally as the source of truth
                    project.saveHorizontalSplitPosition(newPos);
                    GlobalUiSettings.saveHorizontalSplitPosition(newPos);
                    // Remember expanded locations only (ignore collapsed sidebar)
                    if (newPos >= SIDEBAR_COLLAPSED_THRESHOLD) {
                        lastExpandedSidebarLocation = newPos;
                    }
                }
            }
        });

        // Persist Terminal drawer open/proportion globally
        instructionsDrawerSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (instructionsDrawerSplit.isShowing()) {
                int total = instructionsDrawerSplit.getWidth();
                if (total > 0) {
                    double prop = Math.max(
                            0.05,
                            Math.min(0.95, (double) instructionsDrawerSplit.getDividerLocation() / (double) total));
                    GlobalUiSettings.saveTerminalDrawerProportion(prop);
                    GlobalUiSettings.saveTerminalDrawerOpen(instructionsDrawerSplit.getDividerSize() > 0);
                }
            }
        });
        instructionsDrawerSplit.addPropertyChangeListener("dividerSize", e -> {
            GlobalUiSettings.saveTerminalDrawerOpen(instructionsDrawerSplit.getDividerSize() > 0);
        });
    }

    @Override
    public void updateContextHistoryTable() {
        Context selectedContext = contextManager.selectedContext(); // Can be null
        updateContextHistoryTable(selectedContext);
    }

    @Override
    public void updateContextHistoryTable(
            @org.jetbrains.annotations.Nullable Context contextToSelect) { // contextToSelect can be null
        historyOutputPanel.updateHistoryTable(contextToSelect);
    }

    public boolean isPositionOnScreen(int x, int y) {
        for (var screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            for (var config : screen.getConfigurations()) {
                if (config.getBounds().contains(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void updateCaptureButtons() {
        var messageSize = historyOutputPanel.getLlmRawMessages().size();
        SwingUtilities.invokeLater(() -> {
            var enabled = messageSize > 0;
            historyOutputPanel.setCopyButtonEnabled(enabled);
            historyOutputPanel.setClearButtonEnabled(enabled);
            historyOutputPanel.setCaptureButtonEnabled(enabled);
            historyOutputPanel.setOpenWindowButtonEnabled(enabled);
        });
    }

    public JFrame getFrame() {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        return frame;
    }

    /** Shows the inline loading spinner in the output panel. */
    @Override
    public void showOutputSpinner(String message) {
        SwingUtilities.invokeLater(() -> {
            historyOutputPanel.showSpinner(message);
        });
    }

    /** Hides the inline loading spinner in the output panel. */
    @Override
    public void hideOutputSpinner() {
        SwingUtilities.invokeLater(historyOutputPanel::hideSpinner);
    }

    /** Shows the session switching spinner in the history panel. */
    @Override
    public void showSessionSwitchSpinner() {
        SwingUtilities.invokeLater(historyOutputPanel::showSessionSwitchSpinner);
    }

    /** Hides the session switching spinner in the history panel. */
    @Override
    public void hideSessionSwitchSpinner() {
        SwingUtilities.invokeLater(historyOutputPanel::hideSessionSwitchSpinner);
    }

    public void focusInput() {
        SwingUtilities.invokeLater(instructionsPanel::requestCommandInputFocus);
    }

    @Override
    public void updateWorkspace() {
        workspacePanel.updateContextTable();
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    public ProjectFilesPanel getProjectFilesPanel() {
        return projectFilesPanel;
    }

    public List<ContextFragment> getSelectedFragments() {
        return workspacePanel.getSelectedFragments();
    }

    public JTabbedPane getLeftTabbedPanel() {
        return leftTabbedPanel;
    }

    @Nullable
    public GitPullRequestsTab getPullRequestsPanel() {
        return pullRequestsPanel;
    }

    @Nullable
    public GitIssuesTab getIssuesPanel() {
        return issuesPanel;
    }

    // --- New helpers for Git tabs moved into Chrome ---

    public void updateLogTab() {
        if (gitLogTab != null) {
            gitLogTab.update();
        }
    }

    public void selectCurrentBranchInLogTab() {
        if (gitLogTab != null) {
            gitLogTab.selectCurrentBranch();
        }
    }

    public void showCommitInLogTab(String commitId) {
        if (gitLogTab != null) {
            for (int i = 0; i < leftTabbedPanel.getTabCount(); i++) {
                if (leftTabbedPanel.getComponentAt(i) == gitLogTab) {
                    leftTabbedPanel.setSelectedIndex(i);
                    break;
                }
            }
            gitLogTab.selectCommitById(commitId);
        }
    }

    private void selectExistingFileHistoryTab(String filePath) {
        var existing = fileHistoryTabs.get(filePath);
        if (existing == null) {
            return;
        }

        // Ensure the history pane is visible
        if (!historyTabbedPane.isVisible()) {
            historyTabbedPane.setVisible(true);
            leftVerticalSplitPane.setDividerSize(originalLeftVerticalDividerSize);
            leftVerticalSplitPane.setDividerLocation(0.7);
        }

        int count = historyTabbedPane.getTabCount();
        for (int i = 0; i < count; i++) {
            if (historyTabbedPane.getComponentAt(i) == existing) {
                historyTabbedPane.setSelectedIndex(i);
                break;
            }
        }
    }

    public void showFileHistory(ProjectFile file) {
        SwingUtilities.invokeLater(() -> addFileHistoryTab(file));
    }

    public void addFileHistoryTab(ProjectFile file) {
        String filePath = file.toString();
        if (fileHistoryTabs.containsKey(filePath)) {
            selectExistingFileHistoryTab(filePath);
            return;
        }

        var historyTab = new GitHistoryTab(this, contextManager, file);

        var tabHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabHeader.setOpaque(false);

        var titleLabel = new JLabel(file.getFileName());
        titleLabel.setOpaque(false);

        var closeButton = new JButton("×");
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
            int idx = historyTabbedPane.indexOfComponent(historyTab);
            if (idx >= 0) {
                historyTabbedPane.remove(idx);
                fileHistoryTabs.remove(filePath);

                // Hide history pane if now empty and remove divider
                if (historyTabbedPane.getTabCount() == 0) {
                    historyTabbedPane.setVisible(false);
                    leftVerticalSplitPane.setDividerSize(0);
                }
            }
        });

        tabHeader.add(titleLabel);
        tabHeader.add(closeButton);

        // Ensure history pane is visible and divider shown
        if (!historyTabbedPane.isVisible()) {
            historyTabbedPane.setVisible(true);
            leftVerticalSplitPane.setDividerSize(originalLeftVerticalDividerSize);
            leftVerticalSplitPane.setDividerLocation(0.7);
        }

        historyTabbedPane.addTab(file.getFileName(), historyTab);
        int newIndex = historyTabbedPane.indexOfComponent(historyTab);
        historyTabbedPane.setTabComponentAt(newIndex, tabHeader);
        historyTabbedPane.setSelectedIndex(newIndex);

        fileHistoryTabs.put(filePath, historyTab);
    }

    @Nullable
    public GitCommitTab getGitCommitTab() {
        return gitCommitTab;
    }

    /** Called by MenuBar after constructing the BlitzForge menu item. */
    public void setBlitzForgeMenuItem(JMenuItem blitzForgeMenuItem) {
        this.blitzForgeMenuItem = blitzForgeMenuItem;
    }

    public void showFileInProjectTree(ProjectFile projectFile) {
        projectFilesPanel.showFileInTree(projectFile);
    }

    @Override
    public InstructionsPanel getInstructionsPanel() {
        return instructionsPanel;
    }

    public WorkspacePanel getContextPanel() {
        return workspacePanel;
    }

    public HistoryOutputPanel getHistoryOutputPanel() {
        return historyOutputPanel;
    }

    public TerminalDrawerPanel getTerminalDrawer() {
        return terminalDrawer;
    }

    /** Append tasks to the Task List panel, if present. Tasks are appended to the current session's list. */
    public void appendTasksToTaskList(List<String> tasks) {
        SwingUtilities.invokeLater(() -> {
            var taskPanel = terminalDrawer.openTaskList();
            taskPanel.appendTasks(tasks);
        });
    }

    public Action getGlobalUndoAction() {
        return globalUndoAction;
    }

    public Action getGlobalRedoAction() {
        return globalRedoAction;
    }

    public Action getGlobalCopyAction() {
        return globalCopyAction;
    }

    public Action getGlobalPasteAction() {
        return globalPasteAction;
    }

    public Action getGlobalToggleMicAction() {
        return globalToggleMicAction;
    }

    private boolean isFocusInContextArea(@org.jetbrains.annotations.Nullable Component focusOwner) {
        if (focusOwner == null) return false;
        // Check if focus is within ContextPanel or HistoryOutputPanel's historyTable
        boolean inContextPanel = SwingUtilities.isDescendingFrom(focusOwner, workspacePanel);
        boolean inHistoryTable = historyOutputPanel.getHistoryTable() != null
                && SwingUtilities.isDescendingFrom(focusOwner, historyOutputPanel.getHistoryTable());
        return inContextPanel || inHistoryTable;
    }

    // --- Global Undo/Redo Action Classes ---
    private class GlobalUndoAction extends AbstractAction {
        public GlobalUndoAction(String name) {
            super(name);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                if (instructionsPanel.getCommandInputUndoManager().canUndo()) {
                    instructionsPanel.getCommandInputUndoManager().undo();
                }
            } else if (isFocusInContextArea(lastRelevantFocusOwner)) {
                if (contextManager.getContextHistory().hasUndoStates()) {
                    contextManager.undoContextAsync();
                }
            }
        }

        public void updateEnabledState() {
            boolean canUndoNow = false;
            if (lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                canUndoNow = instructionsPanel.getCommandInputUndoManager().canUndo();
            } else if (isFocusInContextArea(lastRelevantFocusOwner)) {
                canUndoNow = contextManager.getContextHistory().hasUndoStates();
            }
            setEnabled(canUndoNow);
        }
    }

    private class GlobalRedoAction extends AbstractAction {
        public GlobalRedoAction(String name) {
            super(name);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                if (instructionsPanel.getCommandInputUndoManager().canRedo()) {
                    instructionsPanel.getCommandInputUndoManager().redo();
                }
            } else if (isFocusInContextArea(lastRelevantFocusOwner)) {
                if (contextManager.getContextHistory().hasRedoStates()) {
                    contextManager.redoContextAsync();
                }
            }
        }

        public void updateEnabledState() {
            boolean canRedoNow = false;
            if (lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                canRedoNow = instructionsPanel.getCommandInputUndoManager().canRedo();
            } else if (isFocusInContextArea(lastRelevantFocusOwner)) {
                canRedoNow = contextManager.getContextHistory().hasRedoStates();
            }
            setEnabled(canRedoNow);
        }
    }

    // --- Global Copy/Paste Action Classes ---
    private class GlobalCopyAction extends AbstractAction {
        public GlobalCopyAction(String name) {
            super(name);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (lastRelevantFocusOwner == null) {
                return;
            }
            if (lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                instructionsPanel.getInstructionsArea().copy();
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, historyOutputPanel.getLlmStreamArea())) {
                historyOutputPanel.getLlmStreamArea().copy(); // Assumes MarkdownOutputPanel has copy()
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel)
                    || SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, historyOutputPanel.getHistoryTable())) {
                // If focus is in ContextPanel, use its selected fragments.
                // If focus is in HistoryTable, it's like "Copy All" from ContextPanel.
                List<ContextFragment> fragmentsToCopy = List.of(); // Default to "all"
                if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel)) {
                    fragmentsToCopy = workspacePanel.getSelectedFragments();
                }
                workspacePanel.performContextActionAsync(WorkspacePanel.ContextAction.COPY, fragmentsToCopy);
            }
        }

        public void updateEnabledState() {
            if (lastRelevantFocusOwner == null) {
                setEnabled(false);
                return;
            }

            boolean canCopyNow = false;
            if (lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                var field = instructionsPanel.getInstructionsArea();
                canCopyNow = (field.getSelectedText() != null
                                && !field.getSelectedText().isEmpty())
                        || !field.getText().isEmpty();
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, historyOutputPanel.getLlmStreamArea())) {
                var llmArea = historyOutputPanel.getLlmStreamArea();
                String selectedText = llmArea.getSelectedText();
                canCopyNow =
                        !selectedText.isEmpty() || !llmArea.getDisplayedText().isEmpty();
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel)
                    || SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, historyOutputPanel.getHistoryTable())) {
                // Focus is in a context area, context copy is always available
                canCopyNow = true;
            }
            setEnabled(canCopyNow);
        }
    }

    // for paste from menubar -- ctrl-v paste is handled in individual components
    private class GlobalPasteAction extends AbstractAction {
        public GlobalPasteAction(String name) {
            super(name);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (lastRelevantFocusOwner == null) {
                return;
            }

            if (lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                instructionsPanel.getInstructionsArea().paste();
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel)) {
                workspacePanel.performContextActionAsync(WorkspacePanel.ContextAction.PASTE, List.of());
            }
        }

        public void updateEnabledState() {
            boolean canPasteNow = false;
            if (lastRelevantFocusOwner == null) {
                // leave it false
            } else if (lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                canPasteNow = java.awt.Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor);
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel)) {
                // ContextPanel's doPasteAction checks clipboard content type
                canPasteNow = true;
            }
            setEnabled(canPasteNow);
        }
    }

    // --- Global Toggle Mic Action Class ---
    private class ToggleMicAction extends AbstractAction {
        public ToggleMicAction(String name) {
            super(name);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            InstructionsPanel currentInstructionsPanel = Chrome.this.instructionsPanel;
            VoiceInputButton micButton = currentInstructionsPanel.getVoiceInputButton();
            if (micButton.isEnabled()) {
                micButton.doClick();
            }
        }

        public void updateEnabledState() {
            InstructionsPanel currentInstructionsPanel = Chrome.this.instructionsPanel;
            VoiceInputButton micButton = currentInstructionsPanel.getVoiceInputButton();
            boolean canToggleMic = micButton.isEnabled();
            setEnabled(canToggleMic);
        }
    }

    /**
     * Creates a new themed JFrame with the Brokk icon set properly.
     *
     * @param title The title for the new frame
     * @return A configured JFrame with the application icon
     */
    public static JFrame newFrame(String title, boolean initializeTitleBar) {
        JFrame frame = new JFrame(title);
        applyIcon(frame);
        // macOS  (see https://www.formdev.com/flatlaf/macos/)
        if (SystemInfo.isMacOS) {
            if (SystemInfo.isMacFullWindowContentSupported) {
                frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
                frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);

                // hide window title
                if (SystemInfo.isJava_17_orLater)
                    frame.getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
                else frame.setTitle(null);
            }
        }
        if (initializeTitleBar) applyTitleBar(frame, title);
        return frame;
    }

    public static JFrame newFrame(String title) {
        return newFrame(title, true);
    }

    /**
     * If using full window content, creates a themed title bar.
     *
     * @see <a href="https://www.formdev.com/flatlaf/macos/">FlatLaf macOS Window Decorations</a>
     */
    public static void applyTitleBar(JFrame frame, String title) {
        if (SystemInfo.isMacOS && SystemInfo.isMacFullWindowContentSupported) {
            var titleBar = new JPanel(new BorderLayout());
            titleBar.setBorder(new EmptyBorder(4, 80, 4, 0)); // Padding for window controls
            var label = new JLabel(title, SwingConstants.CENTER);
            titleBar.add(label, BorderLayout.CENTER);
            frame.add(titleBar, BorderLayout.NORTH);
            // Revalidate layout after dynamically adding title bar
            frame.revalidate();
            frame.repaint();
            titleBar.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) { // Double click
                        if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                            // un-maximize the window
                            frame.setExtendedState(JFrame.NORMAL);
                        } else {
                            // maximize the window
                            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                        }
                    }
                }
            });
        }
    }

    /**
     * Applies the application icon to the given window (JFrame or JDialog).
     *
     * @param window The window to set the icon for.
     */
    public static void applyIcon(Window window) {
        try {
            var iconUrl = Chrome.class.getResource(Brokk.ICON_RESOURCE);
            if (iconUrl != null) {
                var icon = new ImageIcon(iconUrl);
                window.setIconImage(icon.getImage());
            } else {
                LogManager.getLogger(Chrome.class).warn("Could not find resource {}", Brokk.ICON_RESOURCE);
            }
        } catch (Exception e) {
            LogManager.getLogger(Chrome.class).warn("Failed to set application icon for window", e);
        }
    }

    /** Disables the history panel via HistoryOutputPanel. */
    @Override
    public void disableHistoryPanel() {
        historyOutputPanel.disableHistory();
    }

    /** Enables the history panel via HistoryOutputPanel. */
    @Override
    public void enableHistoryPanel() {
        historyOutputPanel.enableHistory();
    }

    /**
     * Sets the blocking state on the MarkdownOutputPanel to prevent clearing/resetting during operations.
     *
     * @param blocked true to prevent clear/reset operations, false to allow them
     */
    @Override
    public void blockLlmOutput(boolean blocked) {
        // Ensure that prev setText calls are processed before blocking => we need the invokeLater
        SwingUtilities.invokeLater(() -> {
            historyOutputPanel.setMarkdownOutputPanelBlocking(blocked);
            // Also propagate task progress state to the WebView so the frontend can react
            historyOutputPanel.setTaskInProgress(blocked);
        });
    }

    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        return showConfirmDialog(frame, message, title, optionType, messageType);
    }

    @Override
    public int showConfirmDialog(
            @Nullable Component parent, String message, String title, int optionType, int messageType) {
        //noinspection MagicConstant
        return JOptionPane.showConfirmDialog(parent, message, title, optionType, messageType);
    }

    @Override
    public void postSummarize() {
        updateWorkspace();
        updateContextHistoryTable();
    }

    @Override
    public void systemNotify(String message, String title, int messageType) {
        SwingUtilities.invokeLater(() -> {
            //noinspection MagicConstant
            JOptionPane.showMessageDialog(frame, message, title, messageType);
        });
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        boolean allowed =
                switch (role) {
                    case COST -> GlobalUiSettings.isShowCostNotifications();
                    case ERROR -> GlobalUiSettings.isShowErrorNotifications();
                    case CONFIRM -> GlobalUiSettings.isShowConfirmNotifications();
                    case INFO -> GlobalUiSettings.isShowInfoNotifications();
                };
        if (!allowed) return;

        SwingUtilities.invokeLater(() -> historyOutputPanel.showNotification(role, message));
    }

    /** Helper method to find JScrollPane component within a container */
    @Nullable
    private static Component findScrollPaneIn(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JScrollPane) {
                return comp;
            } else if (comp instanceof Container subContainer) {
                Component found = findScrollPaneIn(subContainer);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static class SearchableContentPanel extends JPanel implements ThemeAware {
        private final List<JComponent> componentsWithChatBackground;
        private final List<MarkdownOutputPanel> markdownPanels;

        public SearchableContentPanel(
                List<JComponent> componentsWithChatBackground, List<MarkdownOutputPanel> markdownPanels) {
            super(new BorderLayout());
            this.componentsWithChatBackground = componentsWithChatBackground;
            this.markdownPanels = markdownPanels;
        }

        public List<MarkdownOutputPanel> getMarkdownPanels() {
            return markdownPanels;
        }

        @Override
        public void applyTheme(GuiTheme guiTheme) {
            Color newBackgroundColor = ThemeColors.getColor(guiTheme.isDarkTheme(), "chat_background");
            componentsWithChatBackground.forEach(c -> c.setBackground(newBackgroundColor));
            SwingUtilities.updateComponentTreeUI(this);
        }
    }

    /**
     * Updates the git tab badge with the current number of modified files. Should be called whenever the git status
     * changes
     */
    public void updateGitTabBadge(int modifiedCount) {
        assert SwingUtilities.isEventDispatchThread() : "updateGitTabBadge(int) must be called on EDT";

        if (gitTabBadgedIcon == null) {
            return; // No git support
        }

        gitTabBadgedIcon.setCount(modifiedCount, leftTabbedPanel);

        // Update tooltip to show the count
        if (gitTabLabel != null) {
            String tooltip = modifiedCount > 0
                    ? String.format("Commit (%d modified file%s)", modifiedCount, modifiedCount == 1 ? "" : "s")
                    : "Commit";
            gitTabLabel.setToolTipText(tooltip);
        }

        // Repaint the tab to show the updated badge
        if (gitTabLabel != null) {
            gitTabLabel.repaint();
        }
    }

    /**
     * Updates the git tab badge with the current number of modified files. Should be called whenever the git status
     * changes. This version fetches the count itself and should only be used when the count is not already available.
     */
    public void updateGitTabBadge() {
        if (gitTabBadgedIcon == null) {
            return; // No git support
        }

        // Fetch the modified count off-EDT to avoid blocking UI
        contextManager.submitBackgroundTask("Updating git badge", () -> {
            try {
                int modifiedCount = (gitCommitTab == null) ? 0 : gitCommitTab.getThreadSafeCachedModifiedFileCount();
                SwingUtilities.invokeLater(() -> updateGitTabBadge(modifiedCount));
            } catch (Exception e) {
                logger.warn("Error getting modified file count for badge: {}", e.getMessage());
                SwingUtilities.invokeLater(() -> updateGitTabBadge(0));
            }
            return null;
        });
    }

    /** Builds a JLabel for use as a square tab component, ensuring width == height. */
    private static JLabel createSquareTabLabel(Icon icon, String tooltip) {
        var label = new JLabel(icon);
        int size = Math.max(icon.getIconWidth(), icon.getIconHeight());
        // add a little padding so the icon isn't flush against the border
        // tabs are usually a bit width biased, so let's also reduce width a bit
        label.setPreferredSize(new Dimension(size, size + 8));
        label.setMinimumSize(label.getPreferredSize());
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setToolTipText(tooltip);

        // If this is a themed icon wrapper, ask it to ensure its delegate is resolved (non-blocking).
        if (icon instanceof io.github.jbellis.brokk.gui.SwingUtil.ThemedIcon themedIcon) {
            try {
                themedIcon.ensureResolved();
            } catch (Exception ignored) {
                // Defensive: do not let icon resolution errors interrupt UI construction
            }
        }

        // Ensure we repaint when the label becomes showing; some themed icons resolve lazily and
        // a repaint on SHOWING ensures the resolved image is painted immediately (fixes hover-only reveal).
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

    /** Calculates an appropriate initial width for the left sidebar based on content and window size. */
    private int computeInitialSidebarWidth() {
        int ideal = projectFilesPanel.getPreferredSize().width;
        int frameWidth = frame.getWidth();

        // Allow between minimum and maximum percentage based on screen width
        int min = (int) (frameWidth * MIN_SIDEBAR_WIDTH_FRACTION);
        double maxFraction =
                frameWidth > WIDE_SCREEN_THRESHOLD ? MAX_SIDEBAR_WIDTH_FRACTION_WIDE : MAX_SIDEBAR_WIDTH_FRACTION;
        int max = (int) (frameWidth * maxFraction);

        return Math.max(min, Math.min(ideal, max));
    }

    /**
     * Toggle collapsing the Workspace (top of Workspace|Instructions split) and hide/show the divider between Output
     * and the bottom stack.
     */
    public void toggleWorkspaceCollapsed() {
        setWorkspaceCollapsed(!workspaceCollapsed);
    }

    /**
     * Collapse/expand the Workspace area.
     *
     * <p>New behavior: - When collapsed, completely remove the Workspace+Instructions split from the bottom stack and
     * show only the Instructions/Drawer as the bottom component. The Output↔Bottom divider remains visible. - When
     * expanded, restore the original Workspace+Instructions split as the bottom component and restore the previous
     * divider location for the top split (Workspace↔Instructions).
     */
    public void setWorkspaceCollapsed(boolean collapsed) {
        Runnable r = () -> {
            if (this.workspaceCollapsed == collapsed) {
                return;
            }

            if (collapsed) {
                // Also save as a proportion for robust restore after resizes
                try {
                    int t = Math.max(0, topSplitPane.getHeight());
                    if (t > 0) {
                        double p = (double) Math.max(0, topSplitPane.getDividerLocation()) / (double) t;
                        // Clamp to avoid pathological values
                        savedTopSplitProportion = Math.max(0.05, Math.min(0.95, p));
                    }
                } catch (Exception ignored) {
                    // fallback will be used on restore
                }

                // Measure the current on-screen height of the Instructions area so we can keep it EXACT
                int instructionsHeightPx = 0;
                try {
                    // Bottom of the topSplitPane is the Instructions container when expanded
                    Component bottom = topSplitPane.getBottomComponent();
                    if (bottom != null) {
                        instructionsHeightPx = Math.max(0, bottom.getHeight());
                    }
                    // Fallback estimate if height not realized yet
                    if (instructionsHeightPx == 0) {
                        int tsTotal = Math.max(0, topSplitPane.getHeight());
                        int tsDivider = topSplitPane.getDividerSize();
                        int maxFromTop = Math.max(0, tsTotal - tsDivider);
                        int minBottom = (bottom != null) ? Math.max(0, bottom.getMinimumSize().height) : 0;
                        instructionsHeightPx = Math.min(
                                Math.max(minBottom, instructionsDrawerSplit.getMinimumSize().height), maxFromTop);
                    }
                } catch (Exception ignored) {
                    // Defensive; we'll clamp during restore regardless
                }
                // Pin the measured Instructions height for exact restore later
                pinnedInstructionsHeightPx = instructionsHeightPx;

                // Swap to Instructions-only in the bottom
                mainVerticalSplitPane.setBottomComponent(instructionsDrawerSplit);

                // Revalidate layout, then set the main divider so bottom == pinned Instructions height
                mainVerticalSplitPane.revalidate();
                SwingUtilities.invokeLater(
                        () -> applyMainDividerForExactBottomHeight(Math.max(0, pinnedInstructionsHeightPx)));

                this.workspaceCollapsed = true;
            } else {
                // Ensure the workspace split bottom points to the instructions drawer, then restore it as bottom
                try {
                    topSplitPane.setBottomComponent(instructionsDrawerSplit);
                } catch (Exception ignored) {
                    // If it's already set due to prior operations, ignore
                }
                mainVerticalSplitPane.setBottomComponent(topSplitPane);

                // Revalidate the top split, then restore Workspace and bottom height exactly
                topSplitPane.revalidate();
                SwingUtilities.invokeLater(() -> {
                    // Choose saved proportion; fall back to DEFAULT if missing
                    double p = (savedTopSplitProportion > 0.0 && savedTopSplitProportion < 1.0)
                            ? savedTopSplitProportion
                            : DEFAULT_WORKSPACE_INSTRUCTIONS_SPLIT;
                    // Clamp proportion
                    p = Math.max(0.05, Math.min(0.95, p));

                    // Compute the target bottom height so that Instructions stays at pinnedInstructionsHeightPx
                    // For a vertical JSplitPane: bottomHeight = (1 - p) * T - dividerSize  =>  T = (pinned +
                    // dividerSize) / (1 - p)
                    int dividerSizeTS = topSplitPane.getDividerSize();
                    int pinned = Math.max(0, pinnedInstructionsHeightPx);
                    int desiredBottom = (int) Math.round((pinned + dividerSizeTS) / Math.max(0.05, (1.0 - p)));

                    // Set the main Output↔Bottom divider so bottom == desiredBottom (clamped)
                    applyMainDividerForExactBottomHeight(desiredBottom);

                    // Finally set the top split divider by proportion to restore Workspace share
                    try {
                        topSplitPane.setDividerLocation(p);
                    } catch (Exception ignored) {
                        // ignore
                    }
                });

                this.workspaceCollapsed = false;
            }

            // Refresh layout/paint
            mainVerticalSplitPane.revalidate();
            mainVerticalSplitPane.repaint();

            // Persist collapsed/expanded state (per-project + global)
            try {
                saveWorkspaceCollapsedSetting(this.workspaceCollapsed);
            } catch (Exception ignored) {
                // Non-fatal persistence failure
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /**
     * Set the Output↔Bottom divider so that the bottom height equals the exact desired pixel height, clamped to the
     * bottom component's minimum size. This is used when collapsing Workspace to guarantee the Instructions area does
     * not resize at all.
     */
    private void applyMainDividerForExactBottomHeight(int desiredBottomPx) {
        int total = mainVerticalSplitPane.getHeight();
        if (total <= 0) {
            return;
        }
        int dividerSize = mainVerticalSplitPane.getDividerSize();
        java.awt.Component bottom = mainVerticalSplitPane.getBottomComponent();
        int minBottom = (bottom != null) ? Math.max(0, bottom.getMinimumSize().height) : 0;

        int target = Math.max(0, desiredBottomPx);
        int minAllowed = minBottom;
        int maxAllowed = Math.max(minAllowed, total - dividerSize); // cannot exceed available space
        int clampedBottom = Math.max(minAllowed, Math.min(target, maxAllowed));

        int safeDivider = Math.max(0, total - dividerSize - clampedBottom);
        mainVerticalSplitPane.setDividerLocation(safeDivider);
    }

    /** Updates the terminal font size for all active terminals. */
    public void updateTerminalFontSize() {
        SwingUtilities.invokeLater(() -> terminalDrawer.updateTerminalFontSize());
    }

    @Override
    public BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        var dialog = requireNonNull(SwingUtil.runOnEdt(() -> new BlitzForgeProgressDialog(this, cancelCallback), null));
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        return dialog;
    }
}
