package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.context.Context;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.dialogs.CreatePullRequestDialog;
import ai.brokk.gui.git.GitCommitTab;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A panel that displays an aggregated diff of changes for the current session/branch.
 */
public class SessionChangesPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SessionChangesPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;

    @Nullable
    private BrokkDiffPanel diffPanel;

    @Nullable
    private String lastBaselineLabel;

    @Nullable
    private BaselineMode lastBaselineMode;

    public SessionChangesPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        setOpaque(false);
    }

    public void updateContent(
            DiffService.CumulativeChanges res,
            List<Map.Entry<String, Context.DiffEntry>> prepared,
            @Nullable String baselineLabel,
            @Nullable BaselineMode baselineMode) {
        this.lastBaselineLabel = baselineLabel;
        this.lastBaselineMode = baselineMode;

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
        try {
            Optional<IGitRepo> repo = repo();
            if (repo.isPresent()) {
                hasUncommittedChanges = !repo.get().getModifiedFiles().isEmpty();
            }
        } catch (Exception e) {
            hasUncommittedChanges = false;
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

        boolean showPR = lastBaselineMode == BaselineMode.NON_DEFAULT_BRANCH
                || (lastBaselineMode == BaselineMode.DEFAULT_WITH_UPSTREAM && res.filesChanged() > 0);

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
        try {
            var root = contextManager.getProject().getRoot();
            if (root.getFileName() != null) projectName = root.getFileName().toString();
        } catch (Exception ignored) {
        }

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
                var bp = chrome.getBuildPane();
                if (bp != null) bp.requestReviewUpdate();
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
                    var bp = chrome.getBuildPane();
                    if (bp != null) bp.requestReviewUpdate();
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
                    var bp = chrome.getBuildPane();
                    if (bp != null) bp.requestReviewUpdate();
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

    private Optional<IGitRepo> repo() {
        try {
            return Optional.of(contextManager.getProject().getRepo());
        } catch (Exception e) {
            return Optional.empty();
        }
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
