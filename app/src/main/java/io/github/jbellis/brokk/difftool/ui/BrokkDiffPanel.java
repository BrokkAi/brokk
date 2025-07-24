package io.github.jbellis.brokk.difftool.ui;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;

import static javax.swing.SwingUtilities.invokeLater;

import java.util.Objects;
import io.github.jbellis.brokk.util.ThreadSafeLRUCache;

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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class BrokkDiffPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(BrokkDiffPanel.class);
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;
    private boolean started;
    private final JLabel loadingLabel = new JLabel("Processing... Please wait.");
    private final GuiTheme theme;
    private final JCheckBox showBlankLineDiffsCheckBox = new JCheckBox("Show blank-lines");

    // All file comparisons with lazy loading cache
    final List<FileComparisonInfo> fileComparisons;
    private int currentFileIndex = 0;
    private final boolean isMultipleCommitsContext;

    // Thread-safe LRU cache for loaded diff panels
    private static final int MAX_CACHED_PANELS = PerformanceConstants.MAX_CACHED_DIFF_PANELS;
    private final ThreadSafeLRUCache<Integer, BufferDiffPanel> panelCache = new ThreadSafeLRUCache<>(MAX_CACHED_PANELS);


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
        @Nullable
        private BufferSource leftSource;
        @Nullable
        private BufferSource rightSource;
        private final GuiTheme theme;
        private final ContextManager contextManager;
        private final List<FileComparisonInfo> fileComparisons;
        private boolean isMultipleCommitsContext = false;

        public Builder(GuiTheme theme, ContextManager contextManager) {
            this.theme = theme;
            this.contextManager = contextManager;
            this.fileComparisons = new ArrayList<>();
            this.leftSource = null; // Initialize @Nullable fields
            this.rightSource = null;
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

        public Builder addComparison(BufferSource leftSource, BufferSource rightSource) {
            this.fileComparisons.add(new FileComparisonInfo(leftSource, rightSource));
            return this;
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

    private JButton btnUndo = new JButton("Undo"); // Initialize to prevent NullAway issues
    private JButton btnRedo = new JButton("Redo");
    private JButton btnSaveAll = new JButton("Save");
    private JButton captureDiffButton = new JButton("Capture Diff");
    private JButton btnNext = new JButton("Next Change");
    private JButton btnPrevious = new JButton("Previous Change");
    private JButton btnPreviousFile = new JButton("Previous File");
    private JButton btnNextFile = new JButton("Next File");
    private JLabel fileIndicatorLabel = new JLabel(""); // Initialize
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
        if (canNavigateToNextFile()) {
            switchToFile(currentFileIndex + 1);
        }
    }

    public void previousFile() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        if (canNavigateToPreviousFile()) {
            switchToFile(currentFileIndex - 1);
        }
    }

    public void switchToFile(int index) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        if (index < 0 || index >= fileComparisons.size()) {
            return;
        }

        // No automatic save; user must choose “Save All”

        logger.debug("Switching to file {} of {}", index + 1, fileComparisons.size());
        currentFileIndex = index;
        loadFileOnDemand(currentFileIndex);
    }


    private void updateNavigationButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        updateUndoRedoButtons();

        btnPreviousFile.setEnabled(canNavigateToPreviousFile());
        btnNextFile.setEnabled(canNavigateToNextFile());
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

        // Add Capture Diff button to the right
        toolBar.add(Box.createHorizontalGlue()); // Pushes subsequent components to the right
        toolBar.add(captureDiffButton);


        return toolBar;
    }

    public void updateUndoRedoButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        var currentPanel = getCurrentContentPanel();

        btnUndo.setEnabled(currentPanel != null && currentPanel.isUndoEnabled());
        btnRedo.setEnabled(currentPanel != null && currentPanel.isRedoEnabled());

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
        var visited = new java.util.HashSet<BufferDiffPanel>();
        if (bufferDiffPanel != null && visited.add(bufferDiffPanel)) {
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
        logger.info("Starting lazy multi-file comparison for {} files", fileComparisons.size());

        // Show the first file immediately
        currentFileIndex = 0;
        loadFileOnDemand(currentFileIndex);
    }

    private void loadFileOnDemand(int fileIndex) {
        if (fileIndex < 0 || fileIndex >= fileComparisons.size()) {
            logger.warn("loadFileOnDemand called with invalid index: {}", fileIndex);
            return;
        }

        var compInfo = fileComparisons.get(fileIndex);
        logger.debug("Loading file on demand: {} (index {})", compInfo.getDisplayName(), fileIndex);

        // First check if panel is already cached (fast read operation)
        var cachedPanel = panelCache.get(fileIndex);
        if (cachedPanel != null) {
            logger.debug("File panel found in cache: {}", compInfo.getDisplayName());
            displayCachedFile(fileIndex, cachedPanel);
            return;
        }

        // Atomic check-and-reserve to prevent concurrent loading
        if (!panelCache.tryReserve(fileIndex)) {
            // Another thread is already loading this file or it was cached between checks
            var nowCachedPanel = panelCache.get(fileIndex);
            if (nowCachedPanel != null) {
                logger.debug("File panel loaded by another thread: {}", compInfo.getDisplayName());
                displayCachedFile(fileIndex, nowCachedPanel);
            } else {
                // Reserved by another thread, show loading and wait
                logger.debug("File panel loading in progress by another thread: {}", compInfo.getDisplayName());
                showLoadingForFile(fileIndex);
            }
            return;
        }

        showLoadingForFile(fileIndex);

        // Use hybrid approach - sync for small files, async for large files
        HybridFileComparison.createDiffPanel(compInfo.leftSource, compInfo.rightSource,
                                           this, theme, contextManager, this.isMultipleCommitsContext, fileIndex);
    }

    private void showLoadingForFile(int fileIndex) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        var compInfo = fileComparisons.get(fileIndex);
        logger.trace("Showing loading indicator for file: {}", compInfo.getDisplayName());

        // Clear existing tabs and show loading label
        tabbedPane.removeAll();
        add(loadingLabel, BorderLayout.CENTER);

        updateFileIndicatorLabel("Loading: " + compInfo.getDisplayName());

        revalidate();
        repaint();
    }

    private void displayCachedFile(int fileIndex, BufferDiffPanel cachedPanel) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        var compInfo = fileComparisons.get(fileIndex);
        logger.trace("Displaying cached file: {}", compInfo.getDisplayName());

        // Remove loading label if present
        remove(loadingLabel);

        // Clear tabs and add the cached panel
        tabbedPane.removeAll();
        tabbedPane.addTab(cachedPanel.getTitle(), cachedPanel);
        this.bufferDiffPanel = cachedPanel;

        // Update file indicator
        updateFileIndicatorLabel(compInfo.getDisplayName());

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
     * Shows the diff panel in a frame.
     *
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
                // Ask about unsaved changes
                if (hasUnsavedChanges()) {
                    var opt = contextManager.getIo().showConfirmDialog(
                            "There are unsaved changes. Save before closing?",
                            "Unsaved Changes",
                            JOptionPane.YES_NO_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) {
                        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                        return;
                    }
                    if (opt == JOptionPane.YES_OPTION) {
                        saveAll();
                    }
                }
                contextManager.getProject().saveDiffWindowBounds(frame);
            }
        });

        frame.setVisible(true);
    }

    private void navigateToNextChange() {
        var panel = getCurrentContentPanel();
        if (panel == null) return;

        if (panel.isAtLastLogicalChange() && canNavigateToNextFile()) {
            nextFile();
        } else {
            panel.doDown();
        }
        refreshAfterNavigation();
    }

    private void navigateToPreviousChange() {
        var panel = getCurrentContentPanel();
        if (panel == null) return;

        if (panel.isAtFirstLogicalChange() && canNavigateToPreviousFile()) {
            previousFile();
            var newPanel = getCurrentContentPanel();
            if (newPanel != null) {
                newPanel.goToLastLogicalChange();
            }
        } else {
            panel.doUp();
        }
        refreshAfterNavigation();
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
            action.accept(panel);
            repaint();
            var diffPanel = getBufferDiffPanel();
            if (diffPanel != null) {
                refreshTabTitle(diffPanel);
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
        var window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispose();
        }
    }

    /**
     * Cache a panel for the given file index.
     * Helper method for both sync and async panel creation.
     * Uses putReserved if the slot was reserved, otherwise regular put.
     */
    public void cachePanel(int fileIndex, BufferDiffPanel panel) {
        var cachedPanel = panelCache.get(fileIndex);
        if (cachedPanel == null) {
            // This was a reserved slot, replace with actual panel
            panelCache.putReserved(fileIndex, panel);
        } else {
            // Direct cache (shouldn't happen in normal flow but handle gracefully)
            panelCache.put(fileIndex, panel);
        }
    }

    /**
     * Clean up resources when the panel is disposed.
     * This ensures cached panels are properly disposed of to free memory.
     */
    public void dispose() {
        logger.debug("Disposing BrokkDiffPanel and clearing panel cache");

        // Caller is responsible for saving before disposal

        // Clear all cached panels and dispose their resources (thread-safe)
        panelCache.clear();

        // Clear current panel reference
        this.bufferDiffPanel = null;

        // Remove all components
        removeAll();
    }
}
