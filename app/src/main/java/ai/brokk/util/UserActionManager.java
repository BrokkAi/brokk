package ai.brokk.util;

import ai.brokk.ExceptionReporter;
import ai.brokk.IConsoleIO;
import ai.brokk.exception.GlobalExceptionHandler;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Manages execution of "user actions" with two clear semantics: - cancelable actions: long-running, interruptible,
 * Stop-button aware (e.g., Ask/Code/Search). - exclusive actions: non-interruptible, short UI tasks that must exclude
 * cancelables (e.g., undo/redo/session ops).
 *
 * <p>Both action types are serialized via a fair lock ("user slot") to prevent overlap.
 */
public class UserActionManager {
    private static final Logger logger = LogManager.getLogger(UserActionManager.class);

    private volatile IConsoleIO io;

    // Single-thread executor to serialize all user actions; cancellation only applies to LLM actions
    private final LoggingExecutorService userExecutor;

    // Track cancelable action state for Stop button & diagnostics
    private final AtomicReference<@Nullable Thread> cancelableThread = new AtomicReference<>();

    public UserActionManager(IConsoleIO io) {
        this.io = io;
        var threadFactory = ExecutorServiceUtil.createNamedThreadFactory("UserActionManager");
        this.userExecutor = createLoggingExecutor(Executors.newSingleThreadExecutor(threadFactory));
    }

    public void setIo(IConsoleIO io) {
        this.io = io;
    }

    public boolean isCancelableActionInProgress() {
        return cancelableThread.get() != null;
    }

    public boolean isLlmTaskInProgress() {
        return isCancelableActionInProgress();
    }

    public boolean isCurrentThreadCancelableAction() {
        return Thread.currentThread() == cancelableThread.get();
    }

    public void cancelActiveAction() {
        var t = cancelableThread.get();
        if (t != null && t.isAlive()) {
            logger.debug("Interrupting cancelable user action thread " + t.getName());
            t.interrupt();
        }
    }

    public CompletableFuture<Void> shutdownAndAwait(long awaitMillis) {
        return userExecutor.shutdownAndAwait(awaitMillis, "UserActionManager.userExecutor");
    }

    // ---------- Exclusive (non-cancelable) ----------

    public CompletableFuture<Void> submitExclusiveAction(Runnable task) {
        return submitExclusiveAction(() -> {
            task.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> submitExclusiveAction(Callable<T> task) {
        return userExecutor.submit(() -> {
            io.disableActionButtons();
            try {
                return task.call();
            } finally {
                // Clear any interrupt status on the worker thread
                Thread.interrupted();
                io.actionComplete();
                io.enableActionButtons();
            }
        });
    }

    // ---------- Cancelable (interruptible) ----------

    /**
     * Runs the given task on userExecutor and returns a CompletableFuture<Void>.
     * Exceptions complete the future exceptionally.
     */
    public CompletableFuture<Void> submitLlmAction(ThrowingRunnable task) {
        return userExecutor.submit(() -> {
            cancelableThread.set(Thread.currentThread());
            io.disableActionButtons();
            try {
                task.run();
            } catch (Exception ex) {
                // let LoggingExecutorService handle
                throw new RuntimeException(ex);
            } finally {
                cancelableThread.set(null);
                // Clear interrupt status so subsequent exclusive actions are unaffected
                Thread.interrupted();
                io.actionComplete();
                io.enableActionButtons();
            }
        });
    }

    // ---------- helpers ----------

    private LoggingExecutorService createLoggingExecutor(ExecutorService delegate) {
        Consumer<Throwable> onError = th -> {
            if (GlobalExceptionHandler.isCausedBy(th, InterruptedException.class)
                    || GlobalExceptionHandler.isCausedBy(th, CancellationException.class)) {
                // UAM-specific policy: log and upload InterruptedException for telemetry because this indicates that a
                // UI action wired up to the Go/Stop button did not handle it correctly.
                logger.warn("Cancelable user action was interrupted/canceled", th);
                try {
                    ExceptionReporter.tryReportException(th);
                } catch (Throwable reportingError) {
                    logger.warn("Failed to upload exception report", reportingError);
                }
            } else {
                GlobalExceptionHandler.handle(th, st -> io.showNotification(IConsoleIO.NotificationRole.ERROR, st));
            }
        };
        return new LoggingExecutorService(delegate, onError);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
