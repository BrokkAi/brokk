package ai.brokk.gui.git;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.TableUtils;
import ai.brokk.gui.util.GitUiUtil;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import ai.brokk.git.ICommitInfo;
import javax.swing.table.TableRowSorter;
import javax.swing.table.TableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.JLabel;

/** A panel representing a single tab showing the Git history for a specific file. */
public class GitHistoryTab extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitHistoryTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final ProjectFile file;

    private JTable fileHistoryTable;
    private DefaultTableModel fileHistoryModel;

    public GitHistoryTab(Chrome chrome, ContextManager contextManager, ProjectFile file) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.file = file;
        buildHistoryTabUI();
        loadFileHistory();
    }

    public String getFilePath() {
        return file.toString();
    }

    private void buildHistoryTabUI() {
        fileHistoryModel =
                new DefaultTableModel(new Object[] {"Message", "Author", "Date", "ID", "Path", "CommitObject"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int c) {
                return String.class;
            }
        };
        fileHistoryTable = new JTable(fileHistoryModel) {
            @Override
            @Nullable
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row == -1) {
                    return null;
                }
                var commit = (ICommitInfo) fileHistoryModel.getValueAt(row, 5);
                String shortId = getRepo().shortHash(commit.id());
                String author = commit.author();
                String date = commit.date() != null
                        ? GitUiUtil.formatRelativeDate(commit.date(), LocalDate.now())
                        : "N/A";
                String message = commit.message();
                String signedStatus = commit.isSigned() ? "Signed" : "Not Signed";

                // Use a more robust width for the tooltip content
                return "<html><body style='width: 350px;'>"
                        + "<b>Commit:</b> " + shortId + "<br>"
                        + "<b>Author:</b> " + author + "<br>"
                        + "<b>Date:</b> " + date + "<br>"
                        + "<b>Signature:</b> " + signedStatus + "<br><br>"
                        + "<p>" + message + "</p>"
                        + "</body></html>";
            }
        };
        fileHistoryTable.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (c instanceof JLabel label) {
                    label.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
                    if (column == 0) { // Message column
                        var commit = (ICommitInfo) fileHistoryModel.getValueAt(row, 5);
                        String text = value.toString();
                        if (commit.isSigned()) {
                            text = "\uD83D\uDD11 " + text;
                        }
                        label.setText(text);
                    }
                }
                return c;
            }
        });
        fileHistoryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileHistoryTable.setAutoCreateRowSorter(true);
        TableRowSorter<TableModel> sorter = new TableRowSorter<>(fileHistoryModel);
        fileHistoryTable.setRowSorter(sorter);

        // Hide ID, Path, and CommitObject columns by default
        fileHistoryTable.getColumn("ID").setMinWidth(0);
        fileHistoryTable.getColumn("ID").setMaxWidth(0);
        fileHistoryTable.getColumn("Path").setMinWidth(0);
        fileHistoryTable.getColumn("Path").setMaxWidth(0);
        fileHistoryTable.getColumn("CommitObject").setMinWidth(0);
        fileHistoryTable.getColumn("CommitObject").setMaxWidth(0);

        var sp = new JScrollPane(fileHistoryTable);
        // TODO: The styleScrollPane method is not found in the GuiTheme class.
        // This could be due to a missing method or an incorrect class reference.
        // chrome.getTheme().styleScrollPane(sp);

        var menu = new JPopupMenu();
        chrome.getTheme().registerPopupMenu(menu);

        var captureDiffItem = new JMenuItem("Capture Diff");
        var compareWithLocalItem = new JMenuItem("Compare with Local");
        var viewFileAtRevItem = new JMenuItem("View File at Revision");
        var viewDiffItem = new JMenuItem("View Diff");
        var viewInLogItem = new JMenuItem("View in Log");
        var editFileItem = new JMenuItem("Edit File");

        menu.add(captureDiffItem);
        menu.add(editFileItem);
        menu.addSeparator();
        menu.add(viewInLogItem);
        menu.addSeparator();
        menu.add(viewFileAtRevItem);
        menu.add(viewDiffItem);
        menu.add(compareWithLocalItem);

        /* right-click selects row */
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    var p = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(p, fileHistoryTable);
                    int row = fileHistoryTable.rowAtPoint(p);
                    if (row >= 0) fileHistoryTable.setRowSelectionInterval(row, row);
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
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
                editFileItem.setEnabled(!contextManager.getFilesInContext().contains(selFile));
            } else {
                editFileItem.setEnabled(false);
            }
        });

        /* double-click => show diff */
        fileHistoryTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = fileHistoryTable.rowAtPoint(e.getPoint());
                if (row < 0) return;

                fileHistoryTable.setRowSelectionInterval(row, row);
                var commitId = (String) fileHistoryModel.getValueAt(row, 3);
                var histFile = (ProjectFile) fileHistoryModel.getValueAt(row, 4);
                GitUiUtil.showFileHistoryDiff(contextManager, chrome, commitId, histFile);
            }
        });

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
            GitUiUtil.showDiffVsLocal(contextManager, chrome, commitId, histFile.toString(), false);
        });

        viewInLogItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                var commitId = (String) fileHistoryTable.getValueAt(row, 3);
                chrome.showCommitInLogTab(commitId);
            }
        });

        editFileItem.addActionListener(e -> GitUiUtil.editFile(contextManager, getFilePath()));

        add(sp, BorderLayout.CENTER);
    }

    private void loadFileHistory() {
        contextManager.submitBackgroundTask("Loading file history: " + file, () -> {
            try {
                var history = getRepo().getFileHistoryWithPaths(file);
                SwingUtilities.invokeLater(() -> {
                    fileHistoryModel.setRowCount(0);
                    if (history.isEmpty()) {
                        fileHistoryModel.addRow(new Object[] {"No history found", "", "", "", "", ""});
                        return;
                    }

                    var today = LocalDate.now(ZoneId.systemDefault());

                    for (var entry : history) {
                        var commit = entry.commit();
                        var pathInCommit = entry.path();
                        fileHistoryModel.addRow(new Object[] {
                            commit.message(),
                            commit.author(),
                            commit.date() != null ? GitUiUtil.formatRelativeDate(commit.date(), LocalDate.now()) : "",
                            commit.id(),
                            pathInCommit.toString(),
                            commit
                        });
                    }
                    if (!history.isEmpty()) {
                        fileHistoryTable.setRowSelectionInterval(0, 0);
                    }

                    TableUtils.fitColumnWidth(fileHistoryTable, 1);
                    TableUtils.fitColumnWidth(fileHistoryTable, 2);
                });
            } catch (Exception ex) {
                logger.error("Error loading file history for: {}", file, ex);
                SwingUtilities.invokeLater(() -> {
                    fileHistoryModel.setRowCount(0);
                    fileHistoryModel.addRow(new Object[] {"Error loading history: " + ex.getMessage(), "", "", "", "", ""});
                });
            }
            return null;
        });
    }

    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }
}
