package io.github.jbellis.brokk.git;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.Environment;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.gpg.bc.internal.BouncyCastleGpgSignerFactory;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jetbrains.annotations.Nullable;

/**
 * A Git repository abstraction using JGit.
 *
 * <p>The semantics are that GitRepo represents a subset of the files in the full Git repository that is located in the
 * `location` directory or one of its parents. The common case is that the git dir is a child of `location` but we
 * support instantiating in one of the working tree's subdirectories as well.
 */
public class GitRepo implements Closeable, IGitRepo {
    private static final Logger logger = LogManager.getLogger(GitRepo.class);

    private final Path projectRoot; // The root directory for ProjectFile instances this GitRepo deals with
    private final Path gitTopLevel; // The actual top-level directory of the git repository
    private final Repository repository;
    private final Git git;
    private final char @Nullable [] gpgPassPhrase; // if the user has enabled GPG signing by default
    private final Supplier<String> tokenSupplier; // Supplier for GitHub token
    private @Nullable Set<ProjectFile> trackedFilesCache = null;

    // New field holding remote-related helpers
    private final GitRepoRemote remote;

    // New field holding worktree-related helpers
    private final GitRepoWorktrees worktrees;

    // New field holding data/workers helper
    private final GitRepoData data;

    public GitRepoRemote remote() {
        return remote;
    }

    public GitRepoWorktrees worktrees() {
        return worktrees;
    }

    public GitRepoData data() {
        return data;
    }

    /**
     * Sanitizes a proposed branch name and ensures it is unique by appending a numerical suffix if necessary. If the
     * initial sanitization results in an empty string, "branch" is used as the base.
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
        // Remove characters not suitable for branch names (keeping only alphanumeric, hyphen, forward slash, and
        // underscore)
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

    /** Get the JGit instance for direct API access */
    public Git getGit() {
        return git;
    }

    // package-private accessor so GitRepoRemote can use the repository when needed
    Repository getRepository() {
        return repository;
    }

    // package-private accessor for projectRoot for the extracted worktrees helper
    Path getProjectRoot() {
        return projectRoot;
    }

    public GitRepo(Path projectRoot) {
        this(projectRoot, MainProject::getGitHubToken);
    }

    GitRepo(Path projectRoot, Supplier<String> tokenSupplier) {
        this.projectRoot = projectRoot;
        this.tokenSupplier = tokenSupplier;

        try {
            var builder = new FileRepositoryBuilder()
                    .setWorkTree(projectRoot.toFile())
                    .findGitDir(projectRoot.toFile());
            if (builder.getGitDir() == null) {
                throw new RuntimeException("No git repo found at or above " + projectRoot);
            }
            repository = builder.build();
            git = new Git(repository);
            worktrees = new GitRepoWorktrees(this);

            // Check for GPG signing
            this.gpgPassPhrase = null; // TODO: Fetch from settings, vault, etc.

            // For worktrees, we need to find the actual repository root, not the .git/worktrees path
            if (isWorktree()) {
                // For worktrees, read the commondir file to find the main repository
                Path gitDir = repository.getDirectory().toPath();
                Path commondirFile = gitDir.resolve("commondir");
                if (Files.exists(commondirFile)) {
                    String commonDirContent = Files.readString(commondirFile, StandardCharsets.UTF_8)
                            .trim();
                    Path commonDir = gitDir.resolve(commonDirContent).normalize();
                    Path parentOfCommonDir = commonDir.getParent();
                    this.gitTopLevel = requireNonNull(
                                    parentOfCommonDir, "Parent of git common-dir should not be null: " + commonDir)
                            .normalize();
                } else {
                    // Fallback: try to parse the gitdir file in the working tree
                    Path gitFile = repository.getWorkTree().toPath().resolve(".git");
                    if (Files.exists(gitFile)) {
                        String gitFileContent = Files.readString(gitFile, StandardCharsets.UTF_8)
                                .trim();
                        if (gitFileContent.startsWith("gitdir: ")) {
                            String gitDirPath = gitFileContent.substring("gitdir: ".length());
                            Path worktreeGitDir = Path.of(gitDirPath).normalize();
                            Path commondirFile2 = worktreeGitDir.resolve("commondir");
                            if (Files.exists(commondirFile2)) {
                                String commonDirContent2 = Files.readString(commondirFile2, StandardCharsets.UTF_8)
                                        .trim();
                                Path commonDir2 = worktreeGitDir
                                        .resolve(commonDirContent2)
                                        .normalize();
                                Path parentOfCommonDir2 = commonDir2.getParent();
                                this.gitTopLevel = requireNonNull(
                                                parentOfCommonDir2,
                                                "Parent of git common-dir (fallback) should not be null: " + commonDir2)
                                        .normalize();
                            } else {
                                // Ultimate fallback
                                this.gitTopLevel = repository
                                        .getDirectory()
                                        .getParentFile()
                                        .toPath()
                                        .normalize();
                            }
                        } else {
                            this.gitTopLevel = repository
                                    .getDirectory()
                                    .getParentFile()
                                    .toPath()
                                    .normalize();
                        }
                    } else {
                        this.gitTopLevel = repository
                                .getDirectory()
                                .getParentFile()
                                .toPath()
                                .normalize();
                    }
                }
            } else {
                // For regular repos, gitTopLevel is the parent of the actual .git directory
                this.gitTopLevel =
                        repository.getDirectory().getParentFile().toPath().normalize();
            }

            // Initialize remote helper now that repository/git/top-level are available
            this.remote = new GitRepoRemote(this);

            // Initialize data helper
            this.data = new GitRepoData(this);

            logger.trace(
                    "Git dir for {} is {}, gitTopLevel is {}", projectRoot, repository.getDirectory(), gitTopLevel);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open repository at " + projectRoot, e);
        }
    }

    /** @return true if GPG signing is enabled by default for this repository, false if otherwise. */
    public boolean isGpgSigned() {
        return gpgPassPhrase != null;
    }

    @Override
    public Path getGitTopLevel() {
        return gitTopLevel;
    }

    /**
     * Converts a ProjectFile (which is relative to projectRoot) into a path string relative to JGit's working tree
     * root, suitable for JGit commands.
     */
    String toRepoRelativePath(ProjectFile file) {
        // ProjectFile.absPath() gives the absolute path on the filesystem.
        // We need to make it relative to JGit's working tree root.
        Path workingTreeRoot = repository.getWorkTree().toPath().normalize();
        Path relativePath = workingTreeRoot.relativize(file.absPath());
        return relativePath.toString().replace('\\', '/');
    }

    /**
     * Creates a ProjectFile instance from a path string returned by JGit. JGit paths are relative to the working tree
     * root. The returned ProjectFile will be relative to projectRoot.
     */
    ProjectFile toProjectFile(String gitPath) {
        Path workingTreeRoot = repository.getWorkTree().toPath().normalize();
        Path absolutePath = workingTreeRoot.resolve(gitPath);
        Path pathRelativeToProjectRoot = projectRoot.relativize(absolutePath);
        return new ProjectFile(projectRoot, pathRelativeToProjectRoot);
    }

    // ==================== Merge Mode Enum ====================

    /** Represents the different merge strategies available. */
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
     * Determines if a merge operation was successful, accounting for the special case where squash merges return
     * MERGED_SQUASHED_NOT_COMMITTED which is actually successful.
     *
     * @param result the MergeResult to check
     * @param mode the merge mode that was used
     * @return true if the merge was successful
     */
    public static boolean isMergeSuccessful(MergeResult result, MergeMode mode) {
        MergeResult.MergeStatus status = result.getMergeStatus();
        return status.isSuccessful()
                || status == MergeResult.MergeStatus.MERGED_NOT_COMMITTED
                || (mode == MergeMode.SQUASH_COMMIT && status == MergeResult.MergeStatus.MERGED_SQUASHED_NOT_COMMITTED);
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
     * Determines if any conflicts exist in a merge result.
     *
     * @param result the MergeResult to check
     * @return true if conflicts exist
     */
    public static boolean hasConflicts(MergeResult result) {
        return result.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING;
    }

    /**
     * Determines if a TransportException indicates a GitHub permission denial. This checks for GitHub-specific error
     * messages from both HTTPS and SSH protocols, including examining the exception cause chain.
     *
     * @param ex the TransportException to check
     * @return true if the exception indicates a GitHub permission error
     */
    public static boolean isGitHubPermissionDenied(org.eclipse.jgit.api.errors.TransportException ex) {
        // Check main exception message
        if (checkMessageForPermissionDenial(ex.getMessage())) {
            return true;
        }

        // Check cause chain for HTTP-related exceptions
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (checkMessageForPermissionDenial(cause.getMessage())) {
                return true;
            }
            cause = cause.getCause();
        }

        return false;
    }

    /**
     * Checks if an exception message indicates a GitHub permission denial.
     *
     * @param msg the exception message to check
     * @return true if the message indicates a permission error
     */
    private static boolean checkMessageForPermissionDenial(@Nullable String msg) {
        if (msg == null) {
            return false;
        }

        var lower = msg.toLowerCase(java.util.Locale.ROOT);

        // GitHub HTTPS: token permission errors
        if (lower.contains("git-receive-pack not permitted")) {
            return true;
        }

        // GitHub SSH: "Permission to user/repo denied"
        if (lower.contains("permission to") && lower.contains("denied")) {
            return true;
        }

        // HTTP status codes indicating permission/auth failures
        if (lower.contains("403") || lower.contains("forbidden")) {
            return true;
        }

        if (lower.contains("401") || lower.contains("unauthorized")) {
            return true;
        }

        return false;
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

    /** Adds files to staging. */
    @Override
    public synchronized void add(Collection<ProjectFile> files) throws GitAPIException {
        var addCommand = git.add();
        for (var file : files) {
            addCommand.addFilepattern(toRepoRelativePath(file));
        }
        addCommand.call();
    }

    @Override
    public synchronized void add(Path path) throws GitAPIException {
        var addCommand = git.add();
        var repoRelativePath = gitTopLevel.relativize(path.toAbsolutePath()).toString();
        addCommand.addFilepattern(repoRelativePath);
        addCommand.call();
    }

    /**
     * Removes a file from the Git index. This corresponds to `git rm --cached <file>`. The working tree file is
     * expected to be deleted separately if desired.
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
     * Forcefully removes files from the working directory and the Git index. This is equivalent to deleting the files
     * and then running `git rm`.
     *
     * @param files The list of files to remove.
     * @throws GitAPIException if the Git command fails.
     */
    @Override
    public synchronized void forceRemoveFiles(List<ProjectFile> files) throws GitAPIException {
        try {
            for (var file : files) {
                Files.deleteIfExists(file.absPath());
            }
            var rmCommand = git.rm();
            for (var file : files) {
                rmCommand.addFilepattern(toRepoRelativePath(file));
            }
            rmCommand.call();
            invalidateCaches();
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /** Returns a list of RepoFile objects representing all tracked files in the repository. */
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
                headTreeId = resolveToObject("HEAD^{tree}");
            } catch (GitAPIException e) {
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
        trackedFilesCache = trackedPaths.stream().map(this::toProjectFile).collect(Collectors.toSet());
        return trackedFilesCache;
    }

    /** Get the current commit ID (HEAD) */
    @Override
    public String getCurrentCommitId() throws GitAPIException {
        var head = resolveToCommit("HEAD");
        return head.getName();
    }

    /**
     * Returns an abbreviated (short) hash for the given revision. Attempts to abbreviate via JGit to a unique short id;
     * falls back to the first 7 chars on error.
     */
    public String shortHash(String rev) {
        try {
            var id = resolveToObject(rev);
            try (var reader = repository.newObjectReader()) {
                var abbrev = reader.abbreviate(id);
                return abbrev.name();
            }
        } catch (GitAPIException | IOException e) {
            logger.warn(e);
            return rev;
        }
    }

    /** Produces a combined diff of staged + unstaged changes, restricted to the given files. */
    @Override
    public synchronized String diffFiles(List<ProjectFile> files) throws GitAPIException {
        return data.diffFiles(files);
    }

    @Override
    public synchronized String diff() throws GitAPIException {
        return data.diff();
    }

    /** Returns a set of uncommitted files with their status (new, modified, deleted). */
    @Override
    public Set<ModifiedFile> getModifiedFiles() throws GitAPIException {
        var statusResult = git.status().call();
        var uncommittedFilesWithStatus = new HashSet<ModifiedFile>();

        // Collect all unique paths from the statuses we are interested in, including conflicts
        var allRelevantPaths = new HashSet<String>();
        allRelevantPaths.addAll(statusResult.getAdded());
        allRelevantPaths.addAll(statusResult.getRemoved());
        allRelevantPaths.addAll(statusResult.getMissing());
        allRelevantPaths.addAll(statusResult.getModified());
        allRelevantPaths.addAll(statusResult.getChanged());
        allRelevantPaths.addAll(statusResult.getConflicting());
        logger.trace("Raw modified files (including conflicts): {}", allRelevantPaths);

        for (var path : allRelevantPaths) {
            var projectFile = toProjectFile(path);
            String determinedStatus;

            // Priority: conflicts first, then added/missing, then general modifications
            if (statusResult.getConflicting().contains(path)) {
                determinedStatus = "conflict";
            } else if (statusResult.getAdded().contains(path)) {
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
                throw new AssertionError(
                        "Path " + path + " from relevant git status sets did not receive a determined status.");
            }
            uncommittedFilesWithStatus.add(new ModifiedFile(projectFile, determinedStatus));
        }

        logger.trace("Modified files: {}", uncommittedFilesWithStatus);
        return uncommittedFilesWithStatus;
    }

    /**
     * Prepares a Git commit command, handling signing if required.
     *
     * @return a properly configured Git commit command.
     */
    public CommitCommand commitCommand() {
        if (!isGpgSigned()) {
            return git.commit().setSign(false);
        } else {
            var signer = new BouncyCastleGpgSignerFactory().create();
            return git.commit().setSigner(signer).setSign(true);
        }
    }

    /**
     * Commit a specific list of RepoFiles.
     *
     * @return The commit ID of the new commit
     */
    public String commitFiles(List<ProjectFile> files, String message) throws GitAPIException {
        add(files);

        var commitCommand = commitCommand().setMessage(message);

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
     * Applies GitHub token authentication to a git transport command if the remote URL is a GitHub HTTPS URL. For all
     * other URLs (SSH, non-GitHub HTTPS, file, etc.), does nothing and lets JGit use its default handling.
     *
     * @param command The git transport command (push, pull, fetch, clone)
     * @param remoteUrl The remote URL to check
     * @throws GitHubAuthenticationException if GitHub HTTPS URL is detected but no token is configured
     */
    public <T, C extends org.eclipse.jgit.api.TransportCommand<C, T>> void applyGitHubAuthentication(
            C command, @Nullable String remoteUrl) throws GitHubAuthenticationException {
        // Only handle GitHub HTTPS URLs - everything else uses JGit defaults
        if (remoteUrl == null || !remoteUrl.startsWith("https://") || !remoteUrl.contains("github.com")) {
            return;
        }

        // GitHub HTTPS requires token
        logger.debug("Using GitHub token authentication for: {}", remoteUrl);
        var githubToken = tokenSupplier.get();
        if (!githubToken.trim().isEmpty()) {
            command.setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", githubToken));
        } else {
            throw new GitHubAuthenticationException("GitHub token required for HTTPS authentication. "
                    + "Configure in Settings -> Global -> GitHub, or use SSH URL instead.");
        }
    }

    /** Check if a local branch has a configured upstream (tracking) branch */
    public boolean hasUpstreamBranch(String branchName) {
        return getTrackingBranch(branchName) != null;
    }

    /** Get the tracking branch name for a local branch */
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

    /** List all local branches */
    public List<String> listLocalBranches() throws GitAPIException {
        var branches = new ArrayList<String>();
        for (var ref : git.branchList().call()) {
            branches.add(ref.getName().replaceFirst("^refs/heads/", ""));
        }
        return branches;
    }

    /** List all remote branches */
    public List<String> listRemoteBranches() throws GitAPIException {
        var branches = new ArrayList<String>();
        for (var ref :
                git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()) {
            branches.add(ref.getName().replaceFirst("^refs/remotes/", ""));
        }
        return branches;
    }

    /** List all tags in the repository, sorted by their commit/tag date in descending order (newest first). */
    public List<String> listTags() throws GitAPIException {
        try (var revWalk = new RevWalk(repository)) {
            record TagInfo(String name, Instant date) {}
            var tagInfos = new ArrayList<TagInfo>();

            for (var ref : git.tagList().call()) {
                String tagName = ref.getName().replaceFirst("^refs/tags/", "");
                Instant date = Instant.EPOCH;

                try {
                    // Handle annotated vs lightweight tags
                    var objId = (ref.getPeeledObjectId() != null) ? ref.getPeeledObjectId() : ref.getObjectId();
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
     * True iff {@code branchName} is one of this repository’s **local** branches. Falls back to {@code false} if the
     * branch list cannot be obtained.
     */
    public boolean isLocalBranch(String branchName) {
        try {
            return listLocalBranches().contains(branchName);
        } catch (GitAPIException e) {
            logger.warn("Unable to enumerate local branches", e);
            return false;
        }
    }

    /**
     * True iff {@code branchName} is one of this repository’s **remote** branches (e.g. origin/main). Falls back to
     * {@code false} on error.
     */
    public boolean isRemoteBranch(String branchName) {
        try {
            return listRemoteBranches().contains(branchName);
        } catch (GitAPIException e) {
            logger.warn("Unable to enumerate remote branches", e);
            return false;
        }
    }

    /**
     * Checkout a conflicted path from a specific stage (ours/theirs) and stage it to resolve the conflict.
     *
     * @param path repository-relative path (use forward slashes)
     * @param side either "ours" or "theirs"
     */
    public synchronized void checkoutPathWithStage(String path, String side) throws GitAPIException {
        CheckoutCommand.Stage stage =
                switch (side) {
                    case "ours" -> CheckoutCommand.Stage.OURS;
                    case "theirs" -> CheckoutCommand.Stage.THEIRS;
                    default -> throw new IllegalArgumentException("side must be 'ours' or 'theirs': " + side);
                };
        git.checkout().addPath(path).setStage(stage).call();
        // Ensure it's staged as resolved
        git.add().addFilepattern(path).call();
        invalidateCaches();
    }

    /**
     * Perform a git mv operation.
     *
     * @param from repository-relative source path
     * @param to repository-relative destination path
     */
    public synchronized void move(String from, String to) throws GitAPIException {
        Path wt = repository.getWorkTree().toPath();
        Path absFrom = wt.resolve(from);
        Path absTo = wt.resolve(to);
        try {
            Files.createDirectories(absTo.getParent());
            Files.move(absFrom, absTo, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
        // Stage as delete + add; Git will detect rename heuristically
        git.rm().addFilepattern(from).call();
        git.add().addFilepattern(to).call();
        invalidateCaches();
    }

    /**
     * Toggle executable bit for a working-tree file and stage the change.
     *
     * @param path repository-relative path
     * @param executable true to set executable, false to unset
     */
    public synchronized void setExecutable(String path, boolean executable) throws GitAPIException {
        Path abs = repository.getWorkTree().toPath().resolve(path);
        try {
            try {
                // Try POSIX permissions first
                var perms = Files.getPosixFilePermissions(abs);
                if (executable) {
                    perms.add(PosixFilePermission.OWNER_EXECUTE);
                } else {
                    perms.remove(PosixFilePermission.OWNER_EXECUTE);
                }
                Files.setPosixFilePermissions(abs, perms);
            } catch (UnsupportedOperationException uoe) {
                // Fallback for non-POSIX filesystems (e.g. Windows)
                var file = abs.toFile();
                // setExecutable(ownerOnly=true) is fine for our purposes
                if (!file.setExecutable(executable, true)) {
                    logger.debug("setExecutable returned false for {}", abs);
                }
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
        // Stage the mode-bit change
        git.add().addFilepattern(path).call();
        invalidateCaches();
    }

    /** Checkout a specific branch */
    @Override
    public void checkout(String branchName) throws GitAPIException {
        git.checkout().setName(branchName).call();
        invalidateCaches();
    }

    /**
     * Checkout a remote branch, creating a local tracking branch with the default naming convention (using the remote
     * branch name)
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

    /** Create a new branch from an existing one without checking it out */
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
     * Creates a new branch from a specific commit. The newBranchName should ideally be the result of
     * {@link #sanitizeBranchName(String)} to ensure it's valid and unique.
     *
     * @param newBranchName The name for the new branch.
     * @param sourceCommitId The commit ID to base the new branch on.
     * @throws GitAPIException if a Git error occurs.
     */
    public void createBranchFromCommit(String newBranchName, String sourceCommitId) throws GitAPIException {
        if (listLocalBranches().contains(newBranchName)) {
            throw new GitStateException(
                    "Branch '" + newBranchName + "' already exists. Use sanitizeBranchName to get a unique name.");
        }

        logger.debug("Creating new branch '{}' from commit '{}'", newBranchName, sourceCommitId);
        git.branchCreate()
                .setName(newBranchName)
                .setStartPoint(sourceCommitId) // JGit's setStartPoint can take a commit SHA
                .call();
        logger.debug("Successfully created branch '{}' from commit '{}'", newBranchName, sourceCommitId);

        invalidateCaches();
    }

    /** Create a new branch from an existing one and check it out */
    public void createAndCheckoutBranch(String newBranchName, String sourceBranchName) throws GitAPIException {
        if (listLocalBranches().contains(newBranchName)) {
            throw new GitStateException(
                    "Branch '" + newBranchName + "' already exists. Use sanitizeBranchName to get a unique name.");
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

    /** Checkout a remote branch, creating a local tracking branch with a specified name */
    public void checkoutRemoteBranch(String remoteBranchName, String localBranchName) throws GitAPIException {
        boolean remoteBranchExists = false;
        try {
            var remoteRef = repository.findRef("refs/remotes/" + remoteBranchName);
            remoteBranchExists = (remoteRef != null);
            logger.debug(
                    "Checking if remote branch exists: {} -> {}", remoteBranchName, remoteBranchExists ? "yes" : "no");
        } catch (Exception e) {
            logger.warn("Error checking remote branch: {}", e.getMessage());
        }

        if (!remoteBranchExists) {
            throw new GitStateException("Remote branch '" + remoteBranchName
                    + "' not found. Ensure the remote exists and has been fetched.");
        }

        if (listLocalBranches().contains(localBranchName)) {
            throw new GitStateException(
                    "Local branch '" + localBranchName + "' already exists. Choose a different name.");
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

    /** Rename a branch */
    public void renameBranch(String oldName, String newName) throws GitAPIException {
        git.branchRename().setOldName(oldName).setNewName(newName).call();
        invalidateCaches();
    }

    /** Check if a branch is fully merged into HEAD */
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

    /** Delete a branch */
    public void deleteBranch(String branchName) throws GitAPIException {
        logger.debug("Attempting to delete branch: {}", branchName);
        var result = git.branchDelete().setBranchNames(branchName).call();
        if (result.isEmpty()) {
            logger.warn("Branch deletion returned empty result for branch: {}", branchName);
            throw new GitStateException(
                    "Branch deletion failed: No results returned. Branch may not exist or requires force delete.");
        }
        for (var deletedRef : result) {
            logger.debug("Successfully deleted branch reference: {}", deletedRef);
        }
    }

    /** Force delete a branch even if it's not fully merged */
    public void forceDeleteBranch(String branchName) throws GitAPIException {
        logger.debug("Attempting to force delete branch: {}", branchName);
        var result =
                git.branchDelete().setBranchNames(branchName).setForce(true).call();
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
        var result = git.merge().include(resolveToCommit(branchName)).call();

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
                    : commitMessages.stream().map(msg -> "- " + msg).collect(Collectors.joining("\n"));
            squashCommitMessage = header + body;
        } catch (GitAPIException e) {
            logger.error(
                    "Failed to get commit messages between {} and {}: {}", branchName, targetBranch, e.getMessage(), e);
            throw e;
        }

        // Perform squash merge
        ObjectId resolvedBranch = resolveToCommit(branchName);

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
            throw new WorktreeDirtyException(
                    "Cannot perform squash merge with staged changes. Please commit or reset them first.");
        }
        if (!status.getModified().isEmpty()) {
            throw new WorktreeDirtyException(
                    "Cannot perform squash merge with modified but uncommitted files. Please commit or stash them first.");
        }

        // Check for untracked files in the target worktree that would be overwritten by the merge
        var changedFiles = listFilesChangedBetweenBranches(branchName, targetBranch).stream()
                .map(mf -> mf.file().toString())
                .collect(Collectors.toSet());
        var untrackedFiles = status.getUntracked();
        var conflictingUntracked =
                untrackedFiles.stream().filter(changedFiles::contains).collect(Collectors.toSet());

        if (!conflictingUntracked.isEmpty()) {
            throw new WorktreeDirtyException(
                    "The following untracked working tree files would be overwritten by merge: "
                            + String.join(", ", conflictingUntracked));
        }

        // Perform squash merge
        var squashResult = git.merge().setSquash(true).include(resolvedBranch).call();

        logger.debug("Squash merge result status: {}", squashResult.getMergeStatus());
        logger.debug("isSuccessful(): {}", squashResult.getMergeStatus().isSuccessful());
        logger.debug("isMergeSuccessful() utility: {}", isMergeSuccessful(squashResult, MergeMode.SQUASH_COMMIT));
        logger.debug("Merge result conflicts: {}", squashResult.getConflicts());
        logger.debug("Merge result failing paths: {}", squashResult.getFailingPaths());
        logger.debug("Merge result checkout conflicts: {}", squashResult.getCheckoutConflicts());

        if (!isMergeSuccessful(squashResult, MergeMode.SQUASH_COMMIT)) {
            logger.warn("Squash merge failed with status: {}", squashResult.getMergeStatus());

            // Provide more specific error information
            if (squashResult.getFailingPaths() != null
                    && !squashResult.getFailingPaths().isEmpty()) {
                String errorDetails = squashResult.getFailingPaths().entrySet().stream()
                        .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                        .collect(Collectors.joining(", "));
                logger.error("Squash merge conflicts: {}", errorDetails);
                invalidateCaches();
                throw new GitOperationException("Squash merge failed due to conflicts in: " + errorDetails);
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
     * Perform a rebase merge of the specified branch into HEAD. This creates a temporary branch, rebases it onto the
     * target, then fast-forward merges.
     *
     * @param branchName The branch to rebase merge
     * @return The result of the final fast-forward merge
     * @throws GitAPIException if any step of the rebase merge fails
     */
    @Override
    public MergeResult rebaseMergeIntoHead(String branchName) throws GitAPIException {
        String targetBranch = getCurrentBranch();
        String tempRebaseBranchName = null;

        try {
            // Create temporary branch for rebase
            tempRebaseBranchName = createTempRebaseBranchName(branchName);
            createBranch(tempRebaseBranchName, branchName);
            checkout(tempRebaseBranchName);

            // Rebase the temporary branch onto target
            ObjectId resolvedTarget = resolveToCommit(targetBranch);
            var rebaseResult = git.rebase().setUpstream(resolvedTarget).call();

            if (!isRebaseSuccessful(rebaseResult)) {
                // Attempt to abort rebase
                try {
                    if (!getCurrentBranch().equals(tempRebaseBranchName)) {
                        checkout(tempRebaseBranchName);
                    }
                    git.rebase()
                            .setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT)
                            .call();
                } catch (GitAPIException abortEx) {
                    logger.error("Failed to abort rebase for {}", tempRebaseBranchName, abortEx);
                }
                throw new GitOperationException("Rebase of '" + branchName + "' onto '" + targetBranch + "' failed: "
                        + rebaseResult.getStatus());
            }

            // Switch back to target branch and fast-forward merge
            checkout(targetBranch);
            MergeResult ffMergeResult = mergeIntoHead(tempRebaseBranchName);

            if (!ffMergeResult.getMergeStatus().isSuccessful()) {
                throw new GitOperationException("Fast-forward merge of rebased '" + tempRebaseBranchName + "' into '"
                        + targetBranch + "' failed: " + ffMergeResult.getMergeStatus());
            }

            invalidateCaches();
            return ffMergeResult;

        } finally {
            // Cleanup: ensure we're on the original branch and delete temp branch
            try {
                if (!getCurrentBranch().equals(targetBranch)) {
                    checkout(targetBranch);
                }
            } catch (GitAPIException e) {
                logger.error("Error ensuring checkout to target branch '{}' during rebase cleanup", targetBranch, e);
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

    /** Revert a specific commit */
    public void revertCommit(String commitId) throws GitAPIException {
        var resolvedCommit = resolveToCommit(commitId);
        git.revert().include(resolvedCommit).call();
        invalidateCaches();
    }

    /** Perform a soft reset to a specific commit */
    public void softReset(String commitId) throws GitAPIException {
        git.reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT)
                .setRef(commitId)
                .call();
        invalidateCaches();
    }

    /** Cherry-picks a commit onto the current HEAD (typically the current branch). */
    public CherryPickResult cherryPickCommit(String commitId) throws GitAPIException {
        var objectId = resolveToCommit(commitId);
        var result = git.cherryPick().include(objectId).call();
        invalidateCaches();
        return result;
    }

    /**
     * Checkout specific files from a commit, restoring them to their state at that commit. This is equivalent to `git
     * checkout <commitId> -- <files>`
     */
    public void checkoutFilesFromCommit(String commitId, List<ProjectFile> files) throws GitAPIException {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No files specified for checkout");
        }

        var checkoutCommand = git.checkout().setStartPoint(commitId);

        // Add each file path to the checkout command
        for (ProjectFile file : files) {
            var relativePath = toRepoRelativePath(file);
            checkoutCommand.addPath(relativePath);
            logger.trace("Adding file to checkout: {}", relativePath);
        }

        checkoutCommand.call();
        invalidateCaches();
    }

    /** Get current branch name */
    @Override
    public String getCurrentBranch() throws GitAPIException {
        try {
            var branch = repository.getBranch();
            if (branch == null) {
                // Check for detached HEAD state by resolving HEAD directly
                ObjectId head;
                try {
                    head = repository.resolve("HEAD");
                } catch (IOException ex) {
                    throw new GitWrappedIOException(ex);
                }
                if (head != null) {
                    try (var reader = repository.newObjectReader()) {
                        var abbrev = reader.abbreviate(head);
                        return abbrev.name();
                    } catch (IOException ioEx) {
                        // Fallback to full SHA on error
                        return head.getName();
                    }
                }
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
        var objectId = resolveToCommit(commitId);

        try (var revWalk = new RevWalk(repository)) {
            var revCommit = revWalk.parseCommit(objectId);
            return Optional.of(this.fromRevCommit(revCommit));
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    // getStashesAsCommits removed, functionality moved to listStashes

    public record RemoteInfo(String url, List<String> branches, List<String> tags, @Nullable String defaultBranch) {}

    /** List commits with detailed information for a specific branch */
    public List<CommitInfo> listCommitsDetailed(String branchName, int maxResults) throws GitAPIException {
        var commits = new ArrayList<CommitInfo>();
        var logCommand = git.log();

        if (!branchName.isEmpty()) {
            try {
                logCommand.add(resolveToCommit(branchName));
            } catch (MissingObjectException | IncorrectObjectTypeException e) {
                throw new GitWrappedIOException(e);
            }
        }

        // Respect maxResults when a finite limit is requested.
        if (maxResults < Integer.MAX_VALUE) {
            logCommand.setMaxCount(maxResults);
        }

        for (var commit : logCommand.call()) {
            commits.add(this.fromRevCommit(commit));
        }
        return commits;
    }

    public List<CommitInfo> listCommitsDetailed(String branchName) throws GitAPIException {
        return listCommitsDetailed(branchName, Integer.MAX_VALUE);
    }
    /**
     * Lists files changed in a specific commit compared to its primary parent. For an initial commit, lists all files
     * in that commit.
     */
    public List<ProjectFile> listFilesChangedInCommit(String commitId) throws GitAPIException {
        var commitObjectId = resolveToCommit(commitId);

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(commitObjectId);
            var newTree = commit.getTree();
            RevTree oldTree = null;

            if (commit.getParentCount() > 0) {
                var parentCommit = revWalk.parseCommit(commit.getParent(0).getId());
                oldTree = parentCommit.getTree();
            }

            try (var diffFormatter =
                    new DiffFormatter(new ByteArrayOutputStream())) { // Output stream is not used for listing files
                diffFormatter.setRepository(repository);
                List<DiffEntry> diffs;
                if (oldTree == null) { // Initial commit
                    // EmptyTreeIterator is not AutoCloseable
                    var emptyTreeIterator = new EmptyTreeIterator();
                    diffs = diffFormatter.scan(
                            emptyTreeIterator, new CanonicalTreeParser(null, repository.newObjectReader(), newTree));
                } else {
                    diffs = diffFormatter.scan(oldTree, newTree);
                }
                return data.extractFilesFromDiffEntries(diffs);
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /** Lists files changed between two commit SHAs (from oldCommitId to newCommitId). */
    @Override
    public List<ProjectFile> listFilesChangedBetweenCommits(String newCommitId, String oldCommitId)
            throws GitAPIException {
        var newObjectId = resolveToCommit(newCommitId);
        var oldObjectId = resolveToCommit(oldCommitId);

        if (newObjectId.equals(oldObjectId)) {
            logger.debug(
                    "listFilesChangedBetweenCommits: newCommitId and oldCommitId are the same ('{}'). Returning empty list.",
                    newCommitId);
            return List.of();
        }

        try (var revWalk = new RevWalk(repository)) {
            var newCommit = revWalk.parseCommit(newObjectId);
            var oldCommit = revWalk.parseCommit(oldObjectId);

            try (var diffFormatter =
                    new DiffFormatter(new ByteArrayOutputStream())) { // Output stream is not used for listing files
                diffFormatter.setRepository(repository);
                var diffs = diffFormatter.scan(oldCommit.getTree(), newCommit.getTree());
                return data.extractFilesFromDiffEntries(diffs);
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * List changed RepoFiles in a commit range.
     *
     * @deprecated Prefer listFilesChangedInCommit or listFilesChangedBetweenCommits for clarity. This method diffs
     *     (lastCommitId + "^") vs (firstCommitId).
     */
    @Override
    @Deprecated
    public List<ProjectFile> listChangedFilesInCommitRange(String firstCommitId, String lastCommitId)
            throws GitAPIException {
        var firstCommitObj = resolveToCommit(firstCommitId);
        var lastCommitObj = resolveToCommit(lastCommitId + "^"); // Note the parent operator here

        try (var revWalk = new RevWalk(repository)) {
            var firstCommit = revWalk.parseCommit(firstCommitObj); // "new"
            var lastCommitParent = revWalk.parseCommit(lastCommitObj); // "old"
            try (var diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) {
                diffFormatter.setRepository(repository);
                var diffs = diffFormatter.scan(lastCommitParent.getTree(), firstCommit.getTree());
                return data.extractFilesFromDiffEntries(diffs);
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /** Show diff between two commits (or a commit and the working directory if newCommitId == HEAD). */
    @Override
    public String showDiff(String newCommitId, String oldCommitId) throws GitAPIException {
        return data.showDiff(newCommitId, oldCommitId);
    }

    /** Retrieves the contents of {@code file} at a given commit ID, or returns an empty string if not found. */
    @Override
    public String getFileContent(String commitId, ProjectFile file) throws GitAPIException {
        return data.getFileContent(commitId, file);
    }

    @Override
    public ObjectId resolveToObject(String revstr) throws GitAPIException {
        try {
            var id = repository.resolve(revstr);
            if (id == null) {
                throw new GitRepoException("Unable to resolve " + revstr, new NoSuchElementException());
            }
            return id;
        } catch (IOException e) {
            throw new GitWrappedIOException("Unable to resolve " + revstr, e);
        }
    }

    @Override
    public ObjectId resolveToCommit(String revstr) throws GitAPIException {
        // Prefer JGit rev-spec peeling first
        try {
            var commitId = repository.resolve(revstr.endsWith("^{commit}") ? revstr : (revstr + "^{commit}"));
            if (commitId != null) {
                return commitId;
            }
        } catch (IOException e) {
            throw new GitWrappedIOException("Unable to resolve commit-ish " + revstr, e);
        }

        // Fallback: resolve to any object and try to peel with RevWalk
        var anyId = resolveToObject(revstr);
        try (var rw = new RevWalk(repository)) {
            try {
                var commit = rw.parseCommit(anyId);
                return commit.getId();
            } catch (IncorrectObjectTypeException e) {
                try {
                    var any = rw.parseAny(anyId);
                    var peeled = rw.peel(any);
                    if (peeled instanceof RevCommit rc) {
                        return rc.getId();
                    }
                    throw new GitStateException("Reference does not resolve to a commit: " + revstr);
                } catch (IOException ioEx) {
                    throw new GitWrappedIOException("Unable to peel object to commit for " + revstr, ioEx);
                }
            } catch (IOException e) {
                throw new GitWrappedIOException("Unable to parse commit-ish " + revstr, e);
            }
        }
    }

    /** Show diff for a specific file between two commits. */
    @Override
    public String showFileDiff(String commitIdA, String commitIdB, ProjectFile file) throws GitAPIException {
        return data.showFileDiff(commitIdA, commitIdB, file);
    }

    /**
     * Apply a diff to the working directory.
     *
     * @param diff The diff to apply.
     * @throws GitAPIException if applying the diff fails.
     */
    @Override
    public void applyDiff(String diff) throws GitAPIException {
        data.applyDiff(diff);
    }

    /** Create a stash from the current changes */
    public @Nullable RevCommit createStash(String message) throws GitAPIException {
        assert !message.isEmpty();
        logger.debug("Creating stash with message: {}", message);
        var stashId = git.stashCreate().setWorkingDirectoryMessage(message).call();
        logger.debug("Stash created with ID: {}", (stashId != null ? stashId.getName() : "none"));
        invalidateCaches();
        return stashId;
    }

    /**
     * Create a stash containing only the specified files. This involves a more complex workflow: 1. Get all uncommitted
     * files 2. Add UN-selected files to index (i.e., everything EXCEPT the files we want to stash) 3. Commit those
     * unselected files to a temporary branch 4. Stash what's left (which will be only our selected files) 5. Soft-reset
     * back to restore the working directory with the UN-selected files uncommitted 6. Clean up the temporary branch
     *
     * @param message The stash message
     * @param filesToStash The specific files to include in the stash
     * @throws GitAPIException If there's an error during the stash process
     */
    public @Nullable RevCommit createPartialStash(String message, List<ProjectFile> filesToStash)
            throws GitAPIException {
        assert !message.isEmpty();
        assert !filesToStash.isEmpty();

        logger.debug("Creating partial stash with message: {} for {} files", message, filesToStash.size());

        // 1. Determine initial staging state
        var status = git.status().call();
        var stagedPaths = new HashSet<String>();
        stagedPaths.addAll(status.getAdded());
        stagedPaths.addAll(status.getChanged());
        stagedPaths.addAll(status.getRemoved());
        var originallyStagedFiles =
                stagedPaths.stream().map(this::toProjectFile).collect(Collectors.toSet());

        var allUncommittedFilesWithStatus = getModifiedFiles();
        var allUncommittedProjectFiles =
                allUncommittedFilesWithStatus.stream().map(ModifiedFile::file).collect(Collectors.toSet());
        if (!allUncommittedProjectFiles.containsAll(new HashSet<>(filesToStash))) {
            throw new GitStateException("Files to stash are not actually uncommitted!?");
        }

        // Files NOT to stash
        var filesToKeep = allUncommittedFilesWithStatus.stream()
                .map(ModifiedFile::file)
                .filter(file -> !filesToStash.contains(file))
                .collect(Collectors.toList());

        if (filesToKeep.isEmpty()) {
            // If all changed files are selected for stashing, just do a regular stash
            logger.debug("All changed files selected for stashing, using regular stash");
            return createStash(message);
        }

        // Remember the original branch
        String originalBranch = getCurrentBranch();
        String tempBranchName = "temp-stash-branch-" + System.currentTimeMillis();
        RevCommit stashId;

        try {
            // 2. Prepare index for temporary commit:
            // - Stage all files we want to keep (those not being stashed)
            // - Unstage any files that are to be stashed but were already staged
            add(filesToKeep);

            var stagedFilesToStash = filesToStash.stream()
                    .filter(originallyStagedFiles::contains)
                    .toList();
            if (!stagedFilesToStash.isEmpty()) {
                var resetCommand = git.reset();
                for (var file : stagedFilesToStash) {
                    resetCommand.addPath(toRepoRelativePath(file));
                }
                resetCommand.call();
            }

            // 3. Create a temporary branch and commit the staged files
            logger.debug("Creating temporary branch: {}", tempBranchName);
            git.branchCreate().setName(tempBranchName).call();
            git.checkout().setName(tempBranchName).call();

            logger.debug("Committing UN-selected files to temporary branch");
            git.commit()
                    .setMessage("Temporary commit to facilitate partial stash")
                    .call();

            // 4. Prepare the remaining files (those to be stashed) by restoring their original staging state
            if (!stagedFilesToStash.isEmpty()) {
                add(stagedFilesToStash);
            }

            // 5. Stash the remaining changes
            logger.debug("Creating stash with only the selected files");
            stashId = git.stashCreate().setWorkingDirectoryMessage(message).call();
            logger.debug("Partial stash created with ID: {}", (stashId != null ? stashId.getName() : "none"));

            // 6. Soft reset to undo the temporary commit, leaving its changes staged
            logger.debug("Soft resetting to restore staged changes of files not stashed");
            git.reset()
                    .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT)
                    .setRef("HEAD~1")
                    .call();

            // 7. Unstage any files from the temporary commit that were not originally staged
            var unstagedFilesToKeep = filesToKeep.stream()
                    .filter(f -> !originallyStagedFiles.contains(f))
                    .toList();
            if (!unstagedFilesToKeep.isEmpty()) {
                var resetCommand = git.reset();
                for (var file : unstagedFilesToKeep) {
                    resetCommand.addPath(toRepoRelativePath(file));
                }
                resetCommand.call();
            }
        } finally {
            // 8. Cleanup: switch back to the original branch and delete the temporary one
            if (!getCurrentBranch().equals(originalBranch)) {
                logger.debug("Checking out original branch: {}", originalBranch);
                git.checkout().setName(originalBranch).call();
            }

            logger.debug("Deleting temporary branch");
            git.branchDelete().setBranchNames(tempBranchName).setForce(true).call();

            invalidateCaches();
        }

        return stashId;
    }

    // StashInfo record removed

    /** Lists all stashes in the repository as CommitInfo objects. */
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

    /** Apply a stash to the working directory without removing it from the stash list */
    public void applyStash(int stashIndex) throws GitAPIException {
        String stashRef = "stash@{" + stashIndex + "}";
        logger.debug("Applying stash: {}", stashRef);
        git.stashApply().setStashRef(stashRef).call();
        logger.debug("Stash applied successfully");
        invalidateCaches();
    }

    /** Pop a stash – apply it, then drop it */
    public void popStash(int stashIndex) throws GitAPIException {
        var stashRef = "stash@{" + stashIndex + "}";
        logger.debug("Popping stash {}", stashRef);
        git.stashApply().setStashRef(stashRef).call();
        git.stashDrop().setStashRef(stashIndex).call();
        logger.debug("Stash pop completed successfully");
        invalidateCaches();
    }

    /** Drop a stash without applying it */
    public void dropStash(int stashIndex) throws GitAPIException {
        logger.debug("Dropping stash at index: {}", stashIndex);
        git.stashDrop().setStashRef(stashIndex).call();
        logger.debug("Stash dropped successfully");
        invalidateCaches();
    }

    /** Returns the full (multi-line) commit message for the given commit id. */
    public String getCommitFullMessage(String commitId) throws GitAPIException {
        var objId = resolveToCommit(commitId);
        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            return commit.getFullMessage();
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /** Get the commit history for specific files */
    public List<CommitInfo> getFileHistory(ProjectFile file, int maxResults) throws GitAPIException {
        var commits = new LinkedHashSet<CommitInfo>();
        try {
            var headId = resolveToCommit("HEAD");
            var diffconfig = repository.getConfig().get(DiffConfig.KEY);

            var path = toRepoRelativePath(file);
            try (var revWalk = new RevWalk(repository)) {
                revWalk.setTreeFilter(FollowFilter.create(path, diffconfig));
                revWalk.markStart(revWalk.parseCommit(headId));

                for (var commit : revWalk) {
                    commits.add(this.fromRevCommit(commit));
                    if (commits.size() >= maxResults) {
                        return new ArrayList<>(commits);
                    }
                }
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
        return new ArrayList<>(commits);
    }

    /** One commit in a file’s history together with the path the file had inside that commit. */
    public record FileHistoryEntry(CommitInfo commit, ProjectFile path) {}

    /**
     * Like {`getFileHistory`} but also returns, for each commit, the path the file had *in that commit* (following
     * renames backwards).
     *
     * @param file the file (at its current path) whose history we want
     */
    public List<FileHistoryEntry> getFileHistoryWithPaths(ProjectFile file) throws GitAPIException {
        // 1. normal commit list, newest → oldest (already follows renames)
        var commits = getFileHistory(file, Integer.MAX_VALUE);
        if (commits.isEmpty()) {
            return new ArrayList<>();
        }

        var results = new ArrayList<FileHistoryEntry>(commits.size());
        var currPath = file; // path valid for current head
        var currGitRel = toRepoRelativePath(currPath);

        try (var revWalk = new RevWalk(repository);
                var diffFmt = new DiffFormatter(new ByteArrayOutputStream())) {
            diffFmt.setRepository(repository);
            diffFmt.setDetectRenames(true);

            for (var commitInfo : commits) {
                // record current path for this commit
                results.add(new FileHistoryEntry(commitInfo, currPath));

                // prepare for next (older) commit
                RevCommit commit;
                try {
                    commit = revWalk.parseCommit(ObjectId.fromString(commitInfo.id()));
                } catch (IOException | IllegalArgumentException e) {
                    logger.warn("Failed to parse commit {}: {}", commitInfo.id(), e.getMessage());
                    continue; // Skip this commit but continue processing
                }

                if (commit.getParentCount() == 0) {
                    // reached root – no parent to diff against
                    logger.debug("Reached root commit {} with no parents", commitInfo.id());
                    continue;
                }

                // For merge commits, check all parents for renames
                boolean renameFound = false;
                for (int i = 0; i < commit.getParentCount() && !renameFound; i++) {
                    try {
                        var parent = revWalk.parseCommit(commit.getParent(i).getId());
                        var diffs = diffFmt.scan(parent.getTree(), commit.getTree());

                        for (var d : diffs) {
                            if (d.getChangeType() == DiffEntry.ChangeType.RENAME
                                    && d.getNewPath().equals(currGitRel)) {
                                // file was renamed in THIS commit; older commits use oldPath
                                logger.debug(
                                        "Detected rename: {} -> {} in commit {}",
                                        d.getOldPath(),
                                        d.getNewPath(),
                                        commitInfo.id());
                                currGitRel = d.getOldPath();
                                currPath = toProjectFile(currGitRel);
                                renameFound = true;
                                break;
                            }
                        }
                    } catch (IOException e) {
                        logger.warn("Failed to process parent {} of commit {}: {}", i, commitInfo.id(), e.getMessage());
                        // Continue with next parent
                    }
                }

                logger.debug("Processing commit {}, current path: {}", commitInfo.id(), currGitRel);
            }
        }
        return results;
    }

    /**
     * Collect commit history for multiple files using a single RevWalk.
     * Follows renames by diffing each commit to its parent with DiffFormatter (rename detection on).
     *
     * Strategy:
     *  - Start with each file's current repo-relative path.
     *  - Walk commits from HEAD backwards (commit-time desc).
     *  - For each commit, diff(parent, commit). If a diff entry touches a tracked path:
     *      - record the commit for that file
     *      - if it's a RENAME where newPath == trackedPath, update trackedPath := oldPath
     *  - Once a file accumulates maxResults, stop tracking it.
     *  - Early-exit when all files are satisfied.
     *
     * Notes:
     *  - Uses first parent only for speed. If you need true merge-aware attribution, iterate all parents
     *    (slower) or make it a toggle.
     *  - Bodies are not retained (RevWalk#setRetainBody(false)).
     */
    public List<CommitInfo> getFileHistories(Collection<ProjectFile> files, int maxResults) throws GitAPIException {
        if (files.isEmpty() || maxResults <= 0) return List.of();

        final Map<ProjectFile, String> trackedPath = new LinkedHashMap<>();
        for (var f : files) trackedPath.put(f, toRepoRelativePath(f));

        final Map<ProjectFile, List<CommitInfo>> results = new LinkedHashMap<>();
        final Set<ProjectFile> active = new LinkedHashSet<>(files);
        for (var f : files) results.put(f, new ArrayList<>(Math.min(maxResults, 32)));

        try (var revWalk = new RevWalk(repository);
                var df = new org.eclipse.jgit.diff.DiffFormatter(
                        org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE)) {

            var headId = resolveToCommit("HEAD");
            var head = revWalk.parseCommit(headId);

            // Keep headers only for speed; we'll parse bodies lazily on demand.
            revWalk.setRetainBody(false);
            revWalk.sort(RevSort.COMMIT_TIME_DESC, true);
            revWalk.markStart(head);

            df.setRepository(repository);
            df.setDetectRenames(true);

            for (var commit : revWalk) {
                if (active.isEmpty()) break;

                RevCommit parent = (commit.getParentCount() > 0) ? revWalk.parseCommit(commit.getParent(0)) : null;
                final var newTree = commit.getTree();
                final var oldTree = (parent == null) ? null : parent.getTree();

                final List<DiffEntry> diffs = df.scan(oldTree, newTree);
                if (diffs.isEmpty()) continue;

                final Set<String> currentPaths = new HashSet<>();
                for (var f : active) currentPaths.add(trackedPath.get(f));

                boolean anyHit = false;
                final Map<ProjectFile, String> backRename = new HashMap<>();

                for (var de : diffs) {
                    final String newPath = de.getNewPath();
                    if (!currentPaths.contains(newPath)) continue;

                    for (var f : active) {
                        if (!Objects.equals(trackedPath.get(f), newPath)) continue;

                        // We’re about to read the short message → ensure the body is available.
                        // This is cheap and only runs for commits we actually keep.
                        revWalk.parseBody(commit);

                        var commits = requireNonNull(results.get(f));
                        commits.add(fromRevCommit(commit));
                        anyHit = true;

                        if (de.getChangeType() == DiffEntry.ChangeType.RENAME) {
                            backRename.put(f, de.getOldPath());
                        }
                    }
                }

                trackedPath.putAll(backRename);
                if (anyHit) {
                    active.removeIf(f -> requireNonNull(results.get(f)).size() >= maxResults);
                }
            }

            return results.values().stream()
                    .flatMap(List::stream)
                    .distinct()
                    .sorted((a, b) -> b.date().compareTo(a.date()))
                    .toList();

        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * Get the URL of the target remote using Git's standard remote resolution including upstream from current branch
     */
    @Override
    public @Nullable String getRemoteUrl() {
        return remote().getUrl();
    }

    /**
     * Reads the .gitignore file from the repository root and returns a list of patterns. Filters out empty lines and
     * comments (lines starting with #).
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
            return lines.map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.warn("Error reading .gitignore file at {}: {}", gitignoreFile, e.getMessage());
            return List.of();
        }
    }

    /**
     * Search commits whose full message, author name, or author e-mail match the supplied regular expression
     * (case-insensitive).
     *
     * @param query a Java regular-expression pattern
     * @return matching commits, newest first (same order as {@code git log})
     * @throws GitAPIException on git errors
     * @throws GitRepoException if the regex is invalid
     */
    public List<CommitInfo> searchCommits(String query) throws GitAPIException {
        var matches = new ArrayList<CommitInfo>();

        Pattern pattern = null;
        boolean regexValid = true;
        try {
            // Prepare case-insensitive regex pattern
            var preparedPattern = query.contains(".*") ? query : ".*" + query + ".*";
            pattern = Pattern.compile("(?i)" + preparedPattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            if (query.startsWith("(?i).*")) {
                // Propagate exception to indicate we should try a fallback pattern
                throw e;
            } else {
                // Try again with pattern escaping from the start
                try {
                    return searchCommits(".*" + Pattern.quote(query) + ".*");
                } catch (PatternSyntaxException rethrownException) {
                    regexValid = false;
                }
            }
        }
        final String fallbackPattern = query.toLowerCase(Locale.ROOT);

        for (var commit : git.log().call()) {
            var msg = commit.getFullMessage();
            var author = commit.getAuthorIdent();
            var name = author.getName();
            var email = author.getEmailAddress();

            boolean match;
            if (regexValid && pattern != null) {
                match = pattern.matcher(msg).find()
                        || pattern.matcher(name).find()
                        || pattern.matcher(email).find();
            } else {
                match = msg.toLowerCase(Locale.ROOT).contains(fallbackPattern)
                        || name.toLowerCase(Locale.ROOT).contains(fallbackPattern)
                        || email.toLowerCase(Locale.ROOT).contains(fallbackPattern);
            }

            if (match) {
                matches.add(this.fromRevCommit(commit));
            }
        }
        return matches;
    }

    @Override
    public void close() {
        git.close();
        repository.close();
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

    static class GitWrappedIOException extends GitAPIException {
        public GitWrappedIOException(IOException e) {
            this(e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }

        public GitWrappedIOException(String message, IOException e) {
            super(message, e);
        }
    }

    /** Factory method to create CommitInfo from a JGit RevCommit. Assumes this is NOT a stash commit. */
    public CommitInfo fromRevCommit(RevCommit commit) {
        return new CommitInfo(
                this,
                commit.getName(),
                commit.getShortMessage(),
                commit.getAuthorIdent().getName(),
                commit.getCommitterIdent().getWhenAsInstant());
    }

    /** Factory method to create CommitInfo for a stash entry from a JGit RevCommit. */
    public CommitInfo fromStashCommit(RevCommit commit, int index) {
        return new CommitInfo(
                this,
                commit.getName(),
                commit.getShortMessage(),
                commit.getAuthorIdent().getName(),
                commit.getAuthorIdent().getWhenAsInstant(),
                index);
    }

    /** Computes the merge base of two commits. */
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
     * Returns the SHA-1 of the merge base between the two rev-specs (or null if none exists). revA and revB may be
     * branch names, tags, commit IDs, etc.
     */
    public @Nullable String getMergeBase(String revA, String revB) throws GitAPIException {
        var idA = resolveToCommit(revA);
        var idB = resolveToCommit(revB);
        var mb = computeMergeBase(idA, idB);
        return mb == null ? null : mb.getName();
    }

    /** Returns true if the given commit is reachable from the specified base ref (e.g., "HEAD" or a branch name). */
    public boolean isCommitReachableFrom(String commitId, String baseRef) throws GitAPIException {
        var commitObj = resolveToCommit(commitId);
        var baseObj = resolveToCommit(baseRef);
        try (var revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(commitObj);
            RevCommit base = revWalk.parseCommit(baseObj);
            return revWalk.isMergedInto(commit, base);
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    public record ListWorktreesResult(List<WorktreeInfo> worktrees, List<Path> invalidPaths) {}

    /** Lists all worktrees in the repository. */
    @Override
    public List<WorktreeInfo> listWorktrees() throws GitAPIException {
        return worktrees().listWorktrees();
    }

    /** Adds a new worktree at the specified path for the given branch. */
    @Override
    public void addWorktree(String branch, Path path) throws GitAPIException {
        worktrees().addWorktree(branch, path);
    }

    /**
     * Removes the worktree at the specified path. This method will fail if the worktree is dirty or has other issues
     * preventing a clean removal, in which case a {@link WorktreeNeedsForceException} will be thrown.
     *
     * @param path The path to the worktree to remove.
     * @throws WorktreeNeedsForceException if the worktree cannot be removed without force.
     * @throws GitAPIException if a different Git error occurs.
     */
    @Override
    public void removeWorktree(Path path, boolean force) throws GitAPIException {
        worktrees().removeWorktree(path, force);
    }

    /** Returns true if this repository is a Git worktree. */
    @Override
    public boolean isWorktree() {
        return worktrees().isWorktree();
    }

    /** Returns the set of branches that are checked out in worktrees. */
    @Override
    public Set<String> getBranchesInWorktrees() throws GitAPIException {
        return worktrees().getBranchesInWorktrees();
    }

    /**
     * Determines the next available path for a new worktree in the specified storage directory. It looks for paths
     * named "wt1", "wt2", etc., and returns the first one that doesn't exist.
     *
     * @param worktreeStorageDir The directory where worktrees are stored.
     * @return A Path for the new worktree.
     * @throws IOException if an I/O error occurs when checking for directory existence.
     */
    @Override
    public Path getNextWorktreePath(Path worktreeStorageDir) throws IOException {
        return worktrees().getNextWorktreePath(worktreeStorageDir);
    }

    @Override
    public boolean supportsWorktrees() {
        return worktrees().supportsWorktrees();
    }

    @Override
    public List<String> getCommitMessagesBetween(String branchName, String targetBranchName) throws GitAPIException {
        var revCommits = getRevCommitsBetween(branchName, targetBranchName, false);
        return revCommits.stream().map(RevCommit::getShortMessage).collect(Collectors.toList());
    }

    /**
     * Lists commits between two branches, returning detailed CommitInfo objects. The commits are those on
     * {@code sourceBranchName} that are not on {@code targetBranchName}. Commits are returned in chronological order
     * (oldest first).
     */
    public List<CommitInfo> listCommitsBetweenBranches(
            String sourceBranchName, String targetBranchName, boolean excludeMergeCommitsFromTarget)
            throws GitAPIException {
        var revCommits = getRevCommitsBetween(sourceBranchName, targetBranchName, excludeMergeCommitsFromTarget);
        return revCommits.stream().map(this::fromRevCommit).collect(Collectors.toList());
    }

    private List<RevCommit> getRevCommitsBetween(
            String sourceBranchName, String targetBranchName, boolean excludeMergeCommitsFromTarget)
            throws GitAPIException {
        List<RevCommit> commits = new ArrayList<>();
        ObjectId sourceHead = resolveToCommit(sourceBranchName);
        ObjectId targetHead = resolveToCommit(targetBranchName);

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
                    logger.warn(
                            "No common merge base found between {} ({}) and {} ({}). Listing commits from {} not on {}.",
                            sourceBranchName,
                            sourceCommit.getName(),
                            targetBranchName,
                            targetCommit.getName(),
                            sourceBranchName,
                            targetBranchName);
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
     * Lists files changed between two branches, specifically the changes introduced on the source branch since it
     * diverged from the target branch.
     */
    public List<ModifiedFile> listFilesChangedBetweenBranches(String sourceBranch, String targetBranch)
            throws GitAPIException {
        ObjectId sourceHeadId = resolveToCommit(sourceBranch);

        ObjectId targetHeadId = resolveToCommit(targetBranch); // Can be null if target branch doesn't exist
        logger.debug(
                "Resolved source branch '{}' to {}, target branch '{}' to {}",
                sourceBranch,
                sourceHeadId,
                targetBranch,
                targetHeadId);

        ObjectId mergeBaseId = computeMergeBase(sourceHeadId, targetHeadId);

        if (mergeBaseId == null) {
            // If no common ancestor is found (e.g., unrelated histories, one branch is new, or one head was null),
            // use the target's head as the base. If targetHeadId is also null (target branch doesn't exist),
            // mergeBaseId will remain null, leading to a diff against an empty tree.
            logger.debug(
                    "No common merge base computed for source {} ({}) and target {} ({}). "
                            + "Falling back to target head {}.",
                    sourceBranch,
                    sourceHeadId,
                    targetBranch,
                    targetHeadId,
                    targetHeadId);
            mergeBaseId = targetHeadId;
        }
        logger.debug(
                "Effective merge base for diffing {} ({}) against {} ({}) is {}",
                sourceBranch,
                sourceHeadId,
                targetBranch,
                targetHeadId,
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
                    // Handled by only using relevant paths (getOldPath for DELETE, getNewPath for
                    // ADD/COPY/MODIFY/RENAME's new side)
                    // and relying on toProjectFile which would inherently handle or error on
                    // /dev/null if it were a
                    // real project path.
                    // The main concern is ensuring we use the correct path (old vs new) based on ChangeType.

                    var result =
                            switch (entry.getChangeType()) {
                                case ADD, COPY -> new ModifiedFile(toProjectFile(entry.getNewPath()), "new");
                                case MODIFY -> new ModifiedFile(toProjectFile(entry.getNewPath()), "modified");
                                case DELETE -> new ModifiedFile(toProjectFile(entry.getOldPath()), "deleted");
                                case RENAME -> {
                                    modifiedFiles.add(new ModifiedFile(toProjectFile(entry.getOldPath()), "deleted"));
                                    yield new ModifiedFile(toProjectFile(entry.getNewPath()), "new");
                                }
                                default -> {
                                    logger.warn(
                                            "Unhandled DiffEntry ChangeType: {} for old path '{}', new path '{}'",
                                            entry.getChangeType(),
                                            entry.getOldPath(),
                                            entry.getNewPath());
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
        logger.debug(
                "Found {} files changed between {} and {}: {}",
                modifiedFiles.size(),
                sourceBranch,
                targetBranch,
                modifiedFiles);
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
     * Checks for historical conflicts between two branches by performing a simulation in a temporary worktree. This
     * method does NOT check for conflicts with the current (dirty) worktree state.
     *
     * @return A string describing the conflict, or null if no historical conflicts are found.
     * @throws WorktreeDirtyException if the target branch has uncommitted changes preventing simulation.
     */
    @Override
    public @Nullable String checkMergeConflicts(String worktreeBranchName, String targetBranchName, MergeMode mode)
            throws GitAPIException {
        // This check is a safeguard. If the target branch has uncommitted changes,
        // `git worktree add` might fail. This gives a cleaner error to the user.
        var status = git.status().call();

        // A worktree is considered “dirty” for merge-simulation purposes ONLY if
        //   • the index contains staged additions / changes, OR
        //   • any *tracked* file is modified, missing, or staged for removal.
        // Untracked/ignored files are allowed; they will be checked separately
        // against the list of paths the merge would touch.
        boolean indexDirty =
                !status.getAdded().isEmpty() || !status.getChanged().isEmpty();

        boolean trackedWTDirty = !status.getModified().isEmpty()
                || !status.getMissing().isEmpty()
                || !status.getRemoved().isEmpty();

        if (indexDirty || trackedWTDirty) {
            throw new WorktreeDirtyException("Target worktree has uncommitted changes.");
        }

        ObjectId worktreeBranchId = resolveToCommit(worktreeBranchName);
        ObjectId targetBranchId = resolveToCommit(targetBranchName);

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

        // fixme: Temporarily disable signing in the in-memory config until we support signing
        final var config = git.getRepository().getConfig();
        final boolean oldSignConfig = config.getBoolean("commit", null, "gpgsign", false);
        try {
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        } catch (IOException e) {
            logger.warn("Exception encountered while attempting to temporarily disable GPG signing.", e);
        }

        try {
            // Add a detached worktree on the target branch
            var addCommand = String.format(
                    "git worktree add --detach %s %s", tempWorktreePath.toAbsolutePath(), targetBranchId.getName());
            Environment.instance.runShellCommand(addCommand, getGitTopLevel(), out -> {}, Environment.GIT_TIMEOUT);

            // Create a GitRepo instance for the temporary worktree to run the simulation
            try (GitRepo tempRepo = new GitRepo(tempWorktreePath)) {
                if (mode == MergeMode.REBASE_MERGE) {
                    var rebaseResult =
                            tempRepo.git.rebase().setUpstream(worktreeBranchId).call();
                    if (rebaseResult.getStatus() == RebaseResult.Status.CONFLICTS
                            || rebaseResult.getStatus() == RebaseResult.Status.STOPPED) {
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
                        return "Merge conflicts detected in: "
                                + String.join(", ", mergeResult.getConflicts().keySet());
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
                Environment.instance.runShellCommand(
                        removeCommand, getGitTopLevel(), out -> {}, Environment.GIT_TIMEOUT);
            } catch (Exception e) {
                logger.error("Failed to clean up temporary worktree at {}", tempWorktreePath, e);
            }
            // Re-enable old signing setting
            try {
                config.setBoolean("commit", null, "gpgsign", oldSignConfig);
                config.save();
            } catch (IOException e) {
                logger.warn("Exception encountered while attempting to set GPG signing to previous value.", e);
            }
        }
    }

    /**
     * Attempts to determine the repository's default branch. Order of preference: 1. The symbolic ref
     * refs/remotes/origin/HEAD (remote's default) 2. Local branch named 'main' 3. Local branch named 'master' 4. First
     * local branch (alphabetically)
     *
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
