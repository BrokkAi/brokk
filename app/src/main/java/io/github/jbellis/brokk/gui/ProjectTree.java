package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.FileSystemEventListener;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.util.FileManagerUtil;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A custom tree component for displaying project files with lazy loading, git tracking status, and interactive
 * features.
 */
public class ProjectTree extends JTree implements FileSystemEventListener {
    private static final Logger logger = LogManager.getLogger(ProjectTree.class);
    private static final String LOADING_PLACEHOLDER = "Loading...";

    private final IProject project;
    private final ContextManager contextManager;
    private final Chrome chrome;

    @Nullable
    private JPopupMenu currentContextMenu;

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
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
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

        // Enable drag support: export selected files (or files under selected directories) as a file list
        // Also enable drop support: accept external files and copy them into the project
        setDragEnabled(true);
        setTransferHandler(new ProjectTreeTransferHandler());
    }

    private void handleDoubleClick(MouseEvent e) {
        TreePath path = getPathForLocation(e.getX(), e.getY());
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof ProjectTreeNode treeNode
                    && treeNode.getFile().isFile()) {
                ProjectFile projectFile = getProjectFileFromNode(node);
                if (projectFile != null) {
                    var fragment = new io.github.jbellis.brokk.context.ContextFragment.ProjectPathFragment(
                            projectFile, contextManager);
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

            List<ProjectFile> targetFiles = List.of();
            boolean bulk = false;

            if (path != null) {
                // If right-clicking on an item not in current selection, change selection to that item.
                // Otherwise, keep current selection.
                if (!isPathSelected(path)) {
                    setSelectionPath(path);
                }

                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.getUserObject() instanceof ProjectTreeNode treeNode) {
                    File f = treeNode.getFile();
                    if (f.isDirectory()) {
                        targetFiles = collectProjectFilesUnderDirectory(f);
                        bulk = true; // Directory context: show "All" actions
                    } else if (f.isFile()) {
                        var selectedFiles = getSelectedProjectFiles();
                        if (!selectedFiles.isEmpty()) {
                            targetFiles = selectedFiles;
                            bulk = selectedFiles.size() > 1;
                        }
                    }
                } else {
                    var selectedFiles = getSelectedProjectFiles();
                    if (!selectedFiles.isEmpty()) {
                        targetFiles = selectedFiles;
                        bulk = selectedFiles.size() > 1;
                    }
                }
            } else {
                var selectedFiles = getSelectedProjectFiles();
                if (!selectedFiles.isEmpty()) {
                    targetFiles = selectedFiles;
                    bulk = selectedFiles.size() > 1;
                }
            }

            if (!targetFiles.isEmpty()) {
                prepareAndShowContextMenu(e.getX(), e.getY(), targetFiles, bulk);
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
                        var selectedFiles = getSelectedProjectFiles();
                        if (!selectedFiles.isEmpty()) {
                            prepareAndShowContextMenu(
                                    bounds.x, bounds.y + bounds.height, selectedFiles, selectedFiles.size() > 1);
                        }
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

    private void populateContextMenu(JPopupMenu contextMenu, List<ProjectFile> targetFiles, boolean bulk) {
        contextMenu.removeAll();

        if (targetFiles.isEmpty()) {
            return;
        }

        // Add "Show History" item only for a single file and non-bulk usage
        if (!bulk && targetFiles.size() == 1) {
            JMenuItem historyItem = getHistoryMenuItem(targetFiles);
            contextMenu.add(historyItem);
            contextMenu.addSeparator();
        }

        boolean allFilesTracked = project.getRepo().getTrackedFiles().containsAll(targetFiles);

        String editLabel = bulk ? "Edit All" : "Edit";
        String summarizeLabel = bulk ? "Summarize All" : "Summarize";

        JMenuItem editItem = new JMenuItem(editLabel);
        editItem.addActionListener(ev -> {
            contextManager.submitContextTask(() -> {
                contextManager.addFiles(targetFiles);
            });
        });
        editItem.setEnabled(allFilesTracked);
        contextMenu.add(editItem);

        boolean canSummarize = anySummarizable(targetFiles);
        if (canSummarize) {
            JMenuItem summarizeItem = new JMenuItem(summarizeLabel);
            summarizeItem.addActionListener(ev -> {
                if (!contextManager.getAnalyzerWrapper().isReady()) {
                    contextManager
                            .getIo()
                            .systemNotify(
                                    AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                                    AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                                    JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                contextManager.submitContextTask(() -> {
                    contextManager.addSummaries(new HashSet<>(targetFiles), Collections.emptySet());
                });
            });
            contextMenu.add(summarizeItem);
        }

        var openInItem = new JMenuItem(FileManagerUtil.fileManagerActionLabel());
        openInItem.setToolTipText(FileManagerUtil.fileManagerActionTooltip());
        Path openTarget;
        if (!bulk && targetFiles.size() == 1) {
            openTarget = targetFiles.getFirst().absPath();
        } else {
            openTarget = getTargetDirectoryFromSelection();
        }
        final Path finalOpenTarget = openTarget;
        openInItem.setEnabled(finalOpenTarget != null && Files.exists(finalOpenTarget));
        openInItem.addActionListener(ev -> {
            if (finalOpenTarget == null || !Files.exists(finalOpenTarget)) {
                chrome.toolError("Selected path no longer exists: "
                        + (finalOpenTarget == null ? "<unknown>" : finalOpenTarget.toAbsolutePath()));
                return;
            }
            SwingWorker<Void, Void> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() throws Exception {
                    FileManagerUtil.revealPath(finalOpenTarget);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get(); // propagate any exception from doInBackground
                    } catch (Exception ex) {
                        chrome.toolError("Failed to open file manager: " + ex.getMessage());
                    }
                }
            };
            worker.execute();
        });
        contextMenu.add(openInItem);

        contextMenu.addSeparator();

        JMenuItem deleteItem = new JMenuItem(targetFiles.size() == 1 ? "Delete File" : "Delete Files");
        deleteItem.addActionListener(ev -> {
            var filesToDelete = targetFiles;

            contextManager.submitExclusiveAction(() -> {
                try {
                    var nonText =
                            filesToDelete.stream().filter(pf -> !pf.isText()).toList();
                    if (!nonText.isEmpty()) {
                        SwingUtilities.invokeLater(
                                () -> chrome.toolError("Only text files can be deleted with undo/redo support"));
                        return;
                    }

                    var trackedSet =
                            project.hasGit() ? project.getRepo().getTrackedFiles() : java.util.Set.<ProjectFile>of();
                    var deletedInfos = filesToDelete.stream()
                            .map(pf -> {
                                var content = pf.exists() ? pf.read().orElse(null) : null;
                                if (content == null) {
                                    return null;
                                }
                                boolean wasTracked = project.hasGit() && trackedSet.contains(pf);
                                return new ContextHistory.DeletedFile(pf, content, wasTracked);
                            })
                            .filter(Objects::nonNull)
                            .toList();

                    if (project.hasGit()) {
                        project.getRepo().forceRemoveFiles(filesToDelete);
                    } else {
                        for (var pf : filesToDelete) {
                            try {
                                Files.deleteIfExists(pf.absPath());
                            } catch (Exception ex) {
                                var msg = "Failed to delete file: " + pf;
                                logger.error(msg, ex);
                                SwingUtilities.invokeLater(() -> chrome.toolError(msg));
                            }
                        }
                    }

                    String fileList =
                            filesToDelete.stream().map(Object::toString).collect(Collectors.joining(", "));
                    var description = "Deleted " + fileList;
                    contextManager.pushContext(ctx -> ctx.withParsedOutput(null, description));

                    if (!deletedInfos.isEmpty()) {
                        var contextHistory = contextManager.getContextHistory();
                        var frozenContext = contextHistory.topContext();
                        contextHistory.addEntryInfo(
                                frozenContext.id(), new ContextHistory.ContextHistoryEntryInfo(deletedInfos));
                        contextManager
                                .getProject()
                                .getSessionManager()
                                .saveHistory(contextHistory, contextManager.getCurrentSessionId());
                    }

                    SwingUtilities.invokeLater(() -> {
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO, "Deleted " + fileList + ". Use Ctrl+Z to undo.");
                    });
                } catch (Exception ex) {
                    logger.error("Error deleting selected files", ex);
                    SwingUtilities.invokeLater(
                            () -> chrome.toolError("Error deleting selected files: " + ex.getMessage()));
                }
            });
        });
        contextMenu.add(deleteItem);

        contextMenu.addSeparator();
        // Add "Run Tests in Shell" item
        JMenuItem runTestsItem = new JMenuItem("Run Tests");
        boolean hasTestFiles = targetFiles.stream().allMatch(ContextManager::isTestFile);
        runTestsItem.setEnabled(hasTestFiles);
        if (!hasTestFiles) {
            runTestsItem.setToolTipText("Non-test files in selection");
        }

        runTestsItem.addActionListener(ev -> {
            contextManager.submitContextTask(() -> {
                var testProjectFiles =
                        targetFiles.stream().filter(ContextManager::isTestFile).collect(Collectors.toSet());

                if (testProjectFiles.isEmpty()) {
                    // This case might occur if selection changes between menu population and action
                    chrome.toolError("No test files were selected to run");
                } else {
                    chrome.getContextManager().runTests(testProjectFiles);
                }
            });
        });
        contextMenu.add(runTestsItem);

        // Add Paste menu item
        contextMenu.addSeparator();
        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.addActionListener(ev -> {
            // Determine target directory for paste
            Path targetDir = getTargetDirectoryFromSelection();
            pasteFilesFromClipboard(targetDir);
        });

        // Enable/disable paste based on clipboard contents
        pasteItem.setEnabled(hasFilesInClipboard());

        contextMenu.add(pasteItem);
    }

    private JMenuItem getHistoryMenuItem(List<ProjectFile> selectedFiles) {
        var file = selectedFiles.getFirst();
        boolean hasGit = contextManager.getProject().hasGit();
        JMenuItem historyItem = new JMenuItem("Show History");
        historyItem.addActionListener(ev -> {
            if (chrome.getGitCommitTab() != null) {
                chrome.addFileHistoryTab(file);
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

    private void prepareAndShowContextMenu(int x, int y, List<ProjectFile> targetFiles, boolean bulk) {
        JPopupMenu contextMenu = getOrCreateContextMenu();
        populateContextMenu(contextMenu, targetFiles, bulk);
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
            if (onlyChild.getUserObject() instanceof ProjectTreeNode childTreeNode
                    && childTreeNode.getFile().isDirectory()) {

                TreePath childPath = new TreePath(onlyChild.getPath());
                // Only expand if it's not already expanded AND its children are not yet loaded
                // (identified by placeholder). This prevents re-triggering on already processed nodes.
                if (!isExpanded(childPath) && !childTreeNode.isChildrenLoaded()) {
                    // Check that the first child is indeed the "Loading..." placeholder.
                    // This ensures we are acting on a directory that is pending its children load.
                    if (onlyChild.getChildCount() == 1
                            && onlyChild.getFirstChild() instanceof DefaultMutableTreeNode
                            && LOADING_PLACEHOLDER.equals(
                                    ((DefaultMutableTreeNode) onlyChild.getFirstChild()).getUserObject())) {
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
            } else {
                logger.warn("Could not find or expand to file in tree: " + targetFile.getRelPath());
            }
        });
    }

    private @Nullable DefaultMutableTreeNode findAndExpandNode(
            DefaultMutableTreeNode currentNode, Path relativePath, int depth) {
        // Ensure current node's children are loaded if it's a directory and not yet loaded
        if (currentNode.getUserObject() instanceof ProjectTreeNode currentPtn
                && currentPtn.getFile().isDirectory()) {
            if (!currentPtn.isChildrenLoaded()
                    && currentNode.getChildCount() > 0
                    && LOADING_PLACEHOLDER.equals(((DefaultMutableTreeNode) currentNode.getFirstChild())
                            .getUserObject()
                            .toString())) {
                // Force load children if not loaded. This relies on treeWillExpand not necessarily being the only
                // loader.
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
            if (currentNode.getUserObject() instanceof ProjectTreeNode ptn
                    && ptn.getFile().isDirectory()
                    && depth > 0
                    && ptn.getFile()
                            .getName()
                            .equals(relativePath.getName(depth - 1).toString())) {
                return currentNode;
            }
            return null; // Target not matched at the end of path traversal.
        }

        if (!(currentNode.getUserObject() instanceof ProjectTreeNode currentPtn
                && currentPtn.getFile().isDirectory())) {
            return null; // Current node is not a directory, or not a ProjectTreeNode, cannot go deeper.
        }

        String targetComponentName = relativePath.getName(depth).toString();
        Enumeration<?> children = currentNode.children();
        while (children.hasMoreElements()) {
            DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) children.nextElement();
            if (childNode.getUserObject() instanceof ProjectTreeNode childPtn
                    && childPtn.getFile().getName().equals(targetComponentName)) {

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
            if (node.getUserObject() instanceof ProjectTreeNode treeNode
                    && treeNode.getFile().isFile()) {
                ProjectFile pf = getProjectFileFromNode(node);
                if (pf != null) {
                    selectedFilesList.add(pf);
                }
            }
        }
        return List.copyOf(selectedFilesList); // Return immutable list
    }

    private List<ProjectFile> collectProjectFilesUnderDirectory(File directory) {
        assert directory.isDirectory();
        var result = new ArrayList<ProjectFile>();
        try (var walk = Files.walk(directory.toPath())) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    var rel = project.getRoot().relativize(p);
                    result.add(new ProjectFile(project.getRoot(), rel));
                } catch (Exception ex) {
                    logger.warn("Skipping non-project path while collecting files under {}: {}", directory, p, ex);
                }
            });
        } catch (Exception e) {
            logger.error("Error collecting files under directory {}", directory, e);
        }
        return List.copyOf(result);
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
            logger.warn(
                    "Could not create ProjectFile from node: "
                            + treeNode.getFile().getAbsolutePath(),
                    e);
            return null;
        }
    }

    private boolean anySummarizable(List<ProjectFile> files) {
        var exts = project.getAnalyzerLanguages().stream()
                .flatMap(lang -> lang.getExtensions().stream())
                .collect(Collectors.toSet());
        return files.stream().anyMatch(pf -> exts.contains(pf.extension()));
    }

    /**
     * Checks if the system clipboard contains files that can be pasted.
     *
     * @return true if clipboard contains file list data
     */
    private boolean hasFilesInClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            return clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor);
        } catch (Exception ex) {
            logger.debug("Error checking clipboard contents", ex);
            return false;
        }
    }

    /**
     * Gets files from the system clipboard.
     *
     * @return List of files from clipboard, or empty list if none available
     */
    @SuppressWarnings("unchecked")
    private List<File> getFilesFromClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
                Transferable contents = clipboard.getContents(null);
                if (contents != null) {
                    Object data = contents.getTransferData(DataFlavor.javaFileListFlavor);
                    if (data instanceof List) {
                        return (List<File>) data;
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Error reading files from clipboard", ex);
        }
        return List.of();
    }

    /**
     * Determines the target directory for pasting files based on current tree selection.
     *
     * @return Path to target directory
     */
    private Path getTargetDirectoryFromSelection() {
        TreePath[] selectionPaths = getSelectionPaths();
        if (selectionPaths == null || selectionPaths.length == 0) {
            // No selection, use project root
            return project.getRoot();
        }

        // Use the first selected path
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectionPaths[0].getLastPathComponent();
        if (!(node.getUserObject() instanceof ProjectTreeNode treeNode)) {
            return project.getRoot();
        }

        File targetFile = treeNode.getFile();
        if (targetFile.isDirectory()) {
            return targetFile.toPath();
        } else {
            // Selected file, use its parent directory
            return targetFile.getParentFile() != null
                    ? targetFile.getParentFile().toPath()
                    : project.getRoot();
        }
    }

    /**
     * Pastes files from the clipboard into the specified target directory.
     *
     * @param targetDirectory Directory where files should be pasted
     */
    private void pasteFilesFromClipboard(Path targetDirectory) {
        List<File> clipboardFiles = getFilesFromClipboard();

        if (clipboardFiles.isEmpty()) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No files in clipboard to paste");
            return;
        }

        // Reuse the existing file drop handler
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                List<File> filesToCopy = new ArrayList<>();

                // Filter out files that are already in the project
                for (File file : clipboardFiles) {
                    Path filePath = file.toPath().toAbsolutePath().normalize();
                    Path projectRoot = project.getRoot().toAbsolutePath().normalize();

                    if (filePath.startsWith(projectRoot)) {
                        logger.debug("Ignoring clipboard file already in project: {}", filePath);
                        continue;
                    }

                    filesToCopy.add(file);
                }

                if (filesToCopy.isEmpty()) {
                    publish("No external files to paste");
                    return null;
                }

                // Copy files using the TransferHandler's copy methods
                ProjectTreeTransferHandler handler = new ProjectTreeTransferHandler();
                for (File file : filesToCopy) {
                    handler.copyFileToProject(file, targetDirectory);
                }

                publish("Pasted " + filesToCopy.size() + " file(s) to "
                        + project.getRoot().relativize(targetDirectory));

                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String message : chunks) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, message);
                }
            }

            @Override
            protected void done() {
                try {
                    get(); // Check for exceptions
                    // Trigger tree refresh
                    onTrackedFilesChanged();
                } catch (Exception ex) {
                    logger.error("Error pasting files from clipboard", ex);
                    chrome.toolError("Error pasting files: " + ex.getMessage());
                }
            }
        };

        worker.execute();
    }

    /** Node wrapper for file information and loading state */
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

    /** Custom cell renderer that colors untracked files red */
    private class ProjectTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {

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

    /**
     * Custom TransferHandler for ProjectTree that supports: - Exporting selected files for drag operations - Importing
     * dropped files from external sources into the project
     */
    private class ProjectTreeTransferHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }

        @Override
        protected @Nullable Transferable createTransferable(JComponent c) {
            // Gather files for export: if directories are selected, include all files under them
            List<ProjectFile> selection = getSelectedProjectFiles();
            if (selection.isEmpty()) {
                // If right-clicked on a directory and then dragged without selecting files explicitly, try to infer
                TreePath lead = getLeadSelectionPath();
                if (lead != null) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) lead.getLastPathComponent();
                    if (node.getUserObject() instanceof ProjectTreeNode treeNode
                            && treeNode.getFile().isDirectory()) {
                        selection = collectProjectFilesUnderDirectory(treeNode.getFile());
                    }
                }
            }

            if (selection.isEmpty()) {
                return null;
            }

            final java.util.List<java.io.File> files =
                    selection.stream().map(pf -> pf.absPath().toFile()).collect(Collectors.toList());

            return new Transferable() {
                private final DataFlavor[] flavors = new DataFlavor[] {DataFlavor.javaFileListFlavor};

                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return flavors.clone();
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return DataFlavor.javaFileListFlavor.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor flavor) {
                    if (!isDataFlavorSupported(flavor)) {
                        throw new UnsupportedOperationException("Unsupported flavor: " + flavor);
                    }
                    return files;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            // Only accept file list drops
            if (!support.isDrop() || !support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return false;
            }

            // Only support COPY action
            boolean copySupported = (COPY & support.getSourceDropActions()) == COPY;
            if (copySupported) {
                support.setDropAction(COPY);
                return true;
            }

            return false;
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }

            try {
                @SuppressWarnings("unchecked")
                List<File> droppedFiles =
                        (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);

                if (droppedFiles == null || droppedFiles.isEmpty()) {
                    return false;
                }

                // Determine drop target directory
                Path targetDirectory = determineDropTargetDirectory(support);

                // Handle the drop in a background task
                handleFileDrop(droppedFiles, targetDirectory);

                return true;
            } catch (Exception ex) {
                logger.error("Error importing dropped files", ex);
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Failed to import dropped files: " + ex.getMessage()));
                return false;
            }
        }

        /** Determines the target directory for the drop operation based on where the drop occurred. */
        private Path determineDropTargetDirectory(TransferSupport support) {
            JTree.DropLocation dropLocation = (JTree.DropLocation) support.getDropLocation();
            TreePath path = dropLocation.getPath();

            if (path == null) {
                // Dropped on empty space, use project root
                return project.getRoot();
            }

            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (!(node.getUserObject() instanceof ProjectTreeNode treeNode)) {
                return project.getRoot();
            }

            File targetFile = treeNode.getFile();
            if (targetFile.isDirectory()) {
                return targetFile.toPath();
            } else {
                // Dropped on a file, use its parent directory
                return targetFile.getParentFile() != null
                        ? targetFile.getParentFile().toPath()
                        : project.getRoot();
            }
        }

        /** Handles the file drop operation by copying files to the target directory. */
        private void handleFileDrop(List<File> droppedFiles, Path targetDirectory) {
            SwingWorker<Void, String> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() throws Exception {
                    List<File> filesToCopy = new ArrayList<>();

                    // Filter out files that are already in the project
                    for (File file : droppedFiles) {
                        Path filePath = file.toPath().toAbsolutePath().normalize();
                        Path projectRoot = project.getRoot().toAbsolutePath().normalize();

                        if (filePath.startsWith(projectRoot)) {
                            logger.debug("Ignoring dropped file already in project: {}", filePath);
                            continue;
                        }

                        filesToCopy.add(file);
                    }

                    if (filesToCopy.isEmpty()) {
                        publish("No external files to copy");
                        return null;
                    }

                    // Copy files
                    for (File file : filesToCopy) {
                        copyFileToProject(file, targetDirectory);
                    }

                    publish("Copied " + filesToCopy.size() + " file(s) to "
                            + project.getRoot().relativize(targetDirectory));

                    return null;
                }

                @Override
                protected void process(List<String> chunks) {
                    for (String message : chunks) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, message);
                    }
                }

                @Override
                protected void done() {
                    try {
                        get(); // Check for exceptions
                        // Trigger tree refresh
                        onTrackedFilesChanged();
                    } catch (Exception ex) {
                        logger.error("Error during file copy operation", ex);
                        chrome.toolError("Error copying files: " + ex.getMessage());
                    }
                }
            };

            worker.execute();
        }

        /** Copies a file or directory recursively to the target directory in the project. */
        private void copyFileToProject(File source, Path targetDir) throws Exception {
            if (source.isDirectory()) {
                copyDirectoryToProject(source, targetDir);
            } else {
                copySingleFileToProject(source, targetDir);
            }
        }

        /** Copies a single file to the target directory. */
        private void copySingleFileToProject(File sourceFile, Path targetDir) throws Exception {
            Path targetPath = targetDir.resolve(sourceFile.getName());

            // Handle file name conflicts
            targetPath = resolveFileNameConflict(targetPath);

            Files.copy(sourceFile.toPath(), targetPath);
            logger.debug("Copied file: {} to {}", sourceFile, targetPath);
        }

        /** Copies a directory and all its contents recursively to the target directory. */
        private void copyDirectoryToProject(File sourceDir, Path targetDir) throws Exception {
            Path targetPath = targetDir.resolve(sourceDir.getName());

            // Create target directory if it doesn't exist
            if (!Files.exists(targetPath)) {
                Files.createDirectories(targetPath);
            }

            // Copy all contents
            File[] children = sourceDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyFileToProject(child, targetPath);
                }
            }

            logger.debug("Copied directory: {} to {}", sourceDir, targetPath);
        }

        /** Resolves file name conflicts by appending a number to the filename. */
        private Path resolveFileNameConflict(Path targetPath) {
            if (!Files.exists(targetPath)) {
                return targetPath;
            }

            Path parentPath = targetPath.getParent();
            if (parentPath == null) {
                // Should not happen in practice, but handle gracefully
                return targetPath;
            }

            String fileName = targetPath.getFileName().toString();
            String baseName;
            String extension;

            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0) {
                baseName = fileName.substring(0, lastDot);
                extension = fileName.substring(lastDot);
            } else {
                baseName = fileName;
                extension = "";
            }

            int counter = 1;
            Path newPath;
            do {
                String newFileName = baseName + " (" + counter + ")" + extension;
                newPath = parentPath.resolve(newFileName);
                counter++;
            } while (Files.exists(newPath));

            return newPath;
        }
    }
}
