package ai.brokk.gui;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.agents.ReviewAgent;
import ai.brokk.agents.ReviewGenerationException;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.ui.AbstractContentPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.ui.DiffProjectFileNavigationTarget;
import ai.brokk.difftool.ui.DiffToolbarCallbacks;
import ai.brokk.difftool.ui.DiffToolbarPanel;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.difftool.ui.ToolbarFeature;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.EditorFontSizeControl;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.MaterialProgressButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.dialogs.CreatePullRequestDialog;
import ai.brokk.gui.git.GitCommitTab;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.GlobalUiSettings;
import ai.brokk.util.ReviewParser;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * A panel that displays an aggregated diff of changes for the current session/branch.
 */
public class SessionChangesPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SessionChangesPanel.class);
    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitRepo repo;
    private final DeferredUpdateHelper deferredUpdateHelper;
    private final TabTitleUpdater tabTitleUpdater;

    @Nullable
    private DiffService.CumulativeChanges lastCumulativeChanges = null;

    public record ReviewState(@Nullable String commitHash, long generatedAtMillis) {}

    public record StalenessInfo(
            int commitsBehind, int uncommittedChanges, boolean differentBranch, boolean unknownCommit) {}

    @Nullable
    private ReviewState lastReviewState = null;

    @Nullable
    private String lastBaselineLabel = null;

    @Nullable
    private BaselineMode lastBaselineMode = null;

    private final ai.brokk.difftool.ui.DiffDisplayCore diffCore;


    private final ai.brokk.difftool.ui.FileTreePanel fileTreePanel;

    private final CodeReviewPanel codeReviewPanel;

    private final JSplitPane leftSplitPane;

    private final JPanel diffContainer;
    private final DiffToolbarPanel diffToolbar;

    private final JComboBox<ComparisonTarget> baselineDropdown;

    private final MaterialButton commitBtn;

    private final MaterialButton pullBtn;

    private final MaterialButton pushBtn;

    private final MaterialButton prBtn;

    private final MaterialProgressButton guidedReviewBtn;

    private final MaterialButton pasteBtn;

    private final CardLayout mainCardLayout;
    private final JPanel cardsPanel;
    private final JLabel emptyLabel;

    private boolean guidedReviewBusy = false;

    /** Whether a review (generated, loaded, or pasted) is currently being displayed. */
    private boolean hasGeneratedReview = false;

    private final JSplitPane rightVerticalSplitPane;

    private final JSplitPane mainSplitPane;

    @Nullable
    private ReviewParser.CodeExcerpt activeExcerpt = null;

    private List<FileComparisonInfo> fileComparisons = List.of();

    @FunctionalInterface
    public interface TabTitleUpdater {
        void updateTitleAndTooltip(String title, String tooltip);
    }

    public SessionChangesPanel(Chrome chrome, ContextManager contextManager, TabTitleUpdater tabTitleUpdater) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.tabTitleUpdater = tabTitleUpdater;

        var maybeRepo = contextManager.getProject().getRepo();
        if (!(maybeRepo instanceof GitRepo gr)) {
            throw new IllegalStateException("SessionChangesPanel requires a GitRepo");
        }
        this.repo = gr;

        setOpaque(false);
        setBorder(new CompoundBorder(
                new LineBorder(UIManager.getColor("Separator.foreground"), 1), new EmptyBorder(6, 6, 6, 6)));

        this.baselineDropdown = new JComboBox<>(ComparisonTarget.values());
        this.baselineDropdown.setOpaque(false);
        this.baselineDropdown.addActionListener(e -> requestUpdate());

        this.commitBtn = new MaterialButton("Changes to Commit");
        this.pullBtn = new MaterialButton("Pull");
        this.pushBtn = new MaterialButton("Push");
        this.prBtn = new MaterialButton("Create PR");
        this.guidedReviewBtn = new MaterialProgressButton("Guided Review", chrome);
        this.pasteBtn = new MaterialButton("Paste Review");
        this.diffContainer = new JPanel(new BorderLayout());
        this.diffContainer.setOpaque(false);

        // Create toolbar with navigation and font controls only (no view mode toggle or tools menu)
        var reviewFeatures = java.util.EnumSet.of(
                ToolbarFeature.CHANGE_NAVIGATION,
                ToolbarFeature.FILE_NAVIGATION,
                ToolbarFeature.FONT_CONTROLS);
        this.diffToolbar = new DiffToolbarPanel(reviewFeatures, createToolbarCallbacks());

        // Wrap diffContainer with toolbar at top
        var diffWithToolbar = new JPanel(new BorderLayout());
        diffWithToolbar.setOpaque(false);
        diffWithToolbar.add(diffToolbar, BorderLayout.NORTH);
        diffWithToolbar.add(diffContainer, BorderLayout.CENTER);

        this.codeReviewPanel = new CodeReviewPanel(this::generateGuidedReview, contextManager);
        this.fileTreePanel = new ai.brokk.difftool.ui.FileTreePanel(
                List.of(), contextManager.getProject().getRoot());

        this.leftSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeReviewPanel.getListPanel(), fileTreePanel);
        this.leftSplitPane.setResizeWeight(0.5);

        this.rightVerticalSplitPane =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeReviewPanel.getDetailPanel(), diffWithToolbar);
        this.rightVerticalSplitPane.setResizeWeight(0.5);

        this.mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplitPane, rightVerticalSplitPane);
        this.mainSplitPane.setDividerLocation(300);

        this.mainCardLayout = new CardLayout();
        this.cardsPanel = new JPanel(mainCardLayout);
        this.cardsPanel.setOpaque(false);

        this.emptyLabel = new JLabel("", SwingConstants.CENTER);
        this.emptyLabel.setBorder(new EmptyBorder(20, 0, 20, 0));
        this.cardsPanel.add(emptyLabel, "EMPTY");
        this.cardsPanel.add(mainSplitPane, "MAIN");

        var headerPanel = new JPanel(new BorderLayout(8, 0));
        headerPanel.setOpaque(false);
        var leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftHeader.setOpaque(false);
        leftHeader.add(baselineDropdown);
        headerPanel.add(leftHeader, BorderLayout.WEST);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(commitBtn);
        buttonPanel.add(pullBtn);
        buttonPanel.add(pushBtn);
        buttonPanel.add(prBtn);
        if (Boolean.parseBoolean(System.getProperty("brokk.devmode", "false"))) {
            buttonPanel.add(pasteBtn);
        }
        buttonPanel.add(guidedReviewBtn);
        headerPanel.add(buttonPanel, BorderLayout.EAST);

        add(headerPanel, BorderLayout.NORTH);
        add(cardsPanel, BorderLayout.CENTER);

        this.deferredUpdateHelper = new DeferredUpdateHelper(this, this::performRefresh);

        var dummyPanel = new ai.brokk.difftool.ui.BrokkDiffPanel(
                new ai.brokk.difftool.ui.BrokkDiffPanel.Builder(chrome.getTheme(), contextManager), chrome.getTheme());
        // Initialize font index from saved settings so UnifiedEditorArea.hasExplicitFontSize() returns true
        ensureFontIndexInitialized();
        dummyPanel.setCurrentFontIndex(currentFontIndex);
        this.diffCore = createDiffCore(dummyPanel);

        // One-time listener installations
        fileTreePanel.setSelectionListener(new DiffProjectFileNavigationTarget() {
            @Override
            public void navigateToFile(int fileIndex) {
                codeReviewPanel.clearSelection();
                activeExcerpt = null;
                diffCore.showFile(fileIndex);
            }

            @Override
            public void navigateToFile(ProjectFile file) {
                codeReviewPanel.clearSelection();
                activeExcerpt = null;
                diffCore.showFile(file);
            }

            @Override
            public void navigateToLocation(ProjectFile file, int lineNumber, ReviewParser.DiffSide side) {
                codeReviewPanel.clearSelection();
                activeExcerpt = null;
                diffCore.showLocation(file, lineNumber, side);
            }
        });

        codeReviewPanel.addReviewNavigationListener(ce -> {
            fileTreePanel.selectFileQuietly(ce.file());
            activeExcerpt = ce;
            diffCore.showLocation(ce.file(), ce.line(), ce.side());
        });

        commitBtn.addActionListener(e -> showCommitDialog());
        pullBtn.addActionListener(e -> performPull());
        pushBtn.addActionListener(e -> performPush());
        prBtn.addActionListener(e -> CreatePullRequestDialog.show(chrome.getFrame(), chrome, contextManager));
        pasteBtn.addActionListener(e -> handlePasteReview());

        SwingUtil.applyPrimaryButtonStyle(commitBtn);
        SwingUtil.applyPrimaryButtonStyle(guidedReviewBtn);
        guidedReviewBtn.setIdleAction(this::generateGuidedReview);
        guidedReviewBtn.setCancelAction(() -> contextManager.interruptLlmAction());
    }

    private ai.brokk.difftool.ui.DiffDisplayCore createDiffCore(ai.brokk.difftool.ui.BrokkDiffPanel dummyPanel) {
        return new ai.brokk.difftool.ui.DiffDisplayCore(
                dummyPanel, contextManager, chrome.getTheme(), fileComparisons, false, 0) {
            @Override
            protected ai.brokk.difftool.ui.AbstractDiffPanel createPanel(
                    int index, ai.brokk.difftool.node.JMDiffNode diffNode) {
                // Review panel always uses unified view with full context
                var panel = new ai.brokk.difftool.ui.unified.UnifiedDiffPanel(dummyPanel, chrome.getTheme(), diffNode);
                panel.setContextMode(ai.brokk.difftool.ui.unified.UnifiedDiffDocument.ContextMode.FULL_CONTEXT);
                panel.applyTheme(chrome.getTheme());
                return panel;
            }

            @Override
            public void showFile(int index) {
                super.showFile(index);
                fileTreePanel.selectFile(index);
            }

            @Override
            protected void displayPanel(
                    int index,
                    ai.brokk.difftool.ui.AbstractDiffPanel panel,
                    int targetLine,
                    ReviewParser.DiffSide targetSide) {
                diffContainer.removeAll();
                diffContainer.add(panel.getComponent(), BorderLayout.CENTER);

                // Apply saved font size to the panel
                applyFontSizeToPanel(panel);

                if (targetLine > 0) {
                    panel.resetAutoScrollFlag();
                    panel.diff(false);
                    if (panel instanceof ai.brokk.difftool.ui.BufferDiffPanel bp) {
                        var side = (targetSide == ReviewParser.DiffSide.OLD)
                                ? ai.brokk.difftool.ui.BufferDiffPanel.PanelSide.LEFT
                                : ai.brokk.difftool.ui.BufferDiffPanel.PanelSide.RIGHT;
                        bp.scrollToLine(targetLine, side);
                    } else if (panel instanceof ai.brokk.difftool.ui.unified.UnifiedDiffPanel up) {
                        up.clearExcerptHighlight();
                        up.scrollToLine(targetLine, targetSide);
                        if (activeExcerpt != null) {
                            String[] lines = activeExcerpt.excerpt().split("\\r?\\n", -1);
                            int endLine = targetLine + Math.max(0, lines.length - 1);
                            up.highlightExcerptLines(targetLine, endLine, targetSide);
                        }
                    }
                } else {
                    if (panel instanceof ai.brokk.difftool.ui.unified.UnifiedDiffPanel up) {
                        up.clearExcerptHighlight();
                    }
                    panel.resetToFirstDifference();
                    panel.diff(true);
                }

                diffContainer.revalidate();
                diffContainer.repaint();

                // Update toolbar button states after panel is displayed
                SwingUtilities.invokeLater(() -> {
                    diffToolbar.updateButtonStates();
                    // Request focus on the panel to prevent focus going elsewhere
                    panel.getComponent().requestFocusInWindow();
                });
            }
        };
    }

    public void requestUpdate() {
        deferredUpdateHelper.requestUpdate();
    }

    public void refreshTitleAsync() {
        SwingUtilities.invokeLater(() -> {
            tabTitleUpdater.updateTitleAndTooltip("Review (...)", "Computing branch-based changes...");
        });

        contextManager
                .submitBackgroundTask("Refreshing review title", () -> {
                    var state = resolveBaselineState();

                    var result = computeCumulativeChanges(state.baselineLabel(), state.baselineMode());
                    SwingUtilities.invokeLater(() -> updateTitleAndTooltipFromResult(result, state.baselineLabel()));
                    return null;
                })
                .exceptionally(ex -> {
                    logger.warn("Failed to refresh title async", ex);
                    SwingUtilities.invokeLater(
                            () -> tabTitleUpdater.updateTitleAndTooltip("Review", "Failed to compute changes"));
                    return null;
                });
    }

    private record BaselineState(BaselineMode baselineMode, String baselineLabel) {}

    private enum ComparisonTarget {
        CUMULATIVE,
        SESSION;
    }

    private @Nullable String resolvedBaselineBranch = null;

    private BaselineState resolveBaselineState() {
        try {
            ComparisonTarget target = (ComparisonTarget) baselineDropdown.getSelectedItem();
            if (target == ComparisonTarget.SESSION) {
                var firstSessionCommit = contextManager.getContextHistory().getFirstGitState();
                if (firstSessionCommit.isPresent()) {
                    return new BaselineState(
                            BaselineMode.SESSION, firstSessionCommit.get().commitHash());
                }
                return new BaselineState(BaselineMode.NO_BASELINE, "No session start found");
            }

            String defaultBranch = repo.getDefaultBranch();
            String currentBranch = repo.getCurrentBranch();

            String remoteName = repo.remote().getOriginRemoteNameWithFallback();
            String upstreamRefCandidate = remoteName != null ? remoteName + "/" + defaultBranch : null;
            boolean hasUpstream =
                    upstreamRefCandidate != null && repo.listRemoteBranches().contains(upstreamRefCandidate);

            String baseline = defaultBranch;
            if (hasUpstream && upstreamRefCandidate != null) {
                // If upstream is ahead of our local default branch, use upstream as the baseline
                String mergeBase = repo.getMergeBase(defaultBranch, upstreamRefCandidate);
                if (mergeBase != null
                        && !mergeBase.equals(
                                repo.resolveToCommit(upstreamRefCandidate).name())) {
                    baseline = upstreamRefCandidate;
                }
            }

            resolvedBaselineBranch = baseline;

            if (!currentBranch.equals(defaultBranch)) {
                return new BaselineState(BaselineMode.NON_DEFAULT_BRANCH, baseline);
            }

            if (hasUpstream && upstreamRefCandidate != null) {
                return new BaselineState(BaselineMode.DEFAULT_WITH_UPSTREAM, upstreamRefCandidate);
            }

            return new BaselineState(BaselineMode.DEFAULT_LOCAL_ONLY, "HEAD");
        } catch (Exception e) {
            logger.warn("Failed to compute baseline for changes", e);
            String errorLabel = e.getMessage();
            return new BaselineState(BaselineMode.NO_BASELINE, errorLabel != null ? errorLabel : "Unknown Error");
        }
    }

    private void performRefresh() {
        tabTitleUpdater.updateTitleAndTooltip("Review (...)", "Computing branch-based changes...");

        contextManager
                .submitBackgroundTask("Computing review changes", this::resolveBaselineState)
                .thenCompose(state -> {
                    lastBaselineLabel = state.baselineLabel();
                    lastBaselineMode = state.baselineMode();

                    SwingUtilities.invokeLater(() -> {
                        tabTitleUpdater.updateTitleAndTooltip("Review (...)", "Computing branch-based changes...");
                    });

                    return refreshCumulativeChangesAsync(state.baselineLabel(), state.baselineMode());
                })
                .thenAccept(result -> {
                    if (result == null) return;
                    lastCumulativeChanges = result;
                    var prepared = DiffService.preparePerFileSummaries(result);

                    StalenessInfo staleness = null;
                    if (lastReviewState != null) {
                        staleness = computeStaleness();
                    }

                    final StalenessInfo finalStaleness = staleness;
                    final String label = lastBaselineLabel != null ? lastBaselineLabel : "";
                    final BaselineMode mode = lastBaselineMode != null ? lastBaselineMode : BaselineMode.NO_BASELINE;

                    SwingUtilities.invokeLater(() -> {
                        updateTitleAndTooltipFromResult(result, label);
                        updateContent(result, prepared, label, mode);

                        if (finalStaleness != null) {
                            codeReviewPanel.getListPanel().setStalenessNotice(formatStalenessMessage(finalStaleness));
                        }
                    });
                })
                .exceptionally(ex -> {
                    logger.warn("Failed to compute cumulative changes", ex);
                    SwingUtilities.invokeLater(() -> {
                        tabTitleUpdater.updateTitleAndTooltip("Review", "Failed to compute changes");
                    });
                    return null;
                });
    }

    private DiffService.CumulativeChanges computeCumulativeChanges(String baselineLabel, BaselineMode baselineMode)
            throws GitAPIException {
        if (baselineMode == BaselineMode.NO_BASELINE) {
            return new DiffService.CumulativeChanges(0, 0, 0, List.of(), List.of(), null);
        }

        Map<ProjectFile, GitRepo.ModifiedFile> fileMap = new HashMap<>();
        String leftCommitSha = null;
        String currentBranch = repo.getCurrentBranch();
        List<CommitInfo> commits = List.of();

        switch (baselineMode) {
            case NON_DEFAULT_BRANCH -> {
                String defaultBranchRef = baselineLabel;
                leftCommitSha = repo.getMergeBase("HEAD", defaultBranchRef);
                if (leftCommitSha != null) {
                    var myChanges = repo.listFilesChangedBetweenCommits(leftCommitSha, "HEAD");
                    for (var mf : myChanges) {
                        fileMap.putIfAbsent(mf.file(), mf);
                    }
                    commits = repo.listCommitsBetweenBranches(leftCommitSha, "HEAD", false);
                } else {
                    leftCommitSha = "HEAD";
                }
                for (var mf : repo.getModifiedFiles()) {
                    fileMap.put(mf.file(), mf);
                }
            }
            case DEFAULT_WITH_UPSTREAM -> {
                String upstreamRef = baselineLabel;
                leftCommitSha = repo.getMergeBase("HEAD", upstreamRef);
                if (leftCommitSha != null) {
                    var myChanges = repo.listFilesChangedBetweenCommits(leftCommitSha, "HEAD");
                    for (var mf : myChanges) {
                        fileMap.putIfAbsent(mf.file(), mf);
                    }
                    commits = repo.listCommitsBetweenBranches(leftCommitSha, "HEAD", false);
                } else {
                    leftCommitSha = "HEAD";
                }
                for (var mf : repo.getModifiedFiles()) {
                    fileMap.put(mf.file(), mf);
                }
            }
            case DEFAULT_LOCAL_ONLY -> {
                for (var mf : repo.getModifiedFiles()) {
                    fileMap.put(mf.file(), mf);
                }
                leftCommitSha = "HEAD";
            }
            case SESSION -> {
                leftCommitSha = baselineLabel;
                var myChanges = repo.listFilesChangedBetweenCommits(leftCommitSha, "HEAD");
                for (var mf : myChanges) {
                    fileMap.putIfAbsent(mf.file(), mf);
                }
                for (var mf : repo.getModifiedFiles()) {
                    fileMap.put(mf.file(), mf);
                }
                commits = List.of();
            }
            default -> throw new AssertionError();
        }

        var fileSet = new HashSet<>(fileMap.values());
        var summarizedChanges =
                DiffService.summarizeDiff(repo, requireNonNull(leftCommitSha), "WORKING", fileSet, commits);

        GitWorkflow.PushPullState pushPullState = null;
        try {
            boolean hasUpstream = repo.hasUpstreamBranch(currentBranch);
            boolean canPush;
            Set<String> unpushedCommitIds = new HashSet<>();
            if (hasUpstream) {
                unpushedCommitIds.addAll(repo.remote().getUnpushedCommitIds(currentBranch));
                canPush = !unpushedCommitIds.isEmpty();
            } else {
                canPush = true;
            }
            pushPullState = new GitWorkflow.PushPullState(hasUpstream, hasUpstream, canPush, unpushedCommitIds);
        } catch (Exception e) {
            logger.debug("Failed to evaluate push/pull state for branch {}", currentBranch, e);
        }

        return new DiffService.CumulativeChanges(
                summarizedChanges.filesChanged(),
                summarizedChanges.totalAdded(),
                summarizedChanges.totalDeleted(),
                summarizedChanges.perFileChanges(),
                commits,
                pushPullState);
    }

    private CompletableFuture<DiffService.CumulativeChanges> refreshCumulativeChangesAsync(
            String baselineLabel, BaselineMode baselineMode) {
        return contextManager.submitBackgroundTask(
                "Computing review changes", () -> computeCumulativeChanges(baselineLabel, baselineMode));
    }

    private void updateTitleAndTooltipFromResult(DiffService.CumulativeChanges result, String baselineLabel) {
        String title;
        String tooltip;

        if (result.filesChanged() == 0) {
            title = "Review (No Changes)";
            tooltip = "No changes" + (baselineLabel.isEmpty() ? "" : " vs " + baselineLabel);
        } else {
            boolean isDark = chrome.getTheme().isDarkTheme();
            Color plusColor = ThemeColors.getColor(isDark, "diff_added_fg");
            Color minusColor = ThemeColors.getColor(isDark, "diff_deleted_fg");
            String baselineSuffix = baselineLabel.isEmpty() ? "" : " vs " + baselineLabel;

            title = String.format(
                    "<html>Review (<span style='color:%s'>+%d</span>/<span style='color:%s'>-%d</span>)</html>",
                    ColorUtil.toHex(plusColor),
                    result.totalAdded(),
                    ColorUtil.toHex(minusColor),
                    result.totalDeleted());

            tooltip = String.format(
                    "%d files changed, %d insertions, %d deletions%s",
                    result.filesChanged(), result.totalAdded(), result.totalDeleted(), baselineSuffix);
        }

        tabTitleUpdater.updateTitleAndTooltip(title, tooltip);
    }

    public void updateContent(
            DiffService.CumulativeChanges res,
            List<Map.Entry<String, DiffService.DiffEntry>> prepared,
            @Nullable String baselineLabel,
            @Nullable BaselineMode baselineMode) {

        // Check if the baseline or mode has changed since the last review was generated
        boolean baselineChanged = (baselineLabel != null && !baselineLabel.equals(lastBaselineLabel))
                || (baselineMode != null && baselineMode != lastBaselineMode);

        if (baselineChanged && hasGeneratedReview) {
            hasGeneratedReview = false;
            lastReviewState = null;
            activeExcerpt = null;
            codeReviewPanel.getDetailPanel().showPlaceholder();
            updateReviewPanelVisibility(false);
        }

        refreshUI(res, prepared, baselineMode);
    }

    private String getEmptyStateMessage() {
        if (BaselineMode.NO_BASELINE == lastBaselineMode) {
            return "No baseline to compare";
        } else if ("HEAD".equals(lastBaselineLabel)) {
            return "Working tree is clean (no uncommitted changes).";
        } else if (lastBaselineLabel != null && !lastBaselineLabel.isBlank()) {
            return "No changes vs " + lastBaselineLabel + ".";
        } else {
            return "No changes to review.";
        }
    }

    private FileComparisonInfo toFileComparisonInfo(Map.Entry<String, DiffService.DiffEntry> entry) {
        ProjectFile pf = null;
        try {
            pf = contextManager.toFile(entry.getKey());
        } catch (Exception e) {
            logger.debug("Failed to resolve ProjectFile for {}", entry.getKey(), e);
        }
        return new FileComparisonInfo(
                pf,
                new BufferSource.StringSource(entry.getValue().oldContent(), "", entry.getKey(), null),
                new BufferSource.StringSource(entry.getValue().newContent(), "", entry.getKey(), null));
    }

    private void updateDropdownLabels() {
        String branchLabel = resolvedBaselineBranch != null ? resolvedBaselineBranch : "branch";

        // Remove action listeners temporarily to avoid triggering updates while rebuilding
        var listeners = baselineDropdown.getActionListeners();
        for (var al : listeners) {
            baselineDropdown.removeActionListener(al);
        }

        ComparisonTarget currentSelection = (ComparisonTarget) baselineDropdown.getSelectedItem();

        baselineDropdown.removeAllItems();
        baselineDropdown.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == ComparisonTarget.CUMULATIVE) {
                    setText("Changes vs " + branchLabel);
                } else if (value == ComparisonTarget.SESSION) {
                    setText("Changes this Session");
                }
                return this;
            }
        });
        baselineDropdown.addItem(ComparisonTarget.CUMULATIVE);
        baselineDropdown.addItem(ComparisonTarget.SESSION);

        // Restore selection without triggering listeners
        if (currentSelection != null) {
            baselineDropdown.setSelectedItem(currentSelection);
        }

        // Re-add action listeners
        for (var al : listeners) {
            baselineDropdown.addActionListener(al);
        }
    }

    private void refreshUI(
            DiffService.CumulativeChanges res,
            List<Map.Entry<String, DiffService.DiffEntry>> prepared,
            @Nullable BaselineMode baselineMode) {

        updateDropdownLabels();

        boolean noBaseline = baselineMode == BaselineMode.NO_BASELINE;
        boolean noChanges = res.filesChanged() == 0;

        // Show MAIN card if we have changes OR if we are currently displaying a review.
        // If we have no baseline at all, we stay in EMPTY state.
        if (noBaseline || (noChanges && !hasGeneratedReview)) {
            diffCore.updateFileComparisons(List.of());
            emptyLabel.setText(getEmptyStateMessage());
            mainCardLayout.show(cardsPanel, "EMPTY");
            revalidate();
            repaint();
            return;
        }

        mainCardLayout.show(cardsPanel, "MAIN");

        boolean hasUncommittedChanges = false;
        try {
            hasUncommittedChanges = !repo.getModifiedFiles().isEmpty();
        } catch (GitAPIException e) {
            logger.debug("Unable to determine uncommitted changes state", e);
        }

        boolean isSession = baselineMode == BaselineMode.SESSION;

        commitBtn.setVisible(!isSession && hasUncommittedChanges);

        guidedReviewBtn.setVisible(true);

        var pushPull = res.pushPullState();
        boolean canPull = pushPull != null && pushPull.canPull();
        pullBtn.setEnabled(canPull && !hasUncommittedChanges);
        if (hasUncommittedChanges) {
            pullBtn.setToolTipText("Commit or stash changes before pulling");
        } else if (!canPull) {
            pullBtn.setToolTipText(
                    pushPull != null && pushPull.hasUpstream()
                            ? "No changes to pull"
                            : "No upstream branch configured");
        } else {
            pullBtn.setToolTipText(null);
        }
        pullBtn.setVisible(!isSession);

        pushBtn.setEnabled(!hasUncommittedChanges);
        pushBtn.setVisible(!isSession && pushPull != null && pushPull.canPush());

        boolean showPR = !isSession
                && (baselineMode == BaselineMode.NON_DEFAULT_BRANCH
                        || (baselineMode == BaselineMode.DEFAULT_WITH_UPSTREAM && res.filesChanged() > 0));

        boolean prBtnEnabled = !hasUncommittedChanges;
        String prBtnTooltip = null;
        if (prBtnEnabled && showPR) {
            try {
                String currentBranch = repo.getCurrentBranch();
                if (GitHubAuth.getOrCreateInstance(contextManager.getProject())
                        .hasOpenPullRequestForBranch(currentBranch)) {
                    prBtnEnabled = false;
                    prBtnTooltip = "A pull request already exists for branch " + currentBranch;
                }
            } catch (Exception e) {
                logger.debug("Could not check for existing PRs: {}", e.getMessage());
            }
        }
        prBtn.setEnabled(prBtnEnabled);
        prBtn.setToolTipText(prBtnTooltip);
        prBtn.setVisible(showPR);

        String projectName = "Project";
        var root = contextManager.getProject().getRoot();
        if (root.getFileName() != null) projectName = root.getFileName().toString();

        List<FileComparisonInfo> nextComparisons =
                prepared.stream().map(this::toFileComparisonInfo).toList();

        // If the file set changed significantly and we aren't showing a review, clear the excerpt
        if (!nextComparisons.equals(this.fileComparisons)) {
            activeExcerpt = null;
        }

        this.fileComparisons = nextComparisons;
        this.diffCore.updateFileComparisons(this.fileComparisons, 0);

        fileTreePanel.updateData(fileComparisons, root, projectName);

        updateReviewPanelVisibility(hasGeneratedReview);

        if (!nextComparisons.isEmpty()) {
            this.diffCore.showFile(0);
        } else {
            diffContainer.removeAll();
            diffContainer.add(new JLabel("No file changes to display", SwingConstants.CENTER), BorderLayout.CENTER);
        }
        applyTheme(chrome.getTheme());

        revalidate();
        repaint();
    }

    private void showCommitDialog() {
        var content = new GitCommitTab(chrome, contextManager);
        content.requestUpdate();
        var dialog = new BaseThemedDialog(chrome.getFrame(), "Changes");
        dialog.getContentRoot().add(content);
        dialog.setSize(800, 600);
        dialog.setLocationRelativeTo(chrome.getFrame());
        Chrome.applyIcon(dialog);
        content.applyTheme(chrome.getTheme());
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                chrome.getRightPanel().requestReviewUpdate();
                var tab = chrome.getGitCommitTab();
                if (tab != null) tab.requestUpdate();
            }
        });
        dialog.setVisible(true);
    }

    private void performPull() {
        contextManager.submitExclusiveAction(() -> {
            try {
                String branch = repo.getCurrentBranch();
                chrome.showOutputSpinner("Pulling " + branch + "...");
                var result = new GitWorkflow(contextManager).pull(branch);
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    if (result.isEmpty()) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Pull completed successfully.");
                    } else {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Pull: " + result);
                    }
                    requestUpdate();
                    chrome.updateGitRepo();
                });
            } catch (GitAPIException e) {
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    chrome.toolError("Pull failed: " + e.getMessage(), "Pull Error");
                });
            }
            return null;
        });
    }

    private void performPush() {
        contextManager.submitExclusiveAction(() -> {
            try {
                String branch = repo.getCurrentBranch();
                chrome.showOutputSpinner("Pushing " + branch + "...");
                var result = new GitWorkflow(contextManager).push(branch);
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    if (result.isEmpty()) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Push completed successfully.");
                    } else {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Push: " + result);
                    }
                    requestUpdate();
                    chrome.updateGitRepo();
                });
            } catch (GitAPIException e) {
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    chrome.toolError("Push failed: " + e.getMessage(), "Push Error");
                });
            }
            return null;
        });
    }

    public @Nullable StalenessInfo computeStaleness() {
        var state = lastReviewState;
        if (state == null) {
            return null;
        }

        String hash = state.commitHash();
        int uncommittedChanges = 0;
        try {
            uncommittedChanges = repo.getModifiedFiles().size();
        } catch (GitAPIException e) {
            logger.debug("Failed to get modified files for staleness check", e);
        }

        if (hash == null) {
            logger.debug("Review has unknown commit hash; cannot compute staleness");
            return new StalenessInfo(0, uncommittedChanges, false, true);
        }

        int commitsBehind = repo.countCommitsSince(hash);

        boolean differentBranch = false;
        try {
            differentBranch = !repo.isCommitReachableFrom(hash, "HEAD");
        } catch (Exception e) {
            logger.debug("Failed to check commit reachability for staleness check", e);
        }

        return new StalenessInfo(commitsBehind, uncommittedChanges, differentBranch, false);
    }

    private static int defaultSplitPaneDividerSize() {
        int size = UIManager.getInt("SplitPane.dividerSize");
        return size > 0 ? size : 8;
    }

    private void updateReviewPanelVisibility(boolean visible) {
        SwingUtil.runOnEdt(() -> {
            var listPanel = codeReviewPanel.getListPanel();
            listPanel.setVisible(visible);

            leftSplitPane.setDividerSize(visible ? defaultSplitPaneDividerSize() : 0);
            if (!visible) {
                leftSplitPane.setDividerLocation(0);
            } else {
                leftSplitPane.setResizeWeight(0.5);
                leftSplitPane.setDividerLocation(0.5);
            }

            codeReviewPanel.getDetailPanel().setVisible(visible);

            rightVerticalSplitPane.setDividerSize(visible ? defaultSplitPaneDividerSize() : 0);
            if (!visible) {
                rightVerticalSplitPane.setDividerLocation(0);
            } else {
                rightVerticalSplitPane.setResizeWeight(0.5);
                rightVerticalSplitPane.setDividerLocation(0.5);
            }

            mainSplitPane.setDividerSize(defaultSplitPaneDividerSize());
            mainSplitPane.setResizeWeight(0.0);
            mainSplitPane.setDividerLocation(visible ? 300 : 200);

            // Hide navigation buttons in guided review mode (navigation via review items)
            diffToolbar.setNavigationVisible(!visible);

            revalidate();
            repaint();
        });
    }

    private void setGuidedReviewBusy(boolean busy) {
        SwingUtil.runOnEdt(() -> {
            guidedReviewBusy = busy;
            if (!busy) {
                guidedReviewBtn.resetToIdle();
            }
        });
    }

    private void generateGuidedReview() {
        if (lastCumulativeChanges == null) return;

        setGuidedReviewBusy(true);
        codeReviewPanel.setBusy(true);
        guidedReviewBtn.setProgress(0);

        contextManager.submitLlmAction(() -> {
            try {
                var changes = lastCumulativeChanges;
                if (changes == null) {
                    SwingUtilities.invokeLater(() -> {
                        codeReviewPanel.setBusy(false);
                        setGuidedReviewBusy(false);
                        revalidate();
                        repaint();
                    });
                    return;
                }

                List<UUID> sessions;
                ComparisonTarget target = (ComparisonTarget) baselineDropdown.getSelectedItem();
                if (target == ComparisonTarget.SESSION) {
                    sessions = List.of(contextManager.getCurrentSessionId());
                } else {
                    sessions = DiffService.CumulativeChanges.findOverlappingSessions(contextManager, changes.commits());
                }

                var agent = new ReviewAgent(changes, sessions, contextManager, chrome, fileComparisons);

                agent.setProgressUpdater((stage, p) -> SwingUtilities.invokeLater(() -> {
                    guidedReviewBtn.setProgress(p);
                }));

                var result = agent.execute();

                String currentHash = repo.getCurrentCommitId();
                long now = System.currentTimeMillis();

                SwingUtilities.invokeLater(() -> {
                    lastReviewState = new ReviewState(currentHash, now);
                    hasGeneratedReview = true;
                    updateReviewPanelVisibility(true);
                    codeReviewPanel.displayReview(result.review(), result.context());
                    codeReviewPanel.setBusy(false);
                    setGuidedReviewBusy(false);
                    codeReviewPanel.getListPanel().setStalenessNotice(null);
                    revalidate();
                    repaint();
                });
            } catch (InterruptedException ex) {
                logger.debug("Review generation cancelled by user");
                SwingUtilities.invokeLater(() -> {
                    codeReviewPanel.setBusy(false);
                    setGuidedReviewBusy(false);
                    revalidate();
                    repaint();
                });
            } catch (ReviewGenerationException ex) {
                logger.warn("Review generation failed: {}", ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    codeReviewPanel.setBusy(false);
                    setGuidedReviewBusy(false);

                    String userMessage = ex.getStopDetails() != null
                            ? "Review generation failed: " + ex.getStopDetails().explanation()
                            : "Review generation failed: " + ex.getMessage();
                    chrome.toolError(userMessage, "Review Error");
                    revalidate();
                    repaint();
                });
            } catch (Exception ex) {
                logger.error("Unexpected error during review generation", ex);
                SwingUtilities.invokeLater(() -> {
                    codeReviewPanel.setBusy(false);
                    setGuidedReviewBusy(false);
                    chrome.toolError("Review generation failed: " + ex.getMessage());
                    revalidate();
                    repaint();
                });
            }
        });
    }

    /**
     * Loads a review from markdown text that was previously generated.
     * This parses the markdown as a GuidedReview and displays it in the review panels.
     *
     * @param markdown The markdown text containing the review
     * @param context The context associated with this review
     */
    public void loadExternalReview(String markdown, Context context) {
        var resolvedExcerpts = ReviewParser.resolveExcerptsNewOnly(contextManager, markdown);
        var review = ReviewParser.instance.parseMarkdownReview(markdown, resolvedExcerpts);

        var gitState = contextManager.getContextHistory().getGitState(context.id());
        @Nullable String hash = gitState.map(gs -> gs.commitHash()).orElse(null);

        if (hash != null) {
            logger.debug("Found GitState for context {}: hash={}", context.id(), hash);
        } else {
            logger.warn(
                    "No GitState found for context {}; review staleness cannot be accurately determined", context.id());
        }

        SwingUtilities.invokeLater(() -> {
            lastReviewState = new ReviewState(hash, System.currentTimeMillis());
            hasGeneratedReview = true;
            requestUpdate(); // Re-trigger refreshUI to handle card layout and panel visibility
            codeReviewPanel.displayReview(review, context);

            StalenessInfo staleness = computeStaleness();
            if (staleness != null) {
                logger.debug(
                        "Computed staleness for loaded review: differentBranch={}, commitsBehind={}, uncommitted={}",
                        staleness.differentBranch(),
                        staleness.commitsBehind(),
                        staleness.uncommittedChanges());
                codeReviewPanel.getListPanel().setStalenessNotice(formatStalenessMessage(staleness));
            }
        });
    }

    private void handlePasteReview() {
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            return;
        }
        String clipboardData;
        try {
            clipboardData = (String) clipboard.getData(DataFlavor.stringFlavor);
        } catch (UnsupportedFlavorException | IOException e) {
            logger.warn(e);
            return;
        }
        if (clipboardData == null || clipboardData.isBlank()) {
            return;
        }

        // Extract JSON from ToolExecutionRequest wrapper if present
        String json = extractReviewJson(clipboardData);
        if (json == null) {
            return;
        }

        // Parse and display
        CompletableFuture.supplyAsync(() -> {
                    var rawReview = ReviewParser.RawReview.fromJson(json);
                    var resolvedExcerpts = ReviewParser.resolveExcerptsNewOnly(contextManager, clipboardData);
                    return ReviewParser.GuidedReview.fromRaw(rawReview, resolvedExcerpts);
                })
                .thenAccept(guidedReview -> {
                    SwingUtilities.invokeLater(() -> {
                        hasGeneratedReview = true;
                        requestUpdate(); // Re-trigger refreshUI to handle card layout and panel visibility
                        codeReviewPanel.displayReview(guidedReview, Context.EMPTY);
                        codeReviewPanel.getListPanel().setStalenessNotice(null);
                    });
                })
                .exceptionally(ex -> {
                    logger.warn("Failed to parse pasted review", ex);
                    SwingUtilities.invokeLater(() -> {
                        chrome.toolError("Failed to parse review from clipboard: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private @Nullable String formatStalenessMessage(StalenessInfo staleness) {
        int commits = staleness.commitsBehind();
        int uncommitted = staleness.uncommittedChanges();
        boolean differentBranch = staleness.differentBranch();
        boolean unknownCommit = staleness.unknownCommit();

        if (!unknownCommit && !differentBranch && commits == 0 && uncommitted == 0) {
            return null;
        }

        StringBuilder sb = new StringBuilder("! ");
        if (unknownCommit) {
            sb.append("Review commit history unavailable; excerpts may not match current code");
        } else if (differentBranch) {
            sb.append("Review appears to be from a different branch; excerpts may not match");
        } else if (commits > 0) {
            sb.append("Review is ").append(commits).append(" commit(s) behind; excerpts may not match current code");
        } else {
            sb.append("Review may be stale");
        }

        if (uncommitted > 0) {
            sb.append(" (").append(uncommitted).append(" uncommitted change(s) present)");
        }

        return sb.toString();
    }

    private @Nullable String extractReviewJson(String text) {
        String trimmed = text.trim();

        // Check for ToolExecutionRequest toString() format
        // Format: { id = "...", name = "createReview", arguments = "{...}" }
        // The outer braces use = instead of : and the arguments value is an escaped JSON string
        if (trimmed.startsWith("{") && trimmed.contains("name = \"createReview\"")) {
            // Find arguments = " and extract the JSON string
            int argsStart = trimmed.indexOf("arguments = \"");
            if (argsStart == -1) {
                return null;
            }
            argsStart += "arguments = \"".length();

            // Find the closing quote - it's the last " before the final " }"
            // The arguments string ends with "}" so we look for "}" }
            int argsEnd = trimmed.lastIndexOf("\" }");
            if (argsEnd == -1 || argsEnd <= argsStart) {
                // Try just finding the last quote before end
                argsEnd = trimmed.lastIndexOf("\"");
                if (argsEnd <= argsStart) {
                    return null;
                }
            }

            String escaped = trimmed.substring(argsStart, argsEnd);
            return unescapeToolCallJson(escaped);
        }

        // Try parsing as raw JSON (starts with { and contains expected fields)
        if (trimmed.startsWith("{") && trimmed.contains("\"overview\"")) {
            return trimmed;
        }

        return null;
    }

    private String unescapeToolCallJson(String escaped) {
        var sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < escaped.length()) {
                char next = escaped.charAt(i + 1);
                switch (next) {
                    case '"' -> {
                        sb.append('"');
                        i++;
                    }
                    case '\\' -> {
                        sb.append('\\');
                        i++;
                    }
                    case 'n' -> {
                        sb.append('\n');
                        i++;
                    }
                    case 'r' -> {
                        sb.append('\r');
                        i++;
                    }
                    case 't' -> {
                        sb.append('\t');
                        i++;
                    }
                    case 'u' -> {
                        // Unicode escape sequence
                        if (i + 5 < escaped.length()) {
                            String hex = escaped.substring(i + 2, i + 6);
                            try {
                                int codePoint = Integer.parseInt(hex, 16);
                                sb.append((char) codePoint);
                                i += 5;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // Font size state for EditorFontSizeControl
    private int currentFontIndex = -1;

    /** Initialize font index from settings if not already set */
    private void ensureFontIndexInitialized() {
        if (currentFontIndex == -1) {
            float saved = GlobalUiSettings.getEditorFontSize();
            if (saved > 0f) {
                // Find closest font index for the saved size
                int closestIndex = EditorFontSizeControl.DEFAULT_FONT_INDEX;
                float minDiff = Math.abs(
                        EditorFontSizeControl.FONT_SIZES.get(EditorFontSizeControl.DEFAULT_FONT_INDEX) - saved);
                for (int i = 0; i < EditorFontSizeControl.FONT_SIZES.size(); i++) {
                    float diff = Math.abs(EditorFontSizeControl.FONT_SIZES.get(i) - saved);
                    if (diff < minDiff) {
                        minDiff = diff;
                        closestIndex = i;
                    }
                }
                currentFontIndex = closestIndex;
            } else {
                currentFontIndex = EditorFontSizeControl.DEFAULT_FONT_INDEX;
            }
        }
    }

    /** Apply current font size to a panel if font has been initialized */
    private void applyFontSizeToPanel(ai.brokk.difftool.ui.AbstractDiffPanel panel) {
        ensureFontIndexInitialized();
        if (currentFontIndex >= 0) {
            float fontSize = EditorFontSizeControl.FONT_SIZES.get(currentFontIndex);
            panel.applyEditorFontSize(fontSize);
        }
    }

    @Nullable
    private AbstractContentPanel getCurrentContentPanel() {
        var panel = diffCore.getCachedPanel(diffCore.getCurrentIndex());
        if (panel instanceof AbstractContentPanel acp) {
            return acp;
        }
        return null;
    }

    private DiffToolbarCallbacks createToolbarCallbacks() {
        return new DiffToolbarCallbacks() {
            @Override
            public void navigateToNextChange() {
                var panel = getCurrentContentPanel();
                if (panel != null) {
                    if (panel.isAtLastLogicalChange() && canNavigateToNextFile()) {
                        nextFile();
                    } else {
                        panel.doDown();
                        diffToolbar.updateButtonStates();
                    }
                }
            }

            @Override
            public void navigateToPreviousChange() {
                var panel = getCurrentContentPanel();
                if (panel != null) {
                    if (panel.isAtFirstLogicalChange() && canNavigateToPreviousFile()) {
                        previousFile();
                        var newPanel = getCurrentContentPanel();
                        if (newPanel != null) {
                            newPanel.goToLastLogicalChange();
                        }
                    } else {
                        panel.doUp();
                        diffToolbar.updateButtonStates();
                    }
                }
            }

            @Override
            public void nextFile() {
                int current = diffCore.getCurrentIndex();
                if (current < fileComparisons.size() - 1) {
                    diffCore.showFile(current + 1);
                    SwingUtilities.invokeLater(() -> diffToolbar.updateButtonStates());
                }
            }

            @Override
            public void previousFile() {
                int current = diffCore.getCurrentIndex();
                if (current > 0) {
                    diffCore.showFile(current - 1);
                    SwingUtilities.invokeLater(() -> diffToolbar.updateButtonStates());
                }
            }

            @Override
            public void performUndo() {
                // Read-only view
            }

            @Override
            public void performRedo() {
                // Read-only view
            }

            @Override
            public void saveAll() {
                // Read-only view
            }

            @Override
            public void switchViewMode(boolean useUnifiedView) {
                // Review panel always uses unified view
            }

            @Override
            public void setShowBlame(boolean show) {
                // Not supported in review panel
            }

            @Override
            public void setShowAllLines(boolean show) {
                // Review panel always shows all lines
            }

            @Override
            public void setShowBlankLineDiffs(boolean show) {
                // Not applicable - review panel uses unified view
            }

            @Override
            public void captureCurrentDiff() {
                // Not supported in view-only mode
            }

            @Override
            public void captureAllDiffs() {
                // Not supported in view-only mode
            }

            @Override
            public boolean canNavigateToNextChange() {
                var panel = getCurrentContentPanel();
                if (panel == null) return false;
                return !panel.isAtLastLogicalChange() || canNavigateToNextFile();
            }

            @Override
            public boolean canNavigateToPreviousChange() {
                var panel = getCurrentContentPanel();
                if (panel == null) return false;
                return !panel.isAtFirstLogicalChange() || canNavigateToPreviousFile();
            }

            @Override
            public boolean canNavigateToNextFile() {
                return fileComparisons.size() > 1 && diffCore.getCurrentIndex() < fileComparisons.size() - 1;
            }

            @Override
            public boolean canNavigateToPreviousFile() {
                return fileComparisons.size() > 1 && diffCore.getCurrentIndex() > 0;
            }

            @Override
            public boolean isUndoEnabled() {
                return false;
            }

            @Override
            public boolean isRedoEnabled() {
                return false;
            }

            @Override
            public boolean hasUnsavedChanges() {
                return false;
            }

            @Override
            public int getUnsavedCount() {
                return 0;
            }

            @Override
            public boolean isUnifiedView() {
                return true; // Review panel always uses unified view
            }

            @Override
            public boolean isBlameAvailable() {
                return false;
            }

            @Override
            public boolean isMultiFile() {
                return fileComparisons.size() > 1;
            }

            @Override
            public boolean isShowingBlame() {
                return false;
            }

            @Override
            public boolean isShowingAllLines() {
                return true; // Review panel always shows all lines
            }

            @Override
            public boolean isShowingBlankLineDiffs() {
                return false; // Not applicable - review panel uses unified view
            }

            @Override
            public int getCurrentFontIndex() {
                return currentFontIndex;
            }

            @Override
            public void setCurrentFontIndex(int index) {
                currentFontIndex = index;
            }

            @Override
            public void increaseEditorFont() {
                DiffToolbarCallbacks.super.increaseEditorFont();
                applyFontSizeToAllPanels();
            }

            @Override
            public void decreaseEditorFont() {
                DiffToolbarCallbacks.super.decreaseEditorFont();
                applyFontSizeToAllPanels();
            }

            @Override
            public void resetEditorFont() {
                DiffToolbarCallbacks.super.resetEditorFont();
                applyFontSizeToAllPanels();
            }

            private void applyFontSizeToAllPanels() {
                if (currentFontIndex < 0) return;
                float fontSize = EditorFontSizeControl.FONT_SIZES.get(currentFontIndex);
                GlobalUiSettings.saveEditorFontSize(fontSize);
                for (var panel : diffCore.getCachedPanels()) {
                    panel.applyEditorFontSize(fontSize);
                }
            }
        };
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        codeReviewPanel.applyTheme(guiTheme);
        fileTreePanel.applyTheme(guiTheme);
        for (var panel : diffCore.getCachedPanels()) {
            panel.applyTheme(guiTheme);
        }
        guidedReviewBtn.repaint();
    }

    public void dispose() {
        diffCore.clearCache();
    }

    public enum BaselineMode {
        NON_DEFAULT_BRANCH,
        DEFAULT_WITH_UPSTREAM,
        DEFAULT_LOCAL_ONLY,
        SESSION,
        NO_BASELINE
    }
}
