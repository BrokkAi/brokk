package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.ICommitInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GitCommitBrowserPanel extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitCommitBrowserPanel.class);

    private static final int COL_ID = 3;
    private static final int COL_UNPUSHED = 4;
    private static final int COL_COMMIT_OBJ = 5;

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final CommitContextReloader reloader;
    private final Options options;

    public static record Options(boolean showSearch, boolean showPushPullButtons) {
        public static final Options DEFAULT = new Options(true, true);
    }

    @FunctionalInterface
    public interface CommitContextReloader {
        void reloadCurrentContext();
    }

    @FunctionalInterface
    private interface StashActionPerformer {
        void perform(int index) throws GitAPIException;
    }

    private JTable commitsTable;
    private DefaultTableModel commitsTableModel;
    private JTree changesTree;
    private DefaultTreeModel changesTreeModel;
    private DefaultMutableTreeNode changesRootNode;
    private JLabel revisionTextLabel;
    private JTextArea revisionIdTextArea;
    private JTextField commitSearchTextField;

    private JMenuItem addToContextItem;
    private JMenuItem softResetItem;
    private JMenuItem revertCommitItem;
    private JMenuItem viewChangesItem;
    private JMenuItem compareAllToLocalItem;
    private JMenuItem popStashCommitItem;
    private JMenuItem applyStashCommitItem;
    private JMenuItem dropStashCommitItem;
    private JMenuItem createBranchFromCommitItem;

    private JButton pullButton;
    private JButton pushButton;

    private String currentBranchOrContextName; // Used by push/pull actions


    public GitCommitBrowserPanel(Chrome chrome, ContextManager contextManager, CommitContextReloader reloader, Options options) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.reloader = reloader;
        this.options = Objects.requireNonNullElse(options, Options.DEFAULT);
        buildCommitBrowserUI();
    }

    public GitCommitBrowserPanel(Chrome chrome, ContextManager contextManager, CommitContextReloader reloader) {
        this(chrome, contextManager, reloader, Options.DEFAULT);
    }

    private void buildCommitBrowserUI() {
        setLayout(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weighty = 1.0;

        JPanel commitsPanel = buildCommitsPanel();
        JPanel changesPanel = buildChangesPanel();

        Dimension nominalPreferredSize = new Dimension(1, 1);
        commitsPanel.setPreferredSize(nominalPreferredSize);
        changesPanel.setPreferredSize(nominalPreferredSize);

        constraints.gridx = 0;
        constraints.weightx = 0.65;
        add(commitsPanel, constraints);

        constraints.gridx = 1;
        constraints.weightx = 0.35;
        add(changesPanel, constraints);
    }

    private JPanel buildCommitsPanel() {
        JPanel commitsPanel = new JPanel(new BorderLayout());
        commitsPanel.setBorder(BorderFactory.createTitledBorder("Commits"));

        if (this.options.showSearch()) {
            JPanel commitSearchInputPanel = new JPanel(new BorderLayout(5, 0));
            commitSearchInputPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            commitSearchTextField = new JTextField();
            commitSearchInputPanel.add(commitSearchTextField, BorderLayout.CENTER);

            JButton commitSearchButton = new JButton("Search");
            Runnable searchAction = () -> {
                String query = commitSearchTextField.getText().trim();
                if (!query.isEmpty()) {
                    searchCommitsInPanel(query);
                } else {
                    reloader.reloadCurrentContext();
                }
            };

            commitSearchButton.addActionListener(e -> searchAction.run());
            commitSearchTextField.addActionListener(e -> searchAction.run());

            commitSearchInputPanel.add(commitSearchButton, BorderLayout.EAST);
            commitsPanel.add(commitSearchInputPanel, BorderLayout.NORTH);
        } else {
            commitSearchTextField = new JTextField(); // Keep it initialized to avoid NPEs if accessed
            commitSearchTextField.setVisible(false);
        }

        setupCommitsTable();
        commitsPanel.add(new JScrollPane(commitsTable), BorderLayout.CENTER);

        // Initialize buttons as they are class members and might be accessed by configureButton
        pullButton = new JButton("Pull");
        pullButton.setToolTipText("Pull changes from remote repository");
        pullButton.setEnabled(false);

        pushButton = new JButton("Push");
        pushButton.setToolTipText("Push commits to remote repository");
        pushButton.setEnabled(false);

        if (this.options.showPushPullButtons()) {
            JPanel commitsPanelButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            commitsPanelButtons.add(pullButton);
            commitsPanelButtons.add(pushButton);
            commitsPanel.add(commitsPanelButtons, BorderLayout.SOUTH);
        }

        return commitsPanel;
    }

    private JPanel buildChangesPanel() {
        JPanel changesPanel = new JPanel(new BorderLayout());
        changesPanel.setBorder(BorderFactory.createTitledBorder("Changes"));

        JPanel revisionDisplayPanel = new JPanel(new BorderLayout(5, 0));
        revisionTextLabel = new JLabel("Revision:");
        revisionIdTextArea = new JTextArea("N/A", 1, 1);
        revisionIdTextArea.setEditable(false);
        revisionIdTextArea.setLineWrap(true);
        revisionIdTextArea.setWrapStyleWord(true);
        revisionIdTextArea.setBackground(UIManager.getColor("Label.background"));
        revisionIdTextArea.setForeground(UIManager.getColor("Label.foreground"));
        revisionIdTextArea.setFont(UIManager.getFont("Label.font"));
        revisionIdTextArea.setBorder(BorderFactory.createEmptyBorder(2,2,2,2));

        revisionDisplayPanel.add(revisionTextLabel, BorderLayout.WEST);
        revisionDisplayPanel.add(revisionIdTextArea, BorderLayout.CENTER);
        changesPanel.add(revisionDisplayPanel, BorderLayout.NORTH);

        setupChangesTree();
        changesPanel.add(new JScrollPane(changesTree), BorderLayout.CENTER);

        return changesPanel;
    }

    private void setupCommitsTable() {
        commitsTableModel = new DefaultTableModel(
                new Object[]{"Message", "Author", "Date", "ID", "Unpushed", "CommitObject"}, 0
        ) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 4 -> Boolean.class;
                    case 5 -> ICommitInfo.class;
                    default -> String.class;
                };
            }
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        commitsTable = new JTable(commitsTableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row >= 0 && columnAtPoint(e.getPoint()) == 0) {
                    return (String) getValueAt(row, 0);
                }
                return super.getToolTipText(e);
            }
        };
        commitsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Hide ID, Unpushed, CommitObject columns
        var idColumn = commitsTable.getColumnModel().getColumn(COL_ID);
        idColumn.setMinWidth(0); idColumn.setMaxWidth(0); idColumn.setWidth(0);

        var unpushedColumn = commitsTable.getColumnModel().getColumn(COL_UNPUSHED);
        unpushedColumn.setMinWidth(0); unpushedColumn.setMaxWidth(0); unpushedColumn.setWidth(0);

        var commitObjectColumn = commitsTable.getColumnModel().getColumn(COL_COMMIT_OBJ);
        commitObjectColumn.setMinWidth(0); commitObjectColumn.setMaxWidth(0); commitObjectColumn.setWidth(0);


        commitsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                boolean isDark = chrome.themeManager != null && chrome.themeManager.isDarkTheme();
                boolean unpushed = (boolean) table.getModel().getValueAt(row, COL_UNPUSHED);

                if (!isSelected) {
                    c.setBackground(unpushed ? Colors.getChanged(isDark) : table.getBackground());
                    c.setForeground(table.getForeground());
                } else {
                    c.setBackground(unpushed ? Colors.getChanged(isDark).darker() : table.getSelectionBackground());
                    c.setForeground(table.getSelectionForeground());
                }
                setValue(value);
                return c;
            }
        });

        setupCommitSelectionListener();
        setupCommitContextMenu();
    }

    private void setupChangesTree() {
        changesRootNode = new DefaultMutableTreeNode("Changes");
        changesTreeModel = new DefaultTreeModel(changesRootNode);
        changesTree = new JTree(changesTreeModel);
        changesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        setupChangesTreeContextMenu();
        setupChangesTreeDoubleClick();
    }

    private static Stream<ProjectFile> safeChangedFiles(ICommitInfo c) {
        try {
            List<ProjectFile> changedFilesList = c.changedFiles();
            if (changedFilesList == null) {
                String commitIdStr = "unknown";
                try { commitIdStr = c.id(); } catch (Exception idEx) { /* ignore */ }
                logger.warn("Commit {} returned null for changedFiles()", commitIdStr);
                return Stream.empty();
            }
            return changedFilesList.stream();
        } catch (GitAPIException ex) {
            String commitIdStr = "unknown";
            try { commitIdStr = c.id(); } catch (Exception idEx) { logger.warn("Could not get ID for commit during GitAPIException logging", idEx); }
            logger.error("GitAPIException fetching changed files for commit {}: {}", commitIdStr, ex.getMessage());
            return Stream.empty();
        } catch (Exception ex) {
            String commitIdStr = "unknown";
            try { commitIdStr = c.id(); } catch (Exception idEx) { logger.warn("Could not get ID for commit during general exception logging", idEx); }
            logger.error("Unexpected exception fetching changed files for commit {}: {}", commitIdStr, ex.getMessage(), ex);
            return Stream.empty();
        }
    }

    private void setupCommitSelectionListener() {
        commitsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;

            int[] selectedRows = commitsTable.getSelectedRows();
            final List<ICommitInfo> allSelectedCommitsFlat;
            String labelPrefixToSet;
            String idTextToSet;

            if (selectedRows.length == 0) {
                allSelectedCommitsFlat = List.of();
                labelPrefixToSet = "Revision:";
                idTextToSet = "N/A";
            } else {
                selectedRows = Arrays.stream(selectedRows).sorted().toArray();
                allSelectedCommitsFlat = Arrays.stream(selectedRows)
                        .mapToObj(row -> (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ))
                        .toList();

                var labelParts = new ArrayList<String>();
                if (selectedRows.length == 1) {
                    labelParts.add(getShortId(allSelectedCommitsFlat.getFirst().id()));
                } else {
                    var contiguousRowIndexGroups = groupContiguous(selectedRows);
                    for (var rowIndexGroup : contiguousRowIndexGroups) {
                        var firstCommitInGroup = (ICommitInfo) commitsTableModel.getValueAt(rowIndexGroup.getFirst(), COL_COMMIT_OBJ);
                        var firstShortId = getShortId(firstCommitInGroup.id());
                        if (rowIndexGroup.size() == 1) {
                            labelParts.add(firstShortId);
                        } else {
                            var lastCommitInGroup = (ICommitInfo) commitsTableModel.getValueAt(rowIndexGroup.getLast(), COL_COMMIT_OBJ);
                            labelParts.add(String.format("%s..%s", firstShortId, getShortId(lastCommitInGroup.id())));
                        }
                    }
                }
                idTextToSet = String.join(", ", labelParts);
                labelPrefixToSet = (labelParts.size() <= 1) ? "Revision:" : "Revisions:";
            }
            revisionTextLabel.setText(labelPrefixToSet);
            revisionIdTextArea.setText(idTextToSet);
            updateChangesForCommits(allSelectedCommitsFlat);
        });
    }

    private void setupCommitContextMenu() {
        var commitsContextMenu = new JPopupMenu();
        registerMenu(commitsContextMenu);
        
        addToContextItem = new JMenuItem("Capture Diff");
        softResetItem = new JMenuItem("Soft Reset to Here");
        revertCommitItem = new JMenuItem("Revert Commit");
        viewChangesItem = new JMenuItem("View Diff");
        compareAllToLocalItem = new JMenuItem("Compare All to Local");
        popStashCommitItem = new JMenuItem("Apply and Remove");
        applyStashCommitItem = new JMenuItem("Apply Stash");
        dropStashCommitItem = new JMenuItem("Drop Stash");
        createBranchFromCommitItem = new JMenuItem("Create Branch From Commit");

        commitsContextMenu.add(addToContextItem);
        commitsContextMenu.add(viewChangesItem);
        commitsContextMenu.add(compareAllToLocalItem);
        commitsContextMenu.add(softResetItem);
        commitsContextMenu.add(revertCommitItem);
        commitsContextMenu.add(createBranchFromCommitItem);
        commitsContextMenu.add(popStashCommitItem);
        commitsContextMenu.add(applyStashCommitItem);
        commitsContextMenu.add(dropStashCommitItem);

        setupCommitContextMenuActions();
        setupCommitContextMenuListener(commitsContextMenu);
    }

    private void setupCommitContextMenuListener(JPopupMenu commitsContextMenu) {
        commitsTable.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = commitsTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        if (!commitsTable.isRowSelected(row)) {
                            commitsTable.setRowSelectionInterval(row, row);
                        } else {
                            boolean clickedRowInSelection = Arrays.stream(commitsTable.getSelectedRows()).anyMatch(sr -> sr == row); // boolean preferred by style guide
                            if (!clickedRowInSelection) commitsTable.setRowSelectionInterval(row, row);
                        }
                    }
                    updateCommitContextMenuState();
                    SwingUtil.runOnEdt(() -> { if (commitsTable.rowAtPoint(e.getPoint()) >=0) commitsContextMenu.show(commitsTable, e.getX(), e.getY()); });
                }
            }
        });
    }
    
    private void updateCommitContextMenuState() {
        int[] selectedRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
        if (selectedRows.length == 0) {
            setAllCommitMenuContextItemsVisible(false);
            return;
        }
        setAllCommitMenuContextItemsVisible(true); // Default to visible, then adjust

        var firstCommitInfo = (ICommitInfo) commitsTableModel.getValueAt(selectedRows[0], COL_COMMIT_OBJ);
        boolean isStash = firstCommitInfo.stashIndex().isPresent(); // boolean preferred by style guide

        viewChangesItem.setEnabled(selectedRows.length == 1);
        compareAllToLocalItem.setEnabled(selectedRows.length == 1 && !isStash);
        softResetItem.setVisible(!isStash);
        softResetItem.setEnabled(selectedRows.length == 1 && !isStash);
        revertCommitItem.setVisible(!isStash);
        revertCommitItem.setEnabled(selectedRows.length > 0 && !isStash); // Git revert doesn't directly do ranges. Enable if any non-stash selected.

        createBranchFromCommitItem.setVisible(!isStash);
        createBranchFromCommitItem.setEnabled(selectedRows.length == 1 && !isStash);

        boolean allSelectedAreStashes = Arrays.stream(selectedRows) // boolean preferred by style guide
                .mapToObj(r -> (ICommitInfo) commitsTableModel.getValueAt(r, COL_COMMIT_OBJ))
                .allMatch(ci -> ci.stashIndex().isPresent());

        popStashCommitItem.setVisible(allSelectedAreStashes);
        popStashCommitItem.setEnabled(allSelectedAreStashes && selectedRows.length == 1);
        applyStashCommitItem.setVisible(allSelectedAreStashes);
        applyStashCommitItem.setEnabled(allSelectedAreStashes && selectedRows.length == 1);
        dropStashCommitItem.setVisible(allSelectedAreStashes);
        dropStashCommitItem.setEnabled(allSelectedAreStashes && selectedRows.length == 1);

        // Hide non-stash items if all are stashes
        if (allSelectedAreStashes) {
            softResetItem.setVisible(false);
            revertCommitItem.setVisible(false);
            compareAllToLocalItem.setVisible(false);
            createBranchFromCommitItem.setVisible(false);
        }
        // Hide stash items if not all are stashes (or none are)
        if (!allSelectedAreStashes) {
            popStashCommitItem.setVisible(false);
            applyStashCommitItem.setVisible(false);
            dropStashCommitItem.setVisible(false);
        }
    }

    private void setAllCommitMenuContextItemsVisible(boolean visible) {
        addToContextItem.setVisible(visible);
        viewChangesItem.setVisible(visible);
        compareAllToLocalItem.setVisible(visible);
        softResetItem.setVisible(visible);
        revertCommitItem.setVisible(visible);
        popStashCommitItem.setVisible(visible);
        applyStashCommitItem.setVisible(visible);
        dropStashCommitItem.setVisible(visible);
        createBranchFromCommitItem.setVisible(visible);
    }

    private void setupCommitContextMenuActions() {
        addToContextItem.addActionListener(e -> {
            int[] selectedRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
            if (selectedRows.length == 0) return;
            selectedRows = Arrays.stream(selectedRows).sorted().toArray();
            var contiguousRowGroups = groupContiguous(selectedRows);

            for (var group : contiguousRowGroups) {
                if (group.isEmpty()) continue;
            GitUiUtil.addCommitRangeToContext(contextManager, chrome, group.stream().mapToInt(Integer::intValue).toArray(), commitsTableModel, COL_COMMIT_OBJ);
        }
    });
        
    softResetItem.addActionListener(e -> {
        int row = commitsTable.getSelectedRow(); // int preferred by style guide
        if (row != -1) {
            var ci = (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ);
            var firstLine = ci.message().split("\n", 2)[0];
            softResetToCommitInternal(ci.id(), firstLine);
        }
    });
        
    viewChangesItem.addActionListener(e -> {
        if (commitsTable.getSelectedRowCount() == 1) {
            var ci = (ICommitInfo) commitsTableModel.getValueAt(commitsTable.getSelectedRow(), COL_COMMIT_OBJ);
            GitUiUtil.openCommitDiffPanel(contextManager, chrome, ci);
        }
    });
        
    revertCommitItem.addActionListener(e -> {
        int row = commitsTable.getSelectedRow(); // int preferred by style guide // Assuming single selection for revert, or first of multiple
        if (row != -1) {
            var ci = (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ);
            revertCommitInternal(ci.id());
        }
    });
        
    popStashCommitItem.addActionListener(e -> handleStashAction(ICommitInfo::stashIndex, this::popStashInternal));
    applyStashCommitItem.addActionListener(e -> handleStashAction(ICommitInfo::stashIndex, this::applyStashInternal));
    dropStashCommitItem.addActionListener(e -> handleStashAction(ICommitInfo::stashIndex, this::dropStashInternal));
        
    compareAllToLocalItem.addActionListener(e -> {
        if (commitsTable.getSelectedRowCount() == 1) {
            var ci = (ICommitInfo) commitsTableModel.getValueAt(commitsTable.getSelectedRow(), COL_COMMIT_OBJ);
            GitUiUtil.compareCommitToLocal(contextManager, chrome, ci);
        }
    });

    createBranchFromCommitItem.addActionListener(e -> {
        int[] selectedRows = commitsTable.getSelectedRows();
        if (selectedRows.length == 1) {
            ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selectedRows[0], COL_COMMIT_OBJ);
            // Show dialog to create branch
            io.github.jbellis.brokk.gui.dialogs.CreateBranchDialog.showDialog(
                    (Frame) SwingUtilities.getWindowAncestor(this),
                    contextManager,
                    chrome,
                    commitInfo.id(),
                    getShortId(commitInfo.id())
            );
        }
    });
    }
    
    private void handleStashAction(java.util.function.Function<ICommitInfo, Optional<Integer>> stashIndexExtractor,
                                   java.util.function.IntConsumer stashOperation) {
        int row = commitsTable.getSelectedRow(); // int preferred by style guide
        if (row != -1) {
            var commitInfo = (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ);
            var stashIdxOpt = stashIndexExtractor.apply(commitInfo);
            stashIdxOpt.ifPresentOrElse(
                stashOperation::accept,
                () -> logger.warn("Stash action triggered on a non-stash commit: {}", commitInfo.id())
            );
        }
    }

    private void setupChangesTreeContextMenu() {
        var changesContextMenu = new JPopupMenu();
        registerMenu(changesContextMenu);
        
        var addFileToContextItem = new JMenuItem("Capture Diff");
        var compareFileWithLocalItem = new JMenuItem("Compare with Local");
        var viewFileAtRevisionItem = new JMenuItem("View File at Revision");
        var viewDiffItem = new JMenuItem("View Diff");
        var viewHistoryItem = new JMenuItem("View History");
        var editFileItem = new JMenuItem("Edit File(s)");
        var comparePrevWithLocalItem = new JMenuItem("Compare Previous with Local");

        changesContextMenu.add(addFileToContextItem);
        changesContextMenu.add(editFileItem);
        changesContextMenu.addSeparator();
        changesContextMenu.add(viewHistoryItem);
        changesContextMenu.addSeparator();
        changesContextMenu.add(viewFileAtRevisionItem);
        changesContextMenu.add(viewDiffItem);
        changesContextMenu.add(compareFileWithLocalItem);
        changesContextMenu.add(comparePrevWithLocalItem);

        setupChangesTreeContextMenuListener(changesContextMenu);
        setupChangesTreeContextMenuActions(addFileToContextItem, compareFileWithLocalItem, 
                                         viewFileAtRevisionItem, viewDiffItem, viewHistoryItem, 
                                         editFileItem, comparePrevWithLocalItem);
    }

    private void setupChangesTreeContextMenuListener(JPopupMenu changesContextMenu) {
        changesTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { handleChangesPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { handleChangesPopup(e); }
            private void handleChangesPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = changesTree.getRowForLocation(e.getX(), e.getY()); // int preferred by style guide
                    if (row >= 0 && !changesTree.isRowSelected(row)) changesTree.setSelectionRow(row);
                    
                    var paths = changesTree.getSelectionPaths();
                    boolean hasFileSel = (paths != null && paths.length > 0 && hasFileNodesSelected(paths)); // boolean preferred by style guide
                    boolean isSingleCommit = (commitsTable.getSelectedRowCount() == 1); // boolean preferred by style guide
                    boolean singleFileSel = (paths != null && paths.length == 1 && hasFileSel); // boolean preferred by style guide

                    for (var comp : changesContextMenu.getComponents()) {
                        if (comp instanceof JMenuItem mi) {
                            switch (mi.getText()) {
                                case "View History", "Capture Diff", "Edit File(s)" -> mi.setEnabled(hasFileSel);
                                case "View File at Revision", "View Diff", "Compare with Local", "Compare Previous with Local" -> 
                                    mi.setEnabled(singleFileSel && isSingleCommit);
                            }
                        }
                    }
                    changesContextMenu.show(changesTree, e.getX(), e.getY());
                }
            }
        });
    }

    private void setupChangesTreeContextMenuActions(JMenuItem addFileToContextItem, JMenuItem compareFileWithLocalItem,
                                                   JMenuItem viewFileAtRevisionItem, JMenuItem viewDiffItem,
                                                   JMenuItem viewHistoryItem, JMenuItem editFileItem,
                                                   JMenuItem comparePrevWithLocalItem) {
        addFileToContextItem.addActionListener(e -> {
            var files = getSelectedFilePathsFromTree();
            if (!files.isEmpty() && commitsTable.getSelectedRowCount() >= 1) {
                int[] selRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
                selRows = Arrays.stream(selRows).sorted().toArray();
                var firstCid = ((ICommitInfo)commitsTableModel.getValueAt(selRows[0], COL_COMMIT_OBJ)).id();
                var lastCid = ((ICommitInfo)commitsTableModel.getValueAt(selRows[selRows.length-1], COL_COMMIT_OBJ)).id();
                GitUiUtil.addFilesChangeToContext(contextManager, chrome, firstCid, lastCid, files.stream().map(fp -> contextManager.toFile(fp)).toList());
            }
        });
        
        compareFileWithLocalItem.addActionListener(e -> handleSingleFileSingleCommitAction((cid, fp) -> GitUiUtil.showDiffVsLocal(contextManager, chrome, cid, fp, false)));
        comparePrevWithLocalItem.addActionListener(e -> handleSingleFileSingleCommitAction((cid, fp) -> GitUiUtil.showDiffVsLocal(contextManager, chrome, cid, fp, true)));
        viewFileAtRevisionItem.addActionListener(e -> handleSingleFileSingleCommitAction((cid, fp) -> GitUiUtil.viewFileAtRevision(contextManager, chrome, cid, fp)));
        viewDiffItem.addActionListener(e -> handleSingleFileSingleCommitAction((cid, fp) -> GitUiUtil.showFileHistoryDiff(contextManager, chrome, cid, contextManager.toFile(fp))));
        
        viewHistoryItem.addActionListener(e -> getSelectedFilePathsFromTree().forEach(fp -> chrome.getGitPanel().addFileHistoryTab(contextManager.toFile(fp))));
        editFileItem.addActionListener(e -> getSelectedFilePathsFromTree().forEach(fp -> GitUiUtil.editFile(contextManager, fp)));
    }
    
    private void handleSingleFileSingleCommitAction(java.util.function.BiConsumer<String, String> action) {
        var paths = changesTree.getSelectionPaths();
        int[] selRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
        if (paths != null && paths.length == 1 && selRows.length == 1 && TreeNodeInfo.fromPath(paths[0], changesRootNode).isFile()) {
            var filePath = TreeNodeInfo.fromPath(paths[0], changesRootNode).filePath();
            var commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selRows[0], COL_COMMIT_OBJ);
            action.accept(commitInfo.id(), filePath);
        }
    }

    private void setupChangesTreeDoubleClick() {
        changesTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleSingleFileSingleCommitAction((cid, fp) -> GitUiUtil.showFileHistoryDiff(contextManager, chrome, cid, contextManager.toFile(fp)));
                }
            }
        });
    }

    private void updateChangesForCommits(List<ICommitInfo> commits) {
        if (commits == null || commits.isEmpty()) {
            changesRootNode.removeAllChildren();
            changesTreeModel.reload();
            return;
        }
        contextManager.submitBackgroundTask("Fetching changes for commits", () -> {
            try {
                var allChangedFiles = commits.stream()
                                             .flatMap(GitCommitBrowserPanel::safeChangedFiles)
                                             .collect(Collectors.toSet());
                SwingUtil.runOnEdt(() -> {
                    changesRootNode.removeAllChildren();
                    if (allChangedFiles.isEmpty()) {
                        changesTreeModel.reload(); return;
                    }
                    var filesByDir = new HashMap<Path, List<String>>();
                    for (var file : allChangedFiles) {
                        filesByDir.computeIfAbsent(file.getParent(), k -> new ArrayList<>()).add(file.getFileName());
                    }
                    var sortedDirs = new ArrayList<>(filesByDir.keySet());
                    sortedDirs.sort(Comparator.comparing(Path::toString));
                    for (var dirPath : sortedDirs) {
                        var files = filesByDir.get(dirPath); files.sort(String::compareTo);
                        var dirNode = dirPath.equals(Path.of("")) ? changesRootNode : new DefaultMutableTreeNode(dirPath);
                        if (dirNode != changesRootNode) changesRootNode.add(dirNode);
                        for (var f : files) dirNode.add(new DefaultMutableTreeNode(f));
                    }
                    changesTreeModel.reload();
                    expandAllNodes(changesTree, 0, changesTree.getRowCount());
                });
            } catch (Exception e) {
                logger.error("Error fetching changes for multiple commits", e);
                SwingUtil.runOnEdt(() -> {
                    changesRootNode.removeAllChildren();
                    changesRootNode.add(new DefaultMutableTreeNode("Error: " + e.getMessage()));
                    changesTreeModel.reload();
                });
            }
            return null;
        });
    }

    private void softResetToCommitInternal(String commitId, String commitMessage) {
        contextManager.submitUserTask("Soft resetting to " + getShortId(commitId), () -> {
            var oldHeadId = getOldHeadId();
            try {
                getRepo().softReset(commitId);
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput("Soft reset from " + getShortId(oldHeadId) + " to " + getShortId(commitId) + ": " + commitMessage);
                    refreshCurrentViewAfterGitOp(); // Assumes this method exists or is adapted
                });
            } catch (GitAPIException e) {
                logger.error("Error soft reset to {}: {}", commitId, e.getMessage());
                SwingUtil.runOnEdt(() -> chrome.toolError("Error soft reset: " + e.getMessage()));
            }
        });
    }

    private void revertCommitInternal(String commitId) {
        contextManager.submitUserTask("Reverting " + getShortId(commitId), () -> {
            try {
                getRepo().revertCommit(commitId);
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput("Commit " + getShortId(commitId) + " reverted.");
                    refreshCurrentViewAfterGitOp();
                });
            } catch (GitAPIException e) {
                logger.error("Error reverting {}: {}", commitId, e.getMessage());
                SwingUtil.runOnEdt(() -> chrome.toolError("Error reverting commit: " + e.getMessage()));
            }
        });
    }
    
    private void performStashOp(int idx, String description, StashActionPerformer repoCall, String successMsg, boolean refreshView) {
        contextManager.submitUserTask(description + " @" + idx, () -> {
            try {
                repoCall.perform(idx);
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput(successMsg);
                    if (refreshView) {
                        refreshCurrentViewAfterGitOp();
                    }
                });
            } catch (Exception e) {
                logger.error("{} failed: {}", description, e.getMessage(), e);
                SwingUtil.runOnEdt(() -> chrome.toolError(description + " error: " + e.getMessage()));
            }
        });
    }

    private void popStashInternal(int stashIndex) {
        performStashOp(stashIndex, "Popping stash", getRepo()::popStash, "Stash popped successfully.", true);
    }

    private void applyStashInternal(int stashIndex) {
        performStashOp(stashIndex, "Applying stash", getRepo()::applyStash, "Stash applied successfully.", false);
    }

    private void dropStashInternal(int stashIndex) {
        int result = JOptionPane.showConfirmDialog(this, "Delete stash@{" + stashIndex + "}?", "Confirm Drop", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE); // int preferred by style guide
        if (result != JOptionPane.YES_OPTION) return;
        performStashOp(stashIndex, "Dropping stash", getRepo()::dropStash, "Stash dropped successfully.", true);
    }

    private void pullBranchInternal(String branchName) {
        if (branchName == null || branchName.equals("stashes") || branchName.contains("/")) {
            logger.warn("Pull attempted on invalid context: {}", branchName);
            return;
        }
        contextManager.submitBackgroundTask("Checking upstream for " + branchName, () -> {
            boolean hasUpstream = getRepo().hasUpstreamBranch(branchName); // boolean preferred by style guide
            if (!hasUpstream) {
                SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(this, "Branch '" + branchName + "' has no upstream.", "Pull Error", JOptionPane.WARNING_MESSAGE));
                return null;
            }
            contextManager.submitUserTask("Pulling " + branchName, () -> {
                try {
                    getRepo().pull(); // Assumes pull on current branch is intended if branchName matches
                    SwingUtil.runOnEdt(() -> {
                        chrome.systemOutput("Pulled " + branchName);
                        refreshCurrentViewAfterGitOp();
                        chrome.getGitPanel().updateCommitPanel(); // For uncommitted changes
                    });
                } catch (GitAPIException e) {
                    logger.error("Error pulling {}: {}", branchName, e.getMessage());
                    SwingUtil.runOnEdt(() -> chrome.toolError("Pull error: " + e.getMessage()));
                }
            });
            return null;
        });
    }

    private void pushBranchInternal(String branchName) {
         if (branchName == null || branchName.equals("stashes")) {
            logger.warn("Push attempted on invalid context: {}", branchName);
            return;
        }
        // If it's a remote branch name (e.g. origin/main), it means we are in the context of a remote branch view
        // and push should ideally operate on its local tracking counterpart.
        // For simplicity here, we assume push is on the *current* local branch if `branchName` is its name.
        // The dialog using this panel will provide commits for specific source/target, this is simpler for now.

        contextManager.submitUserTask("Pushing " + branchName, () -> {
            try {
                getRepo().push(); // Pushes current branch
                SwingUtil.runOnEdt(() -> {
                    chrome.systemOutput("Pushed " + branchName);
                    refreshCurrentViewAfterGitOp();
                });
            } catch (GitRepo.GitPushRejectedException e) {
                 logger.warn("Push rejected for {}: {}", branchName, e.getMessage());
                 SwingUtil.runOnEdt(() -> chrome.toolError("Push rejected. Tip: Pull changes first.\nDetails: " + e.getMessage(), "Push Rejected"));
            } catch (GitAPIException e) {
                logger.error("Error pushing {}: {}", branchName, e.getMessage());
                SwingUtil.runOnEdt(() -> chrome.toolError("Push error: " + e.getMessage()));
            }
        });
    }
    
    // This is a placeholder. The actual refresh logic might be more complex
    // or handled by the parent component (GitLogTab or CreatePullRequestDialog).
    private void refreshCurrentViewAfterGitOp() {
        if (currentBranchOrContextName != null) {
            // Re-fetch and display commits for the current context.
            // This is a simplified call; actual parameters for unpushed, canPush/Pull might need re-evaluation.
            // For now, assume a simple refresh of the commit list.
            if ("stashes".equals(currentBranchOrContextName)) {
                loadStashesInPanel();
            } else {
                loadCommitsForBranchInPanel(currentBranchOrContextName);
            }
        }
    }

    private void loadStashesInPanel() {
        contextManager.submitBackgroundTask("Fetching stashes", () -> {
            try {
                var stashes = getRepo().listStashes();
                setCommits(stashes, Collections.emptySet(), false, false, "stashes");
            } catch (Exception e) {
                logger.error("Error fetching stashes for panel", e);
                SwingUtil.runOnEdt(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(new Object[]{"Error loading stashes: " + e.getMessage(), "", "", "", false, null});
                });
            }
            return null;
        });
    }

    private void loadCommitsForBranchInPanel(String branchName) {
        contextManager.submitBackgroundTask("Fetching commits for " + branchName, () -> {
            try {
                var commits = getRepo().listCommitsDetailed(branchName);
                var unpushedIds = new HashSet<String>();
                boolean canP = false; boolean canPl = false; // booleans preferred by style guide
                if (!branchName.contains("/")) { // Local branch
                    canPl = getRepo().hasUpstreamBranch(branchName);
                    if (canPl) {
                         unpushedIds.addAll(getRepo().getUnpushedCommitIds(branchName));
                         canP = !unpushedIds.isEmpty();
                    }
                }
                setCommits(commits, unpushedIds, canP, canPl, branchName);
            } catch (Exception e) {
                logger.error("Error fetching commits for panel (branch: {}): {}", branchName, e);
                SwingUtil.runOnEdt(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(new Object[]{"Error loading commits: " + e.getMessage(), "", "", "", false, null});
                });
            }
            return null;
        });
    }

    private void searchCommitsInPanel(String query) {
        contextManager.submitBackgroundTask("Searching commits for: " + query, () -> {
            try {
                var searchResults = getRepo().searchCommits(query);
                // For search results, unpushed status and push/pull buttons are less relevant,
                // so we pass empty/false for them.
                setCommits(searchResults, Collections.emptySet(), false, false, "Search: " + query);
                SwingUtil.runOnEdt(() -> {
                     if (searchResults.isEmpty()) chrome.systemOutput("No commits found matching: " + query);
                     else chrome.systemOutput("Found " + searchResults.size() + " commits matching: " + query);
                });
            } catch (Exception e) {
                logger.error("Error searching commits for panel: {}", query, e);
                SwingUtil.runOnEdt(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(new Object[]{"Error searching: " + e.getMessage(), "", "", "", false, null});
                    chrome.toolError("Error searching commits: " + e.getMessage());
                });
            }
            return null;
        });
    }
    
    public void clearCommitView() {
        this.currentBranchOrContextName = null;
        SwingUtil.runOnEdt(() -> {
            commitsTableModel.setRowCount(0);
            changesRootNode.removeAllChildren();
            changesTreeModel.reload();
            revisionTextLabel.setText("Revision:");
            revisionIdTextArea.setText("N/A");
            if (this.options.showPushPullButtons()) {
                pullButton.setEnabled(false);
                pushButton.setEnabled(false);
            }
            clearSearchField();
        });
    }

    public void clearSearchField() {
        if (this.options.showSearch() && commitSearchTextField != null) {
            SwingUtil.runOnEdt(() -> commitSearchTextField.setText(""));
        }
    }

    public void setCommits(List<? extends ICommitInfo> commits, Set<String> unpushedCommitIds,
                           boolean canPush, boolean canPull, String activeBranchOrContextName) {
        this.currentBranchOrContextName = activeBranchOrContextName;

        var commitRows = new ArrayList<Object[]>();
        var today = java.time.LocalDate.now(java.time.ZoneId.systemDefault());
        for (ICommitInfo commit : commits) {
            commitRows.add(new Object[]{
                    commit.message(), commit.author(), GitLogTab.formatCommitDate(commit.date(), today),
                    commit.id(), unpushedCommitIds.contains(commit.id()), commit
            });
        }

        SwingUtil.runOnEdt(() -> {
            commitsTableModel.setRowCount(0);
            changesRootNode.removeAllChildren();
            changesTreeModel.reload();

            boolean isStashView = "stashes".equals(activeBranchOrContextName); // boolean preferred by style guide
            boolean isSearchView = activeBranchOrContextName != null && activeBranchOrContextName.startsWith("Search:"); // boolean preferred by style guide
            boolean isRemoteBranchView;
            try {
                // A branch is remote only if it actually appears in the repo’s remote-branch list
                isRemoteBranchView = activeBranchOrContextName != null
                                     && getRepo().listRemoteBranches().contains(activeBranchOrContextName);
            } catch (org.eclipse.jgit.api.errors.GitAPIException ex) {
                logger.warn("Could not determine if '{}' is a remote branch. Assuming local. Error: {}",
                            activeBranchOrContextName, ex.getMessage());
                isRemoteBranchView = false;
            }

            if (this.options.showPushPullButtons()) {
                var pullTooltip = canPull ? "Pull changes for " + activeBranchOrContextName : "Cannot pull";
                boolean pullEnabled = canPull && !isStashView && !isSearchView && !isRemoteBranchView;
                java.awt.event.ActionListener pullListener = pullEnabled
                        ? e -> pullBranchInternal(activeBranchOrContextName)
                        : null;
                configureButton(pullButton, pullEnabled, pullTooltip, pullListener);

                var pushTooltip = canPush ? "Push " + unpushedCommitIds.size() + " commit(s) for " + activeBranchOrContextName : "Nothing to push or no upstream for " + activeBranchOrContextName;
                boolean pushEnabled = canPush && !isStashView && !isSearchView && !isRemoteBranchView;
                java.awt.event.ActionListener pushListener = pushEnabled
                        ? e -> pushBranchInternal(activeBranchOrContextName)
                        : null;
                configureButton(pushButton, pushEnabled, pushTooltip, pushListener);
            }

            if (commitRows.isEmpty()) {
                revisionTextLabel.setText("Revision:");
                revisionIdTextArea.setText("N/A");
                if (!activeBranchOrContextName.startsWith("Search:")) { // Don't show "no commits" for empty search
                     commitsTableModel.addRow(new Object[]{"No commits found.", "", "", "", false, null});
                }
                return;
            }

            for (Object[] rowData : commitRows) commitsTableModel.addRow(rowData);
            TableUtils.fitColumnWidth(commitsTable, 1); // Author
            TableUtils.fitColumnWidth(commitsTable, 2); // Date

            if (commitsTableModel.getRowCount() > 0) commitsTable.setRowSelectionInterval(0, 0);
            else {
                revisionTextLabel.setText("Revision:");
                revisionIdTextArea.setText("N/A");
            }
        });
    }

    // Helper methods from GitLogTab (static or instance methods if they don't depend on GitLogTab's specific state)
    private String getShortId(String commitId) {
        return commitId != null && commitId.length() >= 7 ? commitId.substring(0, 7) : commitId;
    }

    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }

    private String getOldHeadId() {
        try { return getRepo().getCurrentCommitId(); }
        catch (GitAPIException e) { return "unknown"; }
    }

    private void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; i++) tree.expandRow(i);
        if (tree.getRowCount() > rowCount) expandAllNodes(tree, rowCount, tree.getRowCount());
    }

    private boolean hasFileNodesSelected(TreePath[] paths) {
        return paths != null &&
               Arrays.stream(paths)
                     .map(p -> TreeNodeInfo.fromPath(p, changesRootNode))
                     .anyMatch(TreeNodeInfo::isFile);
    }

    private List<String> getSelectedFilePathsFromTree() {
        var paths = changesTree.getSelectionPaths();
        if (paths == null) return List.of();
        return Arrays.stream(paths)
                .map(p -> TreeNodeInfo.fromPath(p, changesRootNode).filePath())
                .filter(Objects::nonNull)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    // Helper record for TreePath analysis
    private record TreeNodeInfo(DefaultMutableTreeNode node, DefaultMutableTreeNode rootNode, boolean isFile, String filePath) {
        public static TreeNodeInfo fromPath(TreePath path, DefaultMutableTreeNode rootNode) {
            if (path == null) {
                return new TreeNodeInfo(null, rootNode, false, null);
            }
            var node = (DefaultMutableTreeNode) path.getLastPathComponent();

            // A node is a file if (and only if) it is a leaf and not the synthetic root
            boolean isFileResult = node != rootNode && node.isLeaf();

            // Build the full path by concatenating every component except the synthetic root label
            var components = new ArrayList<String>();
            for (var o : path.getPath()) {
                var n = (DefaultMutableTreeNode) o;
                if (n == rootNode) continue;  // skip synthetic root
                components.add(n.getUserObject().toString());
            }
            String calculatedFilePath = String.join("/", components);

            return new TreeNodeInfo(node, rootNode, isFileResult, calculatedFilePath);
        }
    }   // <-- close TreeNodeInfo record

    public void selectCommitById(String commitId) {
        SwingUtil.runOnEdt(() -> {
            for (int i = 0; i < commitsTableModel.getRowCount(); i++) {
                ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(i, COL_COMMIT_OBJ);
                if (commitInfo != null && commitId.equals(commitInfo.id())) {
                    commitsTable.setRowSelectionInterval(i, i);
                    commitsTable.scrollRectToVisible(commitsTable.getCellRect(i, 0, true));
                    // The selection listener will handle updating changes and revision display
                    return;
                }
            }
            chrome.systemOutput("Commit " + getShortId(commitId) + " not found in current commit browser view.");
        });
    }

    public List<ICommitInfo> getSelectedCommits() {
        int[] selectedRows = commitsTable.getSelectedRows(); // int[] preferred by style guide
        if (selectedRows.length == 0) {
            return List.of();
        }
        return Arrays.stream(selectedRows)
                .mapToObj(row -> (ICommitInfo) commitsTableModel.getValueAt(row, COL_COMMIT_OBJ))
                .toList();
    }

    private static List<List<Integer>> groupContiguous(int[] rows) { // int[] preferred by style guide
        if (rows.length == 0) return List.of();

        var groups = new ArrayList<List<Integer>>();
        var currentGroup = new ArrayList<Integer>();
        currentGroup.add(rows[0]);
        groups.add(currentGroup);

        for (int i = 1; i < rows.length; i++) { // int preferred by style guide
            if (rows[i] == rows[i - 1] + 1) {
                currentGroup.add(rows[i]);
            } else {
                currentGroup = new ArrayList<>();
                currentGroup.add(rows[i]);
                groups.add(currentGroup);
            }
        }
        return groups;
    }

    private void configureButton(JButton button, boolean enabled, String tooltip, java.awt.event.ActionListener listener) {
        button.setEnabled(enabled);
        // Visibility is now controlled at a higher level (when adding to panel)
        // and should not be changed here if options.showPushPullButtons is true.
        button.setToolTipText(tooltip);
        for (var l : button.getActionListeners()) {
            button.removeActionListener(l);
        }
        if (enabled && listener != null) {
            button.addActionListener(listener);
        }
    }

    private void registerMenu(JPopupMenu menu) {
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(menu);
        } else {
            SwingUtil.runOnEdt(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(menu);
                }
            });
        }
    }
}
