package ai.brokk.gui;

import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.gui.util.Icons;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A utility class containing shared constants and TableCellRenderers for the activity history tables in both
 * HistoryOutputPanel and SessionsDialog.
 */
public final class ActivityTableRenderers {
    private static final Logger logger = LogManager.getLogger(ActivityTableRenderers.class);

    public static final int COL_ICON = 0;
    public static final int COL_ACTION = 1;
    public static final int COL_CONTEXT = 2;

    private ActivityTableRenderers() {
        // Prevent instantiation
    }

    public record GroupRow(UUID key, boolean expanded, boolean containsClearHistory) {}

    public record ActionText(ComputedValue<String> text, int indentLevel) {}

    public static boolean isSeparatorAction(@Nullable Object actionValue) {
        if (actionValue == null) {
            return false;
        }
        String action = actionValue.toString().trim();
        return ContextDelta.CLEARED_TASK_HISTORY.equalsIgnoreCase(action)
                || ContextDelta.DROPPED_ALL_CONTEXT.equalsIgnoreCase(action);
    }

    public static String normalizedAction(@Nullable Object actionValue) {
        return actionValue == null ? "" : actionValue.toString().trim();
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
            Object actionValue = table.getModel().getValueAt(row, COL_ACTION);
            if (isSeparatorAction(actionValue)) {
                separatorPainter.setAction(normalizedAction(actionValue));
                separatorPainter.setCellContext(table, row, column);
                separatorPainter.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                separatorPainter.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                separatorPainter.setText("");
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
            setVerticalAlignment(JLabel.TOP);
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
                separatorPainter.setAction(normalizedAction(value));
                separatorPainter.setCellContext(table, row, column);
                separatorPainter.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                separatorPainter.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                separatorPainter.setText("");
                return separatorPainter;
            }

            // Fallback for normal cells
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setVerticalAlignment(JLabel.TOP);
            if (value != null) {
                setToolTipText(value.toString());
            }
            return this;
        }
    }

    /**
     * Icon renderer that mirrors the Action column's indentation for nested rows.
     */
    public static class IndentedIconRenderer extends IconCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            // Retrieve the action value from column COL_ACTION; derive indent level from ActionText if present
            Object actionVal = table.getModel().getValueAt(row, COL_ACTION);
            Object actionForCheck =
                    (actionVal instanceof ActionText atTmp) ? atTmp.text().renderNowOr(Context.SUMMARIZING) : actionVal;

            // Detect group header rows from column COL_CONTEXT
            Object contextCol2 = table.getModel().getValueAt(row, COL_CONTEXT);
            boolean isHeader = (contextCol2 instanceof GroupRow);

            // Preserve separator and header behavior by delegating to the base renderer unchanged
            if (isSeparatorAction(actionForCheck) || isHeader) {
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }

            int indentLevel = (actionVal instanceof ActionText at) ? Math.max(0, at.indentLevel()) : 0;
            Component comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            // Attempt to override the icon based on TaskMeta.type() of the most recent TaskEntry,
            // but only for AI result contexts.
            try {
                Object ctxVal = table.getModel().getValueAt(row, COL_CONTEXT);
                if (ctxVal instanceof Context ctx) {
                    var meta = lastMetaOf(ctx);
                    if (comp instanceof JLabel lbl) {
                        if (ctx.isAiResult() && meta != null) {
                            var chosen = iconFor(meta.type());
                            lbl.setIcon(chosen);
                        } else if (!ctx.isAiResult()) {
                            // Ensure non-AI rows have no type icon override
                            lbl.setIcon(null);
                        }
                    }

                    // Compute tooltip: include model details only for AI + meta; otherwise use plain action text
                    String actionText;
                    Object col1 = table.getModel().getValueAt(row, COL_ACTION);
                    if (col1 instanceof ActionText at2) {
                        actionText = at2.text().renderNowOr(Context.SUMMARIZING);
                    } else {
                        actionText = (col1 != null) ? col1.toString() : "";
                    }
                    if (comp instanceof JComponent jc) {
                        if (ctx.isAiResult() && meta != null) {
                            jc.setToolTipText(buildTooltipWithModel(ctx, actionText));
                        } else {
                            jc.setToolTipText(actionText);
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Error computing icon/tooltip for context row", e);
                // Best-effort icon override only; fall back silently on any errors
            }

            // Apply inset: top-level rows get a base margin; nested rows align exactly with action indent
            if (comp instanceof JComponent jc) {
                int perLevelInset = indentLevel * Constants.H_GAP * 4;
                int inset = (indentLevel == 0) ? (Constants.H_GAP * 2) : perLevelInset;
                jc.setBorder(new EmptyBorder(0, inset, 0, 0));
            }
            // Align icon left only for non-header, non-separator rows
            if (comp instanceof JLabel lbl) {
                lbl.setHorizontalAlignment(JLabel.LEFT);
            }
            return comp;
        }

        private static Icon iconFor(TaskResult.Type type) {
            switch (type) {
                case ARCHITECT -> {
                    return Icons.ARCHITECT;
                }
                case CODE -> {
                    return Icons.CODE;
                }
                case ASK -> {
                    return Icons.ASK;
                }
                case SEARCH -> {
                    return Icons.SEARCH;
                }
                case CONTEXT -> {
                    return Icons.CONTEXT;
                }
                case MERGE -> {
                    return Icons.MERGE;
                }
                case BLITZFORGE -> {
                    return Icons.BLITZFORGE;
                }
                case REVIEW -> {
                    return Icons.FLOWSHEET;
                }
                default -> {
                    return Icons.CHAT_BUBBLE;
                }
            }
        }
    }

    /**
     * A simple icon that renders a triangle pointing down (expanded) or right (collapsed).
     */
    public static class TriangleIcon implements Icon {
        private final boolean expanded;
        private final int size;

        public TriangleIcon(boolean expanded) {
            this(expanded, 12);
        }

        public TriangleIcon(boolean expanded, int size) {
            this.expanded = expanded;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int triW = 8;
                int triH = 8;
                int cx = x + (getIconWidth() - triW) / 2;
                int cy = y + (getIconHeight() - triH) / 2;

                Polygon p = new Polygon();
                if (expanded) {
                    // down triangle
                    p.addPoint(cx, cy);
                    p.addPoint(cx + triW, cy);
                    p.addPoint(cx + triW / 2, cy + triH);
                } else {
                    // right triangle
                    p.addPoint(cx, cy);
                    p.addPoint(cx + triW, cy + triH / 2);
                    p.addPoint(cx, cy + triH);
                }

                Color color = c.isEnabled()
                        ? UIManager.getColor("Label.foreground")
                        : UIManager.getColor("Label.disabledForeground");
                if (color == null) color = Color.DARK_GRAY;
                g2.setColor(color);
                g2.fillPolygon(p);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    // --- Static utilities for model info and tooltips ---

    public static @Nullable TaskResult.TaskMeta lastMetaOf(Context ctx) {
        var history = ctx.getTaskHistory();
        if (history.isEmpty()) return null;
        var last = history.getLast();
        return last.meta();
    }

    public static @Nullable Service.ModelConfig modelOf(Context ctx) {
        var meta = lastMetaOf(ctx);
        return (meta == null) ? null : meta.primaryModel();
    }

    public static @Nullable String taskTypeOf(Context ctx) {
        var meta = lastMetaOf(ctx);
        return (meta == null) ? null : meta.type().displayName();
    }

    public static String summarizeModel(Service.ModelConfig spec) {
        var name = spec.name();
        var rl = spec.reasoning().name();
        if (!rl.isBlank()) {
            return name + " (" + rl + ")";
        }
        return name;
    }

    public static String buildTooltipWithModel(@Nullable Context ctx, String base) {
        if (ctx == null) return base;
        var spec = modelOf(ctx);
        if (spec == null) return base;
        var taskType = taskTypeOf(ctx);
        var tt = taskType == null ? "" : taskType + " ";

        var header = "[" + summarizeModel(spec) + "]";
        return "<html>" + escapeHtml(tt + header) + "<br/>" + escapeHtml(base) + "</html>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A component that paints a horizontal or squiggly line for separator rows in the history table. */
    private static class SeparatorPainter extends JLabel {
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
            if (getText() == null || getText().isEmpty()) {
                return new Dimension(super.getPreferredSize().width, 8);
            }
            return super.getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            if (table == null) {
                return;
            }

            int totalWidth = table.getWidth();
            int iconColumnWidth = table.getColumnModel().getColumn(COL_ICON).getWidth();
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

                int y;
                if (getText() == null || getText().isEmpty()) {
                    y = getHeight() / 2;
                } else {
                    FontMetrics fm = g.getFontMetrics();
                    y = getInsets().top + fm.getAscent() + fm.getDescent() + 1;
                }

                if (ContextDelta.CLEARED_TASK_HISTORY.equalsIgnoreCase(action)) {
                    g2.drawLine(drawStart, y, drawEnd, y);
                } else if (ContextDelta.DROPPED_ALL_CONTEXT.equalsIgnoreCase(action)) {
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
