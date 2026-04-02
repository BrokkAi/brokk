package ai.brokk.concurrent;

import ai.brokk.exception.GlobalExceptionHandler;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class LoggingFuture {
    private LoggingFuture() {}

    private static <T> CompletableFuture<T> supplyCallableOnVirtualThread(Callable<T> callable) {
        var cf = new EdtAwareFuture<T>();

        Thread virtualThread = Thread.ofVirtual().unstarted(() -> {
            try {
                if (!cf.isCancelled()) {
                    cf.complete(callable.call());
                }
            } catch (Throwable th) {
                if (cf.isCancelled() && (th instanceof InterruptedException || th instanceof CancellationException)) {
                    return;
                }
                GlobalExceptionHandler.handle(th);
                cf.completeExceptionally(th);
            }
        });

        cf.whenComplete((v, th) -> {
            if (th instanceof CancellationException) {
                virtualThread.interrupt();
            }
        });

        virtualThread.start();
        return cf;
    }

    public static <T> CompletableFuture<T> supplyCallableAsync(Callable<T> callable) {
        return supplyCallableAsync(callable, ForkJoinPool.commonPool());
    }

    public static <T> CompletableFuture<T> supplyCallableAsync(Callable<T> callable, Executor executor) {
        var cf = new EdtAwareFuture<T>();
        executor.execute(() -> {
            try {
                cf.complete(callable.call());
            } catch (Throwable th) {
                GlobalExceptionHandler.handle(th);
                cf.completeExceptionally(th);
            }
        });
        return cf;
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return supplyCallableAsync(supplier::get, ForkJoinPool.commonPool());
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
        return supplyCallableAsync(supplier::get, executor);
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return supplyCallableAsync(Executors.callable(runnable, null), ForkJoinPool.commonPool());
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        return supplyCallableAsync(Executors.callable(runnable, null), executor);
    }

    public static <T> CompletableFuture<T> supplyVirtual(Supplier<T> supplier) {
        return supplyCallableVirtual(supplier::get);
    }

    public static <T> CompletableFuture<T> supplyCallableVirtual(Callable<T> callable) {
        return supplyCallableOnVirtualThread(callable);
    }

    public static CompletableFuture<Void> runVirtual(Runnable runnable) {
        return supplyCallableVirtual(Executors.callable(runnable, null));
    }

    public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
        var result = new EdtAwareFuture<Void>();
        CompletableFuture.allOf(cfs).whenComplete((v, th) -> {
            if (th != null) {
                GlobalExceptionHandler.handle(th);
                result.completeExceptionally(th);
            } else {
                result.complete(null);
            }
        });
        return result;
    }

    public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs) {
        var result = new EdtAwareFuture<Object>();
        CompletableFuture.anyOf(cfs).whenComplete((v, th) -> {
            if (th != null) {
                GlobalExceptionHandler.handle(th);
                result.completeExceptionally(th);
            } else {
                result.complete(v);
            }
        });
        return result;
    }
}
