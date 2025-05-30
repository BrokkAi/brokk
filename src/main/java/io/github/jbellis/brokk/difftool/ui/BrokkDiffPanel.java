package io.github.jbellis.brokk.difftool.ui;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.patch.Patch;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class BrokkDiffPanel extends JPanel implements PropertyChangeListener {
    private final ContextManager contextManager;
    private final JTabbedPane tabbedPane;
    private boolean started;
    private final JLabel loadingLabel = new JLabel("Processing... Please wait.");
    private final BufferSource leftSource;
    private final BufferSource rightSource;
    private final GuiTheme theme;


    public BrokkDiffPanel(Builder builder, GuiTheme theme) {
        this.theme = theme;
        assert builder.contextManager != null;
        this.contextManager = builder.contextManager;
        this.leftSource = builder.leftSource;
        this.rightSource = builder.rightSource;
        assert this.contextManager != null : "ContextManager cannot be null";
        assert this.leftSource != null : "Left source cannot be null";
        assert this.rightSource != null : "Right source cannot be null";

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
        private final GuiTheme theme; // Default to light theme
        private final ContextManager contextManager;

        public Builder(GuiTheme theme, ContextManager contextManager) {
            this.theme = theme;
            assert contextManager != null;
            this.contextManager = contextManager;
        }

        public Builder leftSource(BufferSource source) {
            this.leftSource = source;
            return this;
        }

        public Builder rightSource(BufferSource source) {
            this.rightSource = source;
            return this;
        }

        public BrokkDiffPanel build() {
            if (leftSource == null || rightSource == null) {
                throw new IllegalStateException("Both left and right sources must be provided.");
            }
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

    public JButton getBtnRedo() {
        return btnRedo;
    }

    private JButton btnRedo;
    private JButton captureDiffButton;
    private BufferDiffPanel bufferDiffPanel;

    public void setBufferDiffPanel(BufferDiffPanel bufferDiffPanel) {
        this.bufferDiffPanel = bufferDiffPanel;
    }

    private BufferDiffPanel getBufferDiffPanel() {
        return bufferDiffPanel;
    }

    private JToolBar createToolbar() {
        // Create toolbar
        JToolBar toolBar = new JToolBar();

        // Create buttons
        JButton btnNext = new JButton("Next Change");
        JButton btnPrevious = new JButton("Previous Change");
        btnUndo = new JButton("Undo");
        btnRedo = new JButton("Redo");
        captureDiffButton = new JButton("Capture Diff");

        btnNext.addActionListener(e -> {
            getCurrentContentPanel().doDown();
            repaint();
        });
        btnPrevious.addActionListener(e -> {
            getCurrentContentPanel().doUp();
            repaint();
        });
        btnUndo.addActionListener(e -> {
            getCurrentContentPanel().doUndo();
            repaint();
            getBufferDiffPanel().doSave();
        });
        btnRedo.addActionListener(e -> {
            getCurrentContentPanel().doRedo();
            repaint();
            getBufferDiffPanel().doSave();
        });
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

            Patch<String> patch = DiffUtils.diff(leftLines, rightLines, (DiffAlgorithmListener) null);
            List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(this.leftSource.title(), this.rightSource.title(), leftLines, patch, 0);
            String diffText = String.join("\n", unifiedDiff);

            var description = "Captured Diff: %s vs %s".formatted(this.leftSource.title(), this.rightSource.title());

            String detectedFilename = null;
            if (this.leftSource instanceof BufferSource.StringSource s && s.filename() != null) {
                detectedFilename = s.filename();
            } else if (this.leftSource instanceof BufferSource.FileSource f) {
                detectedFilename = f.file().getName();
            }

            if (detectedFilename == null) { // Try right source if left didn't provide a filename
                if (this.rightSource instanceof BufferSource.StringSource s && s.filename() != null) {
                    detectedFilename = s.filename();
                } else if (this.rightSource instanceof BufferSource.FileSource f) {
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

            var fragment = new ContextFragment.StringFragment(diffText, description, syntaxStyle);
            contextManager.addVirtualFragment(fragment);
            contextManager.getIo().systemOutput("Added captured diff to context: " + description);
        });
        // Add buttons to toolbar with spacing
        toolBar.add(btnPrevious);
        toolBar.add(Box.createHorizontalStrut(10)); // 10px spacing
        toolBar.add(btnNext);
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
        if (getCurrentContentPanel() != null) {
            boolean canUndo = getCurrentContentPanel().isUndoEnabled();
            boolean canRedo = getCurrentContentPanel().isRedoEnabled();
            getBtnUndo().setEnabled(canUndo);
            getBtnRedo().setEnabled(canRedo);
        }
    }

    public void launchComparison() {
        loadingLabel.setFont(loadingLabel.getFont().deriveFont(Font.BOLD));
        add(loadingLabel, BorderLayout.SOUTH);
        revalidate();
        repaint();
        compare(); // Pass the stored parameters to compare()

    }

    private void compare() {
        FileComparison fileComparison = new FileComparison.FileComparisonBuilder(this, theme)
                .withSources(this.leftSource, this.rightSource)
                .build();

        fileComparison.addPropertyChangeListener(this);
        fileComparison.execute();
    }


    public AbstractContentPanel getCurrentContentPanel() {
        return (AbstractContentPanel) getTabbedPane().getSelectedComponent();
    }


    public void propertyChange(PropertyChangeEvent evt) {
        if ("state".equals(evt.getPropertyName()) &&
                SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
            try {
                String result = (String) ((SwingWorker<?, ?>) evt.getSource()).get();
                if (result != null) {
                    compare(); // Ensure compare() gets the correct parameters
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            } finally {
                remove(loadingLabel);
                revalidate();
                repaint();
            }
        }
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
}
