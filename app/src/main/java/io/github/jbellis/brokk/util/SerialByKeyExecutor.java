package io.github.jbellis.brokk.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * A utility class that submits tasks to an ExecutorService, with the constraint that tasks
 * submitted with the same key are executed serially.
 */
public class SerialByKeyExecutor {

    private static final Logger logger = LogManager.getLogger(SerialByKeyExecutor.class);

    private final ExecutorService executor;

    /**
     * Maps task key to the last Future submitted with that key.
     */
    private final ConcurrentHashMap<String, CompletableFuture<?>> activeFutures = new ConcurrentHashMap<>();

    /**
     * Creates a new SerialByKeyExecutor that will use the given ExecutorService to run tasks.
     *
     * @param executor the ExecutorService to use
     */
    public SerialByKeyExecutor(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    /**
     * Submits a task for execution. Tasks with the same key will be executed in the order they were submitted.
     *
     * @param key the key to associate with the task
     * @param task the task to execute
     * @param <T> the type of the task's result
     * @return a CompletableFuture representing the pending completion of the task
     */
    public <T> CompletableFuture<T> submit(String key, Callable<T> task) {
        var supplier = toSupplier(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                logger.error("Task for key '{}' failed", key, e);
                throw e;
            }
        });

        /*
         * We insert a *placeholder* future into the map first.  The real work is executed
         * afterwards; once it finishes we (1) remove the map entry and (2) complete the
         * placeholder.  Thus, by the time callers observe completion, the key has already
         * been cleaned up.
         */
        @SuppressWarnings("unchecked")
        CompletableFuture<T> placeholder = (CompletableFuture<T>) activeFutures.compute(
                key,
                (String k, @Nullable CompletableFuture<?> previous) -> {
                    var resultFuture = new CompletableFuture<T>();

                    Runnable scheduleTask = () -> CompletableFuture
                            .supplyAsync(supplier, executor)
                            .whenCompleteAsync((T res, @Nullable Throwable err) -> {
                                // guarantee cleanup precedes observable completion
                                activeFutures.remove(k, resultFuture);
                                if (err != null) {
                                    resultFuture.completeExceptionally(err);
                                } else {
                                    resultFuture.complete(res);
                                }
                            }, executor);

                    if (previous == null) {
                        // no pending work: execute immediately
                        scheduleTask.run();
                    } else {
                        // chain after the previous placeholder, regardless of its outcome
                        previous.whenCompleteAsync((r, e) -> scheduleTask.run(), executor);
                    }

                    return resultFuture;
                });

        return placeholder;
    }

    /**
     * Submits a task with no return value for execution.
     *
     * @param key the key to associate with the task
     * @param task the task to execute
     * @return a CompletableFuture representing the pending completion of the task
     */
    public CompletableFuture<Void> submit(String key, Runnable task) {
        return submit(key, () -> {
            try {
                task.run();
            } catch (Exception e) {
                throw e;
            }
            return null;
        });
    }



    /**
     * Converts a Callable into a Supplier, wrapping any checked exceptions from the Callable in a RuntimeException.
     */
    private static <T> Supplier<T> toSupplier(Callable<T> callable) {
        return () -> {
            try {
                return callable.call();
            } catch (Exception e) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * @return the number of keys with active tasks.
     */
    public int getActiveKeyCount() {
        return activeFutures.size();
    }
}
