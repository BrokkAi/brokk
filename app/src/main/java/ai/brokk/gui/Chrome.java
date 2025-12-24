package ai.brokk.gui;

import static ai.brokk.gui.Constants.*;
import static java.util.Objects.requireNonNull;

import ai.brokk.Brokk;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.agents.BlitzForge;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.components.SpinnerIconUtil;
import ai.brokk.gui.dependencies.DependenciesPanel;
import ai.brokk.gui.dialogs.BlitzForgeProgressDialog;
import ai.brokk.gui.dialogs.SettingsDialog;
import ai.brokk.gui.git.GitCommitTab;
import ai.brokk.gui.git.GitHistoryTab;
import ai.brokk.gui.git.GitIssuesTab;
import ai.brokk.gui.git.GitLogTab;
import ai.brokk.gui.git.GitPullRequestsTab;
import ai.brokk.gui.git.GitWorktreeTab;
import ai.brokk.gui.mop.MarkdownOutputPanel;
import ai.brokk.gui.mop.MarkdownOutputPool;
import ai.brokk.gui.terminal.TaskListPanel;
import ai.brokk.gui.tests.FileBasedTestRunsStore;
import ai.brokk.gui.tests.TestRunnerPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.theme.ThemeTitleBarManager;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.init.onboarding.BuildSettingsStep;
import ai.brokk.init.onboarding.GitConfigStep;
import ai.brokk.init.onboarding.MigrationStep;
import ai.brokk.init.onboarding.OnboardingOrchestrator;
import ai.brokk.init.onboarding.OnboardingStep;
import ai.brokk.init.onboarding.PostGitStyleRegenerationStep;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.util.*;
import com.formdev.flatlaf.util.SystemInfo;
import com.formdev.flatlaf.util.UIScale;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class Chrome
        implements AutoCloseable, IConsoleIO, IContextManager.ContextListener, IContextManager.AnalyzerCallback {
    private static final Logger logger = LogManager.getLogger(Chrome.class);

    // Track open Chrome instances for window cascading
    private static final Set<Chrome> openInstances = ConcurrentHashMap.newKeySet();

    // Default layout proportions - can be overridden by saved preferences
    private static final double MIN_SIDEBAR_WIDTH_FRACTION = 0.12; // 12% minimum sidebar width
    private static final int MIN_SIDEBAR_WIDTH_PX = 220; // absolute minimum sidebar width for usability
    private static final double MAX_SIDEBAR_WIDTH_FRACTION = 0.40; // 40% maximum sidebar width (normal screens)
    private static final double MAX_SIDEBAR_WIDTH_FRACTION_WIDE = 0.25; // 25% maximum sidebar width (wide screens)
    private static final int WIDE_SCREEN_THRESHOLD = 2000; // Screen width threshold for wide screen layout
    private static final int SIDEBAR_COLLAPSED_THRESHOLD = 50;

    // Used as the default text for the background tasks label
    private final String BGTASK_EMPTY = "No background tasks";

    // Preview management
    private final PreviewManager previewManager;

    // Per-Chrome instance dialog tracking to support multiple Chrome windows with independent dialogs
    private final Map<String, JDialog> openDialogs = new ConcurrentHashMap<>();

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

    // Store original divider size for hiding/showing divider
    private int originalBottomDividerSize;

    // Swing components:
    final JFrame frame;
    private JLabel backgroundStatusLabel;
    private final JPanel mainPanel;

    private final RightPanel rightPanel;
    private final ToolsPane toolsPane;
    private final JSplitPane leftVerticalSplitPane; // Left: tabs (top) + file history (bottom)
    private final JTabbedPane fileHistoryPane; // Bottom area for file history
    private int originalLeftVerticalDividerSize;

    /**
     * Horizontal split between left tab stack and right output stack
     */
    private JSplitPane horizontalSplitPane;

    // Panels:
    private final WorkspacePanel workspacePanel;
    private final ProjectFilesPanel projectFilesPanel; // New panel for project files
    private final TestRunnerPanel testRunnerPanel;
    private final DependenciesPanel dependenciesPanel;

    // For GitHistoryTab instances opened as top-level tabs
    private final Map<String, GitHistoryTab> fileHistoryTabs = new HashMap<>();

    // Caches the last branch string we applied to InstructionsPanel to avoid redundant UI refreshes
    @Nullable
    private String lastDisplayedBranchLabel = null;

    // Reference to Tools ▸ BlitzForge... menu item so we can enable/disable it
    @SuppressWarnings("NullAway.Init") // Initialized by MenuBar after constructor
    private JMenuItem blitzForgeMenuItem;

    /**
     * Default constructor sets up the UI.
     */
    @SuppressWarnings("NullAway.Init") // For complex Swing initialization patterns
    public Chrome(ContextManager contextManager) {
        assert SwingUtilities.isEventDispatchThread() : "Chrome constructor must run on EDT";
        this.contextManager = contextManager;
        this.previewManager = new PreviewManager(this);
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

        // Initialize theme manager before creating UI components that may need it
        initializeThemeManager();

        // Create encapsulated build stack
        rightPanel = new RightPanel(this, contextManager);
        // Wire up the scroll pane now that buildPane exists
        themeManager.setMainScrollPane(rightPanel.getHistoryOutputPanel().getLlmScrollPane());

        // Bottom Area: Context/Git + Status
        this.mainPanel = new JPanel(new BorderLayout());
        // Status labels at the very bottom
        // System message label (left side)

        // Background status label (right side)
        backgroundStatusLabel = new JLabel(BGTASK_EMPTY);
        backgroundStatusLabel.setBorder(new EmptyBorder(V_GLUE, H_GAP, V_GLUE, H_PAD));

        // Initialize shared analyzer rebuild status strip before first use
        // MUST be initialized before being added to statusPanel to avoid NullPointerException.
        this.analyzerStatusStrip = new AnalyzerStatusStrip();
        this.analyzerStatusStrip.setVisible(false);

        // Panel to hold both labels
        var statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(getAnalyzerStatusStrip(), BorderLayout.WEST);
        statusPanel.add(backgroundStatusLabel, BorderLayout.EAST);

        var statusLabels = (JComponent) statusPanel;
        this.mainPanel.add(statusLabels, BorderLayout.SOUTH);
        // Center of bottomPanel will be filled in onComplete based on git presence

        gbc.weighty = 1.0;
        gbc.gridy = 0;
        contentPanel.add(this.mainPanel, gbc);

        mainPanel.add(contentPanel, BorderLayout.CENTER);
        frame.add(mainPanel, BorderLayout.CENTER);

        // Initialize global undo/redo actions now that instructionsPanel is available
        // contextManager is also available (passed in constructor)
        // contextPanel and historyOutputPanel will be null until onComplete
        this.globalUndoAction = new GlobalUndoAction("Undo");
        this.globalRedoAction = new GlobalRedoAction("Redo");
        this.globalCopyAction = new GlobalCopyAction("Copy");
        this.globalPasteAction = new GlobalPasteAction("Paste");
        this.globalToggleMicAction = new ToggleMicAction("Toggle Microphone");

        // Defer restoring window size and divider positions until after
        // all split panes are fully constructed.
        var rootPath = getProject().getRoot();
        var title = "%s (%s)".formatted(rootPath.getFileName(), rootPath.getParent());
        frame.setTitle(title);

        // Show initial system message
        showNotification(
                NotificationRole.INFO, "Opening project at " + getProject().getRoot());

        // Test runner persistence and panel
        var brokkDir = getProject().getRoot().resolve(AbstractProject.BROKK_DIR);
        var testRunsStore = new FileBasedTestRunsStore(brokkDir.resolve("test_runs.json"));
        this.testRunnerPanel = new TestRunnerPanel(this, testRunsStore);

        // Create workspace panel, dependencies panel, and project files panel
        workspacePanel = new WorkspacePanel(this, contextManager);
        dependenciesPanel = new DependenciesPanel(this);
        projectFilesPanel = new ProjectFilesPanel(this, contextManager, dependenciesPanel);

        toolsPane = new ToolsPane(this, contextManager, projectFilesPanel, testRunnerPanel);

        // Register all listeners after all components are initialized
        registerAllListeners();

        if (getProject().hasGit()) {
            // Initial refreshes are now done in the background
            contextManager.submitBackgroundTask("Loading project state", () -> {
                updateGitRepo();
                projectFilesPanel.requestUpdate();
                return null;
            });
        }

        // 3) Final horizontal split: left tabs | right stack
        horizontalSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        // Create a vertical split on the left: top = regular tabs, bottom = per-file history tabs
        fileHistoryPane = new JTabbedPane();
        fileHistoryPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT); // keep single row; scroll horizontally
        fileHistoryPane.setVisible(false); // hidden until a history tab is added

        leftVerticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        leftVerticalSplitPane.setTopComponent(toolsPane);
        leftVerticalSplitPane.setBottomComponent(fileHistoryPane);
        leftVerticalSplitPane.setResizeWeight(0.7); // top gets most space by default
        // Ensure the entire left stack (tabs + per-file history) honors the minimum sidebar width
        leftVerticalSplitPane.setMinimumSize(new Dimension(MIN_SIDEBAR_WIDTH_PX, 0));
        originalLeftVerticalDividerSize = leftVerticalSplitPane.getDividerSize();
        leftVerticalSplitPane.setDividerSize(0); // hide divider when no history is shown

        horizontalSplitPane.setLeftComponent(leftVerticalSplitPane);
        horizontalSplitPane.setRightComponent(rightPanel);
        // Let the left side drive the minimum width for the whole sidebar region
        // Ensure the right stack can shrink enough so the sidebar can grow
        rightPanel.setMinimumSize(new Dimension(200, 0));
        // Left panel keeps its preferred width; right panel takes the remaining space
        horizontalSplitPane.setResizeWeight(0.0);
        int tempDividerLocation = 300; // Reasonable default that will be recalculated
        horizontalSplitPane.setDividerLocation(tempDividerLocation);
        // Initialize the remembered expanded location (will be updated later)
        toolsPane.setLastExpandedSidebarLocation(tempDividerLocation);

        // Store original divider size
        originalBottomDividerSize = horizontalSplitPane.getDividerSize();

        this.mainPanel.add(horizontalSplitPane, BorderLayout.CENTER);

        // Force layout update for the bottom panel
        this.mainPanel.revalidate();
        this.mainPanel.repaint();

        // Set initial enabled state for global actions after all components are ready
        this.globalUndoAction.updateEnabledState();
        this.globalRedoAction.updateEnabledState();
        this.globalCopyAction.updateEnabledState();
        this.globalPasteAction.updateEnabledState();

        // Build menu (now that everything else is ready)
        frame.setJMenuBar(MenuBar.buildMenuBar(this));

        // Register global keyboard shortcuts now that actions are fully initialized
        registerGlobalKeyboardShortcuts();

        // Set up focus traversal with individual components for granular navigation
        var focusOrder = List.<Component>of(
                rightPanel.getInstructionsPanel().getInstructionsArea(),
                rightPanel.getInstructionsPanel().getModelSelectorComponent(),
                rightPanel.getInstructionsPanel().getMicButton(),
                rightPanel.getInstructionsPanel().getWandButton(),
                rightPanel.getInstructionsPanel().getHistoryDropdown(),
                rightPanel.getTaskListPanel().getGoStopButton(),
                rightPanel.getTaskListPanel().getTaskInput(),
                rightPanel.getTaskListPanel().getTaskList(),
                projectFilesPanel.getSearchField(),
                projectFilesPanel.getRefreshButton(),
                projectFilesPanel.getProjectTree(),
                dependenciesPanel.getDependencyTable(),
                dependenciesPanel.getAddButton(),
                dependenciesPanel.getRemoveButton(),
                rightPanel.getHistoryOutputPanel().getHistoryTable(),
                rightPanel.getHistoryOutputPanel().getLlmStreamArea());
        frame.setFocusTraversalPolicy(new ChromeFocusTraversalPolicy(focusOrder));
        frame.setFocusCycleRoot(true);
        frame.setFocusTraversalPolicyProvider(true);

        // Complete all layout operations synchronously before showing window
        completeLayoutSynchronously();
        applyVerticalActivityLayout();

        // Final validation and repaint before making window visible
        frame.validate();
        frame.repaint();

        // Apply Advanced Mode visibility at startup so default (easy mode) hides advanced UI
        try {
            applyAdvancedModeVisibility();
            rightPanel.getInstructionsPanel().applyAdvancedModeForInstructions(GlobalUiSettings.isAdvancedMode());
        } catch (Exception ex) {
            logger.debug("applyAdvancedModeVisibility at startup failed (non-fatal)", ex);
        }

        updateWorkspace();
        updateContextHistoryTable();

        // Now show the window with complete layout
        frame.setVisible(true);

        SwingUtilities.invokeLater(() -> MarkdownOutputPool.instance());

        // Defer .gitignore check until initialization completes
        scheduleGitConfigurationAfterInit();

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
        return (AbstractProject) contextManager.getProject();
    }

    private void initializeThemeManager() {
        logger.trace("Initializing theme manager");
        // Initialize theme manager with null scroll pane initially to break circular dependency with buildPane
        themeManager = new GuiTheme(frame, null, this);

        // Apply current theme and wrap mode based on global settings
        String currentTheme = MainProject.getTheme();
        logger.trace("Applying theme from project settings: {}", currentTheme);
        boolean wrapMode = MainProject.getCodeBlockWrapMode();
        switchThemeAndWrapMode(currentTheme, wrapMode);
    }

    /**
     * Lightweight method to preview a context without updating history Only updates the LLM text area and context panel
     * display
     */
    public void setContext(Context ctx) {
        final boolean updateOutput = (!activeContext.equals(ctx) && !contextManager.isTaskScopeInProgress());
        activeContext = ctx;
        SwingUtilities.invokeLater(() -> {
            workspacePanel.populateContextTable(ctx);
            rightPanel.getTaskListPanel().contextChanged(ctx);
            // Determine if the current context (ctx) is the latest one in the history
            boolean isEditable;
            Context latestContext = contextManager.getContextHistory().liveContext();
            isEditable = latestContext.equals(ctx);
            // workspacePanel is a final field initialized in the constructor, so it won't be null here.
            workspacePanel.setWorkspaceEditable(isEditable);
            // Toggle read-only state for InstructionsPanel UI (chips + token bar)
            rightPanel.getInstructionsPanel().setContextReadOnly(!isEditable);
            // Also update instructions panel (token bar/chips) to reflect the selected context and read-only state
            rightPanel.getInstructionsPanel().contextChanged(ctx);

            // only update the MOP when no task is in progress
            // otherwise the TaskScope.append() will take care of it
            if (updateOutput) {
                var taskHistory = ctx.getTaskHistory();
                if (taskHistory.isEmpty()) {
                    rightPanel.getHistoryOutputPanel().clearLlmOutput();
                } else {
                    var historyTasks = taskHistory.subList(0, taskHistory.size() - 1);
                    var mainTask = taskHistory.getLast();
                    rightPanel.getHistoryOutputPanel().setLlmAndHistoryOutput(historyTasks, mainTask);
                }
            }

            updateCaptureButtons();
        });
    }

    // Theme manager and constants
    private GuiTheme themeManager;

    // Shared analyzer rebuild status strip
    private final AnalyzerStatusStrip analyzerStatusStrip;

    public void switchTheme(boolean isDark) {
        themeManager.applyTheme(isDark);
    }

    public void switchTheme(String themeName) {
        boolean wordWrap = MainProject.getCodeBlockWrapMode();
        themeManager.applyTheme(themeName, wordWrap);
    }

    public void switchThemeAndWrapMode(boolean isDark, boolean wordWrap) {
        themeManager.applyTheme(isDark, wordWrap);
    }

    public void switchThemeAndWrapMode(String themeName, boolean wordWrap) {
        themeManager.applyTheme(themeName, wordWrap);
    }

    public GuiTheme getTheme() {
        return themeManager;
    }

    @Override
    public List<ChatMessage> getLlmRawMessages() {
        if (SwingUtilities.isEventDispatchThread()) {
            return rightPanel.getHistoryOutputPanel().getLlmRawMessages();
        }

        while (true) {
            try {
                SwingUtilities.invokeAndWait(() -> {});
                final CompletableFuture<List<ChatMessage>> future = new CompletableFuture<>();
                SwingUtilities.invokeAndWait(
                        () -> future.complete(rightPanel.getHistoryOutputPanel().getLlmRawMessages()));
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

    /**
     * Retrieves the current text from the command input.
     */
    public String getInputText() {
        return rightPanel.getInstructionsPanel().getInstructions();
    }

    @Override
    public void disableActionButtons() {
        SwingUtil.runOnEdt(() -> {
            disableHistoryPanel();
            rightPanel.getInstructionsPanel().disableButtons();
            rightPanel.getTaskListPanel().disablePlay();
            if (toolsPane.getGitCommitTab() != null) {
                toolsPane.getGitCommitTab().disableButtons();
            }
            blitzForgeMenuItem.setEnabled(false);
        });
    }

    @Override
    public void enableActionButtons() {
        SwingUtil.runOnEdt(() -> {
            rightPanel.getInstructionsPanel().enableButtons();
            rightPanel.getTaskListPanel().enablePlay();
            if (toolsPane.getGitCommitTab() != null) {
                toolsPane.getGitCommitTab().enableButtons();
            }
            blitzForgeMenuItem.setEnabled(true);
        });
    }

    @Override
    public void updateCommitPanel() {
        if (toolsPane.getGitCommitTab() != null) {
            toolsPane.getGitCommitTab().requestUpdate();
        }
    }

    @Override
    public void updateGitRepo() {
        assert !SwingUtilities.isEventDispatchThread() : "Long running git refresh running on the EDT";
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
            // Only refresh branch UI if it actually changed (avoid redundant UI updates)
            boolean branchUiNeedsRefresh =
                    lastDisplayedBranchLabel == null || !lastDisplayedBranchLabel.equals(display);
            if (branchUiNeedsRefresh) {
                try {
                    refreshBranchUi(display);
                    lastDisplayedBranchLabel = display;
                } catch (Exception ex) {
                    logger.warn("updateGitRepo: failed to refresh InstructionsPanel branch UI: {}", ex.getMessage());
                }
            } else {
                logger.trace("updateGitRepo: branch unchanged ({}), skipping branch UI refresh", display);
            }
            // Continue to update git panels even if branch unchanged (e.g., new commits)
        }

        // Update individual Git-related panels and log what is being updated
        if (toolsPane.getGitCommitTab() != null) {
            logger.trace("updateGitRepo: updating GitCommitTab");
            toolsPane.getGitCommitTab().requestUpdate();
        }

        if (toolsPane.getGitLogTab() != null) {
            logger.trace("updateGitRepo: updating GitLogTab");
            toolsPane.getGitLogTab().requestUpdate();
        }

        if (toolsPane.getGitWorktreeTab() != null) {
            logger.trace("updateGitRepo: refreshing GitWorktreeTab");
            toolsPane.getGitWorktreeTab().requestUpdate();
        }

        logger.trace("updateGitRepo: updating ProjectFilesPanel");
        projectFilesPanel.requestUpdate();

        // Ensure the Changes tab reflects the current repo/branch state
        rightPanel.requestReviewUpdate();

        logger.trace("updateGitRepo: finished");
    }

    /**
     * Executes a set of test files and streams the output to the test runner panel.
     */
    public void runTests(Set<ProjectFile> testFiles) throws InterruptedException {
        SwingUtilities.invokeLater(toolsPane::selectTestsTab);
        testRunnerPanel.runTests(testFiles);
    }

    /**
     * Recreate the top-level Issues panel (e.g. after provider change).
     */
    public void recreateIssuesPanel() {
        SwingUtilities.invokeLater(() -> toolsPane.recreateIssuesPanel(contextManager));
    }

    private void registerGlobalKeyboardShortcuts() {
        var rootPane = frame.getRootPane();

        // Cmd/Ctrl+Z => undo (configurable)
        KeyStroke undoKeyStroke = GlobalUiSettings.getKeybinding(
                "global.undo",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, undoKeyStroke, "globalUndo");
        rootPane.getActionMap().put("globalUndo", globalUndoAction);

        // Cmd/Ctrl+Shift+Z (or Cmd/Ctrl+Y) => redo
        KeyStroke redoKeyStroke = GlobalUiSettings.getKeybinding(
                "global.redo",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_Z,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        // For Windows/Linux, Ctrl+Y is also common for redo
        KeyStroke redoYKeyStroke = GlobalUiSettings.getKeybinding(
                "global.redoY",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));

        bindKey(rootPane, redoKeyStroke, "globalRedo");
        bindKey(rootPane, redoYKeyStroke, "globalRedo");
        rootPane.getActionMap().put("globalRedo", globalRedoAction);

        // Cmd/Ctrl+C => global copy
        KeyStroke copyKeyStroke = GlobalUiSettings.getKeybinding(
                "global.copy",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, copyKeyStroke, "globalCopy");
        rootPane.getActionMap().put("globalCopy", globalCopyAction);

        // Cmd/Ctrl+V => global paste
        KeyStroke pasteKeyStroke = GlobalUiSettings.getKeybinding(
                "global.paste",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, pasteKeyStroke, "globalPaste");
        rootPane.getActionMap().put("globalPaste", globalPasteAction);

        // Cmd/Ctrl+L => toggle microphone
        KeyStroke toggleMicKeyStroke = GlobalUiSettings.getKeybinding(
                "global.toggleMicrophone", KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_L));
        bindKey(rootPane, toggleMicKeyStroke, "globalToggleMic");
        rootPane.getActionMap().put("globalToggleMic", globalToggleMicAction);

        // Submit action (configurable; default Cmd/Ctrl+Enter) - only when instructions area is focused
        KeyStroke submitKeyStroke = GlobalUiSettings.getKeybinding(
                "instructions.submit",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        // Bind directly to instructions area instead of globally to avoid interfering with other components
        var ip = rightPanel.getInstructionsPanel();
        ip.getInstructionsArea().getInputMap(JComponent.WHEN_FOCUSED).put(submitKeyStroke, "submitAction");
        ip.getInstructionsArea().getActionMap().put("submitAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        ip.onActionButtonPressed();
                    } catch (Exception ex) {
                        logger.error("Error executing submit action", ex);
                    }
                });
            }
        });

        // Cmd/Ctrl+M => toggle Code/Answer mode (configurable; only in Advanced Mode)
        if (GlobalUiSettings.isAdvancedMode()) {
            KeyStroke toggleModeKeyStroke = GlobalUiSettings.getKeybinding(
                    "instructions.toggleMode", KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_M));
            bindKey(rootPane, toggleModeKeyStroke, "toggleCodeAnswer");
            rootPane.getActionMap().put("toggleCodeAnswer", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // Defensive guard: protects against race conditions during live mode switching.
                    // Even though this binding is only registered in Advanced Mode, refreshKeybindings()
                    // might have a timing window where the old binding is still active after switching to EZ mode.
                    if (!GlobalUiSettings.isAdvancedMode()) {
                        return;
                    }
                    try {
                        ip.toggleCodeAnswerMode();
                        showNotification(NotificationRole.INFO, "Toggled Code/Ask mode");
                    } catch (Exception ex) {
                        logger.warn("Error toggling Code/Answer mode via shortcut", ex);
                    }
                }
            });
        }

        // Open Settings (configurable; default Cmd/Ctrl+,)
        KeyStroke openSettingsKeyStroke = GlobalUiSettings.getKeybinding(
                "global.openSettings", KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_COMMA));
        bindKey(rootPane, openSettingsKeyStroke, "openSettings");
        rootPane.getActionMap().put("openSettings", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(() -> MenuBar.openSettingsDialog(Chrome.this));
            }
        });

        // Close Window (configurable; default Cmd/Ctrl+W; never allow bare ESC)
        KeyStroke closeWindowKeyStroke = GlobalUiSettings.getKeybinding(
                "global.closeWindow", KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_W));
        if (closeWindowKeyStroke.getKeyCode() == KeyEvent.VK_ESCAPE && closeWindowKeyStroke.getModifiers() == 0) {
            closeWindowKeyStroke = KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_W);
        }
        bindKey(rootPane, closeWindowKeyStroke, "closeMainWindow");
        rootPane.getActionMap().put("closeMainWindow", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            }
        });

        // Register IntelliJ-style shortcuts for switching sidebar panels

        // Alt/Cmd+1 for Project Files
        KeyStroke switchToProjectFiles = GlobalUiSettings.getKeybinding(
                "panel.switchToProjectFiles", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_1));
        bindKey(rootPane, switchToProjectFiles, "switchToProjectFiles");
        rootPane.getActionMap().put("switchToProjectFiles", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toolsPane.getToolsPane().setSelectedIndex(0); // Project Files is always at index 0
            }
        });

        // Alt/Cmd+2 for Tests panel
        KeyStroke switchToTests = GlobalUiSettings.getKeybinding(
                "panel.switchToTests", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_2));
        bindKey(rootPane, switchToTests, "switchToTests");
        rootPane.getActionMap().put("switchToTests", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                var tools = toolsPane.getToolsPane();
                var idx = tools.indexOfComponent(testRunnerPanel);
                if (idx != -1) tools.setSelectedIndex(idx);
            }
        });

        // Alt/Cmd+3 for Changes (GitCommitTab)
        if (toolsPane.getGitCommitTab() != null) {
            KeyStroke switchToChanges = GlobalUiSettings.getKeybinding(
                    "panel.switchToChanges", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_3));
            bindKey(rootPane, switchToChanges, "switchToChanges");
            rootPane.getActionMap().put("switchToChanges", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var tools = toolsPane.getToolsPane();
                    var idx = tools.indexOfComponent(toolsPane.getGitCommitTab());
                    if (idx != -1) tools.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+4 for Log
        if (toolsPane.getGitLogTab() != null) {
            KeyStroke switchToLog = GlobalUiSettings.getKeybinding(
                    "panel.switchToLog", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_4));
            bindKey(rootPane, switchToLog, "switchToLog");
            rootPane.getActionMap().put("switchToLog", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var tools = toolsPane.getToolsPane();
                    var idx = tools.indexOfComponent(toolsPane.getGitLogTab());
                    if (idx != -1) tools.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+5 for Worktrees
        if (toolsPane.getGitWorktreeTab() != null) {
            KeyStroke switchToWorktrees = GlobalUiSettings.getKeybinding(
                    "panel.switchToWorktrees", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_5));
            bindKey(rootPane, switchToWorktrees, "switchToWorktrees");
            rootPane.getActionMap().put("switchToWorktrees", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var tools = toolsPane.getToolsPane();
                    var idx = tools.indexOfComponent(toolsPane.getGitWorktreeTab());
                    if (idx != -1) tools.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+6 for Pull Requests panel (if available)
        if (toolsPane.getPullRequestsPanel() != null) {
            KeyStroke switchToPR = GlobalUiSettings.getKeybinding(
                    "panel.switchToPullRequests", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_6));
            bindKey(rootPane, switchToPR, "switchToPullRequests");
            rootPane.getActionMap().put("switchToPullRequests", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var tools = toolsPane.getToolsPane();
                    var idx = tools.indexOfComponent(toolsPane.getPullRequestsPanel());
                    if (idx != -1) tools.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+7 for Issues panel (if available)
        if (toolsPane.getIssuesPanel() != null) {
            KeyStroke switchToIssues = GlobalUiSettings.getKeybinding(
                    "panel.switchToIssues", KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_7));
            bindKey(rootPane, switchToIssues, "switchToIssues");
            rootPane.getActionMap().put("switchToIssues", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var tools = toolsPane.getToolsPane();
                    var idx = tools.indexOfComponent(toolsPane.getIssuesPanel());
                    if (idx != -1) tools.setSelectedIndex(idx);
                }
            });
        }

        // Drawer navigation shortcuts
        // Cmd/Ctrl+Shift+T => toggle terminal drawer
        KeyStroke toggleTerminalDrawerKeyStroke = GlobalUiSettings.getKeybinding(
                "drawer.toggleTerminal",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_T,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        bindKey(rootPane, toggleTerminalDrawerKeyStroke, "toggleTerminalDrawer");
        rootPane.getActionMap().put("toggleTerminalDrawer", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Terminal drawer removed; instead, switch to the Terminal tab if present.
                SwingUtilities.invokeLater(rightPanel::selectTerminalTab);
            }
        });

        // Cmd/Ctrl+T => switch to terminal tab
        KeyStroke switchToTerminalTabKeyStroke = GlobalUiSettings.getKeybinding(
                "drawer.switchToTerminal",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, switchToTerminalTabKeyStroke, "switchToTerminalTab");
        rootPane.getActionMap().put("switchToTerminalTab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(rightPanel::selectTerminalTab);
            }
        });

        // Cmd/Ctrl+K => switch to tasks tab
        KeyStroke switchToTasksTabKeyStroke = GlobalUiSettings.getKeybinding(
                "drawer.switchToTasks",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_K, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindKey(rootPane, switchToTasksTabKeyStroke, "switchToTasksTab");
        rootPane.getActionMap().put("switchToTasksTab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(rightPanel::selectTasksTab);
            }
        });

        // Workspace actions
        // Ctrl/Cmd+Shift+I => attach context (add content to workspace)
        KeyStroke attachContextKeyStroke = GlobalUiSettings.getKeybinding(
                "workspace.attachContext", KeyboardShortcutUtil.createPlatformShiftShortcut(KeyEvent.VK_I));
        bindKey(rootPane, attachContextKeyStroke, "attachContext");
        rootPane.getActionMap().put("attachContext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(() -> getContextPanel().attachContextViaDialog());
            }
        });

        // Ctrl+I => attach files and summarize
        KeyStroke attachFilesAndSummarizeKeyStroke = GlobalUiSettings.getKeybinding(
                "workspace.attachFilesAndSummarize", KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK));
        bindKey(rootPane, attachFilesAndSummarizeKeyStroke, "attachFilesAndSummarize");
        rootPane.getActionMap().put("attachFilesAndSummarize", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(() -> getContextPanel().attachContextViaDialog(true));
            }
        });

        // Zoom shortcuts: read from global settings (defaults preserved)
        KeyStroke zoomInKeyStroke = GlobalUiSettings.getKeybinding(
                "view.zoomIn",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_PLUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        KeyStroke zoomInEqualsKeyStroke = GlobalUiSettings.getKeybinding(
                "view.zoomInAlt",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_EQUALS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        KeyStroke zoomOutKeyStroke = GlobalUiSettings.getKeybinding(
                "view.zoomOut",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_MINUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        KeyStroke resetZoomKeyStroke = GlobalUiSettings.getKeybinding(
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
                rightPanel.getHistoryOutputPanel().getLlmStreamArea().zoomIn();
            }
        });

        rootPane.getActionMap().put("zoomOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Use MOP webview zoom for global zoom functionality
                rightPanel.getHistoryOutputPanel().getLlmStreamArea().zoomOut();
            }
        });

        rootPane.getActionMap().put("resetZoom", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Use MOP webview zoom for global zoom functionality
                rightPanel.getHistoryOutputPanel().getLlmStreamArea().resetZoom();
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
        if (SwingUtilities.isEventDispatchThread()) {
            rightPanel.getHistoryOutputPanel().appendLlmOutput(token, type, isNewMessage, isReasoning);
        } else {
            SwingUtilities.invokeLater(
                    () -> rightPanel.getHistoryOutputPanel().appendLlmOutput(token, type, isNewMessage, isReasoning));
        }
    }

    @Override
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry main) {
        SwingUtilities.invokeLater(() -> rightPanel.getHistoryOutputPanel().setLlmAndHistoryOutput(history, main));
    }

    @Override
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        if (SwingUtilities.isEventDispatchThread()) {
            rightPanel.getHistoryOutputPanel().prepareOutputForNextStream(history);
        } else {
            try {
                SwingUtilities.invokeAndWait(
                        () -> rightPanel.getHistoryOutputPanel().prepareOutputForNextStream(history));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (InvocationTargetException e) {
                logger.error("Error preparing output for next stream", e);
            }
        }
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

    private void registerAllListeners() {
        // 1. Context and File Listeners
        contextManager.addContextListener(this);
        contextManager.addFileChangeListener(changedFiles -> {
            // Refresh preview windows when tracked files change
            Set<ProjectFile> openPreviewFiles =
                    new HashSet<>(previewManager.getProjectFileToPreviewWindow().keySet());
            openPreviewFiles.retainAll(changedFiles);
            if (!openPreviewFiles.isEmpty()) {
                refreshPreviewsForFiles(openPreviewFiles);
            }
        });

        // 2. Analyzer Callbacks
        contextManager.addAnalyzerCallback(new IContextManager.AnalyzerCallback() {
            @Override
            public void onAnalyzerReady() {
                getProject().getMainProject().getDependencyUpdateScheduler().onAnalyzerReady();
            }
        });

        // 3. UI Component Listeners
        dependenciesPanel.addDependencyStateChangeListener(this::updateProjectFilesTabBadge);

        // 4. Focus and Action State Management
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", evt -> {
            Component oldFocusOwner = (Component) evt.getOldValue();
            Component newFocusOwner = (Component) evt.getNewValue();

            // Apply global focus highlighting
            applyFocusHighlighting(oldFocusOwner, newFocusOwner);

            // Update lastRelevantFocusOwner only if the new focus owner is one of our primary targets
            if (newFocusOwner != null) {
                var hop = rightPanel.getHistoryOutputPanel();
                if (hop.getHistoryTable() != null) {
                    if (newFocusOwner == rightPanel.getInstructionsPanel().getInstructionsArea()
                            || SwingUtilities.isDescendingFrom(newFocusOwner, workspacePanel)
                            || SwingUtilities.isDescendingFrom(newFocusOwner, hop.getHistoryTable())
                            || SwingUtilities.isDescendingFrom(newFocusOwner, hop.getLlmStreamArea())) {
                        this.lastRelevantFocusOwner = newFocusOwner;
                    }
                }
            }

            globalUndoAction.updateEnabledState();
            globalRedoAction.updateEnabledState();
            globalCopyAction.updateEnabledState();
            globalPasteAction.updateEnabledState();
            globalToggleMicAction.updateEnabledState();
        });
    }

    @Override
    public void contextChanged(Context newCtx) {
        SwingUtilities.invokeLater(() -> {
            globalUndoAction.updateEnabledState();
            globalRedoAction.updateEnabledState();
            globalCopyAction.updateEnabledState();
            globalPasteAction.updateEnabledState();
            globalToggleMicAction.updateEnabledState();

            rightPanel.getHistoryOutputPanel().updateUndoRedoButtonStates();
            setContext(newCtx);
            updateContextHistoryTable(newCtx);
        });
    }

    @Override
    public void onTrackedFileChange() {
        rightPanel.requestReviewUpdate();
    }

    /**
     * Creates a searchable content panel with a MarkdownOutputPanel and integrated search bar. This is shared
     * functionality used by both preview windows and detached output windows.
     *
     * @param markdownPanels List of MarkdownOutputPanel instances to make searchable
     * @param toolbarPanel   Optional panel to add to the right of the search bar
     * @return A JPanel containing the search bar, optional toolbar, and content
     */
    public static JPanel createSearchableContentPanel(
            List<MarkdownOutputPanel> markdownPanels, @Nullable JPanel toolbarPanel) {
        return PreviewManager.createSearchableContentPanel(markdownPanels, toolbarPanel, true);
    }

    /**
     * Called by PreviewTextFrame when it's being disposed to clear our reference.
     */
    public void clearPreviewFrame() {
        previewManager.clearPreviewTextFrame();
    }

    /**
     * Refreshes preview windows that display the given files. Called when files change on disk (external edits or
     * internal saves).
     *
     * @param changedFiles The set of files that have changed
     */
    public void refreshPreviewsForFiles(Set<ProjectFile> changedFiles) {
        previewManager.refreshPreviewsForFiles(changedFiles);
    }

    /**
     * Refreshes preview windows that display the given files, optionally excluding a specific frame. Called when files
     * change on disk (external edits or internal saves).
     *
     * @param changedFiles The set of files that have changed
     * @param excludeFrame Optional frame to exclude from refresh (typically the one that just saved)
     */
    public void refreshPreviewsForFiles(Set<ProjectFile> changedFiles, @Nullable JFrame excludeFrame) {
        previewManager.refreshPreviewsForFiles(changedFiles, excludeFrame);
    }

    public PreviewManager getPreviewManager() {
        return previewManager;
    }

    /**
     * Centralized method to open a preview for a specific ProjectFile at a specified line position.
     *
     * @param pf        The ProjectFile to preview.
     * @param startLine The line number (0-based) to position the caret at, or -1 to use default positioning.
     */
    public void previewFile(ProjectFile pf, int startLine) {
        previewManager.previewFile(pf, startLine);
    }

    /**
     * Opens an in-place preview of a context fragment without blocking on the EDT.
     * Uses non-blocking computed accessors when available; otherwise renders placeholders and
     * loads the actual values off-EDT, then updates the UI on the EDT.
     *
     * <p><b>Unified Entry Point:</b> This is the single entry point for all fragment preview operations
     * across the application. All preview requests—whether from WorkspacePanel chips, TokenUsageBar segments,
     * or other UI components—must route through this method to ensure consistent behavior, titles, and content.
     */
    public void openFragmentPreview(ContextFragment fragment) {
        previewManager.openFragmentPreview(fragment);
    }

    private void loadWindowSizeAndPosition() {
        boolean persistPerProject = GlobalUiSettings.isPersistPerProjectBounds();

        // Per-project first (only if enabled)
        var boundsOpt = persistPerProject ? getProject().getMainWindowBounds() : Optional.<Rectangle>empty();
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
                int instanceCount = openInstances.size(); // this instance not yet added
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
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                GlobalUiSettings.saveMainWindowBounds(frame);
                if (GlobalUiSettings.isPersistPerProjectBounds()) {
                    getProject().saveMainWindowBounds(frame);
                }
            }

            @Override
            public void componentMoved(ComponentEvent e) {
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
        // Project-first for horizontal split; global fallback; then compute
        int projectHorizontalPos = project.getHorizontalSplitPosition();
        int properDividerLocation;
        if (projectHorizontalPos > 0) {
            properDividerLocation = Math.min(projectHorizontalPos, Math.max(50, frame.getWidth() - 200));
        } else {
            int globalHorizontalPos = GlobalUiSettings.getHorizontalSplitPosition();
            if (globalHorizontalPos > 0) {
                properDividerLocation = Math.min(globalHorizontalPos, Math.max(50, frame.getWidth() - 200));
            } else {
                int computedWidth = computeInitialSidebarWidth();
                properDividerLocation = computedWidth + horizontalSplitPane.getDividerSize();
            }
        }

        // Restore open/closed state; default to CLOSED when no preference exists
        Boolean sidebarOpenPref = toolsPane.getSavedSidebarOpenPreference();
        boolean shouldOpen = (sidebarOpenPref != null) && sidebarOpenPref;

        if (!shouldOpen) {
            // Collapse sidebar by default or if explicitly saved as closed
            toolsPane.setLastExpandedSidebarLocation(
                    Math.max(toolsPane.getLastExpandedSidebarLocation(), properDividerLocation));
            // Relax minimum sizes to allow full collapse to compact width
            leftVerticalSplitPane.setMinimumSize(new Dimension(0, 0));
            toolsPane.getToolsPane().setMinimumSize(new Dimension(0, 0));
            toolsPane.getToolsPane().setSelectedIndex(0); // Always show Project Files when collapsed
            horizontalSplitPane.setDividerSize(0);
            toolsPane.setSidebarCollapsed(true);
            horizontalSplitPane.setDividerLocation(40);
        } else {
            // Open sidebar using the saved or computed divider location
            horizontalSplitPane.setDividerLocation(properDividerLocation);
            horizontalSplitPane.setDividerSize(originalBottomDividerSize);
            toolsPane.setSidebarCollapsed(false);
            toolsPane.setLastExpandedSidebarLocation(properDividerLocation);
            // Restore minimum sizes so min-width clamp is enforced
            int minPx = computeMinSidebarWidthPx();
            leftVerticalSplitPane.setMinimumSize(new Dimension(minPx, 0));
            toolsPane.getToolsPane().setMinimumSize(new Dimension(minPx, 0));
        }

        // Add property change listeners for future updates (also persist globally)
        addSplitPaneListeners(project);

        // Apply title bar now that layout is complete
        ThemeTitleBarManager.maybeApplyMacTitleBar(frame, frame.getTitle());

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
        rightPanel.restoreDividerLocation();

        // Restore drawer states from global settings
        restoreDrawersFromGlobalSettings();
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

    /**
     * Adds property change listeners to split panes for saving positions (global-first).
     */
    private void addSplitPaneListeners(AbstractProject project) {
        // BuildSplitPane listener moved to BuildPane.

        horizontalSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (!horizontalSplitPane.isShowing()) {
                return;
            }

            int newPos = horizontalSplitPane.getDividerLocation();
            if (newPos <= 0) {
                return;
            }

            // Treat the UI as "collapsed" when the divider is hidden or very close to the left edge.
            boolean isCollapsedUi = horizontalSplitPane.getDividerSize() == 0 || newPos < SIDEBAR_COLLAPSED_THRESHOLD;
            if (isCollapsedUi) {
                return;
            }

            // When the sidebar is expanded, clamp the divider so the left drawer
            // can never be shrunk below the minimum usable width.
            if (!toolsPane.isSidebarCollapsed()) {
                int minWidth = computeMinSidebarWidthPx();
                if (newPos < minWidth) {
                    // Clamp visually and skip persistence of too-small positions
                    if (SwingUtilities.isEventDispatchThread()) {
                        horizontalSplitPane.setDividerLocation(minWidth);
                    } else {
                        try {
                            SwingUtilities.invokeAndWait(() -> horizontalSplitPane.setDividerLocation(minWidth));
                        } catch (InterruptedException | InvocationTargetException ex) {
                            // Log or handle as appropriate; here we ignore
                        }
                    }
                    return;
                }
            }

            // Keep backward-compat but persist globally as the source of truth
            project.saveHorizontalSplitPosition(newPos);
            GlobalUiSettings.saveHorizontalSplitPosition(newPos);
            // Remember expanded locations only (ignore collapsed sidebar)
            if (newPos >= SIDEBAR_COLLAPSED_THRESHOLD) {
                toolsPane.setLastExpandedSidebarLocation(newPos);
            }
        });

        // Terminal drawer removed; no persistence listeners required.
    }

    @Override
    public void updateContextHistoryTable() {
        Context selectedContext = contextManager.selectedContext(); // Can be null
        updateContextHistoryTable(selectedContext);
    }

    @Override
    public void updateContextHistoryTable(@Nullable Context contextToSelect) {
        rightPanel.getHistoryOutputPanel().updateHistoryTable(contextToSelect);
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
        var hop = rightPanel.getHistoryOutputPanel();
        var messageSize = hop.getLlmRawMessages().size();
        SwingUtilities.invokeLater(() -> {
            var enabled = messageSize > 0 || hop.hasDisplayableOutput();
            hop.setCopyButtonEnabled(enabled);
            hop.setClearButtonEnabled(enabled);
            hop.setCaptureButtonEnabled(enabled);
            hop.setOpenWindowButtonEnabled(enabled);
        });
    }

    public JFrame getFrame() {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        return frame;
    }

    /**
     * Shows the inline loading spinner in the output panel.
     */
    @Override
    public void showOutputSpinner(String message) {
        SwingUtilities.invokeLater(() -> rightPanel.getHistoryOutputPanel().showSpinner(message));
    }

    @Override
    public void hideOutputSpinner() {
        SwingUtilities.invokeLater(rightPanel.getHistoryOutputPanel()::hideSpinner);
    }

    @Override
    public void showSessionSwitchSpinner() {
        SwingUtilities.invokeLater(rightPanel.getHistoryOutputPanel()::showSessionSwitchSpinner);
    }

    @Override
    public void hideSessionSwitchSpinner() {
        SwingUtilities.invokeLater(rightPanel.getHistoryOutputPanel()::hideSessionSwitchSpinner);
    }

    @Override
    public void showTransientMessage(String message) {
        SwingUtilities.invokeLater(() -> rightPanel.getHistoryOutputPanel().showTransientMessage(message));
    }

    @Override
    public void hideTransientMessage() {
        SwingUtilities.invokeLater(rightPanel.getHistoryOutputPanel()::hideTransientMessage);
    }

    public void focusInput() {
        SwingUtilities.invokeLater(rightPanel.getInstructionsPanel()::requestCommandInputFocus);
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

    public Map<String, JDialog> getOpenDialogs() {
        return openDialogs;
    }

    public JTabbedPane getToolsPane() {
        return toolsPane.getToolsPane();
    }

    @Nullable
    public GitPullRequestsTab getPullRequestsPanel() {
        return toolsPane.getPullRequestsPanel();
    }

    @Nullable
    public GitIssuesTab getIssuesPanel() {
        return toolsPane.getIssuesPanel();
    }

    public DependenciesPanel getDependenciesPanel() {
        return dependenciesPanel;
    }

    public TestRunnerPanel getTestRunnerPanel() {
        return testRunnerPanel;
    }

    // --- New helpers for Git tabs moved into Chrome ---

    public void updateLogTab() {
        if (toolsPane.getGitLogTab() != null) {
            toolsPane.getGitLogTab().requestUpdate();
        }
    }

    public void selectCurrentBranchInLogTab() {
        if (toolsPane.getGitLogTab() != null) {
            toolsPane.getGitLogTab().selectCurrentBranch();
        }
    }

    public void showCommitInLogTab(String commitId) {
        if (toolsPane.getGitLogTab() != null) {
            var tools = toolsPane.getToolsPane();
            for (int i = 0; i < tools.getTabCount(); i++) {
                if (tools.getComponentAt(i) == toolsPane.getGitLogTab()) {
                    tools.setSelectedIndex(i);
                    break;
                }
            }
            toolsPane.getGitLogTab().selectCommitById(commitId);
        }
    }

    private void selectExistingFileHistoryTab(String filePath) {
        var existing = fileHistoryTabs.get(filePath);
        if (existing == null) {
            return;
        }

        // Ensure the history pane is visible
        if (!fileHistoryPane.isVisible()) {
            fileHistoryPane.setVisible(true);
            leftVerticalSplitPane.setDividerSize(originalLeftVerticalDividerSize);
            leftVerticalSplitPane.setDividerLocation(0.7);
        }

        int count = fileHistoryPane.getTabCount();
        for (int i = 0; i < count; i++) {
            if (fileHistoryPane.getComponentAt(i) == existing) {
                fileHistoryPane.setSelectedIndex(i);
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

        var closeButton = new JButton("×");
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        closeButton.setPreferredSize(new Dimension(24, 24));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.addActionListener(e -> {
            int idx = fileHistoryPane.indexOfComponent(historyTab);
            if (idx >= 0) {
                fileHistoryPane.remove(idx);
                fileHistoryTabs.remove(filePath);
                if (fileHistoryPane.getTabCount() == 0) {
                    fileHistoryPane.setVisible(false);
                    leftVerticalSplitPane.setDividerSize(0);
                }
            }
        });

        tabHeader.add(titleLabel);
        tabHeader.add(closeButton);

        if (!fileHistoryPane.isVisible()) {
            fileHistoryPane.setVisible(true);
            leftVerticalSplitPane.setDividerSize(originalLeftVerticalDividerSize);
            leftVerticalSplitPane.setDividerLocation(0.7);
        }

        fileHistoryPane.addTab(file.getFileName(), historyTab);
        int newIdx = fileHistoryPane.indexOfComponent(historyTab);
        fileHistoryPane.setTabComponentAt(newIdx, tabHeader);
        fileHistoryPane.setSelectedIndex(newIdx);
        fileHistoryTabs.put(filePath, historyTab);
    }

    @Nullable
    public GitCommitTab getGitCommitTab() {
        return toolsPane.getGitCommitTab();
    }

    @Nullable
    public GitLogTab getGitLogTab() {
        return toolsPane.getGitLogTab();
    }

    @Nullable
    public GitWorktreeTab getGitWorktreeTab() {
        return toolsPane.getGitWorktreeTab();
    }

    public void setBlitzForgeMenuItem(JMenuItem it) {
        this.blitzForgeMenuItem = it;
    }

    public void showFileInProjectTree(ProjectFile pf) {
        projectFilesPanel.showFileInTree(pf);
    }

    @Override
    public InstructionsPanel getInstructionsPanel() {
        return rightPanel.getInstructionsPanel();
    }

    public TaskListPanel getTaskListPanel() {
        return rightPanel.getTaskListPanel();
    }

    public WorkspacePanel getContextPanel() {
        return workspacePanel;
    }

    public HistoryOutputPanel getHistoryOutputPanel() {
        return rightPanel.getHistoryOutputPanel();
    }

    public JTabbedPane getCommandPane() {
        return rightPanel.getCommandPane();
    }

    public void updateTerminalFontSize() {}

    /**
     * Hook to apply Advanced Mode UI visibility without restart.
     * Shows/hides tabs that are considered "advanced": Pull Requests, Issues, Log, Worktrees.
     * Centralizes calls to update Instructions panel mode UI and refresh keybindings to avoid duplication.
     * Safe to call from any thread.
     */
    public void applyAdvancedModeVisibility() {
        SwingUtilities.invokeLater(() -> {
            rightPanel.applyAdvancedModeVisibility();
            toolsPane.applyAdvancedModeVisibility();
            updateGitTabBadge(getModifiedFiles().size());
            refreshKeybindings();
        });
    }

    public RightPanel getRightPanel() {
        return rightPanel;
    }

    public void applyVerticalActivityLayout() {
        SwingUtilities.invokeLater(() -> {
            rightPanel.applyVerticalActivityLayout();
            horizontalSplitPane.setRightComponent(rightPanel);
            frame.revalidate();
            frame.repaint();
        });
    }

    /**
     * Schedules the build settings dialog to show after initialization completes.
     * Uses OnboardingOrchestrator to determine which dialogs are needed and
     * shows them sequentially: Migration → Build Settings → Git Config (if needed).
     */
    private void scheduleGitConfigurationAfterInit() {
        logger.debug("Scheduling onboarding after style guide and build details are ready");
        var styleFuture = contextManager.getStyleGuideFuture();
        var buildFuture = getProject().getBuildDetailsFuture();

        // Wait for both futures to complete
        CompletableFuture.allOf(styleFuture.thenApply(c -> null), buildFuture)
                .thenAcceptAsync(v -> {
                    logger.debug("Style guide and build details ready, building onboarding plan");

                    // Build project state
                    var styleSkipped = contextManager.wasStyleGenerationSkipped();
                    var state = OnboardingOrchestrator.buildProjectState(
                            getProject(), styleFuture, buildFuture, styleSkipped);

                    // Build onboarding plan
                    var orchestrator = new OnboardingOrchestrator();
                    var plan = orchestrator.buildPlan(state);

                    logger.info("Onboarding plan has {} steps", plan.size());

                    // Execute all steps synchronously and collect results
                    var results = plan.getSteps().stream()
                            .map(step -> step.execute(state))
                            .toList();

                    // Show dialogs on EDT
                    SwingUtilities.invokeLater(() -> processOnboardingResults(results));
                })
                .exceptionally(ex -> {
                    logger.error("Error waiting for initialization", ex);
                    SwingUtilities.invokeLater(() -> systemNotify(
                            "Error during initialization: " + ex.getMessage(),
                            "Initialization Error",
                            javax.swing.JOptionPane.ERROR_MESSAGE));
                    return null;
                });
    }

    /**
     * Processes onboarding step results and shows appropriate dialogs.
     * Called on EDT after all onboarding steps have completed.
     * <p>
     * Shows dialogs in dependency order:
     * 1. Migration dialog (if MigrationStep flagged)
     * 2. Build settings dialog (if BuildSettingsStep flagged)
     * 3. Git config dialog (if GitConfigStep flagged)
     * 4. Post-git style regeneration offer (if applicable)
     *
     * @param results list of step results
     */
    private void processOnboardingResults(List<OnboardingStep.StepResult> results) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        logger.debug("Processing {} onboarding results", results.size());

        for (var result : results) {
            if (!result.success()) {
                logger.warn("[{}] Step failed: {}", result.stepId(), result.message());
                continue;
            }

            if (!result.requiresUserDialog()) {
                logger.debug("[{}] Step completed without dialog: {}", result.stepId(), result.message());
                continue;
            }

            var dialogData = result.data();
            if (dialogData == null) {
                logger.error("[{}] Step requires dialog but data is null", result.stepId());
                continue;
            }

            // Handle steps that require user dialogs using pattern matching on typed data
            switch (dialogData) {
                case MigrationStep.MigrationDialogData ignored -> {
                    logger.info("[{}] Showing migration dialog", result.stepId());
                    showMigrationDialog();
                }
                case BuildSettingsStep.BuildSettingsDialogData buildData -> {
                    logger.info("[{}] Showing build settings dialog", result.stepId());
                    var dlg = SettingsDialog.showSettingsDialog(this, "Build");
                    dlg.getProjectPanel().showBuildBanner();
                }
                case GitConfigStep.GitConfigDialogData gitConfigData -> {
                    logger.info("[{}] Showing git config dialog", result.stepId());
                    showGitConfigDialog();
                }
                case PostGitStyleRegenerationStep.RegenerationOfferData regenData -> {
                    logger.info("[{}] Showing post-git style regeneration offer", result.stepId());
                    int confirm = showConfirmDialog(
                            regenData.message(),
                            "Regenerate Style Guide",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);

                    if (confirm == JOptionPane.YES_OPTION) {
                        logger.info("[{}] User accepted style regeneration, triggering regeneration", result.stepId());
                        showNotification(IConsoleIO.NotificationRole.INFO, "Regenerating style guide...");

                        var regenerationFuture = contextManager.ensureStyleGuide();
                        regenerationFuture
                                .thenAcceptAsync(styleContent -> {
                                    SwingUtilities.invokeLater(() -> {
                                        logger.info("[{}] Style regeneration completed successfully", result.stepId());
                                        showNotification(
                                                IConsoleIO.NotificationRole.INFO,
                                                "Style guide regenerated successfully");
                                        SettingsDialog.showSettingsDialog(this, "Build");
                                    });
                                })
                                .exceptionally(ex -> {
                                    SwingUtilities.invokeLater(() -> {
                                        logger.error("[{}] Style regeneration failed", result.stepId(), ex);
                                        toolError(
                                                "Failed to regenerate style guide: " + ex.getMessage(),
                                                "Style Regeneration Error");
                                    });
                                    return null;
                                });
                    } else {
                        logger.info("[{}] User declined style regeneration", result.stepId());
                    }
                }
            }
        }

        logger.info("Onboarding dialog sequence complete");

        // Mark onboarding as completed so dialogs won't show again
        getProject().markOnboardingCompleted();
    }

    /**
     * Shows the migration confirmation dialog and performs migration if user accepts.
     */
    private void showMigrationDialog() {
        if (!(getProject() instanceof MainProject mainProject)) {
            return; // Only main projects can be migrated
        }

        try {
            // Check if user already declined
            if (mainProject.getMigrationDeclined()) {
                logger.debug("Migration previously declined by user");
                return;
            }

            String message =
                    """
            This project uses the legacy `style.md` file for style guidance. The application now uses `AGENTS.md` instead.

            Would you like to migrate `style.md` to `AGENTS.md`? This will:
            - Rename `.brokk/style.md` to `AGENTS.md` (at the project root)
            - Stage the change in Git (if the project is a Git repository)
            - You can then review and commit the changes
            """;

            int confirm = showConfirmDialog(
                    getFrame(),
                    message,
                    "Migrate Style Guide to AGENTS.md",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                mainProject.performStyleMdToAgentsMdMigration(this);
            } else {
                mainProject.setMigrationDeclined(true);
                logger.info("User declined style.md to AGENTS.md migration. Decision stored.");
            }
        } catch (Exception e) {
            logger.error("Error during migration dialog: {}", e.getMessage(), e);
            systemNotify(
                    "Error during migration: " + e.getMessage(),
                    "Migration Error",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Shows the git configuration dialog with editable commit message.
     */
    private void showGitConfigDialog() {
        contextManager.submitBackgroundTask("Checking .gitignore", () -> {
            if (!getProject().isGitIgnoreSet()) {
                SwingUtilities.invokeLater(() -> {
                    var dialog = new GitConfigCommitDialog(frame, this, contextManager, getProject());
                    dialog.setVisible(true);
                });
            }
            return null;
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

    private boolean isFocusInContextArea(@Nullable Component focusOwner) {
        if (focusOwner == null) return false;
        // Check if focus is within ContextPanel or HistoryOutputPanel's historyTable
        boolean inContextPanel = SwingUtilities.isDescendingFrom(focusOwner, workspacePanel);
        var hop = rightPanel.getHistoryOutputPanel();
        boolean inHistoryTable =
                hop.getHistoryTable() != null && SwingUtilities.isDescendingFrom(focusOwner, hop.getHistoryTable());
        return inContextPanel || inHistoryTable;
    }

    public GuiTheme getThemeManager() {
        return themeManager;
    }

    // --- Global Undo/Redo Action Classes ---
    private class GlobalUndoAction extends AbstractAction {
        public GlobalUndoAction(String name) {
            super(name);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            var ip = rightPanel.getInstructionsPanel();
            if (lastRelevantFocusOwner == ip.getInstructionsArea()) {
                if (ip.getCommandInputUndoManager().canUndo()) {
                    ip.getCommandInputUndoManager().undo();
                }
            } else if (isFocusInContextArea(lastRelevantFocusOwner)) {
                if (contextManager.getContextHistory().hasUndoStates()) {
                    contextManager.undoContextAsync();
                }
            } else if (ip.getCommandInputUndoManager().canUndo()) {
                // Fallback: undo instructions panel when focus is elsewhere
                ip.getCommandInputUndoManager().undo();
            }
        }

        public void updateEnabledState() {
            var ip = rightPanel.getInstructionsPanel();
            boolean canUndoNow = false;
            if (lastRelevantFocusOwner == ip.getInstructionsArea()) {
                canUndoNow = ip.getCommandInputUndoManager().canUndo();
            } else if (isFocusInContextArea(lastRelevantFocusOwner)) {
                canUndoNow = contextManager.getContextHistory().hasUndoStates();
            } else {
                // Fallback: enable if instructions panel has undoable content
                canUndoNow = ip.getCommandInputUndoManager().canUndo();
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
            var ip = rightPanel.getInstructionsPanel();
            if (lastRelevantFocusOwner == ip.getInstructionsArea()) {
                if (ip.getCommandInputUndoManager().canRedo()) {
                    ip.getCommandInputUndoManager().redo();
                }
            } else if (isFocusInContextArea(lastRelevantFocusOwner)) {
                if (contextManager.getContextHistory().hasRedoStates()) {
                    contextManager.redoContextAsync();
                }
            } else if (ip.getCommandInputUndoManager().canRedo()) {
                // Fallback: redo instructions panel when focus is elsewhere
                ip.getCommandInputUndoManager().redo();
            }
        }

        public void updateEnabledState() {
            var ip = rightPanel.getInstructionsPanel();
            boolean canRedoNow = false;
            if (lastRelevantFocusOwner == ip.getInstructionsArea()) {
                canRedoNow = ip.getCommandInputUndoManager().canRedo();
            } else if (isFocusInContextArea(lastRelevantFocusOwner)) {
                canRedoNow = contextManager.getContextHistory().hasRedoStates();
            } else {
                // Fallback: enable if instructions panel has redoable content
                canRedoNow = ip.getCommandInputUndoManager().canRedo();
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
            var ip = rightPanel.getInstructionsPanel();
            var hop = rightPanel.getHistoryOutputPanel();
            if (lastRelevantFocusOwner == ip.getInstructionsArea()) {
                ip.getInstructionsArea().copy();
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, hop.getLlmStreamArea())) {
                hop.getLlmStreamArea().copy(); // Assumes MarkdownOutputPanel has copy()
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel)
                    || SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, hop.getHistoryTable())) {
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
            var focusOwner = lastRelevantFocusOwner;
            if (focusOwner == null) {
                setEnabled(false);
                return;
            }

            var ip = rightPanel.getInstructionsPanel();
            var hop = rightPanel.getHistoryOutputPanel();

            // Instructions area: enable if there's either a selection or any text
            if (focusOwner == ip.getInstructionsArea()) {
                var field = ip.getInstructionsArea();
                boolean hasSelection = field.getSelectedText() != null
                        && !field.getSelectedText().isEmpty();
                boolean hasText = !field.getText().isEmpty();
                setEnabled(hasSelection || hasText);
                return;
            }

            // If focus is in the MarkdownOutputPanel (LLM output), always enable Copy.
            // The copy handler will choose selection vs full content.
            if (SwingUtilities.isDescendingFrom(focusOwner, hop.getLlmStreamArea())) {
                setEnabled(true);
                return;
            }

            // Focus is in a context area (Workspace panel or History table): Copy is available.
            if (SwingUtilities.isDescendingFrom(focusOwner, workspacePanel)
                    || SwingUtilities.isDescendingFrom(focusOwner, hop.getHistoryTable())) {
                setEnabled(true);
                return;
            }

            // Default: disabled
            setEnabled(false);
        }
    }

    /**
     * Safely retrieves the system clipboard, handling Exceptions in windows
     * Windows when the clipboard is temporarily locked by another process.
     * <p>
     * <b>Background:</b> On Windows, the system clipboard can be temporarily locked when
     * another process is accessing it (e.g., during copy/paste operations in other apps).
     * This causes {@link Toolkit#getSystemClipboard()} to throw {@link IllegalStateException},
     * particularly during EDT focus change processing.
     * <p>
     * <b>Solution:</b> This app treats clipboard lock as transient and non-fatal. Instead of
     * propagating exceptions to the UI, we return {@code null} and let callers gracefully
     * degrade (e.g., disable paste action temporarily, show notification).
     * <p>
     * <b>Related JDK Issue:</b> <a href="https://bugs.openjdk.org/browse/JDK-8353950">JDK-8353950</a>
     * - Windows clipboard interaction instability
     *
     * @return The system clipboard, or null if temporarily unavailable
     */
    @Nullable
    private static Clipboard getSystemClipboardSafe() {
        try {
            return Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (IllegalStateException | HeadlessException e) {
            logger.debug(
                    "System clipboard temporarily unavailable ({})",
                    e.getClass().getSimpleName());
            return null;
        } catch (Exception e) {
            logger.warn("Unexpected error accessing system clipboard", e);
            return null;
        }
    }

    /**
     * Safely reads string data from the system clipboard, handling potential exceptions
     * when the clipboard is temporarily unavailable or doesn't contain string data.
     * <p>
     * <b>Background:</b> On Windows, clipboard access methods like
     * {@link Clipboard#isDataFlavorAvailable(DataFlavor)} and {@link Clipboard#getData(DataFlavor)}
     * can throw {@link IllegalStateException} when the clipboard is locked by another process.
     * This is particularly problematic during rapid focus change events on the EDT.
     * <p>
     * <b>Solution:</b> This wrapper catches all clipboard-related exceptions and returns {@code null}
     * to indicate unavailability, allowing the UI to gracefully handle temporary clipboard locks
     * without propagating exceptions to users.
     * <p>
     * <b>Related JDK Issue:</b> <a href="https://bugs.openjdk.org/browse/JDK-8353950">JDK-8353950</a>
     * - Windows clipboard interaction instability
     *
     * @return The string data from clipboard, or null if unavailable or not a string
     */
    @Nullable
    private static String readStringFromClipboardSafe() {
        var clipboard = getSystemClipboardSafe();
        if (clipboard == null) {
            return null;
        }

        try {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return null;
            }
            var data = clipboard.getData(DataFlavor.stringFlavor);
            return (String) data;
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) {
            logger.warn("Failed to read string from clipboard: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            logger.warn("Unexpected error reading clipboard string data", e);
            return null;
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

            var ip = rightPanel.getInstructionsPanel();
            if (lastRelevantFocusOwner == ip.getInstructionsArea()) {
                ip.getInstructionsArea().paste();
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel)) {
                // Check clipboard availability before attempting paste to avoid Windows clipboard lock exceptions.
                // On Windows, the system clipboard can be temporarily locked by other processes, causing
                // IllegalStateException. We treat this as transient and show a notification instead of failing.
                var clipboard = getSystemClipboardSafe();
                if (clipboard == null) {
                    showNotification(NotificationRole.INFO, "Clipboard is temporarily unavailable");
                    return;
                }
                workspacePanel.performContextActionAsync(WorkspacePanel.ContextAction.PASTE, List.of());
            }
        }

        public void updateEnabledState() {
            var ip = rightPanel.getInstructionsPanel();
            boolean canPasteNow = false;
            if (lastRelevantFocusOwner == null) {
                // leave it false
            } else if (lastRelevantFocusOwner == ip.getInstructionsArea()) {
                // Use safe wrapper instead of direct isDataFlavorAvailable() to avoid Windows clipboard
                // lock exceptions during rapid focus changes on EDT. See JDK-8353950.
                canPasteNow = readStringFromClipboardSafe() != null;
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
            VoiceInputButton micButton = rightPanel.getInstructionsPanel().getVoiceInputButton();
            if (micButton.isEnabled()) {
                micButton.doClick();
            }
        }

        public void updateEnabledState() {
            VoiceInputButton micButton = rightPanel.getInstructionsPanel().getVoiceInputButton();
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
        maybeApplyMacFullWindowContent(frame);
        if (initializeTitleBar) ThemeTitleBarManager.maybeApplyMacTitleBar(frame, title);
        return frame;
    }

    /**
     * Applies macOS full-window-content styling to a window.
     * Sets transparent title bar and hides the native window title.
     * No-op on non-macOS platforms.
     *
     * @param window A JFrame or JDialog (any RootPaneContainer)
     */
    public static void maybeApplyMacFullWindowContent(RootPaneContainer window) {
        if (!SystemInfo.isMacOS || !SystemInfo.isMacFullWindowContentSupported) {
            return;
        }
        var rootPane = window.getRootPane();
        rootPane.putClientProperty("apple.awt.fullWindowContent", true);
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true);
        if (SystemInfo.isJava_17_orLater) {
            rootPane.putClientProperty("apple.awt.windowTitleVisible", false);
        } else if (window instanceof Window w) {
            // For older Java, hide title by setting it to null
            if (w instanceof Frame f) f.setTitle(null);
            else if (w instanceof Dialog d) d.setTitle(null);
        }
    }

    public static JFrame newFrame(String title) {
        return newFrame(title, true);
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

    /**
     * Disables the history panel via HistoryOutputPanel.
     */
    @Override
    public void disableHistoryPanel() {
        rightPanel.getHistoryOutputPanel().disableHistory();
    }

    /**
     * Enables the history panel via HistoryOutputPanel.
     */
    @Override
    public void enableHistoryPanel() {
        rightPanel.getHistoryOutputPanel().enableHistory();
    }

    /**
     * Sets the taskInProgress state on the MarkdownOutputPanel
     */
    @Override
    public void setTaskInProgress(boolean taskInProgress) {
        SwingUtilities.invokeLater(() -> {
            rightPanel.getHistoryOutputPanel().setTaskInProgress(taskInProgress);
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

        SwingUtilities.invokeLater(() -> rightPanel.getHistoryOutputPanel().showNotification(role, message));
    }

    /**
     * Helper method to find JScrollPane component within a container
     */
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

    /**
     * Shared "Rebuilding Code Intelligence" status strip with spinner and text.
     * Hidden by default. Other panels can embed this component via getAnalyzerStatusStrip().
     * The spinner icon is refreshed against the current theme whenever it is shown or a theme is applied.
     */
    private class AnalyzerStatusStrip extends JPanel implements ThemeAware {
        private final JLabel label;

        private AnalyzerStatusStrip() {
            super();
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setBorder(new EmptyBorder(2, 6, 2, 6));
            label = new JLabel("Rebuilding Code Intelligence...");
            label.setIcon(null); // set on show to ensure theme-correct icon
            label.setAlignmentY(Component.CENTER_ALIGNMENT);
            add(label);
            setOpaque(true);
            // Start hidden by default. Visibility controlled by Chrome methods.
            setVisible(false);
        }

        void refreshSpinnerIcon() {
            // Must be called on EDT; SpinnerIconUtil asserts EDT
            Icon icon = SpinnerIconUtil.getSpinner(Chrome.this, true);
            label.setIcon(icon);
        }

        /**
         * Updates the tooltip to show progress details. Safe to call from any thread.
         *
         * @param tooltip The tooltip text, or null to clear
         */
        void setProgressTooltip(String tooltip) {
            SwingUtil.runOnEdt(() -> label.setToolTipText(tooltip));
        }

        @Override
        public void applyTheme(GuiTheme guiTheme) {
            // Keep basic colors in sync with theme; refresh spinner icon if visible
            Color bg = UIManager.getColor("Panel.background");
            Color fg = UIManager.getColor("Label.foreground");
            setBackground(bg != null ? bg : getBackground());
            label.setForeground(fg != null ? fg : label.getForeground());
            if (isVisible()) {
                refreshSpinnerIcon();
            }
            SwingUtilities.updateComponentTreeUI(this);
        }
    }

    /**
     * Updates the git tab badge with the current number of modified files. Should be called whenever the git status
     * changes
     */
    public void updateGitTabBadge(int modifiedCount) {
        assert SwingUtilities.isEventDispatchThread() : "updateGitTabBadge(int) must be called on EDT";
        toolsPane.updateGitTabBadge(modifiedCount);
    }

    /**
     * Updates the Project Files tab badge with the current number of live dependencies. Should be called whenever
     * the dependency count changes or on startup to initialize the badge. EDT-safe.
     */
    @SuppressWarnings("RedundantNullCheck")
    public void updateProjectFilesTabBadge(int dependencyCount) {
        SwingUtil.runOnEdt(() -> {
            if (toolsPane != null) {
                toolsPane.updateProjectFilesTabBadge(dependencyCount);
            }
        });
    }

    public void refreshBranchUi(@Nullable String branchName) {
        SwingUtilities.invokeLater(() -> {
            rightPanel.setBranchLabel(branchName);
            projectFilesPanel.updateBorderTitle();
        });
    }

    /**
     * Calculates an appropriate initial width for the left sidebar based on content and window size.
     */
    public int computeInitialSidebarWidth() {
        int ideal = projectFilesPanel.getPreferredSize().width;
        int frameWidth = frame.getWidth();

        // Allow between minimum and maximum percentage based on screen width,
        // but never below the absolute minimum pixel width.
        int min = Math.max(MIN_SIDEBAR_WIDTH_PX, (int) (frameWidth * MIN_SIDEBAR_WIDTH_FRACTION));
        double maxFraction =
                frameWidth > WIDE_SCREEN_THRESHOLD ? MAX_SIDEBAR_WIDTH_FRACTION_WIDE : MAX_SIDEBAR_WIDTH_FRACTION;
        int max = (int) (frameWidth * maxFraction);

        return Math.max(min, Math.min(ideal, max));
    }

    /**
     * Computes the minimum allowed sidebar width in pixels, combining the
     * absolute minimum with a fraction of the current frame width.
     */
    public int computeMinSidebarWidthPx() {
        int frameWidth = frame.getWidth();
        int byFraction = (int) (frameWidth * MIN_SIDEBAR_WIDTH_FRACTION);
        return Math.max(MIN_SIDEBAR_WIDTH_PX, byFraction);
    }

    /**
     * Get the list of uncommitted modified files from GCT's cache.
     * No repo call needed—uses the already-computed list from GitCommitTab.
     * Safe to call from any thread.
     */
    public List<ProjectFile> getModifiedFiles() {
        if (toolsPane.getGitCommitTab() == null) {
            return List.of();
        }
        return toolsPane.getGitCommitTab().getModifiedFiles();
    }

    /**
     * Shows the shared analyzer rebuild status strip. Safe to call from any thread.
     */
    public void showAnalyzerRebuildStatus() {
        SwingUtil.runOnEdt(() -> {
            analyzerStatusStrip.refreshSpinnerIcon();
            analyzerStatusStrip.setVisible(true);
            analyzerStatusStrip.revalidate();
            analyzerStatusStrip.repaint();
        });
    }

    /**
     * Hides the shared analyzer rebuild status strip. Safe to call from any thread.
     */
    public void hideAnalyzerRebuildStatus() {
        SwingUtil.runOnEdt(() -> {
            analyzerStatusStrip.setVisible(false);
            analyzerStatusStrip.setProgressTooltip(""); // Clear tooltip when hiding
            analyzerStatusStrip.revalidate();
            analyzerStatusStrip.repaint();
        });
    }

    /**
     * Updates the analyzer rebuild status strip tooltip with progress details. Safe to call from any thread.
     *
     * @param progressMessage The progress message to display as a tooltip
     */
    public void updateAnalyzerProgress(String progressMessage) {
        analyzerStatusStrip.setProgressTooltip(progressMessage);
    }

    /**
     * Returns the shared analyzer rebuild status strip so other panels can embed it.
     * Note: Swing components can have only one parent; callers should remove it from
     * a previous parent before adding it elsewhere.
     */
    public JSplitPane getHorizontalSplitPane() {
        return horizontalSplitPane;
    }

    public JSplitPane getLeftVerticalSplitPane() {
        return leftVerticalSplitPane;
    }

    public int getOriginalBottomDividerSize() {
        return originalBottomDividerSize;
    }

    public JComponent getAnalyzerStatusStrip() {
        return analyzerStatusStrip;
    }

    @Override
    public BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        var dialog = requireNonNull(SwingUtil.runOnEdt(() -> new BlitzForgeProgressDialog(this, cancelCallback), null));
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        return dialog;
    }

    private static class ChromeFocusTraversalPolicy extends java.awt.FocusTraversalPolicy {
        private final java.util.List<java.awt.Component> order;

        public ChromeFocusTraversalPolicy(java.util.List<java.awt.Component> order) {
            this.order = order.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.toList());
        }

        private int getIndex(java.awt.Component c) {
            // Find component or one of its ancestors in the order list
            for (java.awt.Component comp = c; comp != null; comp = comp.getParent()) {
                int i = order.indexOf(comp);
                if (i != -1) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Returns true if the component should be included in focus traversal.
         * The Instructions area is skipped when "tab inserts indentation" is enabled,
         * because Tab/Shift+Tab would be trapped for indentation instead of navigation.
         */
        private boolean shouldIncludeInTraversal(java.awt.Component comp) {
            if (!comp.isFocusable() || !comp.isShowing() || !comp.isEnabled()) {
                return false;
            }
            // Skip Instructions area when tab-for-indentation is enabled (would trap focus)
            if (comp instanceof javax.swing.JTextArea textArea) {
                // Check if this is the instructions area by name or other property
                if ("instructionsArea".equals(textArea.getName())
                        && ai.brokk.util.GlobalUiSettings.isInstructionsTabInsertIndentation()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public java.awt.Component getComponentAfter(java.awt.Container focusCycleRoot, java.awt.Component aComponent) {
            if (order.isEmpty()) return aComponent;
            int idx = getIndex(aComponent);
            if (idx == -1) {
                return getFirstComponent(focusCycleRoot);
            }
            for (int i = 1; i <= order.size(); i++) {
                int nextIdx = (idx + i) % order.size();
                java.awt.Component nextComp = order.get(nextIdx);
                if (shouldIncludeInTraversal(nextComp)) {
                    return nextComp;
                }
            }
            return aComponent;
        }

        @Override
        public java.awt.Component getComponentBefore(java.awt.Container focusCycleRoot, java.awt.Component aComponent) {
            if (order.isEmpty()) return aComponent;
            int idx = getIndex(aComponent);
            if (idx == -1) {
                return getLastComponent(focusCycleRoot);
            }
            for (int i = 1; i <= order.size(); i++) {
                int prevIdx = (idx - i + order.size()) % order.size();
                java.awt.Component prevComp = order.get(prevIdx);
                if (shouldIncludeInTraversal(prevComp)) {
                    return prevComp;
                }
            }
            return aComponent;
        }

        @Override
        public java.awt.Component getFirstComponent(java.awt.Container focusCycleRoot) {
            if (order.isEmpty()) return focusCycleRoot;
            for (int i = 0; i < order.size(); i++) {
                java.awt.Component comp = order.get(i);
                if (shouldIncludeInTraversal(comp)) {
                    return comp;
                }
            }
            return focusCycleRoot;
        }

        @Override
        public java.awt.Component getLastComponent(java.awt.Container focusCycleRoot) {
            if (order.isEmpty()) return focusCycleRoot;
            for (int i = order.size() - 1; i >= 0; i--) {
                java.awt.Component comp = order.get(i);
                if (shouldIncludeInTraversal(comp)) {
                    return comp;
                }
            }
            return focusCycleRoot;
        }

        @Override
        public java.awt.Component getDefaultComponent(java.awt.Container focusCycleRoot) {
            return getFirstComponent(focusCycleRoot);
        }
    }

    // Global focus highlighting mechanism
    private static final Color FOCUS_BORDER_COLOR = new Color(0x1F6FEB); // Blue focus color

    private void applyFocusHighlighting(@Nullable Component oldFocus, @Nullable Component newFocus) {
        // Remove highlighting from old component
        if (oldFocus != null && oldFocus != newFocus) {
            removeFocusHighlight(oldFocus);
        }

        // Apply highlighting to new component
        if (newFocus != null && shouldHighlightComponent(newFocus)) {
            applyFocusHighlight(newFocus);
        }
    }

    private boolean shouldHighlightComponent(Component component) {
        // Only highlight components that are part of our focus traversal policy
        if (!component.isFocusable()) {
            return false;
        }

        var ip = rightPanel.getInstructionsPanel();
        var tlp = rightPanel.getTaskListPanel();
        var hop = rightPanel.getHistoryOutputPanel();

        // Check if component is one of our main navigable components
        return component == ip.getInstructionsArea()
                || component == ip.getMicButton()
                || component == ip.getWandButton()
                || component == ip.getHistoryDropdown()
                || component == ip.getModelSelectorComponent()
                || component == projectFilesPanel.getSearchField()
                || component == projectFilesPanel.getRefreshButton()
                || component == projectFilesPanel.getProjectTree()
                || component == dependenciesPanel.getDependencyTable()
                || component == dependenciesPanel.getAddButton()
                || component == dependenciesPanel.getRemoveButton()
                || component == tlp.getTaskInput()
                || component == tlp.getGoStopButton()
                || component == tlp.getTaskList()
                || component == hop.getHistoryTable()
                || component == hop.getLlmStreamArea();
    }

    private void applyFocusHighlight(Component component) {
        if (component instanceof JComponent jcomp) {
            // Store original border if not already stored
            if (jcomp.getClientProperty("originalBorder") == null) {
                jcomp.putClientProperty("originalBorder", jcomp.getBorder());
            }

            // Apply focus border
            var originalBorder = (javax.swing.border.Border) jcomp.getClientProperty("originalBorder");
            var focusBorder = BorderFactory.createLineBorder(FOCUS_BORDER_COLOR, 2);

            if (originalBorder != null) {
                jcomp.setBorder(BorderFactory.createCompoundBorder(focusBorder, originalBorder));
            } else {
                jcomp.setBorder(focusBorder);
            }

            jcomp.repaint();
        }
    }

    private void removeFocusHighlight(Component component) {
        if (component instanceof JComponent jcomp) {
            // Restore original border
            var originalBorder = (javax.swing.border.Border) jcomp.getClientProperty("originalBorder");
            jcomp.setBorder(originalBorder);
            jcomp.repaint();
        }
    }
}
