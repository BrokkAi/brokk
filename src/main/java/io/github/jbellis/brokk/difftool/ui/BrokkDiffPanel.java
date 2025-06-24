package io.github.jbellis.brokk.difftool.ui;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;

import static javax.swing.SwingUtilities.invokeLater;

import java.util.Objects;

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
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class BrokkDiffPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(BrokkDiffPanel.class);
    private static final String STATE_PROPERTY = "state";
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;
    private boolean started;
    private final JLabel loadingLabel = new JLabel("Processing... Please wait.");
    private final GuiTheme theme;
    private final JCheckBox showBlankLineDiffsCheckBox = new JCheckBox("Show blank-lines");

    // All file comparisons with lazy loading cache
    private final List<FileComparisonInfo> fileComparisons;
    private int currentFileIndex = 0;
    private final boolean isMultipleCommitsContext;

    // LRU cache for loaded diff panels - keeps max 3 panels in memory
    private static final int MAX_CACHED_PANELS = 5;
    private final Map<Integer, BufferDiffPanel> panelCache = new LinkedHashMap<>(MAX_CACHED_PANELS + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, BufferDiffPanel> eldest) {
            if (size() > MAX_CACHED_PANELS) {
                logger.debug("Evicting panel from cache: index {}", eldest.getKey());
                // Dispose UI resources of evicted panel
                eldest.getValue().removeAll();
                return true;
            }
            return false;
        }
    };

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
        launchComparison();

        add(createToolbar(), BorderLayout.NORTH);
        add(getTabbedPane(), BorderLayout.CENTER);
    }

    public JButton getBtnUndo() {
        return btnUndo;
    }

    private JButton btnUndo = new JButton("Undo"); // Initialize to prevent NullAway issues
    private JButton btnRedo = new JButton("Redo");
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
            contextManager.addVirtualFragment(fragment);
            contextManager.getIo().systemOutput("Added captured diff to context: " + description);
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

        // Check if panel is already cached
        var cachedPanel = panelCache.get(fileIndex);
        if (cachedPanel != null) {
            logger.debug("File panel found in cache: {}", compInfo.getDisplayName());
            displayCachedFile(fileIndex, cachedPanel);
            return;
        }

        showLoadingForFile(fileIndex);

        var fileComparison = new FileComparison.FileComparisonBuilder(this, theme, contextManager)
                .withSources(compInfo.leftSource, compInfo.rightSource)
                .setMultipleCommitsContext(this.isMultipleCommitsContext)
                .build();

        fileComparison.addPropertyChangeListener(evt -> handleFileComparisonResult(evt, fileIndex));
        fileComparison.execute();
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

    private void showErrorForFile(int fileIndex, String errorMessage) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        var compInfo = fileComparisons.get(fileIndex);
        logger.error("Error loading file: {} - {}", compInfo.getDisplayName(), errorMessage);

        // Show error dialog
        JOptionPane.showMessageDialog(
            this,
            "Error loading file '" + compInfo.getDisplayName() + "':\n" + errorMessage,
            "File Load Error",
            JOptionPane.ERROR_MESSAGE
        );

        // Remove loading indicator
        remove(loadingLabel);
        revalidate();
        repaint();
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

    private void handleFileComparisonResult(java.beans.PropertyChangeEvent evt, int fileIndex) {
        if (STATE_PROPERTY.equals(evt.getPropertyName()) && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
            var compInfo = fileComparisons.get(fileIndex);
            try {
                String result = (String) ((SwingWorker<?, ?>) evt.getSource()).get(); // Explicit type for clarity with cast
                if (result == null) {
                    var comp = (FileComparison) evt.getSource();
                    var loadedPanel = comp.getPanel();

                    // Cache the loaded panel
                    if (loadedPanel != null) {
                        panelCache.put(fileIndex, loadedPanel);
                        invokeLater(() -> {
                            logger.debug("File loaded successfully and cached: {}", compInfo.getDisplayName());
                            displayCachedFile(fileIndex, loadedPanel);
                        });
                    } else {
                        // This case should ideally be handled by FileComparison returning an error string.
                        // However, if getPanel() can return null without an error string, handle it.
                        var errorMsg = "Failed to load panel for " + compInfo.getDisplayName() + " (panel is null).";
                        logger.error(errorMsg);
                        invokeLater(() -> showErrorForFile(fileIndex, errorMsg));
                    }
                } else {
                    invokeLater(() -> {
                        logger.error("Failed to load file: {} - {}", compInfo.getDisplayName(), result);
                        showErrorForFile(fileIndex, result);
                    });
                }
            } catch (InterruptedException | ExecutionException e) {
                invokeLater(() -> {
                    logger.error("Exception loading file: {}", compInfo.getDisplayName(), e);
                    showErrorForFile(fileIndex, Objects.toString(e.getMessage(), "Unknown error"));
                });
            }
        }
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
                diffPanel.doSave();
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
        panelCache.values().forEach(panel -> panel.diff(true)); // Scroll to selection for user-initiated refresh
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
        for (var panel : panelCache.values()) {
            panel.applyTheme(guiTheme);
        }

        // Update all child components including toolbar buttons and labels
        SwingUtilities.updateComponentTreeUI(this);
        revalidate();
        repaint();
    }

    /**
     * Clean up resources when the panel is disposed.
     * This ensures cached panels are properly disposed of to free memory.
     */
    public void dispose() {
        logger.debug("Disposing BrokkDiffPanel and clearing panel cache");

        // Clear all cached panels and dispose their resources
        for (var panel : panelCache.values()) {
            panel.removeAll();
        }
        panelCache.clear();

        // Clear current panel reference
        this.bufferDiffPanel = null;

        // Remove all components
        removeAll();
    }
}
