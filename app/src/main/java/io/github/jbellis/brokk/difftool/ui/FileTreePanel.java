package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import java.awt.*;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.*;
import javax.swing.tree.DefaultTreeCellRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class FileTreePanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(FileTreePanel.class);

    private final JTree fileTree;
    private final DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private final List<BrokkDiffPanel.FileComparisonInfo> fileComparisons;
    private final Path projectRoot;
    private final JScrollPane scrollPane;

    @Nullable
    private GuiTheme currentTheme;

    public interface FileSelectionListener {
        void onFileSelected(int fileIndex);
    }

    @Nullable
    private FileSelectionListener selectionListener;

    private final AtomicBoolean suppressSelectionEvents = new AtomicBoolean(false);

    // Indices of files that currently have unsaved changes
    private final Set<Integer> dirtyIndices = new HashSet<>();
    private volatile int pendingInitialSelection = -1;

    public FileTreePanel(List<BrokkDiffPanel.FileComparisonInfo> fileComparisons, Path projectRoot) {
        this(fileComparisons, projectRoot, null);
    }

    public FileTreePanel(
            List<BrokkDiffPanel.FileComparisonInfo> fileComparisons, Path projectRoot, @Nullable String rootTitle) {
        super(new BorderLayout());
        this.fileComparisons = fileComparisons;
        this.projectRoot = projectRoot;
        this.currentTheme = null; // Initialize to null, will be set via applyTheme

        String displayTitle =
                rootTitle != null ? rootTitle : projectRoot.getFileName().toString();
        rootNode = new DefaultMutableTreeNode(displayTitle);
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        scrollPane = new JScrollPane(fileTree);

        setupTree();

        add(scrollPane, BorderLayout.CENTER);
    }

    private void setupTree() {
        fileTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        fileTree.setRootVisible(true);
        fileTree.setShowsRootHandles(true);
        fileTree.setCellRenderer(new FileTreeCellRenderer());

        fileTree.addTreeSelectionListener(new TreeSelectionListener() {
            @Override
            public void valueChanged(TreeSelectionEvent e) {
                if (suppressSelectionEvents.get() || selectionListener == null) {
                    return;
                }

                var selectedPath = e.getNewLeadSelectionPath();
                if (selectedPath != null) {
                    var node = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
                    if (node.isLeaf() && node != rootNode) {
                        int fileIndex = findFileIndex(selectedPath);
                        if (fileIndex != -1) {
                            selectionListener.onFileSelected(fileIndex);
                        }
                    }
                }
            }
        });
    }

    public void initializeTree() {
        buildTree();
    }

    private void buildTree() {
        rootNode.removeAllChildren();

        // Show loading state immediately
        var loadingNode = new DefaultMutableTreeNode("Loading files...");
        rootNode.add(loadingNode);
        treeModel.reload();

        // Perform file operations in background thread
        new SwingWorker<List<FileWithPath>, Void>() {
            @Override
            protected List<FileWithPath> doInBackground() throws Exception {
                assert !SwingUtilities.isEventDispatchThread() : "Background work should not run on EDT";

                // Collect all files with their complete paths and determine status
                var allFiles = IntStream.range(0, fileComparisons.size())
                        .mapToObj(i -> {
                            var comparison = fileComparisons.get(i);
                            var filePath = extractFilePath(comparison);
                            if (filePath != null) {
                                var path = Path.of(filePath);
                                var status = determineDiffStatus(comparison); // File I/O happens in background
                                return new FileWithPath(path, i, status);
                            }
                            return null;
                        })
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(f -> f.path.toString()))
                        .collect(Collectors.toCollection(ArrayList::new));

                return allFiles;
            }

            @Override
            protected void done() {
                assert SwingUtilities.isEventDispatchThread() : "UI updates must run on EDT";

                try {
                    var allFiles = get();

                    // Clear loading state and build the complete directory structure
                    rootNode.removeAllChildren();
                    allFiles.forEach(fileWithPath -> {
                        var path = fileWithPath.path;
                        var fileName = path.getFileName().toString();
                        var parentPath = path.getParent();

                        // Find or create the parent directory node
                        var parentNode =
                                parentPath == null ? rootNode : findOrCreateDirectoryNode(rootNode, parentPath);

                        // Create and add the file node
                        var fileInfo = new FileInfo(fileName, fileWithPath.index, fileWithPath.status);
                        var fileNode = new DefaultMutableTreeNode(fileInfo);
                        parentNode.add(fileNode);
                    });

                    // Update UI components
                    treeModel.reload();
                    expandAllNodes();
                    fileTree.revalidate();

                    // Perform pending initial selection if one was requested
                    var pendingSelection = pendingInitialSelection;
                    if (pendingSelection >= 0) {
                        pendingInitialSelection = -1; // Clear it first
                        selectFile(pendingSelection);
                    }

                    SwingUtilities.invokeLater(() -> {
                        scrollPane.getViewport().setViewPosition(new Point(0, 0));
                    });
                } catch (Exception e) {
                    // Handle file I/O errors gracefully
                    logger.error("Error building file tree", e);
                }
            }
        }.execute();
    }

    private DefaultMutableTreeNode findOrCreateDirectoryNode(DefaultMutableTreeNode root, Path dirPath) {
        // Split the path into parts
        var parts = new ArrayList<String>();
        var current = dirPath;
        while (current != null && !current.toString().isEmpty()) {
            var fileName = current.getFileName();
            if (fileName != null) {
                parts.add(0, fileName.toString());
            }
            current = current.getParent();
        }

        return findOrCreateDirectoryNodeWithCollapsing(root, parts);
    }

    private DefaultMutableTreeNode findOrCreateDirectoryNodeWithCollapsing(
            DefaultMutableTreeNode root, List<String> parts) {
        if (parts.isEmpty()) {
            return root;
        }

        var currentNode = root;
        var i = 0;

        while (i < parts.size()) {
            var remainingParts = parts.subList(i, parts.size());

            // Try to find existing node that matches current path segment
            DefaultMutableTreeNode matchingChild = findMatchingDirectoryChild(currentNode, remainingParts.get(0));

            if (matchingChild != null) {
                // Found existing node - check if it's collapsed and if we need to split it
                if (matchingChild instanceof CollapsedDirectoryNode collapsedNode && collapsedNode.isCollapsed()) {
                    var nodeSegments = collapsedNode.getSegments();
                    var commonPrefixLength = findCommonPrefixLength(nodeSegments, remainingParts);

                    if (commonPrefixLength == nodeSegments.size() && commonPrefixLength <= remainingParts.size()) {
                        // The collapsed node is a prefix of our path - continue from after the collapsed segment
                        currentNode = matchingChild;
                        i += commonPrefixLength;
                    } else if (commonPrefixLength > 0 && commonPrefixLength < nodeSegments.size()) {
                        // Need to split the collapsed node
                        currentNode = splitCollapsedNode(currentNode, collapsedNode, commonPrefixLength);
                        i += commonPrefixLength;
                    } else {
                        // No common prefix - create new branch
                        break;
                    }
                } else {
                    // Regular node - move to it and continue
                    currentNode = matchingChild;
                    i++;
                }
            } else {
                // No matching child found - create new collapsed chain for remaining parts
                break;
            }
        }

        // Create any remaining path segments
        if (i < parts.size()) {
            var remainingParts = parts.subList(i, parts.size());
            currentNode = createCollapsedChain(currentNode, remainingParts);
        }

        return currentNode;
    }

    @Nullable
    private DefaultMutableTreeNode findMatchingDirectoryChild(DefaultMutableTreeNode parent, String firstSegment) {
        for (int j = 0; j < parent.getChildCount(); j++) {
            var child = (DefaultMutableTreeNode) parent.getChildAt(j);
            var userObject = child.getUserObject();

            if (child instanceof CollapsedDirectoryNode collapsedNode) {
                if (!collapsedNode.getSegments().isEmpty()
                        && collapsedNode.getSegments().get(0).equals(firstSegment)) {
                    return child;
                }
            } else if (userObject instanceof String dirName && dirName.equals(firstSegment)) {
                return child;
            }
        }
        return null;
    }

    private int findCommonPrefixLength(List<String> list1, List<String> list2) {
        int commonLength = 0;
        int maxLength = Math.min(list1.size(), list2.size());

        for (int k = 0; k < maxLength; k++) {
            if (list1.get(k).equals(list2.get(k))) {
                commonLength++;
            } else {
                break;
            }
        }

        return commonLength;
    }

    private DefaultMutableTreeNode splitCollapsedNode(
            DefaultMutableTreeNode parent, CollapsedDirectoryNode collapsedNode, int splitPoint) {
        // Remove the old collapsed node
        parent.remove(collapsedNode);

        // Create new node for the common prefix
        var commonSegments = collapsedNode.getSegments().subList(0, splitPoint);
        var commonNode = createCollapsedNode(commonSegments);
        parent.add(commonNode);

        // Create new node for the remaining segments of the original collapsed node
        if (splitPoint < collapsedNode.getSegments().size()) {
            var remainingSegments = collapsedNode
                    .getSegments()
                    .subList(splitPoint, collapsedNode.getSegments().size());
            var remainingNode = createCollapsedNode(remainingSegments);
            commonNode.add(remainingNode);

            // Move any children from the original collapsed node to the new remaining node
            while (collapsedNode.getChildCount() > 0) {
                var child = (DefaultMutableTreeNode) collapsedNode.getChildAt(0);
                collapsedNode.remove(child);
                remainingNode.add(child);
            }
        }

        return commonNode;
    }

    private DefaultMutableTreeNode createCollapsedChain(DefaultMutableTreeNode parent, List<String> segments) {
        if (segments.isEmpty()) {
            return parent;
        }

        // Determine if we should collapse this chain
        // We collapse if there's only one path and it has multiple segments
        boolean shouldCollapse = segments.size() > 1;

        if (shouldCollapse) {
            var collapsedNode = createCollapsedNode(segments);
            parent.add(collapsedNode);
            return collapsedNode;
        } else {
            // Create individual nodes
            var currentNode = parent;
            for (var segment : segments) {
                var newNode = new CollapsedDirectoryNode(segment);
                currentNode.add(newNode);
                currentNode = newNode;
            }
            return currentNode;
        }
    }

    private CollapsedDirectoryNode createCollapsedNode(List<String> segments) {
        if (segments.size() == 1) {
            return new CollapsedDirectoryNode(segments.get(0));
        } else {
            return new CollapsedDirectoryNode(String.join("/", segments), segments, true);
        }
    }

    @Nullable
    private String extractFilePath(BrokkDiffPanel.FileComparisonInfo comparison) {
        // Try to get the best available path information
        String leftPath = getSourcePath(comparison.leftSource);
        String rightPath = getSourcePath(comparison.rightSource);

        // Select the best path - prefer absolute paths, then paths with directory structure
        String selectedPath = null;

        // First, try to find an absolute path
        try {
            if (leftPath != null && Path.of(leftPath).isAbsolute()) {
                selectedPath = leftPath;
            } else if (rightPath != null && Path.of(rightPath).isAbsolute()) {
                selectedPath = rightPath;
            }
        } catch (InvalidPathException e) {
            logger.warn(
                    "Invalid path encountered during absolute path check - leftPath: '{}', rightPath: '{}'",
                    leftPath,
                    rightPath,
                    e);
            // Continue with directory structure check
        }

        // If no absolute path found, prefer paths with directory structure
        if (selectedPath == null) {
            if (leftPath != null && leftPath.contains("/")) {
                selectedPath = leftPath;
            } else if (rightPath != null && rightPath.contains("/")) {
                selectedPath = rightPath;
            }
            // Fall back to any available path
            else if (leftPath != null) {
                selectedPath = leftPath;
            } else if (rightPath != null) {
                selectedPath = rightPath;
            } else {
                selectedPath = comparison.getDisplayName();
            }
        }

        // Strip project root prefix to show relative path from project root
        return stripProjectRoot(selectedPath);
    }

    @Nullable
    private String stripProjectRoot(@Nullable String filePath) {
        if (filePath == null) {
            return null;
        }

        try {
            var path = Path.of(filePath);
            if (path.isAbsolute() && path.startsWith(projectRoot)) {
                var relativePath = projectRoot.relativize(path);
                return relativePath.toString();
            }
        } catch (InvalidPathException e) {
            logger.warn("Invalid path encountered while stripping project root: '{}'", filePath, e);
            // Return original path for further processing
        }

        return filePath;
    }

    @Nullable
    private String getSourcePath(BufferSource source) {
        if (source instanceof BufferSource.FileSource fs) {
            // For FileSource, always use absolute path to get full directory structure
            var file = fs.file();
            return file.getAbsolutePath();
        } else if (source instanceof BufferSource.StringSource ss && ss.filename() != null) {
            return ss.filename();
        }
        return null;
    }

    private DiffStatus determineDiffStatus(BrokkDiffPanel.FileComparisonInfo comparison) {
        boolean leftExists = hasContent(comparison.leftSource);
        boolean rightExists = hasContent(comparison.rightSource);

        if (leftExists && !rightExists) {
            return DiffStatus.DELETED;
        } else if (!leftExists && rightExists) {
            return DiffStatus.ADDED;
        } else if (leftExists && rightExists) {
            return DiffStatus.MODIFIED;
        }

        return DiffStatus.UNCHANGED;
    }

    private boolean hasContent(BufferSource source) {
        if (source instanceof BufferSource.StringSource ss) {
            return !ss.content().isEmpty();
        } else if (source instanceof BufferSource.FileSource fs) {
            return fs.file().exists() && fs.file().length() > 0;
        }
        return false;
    }

    private void expandAllNodes() {
        var row = 0;
        while (row < fileTree.getRowCount()) {
            fileTree.expandRow(row);
            row++;
        }
    }

    private int findFileIndex(TreePath path) {
        var node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof FileInfo fileInfo) {
            return fileInfo.index();
        }
        return -1;
    }

    public void setSelectionListener(FileSelectionListener listener) {
        this.selectionListener = listener;
    }

    public void selectFile(int fileIndex) {
        if (fileIndex < 0 || fileIndex >= fileComparisons.size()) {
            logger.warn("Invalid file index: {} (valid range: 0-{})", fileIndex, fileComparisons.size() - 1);
            return;
        }

        suppressSelectionEvents.set(true);
        try {
            var targetPath = findPathForFileIndex(fileIndex);
            if (targetPath != null) {
                fileTree.setSelectionPath(targetPath);
                fileTree.scrollPathToVisible(targetPath);
                pendingInitialSelection = -1; // Clear pending selection since we successfully selected
            } else {
                // Tree isn't ready yet, store the pending selection
                pendingInitialSelection = fileIndex;
            }
        } finally {
            suppressSelectionEvents.set(false);
        }
    }

    /** Update the set of file indices that are dirty (have unsaved changes). Must be called on the EDT. */
    public void setDirtyFiles(Set<Integer> indices) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        // Compute delta between previous and new sets to avoid collapsing the tree
        var oldDirty = new HashSet<>(dirtyIndices);
        var newDirty = new HashSet<>(indices);

        // If nothing changed, do nothing
        if (oldDirty.equals(newDirty)) {
            return;
        }

        // Update internal state first
        dirtyIndices.clear();
        dirtyIndices.addAll(newDirty);

        // Symmetric difference: (old - new) U (new - old)
        var changed = new HashSet<Integer>(oldDirty);
        changed.removeAll(newDirty);
        for (var idx : newDirty) {
            if (!oldDirty.contains(idx)) {
                changed.add(idx);
            }
        }

        // Notify only the affected leaf nodes; preserve expansion/selection
        for (var idx : changed) {
            var path = findPathForFileIndex(idx);
            if (path != null) {
                var node = (DefaultMutableTreeNode) path.getLastPathComponent();
                treeModel.nodeChanged(node);
            }
        }

        // Repaint to reflect icon/tooltip changes; no reload to keep expansion/selection
        fileTree.repaint();
    }

    @Nullable
    private TreePath findPathForFileIndex(int fileIndex) {
        return findNodeWithFileIndex(rootNode, fileIndex, new TreePath(rootNode));
    }

    @Nullable
    private TreePath findNodeWithFileIndex(DefaultMutableTreeNode node, int targetIndex, TreePath currentPath) {
        if (node.getUserObject() instanceof FileInfo fileInfo && fileInfo.index() == targetIndex) {
            return currentPath;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            var child = (DefaultMutableTreeNode) node.getChildAt(i);
            var childPath = currentPath.pathByAddingChild(child);
            var result = findNodeWithFileIndex(child, targetIndex, childPath);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    @Nullable
    public GuiTheme getCurrentTheme() {
        return currentTheme;
    }

    @Override
    public void applyTheme(GuiTheme theme) {
        this.currentTheme = theme;
        SwingUtilities.updateComponentTreeUI(this);
        revalidate();
        repaint();
    }

    private class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        public FileTreeCellRenderer() {}

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            var node = (DefaultMutableTreeNode) value;
            var userObject = node.getUserObject();

            if (userObject instanceof FileInfo fileInfo) {
                setText(fileInfo.name());
                boolean isDirty = dirtyIndices.contains(fileInfo.index());
                var theme = FileTreePanel.this.currentTheme;
                if (isDirty) {
                    setIcon(getDirtyStatusIcon(fileInfo.status(), theme));
                    setToolTipText(fileInfo.name() + " (" + getStatusText(fileInfo.status()) + ", unsaved changes)");
                } else {
                    setIcon(getDiffStatusIcon(fileInfo.status(), theme));
                    setToolTipText(fileInfo.name() + " (" + getStatusText(fileInfo.status()) + ")");
                }
            } else if (node instanceof CollapsedDirectoryNode collapsedNode) {
                setText(collapsedNode.getDisplayName());
                setIcon(expanded ? getOpenIcon() : getClosedIcon());

                // Create tooltip showing the full expanded structure
                if (collapsedNode.isCollapsed()) {
                    var expandedPath = String.join(" > ", collapsedNode.getSegments());
                    setToolTipText(expandedPath + " (" + node.getChildCount() + " items)");
                } else {
                    setToolTipText(collapsedNode.getDisplayName() + " (" + node.getChildCount() + " items)");
                }
            } else if (userObject instanceof String dirName) {
                setText(dirName);
                if (node.isLeaf()) {
                    setIcon(UIManager.getIcon("FileView.fileIcon"));
                    setToolTipText(dirName);
                } else {
                    setIcon(expanded ? getOpenIcon() : getClosedIcon());
                    setToolTipText(dirName + " (" + node.getChildCount() + " items)");
                }
            }

            return this;
        }

        private static Icon getDiffStatusIcon(DiffStatus status, @Nullable GuiTheme theme) {
            // Use theme-aware git status colors for consistency, fallback to dark theme if null
            boolean isDark = theme == null || theme.isDarkTheme();
            return switch (status) {
                case ADDED -> createStatusIcon(ThemeColors.getColor(isDark, ThemeColors.GIT_STATUS_ADDED));
                case DELETED -> createStatusIcon(ThemeColors.getColor(isDark, ThemeColors.GIT_STATUS_DELETED));
                case MODIFIED -> createStatusIcon(ThemeColors.getColor(isDark, ThemeColors.GIT_STATUS_MODIFIED));
                case UNCHANGED -> createStatusIcon(ThemeColors.getColor(isDark, ThemeColors.GIT_STATUS_UNKNOWN));
            };
        }

        private static Icon getDirtyStatusIcon(DiffStatus status, @Nullable GuiTheme theme) {
            // For dirty files, keep the circle and add a small asterisk indicator to the left
            boolean isDark = theme == null || theme.isDarkTheme();
            Color color =
                    switch (status) {
                        case ADDED -> ThemeColors.getColor(isDark, ThemeColors.GIT_STATUS_ADDED);
                        case DELETED -> ThemeColors.getColor(isDark, ThemeColors.GIT_STATUS_DELETED);
                        case MODIFIED -> ThemeColors.getColor(isDark, ThemeColors.GIT_STATUS_MODIFIED);
                        case UNCHANGED -> ThemeColors.getColor(isDark, ThemeColors.GIT_STATUS_UNKNOWN);
                    };
            return createDirtyStatusIcon(color);
        }

        private static String getStatusText(DiffStatus status) {
            return switch (status) {
                case ADDED -> "Added";
                case DELETED -> "Deleted";
                case MODIFIED -> "Modified";
                case UNCHANGED -> "Unchanged";
            };
        }

        private static Icon createStatusIcon(Color color) {
            return new Icon() {
                @Override
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(color);
                    g2.fillOval(x + 4, y + 4, 8, 8);
                    g2.setColor(color.darker());
                    g2.drawOval(x + 4, y + 4, 8, 8);
                    g2.dispose();
                }

                @Override
                public int getIconWidth() {
                    return 16;
                }

                @Override
                public int getIconHeight() {
                    return 16;
                }
            };
        }

        private static Icon createDirtyStatusIcon(Color color) {
            return new Icon() {
                @Override
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    // Draw the main status circle (same footprint as getDiffStatusIcon)
                    g2.setColor(color);
                    g2.fillOval(x + 4, y + 4, 8, 8);
                    g2.setColor(color.darker());
                    g2.drawOval(x + 4, y + 4, 8, 8);

                    // Draw a small red asterisk further to the left of the circle
                    // Move it even further left and make it smaller
                    int cx = x + 1;
                    int cy = y + 8;
                    int arm = 1; // half-length of each arm

                    g2.setColor(Color.RED);
                    // Horizontal
                    g2.drawLine(cx - arm, cy, cx + arm, cy);
                    // Vertical
                    g2.drawLine(cx, cy - arm, cx, cy + arm);
                    // Diagonal \
                    g2.drawLine(cx - arm, cy - arm, cx + arm, cy + arm);
                    // Diagonal /
                    g2.drawLine(cx - arm, cy + arm, cx + arm, cy - arm);

                    g2.dispose();
                }

                @Override
                public int getIconWidth() {
                    return 16;
                }

                @Override
                public int getIconHeight() {
                    return 16;
                }
            };
        }
    }

    private static class CollapsedDirectoryNode extends DefaultMutableTreeNode {
        private final String compoundPath;
        private final List<String> segments;
        private final boolean isCollapsed;

        public CollapsedDirectoryNode(String compoundPath, List<String> segments, boolean isCollapsed) {
            super(compoundPath);
            this.compoundPath = compoundPath;
            this.segments = new ArrayList<>(segments);
            this.isCollapsed = isCollapsed;
        }

        public CollapsedDirectoryNode(String singleSegment) {
            this(singleSegment, List.of(singleSegment), false);
        }

        public List<String> getSegments() {
            return segments;
        }

        public boolean isCollapsed() {
            return isCollapsed;
        }

        public String getDisplayName() {
            return compoundPath;
        }
    }

    private record FileInfo(String name, int index, DiffStatus status) {}

    private record FileWithPath(Path path, int index, DiffStatus status) {}

    private enum DiffStatus {
        MODIFIED, // File has changes
        ADDED, // File only exists on right side
        DELETED, // File only exists on left side
        UNCHANGED // Files are identical
    }
}
