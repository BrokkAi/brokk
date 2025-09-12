package io.github.jbellis.brokk.gui.components;

import io.modelcontextprotocol.spec.McpSchema;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.jetbrains.annotations.Nullable;

public class McpToolTable extends JTable {

    private final ToolListTableModel model;

    public McpToolTable() {
        super();
        this.model = new ToolListTableModel();
        setModel(this.model);
        setFillsViewportHeight(true);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        // Ellipsize long descriptions in the Description column (index 1)
        getColumnModel().getColumn(1).setCellRenderer(new EllipsingCellRenderer());
    }

    @Override
    public String getToolTipText(java.awt.event.MouseEvent event) {
        var p = event.getPoint();
        int rowIndex = rowAtPoint(p);
        if (rowIndex >= 0) {
            int modelRow = convertRowIndexToModel(rowIndex);
            String desc = model.getDescriptionAt(modelRow);
            String name = model.getNameAt(modelRow);
            return desc.isEmpty() ? name : desc;
        }
        return "";
    }

    public void setToolsFromDetails(List<McpSchema.Tool> tools) {
        model.setToolsFromDetails(tools);
    }

    public void setToolsFromNames(List<String> names) {
        model.setToolsFromNames(names);
    }

    // --- Inner model class ---
    private static class ToolListTableModel extends AbstractTableModel {
        private static class Row {
            final String name;
            final String description;

            Row(String name, @Nullable String description) {
                this.name = name;
                this.description = description == null ? "" : description;
            }
        }

        private final String[] columnNames = {"Tool", "Description"};
        private List<Row> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row r = rows.get(rowIndex);
            return columnIndex == 0 ? r.name : r.description;
        }

        void setToolsFromDetails(List<McpSchema.Tool> tools) {
            List<Row> newRows = new ArrayList<>();
            for (McpSchema.Tool t : tools) {
                newRows.add(new Row(t.name(), t.description()));
            }
            this.rows = newRows;
            fireTableDataChanged();
        }

        void setToolsFromNames(List<String> names) {
            List<Row> newRows = new ArrayList<>();
            for (String n : names) {
                newRows.add(new Row(n, ""));
            }
            this.rows = newRows;
            fireTableDataChanged();
        }

        String getDescriptionAt(int modelRow) {
            if (modelRow < 0 || modelRow >= rows.size()) return "";
            return rows.get(modelRow).description;
        }

        String getNameAt(int modelRow) {
            if (modelRow < 0 || modelRow >= rows.size()) return "";
            return rows.get(modelRow).name;
        }
    }

    // --- Renderer that truncates text with ellipsis to fit the column width ---
    private static class EllipsingCellRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label =
                    (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String text = value == null ? "" : value.toString();
            int colWidth = table.getColumnModel().getColumn(column).getWidth();
            int padding = 4;
            Insets insets = label.getInsets();
            int available = colWidth - insets.left - insets.right - padding;
            if (available < 0) available = 0;
            FontMetrics fm = label.getFontMetrics(label.getFont());
            label.setText(ellipsize(text, fm, available));

            if (!isSelected) {
                label.setForeground(UIManager.getColor("Table.foreground"));
                label.setBackground(UIManager.getColor("Table.background"));
            }
            return label;
        }

        private String ellipsize(String text, FontMetrics fm, int availWidth) {
            if (text.isEmpty()) return "";
            if (fm.stringWidth(text) <= availWidth) return text;
            String ellipsis = "...";
            int ellipsisW = fm.stringWidth(ellipsis);
            int target = availWidth - ellipsisW;
            if (target <= 0) return ellipsis;
            int low = 0, high = text.length();
            while (low < high) {
                int mid = (low + high) >>> 1;
                String sub = text.substring(0, mid);
                int w = fm.stringWidth(sub);
                if (w <= target) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            int cut = Math.max(0, low - 1);
            return text.substring(0, cut) + ellipsis;
        }
    }
}
