package ai.brokk.gui.util;

import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.IProject;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Static utilities for Git host/remote operations: checking commit availability,
 * fetching refs, and branch-related UI helpers.
 */
public interface GitHostUtil {
    Logger logger = LogManager.getLogger(GitHostUtil.class);

    /**
     * Checks if a commit's data is fully available and parsable in the local repository.
     *
     * @param repo The GitRepo instance.
     * @param sha The commit SHA to check.
     * @return true if the commit is resolvable and its object data is parsable, false otherwise.
     */
    static boolean isCommitLocallyAvailable(GitRepo repo, String sha) {
        return repo.remote().isCommitLocallyAvailable(sha);
    }

    /**
     * Ensure a commit SHA is available locally and is fully parsable, fetching the specified refSpec from the remote if
     * necessary.
     *
     * @param repo GitRepo to operate on (non-null).
     * @param sha The commit SHA that must be present and parsable locally.
     * @param refSpec The refSpec to fetch if the SHA is missing or not parsable (e.g.
     *     "+refs/pull/123/head:refs/remotes/origin/pr/123/head").
     * @param remoteName Which remote to fetch from (e.g. "origin").
     * @return true if the SHA is now locally available and parsable, false otherwise.
     */
    static boolean ensureShaIsLocal(GitRepo repo, String sha, String refSpec, String remoteName) {
        return repo.remote().ensureShaIsLocal(sha, refSpec, remoteName);
    }

    /**
     * Gets the current branch name from a project's Git repository.
     *
     * @param project The project to get the branch name from
     * @return The current branch name, or empty string if unable to retrieve
     */
    static String getCurrentBranchName(IProject project) {
        try {
            if (!project.hasGit()) {
                return "";
            }
            IGitRepo repo = project.getRepo();
            if (repo instanceof GitRepo gitRepo) {
                return gitRepo.getCurrentBranch();
            }
        } catch (Exception e) {
            logger.warn("Could not get current branch name", e);
        }
        return "";
    }

    /**
     * Updates a panel's titled border to include the current branch name.
     *
     * @param panel The panel to update
     * @param baseTitle The base title (e.g., "Git", "Project Files")
     * @param branchName The current branch name (may be empty)
     */
    static void updatePanelBorderWithBranch(@Nullable JPanel panel, String baseTitle, String branchName) {
        if (panel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            var border = panel.getBorder();
            if (border instanceof TitledBorder titledBorder) {
                String newTitle = !branchName.isBlank() ? baseTitle + " (" + branchName + ")" : baseTitle;
                titledBorder.setTitle(newTitle);
                panel.revalidate();
                panel.repaint();
            }
        });
    }
}
