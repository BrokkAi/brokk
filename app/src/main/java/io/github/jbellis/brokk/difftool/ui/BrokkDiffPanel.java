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
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.ContentDiffUtils;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.swing.*;
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
    private final JCheckBox showBlankLineDiffsCheckBox = new JCheckBox("Show blank-lines");

    // All file comparisons with lazy loading cache
    final List<FileComparisonInfo> fileComparisons;
    private int currentFileIndex = 0;
    private final boolean isMultipleCommitsContext;
    private final int initialFileIndex;

    // Thread-safe sliding window cache for loaded diff panels
    private static final int WINDOW_SIZE = PerformanceConstants.DEFAULT_SLIDING_WINDOW;
    private static final int MAX_CACHED_PANELS = PerformanceConstants.MAX_CACHED_DIFF_PANELS;
    private final SlidingWindowCache<Integer, BufferDiffPanel> panelCache =
            new SlidingWindowCache<>(MAX_CACHED_PANELS, WINDOW_SIZE);

    /**
     * Inner class to hold a single file comparison metadata Note: No longer holds the diffPanel directly - that's
     * managed by the cache
     */
    static class FileComparisonInfo {
        final BufferSource leftSource;
        final BufferSource rightSource;

        @Nullable
        BufferDiffPanel diffPanel;

        FileComparisonInfo(BufferSource leftSource, BufferSource rightSource) {
            this.leftSource = leftSource;
            this.rightSource = rightSource;
            this.diffPanel = null; // Initialize @Nullable field
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

        // Initialize file comparisons list - all modes use the same approach
        this.fileComparisons = new ArrayList<>(builder.fileComparisons);
        assert !this.fileComparisons.isEmpty() : "File comparisons cannot be empty";
        this.bufferDiffPanel = null; // Initialize @Nullable field

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

        showBlankLineDiffsCheckBox.setSelected(!JMDiffNode.isIgnoreBlankLineDiffs());
        showBlankLineDiffsCheckBox.addActionListener(e -> {
            boolean show = showBlankLineDiffsCheckBox.isSelected();
            JMDiffNode.setIgnoreBlankLineDiffs(!show);
            refreshAllDiffPanels();
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
                    var currentPanel = getBufferDiffPanel();
                    if (currentPanel != null) {
                        resetLayoutHierarchy(currentPanel);
                    }
                }
            }
        });
    }

    public JButton getBtnUndo() {
        return btnUndo;
    }

    private final MaterialButton btnUndo = new MaterialButton("Undo"); // Initialize to prevent NullAway issues
    private final MaterialButton btnRedo = new MaterialButton("Redo");
    private final MaterialButton btnSaveAll = new MaterialButton("Save");

    // Components for undo/redo/save group that need to be hidden together
    private @Nullable Component undoRedoGroupSeparator;
    private @Nullable Component undoRedoGroupStrutBefore;
    private @Nullable Component undoRedoGroupStrutAfter1;
    private @Nullable Component undoRedoGroupStrutAfter2;
    private @Nullable Component undoRedoGroupStrutAfter3;
    private final MaterialButton captureDiffButton = new MaterialButton("Capture Diff");
    private final MaterialButton btnNext = new MaterialButton("Next Change");
    private final MaterialButton btnPrevious = new MaterialButton("Previous Change");
    private final MaterialButton btnPreviousFile = new MaterialButton("Previous File");
    private final MaterialButton btnNextFile = new MaterialButton("Next File");
    private final JLabel fileIndicatorLabel = new JLabel(""); // Initialize

    // Flag to track when layout hierarchy needs reset after navigation
    private volatile boolean needsLayoutReset = false;

    @Nullable
    private BufferDiffPanel bufferDiffPanel;

    public void setBufferDiffPanel(@Nullable BufferDiffPanel bufferDiffPanel) {
        this.bufferDiffPanel = bufferDiffPanel;
    }

    @Nullable
    private BufferDiffPanel getBufferDiffPanel() {
        return bufferDiffPanel;
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
        logger.debug("Window after switch: {}", panelCache.getWindowInfo());
        logMemoryUsage();
    }

    private void updateNavigationButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        updateUndoRedoButtons();

        btnPreviousFile.setEnabled(canNavigateToPreviousFile());
        btnNextFile.setEnabled(canNavigateToNextFile());
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

        logger.debug("All control buttons disabled during file loading");
    }

    private JToolBar createToolbar() {
        // Create toolbar
        var toolBar = new JToolBar();

        // Buttons are already initialized as fields
        fileIndicatorLabel.setFont(fileIndicatorLabel.getFont().deriveFont(Font.BOLD));

        btnNext.addActionListener(e -> navigateToNextChange());
        btnPrevious.addActionListener(e -> navigateToPreviousChange());
        btnUndo.addActionListener(e -> performUndoRedo(AbstractContentPanel::doUndo));
        btnRedo.addActionListener(e -> performUndoRedo(AbstractContentPanel::doRedo));
        btnSaveAll.addActionListener(e -> saveAll());

        // File navigation handlers
        btnPreviousFile.addActionListener(e -> previousFile());
        btnNextFile.addActionListener(e -> nextFile());

        captureDiffButton.addActionListener(e -> {
            var bufferPanel = getBufferDiffPanel();
            if (bufferPanel == null) {
                logger.warn("Capture diff called but bufferPanel is null");
                return;
            }
            var leftPanel = bufferPanel.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
            var rightPanel = bufferPanel.getFilePanel(BufferDiffPanel.PanelSide.RIGHT);
            if (leftPanel == null || rightPanel == null) {
                logger.warn("Capture diff called but left or right panel is null");
                return;
            }
            var leftContent = leftPanel.getEditor().getText();
            var rightContent = rightPanel.getEditor().getText();
            var leftLines = Arrays.asList(leftContent.split("\\R"));
            var rightLines = Arrays.asList(rightContent.split("\\R"));

            // Get the current file comparison sources
            var currentComparison = fileComparisons.get(currentFileIndex);
            var currentLeftSource = currentComparison.leftSource;
            var currentRightSource = currentComparison.rightSource;

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
            toolBar.add(Box.createHorizontalStrut(15));
            toolBar.add(fileIndicatorLabel);
        }

        undoRedoGroupStrutBefore = Box.createHorizontalStrut(20); // 20px spacing
        toolBar.add(undoRedoGroupStrutBefore);
        toolBar.addSeparator(); // Adds space between groups
        // Get reference to the separator that was just added
        undoRedoGroupSeparator = toolBar.getComponent(toolBar.getComponentCount() - 1);
        undoRedoGroupStrutAfter1 = Box.createHorizontalStrut(10); // 10px spacing
        toolBar.add(undoRedoGroupStrutAfter1);
        toolBar.add(btnUndo);
        undoRedoGroupStrutAfter2 = Box.createHorizontalStrut(10); // 10px spacing
        toolBar.add(undoRedoGroupStrutAfter2);
        toolBar.add(btnRedo);
        undoRedoGroupStrutAfter3 = Box.createHorizontalStrut(10); // spacing
        toolBar.add(undoRedoGroupStrutAfter3);
        toolBar.add(btnSaveAll);

        toolBar.add(Box.createHorizontalStrut(20));
        toolBar.addSeparator();
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(showBlankLineDiffsCheckBox);

        toolBar.add(Box.createHorizontalGlue()); // Pushes subsequent components to the right
        toolBar.add(captureDiffButton);

        return toolBar;
    }

    public void updateUndoRedoButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        var currentPanel = getCurrentContentPanel();

        btnUndo.setEnabled(currentPanel != null && currentPanel.isUndoEnabled());
        btnRedo.setEnabled(currentPanel != null && currentPanel.isRedoEnabled());

        // Hide undo/redo completely when both sides are read-only
        boolean showUndoRedo = false;
        if (currentPanel instanceof BufferDiffPanel bp) {
            showUndoRedo = bp.atLeastOneSideEditable();
        }
        btnUndo.setVisible(showUndoRedo);
        btnRedo.setVisible(showUndoRedo);

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

        // Update save button text, enable state, and visibility
        // Compute the exact number of BufferDiffPanels that would be saved by saveAll():
        // include bufferDiffPanel (if present) plus all cached panels (deduplicated),
        // and count those with hasUnsavedChanges() == true.
        int dirtyCount = 0;
        var visited = new HashSet<BufferDiffPanel>();

        if (bufferDiffPanel != null) {
            visited.add(bufferDiffPanel);
            if (bufferDiffPanel.hasUnsavedChanges()) {
                dirtyCount++;
            }
        }

        for (var p : panelCache.nonNullValues()) {
            if (visited.add(p) && p.hasUnsavedChanges()) {
                dirtyCount++;
            }
        }

        String baseSaveText = fileComparisons.size() > 1 ? "Save All" : "Save";
        btnSaveAll.setText(dirtyCount > 0 ? baseSaveText + " (" + dirtyCount + ")" : baseSaveText);
        btnSaveAll.setEnabled(dirtyCount > 0);

        // Hide save button when all sides are read-only (like PR diffs)
        btnSaveAll.setVisible(showUndoRedo);

        // Hide separator and struts for undo/redo/save group when buttons are hidden
        if (undoRedoGroupSeparator != null) {
            undoRedoGroupSeparator.setVisible(showUndoRedo);
        }
        if (undoRedoGroupStrutBefore != null) {
            undoRedoGroupStrutBefore.setVisible(showUndoRedo);
        }
        if (undoRedoGroupStrutAfter1 != null) {
            undoRedoGroupStrutAfter1.setVisible(showUndoRedo);
        }
        if (undoRedoGroupStrutAfter2 != null) {
            undoRedoGroupStrutAfter2.setVisible(showUndoRedo);
        }
        if (undoRedoGroupStrutAfter3 != null) {
            undoRedoGroupStrutAfter3.setVisible(showUndoRedo);
        }

        // Update per-file dirty indicators in the file tree (only when multiple files are shown)
        if (fileComparisons.size() > 1) {
            var dirty = new HashSet<Integer>();

            // Current (visible) file
            if (bufferDiffPanel != null && bufferDiffPanel.hasUnsavedChanges()) {
                dirty.add(currentFileIndex);
            }

            // Cached files (use keys to keep index association)
            for (var key : panelCache.getCachedKeys()) {
                var panel = panelCache.get(key);
                if (panel != null && panel.hasUnsavedChanges()) {
                    dirty.add(key);
                }
            }

            fileTreePanel.setDirtyFiles(dirty);
        }
    }

    /** Returns true if any loaded diff-panel holds modified documents. */
    public boolean hasUnsavedChanges() {
        if (bufferDiffPanel != null && bufferDiffPanel.hasUnsavedChanges()) return true;
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

            // Collect unique panels to process (current + cached)
            var visited = new LinkedHashSet<BufferDiffPanel>();
            if (bufferDiffPanel != null) {
                visited.add(bufferDiffPanel);
            }
            for (var p : panelCache.nonNullValues()) {
                visited.add(p);
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
            try (var scope = contextManager.beginTask(actionDescription, "", false)) {
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
                // Reserved by another thread, show loading and wait
                showLoadingForFile(fileIndex);
            }
            return;
        }

        showLoadingForFile(fileIndex);

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

    private void displayCachedFile(int fileIndex, BufferDiffPanel cachedPanel) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

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
        tabbedPane.addTab(cachedPanel.getTitle(), cachedPanel);
        this.bufferDiffPanel = cachedPanel;

        // Reset auto-scroll flag for file navigation to ensure fresh auto-scroll opportunity
        cachedPanel.resetAutoScrollFlag();

        // Reset selectedDelta to first difference for consistent navigation behavior
        cachedPanel.resetToFirstDifference();

        // Apply theme to ensure proper syntax highlighting
        cachedPanel.applyTheme(theme);

        // Reset dirty state after theme application to prevent false save prompts
        // Theme application can trigger document events that incorrectly mark documents as dirty
        resetDocumentDirtyStateAfterTheme(cachedPanel);

        // Re-establish component resize listeners and set flag for layout reset on next resize
        cachedPanel.refreshComponentListeners();
        needsLayoutReset = true;

        // Apply diff highlights immediately after theme to prevent timing issues
        cachedPanel.diff(true); // Pass true to trigger auto-scroll for cached panels

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

    private void updateFileIndicatorLabel(String text) {
        fileIndicatorLabel.setText(text);
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
    public void displayAndRefreshPanel(int fileIndex, BufferDiffPanel panel) {
        displayCachedFile(fileIndex, panel);
    }

    /**
     * Cache a panel for the given file index. Helper method for both sync and async panel creation. Uses putReserved if
     * the slot was reserved, otherwise regular put.
     */
    public void cachePanel(int fileIndex, BufferDiffPanel panel) {

        // Reset auto-scroll flag for newly created panels
        panel.resetAutoScrollFlag();

        // Ensure creation context is set for debugging
        if ("unknown".equals(panel.getCreationContext())) {
            panel.markCreationContext("cachePanel");
        }

        // Reset selectedDelta to first difference for consistent navigation behavior
        panel.resetToFirstDifference();

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
            logger.debug("Preloading file {} in background", fileIndex);
            var compInfo = fileComparisons.get(fileIndex);

            // Use extracted file validation logic
            if (!FileComparisonHelper.isValidForPreload(compInfo.leftSource, compInfo.rightSource)) {
                logger.warn("Skipping preload of file {} - too large for preload", fileIndex);
                return;
            }

            // Create file loading result (includes size validation and error handling)
            var loadingResult = FileComparisonHelper.createFileLoadingResult(
                    compInfo.leftSource, compInfo.rightSource, contextManager, isMultipleCommitsContext);

            // Create and cache panel on EDT
            SwingUtilities.invokeLater(() -> {
                // Double-check still needed and in window
                if (panelCache.get(fileIndex) == null && panelCache.isInWindow(fileIndex)) {
                    if (loadingResult.isSuccess()) {
                        var panel = new BufferDiffPanel(this, theme);
                        panel.markCreationContext("preload");
                        panel.setDiffNode(loadingResult.getDiffNode());

                        // Apply theme to ensure consistent state and avoid false dirty flags
                        panel.applyTheme(theme);
                        // Clear any transient dirty state caused by mirroring during preload
                        resetDocumentDirtyStateAfterTheme(panel);

                        // Cache will automatically check window constraints
                        panelCache.put(fileIndex, panel);
                    } else {
                        logger.warn("Skipping preload of file {} - {}", fileIndex, loadingResult.getErrorMessage());
                    }
                } else {
                    logger.debug("Preload cancelled for file {} (cached or outside window)", fileIndex);
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

        var usedMB = usedMemory / (1024 * 1024);
        var maxMB = maxMemory / (1024 * 1024);
        var percentUsed = (usedMemory * 100) / maxMemory;

        logger.debug("Memory: {}MB/{}MB ({}%), {}", usedMB, maxMB, percentUsed, panelCache.getWindowInfo());

        // Use configurable threshold for memory cleanup
        if (percentUsed > PerformanceConstants.MEMORY_HIGH_THRESHOLD_PERCENT) {
            logger.warn("Memory usage high ({}%) with sliding window cache", percentUsed);
            performWindowCleanup();
        }
    }

    /** Perform cleanup when memory usage is high */
    private void performWindowCleanup() {
        logger.debug("Performing sliding window memory cleanup");

        // Clear caches in all window panels
        for (var panel : panelCache.nonNullValues()) {
            panel.clearCaches(); // Clear undo history, search results, etc.
        }

        // Suggest garbage collection
        System.gc();

        logger.debug("Window cleanup complete: {}", panelCache.getWindowInfo());
    }

    /**
     * Reset layout hierarchy to fix broken container relationships after file navigation. This rebuilds the
     * BorderLayout relationships to restore proper resize behavior.
     */
    private void resetLayoutHierarchy(BufferDiffPanel currentPanel) {
        // Remove and re-add mainSplitPane to reset BorderLayout relationships
        remove(mainSplitPane);
        invalidate();
        add(mainSplitPane, BorderLayout.CENTER);
        revalidate();

        // Ensure child components are properly updated
        SwingUtilities.invokeLater(() -> {
            getTabbedPane().revalidate();
            currentPanel.revalidate();

            // Refresh scroll synchronizer to maintain diff alignment
            var synchronizer = currentPanel.getScrollSynchronizer();
            if (synchronizer != null) {
                synchronizer.invalidateViewportCacheForBothPanels();
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
        this.bufferDiffPanel = null;

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
}
