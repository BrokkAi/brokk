package ai.brokk.gui;

import static java.util.Objects.requireNonNull;

import ai.brokk.*;
import ai.brokk.LlmOutputMeta;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.context.ComputedSubscription;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.ui.ToolbarFeature;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.SpinnerIconUtil;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.history.HistoryTable;
import ai.brokk.gui.mop.MarkdownOutputPanel;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.theme.ThemeTitleBarManager;
import ai.brokk.gui.util.GitDiffUiUtil;
import ai.brokk.gui.util.Icons;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.GlobalUiSettings;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public class HistoryOutputPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(HistoryOutputPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final HistoryTable historyTableComponent;
    private final MaterialButton undoButton;
    private final MaterialButton redoButton;
    private final MaterialButton compressButton;

    @Nullable
    private JPanel sessionSwitchPanel;

    @Nullable
    private JLabel sessionSwitchSpinner;

    private JLayeredPane historyLayeredPane;

    // Output components
    private final MarkdownOutputPanel llmStreamArea;
    private final JScrollPane llmScrollPane;

    @SuppressWarnings("NullAway.Init") // Initialized in constructor
    private JTabbedPane activityTabs;

    @SuppressWarnings("NullAway.Init") // Initialized in constructor
    private JPanel activityTabsContainer;

    @SuppressWarnings("NullAway.Init") // Initialized in constructor
    private JPanel llmOutputContainer;

    // Capture/notification bar container for fixed sizing in vertical layout
    @Nullable
    private JPanel captureOutputPanel;

    private final MaterialButton copyButton;
    private final MaterialButton clearButton;
    private final MaterialButton captureButton;
    private final MaterialButton openWindowButton;
    private final JPanel notificationAreaPanel;

    private final MaterialButton notificationsButton = new MaterialButton();
    private final List<NotificationEntry> notifications = new CopyOnWriteArrayList<>();
    private final Queue<TransientNotification> notificationQueue = new ConcurrentLinkedQueue<>();
    private final Path notificationsFile;
    private boolean isDisplayingNotification = false;

    private int rolledUpCostCount = 0;

    @Nullable
    private TransientNotification currentlyDisplayedNotification;

    @Nullable
    private JPanel currentlyDisplayedNotificationCard;

    @Nullable
    private JLabel currentlyDisplayedNotificationLabel;

    @Nullable
    private JFrame notificationsDialog;

    @Nullable
    private JPanel notificationsListPanel;

    // Resolve notification colors from ThemeColors for current theme.
    // Returns a list of [background, foreground, border] colors.
    private List<Color> resolveNotificationColors(IConsoleIO.NotificationRole role) {
        boolean isDark = chrome.getThemeManager().isDarkTheme();
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

    // Preset state for staging history before next new message
    private @Nullable List<TaskEntry> pendingHistory = null;

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
        this.llmStreamArea.setShowEmptyState(true);
        this.llmStreamArea.setContextForLookups(contextManager, chrome);
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

        var centerPanel = buildCombinedOutputInstructionsPanel(this.llmScrollPane, this.copyButton);

        // Initialize notification persistence and load saved notifications
        this.notificationsFile = computeNotificationsFile();
        loadPersistedNotifications();

        // Build session controls and activity panel (East)
        this.historyTableComponent = new HistoryTable(contextManager, chrome);
        this.undoButton = new MaterialButton();
        this.redoButton = new MaterialButton();

        this.historyLayeredPane = new JLayeredPane();
        this.historyLayeredPane.setLayout(new OverlayLayout(this.historyLayeredPane));

        var activityPanel = buildActivityPanel(this.historyTableComponent, this.undoButton, this.redoButton);

        // Wrap activity panel in a tabbed pane with single "Activity" tab
        activityTabs = new JTabbedPane(JTabbedPane.TOP);
        activityTabs.addTab("Activity", activityPanel);
        activityTabs.setMinimumSize(new Dimension(250, 0));
        activityTabs.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")));

        // Create center container with both tab panels
        activityTabsContainer = new JPanel(new BorderLayout(Constants.H_GAP, 0));
        activityTabsContainer.add(centerPanel, BorderLayout.CENTER);

        // Main layout: activityTabsContainer (containing centerPanel and activityTabs)
        add(activityTabsContainer, BorderLayout.CENTER);

        // Set minimum sizes for the main panel
        setMinimumSize(new Dimension(300, 200)); // Example minimum size

        // Initialize capture controls to disabled until output is available
        setCopyButtonEnabled(false);
        setClearButtonEnabled(false);
        setCaptureButtonEnabled(false);
        setOpenWindowButtonEnabled(false);

        // Respect current Advanced Mode on construction
        setAdvancedMode(GlobalUiSettings.isAdvancedMode());

        // Default to showing the spinner while initial load/connection happens
        showSessionSwitchSpinner();
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
        notificationText.setFont(historyTableComponent.getTable().getFont());
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

        // Build the content for the Output area
        var outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createEtchedBorder());

        outputPanel.add(llmScrollPane, BorderLayout.CENTER);
        outputPanel.add(capturePanel, BorderLayout.SOUTH); // Add capture panel below LLM output

        // Container for the output area
        var centerContainer = new JPanel(new BorderLayout());
        centerContainer.add(outputPanel, BorderLayout.CENTER);
        centerContainer.setMinimumSize(new Dimension(480, 0)); // Minimum width for output area
        llmOutputContainer = centerContainer;

        return centerContainer;
    }

    /**
     * Builds the Activity history panel that shows past contexts
     */
    private JPanel buildActivityPanel(
            HistoryTable historyTableComponent, MaterialButton undoButton, MaterialButton redoButton) {
        // Create history panel
        var panel = new JPanel(new BorderLayout());

        historyTableComponent.addSelectionListener(contextFromHistory -> {
            if (!contextFromHistory.equals(contextManager.selectedContext())) {
                contextManager.setSelectedContext(contextFromHistory);
            }
        });

        historyTableComponent.addDoubleClickListener(context -> {
            if (isReviewContext(context)) {
                loadReviewFromContext(context);
            } else if (context.isAiResult()) {
                openDiffPreview(context);
            } else {
                openOutputWindowFromContext(context);
            }
        });

        historyTableComponent.addContextMenuListener((context, e) -> showPopupForContext(context, e.getX(), e.getY()));

        AutoScroller.install(historyTableComponent.getScrollPane());

        // Add undo/redo buttons at the bottom, side by side
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));

        undoButton.setMnemonic(KeyEvent.VK_Z);
        undoButton.setToolTipText("Undo the most recent history entry");
        undoButton.setPreferredSize(new Dimension(100, 28));
        undoButton.addActionListener(e -> {
            contextManager.undoContextAsync();
        });
        SwingUtilities.invokeLater(() -> {
            undoButton.setIcon(Icons.UNDO);
        });

        redoButton.setMnemonic(KeyEvent.VK_Y);
        redoButton.setToolTipText("Redo the most recently undone entry");
        redoButton.setPreferredSize(new Dimension(100, 28));
        redoButton.addActionListener(e -> {
            contextManager.redoContextAsync();
        });
        SwingUtilities.invokeLater(() -> {
            redoButton.setIcon(Icons.REDO);
        });

        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);
        buttonPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        historyLayeredPane.add(historyTableComponent, JLayeredPane.DEFAULT_LAYER);

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

    private void showPopupForContext(Context context, int x, int y) {
        // Create popup menu
        JPopupMenu popup = new JPopupMenu();

        JMenuItem undoToHereItem = new JMenuItem("Undo to here");
        undoToHereItem.addActionListener(event -> undoHistoryUntil(context));
        popup.add(undoToHereItem);

        JMenuItem resetToHereItem = new JMenuItem("Copy Context");
        resetToHereItem.addActionListener(event -> resetContextTo(context));
        popup.add(resetToHereItem);

        JMenuItem resetToHereIncludingHistoryItem = new JMenuItem("Copy Context + History");
        resetToHereIncludingHistoryItem.addActionListener(event -> resetContextToIncludingHistory(context));
        popup.add(resetToHereIncludingHistoryItem);

        // Show diff or Load Review based on context type
        if (isReviewContext(context)) {
            JMenuItem loadReviewItem = new JMenuItem("Load Review");
            loadReviewItem.addActionListener(event -> loadReviewFromContext(context));
            popup.add(loadReviewItem);
        } else {
            // Show diff (uses BrokkDiffPanel)
            JMenuItem showDiffItem = new JMenuItem("Show diff");
            showDiffItem.addActionListener(event -> openDiffPreview(context));
            // Enable only if we have a previous context to diff against
            showDiffItem.setEnabled(contextManager.getContextHistory().previousOf(context) != null);
            popup.add(showDiffItem);
        }

        popup.addSeparator();

        JMenuItem newSessionFromWorkspaceItem = new JMenuItem("New Session from Workspace");
        newSessionFromWorkspaceItem.addActionListener(event -> {
            contextManager
                    .createSessionFromContextAsync(context, ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> chrome.getRightPanel().updateSessionComboBox())
                    .exceptionally(ex -> {
                        chrome.toolError("Failed to create new session from workspace: " + ex.getMessage());
                        return null;
                    });
        });
        popup.add(newSessionFromWorkspaceItem);

        // Show popup menu
        popup.show(historyTableComponent.getTable(), x, y);
    }

    /**
     * Restore context to a specific point in history
     */
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

    /**
     * Creates a new context based on the files, fragments, and history from a historical context
     */
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
                contextToSelect != null ? contextToSelect.id() : "null");

        SwingUtilities.invokeLater(() -> {
            historyTableComponent.setHistory(contextManager.getContextHistory(), contextToSelect);
            contextManager.getProject().getMainProject().sessionsListChanged();
            updateUndoRedoButtonStates();
        });
    }

    /**
     * Returns the history table for selection checks
     *
     * @return The JTable containing context history
     */
    public @Nullable JTable getHistoryTable() {
        return historyTableComponent.getTable();
    }

    public JTabbedPane getActivityTabs() {
        return activityTabs;
    }

    public JPanel getActivityTabsContainer() {
        return activityTabsContainer;
    }

    public JPanel getLlmOutputContainer() {
        return llmOutputContainer;
    }

    /**
     * Builds the LLM streaming area where markdown output is displayed
     */
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

    /**
     * Builds the "Capture Output" panel with a horizontal layout: [Capture Text]
     */
    private JPanel buildCaptureOutputPanel(MaterialButton copyButton) {
        var panel = new JPanel(new BorderLayout(5, 3));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        // Fixed height for capture panel
        panel.setPreferredSize(new Dimension(0, 38));
        panel.setMinimumSize(new Dimension(0, 38));

        // Buttons panel on the left
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        // "Open in New Window" button
        SwingUtilities.invokeLater(() -> {
            openWindowButton.setIcon(Icons.OPEN_NEW_WINDOW);
            openWindowButton.setPreferredSize(new Dimension(24, 24));
            openWindowButton.setMinimumSize(new Dimension(24, 24));
            openWindowButton.setMaximumSize(new Dimension(24, 24));
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
        buttonsPanel.add(openWindowButton);

        // Capture button (Advanced Mode only)
        captureButton.setToolTipText("Capture output to workspace");
        captureButton.addActionListener(e -> presentCaptureChoice());
        SwingUtilities.invokeLater(() -> {
            captureButton.setIcon(Icons.CONTENT_CAPTURE);
            captureButton.setPreferredSize(new Dimension(24, 24));
            captureButton.setMinimumSize(new Dimension(24, 24));
            captureButton.setMaximumSize(new Dimension(24, 24));
        });
        captureButton.setVisible(GlobalUiSettings.isAdvancedMode());
        buttonsPanel.add(captureButton);

        // Notifications button
        notificationsButton.setToolTipText("Show notifications");
        notificationsButton.addActionListener(e -> showNotificationsDialog());
        SwingUtilities.invokeLater(() -> {
            notificationsButton.setIcon(Icons.NOTIFICATIONS);
            notificationsButton.setPreferredSize(new Dimension(24, 24));
            notificationsButton.setMinimumSize(new Dimension(24, 24));
            notificationsButton.setMaximumSize(new Dimension(24, 24));
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
                        ContextActionsHandler.ContextAction.COPY, "No active context to copy from."));
                copyItem.setEnabled(copyButton.isEnabled());
                popup.add(copyItem);

                var captureItem = new JMenuItem("Capture Output...");
                captureItem.addActionListener(event -> presentCaptureChoice());
                captureItem.setEnabled(captureButton.isEnabled());
                popup.add(captureItem);

                popup.addSeparator();

                var clearItem = new JMenuItem("Clear Output");
                clearItem.addActionListener(event -> performContextActionOnLatestHistoryFragment(
                        ContextActionsHandler.ContextAction.DROP, "No active context to clear from."));
                clearItem.setEnabled(clearButton.isEnabled());
                popup.add(clearItem);

                var compressItem = new JMenuItem("Compress History");
                compressItem.addActionListener(event -> contextManager.compressHistoryAsync());
                compressItem.setEnabled(compressButton.isEnabled());
                popup.add(compressItem);

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

        this.captureOutputPanel = panel;
        return panel;
    }

    /**
     * Performs a context action (COPY, DROP, etc.) on the most recent HISTORY fragment in the currently selected
     * context. Shows appropriate user feedback if there is no active context or no history fragment.
     */
    private void performContextActionOnLatestHistoryFragment(
            ContextActionsHandler.ContextAction action, String noContextMessage) {
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
        chrome.getContextActionsHandler().performContextActionAsync(action, List.of(historyFrag));
    }

    // Notification API
    public void showNotification(IConsoleIO.NotificationRole role, String message) {
        Runnable r = () -> {
            assert SwingUtilities.isEventDispatchThread() : "showNotification mutations must be called on EDT";

            long now = System.currentTimeMillis();

            var entry = new NotificationEntry(role, message, now);
            notifications.add(entry);

            if (role != IConsoleIO.NotificationRole.COST) {
                notificationQueue.offer(new TransientNotification(role, message, 0, entry));
            } else {
                if (isDisplayingNotification
                        && currentlyDisplayedNotification != null
                        && currentlyDisplayedNotification.role == IConsoleIO.NotificationRole.COST
                        && notificationQueue.isEmpty()
                        && currentlyDisplayedNotificationCard != null
                        && currentlyDisplayedNotificationLabel != null) {
                    rolledUpCostCount++;
                    currentlyDisplayedNotification.message = message;
                    currentlyDisplayedNotification.rolledUpCostCount = rolledUpCostCount;
                    currentlyDisplayedNotification.persistedEntry = entry;

                    currentlyDisplayedNotificationLabel.setText(
                            toNotificationLabelHtml(IConsoleIO.NotificationRole.COST, message, rolledUpCostCount));
                    restartNotificationCardAnimation(requireNonNull(currentlyDisplayedNotificationCard));
                } else {
                    var lastQueued = getLastQueuedNotification();
                    if (lastQueued != null && lastQueued.role == IConsoleIO.NotificationRole.COST) {
                        lastQueued.rolledUpCostCount++;
                        lastQueued.message = message;
                        lastQueued.persistedEntry = entry;
                    } else {
                        notificationQueue.offer(new TransientNotification(role, message, 0, entry));
                    }
                }
            }

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

    public void applyFixedCaptureBarSizing(boolean enabled) {
        // Enforce fixed sizing for the capture/notification bar and buttons in vertical layout.
        // Do not change behavior for standard layout; this is only applied when Chrome enables vertical layout.
        SwingUtilities.invokeLater(() -> {
            final int barHeight = 38;
            final int btnSize = 24;

            if (enabled) {
                if (captureOutputPanel != null) {
                    captureOutputPanel.setPreferredSize(new Dimension(0, barHeight));
                    captureOutputPanel.setMinimumSize(new Dimension(0, barHeight));
                    captureOutputPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, barHeight));
                }
                notificationAreaPanel.setPreferredSize(new Dimension(0, barHeight));
                notificationAreaPanel.setMinimumSize(new Dimension(0, barHeight));
                notificationAreaPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, barHeight));
                Dimension btnDim = new Dimension(btnSize, btnSize);
                openWindowButton.setPreferredSize(btnDim);
                openWindowButton.setMinimumSize(btnDim);
                openWindowButton.setMaximumSize(btnDim);
                notificationsButton.setPreferredSize(btnDim);
                notificationsButton.setMinimumSize(btnDim);
                notificationsButton.setMaximumSize(btnDim);
            }

            if (captureOutputPanel != null) {
                captureOutputPanel.revalidate();
                captureOutputPanel.repaint();
            }
            notificationAreaPanel.revalidate();
            notificationAreaPanel.repaint();
        });
    }

    private JPanel buildNotificationAreaPanel() {
        var p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, 5, 0, 0));
        // Fixed height to match capture panel
        p.setPreferredSize(new Dimension(0, 38));
        p.setMinimumSize(new Dimension(0, 38));
        return p;
    }

    private @Nullable TransientNotification getLastQueuedNotification() {
        TransientNotification last = null;
        for (var n : notificationQueue) {
            last = n;
        }
        return last;
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
        currentlyDisplayedNotification = nextToShow;
        rolledUpCostCount = nextToShow.role == IConsoleIO.NotificationRole.COST ? nextToShow.rolledUpCostCount : 0;

        JPanel card = createNotificationCard(nextToShow.role, nextToShow.message, null, null);
        currentlyDisplayedNotificationCard = card;
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
                frameCounter[0]++;
                if (frameCounter[0] >= (holdDuration / (1000 / fps))) {
                    phase[0] = 1;
                    frameCounter[0] = 0;
                }
            } else if (phase[0] == 1) {
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

    private void restartNotificationCardAnimation(JPanel card) {
        var existing = (Timer) card.getClientProperty("notificationTimer");
        if (existing != null) {
            existing.stop();
        }
        card.putClientProperty("notificationOpacity", 1.0f);
        card.repaint();
        animateNotificationCard(card);
    }

    private void dismissCurrentNotification() {
        isDisplayingNotification = false;
        currentlyDisplayedNotification = null;
        currentlyDisplayedNotificationCard = null;
        currentlyDisplayedNotificationLabel = null;
        rolledUpCostCount = 0;

        notificationAreaPanel.removeAll();
        notificationAreaPanel.revalidate();
        notificationAreaPanel.repaint();
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
        int costCount = role == IConsoleIO.NotificationRole.COST ? rolledUpCostCount : 0;
        var msg = new JLabel(toNotificationLabelHtml(role, message, costCount));
        msg.setForeground(fg);
        msg.setVerticalAlignment(JLabel.CENTER);
        msg.setHorizontalAlignment(JLabel.LEFT);
        card.add(msg, BorderLayout.CENTER);

        currentlyDisplayedNotificationLabel = msg;

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

    private static String toNotificationLabelHtml(IConsoleIO.NotificationRole role, String message, int rolledUpCount) {
        String display = compactMessageForToolbar(role, message);
        if (rolledUpCount > 0) {
            display = display + " [+" + rolledUpCount + " more]";
        }
        return "<html><div style='width:100%; text-align: left; word-wrap: break-word; white-space: normal;'>"
                + escapeHtml(display) + "</div></html>";
    }

    private static String compactMessageForToolbar(IConsoleIO.NotificationRole role, String message) {
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
            AtomicWrites.save(notificationsFile, String.join("\n", linesToPersist) + "\n");
        } catch (Exception e) {
            logger.warn("Failed to persist notifications to {}", notificationsFile, e);
        }
    }

    private void loadPersistedNotifications() {
        try {
            if (!Files.exists(notificationsFile)) {
                return;
            }
            var lines = java.nio.file.Files.readAllLines(notificationsFile, StandardCharsets.UTF_8);
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

            SwingUtilities.invokeLater(() -> {});
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

        var title = "Notifications (" + notifications.size() + ")";
        notificationsDialog = Chrome.newFrame(title);
        notificationsDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        notificationsDialog.setLayout(new BorderLayout(8, 8));
        ThemeTitleBarManager.maybeApplyMacTitleBar(notificationsDialog, title);
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
                    notificationQueue.removeIf(entry -> entry.persistedEntry == n);
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
        var dialog = new BaseThemedDialog(notificationsDialog, "Notification Details");
        var root = dialog.getContentRoot();
        root.setLayout(new BorderLayout());

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

        root.add(scrollPane, BorderLayout.CENTER);
        root.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(notificationsDialog);
        dialog.setVisible(true);
    }

    // Simple container for notifications
    static class VerticalDivider extends JPanel {
        public VerticalDivider() {
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

    private static final class TransientNotification {
        final IConsoleIO.NotificationRole role;
        String message;
        int rolledUpCostCount;

        @Nullable
        NotificationEntry persistedEntry;

        TransientNotification(
                IConsoleIO.NotificationRole role,
                String message,
                int rolledUpCostCount,
                @Nullable NotificationEntry persistedEntry) {
            this.role = role;
            this.message = message;
            this.rolledUpCostCount = rolledUpCostCount;
            this.persistedEntry = persistedEntry;
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

    private void removeNotificationCard() {
        Runnable r = () -> {
            refreshLatestNotificationCard();
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
     * Returns true if the MarkdownOutputPanel has displayable content.
     *
     * <p>This method checks if there is rendered output visible in the panel, even if
     * {@link #getLlmRawMessages()} returns an empty list due to staged {@code pendingHistory}.
     * It is suitable for determining UI state (e.g., enabling/disabling buttons) based on
     * whether there is actual content to display.
     *
     * @return true if the panel has displayable output, false otherwise
     */
    public boolean hasDisplayableOutput() {
        // First check if there is rendered/displayed text in the panel
        String displayedText = llmStreamArea.getDisplayedText();
        if (!displayedText.isEmpty()) {
            return true;
        }

        // Fall back to checking raw messages (in case displayed text is not yet available)
        return !llmStreamArea.getRawMessages().isEmpty();
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
     * Must be called on EDT (Chrome ensures this).
     */
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        assert SwingUtilities.isEventDispatchThread() : "prepareOutputForNextStream must be called on EDT";
        pendingHistory = history;
    }

    /**
     * If a preset history is staged and this is the start of a new message, apply the preset before any text append.
     * Must be called on the EDT.
     */
    private void applyPresetIfNeeded() {
        if (pendingHistory == null) {
            return;
        }

        assert SwingUtilities.isEventDispatchThread() : "applyPresetIfNeeded must be called on EDT";

        var history = pendingHistory;
        pendingHistory = null;

        // Set an explicit empty main TaskEntry (new-task placeholder) and display the staged history
        var emptyMainFragment = new ContextFragments.TaskFragment(contextManager, List.of(), "");
        var emptyMainTask = new TaskEntry(-1, emptyMainFragment, null);
        llmStreamArea.setMainThenHistoryAsync(emptyMainTask, history);
    }

    /**
     * Appends text to the LLM output area
     */
    public void appendLlmOutput(String text, ChatMessageType type, LlmOutputMeta meta) {
        // Apply any staged preset exactly once before the first token of the next stream
        applyPresetIfNeeded();

        llmStreamArea.append(text, type, meta);
    }

    /**
     * Sets the enabled state of the copy text button
     */
    public void setCopyButtonEnabled(boolean enabled) {
        copyButton.setEnabled(enabled);
    }

    /**
     * Sets the enabled state of the clear output button
     */
    public void setClearButtonEnabled(boolean enabled) {
        clearButton.setEnabled(enabled);
    }

    /**
     * Sets the enabled state of the capture (add to context) button
     */
    public void setCaptureButtonEnabled(boolean enabled) {
        captureButton.setEnabled(enabled);
    }

    /**
     * Sets the enabled state of the open-in-new-window button
     */
    public void setOpenWindowButtonEnabled(boolean enabled) {
        openWindowButton.setEnabled(enabled);
    }

    /**
     * Shows the loading spinner with a message in the Markdown area.
     */
    public void showSpinner(String message) {
        llmStreamArea.showSpinner(message);
    }

    /**
     * Hides the loading spinner in the Markdown area.
     */
    public void hideSpinner() {
        llmStreamArea.hideSpinner();
    }

    /**
     * Shows the session switching spinner.
     */
    public void showSessionSwitchSpinner() {
        SwingUtilities.invokeLater(() -> {
            historyTableComponent.getModel().setRowCount(0);
            ComputedSubscription.disposeAll(historyTableComponent.getTable());

            if (sessionSwitchPanel == null) {
                buildSessionSwitchPanel();
                historyLayeredPane.add(requireNonNull(sessionSwitchPanel), JLayeredPane.PALETTE_LAYER);
            }

            sessionSwitchPanel.setVisible(true);
            sessionSwitchPanel.revalidate();
            sessionSwitchPanel.repaint();
        });
    }

    /**
     * Hides the session switching spinner.
     */
    public void hideSessionSwitchSpinner() {
        SwingUtilities.invokeLater(() -> {
            if (sessionSwitchPanel != null) {
                sessionSwitchPanel.setVisible(false);
                sessionSwitchPanel.revalidate();
                sessionSwitchPanel.repaint();
            }
        });
    }

    /**
     * Gets the LLM scroll pane
     */
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
    }

    /**
     * Opens the current output in a new window. If a task is in progress, opens a streaming window;
     * otherwise, opens a window with the currently selected context. Safe to call from any thread.
     */
    public void openOutputInNewWindow() {
        Runnable action = () -> {
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
        };

        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private void openOutputWindowFromContext(Context context) {
        var taskHistory = context.getTaskHistory();
        if (taskHistory.isEmpty()) {
            var output = context.getParsedOutput();
            if (output != null) {
                taskHistory = List.of(new TaskEntry(-1, output, null));
            }
        }

        if (!taskHistory.isEmpty()) {
            var fragment = new ContextFragments.HistoryFragment(contextManager, taskHistory);
            chrome.openFragmentPreview(fragment);
        }
    }

    private void openOutputWindowStreaming() {
        List<ChatMessage> currentMessages = llmStreamArea.getRawMessages();
        var tempFragment = new ContextFragments.TaskFragment(contextManager, currentMessages, "Streaming Output...");
        var history = new ArrayList<>(contextManager.liveContext().getTaskHistory());
        history.add(new TaskEntry(-1, tempFragment, null));

        var fragment = new ContextFragments.HistoryFragment(contextManager, history);
        chrome.openFragmentPreview(fragment);
    }

    /**
     * Presents a choice to capture output to Workspace or to Task List.
     */
    private void presentCaptureChoice() {
        String[] options = {"Workspace", "Task List", "Cancel"};
        int choice = MaterialOptionPane.showOptionDialog(
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

    /**
     * Creates a task list from the currently selected output using the quick model and the createTaskList tool.
     */
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
        CompletableFuture<String> captureTextFuture =
                (last.log() != null) ? last.log().text().future() : CompletableFuture.completedFuture(last.summary());

        captureTextFuture.thenAccept(captureText -> {
            if (captureText.isBlank()) {
                chrome.systemNotify("No content to capture", "Capture failed", JOptionPane.WARNING_MESSAGE);
                return;
            }

            chrome.showOutputSpinner("Creating task list...");
            contextManager.submitLlmAction(() -> {
                try {
                    var model = contextManager.getService().getScanModel();
                    var llm = contextManager.getLlm(new Llm.Options(model, "Create Task List"));
                    llm.setOutput(chrome);

                    var system = new SystemMessage(
                            """
    You are generating an actionable, incremental task list based on the provided capture. Do not speculate beyond it.
    You MUST produce tasks via exactly one tool call: createOrReplaceTaskList(explanation: String, tasks: List<String>).
                                    Do not output free-form text.
    """);
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

                                    Call createOrReplaceTaskList(explanation: String, tasks: List<String>) with your final list. Do not include any explanation outside the tool call.

        Guidance:
    %s
    """
                                    .formatted(captureText, WorkspaceTools.TASK_LIST_GUIDANCE));

                    // Register tool providers
                    var ws = new WorkspaceTools(contextManager.liveContext());
                    var tr = contextManager
                            .getToolRegistry()
                            .builder()
                            .register(this)
                            .register(ws)
                            .build();

                    var toolSpecs = new ArrayList<>(tr.getTools(List.of("createOrReplaceTaskList")));
                    if (toolSpecs.isEmpty()) {
                        chrome.toolError("Required tool 'createOrReplaceTaskList' is not registered.", "Task List");
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
                            if (!"createOrReplaceTaskList".equals(req.name())) {
                                continue;
                            }
                            var ter = tr.executeTool(req);
                            if (ter.status() != ToolExecutionResult.Status.SUCCESS) {
                                chrome.toolError("Failed to create task list: " + ter.resultText(), "Task List");
                            } else {
                                this.contextManager.pushContext(ctx -> ws.getContext());
                            }
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    chrome.systemNotify(
                            "Task list creation was interrupted.", "Task List", JOptionPane.WARNING_MESSAGE);
                } catch (Throwable t) {
                    chrome.systemNotify(
                            "Unexpected error creating task list: " + t.getMessage(),
                            "Task List",
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    chrome.hideOutputSpinner();
                }
            });
        });
    }

    /**
     * Disables the history panel components.
     */
    public void disableHistory() {
        SwingUtilities.invokeLater(() -> {
            historyTableComponent.getTable().setEnabled(false);
            undoButton.setEnabled(false);
            redoButton.setEnabled(false);
            compressButton.setEnabled(false);
            // Optionally change appearance to indicate disabled state
            historyTableComponent.getTable().setForeground(UIManager.getColor("Label.disabledForeground"));
            // Make the table visually distinct when disabled
            historyTableComponent
                    .getTable()
                    .setBackground(UIManager.getColor("Panel.background").darker());
        });
    }

    /**
     * Enables the history panel components.
     */
    public void enableHistory() {
        SwingUtilities.invokeLater(() -> {
            historyTableComponent.getTable().setEnabled(true);
            // Restore appearance
            historyTableComponent.getTable().setForeground(UIManager.getColor("Table.foreground"));
            historyTableComponent.getTable().setBackground(UIManager.getColor("Table.background"));
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
            // Capture button (Output panel)
            captureButton.setVisible(advanced);
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

    /**
     * Returns true if the given Context's most recent task is a REVIEW type.
     */
    private boolean isReviewContext(Context ctx) {
        var meta = ActivityTableRenderers.lastMetaOf(ctx);
        return meta != null && meta.type() == TaskResult.Type.REVIEW;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        assert SwingUtilities.isEventDispatchThread() : "applyTheme must be called on EDT";
        // Propagate theme to child output area
        llmStreamArea.applyTheme(guiTheme);

        SwingUtilities.updateComponentTreeUI(this);
        revalidate();
        repaint();
    }

    public void showTransientMessage(String message) {
        // Apply preset layout before showing transient message
        applyPresetIfNeeded();
        llmStreamArea.showTransientMessage(message);
    }

    public void hideTransientMessage() {
        llmStreamArea.hideTransientMessage();
    }

    public void dispose() {
        assert SwingUtilities.isEventDispatchThread() : "dispose must be called on EDT";
        // Dispose the web-based markdown output panel
        try {
            llmStreamArea.dispose();
        } catch (Throwable t) {
            logger.debug("Ignoring error disposing MarkdownOutputPanel during HistoryOutputPanel.dispose()", t);
        }
    }

    /**
     * Loads a review from a Context that contains a REVIEW TaskResult.
     * Extracts the final AI message, parses it as a GuidedReview, and displays it in SessionChangesPanel.
     */
    private void loadReviewFromContext(Context context) {
        var taskHistory = context.getTaskHistory();
        if (taskHistory.isEmpty()) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No review content found in context.");
            return;
        }

        var lastEntry = taskHistory.getLast();
        var log = lastEntry.log();
        if (log == null) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No review content found in context.");
            return;
        }

        // Extract the final AI message from the TaskFragment
        var messages = log.messages();
        String reviewMarkdown = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.type() == ChatMessageType.AI) {
                if (msg instanceof AiMessage aiMsg) {
                    reviewMarkdown = aiMsg.text();
                    break;
                }
            }
        }

        if (reviewMarkdown == null || reviewMarkdown.isBlank()) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No review content found in context.");
            return;
        }

        // Load the review into SessionChangesPanel
        chrome.getRightPanel().loadReviewFromMarkdown(reviewMarkdown, context);
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

    private void showDiffWindow(Context ctx, List<DiffService.FragmentDiff> diffs) {

        record BufferedSourcePair(BufferSource left, BufferSource right) {}

        if (diffs.isEmpty()) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No changes to show.");
            return;
        }

        contextManager.submitBackgroundTask("Compute diff window entries", () -> {
            // Build a multi-file BrokkDiffPanel like showFileHistoryDiff, but with our per-file old/new buffers
            var prevOfCtx = contextManager.getContextHistory().previousOf(ctx);
            String actionDesc = ctx.getAction(prevOfCtx).renderNowOr(Context.SUMMARIZING);
            var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager)
                    .setMultipleCommitsContext(false)
                    .setRootTitle("Diff: " + actionDesc)
                    .setToolbarFeatures(ToolbarFeature.viewOnly())
                    .setInitialFileIndex(0);
            String tabTitle = "Diff: " + actionDesc;
            if (diffs.size() == 1) {
                var files = diffs.getFirst().fragment().files().join();
                if (!files.isEmpty()) {
                    tabTitle = "Diff of " + files.iterator().next().getFileName();
                }
            }

            var diffPairFutures = new ArrayList<CompletableFuture<BufferedSourcePair>>();
            for (var de : diffs) {
                var task = contextManager.submitBackgroundTask("Compute diff window entry for:" + de, () -> {
                    String pathDisplay;
                    var files = de.fragment().files().join();
                    if (!files.isEmpty()) {
                        var pf = files.iterator().next();
                        pathDisplay = pf.getRelPath().toString();
                    } else {
                        pathDisplay = de.fragment().shortDescription().join();
                    }

                    // Use the typed API matching buildAggregatedChangesPanel: BufferSource.StringSource +
                    // addComparison(BufferSource, BufferSource)
                    String leftContent = de.oldText();
                    String rightContent = safeFragmentText(de);
                    BufferSource left = new BufferSource.StringSource(leftContent, "Previous", pathDisplay, null);
                    BufferSource right = new BufferSource.StringSource(rightContent, "Current", pathDisplay, null);
                    return new BufferedSourcePair(left, right);
                });
                diffPairFutures.add(task);
            }

            var allDone = CompletableFuture.allOf(diffPairFutures.toArray(new CompletableFuture[0]));
            allDone.join(); // Block together

            diffPairFutures.stream().map(CompletableFuture::join).forEach(pair -> {
                builder.addComparison(pair.left(), pair.right());
            });

            final String finalTabTitle = tabTitle;
            SwingUtilities.invokeLater(() -> {
                // Contract: callers must not enforce unified/side-by-side globally; BrokkDiffPanel reads and persists
                // the user's choice when they toggle view (Fixes #1679)
                var panel = builder.build();
                panel.showInTab(chrome.getPreviewManager(), finalTabTitle);
            });
        });
    }

    @Blocking
    private static String safeFragmentText(DiffService.FragmentDiff de) {
        try {
            return de.fragment().text().join();
        } catch (Throwable t) {
            return "";
        }
    }

    private String formatModified(long modifiedMillis) {
        var instant = Instant.ofEpochMilli(modifiedMillis);
        return GitDiffUiUtil.formatRelativeDate(instant, LocalDate.now(ZoneId.systemDefault()));
    }
}
