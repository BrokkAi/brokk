package ai.brokk.git;

import static java.util.Objects.requireNonNull;

import ai.brokk.SessionRegistry;
import ai.brokk.git.IGitRepo.WorktreeInfo;
import ai.brokk.util.Environment;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.jetbrains.annotations.Nullable;

/**
 * Helper class extracted from GitRepo to encapsulate worktree operations.
 *
 * <p>This class keeps a reference to the owning GitRepo and obtains the JGit Repository via repo.getRepository().
 */
public class GitRepoWorktrees {
    private static final Logger logger = LogManager.getLogger(GitRepoWorktrees.class);

    private final GitRepo repo;
    private final Repository repository;

    public GitRepoWorktrees(GitRepo repo) {
        this.repo = repo;
        this.repository = repo.getRepository();
    }

    // Pattern to detect common git-lfs missing/errors lines in git output (case-insensitive).
    // Matches examples like:
    //   git: 'lfs' is not a git command
    //   git-lfs: command not found
    //   external filter 'git-lfs filter-process' failed
    //   This repository is configured for Git LFS
    //   'git-lfs' is not recognized as an internal or external command
    private static final Pattern LFS_MISSING_PATTERN = Pattern.compile(
            "(?i)(git:\\s*'lfs' is not a git command|git-lfs.*not found|external filter 'git-lfs filter-process' failed|this repository is configured for git lfs|is not recognized as an internal or external command|smudge filter lfs failed|filter-process.*failed)");

    private boolean isLfsMissing(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return LFS_MISSING_PATTERN.matcher(output).find();
    }

    /** Probe system 'git --version' in a best-effort way; returns empty string on failure. */
    private String probeGitVersion() {
        try {
            String out = Environment.instance.runShellCommand(
                    "git --version", repo.getGitTopLevel(), o -> {}, Environment.GIT_TIMEOUT);
            return out == null ? "" : out.trim();
        } catch (Environment.SubprocessException e) {
            // Best-effort: return empty to indicate unknown
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * Probe system 'git lfs version' in a best-effort way.
     * Returns the trimmed output if present, or null if git-lfs appears missing/unavailable.
     */
    private @Nullable String probeGitLfsVersion() {
        try {
            String out = Environment.instance.runShellCommand(
                    "git lfs version", repo.getGitTopLevel(), o -> {}, Environment.GIT_TIMEOUT);
            if (out == null || out.isBlank()) return null;
            return out.trim();
        } catch (Environment.SubprocessException e) {
            // git-lfs may be absent; signal by returning null
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Best-effort probe for a path to the `git` executable. On Windows runs `where git`, on macOS/Linux runs
     * `command -v git`. Returns the first non-empty line of output, trimmed, or null on failure.
     */
    private @Nullable String probeGitPath() {
        try {
            String cmd = Environment.isWindows() ? "where git" : "command -v git";
            String out =
                    Environment.instance.runShellCommand(cmd, repo.getGitTopLevel(), o -> {}, Environment.GIT_TIMEOUT);
            if (out == null || out.isBlank()) return null;
            var lines = Splitter.on(Pattern.compile("\\R")).splitToList(out);
            if (lines.isEmpty()) return null;
            String first = lines.get(0).trim();
            return first.isEmpty() ? null : first;
        } catch (Environment.SubprocessException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Create a preview of the given output: first maxLines lines, truncated to maxChars if needed.
     * This is intended for concise logging/UI summaries (we still keep full output in the exception).
     */
    private static String previewOutput(@Nullable String output, int maxLines, int maxChars) {
        if (output == null || output.isEmpty()) return "<no output>";
        var lines = Splitter.on(Pattern.compile("\\R")).splitToList(output);
        boolean moreLines = lines.size() > maxLines;
        String joined = lines.stream().limit(maxLines).collect(Collectors.joining("\n"));
        if (joined.length() > maxChars) {
            joined = joined.substring(0, maxChars) + "\n...(truncated)";
        } else if (moreLines) {
            joined = joined + "\n...(truncated)";
        }
        return joined;
    }

    /** Lists worktrees and invalid paths (those that don't exist on disk). */
    public GitRepo.ListWorktreesResult listWorktreesAndInvalid() throws GitAPIException {
        try {
            var command = "git worktree list --porcelain";
            var output = Environment.instance.runShellCommand(
                    command, repo.getGitTopLevel(), out -> {}, Environment.GIT_TIMEOUT);
            var worktrees = new ArrayList<WorktreeInfo>();
            var invalidPaths = new ArrayList<Path>();
            var lines = Splitter.on(Pattern.compile("\\R")).splitToList(output); // Split by any newline sequence

            Path currentPath = null;
            String currentHead = null;
            String currentBranch = null;

            for (var line : lines) {
                if (line.startsWith("worktree ")) {
                    // Finalize previous entry if data is present
                    if (currentPath != null) {
                        worktrees.add(new WorktreeInfo(currentPath, currentBranch, requireNonNull(currentHead)));
                    }
                    // Reset for next entry
                    currentHead = null;
                    currentBranch = null;

                    var pathStr = line.substring("worktree ".length());
                    try {
                        currentPath = Path.of(pathStr).toRealPath();
                    } catch (NoSuchFileException e) {
                        logger.warn("Worktree path does not exist, scheduling for prune: {}", pathStr);
                        invalidPaths.add(Path.of(pathStr));
                        currentPath = null; // Mark as invalid for subsequent processing
                    } catch (IOException e) {
                        throw new GitRepo.GitRepoException("Failed to resolve worktree path: " + pathStr, e);
                    }
                } else if (line.startsWith("HEAD ")) {
                    // Only process if current worktree path is valid
                    if (currentPath != null) {
                        currentHead = line.substring("HEAD ".length());
                    }
                } else if (line.startsWith("branch ")) {
                    if (currentPath != null) {
                        var branchRef = line.substring("branch ".length());
                        if (branchRef.startsWith("refs/heads/")) {
                            currentBranch = branchRef.substring("refs/heads/".length());
                        } else {
                            currentBranch = branchRef; // Should not happen with porcelain but good to be defensive
                        }
                    }
                } else if (line.equals("detached")) {
                    if (currentPath != null) {
                        // Detached-HEAD worktree: branch remains null (WorktreeInfo.branch is @Nullable).
                        currentBranch = null;
                    }
                }
            }
            // Add the last parsed worktree
            if (currentPath != null) {
                worktrees.add(new WorktreeInfo(currentPath, currentBranch, requireNonNull(currentHead)));
            }
            return new GitRepo.ListWorktreesResult(worktrees, invalidPaths);
        } catch (Environment.SubprocessException e) {
            throw new GitRepo.GitRepoException("Failed to list worktrees: " + e.getOutput(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitRepo.GitRepoException("Listing worktrees was interrupted", e);
        }
    }

    /** Lists all worktrees in the repository. */
    public List<WorktreeInfo> listWorktrees() throws GitAPIException {
        return listWorktreesAndInvalid().worktrees();
    }

    /** Adds a new worktree at the specified path for the given branch. */
    public void addWorktree(String branch, Path path) throws GitAPIException {
        String command = "";
        // Best-effort provenance collection (do not let failures affect primary flow)
        String gitVersion = probeGitVersion(); // returns "" on failure
        String lfsVersion = probeGitLfsVersion(); // returns null on failure
        String gitPath = probeGitPath(); // returns null on failure

        logger.debug(
                "Worktree add provenance: branch='{}', path='{}', gitVersion='{}', gitLfsVersion='{}', gitPath='{}'",
                branch,
                path,
                gitVersion,
                lfsVersion,
                gitPath);

        try {
            // Ensure path is absolute for the command
            var absolutePath = path.toAbsolutePath().normalize();

            // Check if branch exists locally
            List<String> localBranches = repo.listLocalBranches();
            if (localBranches.contains(branch)) {
                // Branch exists, checkout the existing branch
                command = String.format("git worktree add %s %s", absolutePath, branch);
            } else {
                // Branch doesn't exist, create a new one
                command = String.format("git worktree add -b %s %s", branch, absolutePath);
            }
            Environment.instance.runShellCommand(command, repo.getGitTopLevel(), out -> {}, Environment.GIT_TIMEOUT);
        } catch (Environment.SubprocessException e) {
            String output = e.getOutput();
            if (isLfsMissing(output)) {
                // Prepare a concise preview for logs and UI (first N lines / limited chars)
                final int PREVIEW_MAX_LINES = 50;
                final int PREVIEW_MAX_CHARS = 4000;
                String outputPreview = previewOutput(output, PREVIEW_MAX_LINES, PREVIEW_MAX_CHARS);

                // Single structured WARN log to help correlate user reports with captured diagnostics.
                logger.warn(
                        "Git LFS missing during worktree add: gitVersion='{}', lfsVersion='{}', gitPath='{}', command='{}', repoTop='{}', outputPreview='{}'",
                        gitVersion == null ? "<unknown>" : gitVersion,
                        lfsVersion == null ? "<not available>" : lfsVersion,
                        gitPath == null ? "<unknown>" : gitPath,
                        command,
                        repo.getGitTopLevel(),
                        outputPreview);

                // Surface a dedicated exception so UI can show a helpful LFS-install dialog with diagnostics.
                throw new GitRepo.GitLfsMissingException(
                        command, repo.getGitTopLevel(), gitVersion, lfsVersion, gitPath, output, e);
            }
            throw new GitRepo.GitRepoException(
                    "Failed to add worktree at " + path + " for branch " + branch + ": " + output, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
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
            Environment.instance.runShellCommand(command, repo.getGitTopLevel(), out -> {}, Environment.GIT_TIMEOUT);
        } catch (Environment.SubprocessException e) {
            throw new GitRepo.GitRepoException(
                    "Failed to add detached worktree at " + path + " for commit " + commitId + ": " + e.getOutput(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitRepo.GitRepoException(
                    "Adding detached worktree at " + path + " for commit " + commitId + " was interrupted", e);
        }
    }

    /**
     * Removes the worktree at the specified path. This method will fail if the worktree is dirty or has other issues
     * preventing a clean removal, in which case a {@link GitRepo.WorktreeNeedsForceException} will be thrown.
     *
     * @param path The path to the worktree to remove.
     * @throws GitRepo.WorktreeNeedsForceException if the worktree cannot be removed without force.
     * @throws GitAPIException if a different Git error occurs.
     */
    public void removeWorktree(Path path, boolean force) throws GitAPIException {
        try {
            var absolutePath = path.toAbsolutePath().normalize();
            String command;
            if (force) {
                // Use double force as "git worktree lock" requires "remove -f -f" to override
                command = String.format("git worktree remove --force --force %s", absolutePath)
                        .trim();
            } else {
                command = String.format("git worktree remove %s", absolutePath).trim();
            }
            Environment.instance.runShellCommand(command, repo.getGitTopLevel(), out -> {}, Environment.GIT_TIMEOUT);
            SessionRegistry.release(path);
        } catch (Environment.SubprocessException e) {
            String output = e.getOutput();
            // If 'force' was false and the command failed because force is needed,
            // throw WorktreeNeedsForceException
            if (!force
                    && (output.contains("use --force")
                            || output.contains("not empty")
                            || output.contains("dirty")
                            || output.contains("locked working tree"))) {
                throw new GitRepo.WorktreeNeedsForceException(
                        "Worktree at " + path + " requires force for removal: " + output, e);
            }
            // Otherwise, throw a general GitRepoException
            String failMessage = String.format(
                    "Failed to remove worktree at %s%s: %s", path, (force ? " (with force)" : ""), output);
            throw new GitRepo.GitRepoException(failMessage, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String interruptMessage =
                    String.format("Removing worktree at %s%s was interrupted", path, (force ? " (with force)" : ""));
            throw new GitRepo.GitRepoException(interruptMessage, e);
        }
    }

    /**
     * Prunes worktree metadata for worktrees that no longer exist. This is equivalent to `git worktree prune`.
     *
     * @throws GitAPIException if a Git error occurs.
     */
    public void pruneWorktrees() throws GitAPIException {
        try {
            var command = "git worktree prune";
            Environment.instance.runShellCommand(command, repo.getGitTopLevel(), out -> {}, Environment.GIT_TIMEOUT);
        } catch (Environment.SubprocessException e) {
            throw new GitRepo.GitRepoException("Failed to prune worktrees: " + e.getOutput(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitRepo.GitRepoException("Pruning worktrees was interrupted", e);
        }
    }

    /** Returns true if this repository is a Git worktree. */
    public boolean isWorktree() {
        return Files.isRegularFile(repository.getWorkTree().toPath().resolve(".git"));
    }

    /** Returns the set of branches that are checked out in worktrees. */
    public Set<String> getBranchesInWorktrees() throws GitAPIException {
        return listWorktrees().stream()
                .map(WorktreeInfo::branch)
                .filter((branch) -> branch != null && !branch.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Determines the next available path for a new worktree in the specified storage directory. It looks for paths
     * named "wt1", "wt2", etc., and returns the first one that doesn't exist.
     *
     * @param worktreeStorageDir The directory where worktrees are stored.
     * @return A Path for the new worktree.
     * @throws IOException if an I/O error occurs when checking for directory existence.
     */
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

    public boolean supportsWorktrees() {
        try {
            // Try to run a simple git command to check if git executable is available and working
            Environment.instance.runShellCommand(
                    "git --version", repo.getProjectRoot(), output -> {}, Environment.GIT_TIMEOUT);
            return true;
        } catch (Environment.SubprocessException e) {
            // This typically means git command failed, e.g., not found or permission issue
            logger.warn(
                    "Git executable not found or 'git --version' failed, disabling worktree support: {}",
                    e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while checking for git executable, disabling worktree support", e);
            return false;
        }
    }
}
