package io.github.jbellis.brokk.git;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.SessionRegistry;
import io.github.jbellis.brokk.git.IGitRepo.WorktreeInfo;
import io.github.jbellis.brokk.util.Environment;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Helper class extracted from GitRepo to encapsulate worktree operations.
 *
 * <p>This class keeps a reference to the owning GitRepo and obtains the JGit Repository via repo.getRepository().
 */
public class GitRepoWorktrees {
    private static final Logger logger = LogManager.getLogger(GitRepoWorktrees.class);

    private final GitRepo repo;
    private final org.eclipse.jgit.lib.Repository repository;
    private final java.nio.file.Path projectRoot;

    public GitRepoWorktrees(GitRepo repo) {
        this.repo = repo;
        this.repository = repo.getRepository();
        this.projectRoot = repo.getProjectRoot();
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
        try {
            // Ensure path is absolute for the command
            var absolutePath = path.toAbsolutePath().normalize();

            // Check if branch exists locally
            List<String> localBranches = repo.listLocalBranches();
            String command;
            if (localBranches.contains(branch)) {
                // Branch exists, checkout the existing branch
                command = String.format("git worktree add %s %s", absolutePath, branch);
            } else {
                // Branch doesn't exist, create a new one
                command = String.format("git worktree add -b %s %s", branch, absolutePath);
            }
            Environment.instance.runShellCommand(command, repo.getGitTopLevel(), out -> {}, Environment.GIT_TIMEOUT);

            // Recursively copy .brokk/dependencies from the project root into the new worktree
            var sourceDependenciesDir = projectRoot.resolve(".brokk").resolve("dependencies");
            if (!Files.exists(sourceDependenciesDir)) {
                return;
            }

            // Ensure .brokk exists in the new worktree
            var targetDependenciesDir = absolutePath.resolve(".brokk").resolve("dependencies");
            Files.createDirectories(targetDependenciesDir.getParent());

            // copy
            Files.walkFileTree(
                    sourceDependenciesDir, new CopyingFileVisitor(sourceDependenciesDir, targetDependenciesDir));
        } catch (Environment.SubprocessException e) {
            throw new GitRepo.GitRepoException(
                    "Failed to add worktree at " + path + " for branch " + branch + ": " + e.getOutput(), e);
        } catch (IOException e) {
            throw new GitRepo.GitRepoException("Failed to copy dependencies", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitRepo.GitRepoException(
                    "Adding worktree at " + path + " for branch " + branch + " was interrupted", e);
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
                    "git --version", repo.getGitTopLevel(), output -> {}, Environment.GIT_TIMEOUT);
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

    // -------------------- Internal helpers --------------------
    private static class CopyingFileVisitor extends SimpleFileVisitor<Path> {
        private final Path sourceRoot;
        private final Path targetRoot;

        CopyingFileVisitor(Path sourceRoot, Path targetRoot) {
            this.sourceRoot = sourceRoot;
            this.targetRoot = targetRoot;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            var relative = sourceRoot.relativize(dir);
            var targetDir = targetRoot.resolve(relative);
            Files.createDirectories(targetDir);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            var relative = sourceRoot.relativize(file);
            var targetFile = targetRoot.resolve(relative);
            Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return FileVisitResult.CONTINUE;
        }
    }
}
