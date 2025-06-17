package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

/**
 * A panel representing a single tab showing the Git history for a specific file.
 */
public class GitHistoryTab extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitHistoryTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel; // Parent panel to call back for Log tab interaction
    private final ProjectFile file;
    private JTable fileHistoryTable;
    private DefaultTableModel fileHistoryModel;

    public GitHistoryTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel, ProjectFile file) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.gitPanel = gitPanel;
        this.file = file;
        buildHistoryTabUI();
        loadFileHistory();
    }

    public String getFilePath() {
        return file.toString();
    }

    private void buildHistoryTabUI()
    {
        fileHistoryModel = new DefaultTableModel(
                new Object[]{"Message", "Author", "Date", "ID"}, 0
        ) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };

        fileHistoryTable = new JTable(fileHistoryModel);
        fileHistoryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileHistoryTable.setRowHeight(18);

        // Hide the ID column
        fileHistoryTable.getColumnModel().getColumn(3).setMinWidth(0);
        fileHistoryTable.getColumnModel().getColumn(3).setMaxWidth(0);
        fileHistoryTable.getColumnModel().getColumn(3).setWidth(0);

        // Context menu
        JPopupMenu historyContextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(historyContextMenu);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(historyContextMenu);
                }
            });
        }

        JMenuItem addToContextItem       = new JMenuItem("Capture Diff");
        JMenuItem compareWithLocalItem   = new JMenuItem("Compare with Local");
        JMenuItem viewFileAtRevisionItem = new JMenuItem("View File at Revision");
        JMenuItem viewDiffItem           = new JMenuItem("View Diff");
        JMenuItem viewInLogItem          = new JMenuItem("View in Log");
        JMenuItem editFileItem           = new JMenuItem("Edit File");

        historyContextMenu.add(addToContextItem);
        historyContextMenu.add(editFileItem);
        historyContextMenu.addSeparator();
        historyContextMenu.add(viewInLogItem);
        historyContextMenu.addSeparator();
        historyContextMenu.add(viewFileAtRevisionItem);
        historyContextMenu.add(viewDiffItem);
        historyContextMenu.add(compareWithLocalItem);

        // Right-click => select row
        historyContextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, fileHistoryTable);
                    int row = fileHistoryTable.rowAtPoint(point);
                    if (row >= 0) {
                        fileHistoryTable.setRowSelectionInterval(row, row);
                    }
                });
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        fileHistoryTable.setComponentPopupMenu(historyContextMenu);

        // Enable/disable items based on selection
        fileHistoryTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int selectedRowCount = fileHistoryTable.getSelectedRowCount();
                boolean singleSelected = selectedRowCount == 1;

                addToContextItem.setEnabled(singleSelected);
                compareWithLocalItem.setEnabled(singleSelected);
                viewFileAtRevisionItem.setEnabled(singleSelected);
                viewDiffItem.setEnabled(singleSelected);
                viewInLogItem.setEnabled(singleSelected);

                // Edit File => only if not already in context
                if (singleSelected) {
                    var selectedFile = contextManager.toFile(getFilePath());
                    editFileItem.setEnabled(!contextManager.getEditableFiles().contains(selectedFile));
                } else {
                    editFileItem.setEnabled(false);
                }
            }
        });

        // Double-click => show diff
        fileHistoryTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = fileHistoryTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        fileHistoryTable.setRowSelectionInterval(row, row);
                        String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                        GitUiUtil.showFileHistoryDiff(contextManager, chrome, commitId, file);
                    }
                }
            }
        });

        // Menu actions:
        addToContextItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                GitUiUtil.addFileChangeToContext(contextManager, chrome, commitId, file);
            }
        });

        viewFileAtRevisionItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryTable.getValueAt(row, 3);
                GitUiUtil.viewFileAtRevision(contextManager, chrome, commitId, getFilePath());
            }
        });

        viewDiffItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryTable.getValueAt(row, 3);
                GitUiUtil.showFileHistoryDiff(contextManager, chrome, commitId, file);
            }
        });

        compareWithLocalItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryTable.getValueAt(row, 3);
                // Compare commit -> local
                GitUiUtil.showDiffVsLocal(contextManager, chrome,
                                          commitId, getFilePath(), /*useParent=*/ false);
            }
        });

        viewInLogItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryTable.getValueAt(row, 3);
                gitPanel.showCommitInLogTab(commitId);
            }
        });

        editFileItem.addActionListener(e ->
                                               GitUiUtil.editFile(contextManager, getFilePath())
        );

        add(new JScrollPane(fileHistoryTable), BorderLayout.CENTER);
    }


    private void loadFileHistory() {
        contextManager.submitBackgroundTask("Loading file history: " + file, () -> {
            try {
                var repo = getRepo();
                if (repo == null) {
                    SwingUtilities.invokeLater(() -> {
                        fileHistoryModel.setRowCount(0);
                        fileHistoryModel.addRow(new Object[]{"Git repository not available", "", "", ""});
                    });
                    return null;
                }
                var history = repo.getFileHistory(file);
                SwingUtilities.invokeLater(() -> {
                    fileHistoryModel.setRowCount(0);
                    if (history.isEmpty()) {
                        fileHistoryModel.addRow(new Object[]{"No history found", "", "", ""});
                        return;
                    }

                    var today = java.time.LocalDate.now(java.time.ZoneId.systemDefault());
                    for (var commit : history) {
                        var formattedDate = GitLogTab.formatCommitDate(commit.date(), today);
                        fileHistoryModel.addRow(new Object[]{
                                commit.message(),
                                commit.author(),
                                formattedDate,
                                commit.id()
                        });
                    }

                    // Now that data is loaded, adjust column widths
                    TableUtils.fitColumnWidth(fileHistoryTable, 1); // author column
                    TableUtils.fitColumnWidth(fileHistoryTable, 2); // date column
                });
            } catch (Exception e) {
                logger.error("Error loading file history for: {}", file, e);
                SwingUtilities.invokeLater(() -> {
                    fileHistoryModel.setRowCount(0);
                    fileHistoryModel.addRow(new Object[]{
                            "Error loading history: " + e.getMessage(), "", "", ""
                    });
                });
            }
            return null;
        });
    }

    /**
     * Returns the current GitRepo from ContextManager.
     */
    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }
}
