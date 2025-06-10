package io.github.jbellis.brokk.util;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class LoggingExecutorService implements ExecutorService {
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
        var underlyingFuture = delegate.submit(() -> {
            try {
                cf.complete(wrappedCallable.call());
            } catch (Throwable t) {
                // exceptionHandler was already called by wrap()
                cf.completeExceptionally(t);
            }
        });
        // Propagate cancellation from CompletableFuture to the underlying Future
        cf.whenComplete((res, ex) -> { if (cf.isCancelled()) underlyingFuture.cancel(true); });
        return cf;
    }

    @Override
    public CompletableFuture<Void> submit(Runnable task) {
        var wrappedRunnable = wrap(task);
        var cf = new CompletableFuture<Void>();
        var underlyingFuture = delegate.submit(() -> {
            try {
                wrappedRunnable.run();
                cf.complete(null);
            } catch (Throwable t) {
                // exceptionHandler was already called by wrap()
                cf.completeExceptionally(t);
            }
        });
        cf.whenComplete((res, ex) -> { if (cf.isCancelled()) underlyingFuture.cancel(true); });
        return cf;
    }

    @Override
    public <T> CompletableFuture<T> submit(Runnable task, T result) {
        var wrappedRunnable = wrap(task);
        var cf = new CompletableFuture<T>();
        var underlyingFuture = delegate.submit(() -> {
            try {
                wrappedRunnable.run();
                cf.complete(result);
            } catch (Throwable t) {
                // exceptionHandler was already called by wrap()
                cf.completeExceptionally(t);
            }
        });
        cf.whenComplete((res, ex) -> { if (cf.isCancelled()) underlyingFuture.cancel(true); });
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
        delegate.execute(wrap(command));
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
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks.stream().map(this::wrap).toList());
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks.stream().map(this::wrap).toList(), timeout, unit);
    }
}
