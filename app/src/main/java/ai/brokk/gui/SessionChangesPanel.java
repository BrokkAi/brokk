package ai.brokk.gui;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.agents.ReviewAgent;
import ai.brokk.agents.ReviewGenerationException;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.ui.DiffProjectFileNavigationTarget;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.MaterialProgressButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.dialogs.CreatePullRequestDialog;
import ai.brokk.gui.git.GitCommitTab;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.ReviewParser;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public record ReviewState(String commitHash, long generatedAtMillis) {}

    public record StalenessInfo(int commitsBehind, int uncommittedChanges) {}

    @Nullable
    private ReviewState lastReviewState = null;

    @Nullable
    private String lastBaselineLabel = null;

    @Nullable
    private BaselineMode lastBaselineMode = null;

    private ai.brokk.difftool.ui.DiffDisplayCore diffCore;

    private final ai.brokk.difftool.ui.FileTreePanel fileTreePanel;

    private final CodeReviewPanel codeReviewPanel;

    private final JSplitPane leftSplitPane;

    private JPanel diffContainer;

    private JComboBox<ComparisonTarget> baselineDropdown;

    private final MaterialButton commitBtn;

    private final MaterialButton pullBtn;

    private final MaterialButton pushBtn;

    private final MaterialButton prBtn;

    private final MaterialProgressButton guidedReviewBtn;

    private boolean guidedReviewBusy = false;

    private boolean hasGeneratedReview = false;

    @Nullable
    private JScrollPane detailScrollPane = null;

    @Nullable
    private JSplitPane rightVerticalSplitPane = null;

    @Nullable
    private JSplitPane mainSplitPane = null;

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

        this.baselineDropdown = new JComboBox<>(ComparisonTarget.values());
        this.baselineDropdown.setOpaque(false);
        this.baselineDropdown.addActionListener(e -> requestUpdate());

        this.commitBtn = new MaterialButton("Changes to Commit");
        this.pullBtn = new MaterialButton("Pull");
        this.pushBtn = new MaterialButton("Push");
        this.prBtn = new MaterialButton("Create PR");
        this.guidedReviewBtn = new MaterialProgressButton("Guided Review", chrome);
        this.diffContainer = new JPanel(new BorderLayout());
        this.diffContainer.setOpaque(false);

        this.codeReviewPanel = new CodeReviewPanel(this::generateGuidedReview, contextManager);
        this.fileTreePanel = new ai.brokk.difftool.ui.FileTreePanel(
                List.of(), contextManager.getProject().getRoot());

        this.leftSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeReviewPanel.getListPanel(), fileTreePanel);
        this.leftSplitPane.setResizeWeight(0.5);

        this.deferredUpdateHelper = new DeferredUpdateHelper(this, this::performRefresh);

        // Initialize diffCore with empty comparisons - will be replaced on first rebuildUI
        var initialDummyPanel = new ai.brokk.difftool.ui.BrokkDiffPanel(
                new ai.brokk.difftool.ui.BrokkDiffPanel.Builder(chrome.getTheme(), contextManager), chrome.getTheme());
        this.diffCore = new ai.brokk.difftool.ui.DiffDisplayCore(
                initialDummyPanel, contextManager, chrome.getTheme(), List.of(), false, 0);
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
                            int commits = finalStaleness.commitsBehind();
                            int uncommitted = finalStaleness.uncommittedChanges();

                            if (commits > 0 || uncommitted > 0) {
                                StringBuilder sb = new StringBuilder("! Review may be stale: ");
                                if (commits > 0) {
                                    sb.append(commits).append(" commit(s)");
                                }
                                if (uncommitted > 0) {
                                    if (commits > 0) sb.append(", ");
                                    sb.append(uncommitted).append(" uncommitted change(s)");
                                }
                                codeReviewPanel.getListPanel().setStalenessNotice(sb.toString());
                            } else {
                                codeReviewPanel.getListPanel().setStalenessNotice(null);
                            }
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
            return new DiffService.CumulativeChanges(0, 0, 0, List.of(), null);
        }

        Map<ProjectFile, GitRepo.ModifiedFile> fileMap = new HashMap<>();
        String leftCommitSha = null;
        String currentBranch = repo.getCurrentBranch();

        switch (baselineMode) {
            case NON_DEFAULT_BRANCH -> {
                String defaultBranchRef = baselineLabel;
                leftCommitSha = repo.getMergeBase("HEAD", defaultBranchRef);
                if (leftCommitSha != null) {
                    var myChanges = repo.listFilesChangedBetweenCommits(leftCommitSha, "HEAD");
                    for (var mf : myChanges) {
                        fileMap.putIfAbsent(mf.file(), mf);
                    }
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
            }
            default -> throw new AssertionError();
        }

        var fileSet = new HashSet<>(fileMap.values());
        var summarizedChanges = DiffService.summarizeDiff(repo, requireNonNull(leftCommitSha), "WORKING", fileSet);

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

        // Always rebuild UI to keep structure; empty state handled inside rebuildUI
        rebuildUI(res, prepared, baselineMode);
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

    private void rebuildUI(
            DiffService.CumulativeChanges res,
            List<Map.Entry<String, DiffService.DiffEntry>> prepared,
            @Nullable BaselineMode baselineMode) {

        diffCore.clearCache();
        removeAll();

        updateDropdownLabels();

        var headerPanel = new JPanel(new BorderLayout(8, 0));
        headerPanel.setOpaque(false);

        var leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftHeader.setOpaque(false);

        leftHeader.add(baselineDropdown);

        headerPanel.add(leftHeader, BorderLayout.WEST);

        if (res.filesChanged() == 0 || baselineMode == BaselineMode.NO_BASELINE) {
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
            buttonPanel.setOpaque(false);
            headerPanel.add(buttonPanel, BorderLayout.EAST);

            var topContainer = new JPanel(new BorderLayout(0, 6));
            topContainer.setOpaque(false);
            topContainer.add(headerPanel, BorderLayout.NORTH);

            var emptyLabel = new JLabel(getEmptyStateMessage(), SwingConstants.CENTER);
            emptyLabel.setBorder(new EmptyBorder(20, 0, 20, 0));
            topContainer.add(emptyLabel, BorderLayout.CENTER);

            setBorder(new CompoundBorder(
                    new LineBorder(UIManager.getColor("Separator.foreground"), 1), new EmptyBorder(6, 6, 6, 6)));
            add(topContainer, BorderLayout.CENTER);

            revalidate();
            repaint();
            return;
        }

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonPanel.setOpaque(false);

        boolean hasUncommittedChanges = false;
        try {
            hasUncommittedChanges = !repo.getModifiedFiles().isEmpty();
        } catch (GitAPIException e) {
            logger.debug("Unable to determine uncommitted changes state", e);
        }

        boolean isSession = baselineMode == BaselineMode.SESSION;

        SwingUtil.applyPrimaryButtonStyle(commitBtn);
        for (var al : commitBtn.getActionListeners()) commitBtn.removeActionListener(al);
        commitBtn.addActionListener(e -> showCommitDialog());
        commitBtn.setVisible(!isSession && hasUncommittedChanges);
        buttonPanel.add(commitBtn);

        SwingUtil.applyPrimaryButtonStyle(guidedReviewBtn);
        guidedReviewBtn.setVisible(true);
        guidedReviewBtn.setIdleAction(this::generateGuidedReview);
        guidedReviewBtn.setCancelAction(() -> contextManager.interruptLlmAction());

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
        for (var al : pullBtn.getActionListeners()) pullBtn.removeActionListener(al);
        pullBtn.addActionListener(e -> performPull());
        pullBtn.setVisible(!isSession);
        buttonPanel.add(pullBtn);

        pushBtn.setEnabled(!hasUncommittedChanges);
        for (var al : pushBtn.getActionListeners()) pushBtn.removeActionListener(al);
        pushBtn.addActionListener(e -> performPush());
        pushBtn.setVisible(!isSession && pushPull != null && pushPull.canPush());
        buttonPanel.add(pushBtn);

        boolean showPR = !isSession
                && (baselineMode == BaselineMode.NON_DEFAULT_BRANCH
                        || (baselineMode == BaselineMode.DEFAULT_WITH_UPSTREAM && res.filesChanged() > 0));

        boolean prBtnEnabled = !hasUncommittedChanges;
        String prBtnTooltip = null;
        if (prBtnEnabled && showPR) {
            // Check if a PR already exists for this branch
            try {
                String currentBranch = repo.getCurrentBranch();
                if (GitHubAuth.getOrCreateInstance(contextManager.getProject())
                        .hasOpenPullRequestForBranch(currentBranch)) {
                    prBtnEnabled = false;
                    prBtnTooltip = "A pull request already exists for branch " + currentBranch;
                }
            } catch (Exception e) {
                logger.debug("Could not check for existing PRs: {}", e.getMessage());
                // Continue - don't block PR creation just because we couldn't check
            }
        }
        prBtn.setEnabled(prBtnEnabled);
        if (prBtnTooltip != null) {
            prBtn.setToolTipText(prBtnTooltip);
        }
        for (var al : prBtn.getActionListeners()) prBtn.removeActionListener(al);
        prBtn.addActionListener(e -> CreatePullRequestDialog.show(chrome.getFrame(), chrome, contextManager));
        prBtn.setVisible(showPR);
        buttonPanel.add(prBtn);

        buttonPanel.add(guidedReviewBtn);

        headerPanel.add(buttonPanel, BorderLayout.EAST);

        var topContainer = new JPanel(new BorderLayout(0, 6));
        topContainer.setOpaque(false);
        topContainer.add(headerPanel, BorderLayout.NORTH);

        String projectName = "Project";
        var root = contextManager.getProject().getRoot();
        if (root.getFileName() != null) projectName = root.getFileName().toString();

        this.fileComparisons = prepared.stream().map(this::toFileComparisonInfo).toList();

        diffContainer = new JPanel(new BorderLayout());
        diffContainer.setOpaque(false);

        var dummyPanel = new ai.brokk.difftool.ui.BrokkDiffPanel(
                new ai.brokk.difftool.ui.BrokkDiffPanel.Builder(chrome.getTheme(), contextManager), chrome.getTheme());

        this.diffCore =
                new ai.brokk.difftool.ui.DiffDisplayCore(
                        dummyPanel, contextManager, chrome.getTheme(), fileComparisons, false, 0) {
                    @Override
                    protected ai.brokk.difftool.ui.AbstractDiffPanel createPanel(
                            int index, ai.brokk.difftool.node.JMDiffNode diffNode) {
                        var panel = new ai.brokk.difftool.ui.unified.UnifiedDiffPanel(
                                dummyPanel, chrome.getTheme(), diffNode);
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
                    }
                };

        dummyPanel.setBufferDiffPanel(null);

        // Update existing fileTreePanel instead of replacing
        fileTreePanel.removeAll();
        var newTreePanel = new ai.brokk.difftool.ui.FileTreePanel(fileComparisons, root, projectName);
        fileTreePanel.setLayout(new BorderLayout());
        fileTreePanel.add(newTreePanel, BorderLayout.CENTER);

        newTreePanel.setSelectionListener(new DiffProjectFileNavigationTarget() {
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
        newTreePanel.initializeTree();

        codeReviewPanel.addReviewNavigationListener(ce -> {
            newTreePanel.selectFileQuietly(ce.file());
            activeExcerpt = ce;
            diffCore.showLocation(ce.file(), ce.line(), ce.side());
        });

        detailScrollPane = new JScrollPane(codeReviewPanel.getDetailPanel());
        detailScrollPane.setBorder(null);

        rightVerticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, detailScrollPane, diffContainer);
        rightVerticalSplitPane.setResizeWeight(0.5);

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplitPane, rightVerticalSplitPane);
        mainSplitPane.setDividerLocation(300);

        updateReviewPanelVisibility(hasGeneratedReview);

        topContainer.add(mainSplitPane, BorderLayout.CENTER);

        setBorder(new CompoundBorder(
                new LineBorder(UIManager.getColor("Separator.foreground"), 1), new EmptyBorder(6, 6, 6, 6)));
        add(topContainer, BorderLayout.CENTER);

        this.diffCore.showFile(0);
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

        int commitsBehind = repo.countCommitsSince(state.commitHash());
        int uncommittedChanges = 0;
        try {
            uncommittedChanges = repo.getModifiedFiles().size();
        } catch (GitAPIException e) {
            logger.debug("Failed to get modified files for staleness check", e);
        }

        return new StalenessInfo(commitsBehind, uncommittedChanges);
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

            if (detailScrollPane != null) {
                detailScrollPane.setVisible(visible);
            }

            if (rightVerticalSplitPane != null) {
                rightVerticalSplitPane.setDividerSize(visible ? defaultSplitPaneDividerSize() : 0);
                if (!visible) {
                    rightVerticalSplitPane.setDividerLocation(0);
                } else {
                    rightVerticalSplitPane.setResizeWeight(0.5);
                    rightVerticalSplitPane.setDividerLocation(0.5);
                }
            }

            if (mainSplitPane != null) {
                mainSplitPane.setDividerSize(defaultSplitPaneDividerSize());
                mainSplitPane.setResizeWeight(0.0);
                mainSplitPane.setDividerLocation(visible ? 300 : 200);
            }

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

                var agent = new ReviewAgent(changes, contextManager, chrome, fileComparisons);

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
