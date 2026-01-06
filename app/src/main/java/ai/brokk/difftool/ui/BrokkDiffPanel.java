package ai.brokk.difftool.ui;

import ai.brokk.*;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragments;
import ai.brokk.difftool.doc.AbstractBufferDocument;
import ai.brokk.difftool.doc.BufferDocumentIF;
import ai.brokk.difftool.node.JMDiffNode;
import ai.brokk.difftool.ui.unified.UnifiedDiffDocument;
import ai.brokk.difftool.ui.unified.UnifiedDiffPanel;
import ai.brokk.git.GitRepo;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.EditorFontSizeControl;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.FontSizeAware;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.GitDiffUiUtil;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.util.ContentDiffUtils;
import ai.brokk.util.GlobalUiSettings;
import ai.brokk.util.Messages;
import ai.brokk.util.ReviewParser;
import ai.brokk.util.SyntaxDetector;
import dev.langchain4j.data.message.ChatMessage;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.JToggleButton;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

public class BrokkDiffPanel extends JPanel
        implements ThemeAware, EditorFontSizeControl, FontSizeAware, DiffProjectFileNavigationTarget {
    private static final Logger logger = LogManager.getLogger(BrokkDiffPanel.class);
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;
    private final JSplitPane mainSplitPane;
    private final FileTreePanel fileTreePanel;
    private boolean started;
    private final JLabel loadingLabel = createLoadingLabel();
    private final JToggleButton viewModeToggle = new JToggleButton("Unified View");

    // Tools menu items
    private final JCheckBoxMenuItem menuShowBlame = new JCheckBoxMenuItem("Show Git Blame");
    private final JCheckBoxMenuItem menuShowAllLines = new JCheckBoxMenuItem("Show All Lines");
    private final JCheckBoxMenuItem menuShowBlankLineDiffs = new JCheckBoxMenuItem("Show Empty Line Diffs");

    // Settings loaded from GlobalUiSettings on start()
    private boolean globalShowAllLinesInUnified;
    private boolean isUnifiedView;

    // Toolbar for UI controls
    @Nullable
    private JToolBar toolBar;

    // Refactored state management
    private final DiffDisplayCore core;
    private final boolean isMultipleCommitsContext;
    private final boolean forceFileTree;

    public BrokkDiffPanel(Builder builder, GuiTheme theme) {
        this.contextManager = builder.contextManager;
        this.isMultipleCommitsContext = builder.isMultipleCommitsContext;
        this.forceFileTree = builder.forceFileTree;

        // Initialize core logic
        this.core = new DiffDisplayCore(
                this,
                contextManager,
                theme,
                builder.fileComparisons,
                isMultipleCommitsContext,
                builder.initialFileIndex);

        // Initialize blame service if we have a git repo
        if (contextManager.getProject().getRepo() instanceof GitRepo gitRepo) {
            this.blameService = new BlameService(gitRepo.getGit());
        } else {
            this.blameService = null;
        }

        this.currentDiffPanel = null;

        // Make the container focusable, so it can handle key events
        setFocusable(true);
        tabbedPane = new JTabbedPane();
        tabbedPane.setBackground(UIManager.getColor("Panel.background"));
        tabbedPane.setForeground(UIManager.getColor("Panel.foreground"));
        tabbedPane.setOpaque(true);

        // Initialize file tree panel
        fileTreePanel = new FileTreePanel(
                core.getFileComparisons(), contextManager.getProject().getRoot(), builder.rootTitle);

        // Create split pane with file tree on left and tabs on right (if multiple files or multi-file-only mode)
        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplitPane.setBackground(UIManager.getColor("Panel.background"));
        if (showFileTree()) {
            fileTreePanel.setMinimumSize(new Dimension(200, 0)); // Prevent file tree from becoming too small
            mainSplitPane.setLeftComponent(fileTreePanel);
            mainSplitPane.setRightComponent(tabbedPane);
            mainSplitPane.setDividerLocation(250); // 250px for file tree
            mainSplitPane.setResizeWeight(0.25); // Give file tree 25% of resize space
        } else {
            // For single file, only show the tabs without the file tree
            mainSplitPane.setRightComponent(tabbedPane);
            mainSplitPane.setDividerLocation(0); // No left component, no divider
            mainSplitPane.setDividerSize(0); // Hide the divider completely
            mainSplitPane.setEnabled(false); // Disable resizing
        }

        // Set up tree selection listener (if multiple files or multi-file-only mode)
        if (showFileTree()) {
            fileTreePanel.setSelectionListener(this);
        }
        // Add an AncestorListener to trigger 'start()' when the panel is added to a container
        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                start();
                // Initialize file tree after panel is added to UI
                if (showFileTree()) {
                    fileTreePanel.initializeTree();
                }
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {}

            @Override
            public void ancestorRemoved(AncestorEvent event) {}
        });

        // Set up menu items
        menuShowBlankLineDiffs.addActionListener(e -> {
            boolean show = menuShowBlankLineDiffs.isSelected();
            GlobalUiSettings.saveDiffShowBlankLines(show);
            JMDiffNode.setIgnoreBlankLineDiffs(!show);
            refreshAllDiffPanels();
        });

        menuShowAllLines.addActionListener(e -> {
            boolean showAll = menuShowAllLines.isSelected();
            globalShowAllLinesInUnified = showAll;
            GlobalUiSettings.saveDiffShowAllLines(showAll);
            var targetMode = showAll
                    ? UnifiedDiffDocument.ContextMode.FULL_CONTEXT
                    : UnifiedDiffDocument.ContextMode.STANDARD_3_LINES;

            // Apply to the current panel if it's a unified panel
            if (currentDiffPanel instanceof UnifiedDiffPanel unifiedPanel) {
                unifiedPanel.setContextMode(targetMode);
            }
        });

        menuShowBlame.addActionListener(e -> {
            var panel = getCurrentContentPanel();
            boolean show = menuShowBlame.isSelected();

            GlobalUiSettings.saveDiffShowBlame(show);

            if (panel instanceof AbstractDiffPanel adp) {
                adp.setShowGutterBlame(show);
                updateBlameForPanel(adp, show);
            }
        });

        // Set up view mode toggle with icons
        viewModeToggle.setIcon(Icons.VIEW_UNIFIED); // Show unified icon when in side-by-side mode
        viewModeToggle.setSelectedIcon(Icons.VIEW_SIDE_BY_SIDE); // Show side-by-side icon when in unified mode
        viewModeToggle.setText(null); // Remove text, use icon only
        viewModeToggle.setToolTipText("Toggle Unified View");
        viewModeToggle.addActionListener(e -> {
            switchViewMode(viewModeToggle.isSelected());
        });

        revalidate();
    }

    private boolean showFileTree() {
        return forceFileTree || core.getFileComparisons().size() > 1;
    }

    // Builder Class
    public static class Builder {
        private final GuiTheme theme;
        private final ContextManager contextManager;
        private final List<FileComparisonInfo> fileComparisons;
        private boolean isMultipleCommitsContext = false;
        private int initialFileIndex = 0;
        private boolean forceFileTree = false;

        @Nullable
        private String rootTitle;

        @Nullable
        private BufferSource leftSource;

        @Nullable
        private BufferSource rightSource;

        public Builder(GuiTheme theme, ContextManager contextManager) {
            this.theme = theme;
            this.contextManager = contextManager;
            this.fileComparisons = new ArrayList<>();
        }

        public Builder leftSource(BufferSource source) {
            this.leftSource = source;
            return this;
        }

        public Builder rightSource(BufferSource source) {
            this.rightSource = source;
            // Automatically add the comparison
            if (this.leftSource != null) {
                addComparison(this.leftSource, this.rightSource);
            }
            leftSource = null; // Clear to prevent duplicate additions
            rightSource = null;
            return this;
        }

        public void addComparison(BufferSource leftSource, BufferSource rightSource) {
            addComparison(null, leftSource, rightSource);
        }

        public void addComparison(@Nullable ProjectFile file, BufferSource leftSource, BufferSource rightSource) {
            this.fileComparisons.add(new FileComparisonInfo(file, leftSource, rightSource));
        }

        public Builder setMultipleCommitsContext(boolean isMultipleCommitsContext) {
            this.isMultipleCommitsContext = isMultipleCommitsContext;
            return this;
        }

        public Builder setRootTitle(String rootTitle) {
            this.rootTitle = rootTitle;
            return this;
        }

        public Builder setInitialFileIndex(int initialFileIndex) {
            this.initialFileIndex = initialFileIndex;
            return this;
        }

        public Builder setForceFileTree(boolean forceFileTree) {
            this.forceFileTree = forceFileTree;
            return this;
        }

        public BrokkDiffPanel build() {
            assert !fileComparisons.isEmpty() : "At least one file comparison must be added";
            return new BrokkDiffPanel(this, theme);
        }
    }

    public JTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    private void start() {
        if (started) {
            return;
        }
        started = true;

        // Initialize settings from GlobalUiSettings
        this.globalShowAllLinesInUnified = GlobalUiSettings.isDiffShowAllLines();
        this.isUnifiedView = GlobalUiSettings.isDiffUnifiedView();

        // Update menu/toggle states to match loaded settings
        menuShowBlankLineDiffs.setSelected(GlobalUiSettings.isDiffShowBlankLines());
        JMDiffNode.setIgnoreBlankLineDiffs(!GlobalUiSettings.isDiffShowBlankLines());
        menuShowAllLines.setSelected(globalShowAllLinesInUnified);
        viewModeToggle.setSelected(isUnifiedView);

        boolean isGitRepo = contextManager.getProject().getRepo() instanceof GitRepo;
        menuShowBlame.setSelected(GlobalUiSettings.isDiffShowBlame() && isGitRepo);
        menuShowBlame.setEnabled(isGitRepo);

        getTabbedPane().setFocusable(false);
        setLayout(new BorderLayout());
        KeyboardShortcutUtil.registerCloseEscapeShortcut(this, this::close);

        // Register F7/Shift+F7 hotkeys for next/previous change navigation (IntelliJ style)
        KeyboardShortcutUtil.registerGlobalShortcut(
                this, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "nextChange", this::navigateToNextChange);
        KeyboardShortcutUtil.registerGlobalShortcut(
                this,
                KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.SHIFT_DOWN_MASK),
                "previousChange",
                this::navigateToPreviousChange);

        // Register font size adjustment shortcuts (using same keybindings as MOP zoom)
        var zoomInKeyStroke = GlobalUiSettings.getKeybinding(
                "view.zoomIn",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_PLUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        var zoomInEqualsKeyStroke = GlobalUiSettings.getKeybinding(
                "view.zoomInAlt",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_EQUALS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        var zoomOutKeyStroke = GlobalUiSettings.getKeybinding(
                "view.zoomOut",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_MINUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        var resetZoomKeyStroke = GlobalUiSettings.getKeybinding(
                "view.resetZoom",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_0, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));

        KeyboardShortcutUtil.registerGlobalShortcut(
                this, zoomInKeyStroke, "increaseFontSize", this::increaseEditorFont);
        KeyboardShortcutUtil.registerGlobalShortcut(
                this, zoomInEqualsKeyStroke, "increaseFontSize", this::increaseEditorFont);
        KeyboardShortcutUtil.registerGlobalShortcut(
                this, zoomOutKeyStroke, "decreaseFontSize", this::decreaseEditorFont);
        KeyboardShortcutUtil.registerGlobalShortcut(this, resetZoomKeyStroke, "resetFontSize", this::resetEditorFont);

        // Initialize font index from saved settings BEFORE creating panels
        // This ensures hasExplicitFontSize() returns true when panels apply themes
        ensureFontIndexInitialized();

        launchComparison();

        add(createToolbar(), BorderLayout.NORTH);
        add(mainSplitPane, BorderLayout.CENTER);

        // Add component listener to handle window resize events after navigation
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Only perform layout reset if needed after navigation
                if (needsLayoutReset) {
                    needsLayoutReset = false; // Clear flag
                    // Use currentDiffPanel directly instead of getBufferDiffPanel() to support unified panels
                    if (currentDiffPanel != null) {
                        resetLayoutHierarchy(currentDiffPanel);
                    }
                }
            }
        });
    }

    public JButton getBtnUndo() {
        return btnUndo;
    }

    private final MaterialButton btnUndo = new MaterialButton(); // Initialize to prevent NullAway issues
    private final MaterialButton btnRedo = new MaterialButton();
    private final MaterialButton btnSaveAll = new MaterialButton();
    private final MaterialButton captureDiffButton = new MaterialButton();
    private final MaterialButton captureAllDiffsButton = new MaterialButton();

    // Font size adjustment buttons
    private @Nullable MaterialButton btnDecreaseFont;
    private @Nullable MaterialButton btnResetFont;
    private @Nullable MaterialButton btnIncreaseFont;

    // Font size state - implements EditorFontSizeControl
    private int currentFontIndex = -1; // -1 = uninitialized

    @Override
    public int getCurrentFontIndex() {
        return currentFontIndex;
    }

    @Override
    public void setCurrentFontIndex(int index) {
        this.currentFontIndex = index;
    }

    private final MaterialButton btnNext = new MaterialButton();
    private final MaterialButton btnPrevious = new MaterialButton();
    private final MaterialButton btnPreviousFile = new MaterialButton();
    private final MaterialButton btnNextFile = new MaterialButton();
    private final MaterialButton btnTools = new MaterialButton();

    // Blame service (null if not a git repo)
    private final @Nullable BlameService blameService;
    private boolean blameErrorNotified = false;

    // Flag to track when layout hierarchy needs reset after navigation
    private volatile boolean needsLayoutReset = false;

    @Nullable
    private AbstractDiffPanel currentDiffPanel;

    public void setBufferDiffPanel(@Nullable BufferDiffPanel bufferDiffPanel) {
        // Don't allow BufferDiffPanel to override currentDiffPanel when in unified view mode
        if (bufferDiffPanel != null && isUnifiedView) {
            return;
        }

        this.currentDiffPanel = bufferDiffPanel;
    }

    @Nullable
    private BufferDiffPanel getBufferDiffPanel() {
        return currentDiffPanel instanceof BufferDiffPanel ? (BufferDiffPanel) currentDiffPanel : null;
    }

    public void nextFile() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // Disable all control buttons FIRST, before any logic
        disableAllControlButtons();

        if (canNavigateToNextFile()) {
            try {
                switchToFile(core.getCurrentIndex() + 1);
            } catch (Exception e) {
                logger.error("Error navigating to next file", e);
                // Re-enable buttons on exception
                updateNavigationButtons();
            }
        } else {
            // Re-enable buttons if navigation was blocked
            updateNavigationButtons();
        }
    }

    public void previousFile() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // Disable all control buttons FIRST, before any logic
        disableAllControlButtons();

        if (canNavigateToPreviousFile()) {
            try {
                switchToFile(core.getCurrentIndex() - 1);
            } catch (Exception e) {
                logger.error("Error navigating to previous file", e);
                // Re-enable buttons on exception
                updateNavigationButtons();
            }
        } else {
            // Re-enable buttons if navigation was blocked
            updateNavigationButtons();
        }
    }

    public void switchToFile(int index) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        core.showFile(index);
    }

    private void updateNavigationButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        updateUndoRedoButtons();

        btnPreviousFile.setEnabled(canNavigateToPreviousFile());
        btnNextFile.setEnabled(canNavigateToNextFile());
    }

    /**
     * Update toolbar to show appropriate control based on current view mode. Shows whitespace checkbox for side-by-side
     * view, context checkbox for unified view.
     */
    private void updateToolbarForViewMode() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        if (toolBar == null) {
            logger.warn("Toolbar not initialized, cannot update controls");
            return;
        }

        // Show/hide menu items based on current view mode
        if (isUnifiedView) {
            // In unified view: show "Show All Lines", hide "Show Empty Line Diffs"
            menuShowAllLines.setVisible(true);
            menuShowBlankLineDiffs.setVisible(false);
        } else {
            // In side-by-side view: show "Show Empty Line Diffs", hide "Show All Lines"
            menuShowAllLines.setVisible(false);
            menuShowBlankLineDiffs.setVisible(true);
        }

        toolBar.revalidate();
        toolBar.repaint();
    }

    /**
     * Creates a styled loading label for the "Processing... Please wait." message. The label is centered and uses a
     * larger font for better visibility.
     */
    private static JLabel createLoadingLabel() {
        var label = new JLabel("Processing... Please wait.", SwingConstants.CENTER);

        // Make the font larger and bold for better visibility
        var currentFont = label.getFont();
        var largerFont = currentFont.deriveFont(Font.BOLD, currentFont.getSize() + 4);
        label.setFont(largerFont);

        // Add some padding
        label.setBorder(BorderFactory.createEmptyBorder(50, 20, 50, 20));

        return label;
    }

    /**
     * Creates a styled error label similar to the loading label but for error messages. Uses theme-aware colors instead
     * of hardcoded red.
     */
    private JLabel createErrorLabel(String errorMessage) {
        var label = new JLabel("File Too Large to Display", SwingConstants.CENTER);

        // Make the font larger and bold for better visibility (same as loading label)
        var currentFont = label.getFont();
        var largerFont = currentFont.deriveFont(Font.BOLD, currentFont.getSize() + 4);
        label.setFont(largerFont);

        // Use theme-aware error color instead of hardcoded red
        label.setForeground(UIManager.getColor("Label.disabledForeground"));

        // Add some padding (same as loading label)
        label.setBorder(BorderFactory.createEmptyBorder(50, 20, 50, 20));

        // Set the actual error message as tooltip for full details
        label.setToolTipText(errorMessage);

        return label;
    }

    /**
     * Disables all control buttons during file loading to prevent navigation issues. Called from showLoadingForFile()
     * to ensure clean loading states.
     */
    private void disableAllControlButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // File navigation buttons
        btnPreviousFile.setEnabled(false);
        btnNextFile.setEnabled(false);

        // Change navigation buttons
        btnNext.setEnabled(false);
        btnPrevious.setEnabled(false);

        // Edit buttons
        btnUndo.setEnabled(false);
        btnRedo.setEnabled(false);
        btnSaveAll.setEnabled(false);

        // Capture diff button should always remain enabled

    }

    private JToolBar createToolbar() {
        // Create toolbar
        toolBar = new JToolBar();

        // Configure button icons and tooltips
        btnNext.setIcon(Icons.NAVIGATE_NEXT);
        btnNext.setToolTipText("Next Change");
        btnNext.addActionListener(e -> navigateToNextChange());

        btnPrevious.setIcon(Icons.NAVIGATE_BEFORE);
        btnPrevious.setToolTipText("Previous Change");
        btnPrevious.addActionListener(e -> navigateToPreviousChange());

        btnUndo.setIcon(Icons.UNDO);
        btnUndo.setToolTipText("Undo");
        btnUndo.addActionListener(e -> performUndoRedo(AbstractContentPanel::doUndo));

        btnRedo.setIcon(Icons.REDO);
        btnRedo.setToolTipText("Redo");
        btnRedo.addActionListener(e -> performUndoRedo(AbstractContentPanel::doRedo));

        btnSaveAll.setIcon(Icons.SAVE);
        btnSaveAll.setToolTipText("Save");
        btnSaveAll.addActionListener(e -> saveAll());

        // File navigation handlers
        btnPreviousFile.setIcon(Icons.CHEVRON_LEFT);
        btnPreviousFile.setToolTipText("Previous File");
        btnPreviousFile.addActionListener(e -> previousFile());

        btnNextFile.setIcon(Icons.CHEVRON_RIGHT);
        btnNextFile.setToolTipText("Next File");
        btnNextFile.addActionListener(e -> nextFile());

        captureDiffButton.setIcon(Icons.CONTENT_CAPTURE);
        captureDiffButton.setToolTipText("Capture Diff");
        captureDiffButton.addActionListener(e -> {
            var currentComparison = core.getFileComparisons().get(core.getCurrentIndex());
            capture(List.of(currentComparison));
        });

        // "Capture All Diffs" button (visible for multi-file contexts)
        captureAllDiffsButton.setText("Capture All Diffs");
        captureAllDiffsButton.setToolTipText("Capture all file diffs to the context");
        captureAllDiffsButton.addActionListener(e -> capture(new ArrayList<>(core.getFileComparisons())));

        // Add buttons to toolbar with spacing
        toolBar.add(btnPrevious);
        toolBar.add(Box.createHorizontalStrut(10)); // 10px spacing
        toolBar.add(btnNext);

        // Add file navigation buttons if multiple files
        if (core.getFileComparisons().size() > 1) {
            toolBar.add(Box.createHorizontalStrut(20)); // 20px spacing
            toolBar.addSeparator();
            toolBar.add(Box.createHorizontalStrut(10));
            toolBar.add(btnPreviousFile);
            toolBar.add(Box.createHorizontalStrut(10));
            toolBar.add(btnNextFile);
        }

        toolBar.add(Box.createHorizontalStrut(20));
        toolBar.addSeparator();
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(btnUndo);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(btnRedo);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(btnSaveAll);

        toolBar.add(Box.createHorizontalStrut(20));
        toolBar.addSeparator();
        toolBar.add(Box.createHorizontalStrut(10));

        // Add tools button with popup menu
        btnTools.setIcon(Icons.DIFF_TOOLS);
        btnTools.setToolTipText("View Options");
        btnTools.setText(null); // Icon-only button
        btnTools.setBorderPainted(false);
        btnTools.setContentAreaFilled(false);
        btnTools.setFocusPainted(false);
        var toolsMenu = new JPopupMenu();
        toolsMenu.add(menuShowBlame);
        toolsMenu.add(menuShowAllLines);
        toolsMenu.add(menuShowBlankLineDiffs);
        btnTools.addActionListener(e -> toolsMenu.show(btnTools, 0, btnTools.getHeight()));
        toolBar.add(viewModeToggle);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(btnTools);

        // Update control enable/disable state based on view mode
        updateToolbarForViewMode();

        toolBar.add(Box.createHorizontalGlue()); // Pushes subsequent components to the right

        // Font size controls (positioned before capture button)
        // Create font size control buttons using interface methods
        btnDecreaseFont = createDecreaseFontButton(this::decreaseEditorFont);
        btnResetFont = createResetFontButton(this::resetEditorFont);
        btnIncreaseFont = createIncreaseFontButton(this::increaseEditorFont);

        toolBar.add(btnDecreaseFont);
        toolBar.add(Box.createHorizontalStrut(4));
        toolBar.add(btnResetFont);
        toolBar.add(Box.createHorizontalStrut(4));
        toolBar.add(btnIncreaseFont);
        toolBar.add(Box.createHorizontalStrut(8));
        toolBar.add(captureDiffButton);
        if (core.getFileComparisons().size() > 1 || isMultipleCommitsContext) {
            toolBar.add(Box.createHorizontalStrut(8));
            toolBar.add(captureAllDiffsButton);
        }

        return toolBar;
    }

    public void updateUndoRedoButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        var currentPanel = getCurrentContentPanel();

        btnUndo.setEnabled(currentPanel != null && currentPanel.isUndoEnabled());
        btnRedo.setEnabled(currentPanel != null && currentPanel.isRedoEnabled());

        // Disable undo/redo when in unified mode or when both sides are read-only
        boolean enableUndoRedo = false;
        if (currentPanel instanceof BufferDiffPanel bp) {
            enableUndoRedo = bp.atLeastOneSideEditable();
        }
        if (!enableUndoRedo) {
            btnUndo.setEnabled(false);
            btnRedo.setEnabled(false);
        }

        if (currentPanel != null) {
            var isFirstChangeOverall = core.getCurrentIndex() == 0 && currentPanel.isAtFirstLogicalChange();
            var isLastChangeOverall =
                    core.getCurrentIndex() == core.getFileComparisons().size() - 1
                            && currentPanel.isAtLastLogicalChange();
            btnPrevious.setEnabled(!isFirstChangeOverall);
            btnNext.setEnabled(!isLastChangeOverall);
        } else {
            btnPrevious.setEnabled(false);
            btnNext.setEnabled(false);
        }

        // Capture diff button should always be enabled
        captureDiffButton.setEnabled(true);

        // Update blame menu item enabled state
        // Blame is available if the panel can provide a target path for blame and it's a git repo
        boolean isGitRepo = contextManager.getProject().getRepo() instanceof GitRepo;
        boolean canShowBlame = false;
        if (isGitRepo && currentDiffPanel != null) {
            canShowBlame = currentDiffPanel.getTargetPathForBlame() != null;
        }
        menuShowBlame.setEnabled(canShowBlame);

        // Update save button text, enable state, and visibility
        // Compute the exact number of panels that would be saved by saveAll():
        // include currentDiffPanel (if present) plus all cached panels (deduplicated),
        // and count those with hasUnsavedChanges() == true.
        int dirtyCount = 0;
        var visited = new HashSet<AbstractDiffPanel>();

        if (currentDiffPanel != null) {
            visited.add(currentDiffPanel);
            if (currentDiffPanel.hasUnsavedChanges()) {
                dirtyCount++;
            }
        }

        for (var p : core.getCachedPanels()) {
            if (visited.add(p) && p.hasUnsavedChanges()) {
                dirtyCount++;
            }
        }

        String baseSaveText = core.getFileComparisons().size() > 1 ? "Save All" : "Save";
        btnSaveAll.setToolTipText(dirtyCount > 0 ? baseSaveText + " (" + dirtyCount + ")" : baseSaveText);
        // Disable save button when in unified mode, when all sides are read-only, or when there are no changes
        btnSaveAll.setEnabled(enableUndoRedo && dirtyCount > 0);

        // Update per-file dirty indicators in the file tree (only when multiple files are shown)
        if (showFileTree()) {
            var dirty = new HashSet<Integer>();

            // Current (visible) file (only if it's a BufferDiffPanel)
            var currentBufferPanel = getBufferDiffPanel();
            if (currentBufferPanel != null && currentBufferPanel.hasUnsavedChanges()) {
                dirty.add(core.getCurrentIndex());
            }

            // Cached files (use keys to keep index association, only BufferDiffPanels can be dirty)
            for (var key : core.getCachedIndices()) {
                var panel = core.getCachedPanel(key);
                if (panel instanceof BufferDiffPanel && panel.hasUnsavedChanges()) {
                    dirty.add(key);
                }
            }

            fileTreePanel.setDirtyFiles(dirty);
        }
    }

    /**
     * Check if any loaded diff-panel holds modified documents.
     */
    public boolean hasUnsavedChanges() {
        if (currentDiffPanel != null && currentDiffPanel.hasUnsavedChanges()) return true;
        for (int i = 0; i < getFileComparisonCount(); i++) {
            var p = core.getCachedPanel(i);
            if (p != null && p.hasUnsavedChanges()) return true;
        }
        return false;
    }

    /** Saves every dirty document across all BufferDiffPanels, producing a single undoable history entry. */
    public void saveAll() {
        // Disable save button temporarily
        btnSaveAll.setEnabled(false);

        // Collect unique BufferDiffPanels to process (current + cached)
        var visited = new LinkedHashSet<BufferDiffPanel>();
        var currentBufferPanel = getBufferDiffPanel();
        if (currentBufferPanel != null) {
            visited.add(currentBufferPanel);
        }
        for (var p : core.getCachedPanels()) {
            if (p instanceof BufferDiffPanel bufferPanel) {
                visited.add(bufferPanel);
            }
        }

        // Filter to only panels with unsaved changes and at least one editable side
        var panelsToSave = visited.stream()
                .filter(p -> p.hasUnsavedChanges() && p.atLeastOneSideEditable())
                .toList();

        if (panelsToSave.isEmpty()) {
            // Nothing to do
            SwingUtilities.invokeLater(this::updateNavigationButtons);
            return;
        }

        // Step 0: Add external files to workspace first (to capture original content for undo)
        var currentContext = contextManager.liveContext();
        var externalFiles = new ArrayList<ProjectFile>();

        for (var p : panelsToSave) {
            var panelFiles = p.getFilesBeingSaved();
            for (var file : panelFiles) {
                // Check if this file is already in the current workspace context
                var editableFilesList = currentContext.fileFragments().toList();
                boolean inWorkspace = editableFilesList.stream()
                        .anyMatch(f -> f instanceof ContextFragments.ProjectPathFragment ppf
                                && ppf.file().equals(file));
                if (!inWorkspace) {
                    externalFiles.add(file);
                }
            }
        }

        if (!externalFiles.isEmpty()) {
            contextManager.addFiles(externalFiles);
        }

        // Step 1: Collect changes (on EDT) before writing to disk
        var allChanges = new ArrayList<BufferDiffPanel.AggregatedChange>();
        for (var p : panelsToSave) {
            allChanges.addAll(p.collectChangesForAggregation());
        }

        // Deduplicate by filename while preserving order
        var mergedByFilename = new LinkedHashMap<String, BufferDiffPanel.AggregatedChange>();
        for (var ch : allChanges) {
            mergedByFilename.putIfAbsent(ch.filename(), ch);
        }

        if (mergedByFilename.isEmpty()) {
            SwingUtilities.invokeLater(this::updateNavigationButtons);
            return;
        }

        // Step 2: Write all changed documents while file change notifications are paused, collecting results
        var perPanelResults = new LinkedHashMap<BufferDiffPanel, BufferDiffPanel.SaveResult>();
        contextManager.withFileChangeNotificationsPaused(() -> {
            for (var p : panelsToSave) {
                var result = p.writeChangedDocuments();
                perPanelResults.put(p, result);
            }
            return null;
        });

        // Merge results across panels
        var successfulFiles = new LinkedHashSet<String>();
        var failedFiles = new LinkedHashMap<String, String>();
        for (var entry : perPanelResults.entrySet()) {
            successfulFiles.addAll(entry.getValue().succeeded());
            entry.getValue().failed().forEach((k, v) -> failedFiles.putIfAbsent(k, v));
        }

        // Filter to only successfully saved files
        var mergedByFilenameSuccessful = new LinkedHashMap<String, BufferDiffPanel.AggregatedChange>();
        for (var e : mergedByFilename.entrySet()) {
            if (successfulFiles.contains(e.getKey())) {
                mergedByFilenameSuccessful.put(e.getKey(), e.getValue());
            }
        }

        // If nothing succeeded, summarize failures and abort history/baseline updates
        if (mergedByFilenameSuccessful.isEmpty()) {
            if (!failedFiles.isEmpty()) {
                var msg = failedFiles.entrySet().stream()
                        .map(en -> Paths.get(en.getKey()).getFileName().toString() + ": " + en.getValue())
                        .collect(Collectors.joining("\n"));
                contextManager
                        .getIo()
                        .systemNotify("No files were saved. Errors:\n" + msg, "Save failed", JOptionPane.ERROR_MESSAGE);
            }
            SwingUtilities.invokeLater(this::updateNavigationButtons);
            return;
        }

        // Step 3: Build a single TaskResult containing diffs for successfully saved files
        var messages = new ArrayList<ChatMessage>();
        var changedFiles = new LinkedHashSet<ProjectFile>();

        int fileCount = mergedByFilenameSuccessful.size();
        // Build a friendlier action title: include filenames when 1-2 files, otherwise count
        var topNames = mergedByFilenameSuccessful.values().stream()
                .limit(2)
                .map(ch -> {
                    var pf = ch.projectFile();
                    return (pf != null)
                            ? pf.toString()
                            : Paths.get(ch.filename()).getFileName().toString();
                })
                .toList();
        String actionDescription;
        if (fileCount == 1) {
            actionDescription = "Saved changes to " + topNames.get(0);
        } else if (fileCount == 2) {
            actionDescription = "Saved changes to " + topNames.get(0) + " and " + topNames.get(1);
        } else {
            actionDescription = "Saved changes to " + fileCount + " files";
        }
        messages.add(Messages.customSystem(actionDescription));

        // Per-file diffs
        for (var entry : mergedByFilenameSuccessful.values()) {
            var filename = entry.filename();
            var diffResult = ContentDiffUtils.computeDiffResult(
                    entry.originalContent(), entry.currentContent(), filename, filename, 3);
            var diffText = diffResult.diff();

            var pf = entry.projectFile();
            var header = "### " + (pf != null ? pf.toString() : filename);
            messages.add(Messages.customSystem(header));
            messages.add(Messages.customSystem("```" + diffText + "```"));

            if (pf != null) {
                changedFiles.add(pf);
            } else {
                // Outside-project file: keep it in the transcript; not tracked in changedFiles
                IConsoleIO iConsoleIO = contextManager.getIo();
                iConsoleIO.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Saved file outside project scope: " + filename + " (not added to workspace history)");
            }
        }

        // Build resulting Context by adding any changed files that are not already editable in the top context
        var top = contextManager.liveContext();
        var resultingCtx = top.addFragments(contextManager.toPathFragments(changedFiles));

        var result = TaskResult.humanResult(
                contextManager, actionDescription, messages, resultingCtx, TaskResult.StopReason.SUCCESS);

        // Add a single history entry for the whole batch
        try (var scope = contextManager.beginTaskUngrouped(actionDescription)) {
            // This is a local save operation (non-LLM). For now we record no TaskMeta.
            scope.append(result);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        logger.info("Saved changes to {} file(s): {}", fileCount, actionDescription);

        // Step 4: Finalize panels selectively and refresh UI
        for (var p : panelsToSave) {
            var saved = perPanelResults
                    .getOrDefault(p, new BufferDiffPanel.SaveResult(Set.of(), Map.of()))
                    .succeeded();
            p.finalizeAfterSaveAggregation(saved);
            p.recalcDirty();
            refreshTabTitle(p);
        }

        // Refresh blame for successfully saved files
        for (String filename : successfulFiles) {
            refreshBlameAfterSave(Paths.get(filename));
        }

        // If some files failed, notify the user after successful saves
        if (!failedFiles.isEmpty()) {
            var msg = failedFiles.entrySet().stream()
                    .map(en -> Paths.get(en.getKey()).getFileName().toString() + ": " + en.getValue())
                    .collect(Collectors.joining("\n"));
            contextManager
                    .getIo()
                    .systemNotify(
                            "Some files could not be saved:\n" + msg,
                            "Partial save completed",
                            JOptionPane.WARNING_MESSAGE);
        }

        repaint();
        SwingUtilities.invokeLater(this::updateNavigationButtons);
    }

    /** Refresh tab title (adds/removes “*”). */
    public void refreshTabTitle(AbstractDiffPanel panel) {
        var idx = tabbedPane.indexOfComponent(panel);
        if (idx != -1) {
            tabbedPane.setTitleAt(idx, panel.getTitle());
        }
    }

    /** Provides access to Chrome methods for BufferDiffPanel. */
    public IConsoleIO getConsoleIO() {
        return contextManager.getIo();
    }

    /** Provides access to the ContextManager for BufferDiffPanel. */
    public ContextManager getContextManager() {
        return contextManager;
    }

    @Override
    public void navigateToFile(int fileIndex) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        core.showFile(fileIndex);
    }

    @Override
    public void navigateToFile(ProjectFile file) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        core.showFile(file);
    }

    @Override
    public void navigateToLocation(ProjectFile file, int lineNumber, ReviewParser.DiffSide side) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        // Note: Core calls back to navigateToLocation if we are switching files,
        // but we ensure scrolling happens on the correct side here.
        scrollToLineInCurrentPanel(lineNumber, side);
    }

    private void scrollToLineInCurrentPanel(int lineNumber, ReviewParser.DiffSide side) {
        if (currentDiffPanel instanceof BufferDiffPanel bp) {
            var panelSide = (side == ReviewParser.DiffSide.OLD)
                    ? BufferDiffPanel.PanelSide.LEFT
                    : BufferDiffPanel.PanelSide.RIGHT;
            bp.scrollToLine(lineNumber, panelSide);
        } else if (currentDiffPanel instanceof UnifiedDiffPanel up) {
            // UnifiedDiffPanel doesn't easily distinguish side-based scrolling yet,
            // but we pass the line. Future implementation might use side to highlight.
            up.scrollToLine(lineNumber);
        }
    }

    /** Returns the number of file comparisons in this panel. */
    public int getFileComparisonCount() {
        return core.getFileComparisons().size();
    }

    public void launchComparison() {
        core.showFile(core.getCurrentIndex());
    }

    /**
     * Display an error message for a file that cannot be loaded. Clears the loading state and shows the error message.
     */
    public void displayErrorForFile(int fileIndex, String errorMessage) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        logger.error("Cannot display file {}: {}", fileIndex, errorMessage);

        var compInfo = core.getFileComparisons().get(fileIndex);

        // Remove loading UI if present
        removeLoadingUi();

        // Clear tabs and show error message
        tabbedPane.removeAll();

        // Create error panel
        var errorPanel = new JPanel(new BorderLayout());
        var errorLabel = createErrorLabel(errorMessage);
        errorPanel.add(errorLabel, BorderLayout.NORTH);

        tabbedPane.addTab(compInfo.getDisplayName() + " (Error)", errorPanel);

        // Re-enable navigation buttons but keep current panel null
        updateNavigationButtons();

        refreshUI();
    }

    private void removeLoadingUi() {
        for (var component : getComponents()) {
            if (component instanceof JPanel p && p.getComponentCount() > 0 && p.getComponent(0) == loadingLabel) {
                remove(p);
                break;
            }
        }
    }

    @Nullable
    public AbstractContentPanel getCurrentContentPanel() {
        var selectedComponent = getTabbedPane().getSelectedComponent();
        if (selectedComponent instanceof AbstractContentPanel abstractContentPanel) {
            return abstractContentPanel;
        }
        return null;
    }

    /**
     * Shows this diff panel in the shared preview tab system.
     *
     * @param manager The PreviewManager to delegate to
     * @param title   The title for the tab
     */
    public void showInTab(ai.brokk.gui.PreviewManager manager, String title) {
        var leftSources =
                core.getFileComparisons().stream().map(fc -> fc.leftSource()).toList();
        var rightSources =
                core.getFileComparisons().stream().map(fc -> fc.rightSource()).toList();
        manager.showDiffInTab(title, this, leftSources, rightSources);
    }

    private void navigateToNextChange() {
        var panel = getCurrentContentPanel();
        if (panel == null) return;

        // Disable change navigation buttons FIRST
        btnNext.setEnabled(false);
        btnPrevious.setEnabled(false);

        try {
            if (panel.isAtLastLogicalChange() && canNavigateToNextFile()) {
                nextFile();
            } else {
                panel.doDown();
                // Re-enable immediately after navigation within same file
                SwingUtilities.invokeLater(this::updateNavigationButtons);
            }
            refreshAfterNavigation();
        } catch (Exception e) {
            logger.error("Error navigating to next change", e);
            updateNavigationButtons();
        }
    }

    private void navigateToPreviousChange() {
        var panel = getCurrentContentPanel();
        if (panel == null) return;

        // Disable change navigation buttons FIRST
        btnNext.setEnabled(false);
        btnPrevious.setEnabled(false);

        try {
            if (panel.isAtFirstLogicalChange() && canNavigateToPreviousFile()) {
                previousFile();
                var newPanel = getCurrentContentPanel();
                if (newPanel != null) {
                    newPanel.goToLastLogicalChange();
                }
            } else {
                panel.doUp();
                // Re-enable immediately after navigation within same file
                SwingUtilities.invokeLater(this::updateNavigationButtons);
            }
            refreshAfterNavigation();
        } catch (Exception e) {
            logger.error("Error navigating to previous change", e);
            updateNavigationButtons();
        }
    }

    private boolean canNavigateToNextFile() {
        return core.getFileComparisons().size() > 1
                && core.getCurrentIndex() < core.getFileComparisons().size() - 1;
    }

    private boolean canNavigateToPreviousFile() {
        return core.getFileComparisons().size() > 1 && core.getCurrentIndex() > 0;
    }

    @Nullable
    private String detectFilename(BufferSource leftSource, BufferSource rightSource) {
        if (leftSource.filename() != null) {
            return leftSource.filename();
        }
        return rightSource.filename();
    }

    /**
     * Builds the user-facing description for a captured diff, including a display name and
     * friendly commit labels for the left and right sources.
     */
    private String buildCaptureDescription(
            BufferSource left, BufferSource right, @Nullable String filenameOrDisplayName) {
        GitRepo repo = null;
        var r = contextManager.getProject().getRepo();
        if (r instanceof GitRepo gr) {
            repo = gr;
        }

        String displayName = (filenameOrDisplayName != null && !filenameOrDisplayName.isBlank())
                ? filenameOrDisplayName
                : Optional.ofNullable(detectFilename(left, right)).orElse(left.title());

        return "Captured Diff: %s - %s vs %s"
                .formatted(
                        displayName,
                        GitDiffUiUtil.friendlyCommitLabel(left.title(), repo),
                        GitDiffUiUtil.friendlyCommitLabel(right.title(), repo));
    }

    private Set<ProjectFile> collectProjectFilesForSources(BufferSource leftSource, BufferSource rightSource) {
        var files = new LinkedHashSet<ProjectFile>();
        addProjectFileForSource(leftSource, files);
        addProjectFileForSource(rightSource, files);
        return Set.copyOf(files);
    }

    private void addProjectFileForSource(BufferSource source, Set<ProjectFile> files) {
        if (source instanceof BufferSource.StringSource ss) {
            var filename = ss.filename();
            if (filename == null || filename.isBlank()) {
                return;
            }
            var normalized = filename.replace('\\', '/');
            try {
                var projectFile = contextManager.toFile(normalized);
                files.add(projectFile);
            } catch (IllegalArgumentException e) {
                logger.debug("Unable to resolve ProjectFile from StringSource filename '{}'", normalized, e);
            }
        } else if (source instanceof BufferSource.FileSource fs) {
            files.add(fs.file());
        }
    }

    /**
     * Capture one or more file comparisons and add a single StringFragment representing the combined diffs.
     * If a single file is being captured and the visible buffer panel is present for that file, the current editor
     * contents are used (including unsaved edits). For multi-file captures the source content is read directly.
     */
    private void capture(List<FileComparisonInfo> comps) {
        if (comps.isEmpty()) return;

        assert SwingUtilities.isEventDispatchThread() : "capture must be called on EDT";

        boolean isSingle = comps.size() == 1;

        // Capture any editor-based overrides on the EDT before doing background work.
        var leftOverrides = new LinkedHashMap<FileComparisonInfo, String>();
        var rightOverrides = new LinkedHashMap<FileComparisonInfo, String>();

        if (isSingle) {
            var currentComparison = core.getFileComparisons().get(core.getCurrentIndex());
            if (comps.getFirst().equals(currentComparison)) {
                var bufferPanel = getBufferDiffPanel();
                if (bufferPanel != null) {
                    var leftPanel = bufferPanel.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
                    var rightPanel = bufferPanel.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);
                    if (leftPanel != null && rightPanel != null) {
                        leftOverrides.put(
                                currentComparison, leftPanel.getEditor().getText());
                        rightOverrides.put(
                                currentComparison, rightPanel.getEditor().getText());
                    }
                }
            }
        }

        contextManager.submitBackgroundTask("Capture diffs", () -> {
            var combinedBuilder = new StringBuilder();
            var filesForFragment = new LinkedHashSet<ProjectFile>();
            String primaryDisplayName = null;

            for (FileComparisonInfo comp : comps) {
                var leftSource = comp.leftSource();
                var rightSource = comp.rightSource();

                String leftContent;
                String rightContent;

                // Use editor overrides when available (single-file capture with an editable BufferDiffPanel).
                String leftOverride = leftOverrides.get(comp);
                String rightOverride = rightOverrides.get(comp);
                if (leftOverride != null && rightOverride != null) {
                    leftContent = leftOverride;
                    rightContent = rightOverride;
                } else {
                    // Fallback to source content; used for multi-file captures and unified view.
                    leftContent = leftSource.content();
                    rightContent = rightSource.content();
                }

                if (Objects.equals(leftContent, rightContent)) {
                    continue;
                }

                String oldName = leftSource.title();
                String newName = rightSource.title();
                var diffText = ContentDiffUtils.computeDiffResult(leftContent, rightContent, oldName, newName, 0)
                        .diff();

                String detectedFilename = detectFilename(leftSource, rightSource);
                String displayName = Optional.ofNullable(detectedFilename).orElse(comp.getDisplayName());

                if (!combinedBuilder.isEmpty()) {
                    combinedBuilder.append("\n\n");
                }
                combinedBuilder.append("### ").append(displayName).append("\n");
                combinedBuilder.append(diffText);

                filesForFragment.addAll(collectProjectFilesForSources(leftSource, rightSource));
                if (primaryDisplayName == null) primaryDisplayName = displayName;
            }

            if (combinedBuilder.isEmpty()) {
                contextManager.getIo().showNotification(IConsoleIO.NotificationRole.INFO, "No diffs to capture");
                return;
            }

            String description;
            if (isSingle) {
                var singleInfo = comps.getFirst();
                description =
                        buildCaptureDescription(singleInfo.leftSource(), singleInfo.rightSource(), primaryDisplayName);
            } else {
                description = "Captured diffs for " + comps.size() + " file(s)";
            }

            String syntaxStyle = SyntaxConstants.SYNTAX_STYLE_NONE;
            if (primaryDisplayName != null) {
                int dotIndex = primaryDisplayName.lastIndexOf('.');
                if (dotIndex > 0 && dotIndex < primaryDisplayName.length() - 1) {
                    var extension = primaryDisplayName.substring(dotIndex + 1);
                    syntaxStyle = SyntaxDetector.fromExtension(extension);
                } else {
                    syntaxStyle = SyntaxDetector.fromExtension(primaryDisplayName);
                }
            }

            var fragment = new ContextFragments.StringFragment(
                    contextManager, combinedBuilder.toString(), description, syntaxStyle, filesForFragment);

            contextManager.submitBackgroundTask("Add fragments to context", () -> {
                contextManager.addFragments(fragment);
                contextManager
                        .getIo()
                        .showNotification(
                                IConsoleIO.NotificationRole.INFO, "Added captured diffs to context: " + description);
                return null;
            });
        });
    }

    private void performUndoRedo(Consumer<AbstractContentPanel> action) {
        var panel = getCurrentContentPanel();
        if (panel != null) {
            // Disable undo/redo buttons FIRST
            btnUndo.setEnabled(false);
            btnRedo.setEnabled(false);

            try {
                action.accept(panel);
                repaint();
                var diffPanel = getBufferDiffPanel();
                if (diffPanel != null) {
                    refreshTabTitle(diffPanel);
                }
                // Re-enable buttons after operation
                SwingUtilities.invokeLater(this::updateNavigationButtons);
            } catch (Exception e) {
                logger.error("Error performing undo/redo operation", e);
                updateNavigationButtons();
            }
        }
    }

    private void refreshAfterNavigation() {
        repaint();
        updateUndoRedoButtons();
    }

    private void refreshUI() {
        updateNavigationButtons();
        revalidate();
        repaint();
    }

    private void refreshAllDiffPanels() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        // Refresh existing cached panels (preserves cache for performance)
        core.getCachedPanels().forEach(panel -> panel.diff(true)); // Scroll to selection for user-initiated refresh
        // Refresh current panel if it's not cached
        var current = getBufferDiffPanel();
        if (current != null) {
            current.diff(true); // Scroll to selection for user-initiated refresh
        }
        // Update navigation buttons after refresh
        SwingUtilities.invokeLater(this::updateUndoRedoButtons);
        repaint();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        assert SwingUtilities.isEventDispatchThread() : "applyTheme must be called on EDT";

        // Update all child components including toolbar buttons and labels while preserving fonts
        // Do this FIRST so UIManager has the new theme colors
        guiTheme.updateComponentTreeUIPreservingFonts(this);

        // Apply theme to cached panels
        for (var panel : core.getCachedPanels()) {
            panel.applyTheme(guiTheme);
        }

        // Apply theme to file tree panel (only if multiple files)
        if (showFileTree()) {
            fileTreePanel.applyTheme(guiTheme);
        }

        // Update tabbedPane and mainSplitPane colors for theme changes (after L&F update)
        var bg = UIManager.getColor("Panel.background");
        var fg = UIManager.getColor("Panel.foreground");
        tabbedPane.setBackground(bg);
        tabbedPane.setForeground(fg);
        tabbedPane.setOpaque(true);
        mainSplitPane.setBackground(bg);

        revalidate();
        repaint();
    }

    private void close() {
        if (checkUnsavedChangesBeforeClose()) {
            var window = SwingUtilities.getWindowAncestor(this);
            if (window != null) {
                window.dispose();
            }
        }
    }

    /**
     * Checks for unsaved changes and prompts user to save before closing.
     *
     * @return true if it's OK to close, false if user cancelled
     */
    private boolean checkUnsavedChangesBeforeClose() {
        if (hasUnsavedChanges()) {
            var window = SwingUtilities.getWindowAncestor(this);
            var parentFrame = (window instanceof JFrame jframe) ? jframe : null;
            var opt = contextManager
                    .getIo()
                    .showConfirmDialog(
                            parentFrame,
                            "There are unsaved changes. Save before closing?",
                            "Unsaved Changes",
                            JOptionPane.YES_NO_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE);
            if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) {
                return false; // Don't close
            }
            if (opt == JOptionPane.YES_OPTION) {
                saveAll();
            }
            // For NO_OPTION, just continue to return true - caller will handle disposal
        }
        return true; // OK to close
    }

    /**
     * Public wrapper for close confirmation. This method is safe to call from any thread: it will ensure the
     * interactive confirmation (and any saves) are performed on the EDT.
     *
     * @param parentWindow the parent window to use for dialogs (may be null)
     * @return true if it's OK to close (user allowed or saved), false to cancel quit
     */
    public boolean confirmClose(Window parentWindow) {
        // If already on EDT, call directly
        if (SwingUtilities.isEventDispatchThread()) {
            return checkUnsavedChangesBeforeClose();
        }
        // Otherwise, run on EDT and wait for result
        var result = new AtomicBoolean(true);
        SwingUtil.runOnEdt(() -> {
            result.set(checkUnsavedChangesBeforeClose());
        });
        return result.get();
    }

    /**
     * Displays a cached panel and updates navigation buttons.
     */
    public void displayAndRefreshPanel(int fileIndex, AbstractDiffPanel panel) {
        displayAndRefreshPanel(fileIndex, panel, -1, ReviewParser.DiffSide.NEW);
    }

    /**
     * Displays a cached panel and updates navigation buttons, optionally scrolling to a specific location.
     */
    public void displayAndRefreshPanel(
            int fileIndex, AbstractDiffPanel panel, int targetLine, ReviewParser.DiffSide targetSide) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        removeLoadingUi();

        tabbedPane.removeAll();
        tabbedPane.addTab(panel.getTitle(), panel.getComponent());
        this.currentDiffPanel = panel;

        if (showFileTree()) {
            fileTreePanel.selectFile(fileIndex);
        }

        panel.resetAutoScrollFlag();

        if (targetLine > 0) {
            // Skip resetToFirstDifference and auto-scroll in diff()
            if (getCurrentFontIndex() >= 0) {
                panel.applyEditorFontSize(FONT_SIZES.get(getCurrentFontIndex()));
            }

            if (panel instanceof BufferDiffPanel bp) {
                resetDocumentDirtyStateAfterTheme(bp);
            }

            panel.refreshComponentListeners();
            needsLayoutReset = true;
            panel.diff(false);

            SwingUtilities.invokeLater(() -> scrollToLineInCurrentPanel(targetLine, targetSide));
        } else {
            panel.resetToFirstDifference();

            if (getCurrentFontIndex() >= 0) {
                panel.applyEditorFontSize(FONT_SIZES.get(getCurrentFontIndex()));
            }

            if (panel instanceof BufferDiffPanel bp) {
                resetDocumentDirtyStateAfterTheme(bp);
            }

            panel.refreshComponentListeners();
            needsLayoutReset = true;
            panel.diff(true);
        }

        boolean isGitRepo = contextManager.getProject().getRepo() instanceof GitRepo;
        if (isGitRepo && menuShowBlame.isSelected()) {
            updateBlameForPanel(panel, true);
        }

        updateNavigationButtons();
        refreshUI();
    }

    /**
     * Apply a specific font size to a single panel's editors and gutters using the panel's polymorphic interface.
     *
     * @param panel The panel to update
     * @param size The font size to apply
     */
    private void applySizeToSinglePanel(@Nullable AbstractDiffPanel panel, float size) {
        if (panel == null) return;
        panel.applyEditorFontSize(size);
    }

    /**
     * Apply font size from current index to every visible code editor and gutter (cached panels + visible panel).
     */
    private void applyAllEditorFontSizes() {
        if (getCurrentFontIndex() < 0) return;

        float fontSize = FONT_SIZES.get(getCurrentFontIndex());
        GlobalUiSettings.saveEditorFontSize(fontSize);

        // Apply to cached panels
        for (var p : core.getCachedPanels()) {
            applySizeToSinglePanel(p, fontSize);
        }

        // Apply to currently visible panel too
        if (currentDiffPanel != null) {
            applySizeToSinglePanel(currentDiffPanel, fontSize);
        }

        SwingUtilities.invokeLater(() -> {
            revalidate();
            repaint();
        });
    }

    /** Increase font size using interface method, then apply to all panels. */
    @Override
    public void increaseEditorFont() {
        EditorFontSizeControl.super.increaseEditorFont();
        applyAllEditorFontSizes();
    }

    /** Decrease font size using interface method, then apply to all panels. */
    @Override
    public void decreaseEditorFont() {
        EditorFontSizeControl.super.decreaseEditorFont();
        applyAllEditorFontSizes();
    }

    /** Reset font size using interface method, then apply to all panels. */
    @Override
    public void resetEditorFont() {
        EditorFontSizeControl.super.resetEditorFont();
        applyAllEditorFontSizes();
    }

    /**
     * Reset layout hierarchy to fix broken container relationships after file navigation. This rebuilds the
     * BorderLayout relationships to restore proper resize behavior.
     */
    private void resetLayoutHierarchy(AbstractDiffPanel currentPanel) {
        // Remove and re-add mainSplitPane to reset BorderLayout relationships
        remove(mainSplitPane);
        invalidate();
        add(mainSplitPane, BorderLayout.CENTER);
        revalidate();

        // Ensure child components are properly updated
        SwingUtilities.invokeLater(() -> {
            getTabbedPane().revalidate();
            currentPanel.getComponent().revalidate();

            // Refresh scroll synchronizer for BufferDiffPanel (side-by-side view)
            if (currentPanel instanceof BufferDiffPanel bufferPanel) {
                var synchronizer = bufferPanel.getScrollSynchronizer();
                if (synchronizer != null) {
                    synchronizer.invalidateViewportCacheForBothPanels();
                }
            }
            // For UnifiedDiffPanel, trigger refreshComponentListeners to ensure proper layout
            else if (currentPanel instanceof UnifiedDiffPanel) {
                currentPanel.refreshComponentListeners();
            }
        });
    }

    /**
     * Reset document dirty state after theme application. This prevents false save prompts caused by document events
     * fired during syntax highlighting setup.
     */
    private void resetDocumentDirtyStateAfterTheme(BufferDiffPanel panel) {
        var diffNode = panel.getDiffNode();
        if (diffNode == null) {
            return;
        }

        // Safely re-evaluate dirty state for both left and right documents.
        // Do NOT unconditionally reset the saved baseline to current content (resetDirtyState),
        // because that may hide real unsaved edits that happened earlier.
        // Instead, ask each AbstractBufferDocument to recheck whether its current content truly
        // matches the saved baseline and clear the changed flag only if appropriate.
        var leftBufferNode = diffNode.getBufferNodeLeft();
        if (leftBufferNode != null) {
            var leftDoc = leftBufferNode.getDocument();
            if (leftDoc instanceof AbstractBufferDocument abd) {
                abd.recheckChangedState();
            }
        }

        var rightBufferNode = diffNode.getBufferNodeRight();
        if (rightBufferNode != null) {
            var rightDoc = rightBufferNode.getDocument();
            if (rightDoc instanceof AbstractBufferDocument abd) {
                abd.recheckChangedState();
            }
        }

        // Trigger recalculation of the panel's dirty state to update UI
        SwingUtilities.invokeLater(panel::recalcDirty);
    }

    /**
     * Clean up resources when the panel is disposed.
     */
    public void dispose() {
        core.clearCache();
        this.currentDiffPanel = null;
        removeAll();
    }

    /**
     * Check if this diff panel matches the given file comparisons. Used to find existing panels showing the same
     * content to avoid duplicates.
     *
     * @param leftSources The left sources to match
     * @param rightSources The right sources to match
     * @return true if this panel shows the same content
     */
    public boolean matchesContent(List<BufferSource> leftSources, List<BufferSource> rightSources) {

        if (core.getFileComparisons().size() != leftSources.size() || leftSources.size() != rightSources.size()) {
            return false;
        }

        // Check if this is an uncommitted changes diff (all left sources are HEAD, all right sources are FileSource)
        boolean isUncommittedChanges = leftSources.stream()
                        .allMatch(src -> src instanceof BufferSource.StringSource ss && "HEAD".equals(ss.title()))
                && rightSources.stream().allMatch(src -> src instanceof BufferSource.FileSource);

        if (isUncommittedChanges) {
            return matchesUncommittedChangesContent(rightSources);
        }

        // Regular order-based comparison for other types of diffs
        for (int i = 0; i < core.getFileComparisons().size(); i++) {
            var existing = core.getFileComparisons().get(i);
            var leftSource = leftSources.get(i);
            var rightSource = rightSources.get(i);

            boolean leftMatches = sourcesMatch(existing.leftSource(), leftSource);
            boolean rightMatches = sourcesMatch(existing.rightSource(), rightSource);

            if (!leftMatches || !rightMatches) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesUncommittedChangesContent(List<BufferSource> rightSources) {

        // Extract the set of filenames from the requested sources (right sources are FileSource)
        var requestedFiles = rightSources.stream()
                .filter(src -> src instanceof BufferSource.FileSource)
                .map(src -> ((BufferSource.FileSource) src).title())
                .collect(Collectors.toSet());

        // Extract the set of filenames from existing panel
        var existingFiles = core.getFileComparisons().stream()
                .filter(fc -> fc.rightSource() instanceof BufferSource.FileSource)
                .map(fc -> ((BufferSource.FileSource) fc.rightSource()).title())
                .collect(Collectors.toSet());

        boolean matches = requestedFiles.equals(existingFiles);
        return matches;
    }

    private boolean sourcesMatch(BufferSource source1, BufferSource source2) {

        if (source1.getClass() != source2.getClass()) {
            return false;
        }

        if (source1 instanceof BufferSource.FileSource fs1 && source2 instanceof BufferSource.FileSource fs2) {
            boolean matches = fs1.file().equals(fs2.file());
            return matches;
        }

        if (source1 instanceof BufferSource.StringSource ss1 && source2 instanceof BufferSource.StringSource ss2) {
            // Early exit for different sizes to avoid expensive content comparison
            if (ss1.content().length() != ss2.content().length()) {
                return false;
            }

            // Compare filename, title, and full content to avoid false positives
            boolean filenameMatch = Objects.equals(ss1.filename(), ss2.filename());
            boolean titleMatch = Objects.equals(ss1.title(), ss2.title());
            boolean contentMatch = Objects.equals(ss1.content(), ss2.content());

            boolean result = filenameMatch && titleMatch && contentMatch;
            return result;
        }

        return false;
    }

    /** Converts technical error messages to user-friendly descriptions. */
    private String formatBlameErrorMessage(String errorMsg) {
        if (errorMsg.contains("File not found")) {
            return "file not found";
        }
        if (errorMsg.contains("Git command failed")) {
            return "git command failed";
        }
        if (errorMsg.toLowerCase(Locale.ROOT).contains("not a git repository")) {
            return "not a git repository";
        }
        // Return simplified version of original message
        return errorMsg.toLowerCase(Locale.ROOT);
    }

    /**
     * Shows one-time error dialog and updates menu text. Prioritizes right over left errors. Doesn't auto-disable
     * blame.
     */
    private void handleBlameError(@Nullable String rightError, @Nullable String leftError) {
        String errorMsg = (rightError != null) ? rightError : leftError;

        if (errorMsg != null && !blameErrorNotified) {
            var userMessage = formatBlameErrorMessage(errorMsg);
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(
                        BrokkDiffPanel.this, userMessage, "Git Blame Unavailable", JOptionPane.WARNING_MESSAGE);
                menuShowBlame.setText("Show Git Blame (unavailable: " + userMessage + ")");
            });
            blameErrorNotified = true;
        } else if (errorMsg != null) {
            SwingUtilities.invokeLater(() -> {
                menuShowBlame.setText("Show Git Blame (unavailable: " + formatBlameErrorMessage(errorMsg) + ")");
            });
        }
    }

    private void updateBlameForPanel(AbstractDiffPanel panel, boolean show) {
        if (!show) {
            panel.clearBlame();
            return;
        }

        var targetPath = panel.getTargetPathForBlame();
        if (targetPath == null) {
            return;
        }

        if (blameService == null) {
            logger.warn("Blame service not available (not a git repo)");
            return;
        }

        // Determine which file comparison this panel represents. Fall back to currentFileIndex if none assigned.
        int fileIndex = panel.getAssociatedFileIndex();
        if (fileIndex < 0 || fileIndex >= core.getFileComparisons().size()) {
            fileIndex = core.getCurrentIndex();
        }
        var comparison = core.getFileComparisons().get(fileIndex);

        // Extract revision information from BufferSources
        String leftRevision = comparison.leftSource().revisionSha();
        String rightRevision = comparison.rightSource().revisionSha();

        final Path finalTargetPath = targetPath;

        // Request blame for right side (use revision if available, otherwise working tree)
        CompletableFuture<Map<Integer, BlameService.BlameInfo>> rightBlameFuture;
        if (rightRevision != null) {
            rightBlameFuture = blameService.requestBlameForRevision(targetPath, rightRevision);
        } else {
            rightBlameFuture = blameService.requestBlame(targetPath);
        }

        // Request blame for left side (use revision if available, otherwise HEAD or empty)
        CompletableFuture<Map<Integer, BlameService.BlameInfo>> leftBlameFuture;
        if (leftRevision != null) {
            leftBlameFuture = blameService.requestBlameForRevision(targetPath, leftRevision);
        } else if (blameService.fileExistsInRevision(targetPath, "HEAD")) {
            leftBlameFuture = blameService.requestBlameForRevision(targetPath, "HEAD");
        } else {
            leftBlameFuture = CompletableFuture.completedFuture(Map.of());
        }

        CompletableFuture.allOf(rightBlameFuture, leftBlameFuture).whenComplete((v, exc) -> {
            var rightMap = rightBlameFuture.join();
            var leftMap = leftBlameFuture.join();

            logger.debug(
                    "Blame returned {} right entries, {} left entries for: {}",
                    rightMap.size(),
                    leftMap.size(),
                    finalTargetPath);

            if (rightMap.isEmpty() && leftMap.isEmpty()) {
                String rightError = blameService.getLastError(finalTargetPath);
                String leftError = blameService.getLastErrorForRevision(finalTargetPath, "HEAD");
                if (rightError != null || leftError != null) {
                    handleBlameError(rightError, leftError);
                }
            } else {
                blameErrorNotified = false;
            }

            SwingUtilities.invokeLater(() -> {
                if (!rightMap.isEmpty() || !leftMap.isEmpty()) {
                    menuShowBlame.setText("Show Git Blame");
                }
                panel.applyBlame(leftMap, rightMap);
            });
        });
    }

    /** Marks blame as stale after document edit. Blame refreshes automatically on save. */
    public void invalidateBlameForDocument(BufferDocumentIF bufferDocument) {
        if (blameService == null) {
            return;
        }

        if (currentDiffPanel instanceof BufferDiffPanel bp) {
            var left = bp.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
            var right = bp.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);

            SwingUtilities.invokeLater(() -> {
                if (left != null && left.getBufferDocument() == bufferDocument) {
                    left.getGutterComponent().markBlameStale();
                }
                if (right != null && right.getBufferDocument() == bufferDocument) {
                    right.getGutterComponent().markBlameStale();
                }
            });
        }
    }

    /** Clears cache and refreshes blame after file save. */
    public void refreshBlameAfterSave(Path filePath) {
        var service = blameService;
        if (service == null) {
            return;
        }

        service.clearCacheFor(filePath);

        if (currentDiffPanel instanceof BufferDiffPanel bp) {
            var left = bp.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
            var right = bp.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);

            if (left != null) {
                var leftDoc = left.getBufferDocument();
                if (leftDoc != null && filePath.toString().equals(leftDoc.getName())) {
                    refreshBlamePanelAsync(service, left, filePath);
                }
            }

            if (right != null) {
                var rightDoc = right.getBufferDocument();
                if (rightDoc != null && filePath.toString().equals(rightDoc.getName())) {
                    refreshBlamePanelAsync(service, right, filePath);
                }
            }
        }
    }

    private void refreshBlamePanelAsync(BlameService service, FilePanel panel, Path filePath) {
        service.requestBlame(filePath).thenAccept(blameMap -> {
            SwingUtilities.invokeLater(() -> {
                panel.getGutterComponent().setBlameLines(blameMap);
            });
        });
    }

    /** Returns true if currently in unified view mode, false for side-by-side. */
    public boolean isUnifiedView() {
        return isUnifiedView;
    }

    /**
     * Get the global preference for showing all lines in unified view.
     *
     * @return true if unified view should show full context, false for 3-line context
     */
    public boolean getGlobalShowAllLinesInUnified() {
        return globalShowAllLinesInUnified;
    }

    /**
     * Switch between unified and side-by-side view modes.
     *
     * @param useUnifiedView true for unified view, false for side-by-side view
     */
    private void switchViewMode(boolean useUnifiedView) {
        if (this.isUnifiedView == useUnifiedView) {
            return; // No change needed
        }

        // Check for unsaved changes before switching views
        if (hasUnsavedChanges()) {
            Object[] options = {"Save All", "Discard", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    this,
                    "You have unsaved changes. Save or discard before switching views?",
                    "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[0]);

            if (choice == 0) { // Save All
                saveAll();
            } else if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) { // Cancel or X button
                // Reset toggle to previous state
                SwingUtilities.invokeLater(() -> viewModeToggle.setSelected(!useUnifiedView));
                return; // Abort the view switch
            }
            // choice == 1 (Discard) - continue with switch, losing edits
        }

        this.isUnifiedView = useUnifiedView;
        GlobalUiSettings.saveDiffUnifiedView(useUnifiedView);

        // Update toolbar controls for the new view mode
        updateToolbarForViewMode();

        // Clear current panel reference since it's the wrong type now
        this.currentDiffPanel = null;

        // Force cache invalidation
        core.clearCache();

        // Refresh the current file with the new view mode
        core.showFile(core.getCurrentIndex());
    }
}
