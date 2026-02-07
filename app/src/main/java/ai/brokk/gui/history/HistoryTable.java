package ai.brokk.gui.history;

import static ai.brokk.gui.ActivityTableRenderers.COL_ACTION;
import static ai.brokk.gui.ActivityTableRenderers.COL_CONTEXT;
import static ai.brokk.gui.ActivityTableRenderers.COL_ICON;

import ai.brokk.ContextManager;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.context.ComputedSubscription;
import ai.brokk.context.Context;
import ai.brokk.context.ContextHistory;
import ai.brokk.gui.ActivityTableRenderers;
import ai.brokk.gui.ActivityTableRenderers.ActionText;
import ai.brokk.gui.ActivityTableRenderers.GroupRow;
import ai.brokk.gui.ActivityTableRenderers.TriangleIcon;
import ai.brokk.gui.BorderUtils;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.util.Icons;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.jetbrains.annotations.Nullable;

/**
 * A reusable component for displaying Context History in a table with grouping, arrows, and diff support.
 */
public class HistoryTable extends JPanel {
    private final JTable table;
    private final DefaultTableModel model;
    private final JScrollPane scrollPane;
    private final ResetArrowLayerUI arrowLayerUI;
    private final ContextManager contextManager;
    private boolean suppressSelectionEvents = false;

    @SuppressWarnings("unused")
    private final Chrome chrome;

    private final Map<UUID, Boolean> groupExpandedState = new HashMap<>();
    private volatile List<HistoryGrouping.GroupDescriptor> latestDescriptors = List.of();
    private @Nullable ContextHistory currentHistory;

    private final List<Consumer<Context>> selectionListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> selectionClearedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Context>> doubleClickListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<Context, MouseEvent>> contextMenuListeners = new CopyOnWriteArrayList<>();

    // Viewport preservation
    private enum PendingSelectionType {
        NONE,
        CLEAR,
        FIRST_IN_GROUP
    }

    private PendingSelectionType pendingSelectionType = PendingSelectionType.NONE;
    private @Nullable UUID pendingSelectionGroupKey = null;
    private boolean suppressScrollOnNextUpdate = false;
    private @Nullable Point pendingViewportPosition = null;

    public HistoryTable(ContextManager contextManager, Chrome chrome) {
        super(new BorderLayout());
        this.contextManager = contextManager;
        this.chrome = chrome;

        // Model init
        this.model = new DefaultTableModel(new Object[] {"", "Action", "Context"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // Table init
        this.table = new JTable(model);
        table.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setTableHeader(null);

        // Renderers
        table.getColumnModel().getColumn(COL_ICON).setCellRenderer(new ActivityTableRenderers.IndentedIconRenderer());
        table.getColumnModel()
                .getColumn(COL_ACTION)
                .setCellRenderer(new HistoryCellRenderer(this, contextManager, chrome));

        // Widths
        table.getColumnModel().getColumn(COL_ICON).setPreferredWidth(38);
        table.getColumnModel().getColumn(COL_ICON).setMinWidth(38);
        table.getColumnModel().getColumn(COL_ICON).setMaxWidth(38);
        table.getColumnModel().getColumn(COL_ACTION).setPreferredWidth(150);
        table.getColumnModel().getColumn(COL_CONTEXT).setMinWidth(0);
        table.getColumnModel().getColumn(COL_CONTEXT).setMaxWidth(0);
        table.getColumnModel().getColumn(COL_CONTEXT).setWidth(0);

        // Arrow Layer
        this.arrowLayerUI = new ResetArrowLayerUI(table, chrome, () -> latestDescriptors);

        // ScrollPane
        this.scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        var layer = new JLayer<>(scrollPane, arrowLayerUI);

        // Listeners for repainting layer and requesting diffs
        scrollPane.getViewport().addChangeListener(e -> {
            layer.repaint();
            requestVisibleDiffs();
        });

        // Add focus border and standard navigation keys
        BorderUtils.addFocusBorder(scrollPane, table);
        setupKeyboardNavigation();
        setupMouseListeners();
        setupSelectionHandler();

        add(layer, BorderLayout.CENTER);
    }

    public void addSelectionListener(Consumer<Context> listener) {
        selectionListeners.add(listener);
    }

    public void addSelectionClearedListener(Runnable listener) {
        selectionClearedListeners.add(listener);
    }

    public void addDoubleClickListener(Consumer<Context> listener) {
        doubleClickListeners.add(listener);
    }

    public void addContextMenuListener(BiConsumer<Context, MouseEvent> listener) {
        contextMenuListeners.add(listener);
    }

    public JTable getTable() {
        return table;
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public DefaultTableModel getModel() {
        return model;
    }

    /**
     * Updates the table with the given history, optionally selecting the specified context.
     * Must be called on EDT.
     */
    private static @Nullable Context getContextFromModelValue(Object val) {
        if (val instanceof ActivityTableRenderers.ContextUiModel uiModel) {
            return uiModel.context();
        }
        return null;
    }

    public void setHistory(ContextHistory history, @Nullable Context contextToSelect) {
        suppressSelectionEvents = true;
        try {
            setHistoryInternal(history, contextToSelect);
        } finally {
            suppressSelectionEvents = false;
        }
    }

    private void setHistoryInternal(ContextHistory history, @Nullable Context contextToSelect) {
        // Reset any per-row height customizations before rebuilding
        table.setRowHeight(table.getRowHeight());

        // Update state logic if the session changed (heuristic based on history object identity)
        if (this.currentHistory != history) {
            groupExpandedState.clear();
            this.currentHistory = history;
        }

        model.setRowCount(0);

        var contexts = history.getHistory();
        if (contexts.isEmpty()) {
            latestDescriptors = List.of();
            arrowLayerUI.setResetEdges(List.of());
            return;
        }

        var descriptors = HistoryGrouping.GroupingBuilder.discoverGroups(contexts, history);
        latestDescriptors = descriptors;

        var resetEdges = history.getResetEdges();
        var resetTargetIds =
                resetEdges.stream().map(ContextHistory.ResetEdge::targetId).collect(Collectors.toSet());

        int rowToSelect = -1;
        int currentRow = 0;

        for (var descriptor : descriptors) {
            var children = descriptor.children();

            if (!descriptor.shouldShowHeader()) {
                var ctx = children.getFirst();
                Context prev = history.previousOf(ctx);
                Icon icon = history.isAiResult(ctx) ? Icons.CHAT_BUBBLE : null;

                ComputedValue<String> description = resetTargetIds.contains(ctx.id())
                        ? ComputedValue.completed("Copy From History")
                        : ctx.getAction(prev);

                ComputedSubscription.bind(description, table, table::repaint);
                var actionVal = new ActionText(description, 0);
                var uiModel = new ActivityTableRenderers.ContextUiModel(ctx, history.isAiResult(ctx));
                model.addRow(new Object[] {icon, actionVal, uiModel});

                if (ctx.equals(contextToSelect)) {
                    rowToSelect = currentRow;
                }
                currentRow++;
                continue;
            }

            // Group header
            var uuidKey = UUID.fromString(descriptor.key());
            boolean expandedDefault = descriptor.isLastGroup();
            boolean expanded = groupExpandedState.computeIfAbsent(uuidKey, k -> expandedDefault);

            boolean containsClearHistory = children.stream().anyMatch(c -> {
                var prev = history.previousOf(c);
                return prev != null
                        && !prev.getTaskHistory().isEmpty()
                        && c.getTaskHistory().isEmpty();
            });

            var groupRow = new GroupRow(uuidKey, expanded, containsClearHistory);
            var headerLabel = descriptor.label();
            ComputedSubscription.bind(headerLabel, table, table::repaint);

            model.addRow(new Object[] {new TriangleIcon(expanded), headerLabel, groupRow});
            currentRow++;

            if (expanded) {
                for (Context child : children) {
                    Context prev = history.previousOf(child);
                    var childDesc = child.getAction(prev);
                    ComputedSubscription.bind(childDesc, table, table::repaint);
                    var childAction = new ActionText(childDesc, 1);
                    Icon childIcon = history.isAiResult(child) ? Icons.CHAT_BUBBLE : null;
                    var uiModel = new ActivityTableRenderers.ContextUiModel(child, history.isAiResult(child));
                    model.addRow(new Object[] {childIcon, childAction, uiModel});

                    if (child.equals(contextToSelect)) {
                        rowToSelect = currentRow;
                    }
                    currentRow++;
                }
            }
        }

        arrowLayerUI.setResetEdges(resetEdges);

        applyPendingSelectionAndScroll(rowToSelect);

        // Post-update adjustment for diffs
        var diffService = history.getDiffService();
        for (int row = 0; row < model.getRowCount(); row++) {
            Object v = model.getValueAt(row, COL_CONTEXT);
            Context ctxRow = getContextFromModelValue(v);
            if (ctxRow != null) {
                var diffsOpt = diffService.peek(ctxRow);
                if (diffsOpt.isPresent() && !diffsOpt.get().isEmpty()) {
                    adjustRowHeightForContext(ctxRow);
                }
            }
        }
        requestVisibleDiffs();
    }

    private void applyPendingSelectionAndScroll(int rowToSelect) {
        // Apply pending selection directive, if any
        if (pendingSelectionType == PendingSelectionType.FIRST_IN_GROUP && pendingSelectionGroupKey != null) {
            int headerRow = findGroupHeaderRow(pendingSelectionGroupKey);
            int candidate = headerRow >= 0 ? headerRow + 1 : -1;
            if (candidate >= 0 && candidate < model.getRowCount()) {
                Object v = model.getValueAt(candidate, COL_CONTEXT);
                if (getContextFromModelValue(v) != null) {
                    rowToSelect = candidate;
                }
            }
        }

        boolean suppress = suppressScrollOnNextUpdate;

        if (pendingSelectionType == PendingSelectionType.CLEAR) {
            table.clearSelection();
        } else if (rowToSelect >= 0) {
            table.setRowSelectionInterval(rowToSelect, rowToSelect);
            if (!suppress) {
                table.scrollRectToVisible(table.getCellRect(rowToSelect, COL_ICON, true));
            }
        } else if (!suppress && model.getRowCount() > 0 && contextManager.isLive()) {
            // Only auto-scroll to bottom if looking at live session, typically.
            // But HistoryOutputPanel logic was simple: if not suppressed, scroll to bottom.
            int lastRow = model.getRowCount() - 1;
            table.setRowSelectionInterval(lastRow, lastRow);
            table.scrollRectToVisible(table.getCellRect(lastRow, COL_ICON, true));
        }

        // Restore viewport if requested
        if (suppress && pendingViewportPosition != null) {
            Point desired = pendingViewportPosition;
            SwingUtilities.invokeLater(() -> {
                scrollPane.getViewport().setViewPosition(clampViewportPosition(scrollPane, desired));
            });
        }

        // Reset directives
        pendingSelectionType = PendingSelectionType.NONE;
        pendingSelectionGroupKey = null;
        suppressScrollOnNextUpdate = false;
        pendingViewportPosition = null;
    }

    private void setupSelectionHandler() {
        table.getSelectionModel().addListSelectionListener(e -> {
            if (suppressSelectionEvents) return;
            int row = table.getSelectedRow();
            if (row >= 0 && row < table.getRowCount()) {
                Object val = model.getValueAt(row, COL_CONTEXT);
                Context ctx = getContextFromModelValue(val);
                if (ctx != null) {
                    selectionListeners.forEach(l -> l.accept(ctx));
                    // Diff is requested by requestVisibleDiffs() for visible rows;
                    // no need to trigger row height adjustment here which causes scroll bounce
                } else {
                    selectionClearedListeners.forEach(Runnable::run);
                }
            } else {
                selectionClearedListeners.forEach(Runnable::run);
            }
        });
    }

    private void setupKeyboardNavigation() {
        // Allow Tab/Shift+Tab to exit the activity table instead of trapping focus
        table.setFocusTraversalKeysEnabled(false);
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "sessActNext");
        table.getActionMap().put("sessActNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                table.transferFocus();
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), "sessActPrev");
        table.getActionMap().put("sessActPrev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                table.transferFocusBackward();
            }
        });
    }

    private void setupMouseListeners() {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) return;

                Object val = model.getValueAt(row, COL_CONTEXT);

                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (val instanceof GroupRow) {
                        toggleGroupRow(row);
                    } else if (e.getClickCount() == 2) {
                        Context ctx = getContextFromModelValue(val);
                        if (ctx != null) {
                            doubleClickListeners.forEach(l -> l.accept(ctx));
                        }
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row < 0) return;
                    Object val = model.getValueAt(row, COL_CONTEXT);
                    Context ctx = getContextFromModelValue(val);
                    if (ctx != null) {
                        suppressSelectionEvents = true;
                        try {
                            table.setRowSelectionInterval(row, row);
                        } finally {
                            suppressSelectionEvents = false;
                        }
                        contextMenuListeners.forEach(l -> l.accept(ctx, e));
                    }
                }
            }
        });

        // Pass clicks from empty viewport to table to request focus
        scrollPane.getViewport().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getSource() == scrollPane.getViewport()) {
                    table.requestFocusInWindow();
                }
            }
        });
    }

    private void toggleGroupRow(int row) {
        Object val = model.getValueAt(row, COL_CONTEXT);
        if (!(val instanceof GroupRow groupRow)) {
            return;
        }
        boolean newState = !groupExpandedState.getOrDefault(groupRow.key(), groupRow.expanded());
        groupExpandedState.put(groupRow.key(), newState);

        // Set selection directive
        if (newState) {
            pendingSelectionType = PendingSelectionType.FIRST_IN_GROUP;
        } else {
            pendingSelectionType = PendingSelectionType.CLEAR;
        }
        pendingSelectionGroupKey = groupRow.key();

        // Preserve viewport
        pendingViewportPosition = scrollPane.getViewport().getViewPosition();
        suppressScrollOnNextUpdate = true;

        if (currentHistory != null) {
            setHistory(currentHistory, null);
        }
    }

    private int findGroupHeaderRow(UUID groupKey) {
        for (int i = 0; i < model.getRowCount(); i++) {
            var v = model.getValueAt(i, COL_CONTEXT);
            if (v instanceof GroupRow gr && gr.key().equals(groupKey)) {
                return i;
            }
        }
        return -1;
    }

    public void adjustRowHeightForContext(Context ctx) {
        assert SwingUtilities.isEventDispatchThread() : "adjustRowHeightForContext must be called on EDT";

        int targetRow = -1;
        for (int row = 0; row < model.getRowCount(); row++) {
            Object val = model.getValueAt(row, COL_CONTEXT);
            if (val instanceof ActivityTableRenderers.ContextUiModel uiModel
                    && uiModel.context().equals(ctx)) {
                targetRow = row;
                break;
            }
        }
        if (targetRow < 0) {
            return;
        }

        int actionCol = COL_ACTION;
        if (actionCol >= table.getColumnCount()) {
            return;
        }

        var renderer = table.getCellRenderer(targetRow, actionCol);
        Component comp = table.prepareRenderer(renderer, targetRow, actionCol);

        int colWidth = table.getColumnModel().getColumn(actionCol).getWidth();
        comp.setSize(colWidth, Short.MAX_VALUE);
        int prefHeight = Math.max(18, comp.getPreferredSize().height + 2);

        if (table.getRowHeight(targetRow) != prefHeight) {
            table.setRowHeight(targetRow, prefHeight);
        }
    }

    private void requestVisibleDiffs() {
        if (table.getRowCount() == 0 || currentHistory == null) {
            return;
        }
        var viewport = scrollPane.getViewport();
        if (viewport == null) {
            return;
        }
        var ds = currentHistory.getDiffService();
        Rectangle viewRect = viewport.getViewRect();

        int first = table.rowAtPoint(new Point(viewRect.x, viewRect.y));
        if (first < 0) first = 0;
        int last = table.rowAtPoint(new Point(viewRect.x, viewRect.y + viewRect.height - 1));
        if (last < 0) last = table.getRowCount() - 1;

        for (int row = first; row <= last; row++) {
            Object v = model.getValueAt(row, COL_CONTEXT);
            if (v instanceof ActivityTableRenderers.ContextUiModel uiModel) {
                Context ctx = uiModel.context();
                ds.diff(ctx).thenAccept(d -> SwingUtilities.invokeLater(() -> adjustRowHeightForContext(ctx)));
            }
        }

        int sel = table.getSelectedRow();
        if (sel >= 0 && sel < table.getRowCount()) {
            Object sv = model.getValueAt(sel, COL_CONTEXT);
            if (sv instanceof ActivityTableRenderers.ContextUiModel uiModel) {
                Context sctx = uiModel.context();
                ds.diff(sctx).thenAccept(d -> SwingUtilities.invokeLater(() -> adjustRowHeightForContext(sctx)));
            }
        }
    }

    /**
     * Returns the currently selected Context, or null if no Context row is selected.
     */
    public @Nullable Context getSelectedContext() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= model.getRowCount()) {
            return null;
        }
        return getContextFromModelValue(model.getValueAt(row, COL_CONTEXT));
    }

    private static Point clampViewportPosition(JScrollPane sp, Point desired) {
        JViewport vp = sp.getViewport();
        if (vp == null) return desired;
        Component view = vp.getView();
        if (view == null) return desired;
        Dimension viewSize = view.getSize();
        Dimension extent = vp.getExtentSize();
        int maxX = Math.max(0, viewSize.width - extent.width);
        int maxY = Math.max(0, viewSize.height - extent.height);
        int x = Math.max(0, Math.min(desired.x, maxX));
        int y = Math.max(0, Math.min(desired.y, maxY));
        return new Point(x, y);
    }
}
