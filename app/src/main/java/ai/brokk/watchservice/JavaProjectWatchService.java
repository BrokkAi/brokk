package ai.brokk.watchservice;

import ai.brokk.analyzer.ProjectFile;
import java.awt.KeyboardFocusManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * File watching implementation using Java's WatchService API.
 *
 * This implementation requires registering every directory individually, which can
 * lead to high file descriptor usage on large projects (especially with node_modules,
 * target, build directories, etc.).
 *
 * For new code, consider using NativeProjectWatchService which uses platform-native
 * recursive watching APIs (FSEvents on macOS, optimized inotify on Linux).
 */
public class JavaProjectWatchService extends AbstractWatchService {

    private static final long DEBOUNCE_DELAY_MS = 500;
    private static final long POLL_TIMEOUT_FOCUSED_MS = 100;
    private static final long POLL_TIMEOUT_UNFOCUSED_MS = 1000;

    private volatile boolean running = true;
    private volatile int pauseCount = 0;
    @Nullable private volatile Thread watcherThread;

    /**
     * Create a LegacyProjectWatchService with multiple listeners.
     * All registered listeners will be notified of file system events.
     */
    public JavaProjectWatchService(
            Path root, @Nullable Path gitRepoRoot, @Nullable Path globalGitignorePath, List<Listener> listeners) {
        super(root, gitRepoRoot, globalGitignorePath, listeners);
    }

    @Override
    public void start(CompletableFuture<?> delayNotificationsUntilCompleted) {
        var t = new Thread(
                () -> watch(delayNotificationsUntilCompleted),
                "DirectoryWatcher@" + Long.toHexString(Thread.currentThread().threadId()));
        this.watcherThread = t;
        t.start();
    }

    private void watch(CompletableFuture<?> delayNotificationsUntilCompleted) {
        logger.debug("Setting up WatchService for {}", root);
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            registerWatchTargets(watchService);

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
                waitWhilePaused();

                WatchKey key = pollForEvents(watchService);

                // No event arrived within the poll window
                if (key == null) {
                    notifyNoFilesChanged();
                    continue;
                }

                // We got an event, collect it and any others within the debounce window
                EventBatch batch = collectDebouncedBatch(key, watchService);

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

    private void registerWatchTargets(WatchService watchService) throws IOException {
        // Recursively register all directories under project root except .brokk and .git
        registerAllDirectories(root, watchService);

        // Always watch git metadata to ensure ref changes (HEAD, refs/heads/*) trigger onRepoChange.
        // Note: For worktrees, gitMetaDir may be external. If on different filesystem or
        // becomes inaccessible, events may stop - this is an inherent limitation of file watching.
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
                            "Failed to watch global gitignore directory {}: {}", globalGitignoreDir, e.getMessage());
                }
            }
        }
    }

    private synchronized void waitWhilePaused() throws InterruptedException {
        while (pauseCount > 0 && running) {
            wait();
        }
    }

    private @Nullable WatchKey pollForEvents(WatchService watchService) throws InterruptedException {
        // Choose a short or long poll depending on focus
        long pollTimeout = isApplicationFocused() ? POLL_TIMEOUT_FOCUSED_MS : POLL_TIMEOUT_UNFOCUSED_MS;
        return watchService.poll(pollTimeout, TimeUnit.MILLISECONDS);
    }

    private EventBatch collectDebouncedBatch(WatchKey initialKey, WatchService watchService)
            throws InterruptedException {
        var batch = new EventBatch();
        collectEventsFromKey(initialKey, watchService, batch);

        long deadline = System.currentTimeMillis() + DEBOUNCE_DELAY_MS;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            WatchKey nextKey = watchService.poll(remaining, TimeUnit.MILLISECONDS);
            if (nextKey == null) break;
            collectEventsFromKey(nextKey, watchService, batch);
        }
        return batch;
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

            if (isUnrelatedEventFromGitignoreParentDirectory(eventPath)) {
                logger.trace("Skipping non-gitignore event from global gitignore directory: {}", eventPath);
                continue;
            }

            // Check if this is an untracked gitignore change that should invalidate cache
            if (shouldInvalidateForGitignoreChange(eventPath)) {
                batch.untrackedGitignoreChanged = true;
            }

            // Convert to ProjectFile - handle paths outside root (e.g., git metadata in worktrees)
            Path relativized;
            Path baseForFile;
            if (gitMetaDir != null && gitRepoRoot != null && eventPath.startsWith(gitMetaDir)) {
                // Git metadata event from external location (e.g., worktree pointing to main repo's .git).
                // INVARIANT: FileWatcherHelper.isGitMetadataChanged() requires relative paths to start
                // with ".git/" prefix, so we reconstruct the path as .git/<relative-to-gitMetaDir>
                relativized = Path.of(".git").resolve(gitMetaDir.relativize(eventPath));
                baseForFile = gitRepoRoot;
                logger.trace("Git metadata event (external): {} -> relative: {}", eventPath, relativized);
            } else {
                try {
                    relativized = root.relativize(eventPath);
                    baseForFile = root;
                } catch (IllegalArgumentException e) {
                    // Path is outside both root and gitMetaDir, skip it
                    logger.trace("Skipping event for path outside root and git: {}", eventPath);
                    continue;
                }
            }
            batch.getFiles().add(new ProjectFile(baseForFile, relativized));

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
            if (pauseCount == 0) {
                notifyAll();
            }
        }
    }

    @Override
    public synchronized boolean isPaused() {
        return pauseCount > 0;
    }

    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            running = false;
            pauseCount = 0; // Ensure any waiting thread is woken up to exit
            notifyAll();
            t = watcherThread;
        }
        // Interrupt and join outside the synchronized block to avoid holding the monitor while waiting.
        // The interrupt wakes the thread from WatchService.poll() so it exits promptly.
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
}
