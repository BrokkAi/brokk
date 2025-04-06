package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.dialogs.PreviewPanel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

public class Chrome implements AutoCloseable, IConsoleIO {
    private static final Logger logger = LogManager.getLogger(Chrome.class);

    // Used as the default text for the background tasks label
    private final String BGTASK_EMPTY = "No background tasks";

    // For collapsing/expanding the Git panel
    private int lastGitPanelDividerLocation = -1;

    // Dependencies:
    ContextManager contextManager;
    private Context activeContext; // Track the currently displayed context

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
    private ContextPanel contextPanel;
    private GitPanel gitPanel; // Will be null for dependency projects

    // Command input panel is now encapsulated in InstructionsPanel.
    private InstructionsPanel instructionsPanel;

    // Track the currently running user-driven future (Code/Ask/Search/Run)
    volatile Future<?> currentUserTask;

    /**
     * Enum representing the different types of context actions that can be performed.
     * This replaces the use of magic strings when calling performContextActionAsync.
     */
    public enum ContextAction {
        EDIT, READ, SUMMARIZE, DROP, COPY, PASTE
    }

    /**
     * Default constructor sets up the UI.
     * We call this from Brokk after creating contextManager, before creating the Coder,
     * and before calling .resolveCircularReferences(...).
     * We allow contextManager to be null for the initial empty UI.
     */
    public Chrome(ContextManager contextManager) {
        this.contextManager = contextManager;

        // 1) Set FlatLaf Look & Feel - we'll use light as default initially
        try {
            com.formdev.flatlaf.FlatLightLaf.setup();
        } catch (Exception e) {
            logger.warn("Failed to set LAF, using default", e);
        }

        // 2) Build main window
        frame = new JFrame("Brokk: Code Intelligence for AI");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(800, 1200);  // Taller than wide
        frame.setLayout(new BorderLayout());

        // Set application icon
        try {
            var iconUrl = getClass().getResource(Brokk.ICON_RESOURCE);
            if (iconUrl != null) {
                var icon = new ImageIcon(iconUrl);
                frame.setIconImage(icon.getImage());
            } else {
                logger.warn("Could not find resource {}", Brokk.ICON_RESOURCE);
            }
        } catch (Exception e) {
            logger.warn("Failed to set application icon", e);
        }

        // 3) Main panel (top area + bottom area)
        frame.add(buildMainPanel(), BorderLayout.CENTER);

        // 4) Register global keyboard shortcuts
        registerGlobalKeyboardShortcuts();

        if (contextManager == null) {
            instructionsPanel.disableButtons();
            // Context action buttons are now in menus – no direct disabling needed here.
        }
    }

    public Project getProject() {
        return contextManager == null ? null : contextManager.getProject();
    }

    /**
     * Allows InstructionsPanel to update the current user task.
     */
    public void setCurrentUserTask(Future<?> task) {
        this.currentUserTask = task;
    }

    public void onComplete() {
        // Initialize model dropdown via InstructionsPanel
        instructionsPanel.initializeModels();

        if (contextManager == null) {
            frame.setTitle("Brokk (no project)");
            instructionsPanel.disableButtons(); // Ensure buttons disabled if no project/context
        } else {
            // Load saved theme, window size, and position
            frame.setTitle("Brokk: " + getProject().getRoot());
            loadWindowSizeAndPosition();

            // If the project uses Git, put the context panel and the Git panel in a split pane
            if (getProject().hasGit()) {
                contextGitSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
                contextGitSplitPane.setResizeWeight(0.7); // 70% for context panel

                contextPanel = new ContextPanel(this, contextManager);
                contextGitSplitPane.setTopComponent(contextPanel);

                gitPanel = new GitPanel(this, contextManager);
                contextGitSplitPane.setBottomComponent(gitPanel);

                bottomPanel.add(contextGitSplitPane, BorderLayout.CENTER);
                updateCommitPanel();
                gitPanel.updateRepo();
            } else {
                // No Git => only a context panel in the center
                gitPanel = null;
                contextPanel = new ContextPanel(this, contextManager);
                bottomPanel.add(contextPanel, BorderLayout.CENTER);
            }

            initializeThemeManager();

            // Force layout update for the bottom panel
            bottomPanel.revalidate();
            bottomPanel.repaint();
        }

        // Build menu (now that everything else is ready)
        frame.setJMenuBar(MenuBar.buildMenuBar(this));

        // Show the window
        frame.setVisible(true);
        frame.validate();
        frame.repaint();

        // Set focus to command input field on startup
        instructionsPanel.requestCommandInputFocus();

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
        contextManager.submitUserTask("Updating .gitignore", () -> {
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
                SwingUtilities.invokeLater(() -> {
                    gitPanel.setCommitMessageText("Update for Brokk project files");
                    updateCommitPanel();
                });

            } catch (Exception e) {
                logger.error("Error setting up git ignore", e);
                toolError("Error setting up git ignore: " + e.getMessage());
            }
        });
    }

    private void initializeThemeManager() {
        assert getProject() != null;

        logger.debug("Initializing theme manager");
        // JMHighlightPainter.initializePainters(); // Removed: Painters are now created dynamically with theme colors
        // Initialize theme manager now that all components are created
        // and contextManager should be properly set
        themeManager = new GuiTheme(frame, historyOutputPanel.getLlmScrollPane(), this);

        // Apply current theme based on project settings
        String currentTheme = Project.getTheme();
        logger.debug("Applying theme from project settings: {}", currentTheme);
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
    public void loadContext(Context ctx) {
        assert ctx != null;

        // If the new context is logically distinct from the active one, update the text.
        // This prevents stomping on an active llm output since it will only be the case
        // if the user is selecting a different context, as opposed to a background task
        // updating the summary or autocontext.
        logger.debug("Loading context.  active={}, new={}", activeContext == null ? "null" : activeContext.getId(), ctx.getId());
        boolean resetOutput = (activeContext == null || activeContext.getId() != ctx.getId());
        activeContext = ctx;

        SwingUtilities.invokeLater(() -> {
            contextPanel.populateContextTable(ctx);
            if (resetOutput) {
                historyOutputPanel.resetLlmOutput(ctx.getParsedOutput() == null ? "" : ctx.getParsedOutput().output());
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
                if (contentPane instanceof PreviewPanel) {
                    ((PreviewPanel) contentPane).updateTheme(themeManager);
                }
            }
        }
    }

    public String getLlmOutputText() {
        return SwingUtil.runOnEDT(() -> historyOutputPanel.getLlmOutputText(), null);
    }

    private JComponent buildBackgroundStatusLabel() {
        backgroundStatusLabel = new JLabel(BGTASK_EMPTY);
        backgroundStatusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        backgroundStatusLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
        backgroundLabelPreferredSize = backgroundStatusLabel.getPreferredSize();
        return backgroundStatusLabel;
    }

    /**
     * Retrieves the current text from the command input.
     */
    public String getInputText() {
        return instructionsPanel.getInputText();
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            historyOutputPanel.clear();
        });
    }

    public void disableUserActionButtons() {
        instructionsPanel.disableButtons();
    }

    public void enableUserActionButtons() {
        instructionsPanel.enableButtons();
    }

    /**
     * Cancels the currently running user-driven Future (Go/Ask/Search), if any
     */
    void stopCurrentUserTask() {
        if (currentUserTask != null && !currentUserTask.isDone()) {
            currentUserTask.cancel(true);
        }
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
        rootPane.getActionMap().put("globalUndo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                instructionsPanel.disableButtons();
                currentUserTask = contextManager.undoContextAsync();
            }
        });

        // Cmd/Ctrl+Shift+Z => redo
        var redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                   Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoKeyStroke, "globalRedo");
        rootPane.getActionMap().put("globalRedo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                instructionsPanel.disableButtons();
                currentUserTask = contextManager.redoContextAsync();
            }
        });

        // Cmd/Ctrl+V => paste
        var pasteKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(pasteKeyStroke, "globalPaste");
        rootPane.getActionMap().put("globalPaste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentUserTask = contextManager.performContextActionAsync(ContextAction.PASTE, List.of());
            }
        });
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
        hideOutputSpinner(); // Ensure spinner is hidden on completion
        SwingUtilities.invokeLater(() -> instructionsPanel.clearCommandResultText());
    }

    @Override
    public void toolErrorRaw(String msg) {
        hideOutputSpinner(); // Ensure spinner is hidden on error
        systemOutput(msg);
    }

    @Override
    public void llmOutput(String token) {
        // Hide spinner on first token
        if (token != null && !token.isEmpty()) {
            hideOutputSpinner();
        }
        SwingUtilities.invokeLater(() -> historyOutputPanel.appendLlmOutput(token));
    }

    public void setLlmOutput(String text) {
        SwingUtilities.invokeLater(() -> historyOutputPanel.setLlmOutput(text));
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

    /**
     * Opens a preview window for a context fragment
     *
     * @param fragment   The fragment to preview
     * @param syntaxType The syntax highlighting style to use
     */
    /**
     * Opens a preview window for a context fragment.
     * For RepoPathFragments with a ProjectFile, uses showInDialog; otherwise, it calls showDialog.
     *
     * @param fragment   The fragment to preview.
     * @param syntaxType The syntax highlighting style to use.
     */
    public void openFragmentPreview(ContextFragment fragment, String syntaxType) {
        try {
            if (fragment instanceof ContextFragment.ProjectPathFragment(ProjectFile file)) {
                PreviewPanel.showInFrame(frame, contextManager, file, syntaxType, themeManager);
            } else {
                String content = fragment.text();
                String title = "Preview: " + fragment.description();
                PreviewPanel previewPanel = new PreviewPanel(contextManager, null, content, syntaxType, themeManager);
                PreviewPanel.showFrame(contextManager, title, previewPanel);
            }
        } catch (IOException ex) {
            toolErrorRaw("Error reading fragment content: " + ex.getMessage());
        } catch (Exception ex) {
            toolErrorRaw("Error opening preview: " + ex.getMessage());
        }
    }

    private void loadWindowSizeAndPosition() {
        var project = getProject();
        if (project == null) {
            frame.setLocationRelativeTo(null);
            return;
        }

        var bounds = project.getMainWindowBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            frame.setLocationRelativeTo(null);
        } else {
            frame.setSize(bounds.width, bounds.height);
            if (bounds.x >= 0 && bounds.y >= 0 && isPositionOnScreen(bounds.x, bounds.y)) {
                frame.setLocation(bounds.x, bounds.y);
            } else {
                frame.setLocationRelativeTo(null);
            }
        }

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
                topSplitPane.setDividerLocation(0.2);
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
        historyOutputPanel.updateHistoryTable(contextToSelect);
    }

    private boolean isPositionOnScreen(int x, int y) {
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
        if (historyOutputPanel != null) {
            historyOutputPanel.showSpinner(message);
        }
    }

    /**
     * Hides the inline loading spinner in the output panel.
     */
    public void hideOutputSpinner() {
        if (historyOutputPanel != null) {
            historyOutputPanel.hideSpinner();
        }
    }

    public void focusInput() {
        SwingUtilities.invokeLater(() -> instructionsPanel.requestCommandInputFocus());
    }

    public void setCommitMessageText(String message) {
        SwingUtilities.invokeLater(() -> {
            if (gitPanel != null) {
                gitPanel.setCommitMessageText(message);
            }
        });
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
        if (contextPanel != null) {
            contextPanel.updateContextTable();
        }
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    public List<ContextFragment> getSelectedFragments() {
        return contextPanel.getSelectedFragments();
    }

    GitPanel getGitPanel() {
        return gitPanel;
    }

    public void showSetAutoContextSizeDialog() {
        var dialog = new JDialog(getFrame(), "Set AutoContext Size", true);
        dialog.setLayout(new BorderLayout());

        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var label = new JLabel("Enter autocontext size (0-100):");
        panel.add(label, BorderLayout.NORTH);

        var spinner = new JSpinner(new SpinnerNumberModel(
                contextManager.selectedContext().getAutoContextFileCount(),
                0, 100, 1
        ));
        panel.add(spinner, BorderLayout.CENTER);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");

        okButton.addActionListener(ev -> {
            var newSize = (int) spinner.getValue();
            contextManager.setAutoContextFilesAsync(newSize);
            dialog.dispose();
        });

        cancelButton.addActionListener(ev -> dialog.dispose());
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(getFrame());
        dialog.setVisible(true);
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
}
