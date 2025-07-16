package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import io.github.jbellis.brokk.FileSystemEventListener;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A custom tree component for displaying project files with lazy loading,
 * git tracking status, and interactive features.
 */
public class ProjectTree extends JTree implements FileSystemEventListener {
    private static final Logger logger = LogManager.getLogger(ProjectTree.class);
    private static final String LOADING_PLACEHOLDER = "Loading...";

    private final IProject project;
    private final ContextManager contextManager;
    private final Chrome chrome;
    @Nullable private JPopupMenu currentContextMenu;


    public ProjectTree(IProject project, ContextManager contextManager, Chrome chrome) {
        this.project = project;
        this.contextManager = contextManager;
        this.chrome = chrome;
        this.contextManager.addFileSystemEventListener(this);

        initializeTree();
        setupTreeBehavior(); // Includes mouse listeners and keyboard bindings now
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        this.contextManager.removeFileSystemEventListener(this);
    }

    private void initializeTree() {
        Path projectRoot = project.getRoot();

        ProjectTreeNode rootNode = new ProjectTreeNode(projectRoot.toFile(), false);
        DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode(rootNode);
        
        // Add loading placeholder initially
        treeRoot.add(new DefaultMutableTreeNode(LOADING_PLACEHOLDER));
        
        setModel(new DefaultTreeModel(treeRoot));
        setRootVisible(true);
        setShowsRootHandles(true);
        getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        setCellRenderer(new ProjectTreeCellRenderer());
        
        // Load root children immediately
        SwingUtilities.invokeLater(() -> loadChildrenForNode(treeRoot));
    }

    private void setupTreeBehavior() {
        addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                loadChildrenForNode(node);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
                // No action needed
            }
        });

        // Mouse listener for double-click (preview) and context menu trigger
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleDoubleClick(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                handlePopupTrigger(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopupTrigger(e);
            }
        });

        // Setup keyboard bindings for context menu
        setupContextMenuKeyBindings();
    }

    private void handleDoubleClick(MouseEvent e) {
        TreePath path = getPathForLocation(e.getX(), e.getY());
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof ProjectTreeNode treeNode && treeNode.getFile().isFile()) {
                ProjectFile projectFile = getProjectFileFromNode(node);
                if (projectFile != null) {
                    var fragment = new io.github.jbellis.brokk.context.ContextFragment.ProjectPathFragment(projectFile, contextManager);
                    chrome.openFragmentPreview(fragment);
                }
            }
        }
    }

    private void handlePopupTrigger(MouseEvent e) {
        if (e.isPopupTrigger()) {
            TreePath path = getPathForLocation(e.getX(), e.getY());
            if (path == null) {
                // If exact hit detection failed, check if we're within any row's vertical bounds
                int row = getRowForLocation(e.getX(), e.getY());
                if (row >= 0) {
                    path = getPathForRow(row);
                } else {
                    // Fallback: find the closest row by Y coordinate
                    int rowCount = getRowCount();
                    for (int i = 0; i < rowCount; i++) {
                        Rectangle rowBounds = getRowBounds(i);
                        if (rowBounds != null && e.getY() >= rowBounds.y && e.getY() < rowBounds.y + rowBounds.height) {
                            path = getPathForRow(i);
                            break;
                        }
                    }
                }
            }
            if (path != null) {
                // If right-clicking on an item not in current selection, change selection to that item.
                // Otherwise, keep current selection.
                if (!isPathSelected(path)) {
                    setSelectionPath(path);
                }
                 // Ensure the node corresponds to a file before showing context menu.
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.getUserObject() instanceof ProjectTreeNode treeNode && treeNode.getFile().isFile()) {
                    prepareAndShowContextMenu(e.getX(), e.getY());
                } else {
                     // If right-clicked on a directory or empty space with files selected, still show for selected files
                    var selectedFiles = getSelectedProjectFiles();
                    if (!selectedFiles.isEmpty()){
                        prepareAndShowContextMenu(e.getX(), e.getY());
                    }
                }
            }
        }
    }
    
    private void setupContextMenuKeyBindings() {
        InputMap inputMap = getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "showContextMenu");
        actionMap.put("showContextMenu", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                TreePath[] selectionPaths = getSelectionPaths();
                if (selectionPaths != null && selectionPaths.length > 0) {
                    Rectangle bounds = getPathBounds(selectionPaths[0]);
                    if (bounds != null) {
                        prepareAndShowContextMenu(bounds.x, bounds.y + bounds.height);
                    }
                }
            }
        });
    }
    
    private JPopupMenu getOrCreateContextMenu() {
        if (currentContextMenu == null) {
            currentContextMenu = new JPopupMenu();
            chrome.themeManager.registerPopupMenu(currentContextMenu);
        }
        return currentContextMenu;
    }

    private void populateContextMenu(JPopupMenu contextMenu) {
        contextMenu.removeAll();
        var selectedFiles = getSelectedProjectFiles();

        if (selectedFiles.isEmpty()) {
            return;
        }

        // Add "Show History" item
        if (selectedFiles.size() == 1) {
            JMenuItem historyItem = getHistoryMenuItem(selectedFiles);
            contextMenu.add(historyItem);
            contextMenu.addSeparator();
        }

        boolean allFilesTracked = project.getRepo().getTrackedFiles().containsAll(selectedFiles);

        JMenuItem editItem = new JMenuItem(selectedFiles.size() == 1 ? "Edit" : "Edit All");
        editItem.addActionListener(ev -> {
            contextManager.submitContextTask("Edit files", () -> {
                contextManager.editFiles(selectedFiles);
            });
        });
        editItem.setEnabled(allFilesTracked);
        contextMenu.add(editItem);

        JMenuItem readItem = new JMenuItem(selectedFiles.size() == 1 ? "Read" : "Read All");
        readItem.addActionListener(ev -> {
            contextManager.submitContextTask("Read files", () -> {
                contextManager.addReadOnlyFiles(selectedFiles);
            });
        });
        contextMenu.add(readItem);

        JMenuItem summarizeItem = new JMenuItem(selectedFiles.size() == 1 ? "Summarize" : "Summarize All");
        summarizeItem.addActionListener(ev -> {
            if (!contextManager.getAnalyzerWrapper().isReady()) {
                contextManager.getIo().systemNotify(AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                                                  AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                                                  JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            contextManager.submitContextTask("Summarize files", () -> {
                contextManager.addSummaries(new HashSet<>(selectedFiles), Collections.emptySet());
            });
        });
        contextMenu.add(summarizeItem);

        contextMenu.addSeparator();
        // Add "Run Tests in Shell" item
        JMenuItem runTestsItem = new JMenuItem("Run Tests in Shell");
        boolean hasTestFiles = selectedFiles.stream().allMatch(ContextManager::isTestFile);
        runTestsItem.setEnabled(hasTestFiles);
        if (!hasTestFiles) {
            runTestsItem.setToolTipText("Non-test files in selection");
        }

        runTestsItem.addActionListener(ev -> {
            contextManager.submitContextTask("Run selected tests", () -> {
                var testProjectFiles = selectedFiles.stream()
                        .filter(ContextManager::isTestFile)
                        .collect(Collectors.toSet());

                if (!testProjectFiles.isEmpty()) {
                    RunTestsService.runTests(chrome, contextManager, testProjectFiles);
                } else {
                    // This case might occur if selection changes between menu population and action
                    chrome.toolError("No test files were selected to run");
                }
            });
        });
        contextMenu.add(runTestsItem);
    }

    private @NotNull JMenuItem getHistoryMenuItem(List<ProjectFile> selectedFiles) {
        var file = selectedFiles.getFirst();
        boolean hasGit = contextManager.getProject().hasGit();
        JMenuItem historyItem = new JMenuItem("Show History");
        historyItem.addActionListener(ev -> {
            if (chrome.getGitPanel() != null) {
                chrome.getGitPanel().addFileHistoryTab(file);
            } else {
                logger.warn("GitPanel is null, cannot show history for {}", file);
            }
        });
        historyItem.setEnabled(hasGit);
        if (!hasGit) {
            historyItem.setToolTipText("Git not available for this project.");
        }
        return historyItem;
    }

    private void prepareAndShowContextMenu(int x, int y) {
        JPopupMenu contextMenu = getOrCreateContextMenu();
        populateContextMenu(contextMenu);
        if (contextMenu.getComponentCount() > 0) {
            contextMenu.show(ProjectTree.this, x, y);
            // Swing's JPopupMenu typically handles focusing its first enabled item.
        }
    }


    private void loadChildrenForNode(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof ProjectTreeNode treeNode)) {
            return;
        }

        if (treeNode.isChildrenLoaded()) {
            return;
        }

        // Remove loading placeholder
        node.removeAllChildren();

        File directory = treeNode.getFile();
        if (!directory.isDirectory()) {
            return;
        }

        try {
            File[] children = directory.listFiles();
            if (children == null) {
                return;
            }

            // Sort: directories first, then files, case-insensitive
            Arrays.sort(children, (f1, f2) -> {
                boolean f1IsDir = f1.isDirectory();
                boolean f2IsDir = f2.isDirectory();
                if (f1IsDir && !f2IsDir) return -1;
                if (!f1IsDir && f2IsDir) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });

            List<DefaultMutableTreeNode> childNodes = new ArrayList<>();
            for (File child : children) {
                ProjectTreeNode childTreeNode = new ProjectTreeNode(child, false);
                DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(childTreeNode);

                if (child.isDirectory()) {
                    // Add loading placeholder for directories
                    childNode.add(new DefaultMutableTreeNode(LOADING_PLACEHOLDER));
                }

                childNodes.add(childNode);
            }

            // Add all children to the tree
            for (DefaultMutableTreeNode childNode : childNodes) {
                node.add(childNode);
            }

            treeNode.setChildrenLoaded(true);
            ((DefaultTreeModel) getModel()).nodeStructureChanged(node);

            // Auto-expand single directory children recursively
            expandSingleDirectoryChildren(node);

        } catch (Exception e) {
            logger.error("Error loading children for directory: " + directory.getAbsolutePath(), e);
        }
    }

    private void expandSingleDirectoryChildren(DefaultMutableTreeNode parentNode) {
        // Only auto-expand if there's exactly one child and it's a directory
        if (parentNode.getChildCount() == 1) {
            DefaultMutableTreeNode onlyChild = (DefaultMutableTreeNode) parentNode.getChildAt(0);
            if (onlyChild.getUserObject() instanceof ProjectTreeNode childTreeNode &&
                childTreeNode.getFile().isDirectory()) {

                TreePath childPath = new TreePath(onlyChild.getPath());
                // Only expand if it's not already expanded AND its children are not yet loaded
                // (identified by placeholder). This prevents re-triggering on already processed nodes.
                if (!isExpanded(childPath) && !childTreeNode.isChildrenLoaded()) {
                    // Check that the first child is indeed the "Loading..." placeholder.
                    // This ensures we are acting on a directory that is pending its children load.
                    if (onlyChild.getChildCount() == 1 &&
                        onlyChild.getFirstChild() instanceof DefaultMutableTreeNode &&
                        LOADING_PLACEHOLDER.equals(((DefaultMutableTreeNode) onlyChild.getFirstChild()).getUserObject())) {
                        expandPath(childPath); // This will trigger the TreeWillExpandListener.
                                               // The listener calls loadChildrenForNode.
                                               // loadChildrenForNode calls this method again, forming the recursive chain.
                    }
                }
            }
        }
    }

    private void invalidateAllChildrenRecursively(DefaultMutableTreeNode node) {
        if (node.getUserObject() instanceof ProjectTreeNode ptn && ptn.getFile().isDirectory()) {
            ptn.setChildrenLoaded(false); // Mark as not loaded
        }
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) children.nextElement();
            invalidateAllChildrenRecursively(childNode);
        }
    }

    private void collectExpandedDirectoryPathsRecursive(DefaultMutableTreeNode node, List<Path> expandedPaths) {
        if (!(node.getUserObject() instanceof ProjectTreeNode ptn)) {
            return;
        }

        if (ptn.getFile().isDirectory()) {
            TreePath currentPath = new TreePath(node.getPath());
            if (isExpanded(currentPath)) {
                expandedPaths.add(project.getRoot().relativize(ptn.getFile().toPath()));
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof DefaultMutableTreeNode childDmtn) {
                collectExpandedDirectoryPathsRecursive(childDmtn, expandedPaths);
            }
        }
    }

    @Override
    public void onTrackedFilesChanged() {
        SwingUtilities.invokeLater(() -> {
            logger.trace("FileSystem change detected, refreshing ProjectTree.");

            DefaultMutableTreeNode root = (DefaultMutableTreeNode) getModel().getRoot();

            // Preserve current selection & expansion state
            List<ProjectFile> previouslySelectedFiles = getSelectedProjectFiles();
            List<Path> previouslyExpandedDirPaths = new ArrayList<>();
            if (root != null) {
                collectExpandedDirectoryPathsRecursive(root, previouslyExpandedDirPaths);
            }

            // Invalidate all loaded children data
            if (root != null && root.getUserObject() instanceof ProjectTreeNode ptn) {
                 ptn.setChildrenLoaded(false);
            }
            if (root != null) {
                invalidateAllChildrenRecursively(root);
                // Refresh the root node's children
                root.removeAllChildren();
                root.add(new DefaultMutableTreeNode(LOADING_PLACEHOLDER));
                ((DefaultTreeModel) getModel()).nodeStructureChanged(root);
                loadChildrenForNode(root); // This starts the loading process and initial auto-expansion
            }


            // After initial load and auto-expansion, restore other expansions
            for (Path expandedDirPath : previouslyExpandedDirPaths) {
                // Skip trying to "expand" the root path itself if it's represented as an empty path,
                // as its children are loaded by loadChildrenForNode(root).
                if (expandedDirPath.getNameCount() == 0) continue;

                DefaultMutableTreeNode nodeToExpand = findAndExpandNode(root, expandedDirPath, 0);
                if (nodeToExpand != null) {
                    TreePath pathToExpand = new TreePath(nodeToExpand.getPath());
                    if (!isExpanded(pathToExpand)) { // Check if findAndExpandNode already did it
                        expandPath(pathToExpand);
                    }
                } else {
                    logger.trace("Could not find previously expanded directory to re-expand: {}", expandedDirPath);
                }
            }

            // Restore selections
            if (!previouslySelectedFiles.isEmpty()) {
                List<TreePath> pathsToSelect = new ArrayList<>();
                for (ProjectFile pf : previouslySelectedFiles) {
                    if (pf.exists()) { // Check if file still exists
                        // findAndExpandNode will expand the path to the file if needed.
                        DefaultMutableTreeNode nodeToSelect = findAndExpandNode(root, pf.getRelPath(), 0);
                        if (nodeToSelect != null) {
                            pathsToSelect.add(new TreePath(nodeToSelect.getPath()));
                        } else {
                            logger.trace("Could not find previously selected file to re-select: {}", pf.getRelPath());
                        }
                    }
                }

                if (!pathsToSelect.isEmpty()) {
                    setSelectionPaths(pathsToSelect.toArray(new TreePath[0]));
                    // Try to scroll to the lead selection path if available, otherwise to the first selected path.
                    TreePath leadPath = getLeadSelectionPath();
                    if (leadPath != null) {
                        scrollPathToVisible(leadPath);
                    } else if (!pathsToSelect.isEmpty()) {
                         scrollPathToVisible(pathsToSelect.get(0));
                    }
                }
            }
            logger.trace("ProjectTree refresh complete.");
        });
    }

    public void selectAndExpandToFile(ProjectFile targetFile) {
        SwingUtilities.invokeLater(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) getModel().getRoot();
            Path relativePath = targetFile.getRelPath();
            DefaultMutableTreeNode targetNode = findAndExpandNode(root, relativePath, 0);
            if (targetNode != null) {
                TreePath treePath = new TreePath(targetNode.getPath());
                setSelectionPath(treePath);
                scrollPathToVisible(treePath);
                requestFocusInWindow(); // Ensure tree has focus to see selection
            } else {
                logger.warn("Could not find or expand to file in tree: " + targetFile.getRelPath());
            }
        });
    }

   private @Nullable DefaultMutableTreeNode findAndExpandNode(DefaultMutableTreeNode currentNode, Path relativePath, int depth) {
        // Ensure current node's children are loaded if it's a directory and not yet loaded
        if (currentNode.getUserObject() instanceof ProjectTreeNode currentPtn && currentPtn.getFile().isDirectory()) {
            if (!currentPtn.isChildrenLoaded() && currentNode.getChildCount() > 0 &&
                LOADING_PLACEHOLDER.equals(((DefaultMutableTreeNode) currentNode.getFirstChild()).getUserObject().toString())) {
                // Force load children if not loaded. This relies on treeWillExpand not necessarily being the only loader.
                // This call must be on EDT if it modifies tree structure directly.
                // loadChildrenForNode should handle model updates.
                 loadChildrenForNode(currentNode); // This should be safe if called on EDT.
            }
        }
        
        if (depth == relativePath.getNameCount()) {
            // Base case: We've traversed all path components.
            // This currentNode should be the target file/directory node.
            if (currentNode.getUserObject() instanceof ProjectTreeNode ptn) {
                // Check if the name matches the final component of the path.
                // For files, ptn.getFile().getName() should match relativePath.getFileName().
                // For directories, it should also match.
                if (ptn.getFile().getName().equals(relativePath.getFileName().toString())) {
                    return currentNode;
                }
            }
            // If the path was, e.g. "src/main" and "main" is the dir node.
            if (currentNode.getUserObject() instanceof ProjectTreeNode ptn && ptn.getFile().isDirectory() &&
                depth > 0 && ptn.getFile().getName().equals(relativePath.getName(depth -1).toString())) {
                 return currentNode;
            }
            return null; // Target not matched at the end of path traversal.
        }


        if (!(currentNode.getUserObject() instanceof ProjectTreeNode currentPtn && currentPtn.getFile().isDirectory())) {
            return null; // Current node is not a directory, or not a ProjectTreeNode, cannot go deeper.
        }

        String targetComponentName = relativePath.getName(depth).toString();
        Enumeration<?> children = currentNode.children();
        while (children.hasMoreElements()) {
            DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) children.nextElement();
            if (childNode.getUserObject() instanceof ProjectTreeNode childPtn &&
                childPtn.getFile().getName().equals(targetComponentName)) {
                
                if (childPtn.getFile().isDirectory()) {
                    TreePath childPath = new TreePath(childNode.getPath());
                    if (!isExpanded(childPath)) {
                        // Expanding will trigger TreeWillExpandListener, which calls loadChildrenForNode
                        expandPath(childPath);
                    }
                }
                // Recurse
                return findAndExpandNode(childNode, relativePath, depth + 1);
            }
        }
        return null; // Child component not found
    }

    private List<ProjectFile> getSelectedProjectFiles() {
        TreePath[] selectionPaths = getSelectionPaths();
        if (selectionPaths == null || selectionPaths.length == 0) {
            return List.of(); // Immutable empty list
        }

        var selectedFilesList = new ArrayList<ProjectFile>();
        for (TreePath path : selectionPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof ProjectTreeNode treeNode && treeNode.getFile().isFile()) {
                ProjectFile pf = getProjectFileFromNode(node);
                if (pf != null) {
                    selectedFilesList.add(pf);
                }
            }
        }
        return List.copyOf(selectedFilesList); // Return immutable list
    }

    private @Nullable ProjectFile getProjectFileFromNode(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof ProjectTreeNode treeNode)) {
            return null;
        }

        try {
            Path filePath = treeNode.getFile().toPath();
            Path relativePath = project.getRoot().relativize(filePath);
            return new ProjectFile(project.getRoot(), relativePath);
        } catch (Exception e) {
            logger.warn("Could not create ProjectFile from node: " + treeNode.getFile().getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * Node wrapper for file information and loading state
     */
    private static class ProjectTreeNode {
        private final File file;
        private boolean childrenLoaded;

        public ProjectTreeNode(File file, boolean childrenLoaded) {
            this.file = file;
            this.childrenLoaded = childrenLoaded;
        }

        public File getFile() {
            return file;
        }

        public boolean isChildrenLoaded() {
            return childrenLoaded;
        }

        public void setChildrenLoaded(boolean childrenLoaded) {
            this.childrenLoaded = childrenLoaded;
        }

        @Override
        public String toString() {
            return file.getName();
        }
    }

    /**
     * Custom cell renderer that colors untracked files red
     */
    private class ProjectTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, 
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode node) {
                if (node.getUserObject() instanceof ProjectTreeNode treeNode) {
                    File file = treeNode.getFile();
                    
                    // Set appropriate icon
                    if (file.isDirectory()) {
                        setIcon(expanded ? getOpenIcon() : getClosedIcon());
                    } else {
                        setIcon(getLeafIcon());
                    }

                    // Color untracked files red (only for files, not directories)
                    if (file.isFile()) {
                        Path relativePath = project.getRoot().relativize(file.toPath());
                        ProjectFile projectFile = new ProjectFile(project.getRoot(), relativePath);
                        if (!project.getRepo().getTrackedFiles().contains(projectFile)) {
                            setForeground(Color.RED);
                        }
                    }
                } else if (LOADING_PLACEHOLDER.equals(node.getUserObject())) {
                    setText(LOADING_PLACEHOLDER);
                    setIcon(null);
                }
            }

            return this;
        }
    }
}
