package io.github.jbellis.brokk.difftool.ui;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;

import static javax.swing.SwingUtilities.invokeLater;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.patch.Patch;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class BrokkDiffPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(BrokkDiffPanel.class);
    private static final String STATE_PROPERTY = "state";
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;
    private boolean started;
    private final JLabel loadingLabel = new JLabel("Processing... Please wait.");
    private final GuiTheme theme;

    // All file comparisons with lazy loading cache
    private final List<FileComparisonInfo> fileComparisons;
    private int currentFileIndex = 0;
    private JLabel fileIndicatorLabel;

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
        BufferDiffPanel diffPanel;
        
        FileComparisonInfo(BufferSource leftSource, BufferSource rightSource) {
            this.leftSource = leftSource;
            this.rightSource = rightSource;
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
        assert builder.contextManager != null;
        this.contextManager = builder.contextManager;

        // Initialize file comparisons list - all modes use the same approach
        this.fileComparisons = new ArrayList<>(builder.fileComparisons);
        assert !this.fileComparisons.isEmpty() : "File comparisons cannot be empty";

        // Make the container focusable, so it can handle key events
        setFocusable(true);
        tabbedPane = new JTabbedPane();
        // Add an AncestorListener to trigger 'start()' when the panel is added to a container
        addAncestorListener(new AncestorListener() {
            public void ancestorAdded(AncestorEvent event) {
                start();
            }

            public void ancestorMoved(AncestorEvent event) {
            }

            public void ancestorRemoved(AncestorEvent event) {
            }
        });

        revalidate();
    }

    // Builder Class
    public static class Builder {
        private BufferSource leftSource;
        private BufferSource rightSource;
        private final GuiTheme theme;
        private final ContextManager contextManager;
        private final List<FileComparisonInfo> fileComparisons;

        public Builder(GuiTheme theme, ContextManager contextManager) {
            this.theme = theme;
            assert contextManager != null;
            this.contextManager = contextManager;
            this.fileComparisons = new ArrayList<>();
        }

        public Builder leftSource(BufferSource source) {
            this.leftSource = source;
            return this;
        }

        public Builder rightSource(BufferSource source) {
            this.rightSource = source;
            // Automatically add the comparison when both sources are set
            if (leftSource != null && rightSource != null) {
                addComparison(leftSource, rightSource);
                leftSource = null; // Clear to prevent duplicate additions
                rightSource = null;
            }
            return this;
        }
        
        public Builder addComparison(BufferSource leftSource, BufferSource rightSource) {
            assert leftSource != null && rightSource != null : "Both left and right sources must be provided for comparison.";
            this.fileComparisons.add(new FileComparisonInfo(leftSource, rightSource));
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

    private JButton btnUndo;
    private JButton btnRedo;
    private JButton captureDiffButton;
    private JButton btnNext;
    private JButton btnPrevious;
    private JButton btnPreviousFile;
    private JButton btnNextFile;
    private BufferDiffPanel bufferDiffPanel;

    public void setBufferDiffPanel(BufferDiffPanel bufferDiffPanel) {
        this.bufferDiffPanel = bufferDiffPanel;
    }

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
        
        if (btnPreviousFile != null) {
            btnPreviousFile.setEnabled(canNavigateToPreviousFile());
        }
        if (btnNextFile != null) {
            btnNextFile.setEnabled(canNavigateToNextFile());
        }
    }
    

    private JToolBar createToolbar() {
        // Create toolbar
        JToolBar toolBar = new JToolBar();

        // Create buttons
        btnNext = new JButton("Next Change");
        btnPrevious = new JButton("Previous Change");
        btnUndo = new JButton("Undo");
        btnRedo = new JButton("Redo");
        captureDiffButton = new JButton("Capture Diff");
        
        // Multi-file navigation buttons
        btnPreviousFile = new JButton("Previous File");
        btnNextFile = new JButton("Next File");
        fileIndicatorLabel = new JLabel("");
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
                contextManager.getIo().toolError("Diff panel not available for capturing diff.");
                return;
            }
            var leftPanel = bufferPanel.getFilePanel(BufferDiffPanel.LEFT);
            var rightPanel = bufferPanel.getFilePanel(BufferDiffPanel.RIGHT);
            if (leftPanel == null || rightPanel == null) {
                contextManager.getIo().toolError("File panels not available for capturing diff.");
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

            String syntaxStyle = SyntaxConstants.SYNTAX_STYLE_NONE;
            if (detectedFilename != null) {
                int dotIndex = detectedFilename.lastIndexOf('.');
                if (dotIndex > 0 && dotIndex < detectedFilename.length() - 1) {
                    String extension = detectedFilename.substring(dotIndex + 1);
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


    public AbstractContentPanel getCurrentContentPanel() {
        Component selectedComponent = getTabbedPane().getSelectedComponent();
        if (selectedComponent instanceof AbstractContentPanel) {
            return (AbstractContentPanel) selectedComponent;
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
        JFrame frame = Chrome.newFrame(title);
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
                var result = (String) ((SwingWorker<?, ?>) evt.getSource()).get();
                if (result == null) {
                    var comp = (FileComparison) evt.getSource();
                    var loadedPanel = comp.getPanel();

                    // Cache the loaded panel
                    panelCache.put(fileIndex, loadedPanel);

                    invokeLater(() -> {
                        logger.debug("File loaded successfully and cached: {}", compInfo.getDisplayName());
                        displayCachedFile(fileIndex, loadedPanel);
                    });
                } else {
                    invokeLater(() -> {
                        logger.error("Failed to load file: {} - {}", compInfo.getDisplayName(), result);
                        showErrorForFile(fileIndex, result);
                    });
                }
            } catch (InterruptedException | ExecutionException e) {
                invokeLater(() -> {
                    logger.error("Exception loading file: {}", compInfo.getDisplayName(), e);
                    showErrorForFile(fileIndex, e.getMessage());
                });
            }
        }
    }
    
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
        if (fileIndicatorLabel != null) {
            fileIndicatorLabel.setText(text);
        }
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
