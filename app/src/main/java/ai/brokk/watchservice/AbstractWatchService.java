package ai.brokk.watchservice;

import static java.util.Objects.requireNonNull;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.RealPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractWatchService implements AutoCloseable {
    protected final Logger logger = LogManager.getLogger(getClass());

    protected final Path root;

    @Nullable
    protected final Path gitRepoRoot;

    @Nullable
    protected final Path gitMetaDir;

    @Nullable
    protected final Path globalGitignorePath;

    @Nullable
    protected final Path globalGitignoreRealPath;

    @Nullable
    protected final Path globalGitignoreDirRealPath;

    protected final List<Listener> listeners;

    protected AbstractWatchService(
            Path root, @Nullable Path gitRepoRoot, @Nullable Path globalGitignorePath, List<Listener> listeners) {
        this.root = root;
        this.gitRepoRoot = gitRepoRoot;
        this.globalGitignorePath = globalGitignorePath;
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        this.gitMetaDir = resolveGitMetaDir(gitRepoRoot);

        // Precompute real paths for robust comparison (handles symlinks, case-insensitive filesystems)
        if (globalGitignorePath != null) {
            this.globalGitignoreRealPath = RealPaths.toRealPath(globalGitignorePath);
            Path parent = globalGitignorePath.getParent();
            this.globalGitignoreDirRealPath = (parent != null) ? RealPaths.toRealPath(parent) : null;
        } else {
            this.globalGitignoreRealPath = null;
            this.globalGitignoreDirRealPath = null;
        }
    }

    /**
     * Resolves the actual git metadata directory, handling worktrees where .git is a file.
     * In worktrees, .git is a file containing "gitdir: /path/to/main/.git/worktrees/xxx".
     *
     * @param gitRepoRoot The git repository root (may be null)
     * @return The resolved git metadata directory path, or gitRepoRoot/.git for regular repos
     */
    static @Nullable Path resolveGitMetaDir(@Nullable Path gitRepoRoot) {
        if (gitRepoRoot == null) {
            return null;
        }
        var gitPath = gitRepoRoot.resolve(".git");
        if (Files.isRegularFile(gitPath)) {
            // Worktree case: .git is a file pointing to the actual git directory
            try {
                var content = Files.readString(gitPath, StandardCharsets.UTF_8).trim();
                if (content.startsWith("gitdir: ")) {
                    var gitDirPath = content.substring("gitdir: ".length());
                    // Resolve against .git file's parent to handle both absolute and relative paths,
                    // then resolve symlinks for consistent path matching during event handling
                    // gitPath is gitRepoRoot.resolve(".git"), so parent is always gitRepoRoot
                    var resolved =
                            RealPaths.toRealPath(requireNonNull(gitPath.getParent()).resolve(gitDirPath));
                    LogManager.getLogger(AbstractWatchService.class)
                            .debug("Resolved worktree git metadata directory: {} -> {}", gitPath, resolved);
                    return resolved;
                }
            } catch (IOException e) {
                LogManager.getLogger(AbstractWatchService.class)
                        .warn("Failed to read .git file for worktree resolution: {}", e.getMessage());
            }
        }
        return gitPath;
    }

    public abstract void start(CompletableFuture<?> delayNotificationsUntilCompleted);

    public abstract void pause();

    public abstract void resume();

    public abstract boolean isPaused();

    /**
     * Dynamically add a listener to receive file system events.
     * @param listener The listener to add
     */
    public void addListener(Listener listener) {
        listeners.add(listener);
        logger.debug("Added listener: {}", listener.getClass().getSimpleName());
    }

    /**
     * Remove a previously added listener.
     * @param listener The listener to remove
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
        logger.debug("Removed listener: {}", listener.getClass().getSimpleName());
    }

    @Override
    public abstract void close();

    /**
     * Checks if an event path originates from the global gitignore's parent directory
     * but is not the gitignore file itself and is not under the project root.
     * This is used to filter out noise when watching a broad directory (like the user home)
     * just to track the global gitignore file.
     *
     * @return true if this event should be skipped (unrelated file in gitignore's parent dir),
     *         false if this event should be processed (either the gitignore itself, under project root,
     *         or not in the gitignore's parent directory)
     */
    protected boolean isUnrelatedEventFromGitignoreParentDirectory(Path eventPath) {
        if (globalGitignoreDirRealPath == null) {
            return false;
        }

        Path eventParent = eventPath.getParent();
        if (eventParent == null) {
            return false;
        }

        // Events under project root are always relevant
        if (eventPath.startsWith(root)) {
            return false;
        }

        // Check if this event is in the gitignore's parent directory
        if (!RealPaths.equals(eventParent, globalGitignoreDirRealPath)) {
            return false;
        }

        // It's in the gitignore directory - only the gitignore file itself is relevant
        if (globalGitignoreRealPath != null && RealPaths.equals(eventPath, globalGitignoreRealPath)) {
            return false;
        }

        return true;
    }

    /**
     * Checks if a gitignore-related file change should trigger cache invalidation.
     */
    protected boolean shouldInvalidateForGitignoreChange(Path eventPath) {
        var fileName = eventPath.getFileName().toString();

        // .git/info/exclude is never tracked by git
        if (fileName.equals("exclude")) {
            var parent = eventPath.getParent();
            if (parent != null && parent.getFileName().toString().equals("info")) {
                var grandParent = parent.getParent();
                if (grandParent != null && grandParent.getFileName().toString().equals(".git")) {
                    logger.debug("Git info exclude file changed: {}", eventPath);
                    return true;
                }
            }
        }

        // For .gitignore files
        if (fileName.equals(".gitignore")) {
            logger.debug("Gitignore file changed: {}", eventPath);
            return true;
        }

        // Check if this is the global gitignore file
        if (globalGitignoreRealPath != null && RealPaths.equals(eventPath, globalGitignoreRealPath)) {
            logger.debug("Global gitignore file changed: {}", eventPath);
            return true;
        }

        return false;
    }

    protected void notifyFilesChanged(EventBatch batch) {
        for (Listener listener : listeners) {
            try {
                listener.onFilesChanged(batch);
            } catch (Exception e) {
                logger.error(
                        "Error notifying listener {} of file changes",
                        listener.getClass().getSimpleName(),
                        e);
            }
        }
    }

    protected void notifyNoFilesChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onNoFilesChangedDuringPollInterval();
            } catch (Exception e) {
                logger.error(
                        "Error notifying listener {} of no file changes",
                        listener.getClass().getSimpleName(),
                        e);
            }
        }
    }

    public interface Listener {
        void onFilesChanged(EventBatch batch);

        default void onNoFilesChangedDuringPollInterval() {
            // Default no-op
        }
    }

    /** fields are mutable since we will collect events until they stop arriving */
    public static class EventBatch {
        boolean isOverflowed;
        boolean untrackedGitignoreChanged;
        final Set<ProjectFile> files = new HashSet<>();

        public boolean isOverflowed() {
            return isOverflowed;
        }

        public boolean isUntrackedGitignoreChanged() {
            return untrackedGitignoreChanged;
        }

        public Set<ProjectFile> getFiles() {
            return files;
        }
    }
}
