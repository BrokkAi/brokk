package ai.brokk;

import ai.brokk.analyzer.ProjectFile;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * File watching service using io.methvin:directory-watcher library.
 * This library uses platform-native recursive watching:
 * - macOS: FSEvents (efficient, ~1 FD per watched path)
 * - Linux: inotify (optimized recursive implementation)
 * - Windows: WatchService with FILE_TREE (native recursive)
 *
 * This is the recommended implementation for most platforms, especially macOS,
 * as it drastically reduces file descriptor usage compared to LegacyProjectWatchService.
 */
public class NativeProjectWatchService implements IWatchService {
    private static final Logger logger = LogManager.getLogger(NativeProjectWatchService.class);

    // Directories to exclude from watching
    private static final Set<String> EXCLUDED_DIR_NAMES = Set.of(
            // Build directories
            "target",
            "build",
            "out",
            "dist",
            "bin",
            ".gradle",
            ".maven",
            // Dependencies
            "node_modules",
            "vendor",
            ".venv",
            "venv",
            "env",
            "__pycache__",
            "bower_components",
            // IDE
            ".idea",
            ".vscode",
            ".settings",
            // VCS (we watch .git separately)
            ".svn",
            ".hg",
            // Brokk
            ".brokk");

    private final Path root;

    @Nullable
    private final Path gitRepoRoot;

    @Nullable
    private final Path gitMetaDir;

    @Nullable
    private final Path globalGitignorePath;

    @Nullable
    private final Path globalGitignoreRealPath;

    private final List<Listener> listeners;

    @Nullable
    private volatile DirectoryWatcher watcher;

    private volatile boolean running = true;
    private volatile int pauseCount = 0;

    @Nullable
    private volatile Thread watcherThread;

    /**
     * Create a NativeProjectWatchService with multiple listeners.
     * All registered listeners will be notified of file system events.
     */
    public NativeProjectWatchService(
            Path root, @Nullable Path gitRepoRoot, @Nullable Path globalGitignorePath, List<Listener> listeners) {
        this.root = root;
        this.gitRepoRoot = gitRepoRoot;
        this.globalGitignorePath = globalGitignorePath;
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        this.gitMetaDir = (gitRepoRoot != null) ? gitRepoRoot.resolve(".git") : null;

        // Precompute real path for robust comparison (handles symlinks, case-insensitive filesystems)
        if (globalGitignorePath != null) {
            Path realPath;
            try {
                realPath = globalGitignorePath.toRealPath();
            } catch (IOException e) {
                // If file doesn't exist or can't be resolved, use original path as fallback
                realPath = globalGitignorePath;
                logger.debug("Could not resolve global gitignore to real path: {}", e.getMessage());
            }
            this.globalGitignoreRealPath = realPath;
        } else {
            this.globalGitignoreRealPath = null;
        }
    }

    @Override
    public void start(CompletableFuture<?> delayNotificationsUntilCompleted) {
        watcherThread = new Thread(() -> beginWatching(delayNotificationsUntilCompleted));
        watcherThread.setName("NativeDirectoryWatcher@" + Long.toHexString(watcherThread.threadId()));
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void beginWatching(CompletableFuture<?> delayNotificationsUntilCompleted) {
        logger.debug("Setting up native directory watcher for {}", root);
        final List<Path> paths = new ArrayList<Path>();
        paths.add(root);
        try {
            // Build the watcher with exclusions
            var builder = DirectoryWatcher.builder()
                    .listener(this::handleEvent)
                    .fileHashing(false); // We don't need file hashing

            // Also watch git metadata if present
            if (gitMetaDir != null && Files.isDirectory(gitMetaDir)) {
                logger.debug("Watching git metadata directory for changes: {}", gitMetaDir);
                paths.add(gitMetaDir);
            } else if (gitRepoRoot != null) {
                logger.debug(
                        "Git metadata directory not found at {}; skipping git metadata watch setup",
                        gitRepoRoot.resolve(".git"));
            } else {
                logger.debug("No git repository detected for {}; skipping git metadata watch setup", root);
            }

            // Watch global gitignore file directory if it exists
            if (globalGitignorePath != null && Files.exists(globalGitignorePath)) {
                Path globalGitignoreDir = globalGitignorePath.getParent();
                if (globalGitignoreDir != null && Files.isDirectory(globalGitignoreDir)) {
                    logger.debug("Watching global gitignore directory for changes: {}", globalGitignoreDir);
                    paths.add(globalGitignoreDir);
                }
            }
            builder.paths(paths);
            watcher = builder.build();

            // Wait for the initial future to complete
            try {
                delayNotificationsUntilCompleted.get();
            } catch (Exception e) {
                logger.error("Error while waiting for the initial Future to complete", e);
                return;
            }

            logger.info("Starting native directory watcher for: {}", root);
            watcher.watch(); // This blocks until watcher is closed

        } catch (IOException e) {
            logger.error("Error setting up native directory watcher", e);
        } catch (Exception e) {
            logger.error("Error starting native directory watcher", e);
        }
    }

    /**
     * Determine if a path should be included in watching.
     * Excludes common build/dependency directories to reduce file descriptor usage.
     */
    private boolean shouldInclude(Path path) {
        String fileName = path.getFileName().toString();

        // Don't filter .git here - we handle it specially in builder
        if (fileName.equals(".git")) {
            return true;
        }

        // Check if directory name is in exclusion list
        if (EXCLUDED_DIR_NAMES.contains(fileName)) {
            return false;
        }

        // Exclude if path starts with any excluded directory
        try {
            Path relativePath = root.relativize(path);
            for (String excludedName : EXCLUDED_DIR_NAMES) {
                if (relativePath.startsWith(excludedName)) {
                    return false;
                }
            }
        } catch (IllegalArgumentException e) {
            // Path is outside root, allow it (might be global gitignore)
            return true;
        }

        return true;
    }

    /**
     * Checks if a gitignore-related file change should trigger cache invalidation.
     */
    private boolean shouldInvalidateForGitignoreChange(Path eventPath) {
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
        if (globalGitignoreRealPath != null) {
            try {
                Path eventRealPath = eventPath.toRealPath();
                if (eventRealPath.equals(globalGitignoreRealPath)) {
                    logger.debug("Global gitignore file changed: {}", eventPath);
                    return true;
                }
            } catch (IOException e) {
                // If toRealPath() fails (file deleted during event), fall back to simple comparison
                if (eventPath.equals(globalGitignorePath)) {
                    logger.debug("Global gitignore file changed: {} (fallback comparison)", eventPath);
                    return true;
                }
            }
        }

        return false;
    }

    private void handleEvent(DirectoryChangeEvent event) {
        // Skip processing if paused
        if (pauseCount > 0) {
            return;
        }

        if (!running) return;

        try {
            Path changedPath = event.path();
            DirectoryChangeEvent.EventType eventType = event.eventType();

            // Filter out excluded paths
            if (!shouldInclude(changedPath)) {
                logger.trace("Skipping excluded path: {}", changedPath);
                return;
            }

            logger.trace("File event: {} on {}", eventType, changedPath);

            // Create event batch
            var batch = new EventBatch();

            // Check if this is an untracked gitignore change
            if (shouldInvalidateForGitignoreChange(changedPath)) {
                batch.untrackedGitignoreChanged = true;
            }

            // Convert to ProjectFile - handle paths outside root (e.g., global gitignore)
            try {
                Path relativePath = root.relativize(changedPath);
                batch.files.add(new ProjectFile(root, relativePath));
            } catch (IllegalArgumentException e) {
                // Path is outside root, skip it for now
                logger.trace("Skipping event for path outside root: {}", changedPath);
                return;
            }

            // Notify listeners
            if (!batch.files.isEmpty()) {
                notifyFilesChanged(batch);
            }

        } catch (Exception e) {
            logger.error("Error handling directory change event", e);
        }
    }

    private void notifyFilesChanged(EventBatch batch) {
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

    @Override
    public synchronized void pause() {
        logger.debug("Pausing native directory watcher");
        pauseCount++;
    }

    @Override
    public synchronized void resume() {
        logger.debug("Resuming native directory watcher");
        if (pauseCount > 0) {
            pauseCount--;
        }
    }

    @Override
    public synchronized boolean isPaused() {
        return pauseCount > 0;
    }

    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
        logger.debug("Added listener: {}", listener.getClass().getSimpleName());
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
        logger.debug("Removed listener: {}", listener.getClass().getSimpleName());
    }

    @Override
    public synchronized void close() {
        running = false;
        pauseCount = 0;

        if (watcher != null) {
            try {
                logger.info("Closing native directory watcher for: {}", root);
                watcher.close();
            } catch (IOException e) {
                logger.error("Error closing native directory watcher", e);
            }
        }

        if (watcherThread != null) {
            try {
                watcherThread.join(1000); // Wait up to 1 second for clean shutdown
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for watcher thread to stop");
            }
        }
    }
}
