package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.*;
import javax.swing.tree.DefaultTreeCellRenderer;
import org.jetbrains.annotations.Nullable;

public class FileTreePanel extends JPanel implements ThemeAware {
    private final JTree fileTree;
    private final DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private final List<BrokkDiffPanel.FileComparisonInfo> fileComparisons;
    private final Path projectRoot;

    public interface FileSelectionListener {
        void onFileSelected(int fileIndex);
    }

    @Nullable
    private FileSelectionListener selectionListener;

    private boolean suppressSelectionEvents = false;

    public FileTreePanel(List<BrokkDiffPanel.FileComparisonInfo> fileComparisons, Path projectRoot) {
        this(fileComparisons, projectRoot, null);
    }

    public FileTreePanel(List<BrokkDiffPanel.FileComparisonInfo> fileComparisons, Path projectRoot, @Nullable String rootTitle) {
        super(new BorderLayout());
        this.fileComparisons = fileComparisons;
        this.projectRoot = projectRoot;

        String displayTitle = rootTitle != null ? rootTitle : projectRoot.getFileName().toString();
        rootNode = new DefaultMutableTreeNode(displayTitle);
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);

        setupTree();
        buildTree();

        add(new JScrollPane(fileTree), BorderLayout.CENTER);
    }

    private void setupTree() {
        fileTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        fileTree.setRootVisible(true);
        fileTree.setShowsRootHandles(true);
        fileTree.setCellRenderer(new FileTreeCellRenderer());

        fileTree.addTreeSelectionListener(new TreeSelectionListener() {
            @Override
            public void valueChanged(TreeSelectionEvent e) {
                if (suppressSelectionEvents || selectionListener == null) {
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

    private void buildTree() {
        rootNode.removeAllChildren();

        // Collect all files with their complete paths
        var allFiles = new ArrayList<FileWithPath>();
        for (int i = 0; i < fileComparisons.size(); i++) {
            var comparison = fileComparisons.get(i);
            var filePath = extractFilePath(comparison);
            if (filePath != null) {
                var path = Path.of(filePath);
                var status = determineDiffStatus(comparison);
                allFiles.add(new FileWithPath(path, i, status));
            }
        }

        // Sort files by their full path to ensure consistent ordering
        allFiles.sort(Comparator.comparing(f -> f.path.toString()));

        // Build the complete directory structure and place files
        for (var fileWithPath : allFiles) {
            var path = fileWithPath.path;
            var fileName = path.getFileName().toString();
            var parentPath = path.getParent();

            // Find or create the parent directory node
            var parentNode = parentPath == null ? rootNode : findOrCreateDirectoryNode(rootNode, parentPath);

            // Create and add the file node
            var fileInfo = new FileInfo(fileName, fileWithPath.index, fileWithPath.status);
            var fileNode = new DefaultMutableTreeNode(fileInfo);
            parentNode.add(fileNode);
        }

        SwingUtilities.invokeLater(() -> {
            treeModel.reload();
            expandAllNodes();
        });
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

        // Navigate/create each directory level
        var currentNode = root;
        for (var part : parts) {
            DefaultMutableTreeNode childNode = null;

            // Look for existing directory node
            for (int i = 0; i < currentNode.getChildCount(); i++) {
                var child = (DefaultMutableTreeNode) currentNode.getChildAt(i);
                var userObject = child.getUserObject();
                if (userObject instanceof String dirName && dirName.equals(part)) {
                    childNode = child;
                    break;
                }
            }

            // Create new directory node if not found
            if (childNode == null) {
                childNode = new DefaultMutableTreeNode(part);
                currentNode.add(childNode);
            }

            currentNode = childNode;
        }

        return currentNode;
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
        } catch (Exception e) {
            // If path parsing fails, continue with directory structure check
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
        } catch (Exception e) {
            // If path parsing fails, return original path
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
        var leftContent = getSourceContent(comparison.leftSource);
        var rightContent = getSourceContent(comparison.rightSource);

        if (leftContent == null && rightContent != null) {
            return DiffStatus.ADDED;
        } else if (leftContent != null && rightContent == null) {
            return DiffStatus.DELETED;
        } else if (leftContent != null && rightContent != null) {
            return leftContent.equals(rightContent) ? DiffStatus.UNCHANGED : DiffStatus.MODIFIED;
        }

        return DiffStatus.UNCHANGED;
    }

    @Nullable
    private String getSourceContent(BufferSource source) {
        if (source instanceof BufferSource.StringSource ss) {
            return ss.content();
        } else if (source instanceof BufferSource.FileSource fs) {
            try {
                return java.nio.file.Files.readString(fs.file().toPath());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
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
            return;
        }

        suppressSelectionEvents = true;
        try {
            var targetPath = findPathForFileIndex(fileIndex);
            if (targetPath != null) {
                fileTree.setSelectionPath(targetPath);
                fileTree.scrollPathToVisible(targetPath);
            }
        } finally {
            suppressSelectionEvents = false;
        }
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

    @Override
    public void applyTheme(GuiTheme theme) {
        SwingUtilities.updateComponentTreeUI(this);
        revalidate();
        repaint();
    }

    private static class FileTreeCellRenderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            var node = (DefaultMutableTreeNode) value;
            var userObject = node.getUserObject();

            if (userObject instanceof FileInfo fileInfo) {
                setText(fileInfo.name());
                setIcon(getDiffStatusIcon(fileInfo.status()));
                setToolTipText(fileInfo.name() + " (" + getStatusText(fileInfo.status()) + ")");
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

        private static Icon getDiffStatusIcon(DiffStatus status) {
            return switch (status) {
                case ADDED -> createStatusIcon(new Color(40, 167, 69)); // Green for added
                case DELETED -> createStatusIcon(new Color(220, 53, 69)); // Red for deleted
                case MODIFIED -> createStatusIcon(new Color(255, 193, 7)); // Yellow for modified
                case UNCHANGED -> createStatusIcon(new Color(108, 117, 125)); // Gray for unchanged
            };
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
