package io.github.jbellis.brokk.gui;

import static io.github.jbellis.brokk.gui.Constants.*;
import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.FrozenFragment;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.dialogs.PreviewImagePanel;
import io.github.jbellis.brokk.gui.dialogs.PreviewTextPanel;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPool;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.search.GenericSearchBar;
import io.github.jbellis.brokk.gui.search.MarkdownSearchableComponent;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class Chrome implements AutoCloseable, IConsoleIO, IContextManager.ContextListener {
    private static final Logger logger = LogManager.getLogger(Chrome.class);

    // Used as the default text for the background tasks label
    private final String BGTASK_EMPTY = "No background tasks";
    private final String SYSMSG_EMPTY = "Ready";

    // is a setContext updating the MOP?
    private boolean skipNextUpdateOutputPanelOnContextChange = false;

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

    // Swing components:
    final JFrame frame;
    private JLabel backgroundStatusLabel;
    private JLabel systemMessageLabel;
    private final List<String> systemMessages = new ArrayList<>();
    private final JPanel bottomPanel;

    private final JSplitPane topSplitPane; // Instructions | Workspace
    private final JSplitPane mainVerticalSplitPane; // (Instructions+Workspace) | Tabbed bottom

    private final JTabbedPane leftTabbedPanel; // ProjectFiles, Git tabs
    private final HistoryOutputPanel historyOutputPanel;
    /** Horizontal split between left tab stack and right output stack */
    private JSplitPane bottomSplitPane;

    // Panels:
    private final WorkspacePanel workspacePanel;
    private final ProjectFilesPanel projectFilesPanel; // New panel for project files

    @Nullable
    private final GitPanel gitPanel; // Null when no git repo is present

    @Nullable
    private GitPullRequestsTab pullRequestsPanel;

    @Nullable
    private GitIssuesTab issuesPanel;

    // Reference to Tools ▸ BlitzForge… menu item so we can enable/disable it
    @SuppressWarnings("NullAway.Init") // Initialized by MenuBar after constructor
    private JMenuItem blitzForgeMenuItem;

    // Command input panel is now encapsulated in InstructionsPanel.
    private final InstructionsPanel instructionsPanel;

    /** Default constructor sets up the UI. */
    @SuppressWarnings("NullAway.Init") // For complex Swing initialization patterns
    public Chrome(ContextManager contextManager) {
        this.contextManager = contextManager;
        this.activeContext = Context.EMPTY; // Initialize activeContext

        // 2) Build main window
        frame = newFrame("Brokk: Code Intelligence for AI");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
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
        systemMessageLabel = new JLabel(SYSMSG_EMPTY);
        systemMessageLabel.setBorder(new EmptyBorder(V_GLUE, H_PAD, V_GLUE, H_GAP));

        // Background status label (right side)
        backgroundStatusLabel = new JLabel(BGTASK_EMPTY);
        backgroundStatusLabel.setBorder(new EmptyBorder(V_GLUE, H_GAP, V_GLUE, H_PAD));

        // Panel to hold both labels
        var statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(systemMessageLabel, BorderLayout.CENTER);
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

        loadWindowSizeAndPosition();
        // Load saved theme, window size, and position
        frame.setTitle("Brokk: " + getProject().getRoot());

        // Show initial system message
        systemOutput("Opening project at " + getProject().getRoot());

        // Create workspace panel and project files panel
        workspacePanel = new WorkspacePanel(this, contextManager);
        projectFilesPanel = new ProjectFilesPanel(this, contextManager);

        // Create left vertical-tabbed pane for ProjectFiles and Git with vertical tab placement
        leftTabbedPanel = new JTabbedPane(JTabbedPane.LEFT);
        // Allow the divider to move further left by reducing the minimum width
        leftTabbedPanel.setMinimumSize(new Dimension(120, 0));
        var projectIcon = requireNonNull(SwingUtil.uiIcon("Brokk.folder_code"));
        leftTabbedPanel.addTab(null, projectIcon, projectFilesPanel);
        var projectTabIdx = leftTabbedPanel.indexOfComponent(projectFilesPanel);
        var projectTabLabel = createSquareTabLabel(projectIcon, "Project Files");
        leftTabbedPanel.setTabComponentAt(projectTabIdx, projectTabLabel);
        projectTabLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                leftTabbedPanel.setSelectedIndex(projectTabIdx);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                leftTabbedPanel.setSelectedIndex(projectTabIdx);
            }
        });

        // Add Git tab if available
        if (getProject().hasGit()) {
            gitPanel = new GitPanel(this, contextManager);
            var gitIcon = requireNonNull(SwingUtil.uiIcon("Brokk.commit"));
            leftTabbedPanel.addTab(null, gitIcon, gitPanel);
            var gitTabIdx = leftTabbedPanel.indexOfComponent(gitPanel);
            var gitTabLabel = createSquareTabLabel(gitIcon, "Git");
            leftTabbedPanel.setTabComponentAt(gitTabIdx, gitTabLabel);
            gitTabLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    leftTabbedPanel.setSelectedIndex(gitTabIdx);
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    leftTabbedPanel.setSelectedIndex(gitTabIdx);
                }
            });
            gitPanel.updateRepo();
            projectFilesPanel.updatePanel();
        } else {
            gitPanel = null;
        }

        // --- New top-level Pull-Requests panel ---------------------------------
        if (getProject().isGitHubRepo() && gitPanel != null) {
            pullRequestsPanel = new GitPullRequestsTab(this, contextManager, gitPanel);
            var prIcon = requireNonNull(SwingUtil.uiIcon("Brokk.pull_request"));
            leftTabbedPanel.addTab(null, prIcon, pullRequestsPanel);
            var prIdx = leftTabbedPanel.indexOfComponent(pullRequestsPanel);
            var prLabel = createSquareTabLabel(prIcon, "Pull Requests");
            leftTabbedPanel.setTabComponentAt(prIdx, prLabel);
            prLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    leftTabbedPanel.setSelectedIndex(prIdx);
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    leftTabbedPanel.setSelectedIndex(prIdx);
                }
            });
        }

        // --- New top-level Issues panel ----------------------------------------
        if (getProject().getIssuesProvider().type() != io.github.jbellis.brokk.issues.IssueProviderType.NONE
                && gitPanel != null) {
            issuesPanel = new GitIssuesTab(this, contextManager, gitPanel);
            var issIcon = requireNonNull(SwingUtil.uiIcon("Brokk.adjust"));
            leftTabbedPanel.addTab(null, issIcon, issuesPanel);
            var issIdx = leftTabbedPanel.indexOfComponent(issuesPanel);
            var issLabel = createSquareTabLabel(issIcon, "Issues");
            leftTabbedPanel.setTabComponentAt(issIdx, issLabel);
            issLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    leftTabbedPanel.setSelectedIndex(issIdx);
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    leftTabbedPanel.setSelectedIndex(issIdx);
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
        workspaceInstructionsSplit.setTopComponent(workspacePanel);
        workspaceInstructionsSplit.setBottomComponent(instructionsPanel);
        workspaceInstructionsSplit.setResizeWeight(0.583); // ~35 % Workspace / 25 % Instructions

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
        bottomSplitPane.setLeftComponent(leftTabbedPanel);
        bottomSplitPane.setRightComponent(outputStackSplit);
        bottomSplitPane.setResizeWeight(0.40); // keep roughly 40% for the left tabs when resizing
        bottomSplitPane.setDividerLocation(0.40); // initial 40% divider

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

        // Show the window
        frame.setVisible(true);
        frame.validate();
        frame.repaint();

        // After the frame is visible, (re)apply the 30 % divider if no saved position exists yet
        SwingUtilities.invokeLater(() -> {
            if (getProject().getHorizontalSplitPosition() == 0) {
                bottomSplitPane.setDividerLocation(0.3);
            }
        });

        // Possibly check if .gitignore is set
        if (getProject().hasGit()) {
            contextManager.submitBackgroundTask("Checking .gitignore", () -> {
                if (!getProject().isGitIgnoreSet()) {
                    SwingUtilities.invokeLater(() -> {
                        int result = JOptionPane.showConfirmDialog(
                                frame,
                                "Update .gitignore and add .brokk project files to git?",
                                "Git Configuration",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE);
                        if (result == JOptionPane.YES_OPTION) {
                            // Ensure gitPanel is not null before calling setupGitIgnore which uses it.
                            // setupGitIgnore has an internal assert, but this check is for external safety.
                            if (this.gitPanel != null) {
                                setupGitIgnore();
                            } else {
                                logger.warn("Attempted to setup .gitignore but GitPanel is null (no Git repo).");
                            }
                        }
                    });
                }
                return null;
            });
        }

        SwingUtilities.invokeLater(() -> MarkdownOutputPool.instance());
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
        if (gitPanel == null) {
            logger.warn("setupGitIgnore called when gitPanel is null. Skipping.");
            return;
        }
        contextManager.submitBackgroundTask("Updating .gitignore", () -> {
            try {
                var project = getProject();
                var gitRepo =
                        (GitRepo) project.getRepo(); // This is the repo for the current project (worktree or main)
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
                    systemOutput("Updated .gitignore with .brokk entries");

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
                systemOutput("Added shared .brokk project files (style.md, review.md, project.properties) to git");

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

        // Apply current theme based on project settings
        String currentTheme = MainProject.getTheme();
        logger.trace("Applying theme from project settings: {}", currentTheme);
        boolean isDark = GuiTheme.THEME_DARK.equalsIgnoreCase(currentTheme);
        switchTheme(isDark);
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
                if (ctx.getParsedOutput() != null) {
                    historyOutputPanel.setLlmOutput(ctx.getParsedOutput());
                } else {
                    historyOutputPanel.clearLlmOutput();
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

    public GuiTheme getTheme() {
        return themeManager;
    }

    @Override
    public List<ChatMessage> getLlmRawMessages(boolean includeReasoning) {
        if (SwingUtilities.isEventDispatchThread()) {
            return historyOutputPanel.getLlmRawMessages(includeReasoning);
        }

        // this can get interrupted at the end of a Code or Ask action, but we don't want to just throw
        // InterruptedException
        // because at this point we're basically done with the action and all that's left is reporting the result. So if
        // we're
        // unlucky enough to be interrupted at exactly the wrong time, we retry instead.
        while (true) {
            try {
                final CompletableFuture<List<ChatMessage>> future = new CompletableFuture<>();
                SwingUtilities.invokeAndWait(
                        () -> future.complete(historyOutputPanel.getLlmRawMessages(includeReasoning)));
                return future.get();
            } catch (InterruptedException e) {
                // retry
            } catch (ExecutionException | InvocationTargetException e) {
                logger.error(e);
                systemOutput("Error retrieving LLM messages");
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
            if (gitPanel != null) {
                gitPanel.getCommitTab().disableButtons();
            }
            blitzForgeMenuItem.setEnabled(false);
            blitzForgeMenuItem.setToolTipText("Waiting for current action to complete");
        });
    }

    @Override
    public void enableActionButtons() {
        SwingUtil.runOnEdt(() -> {
            instructionsPanel.enableButtons();
            if (gitPanel != null) {
                gitPanel.getCommitTab().enableButtons();
            }
            blitzForgeMenuItem.setEnabled(true);
            blitzForgeMenuItem.setToolTipText(null);
        });
    }

    @Override
    public void updateCommitPanel() {
        if (gitPanel != null) {
            gitPanel.updateCommitPanel();
        }
    }

    @Override
    public void updateGitRepo() {
        if (gitPanel != null) {
            gitPanel.updateRepo();
            projectFilesPanel.updatePanel();
        }
    }

    /** Recreate the top-level Issues panel (e.g. after provider change). */
    public void recreateIssuesPanel() {
        SwingUtilities.invokeLater(() -> {
            if (issuesPanel != null) {
                var idx = leftTabbedPanel.indexOfComponent(issuesPanel);
                if (idx != -1) leftTabbedPanel.remove(idx);
            }
            if (gitPanel == null) return; // safety
            issuesPanel = new GitIssuesTab(this, contextManager, gitPanel);
            var icon = requireNonNull(SwingUtil.uiIcon("Brokk.assignment"));
            leftTabbedPanel.addTab(null, icon, issuesPanel);
            var tabIdx = leftTabbedPanel.indexOfComponent(issuesPanel);
            var label = createSquareTabLabel(icon, "Issues");
            leftTabbedPanel.setTabComponentAt(tabIdx, label);
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    leftTabbedPanel.setSelectedIndex(tabIdx);
                }

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

        // Cmd/Ctrl+Z => undo
        var undoKeyStroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(undoKeyStroke, "globalUndo");
        rootPane.getActionMap().put("globalUndo", globalUndoAction);

        // Cmd/Ctrl+Shift+Z (or Cmd/Ctrl+Y) => redo
        var redoKeyStroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
        // For Windows/Linux, Ctrl+Y is also common for redo
        var redoYKeyStroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoKeyStroke, "globalRedo");
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoYKeyStroke, "globalRedo");
        rootPane.getActionMap().put("globalRedo", globalRedoAction);

        // Cmd/Ctrl+C => global copy
        var copyKeyStroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(copyKeyStroke, "globalCopy");
        rootPane.getActionMap().put("globalCopy", globalCopyAction);

        // Cmd/Ctrl+V => global paste
        var pasteKeyStroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(pasteKeyStroke, "globalPaste");
        rootPane.getActionMap().put("globalPaste", globalPasteAction);

        // Cmd/Ctrl+L => toggle microphone
        var toggleMicKeyStroke =
                io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_L);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(toggleMicKeyStroke, "globalToggleMic");
        rootPane.getActionMap().put("globalToggleMic", globalToggleMicAction);

        // Register IntelliJ-style shortcuts for switching sidebar panels
        // Determine the modifier based on platform (Cmd on Mac, Alt on Windows/Linux)
        int modifier =
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac")
                        ? KeyEvent.META_DOWN_MASK
                        : KeyEvent.ALT_DOWN_MASK;

        // Alt/Cmd+1 for Project Files
        var switchToProjectFiles = KeyStroke.getKeyStroke(KeyEvent.VK_1, modifier);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(switchToProjectFiles, "switchToProjectFiles");
        rootPane.getActionMap().put("switchToProjectFiles", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                leftTabbedPanel.setSelectedIndex(0); // Project Files is always at index 0
            }
        });

        // Alt/Cmd+2 for Git panel (if available)
        if (gitPanel != null) {
            var switchToGit = KeyStroke.getKeyStroke(KeyEvent.VK_2, modifier);
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(switchToGit, "switchToGit");
            rootPane.getActionMap().put("switchToGit", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var idx = leftTabbedPanel.indexOfComponent(gitPanel);
                    if (idx != -1) leftTabbedPanel.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+3 for Pull Requests panel (if available)
        if (pullRequestsPanel != null) {
            var switchToPR = KeyStroke.getKeyStroke(KeyEvent.VK_3, modifier);
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(switchToPR, "switchToPullRequests");
            rootPane.getActionMap().put("switchToPullRequests", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var idx = leftTabbedPanel.indexOfComponent(pullRequestsPanel);
                    if (idx != -1) leftTabbedPanel.setSelectedIndex(idx);
                }
            });
        }

        // Alt/Cmd+4 for Issues panel (if available)
        if (issuesPanel != null) {
            var switchToIssues = KeyStroke.getKeyStroke(KeyEvent.VK_4, modifier);
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(switchToIssues, "switchToIssues");
            rootPane.getActionMap().put("switchToIssues", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var idx = leftTabbedPanel.indexOfComponent(issuesPanel);
                    if (idx != -1) leftTabbedPanel.setSelectedIndex(idx);
                }
            });
        }
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
        SwingUtilities.invokeLater(() -> historyOutputPanel.appendLlmOutput(token, type, isNewMessage, isReasoning));
    }

    @Override
    public void setLlmOutput(ContextFragment.TaskFragment newOutput) {
        SwingUtilities.invokeLater(() -> historyOutputPanel.setLlmOutput(newOutput));
    }

    @Override
    public void toolError(String msg, String title) {
        logger.warn("%s: %s".formatted(msg, title));
        SwingUtilities.invokeLater(() -> systemNotify(msg, title, JOptionPane.ERROR_MESSAGE));
    }

    @Override
    public void systemOutput(String message) {
        logger.debug(message);
        systemOutputInternal(message);
    }

    private void systemOutputInternal(String message) {
        SwingUtilities.invokeLater(() -> {
            // Format timestamp as HH:MM
            String timestamp = LocalTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"));
            String timestampedMessage = timestamp + ": " + message;

            // Add to messages list
            systemMessages.add(timestampedMessage);

            // Keep only last 50 messages to prevent memory issues
            if (systemMessages.size() > 50) {
                systemMessages.removeFirst();
            }

            // Update label text (show only the latest message)
            systemMessageLabel.setText(timestampedMessage);

            // Update tooltip with all recent messages
            StringBuilder tooltipText = new StringBuilder("<html>");
            for (int i = Math.max(0, systemMessages.size() - 10); i < systemMessages.size(); i++) {
                if (i > Math.max(0, systemMessages.size() - 10)) {
                    tooltipText.append("<br>");
                }
                tooltipText.append(systemMessages.get(i));
            }
            tooltipText.append("</html>");
            systemMessageLabel.setToolTipText(tooltipText.toString());
        });
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
     * loading/saving bounds using the "preview" key, and visibility.
     *
     * @param contextManager The context manager for accessing project settings.
     * @param title The title for the JFrame.
     * @param contentComponent The JComponent to display within the frame.
     */
    public void showPreviewFrame(ContextManager contextManager, String title, JComponent contentComponent) {
        JFrame previewFrame = newFrame(title);
        previewFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        previewFrame.setContentPane(contentComponent);
        previewFrame.setBackground(themeManager.isDarkTheme() ? UIManager.getColor("chat_background") : Color.WHITE);

        var project = contextManager.getProject();
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

        // Add listener to save bounds using the "preview" key
        previewFrame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                project.savePreviewWindowBounds(previewFrame); // Save JFrame bounds
            }

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                project.savePreviewWindowBounds(previewFrame); // Save JFrame bounds
            }
        });

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

        // Add ESC key binding to close the window (delegates to windowClosing)
        var rootPane = previewFrame.getRootPane();
        var actionMap = rootPane.getActionMap();
        var inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeWindow");
        actionMap.put("closeWindow", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // Simulate window closing event to trigger the WindowListener logic
                previewFrame.dispatchEvent(new WindowEvent(previewFrame, WindowEvent.WINDOW_CLOSING));
            }
        });

        previewFrame.setVisible(true);
    }

    /**
     * Centralized method to open a preview for a specific ProjectFile. Reads the file, determines syntax, creates
     * PreviewTextPanel, and shows the frame.
     *
     * @param pf The ProjectFile to preview.
     */
    public void previewFile(ProjectFile pf) {
        assert SwingUtilities.isEventDispatchThread() : "Preview must be initiated on EDT";

        try {
            // 1. Read file content
            var content = pf.read();

            // 2. Deduce syntax style
            var syntax = pf.getSyntaxStyle();

            // 3. Build the PTP
            // 3. Build the PTP
            // Pass null for the fragment when previewing a file directly.
            // The fragment is primarily relevant when opened from the context table.
            var panel =
                    new PreviewTextPanel(contextManager, pf, content, syntax, themeManager, null); // Pass null fragment

            // 4. Show in frame using toString for the title
            showPreviewFrame(contextManager, "Preview: " + pf, panel);

        } catch (IOException ex) {
            toolError("Error reading file for preview: " + ex.getMessage());
            logger.error("Error reading file {} for preview", pf.absPath(), ex);
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
            if (isCurrentContext && fragment.getType().isPathFragment() && fragment instanceof FrozenFragment frozen) {
                workingFragment = frozen.unfreeze(contextManager);
            } else {
                workingFragment = fragment;
            }

            // Everything below operates on workingFragment
            var title = "Preview: " + workingFragment.description();

            // 2. Output-only fragments (Task / History / Search)
            if (workingFragment.getType().isOutputFragment()) {
                var outputFragment = (ContextFragment.OutputFragment) workingFragment;
                // var escapeHtml = outputFragment.isEscapeHtml();
                var combinedMessages = new ArrayList<ChatMessage>();

                for (TaskEntry entry : outputFragment.entries()) {
                    if (entry.isCompressed()) {

                        combinedMessages.add(Messages.create(
                                Objects.toString(entry.summary(), "Summary not available"), ChatMessageType.SYSTEM));
                    } else {
                        combinedMessages.addAll(castNonNull(entry.log()).messages());
                    }
                }

                var markdownPanel = MarkdownOutputPool.instance().borrow();
                markdownPanel.updateTheme(themeManager.isDarkTheme());
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
                var previewPanel =
                        new PreviewTextPanel(contextManager, null, ghf.text(), ghf.syntaxStyle(), themeManager, ghf);
                showPreviewFrame(contextManager, title, previewPanel);
                return;
            }

            // 5. Path fragments (files on disk) – live vs. snapshot decision
            if (workingFragment.getType().isPathFragment()) {
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
                            try {
                                var panel = new PreviewTextPanel(
                                        contextManager,
                                        null,
                                        externalFile.read(),
                                        externalFile.getSyntaxStyle(),
                                        themeManager,
                                        workingFragment);
                                showPreviewFrame(contextManager, "Preview: " + externalFile, panel);
                            } catch (IOException ex) {
                                toolError("Error reading external file: " + ex.getMessage());
                                logger.error("Error reading external file {}", externalFile.absPath(), ex);
                            }
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
                var snapshotPanel = new PreviewTextPanel(
                        contextManager,
                        null,
                        workingFragment.text(),
                        workingFragment.syntaxStyle(),
                        themeManager,
                        workingFragment);
                showPreviewFrame(contextManager, title, snapshotPanel);
                return;
            }

            // 6. Everything else (virtual fragments, skeletons, etc.)
            var previewPanel = new PreviewTextPanel(
                    contextManager,
                    null,
                    workingFragment.text(),
                    workingFragment.syntaxStyle(),
                    themeManager,
                    workingFragment);
            showPreviewFrame(contextManager, title, previewPanel);
        } catch (IOException ex) {
            toolError("Error reading fragment content: " + ex.getMessage());
            logger.error("Error reading fragment content for preview", ex);
        } catch (Exception ex) {
            logger.debug("Error opening preview", ex);
            toolError("Error opening preview: " + ex.getMessage());
        }
    }

    private void loadWindowSizeAndPosition() {
        var project = getProject();

        var boundsOptional = project.getMainWindowBounds();
        if (boundsOptional.isEmpty()) {
            // No valid saved bounds, apply default placement logic
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice defaultScreen = ge.getDefaultScreenDevice();
            Rectangle screenBounds = defaultScreen.getDefaultConfiguration().getBounds();
            logger.debug(
                    "No saved window bounds found for project. Detected screen size: {}x{} at ({},{})",
                    screenBounds.width,
                    screenBounds.height,
                    screenBounds.x,
                    screenBounds.y);

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
        } else {
            var bounds = boundsOptional.get();
            // Valid bounds found, use them
            frame.setSize(bounds.width, bounds.height);
            if (isPositionOnScreen(bounds.x, bounds.y)) {
                frame.setLocation(bounds.x, bounds.y);
                logger.debug("Restoring window position from saved bounds.");
            } else {
                // Saved position is off-screen, center instead
                frame.setLocationRelativeTo(null);
                logger.debug("Saved window position is off-screen, centering window.");
            }
        }

        // Listener to save bounds on move/resize
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                project.saveMainWindowBounds(frame);
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                project.saveMainWindowBounds(frame);
            }
        });

        SwingUtilities.invokeLater(() -> {
            // Load and set top split position (Instructions | Workspace)
            int topSplitPos = project.getLeftVerticalSplitPosition(); // Reuse this setting
            if (topSplitPos > 0) {
                topSplitPane.setDividerLocation(topSplitPos);
            } else {
                topSplitPane.setDividerLocation(0.583);
            }
            topSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                if (topSplitPane.isShowing()) {
                    var newPos = topSplitPane.getDividerLocation();
                    if (newPos > 0) {
                        project.saveLeftVerticalSplitPosition(newPos); // Reuse this setting
                    }
                }
            });

            // Load and set main vertical split position (Top | Bottom tabs)
            int mainVerticalPos = project.getRightVerticalSplitPosition(); // Reuse this setting
            if (mainVerticalPos > 0) {
                mainVerticalSplitPane.setDividerLocation(mainVerticalPos);
            } else {
                mainVerticalSplitPane.setDividerLocation(0.4);
            }
            mainVerticalSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                if (mainVerticalSplitPane.isShowing()) {
                    var newPos = mainVerticalSplitPane.getDividerLocation();
                    if (newPos > 0) {
                        project.saveRightVerticalSplitPosition(newPos); // Reuse this setting
                    }
                }
            });

            // Store reference to bottom split pane for position saving
            JSplitPane bottomSplitPane = (JSplitPane) mainVerticalSplitPane.getBottomComponent();

            // Load and set bottom horizontal split position (ProjectFiles/Git | Output)
            int bottomHorizPos = project.getHorizontalSplitPosition();
            if (bottomHorizPos > 0) {
                bottomSplitPane.setDividerLocation(bottomHorizPos);
            } else {
                bottomSplitPane.setDividerLocation(0.3);
            }
            bottomSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                if (bottomSplitPane.isShowing()) {
                    var newPos = bottomSplitPane.getDividerLocation();
                    if (newPos > 0) {
                        project.saveHorizontalSplitPosition(newPos);
                    }
                }
            });
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
        var messageSize = historyOutputPanel.getLlmRawMessages(true).size();
        SwingUtilities.invokeLater(() -> historyOutputPanel.setCopyButtonEnabled(messageSize > 0));
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
        SwingUtilities.invokeLater(() -> {
            historyOutputPanel.hideSpinner();
        });
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
        SwingUtilities.invokeLater(() -> instructionsPanel.requestCommandInputFocus());
    }

    public void toggleGitPanel() {
        if (gitPanel == null) {
            return;
        }

        // Switch to Git tab in the left vertical-tabbed pane
        for (int i = 0; i < leftTabbedPanel.getTabCount(); i++) {
            if ("Git".equals(leftTabbedPanel.getTitleAt(i))) {
                leftTabbedPanel.setSelectedIndex(i);
                break;
            }
        }
    }

    @Override
    public void updateWorkspace() {
        workspacePanel.updateContextTable();
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    public List<ContextFragment> getSelectedFragments() {
        return workspacePanel.getSelectedFragments();
    }

    @Nullable
    public GitPanel getGitPanel() { // Made public for WorkspacePanel access
        return gitPanel;
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

    /** Called by MenuBar after constructing the BlitzForge menu item. */
    public void setBlitzForgeMenuItem(JMenuItem blitzForgeMenuItem) {
        this.blitzForgeMenuItem = requireNonNull(blitzForgeMenuItem);
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
     * Creates a new JFrame with the Brokk icon set properly.
     *
     * @param title The title for the new frame
     * @return A configured JFrame with the application icon
     */
    public static JFrame newFrame(String title) {
        JFrame frame = new JFrame(title);
        applyIcon(frame);
        return frame;
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
        });
    }

    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        return showConfirmDialog(frame, message, title, optionType, messageType);
    }

    @Override
    public int showConfirmDialog(
            @Nullable JFrame frame, String message, String title, int optionType, int messageType) {
        //noinspection MagicConstant
        return JOptionPane.showConfirmDialog(frame, message, title, optionType, messageType);
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
        return label;
    }
}
