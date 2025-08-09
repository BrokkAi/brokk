package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface IGitRepo {

    record ModifiedFile(ProjectFile file, String status) {}

    /**
     * Information about a Git worktree.
     * Branch will be null if worktree is in a detached HEAD state.
     */
    record WorktreeInfo(Path path, @Nullable String branch, String commitId) {}
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

    /**
     * Invalidate refs and tracked-files caches
     */
    default void invalidateCaches() {
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

    default void forceRemoveFiles(List<ProjectFile> files) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default List<WorktreeInfo> listWorktrees() throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default void addWorktree(String branch, Path path) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    /**
     * Removes the worktree at the specified path.
     * @param path The path to the worktree to remove.
     * @param force If true, the removal will be forced (equivalent to `git worktree remove --force --force`).
     * @throws GitAPIException if the Git command fails, or WorktreeNeedsForceException if force is false and removal requires it.
     */
    default void removeWorktree(Path path, boolean force) throws GitAPIException {
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
    default @Nullable String checkMergeConflicts(String worktreeBranch, String targetBranch, GitRepo.MergeMode mode) throws GitAPIException {
        throw new UnsupportedOperationException("checkMergeConflicts not implemented");
    }

    default List<String> getCommitMessagesBetween(String branchName, String targetBranchName) throws GitAPIException {
        throw new UnsupportedOperationException("getCommitMessagesBetween not implemented");
    }

    default MergeResult squashMergeIntoHead(String branchName) throws GitAPIException {
        throw new UnsupportedOperationException("squashMergeIntoHead not implemented");
    }

    default MergeResult rebaseMergeIntoHead(String branchName) throws GitAPIException {
        throw new UnsupportedOperationException("rebaseMergeIntoHead not implemented");
    }

    default MergeResult performMerge(String branchName, GitRepo.MergeMode mode) throws GitAPIException {
        throw new UnsupportedOperationException("performMerge not implemented");
    }

    /**
     * Attempts to determine the repository's default branch.
     * Order of preference:
     *   1. The symbolic ref refs/remotes/origin/HEAD (remote's default)
     *   2. Local branch named 'main'
     *   3. Local branch named 'master'
     *   4. First local branch (alphabetically)
     * @return The default branch name.
     * @throws GitRepo.NoDefaultBranchException if no default branch can be determined (e.g., in an empty repository).
     * @throws GitAPIException if a different error occurs while accessing Git data.
     */
    default String getDefaultBranch() throws GitAPIException {
        throw new UnsupportedOperationException("getDefaultBranch not implemented");
    }

    default String getCurrentBranch() throws GitAPIException {
        throw new UnsupportedOperationException("getDefaultBranch not implemented");
    }

    default String getCurrentCommitId() throws GitAPIException {
        throw new UnsupportedOperationException("getCurrentCommitId not implemented");
    }

    default Set<ModifiedFile> getModifiedFiles() throws GitAPIException {
        throw new UnsupportedOperationException("getModifiedFiles not implemented");
    }

    default void checkout(String branchOrCommit) throws GitAPIException {
        throw new UnsupportedOperationException("checkout not implemented");
    }

    default void applyDiff(String diff) throws GitAPIException {
        throw new UnsupportedOperationException("applyDiff not implemented");
    }

    default @Nullable String getRemoteUrl() {
        throw new UnsupportedOperationException();
    }
}
