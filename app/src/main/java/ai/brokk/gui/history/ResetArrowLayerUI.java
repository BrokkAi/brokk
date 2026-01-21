package ai.brokk.gui.history;

import static ai.brokk.gui.ActivityTableRenderers.COL_ICON;

import ai.brokk.context.ContextHistory;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.mop.ThemeColors;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.plaf.LayerUI;

/**
 * A LayerUI that paints reset-from-history arrows over the history table.
 */
public class ResetArrowLayerUI extends LayerUI<JScrollPane> {
    private final JTable table;
    private final Chrome chrome;
    private final Supplier<List<HistoryGrouping.GroupDescriptor>> descriptorsSupplier;

    private List<ContextHistory.ResetEdge> resetEdges = List.of();
    private final Map<ContextHistory.ResetEdge, Integer> edgePaletteIndices = new HashMap<>();
    private int nextPaletteIndex = 0;

    public ResetArrowLayerUI(
            JTable table, Chrome chrome, Supplier<List<HistoryGrouping.GroupDescriptor>> descriptorsSupplier) {
        this.table = table;
        this.chrome = chrome;
        this.descriptorsSupplier = descriptorsSupplier;
    }

    public void setResetEdges(List<ContextHistory.ResetEdge> edges) {
        this.resetEdges = edges;
        // remove color mappings for edges that no longer exist
        edgePaletteIndices.keySet().retainAll(new HashSet<>(edges));
        firePropertyChange("resetEdges", null, edges); // Triggers repaint for the JLayer
    }

    private record Arrow(ContextHistory.ResetEdge edge, int sourceRow, int targetRow, int length) {}

    private Color colorFor(ContextHistory.ResetEdge edge, boolean isDark) {
        int paletteIndex = edgePaletteIndices.computeIfAbsent(edge, e -> {
            int i = nextPaletteIndex;
            nextPaletteIndex = (nextPaletteIndex + 1) % 4; // Cycle through 4 colors
            return i;
        });

        // For light mode, we want darker lines for better contrast against a light background.
        // For dark mode, we want brighter lines.
        var palette = List.of(
                isDark ? Color.LIGHT_GRAY : Color.DARK_GRAY,
                isDark
                        ? ColorUtil.brighter(ThemeColors.getDiffAdded(true), 0.4f)
                        : ColorUtil.brighter(ThemeColors.getDiffAdded(false), -0.4f),
                isDark
                        ? ColorUtil.brighter(ThemeColors.getDiffChanged(true), 0.6f)
                        : ColorUtil.brighter(ThemeColors.getDiffChanged(false), -0.4f),
                isDark
                        ? ColorUtil.brighter(ThemeColors.getDiffDeleted(true), 1.2f)
                        : ColorUtil.brighter(ThemeColors.getDiffDeleted(false), -0.4f));
        return palette.get(paletteIndex);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        super.paint(g, c);
        if (resetEdges.isEmpty()) {
            return;
        }

        // Use unified helper to compute anchor rows for each Context id
        Map<UUID, Integer> contextIdToRow = HistoryGrouping.buildContextToRowMap(descriptorsSupplier.get(), table);

        // Build list of arrows with geometry between the resolved row anchors
        List<Arrow> arrows = new ArrayList<>();
        for (var edge : resetEdges) {
            Integer sourceRow = contextIdToRow.get(edge.sourceId());
            Integer targetRow = contextIdToRow.get(edge.targetId());
            if (sourceRow != null && targetRow != null) {
                var sourceRect = table.getCellRect(sourceRow, COL_ICON, true);
                var targetRect = table.getCellRect(targetRow, COL_ICON, true);
                int y1 = sourceRect.y + sourceRect.height / 2;
                int y2 = targetRect.y + targetRect.height / 2;
                arrows.add(new Arrow(edge, sourceRow, targetRow, Math.abs(y1 - y2)));
            }
        }

        // Draw arrows, longest first (so shorter arrows aren't hidden)
        arrows.sort(Comparator.comparingInt((Arrow a) -> a.length).reversed());

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float lineWidth =
                    (float) (c.getGraphicsConfiguration().getDefaultTransform().getScaleX() >= 2 ? 0.75 : 1.0);
            g2.setStroke(new BasicStroke(lineWidth));

            boolean isDark = chrome.getTheme().isDarkTheme();
            for (var arrow : arrows) {
                g2.setColor(colorFor(arrow.edge(), isDark));
                drawArrow(g2, c, arrow.sourceRow(), arrow.targetRow());
            }
        } finally {
            g2.dispose();
        }
    }

    private void drawArrow(Graphics2D g2, JComponent c, int sourceRow, int targetRow) {
        Rectangle sourceRect = table.getCellRect(sourceRow, 0, true);
        Rectangle targetRect = table.getCellRect(targetRow, 0, true);

        // Convert cell rectangles to the JLayer's coordinate system
        Point sourcePoint =
                SwingUtilities.convertPoint(table, new Point(sourceRect.x, sourceRect.y + sourceRect.height / 2), c);
        Point targetPoint =
                SwingUtilities.convertPoint(table, new Point(targetRect.x, targetRect.y + targetRect.height / 2), c);

        // Don't draw if either point is outside the visible viewport
        if (!c.getVisibleRect().contains(sourcePoint) && !c.getVisibleRect().contains(targetPoint)) {
            // a bit of a hack -- if just one is visible, we still want to draw part of the arrow
            if (c.getVisibleRect().contains(sourcePoint) || c.getVisibleRect().contains(targetPoint)) {
                // one is visible, fall through
            } else {
                return;
            }
        }

        int iconColWidth = table.getColumnModel().getColumn(COL_ICON).getWidth();
        int arrowHeadLength = 5;
        int arrowLeadIn = 1; // length of the line segment before the arrowhead
        int arrowRightMargin = -2; // margin from the right edge of the column

        int tipX = sourcePoint.x + iconColWidth - arrowRightMargin;
        int baseX = tipX - arrowHeadLength;
        int verticalLineX = baseX - arrowLeadIn;

        // Define the path for the arrow shaft
        Path2D.Double path = new Path2D.Double();
        path.moveTo(tipX, sourcePoint.y); // Start at source, aligned with the eventual arrowhead tip
        path.lineTo(verticalLineX, sourcePoint.y); // Horizontal segment at source row
        path.lineTo(verticalLineX, targetPoint.y); // Vertical segment connecting rows
        path.lineTo(baseX, targetPoint.y); // Horizontal segment leading to arrowhead base
        g2.draw(path);

        // Draw the arrowhead at the target, pointing left-to-right
        drawArrowHead(g2, new Point(tipX, targetPoint.y), arrowHeadLength);
    }

    private void drawArrowHead(Graphics2D g2, Point to, int size) {
        // The arrow is always horizontal, left-to-right. Build an isosceles triangle.
        int tipX = to.x;
        int midY = to.y;
        int baseX = to.x - size;
        int halfHeight = (int) Math.round(size * 0.6); // Make it slightly wider than it is long

        var head =
                new Polygon(new int[] {tipX, baseX, baseX}, new int[] {midY, midY - halfHeight, midY + halfHeight}, 3);
        g2.fill(head);
    }
}
