package io.github.jbellis.brokk.gui.dialogs;

import static io.github.jbellis.brokk.SessionManager.SessionInfo;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.SessionRegistry;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.gui.ActivityTableRenderers;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.WorkspacePanel;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.plaf.LayerUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import org.jetbrains.annotations.Nullable;

/** Modal dialog for managing sessions with Activity log, Workspace panel, and MOP preview */
public class SessionsDialog extends JDialog {
    private final Chrome chrome;
    private final ContextManager contextManager;

    // Sessions table components
    private JTable sessionsTable;
    private DefaultTableModel sessionsTableModel;
    private MaterialButton closeButton;

    // Activity history components
    private JTable activityTable;
    private DefaultTableModel activityTableModel;
    private ResetArrowLayerUI arrowLayerUI;

    // Preview components
    private WorkspacePanel workspacePanel;
    private MarkdownOutputPanel markdownOutputPanel;
    private JScrollPane markdownScrollPane;
    private @Nullable Context selectedActivityContext;

    public SessionsDialog(Chrome chrome, ContextManager contextManager) {
        super(chrome.getFrame(), "Manage Sessions", true);
        this.chrome = chrome;
        this.contextManager = contextManager;

        initializeComponents();
        layoutComponents();
        setupEventHandlers();
        refreshSessionsTable();

        // Set larger size for 4-panel layout
        setSize(1400, 800);
        setLocationRelativeTo(chrome.getFrame());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void initializeComponents() {
        // Initialize sessions table model with Active, Session Name, Date, and hidden SessionInfo columns
        sessionsTableModel = new DefaultTableModel(new Object[] {"Active", "Session Name", "Date", "SessionInfo"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // Initialize sessions table
        sessionsTable = new JTable(sessionsTableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                java.awt.Point p = event.getPoint();
                int rowIndex = rowAtPoint(p);
                if (rowIndex >= 0 && rowIndex < getRowCount()) {
                    SessionInfo sessionInfo = (SessionInfo) sessionsTableModel.getValueAt(rowIndex, 3);
                    return "Last modified: "
                            + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(sessionInfo.modified()));
                }
                return super.getToolTipText(event);
            }
        };
        sessionsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        sessionsTable.setTableHeader(null);

        // Set up column renderers for sessions table
        sessionsTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label =
                        (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                label.setHorizontalAlignment(JLabel.CENTER);
                return label;
            }
        });

        // Initialize activity table model
        activityTableModel = new DefaultTableModel(new Object[] {"", "Action", "Context"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // Initialize activity table
        activityTable = new JTable(activityTableModel);
        activityTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        activityTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        activityTable.setTableHeader(null);

        // Set up custom renderers for activity table columns
        activityTable.getColumnModel().getColumn(0).setCellRenderer(new ActivityTableRenderers.IconCellRenderer());
        activityTable.getColumnModel().getColumn(1).setCellRenderer(new ActivityTableRenderers.ActionCellRenderer());

        // Adjust activity table column widths
        activityTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        activityTable.getColumnModel().getColumn(0).setMinWidth(30);
        activityTable.getColumnModel().getColumn(0).setMaxWidth(30);
        activityTable.getColumnModel().getColumn(1).setPreferredWidth(250);
        activityTable.getColumnModel().getColumn(2).setMinWidth(0);
        activityTable.getColumnModel().getColumn(2).setMaxWidth(0);
        activityTable.getColumnModel().getColumn(2).setWidth(0);

        // Initialize workspace panel for preview (copy-only menu)
        workspacePanel = new WorkspacePanel(chrome, contextManager, WorkspacePanel.PopupMenuMode.COPY_ONLY);
        workspacePanel.setWorkspaceEditable(false); // Make workspace read-only in manage dialog

        // Initialize markdown output panel for preview
        markdownOutputPanel = new MarkdownOutputPanel();
        markdownOutputPanel.withContextForLookups(contextManager, chrome);
        markdownOutputPanel.updateTheme(chrome.getTheme().isDarkTheme());
        markdownScrollPane = new JScrollPane(markdownOutputPanel);
        markdownScrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        markdownScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // Initialize arrow layer UI for activity table
        this.arrowLayerUI = new ResetArrowLayerUI(this.activityTable, this.activityTableModel);

        // Initialize buttons
        closeButton = new MaterialButton("Close");
    }

    private void layoutComponents() {
        setLayout(new BorderLayout());

        // Create sessions panel
        JPanel sessionsPanel = new JPanel(new BorderLayout());
        sessionsPanel.setBorder(BorderFactory.createTitledBorder("Sessions"));
        JScrollPane sessionsScrollPane = new JScrollPane(sessionsTable);
        sessionsPanel.add(sessionsScrollPane, BorderLayout.CENTER);

        // Create activity panel
        JPanel activityPanel = new JPanel(new BorderLayout());
        activityPanel.setBorder(BorderFactory.createTitledBorder("Activity"));
        JScrollPane activityScrollPane = new JScrollPane(activityTable);
        activityScrollPane.setBorder(
                BorderFactory.createEmptyBorder(5, 5, 10, 5)); // slight bottom pad to align with Output
        var layer = new JLayer<>(activityScrollPane, arrowLayerUI);
        activityScrollPane.getViewport().addChangeListener(e -> layer.repaint());
        activityPanel.add(layer, BorderLayout.CENTER);

        // Create workspace panel without additional border (workspacePanel already has its own border)
        JPanel workspacePanelContainer = new JPanel(new BorderLayout());
        workspacePanelContainer.add(workspacePanel, BorderLayout.CENTER);

        // Create MOP panel
        JPanel mopPanel = new JPanel(new BorderLayout());
        mopPanel.setBorder(BorderFactory.createTitledBorder("Output"));
        mopPanel.add(markdownScrollPane, BorderLayout.CENTER);

        // Create top row with Sessions (30%), Activity (30%), and MOP (40%) horizontal space
        JSplitPane topFirstSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionsPanel, activityPanel);
        topFirstSplit.setResizeWeight(0.6); // 1.5 : 1  => 0.6 of the sub-split goes to Sessions

        JSplitPane topSecondSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, topFirstSplit, mopPanel);
        topSecondSplit.setResizeWeight(5.0 / 9.0); // (1.5+1) : 2  => 5/9 ≈ 0.556 goes to Sessions+Activity

        // Set divider locations after the dialog is shown to achieve 30%/30%/40% split
        SwingUtilities.invokeLater(() -> {
            int totalWidth = topSecondSplit.getWidth();
            if (totalWidth > 0) {
                // 1.5 : 1 : 2  (total units 4.5)
                // First divider at 1/3 of total width (Sessions width)
                topFirstSplit.setDividerLocation(totalWidth / 3);
                // Second divider at (1/3 + 2/9) = 5/9 of total width (Sessions + Activity)
                topSecondSplit.setDividerLocation((5 * totalWidth) / 9);
            }
        });

        // Create main vertical split with top row and workspace below
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSecondSplit, workspacePanelContainer);
        mainSplit.setResizeWeight(0.75); // Top gets 75%, workspace gets 25%

        // Create button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        buttonPanel.add(closeButton);

        // Add components to dialog
        add(mainSplit, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void setupEventHandlers() {
        // Session selection listener - load session history instead of switching
        sessionsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int[] selected = sessionsTable.getSelectedRows();
            if (selected.length == 1) {
                var info = (SessionInfo) sessionsTableModel.getValueAt(selected[0], 3);
                loadSessionHistory(info.id());
            } else { // multi-select: clear preview panels
                activityTableModel.setRowCount(0);
                clearPreviewPanels();
            }
        });

        // Activity selection listener - update workspace and MOP
        activityTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = activityTable.getSelectedRow();
                if (row >= 0 && row < activityTable.getRowCount()) {
                    selectedActivityContext = (Context) activityTableModel.getValueAt(row, 2);
                    updatePreviewPanels(selectedActivityContext);
                } else {
                    clearPreviewPanels();
                }
            }
        });

        // Add mouse listener for right-click context menu and double-click on sessions table
        sessionsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showSessionContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showSessionContextMenu(e);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = sessionsTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        SessionInfo sessionInfo = (SessionInfo) sessionsTableModel.getValueAt(row, 2);
                        renameSession(sessionInfo);
                    }
                }
            }
        });

        // Button listeners
        closeButton.addActionListener(e -> dispose());

        // ESC key to close
        var rootPane = getRootPane();
        var actionMap = rootPane.getActionMap();
        var inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog");
        actionMap.put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });
    }

    private void loadSessionHistory(UUID sessionId) {
        // Clear current preview panels
        clearPreviewPanels();

        // Load session history asynchronously
        contextManager
                .loadSessionHistoryAsync(sessionId)
                .thenAccept(history -> {
                    SwingUtilities.invokeLater(() -> {
                        populateActivityTable(history);
                    });
                })
                .exceptionally(throwable -> {
                    SwingUtilities.invokeLater(() -> {
                        chrome.toolError("Failed to load session history: " + throwable.getMessage());
                        activityTableModel.setRowCount(0);
                    });
                    return null;
                });
    }

    private void populateActivityTable(ContextHistory history) {
        activityTableModel.setRowCount(0);

        if (history.getHistory().isEmpty()) {
            arrowLayerUI.setResetEdges(List.of());
            return;
        }

        // Add rows for each context in history
        for (var ctx : history.getHistory()) {
            // Add icon for AI responses, null for user actions
            boolean hasAiMessages = ctx.getParsedOutput() != null
                    && ctx.getParsedOutput().messages().stream()
                            .anyMatch(chatMessage -> chatMessage.type() == ChatMessageType.AI);
            Icon iconEmoji = hasAiMessages ? Icons.AI_ROBOT : null;
            activityTableModel.addRow(
                    new Object[] {iconEmoji, ctx.getAction(), ctx // Store the actual context object in hidden column
                    });
        }

        // Update reset edges for arrow painter
        var resetEdges = history.getResetEdges();
        arrowLayerUI.setResetEdges(resetEdges);

        // Select the most recent item (last row) if available
        SwingUtilities.invokeLater(() -> {
            if (activityTableModel.getRowCount() > 0) {
                int lastRow = activityTableModel.getRowCount() - 1;
                activityTable.setRowSelectionInterval(lastRow, lastRow);
                activityTable.scrollRectToVisible(activityTable.getCellRect(lastRow, 0, true));
            }
        });
    }

    private void updatePreviewPanels(Context context) {
        // Update workspace panel with selected context
        workspacePanel.populateContextTable(context);

        // Update MOP with task history if available
        var taskHistory = context.getTaskHistory();
        if (taskHistory.isEmpty()) {
            // Fall back to parsed output for contexts that are not part of a task history
            if (context.getParsedOutput() != null) {
                markdownOutputPanel.setMainThenHistoryAsync(
                        context.getParsedOutput().messages(), List.of());
            } else {
                markdownOutputPanel.clear(); // clears main view, history, and cache
            }
        } else {
            var history = taskHistory.subList(0, taskHistory.size() - 1);
            var main = taskHistory.getLast();
            markdownOutputPanel.setMainThenHistoryAsync(main, history);
        }
    }

    private void clearPreviewPanels() {
        // Clear workspace panel
        workspacePanel.populateContextTable(null);

        // Clear MOP
        markdownOutputPanel.clear();
    }

    public void refreshSessionsTable() {
        sessionsTableModel.setRowCount(0);
        List<SessionInfo> sessions =
                contextManager.getProject().getSessionManager().listSessions();
        sessions.sort(java.util.Comparator.comparingLong(SessionInfo::modified).reversed()); // Sort newest first

        UUID currentSessionId = contextManager.getCurrentSessionId();
        for (var session : sessions) {
            String active = session.id().equals(currentSessionId) ? "✓" : "";
            var date = GitUiUtil.formatRelativeDate(
                    Instant.ofEpochMilli(session.modified()), LocalDate.now(ZoneId.systemDefault()));
            sessionsTableModel.addRow(new Object[] {active, session.name(), date, session});
        }

        // Hide the "SessionInfo" column
        sessionsTable.getColumnModel().getColumn(3).setMinWidth(0);
        sessionsTable.getColumnModel().getColumn(3).setMaxWidth(0);
        sessionsTable.getColumnModel().getColumn(3).setWidth(0);

        // Set column widths for sessions table
        sessionsTable.getColumnModel().getColumn(0).setPreferredWidth(20);
        sessionsTable.getColumnModel().getColumn(0).setMaxWidth(20);
        sessionsTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        sessionsTable.getColumnModel().getColumn(2).setPreferredWidth(110);
        sessionsTable.getColumnModel().getColumn(2).setMaxWidth(110);

        // Select current session and load its history
        for (int i = 0; i < sessionsTableModel.getRowCount(); i++) {
            SessionInfo rowInfo = (SessionInfo) sessionsTableModel.getValueAt(i, 3);
            if (rowInfo.id().equals(currentSessionId)) {
                sessionsTable.setRowSelectionInterval(i, i);
                loadSessionHistory(rowInfo.id()); // Load history for current session
                break;
            }
        }
    }

    private void showSessionContextMenu(MouseEvent e) {
        int row = sessionsTable.rowAtPoint(e.getPoint());
        if (row < 0) return;

        // If right-click happens on a row not already selected, switch to that single row
        if (!sessionsTable.isRowSelected(row)) {
            sessionsTable.setRowSelectionInterval(row, row);
        }

        int[] selectedRows = sessionsTable.getSelectedRows();
        var selectedSessions = java.util.Arrays.stream(selectedRows)
                .mapToObj(r -> (SessionInfo) sessionsTableModel.getValueAt(r, 3))
                .toList();

        JPopupMenu popup = new JPopupMenu();

        /* ---------- single-selection items ---------- */
        if (selectedSessions.size() == 1) {
            var sessionInfo = selectedSessions.getFirst();

            JMenuItem setActiveItem = new JMenuItem("Set as Active");
            setActiveItem.setEnabled(!sessionInfo.id().equals(contextManager.getCurrentSessionId()));
            setActiveItem.addActionListener(
                    ev -> contextManager.switchSessionAsync(sessionInfo.id()).thenRun(() -> {
                        SwingUtilities.invokeLater(this::refreshSessionsTable);
                        contextManager.getProject().getMainProject().sessionsListChanged();
                    }));
            popup.add(setActiveItem);
            popup.addSeparator();

            JMenuItem renameItem = new JMenuItem("Rename");
            renameItem.addActionListener(ev -> renameSession(sessionInfo));
            popup.add(renameItem);
        }

        /* ---------- delete (single or multi) ---------- */
        JMenuItem deleteItem = new JMenuItem(selectedSessions.size() == 1 ? "Delete" : "Delete Selected");
        deleteItem.addActionListener(ev -> {
            int confirm = chrome.showConfirmDialog(
                    SessionsDialog.this,
                    "Are you sure you want to delete the selected session(s)?",
                    "Confirm Delete",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                var partitionedSessions = selectedSessions.stream()
                        .collect(Collectors.partitioningBy(s -> SessionRegistry.isSessionActiveElsewhere(
                                contextManager.getProject().getRoot(), s.id())));
                // partitioning by boolean always returns mappings for both true and false keys
                var activeSessions = castNonNull(partitionedSessions.get(true));
                var deletableSessions = castNonNull(partitionedSessions.get(false));

                if (!activeSessions.isEmpty()) {
                    var sessionNames =
                            activeSessions.stream().map(SessionInfo::name).collect(Collectors.joining(", "));
                    chrome.toolError(
                            "Cannot delete sessions active in other worktrees: " + sessionNames, "Sessions in use");
                }

                if (deletableSessions.isEmpty()) {
                    return;
                }

                var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
                for (var s : deletableSessions) {
                    futures.add(contextManager.deleteSessionAsync(s.id()));
                }
                java.util.concurrent.CompletableFuture.allOf(
                                futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                        .whenComplete((Void v, @Nullable Throwable t) -> {
                            SwingUtilities.invokeLater(this::refreshSessionsTable);
                            contextManager.getProject().getMainProject().sessionsListChanged();
                            if (t != null) {
                                chrome.toolError("Error deleting session:\n" + t.getMessage());
                            }
                        });
            }
        });
        popup.add(deleteItem);

        /* ---------- duplicate (single or multi) ---------- */
        JMenuItem dupItem = new JMenuItem(selectedSessions.size() == 1 ? "Duplicate" : "Duplicate Selected");
        dupItem.addActionListener(ev -> {
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
            for (var s : selectedSessions) {
                futures.add(contextManager.copySessionAsync(s.id(), s.name()));
            }
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .thenRun(() -> {
                        SwingUtilities.invokeLater(this::refreshSessionsTable);
                        contextManager.getProject().getMainProject().sessionsListChanged();
                    });
        });
        popup.add(dupItem);

        // Register popup with theme manager
        chrome.getTheme().registerPopupMenu(popup);

        popup.show(sessionsTable, e.getX(), e.getY());
    }

    private void renameSession(SessionInfo sessionInfo) {
        String newName = JOptionPane.showInputDialog(
                SessionsDialog.this, "Enter new name for session '" + sessionInfo.name() + "':", sessionInfo.name());
        if (newName != null && !newName.trim().isBlank()) {
            contextManager
                    .renameSessionAsync(sessionInfo.id(), CompletableFuture.completedFuture(newName.trim()))
                    .thenRun(() -> {
                        SwingUtilities.invokeLater(this::refreshSessionsTable);
                        contextManager.getProject().getMainProject().sessionsListChanged();
                    });
        }
    }

    @Override
    public void dispose() {
        // Clean up MOP resources
        markdownOutputPanel.dispose();
        super.dispose();
    }

    /** A LayerUI that paints reset-from-history arrows over the history table. */
    private class ResetArrowLayerUI extends LayerUI<JScrollPane> {
        private final JTable table;
        private final DefaultTableModel model;
        private List<ContextHistory.ResetEdge> resetEdges = List.of();
        private final Map<ContextHistory.ResetEdge, Integer> edgePaletteIndices = new HashMap<>();
        private int nextPaletteIndex = 0;

        public ResetArrowLayerUI(JTable table, DefaultTableModel model) {
            this.table = table;
            this.model = model;
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
                            ? ColorUtil.brighter(Colors.getAdded(true), 0.4f)
                            : ColorUtil.brighter(Colors.getAdded(false), -0.4f),
                    isDark
                            ? ColorUtil.brighter(Colors.getChanged(true), 0.6f)
                            : ColorUtil.brighter(Colors.getChanged(false), -0.4f),
                    isDark
                            ? ColorUtil.brighter(Colors.getDeleted(true), 1.2f)
                            : ColorUtil.brighter(Colors.getDeleted(false), -0.4f));
            return palette.get(paletteIndex);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            super.paint(g, c);
            if (resetEdges.isEmpty()) {
                return;
            }

            Map<UUID, Integer> contextIdToRow = new HashMap<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                Context ctx = (Context) model.getValueAt(i, 2);
                if (ctx != null) {
                    contextIdToRow.put(ctx.id(), i);
                }
            }

            // 1. Build list of all possible arrows with their geometry
            List<Arrow> arrows = new ArrayList<>();
            for (var edge : resetEdges) {
                Integer sourceRow = contextIdToRow.get(edge.sourceId());
                Integer targetRow = contextIdToRow.get(edge.targetId());
                if (sourceRow != null && targetRow != null) {
                    var sourceRect = table.getCellRect(sourceRow, 0, true);
                    var targetRect = table.getCellRect(targetRow, 0, true);
                    int y1 = sourceRect.y + sourceRect.height / 2;
                    int y2 = targetRect.y + targetRect.height / 2;
                    arrows.add(new Arrow(edge, sourceRow, targetRow, Math.abs(y1 - y2)));
                }
            }

            // 2. Draw arrows, longest first to prevent shorter arrows from being hidden
            arrows.sort(Comparator.comparingInt((Arrow a) -> a.length).reversed());

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                float lineWidth = (float)
                        (c.getGraphicsConfiguration().getDefaultTransform().getScaleX() >= 2 ? 0.75 : 1.0);
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
            Point sourcePoint = SwingUtilities.convertPoint(
                    table, new Point(sourceRect.x, sourceRect.y + sourceRect.height / 2), c);
            Point targetPoint = SwingUtilities.convertPoint(
                    table, new Point(targetRect.x, targetRect.y + targetRect.height / 2), c);

            // Don't draw if either point is outside the visible viewport
            if (!c.getVisibleRect().contains(sourcePoint) && !c.getVisibleRect().contains(targetPoint)) {
                // a bit of a hack -- if just one is visible, we still want to draw part of the arrow
                if (c.getVisibleRect().contains(sourcePoint)
                        || c.getVisibleRect().contains(targetPoint)) {
                    // one is visible, fall through
                } else {
                    return;
                }
            }

            int iconColWidth = table.getColumnModel().getColumn(0).getWidth();
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

            var head = new Polygon(
                    new int[] {tipX, baseX, baseX}, new int[] {midY, midY - halfHeight, midY + halfHeight}, 3);
            g2.fill(head);
        }
    }

    // ---------- Static helpers for other UI components ----------
    public static void renameCurrentSession(Component parent, Chrome chrome, ContextManager contextManager) {
        var sessionManager = contextManager.getProject().getSessionManager();
        var currentId = contextManager.getCurrentSessionId();
        var maybeInfo = sessionManager.listSessions().stream()
                .filter(s -> s.id().equals(currentId))
                .findFirst();
        if (maybeInfo.isEmpty()) {
            chrome.toolError("Current session not found");
            return;
        }
        var info = maybeInfo.get();
        String newName =
                JOptionPane.showInputDialog(parent, "Enter new name for session '" + info.name() + "':", info.name());
        if (newName != null && !newName.trim().isBlank()) {
            contextManager
                    .renameSessionAsync(
                            info.id(), java.util.concurrent.CompletableFuture.completedFuture(newName.trim()))
                    .thenRun(() -> contextManager.getProject().getMainProject().sessionsListChanged());
        }
    }

    public static void deleteCurrentSession(Component parent, Chrome chrome, ContextManager contextManager) {
        var sessionManager = contextManager.getProject().getSessionManager();
        var currentId = contextManager.getCurrentSessionId();
        var maybeInfo = sessionManager.listSessions().stream()
                .filter(s -> s.id().equals(currentId))
                .findFirst();
        if (maybeInfo.isEmpty()) {
            chrome.toolError("Current session not found");
            return;
        }
        var info = maybeInfo.get();
        int confirm = chrome.showConfirmDialog(
                parent,
                "Are you sure you want to delete the current session?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            contextManager
                    .deleteSessionAsync(info.id())
                    .thenRun(() -> contextManager.getProject().getMainProject().sessionsListChanged());
        }
    }
}
