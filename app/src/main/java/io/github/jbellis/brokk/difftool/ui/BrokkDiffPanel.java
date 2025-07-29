package io.github.jbellis.brokk.difftool.ui;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;

import io.github.jbellis.brokk.util.SlidingWindowCache;

import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import io.github.jbellis.brokk.difftool.scroll.ScrollSynchronizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BrokkDiffPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(BrokkDiffPanel.class);
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;
    private boolean started;
    private final JLabel loadingLabel = createLoadingLabel();
    private final GuiTheme theme;
    private final JCheckBox showBlankLineDiffsCheckBox = new JCheckBox("Show blank-lines");

    // All file comparisons with lazy loading cache
    final List<FileComparisonInfo> fileComparisons;
    private int currentFileIndex = 0;
    private final boolean isMultipleCommitsContext;

    // Thread-safe sliding window cache for loaded diff panels
    private static final int WINDOW_SIZE = PerformanceConstants.DEFAULT_SLIDING_WINDOW;
    private static final int MAX_CACHED_PANELS = PerformanceConstants.MAX_CACHED_DIFF_PANELS;
    private final SlidingWindowCache<Integer, BufferDiffPanel> panelCache =
        new SlidingWindowCache<>(MAX_CACHED_PANELS, WINDOW_SIZE);

    /**
     * Inner class to hold a single file comparison metadata
     * Note: No longer holds the diffPanel directly - that's managed by the cache
     */
    static class FileComparisonInfo {
        final BufferSource leftSource;
        final BufferSource rightSource;
        @Nullable BufferDiffPanel diffPanel;

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

        // Initialize file comparisons list - all modes use the same approach
        this.fileComparisons = new ArrayList<>(builder.fileComparisons);
        assert !this.fileComparisons.isEmpty() : "File comparisons cannot be empty";
        this.bufferDiffPanel = null; // Initialize @Nullable field

        // Make the container focusable, so it can handle key events
        setFocusable(true);
        tabbedPane = new JTabbedPane();
        // Add an AncestorListener to trigger 'start()' when the panel is added to a container
        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                start();
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
            }
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
        launchComparison();

        add(createToolbar(), BorderLayout.NORTH);
        add(getTabbedPane(), BorderLayout.CENTER);
    }

    public JButton getBtnUndo() {
        return btnUndo;
    }

    private final JButton btnUndo = new JButton("Undo"); // Initialize to prevent NullAway issues
    private final JButton btnRedo = new JButton("Redo");
    private final JButton btnSaveAll = new JButton("Save");
    private final JButton captureDiffButton = new JButton("Capture Diff");
    private final JButton btnNext = new JButton("Next Change");
    private final JButton btnPrevious = new JButton("Previous Change");
    private final JButton btnPreviousFile = new JButton("Previous File");
    private final JButton btnNextFile = new JButton("Next File");
    private final JLabel scrollModeIndicatorLabel = new JLabel("Immediate"); // Initialize with default mode
    private final JLabel fileIndicatorLabel = new JLabel(""); // Initialize
    @Nullable private BufferDiffPanel bufferDiffPanel;

    public void setBufferDiffPanel(@Nullable BufferDiffPanel bufferDiffPanel) {
        this.bufferDiffPanel = bufferDiffPanel;
        updateScrollModeIndicator();
    }

    @Nullable
    private BufferDiffPanel getBufferDiffPanel() {
        return bufferDiffPanel;
    }

    public void nextFile() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // Disable all control buttons FIRST, before any logic
        disableAllControlButtons();

        logger.debug("Navigation Step 1: User clicked next file (current: {}, total: {})",
                    currentFileIndex, fileComparisons.size());

        if (canNavigateToNextFile()) {
            try {
                switchToFile(currentFileIndex + 1);
            } catch (Exception e) {
                logger.error("Error navigating to next file", e);
                // Re-enable buttons on exception
                updateNavigationButtons();
            }
        } else {
            logger.debug("Navigation blocked: cannot navigate to next file");
            // Re-enable buttons if navigation was blocked
            updateNavigationButtons();
        }
    }

    public void previousFile() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // Disable all control buttons FIRST, before any logic
        disableAllControlButtons();

        logger.debug("Navigation Step 1: User clicked previous file (current: {}, total: {})",
                    currentFileIndex, fileComparisons.size());

        if (canNavigateToPreviousFile()) {
            try {
                switchToFile(currentFileIndex - 1);
            } catch (Exception e) {
                logger.error("Error navigating to previous file", e);
                // Re-enable buttons on exception
                updateNavigationButtons();
            }
        } else {
            logger.debug("Navigation blocked: cannot navigate to previous file");
            // Re-enable buttons if navigation was blocked
            updateNavigationButtons();
        }
    }

    public void switchToFile(int index) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        if (index < 0 || index >= fileComparisons.size()) {
            logger.warn("Navigation Step 2: Invalid file index {} (valid range: 0-{})",
                       index, fileComparisons.size() - 1);
            return;
        }

        logger.debug("Navigation Step 2: Starting switchToFile from {} to {} with sliding window",
                    currentFileIndex, index);

        // Update sliding window in cache - this automatically evicts files outside window
        panelCache.updateWindowCenter(index, fileComparisons.size());

        currentFileIndex = index;

        // Load current file if not in cache
        loadFileOnDemand(currentFileIndex);

        // Predictively load adjacent files in background
        preloadAdjacentFiles(currentFileIndex);

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
     * Creates a styled loading label for the "Processing... Please wait." message.
     * The label is centered and uses a larger font for better visibility.
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
     * Creates a styled error label similar to the loading label but for error messages.
     * Uses theme-aware colors instead of hardcoded red.
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
     * Disables all control buttons during file loading to prevent navigation issues.
     * Called from showLoadingForFile() to ensure clean loading states.
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

        // Other control buttons
        captureDiffButton.setEnabled(false);

        logger.debug("All control buttons disabled during file loading");
    }


    private JToolBar createToolbar() {
        // Create toolbar
        var toolBar = new JToolBar();

        // Buttons are already initialized as fields
        fileIndicatorLabel.setFont(fileIndicatorLabel.getFont().deriveFont(Font.BOLD));

        // Style the scroll mode indicator
        scrollModeIndicatorLabel.setFont(scrollModeIndicatorLabel.getFont().deriveFont(Font.ITALIC));
        scrollModeIndicatorLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        scrollModeIndicatorLabel.setToolTipText("Current scroll throttling mode");

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

            var patch = DiffUtils.diff(leftLines, rightLines, (DiffAlgorithmListener) null);
            var unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(currentLeftSource.title(),
                                                                   currentRightSource.title(),
                                                                   leftLines,
                                                                   patch,
                                                                   0);
            var diffText = String.join("\n", unifiedDiff);

            var description = "Captured Diff: %s vs %s".formatted(currentLeftSource.title(), currentRightSource.title());

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
            contextManager.submitContextTask("Adding diff to context", () -> {
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

        toolBar.add(Box.createHorizontalStrut(20)); // 20px spacing
        toolBar.addSeparator(); // Adds space between groups
        toolBar.add(Box.createHorizontalStrut(10)); // 10px spacing
        toolBar.add(btnUndo);
        toolBar.add(Box.createHorizontalStrut(10)); // 10px spacing
        toolBar.add(btnRedo);
        toolBar.add(Box.createHorizontalStrut(10)); // spacing
        toolBar.add(btnSaveAll);

        toolBar.add(Box.createHorizontalStrut(20));
        toolBar.addSeparator();
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(showBlankLineDiffsCheckBox);

        toolBar.add(Box.createHorizontalStrut(5)); // Small spacing
        toolBar.add(scrollModeIndicatorLabel);
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
            var isLastChangeOverall = currentFileIndex == fileComparisons.size() - 1 && currentPanel.isAtLastLogicalChange();
            btnPrevious.setEnabled(!isFirstChangeOverall);
            btnNext.setEnabled(!isLastChangeOverall);
        } else {
            btnPrevious.setEnabled(false);
            btnNext.setEnabled(false);
        }

        // Update save button text and enable state
        boolean hasUnsaved = hasUnsavedChanges();
        btnSaveAll.setText(fileComparisons.size() > 1 ? "Save All" : "Save");
        btnSaveAll.setEnabled(hasUnsaved);
    }

    /**
     * Returns true if any loaded diff-panel holds modified documents.
     */
    public boolean hasUnsavedChanges() {
        if (bufferDiffPanel != null && bufferDiffPanel.isDirty()) return true;
        for (var p : panelCache.nonNullValues()) {
            if (p.isDirty()) return true;
        }
        return false;
    }

    /**
     * Saves every dirty document across all BufferDiffPanels.
     */
    public void saveAll() {
        try {
            // Disable save button temporarily
            btnSaveAll.setEnabled(false);

            var visited = new java.util.HashSet<BufferDiffPanel>();
            if (bufferDiffPanel != null) {
                visited.add(bufferDiffPanel);
                bufferDiffPanel.doSave();
                refreshTabTitle(bufferDiffPanel);
            }
            // save each panel
            for (var p : panelCache.nonNullValues()) {
                if (visited.add(p)) {
                    p.doSave();
                    refreshTabTitle(p);
                }
            }
            repaint();

            // Re-enable buttons after save operation
            SwingUtilities.invokeLater(this::updateNavigationButtons);
        } catch (Exception e) {
            logger.error("Error saving files", e);
            updateNavigationButtons();
        }
    }

    /**
     * Refresh tab title (adds/removes “*”).
     */
    public void refreshTabTitle(BufferDiffPanel panel) {
        var idx = tabbedPane.indexOfComponent(panel);
        if (idx != -1) {
            tabbedPane.setTitleAt(idx, panel.getTitle());
        }
    }

    /**
     * Provides access to Chrome methods for BufferDiffPanel.
     */
    public IConsoleIO getConsoleIO() {
        return contextManager.getIo();
    }

    public void launchComparison() {
        logger.info("Navigation Step 0: Launching diff comparison with {} files, starting with file index 0",
                   fileComparisons.size());
        // Show the first file immediately
        currentFileIndex = 0;
        loadFileOnDemand(currentFileIndex);
    }

    private void loadFileOnDemand(int fileIndex) {
        if (fileIndex < 0 || fileIndex >= fileComparisons.size()) {
            logger.warn("loadFileOnDemand called with invalid index: {}", fileIndex);
            return;
        }

        logger.debug("Navigation Step 3: Starting loadFileOnDemand for file index {}", fileIndex);

        var compInfo = fileComparisons.get(fileIndex);

        // First check if panel is already cached (fast read operation)
        var cachedPanel = panelCache.get(fileIndex);
        if (cachedPanel != null) {
            logger.debug("Navigation Step 3: File {} found in cache, displaying immediately", fileIndex);
            displayCachedFile(fileIndex, cachedPanel);
            return;
        }

        // Atomic check-and-reserve to prevent concurrent loading
        if (!panelCache.tryReserve(fileIndex)) {
            // Another thread is already loading this file or it was cached between checks
            var nowCachedPanel = panelCache.get(fileIndex);
            if (nowCachedPanel != null) {
                logger.debug("Navigation Step 3: File {} was cached during reservation check", fileIndex);
                displayCachedFile(fileIndex, nowCachedPanel);
            } else {
                // Reserved by another thread, show loading and wait
                logger.debug("Navigation Step 3: File {} is being loaded by another thread, showing loading state", fileIndex);
                showLoadingForFile(fileIndex);
            }
            return;
        }

        logger.debug("Navigation Step 3: File {} not cached, reserved for loading", fileIndex);
        showLoadingForFile(fileIndex);

        // Use hybrid approach - sync for small files, async for large files
        HybridFileComparison.createDiffPanel(compInfo.leftSource, compInfo.rightSource,
                                           this, theme, contextManager, this.isMultipleCommitsContext, fileIndex);
    }

    private void showLoadingForFile(int fileIndex) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        logger.debug("Navigation Step 4: Showing loading UI for file {} - disabling all control buttons", fileIndex);

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

        logger.debug("Navigation Step 4: Loading UI displayed, all buttons disabled");
    }

    private void displayCachedFile(int fileIndex, BufferDiffPanel cachedPanel) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        logger.debug("Navigation Step 5: Displaying cached file {} and re-enabling control buttons", fileIndex);

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

        // Update scroll mode indicator for this file
        updateScrollModeIndicator();

        // Apply theme to ensure proper syntax highlighting
        cachedPanel.applyTheme(theme);

        // Ensure diff highlights are properly displayed after theme application
        SwingUtilities.invokeLater(() -> {
            cachedPanel.diff(true); // Pass true to trigger auto-scroll for cached panels
            // Update scroll mode indicator after diff is complete and panel is fully initialized
            updateScrollModeIndicator();
        });

        // Update file indicator
        updateFileIndicatorLabel(compInfo.getDisplayName());

        // Re-enable control buttons after loading is complete
        updateNavigationButtons();

        refreshUI();

        logger.debug("Navigation Step 5: File {} successfully displayed, navigation complete", fileIndex);
    }


    /**
     * Display an error message for a file that cannot be loaded.
     * Clears the loading state and shows the error message.
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
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(this);

        // Get saved bounds from Project via the stored ContextManager
        var bounds = contextManager.getProject().getDiffWindowBounds();
        frame.setBounds(bounds);

        // Save window position and size when closing
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (!checkUnsavedChangesBeforeClose()) {
                    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                    return;
                }
                contextManager.getProject().saveDiffWindowBounds(frame);
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

    /**
     * Update the scroll mode indicator based on the current throttling strategy.
     */
    private void updateScrollModeIndicator() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        if (bufferDiffPanel == null) {
            scrollModeIndicatorLabel.setText("N/A");
            scrollModeIndicatorLabel.setToolTipText("No file loaded");
            return;
        }

        var synchronizer = bufferDiffPanel.getScrollSynchronizer();
        if (synchronizer == null) {
            // The BufferDiffPanel might not be fully initialized yet, retry later
            SwingUtilities.invokeLater(() -> {
                if (bufferDiffPanel != null) {
                    var retrySync = bufferDiffPanel.getScrollSynchronizer();
                    if (retrySync == null) {
                        scrollModeIndicatorLabel.setText("N/A");
                        scrollModeIndicatorLabel.setToolTipText("No scroll synchronizer available");
                    } else {
                        updateScrollModeIndicatorWithSync(retrySync);
                    }
                } else {
                    scrollModeIndicatorLabel.setText("N/A");
                    scrollModeIndicatorLabel.setToolTipText("No file loaded");
                }
            });
            return;
        }

        updateScrollModeIndicatorWithSync(synchronizer);
    }

    /**
     * Update the scroll mode indicator with a valid synchronizer.
     */
    private void updateScrollModeIndicatorWithSync(ScrollSynchronizer synchronizer) {
        // Check which throttling mode is currently active
        String modeText;
        String tooltip;

        if (PerformanceConstants.ENABLE_ADAPTIVE_THROTTLING) {
            var metrics = synchronizer.getAdaptiveMetrics();
            var currentMode = metrics.currentMode();
            modeText = currentMode.name().charAt(0) + currentMode.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            tooltip = String.format("Adaptive mode: %s | %s",
                                   currentMode.getDescription(),
                                   metrics.getSummary());
        } else if (PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING) {
            modeText = "Frame-based";
            var throttlingMetrics = synchronizer.getThrottlingMetrics();
            tooltip = String.format("Frame-based throttling | Efficiency: %.1f%% | %d events, %d executions",
                                   throttlingMetrics.efficiency() * 100,
                                   throttlingMetrics.totalEvents(),
                                   throttlingMetrics.totalExecutions());
        } else {
            modeText = "Immediate";
            tooltip = "Immediate execution - No throttling";
        }

        scrollModeIndicatorLabel.setText(modeText);
        scrollModeIndicatorLabel.setToolTipText(tooltip);
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
     * @return true if it's OK to close, false if user cancelled
     */
    private boolean checkUnsavedChangesBeforeClose() {
        if (hasUnsavedChanges()) {
            var opt = contextManager.getIo().showConfirmDialog(
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
        }
        return true; // OK to close
    }

    /**
     * Displays a cached panel and updates navigation buttons.
     * This is the proper way to display panels created by HybridFileComparison.
     */
    public void displayAndRefreshPanel(int fileIndex, BufferDiffPanel panel) {
        displayCachedFile(fileIndex, panel);
    }

    /**
     * Cache a panel for the given file index.
     * Helper method for both sync and async panel creation.
     * Uses putReserved if the slot was reserved, otherwise regular put.
     */
    public void cachePanel(int fileIndex, BufferDiffPanel panel) {
        logger.debug("Navigation Step 4.5: File {} loading completed, caching panel", fileIndex);

        // Only cache if within current window
        if (panelCache.isInWindow(fileIndex)) {
            var cachedPanel = panelCache.get(fileIndex);
            if (cachedPanel == null) {
                // This was a reserved slot, replace with actual panel
                panelCache.putReserved(fileIndex, panel);
                logger.debug("Navigation Step 4.5: Panel {} cached in sliding window (reserved slot)", fileIndex);
            } else {
                // Direct cache (shouldn't happen in normal flow but handle gracefully)
                panelCache.put(fileIndex, panel);
                logger.warn("Navigation Step 4.5: Panel {} cached directly (unexpected path)", fileIndex);
            }
        } else {
            logger.debug("Navigation Step 4.5: Panel {} outside window, not caching but will display", fileIndex);
            // Still display but don't cache
        }
    }

    /**
     * Preload adjacent files in the background for smooth navigation
     */
    private void preloadAdjacentFiles(int currentIndex) {
        contextManager.submitBackgroundTask("Preload adjacent files", () -> {
            // Preload previous file if not cached and in window
            int prevIndex = currentIndex - 1;
            if (prevIndex >= 0 && panelCache.get(prevIndex) == null && panelCache.isInWindow(prevIndex)) {
                preloadFile(prevIndex);
            }

            // Preload next file if not cached and in window
            int nextIndex = currentIndex + 1;
            if (nextIndex < fileComparisons.size() && panelCache.get(nextIndex) == null && panelCache.isInWindow(nextIndex)) {
                preloadFile(nextIndex);
            }
        });
    }

    /**
     * Preload a single file in the background
     */
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
                compInfo.leftSource, compInfo.rightSource,
                contextManager, isMultipleCommitsContext);

            // Create and cache panel on EDT
            SwingUtilities.invokeLater(() -> {
                // Double-check still needed and in window
                if (panelCache.get(fileIndex) == null && panelCache.isInWindow(fileIndex)) {
                    if (loadingResult.isSuccess()) {
                        var panel = new BufferDiffPanel(this, theme);
                        panel.setDiffNode(loadingResult.getDiffNode());

                        // Cache will automatically check window constraints
                        panelCache.put(fileIndex, panel);
                        logger.debug("Preloaded and cached file {}", fileIndex);
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

    /**
     * Log current memory usage and window status
     */
    private void logMemoryUsage() {
        var runtime = Runtime.getRuntime();
        var totalMemory = runtime.totalMemory();
        var freeMemory = runtime.freeMemory();
        var usedMemory = totalMemory - freeMemory;
        var maxMemory = runtime.maxMemory();

        var usedMB = usedMemory / (1024 * 1024);
        var maxMB = maxMemory / (1024 * 1024);
        var percentUsed = (usedMemory * 100) / maxMemory;

        logger.debug("Memory: {}MB/{}MB ({}%), {}",
                    usedMB, maxMB, percentUsed, panelCache.getWindowInfo());

        // Use configurable threshold for memory cleanup
        if (percentUsed > PerformanceConstants.MEMORY_HIGH_THRESHOLD_PERCENT) {
            logger.warn("Memory usage high ({}%) with sliding window cache", percentUsed);
            performWindowCleanup();
        }
    }

    /**
     * Perform cleanup when memory usage is high
     */
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
     * Clean up resources when the panel is disposed.
     * This ensures cached panels are properly disposed of to free memory.
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
}
