package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import io.github.jbellis.brokk.difftool.ui.unified.UnifiedDiffDocument;
import io.github.jbellis.brokk.difftool.ui.unified.UnifiedDiffPanel;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import io.github.jbellis.brokk.util.ContentDiffUtils;
import io.github.jbellis.brokk.util.GlobalUiSettings;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.SlidingWindowCache;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.JToggleButton;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

public class BrokkDiffPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(BrokkDiffPanel.class);
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;
    private final JSplitPane mainSplitPane;
    private final FileTreePanel fileTreePanel;
    private boolean started;
    private final JLabel loadingLabel = createLoadingLabel();
    private final GuiTheme theme;
    private final JToggleButton viewModeToggle = new JToggleButton("Unified View");

    // Tools menu items
    private final JCheckBoxMenuItem menuShowBlame = new JCheckBoxMenuItem("Show Git Blame");
    private final JCheckBoxMenuItem menuShowAllLines = new JCheckBoxMenuItem("Show All Lines");
    private final JCheckBoxMenuItem menuShowBlankLineDiffs = new JCheckBoxMenuItem("Show Empty Line Diffs");

    // Global preferences loaded from GlobalUiSettings
    private boolean globalShowAllLinesInUnified = GlobalUiSettings.isDiffShowAllLines();

    // Toolbar for UI controls
    @Nullable
    private JToolBar toolBar;

    // All file comparisons with lazy loading cache
    final List<FileComparisonInfo> fileComparisons;
    private int currentFileIndex = 0;
    private final boolean isMultipleCommitsContext;
    private final int initialFileIndex;

    // Thread-safe sliding window cache for loaded diff panels
    private static final int WINDOW_SIZE = PerformanceConstants.DEFAULT_SLIDING_WINDOW;
    private static final int MAX_CACHED_PANELS = PerformanceConstants.MAX_CACHED_DIFF_PANELS;
    private final SlidingWindowCache<Integer, IDiffPanel> panelCache =
            new SlidingWindowCache<>(MAX_CACHED_PANELS, WINDOW_SIZE);

    // View mode state loaded from GlobalUiSettings
    private boolean isUnifiedView = GlobalUiSettings.isDiffUnifiedView();

    /**
     * Inner class to hold a single file comparison metadata Note: No longer holds the diffPanel directly - that's
     * managed by the cache
     */
    static class FileComparisonInfo {
        final BufferSource leftSource;
        final BufferSource rightSource;

        @Nullable
        BufferDiffPanel sideBySidePanel; // Side-by-side view panel

        @Nullable
        UnifiedDiffPanel unifiedPanel; // Unified view panel

        FileComparisonInfo(BufferSource leftSource, BufferSource rightSource) {
            this.leftSource = leftSource;
            this.rightSource = rightSource;
            this.sideBySidePanel = null; // Initialize @Nullable fields
            this.unifiedPanel = null;
        }

        // Legacy method to maintain compatibility
        @Nullable
        BufferDiffPanel getDiffPanel() {
            return sideBySidePanel;
        }

        String getDisplayName() {
            // Returns formatted name for UI display
            String leftName = getSourceName(leftSource);
            String rightName = getSourceName(rightSource);

            if (leftName.equals(rightName)) {
                return leftName;
            }
            return leftName + " vs " + rightName;
        }

        private String getSourceName(BufferSource source) {
            if (source instanceof BufferSource.FileSource fs) {
                return fs.file().getName();
            } else if (source instanceof BufferSource.StringSource ss) {
                return ss.filename() != null ? ss.filename() : ss.title();
            }
            return source.title();
        }
    }

    public BrokkDiffPanel(Builder builder, GuiTheme theme) {
        this.theme = theme;
        this.contextManager = builder.contextManager;
        this.isMultipleCommitsContext = builder.isMultipleCommitsContext;
        this.initialFileIndex = builder.initialFileIndex;

        // Initialize blame service if we have a git repo
        if (contextManager.getProject().getRepo() instanceof GitRepo gitRepo) {
            this.blameService = new BlameService(gitRepo.getGit());
        } else {
            this.blameService = null;
        }

        // Initialize file comparisons list - all modes use the same approach
        this.fileComparisons = new ArrayList<>(builder.fileComparisons);
        assert !this.fileComparisons.isEmpty() : "File comparisons cannot be empty";
        this.currentDiffPanel = null; // Initialize @Nullable field

        // Make the container focusable, so it can handle key events
        setFocusable(true);
        tabbedPane = new JTabbedPane();

        // Initialize file tree panel
        fileTreePanel = new FileTreePanel(
                this.fileComparisons, contextManager.getProject().getRoot(), builder.rootTitle);

        // Create split pane with file tree on left and tabs on right (only if multiple files)
        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        if (fileComparisons.size() > 1) {
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

        // Set up tree selection listener (only if multiple files)
        if (fileComparisons.size() > 1) {
            fileTreePanel.setSelectionListener(this::switchToFile);
        }
        // Add an AncestorListener to trigger 'start()' when the panel is added to a container
        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                start();
                // Initialize file tree after panel is added to UI
                if (fileComparisons.size() > 1) {
                    fileTreePanel.initializeTree();
                }
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {}

            @Override
            public void ancestorRemoved(AncestorEvent event) {}
        });

        // Set up menu items
        menuShowBlankLineDiffs.setSelected(GlobalUiSettings.isDiffShowBlankLines());
        JMDiffNode.setIgnoreBlankLineDiffs(!GlobalUiSettings.isDiffShowBlankLines());
        menuShowBlankLineDiffs.addActionListener(e -> {
            boolean show = menuShowBlankLineDiffs.isSelected();
            GlobalUiSettings.saveDiffShowBlankLines(show);
            JMDiffNode.setIgnoreBlankLineDiffs(!show);
            refreshAllDiffPanels();
        });

        menuShowAllLines.setSelected(globalShowAllLinesInUnified);
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

        boolean initialBlameState = GlobalUiSettings.isDiffShowBlame();
        boolean isGitRepo = contextManager.getProject().getRepo() instanceof io.github.jbellis.brokk.git.GitRepo;
        menuShowBlame.setSelected(initialBlameState && isGitRepo);
        menuShowBlame.setEnabled(isGitRepo);
        menuShowBlame.addActionListener(e -> {
            var panel = getCurrentContentPanel();
            boolean show = menuShowBlame.isSelected();

            GlobalUiSettings.saveDiffShowBlame(show);

            if (panel instanceof io.github.jbellis.brokk.difftool.ui.AbstractDiffPanel adp) {
                adp.setShowGutterBlame(show);
                updateBlameForPanel(adp, show);
            } else if (panel instanceof io.github.jbellis.brokk.difftool.ui.IDiffPanel idp) {
                updateBlameForPanel(idp, show);
            }
        });

        // Set up view mode toggle with icons
        viewModeToggle.setSelected(isUnifiedView); // Load from global preference
        viewModeToggle.setIcon(Icons.VIEW_UNIFIED); // Show unified icon when in side-by-side mode
        viewModeToggle.setSelectedIcon(Icons.VIEW_SIDE_BY_SIDE); // Show side-by-side icon when in unified mode
        viewModeToggle.setText(null); // Remove text, use icon only
        viewModeToggle.setToolTipText("Toggle Unified View");
        viewModeToggle.addActionListener(e -> {
            switchViewMode(viewModeToggle.isSelected());
        });

        revalidate();
    }

    // Builder Class
    public static class Builder {
        private final GuiTheme theme;
        private final ContextManager contextManager;
        private final List<FileComparisonInfo> fileComparisons;
        private boolean isMultipleCommitsContext = false;
        private int initialFileIndex = 0;

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
            this.fileComparisons.add(new FileComparisonInfo(leftSource, rightSource));
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
    private IDiffPanel currentDiffPanel;

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

    @Nullable
    private UnifiedDiffPanel getUnifiedDiffPanel() {
        return currentDiffPanel instanceof UnifiedDiffPanel ? (UnifiedDiffPanel) currentDiffPanel : null;
    }

    /**
     * Check if the given panel represents a working tree diff (vs a historical commit diff). Working tree diffs have
     * FileDocument on the right side, while commit diffs have StringDocument.
     */
    private boolean isWorkingTreeDiff(IDiffPanel panel) {
        if (panel instanceof BufferDiffPanel bp) {
            var right = bp.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);
            if (right != null) {
                var bd = right.getBufferDocument();
                return bd instanceof io.github.jbellis.brokk.difftool.doc.FileDocument;
            }
        } else if (panel instanceof UnifiedDiffPanel up) {
            var dn = up.getDiffNode();
            if (dn != null) {
                var rightNode = dn.getBufferNodeRight();
                if (rightNode != null) {
                    var doc = rightNode.getDocument();
                    return doc instanceof io.github.jbellis.brokk.difftool.doc.FileDocument;
                }
            }
        }
        return false;
    }

    /** Get content string from BufferSource, handling both FileSource and StringSource. */
    private static String getContentFromSource(BufferSource source) throws Exception {
        if (source instanceof BufferSource.StringSource stringSource) {
            return stringSource.content();
        } else if (source instanceof BufferSource.FileSource fileSource) {
            var file = fileSource.file();
            if (!file.exists() || !file.isFile()) {
                return "";
            }
            return java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Unsupported BufferSource type: " + source.getClass());
        }
    }

    public void nextFile() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // Disable all control buttons FIRST, before any logic
        disableAllControlButtons();

        if (canNavigateToNextFile()) {
            try {
                switchToFile(currentFileIndex + 1);
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
                switchToFile(currentFileIndex - 1);
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
        if (index < 0 || index >= fileComparisons.size()) {
            logger.warn("Invalid file index {} (valid range: 0-{})", index, fileComparisons.size() - 1);
            return;
        }

        // Update sliding window in cache - this automatically evicts files outside window
        panelCache.updateWindowCenter(index, fileComparisons.size());

        currentFileIndex = index;

        // Load current file if not in cache
        loadFileOnDemand(currentFileIndex);

        // Predictively load adjacent files in background
        preloadAdjacentFiles(currentFileIndex);

        // Update tree selection to match current file (only if multiple files)
        if (fileComparisons.size() > 1) {
            fileTreePanel.selectFile(currentFileIndex);
        }

        updateNavigationButtons();

        // Log memory and window status
        logMemoryUsage();
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
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(50, 20, 50, 20));

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
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(50, 20, 50, 20));

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
            String leftContent;
            String rightContent;
            BufferSource currentLeftSource;
            BufferSource currentRightSource;

            // Handle both unified and side-by-side modes
            var bufferPanel = getBufferDiffPanel();
            var unifiedPanel = getUnifiedDiffPanel();

            if (bufferPanel != null) {
                // Side-by-side mode
                var leftPanel = bufferPanel.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
                var rightPanel = bufferPanel.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);
                if (leftPanel == null || rightPanel == null) {
                    logger.warn("Capture diff called but left or right panel is null");
                    return;
                }
                leftContent = leftPanel.getEditor().getText();
                rightContent = rightPanel.getEditor().getText();

                // Get the current file comparison sources
                var currentComparison = fileComparisons.get(currentFileIndex);
                currentLeftSource = currentComparison.leftSource;
                currentRightSource = currentComparison.rightSource;
            } else if (unifiedPanel != null) {
                // Unified mode - get content from BufferSources
                var currentComparison = fileComparisons.get(currentFileIndex);
                currentLeftSource = currentComparison.leftSource;
                currentRightSource = currentComparison.rightSource;

                try {
                    leftContent = getContentFromSource(currentLeftSource);
                    rightContent = getContentFromSource(currentRightSource);
                } catch (Exception ex) {
                    logger.warn("Failed to get content from sources for diff capture", ex);
                    return;
                }
            } else {
                logger.warn("Capture diff called but both bufferPanel and unifiedPanel are null");
                return;
            }

            var leftLines = Arrays.asList(leftContent.split("\\R"));
            var rightLines = Arrays.asList(rightContent.split("\\R"));

            // Build a friendlier description that shows a shortened hash plus
            // the first-line commit title (trimmed with ... when overly long)
            // Build user-friendly labels for the two sides
            GitRepo repo = null;
            try {
                repo = (GitRepo) contextManager.getProject().getRepo();
            } catch (Exception lookupEx) {
                // Commit message lookup is best-effort; log at TRACE and continue.
                if (logger.isTraceEnabled()) {
                    logger.trace("Commit message lookup failed: {}", lookupEx.toString());
                }
            }
            var description = "Captured Diff: %s vs %s"
                    .formatted(
                            GitUiUtil.friendlyCommitLabel(currentLeftSource.title(), repo),
                            GitUiUtil.friendlyCommitLabel(currentRightSource.title(), repo));

            var patch = DiffUtils.diff(leftLines, rightLines, (DiffAlgorithmListener) null);
            var unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                    currentLeftSource.title(), currentRightSource.title(), leftLines, patch, 0);
            var diffText = String.join("\n", unifiedDiff);

            var detectedFilename = detectFilename(currentLeftSource, currentRightSource);

            var syntaxStyle = SyntaxConstants.SYNTAX_STYLE_NONE;
            if (detectedFilename != null) {
                int dotIndex = detectedFilename.lastIndexOf('.');
                if (dotIndex > 0 && dotIndex < detectedFilename.length() - 1) {
                    var extension = detectedFilename.substring(dotIndex + 1);
                    syntaxStyle = io.github.jbellis.brokk.util.SyntaxDetector.fromExtension(extension);
                } else {
                    // If no extension or malformed, SyntaxDetector might still identify some common filenames
                    syntaxStyle = io.github.jbellis.brokk.util.SyntaxDetector.fromExtension(detectedFilename);
                }
            }

            var fragment = new ContextFragment.StringFragment(contextManager, diffText, description, syntaxStyle);
            contextManager.submitContextTask(() -> {
                contextManager.addVirtualFragment(fragment);
                contextManager.getIo().systemOutput("Added captured diff to context: " + description);
            });
        });
        // Add buttons to toolbar with spacing
        toolBar.add(btnPrevious);
        toolBar.add(Box.createHorizontalStrut(10)); // 10px spacing
        toolBar.add(btnNext);

        // Add file navigation buttons if multiple files
        if (fileComparisons.size() > 1) {
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

        // Only show unified view toggle if dev mode is enabled
        boolean isDevMode = Boolean.parseBoolean(System.getProperty("brokk.devmode", "false"));
        if (isDevMode) {
            toolBar.add(viewModeToggle);
            toolBar.add(Box.createHorizontalStrut(10));
        }

        // Add tools button with popup menu
        btnTools.setIcon(Icons.DIFF_TOOLS);
        btnTools.setToolTipText("View Options");
        btnTools.setText(null); // Icon-only button
        btnTools.setBorderPainted(false);
        btnTools.setContentAreaFilled(false);
        btnTools.setFocusPainted(false);
        var toolsMenu = new JPopupMenu();
        toolsMenu.add(menuShowBlame);
        toolsMenu.add(menuShowBlankLineDiffs);
        if (isDevMode) {
            toolsMenu.add(menuShowAllLines);
        }
        btnTools.addActionListener(e -> toolsMenu.show(btnTools, 0, btnTools.getHeight()));
        toolBar.add(btnTools);

        // Update control enable/disable state based on view mode
        updateToolbarForViewMode();

        toolBar.add(Box.createHorizontalGlue()); // Pushes subsequent components to the right
        toolBar.add(captureDiffButton);

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
            var isFirstChangeOverall = currentFileIndex == 0 && currentPanel.isAtFirstLogicalChange();
            var isLastChangeOverall =
                    currentFileIndex == fileComparisons.size() - 1 && currentPanel.isAtLastLogicalChange();
            btnPrevious.setEnabled(!isFirstChangeOverall);
            btnNext.setEnabled(!isLastChangeOverall);
        } else {
            btnPrevious.setEnabled(false);
            btnNext.setEnabled(false);
        }

        // Capture diff button should always be enabled
        captureDiffButton.setEnabled(true);

        // Update blame menu item enabled state
        // Blame is only available for working tree diffs in git repos (not for historical commit diffs)
        // Note: We don't modify isSelected() here - that represents user preference and should persist across file
        // changes
        boolean isGitRepo = contextManager.getProject().getRepo() instanceof io.github.jbellis.brokk.git.GitRepo;
        boolean isWorkingTree = currentDiffPanel != null && isWorkingTreeDiff(currentDiffPanel);
        menuShowBlame.setEnabled(isGitRepo && isWorkingTree);

        // Update save button text, enable state, and visibility
        // Compute the exact number of panels that would be saved by saveAll():
        // include currentDiffPanel (if present) plus all cached panels (deduplicated),
        // and count those with hasUnsavedChanges() == true.
        int dirtyCount = 0;
        var visited = new HashSet<IDiffPanel>();

        if (currentDiffPanel != null) {
            visited.add(currentDiffPanel);
            if (currentDiffPanel.hasUnsavedChanges()) {
                dirtyCount++;
            }
        }

        for (var p : panelCache.nonNullValues()) {
            if (visited.add(p) && p.hasUnsavedChanges()) {
                dirtyCount++;
            }
        }

        String baseSaveText = fileComparisons.size() > 1 ? "Save All" : "Save";
        btnSaveAll.setToolTipText(dirtyCount > 0 ? baseSaveText + " (" + dirtyCount + ")" : baseSaveText);
        // Disable save button when in unified mode, when all sides are read-only, or when there are no changes
        btnSaveAll.setEnabled(enableUndoRedo && dirtyCount > 0);

        // Update per-file dirty indicators in the file tree (only when multiple files are shown)
        if (fileComparisons.size() > 1) {
            var dirty = new HashSet<Integer>();

            // Current (visible) file (only if it's a BufferDiffPanel)
            var currentBufferPanel = getBufferDiffPanel();
            if (currentBufferPanel != null && currentBufferPanel.hasUnsavedChanges()) {
                dirty.add(currentFileIndex);
            }

            // Cached files (use keys to keep index association, only BufferDiffPanels can be dirty)
            for (var key : panelCache.getCachedKeys()) {
                var panel = panelCache.get(key);
                if (panel instanceof BufferDiffPanel && panel.hasUnsavedChanges()) {
                    dirty.add(key);
                }
            }

            fileTreePanel.setDirtyFiles(dirty);
        }
    }

    /** Returns true if any loaded diff-panel holds modified documents. */
    public boolean hasUnsavedChanges() {
        if (currentDiffPanel != null && currentDiffPanel.hasUnsavedChanges()) return true;
        for (var p : panelCache.nonNullValues()) {
            if (p.hasUnsavedChanges()) return true;
        }
        return false;
    }

    /** Saves every dirty document across all BufferDiffPanels, producing a single undoable history entry. */
    public void saveAll() {
        try {
            // Disable save button temporarily
            btnSaveAll.setEnabled(false);

            // Collect unique BufferDiffPanels to process (current + cached)
            var visited = new LinkedHashSet<BufferDiffPanel>();
            var currentBufferPanel = getBufferDiffPanel();
            if (currentBufferPanel != null) {
                visited.add(currentBufferPanel);
            }
            for (var p : panelCache.nonNullValues()) {
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
                            .anyMatch(f -> f instanceof ContextFragment.ProjectPathFragment ppf
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
                            .systemNotify(
                                    "No files were saved. Errors:\n" + msg, "Save failed", JOptionPane.ERROR_MESSAGE);
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
                    contextManager
                            .getIo()
                            .systemOutput("Saved file outside project scope: " + filename
                                    + " (not added to workspace history)");
                }
            }

            var result = new TaskResult(
                    contextManager,
                    actionDescription,
                    messages,
                    Set.copyOf(changedFiles),
                    TaskResult.StopReason.SUCCESS);

            // Add a single history entry for the whole batch
            try (var scope = contextManager.beginTask("", false)) {
                scope.append(result);
            }
            logger.info("Saved changes to {} file(s): {}", fileCount, actionDescription);

            // Step 4: Finalize panels selectively and refresh UI
            for (var p : panelsToSave) {
                var saved = perPanelResults
                        .getOrDefault(p, new BufferDiffPanel.SaveResult(Set.of(), Map.of()))
                        .succeeded();
                p.finalizeAfterSaveAggregation(saved);
                refreshTabTitle(p);
            }

            // Refresh blame for successfully saved files
            for (String filename : successfulFiles) {
                try {
                    refreshBlameAfterSave(Paths.get(filename));
                } catch (Exception ex) {
                    logger.debug("Failed to refresh blame for {}: {}", filename, ex.getMessage());
                }
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
        } catch (Exception e) {
            logger.error("Error saving files", e);
            updateNavigationButtons();
        }
    }

    /** Refresh tab title (adds/removes “*”). */
    public void refreshTabTitle(BufferDiffPanel panel) {
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

    /** Returns the number of file comparisons in this panel. */
    public int getFileComparisonCount() {
        return fileComparisons.size();
    }

    public void launchComparison() {
        // Show the initial file
        currentFileIndex = initialFileIndex;
        loadFileOnDemand(currentFileIndex);

        // Select the initial file in the tree (only if multiple files)
        if (fileComparisons.size() > 1) {
            fileTreePanel.selectFile(currentFileIndex);
        }
    }

    private void loadFileOnDemand(int fileIndex) {
        loadFileOnDemand(fileIndex, false);
    }

    private void loadFileOnDemand(int fileIndex, boolean skipLoadingUI) {

        if (fileIndex < 0 || fileIndex >= fileComparisons.size()) {
            logger.warn("loadFileOnDemand called with invalid index: {}", fileIndex);
            return;
        }

        var compInfo = fileComparisons.get(fileIndex);

        // First check if panel is already cached (fast read operation)
        var cachedPanel = panelCache.get(fileIndex);
        if (cachedPanel != null) {
            displayCachedFile(fileIndex, cachedPanel);
            return;
        }

        // Atomic check-and-reserve to prevent concurrent loading
        if (!panelCache.tryReserve(fileIndex)) {
            // Another thread is already loading this file or it was cached between checks
            var nowCachedPanel = panelCache.get(fileIndex);
            if (nowCachedPanel != null) {
                displayCachedFile(fileIndex, nowCachedPanel);
            } else {
                // Reserved by another thread, show loading and wait (unless skipping loading UI)
                if (!skipLoadingUI) {
                    showLoadingForFile(fileIndex);
                }
            }
            return;
        }

        // Show loading UI only if not skipping (e.g., during view mode switch)
        if (!skipLoadingUI) {
            showLoadingForFile(fileIndex);
        }

        // Use hybrid approach - sync for small files, async for large files
        HybridFileComparison.createDiffPanel(
                compInfo.leftSource,
                compInfo.rightSource,
                this,
                theme,
                contextManager,
                this.isMultipleCommitsContext,
                fileIndex);
    }

    private void showLoadingForFile(int fileIndex) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        var compInfo = fileComparisons.get(fileIndex);

        // Disable all control buttons during loading
        disableAllControlButtons();

        // Clear existing tabs and show loading label at top center
        tabbedPane.removeAll();

        // Create a panel to hold the loading label at the top
        var loadingPanel = new JPanel(new BorderLayout());
        loadingPanel.add(loadingLabel, BorderLayout.NORTH);
        add(loadingPanel, BorderLayout.CENTER);

        updateFileIndicatorLabel("Loading: " + compInfo.getDisplayName());

        revalidate();
        repaint();
    }

    private void displayCachedFile(int fileIndex, IDiffPanel cachedPanel) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // Check if cached panel type matches current view mode preference
        boolean cachedIsUnified = cachedPanel instanceof UnifiedDiffPanel;

        if (cachedIsUnified != this.isUnifiedView) {

            // Dispose the incompatible panel
            cachedPanel.dispose();

            // Clear current panel reference since it's the wrong type now
            this.currentDiffPanel = null;

            // Clear entire cache to prevent infinite recursion (same pattern as switchViewMode)
            panelCache.clear();

            // Restore window state for adjacent file caching
            panelCache.updateWindowCenter(currentFileIndex, fileComparisons.size());

            // Reload file with correct view mode (cache is now clear, so will create new panel)
            loadFileOnDemand(fileIndex);

            // Verify that panel was actually created after loading

            return;
        }

        var compInfo = fileComparisons.get(fileIndex);

        // Remove loading panel if present (contains the loading label)
        // Find and remove any loading panel
        for (var component : getComponents()) {
            if (component instanceof JPanel panel && panel.getComponentCount() > 0) {
                if (panel.getComponent(0) == loadingLabel) {
                    remove(panel);
                    break;
                }
            }
        }

        // Clear tabs and add the cached panel
        tabbedPane.removeAll();
        tabbedPane.addTab(cachedPanel.getTitle(), cachedPanel.getComponent());
        this.currentDiffPanel = cachedPanel;

        // IMPORTANT: Sync blame state with menu BEFORE any layout-triggering operations
        // This must happen before applyTheme() and diff() which can trigger layout calculations
        // Note: Always set state explicitly to sync cached panels with current menu state
        // Blame is only supported for working tree diffs with valid file paths
        boolean canShowBlame = isWorkingTreeDiff(cachedPanel) && resolveTargetPath(cachedPanel) != null;
        boolean shouldShowBlame = menuShowBlame.isSelected() && canShowBlame;
        if (cachedPanel instanceof BufferDiffPanel bp) {
            var right = bp.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);
            if (right != null) {
                right.getGutterComponent().setShowBlame(shouldShowBlame);
            }
        } else if (cachedPanel instanceof UnifiedDiffPanel up) {
            up.setShowGutterBlame(shouldShowBlame);
        }

        // Reset auto-scroll flag for file navigation to ensure fresh auto-scroll opportunity
        cachedPanel.resetAutoScrollFlag();

        // Reset selectedDelta to first difference for consistent navigation behavior
        cachedPanel.resetToFirstDifference();

        // Apply theme to ensure proper syntax highlighting
        cachedPanel.applyTheme(theme);

        // Reset dirty state after theme application to prevent false save prompts (only for BufferDiffPanel)
        // Theme application can trigger document events that incorrectly mark documents as dirty
        if (cachedPanel instanceof BufferDiffPanel bufferPanel) {
            resetDocumentDirtyStateAfterTheme(bufferPanel);
        }

        // Re-establish component resize listeners and set flag for layout reset on next resize
        cachedPanel.refreshComponentListeners();
        needsLayoutReset = true;

        // Apply diff highlights immediately after theme to prevent timing issues
        cachedPanel.diff(true); // Pass true to trigger auto-scroll for cached panels

        // Start async blame loading (gutter state already synced above)
        if (shouldShowBlame) {
            updateBlameForPanel(cachedPanel, true);
        }

        // Update file indicator
        updateFileIndicatorLabel(compInfo.getDisplayName());

        // Re-enable control buttons after loading is complete
        updateNavigationButtons();

        refreshUI();
    }

    /**
     * Display an error message for a file that cannot be loaded. Clears the loading state and shows the error message.
     */
    public void displayErrorForFile(int fileIndex, String errorMessage) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        logger.error("Cannot display file {}: {}", fileIndex, errorMessage);

        var compInfo = fileComparisons.get(fileIndex);

        // Remove loading panel if present
        for (var component : getComponents()) {
            if (component instanceof JPanel panel && panel.getComponentCount() > 0) {
                if (panel.getComponent(0) == loadingLabel) {
                    remove(panel);
                    break;
                }
            }
        }

        // Clear tabs and show error message
        tabbedPane.removeAll();

        // Create error panel similar to loading panel
        var errorPanel = new JPanel(new BorderLayout());
        var errorLabel = createErrorLabel(errorMessage);
        errorPanel.add(errorLabel, BorderLayout.NORTH);

        tabbedPane.addTab(compInfo.getDisplayName() + " (Too Big)", errorPanel);

        // Update file indicator
        updateFileIndicatorLabel(compInfo.getDisplayName() + " - Too Big");

        // Re-enable navigation buttons but keep current panel null
        updateNavigationButtons();

        // Clear the reserved slot in cache
        panelCache.removeReserved(fileIndex);

        refreshUI();
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
     * Shows the diff panel in a frame. Window bounds are managed via the ContextManager provided during construction.
     *
     * @param title The frame title
     */
    public void showInFrame(String title) {
        var frame = Chrome.newFrame(title);

        // Always intercept close and decide explicitly in windowClosing.
        // This ensures quit actions (eg. Cmd+Q) are intercepted and the user can be prompted.
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.getContentPane().add(this);

        // Get saved bounds from Project via the stored ContextManager
        var bounds = contextManager.getProject().getDiffWindowBounds();
        frame.setBounds(bounds);

        // Save window position and size when closing. We handle the actual disposal here so a
        // global quit (Cmd+Q) still triggers our prompt and can be cancelled by the user.
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Ask user to save if there are unsaved changes. If they cancel, do nothing and keep window open.
                if (confirmClose(frame)) {
                    // User chose to proceed (and possibly saved). Persist window bounds and dispose.
                    try {
                        contextManager.getProject().saveDiffWindowBounds(frame);
                    } catch (Exception ex) {
                        // Be robust: log and continue with dispose even if saving bounds fails.
                        logger.warn("Failed to save diff window bounds on close: {}", ex.getMessage(), ex);
                    }
                    // Explicitly dispose the frame to close the window.
                    frame.dispose();
                } else {
                    // User cancelled - do nothing to prevent closing.
                }
            }
        });

        frame.setVisible(true);
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
        return fileComparisons.size() > 1 && currentFileIndex < fileComparisons.size() - 1;
    }

    private boolean canNavigateToPreviousFile() {
        return fileComparisons.size() > 1 && currentFileIndex > 0;
    }

    @Nullable
    private String detectFilename(BufferSource leftSource, BufferSource rightSource) {
        if (leftSource instanceof BufferSource.StringSource s && s.filename() != null) {
            return s.filename();
        } else if (leftSource instanceof BufferSource.FileSource f) {
            return f.file().getName();
        }

        if (rightSource instanceof BufferSource.StringSource s && s.filename() != null) {
            return s.filename();
        } else if (rightSource instanceof BufferSource.FileSource f) {
            return f.file().getName();
        }
        return null;
    }

    @SuppressWarnings("UnusedVariable")
    private void updateFileIndicatorLabel(String text) {
        // No-op: filename label removed from toolbar
    }

    private void performUndoRedo(java.util.function.Consumer<AbstractContentPanel> action) {
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
        panelCache.nonNullValues().forEach(panel -> panel.diff(true)); // Scroll to selection for user-initiated refresh
        // Refresh current panel if it's not cached
        var current = getBufferDiffPanel();
        if (current != null && !panelCache.containsValue(current)) {
            current.diff(true); // Scroll to selection for user-initiated refresh
        }
        // Update navigation buttons after refresh
        SwingUtilities.invokeLater(this::updateUndoRedoButtons);
        repaint();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        assert SwingUtilities.isEventDispatchThread() : "applyTheme must be called on EDT";

        // Apply theme to cached panels
        for (var panel : panelCache.nonNullValues()) {
            panel.applyTheme(guiTheme);
        }

        // Apply theme to file tree panel (only if multiple files)
        if (fileComparisons.size() > 1) {
            fileTreePanel.applyTheme(guiTheme);
        }

        // Update all child components including toolbar buttons and labels
        SwingUtilities.updateComponentTreeUI(this);
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
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(checkUnsavedChangesBeforeClose());
                } catch (Exception e) {
                    logger.warn("Error while confirming close on EDT: {}", e.getMessage(), e);
                    // Be conservative: cancel quit on unexpected error
                    result.set(false);
                }
            });
        } catch (Exception e) {
            logger.error("Failed to run close confirmation on EDT: {}", e.getMessage(), e);
            return false;
        }
        return result.get();
    }

    /**
     * Displays a cached panel and updates navigation buttons. This is the proper way to display panels created by
     * HybridFileComparison.
     */
    public void displayAndRefreshPanel(int fileIndex, IDiffPanel panel) {
        displayCachedFile(fileIndex, panel);
    }

    /**
     * Cache a panel for the given file index. Helper method for both sync and async panel creation. Uses putReserved if
     * the slot was reserved, otherwise regular put.
     */
    public void cachePanel(int fileIndex, IDiffPanel panel) {
        // Validate that panel type matches current view mode
        boolean isPanelUnified = panel instanceof UnifiedDiffPanel;
        if (isPanelUnified != isUnifiedView) {
            // Don't cache panels that don't match current view mode (prevents async race conditions)
            return;
        }

        // Reset auto-scroll flag for newly created panels
        panel.resetAutoScrollFlag();

        // Ensure creation context is set for debugging (only for BufferDiffPanel)
        if (panel instanceof BufferDiffPanel bufferPanel) {
            if ("unknown".equals(bufferPanel.getCreationContext())) {
                bufferPanel.markCreationContext("cachePanel");
            }
            // Reset selectedDelta to first difference for consistent navigation behavior
            bufferPanel.resetToFirstDifference();
        }

        // Only cache if within current window
        if (panelCache.isInWindow(fileIndex)) {
            var cachedPanel = panelCache.get(fileIndex);
            if (cachedPanel == null) {
                // This was a reserved slot, replace with actual panel
                panelCache.putReserved(fileIndex, panel);
            } else {
                // Direct cache (shouldn't happen in normal flow but handle gracefully)
                panelCache.put(fileIndex, panel);
            }
        } else {
            // Still display but don't cache
        }
    }

    /** Preload adjacent files in the background for smooth navigation */
    private void preloadAdjacentFiles(int currentIndex) {
        contextManager.submitBackgroundTask("Preload adjacent files", () -> {
            // Preload previous file if not cached and in window
            int prevIndex = currentIndex - 1;
            if (prevIndex >= 0 && panelCache.get(prevIndex) == null && panelCache.isInWindow(prevIndex)) {
                preloadFile(prevIndex);
            }

            // Preload next file if not cached and in window
            int nextIndex = currentIndex + 1;
            if (nextIndex < fileComparisons.size()
                    && panelCache.get(nextIndex) == null
                    && panelCache.isInWindow(nextIndex)) {
                preloadFile(nextIndex);
            }
        });
    }

    /** Preload a single file in the background */
    private void preloadFile(int fileIndex) {
        try {
            var compInfo = fileComparisons.get(fileIndex);

            // Use extracted file validation logic
            if (!FileComparisonHelper.isValidForPreload(compInfo.leftSource, compInfo.rightSource)) {
                logger.warn("Skipping preload of file {} - too large for preload", fileIndex);
                return;
            }

            // Create file loading result (includes size validation and error handling)
            var loadingResult = FileComparisonHelper.createFileLoadingResult(
                    compInfo.leftSource, compInfo.rightSource, contextManager, isMultipleCommitsContext);

            // CRITICAL FIX: Compute diff for preloaded JMDiffNode to avoid empty view
            if (loadingResult.isSuccess() && loadingResult.getDiffNode() != null) {
                loadingResult.getDiffNode().diff();
            }

            // Create and cache panel on EDT
            SwingUtilities.invokeLater(() -> {
                // Double-check still needed and in window
                if (panelCache.get(fileIndex) == null && panelCache.isInWindow(fileIndex)) {
                    if (loadingResult.isSuccess()) {
                        // Create appropriate panel type based on current view mode
                        IDiffPanel panel;
                        if (isUnifiedView) {
                            // For UnifiedDiffPanel, we need to check if diffNode is null since constructor requires
                            // non-null
                            var diffNode = loadingResult.getDiffNode();
                            if (diffNode != null) {
                                panel = new UnifiedDiffPanel(this, theme, diffNode);
                            } else {
                                // Fallback to BufferDiffPanel if diffNode is null
                                logger.warn(
                                        "Cannot create UnifiedDiffPanel with null diffNode for file {}, using BufferDiffPanel",
                                        fileIndex);
                                var bufferPanel = new BufferDiffPanel(this, theme);
                                bufferPanel.markCreationContext("preload-fallback");
                                panel = bufferPanel;
                            }
                        } else {
                            var bufferPanel = new BufferDiffPanel(this, theme);
                            bufferPanel.markCreationContext("preload");
                            bufferPanel.setDiffNode(loadingResult.getDiffNode());
                            panel = bufferPanel;
                        }

                        // Apply theme to ensure consistent state and avoid false dirty flags
                        panel.applyTheme(theme);
                        // Clear any transient dirty state caused by mirroring during preload (only for BufferDiffPanel)
                        if (panel instanceof BufferDiffPanel bufferPanel) {
                            resetDocumentDirtyStateAfterTheme(bufferPanel);
                        }

                        // Cache will automatically check window constraints
                        panelCache.put(fileIndex, panel);
                    } else {
                        logger.warn("Skipping preload of file {} - {}", fileIndex, loadingResult.getErrorMessage());
                    }
                } else {
                }
            });

        } catch (Exception e) {
            logger.warn("Failed to preload file {}: {}", fileIndex, e.getMessage());
        }
    }

    /** Log current memory usage and window status */
    private void logMemoryUsage() {
        var runtime = Runtime.getRuntime();
        var totalMemory = runtime.totalMemory();
        var freeMemory = runtime.freeMemory();
        var usedMemory = totalMemory - freeMemory;
        var maxMemory = runtime.maxMemory();

        var percentUsed = (usedMemory * 100) / maxMemory;

        // Use configurable threshold for memory cleanup
        if (percentUsed > PerformanceConstants.MEMORY_HIGH_THRESHOLD_PERCENT) {
            logger.warn("Memory usage high ({}%) with sliding window cache", percentUsed);
            performWindowCleanup();
        }
    }

    /** Perform cleanup when memory usage is high */
    private void performWindowCleanup() {

        // Clear caches in all window panels
        for (var panel : panelCache.nonNullValues()) {
            panel.clearCaches(); // Clear undo history, search results, etc.
        }

        // Suggest garbage collection
        System.gc();
    }

    /**
     * Reset layout hierarchy to fix broken container relationships after file navigation. This rebuilds the
     * BorderLayout relationships to restore proper resize behavior.
     */
    private void resetLayoutHierarchy(IDiffPanel currentPanel) {
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
            if (leftDoc instanceof io.github.jbellis.brokk.difftool.doc.AbstractBufferDocument abd) {
                abd.recheckChangedState();
            }
        }

        var rightBufferNode = diffNode.getBufferNodeRight();
        if (rightBufferNode != null) {
            var rightDoc = rightBufferNode.getDocument();
            if (rightDoc instanceof io.github.jbellis.brokk.difftool.doc.AbstractBufferDocument abd) {
                abd.recheckChangedState();
            }
        }

        // Trigger recalculation of the panel's dirty state to update UI
        SwingUtilities.invokeLater(panel::recalcDirty);
    }

    /**
     * Clean up resources when the panel is disposed. This ensures cached panels are properly disposed of to free
     * memory.
     */
    public void dispose() {
        // Caller is responsible for saving before disposal

        // Clear all cached panels and dispose their resources (thread-safe)
        panelCache.clear();

        // Clear current panel reference
        this.currentDiffPanel = null;

        // Remove all components
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

        if (fileComparisons.size() != leftSources.size() || leftSources.size() != rightSources.size()) {
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
        for (int i = 0; i < fileComparisons.size(); i++) {
            var existing = fileComparisons.get(i);
            var leftSource = leftSources.get(i);
            var rightSource = rightSources.get(i);

            boolean leftMatches = sourcesMatch(existing.leftSource, leftSource);
            boolean rightMatches = sourcesMatch(existing.rightSource, rightSource);

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
        var existingFiles = fileComparisons.stream()
                .filter(fc -> fc.rightSource instanceof BufferSource.FileSource)
                .map(fc -> ((BufferSource.FileSource) fc.rightSource).title())
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

    /**
     * Request and apply blame information for the given panel. This method is asynchronous and will update the gutter
     * components on the EDT.
     *
     * @param panel IDiffPanel (may be BufferDiffPanel or UnifiedDiffPanel)
     * @param show whether to show blame (if false we will clear the gutter blame)
     */
    /** Convert technical error messages to user-friendly descriptions. */
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
     * Resolves the target file path from a diff panel for blame operations.
     *
     * <p>This method extracts the file path from the panel's document, validates it is a FileDocument (not a
     * StringDocument or virtual content), and resolves relative paths against the git repository root.
     *
     * @param panel The diff panel to extract the file path from (non-null)
     * @return The resolved absolute file path, or {@code null} if:
     *     <ul>
     *       <li>The document is not a FileDocument (e.g., StringDocument, virtual content)
     *       <li>No file path could be extracted from the panel
     *       <li>An exception occurred during path resolution
     *     </ul>
     */
    private @Nullable java.nio.file.Path resolveTargetPath(io.github.jbellis.brokk.difftool.ui.IDiffPanel panel) {
        java.nio.file.Path targetPath = null;

        try {
            if (panel instanceof BufferDiffPanel bp) {
                var right = bp.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);
                if (right != null) {
                    var bd = right.getBufferDocument();
                    if (bd != null) {
                        // Accept both FileDocument and StringDocument with valid file paths
                        // This supports local files and revision diffs
                        String name = bd.getName();
                        if (!name.isBlank()) {
                            targetPath = java.nio.file.Paths.get(name);
                        } else {
                            logger.debug("Document has no name/path for blame");
                            return null;
                        }
                    }
                }
            } else if (panel instanceof UnifiedDiffPanel up) {
                var dn = up.getDiffNode();
                if (dn != null) {
                    var rightNode = dn.getBufferNodeRight();
                    if (rightNode != null) {
                        var doc = rightNode.getDocument();
                        // Accept both FileDocument and StringDocument with valid file paths
                        // This supports local files and revision diffs
                        String name = doc.getName();
                        if (!name.isBlank()) {
                            targetPath = java.nio.file.Paths.get(name);
                        } else {
                            logger.debug("Document has no name/path for blame");
                            return null;
                        }
                    }
                }
            }

            if (targetPath == null) {
                logger.debug("No file path found for blame");
                return null;
            }

            // Resolve relative paths against the git repository root
            if (!targetPath.isAbsolute()) {
                var repo = contextManager.getProject().getRepo();
                if (repo instanceof io.github.jbellis.brokk.git.GitRepo gitRepo) {
                    targetPath = gitRepo.getGitTopLevel().resolve(targetPath).normalize();
                } else {
                    // Fallback to absolute path resolution for non-git repos
                    targetPath = targetPath.toAbsolutePath().normalize();
                }
            }
        } catch (Exception ex) {
            logger.warn("Failed to resolve target path for blame: {}", ex.getMessage());
            return null;
        }

        return targetPath;
    }

    /**
     * Applies blame maps to the appropriate gutter components in the panel.
     *
     * <p>Behavior differs by panel type:
     *
     * <ul>
     *   <li><b>BufferDiffPanel (side-by-side):</b> Shows blame on the RIGHT gutter only (revised file), hides blame on
     *       the LEFT gutter
     *   <li><b>UnifiedDiffPanel:</b> Shows both left (HEAD) and right (working tree) blame data, used to display blame
     *       for deletions, context, and additions respectively
     * </ul>
     *
     * @param panel The diff panel to apply blame to (non-null)
     * @param leftMap Blame data for the left/old file (HEAD revision), non-null but may be empty
     * @param rightMap Blame data for the right/new file (working tree), non-null but may be empty
     */
    private void applyBlameMapsToPanel(
            io.github.jbellis.brokk.difftool.ui.IDiffPanel panel,
            Map<Integer, BlameService.BlameInfo> leftMap,
            Map<Integer, BlameService.BlameInfo> rightMap) {
        if (panel instanceof BufferDiffPanel bp) {
            // For side-by-side, show blame on the RIGHT pane gutter (revised)
            var right = bp.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);
            if (right != null) {
                // Always set blame lines (even if empty) and enable display
                // Empty blame data will just show line numbers without blame info
                right.getGutterComponent().setBlameLines(rightMap);
                right.getGutterComponent().setShowBlame(true);
            }
            // Optionally clear/hide left gutter blame
            var left = bp.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
            if (left != null) {
                left.getGutterComponent().setShowBlame(false);
            }
        } else if (panel instanceof UnifiedDiffPanel up) {
            // For unified view, set both right (working tree) and left (HEAD) blame data
            // Always set data (even if empty) - rendering will handle empty data gracefully
            up.setGutterBlameData(rightMap);
            up.setGutterLeftBlameData(leftMap);
            up.setShowGutterBlame(true);
        }
    }

    /**
     * Handles blame errors by showing user notifications and updating UI feedback.
     *
     * <p>This method performs the following actions:
     *
     * <ul>
     *   <li>Shows a one-time warning dialog with user-friendly error message (first error only)
     *   <li>Updates the blame menu item text to reflect the error state
     * </ul>
     *
     * <p>Prioritizes right (working tree) errors over left (HEAD) errors when both are present. If both error
     * parameters are null, no action is taken.
     *
     * <p><b>Note:</b> This method no longer automatically disables blame display. The user explicitly requested blame
     * to be shown, so the gutter will continue to show the blame column (with empty blame data) until the user manually
     * disables it via the menu.
     *
     * @param rightError Error message from working tree blame request, or {@code null} if successful
     * @param leftError Error message from HEAD revision blame request, or {@code null} if successful
     */
    private void handleBlameError(@Nullable String rightError, @Nullable String leftError) {
        String errorMsg = (rightError != null) ? rightError : leftError;

        if (errorMsg != null && !blameErrorNotified) {
            // Show user-friendly error notification (one-time)
            var userMessage = formatBlameErrorMessage(errorMsg);
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(
                        BrokkDiffPanel.this, userMessage, "Git Blame Unavailable", JOptionPane.WARNING_MESSAGE);
                // Update menu item text to show error
                menuShowBlame.setText("Show Git Blame (unavailable: " + userMessage + ")");
            });
            blameErrorNotified = true;
        } else if (errorMsg != null) {
            // Update menu item text even if already notified
            SwingUtilities.invokeLater(() -> {
                menuShowBlame.setText("Show Git Blame (unavailable: " + formatBlameErrorMessage(errorMsg) + ")");
            });
        }

        // Note: We don't automatically hide blame on error - user explicitly requested it
        // The gutter will show line numbers without blame info, maintaining consistent layout
        // User can manually disable blame via the menu if desired
    }

    private void updateBlameForPanel(io.github.jbellis.brokk.difftool.ui.IDiffPanel panel, boolean show) {
        logger.debug(
                "updateBlameForPanel called: panel={}, show={}",
                panel.getClass().getSimpleName(),
                show);

        // Clear any existing displayed blame if hiding
        if (!show) {
            if (panel instanceof BufferDiffPanel bp) {
                var left = bp.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
                var right = bp.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);
                if (left != null) left.getGutterComponent().clearBlame();
                if (right != null) right.getGutterComponent().clearBlame();
            } else if (panel instanceof UnifiedDiffPanel up) {
                up.setShowGutterBlame(false);
            }
            return;
        }

        // Resolve target path from panel
        var targetPath = resolveTargetPath(panel);
        if (targetPath == null) {
            return;
        }

        // Check if blame service is available (only for git repos)
        if (blameService == null) {
            logger.warn("Blame service not available (not a git repo)");
            return;
        }

        // Asynchronously request blame for working tree (right)
        final java.nio.file.Path finalTargetPath = targetPath;
        var rightBlameFuture = blameService.requestBlame(targetPath);

        // Only request HEAD blame if file exists in HEAD (skip for newly added files)
        CompletableFuture<Map<Integer, BlameService.BlameInfo>> leftBlameFuture;
        if (blameService.fileExistsInRevision(targetPath, "HEAD")) {
            leftBlameFuture = blameService.requestBlameForRevision(targetPath, "HEAD");
        } else {
            // File doesn't exist in HEAD (newly added) - use empty map
            leftBlameFuture = CompletableFuture.completedFuture(Map.of());
        }

        // Wait for both to complete
        CompletableFuture.allOf(rightBlameFuture, leftBlameFuture).whenComplete((v, exc) -> {
            var rightMap = rightBlameFuture.join();
            var leftMap = leftBlameFuture.join();

            logger.debug(
                    "Blame returned {} right entries, {} left entries for: {}",
                    rightMap.size(),
                    leftMap.size(),
                    finalTargetPath);

            // Check for errors and provide user feedback (but don't stop - still apply empty blame)
            if (rightMap.isEmpty() && leftMap.isEmpty()) {
                String rightError = blameService.getLastError(finalTargetPath);
                String leftError = blameService.getLastErrorForRevision(finalTargetPath, "HEAD");
                if (rightError != null || leftError != null) {
                    handleBlameError(rightError, leftError);
                }
            } else {
                // Reset error notification flag on successful blame
                blameErrorNotified = false;
            }

            javax.swing.SwingUtilities.invokeLater(() -> {
                // Reset menu item text to default if we have data
                if (!rightMap.isEmpty() || !leftMap.isEmpty()) {
                    menuShowBlame.setText("Show Git Blame");
                }
                // Always apply blame (even if empty) to enable blame column display
                applyBlameMapsToPanel(panel, leftMap, rightMap);
            });
        });
    }

    /**
     * Invalidate blame information for a specific document after it has been edited.
     *
     * <p>When a document is edited, this method marks blame as stale (grayed out) to indicate the line numbers may no
     * longer match. Blame is refreshed automatically when the file is saved.
     *
     * @param bufferDocument The document that was edited (non-null)
     */
    public void invalidateBlameForDocument(BufferDocumentIF bufferDocument) {
        if (blameService == null) {
            return; // No blame service means nothing to invalidate
        }

        if (currentDiffPanel instanceof BufferDiffPanel bp) {
            // Find which panel was edited and mark its blame as stale
            var left = bp.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
            var right = bp.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);

            SwingUtilities.invokeLater(() -> {
                if (left != null && left.getBufferDocument() == bufferDocument) {
                    left.getGutterComponent().markBlameStale();
                    logger.debug("Marked left gutter blame as stale");
                }
                if (right != null && right.getBufferDocument() == bufferDocument) {
                    right.getGutterComponent().markBlameStale();
                    logger.debug("Marked right gutter blame as stale");
                }
            });
        }
    }

    /**
     * Refresh blame information for a saved file.
     *
     * <p>After a file is saved to disk, re-run blame to get fresh data. JGit will read the new file content and show
     * accurate blame for the saved version.
     *
     * @param filePath The absolute path to the file that was saved
     */
    public void refreshBlameAfterSave(java.nio.file.Path filePath) {
        var service = blameService;
        if (service == null) {
            return;
        }

        // Clear cache so next request fetches fresh data from disk
        service.clearCacheFor(filePath);

        if (currentDiffPanel instanceof BufferDiffPanel bp) {
            // Find panel(s) showing this file
            var left = bp.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
            var right = bp.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);

            // Check if left panel shows this file
            if (left != null) {
                var leftDoc = left.getBufferDocument();
                if (leftDoc != null && filePath.toString().equals(leftDoc.getName())) {
                    refreshBlamePanelAsync(service, left, filePath);
                }
            }

            // Check if right panel shows this file
            if (right != null) {
                var rightDoc = right.getBufferDocument();
                if (rightDoc != null && filePath.toString().equals(rightDoc.getName())) {
                    refreshBlamePanelAsync(service, right, filePath);
                }
            }
        }
    }

    private void refreshBlamePanelAsync(BlameService service, FilePanel panel, java.nio.file.Path filePath) {
        logger.debug("Refreshing blame after save for: {}", filePath);
        service.requestBlame(filePath).thenAccept(blameMap -> {
            SwingUtilities.invokeLater(() -> {
                panel.getGutterComponent().setBlameLines(blameMap);
                logger.debug("Updated blame after save: {} ({} lines)", filePath, blameMap.size());
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

        // Clear the current file from cache since we need a different panel type
        var cachedPanel = panelCache.get(currentFileIndex);
        if (cachedPanel != null) {
            // Dispose the old panel to free resources
            cachedPanel.dispose();
        }

        // Clear current panel reference since it's the wrong type now
        this.currentDiffPanel = null;

        // Force cache invalidation - since sliding window manipulation doesn't work reliably,
        // we'll clear the entire cache to ensure the old panel type is removed
        panelCache.clear();

        // Verify the cache is actually clear
        var verifyPanel = panelCache.get(currentFileIndex);
        if (verifyPanel != null) {
            logger.error(
                    "Cache clearing failed - panel still cached after clear(). This indicates a serious cache issue.");
        } else {
        }

        // Refresh the current file with the new view mode (skip loading UI since we already have the data)
        loadFileOnDemand(currentFileIndex, true);
    }
}
