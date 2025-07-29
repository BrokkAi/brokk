package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.awt.KeyboardFocusManager;
import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class ProjectWatchService implements AutoCloseable {
    public interface Listener {
        void onFilesChanged(EventBatch batch);
        void onNoFilesChangedDuringPollInterval();
    }

    private final Logger logger = LogManager.getLogger(ProjectWatchService.class);

    private static final long DEBOUNCE_DELAY_MS = 500;
    private static final long POLL_TIMEOUT_FOCUSED_MS = 100;
    private static final long POLL_TIMEOUT_UNFOCUSED_MS = 1000;

    private final Path root;
    @Nullable
    private final Path gitRepoRoot;
    private final Listener listener;

    private volatile boolean running = true;
    private volatile boolean paused = false;

    public ProjectWatchService(Path root,
                               @Nullable Path gitRepoRoot,
                               Listener listener)
    {
        this.root = root;
        this.gitRepoRoot = gitRepoRoot;
        this.listener = listener;
    }

    public void start(CompletableFuture<?> delayNotificationsUntilCompleted) {
        Thread watcherThread = new Thread(() -> beginWatching(delayNotificationsUntilCompleted), "DirectoryWatcher@" + Long.toHexString(Thread.currentThread().threadId()));
        watcherThread.start();
    }

    private void beginWatching(CompletableFuture<?> delayNotificationsUntilCompleted) {
        logger.debug("Setting up WatchService for {}", root);
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            // Recursively register all directories under project root except .brokk
            registerAllDirectories(root, watchService);

            // If the actual .git directory's parent (gitRepoRoot) is different from the project root
            // (common in worktrees), explicitly watch the .git directory within gitRepoRoot.
            // The registerAllDirectories method's .brokk exclusion (relative to this.root)
            // will not interfere with watching contents of .git.
            if (gitRepoRoot != null && !gitRepoRoot.equals(this.root)) {
                Path actualGitMetaDir = gitRepoRoot.resolve(".git");
                assert Files.isDirectory(actualGitMetaDir);
                logger.debug("Additionally watching git metadata directory for changes: {}", actualGitMetaDir);
                registerAllDirectories(actualGitMetaDir, watchService);
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
                while (paused) {
                    Thread.onSpinWait();
                }

                // Choose a short or long poll depending on focus
                long pollTimeout = isApplicationFocused() ? POLL_TIMEOUT_FOCUSED_MS : POLL_TIMEOUT_UNFOCUSED_MS;
                WatchKey key = watchService.poll(pollTimeout, TimeUnit.MILLISECONDS);

                // No event arrived within the poll window
                if (key == null) {
                    listener.onNoFilesChangedDuringPollInterval();
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
                listener.onFilesChanged(batch);
            }
        } catch (IOException e) {
            logger.error("Error setting up watch service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("FileWatchService thread interrupted; shutting down");
        }
    }

    /** mutable since we will collect events until they stop arriving */
    public static class EventBatch {
        boolean isOverflowed;
        Set<ProjectFile> files = new HashSet<>();

        @Override
        public String toString()
        {
            return "EventBatch{" + "isOverflowed=" + isOverflowed + ", files=" + files + '}';
        }
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
            batch.files.add(new ProjectFile(root, root.relativize(eventPath)));

            // If it's a directory creation, register it so we can watch its children
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(eventPath)) {
                try {
                    registerAllDirectories(eventPath, watchService);
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

        var brokkPrivate = root.resolve(".brokk");
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (var walker = Files.walk(start)) {
                walker.filter(Files::isDirectory)
                        .filter(dir -> !dir.startsWith(brokkPrivate))
                        .forEach(dir -> {
                            try {
                                dir.register(watchService,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                        StandardWatchEventKinds.ENTRY_DELETE,
                                        StandardWatchEventKinds.ENTRY_MODIFY);
                            } catch (IOException e) {
                                logger.warn("Failed to register directory for watching: {}", dir, e);
                            }
                        });
                // Success: If the walk completes without exception, break the retry loop.
                return;
            } catch (IOException | java.io.UncheckedIOException e) {
                // Determine the root cause, handling the case where the UncheckedIOException wraps another exception.
                Throwable cause = (e instanceof java.io.UncheckedIOException uioe) ? uioe.getCause() : e;

                // Retry only if it's a NoSuchFileException and we have attempts left.
                if (cause instanceof java.nio.file.NoSuchFileException && attempt < 3) {
                    logger.warn("Attempt {} failed to walk directory {} due to NoSuchFileException. Retrying in 10ms...", attempt, start, cause);
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
     * Pause the file watching service.
     */
    public synchronized void pause() {
        logger.debug("Pausing file watcher");
        paused = true;
    }

    /**
     * Resume the file watching service.
     */
    public synchronized void resume() {
        logger.debug("Resuming file watcher");
        paused = false;
    }

    @Override
    public void close() {
        running = false;
        resume(); // Ensure any waiting thread is woken up to exit
    }


    /**
     * Checks if any window in the application currently has focus
     *
     * @return true if any application window has focus, false otherwise
     */
    private boolean isApplicationFocused() {
        var focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        return focusedWindow != null;
    }
}
