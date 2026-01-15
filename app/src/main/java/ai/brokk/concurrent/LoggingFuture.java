package ai.brokk.concurrent;

import ai.brokk.exception.GlobalExceptionHandler;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class LoggingFuture {
    private LoggingFuture() {}

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return supplyAsync(supplier, ForkJoinPool.commonPool());
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
        if (executor instanceof LoggingExecutorService) {
            return CompletableFuture.supplyAsync(supplier, executor);
        }

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return supplier.get();
                    } catch (Throwable th) {
                        GlobalExceptionHandler.handle(th, st -> {});
                        throw th;
                    }
                },
                executor);
    }

    public static CompletableFuture<Void> supplyAsync(Runnable runnable) {
        return supplyAsync(runnable, ForkJoinPool.commonPool());
    }

    public static CompletableFuture<Void> supplyAsync(Runnable runnable, Executor executor) {
        return supplyAsync(
                () -> {
                    runnable.run();
                    return null;
                },
                executor);
    }
}
