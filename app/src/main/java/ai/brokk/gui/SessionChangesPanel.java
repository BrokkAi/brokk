package ai.brokk.gui;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.agents.ReviewAgent;
import ai.brokk.agents.ReviewGenerationException;
import ai.brokk.agents.ReviewScope;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.context.ContextHistory;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.node.JMDiffNode;
import ai.brokk.difftool.ui.AbstractContentPanel;
import ai.brokk.difftool.ui.AbstractDiffPanel;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.ui.DiffDisplayCore;
import ai.brokk.difftool.ui.DiffProjectFileNavigationTarget;
import ai.brokk.difftool.ui.DiffToolbarCallbacks;
import ai.brokk.difftool.ui.DiffToolbarPanel;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.difftool.ui.FileTreePanel;
import ai.brokk.difftool.ui.ToolbarFeature;
import ai.brokk.difftool.ui.unified.UnifiedDiffDocument;
import ai.brokk.difftool.ui.unified.UnifiedDiffPanel;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoData.FileDiff;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.MaterialProgressButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.dialogs.CreatePullRequestDialog;
import ai.brokk.gui.git.GitCommitTab;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.util.GlobalUiSettings;
import ai.brokk.util.ReviewParser;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * A panel that displays an aggregated diff of changes for the current session/branch.
 */
public class SessionChangesPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SessionChangesPanel.class);
    private final Chrome chrome;
    private final ContextManager cm;
    private final GitRepo repo;
    private final DeferredUpdateHelper deferredUpdateHelper;
    private final TabTitleUpdater tabTitleUpdater;

    @Nullable
    private DiffService.CumulativeChanges lastCumulativeChanges = null;

    /** Monotonically increasing token to track the latest requestUpdate invocation. */
    private final AtomicLong updateGeneration = new AtomicLong(0);

    public record ReviewState(@Nullable String commitHash, long generatedAtMillis) {}

    public record StalenessInfo(
            int commitsBehind, int uncommittedChanges, boolean differentBranch, boolean unknownCommit) {}

    @Nullable
    private ReviewState lastReviewState = null;

    @Nullable
    private String lastBaselineLabel = null;

    @Nullable
    private BaselineMode lastBaselineMode = null;

    private final DiffDisplayCore diffCore;

    /** If non-null, an explicit commit/ref to compare against. If null, we auto-resolve based on branch. */
    @Nullable
    private String reviewBaselineRef = null;

    private final FileTreePanel fileTreePanel;

    private final CodeReviewPanel codeReviewPanel;

    private final JSplitPane leftSplitPane;

    private final JPanel diffContainer;
    private final DiffToolbarPanel diffToolbar;

    private final JComboBox<String> baselineDropdown;

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
        this.cm = contextManager;
        this.tabTitleUpdater = tabTitleUpdater;

        var maybeRepo = contextManager.getProject().getRepo();
        if (!(maybeRepo instanceof GitRepo gr)) {
            throw new IllegalStateException("SessionChangesPanel requires a GitRepo");
        }
        this.repo = gr;

        setOpaque(false);
        setBorder(new CompoundBorder(
                new LineBorder(UIManager.getColor("Separator.foreground"), 1), new EmptyBorder(6, 6, 6, 6)));

        this.baselineDropdown = new JComboBox<>();
        this.baselineDropdown.setOpaque(false);
        this.baselineDropdown.addActionListener(e -> {
            if (reviewBaselineRef != null && baselineDropdown.getSelectedIndex() == 1) {
                clearReviewBaseline();
            }
        });

        this.commitBtn = createIconButton(Icons.COMMIT, "Changes to Commit");

        this.pullBtn = createIconButton(Icons.DOWNLOAD, "Pull changes from the remote repository");
        this.pushBtn = createIconButton(Icons.PUBLISH, "Push your commits to the remote repository");
        this.prBtn = createIconButton(Icons.ADD_DIAMOND, "Create a pull request for the current branch");
        this.guidedReviewBtn = new MaterialProgressButton("Guided Review", chrome);
        this.guidedReviewBtn.setToolTipText("Generate an AI-powered code review for the current changes");

        this.pasteBtn = createIconButton(Icons.CONTENT_CAPTURE, "Paste a code review from the clipboard (JSON format)");
        this.diffContainer = new JPanel(new BorderLayout());
        this.diffContainer.setOpaque(false);

        // Create toolbar with navigation and font controls only (no view mode toggle or tools menu)
        var reviewFeatures = java.util.EnumSet.of(
                ToolbarFeature.CHANGE_NAVIGATION, ToolbarFeature.FILE_NAVIGATION, ToolbarFeature.FONT_CONTROLS);
        this.diffToolbar = new DiffToolbarPanel(reviewFeatures, createToolbarCallbacks());

        // Wrap diffContainer with toolbar at top
        var diffWithToolbar = new JPanel(new BorderLayout());
        diffWithToolbar.setOpaque(false);
        diffWithToolbar.add(diffToolbar, BorderLayout.NORTH);
        diffWithToolbar.add(diffContainer, BorderLayout.CENTER);

        this.codeReviewPanel = new CodeReviewPanel(this::generateGuidedReview, contextManager);
        this.fileTreePanel =
                new FileTreePanel(List.of(), contextManager.getProject().getRoot());

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

        var dummyPanel =
                new BrokkDiffPanel(new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager), chrome.getTheme());
        // Initialize font from GlobalUiSettings so UnifiedEditorArea.hasExplicitFontSize() returns true
        float savedFontSize = GlobalUiSettings.getEditorFontSize();
        if (savedFontSize > 0f) {
            int fontIdx = dummyPanel.findClosestFontIndex(savedFontSize);
            dummyPanel.setCurrentFontIndex(fontIdx);
        }
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

        SwingUtil.applyPrimaryButtonStyle(guidedReviewBtn);
        guidedReviewBtn.setIdleAction(this::generateGuidedReview);
        guidedReviewBtn.setCancelAction(() -> contextManager.interruptLlmAction());
    }

    private MaterialButton createIconButton(Icon icon, @Nullable String tooltip) {
        var btn = new MaterialButton("") {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(24, 24);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(24, 24);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(24, 24);
            }
        };
        btn.setIcon(icon);
        if (tooltip != null) {
            btn.setToolTipText(tooltip);
        }
        return btn;
    }

    private DiffDisplayCore createDiffCore(BrokkDiffPanel dummyPanel) {
        return new DiffDisplayCore(dummyPanel, cm, chrome.getTheme(), fileComparisons, false, 0) {
            @Override
            protected AbstractDiffPanel createPanel(int index, JMDiffNode diffNode) {
                // Review panel always uses unified view with full context
                var panel = new UnifiedDiffPanel(dummyPanel, chrome.getTheme(), diffNode);
                panel.setContextMode(UnifiedDiffDocument.ContextMode.FULL_CONTEXT);
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
                    int index, AbstractDiffPanel panel, int targetLine, ReviewParser.DiffSide targetSide) {
                diffContainer.removeAll();
                diffContainer.add(panel.getComponent(), BorderLayout.CENTER);

                // Apply saved font size to the panel
                applyFontSizeToPanel(panel);

                if (targetLine > 0) {
                    panel.resetAutoScrollFlag();
                    panel.diff(false);
                    if (panel instanceof BufferDiffPanel bp) {
                        var side = (targetSide == ReviewParser.DiffSide.OLD)
                                ? BufferDiffPanel.PanelSide.LEFT
                                : BufferDiffPanel.PanelSide.RIGHT;
                        bp.scrollToLine(targetLine, side);
                    } else if (panel instanceof UnifiedDiffPanel up) {
                        up.clearExcerptHighlight();
                        up.scrollToLine(targetLine, targetSide);
                        if (activeExcerpt != null) {
                            String[] lines = activeExcerpt.excerpt().split("\\r?\\n", -1);
                            int endLine = targetLine + Math.max(0, lines.length - 1);
                            up.highlightExcerptLines(targetLine, endLine, targetSide);
                        }
                    }
                } else {
                    if (panel instanceof UnifiedDiffPanel up) {
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
        // Capture this generation so we can ignore stale results
        final long thisGeneration = updateGeneration.incrementAndGet();

        // Immediately show placeholder (file count will be computed async)
        SwingUtilities.invokeLater(() -> {
            tabTitleUpdater.updateTitleAndTooltip("Review (...)", "Computing branch-based changes...");
        });

        // Kick off async computation
        LoggingFuture.supplyAsync(() -> {
                    var state = resolveBaselineState();
                    var reviewCtx = ReviewScope.fromBaseline(cm, state.resolvedLeftRef());
                    return new ComputedUpdate(state, reviewCtx);
                })
                .thenAccept(computed -> {
                    // Check if this computation is still relevant
                    if (thisGeneration != updateGeneration.get()) {
                        return; // A newer requestUpdate superseded us
                    }

                    lastBaselineLabel = computed.state.baselineLabel();
                    lastBaselineMode = computed.state.baselineMode();
                    lastCumulativeChanges = computed.ctx.changes();

                    // Update title on EDT
                    SwingUtilities.invokeLater(() -> {
                        if (thisGeneration != updateGeneration.get()) return;
                        updateTitleAndTooltipFromResult(computed.ctx.changes(), computed.state.baselineLabel());
                    });

                    // Trigger deferred UI update
                    deferredUpdateHelper.requestUpdate();
                })
                .exceptionally(ex -> {
                    if (thisGeneration != updateGeneration.get()) return null;
                    logger.warn("Failed to compute cumulative changes", ex);
                    SwingUtilities.invokeLater(() -> {
                        if (thisGeneration != updateGeneration.get()) return;
                        tabTitleUpdater.updateTitleAndTooltip("Review", "Failed to compute changes");
                    });
                    return null;
                });
    }

    private record ComputedUpdate(BaselineState state, ReviewScope ctx) {}

    private record BaselineState(BaselineMode baselineMode, String baselineLabel, String resolvedLeftRef) {}

    private @Nullable String resolvedBaselineBranch = null;

    private BaselineState resolveBaselineState() {
        if (reviewBaselineRef != null) {
            return new BaselineState(BaselineMode.COMMIT_RANGE, reviewBaselineRef, reviewBaselineRef);
        }

        try {
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

            String label = baseline;
            BaselineMode mode = BaselineMode.BRANCH_BASELINE;
            if (currentBranch.equals(defaultBranch)) {
                if (hasUpstream && upstreamRefCandidate != null) {
                    label = upstreamRefCandidate;
                } else {
                    label = "HEAD";
                }
            }

            String resolvedRef = label;
            if (!"HEAD".equals(label)) {
                resolvedRef =
                        requireNonNull(repo.getMergeBase("HEAD", label), "Merge base cannot be null for " + label);
            }

            return new BaselineState(mode, label, resolvedRef);
        } catch (Exception e) {
            logger.warn("Failed to compute baseline for changes", e);
            String errorLabel = e.getMessage();
            return new BaselineState(
                    BaselineMode.NO_BASELINE, errorLabel != null ? errorLabel : "Unknown Error", "HEAD");
        }
    }

    /**
     * Called by DeferredUpdateHelper when the panel becomes visible (or immediately if already visible).
     * This handles only the GUI update using the cached lastCumulativeChanges.
     */
    private void performRefresh() {
        assert SwingUtilities.isEventDispatchThread();

        var result = lastCumulativeChanges;
        if (result == null) {
            // No cached result yet; nothing to display
            return;
        }

        var prepared = DiffService.preparePerFileSummaries(result);

        StalenessInfo staleness = null;
        if (lastReviewState != null) {
            staleness = computeStaleness();
        }

        String label = lastBaselineLabel != null ? lastBaselineLabel : "";
        BaselineMode mode = lastBaselineMode != null ? lastBaselineMode : BaselineMode.NO_BASELINE;

        updateTitleAndTooltipFromResult(result, label);
        updateContent(result, prepared, label, mode);

        if (staleness != null) {
            codeReviewPanel.getListPanel().setStalenessNotice(formatStalenessMessage(staleness));
        }
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
            List<Map.Entry<String, FileDiff>> prepared,
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

    // TODO migrate BrokkDiffPanel to FileDiff
    private FileComparisonInfo toFileComparisonInfo(Map.Entry<String, FileDiff> entry) {
        ProjectFile pf = null;
        try {
            pf = cm.toFile(entry.getKey());
        } catch (Exception e) {
            logger.debug("Failed to resolve ProjectFile for {}", entry.getKey(), e);
        }
        return new FileComparisonInfo(
                pf,
                new BufferSource.StringSource(entry.getValue().oldText(), "", entry.getKey(), null),
                new BufferSource.StringSource(entry.getValue().newText(), "", entry.getKey(), null));
    }

    private void updateDropdownLabels() {
        String branchLabel = resolvedBaselineBranch != null ? resolvedBaselineBranch : "branch";

        // Remove action listeners temporarily to avoid triggering updates while rebuilding
        var listeners = baselineDropdown.getActionListeners();
        for (var al : listeners) {
            baselineDropdown.removeActionListener(al);
        }

        baselineDropdown.removeAllItems();
        if (reviewBaselineRef != null) {
            baselineDropdown.addItem("Changes from " + repo.shortHash(reviewBaselineRef));
            baselineDropdown.addItem("Reset to " + branchLabel);
            baselineDropdown.setSelectedIndex(0);
        } else {
            baselineDropdown.addItem("Changes vs " + branchLabel);
            baselineDropdown.setSelectedIndex(0);
        }

        // Re-add action listeners
        for (var al : listeners) {
            baselineDropdown.addActionListener(al);
        }
    }

    private void refreshUI(
            DiffService.CumulativeChanges res,
            List<Map.Entry<String, FileDiff>> prepared,
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

        commitBtn.setVisible(hasUncommittedChanges);

        guidedReviewBtn.setVisible(true);

        var pushPull = res.pushPullState();
        boolean canPull = pushPull != null && pushPull.canPull();
        pullBtn.setEnabled(canPull && !hasUncommittedChanges);
        if (hasUncommittedChanges) {
            pullBtn.setToolTipText(
                    "Commit or stash your uncommitted changes before pulling from the remote repository");
        } else if (!canPull) {
            pullBtn.setToolTipText(
                    pushPull != null && pushPull.hasUpstream()
                            ? "Already up to date - no new changes to pull from the remote repository"
                            : "No upstream branch configured - set up remote tracking to enable pulling");
        } else {
            pullBtn.setToolTipText("Pull the latest changes from the remote repository into your local branch");
        }
        pullBtn.setVisible(true);

        pushBtn.setEnabled(!hasUncommittedChanges);
        pushBtn.setToolTipText(
                hasUncommittedChanges
                        ? "Commit your uncommitted changes before pushing to the remote repository"
                        : "Push your local commits to the remote repository");
        pushBtn.setVisible(pushPull != null && pushPull.canPush());

        boolean isDefaultBranch = false;
        try {
            isDefaultBranch = repo.getCurrentBranch().equals(repo.getDefaultBranch());
        } catch (Exception e) {
            logger.debug("Failed to check if current branch is default branch", e);
        }

        boolean showPR = (baselineMode == BaselineMode.BRANCH_BASELINE && !isDefaultBranch)
                || (baselineMode == BaselineMode.BRANCH_BASELINE && isDefaultBranch && res.filesChanged() > 0);

        boolean prBtnEnabled = !hasUncommittedChanges;
        String prBtnTooltip = hasUncommittedChanges
                ? "Commit your uncommitted changes before creating a pull request"
                : "Create a pull request to merge the current branch into the default branch";
        if (prBtnEnabled && showPR) {
            try {
                String currentBranch = repo.getCurrentBranch();
                if (GitHubAuth.getOrCreateInstance(cm.getProject()).hasOpenPullRequestForBranch(currentBranch)) {
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
        var root = cm.getProject().getRoot();
        if (root.getFileName() != null) projectName = root.getFileName().toString();

        List<FileComparisonInfo> nextComparisons =
                prepared.stream().map(this::toFileComparisonInfo).toList();

        // If the file set changed significantly and we aren't showing a review, clear the excerpt
        if (!nextComparisons.equals(this.fileComparisons)) {
            activeExcerpt = null;
        }

        this.fileComparisons = nextComparisons;
        this.diffCore.updateFileComparisons(this.fileComparisons, 0);
        diffToolbar.updateButtonStates();

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
        var content = new GitCommitTab(chrome, cm);
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
        cm.submitExclusiveAction(() -> {
            try {
                String branch = repo.getCurrentBranch();
                chrome.showOutputSpinner("Pulling " + branch + "...");
                var result = new GitWorkflow(cm).pull(branch);
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    if (result.isEmpty()) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Pull completed successfully.");
                    } else {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Pull: " + result);
                    }
                });
                CompletableFuture.runAsync(chrome::updateGitRepo);
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
        cm.submitExclusiveAction(() -> {
            try {
                String branch = repo.getCurrentBranch();
                chrome.showOutputSpinner("Pushing " + branch + "...");
                var result = new GitWorkflow(cm).push(branch);
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    if (result.isEmpty()) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Push completed successfully.");
                    } else {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Push: " + result);
                    }
                    requestUpdate();
                });
                CompletableFuture.runAsync(chrome::updateGitRepo);
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

    @Blocking
    private void ensureReviewSession(ReviewScope reviewCtx) {
        // Check if any overlapping session is already active
        UUID currentId = cm.getCurrentSessionId();
        if (reviewCtx.metadata().sessionIds().contains(currentId)) {
            return;
        }

        // if the commits aren't in current session, create a new one.
        Set<String> currentSessionCommits = cm.getContextHistory().getGitStates().values().stream()
                .map(ContextHistory.GitState::commitHash)
                .collect(Collectors.toSet());
        List<String> reviewCommits =
                reviewCtx.changes().commits().stream().map(CommitInfo::id).toList();

        if (!currentSessionCommits.containsAll(reviewCommits) && !reviewCommits.isEmpty()) {
            // Create a new session for this review
            String branchName = null;
            try {
                branchName = repo.getCurrentBranch();
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
            String time = LocalTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"));
            String sessionName = "Review " + branchName + " " + time;
            cm.createSessionAsync(sessionName).join();
        }
    }

    /**
     * Generates a guided review using the currently cached cumulative changes.
     * Called by the Guided Review button when there's already data available.
     */
    private void generateGuidedReview() {
        if (lastCumulativeChanges == null || lastBaselineLabel == null) return;

        ensureCleanThenReview(() -> {
            setGuidedReviewBusy(true);
            codeReviewPanel.setBusy(true);
            guidedReviewBtn.setProgress(0);

            // We use the same resolution logic as requestUpdate to get the correct resolved ref
            LoggingFuture.supplyAsync(() -> {
                        var state = resolveBaselineState();
                        return ReviewScope.fromBaseline(cm, state.resolvedLeftRef());
                    })
                    .thenAccept(this::generateGuidedReviewAsync);
        });
    }

    /**
     * Shows a dialog if the working tree is dirty, offering to commit first or cancel.
     * If the tree is clean, the action runs immediately.
     */
    private void ensureCleanThenReview(Runnable action) {
        assert SwingUtilities.isEventDispatchThread();

        // Use chrome.getModifiedFiles() which returns Set<ProjectFile>
        var dirtyFiles = chrome.getModifiedFiles();

        if (dirtyFiles.isEmpty()) {
            action.run();
            return;
        }

        var owner = SwingUtilities.getWindowAncestor(this);
        var dialog = new BaseThemedDialog(owner, "Uncommitted Changes", Dialog.ModalityType.APPLICATION_MODAL);

        var content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        String message =
                "Your working tree has %d uncommitted change(s).\nWould you like to commit before starting the review?"
                        .formatted(dirtyFiles.size());
        var msgArea = new JTextArea(message);
        msgArea.setEditable(false);
        msgArea.setOpaque(false);
        msgArea.setLineWrap(true);
        msgArea.setWrapStyleWord(true);
        content.add(msgArea, BorderLayout.CENTER);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var commitFirstBtn = new MaterialButton("Commit First");
        SwingUtil.applyPrimaryButtonStyle(commitFirstBtn);
        var cancelBtn = new MaterialButton("Cancel");
        buttons.add(commitFirstBtn);
        buttons.add(cancelBtn);
        content.add(buttons, BorderLayout.SOUTH);

        final int[] choice = new int[] {-1}; // 0 = commit, 1 = cancel
        commitFirstBtn.addActionListener(e -> {
            choice[0] = 0;
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> {
            choice[0] = 1;
            dialog.dispose();
        });

        dialog.getContentRoot().add(content);
        dialog.getRootPane().setDefaultButton(commitFirstBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);

        if (choice[0] == 0) {
            // Commit first, then proceed on success
            var commitDialog = new CommitDialog(
                    chrome.getFrame(), chrome, cm, dirtyFiles, commitResult -> SwingUtilities.invokeLater(action));
            commitDialog.setVisible(true);
        }
        // choice[0] == 1 or -1 (dialog closed): do nothing (cancel)
    }

    /**
     * Core review generation logic that accepts explicit parameters.
     * This allows callers to provide their own computed data rather than relying on cached state.
     */
    private void generateGuidedReviewAsync(ReviewScope scope) {
        LoggingFuture.supplyAsync(() -> {
            // these are broken out separately to avoid deadlock (they both want to run on the exclusive UAM thread)
            ensureReviewSession(scope);
            generateGuidedReviewInternal(scope);
        });
    }

    private void generateGuidedReviewInternal(ReviewScope scope) {
        cm.submitLlmAction(() -> {
            try {
                var agent = new ReviewAgent(scope, cm.getProject().getModelConfig(ModelType.ARCHITECT), true, cm);

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
     */
    public void loadExternalReviewAsync(String markdown, Context context) {
        LoggingFuture.supplyAsync(() -> {
            ReviewScope scope;
            try {
                scope = ReviewScope.fromContext(cm, context);
            } catch (ReviewScope.ReviewLoadException e) {
                logger.warn("Failed to load review scope from context: {}", e.getMessage());
                cm.getIo().toolError("Unable to load review: " + e.getMessage());
                return;
            }

            var resolvedExcerpts = ReviewParser.resolveExcerptsNewOnly(cm, markdown);
            var review = ReviewParser.instance.parseMarkdownReview(markdown, resolvedExcerpts);

            var hash = cm.getContextHistory()
                    .getGitState(context.id())
                    .map(ContextHistory.GitState::commitHash)
                    .orElse(null);

            SwingUtilities.invokeLater(() -> {
                lastCumulativeChanges = scope.changes();
                lastBaselineLabel = scope.metadata().fromRef();
                lastBaselineMode = BaselineMode.BRANCH_BASELINE;

                lastReviewState = new ReviewState(hash, System.currentTimeMillis());
                hasGeneratedReview = true;

                // Re-trigger UI refresh to show panels and handle card layout
                deferredUpdateHelper.requestUpdate();

                codeReviewPanel.displayReview(review, context);
                codeReviewPanel
                        .getListPanel()
                        .setStalenessNotice(formatStalenessMessage(requireNonNull(computeStaleness())));
            });
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
            logger.warn("Failed to get clipboard data", e);
            return;
        }

        if (clipboardData == null || clipboardData.isBlank()) {
            return;
        }

        LoggingFuture.supplyAsync(() -> {
                    var resolvedExcerpts = ReviewParser.resolveExcerptsNewOnly(cm, clipboardData);
                    return ReviewParser.instance.parseMarkdownReview(clipboardData, resolvedExcerpts);
                })
                .thenAccept(guidedReview -> SwingUtilities.invokeLater(() -> {
                    hasGeneratedReview = true;
                    deferredUpdateHelper.requestUpdate();
                    codeReviewPanel.displayReview(guidedReview, Context.EMPTY);
                    codeReviewPanel.getListPanel().setStalenessNotice(null);
                }))
                .exceptionally(ex -> {
                    logger.warn("Failed to parse pasted review", ex);
                    SwingUtilities.invokeLater(() -> chrome.toolError("Failed to parse review: " + ex.getMessage()));
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

    /** Apply current font size to a panel from GlobalUiSettings */
    private void applyFontSizeToPanel(AbstractDiffPanel panel) {
        float saved = GlobalUiSettings.getEditorFontSize();
        if (saved > 0f) {
            panel.applyEditorFontSize(saved);
        }
    }

    @Nullable
    private AbstractContentPanel getCurrentContentPanel() {
        return diffCore.getCachedPanel(diffCore.getCurrentIndex());
    }

    private DiffToolbarCallbacks createToolbarCallbacks() {
        return new SessionChangesToolbarCallbacks(this);
    }

    private static final class SessionChangesToolbarCallbacks implements DiffToolbarCallbacks {
        private final SessionChangesPanel panel;

        SessionChangesToolbarCallbacks(SessionChangesPanel panel) {
            this.panel = panel;
        }

        @Override
        public void ensureFontIndexInitialized() {
            // No local state to initialize - GlobalUiSettings is the source of truth
        }

        @Override
        public void navigateToNextChange() {
            assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
            var contentPanel = panel.getCurrentContentPanel();
            if (contentPanel != null) {
                if (contentPanel.isAtLastLogicalChange() && canNavigateToNextFile()) {
                    nextFile();
                } else {
                    contentPanel.doDown();
                    panel.diffToolbar.updateButtonStates();
                }
            }
        }

        @Override
        public void navigateToPreviousChange() {
            assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
            var contentPanel = panel.getCurrentContentPanel();
            if (contentPanel != null) {
                if (contentPanel.isAtFirstLogicalChange() && canNavigateToPreviousFile()) {
                    previousFile();
                    var newPanel = panel.getCurrentContentPanel();
                    if (newPanel != null) {
                        newPanel.goToLastLogicalChange();
                    }
                } else {
                    contentPanel.doUp();
                    panel.diffToolbar.updateButtonStates();
                }
            }
        }

        @Override
        public void nextFile() {
            assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
            int current = panel.diffCore.getCurrentIndex();
            if (current < panel.fileComparisons.size() - 1) {
                panel.diffCore.showFile(current + 1);
                panel.diffToolbar.updateButtonStates();
            }
        }

        @Override
        public void previousFile() {
            assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
            int current = panel.diffCore.getCurrentIndex();
            if (current > 0) {
                panel.diffCore.showFile(current - 1);
                panel.diffToolbar.updateButtonStates();
            }
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
        public boolean canNavigateToNextChange() {
            var contentPanel = panel.getCurrentContentPanel();
            if (contentPanel == null) return false;
            return !contentPanel.isAtLastLogicalChange() || canNavigateToNextFile();
        }

        @Override
        public boolean canNavigateToPreviousChange() {
            var contentPanel = panel.getCurrentContentPanel();
            if (contentPanel == null) return false;
            return !contentPanel.isAtFirstLogicalChange() || canNavigateToPreviousFile();
        }

        @Override
        public boolean canNavigateToNextFile() {
            return panel.fileComparisons.size() > 1
                    && panel.diffCore.getCurrentIndex() < panel.fileComparisons.size() - 1;
        }

        @Override
        public boolean canNavigateToPreviousFile() {
            return panel.fileComparisons.size() > 1 && panel.diffCore.getCurrentIndex() > 0;
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
            return panel.fileComparisons.size() > 1;
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
            float saved = GlobalUiSettings.getEditorFontSize();
            return saved > 0f ? findClosestFontIndex(saved) : DEFAULT_FONT_INDEX;
        }

        @Override
        public void setCurrentFontIndex(int index) {
            // State is persisted via GlobalUiSettings in font change methods
            // No local state to update
        }

        @Override
        public void increaseEditorFont() {
            assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
            int currentIdx = getCurrentFontIndex();
            if (currentIdx >= FONT_SIZES.size() - 1) return;
            float newSize = FONT_SIZES.get(currentIdx + 1);
            GlobalUiSettings.saveEditorFontSize(newSize);
            applyFontSizeToAllPanels();
        }

        @Override
        public void decreaseEditorFont() {
            assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
            int currentIdx = getCurrentFontIndex();
            if (currentIdx <= 0) return;
            float newSize = FONT_SIZES.get(currentIdx - 1);
            GlobalUiSettings.saveEditorFontSize(newSize);
            applyFontSizeToAllPanels();
        }

        @Override
        public void resetEditorFont() {
            assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
            float newSize = FONT_SIZES.get(DEFAULT_FONT_INDEX);
            GlobalUiSettings.saveEditorFontSize(newSize);
            applyFontSizeToAllPanels();
        }

        private void applyFontSizeToAllPanels() {
            float fontSize = GlobalUiSettings.getEditorFontSize();
            if (fontSize <= 0f) return;
            for (var diffPanel : panel.diffCore.getCachedPanels()) {
                diffPanel.applyEditorFontSize(fontSize);
            }
        }
    }
    /**
     * Starts a review for a specific commit range, computing the diff and file comparisons
     * independently of the UI refresh cycle to avoid race conditions.
     */
    public void startCommitRangeReview(String oldestCommitId) {
        assert SwingUtilities.isEventDispatchThread();

        ensureCleanThenReview(() -> {
            var parentId = oldestCommitId + "^";
            this.reviewBaselineRef = parentId;

            // Update dropdown and trigger refresh for the new baseline
            requestUpdate();

            // Set busy state immediately
            setGuidedReviewBusy(true);
            codeReviewPanel.setBusy(true);
            guidedReviewBtn.setProgress(0);

            // Run the review with the explicitly computed data
            LoggingFuture.supplyCallableAsync(() -> ReviewScope.fromBaseline(cm, parentId))
                    .thenAccept(this::generateGuidedReviewAsync)
                    .exceptionally(ex -> {
                        logger.error("Failed to prepare commit range review", ex);
                        SwingUtilities.invokeLater(() -> {
                            codeReviewPanel.setBusy(false);
                            setGuidedReviewBusy(false);
                            chrome.toolError("Failed to prepare review: " + ex.getMessage());
                        });
                        return null;
                    });
        });
    }

    public void clearReviewBaseline() {
        SwingUtil.runOnEdt(() -> {
            this.reviewBaselineRef = null;
            requestUpdate();
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
        BRANCH_BASELINE,
        COMMIT_RANGE,
        NO_BASELINE
    }
}
