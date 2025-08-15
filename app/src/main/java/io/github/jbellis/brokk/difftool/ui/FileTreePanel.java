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
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.jetbrains.annotations.Nullable;

public class FileTreePanel extends JPanel implements ThemeAware {
    private final JTree fileTree;
    private final DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private final List<BrokkDiffPanel.FileComparisonInfo> fileComparisons;

    public interface FileSelectionListener {
        void onFileSelected(int fileIndex);
    }

    @Nullable
    private FileSelectionListener selectionListener;

    private boolean suppressSelectionEvents = false;

    public FileTreePanel(List<BrokkDiffPanel.FileComparisonInfo> fileComparisons) {
        super(new BorderLayout());
        this.fileComparisons = fileComparisons;

        rootNode = new DefaultMutableTreeNode("Files");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);

        setupTree();
        buildTree();

        add(new JScrollPane(fileTree), BorderLayout.CENTER);
    }

    private void setupTree() {
        fileTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        fileTree.setRootVisible(false);
        fileTree.setShowsRootHandles(true);

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

        var filesByDir = new HashMap<Path, List<FileInfo>>();

        for (int i = 0; i < fileComparisons.size(); i++) {
            var comparison = fileComparisons.get(i);
            var filePath = extractFilePath(comparison);
            if (filePath != null) {
                var path = Path.of(filePath);
                var parent = path.getParent();
                if (parent == null) {
                    parent = Path.of("");
                }

                var fileName = path.getFileName().toString();
                filesByDir.computeIfAbsent(parent, k -> new ArrayList<>()).add(new FileInfo(fileName, i));
            }
        }

        var sortedDirs = new ArrayList<>(filesByDir.keySet());
        sortedDirs.sort(Comparator.comparing(Path::toString));

        for (var dirPath : sortedDirs) {
            var files = filesByDir.get(dirPath);
            if (files != null) {
                files.sort(Comparator.comparing(FileInfo::name));

                var dirNode = dirPath.equals(Path.of("")) ? rootNode : findOrCreateDirectoryNode(rootNode, dirPath);

                for (var fileInfo : files) {
                    var fileNode = new DefaultMutableTreeNode(fileInfo);
                    dirNode.add(fileNode);
                }
            }
        }

        SwingUtilities.invokeLater(() -> {
            treeModel.reload();
            expandAllNodes();
        });
    }

    private DefaultMutableTreeNode findOrCreateDirectoryNode(DefaultMutableTreeNode root, Path dirPath) {
        var parts = new ArrayList<String>();
        var current = dirPath;
        while (current != null && !current.toString().isEmpty()) {
            parts.add(0, current.getFileName().toString());
            current = current.getParent();
        }

        var currentNode = root;
        for (var part : parts) {
            var found = false;
            for (int i = 0; i < currentNode.getChildCount(); i++) {
                var child = (DefaultMutableTreeNode) currentNode.getChildAt(i);
                if (child.getUserObject().toString().equals(part) && !child.isLeaf()) {
                    currentNode = child;
                    found = true;
                    break;
                }
            }

            if (!found) {
                var newNode = new DefaultMutableTreeNode(part);
                currentNode.add(newNode);
                currentNode = newNode;
            }
        }

        return currentNode;
    }

    @Nullable
    private String extractFilePath(BrokkDiffPanel.FileComparisonInfo comparison) {
        if (comparison.leftSource instanceof BufferSource.FileSource fs) {
            return fs.file().getName();
        } else if (comparison.leftSource instanceof BufferSource.StringSource ss && ss.filename() != null) {
            return ss.filename();
        }

        if (comparison.rightSource instanceof BufferSource.FileSource fs) {
            return fs.file().getName();
        } else if (comparison.rightSource instanceof BufferSource.StringSource ss && ss.filename() != null) {
            return ss.filename();
        }

        return comparison.getDisplayName();
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

    private record FileInfo(String name, int index) {}
}
