package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.IContextManager.ContextListener;
import ai.brokk.SessionManager;
import ai.brokk.context.Context;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.NoticeBanner;
import ai.brokk.gui.components.PreviewTabbedPane;
import ai.brokk.gui.components.SplitButton;
import ai.brokk.gui.dialogs.DetachableTabFrame;
import ai.brokk.gui.terminal.TaskListPanel;
import ai.brokk.gui.terminal.TerminalPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.BadgedIcon;
import ai.brokk.gui.util.GitDiffUiUtil;
import ai.brokk.gui.util.Icons;
import ai.brokk.util.GlobalUiSettings;
import ai.brokk.util.HistoryIo;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates the right-side UI stack: Output (History), Instructions, Tasks, and Terminal.
 * Manages the "Build" vs "Review" tabs and the vertical layout transformations.
 */
public class RightPanel extends JPanel implements ThemeAware {
    private static final double DEFAULT_OUTPUT_MAIN_SPLIT = 0.4;

    private final Chrome chrome;
    private final HistoryOutputPanel historyOutputPanel;
    private final InstructionsPanel instructionsPanel;
    private final TaskListPanel taskListPanel;
    private final NoticeBanner historicalNoticePanel;
    private final TerminalPanel terminalPanel;

    private final JPanel sessionHeaderPanel;
    private final SplitButton sessionNameLabel;
    private final MaterialButton newSessionButton;

    private final JSplitPane buildSplitPane;
    private final JTabbedPane buildReviewTabs;
    private PreviewTabbedPane previewTabbedPane;
    private final JTabbedPane commandPane;
    private final JPanel commandPanel;
    private final JPanel branchSelectorPanel;
    private final BranchSelectorButton branchSelectorButton;

    private @Nullable JSplitPane verticalActivityCombinedPanel = null;
    private @Nullable JSplitPane verticalLayoutLeftSplit = null;

    private @Nullable DetachableTabFrame reviewFrame = null;
    private @Nullable DetachableTabFrame terminalFrame = null;

    // Review tab infrastructure
    private final JComponent reviewTabComponent;
    private @Nullable BadgedIcon buildTabBadgedIcon;
    private @Nullable BadgedIcon reviewTabBadgedIcon;

    private int getReviewTabIndex() {
        return buildReviewTabs.indexOfComponent(reviewTabComponent);
    }

    private final ContextManager contextManager;

    private final TabDragUndockHandler tabDragUndockHandler;

    enum UndockTarget {
        REVIEW,
        PREVIEW,
        TERMINAL,
        NONE
    }

    static UndockTarget getUndockTarget(
            Component comp,
            Component reviewTabComponent,
            Component previewTabbedPane,
            Component terminalPanel,
            Component buildSplitPane,
            @Nullable Component verticalActivityCombinedPanel) {
        if (comp == buildSplitPane
                || (verticalActivityCombinedPanel != null && comp == verticalActivityCombinedPanel)) {
            return UndockTarget.NONE;
        }
        if (comp == reviewTabComponent) return UndockTarget.REVIEW;
        if (comp == previewTabbedPane) return UndockTarget.PREVIEW;
        if (comp == terminalPanel) return UndockTarget.TERMINAL;
        return UndockTarget.NONE;
    }

    public RightPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        historyOutputPanel = new HistoryOutputPanel(chrome, contextManager);

        // Session header components
        newSessionButton = new MaterialButton();
        newSessionButton.setToolTipText("Create a new session");
        newSessionButton.addActionListener(e -> {
            contextManager
                    .createSessionAsync(ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> contextManager.getProject().getMainProject().sessionsListChanged());
        });
        SwingUtilities.invokeLater(() -> newSessionButton.setIcon(Icons.ADD));

        sessionNameLabel = new SplitButton("");
        sessionNameLabel.setUnifiedHover(true);
        sessionNameLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        sessionNameLabel.setMenuSupplier(() -> createSessionMenu());
        sessionNameLabel.addActionListener(e -> sessionNameLabel.showPopupMenuInternal());

        sessionHeaderPanel = createSessionHeader();
        updateSessionComboBox();

        historicalNoticePanel = new NoticeBanner();
        instructionsPanel = new InstructionsPanel(chrome);
        taskListPanel = new TaskListPanel(chrome);
        terminalPanel =
                new TerminalPanel(chrome, () -> {}, true, chrome.getProject().getRoot());

        // Command tabs: Instructions | Tasks
        commandPane = new JTabbedPane(JTabbedPane.TOP);
        commandPane.addTab("Instructions", Icons.CHAT_BUBBLE, instructionsPanel);
        commandPane.addTab("Tasks", Icons.LIST, taskListPanel);

        // Branch header
        branchSelectorButton = new BranchSelectorButton(chrome);
        branchSelectorPanel = createBranchSelectorHeader();

        commandPanel = new JPanel(new BorderLayout());
        var commandHeaderStack = new JPanel();
        commandHeaderStack.setLayout(new BoxLayout(commandHeaderStack, BoxLayout.Y_AXIS));
        commandHeaderStack.add(branchSelectorPanel);
        commandHeaderStack.add(historicalNoticePanel);

        commandPanel.add(commandHeaderStack, BorderLayout.NORTH);
        commandPanel.add(commandPane, BorderLayout.CENTER);
        commandPanel.setMinimumSize(new Dimension(200, 325));

        // Build Split: Output (Top) / Commands (Bottom)
        buildSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        buildSplitPane.setTopComponent(historyOutputPanel);
        buildSplitPane.setBottomComponent(commandPanel);
        buildSplitPane.setResizeWeight(DEFAULT_OUTPUT_MAIN_SPLIT);
        buildSplitPane.setMinimumSize(new Dimension(200, 325));
        setupSplitPanePersistence();

        // Preview Pane (lazy content but tab is always present)
        previewTabbedPane = new PreviewTabbedPane(
                chrome,
                chrome.getTheme(),
                title -> {}, // No window title to update here
                () -> {} // Don't close main UI tab when empty
                );

        // Review Tab Setup - show placeholder if no Git repo
        if (chrome.getProject().hasGit()) {
            var sessionChangesPanel = new SessionChangesPanel(chrome, contextManager, this::onReviewTabStateChanged);
            reviewTabComponent = sessionChangesPanel;
        } else {
            var placeholder = new JLabel("Git repository required for Review", SwingConstants.CENTER);
            placeholder.setEnabled(false);
            reviewTabComponent = placeholder;
        }

        // Build | Review | Preview | Terminal Tabs
        buildReviewTabs = new JTabbedPane(SwingConstants.TOP);
        buildReviewTabs.addTab("Build", Icons.HANDYMAN, buildSplitPane);
        buildReviewTabs.addTab("Review", Icons.FLOWSHEET, reviewTabComponent);

        buildReviewTabs.addTab("Preview", Icons.VISIBILITY, previewTabbedPane);
        buildReviewTabs.addTab("Terminal", Icons.TERMINAL, terminalPanel);

        if (!chrome.getPreviewManager().isPreviewDocked()) {
            int idx = buildReviewTabs.indexOfTab("Preview");
            if (idx != -1) {
                buildReviewTabs.removeTabAt(idx);
            }
        }

        // Set up tab change listeners (must be after buildReviewTabs is created)
        setupCommandPaneLogic();

        tabDragUndockHandler = new TabDragUndockHandler();
        tabDragUndockHandler.register();

        contextManager.addContextListener(new ContextListener() {
            @Override
            public void contextChanged(Context newCtx) {
                SwingUtilities.invokeLater(() -> {
                    Context live = contextManager.liveContext();
                    if (!newCtx.id().equals(live.id())) {
                        historicalNoticePanel.setMessage("Viewing historical Context + Tasks");
                    } else {
                        historicalNoticePanel.setMessage(null);
                    }
                });
            }
        });

        add(sessionHeaderPanel, BorderLayout.NORTH);
        add(buildReviewTabs, BorderLayout.CENTER);

        // Restore persistent docking states
        SwingUtilities.invokeLater(() -> {
            // Migration: Build is no longer undockable
            GlobalUiSettings.saveBuildDocked(true);

            if (!GlobalUiSettings.isReviewDocked()) {
                undockReview();
            }
            if (!GlobalUiSettings.isTerminalDocked()) {
                undockTerminal();
            }
        });
    }

    private JPanel createSessionHeader() {
        var header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.setOpaque(true);
        var titledBorder = BorderFactory.createTitledBorder("Session");
        var paddingBorder = BorderFactory.createEmptyBorder(0, 8, 0, 8);
        header.setBorder(BorderFactory.createCompoundBorder(titledBorder, paddingBorder));

        header.add(newSessionButton);
        header.add(new HistoryOutputPanel.VerticalDivider());
        header.add(sessionNameLabel);
        return header;
    }

    private JPopupMenu createSessionMenu() {
        var popup = new JPopupMenu();
        var model = new DefaultListModel<SessionManager.SessionInfo>();
        var sessions = contextManager.getProject().getSessionManager().listSessions();
        sessions.sort(
                Comparator.comparingLong(SessionManager.SessionInfo::modified).reversed());
        for (var s : sessions) model.addElement(s);

        // Pre-populate counts map with null; load actual counts async
        var taskCounts = new ConcurrentHashMap<UUID, HistoryIo.TaskCounts>();

        var list = new JList<SessionManager.SessionInfo>(model);
        list.setVisibleRowCount(Math.min(8, Math.max(3, model.getSize())));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Critical: set prototype and fixed height BEFORE setting renderer
        // This prevents Swing from rendering all cells just to compute sizes
        if (!model.isEmpty()) {
            list.setPrototypeCellValue(model.getElementAt(0));
        }
        list.setFixedCellHeight(36);

        list.setCellRenderer(new SessionInfoRenderer(taskCounts));

        // Load counts asynchronously and repaint as each completes
        // Use countSessionStats to get both AI response and task counts in a single pass
        var sessionManager = contextManager.getProject().getSessionManager();
        for (var s : sessions) {
            var sessionId = s.id();
            contextManager.submitMaintenanceTask("Loading session stats for " + s.name(), () -> {
                var stats = sessionManager.countSessionStats(sessionId);
                taskCounts.put(sessionId, stats.tasks());
                SwingUtilities.invokeLater(list::repaint);
            });
        }

        var currentSessionId = contextManager.getCurrentSessionId();
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i).id().equals(currentSessionId)) {
                list.setSelectedIndex(i);
                break;
            }
        }

        var listener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                SessionManager.SessionInfo sel = list.getSelectedValue();
                if (sel != null && !sel.id().equals(contextManager.getCurrentSessionId())) {
                    contextManager
                            .switchSessionAsync(sel.id())
                            .thenRun(() -> updateSessionComboBox())
                            .exceptionally(ex -> {
                                SwingUtilities.invokeLater(() -> updateSessionComboBox());
                                return null;
                            });
                }
                if (SwingUtilities.getAncestorOfClass(JPopupMenu.class, list) instanceof JPopupMenu p) {
                    p.setVisible(false);
                }
            }
        };
        list.addMouseListener(listener);

        var scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(360, 200));
        popup.add(scroll);
        return popup;
    }

    public void updateSessionComboBox() {
        SwingUtilities.invokeLater(() -> {
            var sessions = contextManager.getProject().getSessionManager().listSessions();
            sessions.sort(Comparator.comparingLong(SessionManager.SessionInfo::modified)
                    .reversed());

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

            sessionNameLabel.setText(labelText);
            sessionNameLabel.setToolTipText(labelText);
            sessionNameLabel.revalidate();
        });
    }

    private static class SessionInfoRenderer extends JPanel implements ListCellRenderer<SessionManager.SessionInfo> {
        private final JLabel nameLabel = new JLabel();
        private final JLabel timeLabel = new JLabel();
        private final JLabel countLabel = new JLabel();
        private final Map<UUID, HistoryIo.TaskCounts> taskCounts;

        SessionInfoRenderer(Map<UUID, HistoryIo.TaskCounts> taskCounts) {
            this.taskCounts = taskCounts;
            setLayout(new BorderLayout(0, 2));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

            var baseSize = nameLabel.getFont().getSize2D();
            var smallFont = nameLabel.getFont().deriveFont(Math.max(10f, baseSize - 2f));
            timeLabel.setFont(smallFont);
            countLabel.setFont(smallFont);

            // Simple two-row layout: name on top, time + count on bottom
            var bottomRow = new JPanel(new BorderLayout(Constants.H_GAP, 0));
            bottomRow.setOpaque(false);
            bottomRow.add(timeLabel, BorderLayout.WEST);
            bottomRow.add(countLabel, BorderLayout.EAST);

            add(nameLabel, BorderLayout.NORTH);
            add(bottomRow, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends SessionManager.SessionInfo> list,
                SessionManager.SessionInfo value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            // For combo box display (index == -1), return simple label
            if (index == -1) {
                var label = new JLabel(value.name());
                label.setOpaque(false);
                label.setEnabled(list.isEnabled());
                label.setForeground(list.getForeground());
                return label;
            }

            // Populate from in-memory data only - no blocking calls
            nameLabel.setText(value.name());
            var instant = Instant.ofEpochMilli(value.modified());
            timeLabel.setText(GitDiffUiUtil.formatRelativeDate(instant, LocalDate.now(ZoneId.systemDefault())));

            // Read pre-computed counts; null means still loading
            var counts = taskCounts.get(value.id());
            if (counts == null) {
                countLabel.setText("...");
            } else if (counts.total() == 0) {
                countLabel.setText("no tasks");
            } else if (counts.incomplete() == 0) {
                countLabel.setText(String.format("%d tasks done", counts.total()));
            } else {
                countLabel.setText(String.format("%d tasks (%d pending)", counts.total(), counts.incomplete()));
            }

            // Apply selection colors
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

    /**
     * Returns true if the given string looks like a Git commit hash (hex string of 7-40 chars).
     * Used to detect detached HEAD states.
     */
    public static boolean isLikelyCommitHash(String s) {
        if (s.length() < 7 || s.length() > 40) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private JPanel createBranchSelectorHeader() {
        var header = new JPanel(new BorderLayout(8, 0));
        var lineBorder = BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"));
        var titledBorder = BorderFactory.createTitledBorder(lineBorder, "Branch");
        header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4), titledBorder));

        var branchDim = new Dimension(210, 28);
        branchSelectorButton.setPreferredSize(branchDim);

        var leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftHeader.setOpaque(false);
        leftHeader.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        leftHeader.add(branchSelectorButton);
        header.add(leftHeader, BorderLayout.WEST);
        return header;
    }

    private void setupCommandPaneLogic() {
        var contextArea = instructionsPanel.getContextAreaContainer();
        commandPane.addChangeListener(e -> {
            var selected = commandPane.getSelectedComponent();
            if (selected == instructionsPanel) {
                taskListPanel.restoreControls();
                var center = instructionsPanel.getCenterPanel();
                center.add(contextArea, Math.min(1, center.getComponentCount()));
                instructionsPanel.restoreModeToggleToBottom();
                instructionsPanel.restoreModelSelectorToBottom();
            } else if (selected == taskListPanel) {
                taskListPanel.setSharedContextArea(contextArea);
                taskListPanel.setSharedModeToggle(instructionsPanel.getModeToggleComponent());
                taskListPanel.setSharedModelSelector(instructionsPanel.getModelSelectorComponent());
            }
        });

        buildReviewTabs.addChangeListener(e -> {
            if (buildReviewTabs.getSelectedComponent() == terminalPanel) {
                terminalPanel.requestFocusInTerminal();
            }
        });

        buildReviewTabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleTabPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleTabPopup(e);
            }

            private void handleTabPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int tabIndex = buildReviewTabs.indexAtLocation(e.getX(), e.getY());
                if (tabIndex == -1) return;

                Component comp = buildReviewTabs.getComponentAt(tabIndex);
                UndockTarget target = getUndockTarget(
                        comp,
                        reviewTabComponent,
                        previewTabbedPane,
                        terminalPanel,
                        buildSplitPane,
                        verticalActivityCombinedPanel);
                JPopupMenu popup = new JPopupMenu();

                switch (target) {
                    case REVIEW -> {
                        JMenuItem undockItem = new JMenuItem("Undock Review", Icons.FLOWSHEET);
                        undockItem.addActionListener(ae -> undockReview());
                        popup.add(undockItem);
                    }
                    case PREVIEW -> {
                        JMenuItem undockItem = new JMenuItem("Undock Preview", Icons.VISIBILITY);
                        undockItem.addActionListener(ae -> undockPreview());
                        popup.add(undockItem);
                    }
                    case TERMINAL -> {
                        JMenuItem undockItem = new JMenuItem("Undock Terminal", Icons.TERMINAL);
                        undockItem.addActionListener(ae -> undockTerminal());
                        popup.add(undockItem);
                    }
                    case NONE -> {}
                }

                if (popup.getComponentCount() > 0) {
                    popup.show(buildReviewTabs, e.getX(), e.getY());
                }
            }
        });
    }

    private void undockReview() {
        if (!GlobalUiSettings.isReviewDocked()) return;

        GlobalUiSettings.saveReviewDocked(false);
        int idx = buildReviewTabs.indexOfTab("Review");
        if (idx != -1) {
            buildReviewTabs.removeTabAt(idx);
        }

        reviewFrame = new DetachableTabFrame("Review", reviewTabComponent, this::redockReview, chrome.getTheme());
        if (reviewTabComponent instanceof SessionChangesPanel scp) {
            scp.requestUpdate();
        }
        reviewFrame.setVisible(true);
    }

    public void redockReview() {
        if (GlobalUiSettings.isReviewDocked()) return;

        GlobalUiSettings.saveReviewDocked(true);
        // Review is 2nd (after Build)
        int idx = Math.min(1, buildReviewTabs.getTabCount());
        buildReviewTabs.insertTab("Review", Icons.FLOWSHEET, reviewTabComponent, null, idx);
        buildReviewTabs.setSelectedIndex(idx);

        updateReviewTabBadge(
                contextManager.getProject().getRepo().getModifiedProjectFiles().size());
        if (reviewTabComponent instanceof SessionChangesPanel scp) {
            scp.requestUpdate();
        }

        if (reviewFrame != null) {
            reviewFrame.dispose();
            reviewFrame = null;
        }
    }

    private void undockPreview() {
        if (!chrome.getPreviewManager().isPreviewDocked()) return;

        chrome.getPreviewManager().setPreviewDocked(false);
        GlobalUiSettings.savePreviewDocked(false);

        int idx = buildReviewTabs.indexOfTab("Preview");
        if (idx != -1) {
            buildReviewTabs.removeTabAt(idx);
        }

        chrome.getPreviewManager().showPreviewInTabbedFrame("Preview", previewTabbedPane, null);
    }

    public void redockPreview() {
        if (chrome.getPreviewManager().isPreviewDocked()) return;

        chrome.getPreviewManager().setPreviewDocked(true);
        GlobalUiSettings.savePreviewDocked(true);

        // Re-add the Preview tab to BuildPane
        int terminalIdx = buildReviewTabs.indexOfTab("Terminal");
        if (terminalIdx != -1) {
            buildReviewTabs.insertTab("Preview", Icons.VISIBILITY, previewTabbedPane, null, terminalIdx);
        } else {
            buildReviewTabs.addTab("Preview", Icons.VISIBILITY, previewTabbedPane);
        }

        selectPreviewTab();
    }

    private void undockTerminal() {
        if (!GlobalUiSettings.isTerminalDocked()) return;

        GlobalUiSettings.saveTerminalDocked(false);
        int idx = buildReviewTabs.indexOfTab("Terminal");
        if (idx != -1) {
            buildReviewTabs.removeTabAt(idx);
        }

        terminalFrame = new DetachableTabFrame("Terminal", terminalPanel, this::redockTerminal, chrome.getTheme());
        terminalFrame.setVisible(true);
    }

    public void redockTerminal() {
        if (GlobalUiSettings.isTerminalDocked()) return;

        GlobalUiSettings.saveTerminalDocked(true);
        buildReviewTabs.addTab("Terminal", Icons.TERMINAL, terminalPanel);
        selectTerminalTab();

        if (terminalFrame != null) {
            terminalFrame.dispose();
            terminalFrame = null;
        }
    }

    public void setPreviewDocked(boolean docked) {
        if (docked) {
            int idx = buildReviewTabs.indexOfTab("Preview");
            if (idx == -1) {
                // Insert before Terminal if possible
                int terminalIdx = buildReviewTabs.indexOfTab("Terminal");
                if (terminalIdx != -1) {
                    buildReviewTabs.insertTab("Preview", Icons.VISIBILITY, previewTabbedPane, null, terminalIdx);
                } else {
                    buildReviewTabs.addTab("Preview", Icons.VISIBILITY, previewTabbedPane);
                }
            }
        } else {
            int idx = buildReviewTabs.indexOfTab("Preview");
            if (idx != -1) {
                buildReviewTabs.removeTabAt(idx);
            }
        }
    }

    private void setupSplitPanePersistence() {
        buildSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (!buildSplitPane.isShowing()) {
                return;
            }
            int newPos = buildSplitPane.getDividerLocation();
            if (newPos > 0) {
                chrome.getProject().saveRightVerticalSplitPosition(newPos);
                GlobalUiSettings.saveRightVerticalSplitPosition(newPos);
            }
        });
    }

    public void applyAdvancedModeVisibility() {
        boolean advanced = GlobalUiSettings.isAdvancedMode();
        branchSelectorPanel.setVisible(chrome.getProject().hasGit());
        historyOutputPanel.setAdvancedMode(advanced);
        instructionsPanel.applyAdvancedMode(advanced);

        int terminalIdx = buildReviewTabs.indexOfTab("Terminal");
        if (!advanced) {
            if (terminalIdx != -1) {
                buildReviewTabs.removeTabAt(terminalIdx);
            }
            if (terminalFrame != null) {
                terminalFrame.setVisible(false);
            }
        } else {
            // If advanced and docked, ensure it is in the tabs
            if (GlobalUiSettings.isTerminalDocked() && terminalIdx == -1) {
                buildReviewTabs.addTab("Terminal", Icons.TERMINAL, terminalPanel);
            } else if (!GlobalUiSettings.isTerminalDocked() && terminalFrame != null) {
                // If advanced and undocked, ensure frame is visible
                terminalFrame.setVisible(true);
            }
        }
    }

    public void applyVerticalActivityLayout() {
        boolean enabled = GlobalUiSettings.isVerticalActivityLayout();
        var activityTabs = historyOutputPanel.getActivityTabs();

        if (enabled) {
            if (verticalActivityCombinedPanel == null) {
                var leftTopPanel = new JPanel(new BorderLayout());
                leftTopPanel.add(activityTabs, BorderLayout.CENTER);

                // Vertical activity layout replaces the original buildSplitPane with a different split structure.
                // It introduces two independent JSplitPanes (verticalLayoutLeftSplit + verticalActivityCombinedPanel)
                // whose divider locations must be persisted separately or they reset to defaults on restart.
                var leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, leftTopPanel, commandPanel);
                leftSplit.setResizeWeight(0.4);
                verticalLayoutLeftSplit = leftSplit;

                leftSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                    if (!leftSplit.isShowing()) return;
                    int newPos = leftSplit.getDividerLocation();
                    if (newPos > 0) {
                        GlobalUiSettings.saveVerticalLayoutLeftSplitPosition(newPos);
                    }
                });

                verticalActivityCombinedPanel = new JSplitPane(
                        JSplitPane.HORIZONTAL_SPLIT, leftSplit, historyOutputPanel.getLlmOutputContainer());
                verticalActivityCombinedPanel.setResizeWeight(0.5);

                var combinedPanel = verticalActivityCombinedPanel;
                combinedPanel.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                    if (!combinedPanel.isShowing()) return;
                    int newPos = combinedPanel.getDividerLocation();
                    if (newPos > 0) {
                        GlobalUiSettings.saveVerticalLayoutHorizontalSplitPosition(newPos);
                    }
                });

                historyOutputPanel.applyFixedCaptureBarSizing(true);
            }

            int buildIdx = buildReviewTabs.indexOfTab("Build");
            if (buildIdx != -1) {
                buildReviewTabs.setComponentAt(buildIdx, verticalActivityCombinedPanel);
            }

            // Restore divider locations for vertical layout splits
            int savedLeftSplit = GlobalUiSettings.getVerticalLayoutLeftSplitPosition();
            int savedHorizontalSplit = GlobalUiSettings.getVerticalLayoutHorizontalSplitPosition();
            if (savedLeftSplit > 0 && verticalLayoutLeftSplit != null) {
                verticalLayoutLeftSplit.setDividerLocation(savedLeftSplit);
            }
            if (savedHorizontalSplit > 0 && verticalActivityCombinedPanel != null) {
                verticalActivityCombinedPanel.setDividerLocation(savedHorizontalSplit);
            }
        } else {
            historyOutputPanel.applyFixedCaptureBarSizing(false);

            // Restore original buildSplitPane structure
            var activityTabsContainer = historyOutputPanel.getActivityTabsContainer();
            activityTabsContainer.removeAll();
            activityTabsContainer.add(historyOutputPanel.getLlmOutputContainer(), BorderLayout.CENTER);
            activityTabsContainer.add(historyOutputPanel.getActivityTabs(), BorderLayout.EAST);

            buildSplitPane.setTopComponent(historyOutputPanel);
            buildSplitPane.setBottomComponent(commandPanel);

            int buildIdx = buildReviewTabs.indexOfTab("Build");
            if (buildIdx != -1) {
                buildReviewTabs.setComponentAt(buildIdx, buildSplitPane);
            }
            verticalActivityCombinedPanel = null;
            verticalLayoutLeftSplit = null;

            // Restore buildSplitPane divider when switching back to standard layout
            restoreBuildSplitPaneDivider();
        }
        revalidate();
        repaint();
    }

    public void setBranchLabel(@Nullable String branch) {
        branchSelectorButton.refreshBranch(branch == null ? "" : branch);
    }

    /**
     * Restores the right panel's divider locations based on the current layout mode.
     * For vertical activity layout, restores verticalLayoutLeftSplit and verticalActivityCombinedPanel.
     * For standard layout, restores buildSplitPane.
     */
    public void restoreDividerLocation() {
        if (GlobalUiSettings.isVerticalActivityLayout() && verticalLayoutLeftSplit != null) {
            // Vertical layout mode - restore those split panes
            int savedLeftSplit = GlobalUiSettings.getVerticalLayoutLeftSplitPosition();
            int savedHorizontalSplit = GlobalUiSettings.getVerticalLayoutHorizontalSplitPosition();
            if (savedLeftSplit > 0) {
                verticalLayoutLeftSplit.setDividerLocation(savedLeftSplit);
            }
            if (savedHorizontalSplit > 0 && verticalActivityCombinedPanel != null) {
                verticalActivityCombinedPanel.setDividerLocation(savedHorizontalSplit);
            }
        } else {
            // Standard layout mode - restore buildSplitPane
            restoreBuildSplitPaneDivider();
        }
    }

    private void restoreBuildSplitPaneDivider() {
        int buildSplitPos = chrome.getProject().getRightVerticalSplitPosition();

        if (buildSplitPos <= 0) {
            buildSplitPos = GlobalUiSettings.getRightVerticalSplitPosition();
        }

        if (buildSplitPos > 0) {
            buildSplitPane.setDividerLocation(buildSplitPos);
        } else {
            int defaultPos = (int) (buildSplitPane.getHeight() * DEFAULT_OUTPUT_MAIN_SPLIT);
            buildSplitPane.setDividerLocation(defaultPos);
        }
    }

    public HistoryOutputPanel getHistoryOutputPanel() {
        return historyOutputPanel;
    }

    public InstructionsPanel getInstructionsPanel() {
        return instructionsPanel;
    }

    public TaskListPanel getTaskListPanel() {
        return taskListPanel;
    }

    private void onReviewTabStateChanged(SessionChangesPanel.ReviewTabState state) {
        SwingUtilities.invokeLater(() -> {
            if (!GlobalUiSettings.isReviewDocked()) {
                if (reviewFrame != null) {
                    reviewFrame.setHeaderTitle(
                            Icons.FLOWSHEET, state.title(), state.tooltip(), state.uncommittedCount());
                }
                return;
            }

            int idx = getReviewTabIndex();
            if (idx != -1) {
                buildReviewTabs.setTitleAt(idx, state.title());
                buildReviewTabs.setToolTipTextAt(idx, state.tooltip());
            }
            updateReviewTabBadge(state.uncommittedCount());
        });
    }

    public void requestReviewUpdate() {
        if (reviewTabComponent instanceof SessionChangesPanel scp) {
            scp.requestUpdate();
        }
    }

    /**
     * Loads a review from markdown text into the SessionChangesPanel.
     * Selects the Review tab and displays the parsed review.
     *
     * @param markdown The markdown text containing the review
     * @param context The context associated with this review
     */
    public void loadReviewFromMarkdown(String markdown, Context context) {
        // Focus the Review tab/frame
        focusReviewTab();

        // Load the review into SessionChangesPanel
        if (reviewTabComponent instanceof SessionChangesPanel scp) {
            scp.loadExternalReviewAsync(markdown, context);
        }
    }

    /**
     * Compatibility overload for reviewing from a commit up to HEAD.
     */
    public void startCommitRangeReview(String oldestCommitId) {
        startCommitRangeReview(oldestCommitId + "^", "HEAD");
    }

    /**
     * Starts a review for a specific range of commits.
     * Selects the Review tab and triggers the review generation in SessionChangesPanel.
     */
    public void startCommitRangeReview(String fromRef, String toRef) {
        SwingUtilities.invokeLater(() -> {
            focusReviewTab();

            if (reviewTabComponent instanceof SessionChangesPanel scp) {
                scp.startCommitRangeReview(fromRef, toRef);
            }
        });
    }

    private void focusReviewTab() {
        if (!GlobalUiSettings.isReviewDocked() && reviewFrame != null) {
            // Review is undocked - bring the frame to front
            reviewFrame.toFront();
            reviewFrame.requestFocus();
        } else {
            // Review is docked - select the tab by component reference (title may be modified)
            int reviewIdx = getReviewTabIndex();
            if (reviewIdx != -1) {
                buildReviewTabs.setSelectedIndex(reviewIdx);
            }
        }
    }

    /**
     * Updates the "Build" tab icon with a numeric badge showing the incomplete task count.
     * @param count The number of incomplete tasks.
     */
    public void updateBuildTabBadge(int count) {
        SwingUtilities.invokeLater(() -> {
            int idx = buildReviewTabs.indexOfTab("Build");
            if (idx == -1) return;

            if (count <= 0) {
                buildReviewTabs.setIconAt(idx, Icons.HANDYMAN);
                buildTabBadgedIcon = null;
            } else {
                if (buildTabBadgedIcon == null) {
                    buildTabBadgedIcon = new BadgedIcon(Icons.HANDYMAN, chrome.getTheme());
                }
                buildTabBadgedIcon.setCount(count, buildReviewTabs);
                buildReviewTabs.setIconAt(idx, buildTabBadgedIcon);
            }
        });
    }

    /**
     * Updates the "Review" tab icon with a numeric badge showing the uncommitted file count.
     * @param count The number of uncommitted files.
     */
    public void updateReviewTabBadge(int count) {
        SwingUtilities.invokeLater(() -> {
            int idx = getReviewTabIndex();
            if (idx == -1) {
                return;
            }

            if (count <= 0) {
                buildReviewTabs.setIconAt(idx, Icons.FLOWSHEET);
                reviewTabBadgedIcon = null;
            } else {
                if (reviewTabBadgedIcon == null) {
                    reviewTabBadgedIcon = new BadgedIcon(Icons.FLOWSHEET, chrome.getTheme());
                }
                reviewTabBadgedIcon.setCount(count, buildReviewTabs);
                buildReviewTabs.setIconAt(idx, reviewTabBadgedIcon);
            }
        });
    }

    public void selectBuildTab() {
        assert SwingUtilities.isEventDispatchThread();
        int idx = buildReviewTabs.indexOfTab("Build");
        if (idx != -1) {
            buildReviewTabs.setSelectedIndex(idx);
        }
    }

    public void selectReviewTab() {
        assert SwingUtilities.isEventDispatchThread();
        int idx = buildReviewTabs.indexOfTab("Review");
        if (idx != -1) {
            buildReviewTabs.setSelectedIndex(idx);
        }
    }

    public void selectPreviewTab() {
        assert SwingUtilities.isEventDispatchThread();
        int idx = buildReviewTabs.indexOfTab("Preview");
        if (idx != -1) {
            buildReviewTabs.setSelectedIndex(idx);
        }
    }

    public void cycleBuildReviewPreview(boolean forward) {
        assert SwingUtilities.isEventDispatchThread();
        List<Integer> indices = new ArrayList<>();
        int buildIdx = buildReviewTabs.indexOfTab("Build");
        if (buildIdx != -1) {
            indices.add(buildIdx);
        }
        // Use component-based lookup for Review since its title changes dynamically
        int reviewIdx = getReviewTabIndex();
        if (reviewIdx != -1) {
            indices.add(reviewIdx);
        }
        int previewIdx = buildReviewTabs.indexOfTab("Preview");
        if (previewIdx != -1) {
            indices.add(previewIdx);
        }
        if (indices.isEmpty()) {
            return;
        }
        int current = buildReviewTabs.getSelectedIndex();
        int pos = indices.indexOf(current);
        if (pos == -1) {
            buildReviewTabs.setSelectedIndex(indices.getFirst());
            return;
        }
        int nextPos = forward ? (pos + 1) % indices.size() : (pos - 1 + indices.size()) % indices.size();
        buildReviewTabs.setSelectedIndex(indices.get(nextPos));
    }

    public void selectTerminalTab() {
        assert SwingUtilities.isEventDispatchThread();
        int idx = buildReviewTabs.indexOfTab("Terminal");
        if (idx != -1) {
            buildReviewTabs.setSelectedIndex(idx);
        }
    }

    public void selectTasksTab() {
        assert SwingUtilities.isEventDispatchThread();
        int buildIdx = buildReviewTabs.indexOfTab("Build");
        if (buildIdx != -1) {
            buildReviewTabs.setSelectedIndex(buildIdx);
        }
        int taskIdx = commandPane.indexOfTab("Tasks");
        if (taskIdx != -1) {
            commandPane.setSelectedIndex(taskIdx);
        }
        taskListPanel.getTaskInput().requestFocusInWindow();
    }

    public void selectInstructionsTab() {
        assert SwingUtilities.isEventDispatchThread();
        int buildIdx = buildReviewTabs.indexOfTab("Build");
        if (buildIdx != -1) {
            buildReviewTabs.setSelectedIndex(buildIdx);
        }
        int instructionsIdx = commandPane.indexOfTab("Instructions");
        if (instructionsIdx != -1) {
            commandPane.setSelectedIndex(instructionsIdx);
        }
        instructionsPanel.requestCommandInputFocus();
    }

    public void toggleInstructionsTasksTab() {
        SwingUtilities.invokeLater(() -> {
            var selected = commandPane.getSelectedComponent();
            if (selected == instructionsPanel) {
                selectTasksTab();
            } else {
                selectInstructionsTab();
            }
        });
    }

    public PreviewTabbedPane getPreviewTabbedPane() {
        return previewTabbedPane;
    }

    public JTabbedPane getCommandPane() {
        return commandPane;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        tabDragUndockHandler.register();
    }

    @Override
    public void removeNotify() {
        tabDragUndockHandler.unregister();
        super.removeNotify();
    }

    private class TabDragUndockHandler implements AWTEventListener {
        private static final int DRAG_THRESHOLD = 32;
        private @Nullable Point pressPoint;
        private int dragTabIndex = -1;
        private boolean undocked;
        private boolean registered = false;

        /**
         * Registers the AWT event listener to intercept mouse events globally within buildReviewTabs.
         * Pairs with unregister() in removeNotify to handle panel re-parenting.
         */
        public void register() {
            if (registered) return;
            Toolkit.getDefaultToolkit()
                    .addAWTEventListener(this, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
            registered = true;
        }

        public void unregister() {
            if (!registered) return;
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
            registered = false;
        }

        @Override
        public void eventDispatched(AWTEvent event) {
            if (!(event instanceof MouseEvent me)) return;

            Component source = me.getComponent();
            if (source == null) return;

            // Only handle events originating from buildReviewTabs or its descendants
            if (source != buildReviewTabs && !SwingUtilities.isDescendingFrom(source, buildReviewTabs)) {
                return;
            }

            switch (me.getID()) {
                case MouseEvent.MOUSE_PRESSED -> handleMousePressed(me);
                case MouseEvent.MOUSE_DRAGGED -> handleMouseDragged(me);
                case MouseEvent.MOUSE_RELEASED -> handleMouseReleased(me);
            }
        }

        private void handleMousePressed(MouseEvent e) {
            undocked = false;
            Point pInTabs = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), buildReviewTabs);

            // Check if press is on a tab header
            int headerIdx = buildReviewTabs.indexAtLocation(pInTabs.x, pInTabs.y);

            // Only allow drag to start if the press is actually on a tab header
            if (headerIdx == -1) {
                dragTabIndex = -1;
                pressPoint = null;
                return;
            }

            dragTabIndex = headerIdx;
            pressPoint = pInTabs;

            Component comp = buildReviewTabs.getComponentAt(dragTabIndex);
            UndockTarget target = getUndockTarget(
                    comp,
                    reviewTabComponent,
                    previewTabbedPane,
                    terminalPanel,
                    buildSplitPane,
                    verticalActivityCombinedPanel);

            if (target != UndockTarget.NONE) {
                buildReviewTabs.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
        }

        private void handleMouseDragged(MouseEvent e) {
            if (dragTabIndex == -1 || pressPoint == null || undocked) return;

            Point currentPointInTabs = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), buildReviewTabs);
            double dist = currentPointInTabs.distance(pressPoint);

            if (dist > DRAG_THRESHOLD) {
                Rectangle headerRect = null;
                try {
                    headerRect = buildReviewTabs.getBoundsAt(dragTabIndex);
                } catch (IndexOutOfBoundsException ex) {
                    headerRect = null;
                }

                boolean outsideTriggerArea;
                if (headerRect != null) {
                    outsideTriggerArea = !headerRect.contains(currentPointInTabs);
                } else {
                    // Fallback: previous behavior using full tabbed pane bounds
                    Rectangle fullBounds = new Rectangle(0, 0, buildReviewTabs.getWidth(), buildReviewTabs.getHeight());
                    outsideTriggerArea = !fullBounds.contains(currentPointInTabs);
                }

                if (outsideTriggerArea) {
                    buildReviewTabs.setCursor(Cursor.getDefaultCursor());
                    triggerUndock(dragTabIndex);
                    undocked = true;
                }
            }
        }

        @SuppressWarnings("unused")
        private void handleMouseReleased(MouseEvent e) {
            if (buildReviewTabs.getCursor().getType() != Cursor.DEFAULT_CURSOR) {
                buildReviewTabs.setCursor(Cursor.getDefaultCursor());
            }
            dragTabIndex = -1;
            pressPoint = null;
            undocked = false;
        }

        private void triggerUndock(int index) {
            if (index < 0 || index >= buildReviewTabs.getTabCount()) return;

            Component comp = buildReviewTabs.getComponentAt(index);
            UndockTarget target = getUndockTarget(
                    comp,
                    reviewTabComponent,
                    previewTabbedPane,
                    terminalPanel,
                    buildSplitPane,
                    verticalActivityCombinedPanel);

            switch (target) {
                case REVIEW -> undockReview();
                case PREVIEW -> undockPreview();
                case TERMINAL -> undockTerminal();
                case NONE -> {}
            }
        }
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        historyOutputPanel.applyTheme(guiTheme);
        if (reviewTabComponent instanceof ThemeAware scp) {
            scp.applyTheme(guiTheme);
        }
        SwingUtilities.updateComponentTreeUI(this);
    }
}
