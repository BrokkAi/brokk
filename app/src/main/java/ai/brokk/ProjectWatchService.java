package ai.brokk;

import ai.brokk.analyzer.ProjectFile;
import java.awt.KeyboardFocusManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class ProjectWatchService implements IWatchService {

    private final Logger logger = LogManager.getLogger(ProjectWatchService.class);

    private static final long DEBOUNCE_DELAY_MS = 500;
    private static final long POLL_TIMEOUT_FOCUSED_MS = 100;
    private static final long POLL_TIMEOUT_UNFOCUSED_MS = 1000;

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

    private volatile boolean running = true;
    private volatile int pauseCount = 0;

    /**
     * Create a ProjectWatchService with multiple listeners.
     * All registered listeners will be notified of file system events.
     */
    public ProjectWatchService(
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
        Thread watcherThread = new Thread(
                () -> beginWatching(delayNotificationsUntilCompleted),
                "DirectoryWatcher@" + Long.toHexString(Thread.currentThread().threadId()));
        watcherThread.start();
    }

    private void beginWatching(CompletableFuture<?> delayNotificationsUntilCompleted) {
        logger.debug("Setting up WatchService for {}", root);
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            // Recursively register all directories under project root except .brokk and .git
            registerAllDirectories(root, watchService);

            // Always watch git metadata to ensure ref changes (HEAD, refs/heads/*) trigger onRepoChange
            if (gitMetaDir != null && Files.isDirectory(gitMetaDir)) {
                logger.debug("Watching git metadata directory for changes: {}", gitMetaDir);
                registerGitMetadata(gitMetaDir, watchService);
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
                    try {
                        globalGitignoreDir.register(
                                watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY);
                    } catch (IOException e) {
                        logger.warn(
                                "Failed to watch global gitignore directory {}: {}",
                                globalGitignoreDir,
                                e.getMessage());
                    }
                }
            }

            // Wait for the initial future to complete.
            // The WatchService will queue any events that arrive during this time.
            try {
                delayNotificationsUntilCompleted.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.debug("Error while waiting for the initial Future to complete", e);
                throw new RuntimeException(e);
            }

            // Watch for events, debounce them, and handle them
            while (running) {
                // Wait if paused
                while (pauseCount > 0) {
                    Thread.onSpinWait();
                }

                // Choose a short or long poll depending on focus
                long pollTimeout = isApplicationFocused() ? POLL_TIMEOUT_FOCUSED_MS : POLL_TIMEOUT_UNFOCUSED_MS;
                WatchKey key = watchService.poll(pollTimeout, TimeUnit.MILLISECONDS);

                // No event arrived within the poll window
                if (key == null) {
                    notifyNoFilesChanged();
                    continue;
                }

                // We got an event, collect it and any others within the debounce window
                var batch = new EventBatch();
                collectEventsFromKey(key, watchService, batch);

                long deadline = System.currentTimeMillis() + DEBOUNCE_DELAY_MS;
                while (true) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    WatchKey nextKey = watchService.poll(remaining, TimeUnit.MILLISECONDS);
                    if (nextKey == null) break;
                    collectEventsFromKey(nextKey, watchService, batch);
                }

                // Process the batch
                notifyFilesChanged(batch);
            }
        } catch (IOException e) {
            logger.error("Error setting up watch service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("FileWatchService thread interrupted; shutting down");
        }
    }

    /**
     * Checks if a gitignore-related file change should trigger cache invalidation.
     * This targets untracked .gitignore files, .git/info/exclude, and global gitignore files
     * which are not covered by git metadata watching.
     */
    private boolean shouldInvalidateForGitignoreChange(Path eventPath) {
        var fileName = eventPath.getFileName().toString();

        // .git/info/exclude is never tracked by git
        // Use path traversal instead of string matching for cross-platform compatibility
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

        // For .gitignore files, only trigger if they're likely untracked
        // (tracked .gitignore changes are handled by git metadata watching)
        if (fileName.equals(".gitignore")) {
            // We can't easily determine if it's tracked here, so we'll be conservative
            // and invalidate. The performance impact is minimal since this is rare.
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

    private void collectEventsFromKey(WatchKey key, WatchService watchService, EventBatch batch) {
        Path watchPath = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                batch.isOverflowed = true;
                continue;
            }

            // Guard: context might be null (OVERFLOW) or not a Path
            if (!(event.context() instanceof Path ctx)) {
                logger.warn("Event is not overflow but has no path: {}", event);
                continue;
            }

            // convert to ProjectFile
            Path eventPath = watchPath.resolve(ctx);

            // Check if this is an untracked gitignore change that should invalidate cache
            if (shouldInvalidateForGitignoreChange(eventPath)) {
                batch.untrackedGitignoreChanged = true;
            }

            Path relativized;
            try {
                relativized = root.relativize(eventPath);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Failed to relativize path: %s to %s".formatted(eventPath, root), e);
            }
            batch.files.add(new ProjectFile(root, relativized));

            // If it's a directory creation, register it so we can watch its children
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(eventPath)) {
                try {
                    if (gitMetaDir != null && eventPath.startsWith(gitMetaDir)) {
                        // Do not exclude .git if the created directory is under git metadata
                        registerGitMetadata(eventPath, watchService);
                    } else {
                        registerAllDirectories(eventPath, watchService);
                    }
                } catch (IOException ex) {
                    logger.warn("Failed to register new directory for watching: {}", eventPath, ex);
                }
            }
        }

        // If the key is no longer valid, we can't watch this path anymore
        if (!key.reset()) {
            logger.warn("Watch key no longer valid: {}", key.watchable());
        }
    }

    /**
     * @param start can be either the root project directory, or a newly created directory we want to add to the watch
     */
    private void registerAllDirectories(Path start, WatchService watchService) throws IOException {
        if (!Files.isDirectory(start)) return;

        // TODO: Parse VSC ignore files to add to this list
        var ignoredDirs = List.of(root.resolve(".brokk"), root.resolve(".git"));
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (var walker = Files.walk(start)) {
                walker.filter(Files::isDirectory)
                        .filter(dir -> ignoredDirs.stream().noneMatch(dir::startsWith))
                        .forEach(dir -> {
                            try {
                                dir.register(
                                        watchService,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                        StandardWatchEventKinds.ENTRY_DELETE,
                                        StandardWatchEventKinds.ENTRY_MODIFY);
                            } catch (IOException e) {
                                logger.warn("Failed to register directory for watching: {}", dir, e);
                            }
                        });
                // Success: If the walk completes without exception, break the retry loop.
                return;
            } catch (IOException | UncheckedIOException e) {
                // Determine the root cause, handling the case where the UncheckedIOException wraps another exception.
                Throwable cause = (e instanceof UncheckedIOException uioe) ? uioe.getCause() : e;

                // Retry only if it's a NoSuchFileException and we have attempts left.
                if (cause instanceof NoSuchFileException && attempt < 3) {
                    logger.warn(
                            "Attempt {} failed to walk directory {} due to NoSuchFileException. Retrying in 10ms...",
                            attempt,
                            start,
                            cause);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(e);
                    }
                }
            }
        } // End of retry loop
        logger.debug("Failed to (completely) register directory `{}` for watching", start);
    }

    /**
     * Recursively register the git metadata directory and its subdirectories without excluding ".git". This ensures we
     * observe ref changes like updates to HEAD and refs/heads/* files.
     */
    private void registerGitMetadata(Path start, WatchService watchService) throws IOException {
        if (!Files.isDirectory(start)) return;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (var walker = Files.walk(start)) {
                walker.filter(Files::isDirectory).forEach(dir -> {
                    try {
                        dir.register(
                                watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY);
                    } catch (IOException e) {
                        logger.warn("Failed to register git metadata directory for watching: {}", dir, e);
                    }
                });
                return;
            } catch (IOException | UncheckedIOException e) {
                Throwable cause = (e instanceof UncheckedIOException uioe) ? uioe.getCause() : e;
                if (cause instanceof NoSuchFileException && attempt < 3) {
                    logger.warn(
                            "Attempt {} failed to walk git metadata directory {} due to NoSuchFileException. Retrying in 10ms...",
                            attempt,
                            start,
                            cause);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        logger.debug("Failed to (completely) register git metadata directory `{}` for watching", start);
    }

    /** Pause the file watching service. */
    @Override
    public synchronized void pause() {
        logger.debug("Pausing file watcher");
        pauseCount++;
    }

    /** Resume the file watching service. */
    @Override
    public synchronized void resume() {
        logger.debug("Resuming file watcher");
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
        pauseCount = 0; // Ensure any waiting thread is woken up to exit
    }

    /**
     * Checks if any window in the application currently has focus
     *
     * @return true if any application window has focus, false otherwise
     */
    private boolean isApplicationFocused() {
        var focusedWindow =
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        return focusedWindow != null;
    }

    /**
     * Notify all registered listeners that files have changed.
     */
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

    /**
     * Notify all registered listeners that no files changed during the poll interval.
     */
    private void notifyNoFilesChanged() {
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
}
