package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.context.Context;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.utils.ColorUtil;
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
import java.util.List;
import java.util.Map;
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
    private final DeferredUpdateHelper deferredUpdateHelper;
    private final TabTitleUpdater tabTitleUpdater;

    @Nullable
    private DiffService.CumulativeChanges lastCumulativeChanges = null;

    @Nullable
    private BrokkDiffPanel diffPanel;

    @FunctionalInterface
    public interface TabTitleUpdater {
        void updateTitleAndTooltip(String title, String tooltip);
    }

    public SessionChangesPanel(Chrome chrome, ContextManager contextManager, TabTitleUpdater tabTitleUpdater) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.tabTitleUpdater = tabTitleUpdater;
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
        var repoOpt = repo();
        if (repoOpt.isEmpty()) {
            tabTitleUpdater.updateTitleAndTooltip("Review", "No Git repository");
            return null;
        }

        var repo = repoOpt.get();
        String currentBranch;
        try {
            currentBranch = repo.getCurrentBranch();
        } catch (Exception e) {
            logger.warn("Failed to get current branch", e);
            tabTitleUpdater.updateTitleAndTooltip("Review", "Failed to get branch");
            return null;
        }

        BaselineMode baselineMode;
        String baselineLabel;
        String defaultBranch;
        try {
            defaultBranch = repo.getDefaultBranch();
        } catch (Exception e) {
            defaultBranch = "main";
        }

        if (RightPanel.isLikelyCommitHash(currentBranch)) {
            baselineMode = BaselineMode.DETACHED;
            baselineLabel = "detached HEAD";
        } else if (!currentBranch.equals(defaultBranch)) {
            baselineMode = BaselineMode.NON_DEFAULT_BRANCH;
            baselineLabel = defaultBranch;
        } else {
            var remoteUrl = repo.getRemoteUrl();
            if (remoteUrl != null && !remoteUrl.isEmpty()) {
                baselineMode = BaselineMode.DEFAULT_WITH_UPSTREAM;
                baselineLabel = "origin/" + defaultBranch;
            } else {
                baselineMode = BaselineMode.DEFAULT_LOCAL_ONLY;
                baselineLabel = "";
            }
        }
        return new BaselineState(baselineMode, baselineLabel);
    }

    private void performRefresh() {
        var state = resolveBaselineState();
        if (state == null) return;

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

    private java.util.Optional<ai.brokk.git.IGitRepo> repo() {
        try {
            return java.util.Optional.of(contextManager.getProject().getRepo());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private DiffService.CumulativeChanges computeCumulativeChanges(String baselineLabel, BaselineMode baselineMode)
            throws Exception {
        var repo = contextManager.getProject().getRepo();

        String leftRef;
        String rightRef = "HEAD";

        if (baselineMode == BaselineMode.DETACHED) {
            // For detached HEAD, compare against empty (show all changes in current commit)
            leftRef = rightRef + "^";
        } else if (baselineMode == BaselineMode.NON_DEFAULT_BRANCH) {
            // Compare current branch against default branch
            leftRef = baselineLabel;
        } else if (baselineMode == BaselineMode.DEFAULT_WITH_UPSTREAM) {
            // Compare local default against remote
            leftRef = baselineLabel;
        } else {
            // DEFAULT_LOCAL_ONLY - no meaningful comparison
            return new DiffService.CumulativeChanges(0, 0, 0, java.util.List.of());
        }

        var modifiedFiles = repo.listFilesChangedBetweenCommits(rightRef, leftRef);
        var modifiedSet = new java.util.HashSet<>(modifiedFiles.stream()
                .map(mf -> new ai.brokk.git.IGitRepo.ModifiedFile(mf.file(), mf.status()))
                .toList());

        return DiffService.summarizeDiff(repo, leftRef, rightRef, modifiedSet);
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
            List<Map.Entry<String, Context.DiffEntry>> prepared,
            @Nullable String baselineLabel,
            @Nullable BaselineMode baselineMode) {

        if (diffPanel != null) {
            diffPanel.dispose();
        }

        removeAll();

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
        var repo = contextManager.getProject().getRepo();
        try {
            hasUncommittedChanges = !repo.getModifiedFiles().isEmpty();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
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

        var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager)
                .setMultipleCommitsContext(false)
                .setRootTitle(projectName)
                .setInitialFileIndex(0)
                .setForceFileTree(true);

        for (var entry : prepared) {
            builder.addComparison(
                    new BufferSource.StringSource(entry.getValue().oldContent(), "", entry.getKey(), null),
                    new BufferSource.StringSource(entry.getValue().newContent(), "", entry.getKey(), null));
        }

        diffPanel = builder.build();
        diffPanel.applyTheme(chrome.getTheme());

        topContainer.add(diffPanel, BorderLayout.CENTER);

        setBorder(new CompoundBorder(
                new LineBorder(UIManager.getColor("Separator.foreground"), 1), new EmptyBorder(6, 6, 6, 6)));
        add(topContainer, BorderLayout.CENTER);

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
                String branch = contextManager.getProject().getRepo().getCurrentBranch();
                chrome.showOutputSpinner("Pulling " + branch + "...");
                new GitWorkflow(contextManager).pull(branch);
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    chrome.getRightPanel().requestReviewUpdate();
                    chrome.updateGitRepo();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    chrome.toolError("Pull failed: " + e.getMessage());
                });
            }
            return null;
        });
    }

    private void performPush() {
        contextManager.submitExclusiveAction(() -> {
            try {
                String branch = contextManager.getProject().getRepo().getCurrentBranch();
                chrome.showOutputSpinner("Pushing " + branch + "...");
                new GitWorkflow(contextManager).push(branch);
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    chrome.getRightPanel().requestReviewUpdate();
                    chrome.updateGitRepo();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    chrome.hideOutputSpinner();
                    chrome.toolError("Push failed: " + e.getMessage());
                });
            }
            return null;
        });
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        if (diffPanel != null) diffPanel.applyTheme(guiTheme);
    }

    public void dispose() {
        if (diffPanel != null) diffPanel.dispose();
        diffPanel = null;
    }

    public enum BaselineMode {
        NON_DEFAULT_BRANCH,
        DEFAULT_WITH_UPSTREAM,
        DEFAULT_LOCAL_ONLY,
        DETACHED,
        NO_BASELINE
    }
}
