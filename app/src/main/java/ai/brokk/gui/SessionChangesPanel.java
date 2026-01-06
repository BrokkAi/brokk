package ai.brokk.gui;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.agents.ReviewAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.ui.DiffProjectFileNavigationTarget;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.dialogs.CreatePullRequestDialog;
import ai.brokk.gui.dialogs.TextAreaConsoleIO;
import ai.brokk.gui.git.GitCommitTab;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.ReviewParser;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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

    @Nullable
    private String lastBaselineLabel = null;

    @Nullable
    private BaselineMode lastBaselineMode = null;

    @Nullable
    private ai.brokk.difftool.ui.DiffDisplayCore diffCore;

    @Nullable
    private ai.brokk.difftool.ui.FileTreePanel fileTreePanel;

    @Nullable
    private CodeReviewPanel codeReviewPanel;

    @Nullable
    private JSplitPane leftSplitPane;

    @Nullable
    private JPanel diffContainer;

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
            // parent should show a placeholder instead
            throw new IllegalStateException("SessionChangesPanel requires a GitRepo");
        }
        this.repo = gr;

        setOpaque(false);
        this.deferredUpdateHelper = new DeferredUpdateHelper(this, this::performRefresh);
    }

    /**
     * Requests a full refresh of the diff content. This is deferred if the panel is not showing.
     */
    public void requestUpdate() {
        deferredUpdateHelper.requestUpdate();
    }

    /**
     * Refreshes just the tab title and tooltip based on current Git state.
     * This runs asynchronously to avoid blocking the EDT with Git operations.
     */
    public void refreshTitleAsync() {
        SwingUtilities.invokeLater(() -> {
            tabTitleUpdater.updateTitleAndTooltip("Review (...)", "Computing branch-based changes...");
        });

        contextManager
                .submitBackgroundTask("Refreshing review title", () -> {
                    var state = resolveBaselineState();
                    if (state == null) return null;

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

    private @Nullable BaselineState resolveBaselineState() {
        try {
            String defaultBranch = repo.getDefaultBranch();
            String currentBranch = repo.getCurrentBranch();

            boolean isDetached = !repo.listLocalBranches().contains(currentBranch);

            if (isDetached) {
                return new BaselineState(BaselineMode.DETACHED, "detached HEAD");
            }

            if (!currentBranch.equals(defaultBranch)) {
                return new BaselineState(BaselineMode.NON_DEFAULT_BRANCH, defaultBranch);
            }

            var remoteBranches = repo.listRemoteBranches();
            String upstreamRef = "origin/" + defaultBranch;
            if (remoteBranches.contains(upstreamRef)) {
                return new BaselineState(BaselineMode.DEFAULT_WITH_UPSTREAM, upstreamRef);
            }

            return new BaselineState(BaselineMode.DEFAULT_LOCAL_ONLY, "HEAD");
        } catch (GitAPIException e) {
            logger.warn("Failed to compute baseline for changes", e);
            return new BaselineState(BaselineMode.NO_BASELINE, "Error: " + e.getMessage());
        }
    }

    private void performRefresh() {
        var state = resolveBaselineState();
        if (state == null) return;

        lastBaselineLabel = state.baselineLabel();
        lastBaselineMode = state.baselineMode();

        // Set loading state
        tabTitleUpdater.updateTitleAndTooltip("Review (...)", "Computing branch-based changes...");

        refreshCumulativeChangesAsync(state.baselineLabel(), state.baselineMode())
                .thenAccept(result -> {
                    lastCumulativeChanges = result;
                    var prepared = DiffService.preparePerFileSummaries(result);
                    SwingUtilities.invokeLater(() -> {
                        updateTitleAndTooltipFromResult(result, state.baselineLabel());
                        updateContent(result, prepared, state.baselineLabel(), state.baselineMode());
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
        if (baselineMode == BaselineMode.DETACHED || baselineMode == BaselineMode.NO_BASELINE) {
            return new DiffService.CumulativeChanges(0, 0, 0, List.of(), null);
        }

        Map<ProjectFile, GitRepo.ModifiedFile> fileMap = new HashMap<>();
        String leftCommitSha = null;
        String currentBranch = repo.getCurrentBranch();

        switch (baselineMode) {
            case NON_DEFAULT_BRANCH -> {
                String defaultBranch = baselineLabel;
                String defaultBranchRef = "refs/heads/" + defaultBranch;
                leftCommitSha = repo.getMergeBase(currentBranch, defaultBranchRef);
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

        if (diffCore != null) {
            diffCore.clearCache();
        }
        removeAll();

        if (res.filesChanged() == 0) {
            String message;
            if (BaselineMode.DETACHED == lastBaselineMode) {
                message = "Detached HEAD \u2014 no changes to review";
            } else if (BaselineMode.NO_BASELINE == lastBaselineMode) {
                message = "No baseline to compare";
            } else if ("HEAD".equals(lastBaselineLabel)) {
                message = "Working tree is clean (no uncommitted changes).";
            } else if (lastBaselineLabel != null && !lastBaselineLabel.isBlank()) {
                message = "No changes vs " + lastBaselineLabel + ".";
            } else {
                message = "No changes to review.";
            }
            var none = new JLabel(message, SwingConstants.CENTER);
            none.setBorder(new EmptyBorder(20, 0, 20, 0));
            setLayout(new BorderLayout());
            add(none, BorderLayout.CENTER);
            revalidate();
            repaint();
            return;
        }

        var headerPanel = new JPanel(new BorderLayout(8, 0));
        headerPanel.setOpaque(false);

        String labelText = (baselineLabel != null && !baselineLabel.isEmpty())
                ? "Comparing vs " + baselineLabel
                : "Branch-based changes";
        var label = new JLabel(labelText);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        headerPanel.add(label, BorderLayout.WEST);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonPanel.setOpaque(false);

        boolean hasUncommittedChanges = false;
        try {
            hasUncommittedChanges = !repo.getModifiedFiles().isEmpty();
        } catch (GitAPIException e) {
            logger.debug("Unable to determine uncommitted changes state", e);
        }

        if (hasUncommittedChanges) {
            var commitBtn = new MaterialButton("Changes to Commit");
            SwingUtil.applyPrimaryButtonStyle(commitBtn);
            commitBtn.addActionListener(e -> showCommitDialog());
            buttonPanel.add(commitBtn);
        }

        var pushPull = res.pushPullState();
        if (pushPull != null && pushPull.canPull()) {
            var pullBtn = new MaterialButton("Pull");
            pullBtn.setEnabled(!hasUncommittedChanges);
            pullBtn.addActionListener(e -> performPull());
            buttonPanel.add(pullBtn);
        }

        if (pushPull != null && pushPull.canPush()) {
            var pushBtn = new MaterialButton("Push");
            pushBtn.setEnabled(!hasUncommittedChanges);
            pushBtn.addActionListener(e -> performPush());
            buttonPanel.add(pushBtn);
        }

        boolean showPR = baselineMode == BaselineMode.NON_DEFAULT_BRANCH
                || (baselineMode == BaselineMode.DEFAULT_WITH_UPSTREAM && res.filesChanged() > 0);

        if (showPR) {
            var prBtn = new MaterialButton("Create PR");
            prBtn.setEnabled(!hasUncommittedChanges);
            prBtn.addActionListener(e -> CreatePullRequestDialog.show(chrome.getFrame(), chrome, contextManager));
            buttonPanel.add(prBtn);
        }

        headerPanel.add(buttonPanel, BorderLayout.EAST);

        var topContainer = new JPanel(new BorderLayout(0, 6));
        topContainer.setOpaque(false);
        topContainer.add(headerPanel, BorderLayout.NORTH);

        String projectName = "Project";
        var root = contextManager.getProject().getRoot();
        if (root.getFileName() != null) projectName = root.getFileName().toString();

        this.fileComparisons = prepared.stream()
                .map(entry -> {
                    ProjectFile pf = null;
                    try {
                        pf = contextManager.toFile(entry.getKey());
                    } catch (Exception ignored) {
                    }

                    return new FileComparisonInfo(
                            pf,
                            new BufferSource.StringSource(entry.getValue().oldContent(), "", entry.getKey(), null),
                            new BufferSource.StringSource(entry.getValue().newContent(), "", entry.getKey(), null));
                })
                .toList();

        diffContainer = new JPanel(new BorderLayout());
        diffContainer.setOpaque(false);

        // We use a dummy panel to satisfy DiffDisplayCore's constructor requirement for now,
        // though ideally DiffDisplayCore should be decoupled from BrokkDiffPanel.
        // For this refactor, we focus on the composition in SessionChangesPanel.
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
                        if (fileTreePanel != null) {
                            fileTreePanel.selectFile(index);
                        }
                    }

                    @Override
                    protected void displayPanel(
                            int index,
                            ai.brokk.difftool.ui.AbstractDiffPanel panel,
                            int targetLine,
                            ReviewParser.DiffSide targetSide) {
                        if (diffContainer != null) {
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
                    }
                };

        // Inject the display logic to update our local diffContainer instead of the dummy panel's tabs
        dummyPanel.setBufferDiffPanel(null); // Clear any default behavior

        this.fileTreePanel = new ai.brokk.difftool.ui.FileTreePanel(fileComparisons, root, projectName);
        this.fileTreePanel.setSelectionListener(new DiffProjectFileNavigationTarget() {
            @Override
            public void navigateToFile(int fileIndex) {
                if (codeReviewPanel != null) codeReviewPanel.clearSelection();
                activeExcerpt = null;
                diffCore.showFile(fileIndex);
            }

            @Override
            public void navigateToFile(ProjectFile file) {
                if (codeReviewPanel != null) codeReviewPanel.clearSelection();
                activeExcerpt = null;
                diffCore.showFile(file);
            }

            @Override
            public void navigateToLocation(ProjectFile file, int lineNumber, ReviewParser.DiffSide side) {
                if (codeReviewPanel != null) codeReviewPanel.clearSelection();
                activeExcerpt = null;
                diffCore.showLocation(file, lineNumber, side);
            }
        });
        this.fileTreePanel.initializeTree();

        codeReviewPanel = new CodeReviewPanel(this::generateGuidedReview);
        codeReviewPanel.addReviewNavigationListener(ce -> {
            if (fileTreePanel != null) fileTreePanel.clearSelection();
            activeExcerpt = ce;
            ProjectFile pf = contextManager.toFile(ce.file());
            diffCore.showLocation(pf, ce.line(), ce.side());
        });

        // Left column: Review List above File Tree
        this.leftSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeReviewPanel.getListPanel(), fileTreePanel);
        leftSplitPane.setResizeWeight(0.5); // 50% split

        // Right side: Review Detail above Diff
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setOpaque(false);
        rightPanel.add(codeReviewPanel.getDetailPanel(), BorderLayout.NORTH);
        rightPanel.add(diffContainer, BorderLayout.CENTER);

        // Main horizontal split: [Review List / File Tree] | [Review Detail / Diff]
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplitPane, rightPanel);
        mainSplit.setDividerLocation(300);

        topContainer.add(mainSplit, BorderLayout.CENTER);

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

    private void generateGuidedReview() {
        if (codeReviewPanel == null || lastCumulativeChanges == null) return;

        codeReviewPanel.setBusy(true);

        var listPanel = codeReviewPanel.getListPanel();
        var parent = listPanel.getParent();
        if (parent == null) return;

        JTextArea logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(ThemeColors.getPanelBackground());
        logArea.setForeground(UIManager.getColor("Label.foreground"));
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(null);

        // Mimic the ReviewListPanel layout to prevent "bouncing"
        JPanel progressPanel = new JPanel(new BorderLayout());
        progressPanel.setBackground(ThemeColors.getPanelBackground());
        progressPanel.setMinimumSize(new Dimension(200, 200));
        progressPanel.setPreferredSize(listPanel.getPreferredSize());

        JPanel shimHeader = new JPanel(new BorderLayout());
        shimHeader.setOpaque(false);
        shimHeader.setBorder(new EmptyBorder(10, 10, 10, 10));
        var generatingBtn = new ai.brokk.gui.components.MaterialButton("Generating...");
        SwingUtil.applyPrimaryButtonStyle(generatingBtn);
        generatingBtn.setEnabled(false);
        shimHeader.add(generatingBtn, BorderLayout.CENTER);

        progressPanel.add(shimHeader, BorderLayout.NORTH);
        progressPanel.add(logScrollPane, BorderLayout.CENTER);

        TextAreaConsoleIO tio = new TextAreaConsoleIO(logArea, chrome, "Starting guided review...", false);

        final int savedDividerLocation = (parent instanceof JSplitPane sp) ? sp.getDividerLocation() : -1;

        SwingUtilities.invokeLater(() -> {
            if (parent instanceof JSplitPane splitPane) {
                splitPane.setTopComponent(progressPanel);
            }
            parent.revalidate();
            parent.repaint();
        });

        contextManager.submitLlmAction(() -> {
            try {
                var changes = lastCumulativeChanges;
                if (changes == null) return;

                String formattedDiff = changes.perFileChanges().stream()
                        .map(de -> "File: " + de.title() + "\n" + de.diff())
                        .collect(java.util.stream.Collectors.joining("\n\n"));

                byte[] hash = MessageDigest.getInstance("SHA-1").digest(formattedDiff.getBytes(StandardCharsets.UTF_8));
                String cacheKey = "review_" + HexFormat.of().formatHex(hash);
                var diskCache = contextManager.getProject().getDiskCache();

                ReviewParser.GuidedReview review;
                var cachedJson = diskCache.get(cacheKey);
                if (cachedJson.isPresent()) {
                    review = ReviewParser.GuidedReview.fromJson(cachedJson.get());
                } else {
                    var agent = new ReviewAgent(formattedDiff, contextManager, tio, fileComparisons);
                    review = agent.execute();
                    diskCache.put(cacheKey, review.toJson());
                }

                logger.info(
                        "Parsed GuidedReview: overview={} chars, designNotes={}, tacticalNotes={}, additionalTests={}",
                        review.overview().length(),
                        review.designNotes().size(),
                        review.tacticalNotes().size(),
                        review.additionalTests().size());

                // Log each design note's excerpts
                for (int i = 0; i < review.designNotes().size(); i++) {
                    var design = review.designNotes().get(i);
                    logger.info(
                            "  DesignNote[{}] '{}': {} excerpts",
                            i,
                            design.title(),
                            design.excerpts().size());
                    for (var excerpt : design.excerpts()) {
                        logger.info(
                                "    Excerpt file='{}', excerpt={} chars",
                                excerpt.file(),
                                excerpt.excerpt().length());
                    }
                }

                // Log tactical notes
                logger.info("Tactical notes count: {}", review.tacticalNotes().size());
                for (int i = 0; i < review.tacticalNotes().size(); i++) {
                    var tactical = review.tacticalNotes().get(i);
                    logger.info(
                            "  TacticalNote[{}] title='{}', file='{}', excerpt={} chars, recommendation={} chars",
                            i,
                            tactical.title(),
                            tactical.excerpt().file(),
                            tactical.excerpt().excerpt().length(),
                            tactical.recommendation().length());
                }

                SwingUtilities.invokeLater(() -> {
                    if (parent instanceof JSplitPane splitPane) {
                        splitPane.setTopComponent(codeReviewPanel.getListPanel());
                        if (savedDividerLocation > 0) {
                            splitPane.setDividerLocation(savedDividerLocation);
                        }
                    }
                    if (codeReviewPanel != null) {
                        codeReviewPanel.displayReview(review);
                        codeReviewPanel.setBusy(false);
                    }
                    parent.revalidate();
                    parent.repaint();
                });
            } catch (Exception ex) {
                logger.error("Failed to generate guided review", ex);
                SwingUtilities.invokeLater(() -> {
                    if (parent instanceof JSplitPane splitPane) {
                        splitPane.setTopComponent(codeReviewPanel.getListPanel());
                        if (savedDividerLocation > 0) {
                            splitPane.setDividerLocation(savedDividerLocation);
                        }
                    }
                    if (codeReviewPanel != null) codeReviewPanel.setBusy(false);
                    chrome.toolError("Review generation failed: " + ex.getMessage());
                    parent.revalidate();
                    parent.repaint();
                });
            }
        });
    }


    @Override
    public void applyTheme(GuiTheme guiTheme) {
        if (codeReviewPanel != null) codeReviewPanel.applyTheme(guiTheme);
        if (fileTreePanel != null) fileTreePanel.applyTheme(guiTheme);
        if (diffCore != null) {
            for (var panel : diffCore.getCachedPanels()) {
                panel.applyTheme(guiTheme);
            }
        }
    }

    public void dispose() {
        if (diffCore != null) {
            diffCore.clearCache();
        }
        diffCore = null;
        leftSplitPane = null;
    }

    public enum BaselineMode {
        NON_DEFAULT_BRANCH,
        DEFAULT_WITH_UPSTREAM,
        DEFAULT_LOCAL_ONLY,
        DETACHED,
        NO_BASELINE
    }
}
