package io.github.jbellis.brokk.util;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.TaskResult;
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
        this.userExecutor = createLoggingExecutor(Executors.newSingleThreadExecutor());
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
            logger.debug("Interrupting cancelable user action thread {}", t.getName());
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

    /** Caller is responsible for handling interruption of `task`. */
    public CompletableFuture<Void> submitLlmAction(ThrowingRunnable task) {
        return userExecutor.submit(() -> {
            cancelableThread.set(Thread.currentThread());
            io.disableActionButtons();

            try {
                task.run();
            } catch (InterruptedException ie) {
                logger.error("LLM task did not handle interruption correctly", ie);
                throw new CancellationException(ie.getMessage());
            } catch (CancellationException cex) {
                logger.error("LLM task did not handle interruption correctly", cex);
                throw cex;
            } finally {
                cancelableThread.set(null);
                // Clear interrupt status so subsequent exclusive actions are unaffected
                Thread.interrupted();
                io.actionComplete();
                io.enableActionButtons();
            }
        });
    }

    /**
     * Will send a "canceled" message to llmOutput on StopReason.INTERRUPTED, and log on unexpected errors, but but
     * further exception handling is up to the caller.
     */
    public CompletableFuture<TaskResult> submitLlmAction(String description, Callable<TaskResult> task) {
        return userExecutor.submit(() -> {
            cancelableThread.set(Thread.currentThread());
            io.disableActionButtons();

            try {
                var result = task.call();
                if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                    io.llmOutput(description + " canceled", ChatMessageType.CUSTOM, true, false);
                }
                return result;
            } catch (InterruptedException | CancellationException cex) {
                logger.error("LLM task did not handle interruption correctly", cex);
                throw cex;
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

    // TODO figure out how to DRY this w/ the executors in ContextManager
    private LoggingExecutorService createLoggingExecutor(ExecutorService delegate) {
        Consumer<Throwable> onError = th -> {
            if (th instanceof InterruptedException) {
                logger.debug("Interrupted task in executor", th);
                return;
            }
            logger.error("Uncaught exception in UserActionManager executor", th);
            io.systemOutput("Uncaught exception in user action thread %s\n%s"
                    .formatted(Thread.currentThread().getName(), getStackTraceAsString(th)));
        };
        return new LoggingExecutorService(delegate, onError);
    }

    private String getStackTraceAsString(Throwable throwable) {
        var sw = new java.io.StringWriter();
        var pw = new java.io.PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws InterruptedException;
    }
}
