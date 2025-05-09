package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.dialogs.PreviewImagePanel;
import io.github.jbellis.brokk.gui.dialogs.PreviewTextPanel;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.util.SyntaxDetector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*; // Added WindowAdapter, WindowEvent
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Chrome implements AutoCloseable, IConsoleIO, IContextManager.ContextListener {
    private static final Logger logger = LogManager.getLogger(Chrome.class);

    // Used as the default text for the background tasks label
    private final String BGTASK_EMPTY = "No background tasks";

    // For collapsing/expanding the Git panel
    private int lastGitPanelDividerLocation = -1;

    // is the change of the context triggered by a user or the system?
    private boolean internalContextChange = true;

    // Dependencies:
    ContextManager contextManager;
    private Context activeContext; // Track the currently displayed context

    // Global Undo/Redo Actions
    private GlobalUndoAction globalUndoAction;
    private GlobalRedoAction globalRedoAction;
    // Global Copy/Paste Actions
    private GlobalCopyAction globalCopyAction;
    private GlobalPasteAction globalPasteAction;
    // necessary for undo/redo because clicking on menubar takes focus from whatever had it
    private Component lastRelevantFocusOwner = null;

    // Swing components:
    final JFrame frame;
    private JLabel backgroundStatusLabel;
    private Dimension backgroundLabelPreferredSize;
    private JPanel bottomPanel;

    private JSplitPane topSplitPane;
    private JSplitPane verticalSplitPane;
    private JSplitPane contextGitSplitPane;
    private HistoryOutputPanel historyOutputPanel;

    // Panels:
    private WorkspacePanel workspacePanel;
    private GitPanel gitPanel; // Will be null for dependency projects

    // Command input panel is now encapsulated in InstructionsPanel.
    private InstructionsPanel instructionsPanel;

    /**
     * Default constructor sets up the UI.
     * We call this from Brokk after creating contextManager, before creating the Coder,
     * and before calling .resolveCircularReferences(...).
     * We allow contextManager to be null for the initial empty UI.
     */
    public Chrome(ContextManager contextManager) {
        this.contextManager = contextManager;

        // 2) Build main window
        frame = newFrame("Brokk: Code Intelligence for AI");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(800, 1200);  // Taller than wide
        frame.setLayout(new BorderLayout());

        // 3) Main panel (top area + bottom area)
        frame.add(buildMainPanel(), BorderLayout.CENTER); // instructionsPanel is created here

        // Initialize global undo/redo actions now that instructionsPanel is available
        // contextManager is also available (passed in constructor)
        // contextPanel and historyOutputPanel will be null until onComplete
        this.globalUndoAction = new GlobalUndoAction("Undo");
        this.globalRedoAction = new GlobalRedoAction("Redo");
        this.globalCopyAction = new GlobalCopyAction("Copy");
        this.globalPasteAction = new GlobalPasteAction("Paste");

        // 4) Register global keyboard shortcuts
        // Global keyboard shortcuts will be registered in onComplete, after all components are set up
        // to ensure actions can correctly determine their initial enabled state.

        if (contextManager == null) {
            disableActionButtons();
        }
    }

    public Project getProject() {
        return contextManager == null ? null : contextManager.getProject();
    }

    public void onComplete() {
        loadWindowSizeAndPosition();
        if (contextManager == null) {
            frame.setTitle("Brokk (no project)");
            instructionsPanel.disableButtons(); // Ensure buttons disabled if no project/context
        } else {
            // Load saved theme, window size, and position
            frame.setTitle("Brokk: " + getProject().getRoot());

            // If the project uses Git, put the context panel and the Git panel in a split pane
            if (getProject().hasGit()) {
                contextGitSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
                contextGitSplitPane.setResizeWeight(0.7); // 70% for context panel

                workspacePanel = new WorkspacePanel(this, contextManager);
                contextGitSplitPane.setTopComponent(workspacePanel);

                gitPanel = new GitPanel(this, contextManager);
                contextGitSplitPane.setBottomComponent(gitPanel);

                bottomPanel.add(contextGitSplitPane, BorderLayout.CENTER);
                updateCommitPanel();
                gitPanel.updateRepo();
            } else {
                // No Git => only a context panel in the center
                gitPanel = null;
                workspacePanel = new WorkspacePanel(this, contextManager);
                bottomPanel.add(workspacePanel, BorderLayout.CENTER);
            }

            initializeThemeManager();

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
                if (newFocusOwner != null && instructionsPanel != null && workspacePanel != null && historyOutputPanel != null && historyOutputPanel.getLlmStreamArea() != null && historyOutputPanel.getHistoryTable() != null)
                {
                    if (newFocusOwner == instructionsPanel.getInstructionsArea()
                        || SwingUtilities.isDescendingFrom(newFocusOwner, workspacePanel)
                        || SwingUtilities.isDescendingFrom(newFocusOwner, historyOutputPanel.getHistoryTable())
                        || SwingUtilities.isDescendingFrom(newFocusOwner, historyOutputPanel.getLlmStreamArea())) // Check for LLM area
                    {
                        this.lastRelevantFocusOwner = newFocusOwner;
                    }
                    // else: lastRelevantFocusOwner remains unchanged if focus moves to a menu or irrelevant component
                }

                if (globalUndoAction != null) globalUndoAction.updateEnabledState();
                if (globalRedoAction != null) globalRedoAction.updateEnabledState();
                if (globalCopyAction != null) globalCopyAction.updateEnabledState();
                if (globalPasteAction != null) globalPasteAction.updateEnabledState();
            });

            // Listen for context changes (Chrome already implements IContextManager.ContextListener)
            contextManager.addContextListener(this);
        }

        // Build menu (now that everything else is ready)
        frame.setJMenuBar(MenuBar.buildMenuBar(this));

        // Register global keyboard shortcuts now that actions are fully initialized
        registerGlobalKeyboardShortcuts();

        // Show the window
        frame.setVisible(true);
        frame.validate();
        frame.repaint();

        // Possibly check if .gitignore is set
        if (getProject() != null && getProject().hasGit()) {
            contextManager.submitBackgroundTask("Checking .gitignore", () -> {
                if (!getProject().isGitIgnoreSet()) {
                    SwingUtilities.invokeLater(() -> {
                        int result = JOptionPane.showConfirmDialog(
                                frame,
                                "Update .gitignore and add .brokk project files to git?",
                                "Git Configuration",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE
                        );
                        if (result == JOptionPane.YES_OPTION) {
                            setupGitIgnore();
                        }
                    });
                }
                return null;
            });
        }
    }

    /**
     * Sets up .gitignore entries and adds .brokk project files to git
     */
    private void setupGitIgnore() {
        contextManager.submitBackgroundTask("Updating .gitignore", () -> {
            try {
                var gitRepo = (GitRepo) getProject().getRepo();
                var root = getProject().getRoot();

                // Update .gitignore
                var gitignorePath = root.resolve(".gitignore");
                String content = "";

                if (Files.exists(gitignorePath)) {
                    content = Files.readString(gitignorePath);
                    if (!content.endsWith("\n")) {
                        content += "\n";
                    }
                }

                // Add entries to .gitignore if they don't exist
                if (!content.contains(".brokk/**") && !content.contains(".brokk/")) {
                    content += "\n### BROKK'S CONFIGURATION ###\n";
                    content += ".brokk/**\n";
                    content += "!.brokk/style.md\n";
                    content += "!.brokk/project.properties\n";

                    Files.writeString(gitignorePath, content);
                    systemOutput("Updated .gitignore with .brokk entries");

                    // Add .gitignore to git if it's not already in the index
                    gitRepo.add(List.of(new ProjectFile(root, ".gitignore")));
                }

                // Create .brokk directory if it doesn't exist
                var brokkDir = root.resolve(".brokk");
                Files.createDirectories(brokkDir);

                // Add specific files to git
                var styleMdPath = brokkDir.resolve("style.md");
                var projectPropsPath = brokkDir.resolve("project.properties");

                // Create files if they don't exist (empty files)
                if (!Files.exists(styleMdPath)) {
                    Files.writeString(styleMdPath, "# Style Guide\n");
                }
                if (!Files.exists(projectPropsPath)) {
                    Files.writeString(projectPropsPath, "# Brokk project configuration\n");
                }

                // Add files to git
                var filesToAdd = new ArrayList<ProjectFile>();
                filesToAdd.add(new ProjectFile(root, ".brokk/style.md"));
                filesToAdd.add(new ProjectFile(root, ".brokk/project.properties"));

                gitRepo.add(filesToAdd);
                systemOutput("Added .brokk project files to git");

                // Update commit message
                gitPanel.setCommitMessageText("Update for Brokk project files");
                updateCommitPanel();
            } catch (Exception e) {
                logger.error("Error setting up git ignore", e);
                toolError("Error setting up git ignore: " + e.getMessage());
            }
        });
    }

    private void initializeThemeManager() {
        assert getProject() != null;

        logger.trace("Initializing theme manager");
        // JMHighlightPainter.initializePainters(); // Removed: Painters are now created dynamically with theme colors
        // Initialize theme manager now that all components are created
        // and contextManager should be properly set
        themeManager = new GuiTheme(frame, historyOutputPanel.getLlmScrollPane(), this);

        // Apply current theme based on project settings
        String currentTheme = Project.getTheme();
        logger.trace("Applying theme from project settings: {}", currentTheme);
        boolean isDark = THEME_DARK.equalsIgnoreCase(currentTheme);
        themeManager.applyTheme(isDark);
        historyOutputPanel.updateTheme(isDark);
    }

    /**
     * Build the main panel that includes:
     * - InstructionsPanel
     * - HistoryOutputPane
     * - the bottom area (context/git panel + status label)
     */
    private JPanel buildMainPanel() {
        var panel = new JPanel(new BorderLayout());

        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 2, 2, 2);

        // Top Area: Instructions + History/Output
        instructionsPanel = new InstructionsPanel(this);
        historyOutputPanel = new HistoryOutputPanel(this, contextManager);

        topSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        topSplitPane.setResizeWeight(0.4); // Instructions panel gets less space initially
        topSplitPane.setTopComponent(instructionsPanel);
        topSplitPane.setBottomComponent(historyOutputPanel);

        // Bottom Area: Context/Git + Status
        bottomPanel = new JPanel(new BorderLayout());
        // Status label at the very bottom
        var statusLabel = buildBackgroundStatusLabel();
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);
        // Center of bottomPanel will be filled in onComplete based on git presence

        // Main Vertical Split: Top Area / Bottom Area
        verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        verticalSplitPane.setResizeWeight(0.3); // Give top area (instructions/history) 30% initially
        verticalSplitPane.setTopComponent(topSplitPane);
        verticalSplitPane.setBottomComponent(bottomPanel);

        gbc.weighty = 1.0;
        gbc.gridy = 0;
        contentPanel.add(verticalSplitPane, gbc);

        panel.add(contentPanel, BorderLayout.CENTER);
        return panel;
    }


    /**
     * Lightweight method to preview a context without updating history
     * Only updates the LLM text area and context panel display
     */
    public void setContext(Context ctx) {
        assert ctx != null;

        // If the new context is logically distinct from the active one, update the text.
        // This prevents stomping on an active llm output since it will only be the case
        // if the user is selecting a different context, as opposed to a background task
        // updating the summary or autocontext.
        logger.trace("Loading context.  active={}, new={}", activeContext == null ? "null" : activeContext.getId(), ctx.getId());
        // If internalContextChange is true, it means it's a programmatic selection (new history item),
        // so don't force scroll. Otherwise (user click), do force scroll. Do not scroll the welcome message (id=1)
        boolean forceScrollToTop = !this.internalContextChange || ctx.getId() == 1;

        boolean resetOutput = (activeContext == null || activeContext.getId() != ctx.getId());
        activeContext = ctx;

        SwingUtilities.invokeLater(() -> {
            workspacePanel.populateContextTable(ctx);
            if (resetOutput) {
                if (ctx.getParsedOutput() != null) {
                    historyOutputPanel.resetLlmOutput(ctx.getParsedOutput(), forceScrollToTop);
                } else {
                    historyOutputPanel.clearLlmOutput();
                }
            }
            updateCaptureButtons();
        });
    }

    // Theme manager and constants
    GuiTheme themeManager;
    private static final String THEME_DARK = "dark";
    private static final String THEME_LIGHT = "light";

    public void switchTheme(boolean isDark) {
        themeManager.applyTheme(isDark);
        historyOutputPanel.updateTheme(isDark);
        for (Window window : Window.getWindows()) {
            if (window instanceof JFrame && window != frame) {
                Container contentPane = ((JFrame) window).getContentPane();
                if (contentPane instanceof PreviewTextPanel) {
                    ((PreviewTextPanel) contentPane).updateTheme(themeManager);
                }
            }
        }
    }

    @Override
    public String getLlmOutputText() {
        return SwingUtil.runOnEDT(() -> historyOutputPanel.getLlmOutputText(), null);
    }

    @Override
    public List<ChatMessage> getLlmRawMessages() {
        return SwingUtil.runOnEDT(() -> historyOutputPanel.getLlmRawMessages(), null);
    }

    private JComponent buildBackgroundStatusLabel() {
        backgroundStatusLabel = new JLabel(BGTASK_EMPTY);
        backgroundStatusLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
        backgroundLabelPreferredSize = backgroundStatusLabel.getPreferredSize();
        return backgroundStatusLabel;
    }

    /**
     * Retrieves the current text from the command input.
     */
    public String getInputText() {
        return instructionsPanel.getInstructions();
    }

    public void disableActionButtons() {
        instructionsPanel.disableButtons();
        if (gitPanel != null) {
            gitPanel.getCommitTab().disableButtons();
        }
    }

    public void enableActionButtons() {
        instructionsPanel.enableButtons();
        gitPanel.getCommitTab().enableButtons();
    }

    public void updateCommitPanel() {
        if (gitPanel != null) {
            gitPanel.updateCommitPanel();
        }
    }

    public void updateGitRepo() {
        if (gitPanel != null) {
            gitPanel.updateRepo();
        }
    }

    private void registerGlobalKeyboardShortcuts() {
        var rootPane = frame.getRootPane();

        // Cmd/Ctrl+Z => undo
        var undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(undoKeyStroke, "globalUndo");
        rootPane.getActionMap().put("globalUndo", globalUndoAction);

        // Cmd/Ctrl+Shift+Z (or Cmd/Ctrl+Y) => redo
        var redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                   Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
        // For Windows/Linux, Ctrl+Y is also common for redo
        var redoYKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoKeyStroke, "globalRedo");
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoYKeyStroke, "globalRedo");
        rootPane.getActionMap().put("globalRedo", globalRedoAction);

        // Cmd/Ctrl+C => global copy
        var copyKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(copyKeyStroke, "globalCopy");
        rootPane.getActionMap().put("globalCopy", globalCopyAction);

        // Cmd/Ctrl+V => global paste
        var pasteKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(pasteKeyStroke, "globalPaste");
        rootPane.getActionMap().put("globalPaste", globalPasteAction);
    }

    @Override
    public void actionOutput(String msg) {
        SwingUtilities.invokeLater(() -> {
            instructionsPanel.setCommandResultText(msg);
            logger.info(msg);
        });
    }

    @Override
    public void actionComplete() {
        SwingUtilities.invokeLater(() -> instructionsPanel.clearCommandResultText());
    }

    @Override
    public void toolErrorRaw(String msg) {
        systemOutput(msg);
    }

    @Override
    public void llmOutput(String token, ChatMessageType type) {
        // TODO: use messageSubType later on
        SwingUtilities.invokeLater(() -> historyOutputPanel.appendLlmOutput(token, type));
    }

    public void setLlmOutput(ContextFragment.TaskFragment newOutput) {
        SwingUtilities.invokeLater(() -> historyOutputPanel.setLlmOutput(newOutput));
    }

    @Override
    public void systemOutput(String message) {
        SwingUtilities.invokeLater(() -> instructionsPanel.appendSystemOutput(message));
    }

    public void backgroundOutput(String message) {
        SwingUtilities.invokeLater(() -> {
            if (message == null || message.isEmpty()) {
                backgroundStatusLabel.setText(BGTASK_EMPTY);
            } else {
                backgroundStatusLabel.setText(message);
            }
            backgroundStatusLabel.setPreferredSize(backgroundLabelPreferredSize);
        });
    }

    @Override
    public void close() {
        logger.info("Closing Chrome UI");
        if (contextManager != null) {
            contextManager.close();
        }
        if (frame != null) {
            frame.dispose();
        }
    }

    @Override
    public void contextChanged(Context newCtx) {
        SwingUtilities.invokeLater(() -> {
            // This method is called by ContextManager when its history might have changed
            // (e.g., after an undo/redo operation affecting context or any pushContext).
            // We need to ensure the global action states are updated even if focus didn't change.
            if (globalUndoAction != null) globalUndoAction.updateEnabledState();
            if (globalRedoAction != null) globalRedoAction.updateEnabledState();
            if (globalCopyAction != null) globalCopyAction.updateEnabledState();
            if (globalPasteAction != null) globalPasteAction.updateEnabledState();

            // Also update HistoryOutputPanel's local buttons
            if (historyOutputPanel != null) historyOutputPanel.updateUndoRedoButtonStates();

            // Update the main context table and history table display
            setContext(newCtx); // Handles contextPanel update and historyOutputPanel.resetLlmOutput
            updateContextHistoryTable(newCtx); // Handles historyOutputPanel.updateHistoryTable
        });
    }

    /**
     * Creates and shows a standard preview JFrame for a given component.
     * Handles title, default close operation, loading/saving bounds using the "preview" key,
     * and visibility.
     *
     * @param contextManager   The context manager for accessing project settings.
     * @param title            The title for the JFrame.
     * @param contentComponent The JComponent to display within the frame.
     */
    public void showPreviewFrame(ContextManager contextManager, String title, JComponent contentComponent) {
        JFrame previewFrame = newFrame(title);
        previewFrame.setContentPane(contentComponent);
        // Set initial default close operation. This will be checked/modified by the WindowListener.
        previewFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        var project = contextManager.getProject();
        assert project != null;
        var storedBounds = project.getPreviewWindowBounds(); // Use preview bounds
        if (storedBounds != null && storedBounds.width > 0 && storedBounds.height > 0) {
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

        // Add a WindowListener to handle the close ('X') button click
        previewFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Check if the content is a PreviewTextPanel and if it has unsaved changes
                if (contentComponent instanceof PreviewTextPanel ptp) {
                    if (!ptp.confirmClose()) {
                        // If confirmClose returns false (user cancelled), do nothing.
                        // We must explicitly set the default close operation here because
                        // the user might click 'X' multiple times.
                        previewFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    } else {
                        // If confirmClose returns true (Save/Don't Save), allow disposal.
                        previewFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                        // Note: The window listener only *vetoes* the close. If it doesn't veto,
                        // the default close operation takes over. We don't need to call dispose() here.
                    }
                } else {
                    // If not a PreviewTextPanel, just allow the default dispose operation
                    previewFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                }
            }
        });


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
     * Centralized method to open a preview for a specific ProjectFile.
     * Reads the file, determines syntax, creates PreviewTextPanel, and shows the frame.
     *
     * @param pf The ProjectFile to preview.
     */
    public void previewFile(ProjectFile pf) {
        assert pf != null;
        assert SwingUtilities.isEventDispatchThread() : "Preview must be initiated on EDT";

        try {
            // 1. Read file content
            var content = pf.read();

            // 2. Deduce syntax style
            var syntax = SyntaxDetector.detect(pf);

            // 3. Build the PTP
            // 3. Build the PTP
            // Pass null for the fragment when previewing a file directly.
            // The fragment is primarily relevant when opened from the context table.
            var panel = new PreviewTextPanel(
                    contextManager, pf, content,
                    syntax,
                    themeManager, null); // Pass null fragment

            // 4. Show in frame using toString for the title
            showPreviewFrame(contextManager, "Preview: " + pf, panel);

        } catch (IOException ex) {
            toolErrorRaw("Error reading file for preview: " + ex.getMessage());
            logger.error("Error reading file {} for preview", pf.absPath(), ex);
        } catch (Exception ex) {
            toolErrorRaw("Error opening file preview: " + ex.getMessage());
            logger.error("Unexpected error opening preview for file {}", pf.absPath(), ex);
        }
    }

    /**
     * Opens a preview window for a context fragment.
     * Uses PreviewTextPanel for text fragments and PreviewImagePanel for image fragments.
     * Uses MarkdownOutputPanel for Markdown and Diff fragments.
     *
     * @param fragment The fragment to preview.
     */
    public void openFragmentPreview(ContextFragment fragment) {
        try {
            String title = "Preview: " + fragment.description();

            if (fragment instanceof ContextFragment.OutputFragment outputFragment) {
                // Create a panel to hold all message panels
                JPanel messagesContainer = new JPanel();
                messagesContainer.setLayout(new BoxLayout(messagesContainer, BoxLayout.Y_AXIS));
                messagesContainer.setBackground(themeManager != null && themeManager.isDarkTheme() ?
                                                UIManager.getColor("Panel.background") : Color.WHITE);

                // Get all messages and create a MarkdownOutputPanel for each
                List<TaskEntry> taskEntries = outputFragment.entries();
                for (TaskEntry entry : taskEntries) {
                    var markdownPanel = new MarkdownOutputPanel();
                    markdownPanel.updateTheme(themeManager != null && themeManager.isDarkTheme());
                    markdownPanel.setText(entry);
                    markdownPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));
                    messagesContainer.add(markdownPanel);
                }

                // Wrap in a scroll pane
                var scrollPane = new JScrollPane(messagesContainer);
                scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                scrollPane.getVerticalScrollBar().setUnitIncrement(16);

                showPreviewFrame(contextManager, title, scrollPane); // Use helper
                return;
            }

            if (!fragment.isText()) {
                // Handle image fragments
                if (fragment instanceof ContextFragment.PasteImageFragment pif) {
                    var imagePanel = new PreviewImagePanel(contextManager, null, themeManager);
                    imagePanel.setImage(pif.image());
                    showPreviewFrame(contextManager, title, imagePanel); // Use helper
                } else if (fragment instanceof ContextFragment.ImageFileFragment iff) {
                    // PreviewImagePanel has its own static showInFrame that uses showPreviewFrame
                    PreviewImagePanel.showInFrame(frame, contextManager, iff.file(), themeManager);
                }
                return;
            }

            // Handle text fragments
            String content = fragment.text();
            io.github.jbellis.brokk.analyzer.ProjectFile file = null;
            // Handle PathFragment using the unified previewFile method
            if (fragment instanceof ContextFragment.PathFragment pf) {
                 // Ensure we are on the EDT before calling previewFile
                if (!SwingUtilities.isEventDispatchThread()) {
                    SwingUtilities.invokeLater(() -> previewFile((ProjectFile) pf.file()));
                } else {
                    previewFile((ProjectFile) pf.file());
                }
                return; // previewFile handles showing the frame
            }

            // Handle other text-based fragments (e.g., VirtualFragment, GitFileFragment)
            String syntaxStyle = fragment.syntaxStyle();
            // Check specifically for GitFileFragment to get the associated file, otherwise null
            file = (fragment instanceof ContextFragment.GitFileFragment gff) ? (ProjectFile) gff.file() : null;
            var previewPanel = new PreviewTextPanel(contextManager, file, content, syntaxStyle, themeManager, fragment);
            showPreviewFrame(contextManager, title, previewPanel); // Use helper for these too

        } catch (IOException ex) { // IOException mainly from fragment.text()
            toolErrorRaw("Error reading fragment content: " + ex.getMessage());
            logger.error("Error reading fragment content for preview", ex);
        } catch (Exception ex) {
            logger.debug("Error opening preview", ex);
            toolErrorRaw("Error opening preview: " + ex.getMessage());
        }
    }

    private void loadWindowSizeAndPosition() {
        var project = getProject();

        var boundsOptional = project == null ? Optional.<Rectangle>empty() : project.getMainWindowBounds();
        if (boundsOptional.isEmpty()) {
            // No valid saved bounds, apply default placement logic
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice defaultScreen = ge.getDefaultScreenDevice();
            Rectangle screenBounds = defaultScreen.getDefaultConfiguration().getBounds();
            logger.debug("No saved window bounds found for project. Detected screen size: {}x{} at ({},{})",
                         screenBounds.width, screenBounds.height, screenBounds.x, screenBounds.y);

            // Default to 1920x1080 or screen size, whichever is smaller, and center.
            int defaultWidth = Math.min(1920, screenBounds.width);
            int defaultHeight = Math.min(1080, screenBounds.height);

            int x = screenBounds.x + (screenBounds.width - defaultWidth) / 2;
            int y = screenBounds.y + (screenBounds.height - defaultHeight) / 2;

            frame.setBounds(x, y, defaultWidth, defaultHeight);
            logger.debug("Applying default window placement: {}x{} at ({},{}), centered on screen.",
                         defaultWidth, defaultHeight, x, y);
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

        if (project == null) {
            return;
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
            int topSplitPos = project.getTopSplitPosition();
            if (topSplitPos > 0) {
                topSplitPane.setDividerLocation(topSplitPos);
            } else {
                topSplitPane.setDividerLocation(0.4); // Sensible default: 40% for instructions panel
            }
            topSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                if (topSplitPane.isShowing()) {
                    var newPos = topSplitPane.getDividerLocation();
                    if (newPos > 0) {
                        project.saveTopSplitPosition(newPos);
                    }
                }
            });

            int verticalPos = project.getVerticalSplitPosition();
            if (verticalPos > 0) {
                verticalSplitPane.setDividerLocation(verticalPos);
            } else {
                verticalSplitPane.setDividerLocation(0.3);
            }
            verticalSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                if (verticalSplitPane.isShowing()) {
                    var newPos = verticalSplitPane.getDividerLocation();
                    if (newPos > 0) {
                        project.saveVerticalSplitPosition(newPos);
                    }
                }
            });

            if (contextGitSplitPane != null) {
                int contextGitPos = project.getContextGitSplitPosition();
                if (contextGitPos > 0) {
                    contextGitSplitPane.setDividerLocation(contextGitPos);
                } else {
                    contextGitSplitPane.setDividerLocation(0.7);
                }

                contextGitSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                    if (contextGitSplitPane.isShowing()) {
                        var newPos = contextGitSplitPane.getDividerLocation();
                        if (newPos > 0) {
                            project.saveContextGitSplitPosition(newPos);
                        }
                    }
                });
            }
        });
    }

    public void updateContextHistoryTable() {
        Context selectedContext = contextManager.selectedContext();
        updateContextHistoryTable(selectedContext);
    }

    public void updateContextHistoryTable(Context contextToSelect) {
        this.internalContextChange = true;
        try {
            historyOutputPanel.updateHistoryTable(contextToSelect);
        } finally {
            SwingUtilities.invokeLater(() -> this.internalContextChange = false);
        }
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
        String text = historyOutputPanel.getLlmOutputText();
        SwingUtilities.invokeLater(() -> historyOutputPanel.setCopyButtonEnabled(!text.isBlank()));
    }

    public JFrame getFrame() {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        return frame;
    }

    /**
     * Shows the inline loading spinner in the output panel.
     */
    public void showOutputSpinner(String message) {
        SwingUtilities.invokeLater(() -> {
            if (historyOutputPanel != null) {
                historyOutputPanel.showSpinner(message);
            }
        });
    }

    /**
     * Hides the inline loading spinner in the output panel.
     */
    public void hideOutputSpinner() {
        SwingUtilities.invokeLater(() -> {
            if (historyOutputPanel != null) {
                historyOutputPanel.hideSpinner();
            }
        });
    }

    public void focusInput() {
        SwingUtilities.invokeLater(() -> instructionsPanel.requestCommandInputFocus());
    }

    public void toggleGitPanel() {
        if (contextGitSplitPane == null) {
            return;
        }

        lastGitPanelDividerLocation = contextGitSplitPane.getDividerLocation();
        var totalHeight = contextGitSplitPane.getHeight();
        var dividerSize = contextGitSplitPane.getDividerSize();
        contextGitSplitPane.setDividerLocation(totalHeight - dividerSize - 1);

        logger.debug("Git panel collapsed; stored divider location={}", lastGitPanelDividerLocation);

        contextGitSplitPane.revalidate();
        contextGitSplitPane.repaint();
    }

    public void updateContextTable() {
        if (workspacePanel != null) {
            workspacePanel.updateContextTable();
        }
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    public List<ContextFragment> getSelectedFragments() {
        return workspacePanel.getSelectedFragments();
    }

    GitPanel getGitPanel() {
        return gitPanel;
    }

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

    private boolean isFocusInContextArea(Component focusOwner) {
        if (focusOwner == null) return false;
        // Check if focus is within ContextPanel or HistoryOutputPanel's historyTable
        boolean inContextPanel = workspacePanel != null && SwingUtilities.isDescendingFrom(focusOwner, workspacePanel);
        boolean inHistoryTable = historyOutputPanel != null && historyOutputPanel.getHistoryTable() != null &&
                                 SwingUtilities.isDescendingFrom(focusOwner, historyOutputPanel.getHistoryTable());
        return inContextPanel || inHistoryTable;
    }

    private boolean isFocusInTextCopyableArea(Component focusOwner) {
        if (focusOwner == null) return false;
        boolean inCommandInput = instructionsPanel != null && instructionsPanel.getInstructionsArea() != null &&
                                 lastRelevantFocusOwner == instructionsPanel.getInstructionsArea();
        boolean inLlmStreamArea = historyOutputPanel != null && historyOutputPanel.getLlmStreamArea() != null &&
                                 SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, historyOutputPanel.getLlmStreamArea());
        return inCommandInput || inLlmStreamArea;
    }


    // --- Global Undo/Redo Action Classes ---
    private class GlobalUndoAction extends AbstractAction {
        public GlobalUndoAction(String name) {
            super(name);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (instructionsPanel != null && lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                if (instructionsPanel.getCommandInputUndoManager().canUndo()) {
                    instructionsPanel.getCommandInputUndoManager().undo();
                }
            } else if (contextManager != null && isFocusInContextArea(lastRelevantFocusOwner)) {
                if (contextManager.getContextHistory().hasUndoStates()) {
                    contextManager.undoContextAsync();
                }
            }
        }

        public void updateEnabledState() {
            boolean canUndoNow = false;
            if (instructionsPanel != null && lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                canUndoNow = instructionsPanel.getCommandInputUndoManager().canUndo();
            } else if (contextManager != null && isFocusInContextArea(lastRelevantFocusOwner)) {
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
            if (instructionsPanel != null && lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                if (instructionsPanel.getCommandInputUndoManager().canRedo()) {
                    instructionsPanel.getCommandInputUndoManager().redo();
                }
            } else if (contextManager != null && isFocusInContextArea(lastRelevantFocusOwner)) {
                if (contextManager.getContextHistory().hasRedoStates()) {
                    contextManager.redoContextAsync();
                }
            }
        }

        public void updateEnabledState() {
            boolean canRedoNow = false;
            if (instructionsPanel != null && lastRelevantFocusOwner == instructionsPanel.getInstructionsArea()) {
                canRedoNow = instructionsPanel.getCommandInputUndoManager().canRedo();
            } else if (contextManager != null && isFocusInContextArea(lastRelevantFocusOwner)) {
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
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel) ||
                        SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, historyOutputPanel.getHistoryTable())) {
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
                canCopyNow = (field.getSelectedText() != null && !field.getSelectedText().isEmpty()) || !field.getText().isEmpty();
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, historyOutputPanel.getLlmStreamArea())) {
                var llmArea = historyOutputPanel.getLlmStreamArea();
                String selectedText = llmArea.getSelectedText();
                canCopyNow = (selectedText != null && !selectedText.isEmpty()) || !llmArea.getDisplayedText().isEmpty();
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel) ||
                        SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, historyOutputPanel.getHistoryTable())) {
                // ContextPanel's copy action is enabled if contextManager is available,
                // as it can copy the goal even if the context itself is empty.
                canCopyNow = contextManager != null;
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
                canPasteNow = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor);
            } else if (SwingUtilities.isDescendingFrom(lastRelevantFocusOwner, workspacePanel)) {
                // ContextPanel's doPasteAction checks clipboard content type. Enable if CM is available.
                canPasteNow = contextManager != null;
            }
            setEnabled(canPasteNow);
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
     * Creates a new JDialog with the Brokk icon set properly.
     *
     * @param owner The parent Frame for this dialog
     * @param title The title for the new dialog
     * @param modal Whether the dialog should be modal
     * @return A configured JDialog with the application icon
     */
    public static JDialog newDialog(Frame owner, String title, boolean modal) {
        JDialog dialog = new JDialog(owner, title, modal);
        applyIcon(dialog);
        return dialog;
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
    public void disableHistoryPanel() {
        if (historyOutputPanel != null) {
            historyOutputPanel.disableHistory();
        }
    }

    /**
     * Enables the history panel via HistoryOutputPanel.
     */
    public void enableHistoryPanel() {
        if (historyOutputPanel != null) {
            historyOutputPanel.enableHistory();
        }
    }

    /**
     * Sets the blocking state on the MarkdownOutputPanel to prevent clearing/resetting during operations.
     *
     * @param blocked true to prevent clear/reset operations, false to allow them
     */
    public void blockLlmOutput(boolean blocked) {
        // Ensure that prev setText calls are processed before blocking => we need the invokeLater
        SwingUtilities.invokeLater(() -> {
            if (historyOutputPanel != null) {
                historyOutputPanel.setMarkdownOutputPanelBlocking(blocked);
            }
        });
    }
}
