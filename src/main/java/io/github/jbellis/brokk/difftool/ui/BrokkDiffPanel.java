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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

public class BrokkDiffPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(BrokkDiffPanel.class);
    private static final String STATE_PROPERTY = "state";
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;
    private boolean started;
    private final JLabel loadingLabel = new JLabel("Processing... Please wait.");
    private final GuiTheme theme;
    
    // All file comparisons (single file is just a list of size 1)
    private final List<FileComparisonInfo> fileComparisons;
    private int currentFileIndex = 0;
    private JLabel fileIndicatorLabel;
    
    /**
     * Inner class to hold a single file comparison
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

        /**
         * Add a single file comparison (backward compatibility - part 1)
         */
        public Builder leftSource(BufferSource source) {
            this.leftSource = source;
            return this;
        }

        /**
         * Complete a single file comparison (backward compatibility - part 2)
         */
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
        
        /**
         * Add a file comparison (preferred method)
         */
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
    
    // Multi-file navigation methods
    public void nextFile() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        if (fileComparisons.size() <= 1 || currentFileIndex >= fileComparisons.size() - 1) {
            return;
        }
        currentFileIndex++;
        switchToFile(currentFileIndex);
    }
    
    public void previousFile() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        if (fileComparisons.size() <= 1 || currentFileIndex <= 0) {
            return;
        }
        currentFileIndex--;
        switchToFile(currentFileIndex);
    }
    
    public void switchToFile(int index) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        if (index < 0 || index >= fileComparisons.size()) {
            return;
        }
        logger.debug("Switching to file {} of {}", index + 1, fileComparisons.size());
        currentFileIndex = index;
        updateFileDisplay();
    }
    
    private void updateFileDisplay() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        
        // Load the file on demand when switching to it
        loadFileOnDemand(currentFileIndex);
    }
    
    private void updateNavigationButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        updateUndoRedoButtons();
        updateFileNavigationButtons();
    }
    
    private void updateFileNavigationButtons() {
        if (btnPreviousFile != null) {
            btnPreviousFile.setEnabled(currentFileIndex > 0);
        }
        if (btnNextFile != null) {
            btnNextFile.setEnabled(currentFileIndex < fileComparisons.size() - 1);
        }
    }
    
    public int getCurrentFileIndex() {
        return currentFileIndex;
    }
    
    public int getTotalFiles() {
        return fileComparisons.size();
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
        btnUndo.addActionListener(e -> {
            AbstractContentPanel panel = getCurrentContentPanel();
            if (panel != null) {
                panel.doUndo();
                repaint();
                BufferDiffPanel diffPanel = getBufferDiffPanel();
                if (diffPanel != null) {
                    diffPanel.doSave();
                }
            }
        });
        btnRedo.addActionListener(e -> {
            AbstractContentPanel panel = getCurrentContentPanel();
            if (panel != null) {
                panel.doRedo();
                repaint();
                BufferDiffPanel diffPanel = getBufferDiffPanel();
                if (diffPanel != null) {
                    diffPanel.doSave();
                }
            }
        });
        
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
            String leftContent = leftPanel.getEditor().getText();
            String rightContent = rightPanel.getEditor().getText();
            List<String> leftLines = Arrays.asList(leftContent.split("\\R"));
            List<String> rightLines = Arrays.asList(rightContent.split("\\R"));

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

            String detectedFilename = null;
            if (currentLeftSource instanceof BufferSource.StringSource s && s.filename() != null) {
                detectedFilename = s.filename();
            } else if (currentLeftSource instanceof BufferSource.FileSource f) {
                detectedFilename = f.file().getName();
            }

            if (detectedFilename == null) { // Try right source if left didn't provide a filename
                if (currentRightSource instanceof BufferSource.StringSource s && s.filename() != null) {
                    detectedFilename = s.filename();
                } else if (currentRightSource instanceof BufferSource.FileSource f) {
                    detectedFilename = f.file().getName();
                }
            }

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
        if (currentPanel != null) {
            btnUndo.setEnabled(currentPanel.isUndoEnabled());
            btnRedo.setEnabled(currentPanel.isRedoEnabled());

            boolean isFirstChangeOverall = currentFileIndex == 0 && currentPanel.isAtFirstLogicalChange();
            btnPrevious.setEnabled(!isFirstChangeOverall);

            boolean isLastChangeOverall = currentFileIndex == fileComparisons.size() - 1 && currentPanel.isAtLastLogicalChange();
            btnNext.setEnabled(!isLastChangeOverall);
        } else {
            // Disable all if no panel
            btnUndo.setEnabled(false);
            btnRedo.setEnabled(false);
            btnPrevious.setEnabled(false);
            btnNext.setEnabled(false);
        }
    }

    public void launchComparison() {
        logger.info("Starting lazy multi-file comparison for {} files", fileComparisons.size());
        
        // Log all file comparisons
        IntStream.range(0, fileComparisons.size()).forEach(idx -> {
            var comp = fileComparisons.get(idx);
            logger.trace("File {}: {} (comparing '{}' vs '{}')",
                         idx + 1,
                         comp.getDisplayName(),
                         comp.leftSource.title(),
                         comp.rightSource.title());
        });
        
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
        
        // Check if this file is already loaded
        if (compInfo.diffPanel != null) {
            logger.debug("File already loaded: {}", compInfo.getDisplayName());
            displayExistingFile(fileIndex);
            return;
        }
        
        // Show loading indicator for this file
        showLoadingForFile(fileIndex);
        
        // Create and execute the file comparison
        var fileComparison = new FileComparison.FileComparisonBuilder(this, theme, contextManager)
                .withSources(compInfo.leftSource, compInfo.rightSource)
                .build();
        
        fileComparison.addPropertyChangeListener(evt -> {
            if (STATE_PROPERTY.equals(evt.getPropertyName()) &&
                    SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                try {
                    var result = (String) ((SwingWorker<?, ?>) evt.getSource()).get();
                    if (result == null) {
                        // Success - store the BufferDiffPanel
                        var comp = (FileComparison) evt.getSource();
                        compInfo.diffPanel = comp.getPanel();
                        
                        SwingUtilities.invokeLater(() -> {
                            logger.debug("File loaded successfully: {}", compInfo.getDisplayName());
                            displayExistingFile(fileIndex);
                        });
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            logger.error("Failed to load file: {} - {}", compInfo.getDisplayName(), result);
                            showErrorForFile(fileIndex, result);
                        });
                    }
                } catch (InterruptedException | ExecutionException e) {
                    SwingUtilities.invokeLater(() -> {
                        logger.error("Exception loading file: {}", compInfo.getDisplayName(), e);
                        showErrorForFile(fileIndex, e.getMessage());
                    });
                }
            }
        });
        
        fileComparison.execute();
    }
    
    private void showLoadingForFile(int fileIndex) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        
        var compInfo = fileComparisons.get(fileIndex);
        logger.trace("Showing loading indicator for file: {}", compInfo.getDisplayName());
        
        // Clear existing tabs and show loading label
        tabbedPane.removeAll();
        add(loadingLabel, BorderLayout.CENTER);
        
        // Update file indicator
        if (fileIndicatorLabel != null) {
            fileIndicatorLabel.setText("Loading: " + compInfo.getDisplayName());
        }
        
        revalidate();
        repaint();
    }
    
    private void displayExistingFile(int fileIndex) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        
        var compInfo = fileComparisons.get(fileIndex);
        if (compInfo.diffPanel == null) {
            logger.warn("displayExistingFile called but diffPanel is null for: {}", compInfo.getDisplayName());
            return;
        }
        
        logger.trace("Displaying existing file: {}", compInfo.getDisplayName());
        
        // Remove loading label if present
        remove(loadingLabel);
        
        // Clear tabs and add the loaded panel
        tabbedPane.removeAll();
        tabbedPane.addTab(compInfo.diffPanel.getTitle(), compInfo.diffPanel);
        this.bufferDiffPanel = compInfo.diffPanel;
        
        // Update file indicator
        if (fileIndicatorLabel != null) {
            fileIndicatorLabel.setText(compInfo.getDisplayName());
        }
        
        updateNavigationButtons();
        revalidate();
        repaint();
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
        repaint();
        updateUndoRedoButtons();
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
        repaint();
        updateUndoRedoButtons();
    }
    
    private boolean canNavigateToNextFile() {
        return getTotalFiles() > 1 && getCurrentFileIndex() < getTotalFiles() - 1;
    }
    
    private boolean canNavigateToPreviousFile() {
        return getTotalFiles() > 1 && getCurrentFileIndex() > 0;
    }
}
