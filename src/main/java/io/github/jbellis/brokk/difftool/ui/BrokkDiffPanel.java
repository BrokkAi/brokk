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
        if (fileComparisons.size() <= 1) {
            return;
        }
        currentFileIndex = (currentFileIndex + 1) % fileComparisons.size();
        switchToFile(currentFileIndex);
    }
    
    public void previousFile() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        if (fileComparisons.size() <= 1) {
            return;
        }
        currentFileIndex = (currentFileIndex - 1 + fileComparisons.size()) % fileComparisons.size();
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
        
        var currentComparison = fileComparisons.get(currentFileIndex);
        
        // Update the file indicator label
        if (fileIndicatorLabel != null) {
            var text = String.format("File %d of %d: %s",
                                     currentFileIndex + 1,
                                     fileComparisons.size(),
                                     currentComparison.getDisplayName());
            fileIndicatorLabel.setText(text);
        }
        
        // Switch to the appropriate BufferDiffPanel
        if (currentComparison.diffPanel != null) {
            // Remove all tabs and add the current one
            tabbedPane.removeAll();
            tabbedPane.addTab(currentComparison.diffPanel.getTitle(), currentComparison.diffPanel);
            this.bufferDiffPanel = currentComparison.diffPanel;
            updateNavigationButtons();
        }
    }
    
    private void updateNavigationButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        updateUndoRedoButtons();
        // Could also enable/disable file navigation buttons here if needed
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
        JButton btnPreviousFile = new JButton("Previous File");
        JButton btnNextFile = new JButton("Next File");
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
        loadingLabel.setFont(loadingLabel.getFont().deriveFont(Font.BOLD));
        add(loadingLabel, BorderLayout.SOUTH);
        revalidate();
        repaint();
        
        compareAllFiles();
    }
    
    private void compareAllFiles() {
        logger.info("Starting multi-file comparison for {} files", fileComparisons.size());
        
        // Log all file comparisons
        IntStream.range(0, fileComparisons.size()).forEach(idx -> {
            var comp = fileComparisons.get(idx);
            logger.trace("File {}: {} (comparing '{}' vs '{}')",
                         idx + 1,
                         comp.getDisplayName(),
                         comp.leftSource.title(),
                         comp.rightSource.title());
        });
        
        IntStream.range(0, fileComparisons.size())
                 .forEach(i -> {
                     var compInfo = fileComparisons.get(i);
                     var fileComparison = new FileComparison.FileComparisonBuilder(this,
                                                                                    theme,
                                                                                    this.contextManager)
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
                                     fileComparisons.get(i).diffPanel = comp.getPanel();
                                     
                                     // If this is the first file, display it
                                     if (i == 0) {
                                         SwingUtilities.invokeLater(() -> {
                                             currentFileIndex = 0;
                                             updateFileDisplay();
                                             remove(loadingLabel);
                                             revalidate();
                                             repaint();
                                         });
                                     }
                                 }
                             } catch (InterruptedException | ExecutionException e) {
                                 throw new RuntimeException(e);
                             }
                         }
                     });
                     
                     fileComparison.execute();
                 });
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
