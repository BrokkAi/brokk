package ai.brokk.gui.util;

import ai.brokk.ContextManager;
import ai.brokk.MainProject;
import ai.brokk.WorktreeProject;
import ai.brokk.git.GitRepo;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.MergeDialogPanel;
import java.awt.Component;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for creating merge dialogs that can be used by both GitLogTab and GitWorktreeTab.
 * Delegates UI and interaction logic to MergeDialogPanel.
 */
public class MergeDialogUtil {
    private static final Logger logger = LogManager.getLogger(MergeDialogUtil.class);

    public record MergeDialogOptions(
            String sourceBranch,
            String dialogTitle,
            boolean showDeleteWorktree,
            boolean showDeleteBranch,
            Component parentComponent,
            Chrome chrome,
            ContextManager contextManager) {
        /** Create options for GitLogTab branch merge (no worktree deletion). */
        public static MergeDialogOptions forBranchMerge(
                String sourceBranch, Component parentComponent, Chrome chrome, ContextManager contextManager) {
            return new MergeDialogOptions(
                    sourceBranch,
                    "Merge branch '" + sourceBranch + "'",
                    false, // no worktree deletion
                    true, // allow branch deletion
                    parentComponent,
                    chrome,
                    contextManager);
        }

        /** Create options for GitWorktreeTab merge (with worktree deletion). */
        public static MergeDialogOptions forWorktreeMerge(
                String sourceBranch, Component parentComponent, Chrome chrome, ContextManager contextManager) {
            return new MergeDialogOptions(
                    sourceBranch,
                    "Merge branch '" + sourceBranch + "'",
                    true, // allow worktree deletion
                    true, // allow branch deletion
                    parentComponent,
                    chrome,
                    contextManager);
        }
    }

    public record MergeDialogResult(
            boolean confirmed,
            String sourceBranch,
            String targetBranch,
            GitRepo.MergeMode mergeMode,
            boolean deleteWorktree,
            boolean deleteBranch) {
        public static MergeDialogResult cancelled() {
            return new MergeDialogResult(false, "", "", GitRepo.MergeMode.MERGE_COMMIT, false, false);
        }
    }

    /**
     * Shows a merge dialog with the specified options.
     * - Disables OK on conflicts.
     * - Performs dirty worktree check in the Worktree flow and prevents proceeding if dirty.
     */
    public static MergeDialogResult showMergeDialog(MergeDialogOptions options) {
        var project = options.contextManager().getProject();

        // Obtain main project for branch operations
        MainProject mainProject;
        if (project instanceof WorktreeProject worktreeProject) {
            mainProject = worktreeProject.getParent();
        } else {
            mainProject = project.getMainProject();
        }

        // Validate repo type early (avoid opening dialog on unsupported repo)
        if (!(mainProject.getRepo() instanceof GitRepo)) {
            logger.error(
                    "Merge operation requires Git repository, got: {}",
                    mainProject.getRepo().getClass().getSimpleName());
            JOptionPane.showMessageDialog(
                    options.parentComponent(),
                    "This operation requires a Git repository",
                    "Repository Type Error",
                    JOptionPane.ERROR_MESSAGE);
            return MergeDialogResult.cancelled();
        }

        var owner = SwingUtilities.getWindowAncestor(options.parentComponent());
        MergeDialogPanel dialog = new MergeDialogPanel(owner instanceof java.awt.Frame f ? f : null, options);

        MergeDialogPanel.Result r = dialog.showDialog();

        if (r.confirmed()) {
            logger.info(
                    "Merge confirmed for source branch '{}' into target branch '{}' using mode '{}'. Remove worktree: {}, Remove branch: {}",
                    r.sourceBranch(),
                    r.targetBranch(),
                    r.mergeMode(),
                    r.deleteWorktree(),
                    r.deleteBranch());

            return new MergeDialogResult(
                    true, r.sourceBranch(), r.targetBranch(), r.mergeMode(), r.deleteWorktree(), r.deleteBranch());
        }

        return MergeDialogResult.cancelled();
    }
}
