package ai.brokk.gui;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.gui.components.PreviewTabbedPane;
import ai.brokk.gui.terminal.TaskListPanel;
import ai.brokk.gui.terminal.TerminalPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import javax.swing.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates the right-side UI stack: Output (History), Instructions, Tasks, and Terminal.
 * Manages the "Build" vs "Review" tabs and the vertical layout transformations.
 */
public class RightPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(RightPanel.class);
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

    // Review tab infrastructure
    private final SessionChangesPanel sessionChangesPanel;

    private int getReviewTabIndex() {
        return buildReviewTabs.indexOfComponent(sessionChangesPanel);
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

        // Review Tab Setup
        sessionChangesPanel = new SessionChangesPanel(chrome, contextManager, this::updateReviewTabTitleAndTooltip);

        // Build | Review | Preview | Terminal Tabs
        buildReviewTabs = new JTabbedPane(JTabbedPane.TOP);
        buildReviewTabs.addTab("Build", Icons.SCIENCE, buildSplitPane);
        buildReviewTabs.addTab("Review", Icons.FLOWSHEET, sessionChangesPanel);

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
        sessions.sort(java.util.Comparator.comparingLong(ai.brokk.SessionManager.SessionInfo::modified).reversed());
        for (var s : sessions) model.addElement(s);

        var list = new JList<ai.brokk.SessionManager.SessionInfo>(model);
        list.setVisibleRowCount(Math.min(8, Math.max(3, model.getSize())));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(historyOutputPanel.new SessionInfoRenderer());

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
                    contextManager.switchSessionAsync(sel.id())
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
        historyOutputPanel.updateSessionComboBox(sessionNameLabel);
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
            if (!buildSplitPane.isShowing()) return;
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

                var leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, leftTopPanel, commandPanel);
                leftSplit.setResizeWeight(0.4);

                verticalActivityCombinedPanel = new JSplitPane(
                        JSplitPane.HORIZONTAL_SPLIT, leftSplit, historyOutputPanel.getLlmOutputContainer());
                verticalActivityCombinedPanel.setResizeWeight(0.5);
                historyOutputPanel.applyFixedCaptureBarSizing(true);
            }

            int buildIdx = buildReviewTabs.indexOfTab("Build");
            if (buildIdx != -1) {
                buildReviewTabs.setComponentAt(buildIdx, verticalActivityCombinedPanel);
            }
        } else {
            historyOutputPanel.applyFixedCaptureBarSizing(false);

            // Restore original buildSplitPane structure
            var activityTabsContainer = historyOutputPanel.getActivityTabsContainer();
            activityTabsContainer.removeAll();
            activityTabsContainer.add(historyOutputPanel.getLlmOutputContainer(), BorderLayout.CENTER);

            buildSplitPane.setTopComponent(historyOutputPanel);
            buildSplitPane.setBottomComponent(commandPanel);

            int buildIdx = buildReviewTabs.indexOfTab("Build");
            if (buildIdx != -1) {
                buildReviewTabs.setComponentAt(buildIdx, buildSplitPane);
            }
            verticalActivityCombinedPanel = null;
        }
        revalidate();
        repaint();
    }

    public void setBranchLabel(@Nullable String branch) {
        branchSelectorButton.refreshBranch(branch == null ? "" : branch);
    }

    public void restoreDividerLocation() {
        int buildSplitPos = GlobalUiSettings.getRightVerticalSplitPosition();
        if (buildSplitPos > 0) {
            buildSplitPane.setDividerLocation(buildSplitPos);
        } else {
            buildSplitPane.setDividerLocation((int) (buildSplitPane.getHeight() * DEFAULT_OUTPUT_MAIN_SPLIT));
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
        sessionChangesPanel.refreshTitleOnly();
        sessionChangesPanel.requestUpdate();
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
        sessionChangesPanel.applyTheme(guiTheme);
        SwingUtilities.updateComponentTreeUI(this);
    }
}
