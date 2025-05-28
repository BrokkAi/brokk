package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.ICommitInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import java.util.stream.Collectors;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.*;
import java.lang.reflect.InvocationTargetException; // Needed for invokeAndWait
import java.util.List;

/**
 * Panel that contains the "Log" tab UI and related functionality:
 * - Branch tables (local & remote)
 * - Commits table
 * - Tree of changed files (per commit)
 * - Search functionality
 */
public class GitLogTab extends JPanel {

    // Methods to expose to GitPanel for finding and selecting commits by ID

    private static final Logger logger = LogManager.getLogger(GitLogTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;

    // Branches
    private JTable branchTable;
    private DefaultTableModel branchTableModel;
    private JTable remoteBranchTable;
    private DefaultTableModel remoteBranchTableModel;

    // Commits
    private JTable commitsTable;
    private DefaultTableModel commitsTableModel;
    private JButton pullButton; // Used for local branches
    private JButton pushButton; // Used for local branches

    // Changes tree
    private JTree changesTree;
    private DefaultTreeModel changesTreeModel;
    private DefaultMutableTreeNode changesRootNode;
    private JLabel revisionTextLabel; // For "Revision:" or "Revisions:"
    private JTextArea revisionIdTextArea; // For the actual commit ID(s)

    // Search
    private JTextArea searchField;

    /**
     * Constructor. Builds and arranges the UI components for the Log tab.
     */
    public GitLogTab(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        buildLogTabUI();
    }

    /**
     * Creates and arranges all the "Log" tab sub-panels:
     * - Branches (local + remote)
     * - Commits
     * - Changes tree
     * - Simple text search
     */
    private void buildLogTabUI()
    {
        // Main container uses GridBagLayout with approximate percentage widths
        JPanel logPanel = new JPanel(new GridBagLayout());
        add(logPanel, BorderLayout.CENTER);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weighty = 1.0;  // always fill vertically

        // ============ Branches Panel (left ~15%) ============

        JPanel branchesPanel = new JPanel(new BorderLayout());
        branchesPanel.setBorder(BorderFactory.createTitledBorder("Branches"));

        // Create tabbed pane for Local and Remote branches
        JTabbedPane branchTabbedPane = new JTabbedPane();

        // Local branches panel
        JPanel localBranchPanel = new JPanel(new BorderLayout());
        branchTableModel = new DefaultTableModel(new Object[]{"", "Branch"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        branchTable = new JTable(branchTableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (column == 1 && row >= 0 && row < getRowCount()) {
                    String branchName = (String) getValueAt(row, 1);
                    if ("stashes".equals(branchName)) {
                        c.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 13));
                    } else {
                        c.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                    }
                }
                return c;
            }

            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int col = columnAtPoint(e.getPoint());
                if (row >= 0 && col == 1) { // Branch name column
                    return (String) getValueAt(row, col);
                }
                return super.getToolTipText(e);
            }
        };
        branchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); // Ensure only one branch can be selected
        branchTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        branchTable.setRowHeight(18);
        branchTable.getColumnModel().getColumn(0).setMaxWidth(20);
        branchTable.getColumnModel().getColumn(0).setMinWidth(20);
        branchTable.getColumnModel().getColumn(0).setPreferredWidth(20);
        localBranchPanel.add(new JScrollPane(branchTable), BorderLayout.CENTER);

        // Remote branches panel
        JPanel remoteBranchPanel = new JPanel(new BorderLayout());
        remoteBranchTableModel = new DefaultTableModel(new Object[]{"Branch"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        remoteBranchTable = new JTable(remoteBranchTableModel);
        remoteBranchTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        remoteBranchTable.setRowHeight(18);
        remoteBranchPanel.add(new JScrollPane(remoteBranchTable), BorderLayout.CENTER);

        // Add panels to tabbed pane
        branchTabbedPane.addTab("Local", localBranchPanel);
        branchTabbedPane.addTab("Remote", remoteBranchPanel);

        branchesPanel.add(branchTabbedPane, BorderLayout.CENTER);

        // "Refresh" button for branches
        JPanel branchButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshBranchesButton = new JButton("Refresh");
        refreshBranchesButton.addActionListener(e -> update());
        branchButtonPanel.add(refreshBranchesButton);
        branchesPanel.add(branchButtonPanel, BorderLayout.SOUTH);

        // ============ Commits Panel (center ~50%) ============

        JPanel commitsPanel = new JPanel(new BorderLayout());
        commitsPanel.setBorder(BorderFactory.createTitledBorder("Commits"));

        // Add hidden column 5 for ICommitInfo object
        commitsTableModel = new DefaultTableModel(
                new Object[]{"Message", "Author", "Date", "ID", "Unpushed", "CommitObject"}, 0
        )
        {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 4 -> Boolean.class;          // unpushed
                    case 5 -> ICommitInfo.class;      // CommitObject (hidden)
                    default -> String.class;           // Message, Author, Date, ID
                };
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        commitsTable = new JTable(commitsTableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int col = columnAtPoint(e.getPoint());
                if (row >= 0 && col == 0) { // commit message column
                    return (String) getValueAt(row, col);
                }
                return super.getToolTipText(e);
            }
        };
        commitsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Hide id, unpushed, and CommitObject columns (indices 3, 4, 5)
        for (int i = 3; i <= 5; i++) {
            var column = commitsTable.getColumnModel().getColumn(i);
            column.setMinWidth(0);
            column.setMaxWidth(0);
            column.setWidth(0);
        }

        // Highlight unpushed rows
        commitsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
            {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                boolean isDark = chrome.themeManager != null && chrome.themeManager.isDarkTheme();
                boolean unpushed = (boolean) table.getModel().getValueAt(row, 4);

                if (!isSelected) {
                    if (unpushed) {
                        c.setBackground(Colors.getChanged(isDark));
                    } else {
                        c.setBackground(table.getBackground()); // Reset background if not unpushed
                    }
                    c.setForeground(table.getForeground());
                } else {
                    // Ensure selected rows also get the correct background if they are unpushed
                    if (unpushed) {
                        c.setBackground(Colors.getChanged(isDark).darker()); // Slightly darker for selection maybe? Or use selection colors
                    } else {
                        c.setBackground(table.getSelectionBackground()); // Use default selection background
                    }
                    c.setForeground(table.getSelectionForeground()); // Use default selection foreground
                }
                // Set value after background is determined
                setValue(value);
                return c;
            }
        });

        // Commit selection => show changed files & update revision label
        commitsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }

            // The e.getValueIsAdjusting() check is already at the top of this listener.
            // The following declarations were duplicated.

            int[] selectedRows = commitsTable.getSelectedRows();
            final List<ICommitInfo> allSelectedCommitsFlat;
            String labelPrefixToSet;
            String idTextToSet;

            if (selectedRows.length == 0) {
                allSelectedCommitsFlat = List.of();
                labelPrefixToSet = "Revision:";
                idTextToSet = "N/A";
            } else {
                Arrays.sort(selectedRows);
                allSelectedCommitsFlat = Arrays.stream(selectedRows)
                        .mapToObj(row -> (ICommitInfo) commitsTableModel.getValueAt(row, 5))
                        .toList();

                var labelParts = new ArrayList<String>();
                if (selectedRows.length == 1) {
                    labelParts.add(getShortId(allSelectedCommitsFlat.getFirst().id()));
                } else { // selectedRows.length > 1
                    var contiguousRowIndexGroups = new ArrayList<List<Integer>>();
                    var currentGroup = new ArrayList<Integer>();
                    currentGroup.add(selectedRows[0]);
                    contiguousRowIndexGroups.add(currentGroup);

                    for (int i = 1; i < selectedRows.length; i++) {
                        if (selectedRows[i] == selectedRows[i - 1] + 1) {
                            currentGroup.add(selectedRows[i]);
                        } else {
                            currentGroup = new ArrayList<>();
                            currentGroup.add(selectedRows[i]);
                            contiguousRowIndexGroups.add(currentGroup);
                        }
                    }

                    for (var rowIndexGroup : contiguousRowIndexGroups) {
                        ICommitInfo firstCommitInGroup = (ICommitInfo) commitsTableModel.getValueAt(rowIndexGroup.getFirst(), 5);
                        String firstShortId = getShortId(firstCommitInGroup.id());

                        if (rowIndexGroup.size() == 1) {
                            labelParts.add(firstShortId);
                        } else {
                            ICommitInfo lastCommitInGroup = (ICommitInfo) commitsTableModel.getValueAt(rowIndexGroup.getLast(), 5);
                            String lastShortId = getShortId(lastCommitInGroup.id());
                            labelParts.add(String.format("%s..%s", firstShortId, lastShortId));
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

        // Context menu
        JPopupMenu commitsContextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(commitsContextMenu);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(commitsContextMenu);
                }
            });
        }
        JMenuItem addToContextItem = new JMenuItem("Capture Diff");
        JMenuItem softResetItem = new JMenuItem("Soft Reset to Here");
        JMenuItem revertCommitItem = new JMenuItem("Revert Commit");
        JMenuItem popStashCommitItem = new JMenuItem("Apply and Remove");
        JMenuItem applyStashCommitItem = new JMenuItem("Apply Stash");
        JMenuItem dropStashCommitItem = new JMenuItem("Drop Stash");

        commitsContextMenu.add(addToContextItem);
        commitsContextMenu.add(softResetItem);
        commitsContextMenu.add(revertCommitItem);
        commitsContextMenu.add(popStashCommitItem);
        commitsContextMenu.add(applyStashCommitItem);
        commitsContextMenu.add(dropStashCommitItem);

        commitsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            // Renamed from handleCommitsPopup for clarity
            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = commitsTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        // Ensure the clicked row is selected for context menu actions
                        if (!commitsTable.isRowSelected(row)) {
                            commitsTable.setRowSelectionInterval(row, row);
                        } else {
                            // If multiple rows are selected and the click is within the selection,
                            // don't change the selection. Otherwise, select only the clicked row.
                            int[] selectedRows = commitsTable.getSelectedRows();
                            boolean clickedRowInSelection = false;
                            for (int selectedRow : selectedRows) {
                                if (row == selectedRow) {
                                    clickedRowInSelection = true;
                                    break;
                                }
                            }
                            if (!clickedRowInSelection) {
                                commitsTable.setRowSelectionInterval(row, row);
                            }
                        }
                    }
                    updateCommitContextMenu(); // Update menu items based on new selection
                    // Use invokeLater to ensure selection updates are processed before showing menu
                    SwingUtilities.invokeLater(() -> {
                        // Re-check row at point in case of drag/scroll during invokeLater delay
                        int currentRow = commitsTable.rowAtPoint(e.getPoint());
                        if (currentRow >= 0) { // Only show if still over a valid row
                            commitsContextMenu.show(commitsTable, e.getX(), e.getY());
                        }
                    });
                }
            }

            private void updateCommitContextMenu() {
                int[] selectedRows = commitsTable.getSelectedRows(); // Use all selected rows
                if (selectedRows.length == 0) { // No rows selected
                    addToContextItem.setVisible(false);
                    softResetItem.setVisible(false);
                    revertCommitItem.setVisible(false);
                    popStashCommitItem.setVisible(false);
                    applyStashCommitItem.setVisible(false);
                    dropStashCommitItem.setVisible(false);
                    return;
                }

                // Get commit info for the *first* selected row to determine context
                ICommitInfo firstCommitInfo = (ICommitInfo) commitsTableModel.getValueAt(selectedRows[0], 5);
                boolean isStash = firstCommitInfo.stashIndex().isPresent();

                addToContextItem.setVisible(true); // Always visible if any commit selected

                softResetItem.setVisible(!isStash);
                softResetItem.setEnabled(selectedRows.length == 1 && !isStash); // Only for single, non-stash selection
                revertCommitItem.setVisible(!isStash);
                revertCommitItem.setEnabled(selectedRows.length > 0 && !isStash); // Allow reverting multiple commits? Git revert doesn't directly do ranges. Enable if any non-stash selected.

                // Stash actions only visible if *all* selected commits are stashes
                boolean allSelectedAreStashes = true;
                if (selectedRows.length > 0) {
                     var firstStashIndex = ((ICommitInfo) commitsTableModel.getValueAt(selectedRows[0], 5)).stashIndex();
                     if (!firstStashIndex.isPresent()) {
                         allSelectedAreStashes = false;
                     } else {
                         for (int i = 1; i < selectedRows.length; i++) {
                             if (!((ICommitInfo) commitsTableModel.getValueAt(selectedRows[i], 5)).stashIndex().isPresent()) {
                                 allSelectedAreStashes = false;
                                 break;
                             }
                         }
                     }
                } else {
                     allSelectedAreStashes = false; // No selection cannot be all stashes
                }


                popStashCommitItem.setVisible(allSelectedAreStashes);
                // "Apply and Remove" can be done for any single selected stash.
                popStashCommitItem.setEnabled(allSelectedAreStashes && selectedRows.length == 1);
                applyStashCommitItem.setVisible(allSelectedAreStashes);
                applyStashCommitItem.setEnabled(allSelectedAreStashes && selectedRows.length == 1); // Only apply single stash
                dropStashCommitItem.setVisible(allSelectedAreStashes);
                dropStashCommitItem.setEnabled(allSelectedAreStashes && selectedRows.length == 1); // Only drop single stash
            }
        });

        // Context menu actions
        addToContextItem.addActionListener(e -> {
            int[] selectedRows = commitsTable.getSelectedRows();
            if (selectedRows.length == 0) {
                return;
            }

            Arrays.sort(selectedRows); // Ensure rows are sorted for contiguous group detection

            var contiguousRowGroups = new ArrayList<List<Integer>>();
            if (selectedRows.length > 0) {
                var currentGroup = new ArrayList<Integer>();
                currentGroup.add(selectedRows[0]);
                contiguousRowGroups.add(currentGroup);

                for (int i = 1; i < selectedRows.length; i++) {
                    if (selectedRows[i] == selectedRows[i - 1] + 1) {
                        // Current row is contiguous with the previous one, add to current group
                        currentGroup.add(selectedRows[i]);
                    } else {
                        // Start of a new non-contiguous group
                        currentGroup = new ArrayList<>();
                        currentGroup.add(selectedRows[i]);
                        contiguousRowGroups.add(currentGroup);
                    }
                }
            }

            for (var group : contiguousRowGroups) {
                if (group.isEmpty()) { // Should not happen with the logic above, but good practice
                    continue;
                }
                // Convert the list of rows for the current group to an int array
                int[] groupAsIntArray = group.stream().mapToInt(Integer::intValue).toArray();

                // Call the utility method for each contiguous group of rows.
                // It's assumed that addCommitRangeToContext will derive the
                // first and last commit of this specific group and add one fragment.
                // Pass the table model and column index for CommitInfo object
                GitUiUtil.addCommitRangeToContext(contextManager, chrome, groupAsIntArray, commitsTableModel, 5);
            }
        });
        softResetItem.addActionListener(e -> {
            int row = commitsTable.getSelectedRow();
            if (row != -1) {
                // Get CommitInfo from the hidden column 5
                ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(row, 5);
                String commitId = commitInfo.id();
                String commitMessage = commitInfo.message();
                String firstLine = commitMessage.contains("\n")
                                   ? commitMessage.substring(0, commitMessage.indexOf('\n'))
                                   : commitMessage;
                softResetToCommit(commitId, firstLine);
            }
        });
        revertCommitItem.addActionListener(e -> {
            int row = commitsTable.getSelectedRow(); // TODO: Handle multiple selections for revert? Git revert takes single commits.
            if (row != -1) {
                // Get CommitInfo from the hidden column 5
                ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(row, 5);
                String commitId = commitInfo.id();
                revertCommit(commitId);
            }
        });
        popStashCommitItem.addActionListener(e -> {
            int row = commitsTable.getSelectedRow();
            if (row != -1) {
                // Get CommitInfo from the hidden column 5
                ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(row, 5);
                // Get stash index directly from CommitInfo
                commitInfo.stashIndex().ifPresent(this::popStash);
                 // Optional: Log if stash index is not present (shouldn't happen if menu item enabled correctly)
                 if (commitInfo.stashIndex().isEmpty()) {
                      logger.warn("Pop Stash action triggered on a non-stash commit: {}", commitInfo.id());
                 }
            }
        });
        applyStashCommitItem.addActionListener(e -> {
            int row = commitsTable.getSelectedRow();
            if (row != -1) {
                // Get CommitInfo from the hidden column 5
                ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(row, 5);
                // Get stash index directly from CommitInfo
                commitInfo.stashIndex().ifPresent(this::applyStash);
                if (commitInfo.stashIndex().isEmpty()) {
                    logger.warn("Apply Stash action triggered on a non-stash commit: {}", commitInfo.id());
                }
            }
        });
        dropStashCommitItem.addActionListener(e -> {
            int row = commitsTable.getSelectedRow();
            if (row != -1) {
                // Get CommitInfo from the hidden column 5
                ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(row, 5);
                // Get stash index directly from CommitInfo
                commitInfo.stashIndex().ifPresent(this::dropStash);
                 if (commitInfo.stashIndex().isEmpty()) {
                     logger.warn("Drop Stash action triggered on a non-stash commit: {}", commitInfo.id());
                 }
            }
        });

        // The duplicated old mouse listener and action listener code has been removed.
        // The correct, new listeners are already present earlier in the method.
        commitsPanel.add(new JScrollPane(commitsTable), BorderLayout.CENTER);

        // Buttons below commits table
        JPanel commitsPanelButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        pullButton = new JButton("Pull");
        pullButton.setToolTipText("Pull changes from remote repository");
        pullButton.setEnabled(false);
        pullButton.addActionListener(e -> pullBranch());
        commitsPanelButtons.add(pullButton);

        pushButton = new JButton("Push");
        pushButton.setToolTipText("Push commits to remote repository");
        pushButton.setEnabled(false);
        pushButton.addActionListener(e -> pushBranch());
        commitsPanelButtons.add(pushButton);
        commitsPanel.add(commitsPanelButtons, BorderLayout.SOUTH);

        // ============ Changes Panel (right ~25%) ============

        JPanel changesPanel = new JPanel(new BorderLayout());
        changesPanel.setBorder(BorderFactory.createTitledBorder("Changes"));

        // Panel for Revision Label and Text Area
        JPanel revisionDisplayPanel = new JPanel(new BorderLayout(5, 0)); // 5px hgap
        revisionTextLabel = new JLabel("Revision:");
        revisionIdTextArea = new JTextArea("N/A", 1, 1); // Min 1 row, 1 col (will expand)
        revisionIdTextArea.setEditable(false);
        revisionIdTextArea.setLineWrap(true);
        revisionIdTextArea.setWrapStyleWord(true);
        revisionIdTextArea.setBackground(UIManager.getColor("Label.background"));
        revisionIdTextArea.setForeground(UIManager.getColor("Label.foreground"));
        revisionIdTextArea.setFont(UIManager.getFont("Label.font"));
        revisionIdTextArea.setBorder(BorderFactory.createEmptyBorder(2,2,2,2)); // Small padding like a label

        revisionDisplayPanel.add(revisionTextLabel, BorderLayout.WEST);
        revisionDisplayPanel.add(revisionIdTextArea, BorderLayout.CENTER);
        changesPanel.add(revisionDisplayPanel, BorderLayout.NORTH);

        changesRootNode = new DefaultMutableTreeNode("Changes");
        changesTreeModel = new DefaultTreeModel(changesRootNode);
        changesTree = new JTree(changesTreeModel);
        changesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        changesPanel.add(new JScrollPane(changesTree), BorderLayout.CENTER);

        // Context menu on changes tree
        JPopupMenu changesContextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(changesContextMenu);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(changesContextMenu);
                }
            });
        }
        JMenuItem addFileToContextItem = new JMenuItem("Capture Diff");
        JMenuItem compareFileWithLocalItem = new JMenuItem("Compare with Local");
        JMenuItem viewFileAtRevisionItem = new JMenuItem("View File at Revision");
        JMenuItem viewDiffItem = new JMenuItem("View Diff");
        JMenuItem viewHistoryItem = new JMenuItem("View History");
        JMenuItem editFileItem = new JMenuItem("Edit File(s)");
        JMenuItem comparePrevWithLocalItem = new JMenuItem("Compare Previous with Local");

        changesContextMenu.add(addFileToContextItem);
        changesContextMenu.add(editFileItem);
        changesContextMenu.addSeparator();
        changesContextMenu.add(viewHistoryItem);
        changesContextMenu.addSeparator();
        changesContextMenu.add(viewFileAtRevisionItem);
        changesContextMenu.add(viewDiffItem);
        changesContextMenu.add(compareFileWithLocalItem);
        changesContextMenu.add(comparePrevWithLocalItem);

        changesTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleChangesPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleChangesPopup(e);
            }

            private void handleChangesPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = changesTree.getRowForLocation(e.getX(), e.getY());
                    if (row >= 0) {
                        if (!changesTree.isRowSelected(row)) {
                            changesTree.setSelectionRow(row);
                        }
                    }
                    TreePath[] paths = changesTree.getSelectionPaths();
                    boolean hasFileSelection = (paths != null && paths.length > 0 && hasFileNodesSelected(paths));
                    int[] selRows = commitsTable.getSelectedRows();
                    boolean isSingleCommit = (selRows.length == 1);

                    boolean singleFileSelected = (paths != null && paths.length == 1 && hasFileSelection);

                    viewHistoryItem.setEnabled(singleFileSelected);
                    addFileToContextItem.setEnabled(hasFileSelection);
                    editFileItem.setEnabled(hasFileSelection);
                    viewFileAtRevisionItem.setEnabled(singleFileSelected && isSingleCommit);
                    viewDiffItem.setEnabled(singleFileSelected && isSingleCommit);
                    compareFileWithLocalItem.setEnabled(singleFileSelected && isSingleCommit);
                    comparePrevWithLocalItem.setEnabled(singleFileSelected && isSingleCommit);

                    changesContextMenu.show(changesTree, e.getX(), e.getY());
                }
            }
        });
        changesTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    var path = changesTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        int[] selRows = commitsTable.getSelectedRows();
                        if (selRows.length == 1 && isFileNode(path)) {
                            // Get CommitInfo from hidden column 5
                            ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selRows[0], 5);
                            String commitId = commitInfo.id();
                            String filePath = getFilePathFromTreePath(path);
                            GitUiUtil.showFileHistoryDiff(contextManager, chrome, commitId, contextManager.toFile(filePath));
                        }
                    }
                }
            }
        });

        addFileToContextItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                List<String> selectedFilePaths = getSelectedFilePaths(paths);
                if (!selectedFilePaths.isEmpty()) {
                    int[] selRows = commitsTable.getSelectedRows();
                    if (selRows.length >= 1) {
                        // Extract commit IDs from CommitInfo objects
                        var sorted = selRows.clone();
                        Arrays.sort(sorted);
                        String firstCommitId = ((ICommitInfo) commitsTableModel.getValueAt(sorted[0], 5)).id();
                        String lastCommitId = ((ICommitInfo) commitsTableModel.getValueAt(sorted[sorted.length - 1], 5)).id();

                        // Convert file paths to ProjectFile objects
                        List<ProjectFile> projectFiles = selectedFilePaths.stream()
                                .map(fp -> new ProjectFile(contextManager.getRoot(), fp))
                                .toList();

                        GitUiUtil.addFilesChangeToContext(contextManager, chrome, firstCommitId,
                                                          lastCommitId, projectFiles);
                    }
                }
            }
        });
        compareFileWithLocalItem.addActionListener(e -> {
            var paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length == 1) {
                String filePath = getFilePathFromTreePath(paths[0]);
                int[] selRows = commitsTable.getSelectedRows();
                if (selRows.length == 1) {
                    // Get CommitInfo from hidden column 5
                    ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selRows[0], 5);
                    String commitId = commitInfo.id();
                    GitUiUtil.showDiffVsLocal(contextManager, chrome,
                                              commitId, filePath, /*useParent=*/ false);
                }
            }
        });
        comparePrevWithLocalItem.addActionListener(e -> {
            var paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length == 1) {
                String filePath = getFilePathFromTreePath(paths[0]);
                int[] selRows = commitsTable.getSelectedRows();
                if (selRows.length == 1) {
                    // Get CommitInfo from hidden column 5
                    ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selRows[0], 5);
                    String commitId = commitInfo.id();
                    GitUiUtil.showDiffVsLocal(contextManager, chrome,
                                              commitId, filePath, /*useParent=*/ true);
                }
            }
        });
        viewFileAtRevisionItem.addActionListener(e -> {
            var paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length == 1) {
                String filePath = getFilePathFromTreePath(paths[0]);
                int[] selRows = commitsTable.getSelectedRows();
                if (selRows.length == 1) {
                    // Get CommitInfo from hidden column 5
                    ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selRows[0], 5);
                    String commitId = commitInfo.id();
                    GitUiUtil.viewFileAtRevision(contextManager, chrome, commitId, filePath);
                }
            }
        });
        viewDiffItem.addActionListener(e -> {
            var paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length == 1) {
                int[] selRows = commitsTable.getSelectedRows();
                if (selRows.length == 1 && isFileNode(paths[0])) {
                    // Get CommitInfo from hidden column 5
                    ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(selRows[0], 5);
                    String commitId = commitInfo.id();
                    String filePath = getFilePathFromTreePath(paths[0]);
                    GitUiUtil.showFileHistoryDiff(contextManager, chrome, commitId, contextManager.toFile(filePath));
                }
            }
        });
        viewHistoryItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            if (paths != null) {
                var selectedFiles = getSelectedFilePaths(paths);
                for (String fp : selectedFiles) {
                    var repoFile = new ProjectFile(contextManager.getRoot(), fp);
                    chrome.getGitPanel().addFileHistoryTab(repoFile);
                }
            }
        });
        editFileItem.addActionListener(e -> {
            var paths = changesTree.getSelectionPaths();
            if (paths != null) {
                var selectedFiles = getSelectedFilePaths(paths);
                for (var fp : selectedFiles) {
                    GitUiUtil.editFile(contextManager, fp);
                }
            }
        });

        // ============ Search Panel (rightmost ~10%) ============

        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(BorderFactory.createTitledBorder("Search"));
        searchField = new JTextArea(3, 10);
        searchField.setLineWrap(true);
        searchField.setWrapStyleWord(true);
        searchPanel.add(new JScrollPane(searchField), BorderLayout.CENTER);

        JPanel searchButtonPanel = new JPanel();
        searchButtonPanel.setLayout(new BoxLayout(searchButtonPanel, BoxLayout.Y_AXIS));

        JButton textSearchButton = new JButton("Text Search");
        textSearchButton.addActionListener(e -> {
            String query = searchField.getText().trim();
            if (!query.isEmpty()) {
                searchCommits(query);
            }
        });
        textSearchButton.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, textSearchButton.getPreferredSize().height)
        );

        JButton resetButton = new JButton("Reset");
        resetButton.addActionListener(e -> {
            searchField.setText("");
            int sel = branchTable.getSelectedRow();
            if (sel != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(sel, 1);
                updateCommitsForBranch(branchDisplay);
            }
        });
        resetButton.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, resetButton.getPreferredSize().height)
        );

        searchButtonPanel.add(Box.createVerticalStrut(5));
        searchButtonPanel.add(textSearchButton);
        searchButtonPanel.add(Box.createVerticalStrut(5));
        searchButtonPanel.add(resetButton);
        searchButtonPanel.add(Box.createVerticalStrut(5));
        searchPanel.add(searchButtonPanel, BorderLayout.SOUTH);

        // ============ Add sub-panels to logPanel with GridBag ============

        // Set a nominal preferred size for direct children of logPanel
        // to make GridBagLayout distribute space primarily based on weightx.
        Dimension nominalPreferredSize = new Dimension(1, 1);
        branchesPanel.setPreferredSize(nominalPreferredSize);
        commitsPanel.setPreferredSize(nominalPreferredSize);
        changesPanel.setPreferredSize(nominalPreferredSize);
        searchPanel.setPreferredSize(nominalPreferredSize);

        constraints.gridx = 0; // branches
        constraints.weightx = 0.15;
        logPanel.add(branchesPanel, constraints);

        constraints.gridx = 1; // commits
        constraints.weightx = 0.50;
        logPanel.add(commitsPanel, constraints);

        constraints.gridx = 2; // changes
        constraints.weightx = 0.25;
        logPanel.add(changesPanel, constraints);

        constraints.gridx = 3; // search
        constraints.weightx = 0.10;
        logPanel.add(searchPanel, constraints);

        // Listeners for branch table
        branchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && branchTable.getSelectedRow() != -1) {
                String branchName = (String) branchTableModel.getValueAt(branchTable.getSelectedRow(), 1);
                remoteBranchTable.clearSelection();
                updateCommitsForBranch(branchName);
            }
        });
        remoteBranchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && remoteBranchTable.getSelectedRow() != -1) {
                String branchName = (String) remoteBranchTableModel.getValueAt(remoteBranchTable.getSelectedRow(), 0);
                branchTable.clearSelection();
                updateCommitsForBranch(branchName);
                pushButton.setVisible(true);
            }
        });

        // Local branch context menu
        JPopupMenu branchContextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(branchContextMenu);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(branchContextMenu);
                }
            });
        }
        JMenuItem checkoutItem = new JMenuItem("Checkout");
        JMenuItem newBranchItem = new JMenuItem("New Branch From This");
        JMenuItem mergeItem = new JMenuItem("Merge into HEAD");
        JMenuItem renameItem = new JMenuItem("Rename");
        JMenuItem deleteItem = new JMenuItem("Delete");
        branchContextMenu.add(checkoutItem);
        branchContextMenu.add(newBranchItem);
        branchContextMenu.add(mergeItem);
        branchContextMenu.add(renameItem);
        branchContextMenu.add(deleteItem);

        branchTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleBranchPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleBranchPopup(e);
            }

            private void handleBranchPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = branchTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        if (!branchTable.isRowSelected(row)) {
                            branchTable.setRowSelectionInterval(row, row);
                        }
                    }
                    checkBranchContextMenuState(branchContextMenu);
                    branchContextMenu.show(branchTable, e.getX(), e.getY());
                }
            }
        });

        checkoutItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                checkoutBranch(branchDisplay);
            }
        });
        newBranchItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String sourceBranch = (String) branchTableModel.getValueAt(selectedRow, 1);
                createNewBranchFrom(sourceBranch);
            }
        });
        mergeItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                mergeBranchIntoHead(branchDisplay);
            }
        });
        renameItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                if (branchDisplay.startsWith("Local: ")) {
                    String branchName = branchDisplay.substring("Local: ".length());
                    renameBranch(branchName);
                }
            }
        });
        deleteItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                var branchName = (String) branchTableModel.getValueAt(selectedRow, 1);
                deleteBranch(branchName);
            }
        });
    }

    /**
     * Returns the file path from a node in the "Changes" tree.
     * If the node is a child of the root, it's treated as a directory node,
     * else it is considered a file node with parent = directory node.
     */
    private String getFilePathFromTreePath(TreePath path)
    {
        if (path == null) {
            return "";
        }
        // The node for the path component
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        // If it's the root (changesRootNode) or no parent, there's no valid file path
        if (node == changesRootNode || node.getParent() == null) {
            return "";
        }

        // If the parent is the root node, then 'node' is a top-level directory
        if (node.getParent() == changesRootNode) {
            // Node itself is probably a directory name; not a file
            return node.getUserObject().toString();
        }

        // Otherwise, node is a file; parent is a directory node
        DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) node.getParent();
        String dirPath = parentNode.getUserObject().toString();
        String fileName = node.getUserObject().toString();

        return dirPath.isEmpty() ? fileName : dirPath + "/" + fileName;
    }

    /**
     * Returns true if the last component of this path is a file node (i.e.,
     * its parent is not the root).
     */
    private boolean isFileNode(TreePath path)
    {
        if (path == null) {
            return false;
        }
        var node = (DefaultMutableTreeNode) path.getLastPathComponent();
        return (node.getParent() != null && node.getParent() != changesRootNode);
    }

    /**
     * Update the branch list (local + remote), attempting to preserve the selection.
     * If the previously selected branch no longer exists, it selects the current git branch.
     */
    public void update() {
        // Use invokeAndWait with a Runnable and an external holder for the result
        String previouslySelectedBranch = SwingUtil.runOnEdt(() -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                // Ensure the row index is still valid before accessing model
                if (selectedRow < branchTableModel.getRowCount()) {
                    return (String) branchTableModel.getValueAt(selectedRow, 1);
                }
            }
            return null;
        }, null);

        contextManager.submitBackgroundTask("Fetching git branches", () -> {
            try {
                String currentGitBranch = getRepo().getCurrentBranch(); // Get current branch from Git
                List<String> localBranches = getRepo().listLocalBranches();
                List<String> remoteBranches = getRepo().listRemoteBranches();

                // Prepare data rows off the EDT
                List<Object[]> localBranchRows = new ArrayList<>();
                int targetSelectionIndex = -1; // Index to select after update
                String targetBranchToSelect = previouslySelectedBranch; // Prioritize previous selection

                // Add virtual "stashes" entry first if stashes exist
                boolean hasStashes = false;
                try {
                    // Just check if the list is non-empty, avoid fetching full info yet
                    hasStashes = !getRepo().listStashes().isEmpty();
                    if (hasStashes) {
                        localBranchRows.add(new Object[]{"", "stashes"});
                        if ("stashes".equals(targetBranchToSelect)) {
                            targetSelectionIndex = 0; // If 'stashes' was selected, mark its index
                        }
                    }
                } catch (GitAPIException e) {
                    logger.warn("Could not check for stashes existence", e);
                }

                // Process actual local branches
                for (String branch : localBranches) {
                    String checkmark = branch.equals(currentGitBranch) ? "✓" : "";
                    localBranchRows.add(new Object[]{checkmark, branch});
                    // If this branch was the target (previously selected) and we haven't found it yet
                    if (branch.equals(targetBranchToSelect) && targetSelectionIndex == -1) {
                        targetSelectionIndex = localBranchRows.size() - 1;
                    }
                }

                // If the previously selected branch wasn't found (or nothing was selected),
                // fall back to the current Git branch.
                if (targetSelectionIndex == -1) {
                    targetBranchToSelect = currentGitBranch; // Update target
                    // Find the index of the current Git branch
                    for (int i = 0; i < localBranchRows.size(); i++) {
                        // Compare with the branch name in column 1
                        if (localBranchRows.get(i)[1].equals(targetBranchToSelect)) {
                            targetSelectionIndex = i;
                            break;
                        }
                    }
                }

                List<Object[]> remoteBranchRows = remoteBranches.stream()
                        .map(branch -> new Object[]{branch})
                        .toList();

                final int finalTargetSelectionIndex = targetSelectionIndex;
                final String finalSelectedBranchName = targetBranchToSelect; // The branch name we actually selected/targeted

                SwingUtilities.invokeLater(() -> {
                    branchTableModel.setRowCount(0);
                    remoteBranchTableModel.setRowCount(0);

                    for (var rowData : localBranchRows) {
                        branchTableModel.addRow(rowData);
                    }
                    for (var rowData : remoteBranchRows) {
                        remoteBranchTableModel.addRow(rowData);
                    }

                    if (finalTargetSelectionIndex >= 0 && finalSelectedBranchName != null) {
                        branchTable.setRowSelectionInterval(finalTargetSelectionIndex, finalTargetSelectionIndex);
                        // Ensure selected branch is visible by scrolling to it
                        branchTable.scrollRectToVisible(branchTable.getCellRect(finalTargetSelectionIndex, 0, true));
                        updateCommitsForBranch(finalSelectedBranchName); // Load commits for the branch we ended up selecting
                    } else {
                        // This case might happen if the repo becomes empty or only has remote branches initially
                        logger.warn("Could not select any local branch (target: {}, current git: {}). Clearing commits.",
                                    previouslySelectedBranch, currentGitBranch);
                        commitsTableModel.setRowCount(0);
                        changesRootNode.removeAllChildren();
                        changesTreeModel.reload();
                        revisionTextLabel.setText("Revision:");
                        revisionIdTextArea.setText("N/A");
                        pullButton.setEnabled(false);
                        pushButton.setEnabled(false);
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching branches", e);
                SwingUtilities.invokeLater(() -> {
                    branchTableModel.setRowCount(0);
                    branchTableModel.addRow(new Object[]{"", "Error fetching branches: " + e.getMessage()});
                    remoteBranchTableModel.setRowCount(0);
                    commitsTableModel.setRowCount(0);
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();
                    revisionTextLabel.setText("Revision:");
                    revisionIdTextArea.setText("N/A");
                });
            }
            return null;
        });
    }

    /**
     * Update commits list for the given branch and highlights unpushed commits if applicable.
     */
    private void updateCommitsForBranch(String branchName) {
        contextManager.submitBackgroundTask("Fetching commits for " + branchName, () -> {
            try {
                List<CommitInfo> commits;
                Set<String> unpushedCommitIds = new HashSet<>();
                boolean canPush = false;
                boolean canPull = false;

                // Special handling for stashes virtual branch
                if ("stashes".equals(branchName)) {
                    try {
                        // Directly call listStashes which now returns List<CommitInfo>
                        commits = getRepo().listStashes();
                    } catch (GitAPIException e) {
                        logger.error("Error fetching stashes", e);
                        commits = List.of(); // Ensure commits is initialized
                    }
                } else {
                    // Normal branch handling
                    commits = getRepo().listCommitsDetailed(branchName);
                    var isLocalBranch = branchName.equals(getRepo().getCurrentBranch()) || !branchName.contains("/");
                    if (isLocalBranch) {
                        canPull = getRepo().hasUpstreamBranch(branchName);
                        try {
                            unpushedCommitIds.addAll(getRepo().getUnpushedCommitIds(branchName));
                            canPush = !unpushedCommitIds.isEmpty() && canPull; // Can only push if upstream exists
                        } catch (GitAPIException e) {
                            logger.warn("Could not check for unpushed commits: {}", e.getMessage());
                        }
                    }
                }

                boolean finalCanPush = canPush;
                boolean finalCanPull = canPull;
                int unpushedCount = unpushedCommitIds.size();
                List<CommitInfo> finalCommits = commits; // Keep final reference

                // Prepare commit rows off EDT
                List<Object[]> commitRows = new ArrayList<>();
                if (!finalCommits.isEmpty()) {
                    java.time.LocalDate today = java.time.LocalDate.now();
                    for (CommitInfo commit : finalCommits) {
                        String formattedDate = formatCommitDate(commit.date(), today);
                        boolean isUnpushed = unpushedCommitIds.contains(commit.id());
                        commitRows.add(new Object[]{
                                commit.message(),
                                commit.author(),
                                formattedDate,
                                commit.id(),
                                isUnpushed,
                                commit // Add the CommitInfo object itself to the hidden column
                        });
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();

                    pullButton.setEnabled(finalCanPull);
                    pullButton.setToolTipText(finalCanPull
                                              ? "Pull changes from remote for " + branchName
                                              : "No upstream branch configured for " + branchName);
                    pullButton.setVisible(!branchName.equals("stashes")); // Hide pull button for stashes

                    pushButton.setEnabled(finalCanPush);
                    pushButton.setToolTipText(finalCanPush
                                              ? "Push " + unpushedCount + " commit(s) to remote"
                                              : "No unpushed commits or no upstream branch configured");
                    pushButton.setVisible(!branchName.equals("stashes")); // Hide push button for stashes

                    if (commitRows.isEmpty()) {
                        revisionTextLabel.setText("Revision:");
                        revisionIdTextArea.setText("N/A");
                        return;
                    }

                    for (Object[] rowData : commitRows) {
                        commitsTableModel.addRow(rowData);
                    }

                    // Fit column widths for author and date
                    TableUtils.fitColumnWidth(commitsTable, 1); // Author column
                    TableUtils.fitColumnWidth(commitsTable, 2); // Date column

                    if (commitsTableModel.getRowCount() > 0) {
                        commitsTable.setRowSelectionInterval(0, 0);
                        // Listener will handle updates to revision display and changes tree
                    } else {
                        // Ensure changes area and revision display are cleared if no commits
                        changesRootNode.removeAllChildren();
                        changesTreeModel.reload();
                        revisionTextLabel.setText("Revision:");
                        revisionIdTextArea.setText("N/A");
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching commits for branch: " + branchName, e);
                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(new Object[]{
                            "Error fetching commits: " + e.getMessage(), "", "", ""
                    });
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();
                    revisionTextLabel.setText("Revision:");
                    revisionIdTextArea.setText("N/A");
                });
            }
            return null;
        });
    }

    /**
     * Fills the "Changes" tree with files from the selected commit objects.
     */
    private void updateChangesForCommits(List<ICommitInfo> commits) {
        // The revision display (revisionTextLabel, revisionIdTextArea) is updated by the ListSelectionListener.
        if (commits == null || commits.isEmpty()) {
            changesRootNode.removeAllChildren();
            changesTreeModel.reload();
            return;
        }

        // Label update logic removed from here, handled by listener.

        contextManager.submitBackgroundTask("Fetching changes for commits", () -> {
            try {
                // Aggregate changed files from all selected commit objects
                var allChangedFiles = new HashSet<ProjectFile>();
                for (ICommitInfo commit : commits) {
                    // Use the changedFiles() method from the interface
                    allChangedFiles.addAll(commit.changedFiles());
                }

                // Update UI on EDT
                SwingUtilities.invokeLater(() -> {
                    changesRootNode.removeAllChildren();
                    if (allChangedFiles.isEmpty()) {
                        changesTreeModel.reload();
                        return;
                    }

                    Map<Path, List<String>> filesByDir = new HashMap<>();
                    for (var file : allChangedFiles) {
                        filesByDir.computeIfAbsent(file.getParent(), k -> new ArrayList<>()).add(file.getFileName());
                    }

                    for (var entry : filesByDir.entrySet()) {
                        var dirPath = entry.getKey();
                        var files = entry.getValue();

                        DefaultMutableTreeNode dirNode;
                        if (dirPath.equals(Path.of(""))) {
                            dirNode = changesRootNode;
                        } else {
                            dirNode = new DefaultMutableTreeNode(dirPath);
                            changesRootNode.add(dirNode);
                        }
                        for (var f : files) {
                            dirNode.add(new DefaultMutableTreeNode(f));
                        }
                    }
                    changesTreeModel.reload();
                    expandAllNodes(changesTree, 0, changesTree.getRowCount());
                });
            } catch (Exception e) {
                logger.error("Error fetching changes for multiple commits", e);
                SwingUtilities.invokeLater(() -> {
                    changesRootNode.removeAllChildren();
                    changesRootNode.add(new DefaultMutableTreeNode("Error: " + e.getMessage()));
                    changesTreeModel.reload();
                });
            }
            return null;
        });
    }

    /**
     * Soft reset HEAD to the specified commit, keeping changes in working directory.
     */
    private void softResetToCommit(String commitId, String commitMessage) {
        contextManager.submitUserTask("Soft resetting to commit: " + commitId, () -> {
            String oldHeadId = getOldHeadId();
            try {
                getRepo().softReset(commitId);
                SwingUtilities.invokeLater(() -> {
                    String oldHeadShort = oldHeadId.length() >= 7 ? oldHeadId.substring(0, 7) : oldHeadId;
                    String newHeadShort = commitId.length() >= 7 ? commitId.substring(0, 7) : commitId;
                    chrome.systemOutput("Soft reset from " + oldHeadShort + " to " + newHeadShort + ": " + commitMessage);

                    // Refresh uncommitted files or anything else if needed
                    // For example, if you have a method in GitPanel to update uncommitted:
                    // (You’d call something like gitPanelInstance.updateSuggestCommitButton();)

                    // Refresh the current branch's commits
                    int branchRow = branchTable.getSelectedRow();
                    if (branchRow != -1) {
                        String branchDisplay = (String) branchTableModel.getValueAt(branchRow, 1);
                        updateCommitsForBranch(branchDisplay);
                    }
                });
            } catch (GitAPIException e) {
                logger.error("Error performing soft reset to commit: {}", commitId, e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error performing soft reset: " + e.getMessage()));
            }
        });
    }

    /**
     * Pull changes from the remote for the current branch.
     */
    private void pullBranch() {
        int selectedRow = branchTable.getSelectedRow();
        if (selectedRow == -1) return;
        String branchName = (String) branchTableModel.getValueAt(selectedRow, 1);

        // Verify it's a local branch and has an upstream
        boolean isLocalBranch = !branchName.contains("/") && !"stashes".equals(branchName);
        if (!isLocalBranch) {
            logger.warn("Attempted to pull non-local branch: {}", branchName);
            return;
        }

        contextManager.submitBackgroundTask("Checking upstream for " + branchName, () -> {
            boolean hasUpstream = getRepo().hasUpstreamBranch(branchName);
            if (!hasUpstream) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        this,
                        "The selected local branch '" + branchName + "' has no upstream branch configured.\n" +
                                "You need to set an upstream (e.g., using `git branch --set-upstream-to=origin/" + branchName + "`) " +
                                "before you can pull.",
                        "No Upstream Branch",
                        JOptionPane.WARNING_MESSAGE));
                return null;
            }

            // Proceed with pull
            contextManager.submitUserTask("Pulling changes for " + branchName, () -> {
                try {
                    getRepo().pull();
                    SwingUtilities.invokeLater(() -> {
                        chrome.systemOutput("Successfully pulled changes for " + branchName);
                        updateCommitsForBranch(branchName); // Refresh commits after pull
                        // Potentially refresh other parts of the UI if needed, e.g., uncommitted files
                        chrome.getGitPanel().updateCommitPanel();
                    });
                } catch (GitAPIException e) {
                    logger.error("Error pulling branch: {}", branchName, e);
                    SwingUtilities.invokeLater(() -> chrome.toolErrorRaw("Error pulling changes: " + e.getMessage()));
                }
            });
            return null;
        });
    }

    /**
     * Revert a single commit.
     */
    private void revertCommit(String commitId) {
        contextManager.submitUserTask("Reverting commit", () -> {
            try {
                getRepo().revertCommit(commitId);
                chrome.systemOutput("Commit was successfully reverted.");

                int branchRow = branchTable.getSelectedRow();
                if (branchRow != -1) {
                    String branchDisplay = (String) branchTableModel.getValueAt(branchRow, 1);
                    updateCommitsForBranch(branchDisplay);
                }
            } catch (GitAPIException e) {
                chrome.toolErrorRaw("Error reverting commit: " + e.getMessage());
            }
        });
    }

    /**
     * Push the current branch to remote.
     */
    private void pushBranch() {
        int selectedRow = branchTable.getSelectedRow();
        if (selectedRow == -1) return;
        String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);

        contextManager.submitBackgroundTask("Pushing branch " + branchDisplay, () -> {
            try {
                getRepo().push();
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Successfully pushed " + branchDisplay + " to remote");
                    updateCommitsForBranch(branchDisplay);
                });
            } catch (GitAPIException e) {
                logger.error("Error pushing branch: {}", branchDisplay, e);
                SwingUtilities.invokeLater(() -> chrome.toolErrorRaw(e.getMessage()));
            }
            return null;
        });
    }

    /**
     * Check out a branch (local or remote).
     */
    private void checkoutBranch(String branchName) {
        contextManager.submitUserTask("Checking out branch: " + branchName, () -> {
            try {
                if (branchName.startsWith("origin/") || branchName.contains("/")) {
                    getRepo().checkoutRemoteBranch(branchName);
                    chrome.systemOutput("Created local tracking branch for " + branchName);
                } else {
                    getRepo().checkout(branchName);
                }
                update();
            } catch (GitAPIException e) {
                logger.error("Error checking out branch: {}", branchName, e);
                chrome.toolErrorRaw(e.getMessage());
            }
        });
    }

    /**
     * Merge a branch into HEAD.
     */
    private void mergeBranchIntoHead(String branchName) {
        contextManager.submitUserTask("Merging branch: " + branchName, () -> {
            try {
                MergeResult mergeResult = getRepo().mergeIntoHead(branchName);
                MergeResult.MergeStatus status = mergeResult.getMergeStatus();

                if (status.isSuccessful()) {
                    if (status == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                        chrome.systemOutput("Branch '" + branchName + "' is already up-to-date with HEAD.");
                    } else {
                        chrome.systemOutput("Branch '" + branchName + "' was successfully merged into HEAD.");
                    }
                } else if (status == MergeResult.MergeStatus.CONFLICTING) {
                    String conflictingFiles = mergeResult.getConflicts().keySet().stream()
                            .map(s -> "  - " + s)
                            .collect(Collectors.joining("\n"));
                    chrome.toolErrorRaw("Merge conflicts detected for branch '" + branchName + "'.\n" +
                                        "Please resolve conflicts manually and then commit.\n" +
                                        "Conflicting files:\n" + conflictingFiles);
                } else {
                    // For other non-successful statuses like FAILED, ABORTED etc.
                    chrome.toolErrorRaw("Merge of branch '" + branchName + "' failed with error: " + status);
                }
                update(); // Refresh UI to reflect new state (merged, conflicting, or failed)
            } catch (GitAPIException e) {
                logger.error("Error merging branch: {}", branchName, e);
                chrome.toolErrorRaw("Error merging branch '" + branchName + "': " + e.getMessage());
                update(); // Refresh UI to show current state after error
            }
        });
    }

    /**
     * Creates a new branch from an existing one and checks it out.
     */
    private void createNewBranchFrom(String sourceBranch) {
        String newName = JOptionPane.showInputDialog(
                this,
                "Enter name for new branch from '" + sourceBranch + "':",
                "Create New Branch",
                JOptionPane.QUESTION_MESSAGE
        );
        if (newName != null && !newName.trim().isEmpty()) {
            contextManager.submitUserTask("Creating new branch: " + newName + " from " + sourceBranch, () -> {
                try {
                    getRepo().createAndCheckoutBranch(newName, sourceBranch);
                    update();
                    chrome.systemOutput("Created and checked out new branch '" + newName + "' from '" + sourceBranch + "'");
                } catch (GitAPIException e) {
                    logger.error("Error creating new branch from {}: {}", sourceBranch, e);
                    chrome.toolErrorRaw("Error creating new branch: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Rename a local branch.
     */
    private void renameBranch(String branchName) {
        String newName = JOptionPane.showInputDialog(
                this,
                "Enter new name for branch '" + branchName + "':",
                "Rename Branch",
                JOptionPane.QUESTION_MESSAGE
        );
        if (newName != null && !newName.trim().isEmpty()) {
            contextManager.submitUserTask("Renaming branch: " + branchName, () -> {
                try {
                    getRepo().renameBranch(branchName, newName);
                    SwingUtilities.invokeLater(this::update);
                    chrome.systemOutput("Branch '" + branchName + "' renamed to '" + newName + "' successfully.");
                } catch (GitAPIException e) {
                    logger.error("Error renaming branch: {}", branchName, e);
                    chrome.toolErrorRaw("Error renaming branch: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Delete a local branch (with checks for merges).
     */
    private void deleteBranch(String branchName) {
        contextManager.submitUserTask("Checking branch merge status", () -> {
            try {
                boolean isMerged = getRepo().isBranchMerged(branchName);
                SwingUtilities.invokeLater(() -> {
                    if (isMerged) {
                        int result = JOptionPane.showConfirmDialog(
                                this,
                                "Are you sure you want to delete branch '" + branchName + "'?",
                                "Delete Branch",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE
                        );
                        if (result == JOptionPane.YES_OPTION) {
                            performBranchDeletion(branchName, false);
                        }
                    } else {
                        Object[] options = {"Force Delete", "Cancel"};
                        int result = JOptionPane.showOptionDialog(
                                this,
                                "Branch '" + branchName + "' is not fully merged.\n" +
                                        "Changes on this branch will be lost if deleted.\n" +
                                        "Do you want to force delete it?",
                                "Unmerged Branch",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE,
                                null,
                                options,
                                options[1]
                        );
                        if (result == 0) { // Force Delete
                            performBranchDeletion(branchName, true);
                        }
                    }
                });
            } catch (GitAPIException e) {
                logger.error("Error checking branch merge status: {}", branchName, e);
                chrome.toolErrorRaw("Error checking branch status: " + e.getMessage());
            }
        });
    }

    /**
     * Perform the actual branch deletion with or without force.
     */
    private void performBranchDeletion(String branchName, boolean force) {
        contextManager.submitUserTask("Deleting branch: " + branchName, () -> {
            try {
                logger.debug("Initiating {} deletion for branch: {}",
                             force ? "force" : "normal", branchName);

                // Check if branch exists before trying to delete
                List<String> localBranches = getRepo().listLocalBranches();
                if (!localBranches.contains(branchName)) {
                    chrome.toolErrorRaw("Cannot delete branch '" + branchName + "' - it doesn't exist locally");
                    return;
                }

                // Check if it's the current branch
                String currentBranch = getRepo().getCurrentBranch();
                if (branchName.equals(currentBranch)) {
                    logger.warn("Cannot delete branch '{}' - it is the currently checked out branch", branchName);
                    chrome.toolErrorRaw("Cannot delete the current branch. Please checkout a different branch first.");
                    return;
                }

                if (force) {
                    getRepo().forceDeleteBranch(branchName);
                } else {
                    getRepo().deleteBranch(branchName);
                }

                SwingUtilities.invokeLater(this::update);
                chrome.systemOutput("Branch '" + branchName + "' " + (force ? "force " : "") + "deleted successfully.");
            } catch (GitAPIException e) {
                chrome.toolErrorRaw("Error deleting branch '" + branchName + "': " + e.getMessage());
            }
        });
    }

    /**
     * Text search across all commits. Shows results in the commits table.
     */
    private void searchCommits(String query) {
        contextManager.submitUserTask("Searching commits for: " + query, () -> {
            try {
                List<CommitInfo> searchResults = getRepo().searchCommits(query);

                // Prepare rows off EDT
                List<Object[]> commitRows = new ArrayList<>();
                java.time.LocalDate today = java.time.LocalDate.now(); // needed for formatting
                for (CommitInfo commit : searchResults) {
                    String formattedDate = formatCommitDate(commit.date(), today);
                    commitRows.add(new Object[]{
                            commit.message(),
                            commit.author(),
                            formattedDate, // use formatted date
                            commit.id(),
                            false, // Search results are never 'unpushed' in this context
                            commit // Add CommitInfo object
                    });
                }

                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();

                    if (commitRows.isEmpty()) {
                        revisionTextLabel.setText("Revision:");
                        revisionIdTextArea.setText("N/A");
                        chrome.systemOutput("No commits found matching: " + query);
                        return;
                    }

                    for (Object[] rowData : commitRows) {
                        commitsTableModel.addRow(rowData);
                    }

                    chrome.systemOutput("Found " + commitRows.size() + " commits matching: " + query);
                    if (commitsTableModel.getRowCount() > 0) {
                        // Fit column widths
                        TableUtils.fitColumnWidth(commitsTable, 1); // Author
                        TableUtils.fitColumnWidth(commitsTable, 2); // Date
                        commitsTable.setRowSelectionInterval(0, 0);
                        // Listener will handle updates to revision display and changes tree
                    } else {
                        // Ensure changes area and revision display are cleared if no search results
                        changesRootNode.removeAllChildren();
                        changesTreeModel.reload();
                        revisionTextLabel.setText("Revision:");
                        revisionIdTextArea.setText("N/A");
                    }
                });
            } catch (Exception e) {
                logger.error("Error searching commits: {}", query, e);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolErrorRaw("Error searching commits: " + e.getMessage());
                    revisionTextLabel.setText("Revision:");
                    revisionIdTextArea.setText("N/A");
                });
            }
        });
    }

    // ==================================================================
    // Helper methods
    // ==================================================================

    private String getShortId(String commitId) {
        return commitId != null && commitId.length() >= 7 ? commitId.substring(0, 7) : commitId;
    }

    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }

    private String getOldHeadId() {
        try {
            return getRepo().getCurrentCommitId();
        } catch (GitAPIException e) {
            return "unknown";
        }
    }

    /**
     * Recursively expand all nodes in the JTree.
     */
    private void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; i++) {
            tree.expandRow(i);
        }
        if (tree.getRowCount() > rowCount) {
            expandAllNodes(tree, rowCount, tree.getRowCount());
        }
    }

    /**
     * Format commit date to show e.g. "HH:MM:SS today" if it is today's date.
     */
    static String formatCommitDate(Date date, java.time.LocalDate today) {
        try {
            java.time.LocalDate commitDate = date.toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();

            String timeStr = new java.text.SimpleDateFormat("HH:mm:ss").format(date);

            if (commitDate.equals(today)) {
                // If it's today's date, just show the time with "today"
                return "Today " + timeStr;
            } else if (commitDate.equals(today.minusDays(1))) {
                // If it's yesterday
                return "Yesterday " + timeStr;
            } else if (commitDate.isAfter(today.minusDays(7))) {
                // If within the last week, show day of week
                String dayName = commitDate.getDayOfWeek().toString();
                dayName = dayName.substring(0, 1).toUpperCase() + dayName.substring(1).toLowerCase();
                return dayName + " " + timeStr;
            }

            // Otherwise, show the standard date format
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
        } catch (Exception e) {
            logger.debug("Could not format date: {}", date, e);
            return date.toString();
        }
    }

    /**
     * Check if any file nodes (as opposed to directory nodes) are selected.
     */
    private boolean hasFileNodesSelected(TreePath[] paths) {
        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node != changesRootNode && node.getParent() != null && node.getParent() != changesRootNode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retrieve file paths from the selected tree nodes.
     */
    private List<String> getSelectedFilePaths(TreePath[] paths) {
        List<String> selectedFiles = new ArrayList<>();
        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node == changesRootNode || node.getParent() == changesRootNode) {
                continue;
            }
            String fileName = node.getUserObject().toString();
            DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) node.getParent();
            String dirPath = parentNode.getUserObject().toString();
            String filePath = dirPath.isEmpty() ? fileName : dirPath + "/" + fileName;
            selectedFiles.add(filePath);
        }
        return selectedFiles;
    }

    /**
     * Pop a stash - apply it to the working directory and remove it from the stash list
     */
    private void popStash(int stashIndex) {
        contextManager.submitUserTask("Popping stash", () -> {
            try {
                getRepo().popStash(stashIndex);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Stash popped successfully");
                    update(); // Refresh branches to show updated stash list
                });
            } catch (Exception e) {
                logger.error("Error popping stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error popping stash: " + e.getMessage()));
            }
        });
    }

    /**
     * Apply a stash to the working directory without removing it from the stash list
     */
    private void applyStash(int stashIndex) {
        contextManager.submitUserTask("Applying stash", () -> {
            try {
                getRepo().applyStash(stashIndex);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Stash applied successfully");
                });
            } catch (Exception e) {
                logger.error("Error applying stash", e);
                SwingUtilities.invokeLater(() -> chrome.toolErrorRaw("Error applying stash: " + e.getMessage()));
            }
        });
    }

    /**
     * Drop a stash without applying it
     */
    private void dropStash(int stashIndex) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete this stash?",
                "Delete Stash",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        contextManager.submitUserTask("Dropping stash", () -> {
            try {
                getRepo().dropStash(stashIndex);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Stash dropped successfully");
                    update(); // Refresh branches to show updated stash list
                });
            } catch (Exception e) {
                logger.error("Error dropping stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error dropping stash: " + e.getMessage()));
            }
        });
    }

    /**
     * Enables/Disables items in the local-branch context menu based on selection.
     */
    private void checkBranchContextMenuState(JPopupMenu menu) {
        int selectedRow = branchTable.getSelectedRow();
        boolean isLocal = (selectedRow != -1);

        // Check if the selected branch is the current branch
        boolean isCurrentBranch = false;
        if (isLocal) {
            String checkmark = (String) branchTableModel.getValueAt(selectedRow, 0);
            isCurrentBranch = "✓".equals(checkmark);
        }

        // newBranch = menu.getComponent(1), merge = menu.getComponent(2)
        // rename = menu.getComponent(3), delete = menu.getComponent(4)
        // Adjust as needed if you change order
        menu.getComponent(3).setEnabled(isLocal);
        menu.getComponent(4).setEnabled(isLocal && !isCurrentBranch); // Can't delete current branch
    }

    /**
     * Selects the current branch in the branch table.
     * Called after PR checkout to highlight the new branch.
     */
    public void selectCurrentBranch() {
        contextManager.submitBackgroundTask("Finding current branch", () -> {
            try {
                String currentBranch = getRepo().getCurrentBranch();
                SwingUtilities.invokeLater(() -> {
                    // Find and select the current branch in the branches table
                    for (int i = 0; i < branchTableModel.getRowCount(); i++) {
                        String branchName = (String) branchTableModel.getValueAt(i, 1);
                        if (branchName.equals(currentBranch)) {
                            branchTable.setRowSelectionInterval(i, i);
                            branchTable.scrollRectToVisible(branchTable.getCellRect(i, 0, true));
                            updateCommitsForBranch(currentBranch);
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("Error selecting current branch", e);
            }
            return null;
        });
    }

    /**
     * Selects a commit in the commits table by its ID.
     */
    public void selectCommitById(String commitId) {
        for (int i = 0; i < commitsTableModel.getRowCount(); i++) {
            // Get CommitInfo from hidden column 5
            ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(i, 5);
            if (commitId.equals(commitInfo.id())) {
                commitsTable.setRowSelectionInterval(i, i);
                commitsTable.scrollRectToVisible(commitsTable.getCellRect(i, 0, true));
                // Listener will handle updateChangesForCommits and revisionLabel
                return;
            }
        }

        // If not found in the current view, let the user know
        chrome.systemOutput("Commit " + commitId.substring(0, 7) + " not found in current branch view");
    }
}
