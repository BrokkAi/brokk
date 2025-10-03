package io.github.jbellis.brokk.gui;

import static io.github.jbellis.brokk.SessionManager.SessionInfo;
import static java.util.Objects.requireNonNull;

import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.components.SpinnerIconUtil;
import io.github.jbellis.brokk.gui.components.SplitButton;
import io.github.jbellis.brokk.gui.dialogs.SessionsDialog;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.LayerUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class HistoryOutputPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(HistoryOutputPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final JTable historyTable;
    private final DefaultTableModel historyModel;
    private final MaterialButton undoButton;
    private final MaterialButton redoButton;
    private final MaterialButton compressButton;
    private final JCheckBox autoCompressCheckbox;
    private final JComboBox<SessionInfo> sessionComboBox;
    private final SplitButton newSessionButton;
    private final SplitButton manageSessionsButton;
    private ResetArrowLayerUI arrowLayerUI;

    @Nullable
    private JPanel sessionSwitchPanel;

    @Nullable
    private JLabel sessionSwitchSpinner;

    private JLayeredPane historyLayeredPane;

    @SuppressWarnings("NullAway.Init") // Initialized in constructor
    private JScrollPane historyScrollPane;

    // Output components
    private final MarkdownOutputPanel llmStreamArea;
    private final JScrollPane llmScrollPane;

    @Nullable
    private JTextArea captureDescriptionArea;

    private final MaterialButton copyButton;
    private final JPanel notificationAreaPanel;

    private final MaterialButton notificationsButton = new MaterialButton();
    private final java.util.List<NotificationEntry> notifications = new java.util.ArrayList<>();
    private final java.util.Queue<NotificationEntry> notificationQueue = new java.util.ArrayDeque<>();
    private final Path notificationsFile;
    private boolean isDisplayingNotification = false;

    public static enum NotificationRole {
        ERROR,
        CONFIRM,
        COST,
        INFO
    }

    // Resolve notification colors from ThemeColors for current theme.
    // Returns a list of [background, foreground, border] colors.
    private java.util.List<Color> resolveNotificationColors(NotificationRole role) {
        boolean isDark = chrome.themeManager.isDarkTheme();
        return switch (role) {
            case ERROR ->
                java.util.List.of(
                        ThemeColors.getColor(isDark, "notif_error_bg"),
                        ThemeColors.getColor(isDark, "notif_error_fg"),
                        ThemeColors.getColor(isDark, "notif_error_border"));
            case CONFIRM ->
                java.util.List.of(
                        ThemeColors.getColor(isDark, "notif_confirm_bg"),
                        ThemeColors.getColor(isDark, "notif_confirm_fg"),
                        ThemeColors.getColor(isDark, "notif_confirm_border"));
            case COST ->
                java.util.List.of(
                        ThemeColors.getColor(isDark, "notif_cost_bg"),
                        ThemeColors.getColor(isDark, "notif_cost_fg"),
                        ThemeColors.getColor(isDark, "notif_cost_border"));
            case INFO ->
                java.util.List.of(
                        ThemeColors.getColor(isDark, "notif_info_bg"),
                        ThemeColors.getColor(isDark, "notif_info_fg"),
                        ThemeColors.getColor(isDark, "notif_info_border"));
        };
    }

    private final List<OutputWindow> activeStreamingWindows = new ArrayList<>();

    // Diff caching
    private final Map<UUID, List<Context.DiffEntry>> diffCache = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> diffInFlight = ConcurrentHashMap.newKeySet();
    private Map<UUID, Context> previousContextMap = new HashMap<>();

    @Nullable
    private String lastSpinnerMessage = null; // Explicitly initialize

    // Track expand/collapse state for grouped non-LLM action runs
    private final Map<UUID, Boolean> groupExpandedState = new HashMap<>();

    // Selection directives applied after a table rebuild (for expand/collapse UX)
    private PendingSelectionType pendingSelectionType = PendingSelectionType.NONE;
    private @Nullable UUID pendingSelectionGroupKey = null;

    // Viewport preservation flags for group expand/collapse operations
    private boolean suppressScrollOnNextUpdate = false;
    private @Nullable Point pendingViewportPosition = null;

    // Session AI response counts and in-flight loaders
    private final Map<UUID, Integer> sessionAiResponseCounts = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> sessionCountLoading = ConcurrentHashMap.newKeySet();

    /**
     * Constructs a new HistoryOutputPane.
     *
     * @param chrome The parent Chrome instance
     * @param contextManager The context manager
     */
    public HistoryOutputPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout()); // Use BorderLayout
        this.chrome = chrome;
        this.contextManager = contextManager;

        // commandResultLabel initialization removed

        // Build combined Output + Instructions panel (Center)
        this.llmStreamArea = new MarkdownOutputPanel();
        this.llmStreamArea.withContextForLookups(contextManager, chrome);
        this.llmScrollPane = buildLLMStreamScrollPane(this.llmStreamArea);
        this.copyButton = new MaterialButton();
        SwingUtilities.invokeLater(() -> {
            this.copyButton.setIcon(Icons.CONTENT_COPY);
        });
        this.compressButton = new MaterialButton("Compress");
        this.autoCompressCheckbox = new JCheckBox("Auto");
        this.notificationAreaPanel = buildNotificationAreaPanel();
        var centerPanel = buildCombinedOutputInstructionsPanel(this.llmScrollPane, this.copyButton);
        add(centerPanel, BorderLayout.CENTER);

        // Initialize notification persistence and load saved notifications
        this.notificationsFile = computeNotificationsFile();
        loadPersistedNotifications();

        // Build session controls and activity panel (East)
        this.historyModel = new DefaultTableModel(new Object[] {"", "Action", "Context"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        this.historyTable = new JTable(this.historyModel);
        this.arrowLayerUI = new ResetArrowLayerUI(this.historyTable, this.historyModel);
        this.undoButton = new MaterialButton();
        this.redoButton = new MaterialButton();
        this.sessionComboBox = new JComboBox<>();
        this.newSessionButton = new SplitButton("New");
        this.manageSessionsButton = new SplitButton("Manage");

        this.historyLayeredPane = new JLayeredPane();
        this.historyLayeredPane.setLayout(new OverlayLayout(this.historyLayeredPane));

        var sessionControlsPanel =
                buildSessionControlsPanel(this.sessionComboBox, this.newSessionButton, this.manageSessionsButton);
        var activityPanel = buildActivityPanel(this.historyTable, this.undoButton, this.redoButton);

        // Create main history panel with session controls above activity
        var historyPanel = new JPanel(new BorderLayout());
        historyPanel.add(sessionControlsPanel, BorderLayout.NORTH);
        historyPanel.add(activityPanel, BorderLayout.CENTER);

        // Calculate preferred width to match old panel size
        int preferredWidth = 230;
        var preferredSize = new Dimension(preferredWidth, historyPanel.getPreferredSize().height);
        historyPanel.setPreferredSize(preferredSize);
        historyPanel.setMinimumSize(preferredSize);
        historyPanel.setMaximumSize(new Dimension(preferredWidth, Integer.MAX_VALUE));

        add(historyPanel, BorderLayout.EAST);

        // Set minimum sizes for the main panel
        setMinimumSize(new Dimension(300, 200)); // Example minimum size
    }

    private void buildSessionSwitchPanel() {
        // This is the main panel that will be added to the layered pane.
        // It uses BorderLayout to position its content at the top.
        sessionSwitchPanel = new JPanel(new BorderLayout());
        sessionSwitchPanel.setOpaque(true);
        sessionSwitchPanel.setVisible(false);

        // This is the panel that actually holds the spinner and text.
        // It will be placed at the top of sessionSwitchPanel.
        var contentPanel = new JPanel(new BorderLayout(5, 0)); // stretch horizontally
        contentPanel.setOpaque(false);
        contentPanel.setBorder(new EmptyBorder(8, 5, 5, 5));

        sessionSwitchSpinner = new JLabel();
        var spinnerIcon = SpinnerIconUtil.getSpinner(chrome, false);
        if (spinnerIcon != null) {
            sessionSwitchSpinner.setIcon(spinnerIcon);
        }

        JLabel notificationText = new JLabel("Loading session...");
        notificationText.setOpaque(false);
        notificationText.setFont(historyTable.getFont());
        notificationText.setForeground(UIManager.getColor("Label.foreground"));
        notificationText.setBorder(null);

        contentPanel.add(sessionSwitchSpinner, BorderLayout.WEST);
        contentPanel.add(notificationText, BorderLayout.CENTER);

        sessionSwitchPanel.add(contentPanel, BorderLayout.NORTH);
    }

    private JPanel buildCombinedOutputInstructionsPanel(JScrollPane llmScrollPane, MaterialButton copyButton) {
        // Build capture output panel (copyButton is passed in)
        var capturePanel = buildCaptureOutputPanel(copyButton);

        // Output panel with LLM stream
        var outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Output",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));
        outputPanel.add(llmScrollPane, BorderLayout.CENTER);
        outputPanel.add(capturePanel, BorderLayout.SOUTH); // Add capture panel below LLM output

        // Container for the combined section
        var centerContainer = new JPanel(new BorderLayout());
        centerContainer.add(outputPanel, BorderLayout.CENTER);
        centerContainer.setMinimumSize(new Dimension(200, 0)); // Minimum width for combined area

        return centerContainer;
    }

    /** Builds the session controls panel with combo box and buttons */
    private JPanel buildSessionControlsPanel(
            JComboBox<SessionInfo> sessionComboBox, SplitButton newSessionButton, SplitButton manageSessionsButton) {
        var panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Sessions",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        // Session combo box (passed in)
        sessionComboBox.setRenderer(new SessionInfoRenderer());
        sessionComboBox.setMaximumRowCount(10);

        // Add selection listener for session switching
        sessionComboBox.addActionListener(e -> {
            var selectedSession = (SessionInfo) sessionComboBox.getSelectedItem();
            if (selectedSession != null && !selectedSession.id().equals(contextManager.getCurrentSessionId())) {
                contextManager.switchSessionAsync(selectedSession.id());
            }
        });

        // Buttons panel
        // Use GridLayout to make buttons share width equally
        var buttonsPanel = new JPanel(new GridLayout(1, 2, 5, 0)); // 1 row, 2 columns, 5px hgap

        // Tooltip and action listener for the new session button
        newSessionButton.setToolTipText("Create a new session");
        // Primary click → empty session
        newSessionButton.addActionListener(e -> {
            contextManager
                    .createSessionAsync(ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> contextManager.getProject().getMainProject().sessionsListChanged());
        });
        // Split-arrow menu → session with copied workspace
        newSessionButton.setMenuSupplier(() -> {
            var popup = new JPopupMenu();
            var copyWorkspaceItem = new JMenuItem("New + Copy Workspace");
            copyWorkspaceItem.addActionListener(ev -> {
                contextManager
                        .createSessionFromContextAsync(contextManager.topContext(), ContextManager.DEFAULT_SESSION_NAME)
                        .thenRun(() ->
                                contextManager.getProject().getMainProject().sessionsListChanged());
            });
            popup.add(copyWorkspaceItem);
            return popup;
        });

        // Tooltip and action listener for the manage sessions button
        manageSessionsButton.setToolTipText("Manage sessions (rename, delete, copy)");
        manageSessionsButton.setMenuSupplier(() -> {
            var popup = new JPopupMenu();

            var renameItem = new JMenuItem("Rename Current Session");
            renameItem.addActionListener(
                    ev -> SessionsDialog.renameCurrentSession(HistoryOutputPanel.this, chrome, contextManager));
            popup.add(renameItem);

            var deleteItem = new JMenuItem("Delete Current Session");
            deleteItem.addActionListener(
                    ev -> SessionsDialog.deleteCurrentSession(HistoryOutputPanel.this, chrome, contextManager));
            popup.add(deleteItem);

            return popup;
        });
        manageSessionsButton.addActionListener(e -> {
            var dialog = new SessionsDialog(chrome, contextManager);
            dialog.setVisible(true);
        });

        buttonsPanel.add(newSessionButton);
        buttonsPanel.add(manageSessionsButton);

        panel.add(sessionComboBox, BorderLayout.CENTER);
        panel.add(buttonsPanel, BorderLayout.SOUTH);

        // Initialize with current sessions
        updateSessionComboBox();

        return panel;
    }

    /** Updates the session combo box with current sessions and selects the active one */
    public void updateSessionComboBox() {
        SwingUtilities.invokeLater(() -> {
            // Remove action listener temporarily
            var listeners = sessionComboBox.getActionListeners();
            for (var listener : listeners) {
                sessionComboBox.removeActionListener(listener);
            }

            // Clear and repopulate all sessions (dropdown shows at most 10 rows, scroll for more)
            sessionComboBox.removeAllItems();
            var sessions = contextManager.getProject().getSessionManager().listSessions();
            sessions.sort(
                    java.util.Comparator.comparingLong(SessionInfo::modified).reversed()); // Most recent first
            for (var session : sessions) {
                sessionComboBox.addItem(session);
            }

            // Select current session
            var currentSessionId = contextManager.getCurrentSessionId();
            for (int i = 0; i < sessionComboBox.getItemCount(); i++) {
                var sessionInfo = sessionComboBox.getItemAt(i);
                if (sessionInfo.id().equals(currentSessionId)) {
                    sessionComboBox.setSelectedIndex(i);
                    break;
                }
            }

            // Restore action listeners
            for (var listener : listeners) {
                sessionComboBox.addActionListener(listener);
            }
        });
    }

    /** Builds the Activity history panel that shows past contexts */
    private JPanel buildActivityPanel(JTable historyTable, MaterialButton undoButton, MaterialButton redoButton) {
        // Create history panel
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Activity",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        historyTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Remove table header
        historyTable.setTableHeader(null);

        // Set up custom renderers for history table columns
        historyTable.getColumnModel().getColumn(0).setCellRenderer(new ActivityTableRenderers.IconCellRenderer());
        historyTable.getColumnModel().getColumn(1).setCellRenderer(new DiffAwareActionRenderer());

        // Add selection listener to preview context (ignore group header rows)
        historyTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = historyTable.getSelectedRow();
                if (row >= 0 && row < historyTable.getRowCount()) {
                    var val = historyModel.getValueAt(row, 2);
                    if (val instanceof Context ctx) {
                        contextManager.setSelectedContext(ctx);
                        // setContext is for *previewing* a context without changing selection state in the manager
                        chrome.setContext(ctx);
                    }
                }
            }
        });

        // Add mouse listener for right-click context menu, expand/collapse on group header, and double-click action
        historyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = historyTable.rowAtPoint(e.getPoint());
                if (row < 0) return;
                var val = historyModel.getValueAt(row, 2);

                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (val instanceof GroupRow) {
                        // Toggle expand/collapse on click for the group header
                        toggleGroupRow(row);
                        return;
                    }
                    if (e.getClickCount() == 2 && val instanceof Context context) {
                        if (context.isAiResult()) {
                            openDiffPreview(context);
                        } else {
                            openOutputWindowFromContext(context);
                        }
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextHistoryPopupMenuIfContext(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextHistoryPopupMenuIfContext(e);
                }
            }

            private void showContextHistoryPopupMenuIfContext(MouseEvent e) {
                int row = historyTable.rowAtPoint(e.getPoint());
                if (row < 0) return;
                showContextHistoryPopupMenu(e);
            }
        });

        // Adjust column widths - set emoji column width and hide the context object column
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        historyTable.getColumnModel().getColumn(0).setMinWidth(30);
        historyTable.getColumnModel().getColumn(0).setMaxWidth(30);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        historyTable.getColumnModel().getColumn(2).setMinWidth(0);
        historyTable.getColumnModel().getColumn(2).setMaxWidth(0);
        historyTable.getColumnModel().getColumn(2).setWidth(0);

        // Add table to scroll pane with AutoScroller
        this.historyScrollPane = new JScrollPane(historyTable);
        var layer = new JLayer<>(historyScrollPane, arrowLayerUI);
        historyScrollPane.getViewport().addChangeListener(e -> layer.repaint());
        historyScrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        AutoScroller.install(historyScrollPane);
        BorderUtils.addFocusBorder(historyScrollPane, historyTable);

        // Add MouseListener to scrollPane's viewport to request focus for historyTable
        historyScrollPane.getViewport().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getSource() == historyScrollPane.getViewport()) { // Click was on the viewport itself
                    historyTable.requestFocusInWindow();
                }
            }
        });

        // Add undo/redo buttons at the bottom, side by side
        var buttonPanel = new JPanel(new GridLayout(1, 2, 5, 0)); // 1 row, 2 columns, 5px hgap

        undoButton.setMnemonic(KeyEvent.VK_Z);
        undoButton.setToolTipText("Undo the most recent history entry");
        undoButton.addActionListener(e -> {
            contextManager.undoContextAsync();
        });
        SwingUtilities.invokeLater(() -> {
            undoButton.setIcon(Icons.UNDO);
        });

        redoButton.setMnemonic(KeyEvent.VK_Y);
        redoButton.setToolTipText("Redo the most recently undone entry");
        redoButton.addActionListener(e -> {
            contextManager.redoContextAsync();
        });
        SwingUtilities.invokeLater(() -> {
            redoButton.setIcon(Icons.REDO);
        });

        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);
        buttonPanel.setBorder(new EmptyBorder(5, 0, 10, 0)); // Add top + slight bottom padding to align with Output

        historyLayeredPane.add(layer, JLayeredPane.DEFAULT_LAYER);

        panel.add(historyLayeredPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        // Calculate preferred width for the history panel
        // Table width (30 + 150) + scrollbar (~20) + padding = ~210
        // Button width (100 + 100 + 5) + padding = ~215
        int preferredWidth = 230; // Give a bit more room
        var preferredSize = new Dimension(preferredWidth, panel.getPreferredSize().height);
        panel.setPreferredSize(preferredSize);
        panel.setMinimumSize(preferredSize);
        panel.setMaximumSize(new Dimension(preferredWidth, Integer.MAX_VALUE)); // Fixed width, flexible height

        updateUndoRedoButtonStates();

        return panel;
    }

    /**
     * Updates the enabled state of the local Undo and Redo buttons based on the current state of the ContextHistory.
     */
    public void updateUndoRedoButtonStates() {
        SwingUtilities.invokeLater(() -> {
            undoButton.setEnabled(contextManager.getContextHistory().hasUndoStates());
            redoButton.setEnabled(contextManager.getContextHistory().hasRedoStates());
        });
    }

    /** Shows the context menu for the context history table (supports Context and GroupRow). */
    private void showContextHistoryPopupMenu(MouseEvent e) {
        int row = historyTable.rowAtPoint(e.getPoint());
        if (row < 0) return;

        Object val = historyModel.getValueAt(row, 2);

        // Direct Context row: select and show popup
        if (val instanceof Context context) {
            historyTable.setRowSelectionInterval(row, row);
            showPopupForContext(context, e.getX(), e.getY());
            return;
        }

        // Group header row: expand if needed, then target the first child row
        if (val instanceof GroupRow group) {
            var key = group.key();
            boolean expandedNow = groupExpandedState.getOrDefault(key, group.expanded());

            Runnable showAfterExpand = () -> {
                int headerRow = findGroupHeaderRow(key);
                if (headerRow >= 0) {
                    int firstChildRow = headerRow + 1;
                    if (firstChildRow < historyModel.getRowCount()) {
                        Object childVal = historyModel.getValueAt(firstChildRow, 2);
                        if (childVal instanceof Context ctx) {
                            historyTable.setRowSelectionInterval(firstChildRow, firstChildRow);
                            showPopupForContext(ctx, e.getX(), e.getY());
                        }
                    }
                }
            };

            if (!expandedNow) {
                groupExpandedState.put(key, true);
                // Preserve viewport while expanding so the view doesn't jump
                pendingViewportPosition = historyScrollPane.getViewport().getViewPosition();
                suppressScrollOnNextUpdate = true;
                updateHistoryTable(null);
                // Ensure the table is rebuilt first, then select and show the popup
                SwingUtilities.invokeLater(showAfterExpand);
            } else {
                showAfterExpand.run();
            }
        }
    }

    private int findGroupHeaderRow(UUID groupKey) {
        for (int i = 0; i < historyModel.getRowCount(); i++) {
            var v = historyModel.getValueAt(i, 2);
            if (v instanceof GroupRow gr && gr.key().equals(groupKey)) {
                return i;
            }
        }
        return -1;
    }

    private void showPopupForContext(Context context, int x, int y) {
        // Create popup menu
        JPopupMenu popup = new JPopupMenu();

        JMenuItem undoToHereItem = new JMenuItem("Undo to here");
        undoToHereItem.addActionListener(event -> undoHistoryUntil(context));
        popup.add(undoToHereItem);

        JMenuItem resetToHereItem = new JMenuItem("Copy Workspace");
        resetToHereItem.addActionListener(event -> resetContextTo(context));
        popup.add(resetToHereItem);

        JMenuItem resetToHereIncludingHistoryItem = new JMenuItem("Copy Workspace with History");
        resetToHereIncludingHistoryItem.addActionListener(event -> resetContextToIncludingHistory(context));
        popup.add(resetToHereIncludingHistoryItem);

        // Show diff (uses BrokkDiffPanel)
        JMenuItem showDiffItem = new JMenuItem("Show diff");
        showDiffItem.addActionListener(event -> openDiffPreview(context));
        // Enable only if we have a previous context to diff against
        showDiffItem.setEnabled(previousContextMap.get(context.id()) != null);
        popup.add(showDiffItem);

        popup.addSeparator();

        JMenuItem newSessionFromWorkspaceItem = new JMenuItem("New Session from Workspace");
        newSessionFromWorkspaceItem.addActionListener(event -> {
            contextManager
                    .createSessionFromContextAsync(context, ContextManager.DEFAULT_SESSION_NAME)
                    .exceptionally(ex -> {
                        chrome.toolError("Failed to create new session from workspace: " + ex.getMessage());
                        return null;
                    });
        });
        popup.add(newSessionFromWorkspaceItem);

        // Register popup with theme manager
        chrome.themeManager.registerPopupMenu(popup);

        // Show popup menu
        popup.show(historyTable, x, y);
    }

    /** Restore context to a specific point in history */
    private void undoHistoryUntil(Context targetContext) {
        contextManager.undoContextUntilAsync(targetContext);
    }

    /**
     * Creates a new context based on the files and fragments from a historical context, while preserving current
     * conversation history
     */
    private void resetContextTo(Context targetContext) {
        contextManager.resetContextToAsync(targetContext);
    }

    /** Creates a new context based on the files, fragments, and history from a historical context */
    private void resetContextToIncludingHistory(Context targetContext) {
        contextManager.resetContextToIncludingHistoryAsync(targetContext);
    }

    /**
     * Updates the context history table with the current context history, and selects the given context
     *
     * @param contextToSelect Context to select in the history table
     */
    public void updateHistoryTable(@Nullable Context contextToSelect) {
        logger.debug(
                "Updating context history table with context {}",
                contextToSelect != null ? contextToSelect.getAction() : "null");
        assert contextToSelect == null || !contextToSelect.containsDynamicFragments();

        SwingUtilities.invokeLater(() -> {
            // Recompute previous-context map for diffing AI result contexts
            {
                var list = contextManager.getContextHistoryList();
                var map = new HashMap<UUID, Context>();
                for (int i = 1; i < list.size(); i++) {
                    map.put(list.get(i).id(), list.get(i - 1));
                }
                previousContextMap = map;
            }
            historyModel.setRowCount(0);

            int rowToSelect = -1;
            int currentRow = 0;

            var contexts = contextManager.getContextHistoryList();
            // Proactively compute diffs so grouping can reflect file-diff boundaries
            for (var c : contexts) {
                scheduleDiffComputation(c);
            }
            boolean lastIsNonLlm = !contexts.isEmpty() && !isGroupingBoundary(contexts.getLast());

            for (int i = 0; i < contexts.size(); i++) {
                var ctx = contexts.get(i);
                if (isGroupingBoundary(ctx)) {
                    Icon icon = ctx.isAiResult() ? Icons.CHAT_BUBBLE : null;
                    historyModel.addRow(new Object[] {icon, ctx.getAction(), ctx});
                    if (ctx.equals(contextToSelect)) {
                        rowToSelect = currentRow;
                    }
                    currentRow++;
                } else {
                    int j = i;
                    while (j < contexts.size() && !isGroupingBoundary(contexts.get(j))) {
                        j++;
                    }
                    var children = contexts.subList(i, j);
                    if (children.size() == 1) {
                        var child = children.get(0);
                        // Render single-entry groups as a normal top-level entry
                        historyModel.addRow(new Object[] {null, child.getAction(), child});
                        if (child.equals(contextToSelect)) {
                            rowToSelect = currentRow;
                        }
                        currentRow++;
                    } else { // children.size() >= 2
                        String title;
                        if (children.size() == 2) {
                            title = firstWord(children.get(0).getAction()) + " + "
                                    + firstWord(children.get(1).getAction());
                        } else {
                            title = children.size() + " actions";
                        }
                        var first = children.get(0); // For key and other metadata
                        var key = first.id();
                        boolean isLastGroup = j == contexts.size();
                        boolean expandedDefault = isLastGroup && lastIsNonLlm;
                        boolean expanded = groupExpandedState.getOrDefault(key, expandedDefault);

                        boolean containsClearHistory = children.stream()
                                .anyMatch(c ->
                                        ActivityTableRenderers.CLEARED_TASK_HISTORY.equalsIgnoreCase(c.getAction()));

                        var groupRow = new GroupRow(key, expanded, containsClearHistory);
                        historyModel.addRow(new Object[] {new TriangleIcon(expanded), title, groupRow});
                        currentRow++;

                        if (expanded) {
                            for (var child : children) {
                                String childText = "   " + child.getAction();
                                historyModel.addRow(new Object[] {null, childText, child});
                                if (child.equals(contextToSelect)) {
                                    rowToSelect = currentRow;
                                }
                                currentRow++;
                            }
                        }
                    }

                    i = j - 1;
                }
            }

            // Apply pending selection directive, if any
            if (pendingSelectionType == PendingSelectionType.FIRST_IN_GROUP && pendingSelectionGroupKey != null) {
                int headerRow = findGroupHeaderRow(pendingSelectionGroupKey);
                int candidate = headerRow >= 0 ? headerRow + 1 : -1;
                if (candidate >= 0 && candidate < historyModel.getRowCount()) {
                    Object v = historyModel.getValueAt(candidate, 2);
                    if (v instanceof Context) {
                        rowToSelect = candidate;
                    }
                }
            }

            boolean suppress = suppressScrollOnNextUpdate;

            if (pendingSelectionType == PendingSelectionType.CLEAR) {
                historyTable.clearSelection();
                // Do not auto-select any row when collapsing a group
            } else if (rowToSelect >= 0) {
                historyTable.setRowSelectionInterval(rowToSelect, rowToSelect);
                if (!suppress) {
                    historyTable.scrollRectToVisible(historyTable.getCellRect(rowToSelect, 0, true));
                }
            } else if (!suppress && historyModel.getRowCount() > 0) {
                int lastRow = historyModel.getRowCount() - 1;
                historyTable.setRowSelectionInterval(lastRow, lastRow);
                historyTable.scrollRectToVisible(historyTable.getCellRect(lastRow, 0, true));
            }

            // Restore viewport if requested
            if (suppress && pendingViewportPosition != null) {
                Point desired = pendingViewportPosition;
                SwingUtilities.invokeLater(() -> {
                    historyScrollPane.getViewport().setViewPosition(clampViewportPosition(historyScrollPane, desired));
                });
            }

            // Reset directive after applying
            pendingSelectionType = PendingSelectionType.NONE;
            pendingSelectionGroupKey = null;
            suppressScrollOnNextUpdate = false;
            pendingViewportPosition = null;

            contextManager.getProject().getMainProject().sessionsListChanged();
            var resetEdges = contextManager.getContextHistory().getResetEdges();
            arrowLayerUI.setResetEdges(resetEdges);
            updateUndoRedoButtonStates();
        });
    }

    /**
     * Returns the history table for selection checks
     *
     * @return The JTable containing context history
     */
    public @Nullable JTable getHistoryTable() {
        return historyTable;
    }

    /** Builds the LLM streaming area where markdown output is displayed */
    private JScrollPane buildLLMStreamScrollPane(MarkdownOutputPanel llmStreamArea) {
        // Wrap it in a scroll pane for layout purposes, but disable scrollbars
        // as scrolling is handled by the WebView inside MarkdownOutputPanel.
        var jsp = new JScrollPane(
                llmStreamArea, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        jsp.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Add a text change listener to update capture buttons
        llmStreamArea.addTextChangeListener(chrome::updateCaptureButtons);

        return jsp;
    }

    // buildSystemMessagesArea removed

    // buildCommandResultLabel removed

    /** Builds the "Capture Output" panel with a horizontal layout: [Capture Text] */
    private JPanel buildCaptureOutputPanel(MaterialButton copyButton) {
        var panel = new JPanel(new BorderLayout(5, 3));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        // Placeholder area in center - will get all extra space
        captureDescriptionArea = new JTextArea("");
        captureDescriptionArea.setEditable(false);
        captureDescriptionArea.setBackground(panel.getBackground());
        captureDescriptionArea.setBorder(null);
        captureDescriptionArea.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        captureDescriptionArea.setLineWrap(true);
        captureDescriptionArea.setWrapStyleWord(true);
        // notification area now occupies the CENTER; description area removed

        // Buttons panel on the left
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        copyButton.setMnemonic(KeyEvent.VK_T);
        copyButton.setToolTipText("Copy the output to clipboard");
        copyButton.addActionListener(e -> {
            var ctx = contextManager.selectedContext();
            if (ctx == null) {
                chrome.systemOutput("No active context to copy from.");
                return;
            }

            var historyOpt = ctx.getAllFragmentsInDisplayOrder().stream()
                    .filter(f -> f.getType() == ContextFragment.FragmentType.HISTORY)
                    .reduce((first, second) -> second); // use the most recent HISTORY fragment

            if (historyOpt.isEmpty()) {
                chrome.systemOutput("No conversation history found in the current workspace.");
                return;
            }

            var historyFrag = historyOpt.get();
            chrome.getContextPanel().performContextActionAsync(WorkspacePanel.ContextAction.COPY, List.of(historyFrag));
        });
        // Set minimum size
        copyButton.setMinimumSize(copyButton.getPreferredSize());
        buttonsPanel.add(copyButton);

        // "Capture" button
        var captureButton = new MaterialButton();
        SwingUtilities.invokeLater(() -> {
            captureButton.setIcon(Icons.CONTENT_CAPTURE);
        });
        captureButton.setMnemonic(KeyEvent.VK_C);
        captureButton.setToolTipText("Add the output to context");
        captureButton.addActionListener(e -> {
            presentCaptureChoice();
        });
        // Set minimum size
        captureButton.setMinimumSize(captureButton.getPreferredSize());
        buttonsPanel.add(captureButton);

        // "Open in New Window" button
        var openWindowButton = new MaterialButton();
        SwingUtilities.invokeLater(() -> {
            openWindowButton.setIcon(Icons.OPEN_NEW_WINDOW);
        });
        openWindowButton.setMnemonic(KeyEvent.VK_W);
        openWindowButton.setToolTipText("Open the output in a new window");
        openWindowButton.addActionListener(e -> {
            if (llmStreamArea.isBlocking()) {
                openOutputWindowStreaming();
            } else {
                var context = contextManager.selectedContext();
                if (context == null) {
                    logger.warn("Cannot open output in new window: current context is null.");
                    return;
                }
                openOutputWindowFromContext(context);
            }
        });
        // Set minimum size
        openWindowButton.setMinimumSize(openWindowButton.getPreferredSize());
        buttonsPanel.add(openWindowButton);

        // Notifications button
        notificationsButton.setToolTipText("Show notifications");
        notificationsButton.addActionListener(e -> showNotificationsDialog());
        SwingUtilities.invokeLater(() -> {
            notificationsButton.setIcon(Icons.NOTIFICATIONS);
            notificationsButton.setMinimumSize(notificationsButton.getPreferredSize());
        });
        buttonsPanel.add(notificationsButton);

        // Add buttons panel to the left
        panel.add(buttonsPanel, BorderLayout.WEST);
        // Add notification area to the right of the buttons panel
        panel.add(notificationAreaPanel, BorderLayout.CENTER);

        // Right-aligned panel: Compress + Auto on the right of the same row
        var rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        // Auto checkbox
        boolean autoInitial = MainProject.getHistoryAutoCompress();
        autoCompressCheckbox.setSelected(autoInitial);
        autoCompressCheckbox.setToolTipText(
                "Automatically compress when history exceeds 10% of the model context window and before Task List runs");
        // Ensure single listener (avoid duplicates if panel rebuilt)
        for (var al : autoCompressCheckbox.getActionListeners()) {
            autoCompressCheckbox.removeActionListener(al);
        }
        autoCompressCheckbox.addActionListener(e -> {
            MainProject.setHistoryAutoCompress(autoCompressCheckbox.isSelected());
        });

        // Compress button
        compressButton.setToolTipText("Compress conversation history now");
        // Ensure single listener (avoid duplicates if panel rebuilt)
        for (var al : compressButton.getActionListeners()) {
            compressButton.removeActionListener(al);
        }
        compressButton.addActionListener(e -> {
            contextManager.compressHistoryAsync();
        });
        // Set minimum size similar to other buttons
        compressButton.setMinimumSize(compressButton.getPreferredSize());
        rightButtonsPanel.add(compressButton);

        // Add Auto to the right of Compress
        rightButtonsPanel.add(autoCompressCheckbox);

        panel.add(rightButtonsPanel, BorderLayout.EAST);

        return panel;
    }

    // Notification API
    public void showNotification(NotificationRole role, String message) {
        Runnable r = () -> {
            var entry = new NotificationEntry(role, message, System.currentTimeMillis());
            notifications.add(entry);
            notificationQueue.offer(entry);
            updateNotificationsButton();
            persistNotificationsAsync();
            refreshLatestNotificationCard();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    public void showConfirmNotification(String message, Runnable onAccept, Runnable onReject) {
        Runnable r = () -> {
            var entry = new NotificationEntry(NotificationRole.CONFIRM, message, System.currentTimeMillis());
            notifications.add(entry);
            updateNotificationsButton();
            persistNotificationsAsync();

            if (isDisplayingNotification) {
                notificationQueue.offer(entry);
            } else {
                notificationAreaPanel.removeAll();
                isDisplayingNotification = true;
                JPanel card = createNotificationCard(NotificationRole.CONFIRM, message, onAccept, onReject);
                notificationAreaPanel.add(card);
                animateNotificationCard(card);
                notificationAreaPanel.revalidate();
                notificationAreaPanel.repaint();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private JPanel buildNotificationAreaPanel() {
        var p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, 5, 0, 0));
        // Preferred width to allow message text and controls; height flexes with content
        p.setPreferredSize(new Dimension(0, 0));
        return p;
    }

    // Show the next notification from the queue
    private void refreshLatestNotificationCard() {
        if (isDisplayingNotification || notificationQueue.isEmpty()) {
            return;
        }

        var nextToShow = notificationQueue.poll();
        if (nextToShow == null) {
            return;
        }

        notificationAreaPanel.removeAll();
        isDisplayingNotification = true;
        JPanel card = createNotificationCard(nextToShow.role, nextToShow.message, null, null);
        notificationAreaPanel.add(card);
        animateNotificationCard(card);
        notificationAreaPanel.revalidate();
        notificationAreaPanel.repaint();
    }

    private void animateNotificationCard(JPanel card) {
        card.putClientProperty("notificationOpacity", 0.0f);

        final int fadeInDuration = 2000; // 2 seconds
        final int holdDuration = 1000; // 10 seconds
        final int fadeOutDuration = 2000; // 2 seconds
        final int fps = 30;
        final int fadeInFrames = (fadeInDuration * fps) / 1000;
        final int fadeOutFrames = (fadeOutDuration * fps) / 1000;
        final float fadeInStep = 1.0f / fadeInFrames;
        final float fadeOutStep = 1.0f / fadeOutFrames;

        final Timer[] timerHolder = new Timer[1];
        final int[] frameCounter = {0};
        final int[] phase = {0}; // 0=fade in, 1=hold, 2=fade out

        Timer timer = new Timer(1000 / fps, e -> {
            float currentOpacity = (Float) card.getClientProperty("notificationOpacity");

            if (phase[0] == 0) {
                // Fade in
                currentOpacity = Math.min(1.0f, currentOpacity + fadeInStep);
                card.putClientProperty("notificationOpacity", currentOpacity);
                card.repaint();

                if (currentOpacity >= 1.0f) {
                    phase[0] = 1;
                    frameCounter[0] = 0;
                }
            } else if (phase[0] == 1) {
                // Hold
                frameCounter[0]++;
                if (frameCounter[0] >= (holdDuration / (1000 / fps))) {
                    phase[0] = 2;
                    frameCounter[0] = 0;
                }
            } else if (phase[0] == 2) {
                // Fade out
                currentOpacity = Math.max(0.0f, currentOpacity - fadeOutStep);
                card.putClientProperty("notificationOpacity", currentOpacity);
                card.repaint();

                if (currentOpacity <= 0.0f) {
                    timerHolder[0].stop();
                    dismissCurrentNotification();
                }
            }
        });

        timerHolder[0] = timer;
        card.putClientProperty("notificationTimer", timer);
        timer.start();
    }

    private void dismissCurrentNotification() {
        isDisplayingNotification = false;
        notificationAreaPanel.removeAll();
        notificationAreaPanel.revalidate();
        notificationAreaPanel.repaint();
        // Show the next notification (if any)
        refreshLatestNotificationCard();
    }

    private JPanel createNotificationCard(
            NotificationRole role, String message, @Nullable Runnable onAccept, @Nullable Runnable onReject) {
        var colors = resolveNotificationColors(role);
        Color bg = colors.get(0);
        Color fg = colors.get(1);
        Color border = colors.get(2);

        // Rounded, modern container
        var card = new RoundedPanel(12, bg, border);
        card.setLayout(new BorderLayout(8, 4));
        card.setBorder(new EmptyBorder(2, 8, 2, 8));

        // Center: show full message (including full cost details for COST)
        String display = compactMessageForToolbar(role, message);
        var msg = new JLabel(
                "<html><div style='width:100%; text-align: left; word-wrap: break-word; white-space: normal;'>"
                        + escapeHtml(display) + "</div></html>");
        msg.setForeground(fg);
        msg.setVerticalAlignment(JLabel.CENTER);
        msg.setHorizontalAlignment(JLabel.LEFT);
        card.add(msg, BorderLayout.CENTER);

        // Right: actions
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);

        if (role == NotificationRole.CONFIRM) {
            var acceptBtn = new MaterialButton("Accept");
            acceptBtn.setToolTipText("Accept");
            acceptBtn.addActionListener(e -> {
                if (onAccept != null) onAccept.run();
                removeNotificationCard();
            });
            actions.add(acceptBtn);

            var rejectBtn = new MaterialButton("Reject");
            rejectBtn.setToolTipText("Reject");
            rejectBtn.addActionListener(e -> {
                if (onReject != null) onReject.run();
                removeNotificationCard();
            });
            actions.add(rejectBtn);
        } else {
            var closeBtn = new MaterialButton();
            closeBtn.setToolTipText("Dismiss");
            SwingUtilities.invokeLater(() -> {
                var icon = Icons.CLOSE;
                if (icon instanceof SwingUtil.ThemedIcon themedIcon) {
                    closeBtn.setIcon(themedIcon.withSize(18));
                } else {
                    closeBtn.setIcon(icon);
                }
            });
            closeBtn.addActionListener(e -> {
                var timer = (Timer) card.getClientProperty("notificationTimer");
                if (timer != null) {
                    timer.stop();
                }
                dismissCurrentNotification();
            });
            actions.add(closeBtn);
        }
        card.add(actions, BorderLayout.EAST);

        // Allow card to grow vertically; overall area scrolls when necessary
        return card;
    }

    private static String compactMessageForToolbar(NotificationRole role, String message) {
        // Show full details for COST; compact other long messages to keep the toolbar tidy
        if (role == NotificationRole.COST) {
            return message;
        }
        int max = 160;
        if (message.length() <= max) return message;
        return message.substring(0, max - 3) + "...";
    }

    private static class RoundedPanel extends JPanel {
        private final int radius;
        private final Color bg;
        private final Color border;

        RoundedPanel(int radius, Color bg, Color border) {
            super();
            this.radius = radius;
            this.bg = bg;
            this.border = border;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Apply opacity animation if present
                Float opacity = (Float) getClientProperty("notificationOpacity");
                if (opacity != null && opacity < 1.0f) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
                }

                int w = getWidth();
                int h = getHeight();
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, w - 1, h - 1, radius, radius);
                g2.setColor(border);
                g2.drawRoundRect(0, 0, w - 1, h - 1, radius, radius);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private static class ScrollableWidthPanel extends JPanel implements Scrollable {
        ScrollableWidthPanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }
    }

    // Update the notifications button (removed count display)
    private void updateNotificationsButton() {
        // No-op: button just shows icon without count
    }

    // Notification persistence

    private Path computeNotificationsFile() {
        var dir = Paths.get(System.getProperty("user.home"), ".brokk");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.warn("Unable to create notifications directory {}", dir, e);
        }
        return dir.resolve("notifications.log");
    }

    private void persistNotificationsAsync() {
        CompletableFuture.runAsync(this::persistNotifications);
    }

    private void persistNotifications() {
        try {
            var linesToPersist = notifications.stream()
                    .sorted(Comparator.comparingLong((NotificationEntry n) -> n.timestamp)
                            .reversed())
                    .limit(100)
                    .map(n -> {
                        var msgB64 = Base64.getEncoder().encodeToString(n.message.getBytes(StandardCharsets.UTF_8));
                        return "2|" + n.role.name() + "|" + n.timestamp + "|" + msgB64;
                    })
                    .toList();
            Files.write(notificationsFile, linesToPersist, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Failed to persist notifications to {}", notificationsFile, e);
        }
    }

    private void loadPersistedNotifications() {
        try {
            if (!Files.exists(notificationsFile)) {
                return;
            }
            var lines = Files.readAllLines(notificationsFile, StandardCharsets.UTF_8);
            for (var line : lines) {
                if (line == null || line.isBlank()) continue;
                var parts = line.split("\\|", 4);
                if (parts.length < 4) continue;

                // Skip old format (version 1)
                if ("1".equals(parts[0])) continue;
                if (!"2".equals(parts[0])) continue;

                NotificationRole role;
                try {
                    role = NotificationRole.valueOf(parts[1]);
                } catch (IllegalArgumentException iae) {
                    continue;
                }

                long ts;
                try {
                    ts = Long.parseLong(parts[2]);
                } catch (NumberFormatException nfe) {
                    ts = System.currentTimeMillis();
                }

                String message;
                try {
                    var bytes = Base64.getDecoder().decode(parts[3]);
                    message = new String(bytes, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException iae) {
                    message = parts[3];
                }

                notifications.add(new NotificationEntry(role, message, ts));
            }

            SwingUtilities.invokeLater(() -> {
                updateNotificationsButton();
            });
        } catch (Exception e) {
            logger.warn("Failed to load persisted notifications from {}", notificationsFile, e);
        }
    }

    // Dialog showing a list of all notifications
    private void showNotificationsDialog() {
        var dialog = new JDialog(chrome.getFrame(), "Notifications (" + notifications.size() + ")", true);
        dialog.setLayout(new BorderLayout(8, 8));

        // Build list panel
        var listPanel = new ScrollableWidthPanel(new GridBagLayout());
        listPanel.setOpaque(false);
        listPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        rebuildNotificationsList(dialog, listPanel);

        var scroll = new JScrollPane(listPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // Footer with limit note and buttons
        var footer = new JPanel(new BorderLayout());
        footer.setBorder(new EmptyBorder(8, 8, 8, 8));

        var noteLabel = new JLabel("The most recent 100 notifications are retained.");
        noteLabel.setFont(noteLabel.getFont().deriveFont(Font.ITALIC));
        noteLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        footer.add(noteLabel, BorderLayout.WEST);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        var closeBtn = new MaterialButton("Ok");
        SwingUtil.applyPrimaryButtonStyle(closeBtn);
        closeBtn.addActionListener(e -> dialog.dispose());
        buttonPanel.add(closeBtn);

        var clearAllBtn = new MaterialButton("Clear All");
        clearAllBtn.addActionListener(e -> {
            notifications.clear();
            notificationQueue.clear();
            updateNotificationsButton();
            persistNotificationsAsync();
            dialog.dispose();
        });
        buttonPanel.add(clearAllBtn);

        footer.add(buttonPanel, BorderLayout.EAST);

        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(footer, BorderLayout.SOUTH);

        dialog.setSize(640, 480);
        dialog.setLocationRelativeTo(chrome.getFrame());
        dialog.setVisible(true);
    }

    private void rebuildNotificationsList(JDialog dialog, JPanel listPanel) {
        listPanel.removeAll();
        dialog.setTitle("Notifications (" + notifications.size() + ")");

        if (notifications.isEmpty()) {
            GridBagConstraints gbcEmpty = new GridBagConstraints();
            gbcEmpty.gridx = 0;
            gbcEmpty.gridy = 0;
            gbcEmpty.weightx = 1.0;
            gbcEmpty.fill = GridBagConstraints.HORIZONTAL;
            listPanel.add(new JLabel("No notifications."), gbcEmpty);
        } else {
            // Sort by timestamp descending (newest first)
            var sortedNotifications = new ArrayList<>(notifications);
            sortedNotifications.sort(Comparator.comparingLong((NotificationEntry n) -> n.timestamp)
                    .reversed());

            for (int i = 0; i < sortedNotifications.size(); i++) {
                var n = sortedNotifications.get(i);
                var colors = resolveNotificationColors(n.role);
                Color bg = colors.get(0);
                Color fg = colors.get(1);
                Color border = colors.get(2);

                var card = new RoundedPanel(12, bg, border);
                card.setLayout(new BorderLayout(8, 4));
                card.setBorder(new EmptyBorder(4, 8, 4, 8));
                card.setMinimumSize(new Dimension(0, 30));

                // Left: unread indicator (if unread) + message with bold timestamp at end
                var leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                leftPanel.setOpaque(false);

                String timeStr = formatModified(n.timestamp);
                String combined = escapeHtml(n.message) + " <b>" + escapeHtml(timeStr) + "</b>";
                var msgLabel = new JLabel("<html><div style='width:100%; word-wrap: break-word; white-space: normal;'>"
                        + combined + "</div></html>");
                msgLabel.setForeground(fg);
                msgLabel.setHorizontalAlignment(JLabel.LEFT);
                msgLabel.setVerticalAlignment(JLabel.CENTER);

                leftPanel.add(msgLabel);
                card.add(leftPanel, BorderLayout.CENTER);

                // Right: close button (half size)
                var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
                actions.setOpaque(false);

                var closeBtn = new MaterialButton();
                closeBtn.setToolTipText("Remove this notification");
                SwingUtilities.invokeLater(() -> {
                    var icon = Icons.CLOSE;
                    if (icon instanceof SwingUtil.ThemedIcon themedIcon) {
                        closeBtn.setIcon(themedIcon.withSize(12));
                    } else {
                        closeBtn.setIcon(icon);
                    }
                });
                closeBtn.addActionListener(e -> {
                    notifications.remove(n);
                    notificationQueue.removeIf(entry -> entry == n);
                    updateNotificationsButton();
                    persistNotificationsAsync();
                    rebuildNotificationsList(dialog, listPanel);
                });
                closeBtn.setPreferredSize(new Dimension(24, 24));
                actions.add(closeBtn);

                card.add(actions, BorderLayout.EAST);

                GridBagConstraints gbc = new GridBagConstraints();
                gbc.gridx = 0;
                gbc.gridy = i;
                gbc.weightx = 1.0;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.insets = new Insets(0, 0, 6, 0);
                listPanel.add(card, gbc);
            }

            // Add a filler component that takes up all extra vertical space
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = sortedNotifications.size();
            gbc.weighty = 1.0;
            gbc.fill = GridBagConstraints.VERTICAL;
            var filler = new JPanel();
            filler.setOpaque(false);
            listPanel.add(filler, gbc);
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    // Simple container for notifications
    private static class NotificationEntry {
        final NotificationRole role;
        final String message;
        final long timestamp;

        NotificationEntry(NotificationRole role, String message, long timestamp) {
            this.role = role;
            this.message = message;
            this.timestamp = timestamp;
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void removeNotificationCard() {
        Runnable r = () -> {
            refreshLatestNotificationCard();
            updateNotificationsButton();
            persistNotificationsAsync();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    public List<ChatMessage> getLlmRawMessages() {
        return llmStreamArea.getRawMessages();
    }

    /**
     * Displays a full conversation, splitting it between the history area (for all but the last task) and the main area
     * (for the last task).
     *
     * @param history The list of tasks to show in the history section.
     * @param main The final task to show in the main output section.
     */
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry main) {
        // prioritize rendering live area, then history (explicitly sequenced with flush)
        llmStreamArea.setMainThenHistoryAsync(main, history);
    }

    /** Appends text to the LLM output area */
    public void appendLlmOutput(String text, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
        llmStreamArea.append(text, type, isNewMessage, isReasoning);
        activeStreamingWindows.forEach(
                window -> window.getMarkdownOutputPanel().append(text, type, isNewMessage, isReasoning));
    }

    /** Sets the enabled state of the copy text button */
    public void setCopyButtonEnabled(boolean enabled) {
        copyButton.setEnabled(enabled);
    }

    /** Shows the loading spinner with a message in the Markdown area. */
    public void showSpinner(String message) {
        llmStreamArea.showSpinner(message);
        lastSpinnerMessage = message;
        activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().showSpinner(message));
    }

    /** Hides the loading spinner in the Markdown area. */
    public void hideSpinner() {
        llmStreamArea.hideSpinner();
        lastSpinnerMessage = null;
        activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().hideSpinner());
    }

    /** Shows the session switching spinner. */
    public void showSessionSwitchSpinner() {
        SwingUtilities.invokeLater(() -> {
            historyModel.setRowCount(0);
            JPanel ssp = sessionSwitchPanel;
            if (ssp == null) {
                buildSessionSwitchPanel();
                ssp = requireNonNull(sessionSwitchPanel);
                historyLayeredPane.add(ssp, JLayeredPane.PALETTE_LAYER);
            }
            ssp.setVisible(true);
            ssp.revalidate();
            ssp.repaint();
        });
    }

    /** Hides the session switching spinner. */
    public void hideSessionSwitchSpinner() {
        SwingUtilities.invokeLater(() -> {
            if (sessionSwitchPanel != null) {
                sessionSwitchPanel.setVisible(false);
                sessionSwitchPanel.revalidate();
                sessionSwitchPanel.repaint();
            }
        });
    }

    /** Gets the LLM scroll pane */
    public JScrollPane getLlmScrollPane() {
        return llmScrollPane;
    }

    public MarkdownOutputPanel getLlmStreamArea() {
        return llmStreamArea;
    }

    public void clearLlmOutput() {
        llmStreamArea.clear();
    }

    /**
     * Sets the blocking state on the contained MarkdownOutputPanel.
     *
     * @param blocked true to prevent clear/reset, false otherwise.
     */
    public void setMarkdownOutputPanelBlocking(boolean blocked) {
        llmStreamArea.setBlocking(blocked);
        if (!blocked) {
            activeStreamingWindows.forEach(
                    window -> window.getMarkdownOutputPanel().setBlocking(false));
            activeStreamingWindows.clear();
        }
    }

    private void openOutputWindowFromContext(Context context) {
        var taskHistory = context.getTaskHistory();
        TaskEntry mainTask = null;
        List<TaskEntry> historyTasks = List.of();
        if (!taskHistory.isEmpty()) {
            historyTasks = taskHistory.subList(0, taskHistory.size() - 1);
            mainTask = taskHistory.getLast();
        } else {
            var output = context.getParsedOutput();
            if (output != null) {
                mainTask = new TaskEntry(-1, output, null);
            }
        }
        if (mainTask != null) {
            String titleHint = context.getAction();
            new OutputWindow(this, historyTasks, mainTask, titleHint, chrome.themeManager.isDarkTheme(), false);
        }
    }

    private void openOutputWindowStreaming() {
        // show all = grab all messages, including reasoning for preview window
        List<ChatMessage> currentMessages = llmStreamArea.getRawMessages();
        var tempFragment = new ContextFragment.TaskFragment(contextManager, currentMessages, "Streaming Output...");
        var history = contextManager.topContext().getTaskHistory();
        var mainTask = new TaskEntry(-1, tempFragment, null);
        String titleHint = lastSpinnerMessage;
        OutputWindow newStreamingWindow =
                new OutputWindow(this, history, mainTask, titleHint, chrome.themeManager.isDarkTheme(), true);
        if (lastSpinnerMessage != null) {
            newStreamingWindow.getMarkdownOutputPanel().showSpinner(lastSpinnerMessage);
        }
        activeStreamingWindows.add(newStreamingWindow);
        newStreamingWindow.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent evt) {
                activeStreamingWindows.remove(newStreamingWindow);
            }
        });
    }

    /** Presents a choice to capture output to Workspace or to Task List. */
    private void presentCaptureChoice() {
        var options = new Object[] {"Workspace", "Task List", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                chrome.getFrame(),
                "Where would you like to capture this output?",
                "Capture Output",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 0) { // Workspace
            contextManager.captureTextFromContextAsync();
        } else if (choice == 1) { // Task List
            createTaskListFromOutputAsync();
        } // else Cancel -> do nothing
    }

    /** Creates a task list from the currently selected output using the quick model and the createTaskList tool. */
    private void createTaskListFromOutputAsync() {
        var selected = contextManager.selectedContext();
        if (selected == null) {
            chrome.systemNotify("No content to capture", "Capture failed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        var parsedOutput = selected.getParsedOutput();
        if (parsedOutput == null) {
            chrome.systemNotify("No content to capture", "Capture failed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        var captureText = parsedOutput.text();
        if (captureText.isBlank()) {
            chrome.systemNotify(
                    "Nothing to capture from the selected output", "Capture failed", JOptionPane.WARNING_MESSAGE);
            return;
        }

        chrome.showOutputSpinner("Creating task list...");
        contextManager.submitLlmAction(() -> {
            try {
                var model = contextManager.getService().quickModel();
                var llm = new Llm(model, "Create Task List", contextManager, false, false);
                llm.setOutput(chrome);

                var system = new SystemMessage(
                        "You are generating an actionable, incremental task list based on the provided capture."
                                + "Do not speculate beyond it. You MUST produce tasks via the tool call createTaskList(List<String>). "
                                + "Do not output free-form text.");
                var user = new UserMessage(
                        """
                        <capture>
                        %s
                        </capture>

                        Instructions:
                        - Prefer using tasks that are already defined in the capture.
                        - If no such tasks exist, use your best judgement with the following guidelines:
                          - Extract 3-8 tasks that are right-sized (~2 hours each), each with a single concrete goal.
                          - Prefer tasks that keep the project buildable and testable after each step.
                          - Avoid multi-goal items; split if needed.
                          - Avoid external/non-code tasks.
                        - Include all the relevant details that you see in the capture for each task, but do not embellish or speculate.

                        Call the tool createTaskList(List<String>) with your final list. Do not include any explanation outside the tool call.
                        """
                                .stripIndent()
                                .formatted(captureText));

                var toolSpecs = contextManager.getToolRegistry().getRegisteredTools(List.of("createTaskList"));
                if (toolSpecs.isEmpty()) {
                    chrome.toolError("Required tool 'createTaskList' is not registered.", "Task List");
                    return;
                }

                var toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, this);
                var result = llm.sendRequest(List.of(system, user), toolContext, false);
                if (result.error() != null || result.isEmpty()) {
                    var msg = result.error() != null
                            ? String.valueOf(result.error().getMessage())
                            : "Empty response";
                    chrome.toolError("Failed to create task list: " + msg, "Task List");
                } else {
                    var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
                    assert ai.hasToolExecutionRequests(); // LLM enforces
                    for (var req : ai.toolExecutionRequests()) {
                        if (!"createTaskList".equals(req.name())) {
                            continue;
                        }
                        var ter = contextManager.getToolRegistry().executeTool(HistoryOutputPanel.this, req);
                        if (ter.status() == ToolExecutionResult.Status.FAILURE) {
                            chrome.toolError("Failed to create task list: " + ter.resultText(), "Task List");
                        }
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                chrome.systemNotify("Task list creation was interrupted.", "Task List", JOptionPane.WARNING_MESSAGE);
            } catch (Throwable t) {
                chrome.systemNotify(
                        "Unexpected error creating task list: " + t.getMessage(),
                        "Task List",
                        JOptionPane.ERROR_MESSAGE);
            } finally {
                chrome.hideOutputSpinner();
            }
        });
    }

    /** Inner class representing a detached window for viewing output text */
    private static class OutputWindow extends JFrame {
        private final IProject project;
        private final MarkdownOutputPanel outputPanel;

        /**
         * Creates a new output window with the given content and optional history.
         *
         * @param parentPanel The parent HistoryOutputPanel
         * @param history The conversation tasks to display in the history section (all but the main task)
         * @param main The main/last task to display in the live area
         * @param titleHint A hint for the window title (e.g., task summary or spinner message)
         * @param isDark Whether to use dark theme
         * @param isBlockingMode Whether the window shows a streaming (in-progress) output
         */
        public OutputWindow(
                HistoryOutputPanel parentPanel,
                List<TaskEntry> history,
                TaskEntry main,
                @Nullable String titleHint,
                boolean isDark,
                boolean isBlockingMode) {
            super(determineWindowTitle(titleHint, isBlockingMode)); // Call superclass constructor first

            // Set icon from Chrome.newFrame
            try {
                var iconUrl = Chrome.class.getResource(Brokk.ICON_RESOURCE);
                if (iconUrl != null) {
                    var icon = new ImageIcon(iconUrl);
                    setIconImage(icon.getImage());
                }
            } catch (Exception e) {
                logger.debug("Failed to set OutputWindow icon", e);
            }

            this.project = parentPanel.contextManager.getProject(); // Get project reference
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            // Create markdown panel with the text
            outputPanel = new MarkdownOutputPanel();
            outputPanel.withContextForLookups(parentPanel.contextManager, parentPanel.chrome);
            outputPanel.updateTheme(isDark);
            // Seed main content first, then history
            outputPanel.setMainThenHistoryAsync(main, history).thenRun(() -> outputPanel.setBlocking(isBlockingMode));

            // Create toolbar panel with capture button if not in blocking mode
            JPanel toolbarPanel = null;
            if (!isBlockingMode) {
                toolbarPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
                toolbarPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));

                MaterialButton captureButton = new MaterialButton("Capture");
                captureButton.setToolTipText("Add the output to context");
                captureButton.addActionListener(e -> {
                    parentPanel.presentCaptureChoice();
                });
                toolbarPanel.add(captureButton);
            }

            // Use shared utility method to create searchable content panel with optional toolbar
            JPanel contentPanel = Chrome.createSearchableContentPanel(List.of(outputPanel), toolbarPanel);

            // Add the content panel to the frame
            add(contentPanel);

            // Load saved size and position, or use defaults
            var bounds = project.getOutputWindowBounds();
            if (bounds.width <= 0 || bounds.height <= 0) {
                setSize(800, 600); // Default size
                setLocationRelativeTo(parentPanel); // Center relative to parent
            } else {
                setSize(bounds.width, bounds.height);
                if (bounds.x >= 0 && bounds.y >= 0 && parentPanel.chrome.isPositionOnScreen(bounds.x, bounds.y)) {
                    setLocation(bounds.x, bounds.y);
                } else {
                    setLocationRelativeTo(parentPanel); // Center relative to parent if off-screen
                }
            }

            // Add listeners to save position/size on change
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    project.saveOutputWindowBounds(OutputWindow.this);
                }

                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    project.saveOutputWindowBounds(OutputWindow.this);
                }
            });

            // Add ESC key binding to close the window
            var rootPane = getRootPane();
            var actionMap = rootPane.getActionMap();
            var inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeWindow");
            actionMap.put("closeWindow", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    dispose();
                }
            });

            // Make window visible
            setVisible(true);
        }

        private static String determineWindowTitle(@Nullable String titleHint, boolean isBlockingMode) {
            String windowTitle;
            if (isBlockingMode) {
                windowTitle = "Output (In progress)";
                if (titleHint != null && !titleHint.isBlank()) {
                    windowTitle = "Output: " + titleHint;
                    String taskType = null;
                    if (titleHint.contains(InstructionsPanel.ACTION_CODE)) {
                        taskType = InstructionsPanel.ACTION_CODE;
                    } else if (titleHint.contains(InstructionsPanel.ACTION_ARCHITECT)) {
                        taskType = InstructionsPanel.ACTION_ARCHITECT;
                    } else if (titleHint.contains(InstructionsPanel.ACTION_SEARCH)) {
                        taskType = InstructionsPanel.ACTION_SEARCH;
                    } else if (titleHint.contains(InstructionsPanel.ACTION_ASK)) {
                        taskType = InstructionsPanel.ACTION_ASK;
                    } else if (titleHint.contains(InstructionsPanel.ACTION_RUN)) {
                        taskType = InstructionsPanel.ACTION_RUN;
                    }
                    if (taskType != null) {
                        windowTitle = String.format("Output (%s in progress)", taskType);
                    }
                }
            } else {
                windowTitle = "Output";
                if (titleHint != null && !titleHint.isBlank()) {
                    windowTitle = "Output: " + titleHint;
                }
            }
            return windowTitle;
        }

        /** Gets the MarkdownOutputPanel used by this window. */
        public MarkdownOutputPanel getMarkdownOutputPanel() {
            return outputPanel;
        }
    }

    /** Disables the history panel components. */
    public void disableHistory() {
        SwingUtilities.invokeLater(() -> {
            historyTable.setEnabled(false);
            undoButton.setEnabled(false);
            redoButton.setEnabled(false);
            compressButton.setEnabled(false);
            autoCompressCheckbox.setEnabled(false);
            // Optionally change appearance to indicate disabled state
            historyTable.setForeground(UIManager.getColor("Label.disabledForeground"));
            // Make the table visually distinct when disabled
            historyTable.setBackground(UIManager.getColor("Panel.background").darker());
        });
    }

    /** Enables the history panel components. */
    public void enableHistory() {
        SwingUtilities.invokeLater(() -> {
            historyTable.setEnabled(true);
            // Restore appearance
            historyTable.setForeground(UIManager.getColor("Table.foreground"));
            historyTable.setBackground(UIManager.getColor("Table.background"));
            compressButton.setEnabled(true);
            autoCompressCheckbox.setEnabled(true);
            updateUndoRedoButtonStates();
        });
    }

    /** A renderer that shows the action text and a diff summary (when available) under it. */
    private class DiffAwareActionRenderer extends DefaultTableCellRenderer {
        private final ActivityTableRenderers.ActionCellRenderer fallback =
                new ActivityTableRenderers.ActionCellRenderer();
        private final Font smallFont = new Font(Font.DIALOG, Font.PLAIN, 11);

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            // Separator handling delegates to existing painter
            if (ActivityTableRenderers.isSeparatorAction(value)) {
                var comp = fallback.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                return adjustRowHeight(table, row, column, comp);
            }

            // Determine context for this row
            Object ctxVal = table.getModel().getValueAt(row, 2);

            // If not a Context row, render a normal label (top-aligned)
            if (!(ctxVal instanceof Context ctx)) {
                var comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (comp instanceof JLabel lbl) {
                    lbl.setVerticalAlignment(JLabel.TOP);
                }
                return adjustRowHeight(table, row, column, comp);
            }

            // Decide whether to render a diff panel or just the label
            var cached = diffCache.get(ctx.id());

            // Not yet cached → kick off background computation; show a compact label for now
            if (cached == null) {
                scheduleDiffComputation(ctx);
                var comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (comp instanceof JLabel lbl) {
                    lbl.setVerticalAlignment(JLabel.TOP);
                }
                return adjustRowHeight(table, row, column, comp);
            }

            // Cached but empty → no changes; compact label
            if (cached.isEmpty()) {
                var comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (comp instanceof JLabel lbl) {
                    lbl.setVerticalAlignment(JLabel.TOP);
                }
                return adjustRowHeight(table, row, column, comp);
            }

            // Cached with entries → build diff summary panel
            boolean isDark = chrome.getTheme().isDarkTheme();

            // Container for per-file rows with an inset on the left
            var diffPanel = new JPanel();
            diffPanel.setLayout(new BoxLayout(diffPanel, BoxLayout.Y_AXIS));
            diffPanel.setOpaque(false);
            diffPanel.setBorder(new EmptyBorder(0, Constants.H_GAP, 0, 0));

            for (var de : cached) {
                String bareName;
                try {
                    var files = de.fragment().files();
                    if (!files.isEmpty()) {
                        var pf = files.iterator().next();
                        bareName = pf.getRelPath().getFileName().toString();
                    } else {
                        bareName = de.fragment().shortDescription();
                    }
                } catch (Exception ex) {
                    bareName = de.fragment().shortDescription();
                }

                var nameLabel = new JLabel(bareName + " ");
                nameLabel.setFont(smallFont);
                nameLabel.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());

                var plus = new JLabel("+" + de.linesAdded());
                plus.setFont(smallFont);
                plus.setForeground(io.github.jbellis.brokk.difftool.utils.Colors.getAdded(!isDark));

                var minus = new JLabel("-" + de.linesDeleted());
                minus.setFont(smallFont);
                minus.setForeground(io.github.jbellis.brokk.difftool.utils.Colors.getDeleted(!isDark));

                var rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                rowPanel.setOpaque(false);
                rowPanel.add(nameLabel);
                rowPanel.add(plus);
                rowPanel.add(minus);

                diffPanel.add(rowPanel);
            }

            // Build composite panel (action text on top, diff below)
            var panel = new JPanel(new BorderLayout());
            panel.setOpaque(true);
            panel.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            panel.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());

            var actionLabel = new JLabel(value != null ? value.toString() : "");
            actionLabel.setOpaque(false);
            actionLabel.setFont(table.getFont());
            actionLabel.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            panel.add(actionLabel, BorderLayout.NORTH);
            panel.add(diffPanel, BorderLayout.CENTER);

            return adjustRowHeight(table, row, column, panel);
        }

        /**
         * Adjust the row height to the preferred height of the rendered component. This keeps rows compact when there
         * is no diff and expands only as needed when a diff summary is present.
         */
        private Component adjustRowHeight(JTable table, int row, int column, Component comp) {
            int colWidth = table.getColumnModel().getColumn(column).getWidth();
            // Give the component the column width so its preferred height is accurate.
            comp.setSize(colWidth, Short.MAX_VALUE);
            int pref = Math.max(18, comp.getPreferredSize().height + 2); // small vertical breathing room
            if (table.getRowHeight(row) != pref) {
                table.setRowHeight(row, pref);
            }
            return comp;
        }
    }

    /** Schedule background computation (with caching) of diff for an AI result context. */
    private void scheduleDiffComputation(Context ctx) {
        if (diffCache.containsKey(ctx.id())) return;
        if (!diffInFlight.add(ctx.id())) return;

        var prev = previousContextMap.get(ctx.id());
        if (prev == null) {
            diffInFlight.remove(ctx.id());
            return;
        }

        contextManager.submitBackgroundTask("Compute diff for history entry", () -> {
            try {
                var diffs = ctx.getDiff(prev);
                diffCache.put(ctx.id(), diffs);
            } finally {
                diffInFlight.remove(ctx.id());
                SwingUtilities.invokeLater(() -> {
                    historyTable.repaint();
                    // Rebuild table so group boundaries can reflect new diff availability
                    updateHistoryTable(null);
                });
            }
        });
    }

    /** Open a multi-file diff preview window for the given AI result context. */
    private void openDiffPreview(Context ctx) {
        var prev = previousContextMap.get(ctx.id());
        if (prev == null) {
            chrome.systemOutput("No previous context to diff against.");
            return;
        }

        contextManager.submitBackgroundTask("Preparing diff preview", () -> {
            var diffs = diffCache.computeIfAbsent(ctx.id(), id -> ctx.getDiff(prev));
            SwingUtilities.invokeLater(() -> showDiffWindow(ctx, diffs));
        });
    }

    private void showDiffWindow(Context ctx, List<Context.DiffEntry> diffs) {
        if (diffs.isEmpty()) {
            chrome.systemOutput("No changes to show.");
            return;
        }

        // Build a multi-file BrokkDiffPanel like showFileHistoryDiff, but with our per-file old/new buffers
        var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager)
                .setMultipleCommitsContext(false)
                .setRootTitle("Diff: " + ctx.getAction())
                .setInitialFileIndex(0);

        for (var de : diffs) {
            String pathDisplay;
            try {
                var files = de.fragment().files();
                if (!files.isEmpty()) {
                    var pf = files.iterator().next();
                    pathDisplay = pf.getRelPath().toString();
                } else {
                    pathDisplay = de.fragment().shortDescription();
                }
            } catch (Exception ex) {
                pathDisplay = de.fragment().shortDescription();
            }

            var left = new BufferSource.StringSource(de.oldContent(), "Previous", pathDisplay);
            var right = new BufferSource.StringSource(de.fragment().text(), "Current", pathDisplay);
            builder.addComparison(left, right);
        }

        var panel = builder.build();
        panel.showInFrame("Diff: " + ctx.getAction());
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

            // Map context IDs to the visible row indices where arrows should anchor.
            // - For visible Context rows, map directly to their row.
            // - For contexts hidden by collapsed groups, map to the group header row.
            Map<UUID, Integer> contextIdToRow = new HashMap<>();

            // 1) First pass: map all visible Context rows
            for (int i = 0; i < model.getRowCount(); i++) {
                var val = model.getValueAt(i, 2);
                if (val instanceof Context ctx) {
                    contextIdToRow.put(ctx.id(), i);
                }
            }

            // 2) Build helper data from the full context history to determine group membership
            var contexts = contextManager.getContextHistoryList();
            Map<UUID, Integer> idToIndex = new HashMap<>();
            for (int i = 0; i < contexts.size(); i++) {
                idToIndex.put(contexts.get(i).id(), i);
            }

            // 3) Second pass: for collapsed groups, map their children context IDs to the group header row
            for (int row = 0; row < model.getRowCount(); row++) {
                var val = model.getValueAt(row, 2);
                if (val instanceof GroupRow gr && !gr.expanded()) {
                    Integer startIdx = idToIndex.get(gr.key());
                    if (startIdx == null) {
                        continue;
                    }
                    int j = startIdx;
                    while (j < contexts.size() && !isGroupingBoundary(contexts.get(j))) {
                        UUID ctxId = contexts.get(j).id();
                        // Only map if not already visible; collapsed children should anchor to the header row
                        contextIdToRow.putIfAbsent(ctxId, row);
                        j++;
                    }
                }
            }

            // 4) Build list of arrows with geometry between the resolved row anchors
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

            // 5) Draw arrows, longest first (so shorter arrows aren't hidden)
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

    // --- Tree-like grouping support types and helpers ---

    public static record GroupRow(UUID key, boolean expanded, boolean containsClearHistory) {}

    private enum PendingSelectionType {
        NONE,
        CLEAR,
        FIRST_IN_GROUP
    }

    private static final class TriangleIcon implements Icon {
        private final boolean expanded;
        private final int size;

        TriangleIcon(boolean expanded) {
            this(expanded, 12);
        }

        TriangleIcon(boolean expanded, int size) {
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

    private boolean isGroupingBoundary(Context ctx) {
        // Grouping boundaries are independent of diff presence.
        // Boundary when this is an AI result, or an explicit "dropped all context" separator.
        return ctx.isAiResult() || ActivityTableRenderers.DROPPED_ALL_CONTEXT.equals(ctx.getAction());
    }

    private static String firstWord(String text) {
        if (text.isBlank()) {
            return "";
        }
        var trimmed = text.trim();
        int idx = trimmed.indexOf(' ');
        return idx < 0 ? trimmed : trimmed.substring(0, idx);
    }

    private void toggleGroupRow(int row) {
        var val = historyModel.getValueAt(row, 2);
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

        // Preserve viewport and suppress any scroll caused by table rebuild
        pendingViewportPosition = historyScrollPane.getViewport().getViewPosition();
        suppressScrollOnNextUpdate = true;

        updateHistoryTable(null);
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

    private String formatModified(long modifiedMillis) {
        var instant = Instant.ofEpochMilli(modifiedMillis);
        return GitUiUtil.formatRelativeDate(instant, LocalDate.now(ZoneId.systemDefault()));
    }

    private void triggerAiCountLoad(SessionInfo session) {
        var id = session.id();
        if (sessionAiResponseCounts.containsKey(id) || sessionCountLoading.contains(id)) {
            return;
        }
        sessionCountLoading.add(id);
        CompletableFuture.supplyAsync(() -> {
                    try {
                        var sm = contextManager.getProject().getSessionManager();
                        var ch = sm.loadHistory(id, contextManager);
                        if (ch == null) return 0;
                        return countAiResponses(ch);
                    } catch (Exception e) {
                        logger.warn("Failed to load history for session {}", id, e);
                        return 0;
                    }
                })
                .thenAccept(count -> {
                    sessionAiResponseCounts.put(id, count);
                    sessionCountLoading.remove(id);
                    SwingUtilities.invokeLater(() -> {
                        sessionComboBox.repaint();
                    });
                });
    }

    private int countAiResponses(ContextHistory ch) {
        var list = ch.getHistory();
        int count = 0;
        for (var ctx : list) {
            if (ctx.isAiResult()) count++;
        }
        return count;
    }

    private class SessionInfoRenderer extends JPanel implements ListCellRenderer<SessionInfo> {
        private final JLabel nameLabel = new JLabel();
        private final JLabel timeLabel = new JLabel();
        private final JLabel countLabel = new JLabel();
        private final JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, Constants.H_GAP, 0));

        SessionInfoRenderer() {
            setLayout(new BorderLayout());
            setOpaque(true);

            // Remove bold from nameLabel
            // nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));

            var baseSize = timeLabel.getFont().getSize2D();
            timeLabel.setFont(timeLabel.getFont().deriveFont(Math.max(10f, baseSize - 2f)));
            countLabel.setFont(timeLabel.getFont());

            row2.setOpaque(false);
            row2.setBorder(new EmptyBorder(0, Constants.H_GAP, 0, 0));
            row2.add(timeLabel);
            row2.add(countLabel);

            add(nameLabel, BorderLayout.NORTH);
            add(row2, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends SessionInfo> list,
                SessionInfo value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            if (index == -1) {
                var label = new JLabel(value.name());
                label.setOpaque(false);
                label.setEnabled(list.isEnabled());
                label.setForeground(list.getForeground());
                return label;
            }
            nameLabel.setText(value.name());
            timeLabel.setText(formatModified(value.modified()));

            var cnt = sessionAiResponseCounts.get(value.id());
            countLabel.setText(cnt != null ? String.format("%d %s", cnt, cnt == 1 ? "task" : "tasks") : "");
            if (cnt == null) {
                triggerAiCountLoad(value);
            }

            var bg = isSelected ? list.getSelectionBackground() : list.getBackground();
            var fg = isSelected ? list.getSelectionForeground() : list.getForeground();

            setBackground(bg);
            nameLabel.setForeground(fg);
            timeLabel.setForeground(fg);
            countLabel.setForeground(fg);

            setEnabled(list.isEnabled());
            return this;
        }
    }
}
