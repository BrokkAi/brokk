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
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates the right-side UI stack: Output (History), Instructions, Tasks, and Terminal.
 * Manages the "Build" vs "Review" tabs and the vertical layout transformations.
 */
public class BuildPane extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(BuildPane.class);
    private static final double DEFAULT_OUTPUT_MAIN_SPLIT = 0.4;

    private final Chrome chrome;
    private final HistoryOutputPanel historyOutputPanel;
    private final InstructionsPanel instructionsPanel;
    private final TaskListPanel taskListPanel;
    private final TerminalPanel terminalPanel;

    private final JSplitPane buildSplitPane;
    private final JTabbedPane buildReviewTabs;
    private final PreviewTabbedPane previewTabbedPane;
    private boolean isPreviewDocked;
    private final JTabbedPane commandPane;
    private final JPanel commandPanel;
    private final JPanel branchSelectorPanel;
    private final BranchSelectorButton branchSelectorButton;

    private @Nullable JSplitPane verticalActivityCombinedPanel = null;

    public BuildPane(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;

        historyOutputPanel = new HistoryOutputPanel(chrome, contextManager);
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
        previewTabbedPane = new PreviewTabbedPane(chrome, chrome.getTheme(),
                title -> {}, // No window title to update here
                () -> {} // Don't close main UI tab when empty
        );

        // Build | Review | Preview | Terminal Tabs
        buildReviewTabs = new JTabbedPane(JTabbedPane.TOP);
        buildReviewTabs.addTab("Build", Icons.SCIENCE, buildSplitPane);

        var reviewPlaceholder = historyOutputPanel.getChangesTabPlaceholder();
        if (reviewPlaceholder != null) {
            buildReviewTabs.addTab("Review", Icons.FLOWSHEET, reviewPlaceholder);
        }

        isPreviewDocked = GlobalUiSettings.isPreviewDocked();
        buildReviewTabs.addTab("Preview", Icons.VISIBILITY, previewTabbedPane);
        buildReviewTabs.addTab("Terminal", Icons.TERMINAL, terminalPanel);

        if (!isPreviewDocked) {
            int idx = buildReviewTabs.indexOfTab("Preview");
            if (idx != -1) {
                buildReviewTabs.removeTabAt(idx);
            }
        }

        // Set up tab change listeners (must be after buildReviewTabs is created)
        setupCommandPaneLogic();

        add(historyOutputPanel.getSessionHeaderPanel(), BorderLayout.NORTH);
        add(buildReviewTabs, BorderLayout.CENTER);
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
        if (!isPreviewDocked) return;

        isPreviewDocked = false;
        GlobalUiSettings.savePreviewDocked(false);

        // Move all current tabs to the PreviewManager's frame
        var manager = chrome.getPreviewManager();
        var dockedTabs = previewTabbedPane.getFileToTabMap();
        
        // Use a copy to avoid concurrent modification while closing tabs
        var files = new java.util.HashSet<>(dockedTabs.keySet());
        for (var file : files) {
            var comp = dockedTabs.get(file);
            if (comp instanceof JComponent jc) {
                previewTabbedPane.closeTab(jc, file);
                manager.showPreviewInTabbedFrame("Preview: " + file.toString(), jc, null);
            }
        }

        int idx = buildReviewTabs.indexOfTab("Preview");
        if (idx != -1) {
            buildReviewTabs.removeTabAt(idx);
        }
    }

    public void setPreviewDocked(boolean docked) {
        if (this.isPreviewDocked == docked) return;
        this.isPreviewDocked = docked;
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
        var outputTabsContainer = historyOutputPanel.getOutputTabsContainer();
        outputTabsContainer.setVisible(!enabled);

        if (enabled) {
            if (verticalActivityCombinedPanel == null) {
                var sessionHeader = historyOutputPanel.getSessionHeaderPanel();
                var leftTopPanel = new JPanel(new BorderLayout());
                leftTopPanel.add(sessionHeader, BorderLayout.NORTH);
                leftTopPanel.add(activityTabs, BorderLayout.CENTER);

                var leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, leftTopPanel, commandPanel);
                leftSplit.setResizeWeight(0.4);

                verticalActivityCombinedPanel = new JSplitPane(
                        JSplitPane.HORIZONTAL_SPLIT, leftSplit, historyOutputPanel.getOutputTabsContainer());
                verticalActivityCombinedPanel.setResizeWeight(0.5);
                historyOutputPanel.applyFixedCaptureBarSizing(true);
            }
            removeAll();
            add(verticalActivityCombinedPanel, BorderLayout.CENTER);
        } else {
            historyOutputPanel.applyFixedCaptureBarSizing(false);
            removeAll();
            add(historyOutputPanel.getSessionHeaderPanel(), BorderLayout.NORTH);
            add(buildReviewTabs, BorderLayout.CENTER);
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

    public void setReviewTabTitle(String title) {
        int idx = buildReviewTabs.indexOfTab("Review");
        if (idx != -1) {
            buildReviewTabs.setTitleAt(idx, title);
        }
    }

    public void setReviewTabTooltip(String tooltip) {
        int idx = buildReviewTabs.indexOfTab("Review");
        if (idx != -1) {
            buildReviewTabs.setToolTipTextAt(idx, tooltip);
        }
    }

    public void setReviewTabLoading(boolean loading) {
        int idx = buildReviewTabs.indexOfTab("Review");
        if (idx != -1) {
            buildReviewTabs.setIconAt(idx, loading ? Icons.REFRESH : Icons.FLOWSHEET);
        }
    }

    public void selectReviewTab() {
        int idx = buildReviewTabs.indexOfTab("Review");
        if (idx != -1) {
            buildReviewTabs.setSelectedIndex(idx);
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
        SwingUtilities.updateComponentTreeUI(this);
    }
}
