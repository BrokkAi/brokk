package io.github.jbellis.brokk.gui.widgets;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.mop.ThemeColors;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.util.*;
import java.util.List;

/**
 * Scroll-pane component that displays a list of files plus their git status.
 * The underlying JTable is exposed via {@link #getTable()} for callers that
 * need to add listeners or context menus.
 */
public final class FileStatusTable extends JScrollPane {

    private final JTable table;
    private final Map<ProjectFile, String> statusMap = new HashMap<>();

    public FileStatusTable() {
        super(null, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED);

        var model = new DefaultTableModel(new Object[]{"Filename", "Path", "ProjectFile"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 2 ? ProjectFile.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(model);
        table.setRowHeight(18);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    javax.swing.JTable tbl, Object value,
                    boolean isSelected, boolean hasFocus,
                    int row, int column)
            {
                var cell = (DefaultTableCellRenderer)
                        super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);

                var pf = (ProjectFile) tbl.getModel().getValueAt(row, 2);
                String st = statusMap.getOrDefault(pf, "");
                boolean dk = UIManager.getLookAndFeel().getName().toLowerCase().contains("dark");

                var newC = ThemeColors.getColor(dk, "git_status_new");
                var modC = ThemeColors.getColor(dk, "git_status_modified");
                var delC = ThemeColors.getColor(dk, "git_status_deleted");

                if (isSelected) {
                    cell.setForeground(tbl.getSelectionForeground());
                } else {
                    cell.setForeground(
                            switch (st) {
                                case "new" -> newC;
                                case "deleted" -> delC;
                                case "modified" -> modC;
                                default -> tbl.getForeground();
                            });
                }
                return cell;
            }
        });

        // hide ProjectFile column
        var hidden = table.getColumnModel().getColumn(2);
        hidden.setMinWidth(0);
        hidden.setMaxWidth(0);
        hidden.setPreferredWidth(0);

        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(450);

        setViewportView(table);
    }

    public void setFiles(Collection<GitRepo.ModifiedFile> modifiedFiles) {
        statusMap.clear();
        var model = (DefaultTableModel) table.getModel();
        model.setRowCount(0);

        modifiedFiles.forEach(mf -> {
            statusMap.put(mf.file(), mf.status());
            model.addRow(new Object[]{mf.file().getFileName(), mf.file().getParent().toString(), mf.file()});
        });
    }

    public void setFilesFromProjects(Collection<ProjectFile> files) {
        statusMap.clear();
        var model = (DefaultTableModel) table.getModel();
        model.setRowCount(0);

        files.forEach(pf -> {
            statusMap.put(pf, "modified");
            model.addRow(new Object[]{pf.getFileName(), pf.getParent().toString(), pf});
        });
    }

    public List<ProjectFile> getSelectedFiles() {
        var rows = table.getSelectedRows();
        var model = (DefaultTableModel) table.getModel();
        List<ProjectFile> out = new ArrayList<>(rows.length);
        for (int r : rows) out.add((ProjectFile) model.getValueAt(r, 2));
        return out;
    }

    public JTable getTable() {
        return table;
    }

    /**
     * Returns the Git status for the given ProjectFile.
     *
     * @param pf The ProjectFile to get the status for.
     * @return The status string (e.g., "new", "modified", "deleted"), or an empty string if not found.
     */
    public String statusFor(ProjectFile pf) {
        return statusMap.getOrDefault(pf, "");
    }
}
