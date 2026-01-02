package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.gui.components.PreviewTabbedPane;
import ai.brokk.gui.terminal.TaskListPanel;
import ai.brokk.gui.terminal.TerminalPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.util.UUID;
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
    private final TerminalPanel terminalPanel;

    private final JPanel sessionHeaderPanel;
    private final ai.brokk.gui.components.SplitButton sessionNameLabel;
    private final ai.brokk.gui.components.MaterialButton newSessionButton;

    private final JSplitPane buildSplitPane;
    private final JTabbedPane buildReviewTabs;
    private PreviewTabbedPane previewTabbedPane;
    private final JTabbedPane commandPane;
    private final JPanel commandPanel;
    private final JPanel branchSelectorPanel;
    private final BranchSelectorButton branchSelectorButton;

    private @Nullable JSplitPane verticalActivityCombinedPanel = null;
    private @Nullable JSplitPane verticalLayoutLeftSplit = null;

    // Review tab infrastructure
    private final JComponent reviewTabComponent;

    private int getReviewTabIndex() {
        return buildReviewTabs.indexOfComponent(reviewTabComponent);
    }

    private final ContextManager contextManager;

    public RightPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        historyOutputPanel = new HistoryOutputPanel(chrome, contextManager);

        // Session header components
        newSessionButton = new ai.brokk.gui.components.MaterialButton();
        newSessionButton.setToolTipText("Create a new session");
        newSessionButton.addActionListener(e -> {
            contextManager
                    .createSessionAsync(ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> contextManager.getProject().getMainProject().sessionsListChanged());
        });
        SwingUtilities.invokeLater(() -> newSessionButton.setIcon(ai.brokk.gui.util.Icons.ADD));

        sessionNameLabel = new ai.brokk.gui.components.SplitButton("");
        sessionNameLabel.setUnifiedHover(true);
        sessionNameLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        sessionNameLabel.setMenuSupplier(() -> createSessionMenu());
        sessionNameLabel.addActionListener(e -> sessionNameLabel.showPopupMenuInternal());

        sessionHeaderPanel = createSessionHeader();
        updateSessionComboBox();

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
        commandPanel.add(branchSelectorPanel, BorderLayout.NORTH);
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
            var sessionChangesPanel =
                    new SessionChangesPanel(chrome, contextManager, this::updateReviewTabTitleAndTooltip);
            reviewTabComponent = sessionChangesPanel;
        } else {
            var placeholder = new JLabel("Git repository required for Review", SwingConstants.CENTER);
            placeholder.setEnabled(false);
            reviewTabComponent = placeholder;
        }

        // Build | Review | Preview | Terminal Tabs
        buildReviewTabs = new JTabbedPane(JTabbedPane.TOP);
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

        add(sessionHeaderPanel, BorderLayout.NORTH);
        add(buildReviewTabs, BorderLayout.CENTER);
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
        var model = new DefaultListModel<ai.brokk.SessionManager.SessionInfo>();
        var sessions = contextManager.getProject().getSessionManager().listSessions();
        sessions.sort(java.util.Comparator.comparingLong(ai.brokk.SessionManager.SessionInfo::modified)
                .reversed());
        for (var s : sessions) model.addElement(s);

        // Pre-populate counts map with placeholder; load actual counts async
        var taskCounts = new java.util.concurrent.ConcurrentHashMap<UUID, Integer>();
        for (var s : sessions) {
            taskCounts.put(s.id(), -1); // -1 means "loading"
        }

        var list = new JList<ai.brokk.SessionManager.SessionInfo>(model);
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
        var sessionManager = contextManager.getProject().getSessionManager();
        for (var s : sessions) {
            var sessionId = s.id();
            contextManager.getBackgroundTasks().submit(() -> {
                int count = sessionManager.countAiResponses(sessionId);
                taskCounts.put(sessionId, count);
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

        var listener = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                var sel = list.getSelectedValue();
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
        scroll.setPreferredSize(new java.awt.Dimension(360, 200));
        popup.add(scroll);
        chrome.getThemeManager().registerPopupMenu(popup);
        return popup;
    }

    public void updateSessionComboBox() {
        SwingUtilities.invokeLater(() -> {
            var sessions = contextManager.getProject().getSessionManager().listSessions();
            sessions.sort(java.util.Comparator.comparingLong(ai.brokk.SessionManager.SessionInfo::modified)
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

    private static class SessionInfoRenderer extends JPanel
            implements ListCellRenderer<ai.brokk.SessionManager.SessionInfo> {
        private final JLabel nameLabel = new JLabel();
        private final JLabel timeLabel = new JLabel();
        private final JLabel countLabel = new JLabel();
        private final java.util.Map<UUID, Integer> taskCounts;

        SessionInfoRenderer(java.util.Map<UUID, Integer> taskCounts) {
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
                JList<? extends ai.brokk.SessionManager.SessionInfo> list,
                ai.brokk.SessionManager.SessionInfo value,
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
            var instant = java.time.Instant.ofEpochMilli(value.modified());
            timeLabel.setText(ai.brokk.gui.util.GitDiffUiUtil.formatRelativeDate(
                    instant, java.time.LocalDate.now(java.time.ZoneId.systemDefault())));

            // Read pre-computed count; -1 means still loading
            int cnt = taskCounts.getOrDefault(value.id(), -1);
            countLabel.setText(cnt < 0 ? "..." : String.format("%d %s", cnt, cnt == 1 ? "task" : "tasks"));

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
        var header = new JPanel(new BorderLayout(Constants.H_GAP, 0));
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
                instructionsPanel.restoreModelSelectorToBottom();
            } else if (selected == taskListPanel) {
                taskListPanel.setSharedContextArea(contextArea);
                taskListPanel.setSharedModelSelector(instructionsPanel.getModelSelectorComponent());
            }
        });

        buildReviewTabs.addChangeListener(e -> {
            if (buildReviewTabs.getSelectedComponent() == terminalPanel) {
                terminalPanel.requestFocusInTerminal();
            }
        });

        buildReviewTabs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                handleTabPopup(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                handleTabPopup(e);
            }

            private void handleTabPopup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int tabIndex = buildReviewTabs.indexAtLocation(e.getX(), e.getY());
                if (tabIndex == -1) return;

                String title = buildReviewTabs.getTitleAt(tabIndex);
                if ("Preview".equals(title)) {
                    JPopupMenu popup = new JPopupMenu();
                    JMenuItem undockItem = new JMenuItem("Undock Preview", Icons.VISIBILITY);
                    undockItem.addActionListener(ae -> undockPreview());
                    popup.add(undockItem);
                    popup.show(buildReviewTabs, e.getX(), e.getY());
                }
            }
        });
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
        // previewTabbedPane is now owned by PreviewFrame - don't create a replacement
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

        int terminalIdx = buildReviewTabs.indexOfTab("Terminal");
        if (!advanced && terminalIdx != -1) {
            buildReviewTabs.removeTabAt(terminalIdx);
        } else if (advanced && terminalIdx == -1) {
            buildReviewTabs.addTab("Terminal", Icons.TERMINAL, terminalPanel);
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

            // Restore divider locations from GlobalUiSettings so the user's preferred vertical layout survives
            // restarts. Deferred via invokeLater because setDividerLocation() can be ignored if the split pane
            // hasn't been laid out yet (size is 0) after the component swap above.
            SwingUtilities.invokeLater(() -> {
                int savedLeftSplit = GlobalUiSettings.getVerticalLayoutLeftSplitPosition();
                int savedHorizontalSplit = GlobalUiSettings.getVerticalLayoutHorizontalSplitPosition();
                if (savedLeftSplit > 0 && verticalLayoutLeftSplit != null) {
                    verticalLayoutLeftSplit.setDividerLocation(savedLeftSplit);
                }
                if (savedHorizontalSplit > 0 && verticalActivityCombinedPanel != null) {
                    verticalActivityCombinedPanel.setDividerLocation(savedHorizontalSplit);
                }
            });
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
        }
        revalidate();
        repaint();
    }

    public void setBranchLabel(@Nullable String branch) {
        branchSelectorButton.refreshBranch(branch == null ? "" : branch);
    }

    public void restoreDividerLocation() {
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

    private void updateReviewTabTitleAndTooltip(String title, String tooltip) {
        int idx = getReviewTabIndex();
        if (idx != -1) {
            buildReviewTabs.setTitleAt(idx, title);
            buildReviewTabs.setToolTipTextAt(idx, tooltip);
        }
    }

    public void requestReviewUpdate() {
        if (reviewTabComponent instanceof SessionChangesPanel scp) {
            scp.refreshTitleAsync();
            scp.requestUpdate();
        }
    }

    public void selectPreviewTab() {
        int idx = buildReviewTabs.indexOfTab("Preview");
        if (idx != -1) {
            buildReviewTabs.setSelectedIndex(idx);
        }
    }

    public void selectTerminalTab() {
        int idx = buildReviewTabs.indexOfTab("Terminal");
        if (idx != -1) {
            buildReviewTabs.setSelectedIndex(idx);
        }
    }

    public void selectTasksTab() {
        int buildIdx = buildReviewTabs.indexOfTab("Build");
        if (buildIdx != -1) {
            buildReviewTabs.setSelectedIndex(buildIdx);
        }
        int taskIdx = commandPane.indexOfTab("Tasks");
        if (taskIdx != -1) {
            commandPane.setSelectedIndex(taskIdx);
        }
    }

    public PreviewTabbedPane getPreviewTabbedPane() {
        return previewTabbedPane;
    }

    public JTabbedPane getCommandPane() {
        return commandPane;
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
