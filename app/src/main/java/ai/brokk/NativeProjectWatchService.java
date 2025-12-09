package ai.brokk;

import ai.brokk.analyzer.ProjectFile;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
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
    private static final long DEBOUNCE_DELAY_MS = 500;

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

    // Debouncing fields
    private final ScheduledExecutorService debounceExecutor;
    private final Object debounceLock = new Object();
    private EventBatch accumulatedBatch = new EventBatch();

    @Nullable
    private ScheduledFuture<?> pendingFlush;

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

        // Initialize debounce executor
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("NativeWatcherDebouncer@" + Long.toHexString(t.threadId()));
            t.setDaemon(true);
            return t;
        });

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
            var builder = DirectoryWatcher.builder().listener(this::handleEvent).fileHashing(true);

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

            // Start the DirectoryWatcher in an inner thread so we can schedule a warmup
            // notification after the watcher has been started (reduces races in tests).
            final CountDownLatch watcherStarted = new CountDownLatch(1);
            Thread innerWatcher = new Thread(() -> {
                // Signal that the watcher thread is about to start watching.
                watcherStarted.countDown();
                try {
                    watcher.watch();
                } catch (Exception e) {
                    logger.error("Error in directory watcher loop", e);
                }
            });
            innerWatcher.setName("NativeDirectoryWatcherLoop@" + Long.toHexString(innerWatcher.threadId()));
            innerWatcher.setDaemon(true);
            innerWatcher.start();

            // Wait briefly for the inner watcher thread to reach the watching point. If interrupted,
            // proceed anyway since we will still attempt to schedule the warmup.
            try {
                watcherStarted.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Schedule a short delayed warmup notification so listeners know the watcher is active.
            // This runs on the debounce executor and will not interfere with the normal debounce logic.
            debounceExecutor.schedule(() -> {
                try {
                    notifyFilesChanged(new EventBatch());
                } catch (Exception e) {
                    logger.debug("Error delivering warmup notification: {}", e.getMessage());
                }
            }, 200, TimeUnit.MILLISECONDS);

            // Block this thread until the inner watcher thread completes so shutdown semantics remain the same.
            try {
                innerWatcher.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (IOException e) {
            logger.error("Error setting up native directory watcher", e);
        } catch (Exception e) {
            logger.error("Error starting native directory watcher", e);
        }
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
        // If we're not running, ignore events.
        if (!running) {
            return;
        }

        try {
            Path changedPath = event.path();
            DirectoryChangeEvent.EventType eventType = event.eventType();

            logger.trace("File event: {} on {}", eventType, changedPath);

            synchronized (debounceLock) {
                // Always record gitignore-related changes and accumulate file events.
                // When the watcher is paused we intentionally continue buffering events
                // in `accumulatedBatch` but we DO NOT schedule the debounced flush until
                // we are resumed. This preserves events that occur during pauses.
                if (shouldInvalidateForGitignoreChange(changedPath)) {
                    accumulatedBatch.untrackedGitignoreChanged = true;
                }

                // Convert to ProjectFile - handle paths outside root (e.g., global gitignore)
                try {
                    Path relativePath = root.relativize(changedPath);
                    accumulatedBatch.files.add(new ProjectFile(root, relativePath));
                } catch (IllegalArgumentException e) {
                    // Path is outside root, skip it for now
                    logger.trace("Skipping event for path outside root: {}", changedPath);
                    return;
                }

                // If paused, do not schedule a debounce flush; the events remain buffered.
                if (pauseCount > 0) {
                    logger.trace("Watcher is paused; buffering event for {}", changedPath);
                    return;
                }

                // Cancel existing flush and schedule new one
                if (pendingFlush != null) {
                    pendingFlush.cancel(false);
                }
                pendingFlush = debounceExecutor.schedule(
                        this::flushAccumulatedEvents, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
            }

        } catch (Exception e) {
            logger.error("Error handling directory change event", e);
        }
    }

    /**
     * Flushes the accumulated events to listeners.
     * Called by the debounce executor after DEBOUNCE_DELAY_MS of inactivity.
     */
    private void flushAccumulatedEvents() {
        EventBatch batchToNotify;

        // First, check paused state BEFORE swapping batches. Call isPaused() outside of debounceLock
        // to avoid deadlock with pause(), which synchronizes on 'this' then on debounceLock.
        if (isPaused()) {
            // Ensure pendingFlush is cleared while holding debounceLock to avoid races.
            synchronized (debounceLock) {
                pendingFlush = null;
            }
            logger.trace("Flush suppressed because watcher is paused; keeping events buffered");
            return;
        }

        synchronized (debounceLock) {
            // If there's nothing to notify, return early
            if (accumulatedBatch.files.isEmpty() && !accumulatedBatch.untrackedGitignoreChanged) {
                return;
            }

            // Swap out the batch and reset
            batchToNotify = accumulatedBatch;
            accumulatedBatch = new EventBatch();
            pendingFlush = null;
        }

        logger.debug("Flushing {} accumulated file events", batchToNotify.files.size());
        notifyFilesChanged(batchToNotify);
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

        // While paused, prevent any scheduled flush from executing.
        // Acquire the debounceLock to synchronize with flush scheduling/cancellation.
        synchronized (debounceLock) {
            if (pendingFlush != null) {
                // Cancel the scheduled flush but do NOT clear accumulatedBatch — events must be preserved
                // so they can be flushed when resume() brings pauseCount back to zero.
                pendingFlush.cancel(false);
                pendingFlush = null;
                logger.trace("Canceled pending flush due to pause; events will remain buffered");
            }
        }
    }

    @Override
    public void resume() {
        logger.debug("Resuming native directory watcher");

        boolean shouldFlush = false;
        // Protect mutation of pauseCount with a synchronized block on 'this' (consistent with pause()).
        synchronized (this) {
            if (pauseCount > 0) {
                pauseCount--;
                if (pauseCount == 0) {
                    shouldFlush = true;
                }
            }
        }

        if (shouldFlush) {
            // Acquire debounceLock to coordinate with handleEvent/pendingFlush management.
            // Cancel any existing scheduled flush and schedule an immediate (0ms) flush on the debounce executor.
            // Storing the ScheduledFuture in pendingFlush prevents duplicate scheduling races.
            synchronized (debounceLock) {
                if (pendingFlush != null) {
                    pendingFlush.cancel(false);
                    pendingFlush = null;
                }
                // Schedule a zero-delay flush so the work runs on the debounce executor thread.
                pendingFlush = debounceExecutor.schedule(this::flushAccumulatedEvents, 0, TimeUnit.MILLISECONDS);
            }
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

        // Cancel any pending flush to avoid it running while we are shutting down.
        synchronized (debounceLock) {
            if (pendingFlush != null) {
                pendingFlush.cancel(false);
                pendingFlush = null;
            }
        }

        // Shutdown debounce executor
        debounceExecutor.shutdown();
        try {
            if (!debounceExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                debounceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            debounceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

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
