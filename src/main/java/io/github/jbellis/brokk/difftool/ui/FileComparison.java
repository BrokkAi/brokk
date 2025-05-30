package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.node.FileNode;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.node.StringNode;
import io.github.jbellis.brokk.gui.GuiTheme;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class FileComparison extends SwingWorker<String, Object> {
    private final BrokkDiffPanel mainPanel;
    private JMDiffNode diffNode;
    private BufferDiffPanel panel;
    private final BufferSource leftSource;
    private final BufferSource rightSource;
    private final GuiTheme theme;

    // Constructor
    private FileComparison(FileComparisonBuilder builder, GuiTheme theme) {
        this.mainPanel = builder.mainPanel;
        this.leftSource = builder.leftSource;
        this.rightSource = builder.rightSource;
        this.theme = theme;
    }

    // Static Builder class
    public static class FileComparisonBuilder {
        private final BrokkDiffPanel mainPanel;
        private BufferSource leftSource;
        private BufferSource rightSource;
        private GuiTheme theme; // Default to light

        public FileComparisonBuilder(BrokkDiffPanel mainPanel, GuiTheme theme) {
            this.mainPanel = mainPanel;
            this.theme = theme;
        }

        public FileComparisonBuilder withSources(BufferSource left, BufferSource right) {
            this.leftSource = left;
            this.rightSource = right;
            return this;
        }

        public FileComparisonBuilder withTheme(GuiTheme theme) {
            this.theme = theme;
            return this;
        }

        public FileComparison build() {
            if (leftSource == null || rightSource == null) {
                throw new IllegalStateException("Both left and right sources must be provided for comparison.");
            }
            return new FileComparison(this, theme);
        }
    }

    public BufferDiffPanel getPanel() {
        return panel;
    }

    @Override
    public String doInBackground() {
        if (leftSource == null || rightSource == null) {
            return "Error: Both left and right sources must be provided.";
        }

        if (diffNode == null) {
            diffNode = createDiffNode(leftSource, rightSource);
        }

        // If no errors, proceed to diffing
        // diffNode can be null if createDiffNode returns null (though it shouldn't with current logic)
        if (diffNode != null) {
            // Call diff() directly - we're already in a background thread
            diffNode.diff();
        } else {
            // This case should ideally not be reached if sources are non-null
            return "Error: Could not create diff node from sources.";
        }
        return null;
    }

    private JMDiffNode createDiffNode(BufferSource left, BufferSource right) {
        Objects.requireNonNull(left, "Left source cannot be null");
        Objects.requireNonNull(right, "Right source cannot be null");

        String leftDocName = left.title();
        if (left instanceof BufferSource.StringSource stringSourceLeft && stringSourceLeft.filename() != null) {
            leftDocName = stringSourceLeft.filename();
        }

        String rightDocName = right.title();
        if (right instanceof BufferSource.StringSource stringSourceRight && stringSourceRight.filename() != null) {
            rightDocName = stringSourceRight.filename();
        }

        // For the JMDiffNode itself, we can use a combined title or a generic one.
        // Using left and right titles might be too long. Let's use a generic approach or a fixed title.
        // For now, we'll keep the original logic of using left.title() for the JMDiffNode's internal name,
        // but the individual FileNode/StringNode will use the potentially more accurate doc names for syntax.
        var node = new JMDiffNode(left.title(), true);

        if (left instanceof BufferSource.FileSource fileSourceLeft) {
            // FileSource title is typically the filename already
            node.setBufferNodeLeft(new FileNode(fileSourceLeft.title(), fileSourceLeft.file()));
        } else if (left instanceof BufferSource.StringSource stringSourceLeft) {
            // Use leftDocName (which prioritizes filename) for StringNode
            node.setBufferNodeLeft(new StringNode(leftDocName, stringSourceLeft.content()));
        } else {
            throw new IllegalArgumentException("Unknown left source type: " + left.getClass());
        }

        if (right instanceof BufferSource.FileSource fileSourceRight) {
            // FileSource title is typically the filename already
            node.setBufferNodeRight(new FileNode(fileSourceRight.title(), fileSourceRight.file()));
        } else if (right instanceof BufferSource.StringSource stringSourceRight) {
            // Use rightDocName (which prioritizes filename) for StringNode
            node.setBufferNodeRight(new StringNode(rightDocName, stringSourceRight.content()));
        } else {
            throw new IllegalArgumentException("Unknown right source type: " + right.getClass());
        }

        return node;
    }

    private static ImageIcon getScaledIcon() {
        try {
            BufferedImage originalImage = ImageIO.read(Objects.requireNonNull(FileComparison.class.getResource("/images/compare.png")));
            Image scaledImage = originalImage.getScaledInstance(24, 24, Image.SCALE_SMOOTH);
            return new ImageIcon(scaledImage);
        } catch (IOException | NullPointerException e) {
            System.err.println("Image not found: " + "/images/compare.png" + ": " + e.getMessage());
            // Optionally rethrow if icon is critical
            // throw new RuntimeException("Failed to load required image icon", e);
            return null;
        }
    }

    @Override
    protected void done() {
        try {
            String result = get();
            if (result != null) {
                JOptionPane.showMessageDialog(mainPanel, result, "Error opening file", JOptionPane.ERROR_MESSAGE);
            } else {
                panel = new BufferDiffPanel(mainPanel, theme);
                panel.setDiffNode(diffNode);
                ImageIcon resizedIcon = getScaledIcon();
                mainPanel.getTabbedPane().addTab(panel.getTitle(), resizedIcon, panel);
                mainPanel.getTabbedPane().setSelectedComponent(panel);
                
                // Apply theme after the panel is added to the UI hierarchy
                SwingUtilities.invokeLater(() -> panel.applyTheme(theme));
            }
        } catch (Exception ex) {
            // Handle exceptions during the 'done' phase, e.g., from get()
            System.err.println("Error completing file comparison task: " + ex.getMessage());
            JOptionPane.showMessageDialog(mainPanel, "Error finalizing comparison: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            // Rethrow if necessary
            // throw new RuntimeException("Failed to complete comparison UI update", ex);
        }
    }
}
