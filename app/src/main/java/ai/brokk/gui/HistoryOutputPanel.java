package ai.brokk.gui;

import static ai.brokk.SessionManager.SessionInfo;
import static java.util.Objects.requireNonNull;

import ai.brokk.Brokk;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.Llm;
import ai.brokk.MainProject;
import ai.brokk.TaskEntry;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextHistory;
import ai.brokk.context.FrozenFragment;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.SpinnerIconUtil;
import ai.brokk.gui.components.SplitButton;
import ai.brokk.gui.mop.MarkdownOutputPanel;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.GitUiUtil;
import ai.brokk.gui.util.Icons;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.ContentDiffUtils;
import ai.brokk.util.GlobalUiSettings;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.LayerUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class HistoryOutputPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(HistoryOutputPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final JTable historyTable;
    private final DefaultTableModel historyModel;
    private final MaterialButton undoButton;
    private final MaterialButton redoButton;
    private final MaterialButton compressButton;
    // Replaced the JComboBox-based session UI with a label + list used in the dropdown.
    @Nullable
    private JList<SessionInfo> sessionsList;

    private final SplitButton sessionNameLabel;
    private final MaterialButton newSessionButton;
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

    // Output tabs
    @Nullable
    private JTabbedPane outputTabs;

    @Nullable
    private JPanel changesTabPlaceholder;

    @Nullable
    private JPanel outputTabContent;

    @Nullable
    private JComponent aggregatedChangesPanel;

    @Nullable
    private JTextArea captureDescriptionArea;

    private final MaterialButton copyButton;
    private final MaterialButton clearButton;
    private final MaterialButton captureButton;
    private final MaterialButton openWindowButton;
    private final JPanel notificationAreaPanel;

    private final MaterialButton notificationsButton = new MaterialButton();
    private final List<NotificationEntry> notifications = new CopyOnWriteArrayList<>();
    private final Queue<NotificationEntry> notificationQueue = new ConcurrentLinkedQueue<>();
    private final Path notificationsFile;
    private boolean isDisplayingNotification = false;

    @Nullable
    private JFrame notificationsDialog;

    @Nullable
    private JPanel notificationsListPanel;

    // Resolve notification colors from ThemeColors for current theme.
    // Returns a list of [background, foreground, border] colors.
    private List<Color> resolveNotificationColors(IConsoleIO.NotificationRole role) {
        boolean isDark = chrome.themeManager.isDarkTheme();
        return switch (role) {
            case ERROR ->
                List.of(
                        ThemeColors.getColor(isDark, "notif_error_bg"),
                        ThemeColors.getColor(isDark, "notif_error_fg"),
                        ThemeColors.getColor(isDark, "notif_error_border"));
            case CONFIRM ->
                List.of(
                        ThemeColors.getColor(isDark, "notif_confirm_bg"),
                        ThemeColors.getColor(isDark, "notif_confirm_fg"),
                        ThemeColors.getColor(isDark, "notif_confirm_border"));
            case COST ->
                List.of(
                        ThemeColors.getColor(isDark, "notif_cost_bg"),
                        ThemeColors.getColor(isDark, "notif_cost_fg"),
                        ThemeColors.getColor(isDark, "notif_cost_border"));
            case INFO ->
                List.of(
                        ThemeColors.getColor(isDark, "notif_info_bg"),
                        ThemeColors.getColor(isDark, "notif_info_fg"),
                        ThemeColors.getColor(isDark, "notif_info_border"));
        };
    }

    private final List<OutputWindow> activeStreamingWindows = new ArrayList<>();

    @Nullable
    private String lastSpinnerMessage = null; // Explicitly initialize

    // Preset state for staging history before next new message
    private @Nullable List<TaskEntry> pendingHistory = null;

    // Track expand/collapse state for grouped non-LLM action runs
    private final Map<UUID, Boolean> groupExpandedState = new HashMap<>();

    // Cache of latest group descriptors used to render the table; used by arrow painter
    private volatile java.util.List<HistoryGrouping.GroupDescriptor> latestDescriptors = java.util.List.of();

    // Selection directives applied after a table rebuild (for expand/collapse UX)
    private PendingSelectionType pendingSelectionType = PendingSelectionType.NONE;
    private @Nullable UUID pendingSelectionGroupKey = null;

    // Viewport preservation flags for group expand/collapse operations
    private boolean suppressScrollOnNextUpdate = false;
    private @Nullable Point pendingViewportPosition = null;

    // Session AI response counts and in-flight loaders
    private final Map<UUID, Integer> sessionAiResponseCounts = new ConcurrentHashMap<>();
    private final Set<UUID> sessionCountLoading = ConcurrentHashMap.newKeySet();

    @Nullable
    private CumulativeChanges lastCumulativeChanges;

    /**
     * Constructs a new HistoryOutputPane.
     *
     * @param chrome         The parent Chrome instance
     * @param contextManager The context manager
     */
    public HistoryOutputPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout()); // Use BorderLayout
        this.chrome = chrome;
        this.contextManager = contextManager;

        // Build combined Output + Instructions panel (Center)
        this.llmStreamArea = new MarkdownOutputPanel();
        this.llmStreamArea.withContextForLookups(contextManager, chrome);
        this.llmScrollPane = buildLLMStreamScrollPane(this.llmStreamArea);
        this.copyButton = new MaterialButton();
        this.clearButton = new MaterialButton();
        this.captureButton = new MaterialButton();
        this.openWindowButton = new MaterialButton();
        SwingUtilities.invokeLater(() -> {
            this.copyButton.setIcon(Icons.CONTENT_COPY);
        });
        this.compressButton = new MaterialButton();
        this.notificationAreaPanel = buildNotificationAreaPanel();

        // Initialize new session button early (used by buildCaptureOutputPanel)
        this.newSessionButton = new MaterialButton();
        this.newSessionButton.setToolTipText("Create a new session");
        this.newSessionButton.addActionListener(e -> {
            contextManager
                    .createSessionAsync(ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> contextManager.getProject().getMainProject().sessionsListChanged());
        });
        // Set the "+" icon asynchronously (keeps EDT responsive for lookups)
        SwingUtilities.invokeLater(() -> this.newSessionButton.setIcon(Icons.ADD));

        // Session selector split button (dropdown only)
        this.sessionNameLabel = new SplitButton("");
        this.sessionNameLabel.setUnifiedHover(true);
        this.sessionNameLabel.setMenuSupplier(() -> {
            var popup = new JPopupMenu();

            // Build the sessions list model
            var model = new DefaultListModel<SessionInfo>();
            var sessions = contextManager.getProject().getSessionManager().listSessions();
            sessions.sort(Comparator.comparingLong(SessionInfo::modified).reversed());
            for (var s : sessions) model.addElement(s);

            var list = new JList<SessionInfo>(model);
            list.setVisibleRowCount(Math.min(8, Math.max(3, model.getSize())));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new SessionInfoRenderer());

            // Select current session in the list
            var currentSessionId = contextManager.getCurrentSessionId();
            for (int i = 0; i < model.getSize(); i++) {
                if (model.getElementAt(i).id().equals(currentSessionId)) {
                    list.setSelectedIndex(i);
                    break;
                }
            }

            // Mouse click: switch session and close popup
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    var sel = list.getSelectedValue();
                    if (sel == null) return;
                    UUID current = contextManager.getCurrentSessionId();
                    if (!sel.id().equals(current)) {
                        contextManager
                                .switchSessionAsync(sel.id())
                                .thenRun(() -> updateSessionComboBox())
                                .exceptionally(ex -> {
                                    chrome.toolError("Failed to switch sessions: " + ex.getMessage());
                                    return null;
                                });
                    }
                    Component c = list;
                    while (c != null && !(c instanceof JPopupMenu)) {
                        c = c.getParent();
                    }
                    if (c instanceof JPopupMenu popupOwner) {
                        popupOwner.setVisible(false);
                    }
                }
            });

            // Enter key: switch session and close popup
            list.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                        var sel = list.getSelectedValue();
                        if (sel == null) return;
                        UUID current = contextManager.getCurrentSessionId();
                        if (!sel.id().equals(current)) {
                            contextManager
                                    .switchSessionAsync(sel.id())
                                    .thenRun(() -> updateSessionComboBox())
                                    .exceptionally(ex -> {
                                        chrome.toolError("Failed to switch sessions: " + ex.getMessage());
                                        return null;
                                    });
                        }
                        Component c = list;
                        while (c != null && !(c instanceof JPopupMenu)) {
                            c = c.getParent();
                        }
                        if (c instanceof JPopupMenu popupOwner) {
                            popupOwner.setVisible(false);
                        }
                    }
                }
            });

            var scroll = new JScrollPane(list);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            int prefWidth = 360;
            int rowHeight = list.getFont().getSize() + 6;
            int prefHeight = Math.min(list.getVisibleRowCount() * rowHeight, 8 * rowHeight);
            int available = getAvailableSpaceBelow(sessionNameLabel);
            int cappedHeight = Math.min(prefHeight, available);
            scroll.setPreferredSize(new Dimension(prefWidth, cappedHeight));

            popup.add(scroll);
            chrome.themeManager.registerPopupMenu(popup);
            return popup;
        });
        this.sessionNameLabel.addActionListener(e -> this.sessionNameLabel.showPopupMenuInternal());

        var centerPanel = buildCombinedOutputInstructionsPanel(this.llmScrollPane, this.copyButton);

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

        this.historyLayeredPane = new JLayeredPane();
        this.historyLayeredPane.setLayout(new OverlayLayout(this.historyLayeredPane));

        var activityPanel = buildActivityPanel(this.historyTable, this.undoButton, this.redoButton);

        // Ensure session label under Output is initialized
        updateSessionComboBox();

        // Create header panel with all controls in a simple horizontal layout
        var headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        headerPanel.setOpaque(true);
        var titledBorder = BorderFactory.createTitledBorder("Session");
        var paddingBorder = BorderFactory.createEmptyBorder(0, 8, 0, 8);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(titledBorder, paddingBorder));

        headerPanel.add(newSessionButton);
        headerPanel.add(new VerticalDivider());

        sessionNameLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        headerPanel.add(sessionNameLabel);

        // Wrap activity panel in a tabbed pane with single "Activity" tab
        var activityTabs = new JTabbedPane(JTabbedPane.TOP);
        activityTabs.addTab("Activity", activityPanel);
        activityTabs.setMinimumSize(new Dimension(250, 0));
        activityTabs.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")));

        // Create center container with both tab panels
        var centerContainer = new JPanel(new BorderLayout(Constants.H_GAP, 0));
        centerContainer.add(centerPanel, BorderLayout.CENTER);
        centerContainer.add(activityTabs, BorderLayout.EAST);

        // Main layout: header at top, center container in center
        add(headerPanel, BorderLayout.NORTH);
        add(centerContainer, BorderLayout.CENTER);

        // Set minimum sizes for the main panel
        setMinimumSize(new Dimension(300, 200)); // Example minimum size

        // Initialize capture controls to disabled until output is available
        setCopyButtonEnabled(false);
        setClearButtonEnabled(false);
        setCaptureButtonEnabled(false);
        setOpenWindowButtonEnabled(false);

        // Respect current Advanced Mode on construction
        setAdvancedMode(GlobalUiSettings.isAdvancedMode());
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
        assert SwingUtilities.isEventDispatchThread() : "buildCombinedOutputInstructionsPanel must be called on EDT";

        // Build capture output panel (copyButton is passed in)
        var capturePanel = buildCaptureOutputPanel(copyButton);

        // Build the content for the Output tab (existing UI)
        var outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createEtchedBorder());

        outputPanel.add(llmScrollPane, BorderLayout.CENTER);
        outputPanel.add(capturePanel, BorderLayout.SOUTH); // Add capture panel below LLM output

        // Save as the output tab content wrapper
        this.outputTabContent = outputPanel;

        // Placeholder for the Changes tab
        var placeholder = new JPanel(new BorderLayout());
        var placeholderLabel = new JLabel("Changes will appear here", SwingConstants.CENTER);
        placeholderLabel.setBorder(new EmptyBorder(20, 0, 20, 0));
        placeholder.add(placeholderLabel, BorderLayout.CENTER);
        this.changesTabPlaceholder = placeholder;

        // Create the tabbed pane and add both tabs
        var tabs = new JTabbedPane();
        tabs.addTab("Output", outputPanel);
        tabs.addTab("Changes", placeholder);
        this.outputTabs = tabs;

        // Container for the combined section
        var centerContainer = new JPanel(new BorderLayout());
        centerContainer.add(tabs, BorderLayout.CENTER);
        centerContainer.setMinimumSize(new Dimension(480, 0)); // Minimum width for combined area

        return centerContainer;
    }

    /**
     * Builds the Sessions panel container (temporary until removal).
     * <p>
     * Note: The "New Session" control has been relocated to the Output panel bar (east side).
     * The session name label is shown under the Output section title.
     * This panel currently does not render the new session control to avoid duplication.
     */
    private JPanel buildSessionControlsPanel(JLabel sessionNameLabel, SplitButton newSessionButton) {
        var panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Sessions",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        // Top row: compact layout with button on the left and the session name on the right.
        var topRow = new JPanel(new BorderLayout(6, 0));
        topRow.setOpaque(false);

        // Ensure new session button has its tooltip and primary action defined by caller.
        // (Action listener that creates a session is attached where the button is created.)

        // New session button intentionally not added here; it lives in the Output panel bar.

        // Session name label moved under the Output panel title; not added here.

        panel.add(topRow, BorderLayout.NORTH);

        // Populate the label and session list data
        updateSessionComboBox();

        return panel;
    }

    private JList<SessionInfo> buildSessionsList() {
        var model = new DefaultListModel<SessionInfo>();
        var list = new JList<SessionInfo>(model);
        list.setVisibleRowCount(8);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SessionInfoRenderer());

        // Mouse handling: clicking a session switches to it and closes the popup if present.
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                var sel = list.getSelectedValue();
                if (sel == null) return;
                UUID current = contextManager.getCurrentSessionId();
                if (!sel.id().equals(current)) {
                    contextManager
                            .switchSessionAsync(sel.id())
                            .thenRun(() -> updateSessionComboBox())
                            .exceptionally(ex -> {
                                chrome.toolError("Failed to switch sessions: " + ex.getMessage());
                                return null;
                            });
                }
                // Try to close enclosing JPopupMenu if any
                Component c = list;
                while (c != null && !(c instanceof JPopupMenu)) {
                    c = c.getParent();
                }
                if (c instanceof JPopupMenu popup) {
                    popup.setVisible(false);
                }
            }
        });

        return list;
    }

    // Integrator note: When sessions are created/deleted/renamed externally, call
    // HistoryOutputPanel.updateSessionComboBox() to keep the compact session label
    // and popup list synchronized.

    /**
     * Refresh the compact session label and (if present) the sessions popup list model.
     *
     * <p>This method runs on the EDT and updates the right-aligned compact session label,
     * and, if the session popup list has already been created/opened, swaps in a new
     * list model showing the current sessions and selects the active session.
     *
     * <p>Call this from outside when the active session or the session list has changed
     * (for example, the SessionManager, SessionsDialog, or other UI components mutate sessions).
     * It is safe to call from non-EDT threads because this method schedules work via
     * {@link SwingUtilities#invokeLater(Runnable)}.
     */
    public void updateSessionComboBox() {
        SwingUtilities.invokeLater(() -> {
            var sessions = contextManager.getProject().getSessionManager().listSessions();
            sessions.sort(Comparator.comparingLong(SessionInfo::modified).reversed());

            // If the sessions list UI exists (i.e. the menu was opened), replace its model atomically.
            if (sessionsList != null) {
                var newModel = new DefaultListModel<SessionInfo>();
                for (var s : sessions) newModel.addElement(s);
                sessionsList.setModel(newModel);

                // Select current session in the list
                var currentSessionId = contextManager.getCurrentSessionId();
                for (int i = 0; i < newModel.getSize(); i++) {
                    if (newModel.getElementAt(i).id().equals(currentSessionId)) {
                        sessionsList.setSelectedIndex(i);
                        break;
                    }
                }
                sessionsList.repaint();
            }

            // Update compact label to show the active session name (with ellipsize and tooltip)
            var currentSessionId = contextManager.getCurrentSessionId();
            String labelText = "";
            for (var s : sessions) {
                if (s.id().equals(currentSessionId)) {
                    labelText = s.name();
                    break;
                }
            }
            if (labelText.isBlank()) {
                labelText = ContextManager.DEFAULT_SESSION_NAME;
            }

            final String fullName = labelText;
            sessionNameLabel.setText(fullName);
            sessionNameLabel.setToolTipText(fullName);
            sessionNameLabel.revalidate();
            // Only repaint the scrollable sessionsList when visible; avoid repainting the old label/combo-box.
            SwingUtilities.invokeLater(() -> {
                if (sessionsList != null) {
                    sessionsList.repaint();
                }
            });
        });
    }

    /** Builds the Activity history panel that shows past contexts */
    private JPanel buildActivityPanel(JTable historyTable, MaterialButton undoButton, MaterialButton redoButton) {
        // Create history panel
        var panel = new JPanel(new BorderLayout());

        historyTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Remove table header
        historyTable.setTableHeader(null);

        // Set up custom renderers for history table columns
        historyTable.getColumnModel().getColumn(0).setCellRenderer(new IndentedIconRenderer());
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
        // Increase to 44 to accommodate one indent level (~15px) + possibly 24px themed icon, ensuring no clipping.
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(38);
        historyTable.getColumnModel().getColumn(0).setMinWidth(38);
        historyTable.getColumnModel().getColumn(0).setMaxWidth(38);
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
        int preferredWidth = 250; // Give a bit more room
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
        showDiffItem.setEnabled(contextManager.getContextHistory().previousOf(context) != null);
        popup.add(showDiffItem);

        popup.addSeparator();

        JMenuItem newSessionFromWorkspaceItem = new JMenuItem("New Session from Workspace");
        newSessionFromWorkspaceItem.addActionListener(event -> {
            contextManager
                    .createSessionFromContextAsync(context, ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> updateSessionComboBox())
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
            historyModel.setRowCount(0);

            int rowToSelect = -1;
            int currentRow = 0;

            var contexts = contextManager.getContextHistoryList();
            // Warm up diffs centrally via ContextHistory.DiffService
            contextManager.getContextHistory().getDiffService().warmUp(contexts);
            var descriptors = HistoryGrouping.GroupingBuilder.discoverGroups(contexts, this::isGroupingBoundary);
            latestDescriptors = descriptors;

            for (var descriptor : descriptors) {
                var children = descriptor.children();

                if (!descriptor.shouldShowHeader()) {
                    // Render singleton (no header)
                    assert children.size() == 1 : "Descriptor without header must be singleton";
                    var ctx = children.getFirst();
                    Icon icon = ctx.isAiResult() ? Icons.CHAT_BUBBLE : null;
                    var actionVal = new ActionText(ctx.getAction(), 0);
                    historyModel.addRow(new Object[] {icon, actionVal, ctx});
                    if (ctx.equals(contextToSelect)) {
                        rowToSelect = currentRow;
                    }
                    currentRow++;
                    continue;
                }

                // Render group header + (optional) children if expanded
                var uuidKey = UUID.fromString(descriptor.key());
                boolean expandedDefault = descriptor.isLastGroup();
                boolean expanded = groupExpandedState.computeIfAbsent(uuidKey, k -> expandedDefault);

                boolean containsClearHistory = children.stream()
                        .anyMatch(c -> ActivityTableRenderers.CLEARED_TASK_HISTORY.equalsIgnoreCase(c.getAction()));

                var groupRow = new GroupRow(uuidKey, expanded, containsClearHistory);
                historyModel.addRow(new Object[] {new TriangleIcon(expanded), descriptor.label(), groupRow});
                currentRow++;

                if (expanded) {
                    for (var child : children) {
                        var childAction = new ActionText(child.getAction(), 1);
                        Icon childIcon = child.isAiResult() ? Icons.CHAT_BUBBLE : null;
                        historyModel.addRow(new Object[] {childIcon, childAction, child});
                        if (child.equals(contextToSelect)) {
                            rowToSelect = currentRow;
                        }
                        currentRow++;
                    }
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

            // Put the Changes tab into a loading state before aggregation
            var tabs = outputTabs;
            if (tabs != null) {
                int idx = -1;
                if (changesTabPlaceholder != null) {
                    idx = tabs.indexOfComponent(changesTabPlaceholder);
                }
                if (idx < 0 && tabs.getTabCount() >= 2) {
                    idx = 1; // Fallback: assume second tab is "Changes"
                }
                if (idx >= 0) {
                    try {
                        tabs.setTitleAt(idx, "Changes (...)");
                        tabs.setToolTipTextAt(idx, "Computing cumulative changes...");
                    } catch (IndexOutOfBoundsException ignore) {
                        // Tab might have changed; ignore safely
                    }
                }

                // Replace content with a spinner while loading
                if (changesTabPlaceholder != null) {
                    var container = changesTabPlaceholder;
                    container.removeAll();
                    container.setLayout(new BorderLayout());

                    var spinnerLabel = new JLabel("Computing cumulative changes...", SwingConstants.CENTER);
                    var spinnerIcon = SpinnerIconUtil.getSpinner(chrome, true);
                    if (spinnerIcon != null) {
                        spinnerLabel.setIcon(spinnerIcon);
                        spinnerLabel.setHorizontalTextPosition(SwingConstants.CENTER);
                        spinnerLabel.setVerticalTextPosition(SwingConstants.BOTTOM);
                    }

                    container.add(spinnerLabel, BorderLayout.CENTER);
                    container.revalidate();
                    container.repaint();
                }
            }

            // Recompute cumulative changes summary for the Changes tab in the background
            refreshCumulativeChangesAsync();
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

        // "Open in New Window" button
        SwingUtilities.invokeLater(() -> {
            openWindowButton.setIcon(Icons.OPEN_NEW_WINDOW);
        });
        openWindowButton.setMnemonic(KeyEvent.VK_W);
        openWindowButton.setToolTipText("Open the output in a new window");
        openWindowButton.addActionListener(e -> {
            if (llmStreamArea.taskInProgress()) {
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

        var popupListener = new MouseAdapter() {
            private void showPopupMenu(MouseEvent e) {
                var popup = new JPopupMenu();

                var copyItem = new JMenuItem("Copy Output");
                copyItem.addActionListener(event -> performContextActionOnLatestHistoryFragment(
                        WorkspacePanel.ContextAction.COPY, "No active context to copy from."));
                copyItem.setEnabled(copyButton.isEnabled());
                popup.add(copyItem);

                var captureItem = new JMenuItem("Capture Output...");
                captureItem.addActionListener(event -> presentCaptureChoice());
                captureItem.setEnabled(captureButton.isEnabled());
                popup.add(captureItem);

                popup.addSeparator();

                var clearItem = new JMenuItem("Clear Output");
                clearItem.addActionListener(event -> performContextActionOnLatestHistoryFragment(
                        WorkspacePanel.ContextAction.DROP, "No active context to clear from."));
                clearItem.setEnabled(clearButton.isEnabled());
                popup.add(clearItem);

                var compressItem = new JMenuItem("Compress History");
                compressItem.addActionListener(event -> contextManager.compressHistoryAsync());
                compressItem.setEnabled(compressButton.isEnabled());
                popup.add(compressItem);

                chrome.themeManager.registerPopupMenu(popup);
                popup.show(e.getComponent(), e.getX(), e.getY());
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopupMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopupMenu(e);
                }
            }
        };
        panel.addMouseListener(popupListener);
        buttonsPanel.addMouseListener(popupListener);
        notificationAreaPanel.addMouseListener(popupListener);

        return panel;
    }

    /**
     * Performs a context action (COPY, DROP, etc.) on the most recent HISTORY fragment in the currently selected
     * context. Shows appropriate user feedback if there is no active context or no history fragment.
     */
    private void performContextActionOnLatestHistoryFragment(
            WorkspacePanel.ContextAction action, String noContextMessage) {
        var ctx = contextManager.selectedContext();
        if (ctx == null) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, noContextMessage);
            return;
        }

        var historyOpt = ctx.getAllFragmentsInDisplayOrder().stream()
                .filter(f -> f.getType() == ContextFragment.FragmentType.HISTORY)
                .reduce((first, second) -> second);

        if (historyOpt.isEmpty()) {
            chrome.showNotification(
                    IConsoleIO.NotificationRole.INFO, "No conversation history found in the current workspace.");
            return;
        }

        var historyFrag = historyOpt.get();
        chrome.getContextPanel().performContextActionAsync(action, List.of(historyFrag));
    }

    // Notification API
    public void showNotification(IConsoleIO.NotificationRole role, String message) {
        Runnable r = () -> {
            var entry = new NotificationEntry(role, message, System.currentTimeMillis());
            notifications.add(entry);
            notificationQueue.offer(entry);
            updateNotificationsButton();
            persistNotificationsAsync();
            refreshLatestNotificationCard();
            refreshNotificationsDialog();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    public void showConfirmNotification(String message, Runnable onAccept, Runnable onReject) {
        Runnable r = () -> {
            var entry = new NotificationEntry(IConsoleIO.NotificationRole.CONFIRM, message, System.currentTimeMillis());
            notifications.add(entry);
            updateNotificationsButton();
            persistNotificationsAsync();
            refreshNotificationsDialog();

            if (isDisplayingNotification) {
                notificationQueue.offer(entry);
            } else {
                notificationAreaPanel.removeAll();
                isDisplayingNotification = true;
                JPanel card = createNotificationCard(IConsoleIO.NotificationRole.CONFIRM, message, onAccept, onReject);
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
        card.putClientProperty("notificationOpacity", 1.0f);

        final int holdDuration = 1000; // 1 second
        final int fadeOutDuration = 1000; // 1 second
        final int fps = 30;
        final int fadeOutFrames = (fadeOutDuration * fps) / 1000;
        final float fadeOutStep = 1.0f / fadeOutFrames;

        final Timer[] timerHolder = new Timer[1];
        final int[] frameCounter = {0};
        final int[] phase = {0}; // 0=hold, 1=fade out

        Timer timer = new Timer(1000 / fps, e -> {
            float currentOpacity = (Float) card.getClientProperty("notificationOpacity");

            if (phase[0] == 0) {
                // Hold
                frameCounter[0]++;
                if (frameCounter[0] >= (holdDuration / (1000 / fps))) {
                    phase[0] = 1;
                    frameCounter[0] = 0;
                }
            } else if (phase[0] == 1) {
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
            IConsoleIO.NotificationRole role,
            String message,
            @Nullable Runnable onAccept,
            @Nullable Runnable onReject) {
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

        if (role == IConsoleIO.NotificationRole.CONFIRM) {
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

    private static String compactMessageForToolbar(IConsoleIO.NotificationRole role, String message) {
        // Show full details for COST; compact other long messages to keep the toolbar tidy
        if (role == IConsoleIO.NotificationRole.COST) {
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

                IConsoleIO.NotificationRole role;
                try {
                    role = IConsoleIO.NotificationRole.valueOf(parts[1]);
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

    // Dialog showing a list of all notifications (modeless, reusable)
    private void showNotificationsDialog() {
        if (notificationsDialog != null && notificationsDialog.isDisplayable()) {
            // Reuse existing window
            var lp = requireNonNull(notificationsListPanel, "notificationsListPanel");
            rebuildNotificationsList(notificationsDialog, lp);
            notificationsDialog.toFront();
            notificationsDialog.requestFocus();
            notificationsDialog.setVisible(true);
            return;
        }

        notificationsDialog = new JFrame("Notifications (" + notifications.size() + ")");
        // Set window icon similar to OutputWindow
        try {
            var iconUrl = Chrome.class.getResource(Brokk.ICON_RESOURCE);
            if (iconUrl != null) {
                var icon = new ImageIcon(iconUrl);
                notificationsDialog.setIconImage(icon.getImage());
            }
        } catch (Exception ex) {
            logger.debug("Failed to set notifications window icon", ex);
        }
        notificationsDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        notificationsDialog.setLayout(new BorderLayout(8, 8));
        notificationsDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                notificationsDialog = null;
                notificationsListPanel = null;
            }
        });

        // Build list panel
        notificationsListPanel = new ScrollableWidthPanel(new GridBagLayout());
        notificationsListPanel.setOpaque(false);
        notificationsListPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        rebuildNotificationsList(notificationsDialog, notificationsListPanel);

        var scroll = new JScrollPane(notificationsListPanel);
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
        closeBtn.addActionListener(e -> {
            if (notificationsDialog != null) {
                notificationsDialog.dispose();
                notificationsDialog = null;
                notificationsListPanel = null;
            }
        });
        buttonPanel.add(closeBtn);

        var clearAllBtn = new MaterialButton("Clear All");
        clearAllBtn.addActionListener(e -> {
            notifications.clear();
            notificationQueue.clear();
            updateNotificationsButton();
            persistNotificationsAsync();
            if (notificationsDialog != null && notificationsListPanel != null) {
                rebuildNotificationsList(notificationsDialog, notificationsListPanel);
            }
        });
        buttonPanel.add(clearAllBtn);

        footer.add(buttonPanel, BorderLayout.EAST);

        notificationsDialog.add(scroll, BorderLayout.CENTER);
        notificationsDialog.add(footer, BorderLayout.SOUTH);

        notificationsDialog.setSize(640, 480);
        notificationsDialog.setLocationRelativeTo(chrome.getFrame());
        notificationsDialog.setVisible(true);
    }

    private void rebuildNotificationsList(JFrame dialog, JPanel listPanel) {
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

                // Clicking on the message area opens a detail view
                leftPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                leftPanel.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        showFullNotificationMessage(n);
                    }
                });

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

    private void showFullNotificationMessage(NotificationEntry notification) {
        var dialog = new JDialog(notificationsDialog, "Notification Details", true);
        dialog.setLayout(new BorderLayout());

        var textArea = new JTextArea(notification.message);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setEditable(false);
        textArea.setOpaque(false);
        textArea.setBorder(new EmptyBorder(10, 10, 10, 10));
        textArea.setFont(UIManager.getFont("Label.font"));

        var scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(null);
        scrollPane.setPreferredSize(new Dimension(500, 300));

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new MaterialButton("OK");
        okButton.addActionListener(e -> dialog.dispose());
        SwingUtil.applyPrimaryButtonStyle(okButton);
        buttonPanel.add(okButton);
        buttonPanel.setBorder(new EmptyBorder(5, 10, 5, 10));

        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(notificationsDialog);
        dialog.setVisible(true);
    }

    // Simple container for notifications
    private static class VerticalDivider extends JPanel {
        VerticalDivider() {
            setOpaque(false);
            setPreferredSize(new Dimension(2, 32));
            setMinimumSize(new Dimension(2, 32));
            setMaximumSize(new Dimension(2, 32));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int x = (getWidth() - 1) / 2;
            g.setColor(UIManager.getColor("Label.foreground"));
            g.drawLine(x, 0, x, getHeight());
        }
    }

    private static class NotificationEntry {
        final IConsoleIO.NotificationRole role;
        final String message;
        final long timestamp;

        NotificationEntry(IConsoleIO.NotificationRole role, String message, long timestamp) {
            this.role = role;
            this.message = message;
            this.timestamp = timestamp;
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String toHex(Color c) {
        if (c == null) return "#000000";
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void removeNotificationCard() {
        Runnable r = () -> {
            refreshLatestNotificationCard();
            updateNotificationsButton();
            persistNotificationsAsync();
            refreshNotificationsDialog();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    // If the notifications window is open, rebuild it to reflect latest items.
    private void refreshNotificationsDialog() {
        SwingUtilities.invokeLater(() -> {
            if (notificationsDialog != null && notificationsDialog.isVisible() && notificationsListPanel != null) {
                rebuildNotificationsList(notificationsDialog, notificationsListPanel);
            }
        });
    }

    public List<ChatMessage> getLlmRawMessages() {
        // pending history means the main area is cleared with the next message with the pending history
        return pendingHistory != null ? List.of() : llmStreamArea.getRawMessages();
    }

    /**
     * Displays a full conversation, splitting it between the history area (for all but the last task) and the main area
     * (for the last task).
     *
     * @param history The list of tasks to show in the history section.
     * @param main    The final task to show in the main output section.
     */
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry main) {
        // Clear any staged preset since we are explicitly setting both main and history
        pendingHistory = null;

        // prioritize rendering live area, then history (explicitly sequenced with flush)
        llmStreamArea.setMainThenHistoryAsync(main, history);
    }

    /**
     * Preset the next history to show on the Output panel without immediately updating the UI;
     * the preset will apply automatically on the first token of the next new message, the main area will be cleared.
     */
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        Runnable r = () -> pendingHistory = history;
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /**
     * If a preset history is staged and this is the start of a new message, apply the preset before any text append.
     * Must be called on the EDT.
     */
    private void applyPresetIfNeeded(boolean isNewMessage) {
        if (!isNewMessage || pendingHistory == null) {
            return;
        }

        assert SwingUtilities.isEventDispatchThread() : "applyPresetIfNeeded must be called on EDT";

        var history = pendingHistory;
        pendingHistory = null;

        // Set an explicit empty main TaskEntry (new-task placeholder) and display the staged history
        var emptyMainFragment = new ContextFragment.TaskFragment(contextManager, List.of(), "");
        var emptyMainTask = new TaskEntry(-1, emptyMainFragment, null);
        llmStreamArea.setMainThenHistoryAsync(emptyMainTask, history);
    }

    /** Appends text to the LLM output area */
    public void appendLlmOutput(String text, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
        // Apply any staged preset exactly once before the first token of the next stream
        applyPresetIfNeeded(isNewMessage);

        llmStreamArea.append(text, type, isNewMessage, isReasoning);
        activeStreamingWindows.forEach(
                window -> window.getMarkdownOutputPanel().append(text, type, isNewMessage, isReasoning));
    }

    /** Sets the enabled state of the copy text button */
    public void setCopyButtonEnabled(boolean enabled) {
        copyButton.setEnabled(enabled);
    }

    /** Sets the enabled state of the clear output button */
    public void setClearButtonEnabled(boolean enabled) {
        clearButton.setEnabled(enabled);
    }

    /** Sets the enabled state of the capture (add to context) button */
    public void setCaptureButtonEnabled(boolean enabled) {
        captureButton.setEnabled(enabled);
    }

    /** Sets the enabled state of the open-in-new-window button */
    public void setOpenWindowButtonEnabled(boolean enabled) {
        openWindowButton.setEnabled(enabled);
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

            // Dispose and clear any existing aggregated Changes panel
            if (aggregatedChangesPanel instanceof BrokkDiffPanel diffPanel) {
                try {
                    diffPanel.dispose();
                } catch (Throwable t) {
                    logger.debug(
                            "Ignoring error disposing previous aggregated BrokkDiffPanel during session switch", t);
                }
            }
            aggregatedChangesPanel = null;

            // Update the Changes tab to a loading state and show a spinner placeholder
            if (outputTabs != null) {
                int idx = -1;
                if (changesTabPlaceholder != null) {
                    idx = outputTabs.indexOfComponent(changesTabPlaceholder);
                }
                if (idx < 0 && outputTabs.getTabCount() >= 2) {
                    idx = 1; // Fallback: assume second tab is "Changes"
                }
                if (idx >= 0) {
                    try {
                        outputTabs.setTitleAt(idx, "(...)");
                        outputTabs.setToolTipTextAt(idx, "Computing cumulative changes...");
                    } catch (IndexOutOfBoundsException ignore) {
                        // Safe-guard: tab lineup may have changed
                    }
                }
            }

            if (changesTabPlaceholder != null) {
                var container = changesTabPlaceholder;
                container.removeAll();
                container.setLayout(new BorderLayout());

                var spinnerLabel = new JLabel("Computing cumulative changes...", SwingConstants.CENTER);
                var spinnerIcon = SpinnerIconUtil.getSpinner(chrome, true);
                if (spinnerIcon != null) {
                    spinnerLabel.setIcon(spinnerIcon);
                    spinnerLabel.setHorizontalTextPosition(SwingConstants.CENTER);
                    spinnerLabel.setVerticalTextPosition(SwingConstants.BOTTOM);
                }

                container.add(spinnerLabel, BorderLayout.CENTER);
                container.revalidate();
                container.repaint();
            }

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
            // Trigger a fresh aggregation for the newly selected session
            refreshCumulativeChangesAsync();
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

    public void setTaskInProgress(boolean inProgress) {
        llmStreamArea.setTaskInProgress(inProgress);
        activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().setTaskInProgress(inProgress));
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
            new OutputWindow(this, historyTasks, mainTask, titleHint, MainProject.getTheme(), false);
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
                new OutputWindow(this, history, mainTask, titleHint, MainProject.getTheme(), true);
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

        var history = selected.getTaskHistory();
        if (history.isEmpty()) {
            chrome.systemNotify("No content to capture", "Capture failed", JOptionPane.WARNING_MESSAGE);
            return;
        }

        var last = history.getLast();
        String captureText = (last.log() != null) ? last.log().text() : last.summary();

        if (captureText == null || captureText.isBlank()) {
            chrome.systemNotify("No content to capture", "Capture failed", JOptionPane.WARNING_MESSAGE);
            return;
        }

        chrome.showOutputSpinner("Creating task list...");
        contextManager.submitLlmAction(() -> {
            try {
                var model = contextManager.getService().quickModel();
                var llm = contextManager.getLlm(new Llm.Options(model, "Create Task List"));
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
                                .formatted(captureText));

                // Register tool providers
                var tr = contextManager
                        .getToolRegistry()
                        .builder()
                        .register(this)
                        .register(new WorkspaceTools(contextManager))
                        .build();

                var toolSpecs = new ArrayList<dev.langchain4j.agent.tool.ToolSpecification>();
                toolSpecs.addAll(tr.getTools(List.of("createTaskList")));
                if (toolSpecs.isEmpty()) {
                    chrome.toolError("Required tool 'createTaskList' is not registered.", "Task List");
                    return;
                }

                var toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr);
                var result = llm.sendRequest(List.of(system, user), toolContext);
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
                        var ter = tr.executeTool(req);
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
         * @param parentPanel      The parent HistoryOutputPanel
         * @param history          The conversation tasks to display in the history section (all but the main task)
         * @param main             The main/last task to display in the live area
         * @param titleHint        A hint for the window title (e.g., task summary or spinner message)
         * @param themeName        The theme name (dark, light, or high-contrast)
         * @param isTaskInProgress Whether the window shows a streaming (in-progress) output
         */
        public OutputWindow(
                HistoryOutputPanel parentPanel,
                List<TaskEntry> history,
                TaskEntry main,
                @Nullable String titleHint,
                String themeName,
                boolean isTaskInProgress) {
            super(determineWindowTitle(titleHint, isTaskInProgress)); // Call superclass constructor first

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
            outputPanel.updateTheme(themeName);
            // Seed main content first, then history
            outputPanel
                    .setMainThenHistoryAsync(main, history)
                    .thenRun(() -> outputPanel.setTaskInProgress(isTaskInProgress));

            // Create toolbar panel with capture button if not task in progress
            JPanel toolbarPanel = null;
            if (!isTaskInProgress) {
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
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    project.saveOutputWindowBounds(OutputWindow.this);
                }

                @Override
                public void componentMoved(ComponentEvent e) {
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
                public void actionPerformed(ActionEvent e) {
                    dispose();
                }
            });

            // Make window visible
            setVisible(true);
        }

        private static String determineWindowTitle(@Nullable String titleHint, boolean isTaskInProgress) {
            String windowTitle;
            if (isTaskInProgress) {
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
            updateUndoRedoButtonStates();
        });
    }

    /**
     * Applies Advanced Mode visibility to session management UI.
     * When advanced is false (easy mode), hides:
     * - the "Open the output in a new window" button
     */
    public void setAdvancedMode(boolean advanced) {
        Runnable r = () -> {
            // Open in new window button (Output panel)
            openWindowButton.setVisible(advanced);
            var btnParent = openWindowButton.getParent();
            if (btnParent != null) {
                btnParent.revalidate();
                btnParent.repaint();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** Icon renderer that mirrors the Action column's indentation for nested rows. */
    private class IndentedIconRenderer extends ActivityTableRenderers.IconCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            // Retrieve the action value from column 1; derive indent level from ActionText if present
            Object actionVal = table.getModel().getValueAt(row, 1);
            Object actionForCheck = (actionVal instanceof ActionText at) ? at.text() : actionVal;

            // Detect group header rows from column 2
            Object contextCol2 = table.getModel().getValueAt(row, 2);
            boolean isHeader = (contextCol2 instanceof GroupRow);

            // Preserve separator and header behavior by delegating to the base renderer unchanged
            if (ActivityTableRenderers.isSeparatorAction(actionForCheck) || isHeader) {
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }

            int indentLevel = (actionVal instanceof ActionText at) ? Math.max(0, at.indentLevel()) : 0;
            Component comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

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
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        assert SwingUtilities.isEventDispatchThread() : "applyTheme must be called on EDT";
        // Propagate theme to child output area
        llmStreamArea.applyTheme(guiTheme);
        // Propagate to aggregated Changes panel (BrokkDiffPanel implements ThemeAware)
        if (aggregatedChangesPanel instanceof ThemeAware ta) {
            ta.applyTheme(guiTheme);
        }

        // Recompute the Changes tab title colors to match the new theme if we have a computed summary
        if (outputTabs != null && lastCumulativeChanges != null) {
            int idx = -1;
            if (changesTabPlaceholder != null) {
                idx = outputTabs.indexOfComponent(changesTabPlaceholder);
            }
            if (idx < 0 && outputTabs.getTabCount() >= 2) {
                idx = 1; // Fallback: assume second tab is "Changes"
            }
            if (idx >= 0) {
                var res = lastCumulativeChanges;
                try {
                    if (res.filesChanged() == 0) {
                        outputTabs.setTitleAt(idx, "Changes (0)");
                        outputTabs.setToolTipTextAt(idx, "No changes in this session.");
                    } else {
                        boolean isDark = chrome.getTheme().isDarkTheme();
                        Color plusColor = ThemeColors.getColor(isDark, "diff_added_fg");
                        Color minusColor = ThemeColors.getColor(isDark, "diff_deleted_fg");
                        String htmlTitle = String.format(
                                "<html>Changes (%d, <span style='color:%s'>+%d</span>/<span style='color:%s'>-%d</span>)</html>",
                                res.filesChanged(),
                                toHex(plusColor),
                                res.totalAdded(),
                                toHex(minusColor),
                                res.totalDeleted());
                        outputTabs.setTitleAt(idx, htmlTitle);
                        String tooltip = "Cumulative changes: "
                                + res.filesChanged()
                                + " files, +" + res.totalAdded()
                                + "/-" + res.totalDeleted();
                        outputTabs.setToolTipTextAt(idx, tooltip);
                    }
                } catch (IndexOutOfBoundsException ignore) {
                    // Tab lineup changed; safe to ignore
                }
            }
        }

        SwingUtilities.updateComponentTreeUI(this);
        revalidate();
        repaint();
    }

    /**
     * Releases owned resources. Must be called on the EDT.
     */
    public void dispose() {
        assert SwingUtilities.isEventDispatchThread() : "dispose must be called on EDT";
        // Dispose aggregated changes panel if present
        if (aggregatedChangesPanel instanceof BrokkDiffPanel diffPanel) {
            try {
                diffPanel.dispose();
            } catch (Throwable t) {
                logger.debug(
                        "Ignoring error disposing aggregated BrokkDiffPanel during HistoryOutputPanel.dispose()", t);
            } finally {
                aggregatedChangesPanel = null;
            }
        } else {
            aggregatedChangesPanel = null;
        }
        // Dispose the web-based markdown output panel
        try {
            llmStreamArea.dispose();
        } catch (Throwable t) {
            logger.debug("Ignoring error disposing MarkdownOutputPanel during HistoryOutputPanel.dispose()", t);
        }
    }

    /** A renderer that shows the action text and a diff summary (when available) under it. */
    private class DiffAwareActionRenderer extends DefaultTableCellRenderer {
        private final ActivityTableRenderers.ActionCellRenderer fallback =
                new ActivityTableRenderers.ActionCellRenderer();
        private final Font smallFont = new Font(Font.DIALOG, Font.PLAIN, 11);

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            // Extract action text and structural indent level
            int indentLevel = 0;
            String actionText;
            if (value instanceof ActionText at) {
                actionText = at.text();
                indentLevel = Math.max(0, at.indentLevel());
            } else {
                actionText = value != null ? value.toString() : "";
            }

            // Separator handling delegates to existing painter
            if (ActivityTableRenderers.isSeparatorAction(actionText)) {
                var comp = fallback.getTableCellRendererComponent(table, actionText, isSelected, hasFocus, row, column);
                return adjustRowHeight(table, row, column, comp);
            }

            // Determine context for this row
            Object ctxVal = table.getModel().getValueAt(row, 2);

            // If not a Context row, render a normal label (top-aligned)
            if (!(ctxVal instanceof Context ctx)) {
                var comp = super.getTableCellRendererComponent(table, actionText, isSelected, hasFocus, row, column);
                if (comp instanceof JLabel lbl) {
                    lbl.setVerticalAlignment(JLabel.TOP);
                }
                if (comp instanceof JComponent jc) {
                    jc.setToolTipText(actionText); // Show full header text on hover
                }
                return adjustRowHeight(table, row, column, comp);
            }

            // Decide whether to render a diff panel or just the label
            var ds = contextManager.getContextHistory().getDiffService();
            var cachedOpt = ds.peek(ctx);

            // Kick off background computation if needed
            if (cachedOpt.isEmpty()) {
                ds.diff(ctx).whenComplete((r, ex) -> SwingUtilities.invokeLater(table::repaint));
            }

            // Build action component using LAF-consistent renderer, but make it non-opaque
            var actionComp =
                    fallback.getTableCellRendererComponent(table, actionText, isSelected, hasFocus, row, column);
            if (actionComp instanceof JComponent jc) {
                jc.setOpaque(false);
            }

            // Composite panel that applies structural indent to the entire cell
            var panel = new JPanel(new BorderLayout());
            panel.setOpaque(true);
            panel.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            panel.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());

            int indentPx = indentLevel * Constants.H_GAP * 3;
            panel.setBorder(new EmptyBorder(0, indentPx, 0, 0));
            panel.add(actionComp, BorderLayout.NORTH);
            // Ensure tooltip is visible even though we return a composite panel
            panel.setToolTipText(actionText);

            // If we have cached diff entries, add a summary panel; otherwise, action-only
            if (cachedOpt.isPresent() && !cachedOpt.get().isEmpty()) {
                boolean isDark = chrome.getTheme().isDarkTheme();

                var diffPanel = new JPanel();
                diffPanel.setLayout(new BoxLayout(diffPanel, BoxLayout.Y_AXIS));
                diffPanel.setOpaque(false);
                // No extra left inset; the outer panel border provides the indent alignment
                diffPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

                for (var de : cachedOpt.get()) {
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
                    plus.setForeground(ThemeColors.getDiffAdded(!isDark));

                    var minus = new JLabel("-" + de.linesDeleted());
                    minus.setFont(smallFont);
                    minus.setForeground(ThemeColors.getDiffDeleted(!isDark));

                    var rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                    rowPanel.setOpaque(false);
                    rowPanel.add(nameLabel);
                    rowPanel.add(plus);
                    rowPanel.add(minus);

                    diffPanel.add(rowPanel);
                }

                panel.add(diffPanel, BorderLayout.CENTER);
            }

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

    /** Open a multi-file diff preview window for the given AI result context. */
    private void openDiffPreview(Context ctx) {
        var ch = contextManager.getContextHistory();
        if (ch.previousOf(ctx) == null) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No previous context to diff against.");
            return;
        }
        var ds = ch.getDiffService();
        ds.diff(ctx).thenAccept(diffs -> SwingUtilities.invokeLater(() -> showDiffWindow(ctx, diffs)));
    }

    private void showDiffWindow(Context ctx, List<Context.DiffEntry> diffs) {
        if (diffs.isEmpty()) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No changes to show.");
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

            // Use the typed API matching buildAggregatedChangesPanel: BufferSource.StringSource +
            // addComparison(BufferSource, BufferSource)
            String leftContent = de.oldContent() == null ? "" : de.oldContent();
            String rightContent = safeFragmentText(de);
            BufferSource left = new BufferSource.StringSource(leftContent, "Previous", pathDisplay, null);
            BufferSource right = new BufferSource.StringSource(rightContent, "Current", pathDisplay, null);
            builder.addComparison(left, right);
        }

        if (!GlobalUiSettings.isDiffUnifiedView()) GlobalUiSettings.saveDiffUnifiedView(true);
        var panel = builder.build();
        panel.showInFrame("Diff: " + ctx.getAction());
    }

    // Compute the net changes across the entire session history in the background,
    // reusing cached diffs where possible. Updates the "Changes" tab title and content on the EDT.
    // Net changes = earliest version -> latest version for each file (matches the unified diff visual).
    private CompletableFuture<CumulativeChanges> refreshCumulativeChangesAsync() {
        return contextManager
                .submitBackgroundTask("Aggregate session changes", () -> {
                    var contexts = contextManager.getContextHistoryList();
                    var ch = contextManager.getContextHistory();
                    var ds = ch.getDiffService();

                    // Step 1: Collect all DiffEntry objects across all contexts (compute in parallel via cache)
                    var futures = new ArrayList<CompletableFuture<List<Context.DiffEntry>>>();
                    for (var ctx : contexts) {
                        if (ch.previousOf(ctx) != null) {
                            futures.add(ds.diff(ctx));
                        }
                    }
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .join();

                    var allDiffEntries = new ArrayList<Context.DiffEntry>();
                    for (var f : futures) {
                        try {
                            var list = f.get();
                            allDiffEntries.addAll(list);
                        } catch (Exception ignore) {
                            // skip failed computations; best-effort aggregation
                        }
                    }

                    // Step 2: Identify unique frozen fragments by source matching
                    var uniqueFragments = new ArrayList<FrozenFragment>();
                    for (var de : allDiffEntries) {
                        FrozenFragment ff = de.fragment();
                        boolean found = false;
                        for (var existing : uniqueFragments) {
                            if (ff.hasSameSource(existing)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            uniqueFragments.add(ff);
                        }
                    }

                    // Step 3: For each unique fragment, find earliest and latest versions and compute net diff
                    List<PerFileChange> perFileChanges = new ArrayList<>();
                    int totalAdded = 0;
                    int totalDeleted = 0;

                    for (var uniqueFrag : uniqueFragments) {
                        String earliestContent = null;
                        String latestContent = null;

                        // Forward loop: find first occurrence (earliest)
                        for (var de : allDiffEntries) {
                            if (uniqueFrag.hasSameSource(de.fragment())) {
                                earliestContent = de.oldContent();
                                break;
                            }
                        }

                        // Backward loop: find last occurrence (latest)
                        for (int i = allDiffEntries.size() - 1; i >= 0; i--) {
                            var de = allDiffEntries.get(i);
                            if (uniqueFrag.hasSameSource(de.fragment())) {
                                latestContent = safeFragmentText(de);
                                break;
                            }
                        }

                        if (earliestContent != null) {
                            // Compute net diff for this fragment
                            int[] netCounts = computeNetLineCounts(earliestContent, latestContent);
                            totalAdded += netCounts[0];
                            totalDeleted += netCounts[1];

                            // Derive a display file name for the diff view; keep extension for syntax highlighting
                            String displayFile;
                            var files = uniqueFrag.files();
                            if (files.size() == 1) {
                                var pf = files.iterator().next();
                                displayFile = pf.getRelPath().toString();
                            } else {
                                displayFile = uniqueFrag.shortDescription();
                            }

                            perFileChanges.add(new PerFileChange(displayFile, earliestContent, latestContent));
                        }
                    }

                    return new CumulativeChanges(perFileChanges.size(), totalAdded, totalDeleted, perFileChanges);
                })
                .thenApply(result -> {
                    // Update UI on EDT
                    SwingUtilities.invokeLater(() -> {
                        lastCumulativeChanges = result;

                        var tabs = outputTabs;
                        if (tabs != null) {
                            int idx = -1;
                            if (changesTabPlaceholder != null) {
                                idx = tabs.indexOfComponent(changesTabPlaceholder);
                            }
                            if (idx < 0 && tabs.getTabCount() >= 2) {
                                // Fallback: assume second tab is "Changes"
                                idx = 1;
                            }
                            if (idx >= 0) {
                                if (result.filesChanged() == 0) {
                                    try {
                                        tabs.setTitleAt(idx, "Changes (0)");
                                        tabs.setToolTipTextAt(idx, "No changes in this session.");
                                    } catch (IndexOutOfBoundsException ignore) {
                                        // Tab disappeared or index changed; ignore
                                    }
                                } else {
                                    boolean isDark = chrome.getTheme().isDarkTheme();
                                    Color plusColor = ThemeColors.getColor(isDark, "diff_added_fg");
                                    Color minusColor = ThemeColors.getColor(isDark, "diff_deleted_fg");
                                    String htmlTitle = String.format(
                                            "<html>Changes (%d, <span style='color:%s'>+%d</span>/<span style='color:%s'>-%d</span>)</html>",
                                            result.filesChanged(),
                                            toHex(plusColor),
                                            result.totalAdded(),
                                            toHex(minusColor),
                                            result.totalDeleted());
                                    try {
                                        tabs.setTitleAt(idx, htmlTitle);
                                        String tooltip = "Cumulative changes: "
                                                + result.filesChanged()
                                                + " files, +" + result.totalAdded()
                                                + "/-" + result.totalDeleted();
                                        tabs.setToolTipTextAt(idx, tooltip);
                                    } catch (IndexOutOfBoundsException ignore) {
                                        // Tab disappeared or index changed; ignore
                                    }
                                }
                            }
                        }

                        // Render or update the Changes tab content
                        updateChangesTabContent(result);
                    });
                    return result;
                });
    }

    // Build and insert the aggregated multi-file diff panel into the Changes tab.
    // Must be called on the EDT.
    private void updateChangesTabContent(CumulativeChanges res) {
        assert SwingUtilities.isEventDispatchThread() : "updateChangesTabContent must run on EDT";
        var container = changesTabPlaceholder;
        if (container == null) {
            return;
        }

        // Dispose any previous diff panel to free resources
        if (aggregatedChangesPanel instanceof BrokkDiffPanel diffPanel) {
            try {
                diffPanel.dispose();
            } catch (Throwable t) {
                logger.debug("Ignoring error disposing previous BrokkDiffPanel", t);
            }
        }
        aggregatedChangesPanel = null;

        container.removeAll();

        if (res.filesChanged() == 0) {
            var none = new JLabel("No changes in this session.", SwingConstants.CENTER);
            none.setBorder(new EmptyBorder(20, 0, 20, 0));
            container.setLayout(new BorderLayout());
            container.add(none, BorderLayout.CENTER);
            container.revalidate();
            container.repaint();
            return;
        }

        try {
            var aggregatedPanel = buildAggregatedChangesPanel(res);
            container.setLayout(new BorderLayout());
            container.add(aggregatedPanel, BorderLayout.CENTER);
        } catch (Throwable t) {
            logger.warn("Failed to build aggregated Changes panel", t);
            container.setLayout(new BorderLayout());
            var err = new JLabel("Unable to display aggregated changes.", SwingConstants.CENTER);
            err.setBorder(new EmptyBorder(20, 0, 20, 0));
            container.removeAll();
            container.add(err, BorderLayout.CENTER);
            aggregatedChangesPanel = null;
        }
        container.revalidate();
        container.repaint();
    }

    // Constructs a panel containing a summary header and a BrokkDiffPanel with per-file comparisons.
    // Sets aggregatedChangesPanel to the created BrokkDiffPanel for lifecycle management.
    private JPanel buildAggregatedChangesPanel(CumulativeChanges res) {
        var wrapper = new JPanel(new BorderLayout());

        // Use a compound border: line border for separation + padding
        wrapper.setBorder(new CompoundBorder(
                new LineBorder(UIManager.getColor("Separator.foreground"), 1), new EmptyBorder(6, 6, 6, 6)));

        // Build BrokkDiffPanel with string sources
        String projectName = "Project";
        try {
            var proj = contextManager.getProject();
            var root = proj.getRoot();
            if (root.getFileName() != null) {
                projectName = root.getFileName().toString();
            }
        } catch (Exception ignored) {
            // fallback to default projectName
        }

        var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager)
                .setMultipleCommitsContext(false)
                .setRootTitle(projectName)
                .setInitialFileIndex(0)
                .setForceFileTree(true);

        // Stable order by display file path
        var changes = new ArrayList<>(res.perFileChanges());
        changes.sort(Comparator.comparing(PerFileChange::displayFile));

        for (var change : changes) {
            String path = change.displayFile();
            String leftContent = change.earliestOld();
            String rightContent = change.latestNew();

            // Use non-ref titles to avoid accidental git ref resolution; keep filename for syntax highlighting.
            BufferSource left = new BufferSource.StringSource(leftContent, "", path, null);
            BufferSource right = new BufferSource.StringSource(rightContent, "", path, null);
            builder.addComparison(left, right);
        }

        if (!GlobalUiSettings.isDiffUnifiedView()) GlobalUiSettings.saveDiffUnifiedView(true);
        var diffPanel = builder.build();
        aggregatedChangesPanel = diffPanel;
        // Ensure the embedded diff reflects the current theme immediately
        diffPanel.applyTheme(chrome.getTheme());

        wrapper.add(diffPanel, BorderLayout.CENTER);
        return wrapper;
    }

    // Compute the net added/deleted line counts between two versions of a file.
    // Uses ContentDiffUtils for accurate Myers-algorithm-based diff counts.
    private static int[] computeNetLineCounts(String earliestOld, String latestNew) {
        var result = ContentDiffUtils.computeDiffResult(earliestOld, latestNew, "old", "new");
        return new int[] {result.added(), result.deleted()};
    }

    private static String safeFragmentText(Context.DiffEntry de) {
        try {
            return de.fragment().text();
        } catch (Throwable t) {
            return "";
        }
    }

    private record PerFileChange(String displayFile, String earliestOld, String latestNew) {}

    private record CumulativeChanges(
            int filesChanged, int totalAdded, int totalDeleted, List<PerFileChange> perFileChanges) {}

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
            Map<UUID, Integer> contextIdToRow = HistoryGrouping.buildContextToRowMap(latestDescriptors, table);

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

    // Structural action text + indent data for column 1 (Option A)
    private static record ActionText(String text, int indentLevel) {}

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
        // Boundary when this is an AI result WITHOUT a groupId, or an explicit "dropped all context" separator.
        // Note: AI results that carry a groupId are NOT boundaries and may merge into a single GROUP_BY_ID run.
        return (ctx.isAiResult() && ctx.getGroupId() == null)
                || ActivityTableRenderers.DROPPED_ALL_CONTEXT.equals(ctx.getAction());
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

    // Adapted from BranchSelectorButton#getAvailableSpaceBelow:
    // Compute vertical space available below the given anchor within the current screen,
    // accounting for taskbar/dock insets. If the component is not yet showing, fall back to 400.
    private int getAvailableSpaceBelow(Component anchor) {
        try {
            Point screenLoc = anchor.getLocationOnScreen();
            GraphicsConfiguration gc = anchor.getGraphicsConfiguration();
            Rectangle screenBounds = (gc != null)
                    ? gc.getBounds()
                    : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            Insets insets = (gc != null) ? Toolkit.getDefaultToolkit().getScreenInsets(gc) : new Insets(0, 0, 0, 0);
            int bottomEdge = screenBounds.y + screenBounds.height - insets.bottom;
            int anchorBottom = screenLoc.y + anchor.getHeight();
            return Math.max(0, bottomEdge - anchorBottom);
        } catch (IllegalComponentStateException e) {
            // If not yet showing, fall back to a reasonable default
            return 400;
        }
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

    /**
     * Kicks off a background load of the AI-response count for the given session. Runs on a platform thread to avoid
     * blocking the common ForkJoinPool. Safe to call repeatedly; concurrent calls are deduped by sessionCountLoading.
     */
    private void triggerAiCountLoad(SessionInfo session) {
        final var id = session.id();

        // Fast-path dedupe: if we already have a value or a load is in-flight, bail.
        if (sessionAiResponseCounts.containsKey(id) || !sessionCountLoading.add(id)) {
            return;
        }

        Thread.ofPlatform().name("ai-count-" + id).start(() -> {
            int count = 0;
            try {
                var sm = contextManager.getProject().getSessionManager();
                var ch = sm.loadHistory(id, contextManager);
                count = (ch == null) ? 0 : countAiResponses(ch);
            } catch (Throwable t) {
                logger.warn("Failed to load history for session {}", id, t);
                count = 0;
            } finally {
                sessionAiResponseCounts.put(id, count);
                sessionCountLoading.remove(id);
                // Only repaint the scrollable sessionsList when visible; avoid repainting the old label/combo-box.
                SwingUtilities.invokeLater(() -> {
                    if (sessionsList != null) {
                        sessionsList.repaint();
                    }
                });
            }
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
