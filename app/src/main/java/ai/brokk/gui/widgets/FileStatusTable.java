package ai.brokk.gui.widgets;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.gui.mop.ThemeColors;
import java.awt.Component;
import java.util.*;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Scroll-pane component that displays a list of files plus their git status. The underlying JTable is exposed via
 * {@link #getTable()} for callers that need to add listeners or context menus.
 */
public final class FileStatusTable extends JScrollPane {

    private final JTable table;
    private final Map<ProjectFile, String> statusMap = new HashMap<>();
    private final Set<ProjectFile> conflictFiles = new HashSet<>();

    public FileStatusTable() {
        super(null, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED);

        var model = new DefaultTableModel(new Object[] {"", "Filename", "Path"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                // Column 2 now stores ProjectFile directly
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
            public Component getTableCellRendererComponent(
                    JTable tbl, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                var cell = (DefaultTableCellRenderer)
                        super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);

                var pf = (ProjectFile) tbl.getModel().getValueAt(row, 2);
                String st = statusMap.getOrDefault(pf, "");
                boolean dk = UIManager.getLookAndFeel()
                        .getName()
                        .toLowerCase(Locale.ROOT)
                        .contains("dark");

                var newC = ThemeColors.getColor(dk, ThemeColors.GIT_STATUS_NEW);
                var modC = ThemeColors.getColor(dk, ThemeColors.GIT_STATUS_MODIFIED);
                var delC = ThemeColors.getColor(dk, ThemeColors.GIT_STATUS_DELETED);

                if (column == 0) {
                    // Conflict column - center align and use warning color
                    cell.setHorizontalAlignment(SwingConstants.CENTER);
                    if (isSelected) {
                        cell.setForeground(tbl.getSelectionForeground());
                    } else if ("!".equals(value)) {
                        cell.setForeground(ThemeColors.getColor(dk, ThemeColors.GIT_STATUS_DELETED));
                    } else {
                        cell.setForeground(tbl.getForeground());
                    }
                } else {
                    cell.setHorizontalAlignment(SwingConstants.LEFT);

                    // Display the path column as the parent directory of the ProjectFile
                    if (column == 2 && pf != null) {
                        cell.setText(pf.getParent().toString());
                    }

                    if (isSelected) {
                        cell.setForeground(tbl.getSelectionForeground());
                    } else {
                        cell.setForeground(
                                switch (st) {
                                    case "new" -> newC;
                                    case "deleted" -> delC;
                                    case "modified" -> modC;
                                    case "conflict" -> modC;
                                    default -> tbl.getForeground();
                                });
                    }
                }
                return cell;
            }
        });

        // Conflict indicator column - narrow
        table.getColumnModel().getColumn(0).setPreferredWidth(20);
        table.getColumnModel().getColumn(0).setMinWidth(20);
        table.getColumnModel().getColumn(0).setMaxWidth(30);

        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(450);

        setViewportView(table);
    }

    public void setFiles(Collection<GitRepo.ModifiedFile> modifiedFiles) {
        statusMap.clear();
        var model = (DefaultTableModel) table.getModel();
        model.setRowCount(0);

        // Sort by parent path then filename to provide a stable, predictable order
        modifiedFiles.stream()
                .sorted(Comparator.comparing((GitRepo.ModifiedFile mf) ->
                                mf.file().getParent().toString())
                        .thenComparing(mf -> mf.file().getFileName()))
                .forEach(mf -> {
                    statusMap.put(mf.file(), mf.status().toString());
                    String conflictIndicator = conflictFiles.contains(mf.file()) ? "!" : "";
                    model.addRow(new Object[] {conflictIndicator, mf.file().getFileName(), mf.file()});
                });
    }

    public void setFilesFromProjects(Collection<ProjectFile> files) {
        statusMap.clear();
        var model = (DefaultTableModel) table.getModel();
        model.setRowCount(0);

        // Sort by parent path then filename for consistent ordering
        files.stream()
                .sorted(Comparator.comparing((ProjectFile pf) -> pf.getParent().toString())
                        .thenComparing(ProjectFile::getFileName))
                .forEach(pf -> {
                    statusMap.put(pf, "modified");
                    String conflictIndicator = conflictFiles.contains(pf) ? "!" : "";
                    model.addRow(new Object[] {conflictIndicator, pf.getFileName(), pf});
                });
    }

    public List<ProjectFile> getSelectedFiles() {
        var rows = table.getSelectedRows();
        var model = (DefaultTableModel) table.getModel();
        List<ProjectFile> out = new ArrayList<>(rows.length);
        for (int r : rows) out.add((ProjectFile) model.getValueAt(r, 2));
        return out;
    }

    public void setConflictFiles(Set<ProjectFile> conflicts) {
        conflictFiles.clear();
        conflictFiles.addAll(conflicts);
    }

    public boolean hasConflicts() {
        return !conflictFiles.isEmpty();
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
