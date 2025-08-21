package io.github.jbellis.brokk.gui;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import org.jetbrains.annotations.Nullable;

/**
 * A utility class containing shared constants and TableCellRenderers for the activity history tables in both
 * HistoryOutputPanel and SessionsDialog.
 */
public final class ActivityTableRenderers {
    public static final String CLEARED_TASK_HISTORY = "Cleared Task History";
    public static final String DROPPED_ALL_CONTEXT = "Dropped all Context";

    private ActivityTableRenderers() {
        // Prevent instantiation
    }

    public static boolean isSeparatorAction(@Nullable Object actionValue) {
        if (actionValue == null) {
            return false;
        }
        String action = actionValue.toString();
        return CLEARED_TASK_HISTORY.equalsIgnoreCase(action) || DROPPED_ALL_CONTEXT.equalsIgnoreCase(action);
    }

    /**
     * A TableCellRenderer for the first column (icons) of the history table. It hides the icon for separator rows to
     * allow the separator to span the cell.
     */
    public static class IconCellRenderer extends DefaultTableCellRenderer {
        private final SeparatorPainter separatorPainter = new SeparatorPainter();

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Object actionValue = table.getModel().getValueAt(row, 1);
            if (isSeparatorAction(actionValue)) {
                separatorPainter.setAction(castNonNull(actionValue).toString());
                separatorPainter.setCellContext(table, row, column);
                separatorPainter.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                separatorPainter.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                return separatorPainter;
            }

            // Fallback for normal cells
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof Icon icon) {
                setIcon(icon);
                setText("");
            } else {
                setIcon(null);
                setText(value != null ? value.toString() : "");
            }
            setHorizontalAlignment(JLabel.CENTER);
            return this;
        }
    }

    /**
     * A TableCellRenderer for the second column (action text) of the history table. It replaces specific action texts
     * with graphical separators.
     */
    public static class ActionCellRenderer extends DefaultTableCellRenderer {
        private final SeparatorPainter separatorPainter = new SeparatorPainter();

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            if (isSeparatorAction(value)) {
                separatorPainter.setOpaque(true);
                separatorPainter.setAction(castNonNull(value).toString());
                separatorPainter.setCellContext(table, row, column);
                separatorPainter.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                separatorPainter.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                return separatorPainter;
            }

            // Fallback for normal cells
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value != null) {
                setToolTipText(value.toString());
            }
            return this;
        }
    }

    /** A component that paints a horizontal or squiggly line for separator rows in the history table. */
    private static class SeparatorPainter extends JComponent {
        private String action = "";
        private @Nullable JTable table;
        private int row;
        private int column;
        private static final int SQUIGGLE_AMPLITUDE = 2;
        private static final double PIXELS_PER_SQUIGGLE_WAVE = 24.0;

        public SeparatorPainter() {
            setOpaque(true);
        }

        public void setAction(String action) {
            this.action = action;
            setToolTipText(action);
        }

        public void setCellContext(JTable table, int row, int column) {
            this.table = table;
            this.row = row;
            this.column = column;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, 8);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (isOpaque()) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }

            if (table == null) {
                return;
            }

            int totalWidth = table.getWidth();
            int iconColumnWidth = table.getColumnModel().getColumn(0).getWidth();
            int margin = iconColumnWidth / 2;
            int ruleStartX = margin;
            int ruleEndX = totalWidth - margin - 1;

            Rectangle cellRect = table.getCellRect(row, column, false);
            int localStartX = ruleStartX - cellRect.x;
            int localEndX = ruleEndX - cellRect.x;

            int drawStart = Math.max(0, localStartX);
            int drawEnd = Math.min(getWidth(), localEndX);

            if (drawStart >= drawEnd) {
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(getForeground());
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int y = getHeight() / 2;

                if (CLEARED_TASK_HISTORY.equalsIgnoreCase(action)) {
                    g2.drawLine(drawStart, y, drawEnd, y);
                } else if (DROPPED_ALL_CONTEXT.equalsIgnoreCase(action)) {
                    int lineWidth = ruleEndX - ruleStartX;
                    if (lineWidth <= 0) {
                        return;
                    }

                    // Dynamically calculate frequency to ensure the wave completes an integer number of cycles
                    int waves = Math.max(1, (int) Math.round(lineWidth / PIXELS_PER_SQUIGGLE_WAVE));
                    double frequency = (2 * Math.PI * waves) / lineWidth;

                    Path2D.Double path = new Path2D.Double();
                    int globalXStart = cellRect.x + drawStart;
                    double startY = y - SQUIGGLE_AMPLITUDE * Math.sin((globalXStart - ruleStartX) * frequency);
                    path.moveTo(drawStart, startY);
                    for (int x = drawStart + 1; x < drawEnd; x++) {
                        int globalX = cellRect.x + x;
                        double waveY = y - SQUIGGLE_AMPLITUDE * Math.sin((globalX - ruleStartX) * frequency);
                        path.lineTo(x, waveY);
                    }
                    g2.draw(path);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
