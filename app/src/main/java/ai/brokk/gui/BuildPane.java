package ai.brokk.gui;

import ai.brokk.ContextManager;
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

        // Build | Review | Terminal Tabs
        buildReviewTabs = new JTabbedPane(JTabbedPane.TOP);
        buildReviewTabs.addTab("Build", Icons.SCIENCE, buildSplitPane);
        var reviewPlaceholder = historyOutputPanel.getChangesTabPlaceholder();
        if (reviewPlaceholder != null) {
            buildReviewTabs.addTab("Review", Icons.FLOWSHEET, reviewPlaceholder);
        }
        buildReviewTabs.addTab("Terminal", Icons.TERMINAL, terminalPanel);

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

    public JTabbedPane getBuildReviewTabs() {
        return buildReviewTabs;
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
