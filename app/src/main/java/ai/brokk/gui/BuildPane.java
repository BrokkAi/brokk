package ai.brokk.gui;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import ai.brokk.gui.components.PreviewTabbedPane;
import ai.brokk.gui.components.SpinnerIconUtil;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.terminal.TaskListPanel;
import ai.brokk.gui.terminal.TerminalPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
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
    private PreviewTabbedPane previewTabbedPane;
    private final JTabbedPane commandPane;
    private final JPanel commandPanel;
    private final JPanel branchSelectorPanel;
    private final BranchSelectorButton branchSelectorButton;

    private @Nullable JSplitPane verticalActivityCombinedPanel = null;

    // Review tab infrastructure
    private final JPanel reviewTabContainer;
    private final DeferredUpdateHelper reviewUpdateHelper;

    @Nullable
    private SessionChangesPanel sessionChangesPanel;

    @Nullable
    private DiffService.CumulativeChanges lastCumulativeChanges;

    @Nullable
    private String lastBaselineLabel;

    @Nullable
    private SessionChangesPanel.BaselineMode lastBaselineMode;

    private final ContextManager contextManager;

    public BuildPane(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

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
        previewTabbedPane = new PreviewTabbedPane(
                chrome,
                chrome.getTheme(),
                title -> {}, // No window title to update here
                () -> {} // Don't close main UI tab when empty
                );

        // Review Tab Setup
        reviewTabContainer = new JPanel(new BorderLayout());
        reviewUpdateHelper = new DeferredUpdateHelper(reviewTabContainer, this::performRefreshReviewPanel);

        // Build | Review | Preview | Terminal Tabs
        buildReviewTabs = new JTabbedPane(JTabbedPane.TOP);
        buildReviewTabs.addTab("Build", Icons.SCIENCE, buildSplitPane);
        buildReviewTabs.addTab("Review", Icons.FLOWSHEET, reviewTabContainer);
        // Set minimum size to preferred size to ensure the tab labels and icons are respected
        reviewTabContainer.setMinimumSize(reviewTabContainer.getPreferredSize());

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

        add(historyOutputPanel.getSessionHeaderPanel(), BorderLayout.NORTH);
        add(buildReviewTabs, BorderLayout.CENTER);
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
        // Replace with a fresh pane for internal tracking while undocked
        this.previewTabbedPane = new PreviewTabbedPane(chrome, chrome.getTheme(), title -> {}, () -> {});
    }

    public void redockPreview(PreviewTabbedPane sourcePane) {
        if (chrome.getPreviewManager().isPreviewDocked()) return;

        chrome.getPreviewManager().setPreviewDocked(true);
        GlobalUiSettings.savePreviewDocked(true);

        this.previewTabbedPane = sourcePane;

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

    public void requestReviewUpdate() {
        reviewUpdateHelper.requestUpdate();
    }

    private void performRefreshReviewPanel() {
        SwingUtil.runOnEdt(() -> {
            if (sessionChangesPanel != null) {
                sessionChangesPanel.dispose();
                sessionChangesPanel = null;
            }

            setReviewTabTitle("Review (...)");
            setReviewTabTooltip("Computing branch-based changes...");

            reviewTabContainer.removeAll();
            var spinnerLabel = new JLabel("Computing branch-based changes...", SwingConstants.CENTER);
            var spinnerIcon = SpinnerIconUtil.getSpinner(chrome, true);
            if (spinnerIcon != null) {
                spinnerLabel.setIcon(spinnerIcon);
                spinnerLabel.setHorizontalTextPosition(SwingConstants.CENTER);
                spinnerLabel.setVerticalTextPosition(SwingConstants.BOTTOM);
            }
            reviewTabContainer.add(spinnerLabel, BorderLayout.CENTER);
            reviewTabContainer.revalidate();
            reviewTabContainer.repaint();

            refreshCumulativeChangesAsync();
        });
    }

    private void refreshCumulativeChangesAsync() {
        contextManager
                .submitBackgroundTask("Compute branch-based changes", () -> {
                    var repoOpt = repo();
                    if (repoOpt.isEmpty()) {
                        return new DiffService.CumulativeChanges(0, 0, 0, List.of(), null);
                    }

                    var repo = repoOpt.get();
                    if (!(repo instanceof ai.brokk.git.GitRepo gitRepo)) {
                        return new DiffService.CumulativeChanges(0, 0, 0, List.of(), null);
                    }

                    var baseline = computeBaselineForChanges(gitRepo);
                    lastBaselineLabel = baseline.displayLabel();
                    lastBaselineMode = baseline.mode();

                    if (baseline.mode() == SessionChangesPanel.BaselineMode.DETACHED
                            || baseline.mode() == SessionChangesPanel.BaselineMode.NO_BASELINE) {
                        return new DiffService.CumulativeChanges(0, 0, 0, List.of(), null);
                    }

                    try {
                        Map<ProjectFile, IGitRepo.ModifiedFile> fileMap = new HashMap<>();
                        String leftCommitSha = null;
                        String currentBranch = gitRepo.getCurrentBranch();

                        switch (baseline.mode()) {
                            case NON_DEFAULT_BRANCH -> {
                                String defaultBranch = baseline.baselineRef();
                                String defaultBranchRef = "refs/heads/" + defaultBranch;
                                leftCommitSha = gitRepo.getMergeBase(currentBranch, defaultBranchRef);
                                if (leftCommitSha != null) {
                                    var myChanges = gitRepo.listFilesChangedBetweenCommits(leftCommitSha, "HEAD");
                                    for (var mf : myChanges) fileMap.putIfAbsent(mf.file(), mf);
                                } else {
                                    leftCommitSha = "HEAD";
                                }
                                for (var mf : gitRepo.getModifiedFiles()) fileMap.put(mf.file(), mf);
                            }
                            case DEFAULT_WITH_UPSTREAM -> {
                                String upstreamRef = baseline.baselineRef();
                                leftCommitSha = gitRepo.getMergeBase("HEAD", upstreamRef);
                                if (leftCommitSha != null) {
                                    var myChanges = gitRepo.listFilesChangedBetweenCommits(leftCommitSha, "HEAD");
                                    for (var mf : myChanges) fileMap.putIfAbsent(mf.file(), mf);
                                } else {
                                    leftCommitSha = "HEAD";
                                }
                                for (var mf : gitRepo.getModifiedFiles()) fileMap.put(mf.file(), mf);
                            }
                            case DEFAULT_LOCAL_ONLY -> {
                                for (var mf : gitRepo.getModifiedFiles()) fileMap.put(mf.file(), mf);
                                leftCommitSha = "HEAD";
                            }
                            default -> {}
                        }

                        var fileSet = new HashSet<>(fileMap.values());
                        var summarizedChanges =
                                DiffService.summarizeDiff(repo, requireNonNull(leftCommitSha), "WORKING", fileSet);

                        boolean hasUpstream = gitRepo.hasUpstreamBranch(currentBranch);
                        boolean canPush;
                        Set<String> unpushedCommitIds = new HashSet<>();
                        if (hasUpstream) {
                            unpushedCommitIds.addAll(gitRepo.remote().getUnpushedCommitIds(currentBranch));
                            canPush = !unpushedCommitIds.isEmpty();
                        } else {
                            canPush = true;
                        }
                        GitWorkflow.PushPullState pushPullState =
                                new GitWorkflow.PushPullState(hasUpstream, hasUpstream, canPush, unpushedCommitIds);

                        return new DiffService.CumulativeChanges(
                                summarizedChanges.perFileChanges().size(),
                                summarizedChanges.totalAdded(),
                                summarizedChanges.totalDeleted(),
                                summarizedChanges.perFileChanges(),
                                pushPullState);

                    } catch (Exception e) {
                        logger.warn("Failed to compute branch-based changes", e);
                        return new DiffService.CumulativeChanges(0, 0, 0, List.of(), null);
                    }
                })
                .thenAccept(result -> {
                    var preparedSummaries = DiffService.preparePerFileSummaries(result);
                    SwingUtilities.invokeLater(() -> {
                        lastCumulativeChanges = result;
                        setReviewTabTitleAndTooltip(result);
                        updateReviewTabContentUi(result, preparedSummaries);
                    });
                });
    }

    private void setReviewTabTitleAndTooltip(DiffService.CumulativeChanges res) {
        boolean isSpecialState = "detached HEAD".equals(lastBaselineLabel) || "No repository".equals(lastBaselineLabel);
        String baselineSuffix = (!isSpecialState && lastBaselineLabel != null && !lastBaselineLabel.isEmpty())
                ? " vs " + lastBaselineLabel
                : "";

        if (res.filesChanged() == 0) {
            setReviewTabTitle("Review (0)");
            String tooltipMsg = isSpecialState
                    ? "No baseline to compare"
                    : ("HEAD".equals(lastBaselineLabel)
                            ? "Working tree is clean"
                            : (lastBaselineLabel != null && !lastBaselineLabel.isBlank()
                                    ? "No changes vs " + lastBaselineLabel
                                    : "No changes to review"));
            setReviewTabTooltip(tooltipMsg + baselineSuffix + ".");
        } else {
            boolean isDark = chrome.getTheme().isDarkTheme();
            Color plusColor = ThemeColors.getColor(isDark, "diff_added_fg");
            Color minusColor = ThemeColors.getColor(isDark, "diff_deleted_fg");
            String htmlTitle = String.format(
                    "<html>Review (%d, <span style='color:%s'>+%d</span>/<span style='color:%s'>-%d</span>)</html>",
                    res.filesChanged(),
                    ColorUtil.toHex(plusColor),
                    res.totalAdded(),
                    ColorUtil.toHex(minusColor),
                    res.totalDeleted());
            setReviewTabTitle(htmlTitle);
            setReviewTabTooltip("Cumulative changes: " + res.filesChanged() + " files, +" + res.totalAdded() + "/-"
                    + res.totalDeleted() + baselineSuffix);
        }
    }

    private void updateReviewTabContentUi(
            DiffService.CumulativeChanges res, List<Map.Entry<String, Context.DiffEntry>> prepared) {
        if (sessionChangesPanel != null) {
            sessionChangesPanel.dispose();
            sessionChangesPanel = null;
        }
        reviewTabContainer.removeAll();

        if (res.filesChanged() == 0) {
            String message = (lastBaselineMode == SessionChangesPanel.BaselineMode.DETACHED)
                    ? "Detached HEAD \u2014 no changes to review"
                    : ((lastBaselineMode == SessionChangesPanel.BaselineMode.NO_BASELINE)
                            ? "No baseline to compare"
                            : ("HEAD".equals(lastBaselineLabel)
                                    ? "Working tree is clean (no uncommitted changes)."
                                    : ((lastBaselineLabel != null && !lastBaselineLabel.isBlank())
                                            ? "No changes vs " + lastBaselineLabel + "."
                                            : "No changes to review.")));
            var none = new JLabel(message, SwingConstants.CENTER);
            none.setBorder(new EmptyBorder(20, 0, 20, 0));
            reviewTabContainer.add(none, BorderLayout.CENTER);
        } else {
            sessionChangesPanel = new SessionChangesPanel(chrome, contextManager);
            sessionChangesPanel.updateContent(res, prepared, lastBaselineLabel, lastBaselineMode);
            reviewTabContainer.add(sessionChangesPanel, BorderLayout.CENTER);
        }
        reviewTabContainer.revalidate();
        reviewTabContainer.repaint();
    }

    private record BaselineInfo(SessionChangesPanel.BaselineMode mode, String baselineRef, String displayLabel) {}

    private BaselineInfo computeBaselineForChanges(GitRepo gitRepo) {
        try {
            String defaultBranch = gitRepo.getDefaultBranch();
            String currentBranch = gitRepo.getCurrentBranch();

            boolean isDetached = isLikelyCommitHash(currentBranch)
                    || !gitRepo.listLocalBranches().contains(currentBranch);
            if (isDetached) return new BaselineInfo(SessionChangesPanel.BaselineMode.DETACHED, "HEAD", "detached HEAD");

            if (!currentBranch.equals(defaultBranch))
                return new BaselineInfo(
                        SessionChangesPanel.BaselineMode.NON_DEFAULT_BRANCH, defaultBranch, defaultBranch);

            if (gitRepo.listRemoteBranches().contains("origin/" + defaultBranch)) {
                return new BaselineInfo(
                        SessionChangesPanel.BaselineMode.DEFAULT_WITH_UPSTREAM,
                        "origin/" + defaultBranch,
                        "origin/" + defaultBranch);
            }
            return new BaselineInfo(SessionChangesPanel.BaselineMode.DEFAULT_LOCAL_ONLY, "HEAD", "HEAD");
        } catch (Exception e) {
            return new BaselineInfo(SessionChangesPanel.BaselineMode.NO_BASELINE, "", "Error: " + e.getMessage());
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Optional<IGitRepo> repo() {
        try {
            return Optional.of(contextManager.getProject().getRepo());
        } catch (Exception e) {
            return Optional.empty();
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
        if (sessionChangesPanel != null) sessionChangesPanel.applyTheme(guiTheme);
        if (lastCumulativeChanges != null) setReviewTabTitleAndTooltip(lastCumulativeChanges);
        SwingUtilities.updateComponentTreeUI(this);
    }
}
