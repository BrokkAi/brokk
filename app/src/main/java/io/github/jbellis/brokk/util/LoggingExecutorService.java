package io.github.jbellis.brokk.util;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LoggingExecutorService implements ExecutorService {
    private static final Logger logger = LogManager.getLogger(LoggingExecutorService.class);
    private final ExecutorService delegate;
    private final Consumer<Throwable> exceptionHandler;

    public LoggingExecutorService(ExecutorService delegate, Consumer<Throwable> exceptionHandler) {
        this.delegate = delegate;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        var wrappedCallable = wrap(task);
        var cf = new CompletableFuture<T>();
        try {
            var underlyingFuture = delegate.submit(() -> {
                try {
                    cf.complete(wrappedCallable.call());
                } catch (Throwable t) {
                    // exceptionHandler was already called by wrap()
                    cf.completeExceptionally(t);
                }
            });
            // Propagate cancellation from CompletableFuture to the underlying Future
            cf.whenComplete((res, ex) -> {
                if (cf.isCancelled()) underlyingFuture.cancel(true);
            });
        } catch (RejectedExecutionException e) {
            logger.trace("Task rejected because executor is shut down", e);
            cf.completeExceptionally(e);
        }
        return cf;
    }

    @Override
    public CompletableFuture<Void> submit(Runnable task) {
        var wrappedRunnable = wrap(task);
        var cf = new CompletableFuture<Void>();
        try {
            var underlyingFuture = delegate.submit(() -> {
                try {
                    wrappedRunnable.run();
                    cf.complete(null);
                } catch (Throwable t) {
                    // exceptionHandler was already called by wrap()
                    cf.completeExceptionally(t);
                }
            });
            cf.whenComplete((res, ex) -> {
                if (cf.isCancelled()) underlyingFuture.cancel(true);
            });
        } catch (RejectedExecutionException e) {
            logger.trace("Task rejected because executor is shut down", e);
            cf.completeExceptionally(e);
        }
        return cf;
    }

    @Override
    public <T> CompletableFuture<T> submit(Runnable task, T result) {
        var wrappedRunnable = wrap(task);
        var cf = new CompletableFuture<T>();
        try {
            var underlyingFuture = delegate.submit(() -> {
                try {
                    wrappedRunnable.run();
                    cf.complete(result);
                } catch (Throwable t) {
                    // exceptionHandler was already called by wrap()
                    cf.completeExceptionally(t);
                }
            });
            cf.whenComplete((res, ex) -> {
                if (cf.isCancelled()) underlyingFuture.cancel(true);
            });
        } catch (RejectedExecutionException e) {
            logger.trace("Task rejected because executor is shut down", e);
            cf.completeExceptionally(e);
        }
        return cf;
    }

    private <T> Callable<T> wrap(Callable<T> task) {
        return () -> {
            try {
                return task.call();
            } catch (Throwable th) {
                exceptionHandler.accept(th);
                throw th;
            }
        };
    }

    private Runnable wrap(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable th) {
                exceptionHandler.accept(th);
                throw th;
            }
        };
    }

    @Override
    public void execute(Runnable command) {
        try {
            delegate.execute(wrap(command));
        } catch (RejectedExecutionException e) {
            logger.trace("Task rejected because executor is shut down", e);
            // Exception is logged and swallowed as execute is void
        }
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    /**
     * Gracefully shutdown this executor and await termination for up to the given timeout (seconds), forcing
     * shutdownNow() if the timeout elapses. Returns a CompletableFuture that completes when shutdown sequence finishes.
     *
     * @param timeoutMillis ms to wait for termination before forcing shutdownNow
     * @param name Name used in logging
     * @return CompletableFuture that completes when shutdown process finishes
     */
    public CompletableFuture<Void> shutdownAndAwait(long timeoutMillis, String name) {
        return CompletableFuture.runAsync(() -> {
            delegate.shutdown();
            try {
                if (!delegate.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    logger.warn("{} did not terminate within {}ms; forcing shutdownNow()", name, timeoutMillis);
                    var pending = delegate.shutdownNow();
                    if (!pending.isEmpty()) {
                        logger.debug("Canceled {} queued tasks in {}", pending.size(), name);
                    }
                    if (!delegate.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        logger.warn("{} still not terminated after shutdownNow()", name);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while awaiting termination of {}", name, e);
            }
        });
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        var wrappedTasks = tasks.stream().map(this::wrap).toList();
        return delegate.invokeAll(wrappedTasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        var wrappedTasks = tasks.stream().map(this::wrap).toList();
        return delegate.invokeAll(wrappedTasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks.stream().map(this::wrap).toList());
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks.stream().map(this::wrap).toList(), timeout, unit);
    }
}
