package ai.brokk.gui;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.EditBlock;
import ai.brokk.ICodeReview;
import ai.brokk.IConsoleIO;
import ai.brokk.agents.ReviewAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.ui.AbstractDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.ui.DiffPanelManager;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.difftool.ui.FileTreePanel;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.dialogs.CreatePullRequestDialog;
import ai.brokk.gui.git.GitCommitTab;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
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

    @Nullable
    private String lastBaselineLabel = null;

    @Nullable
    private BaselineMode lastBaselineMode = null;

    @Nullable
    private DiffPanelManager panelManager;

    @Nullable
    private FileTreePanel fileTreePanel;

    @Nullable
    private CodeReviewPanel codeReviewPanel;

    private final JPanel diffContainer = new JPanel(new BorderLayout());

    private List<FileComparisonInfo> currentComparisons = List.of();
    private List<String> currentComparisonPaths = List.of();
    private List<String> currentComparisonNewContents = List.of();
    private int currentExcerptIndex = 0;
    private List<ICodeReview.CodeExcerpt> currentExcerpts = List.of();

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

        if (panelManager != null) {
            panelManager.clearCache();
        }
        diffContainer.removeAll();
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

        this.currentComparisonPaths = prepared.stream()
                .map(Map.Entry::getKey)
                .toList();
        this.currentComparisonNewContents = prepared.stream()
                .map(entry -> entry.getValue().newContent())
                .toList();
        this.currentComparisons = prepared.stream()
                .map(entry -> new FileComparisonInfo(
                        new BufferSource.StringSource(entry.getValue().oldContent(), "", entry.getKey(), null),
                        new BufferSource.StringSource(entry.getValue().newContent(), "", entry.getKey(), null)))
                .toList();

        fileTreePanel = new FileTreePanel(currentComparisons, root, projectName);
        codeReviewPanel = new CodeReviewPanel(contextManager);
        codeReviewPanel.addReviewNavigationListener(new CodeReviewPanel.ReviewTriggerListener() {
            @Override
            public void onTriggerReview() {
                generateGuidedReview(baselineLabel, baselineMode);
            }

            @Override
            public void onSelect(String explanation, List<ICodeReview.CodeExcerpt> excerpts) {
                currentExcerpts = excerpts;
                currentExcerptIndex = 0;
                navigateToExcerpt(0);
            }
        });

        panelManager = new DiffPanelManager(
                null,
                currentComparisons,
                contextManager,
                panel -> {
                    diffContainer.removeAll();
                    diffContainer.add(panel.getComponent(), BorderLayout.CENTER);
                    diffContainer.revalidate();
                    diffContainer.repaint();
                },
                chrome::getTheme);

        fileTreePanel.setSelectionListener(panelManager);
        fileTreePanel.initializeTree();

        // Add "Next Excerpt" functionality if multiple excerpts exist
        Action nextExcerptAction = new AbstractAction("Next Excerpt") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (currentExcerpts.size() > 1) {
                    currentExcerptIndex = (currentExcerptIndex + 1) % currentExcerpts.size();
                    navigateToExcerpt(currentExcerptIndex);
                }
            }
        };
        this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("released F3"), "nextExcerpt");
        this.getActionMap().put("nextExcerpt", nextExcerptAction);

        var leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeReviewPanel, fileTreePanel);
        leftSplit.setDividerLocation(300);
        leftSplit.setResizeWeight(0.5);

        var splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, diffContainer);
        splitPane.setDividerLocation(300);
        topContainer.add(splitPane, BorderLayout.CENTER);

        setBorder(new CompoundBorder(
                new LineBorder(UIManager.getColor("Separator.foreground"), 1), new EmptyBorder(6, 6, 6, 6)));
        add(topContainer, BorderLayout.CENTER);

        panelManager.navigateToFile(0);
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

    private void generateGuidedReview(@Nullable String baselineLabel, @Nullable BaselineMode baselineMode) {
        if (codeReviewPanel == null || lastCumulativeChanges == null) return;

        codeReviewPanel.setBusy(true);

        contextManager.submitLlmAction(() -> {
            try {
                var changes = lastCumulativeChanges;
                if (changes == null) return;

                String formattedDiff = changes.perFileChanges().stream()
                        .map(de -> "File: " + de.title() + "\n" + de.diff())
                        .collect(java.util.stream.Collectors.joining("\n\n"));

                var agent = new ReviewAgent(formattedDiff, contextManager);
                var review = agent.execute();

                SwingUtilities.invokeLater(() -> {
                    if (codeReviewPanel != null) {
                        codeReviewPanel.displayReview(review);
                        codeReviewPanel.setBusy(false);
                    }
                });
            } catch (Exception ex) {
                logger.error("Failed to generate guided review", ex);
                SwingUtilities.invokeLater(() -> {
                    if (codeReviewPanel != null) codeReviewPanel.setBusy(false);
                    chrome.toolError("Review generation failed: " + ex.getMessage());
                });
            }
        });
    }

    private void navigateToExcerpt(int index) {
        if (panelManager == null || index < 0 || index >= currentExcerpts.size()) return;

        ICodeReview.CodeExcerpt excerpt = currentExcerpts.get(index);
        String relPath = excerpt.file().getRelPath().toString();
        String excerptText = excerpt.excerpt();

        int targetFileIndex = -1;
        for (int i = 0; i < currentComparisonPaths.size(); i++) {
            if (currentComparisonPaths.get(i).equals(relPath)) {
                targetFileIndex = i;
                break;
            }
        }

        if (targetFileIndex == -1) return;

        // Find the line number using whitespace-insensitive search in the NEW content
        String newContent = currentComparisonNewContents.get(targetFileIndex);
        String[] lines = newContent.split("\\r?\\n", -1);
        String[] targetLines = excerptText.split("\\r?\\n", -1);

        int lineNum = findLineIgnoringWhitespace(lines, targetLines);
        if (lineNum != -1) {
            panelManager.navigateToLocation(targetFileIndex, lineNum + 1);
        } else {
            panelManager.navigateToFile(targetFileIndex);
        }
    }

    /**
     * Finds the starting line index where targetLines match in originalLines,
     * ignoring leading/trailing whitespace differences on each line.
     * Returns -1 if no match found.
     */
    private static int findLineIgnoringWhitespace(String[] originalLines, String[] targetLines) {
        if (targetLines.length == 0) return -1;
        if (targetLines.length > originalLines.length) return -1;

        for (int start = 0; start <= originalLines.length - targetLines.length; start++) {
            boolean matches = true;
            for (int j = 0; j < targetLines.length; j++) {
                if (!originalLines[start + j].strip().equals(targetLines[j].strip())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return start;
            }
        }
        return -1;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        if (fileTreePanel != null) fileTreePanel.applyTheme(guiTheme);
        if (codeReviewPanel != null) codeReviewPanel.applyTheme(guiTheme);
        if (panelManager != null) {
            for (AbstractDiffPanel panel : panelManager.getCachedPanels()) {
                panel.applyTheme(guiTheme);
            }
        }
    }

    public void dispose() {
        if (panelManager != null) {
            panelManager.clearCache();
        }
        panelManager = null;
    }

    public enum BaselineMode {
        NON_DEFAULT_BRANCH,
        DEFAULT_WITH_UPSTREAM,
        DEFAULT_LOCAL_ONLY,
        DETACHED,
        NO_BASELINE
    }
}
