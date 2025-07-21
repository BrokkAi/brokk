package io.github.jbellis.brokk.git;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.jetbrains.annotations.Nullable;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * A Git repository abstraction using JGit.
 * <p>
 * The semantics are that GitRepo represents a subset of the files in the full Git repository
 * that is located in the `location` directory or one of its parents. The common case is that
 * the git dir is a child of `location` but we support instantiating in one of the working
 * tree's subdirectories as well.
 */
public class GitRepo implements Closeable, IGitRepo {
    private static final Logger logger = LogManager.getLogger(GitRepo.class);

    private final Path projectRoot; // The root directory for ProjectFile instances this GitRepo deals with
    private final Path gitTopLevel; // The actual top-level directory of the git repository
    private final Repository repository;
    private final Git git;
    private @Nullable Set<ProjectFile> trackedFilesCache = null;

    /**
     * Returns true if the directory has a .git folder, is a valid repository,
     * and contains at least one local branch.
     */
    public static boolean hasGitRepo(Path dir) {
        try {
            var builder = new FileRepositoryBuilder().findGitDir(dir.toFile());
            if (builder.getGitDir() == null) {
                return false;
            }
            try (var repo = builder.build()) {
                // A valid repo for Brokk must have at least one local branch
                return !repo.getRefDatabase()
                            .getRefsByPrefix("refs/heads/")
                            .isEmpty();
            }
        } catch (IOException e) {
            // Corrupted or unreadable repo -> treat as non-git
            logger.warn("Could not read git repo at {}: {}", dir, e.getMessage());
            return false;
        }
    }

    /**
     * Sanitizes a proposed branch name and ensures it is unique by appending a numerical suffix if necessary.
     * If the initial sanitization results in an empty string, "branch" is used as the base.
     *
     * @param proposedName The desired branch name.
     * @return A sanitized and unique branch name.
     * @throws GitAPIException if an error occurs while listing local branches.
     */
    @Override
    public String sanitizeBranchName(String proposedName) throws GitAPIException {
        String sanitized = proposedName.trim().toLowerCase(Locale.ROOT);
        // Replace whitespace with hyphens
        sanitized = sanitized.replaceAll("\\s+", "-");
        // Remove characters not suitable for branch names (keeping only alphanumeric, hyphen, forward slash, and underscore)
        sanitized = sanitized.replaceAll("[^a-z0-9-/_]", "");
        // Remove leading or trailing hyphens that might result from sanitation
        sanitized = sanitized.replaceAll("^-+|-+$", "");

        if (sanitized.isEmpty()) {
            sanitized = "branch"; // Default base name if sanitization results in an empty string
        }

        List<String> localBranches = listLocalBranches();

        if (!localBranches.contains(sanitized)) {
            return sanitized;
        }

        String baseCandidate = sanitized;
        int N = 2;
        String nextCandidate;
        do {
            nextCandidate = baseCandidate + "-" + N;
            N++;
        } while (localBranches.contains(nextCandidate));

        return nextCandidate;
    }

    /**
     * Get the JGit instance for direct API access
     */
    public Git getGit() {
        return git;
    }

    public GitRepo(Path projectRoot) {
        this.projectRoot = projectRoot;

        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            builder.findGitDir(projectRoot.toFile());
            if (builder.getGitDir() == null) {
                throw new RuntimeException("No git repo found at or above " + projectRoot);
            }
            repository = builder.build();
            git = new Git(repository);

            // For worktrees, we need to find the actual repository root, not the .git/worktrees path
            if (isWorktree()) {
                // For worktrees, read the commondir file to find the main repository
                Path gitDir = repository.getDirectory().toPath();
                Path commondirFile = gitDir.resolve("commondir");
                if (Files.exists(commondirFile)) {
                    String commonDirContent = Files.readString(commondirFile, StandardCharsets.UTF_8).trim();
                    Path commonDir = gitDir.resolve(commonDirContent).normalize();
                    Path parentOfCommonDir = commonDir.getParent();
                    this.gitTopLevel = requireNonNull(parentOfCommonDir, "Parent of git common-dir should not be null: " + commonDir).normalize();
                } else {
                    // Fallback: try to parse the gitdir file in the working tree
                    Path gitFile = repository.getWorkTree().toPath().resolve(".git");
                    if (Files.exists(gitFile)) {
                        String gitFileContent = Files.readString(gitFile, StandardCharsets.UTF_8).trim();
                        if (gitFileContent.startsWith("gitdir: ")) {
                            String gitDirPath = gitFileContent.substring("gitdir: ".length());
                            Path worktreeGitDir = Path.of(gitDirPath).normalize();
                            Path commondirFile2 = worktreeGitDir.resolve("commondir");
                            if (Files.exists(commondirFile2)) {
                                String commonDirContent2 = Files.readString(commondirFile2, StandardCharsets.UTF_8).trim();
                                Path commonDir2 = worktreeGitDir.resolve(commonDirContent2).normalize();
                                Path parentOfCommonDir2 = commonDir2.getParent();
                                this.gitTopLevel = requireNonNull(parentOfCommonDir2, "Parent of git common-dir (fallback) should not be null: " + commonDir2).normalize();
                            } else {
                                // Ultimate fallback
                                this.gitTopLevel = repository.getDirectory().getParentFile().toPath().normalize();
                            }
                        } else {
                            this.gitTopLevel = repository.getDirectory().getParentFile().toPath().normalize();
                        }
                    } else {
                        this.gitTopLevel = repository.getDirectory().getParentFile().toPath().normalize();
                    }
                }
            } else {
                // For regular repos, gitTopLevel is the parent of the actual .git directory
                this.gitTopLevel = repository.getDirectory().getParentFile().toPath().normalize();
            }
            logger.trace("Git dir for {} is {}, gitTopLevel is {}", projectRoot, repository.getDirectory(), gitTopLevel);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open repository at " + projectRoot, e);
        }
    }

    @Override
    public Path getGitTopLevel() {
        return gitTopLevel;
    }

    /**
     * Converts a ProjectFile (which is relative to projectRoot) into a path string
     * relative to JGit's working tree root, suitable for JGit commands.
     */
    private String toRepoRelativePath(ProjectFile file) {
        // ProjectFile.absPath() gives the absolute path on the filesystem.
        // We need to make it relative to JGit's working tree root.
        Path workingTreeRoot = repository.getWorkTree().toPath().normalize();
        Path relativePath = workingTreeRoot.relativize(file.absPath());
        return relativePath.toString().replace('\\', '/');
    }

    /**
     * Creates a ProjectFile instance from a path string returned by JGit.
     * JGit paths are relative to the working tree root. The returned ProjectFile will be
     * relative to projectRoot.
     */
    private ProjectFile toProjectFile(String gitPath) {
        Path workingTreeRoot = repository.getWorkTree().toPath().normalize();
        Path absolutePath = workingTreeRoot.resolve(gitPath);
        Path pathRelativeToProjectRoot = projectRoot.relativize(absolutePath);
        return new ProjectFile(projectRoot, pathRelativeToProjectRoot);
    }

    // ==================== Merge Mode Enum ====================

    /**
     * Represents the different merge strategies available.
     */
    public enum MergeMode {
        MERGE_COMMIT("Merge commit"),
        SQUASH_COMMIT("Squash and merge"),
        REBASE_MERGE("Rebase and merge");

        private final String displayName;

        MergeMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    // ==================== Git Operation Status Utility Methods ====================

    /**
     * Determines if a merge operation was successful, accounting for the special case
     * where squash merges return MERGED_SQUASHED_NOT_COMMITTED which is actually successful.
     *
     * @param result the MergeResult to check
     * @param mode the merge mode that was used
     * @return true if the merge was successful
     */
    public static boolean isMergeSuccessful(MergeResult result, MergeMode mode) {
        MergeResult.MergeStatus status = result.getMergeStatus();
        return status.isSuccessful() ||
               status == MergeResult.MergeStatus.MERGED_NOT_COMMITTED ||
               (mode == MergeMode.SQUASH_COMMIT && status == MergeResult.MergeStatus.MERGED_SQUASHED_NOT_COMMITTED);
    }

    /**
     * Determines if a rebase operation was successful.
     *
     * @param result the RebaseResult to check
     * @return true if the rebase was successful
     */
    public static boolean isRebaseSuccessful(RebaseResult result) {
        return result.getStatus().isSuccessful();
    }

    /**
     * Determines if a push operation was successful for a specific ref update.
     *
     * @param status the RemoteRefUpdate.Status to check
     * @return true if the push was successful
     */
    public static boolean isPushSuccessful(RemoteRefUpdate.Status status) {
        return status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE;
    }

    /**
     * Determines if any conflicts exist in a merge result.
     *
     * @param result the MergeResult to check
     * @return true if conflicts exist
     */
    public static boolean hasConflicts(MergeResult result) {
        return result.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING;
    }

    /**
     * Gets a user-friendly description of a merge result status.
     *
     * @param result the MergeResult to describe
     * @param mode the merge mode that was used
     * @return a human-readable description of the merge result
     */
    public static String describeMergeResult(MergeResult result, MergeMode mode) {
        MergeResult.MergeStatus status = result.getMergeStatus();
        return switch (status) {
            case FAST_FORWARD -> "Fast-forward merge";
            case ALREADY_UP_TO_DATE -> "Already up to date";
            case MERGED -> "Merge commit created";
            case MERGED_SQUASHED_NOT_COMMITTED -> "Squashed changes ready for commit";
            case CONFLICTING -> "Merge conflicts detected";
            case ABORTED -> "Merge aborted";
            case FAILED -> "Merge failed";
            default -> "Merge result: " + status;
        };
    }

    @Override
    public synchronized void invalidateCaches() {
        logger.debug("GitRepo refresh");
        // TODO probably we should split ".git changed" apart from "tracked files changed"
        repository.getRefDatabase().refresh();
        trackedFilesCache = null;
    }

    /**
     * Adds files to staging.
     */
    @Override
    public synchronized void add(List<ProjectFile> files) throws GitAPIException {
        var addCommand = git.add();
        for (var file : files) {
            addCommand.addFilepattern(toRepoRelativePath(file));
        }
        addCommand.call();
    }

    @Override
    public synchronized void add(Path path) throws GitAPIException {
        var addCommand = git.add();
        addCommand.addFilepattern(path.toString());
        addCommand.call();
    }

    /**
     * Removes a file from the Git index. This corresponds to `git rm --cached <file>`.
     * The working tree file is expected to be deleted separately if desired.
     *
     * @param file The file to remove from the index.
     * @throws GitAPIException if the Git command fails.
     */
    @Override
    public synchronized void remove(ProjectFile file) throws GitAPIException {
        logger.debug("Removing file from Git index (git rm --cached): {}", file);
        git.rm()
                .addFilepattern(toRepoRelativePath(file))
                .setCached(true) // Remove from index only -- EditBlock removes from disk
                .call();
        invalidateCaches(); // Refresh repository state, including tracked files cache
    }

    /**
     * Returns a list of RepoFile objects representing all tracked files in the repository.
     */
    @Override
    public synchronized Set<ProjectFile> getTrackedFiles() {
        if (trackedFilesCache != null) {
            return trackedFilesCache;
        }
        var trackedPaths = new HashSet<String>();
        try {
            // HEAD (unchanged) files
            ObjectId headTreeId = null;
            try {
                headTreeId = resolve("HEAD^{tree}");
            } catch (GitRepoException e) {
                // HEAD^{tree} might not exist in empty repos - this is allowed
                logger.debug("HEAD^{{tree}} not resolvable: {}", e.getMessage());
            }
            if (headTreeId != null) {
                try (var revWalk = new RevWalk(repository);
                     var treeWalk = new TreeWalk(repository)) {
                    var headTree = revWalk.parseTree(headTreeId);
                    treeWalk.addTree(headTree);
                    treeWalk.setRecursive(true);
                    while (treeWalk.next()) {
                        String gitPath = treeWalk.getPathString();
                        // Only add paths that are under the projectRoot
                        Path workingTreeRoot = repository.getWorkTree().toPath().normalize();
                        Path absoluteFilePathInWorktree = workingTreeRoot.resolve(gitPath);
                        if (absoluteFilePathInWorktree.startsWith(projectRoot)) {
                            trackedPaths.add(gitPath);
                        }
                    }
                }
            }
            // Staged/modified/added/removed
            var status = git.status().call();
            Path workingTreeRoot = repository.getWorkTree().toPath().normalize();
            Stream.of(status.getChanged(), status.getModified(), status.getAdded(), status.getRemoved())
                    .flatMap(Collection::stream)
                    .filter(gitPath -> {
                        Path absoluteFilePathInWorktree = workingTreeRoot.resolve(gitPath);
                        return absoluteFilePathInWorktree.startsWith(projectRoot);
                    })
                    .forEach(trackedPaths::add);
        } catch (IOException | GitAPIException e) {
            logger.error("getTrackedFiles failed", e);
            // not really much caller can do about this, it's a critical method
            throw new RuntimeException(e);
        }
        trackedFilesCache = trackedPaths.stream()
                .map(this::toProjectFile)
                .collect(Collectors.toSet());
        return trackedFilesCache;
    }

    /**
     * Get the current commit ID (HEAD)
     */
    public String getCurrentCommitId() throws GitAPIException {
        var head = resolve("HEAD");
        return head.getName();
    }

    /**
     * Performs git diff operation with the given filter group, handling NoHeadException for empty repositories.
     */
    private String performDiffWithFilter(TreeFilter filterGroup) throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            try {
                // 1) staged changes
                git.diff()
                        .setCached(true)
                        .setShowNameAndStatusOnly(false)
                        .setPathFilter(filterGroup)
                        .setOutputStream(out)
                        .call();
                var staged = out.toString(StandardCharsets.UTF_8);
                out.reset();

                // 2) unstaged changes
                git.diff()
                        .setCached(false)
                        .setShowNameAndStatusOnly(false)
                        .setPathFilter(filterGroup)
                        .setOutputStream(out)
                        .call();
                var unstaged = out.toString(StandardCharsets.UTF_8);

                return Stream.of(staged, unstaged)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("\n"));
            } catch (NoHeadException e) {
                // Handle empty repository case - return empty diff for repositories with no commits
                return "";
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Produces a combined diff of staged + unstaged changes, restricted to the given files.
     */
    @Override
    public synchronized String diffFiles(List<ProjectFile> files) throws GitAPIException {
        var filters = files.stream()
                .map(file -> PathFilter.create(toRepoRelativePath(file)))
                .collect(Collectors.toCollection(ArrayList::new));
        var filterGroup = PathFilterGroup.create(filters);

        return performDiffWithFilter(filterGroup);
    }

    @Override
    public synchronized String diff() throws GitAPIException {
        var status = git.status().call();
        var trackedPaths = new HashSet<String>();
        trackedPaths.addAll(status.getModified());
        trackedPaths.addAll(status.getChanged());
        trackedPaths.addAll(status.getAdded());
        trackedPaths.addAll(status.getRemoved());
        trackedPaths.addAll(status.getMissing());

        if (trackedPaths.isEmpty()) {
            return "";
        }

        var filters = trackedPaths.stream()
                .map(PathFilter::create)
                .collect(Collectors.toCollection(ArrayList::new));
        var filterGroup = PathFilterGroup.create(filters);

        return performDiffWithFilter(filterGroup);
    }

    /**
     * Returns a set of uncommitted files with their status (new, modified, deleted).
     */
    public Set<ModifiedFile> getModifiedFiles() throws GitAPIException {
        var statusResult = git.status().call();
        var uncommittedFilesWithStatus = new HashSet<ModifiedFile>();

        // Collect all unique paths from the statuses we are interested in
        var allRelevantPaths = new HashSet<String>();
        allRelevantPaths.addAll(statusResult.getAdded());
        allRelevantPaths.addAll(statusResult.getRemoved());
        allRelevantPaths.addAll(statusResult.getMissing());
        allRelevantPaths.addAll(statusResult.getModified());
        allRelevantPaths.addAll(statusResult.getChanged());
        logger.trace("Raw modified files: {}", allRelevantPaths);

        for (var path : allRelevantPaths) {
            var projectFile = toProjectFile(path);
            String determinedStatus;

            // Determine status based on "git commit -a" behavior:
            // 1. Added files are "new".
            // 2. Files missing from working tree are "deleted".
            // 3. Otherwise, any other change (modified in WT, changed in index,
            //    or staged for removal but existing in WT) is "modified".
            if (statusResult.getAdded().contains(path)) {
                determinedStatus = "new";
            } else if (statusResult.getMissing().contains(path)) {
                determinedStatus = "deleted";
            } else if (statusResult.getModified().contains(path)
                    || statusResult.getChanged().contains(path)
                    || statusResult.getRemoved().contains(path)) {
                // If removed from index but present in WT, it's a modification for "commit -a"
                determinedStatus = "modified";
            } else {
                // This should not be reached if `path` originated from one of the status sets
                // used to populate `allRelevantPaths` and the logic above is complete.
                throw new AssertionError("Path " + path + " from relevant git status sets did not receive a determined status.");
            }
            uncommittedFilesWithStatus.add(new ModifiedFile(projectFile, determinedStatus));
        }

        logger.trace("Modified files: {}", uncommittedFilesWithStatus);
        return uncommittedFilesWithStatus;
    }

    /**
     * Commit a specific list of RepoFiles.
     *
     * @return The commit ID of the new commit
     */
    public String commitFiles(List<ProjectFile> files, String message) throws GitAPIException {
        add(files);
        var commitCommand = git.commit().setMessage(message);

        if (!files.isEmpty()) {
            for (var file : files) {
                commitCommand.setOnly(toRepoRelativePath(file));
            }
        }

        var commitResult = commitCommand.call();
        var commitId = commitResult.getId().getName();
        invalidateCaches();
        return commitId;
    }

    /**
     * Push the committed changes for the specified branch to the "origin" remote.
     * This method assumes the remote is "origin" and the remote branch has the same name as the local branch.
     * If the branch has a configured upstream, JGit might use that, but explicitly setting RefSpec ensures this branch is pushed.
     *
     * @param branchName The name of the local branch to push.
     * @throws GitAPIException if the push fails.
     */
    public void push(String branchName) throws GitAPIException {
        if (branchName.isBlank()) {
            throw new IllegalArgumentException("Branch name cannot be null or empty for push operation.");
        }

        logger.debug("Pushing branch {} to origin", branchName);
        var refSpec = new RefSpec(String.format("refs/heads/%s:refs/heads/%s", branchName, branchName));

        Iterable<PushResult> results = git.push()
                                          .setRemote("origin") // Default to "origin"
                                          .setRefSpecs(refSpec)
                                          .call();
        List<String> rejectionMessages = new ArrayList<>();

        for (var result : results) {
            for (var rru : result.getRemoteUpdates()) {
                var status = rru.getStatus();
                // Consider any status other than OK or UP_TO_DATE as a failure for that ref.
                if (!isPushSuccessful(status)) {
                    String message = "Ref '" + rru.getRemoteName() + "' (local '" + branchName + "') update failed: ";
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ||
                        status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                        message += "The remote contains work that you do not have locally. " +
                                "Pull and merge from the remote (or rebase) before pushing.";
                    } else {
                        message += status.toString();
                        if (rru.getMessage() != null) {
                            message += " (" + rru.getMessage() + ")";
                        }
                    }
                    rejectionMessages.add(message);
                }
            }
        }

        if (!rejectionMessages.isEmpty()) {
            throw new GitPushRejectedException("Push rejected by remote:\n" + String.join("\n", rejectionMessages));
        }
        // If loop completes without rejections, push was successful or refs were up-to-date.
    }

    /**
     * Pushes the given local branch to the specified remote,
     * creates upstream tracking for it, and returns the PushResult list.
     * Assumes the remote branch should have the same name as the local branch.
     */
    public Iterable<PushResult> pushAndSetRemoteTracking(String localBranchName, String remoteName) throws GitAPIException {
        return pushAndSetRemoteTracking(localBranchName, remoteName, localBranchName);
    }

    /**
     * Pushes the given local branch to the specified remote,
     * creates upstream tracking for it, and returns the PushResult list.
     */
    public Iterable<PushResult> pushAndSetRemoteTracking(String localBranchName, String remoteName, String remoteBranchName) throws GitAPIException {
        logger.debug("Pushing branch {} to {}/{} and setting up remote tracking", localBranchName, remoteName, remoteBranchName);
        var refSpec = new RefSpec(
                String.format("refs/heads/%s:refs/heads/%s", localBranchName, remoteBranchName)
        );

        // 1. Push the branch
        Iterable<PushResult> results = git.push()
                                          .setRemote(remoteName)
                                          .setRefSpecs(refSpec)
                                          .call();

        List<String> rejectionMessages = new ArrayList<>();
        for (var result : results) {
            for (var rru : result.getRemoteUpdates()) {
                var status = rru.getStatus();
                if (!isPushSuccessful(status)) {
                    String message = "Ref '" + rru.getRemoteName() + "' (local '" + localBranchName + "') update failed: ";
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ||
                        status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                        message += "The remote contains work that you do not have locally. " +
                                "Pull and merge from the remote (or rebase) before pushing.";
                    } else {
                        message += status.toString();
                        if (rru.getMessage() != null) {
                            message += " (" + rru.getMessage() + ")";
                        }
                    }
                    rejectionMessages.add(message);
                }
            }
        }

        if (!rejectionMessages.isEmpty()) {
            throw new GitPushRejectedException("Push rejected by remote:\n" + String.join("\n", rejectionMessages));
        }

        // 2. Record upstream info in config only if push was successful
        try {
            var config = repository.getConfig();
            config.setString("branch", localBranchName, "remote", remoteName);
            config.setString("branch", localBranchName, "merge", "refs/heads/" + remoteBranchName);
            config.save();
            logger.info("Successfully set up remote tracking for branch {} -> {}/{}", localBranchName, remoteName, remoteBranchName);
        } catch (IOException e) {
            throw new GitRepoException("Push to " + remoteName + "/" + remoteBranchName + " succeeded, but failed to set up remote tracking configuration for " + localBranchName, e);
        }

        invalidateCaches();

        return results;
    }

    /**
     * Fetches all remotes with pruning, reporting progress to the given monitor.
     *
     * @param pm The progress monitor to report to.
     * @throws GitAPIException if a Git error occurs.
     */
    public void fetchAll(ProgressMonitor pm) throws GitAPIException {
        for (String remote : repository.getRemoteNames()) {
            git.fetch()
               .setRemote(remote)
               .setRemoveDeletedRefs(true)   // --prune
               .setProgressMonitor(pm)
               .call();
        }
        invalidateCaches(); // Invalidate caches & ref-db
    }

    /**
     * Pull changes from the remote repository for the current branch
     */
    public void pull() throws GitAPIException {
        git.pull().call();
    }

    /**
     * Get a set of commit IDs that exist in the local branch but not in its remote tracking branch
     */
    public Set<String> getUnpushedCommitIds(String branchName) throws GitAPIException {
        var unpushedCommits = new HashSet<String>();
        var trackingBranch = getTrackingBranch(branchName);
        if (trackingBranch == null) {
            return unpushedCommits;
        }

        var branchRef = "refs/heads/" + branchName;
        var trackingRef = "refs/remotes/" + trackingBranch;

        var localObjectId = resolve(branchRef);
        var remoteObjectId = resolve(trackingRef);

        try (var revWalk = new RevWalk(repository)) {
            try {
                revWalk.markStart(revWalk.parseCommit(localObjectId));
                revWalk.markUninteresting(revWalk.parseCommit(remoteObjectId));
            } catch (IOException e) {
                throw new GitWrappedIOException(e);
            }

            revWalk.forEach(commit -> unpushedCommits.add(commit.getId().getName()));
        }
        return unpushedCommits;
    }

    /**
     * Check if a local branch has a configured upstream (tracking) branch
     */
    public boolean hasUpstreamBranch(String branchName) {
        return getTrackingBranch(branchName) != null;
    }

    /**
     * Get the tracking branch name for a local branch
     */
    private @Nullable String getTrackingBranch(String branchName) {
        try {
            var config = repository.getConfig();
            var trackingBranch = config.getString("branch", branchName, "remote");
            var remoteBranch = config.getString("branch", branchName, "merge");

            if (trackingBranch != null && remoteBranch != null) {
                if (remoteBranch.startsWith("refs/heads/")) {
                    remoteBranch = remoteBranch.substring("refs/heads/".length());
                }
                return trackingBranch + "/" + remoteBranch;
            }
            return null;
        } catch (Exception e) {
            // Return null if there's any unexpected config or parse issue
            return null;
        }
    }

    /**
     * List all local branches
     */
    public List<String> listLocalBranches() throws GitAPIException {
        var branches = new ArrayList<String>();
        for (var ref : git.branchList().call()) {
            branches.add(ref.getName().replaceFirst("^refs/heads/", ""));
        }
        return branches;
    }

    /**
     * List all remote branches
     */
    public List<String> listRemoteBranches() throws GitAPIException {
        var branches = new ArrayList<String>();
        for (var ref : git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()) {
            branches.add(ref.getName().replaceFirst("^refs/remotes/", ""));
        }
        return branches;
    }

    /**
     * List all tags in the repository, sorted by their commit/tag date
     * in descending order (newest first).
     */
    public List<String> listTags() throws GitAPIException {
        try (var revWalk = new RevWalk(repository)) {
            record TagInfo(String name, Instant date) {}
            var tagInfos = new ArrayList<TagInfo>();

            for (var ref : git.tagList().call()) {
                String tagName = ref.getName().replaceFirst("^refs/tags/", "");
                Instant date = Instant.EPOCH;

                try {
                    // Handle annotated vs lightweight tags
                    var objId = (ref.getPeeledObjectId() != null)
                                ? ref.getPeeledObjectId()
                                : ref.getObjectId();
                    var revObj = revWalk.parseAny(objId);

                    if (revObj instanceof RevCommit commit) {
                        date = commit.getCommitterIdent().getWhenAsInstant();
                    } else if (revObj instanceof RevTag tag) {
                        if (tag.getTaggerIdent() != null) {
                            date = tag.getTaggerIdent().getWhenAsInstant();
                        }
                    }
                } catch (Exception e) {
                    // If we cannot resolve the tag date, leave it as Instant.EPOCH
                    logger.debug("Could not resolve date for tag '{}': {}", tagName, e.getMessage());
                }

                tagInfos.add(new TagInfo(tagName, date));
            }

            return tagInfos.stream()
                           .sorted((a, b) -> b.date().compareTo(a.date())) // newest first
                           .map(TagInfo::name)
                           .collect(Collectors.toList());
        }
    }

    /**
     * True iff {@code branchName} is one of this repository’s **local** branches.
     * Falls back to {@code false} if the branch list cannot be obtained.
     */
    public boolean isLocalBranch(String branchName)
    {
        try {
            return listLocalBranches().contains(branchName);
        } catch (GitAPIException e) {
            logger.warn("Unable to enumerate local branches", e);
            return false;
        }
    }

    /**
     * True iff {@code branchName} is one of this repository’s **remote** branches
     * (e.g. origin/main).  Falls back to {@code false} on error.
     */
    public boolean isRemoteBranch(String branchName)
    {
        try {
            return listRemoteBranches().contains(branchName);
        } catch (GitAPIException e) {
            logger.warn("Unable to enumerate remote branches", e);
            return false;
        }
    }

    /**
     * Checkout a specific branch
     */
    public void checkout(String branchName) throws GitAPIException {
        git.checkout().setName(branchName).call();
        invalidateCaches();
    }

    /**
     * Checkout a remote branch, creating a local tracking branch
     * with the default naming convention (using the remote branch name)
     */
    public void checkoutRemoteBranch(String remoteBranchName) throws GitAPIException {
        String branchName;
        if (remoteBranchName.contains("/")) {
            branchName = remoteBranchName.substring(remoteBranchName.indexOf('/') + 1);
        } else {
            branchName = remoteBranchName;
        }
        checkoutRemoteBranch(remoteBranchName, branchName);
    }

    /**
     * Create a new branch from an existing one without checking it out
     */
    public void createBranch(String newBranchName, String sourceBranchName) throws GitAPIException {
        if (listLocalBranches().contains(newBranchName)) {
            throw new GitStateException("Branch '" + newBranchName + "' already exists");
        }

        logger.debug("Creating new branch '{}' from '{}'", newBranchName, sourceBranchName);
        git.branchCreate()
                .setName(newBranchName)
                .setStartPoint(sourceBranchName)
                .call();
        logger.debug("Successfully created branch '{}'", newBranchName);

        invalidateCaches();
    }

    /**
     * Creates a new branch from a specific commit.
     * The newBranchName should ideally be the result of {@link #sanitizeBranchName(String)}
     * to ensure it's valid and unique.
     *
     * @param newBranchName The name for the new branch.
     * @param sourceCommitId The commit ID to base the new branch on.
     * @throws GitAPIException if a Git error occurs.
     */
    public void createBranchFromCommit(String newBranchName, String sourceCommitId) throws GitAPIException {
        if (listLocalBranches().contains(newBranchName)) {
            throw new GitStateException("Branch '" + newBranchName + "' already exists. Use sanitizeBranchName to get a unique name.");
        }

        logger.debug("Creating new branch '{}' from commit '{}'", newBranchName, sourceCommitId);
        git.branchCreate()
                .setName(newBranchName)
                .setStartPoint(sourceCommitId) // JGit's setStartPoint can take a commit SHA
                .call();
        logger.debug("Successfully created branch '{}' from commit '{}'", newBranchName, sourceCommitId);

        invalidateCaches();
    }

    /**
     * Create a new branch from an existing one and check it out
     */
    public void createAndCheckoutBranch(String newBranchName, String sourceBranchName) throws GitAPIException {
        if (listLocalBranches().contains(newBranchName)) {
            throw new GitStateException("Branch '" + newBranchName + "' already exists. Use sanitizeBranchName to get a unique name.");
        }

        logger.debug("Creating new branch '{}' from '{}'", newBranchName, sourceBranchName);
        git.checkout()
                .setCreateBranch(true)
                .setName(newBranchName)
                .setStartPoint(sourceBranchName)
                .call();
        logger.debug("Successfully created and checked out branch '{}'", newBranchName);

        invalidateCaches();
    }

    /**
     * Checkout a remote branch, creating a local tracking branch with a specified name
     */
    public void checkoutRemoteBranch(String remoteBranchName, String localBranchName) throws GitAPIException {
        boolean remoteBranchExists = false;
        try {
            var remoteRef = repository.findRef("refs/remotes/" + remoteBranchName);
            remoteBranchExists = (remoteRef != null);
            logger.debug("Checking if remote branch exists: {} -> {}", remoteBranchName,
                         remoteBranchExists ? "yes" : "no");
        } catch (Exception e) {
            logger.warn("Error checking remote branch: {}", e.getMessage());
        }

        if (!remoteBranchExists) {
            throw new GitStateException("Remote branch '" + remoteBranchName + "' not found. Ensure the remote exists and has been fetched.");
        }

        if (listLocalBranches().contains(localBranchName)) {
            throw new GitStateException("Local branch '" + localBranchName + "' already exists. Choose a different name.");
        }

        logger.debug("Creating local branch '{}' from remote '{}'", localBranchName, remoteBranchName);
        git.checkout()
                .setCreateBranch(true)
                .setName(localBranchName)
                .setStartPoint(remoteBranchName)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call();
        logger.debug("Successfully created and checked out branch '{}'", localBranchName);

        invalidateCaches();
    }

    /**
     * Rename a branch
     */
    public void renameBranch(String oldName, String newName) throws GitAPIException {
        git.branchRename().setOldName(oldName).setNewName(newName).call();
        invalidateCaches();
    }

    /**
     * Check if a branch is fully merged into HEAD
     */
    public boolean isBranchMerged(String branchName) throws GitAPIException {
        var mergedBranches = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .setContains("HEAD")
                .call();
        for (var ref : mergedBranches) {
            var name = ref.getName().replaceFirst("^refs/heads/", "");
            if (name.equals(branchName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Delete a branch
     */
    public void deleteBranch(String branchName) throws GitAPIException {
        logger.debug("Attempting to delete branch: {}", branchName);
        var result = git.branchDelete().setBranchNames(branchName).call();
        if (result.isEmpty()) {
            logger.warn("Branch deletion returned empty result for branch: {}", branchName);
            throw new GitStateException("Branch deletion failed: No results returned. Branch may not exist or requires force delete.");
        }
        for (var deletedRef : result) {
            logger.debug("Successfully deleted branch reference: {}", deletedRef);
        }
    }

    /**
     * Force delete a branch even if it's not fully merged
     */
    public void forceDeleteBranch(String branchName) throws GitAPIException {
        logger.debug("Attempting to force delete branch: {}", branchName);
        var result = git.branchDelete().setBranchNames(branchName).setForce(true).call();
        if (result.isEmpty()) {
            logger.warn("Force branch deletion returned empty result for branch: {}", branchName);
            throw new GitStateException("Force branch deletion failed: No results returned. Branch may not exist.");
        }
        for (var deletedRef : result) {
            logger.debug("Successfully force deleted branch reference: {}", deletedRef);
        }
    }

    /**
     * Merge a branch into HEAD
     *
     * @return The result of the merge operation.
     */
    public MergeResult mergeIntoHead(String branchName) throws GitAPIException {
        var result = git.merge().include(resolve(branchName)).call();

        logger.trace("Merge result status: {}", result.getMergeStatus());
        logger.trace("isSuccessful(): {}", result.getMergeStatus().isSuccessful());
        logger.trace("isMergeSuccessful() utility: {}", isMergeSuccessful(result, MergeMode.MERGE_COMMIT));

        invalidateCaches();
        return result;
    }

    /**
     * Perform a squash merge of the specified branch into HEAD.
     *
     * @param branchName The branch to squash merge
     * @return The result of the squash merge operation
     * @throws GitAPIException if the squash merge fails
     */
    @Override
    public MergeResult squashMergeIntoHead(String branchName) throws GitAPIException {
        logger.trace("Squash merging '{}' into HEAD", branchName);
        String targetBranch = getCurrentBranch();

        // Build squash commit message
        String squashCommitMessage;
        try {
            var commitMessages = getCommitMessagesBetween(branchName, targetBranch);
            String header = "Squash merge branch '" + branchName + "' into '" + targetBranch + "'\n\n";
            String body = commitMessages.isEmpty()
                    ? "- No individual commit messages found between " + branchName + " and " + targetBranch + "."
                    : commitMessages.stream()
                            .map(msg -> "- " + msg)
                            .collect(Collectors.joining("\n"));
            squashCommitMessage = header + body;
        } catch (GitAPIException e) {
            logger.error("Failed to get commit messages between {} and {}: {}", branchName, targetBranch, e.getMessage(), e);
            throw e;
        }

        // Perform squash merge
        ObjectId resolvedBranch = resolve(branchName);

        // Check repository state before merge
        var status = git.status().call();
        logger.trace("Working tree clean: {}", status.isClean());
        logger.trace("Added files: {}", status.getAdded());
        logger.trace("Changed files: {}", status.getChanged());
        logger.trace("Modified files: {}", status.getModified());
        logger.trace("Removed files: {}", status.getRemoved());
        logger.trace("Missing files: {}", status.getMissing());
        logger.trace("Untracked files: {}", status.getUntracked());
        logger.trace("Conflicting files: {}", status.getConflicting());

        // Check for staged changes, modified tracked files, or conflicting untracked files
        if (!status.getAdded().isEmpty() || !status.getChanged().isEmpty()) {
            throw new WorktreeDirtyException("Cannot perform squash merge with staged changes. Please commit or reset them first.");
        }
        if (!status.getModified().isEmpty()) {
            throw new WorktreeDirtyException("Cannot perform squash merge with modified but uncommitted files. Please commit or stash them first.");
        }

        // Check for untracked files in the target worktree that would be overwritten by the merge
        var changedFiles = listFilesChangedBetweenBranches(branchName, targetBranch).stream()
                                                                                    .map(mf -> mf.file().toString())
                                                                                    .collect(Collectors.toSet());
        var untrackedFiles = status.getUntracked();
        var conflictingUntracked = untrackedFiles.stream()
                                                 .filter(changedFiles::contains)
                                                 .collect(Collectors.toSet());

        if (!conflictingUntracked.isEmpty()) {
            throw new WorktreeDirtyException("The following untracked working tree files would be overwritten by merge: "
                                           + String.join(", ", conflictingUntracked));
        }

        // Perform squash merge
        var squashResult = git.merge()
                .setSquash(true)
                .include(resolvedBranch)
                .call();

        logger.debug("Squash merge result status: {}", squashResult.getMergeStatus());
        logger.debug("isSuccessful(): {}", squashResult.getMergeStatus().isSuccessful());
        logger.debug("isMergeSuccessful() utility: {}", isMergeSuccessful(squashResult, MergeMode.SQUASH_COMMIT));
        logger.debug("Merge result conflicts: {}", squashResult.getConflicts());
        logger.debug("Merge result failing paths: {}", squashResult.getFailingPaths());
        logger.debug("Merge result checkout conflicts: {}", squashResult.getCheckoutConflicts());

        if (!isMergeSuccessful(squashResult, MergeMode.SQUASH_COMMIT)) {
            logger.warn("Squash merge failed with status: {}", squashResult.getMergeStatus());

            // Provide more specific error information
            if (squashResult.getFailingPaths() != null && !squashResult.getFailingPaths().isEmpty()) {
                String errorDetails = squashResult.getFailingPaths().entrySet().stream()
                    .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                    .collect(Collectors.joining(", "));
                logger.error("Squash merge conflicts: {}", errorDetails);
                invalidateCaches();
                throw new GitAPIException("Squash merge failed due to conflicts in: " + errorDetails) {};
            }

            invalidateCaches();
            return squashResult;
        }

        // Commit the squashed changes
        try {
            git.commit().setMessage(squashCommitMessage).call();
            invalidateCaches();
            return squashResult;
        } catch (GitAPIException e) {
            logger.error("Failed to commit squashed changes: {}", e.getMessage(), e);
            invalidateCaches();
            throw e;
        }
    }

    /**
     * Perform a rebase merge of the specified branch into HEAD.
     * This creates a temporary branch, rebases it onto the target, then fast-forward merges.
     *
     * @param branchName The branch to rebase merge
     * @return The result of the final fast-forward merge
     * @throws GitAPIException if any step of the rebase merge fails
     */
    @Override
    public MergeResult rebaseMergeIntoHead(String branchName) throws GitAPIException {
        String targetBranch = getCurrentBranch();
        String originalBranch = targetBranch;
        String tempRebaseBranchName = null;

        try {
            // Create temporary branch for rebase
            tempRebaseBranchName = createTempRebaseBranchName(branchName);
            createBranch(tempRebaseBranchName, branchName);
            checkout(tempRebaseBranchName);

            // Rebase the temporary branch onto target
            ObjectId resolvedTarget = resolve(targetBranch);
            var rebaseResult = git.rebase()
                    .setUpstream(resolvedTarget)
                    .call();

            if (!isRebaseSuccessful(rebaseResult)) {
                // Attempt to abort rebase
                try {
                    if (!getCurrentBranch().equals(tempRebaseBranchName)) {
                        checkout(tempRebaseBranchName);
                    }
                    git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT).call();
                } catch (GitAPIException abortEx) {
                    logger.error("Failed to abort rebase for {}", tempRebaseBranchName, abortEx);
                }
                throw new GitAPIException("Rebase of '" + branchName + "' onto '" + targetBranch + "' failed: " + rebaseResult.getStatus()) {};
            }

            // Switch back to target branch and fast-forward merge
            checkout(targetBranch);
            MergeResult ffMergeResult = mergeIntoHead(tempRebaseBranchName);

            if (!ffMergeResult.getMergeStatus().isSuccessful()) {
                throw new GitAPIException("Fast-forward merge of rebased '" + tempRebaseBranchName + "' into '" + targetBranch + "' failed: " + ffMergeResult.getMergeStatus()) {};
            }

            invalidateCaches();
            return ffMergeResult;

        } finally {
            // Cleanup: ensure we're on the original branch and delete temp branch
            try {
                if (!getCurrentBranch().equals(originalBranch)) {
                    checkout(originalBranch);
                }
            } catch (GitAPIException e) {
                logger.error("Error ensuring checkout to target branch '{}' during rebase cleanup", originalBranch, e);
            }

            if (tempRebaseBranchName != null) {
                try {
                    forceDeleteBranch(tempRebaseBranchName);
                } catch (GitAPIException e) {
                    logger.error("Failed to delete temporary rebase branch {}", tempRebaseBranchName, e);
                }
            }
        }
    }

    /**
     * Perform a merge operation with the specified mode.
     *
     * @param branchName The branch to merge
     * @param mode The merge mode (MERGE_COMMIT, SQUASH_COMMIT, or REBASE_MERGE)
     * @return The result of the merge operation
     * @throws GitAPIException if the merge fails
     */
    @Override
    public MergeResult performMerge(String branchName, MergeMode mode) throws GitAPIException {
        logger.debug("performMerge called with branch: {} and mode: {}", branchName, mode);
        try {
            return switch (mode) {
                case MERGE_COMMIT -> {
                    logger.trace("Performing merge commit");
                    yield mergeIntoHead(branchName);
                }
                case SQUASH_COMMIT -> {
                    logger.trace("Performing squash commit");
                    yield squashMergeIntoHead(branchName);
                }
                case REBASE_MERGE -> {
                    logger.trace("Performing rebase merge");
                    yield rebaseMergeIntoHead(branchName);
                }
            };
        } catch (GitAPIException e) {
            logger.error("performMerge failed for branch: {} with mode: {}: {}", branchName, mode, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Revert a specific commit
     */
    public void revertCommit(String commitId) throws GitAPIException {
        try {
            var resolvedCommit = repository.resolve(commitId);
            if (resolvedCommit == null) {
                throw new GitRepoException("Unable to resolve commit: " + commitId, new NoSuchElementException());
            }
            git.revert().include(resolvedCommit).call();
        } catch (IOException e) {
            throw new GitRepoException("Unable to resolve" + commitId, e);
        }
        invalidateCaches();
    }

    /**
     * Perform a soft reset to a specific commit
     */
    public void softReset(String commitId) throws GitAPIException {
        git.reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT)
                .setRef(commitId)
                .call();
        invalidateCaches();
    }

    /**
     * Checkout specific files from a commit, restoring them to their state at that commit.
     * This is equivalent to `git checkout <commitId> -- <files>`
     */
    public void checkoutFilesFromCommit(String commitId, List<ProjectFile> files) throws GitAPIException {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No files specified for checkout");
        }

        logger.debug("Checking out {} files from commit {}", files.size(), commitId);

        var checkoutCommand = git.checkout()
                .setStartPoint(commitId);

        // Add each file path to the checkout command
        for (ProjectFile file : files) {
            var relativePath = toRepoRelativePath(file);
            checkoutCommand.addPath(relativePath);
            logger.trace("Adding file to checkout: {}", relativePath);
        }

        checkoutCommand.call();
        invalidateCaches();

        logger.debug("Successfully checked out {} files from commit {}", files.size(), commitId);
    }

    /**
     * Get current branch name
     */
    @Override
    public String getCurrentBranch() throws GitAPIException {
        try {
            var branch = repository.getBranch();
            if (branch == null) {
                throw new GitRepoException("Repository has no HEAD", new NullPointerException());
            }
            return branch;
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * Retrieves basic information (message, author, date) for a single commit from the local repository.
     *
     * @param commitId The SHA of the commit.
     * @return An Optional containing the CommitInfo if the commit is found, otherwise an empty Optional.
     * @throws GitAPIException if there's an error accessing Git data.
     */
    public Optional<CommitInfo> getLocalCommitInfo(String commitId) throws GitAPIException {
        var objectId = resolve(commitId);

        try (var revWalk = new RevWalk(repository)) {
            var revCommit = revWalk.parseCommit(objectId);
            return Optional.of(this.fromRevCommit(revCommit));
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    // getStashesAsCommits removed, functionality moved to listStashes

    /**
     * A record to hold a modified file and its status.
     */
    public record ModifiedFile(ProjectFile file, String status) {
    }

    /**
     * List commits with detailed information for a specific branch
     */
    public List<CommitInfo> listCommitsDetailed(String branchName) throws GitAPIException {
        var commits = new ArrayList<CommitInfo>();
        var logCommand = git.log();

        if (!branchName.isEmpty()) {
            try {
                logCommand.add(resolve(branchName));
            } catch (MissingObjectException | IncorrectObjectTypeException e) {
                throw new GitWrappedIOException(e);
            }
        }

        for (var commit : logCommand.call()) {
            // Use factory method
            commits.add(this.fromRevCommit(commit));
        }
        return commits;
    }

    private List<ProjectFile> extractFilesFromDiffEntries(List<DiffEntry> diffs) {
        var fileSet = new HashSet<String>();
        for (var diff : diffs) {
            if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
                fileSet.add(diff.getOldPath());
            } else if (diff.getChangeType() == DiffEntry.ChangeType.ADD || diff.getChangeType() == DiffEntry.ChangeType.COPY) {
                fileSet.add(diff.getNewPath());
            } else { // MODIFY, RENAME
                fileSet.add(diff.getNewPath()); // new path is usually the one of interest
                if (diff.getOldPath() != null && !diff.getOldPath().equals(diff.getNewPath())) {
                    fileSet.add(diff.getOldPath()); // For renames, include old path too
                }
            }
        }
        return fileSet.stream()
                .filter(path -> !"/dev/null".equals(path))
                .sorted() // Sort paths alphabetically for consistent ordering
                .map(this::toProjectFile)
                .collect(Collectors.toList());
    }

    /**
     * Lists files changed in a specific commit compared to its primary parent.
     * For an initial commit, lists all files in that commit.
     */
    public List<ProjectFile> listFilesChangedInCommit(String commitId) throws GitAPIException {
        var commitObjectId = resolve(commitId);

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(commitObjectId);
            var newTree = commit.getTree();
            RevTree oldTree = null;

            if (commit.getParentCount() > 0) {
                var parentCommit = revWalk.parseCommit(commit.getParent(0).getId());
                oldTree = parentCommit.getTree();
            }

            try (var diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) { // Output stream is not used for listing files
                diffFormatter.setRepository(repository);
                List<DiffEntry> diffs;
                if (oldTree == null) { // Initial commit
                    // EmptyTreeIterator is not AutoCloseable
                    var emptyTreeIterator = new EmptyTreeIterator();
                    diffs = diffFormatter.scan(emptyTreeIterator, new CanonicalTreeParser(null, repository.newObjectReader(), newTree));
                } else {
                    diffs = diffFormatter.scan(oldTree, newTree);
                }
                return extractFilesFromDiffEntries(diffs);
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * Lists files changed between two commit SHAs (from oldCommitId to newCommitId).
     */
    @Override
    public List<ProjectFile> listFilesChangedBetweenCommits(String newCommitId, String oldCommitId) throws GitAPIException {
        var newObjectId = resolve(newCommitId);
        var oldObjectId = resolve(oldCommitId);

        if (newObjectId.equals(oldObjectId)) {
            logger.debug("listFilesChangedBetweenCommits: newCommitId and oldCommitId are the same ('{}'). Returning empty list.", newCommitId);
            return List.of();
        }

        try (var revWalk = new RevWalk(repository)) {
            var newCommit = revWalk.parseCommit(newObjectId);
            var oldCommit = revWalk.parseCommit(oldObjectId);

            try (var diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) { // Output stream is not used for listing files
                diffFormatter.setRepository(repository);
                var diffs = diffFormatter.scan(oldCommit.getTree(), newCommit.getTree());
                return extractFilesFromDiffEntries(diffs);
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }


    /**
     * List changed RepoFiles in a commit range.
     *
     * @deprecated Prefer listFilesChangedInCommit or listFilesChangedBetweenCommits for clarity.
     * This method diffs (lastCommitId + "^") vs (firstCommitId).
     */
    @Override
    @Deprecated
    public List<ProjectFile> listChangedFilesInCommitRange(String firstCommitId, String lastCommitId) throws GitAPIException {
        var firstCommitObj = resolve(firstCommitId);
        var lastCommitObj = resolve(lastCommitId + "^"); // Note the parent operator here

        try (var revWalk = new RevWalk(repository)) {
            var firstCommit = revWalk.parseCommit(firstCommitObj); // "new"
            var lastCommitParent = revWalk.parseCommit(lastCommitObj); // "old"
            try (var diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) {
                diffFormatter.setRepository(repository);
                var diffs = diffFormatter.scan(lastCommitParent.getTree(), firstCommit.getTree());
                return extractFilesFromDiffEntries(diffs);
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * Show diff between two commits (or a commit and the working directory if newCommitId == HEAD).
     */
    @Override
    public String showDiff(String newCommitId, String oldCommitId) throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            logger.debug("Generating diff from {} to {}", oldCommitId, newCommitId);

            var oldTreeIter = prepareTreeParser(oldCommitId);
            if (oldTreeIter == null) {
                logger.warn("Old commit/tree {} not found. Returning empty diff.", oldCommitId);
                return "";
            }

            if ("HEAD".equals(newCommitId)) {
                git.diff()
                        .setOldTree(oldTreeIter)
                        .setNewTree(null) // Working tree
                        .setOutputStream(out)
                        .call();
            } else {
                var newTreeIter = prepareTreeParser(newCommitId);
                if (newTreeIter == null) {
                    logger.warn("New commit/tree {} not found. Returning empty diff.", newCommitId);
                    return "";
                }

                git.diff()
                        .setOldTree(oldTreeIter)
                        .setNewTree(newTreeIter)
                        .setOutputStream(out)
                        .call();
            }

            var result = out.toString(StandardCharsets.UTF_8);
            logger.debug("Generated diff of {} bytes", result.length());
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Retrieves the contents of {@code file} at a given commit ID, or returns an empty string if not found.
     */
    @Override
    public String getFileContent(String commitId, ProjectFile file) throws GitAPIException {
        if (commitId.isBlank()) {
            logger.debug("getFileContent called with blank commitId; returning empty string");
            return "";
        }

        var objId = resolve(commitId);

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            var tree = commit.getTree();
            try (var treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                String targetPath = toRepoRelativePath(file);
                while (treeWalk.next()) {
                    if (treeWalk.getPathString().equals(targetPath)) {
                        var blobId = treeWalk.getObjectId(0);
                        var loader = repository.open(blobId);
                        return new String(loader.getBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
        logger.debug("File '{}' not found at commit '{}'", file, commitId);
        return "";
    }

    @Override
    public ObjectId resolve(String revstr) throws GitAPIException {
        try {
            var id = repository.resolve(revstr);
            if (id == null) {
                throw new GitRepoException("Unable to resolve " + revstr, new NoSuchElementException());
            }
            return id;
        } catch (IOException e) {
            throw new GitRepoException("Unable to resolve " + revstr, e);
        }
    }

    /**
     * Show diff for a specific file between two commits.
     */
    @Override
    public String showFileDiff(String commitIdA, String commitIdB, ProjectFile file) throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            var pathFilter = PathFilter.create(toRepoRelativePath(file));
            if ("HEAD".equals(commitIdA)) {
                git.diff()
                        .setOldTree(prepareTreeParser(commitIdB))
                        .setNewTree(null) // Working tree
                        .setPathFilter(pathFilter)
                        .setOutputStream(out)
                        .call();
            } else {
                git.diff()
                        .setOldTree(prepareTreeParser(commitIdB))
                        .setNewTree(prepareTreeParser(commitIdA))
                        .setPathFilter(pathFilter)
                        .setOutputStream(out)
                        .call();
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Prepares an AbstractTreeIterator for the given commit-ish string.
     */
    private @Nullable CanonicalTreeParser prepareTreeParser(String objectId) throws GitAPIException {
        if (objectId.isBlank()) {
            logger.warn("prepareTreeParser called with blank ref. Returning null iterator.");
            return null;
        }

        var objId = resolve(objectId);

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            var treeId = commit.getTree().getId();
            try (var reader = repository.newObjectReader()) {
                return new CanonicalTreeParser(null, reader, treeId);
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * Create a stash from the current changes
     */
    public void createStash(String message) throws GitAPIException {
        assert !message.isEmpty();
        logger.debug("Creating stash with message: {}", message);
        var stashId = git.stashCreate()
                .setWorkingDirectoryMessage(message)
                .call();
        logger.debug("Stash created with ID: {}", (stashId != null ? stashId.getName() : "none"));
        invalidateCaches();
    }

    /**
     * Create a stash containing only the specified files.
     * This involves a more complex workflow:
     * 1. Get all uncommitted files
     * 2. Add UN-selected files to index (i.e., everything EXCEPT the files we want to stash)
     * 3. Commit those unselected files to a temporary branch
     * 4. Stash what's left (which will be only our selected files)
     * 5. Soft-reset back to restore the working directory with the UN-selected files uncommitted
     * 6. Clean up the temporary branch
     *
     * @param message      The stash message
     * @param filesToStash The specific files to include in the stash
     * @throws GitAPIException If there's an error during the stash process
     */
    public void createPartialStash(String message, List<ProjectFile> filesToStash) throws GitAPIException {
        assert !message.isEmpty();
        assert !filesToStash.isEmpty();

        logger.debug("Creating partial stash with message: {} for {} files", message, filesToStash.size());

        var allUncommittedFilesWithStatus = getModifiedFiles();
        var allUncommittedProjectFiles = allUncommittedFilesWithStatus.stream()
                .map(ModifiedFile::file)
                .collect(Collectors.toSet());
        if (!allUncommittedProjectFiles.containsAll(new HashSet<>(filesToStash))) {
            throw new GitStateException("Files to stash are not actually uncommitted!?");
        }

        // Files NOT to stash
        var filesToCommit = allUncommittedFilesWithStatus.stream()
                .map(ModifiedFile::file)
                .filter(file -> !filesToStash.contains(file))
                .collect(Collectors.toList());

        if (filesToCommit.isEmpty()) {
            // If all changed files are selected for stashing, just do a regular stash
            logger.debug("All changed files selected for stashing, using regular stash");
            createStash(message);
            return;
        }

        // Remember the original branch
        String originalBranch = getCurrentBranch();
        String tempBranchName = "temp-stash-branch-" + System.currentTimeMillis();

        // Add the UN-selected files to the index
        add(filesToCommit);

        // Create a temporary branch and commit those files
        logger.debug("Creating temporary branch: {}", tempBranchName);
        git.branchCreate().setName(tempBranchName).call();

        logger.debug("Committing UN-selected files to temporary branch");
        git.commit()
                .setMessage("Temporary commit to facilitate partial stash")
                .call();

        // Now stash the remaining changes
        logger.debug("Creating stash with only the selected files");
        var stashId = git.stashCreate().setWorkingDirectoryMessage(message).call();
        logger.debug("Partial stash created with ID: {}", (stashId != null ? stashId.getName() : "none"));

        // Soft reset to restore uncommitted files
        logger.debug("Soft resetting to restore UN-selected files as uncommitted");
        git.reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT)
                .setRef("HEAD~1")
                .call();

        // Checkout the original branch
        logger.debug("Checking out original branch: {}", originalBranch);
        git.checkout().setName(originalBranch).call();

        // Delete the temporary branch
        logger.debug("Deleting temporary branch");
        git.branchDelete().setBranchNames(tempBranchName).setForce(true).call();

        invalidateCaches();
    }

    // StashInfo record removed

    /**
     * Lists all stashes in the repository as CommitInfo objects.
     */
    public List<CommitInfo> listStashes() throws GitAPIException {
        var stashes = new ArrayList<CommitInfo>();
        var collection = git.stashList().call();
        int index = 0;

        // Iterate through stashes, creating CommitInfo for each
        for (var stashCommit : collection) {
            // Use factory method for stash commits, passing the index
            stashes.add(this.fromStashCommit(stashCommit, index));
            index++;
        }
        return stashes;
    }

    /**
     * Gets additional commits from a stash (index, untracked files)
     */
    public Map<String, CommitInfo> listAdditionalStashCommits(String stashRef) throws GitAPIException {
        Map<String, CommitInfo> additionalCommits = new HashMap<>();
        var rev = resolve(stashRef);

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(rev);

            // stash@{0} - main stash commit (merge).
            // stash@{0}^1 - original HEAD
            // stash@{0}^2 - index changes
            // stash@{0}^3 - untracked changes (only if stash was created with -u / -a)

            if (commit.getParentCount() < 2) {
                logger.warn("Stash {} is not a merge commit, which is unexpected", stashRef);
                return additionalCommits;
            }

            var headCommit = commit.getParent(0);
            var indexCommit = commit.getParent(1);
            revWalk.parseHeaders(headCommit);
            revWalk.parseHeaders(indexCommit);

            // Compare HEAD tree vs index tree
            try (var diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) {
                diffFormatter.setRepository(repository);
                var diffs = diffFormatter.scan(headCommit.getTree(), indexCommit.getTree());
                if (!diffs.isEmpty()) {
                    // Use factory method
                    additionalCommits.put("index", this.fromRevCommit(indexCommit));
                }
            }

            // Check for untracked commit
            if (commit.getParentCount() > 2) {
                var untrackedCommit = commit.getParent(2);
                revWalk.parseHeaders(untrackedCommit);
                boolean hasFiles = false;
                try (var treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(untrackedCommit.getTree());
                    treeWalk.setRecursive(true);
                    hasFiles = treeWalk.next();
                }
                if (hasFiles) {
                    // Use factory method
                    additionalCommits.put("untracked", this.fromRevCommit(untrackedCommit));
                }
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }

        return additionalCommits;
    }

    /**
     * Apply a stash to the working directory without removing it from the stash list
     */
    public void applyStash(int stashIndex) throws GitAPIException {
        String stashRef = "stash@{" + stashIndex + "}";
        logger.debug("Applying stash: {}", stashRef);
        git.stashApply()
                .setStashRef(stashRef)
                .call();
        logger.debug("Stash applied successfully");
        invalidateCaches();
    }

    /**
     * Pop a stash – apply it, then drop it
     */
    public void popStash(int stashIndex) throws GitAPIException {
        var stashRef = "stash@{" + stashIndex + "}";
        logger.debug("Popping stash {}", stashRef);
        git.stashApply()
                .setStashRef(stashRef)
                .setRestoreIndex(false)
                .setRestoreUntracked(true)
                .ignoreRepositoryState(true)
                .call();
        git.stashDrop().setStashRef(stashIndex).call();
        logger.debug("Stash pop completed successfully");
        invalidateCaches();
    }

    /**
     * Drop a stash without applying it
     */
    public void dropStash(int stashIndex) throws GitAPIException {
        logger.debug("Dropping stash at index: {}", stashIndex);
        git.stashDrop()
                .setStashRef(stashIndex)
                .call();
        logger.debug("Stash dropped successfully");
        invalidateCaches();
    }

    /**
      * Returns the full (multi-line) commit message for the given commit id.
      */
     public String getCommitFullMessage(String commitId) throws GitAPIException {
         var objId = resolve(commitId);
         try (var revWalk = new RevWalk(repository)) {
             var commit = revWalk.parseCommit(objId);
             return commit.getFullMessage();
         } catch (IOException e) {
             throw new GitWrappedIOException(e);
         }
     }
 
     /**
      * Get the commit history for a specific file
      */
     public List<CommitInfo> getFileHistory(ProjectFile file) throws GitAPIException {
        var commits = new ArrayList<CommitInfo>();
        for (var commit : git.log().addPath(toRepoRelativePath(file)).call()) {
            // Use factory method
            commits.add(this.fromRevCommit(commit));
        }
        return commits;
    }

    /**
     * Get the URL of the specified remote (defaults to "origin")
     */
    public @Nullable String getRemoteUrl(String remoteName) {
        try {
            var config = repository.getConfig();
            return config.getString("remote", remoteName, "url"); // getString can return null
        } catch (Exception e) {
            logger.warn("Failed to get remote URL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the URL of the origin remote
     */
    public @Nullable String getRemoteUrl() {
        return getRemoteUrl("origin");
    }

    /**
     * Reads the .gitignore file from the repository root and returns a list of patterns.
     * Filters out empty lines and comments (lines starting with #).
     *
     * @return A list of patterns from .gitignore, or an empty list if the file doesn't exist or an error occurs.
     */
    public List<String> getIgnoredPatterns() {
        var gitignoreFile = this.gitTopLevel.resolve(".gitignore");
        if (!Files.exists(gitignoreFile) || !Files.isReadable(gitignoreFile)) {
            logger.debug(".gitignore file not found or not readable at {}", gitignoreFile);
            return List.of();
        }

        try (var lines = Files.lines(gitignoreFile, StandardCharsets.UTF_8)) {
            return lines
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.warn("Error reading .gitignore file at {}: {}", gitignoreFile, e.getMessage());
            return List.of();
        }
    }

    /**
     * Search commits by text in message/author/email
     */
    public List<CommitInfo> searchCommits(String query) throws GitAPIException {
        var commits = new ArrayList<CommitInfo>();
        var lowerQuery = query.toLowerCase(Locale.ROOT);

        for (var commit : git.log().call()) {
            var cMessage = commit.getFullMessage().toLowerCase(Locale.ROOT);
            var cAuthorName = commit.getAuthorIdent().getName().toLowerCase(Locale.ROOT);
            var cAuthorEmail = commit.getAuthorIdent().getEmailAddress().toLowerCase(Locale.ROOT);

            if (cMessage.contains(lowerQuery)
                    || cAuthorName.contains(lowerQuery)
                    || cAuthorEmail.contains(lowerQuery)) {
                // Use factory method
                commits.add(this.fromRevCommit(commit));
            }
        }
        return commits;
    }

    @Override
    public void close() {
        git.close();
        repository.close();
    }

    /**
     * Initializes a new Git repository in the specified root directory.
     * Creates a .gitignore file with a .brokk/ entry if it doesn't exist or if .brokk/ is missing.
     *
     * @param root The path to the directory where the Git repository will be initialized.
     * @throws GitAPIException If an error occurs during Git initialization.
     * @throws IOException     If an I/O error occurs while creating or modifying .gitignore.
     */
    public static void initRepo(Path root) throws GitAPIException, IOException {
        logger.info("Initializing new Git repository at {}", root);
        Git.init().setDirectory(root.toFile()).call();
        logger.info("Git repository initialized at {}.", root);
        ensureBrokkIgnored(root);
    }

    private static void ensureBrokkIgnored(Path root) throws IOException {
        Path gitignorePath = root.resolve(".gitignore");
        String brokkDirEntry = ".brokk/";

        if (!Files.exists(gitignorePath)) {
            Files.writeString(gitignorePath, brokkDirEntry + "\n", StandardCharsets.UTF_8);
            logger.info("Created default .gitignore file with '{}' entry at {}.", brokkDirEntry, gitignorePath);
        } else {
            List<String> lines = Files.readAllLines(gitignorePath, StandardCharsets.UTF_8);
            boolean entryExists = lines.stream().anyMatch(line -> line.trim().equals(brokkDirEntry.trim()) || line.trim().equals(brokkDirEntry.substring(0, brokkDirEntry.length() - 1)));

            if (!entryExists) {
                // Append with a newline ensuring not to add multiple blank lines if file ends with one
                String contentToAppend = (lines.isEmpty() || lines.getLast().isBlank()) ? brokkDirEntry + "\n" : "\n" + brokkDirEntry + "\n";
                Files.writeString(gitignorePath, contentToAppend, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                logger.info("Appended '{}' entry to existing .gitignore file at {}.", brokkDirEntry, gitignorePath);
            } else {
                logger.debug("'{}' entry already exists in .gitignore file at {}.", brokkDirEntry, gitignorePath);
            }
        }
    }

    /**
     * Clones a remote repository into {@code directory}.
     * If {@code depth} &gt; 0 a shallow clone of that depth is performed,
     * otherwise a full clone is made.
     *
     * If the URL looks like a plain GitHub HTTPS repo without “.git”
     * (e.g. https://github.com/Owner/Repo) we automatically append “.git”.
     */
    public static GitRepo cloneRepo(String remoteUrl, Path directory, int depth) throws GitAPIException {
        requireNonNull(remoteUrl, "remoteUrl");
        requireNonNull(directory, "directory");

        String effectiveUrl = normalizeRemoteUrl(remoteUrl);

        // Ensure the target directory is empty (or doesn’t yet exist)
        if (Files.exists(directory) &&
            directory.toFile().list() != null &&
            directory.toFile().list().length > 0)
        {
            throw new IllegalArgumentException("Target directory " + directory + " must be empty or not yet exist");
        }

        try {
            var cloneCmd = Git.cloneRepository()
                              .setURI(effectiveUrl)
                              .setDirectory(directory.toFile())
                              .setCloneAllBranches(depth <= 0);
            if (depth > 0) {
                cloneCmd.setDepth(depth);
                cloneCmd.setNoTags();
            }
            // Perform clone and immediately close the returned Git handle
            try (var ignored = cloneCmd.call()) {
                // nothing – resources closed via try-with-resources
            }
            return new GitRepo(directory);
        } catch (GitAPIException e) {
            logger.error("Failed to clone {} into {}: {}", effectiveUrl, directory, e.getMessage(), e);
            throw e;
        }
    }

    /** Adds “.git” to simple GitHub HTTPS URLs when missing. */
    private static String normalizeRemoteUrl(String remoteUrl)
    {
        var pattern = Pattern.compile("^https://github\\.com/[^/]+/[^/]+$");
        return pattern.matcher(remoteUrl).matches() && !remoteUrl.endsWith(".git")
               ? remoteUrl + ".git"
               : remoteUrl;
    }

    public static class GitRepoException extends GitAPIException {
        public GitRepoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class WorktreeNeedsForceException extends GitRepoException {
        public WorktreeNeedsForceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class WorktreeDirtyException extends GitAPIException {
        public WorktreeDirtyException(String message) {
            super(message);
        }
    }

    public static class GitStateException extends GitAPIException {
        public GitStateException(String message) {
            super(message);
        }
    }

    public static class GitPushRejectedException extends GitAPIException {
        public GitPushRejectedException(String message) {
            super(message);
        }
    }

    public static class NoDefaultBranchException extends GitAPIException {
        public NoDefaultBranchException(String message) {
            super(message);
        }
    }

    private static class GitWrappedIOException extends GitAPIException {
        public GitWrappedIOException(IOException e) {
            super(e.getMessage(), e);
        }
    }

    /**
     * Factory method to create CommitInfo from a JGit RevCommit.
     * Assumes this is NOT a stash commit.
     */
    public CommitInfo fromRevCommit(RevCommit commit) {
        return new CommitInfo(this,
                              commit.getName(),
                              commit.getShortMessage(),
                              commit.getAuthorIdent().getName(),
                              commit.getCommitterIdent().getWhenAsInstant());
    }

    /**
     * Factory method to create CommitInfo for a stash entry from a JGit RevCommit.
     */
    public CommitInfo fromStashCommit(RevCommit commit, int index) {
        return new CommitInfo(this,
                              commit.getName(),
                              commit.getShortMessage(),
                              commit.getAuthorIdent().getName(),
                              commit.getAuthorIdent().getWhenAsInstant(),
                              index);
    }

    /**
     * Computes the merge base of two commits.
     */
    private @Nullable ObjectId computeMergeBase(ObjectId firstId, ObjectId secondId) throws GitAPIException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit firstCommit = walk.parseCommit(firstId);
            RevCommit secondCommit = walk.parseCommit(secondId);

            walk.reset(); // Reset before setting new filter and marking starts
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(firstCommit);
            walk.markStart(secondCommit);
            RevCommit mergeBase = walk.next();
            return mergeBase != null ? mergeBase.getId() : null;
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * Returns the SHA-1 of the merge base between the two rev-specs (or null
     * if none exists).  revA and revB may be branch names, tags, commit IDs, etc.
     */
    public @Nullable String getMergeBase(String revA, String revB) throws GitAPIException {
        var idA = resolve(revA);
        var idB = resolve(revB);
        var mb = computeMergeBase(idA, idB);
        return mb == null ? null : mb.getName();
    }

    /**
     * Lists all worktrees in the repository.
     */
    @Override
    public List<WorktreeInfo> listWorktrees() throws GitAPIException {
        try {
            var command = "git worktree list --porcelain";
            var output = Environment.instance.runShellCommand(command, gitTopLevel, out -> {});
            var worktrees = new ArrayList<WorktreeInfo>();
            var lines = Splitter.on(Pattern.compile("\\R")).splitToList(output); // Split by any newline sequence

            Path currentPath = null;
            String currentHead = null;
            String currentBranch = null;

            for (var line : lines) {
                if (line.startsWith("worktree ")) {
                    // Finalize previous entry if data is present
                    if (currentPath != null) {
                        worktrees.add(new WorktreeInfo(currentPath,
                                                       currentBranch,
                                                       requireNonNull(currentHead)));
                        currentHead = null;
                        currentBranch = null;
                    }

                    try {
                        currentPath = Path.of(line.substring("worktree ".length())).toRealPath();
                    } catch (IOException e) {
                        throw new GitRepoException("Failed to resolve worktree path: " + line.substring("worktree ".length()), e);
                    }
                } else if (line.startsWith("HEAD ")) {
                    currentHead = line.substring("HEAD ".length());
                } else if (line.startsWith("branch ")) {
                    var branchRef = line.substring("branch ".length());
                    if (branchRef.startsWith("refs/heads/")) {
                        currentBranch = branchRef.substring("refs/heads/".length());
                    } else {
                        currentBranch = branchRef; // Should not happen with porcelain but good to be defensive
                    }
                } else if (line.equals("detached")) {
                    // Detached-HEAD worktree: branch remains null (WorktreeInfo.branch is @Nullable).
                    currentBranch = null;
                }
            }
            // Add the last parsed worktree
            if (currentPath != null) {
                worktrees.add(new WorktreeInfo(currentPath,
                                               currentBranch,   // empty string for detached worktrees
                                               requireNonNull(currentHead)));
            }
            return worktrees;
        } catch (Environment.SubprocessException e) {
            throw new GitRepoException("Failed to list worktrees: " + e.getOutput(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitRepoException("Listing worktrees was interrupted", e);
        }
    }

    /**
     * Adds a new worktree at the specified path for the given branch.
     */
    @Override
    public void addWorktree(String branch, Path path) throws GitAPIException {
        try {
            // Ensure path is absolute for the command
            var absolutePath = path.toAbsolutePath().normalize();

            // Check if branch exists locally
            List<String> localBranches = listLocalBranches();
            String command;
            if (localBranches.contains(branch)) {
                // Branch exists, checkout the existing branch
                command = String.format("git worktree add %s %s", absolutePath, branch);
            } else {
                // Branch doesn't exist, create a new one
                command = String.format("git worktree add -b %s %s", branch, absolutePath);
            }
            Environment.instance.runShellCommand(command, gitTopLevel, out -> {});

            // Recursively copy .brokk/dependencies from the project root into the new worktree
            var sourceDependenciesDir = projectRoot.resolve(".brokk").resolve("dependencies");
            if (!Files.exists(sourceDependenciesDir)) {
                return;
            }

            // Ensure .brokk exists in the new worktree
            var targetDependenciesDir = absolutePath.resolve(".brokk").resolve("dependencies");
            Files.createDirectories(targetDependenciesDir.getParent());

            // copy
            Files.walkFileTree(sourceDependenciesDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    var relative = sourceDependenciesDir.relativize(dir);
                    var targetDir = targetDependenciesDir.resolve(relative);
                    Files.createDirectories(targetDir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    var relative = sourceDependenciesDir.relativize(file);
                    var targetFile = targetDependenciesDir.resolve(relative);
                    Files.copy(file,
                               targetFile,
                               StandardCopyOption.REPLACE_EXISTING,
                               StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Environment.SubprocessException e) {
            throw new GitRepoException("Failed to add worktree at " + path + " for branch " + branch + ": " + e.getOutput(), e);
        } catch (IOException e) {
            throw new GitRepoException("Failed to copy dependencies", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitRepoException("Adding worktree at " + path + " for branch " + branch + " was interrupted", e);
        }
    }

    /**
     * Adds a new detached worktree at the specified path, checked out to a specific commit.
     *
     * @param path The path where the new worktree will be created.
     * @param commitId The commit SHA to check out in the new detached worktree.
     * @throws GitAPIException if a Git error occurs.
     */
    public void addWorktreeDetached(Path path, String commitId) throws GitAPIException {
        try {
            var absolutePath = path.toAbsolutePath().normalize();
            var command = String.format("git worktree add --detach %s %s", absolutePath, commitId);
            Environment.instance.runShellCommand(command, gitTopLevel, out -> {});
        } catch (Environment.SubprocessException e) {
            throw new GitRepoException("Failed to add detached worktree at " + path + " for commit " + commitId + ": " + e.getOutput(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitRepoException("Adding detached worktree at " + path + " for commit " + commitId + " was interrupted", e);
        }
    }

    /**
     * Removes the worktree at the specified path.
     * This method will fail if the worktree is dirty or has other issues preventing a clean removal,
     * in which case a {@link WorktreeNeedsForceException} will be thrown.
     * @param path The path to the worktree to remove.
     * @throws WorktreeNeedsForceException if the worktree cannot be removed without force.
     * @throws GitAPIException if a different Git error occurs.
     */
    @Override
    public void removeWorktree(Path path, boolean force) throws GitAPIException {
        try {
            var absolutePath = path.toAbsolutePath().normalize();
            String command;
            if (force) {
                // Use double force as "git worktree lock" requires "remove -f -f" to override
                command = String.format("git worktree remove --force --force %s", absolutePath).trim();
            } else {
                command = String.format("git worktree remove %s", absolutePath).trim();
            }
            Environment.instance.runShellCommand(command, gitTopLevel, out -> {});
        } catch (Environment.SubprocessException e) {
            String output = e.getOutput();
            // If 'force' was false and the command failed because force is needed, throw WorktreeNeedsForceException
            if (!force && (output.contains("use --force") || output.contains("not empty") || output.contains("dirty") || output.contains("locked working tree"))) {
                throw new WorktreeNeedsForceException("Worktree at " + path + " requires force for removal: " + output, e);
            }
            // Otherwise, throw a general GitRepoException
            String failMessage = String.format("Failed to remove worktree at %s%s: %s",
                                               path, (force ? " (with force)" : ""), output);
            throw new GitRepoException(failMessage, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String interruptMessage = String.format("Removing worktree at %s%s was interrupted",
                                                    path, (force ? " (with force)" : ""));
            throw new GitRepoException(interruptMessage, e);
        }
    }

    /**
     * Returns true if this repository is a Git worktree.
     */
    @Override
    public boolean isWorktree() {
        return Files.isRegularFile(repository.getWorkTree().toPath().resolve(".git"));
    }

    /**
     * Returns the set of branches that are checked out in worktrees.
     */
    @Override
    public Set<String> getBranchesInWorktrees() throws GitAPIException {
        return listWorktrees().stream()
                .map(WorktreeInfo::branch)
                .filter(branch -> branch != null && !branch.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Determines the next available path for a new worktree in the specified storage directory.
     * It looks for paths named "wt1", "wt2", etc., and returns the first one that doesn't exist.
     *
     * @param worktreeStorageDir The directory where worktrees are stored.
     * @return A Path for the new worktree.
     * @throws IOException if an I/O error occurs when checking for directory existence.
     */
    @Override
    public Path getNextWorktreePath(Path worktreeStorageDir) throws IOException {
        Files.createDirectories(worktreeStorageDir); // Ensure base directory exists
        int nextWorktreeNum = 1;
        Path newWorktreePath;
        while (true) {
            Path potentialPath = worktreeStorageDir.resolve("wt" + nextWorktreeNum);
            if (!Files.exists(potentialPath)) {
                newWorktreePath = potentialPath;
                break;
            }
            nextWorktreeNum++;
        }
        return newWorktreePath;
    }

    @Override
    public boolean supportsWorktrees() {
        try {
            // Try to run a simple git command to check if git executable is available and working
            Environment.instance.runShellCommand("git --version", gitTopLevel, output -> {});
            return true;
        } catch (Environment.SubprocessException e) {
            // This typically means git command failed, e.g., not found or permission issue
            logger.warn("Git executable not found or 'git --version' failed, disabling worktree support: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while checking for git executable, disabling worktree support", e);
            return false;
        }
    }

    @Override
    public List<String> getCommitMessagesBetween(String branchName, String targetBranchName) throws GitAPIException {
        var revCommits = getRevCommitsBetween(branchName, targetBranchName, false);
        return revCommits.stream()
                .map(RevCommit::getShortMessage)
                .collect(Collectors.toList());
    }

    /**
     * Lists commits between two branches, returning detailed CommitInfo objects.
     * The commits are those on {@code sourceBranchName} that are not on {@code targetBranchName}.
     * Commits are returned in chronological order (oldest first).
     */
    public List<CommitInfo> listCommitsBetweenBranches(String sourceBranchName, String targetBranchName, boolean excludeMergeCommitsFromTarget) throws GitAPIException {
        var revCommits = getRevCommitsBetween(sourceBranchName, targetBranchName, excludeMergeCommitsFromTarget);
        return revCommits.stream()
                .map(this::fromRevCommit)
                .collect(Collectors.toList());
    }

    private List<RevCommit> getRevCommitsBetween(String sourceBranchName, String targetBranchName, boolean excludeMergeCommitsFromTarget) throws GitAPIException {
        List<RevCommit> commits = new ArrayList<>();
        ObjectId sourceHead = resolve(sourceBranchName);
        ObjectId targetHead = resolve(targetBranchName);

        // targetHead can be null if the target branch doesn't exist (e.g. creating a PR to a new remote branch)

        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit sourceCommit = revWalk.parseCommit(sourceHead);
            revWalk.markStart(sourceCommit);

            RevCommit targetCommit = revWalk.parseCommit(targetHead);

            if (excludeMergeCommitsFromTarget) {
                revWalk.markUninteresting(targetCommit); // Hide everything reachable from target
                revWalk.setRevFilter(RevFilter.NO_MERGES); // Exclude all merge commits
            } else {
                // Original logic for "target..source"
                ObjectId mergeBaseId = computeMergeBase(sourceCommit.getId(), targetCommit.getId());
                if (mergeBaseId != null) {
                    // The RevCommit must be parsed by this revWalk instance to be used by it
                    RevCommit mergeBaseForRevWalk = revWalk.parseCommit(mergeBaseId);
                    revWalk.markUninteresting(mergeBaseForRevWalk);
                } else {
                    // No common ancestor. This implies targetBranchName is either an ancestor of sourceBranchName,
                    // or they are unrelated. To get `target..source` behavior, mark target as uninteresting.
                    logger.warn("No common merge base found between {} ({}) and {} ({}). Listing commits from {} not on {}.",
                                sourceBranchName, sourceCommit.getName(),
                                targetBranchName, targetCommit.getName(),
                                sourceBranchName, targetBranchName);
                    revWalk.markUninteresting(targetCommit);
                }
            }

            // Iterate and collect commits
            for (RevCommit commit : revWalk) {
                commits.add(commit);
            }
            // Commits are listed newest-first by RevWalk. Reverse to get chronological order (oldest first).
            Collections.reverse(commits);
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
        return commits;
    }

    /**
     * True when the local branch has no upstream or is ahead of its upstream.
     * Returns false if the provided branch name is not a local branch.
     */
    public boolean branchNeedsPush(String branch) throws GitAPIException {
        if (!listLocalBranches().contains(branch)) {
            return false; // Not a local branch, so it cannot need pushing
        }
        return !hasUpstreamBranch(branch)                // never pushed → no upstream
               || !getUnpushedCommitIds(branch).isEmpty(); // ahead of remote
    }

    /**
     * Lists files changed between two branches, specifically the changes introduced on the source branch
     * since it diverged from the target branch.
     */
    public List<ModifiedFile> listFilesChangedBetweenBranches(String sourceBranch, String targetBranch)
            throws GitAPIException {
        ObjectId sourceHeadId = resolve(sourceBranch);

        ObjectId targetHeadId = resolve(targetBranch); // Can be null if target branch doesn't exist
        logger.debug("Resolved source branch '{}' to {}, target branch '{}' to {}",
                     sourceBranch, sourceHeadId, targetBranch, targetHeadId);


        ObjectId mergeBaseId = computeMergeBase(sourceHeadId, targetHeadId);

        if (mergeBaseId == null) {
            // If no common ancestor is found (e.g., unrelated histories, one branch is new, or one head was null),
            // use the target's head as the base. If targetHeadId is also null (target branch doesn't exist),
            // mergeBaseId will remain null, leading to a diff against an empty tree.
            logger.debug("No common merge base computed for source {} ({}) and target {} ({}). " +
                         "Falling back to target head {}.",
                         sourceBranch, sourceHeadId, targetBranch, targetHeadId,
                         targetHeadId);
            mergeBaseId = targetHeadId;
        }
        logger.debug("Effective merge base for diffing {} ({}) against {} ({}) is {}",
                     sourceBranch, sourceHeadId, targetBranch, targetHeadId,
                     mergeBaseId);


        var modifiedFiles = new ArrayList<ModifiedFile>();

        try (RevWalk revWalk = new RevWalk(repository);
             // DiffFormatter output stream is not used for file listing but is required by constructor
             DiffFormatter diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) {
            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true); // Enable rename detection

            RevCommit sourceCommit = revWalk.parseCommit(sourceHeadId);
            RevTree newTree = sourceCommit.getTree();
            try (var reader = repository.newObjectReader()) {
                var newTreeParser = new CanonicalTreeParser(null, reader, newTree);

                AbstractTreeIterator oldTreeParser;
                RevCommit mergeBaseCommit = revWalk.parseCommit(mergeBaseId);
                RevTree oldTree = mergeBaseCommit.getTree();
                // oldTreeParser needs its own reader, or ensure the existing reader is used correctly
                try (var oldTreeReader = repository.newObjectReader()) {
                     oldTreeParser = new CanonicalTreeParser(null, oldTreeReader, oldTree);
                }

                List<DiffEntry> diffs = diffFormatter.scan(oldTreeParser, newTreeParser);

                for (var entry : diffs) {
                    // Skip /dev/null paths, which can appear for add/delete of binary files or certain modes
                    // Handled by only using relevant paths (getOldPath for DELETE, getNewPath for ADD/COPY/MODIFY/RENAME's new side)
                    // and relying on toProjectFile which would inherently handle or error on /dev/null if it were a real project path.
                    // The main concern is ensuring we use the correct path (old vs new) based on ChangeType.

                    var result = switch (entry.getChangeType()) {
                        case ADD, COPY -> new ModifiedFile(toProjectFile(entry.getNewPath()), "new");
                        case MODIFY -> new ModifiedFile(toProjectFile(entry.getNewPath()), "modified");
                        case DELETE -> new ModifiedFile(toProjectFile(entry.getOldPath()), "deleted");
                        case RENAME -> {
                            modifiedFiles.add(new ModifiedFile(toProjectFile(entry.getOldPath()), "deleted"));
                            yield new ModifiedFile(toProjectFile(entry.getNewPath()), "new");
                        }
                        default -> {
                            logger.warn("Unhandled DiffEntry ChangeType: {} for old path '{}', new path '{}'",
                                        entry.getChangeType(), entry.getOldPath(), entry.getNewPath());
                            yield null;
                    }
                    };
                    if (result != null) {
                        modifiedFiles.add(result);
                    }
                }
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }

        // Sort for consistent UI presentation
        modifiedFiles.sort(Comparator.comparing(mf -> mf.file().toString()));
        logger.debug("Found {} files changed between {} and {}: {}", modifiedFiles.size(), sourceBranch, targetBranch, modifiedFiles);
        return modifiedFiles;
    }

    private static String createTempBranchName(String prefix) {
        return prefix + "_" + System.currentTimeMillis();
    }

    public static String createTempRebaseBranchName(String sourceBranchName) {
        String sanitized = sourceBranchName.replaceAll("[^a-zA-Z0-9-_]", "_");
        return createTempBranchName("brokk_temp_rebase_" + sanitized);
    }

    /**
     * Checks for historical conflicts between two branches by performing a simulation in a temporary worktree.
     * This method does NOT check for conflicts with the current (dirty) worktree state.
     *
     * @return A string describing the conflict, or null if no historical conflicts are found.
     * @throws WorktreeDirtyException if the target branch has uncommitted changes preventing simulation.
     */
    @Override
    public @Nullable String checkMergeConflicts(String worktreeBranchName, String targetBranchName, MergeMode mode) throws GitAPIException {
        // This check is a safeguard. If the target branch has uncommitted changes,
        // `git worktree add` might fail. This gives a cleaner error to the user.
        var status = git.status().call();

        // A worktree is considered “dirty” for merge-simulation purposes ONLY if
        //   • the index contains staged additions / changes, OR
        //   • any *tracked* file is modified, missing, or staged for removal.
        // Untracked/ignored files are allowed; they will be checked separately
        // against the list of paths the merge would touch.
        boolean indexDirty =
                !status.getAdded().isEmpty() ||
                !status.getChanged().isEmpty();

        boolean trackedWTDirty =
                !status.getModified().isEmpty() ||
                !status.getMissing().isEmpty() ||
                !status.getRemoved().isEmpty();

        if (indexDirty || trackedWTDirty) {
            throw new WorktreeDirtyException("Target worktree has uncommitted changes.");
        }

        ObjectId worktreeBranchId = resolve(worktreeBranchName);
        ObjectId targetBranchId = resolve(targetBranchName);

        // Create a unique temporary directory for the worktree inside .git/worktrees
        // to avoid cross-device issues and for easier cleanup.
        Path worktreesDir = getGitTopLevel().resolve(".git").resolve("worktrees");
        Path tempWorktreePath;
        try {
            Files.createDirectories(worktreesDir);
            tempWorktreePath = Files.createTempDirectory(worktreesDir, "brokk_merge_check_");
        } catch (IOException e) {
            throw new GitRepoException("Failed to create temporary directory for merge check", e);
        }

        logger.debug("Performing merge conflict simulation in temporary worktree: {}", tempWorktreePath);

        try {
            // Add a detached worktree on the target branch
            var addCommand = String.format("git worktree add --detach %s %s", tempWorktreePath.toAbsolutePath(), targetBranchId.getName());
            Environment.instance.runShellCommand(addCommand, getGitTopLevel(), out -> {});

            // Create a GitRepo instance for the temporary worktree to run the simulation
            try (GitRepo tempRepo = new GitRepo(tempWorktreePath)) {
                if (mode == MergeMode.REBASE_MERGE) {
                    var rebaseResult = tempRepo.git.rebase().setUpstream(worktreeBranchId).call();
                    if (rebaseResult.getStatus() == RebaseResult.Status.CONFLICTS || rebaseResult.getStatus() == RebaseResult.Status.STOPPED) {
                        return "Rebase conflicts detected.";
                    } else if (!isRebaseSuccessful(rebaseResult)) {
                        return "Rebase pre-check failed: " + rebaseResult.getStatus();
                    }
                } else { // MERGE_COMMIT or SQUASH_COMMIT
                    MergeCommand mergeCmd = tempRepo.git.merge().include(worktreeBranchId);
                    if (mode == MergeMode.SQUASH_COMMIT) {
                        mergeCmd.setSquash(true);
                    }
                    mergeCmd.setCommit(false); // Don't create a commit during simulation
                    MergeResult mergeResult = mergeCmd.call();

                    if (hasConflicts(mergeResult)) {
                        return "Merge conflicts detected in: " + String.join(", ", mergeResult.getConflicts().keySet());
                    } else if (!isMergeSuccessful(mergeResult, mode)) {
                        return "Merge pre-check failed: " + mergeResult.getMergeStatus();
                    }
                }
            }
            return null; // No conflicts found
        } catch (Environment.SubprocessException | InterruptedException e) {
            throw new GitRepoException("Failed to execute command for temporary worktree", e);
        } finally {
            // Forcefully remove the temporary worktree and its directory
            try {
                var removeCommand = String.format("git worktree remove --force %s", tempWorktreePath.toAbsolutePath());
                Environment.instance.runShellCommand(removeCommand, getGitTopLevel(), out -> {});
            } catch (Exception e) {
                logger.error("Failed to clean up temporary worktree at {}", tempWorktreePath, e);
            }
        }
    }

    /**
     * Attempts to determine the repository's default branch.
     * Order of preference:
     *   1. The symbolic ref refs/remotes/origin/HEAD (remote's default)
     *   2. Local branch named 'main'
     *   3. Local branch named 'master'
     *   4. First local branch (alphabetically)
     * @return The default branch name.
     * @throws NoDefaultBranchException if no default branch can be determined (e.g., in an empty repository).
     * @throws GitAPIException if an error occurs while accessing Git data.
     */
    @Override
    public String getDefaultBranch() throws GitAPIException {
        // 1. Check remote HEAD symbolic ref (typically origin/HEAD)
        try {
            var remoteHeadRef = repository.findRef("refs/remotes/origin/HEAD");
            if (remoteHeadRef != null && remoteHeadRef.isSymbolic()) {
                var target = remoteHeadRef.getTarget().getName(); // e.g., "refs/remotes/origin/main"
                if (target.startsWith("refs/remotes/origin/")) {
                    var branchName = target.substring("refs/remotes/origin/".length());
                    // Verify this branch actually exists locally, otherwise it's not a usable default
                    if (listLocalBranches().contains(branchName)) {
                        logger.debug("Default branch from origin/HEAD: {}", branchName);
                        return branchName;
                    }
                }
            }
        } catch (IOException e) {
            // Log and continue, as this is just one way to find the default
            logger.warn("IOException while trying to read refs/remotes/origin/HEAD", e);
        }

        // 2. Check for local 'main'
        var localBranches = listLocalBranches();
        if (localBranches.contains("main")) {
            logger.debug("Default branch found: local 'main'");
            return "main";
        }

        // 3. Check for local 'master'
        if (localBranches.contains("master")) {
            logger.debug("Default branch found: local 'master'");
            return "master";
        }

        // 4. Fallback to the first local branch alphabetically
        if (!localBranches.isEmpty()) {
            Collections.sort(localBranches);
            var firstBranch = localBranches.getFirst();
            logger.debug("Default branch fallback: alphabetically first local branch '{}'", firstBranch);
            return firstBranch;
        }

        // 5. No branches found
        throw new NoDefaultBranchException("Repository has no local branches and no default can be determined.");
    }
}
