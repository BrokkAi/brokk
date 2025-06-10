package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface IGitRepo {
    
    /**
     * Information about a Git worktree.
     */
    record WorktreeInfo(Path path, String branch, String commitId) {}
    Set<ProjectFile> getTrackedFiles();

    default String diff() throws GitAPIException {
        return "";
    }

    default String sanitizeBranchName(String proposedName) throws GitAPIException {
        return proposedName;
    }

    default Path getGitTopLevel() {
        throw new UnsupportedOperationException();
    }

    default void refresh() {
    }

    default ObjectId resolve(String s) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default String diffFiles(List<ProjectFile> selectedFiles) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default String showFileDiff(String head, String commitId, ProjectFile file) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default String getFileContent(String commitId, ProjectFile file) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default String showDiff(String firstCommitId, String s) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default List<ProjectFile> listChangedFilesInCommitRange(String firstCommitId, String lastCommitId) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default List<ProjectFile> listFilesChangedBetweenCommits(String newCommitId, String oldCommitId) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default void add(List<ProjectFile> files) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    /**
     * for the rare case when you need to add a file (e.g. .gitignore) that is not necessarily under the project's root
     */
    default void add(Path path) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default void remove(ProjectFile file) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default List<WorktreeInfo> listWorktrees() throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default void addWorktree(String branch, Path path) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default void removeWorktree(Path path) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default boolean isWorktree() {
        return false;
    }

    default Set<String> getBranchesInWorktrees() throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default Path getNextWorktreePath(Path worktreeStorageDir) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Checks if the repository implementation supports worktree operations.
     * This often depends on the availability of a command-line Git executable.
     * @return true if worktrees are supported, false otherwise.
     */
    default boolean supportsWorktrees() {
        return false;
    }

    /**
     * Checks for merge conflicts between a worktree branch and a target branch using a specified merge mode.
     *
     * @param worktreeBranch The branch of the worktree to be merged.
     * @param targetBranch   The branch to merge into.
     * @param mode           The merge strategy (MergeMode enum from GitWorktreeTab).
     * @return A string describing conflicts if any, or null/empty if no conflicts.
     * @throws GitAPIException if a Git error occurs during the check.
     */
    default String checkMergeConflicts(String worktreeBranch, String targetBranch, io.github.jbellis.brokk.gui.GitWorktreeTab.MergeMode mode) throws GitAPIException {
        throw new UnsupportedOperationException("checkMergeConflicts not implemented");
    }

    default List<String> getCommitMessagesBetween(String branchName, String targetBranchName) throws GitAPIException {
        throw new UnsupportedOperationException("getCommitMessagesBetween not implemented");
    }
}
