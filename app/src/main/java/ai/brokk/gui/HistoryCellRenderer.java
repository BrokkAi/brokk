package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.gui.mop.ThemeColors;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import org.jetbrains.annotations.Nullable;

/**
 * Table cell renderer for the History "Action" column that can optionally show per-context diff summaries.
 *
 * <p>This is a standalone, reusable class that encapsulates the logic previously implemented as an inner
 * class of {@link HistoryOutputPanel}. It delegates to {@link ActivityTableRenderers.ActionCellRenderer}
 * for the primary action text, and optionally shows a compact, per-fragment diff summary underneath
 * using {@link ThemeColors} for coloring.
 *
 * <p>The renderer is deliberately side-effect free with respect to table layout: it does <b>not</b> modify
 * row heights or table state while painting, to avoid repaint storms and feedback loops on the EDT.
 *
 * <p>Implementation note: this renderer uses a flyweight pattern. It reuses a single outer panel and a pool
 * of child components across all rows, avoiding repeated allocation and UI installation during painting.</p>
 */
public final class HistoryCellRenderer extends DefaultTableCellRenderer {

    private final ActivityTableRenderers.ActionCellRenderer fallback = new ActivityTableRenderers.ActionCellRenderer();
    private final Font smallFont = new Font(Font.DIALOG, Font.PLAIN, 11);

    private final HistoryOutputPanel historyOutputPanel;
    private final ContextManager contextManager;
    private final Chrome chrome;

    @SuppressWarnings("unused")
    private final JTable historyTable;

    // Flyweight components reused for all cells
    private final JPanel outerPanel;
    private final JPanel diffPanel;
    private final List<DiffRow> diffRows = new ArrayList<>();

    /**
     * Creates a new HistoryCellRenderer.
     *
     * @param historyOutputPanel owning HistoryOutputPanel (used for tooltip helpers)
     * @param contextManager     context manager used to obtain diff services
     * @param chrome             chrome instance used for theme lookups
     * @param historyTable       the history JTable instance
     */
    public HistoryCellRenderer(
            HistoryOutputPanel historyOutputPanel, ContextManager contextManager, Chrome chrome, JTable historyTable) {
        this.historyOutputPanel = historyOutputPanel;
        this.contextManager = contextManager;
        this.chrome = chrome;
        this.historyTable = historyTable;

        this.outerPanel = new JPanel(new BorderLayout());
        outerPanel.setOpaque(true);

        this.diffPanel = new JPanel();
        diffPanel.setLayout(new javax.swing.BoxLayout(diffPanel, javax.swing.BoxLayout.Y_AXIS));
        diffPanel.setOpaque(false);
        diffPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        // Extract action text and indent level.
        int indentLevel = 0;
        String actionText;
        if (value instanceof HistoryOutputPanel.ActionText at) {
            actionText = at.text();
            indentLevel = Math.max(0, at.indentLevel());
        } else {
            actionText = value != null ? value.toString() : "";
        }

        // Separator rows are rendered entirely by the existing fallback renderer.
        if (ActivityTableRenderers.isSeparatorAction(actionText)) {
            return fallback.getTableCellRendererComponent(table, actionText, isSelected, hasFocus, row, column);
        }

        // Column 2 holds either a Context or a GroupRow; only Context rows get diff summaries.
        Object ctxVal = table.getModel().getValueAt(row, 2);
        if (!(ctxVal instanceof Context ctx)) {
            Component comp = super.getTableCellRendererComponent(table, actionText, isSelected, hasFocus, row, column);
            if (comp instanceof JLabel lbl) {
                lbl.setVerticalAlignment(JLabel.TOP);
            }
            if (comp instanceof JComponent jc) {
                jc.setToolTipText(actionText);
            }
            return comp;
        }

        // Decide whether to render a diff panel below the action, using the cached DiffService results.
        var diffService = contextManager.getContextHistory().getDiffService();
        var cachedOpt = diffService.peek(ctx);

        // Kick off background computation if needed; when finished, adjust row height (on EDT)
        // and repaint the table once. This keeps the renderer side-effect free while still
        // expanding rows that gain diff summaries.
        if (cachedOpt.isEmpty()) {
            diffService.diff(ctx).whenComplete((result, ex) -> {
                if (ex != null || result == null || result.isEmpty()) {
                    SwingUtilities.invokeLater(table::repaint);
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    historyOutputPanel.adjustRowHeightForContext(ctx);
                    table.repaint();
                });
            });
        }

        // Primary action component rendered via the existing ActionCellRenderer (for consistent look).
        Component actionComp =
                fallback.getTableCellRendererComponent(table, actionText, isSelected, hasFocus, row, column);
        if (actionComp instanceof JComponent jc) {
            jc.setOpaque(false); // Allow the outer panel to control background painting.
        }

        // Reset and configure the flyweight outer panel
        outerPanel.removeAll();
        outerPanel.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
        outerPanel.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());

        int indentPx = indentLevel * Constants.H_GAP * 3;
        outerPanel.setBorder(new EmptyBorder(0, indentPx, 0, 0));
        outerPanel.add(actionComp, BorderLayout.NORTH);

        // Ensure tooltip is visible even though we return a composite panel.
        outerPanel.setToolTipText(historyOutputPanel.buildTooltipWithModel(ctx, actionText));

        List<Context.DiffEntry> diffs = cachedOpt.orElseGet(List::of);
        if (!diffs.isEmpty()) {
            boolean isDark = chrome.getTheme().isDarkTheme();
            Color plusColor = ThemeColors.getColor(isDark, "diff_added_fg");
            Color minusColor = ThemeColors.getColor(isDark, "diff_deleted_fg");

            ensureDiffRowCapacity(diffs.size());

            for (int i = 0; i < diffRows.size(); i++) {
                DiffRow dr = diffRows.get(i);
                if (i < diffs.size()) {
                    Context.DiffEntry de = diffs.get(i);
                    String bareName = computeBareName(de);

                    dr.nameLabel.setText(bareName + " ");
                    dr.nameLabel.setFont(smallFont);
                    dr.nameLabel.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());

                    dr.plusLabel.setText("+" + de.linesAdded());
                    dr.plusLabel.setFont(smallFont);
                    dr.plusLabel.setForeground(plusColor);

                    dr.minusLabel.setText("-" + de.linesDeleted());
                    dr.minusLabel.setFont(smallFont);
                    dr.minusLabel.setForeground(minusColor);

                    dr.panel.setVisible(true);
                } else {
                    dr.panel.setVisible(false);
                }
            }

            diffPanel.setVisible(true);
            outerPanel.add(diffPanel, BorderLayout.CENTER);
        } else {
            diffPanel.setVisible(false);
        }

        return outerPanel;
    }

    /**
     * Ensures that the diffRows pool has at least {@code needed} entries, creating new rows
     * and adding them to the diffPanel only when necessary.
     */
    private void ensureDiffRowCapacity(int needed) {
        while (diffRows.size() < needed) {
            DiffRow dr = new DiffRow();
            diffRows.add(dr);
            diffPanel.add(dr.panel);
        }
    }

    /**
     * Computes a short display name for a diff entry: preferably the filename of the first ProjectFile,
     * or the fragment's shortDescription() as a fallback.
     */
    private String computeBareName(Context.DiffEntry de) {
        try {
            var fragment = de.fragment();
            Set<ProjectFile> files = Set.of();
            var computedFilesOpt = fragment.files();
            var filesOpt = computedFilesOpt.tryGet();
            if (filesOpt.isPresent()) {
                files = filesOpt.get();
            }
            // ComputedSubscription.bind has been moved to HistoryOutputPanel to avoid
            // registering listeners from within the rendering path.
            if (!files.isEmpty()) {
                var pf = files.iterator().next();
                return pf.getRelPath().getFileName().toString();
            } else {
                return fragment.shortDescription().renderNowOr("(Loading...)");
            }
        } catch (Exception ex) {
            return de.fragment().shortDescription().renderNowOr("(Loading...)");
        }
    }

    /**
     * Simple flyweight row structure: panel + three labels.
     */
    private static final class DiffRow {
        final JPanel panel;
        final JLabel nameLabel;
        final JLabel plusLabel;
        final JLabel minusLabel;

        DiffRow() {
            panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            panel.setOpaque(false);

            nameLabel = new JLabel();
            plusLabel = new JLabel();
            minusLabel = new JLabel();

            panel.add(nameLabel);
            panel.add(plusLabel);
            panel.add(minusLabel);
        }
    }
}
