package ai.brokk.watchservice;

import static java.util.Objects.requireNonNull;

import ai.brokk.analyzer.ProjectFile;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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
public class NativeProjectWatchService extends AbstractWatchService {
    private static final long DEBOUNCE_DELAY_MS = 500;

    @Nullable
    private volatile DirectoryWatcher watcher;

    private volatile boolean running = true;

    @Nullable
    private volatile Thread watcherThread;

    // Debouncing fields - all guarded by 'lock'
    private final ScheduledExecutorService debounceExecutor;
    private final ReentrantLock lock = new ReentrantLock();
    private int pauseCount = 0;
    private EventBatch accumulatedBatch = new EventBatch();

    @Nullable
    private ScheduledFuture<?> pendingFlush;

    /**
     * Create a NativeProjectWatchService with multiple listeners.
     * All registered listeners will be notified of file system events.
     */
    public NativeProjectWatchService(
            Path root, @Nullable Path gitRepoRoot, @Nullable Path globalGitignorePath, List<Listener> listeners) {
        super(root, gitRepoRoot, globalGitignorePath, listeners);

        // Initialize debounce executor
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("NativeWatcherDebouncer@" + Long.toHexString(t.threadId()));
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start(CompletableFuture<?> delayNotificationsUntilCompleted) {
        watcherThread = new Thread(() -> watch(delayNotificationsUntilCompleted));
        watcherThread.setName("NativeDirectoryWatcher@" + Long.toHexString(watcherThread.threadId()));
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void watch(CompletableFuture<?> delayNotificationsUntilCompleted) {
        logger.debug("Setting up native directory watcher for {}", root);
        final List<Path> paths = new ArrayList<Path>();
        paths.add(root);
        try {
            // Build the watcher with exclusions
            var builder = DirectoryWatcher.builder().listener(this::handleEvent).fileHashing(true);

            // Also watch git metadata if present (may be external for worktrees).
            // Note: If external directory is on different filesystem or becomes inaccessible,
            // events may stop - this is an inherent limitation of file watching.
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
            } catch (InterruptedException e) {
                logger.info("Watcher setup interrupted while waiting for initialization to complete");
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }

            // Start watching (blocks until watcher is closed)
            logger.info("Starting native directory watcher for: {}", root);
            requireNonNull(watcher).watch();
        } catch (ClosedWatchServiceException e) {
            // ClosedWatchServiceException during watch() is expected if close() was called concurrently.
            // This can happen during registerPaths() or runEventLoop() when the underlying WatchService
            // is closed by another thread calling NativeProjectWatchService.close().
            if (!running) {
                logger.debug("Native directory watcher closed during startup/watching (normal shutdown)");
            } else {
                logger.error("Unexpected ClosedWatchServiceException while watcher is still running", e);
            }
        } catch (IOException e) {
            logger.error("Error setting up native directory watcher", e);
        }
    }

    private void handleEvent(DirectoryChangeEvent event) {
        // If we're not running, ignore events.
        if (!running) {
            return;
        }

        Path changedPath = event.path();
        DirectoryChangeEvent.EventType eventType = event.eventType();

        logger.trace("File event: {} on {}", eventType, changedPath);

        lock.lock();
        try {
            // Always buffer events under lock
            if (shouldInvalidateForGitignoreChange(changedPath)) {
                accumulatedBatch.untrackedGitignoreChanged = true;
            }

            if (isUnrelatedEventFromGitignoreParentDirectory(changedPath)) {
                logger.trace("Skipping non-gitignore event from global gitignore directory: {}", changedPath);
                return;
            }

            // Convert to ProjectFile - handle paths outside root (e.g., git metadata in worktrees)
            Path relativePath;
            Path baseForFile;
            if (gitMetaDir != null && gitRepoRoot != null && changedPath.startsWith(gitMetaDir)) {
                // Git metadata event from external location (e.g., worktree pointing to main repo's .git).
                // INVARIANT: FileWatcherHelper.isGitMetadataChanged() requires relative paths to start
                // with ".git/" prefix, so we reconstruct the path as .git/<relative-to-gitMetaDir>
                relativePath = Path.of(".git").resolve(gitMetaDir.relativize(changedPath));
                baseForFile = gitRepoRoot;
                logger.trace("Git metadata event (external): {} -> relative: {}", changedPath, relativePath);
            } else {
                try {
                    relativePath = root.relativize(changedPath);
                    baseForFile = root;
                } catch (IllegalArgumentException e) {
                    // Path is outside both root and gitMetaDir, skip it
                    logger.trace("Skipping event for path outside root and git: {}", changedPath);
                    return;
                }
            }
            accumulatedBatch.getFiles().add(new ProjectFile(baseForFile, relativePath));

            // Conditionally schedule flush only if not paused
            if (pauseCount == 0) {
                // Cancel existing flush and schedule new one
                if (pendingFlush != null) {
                    pendingFlush.cancel(false);
                }
                pendingFlush = debounceExecutor.schedule(
                        this::flushAccumulatedEvents, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
            } else {
                logger.trace("Watcher is paused; buffering event for {}", changedPath);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Flushes the accumulated events to listeners.
     * Called by the debounce executor after DEBOUNCE_DELAY_MS of inactivity.
     */
    private void flushAccumulatedEvents() {
        lock.lock();
        try {
            // If paused, don't flush
            if (pauseCount > 0) {
                pendingFlush = null;
                logger.trace("Flush suppressed because watcher is paused; keeping events buffered");
                return;
            }

            // If there's nothing to notify, return early
            if (accumulatedBatch.getFiles().isEmpty() && !accumulatedBatch.isUntrackedGitignoreChanged()) {
                pendingFlush = null;
                return;
            }

            // Swap out the batch and reset
            EventBatch batchToNotify = accumulatedBatch;
            accumulatedBatch = new EventBatch();
            pendingFlush = null;

            // Notify listeners while holding the lock to guarantee pause() semantics
            logger.debug(
                    "Flushing {} accumulated file events",
                    batchToNotify.getFiles().size());
            notifyFilesChanged(batchToNotify);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void pause() {
        logger.debug("Pausing native directory watcher");

        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Cannot call pause() from within a listener callback");
        }

        lock.lock();
        try {
            pauseCount++;

            // Cancel any pending flush
            if (pendingFlush != null) {
                pendingFlush.cancel(false);
                pendingFlush = null;
                logger.trace("Canceled pending flush due to pause; events will remain buffered");
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void resume() {
        logger.debug("Resuming native directory watcher");

        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Cannot call resume() from within a listener callback");
        }

        lock.lock();
        try {
            if (pauseCount > 0) {
                pauseCount--;
                // Atomically check if we should trigger flush
                if (pauseCount == 0) {
                    // Cancel any existing scheduled flush
                    if (pendingFlush != null) {
                        pendingFlush.cancel(false);
                    }
                    // Schedule immediate flush on the debounce executor thread
                    pendingFlush = debounceExecutor.schedule(this::flushAccumulatedEvents, 0, TimeUnit.MILLISECONDS);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isPaused() {
        lock.lock();
        try {
            return pauseCount > 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        running = false;

        // Cancel any pending flush to avoid it running while we are shutting down.
        lock.lock();
        try {
            pauseCount = 0;
            if (pendingFlush != null) {
                pendingFlush.cancel(false);
                pendingFlush = null;
            }
        } finally {
            lock.unlock();
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

        // closing watcher signals thread to exit
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
