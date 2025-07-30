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
    private final GitPanel gitPanel;
    private final ProjectFile file;

    private JTable fileHistoryTable;
    private DefaultTableModel fileHistoryModel;

    public GitHistoryTab(Chrome chrome,
                         ContextManager contextManager,
                         GitPanel gitPanel,
                         ProjectFile file)
    {
        super(new BorderLayout());
        this.chrome          = chrome;
        this.contextManager  = contextManager;
        this.gitPanel        = gitPanel;
        this.file            = file;
        buildHistoryTabUI();
        loadFileHistory();
    }

    public String getFilePath() {
        return file.toString();
    }

    /* -----------------------  UI construction  ----------------------- */

    private void buildHistoryTabUI()
    {
        fileHistoryModel = new DefaultTableModel(
                new Object[]{"Message", "Author", "Date", "ID", "Path"}, 0)
        {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) { return String.class; }
        };

        fileHistoryTable = new JTable(fileHistoryModel);
        fileHistoryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileHistoryTable.setRowHeight(18);

        // Hide the “ID” and “Path” columns
        for (int col : new int[]{3, 4}) {
            fileHistoryTable.getColumnModel().getColumn(col).setMinWidth(0);
            fileHistoryTable.getColumnModel().getColumn(col).setMaxWidth(0);
            fileHistoryTable.getColumnModel().getColumn(col).setWidth(0);
        }

        /* -------- context menu -------- */

        var menu                  = new JPopupMenu();
        chrome.themeManager.registerPopupMenu(menu);

        var captureDiffItem       = new JMenuItem("Capture Diff");
        var compareWithLocalItem  = new JMenuItem("Compare with Local");
        var viewFileAtRevItem     = new JMenuItem("View File at Revision");
        var viewDiffItem          = new JMenuItem("View Diff");
        var viewInLogItem         = new JMenuItem("View in Log");
        var editFileItem          = new JMenuItem("Edit File");

        menu.add(captureDiffItem);
        menu.add(editFileItem);
        menu.addSeparator();
        menu.add(viewInLogItem);
        menu.addSeparator();
        menu.add(viewFileAtRevItem);
        menu.add(viewDiffItem);
        menu.add(compareWithLocalItem);

        /* right-click selects row */
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    var p = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(p, fileHistoryTable);
                    int row = fileHistoryTable.rowAtPoint(p);
                    if (row >= 0) fileHistoryTable.setRowSelectionInterval(row, row);
                });
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        fileHistoryTable.setComponentPopupMenu(menu);

        /* enable / disable menu items */
        fileHistoryTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            boolean single = fileHistoryTable.getSelectedRowCount() == 1;

            captureDiffItem.setEnabled(single);
            compareWithLocalItem.setEnabled(single);
            viewFileAtRevItem.setEnabled(single);
            viewDiffItem.setEnabled(single);
            viewInLogItem.setEnabled(single);

            if (single) {
                var selFile = contextManager.toFile(getFilePath());
                editFileItem.setEnabled(!contextManager.getEditableFiles().contains(selFile));
            } else {
                editFileItem.setEnabled(false);
            }
        });

        /* double-click → show diff */
        fileHistoryTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = fileHistoryTable.rowAtPoint(e.getPoint());
                if (row < 0) return;

                fileHistoryTable.setRowSelectionInterval(row, row);
                var commitId = (String) fileHistoryModel.getValueAt(row, 3);
                var histFile = (ProjectFile) fileHistoryModel.getValueAt(row, 4);
                GitUiUtil.showFileHistoryDiff(contextManager, chrome, commitId, histFile);
            }
        });

        /* ----- context-menu actions ----- */

        captureDiffItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row < 0) return;
            var commitId = (String) fileHistoryModel.getValueAt(row, 3);
            var histFile = (ProjectFile) fileHistoryModel.getValueAt(row, 4);
            GitUiUtil.addFileChangeToContext(contextManager, chrome, commitId, histFile);
        });

        viewFileAtRevItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row < 0) return;
            var commitId = (String) fileHistoryTable.getValueAt(row, 3);
            var histFile = (ProjectFile) fileHistoryTable.getValueAt(row, 4);
            GitUiUtil.viewFileAtRevision(contextManager, chrome, commitId, histFile.toString());
        });

        viewDiffItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row < 0) return;
            var commitId = (String) fileHistoryTable.getValueAt(row, 3);
            var histFile = (ProjectFile) fileHistoryTable.getValueAt(row, 4);
            GitUiUtil.showFileHistoryDiff(contextManager, chrome, commitId, histFile);
        });

        compareWithLocalItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row < 0) return;
            var commitId = (String) fileHistoryTable.getValueAt(row, 3);
            var histFile = (ProjectFile) fileHistoryTable.getValueAt(row, 4);
            GitUiUtil.showDiffVsLocal(contextManager, chrome,
                                      commitId, histFile.toString(), false);
        });

        viewInLogItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                var commitId = (String) fileHistoryTable.getValueAt(row, 3);
                gitPanel.showCommitInLogTab(commitId);
            }
        });

        editFileItem.addActionListener(e ->
                GitUiUtil.editFile(contextManager, getFilePath()));

        add(new JScrollPane(fileHistoryTable), BorderLayout.CENTER);
    }

    /* -----------------------  data loading  ----------------------- */

    private void loadFileHistory()
    {
        contextManager.submitBackgroundTask("Loading file history: " + file, () -> {
            try {
                var history = getRepo().getFileHistoryWithPaths(file);
                SwingUtilities.invokeLater(() -> {
                    fileHistoryModel.setRowCount(0);
                    if (history.isEmpty()) {
                        fileHistoryModel.addRow(
                                new Object[]{"No history found", "", "", "", ""});
                        return;
                    }

                    var today = java.time.LocalDate.now(
                            java.time.ZoneId.systemDefault());

                    for (var entry : history) {
                        var date = GitUiUtil.formatRelativeDate(
                                entry.commit().date(), today);
                        fileHistoryModel.addRow(new Object[]{
                                entry.commit().message(),
                                entry.commit().author(),
                                date,
                                entry.commit().id(),
                                entry.path()
                        });
                    }

                    TableUtils.fitColumnWidth(fileHistoryTable, 1);
                    TableUtils.fitColumnWidth(fileHistoryTable, 2);
                });
            } catch (Exception ex) {
                logger.error("Error loading file history for: {}", file, ex);
                SwingUtilities.invokeLater(() -> {
                    fileHistoryModel.setRowCount(0);
                    fileHistoryModel.addRow(new Object[]{
                            "Error loading history: " + ex.getMessage(),
                            "", "", "", ""});
                });
            }
            return null;
        });
    }

    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }
}
