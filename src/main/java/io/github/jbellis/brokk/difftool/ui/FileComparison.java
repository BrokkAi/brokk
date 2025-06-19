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
import java.util.Optional;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;


public class FileComparison extends SwingWorker<String, Object> {
    private final BrokkDiffPanel mainPanel;
    private final ContextManager contextManager;
    private JMDiffNode diffNode;
    private BufferDiffPanel panel;
    private final BufferSource leftSource;
    private final BufferSource rightSource;
    private final GuiTheme theme;
    private final boolean isMultipleCommitsContext;

    // Constructor
    private FileComparison(FileComparisonBuilder builder, GuiTheme theme, ContextManager contextManager) {
        this.mainPanel = builder.mainPanel;
        this.leftSource = builder.leftSource;
        this.rightSource = builder.rightSource;
        this.theme = theme;
        this.contextManager = contextManager;
        this.isMultipleCommitsContext = builder.isMultipleCommitsContext;
    }

    // Static Builder class
    public static class FileComparisonBuilder {
        private final BrokkDiffPanel mainPanel;
        private final ContextManager contextManager;
        private BufferSource leftSource;
        private BufferSource rightSource;
        private GuiTheme theme; // Default to light
        private boolean isMultipleCommitsContext = false;

        public FileComparisonBuilder(BrokkDiffPanel mainPanel, GuiTheme theme, ContextManager contextManager) {
            this.mainPanel = mainPanel;
            this.theme = theme;
            this.contextManager = contextManager;
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

        public FileComparisonBuilder setMultipleCommitsContext(boolean isMultipleCommitsContext) {
            this.isMultipleCommitsContext = isMultipleCommitsContext;
            return this;
        }

        public FileComparison build() {
            if (leftSource == null || rightSource == null) {
                throw new IllegalStateException("Both left and right sources must be provided for comparison.");
            }
            return new FileComparison(this, theme, contextManager);
        }
    }

    public BufferDiffPanel getPanel() {
        return panel;
    }

    @Override
    public @Nullable String doInBackground() {
        if (diffNode == null) {
            diffNode = createDiffNode(leftSource, rightSource);
        }
        diffNode.diff();
        return null;
    }

    private @Nullable String getDisplayTitleForSource(BufferSource source) {
        String originalTitle = source.title();

        if (source instanceof BufferSource.FileSource) {
            return "Working Tree"; // Represent local files as "Working Tree"
        }

        // For StringSource, originalTitle is typically a commit ID or "HEAD"
        if (originalTitle == null || originalTitle.isBlank() || originalTitle.equals("HEAD") || originalTitle.startsWith("[No Parent]")) {
            return originalTitle; // Handle special markers or blank as is
        }

        // Attempt to treat as commitId and fetch message
        IGitRepo repo = contextManager.getProject().getRepo();
        if (repo instanceof GitRepo gitRepo) { // Ensure it's our GitRepo implementation
            try {
                String commitIdToLookup = originalTitle.endsWith("^") ? originalTitle.substring(0, originalTitle.length() - 1) : originalTitle;
                Optional<CommitInfo> commitInfoOpt = gitRepo.getLocalCommitInfo(commitIdToLookup);
                if (commitInfoOpt.isPresent()) {
                    return commitInfoOpt.get().message(); // This is already the short/first line
                }
            } catch (GitAPIException e) {
                // Fall through to return originalTitle
            }
        }
        return originalTitle; // Fallback to original commit ID if message not found or repo error
    }

    private JMDiffNode createDiffNode(BufferSource left, BufferSource right) {
        Objects.requireNonNull(left, "Left source cannot be null");
        Objects.requireNonNull(right, "Right source cannot be null");

        String leftDocSyntaxHint = left.title();
        if (left instanceof BufferSource.StringSource stringSourceLeft && stringSourceLeft.filename() != null) {
            leftDocSyntaxHint = stringSourceLeft.filename();
        } else if (left instanceof BufferSource.FileSource fileSourceLeft) {
            leftDocSyntaxHint = fileSourceLeft.file().getName();
        }

        String rightDocSyntaxHint = right.title();
        if (right instanceof BufferSource.StringSource stringSourceRight && stringSourceRight.filename() != null) {
            rightDocSyntaxHint = stringSourceRight.filename();
        } else if (right instanceof BufferSource.FileSource fileSourceRight) {
            rightDocSyntaxHint = fileSourceRight.file().getName();
        }

        String leftFileDisplay = leftDocSyntaxHint;
        String nodeTitle;
        if (this.isMultipleCommitsContext) {
            nodeTitle = leftFileDisplay;
        } else {
            nodeTitle = "%s (%s)".formatted(leftFileDisplay, getDisplayTitleForSource(left));
        }
        var node = new JMDiffNode(nodeTitle, true);

        if (left instanceof BufferSource.FileSource fileSourceLeft) {
            node.setBufferNodeLeft(new FileNode(leftDocSyntaxHint, fileSourceLeft.file()));
        } else if (left instanceof BufferSource.StringSource stringSourceLeft) {
            node.setBufferNodeLeft(new StringNode(leftDocSyntaxHint, stringSourceLeft.content()));
        }

        if (right instanceof BufferSource.FileSource fileSourceRight) {
            node.setBufferNodeRight(new FileNode(rightDocSyntaxHint, fileSourceRight.file()));
        } else if (right instanceof BufferSource.StringSource stringSourceRight) {
            node.setBufferNodeRight(new StringNode(rightDocSyntaxHint, stringSourceRight.content()));
        }

        return node;
    }

    private static @Nullable ImageIcon getScaledIcon() {
        try {
            BufferedImage originalImage = ImageIO.read(Objects.requireNonNull(FileComparison.class.getResource("/images/compare.png")));
            Image scaledImage = originalImage.getScaledInstance(24, 24, Image.SCALE_SMOOTH);
            return new ImageIcon(scaledImage);
        } catch (IOException | NullPointerException e) {
            System.err.println("Image not found: " + "/images/compare.png" + ": " + e.getMessage());
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
        }
    }
}
