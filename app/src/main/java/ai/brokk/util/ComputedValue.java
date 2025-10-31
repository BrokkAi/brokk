package ai.brokk.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * One-shot, self-materializing computed value.
 *
 * Characteristics:
 * - Autostarts a single, short-lived daemon thread (or executor task) upon construction for non-preseeded values.
 * - Predictable thread names: cv-<name>-<sequence>.
 * - Non-blocking probe via {@link #tryGet()}.
 * - Best-effort bounded wait via {@link #await(Duration)}. If invoked on the Swing EDT, returns Optional.empty()
 *   immediately (never blocks the EDT).
 *
 * Notes:
 * - Exceptions thrown by the supplier complete the future exceptionally.
 * - {@code tryGet()} returns empty if not completed normally (including exceptional completion).
 */
public final class ComputedValue<T> {
    private static final Logger logger = LogManager.getLogger(ComputedValue.class);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private final String name;
    private final Supplier<T> supplier;

    final CompletableFuture<T> futureRef;

    // listeners registered via onComplete; guarded by 'this'
    private final List<BiConsumer<? super T, ? super Throwable>> listeners = new ArrayList<>();

    /**
     * Create the computation with a predictable name for the thread.
     * The computation autostarts by default.
     *
     * @param name       used in the worker thread name; not null/blank
     * @param supplier   computation to run
     */
    public ComputedValue(String name, Supplier<T> supplier) {
        this(name, supplier, (Executor) null);
    }

    /**
     * Create the computation with a predictable name for the thread.
     * The computation autostarts by default.
     *
     * @param name       used in the worker thread name; not null/blank
     * @param supplier   computation to run
     * @param executor   optional executor on which to run the supplier; if null, a dedicated daemon thread is used
     */
    public ComputedValue(String name, Supplier<T> supplier, @Nullable Executor executor) {
        this(name, supplier, new CompletableFuture<>());
        // Start exactly once at construction time
        var f = futureRef;
        String threadName = "cv-" + this.name + "-" + SEQ.incrementAndGet();
        Runnable task = () -> {
            try {
                var value = this.supplier.get();
                f.complete(value);
                notifyComplete(value, null);
            } catch (Throwable ex) {
                f.completeExceptionally(ex);
                notifyComplete(null, ex);
                logger.debug("ComputedValue supplier for {} failed", this.name, ex);
            }
        };
        if (executor == null) {
            var t = new Thread(task, threadName);
            t.setDaemon(true);
            t.start();
        } else {
            executor.execute(task);
        }
    }

    private ComputedValue(String name, Supplier<T> supplier, CompletableFuture<T> future) {
        this.name = name.isBlank() ? "value" : name;
        this.supplier = supplier;
        this.futureRef = future;
    }

    /**
     * Create an already-completed ComputedValue with a custom name. No worker thread is started.
     */
    public static <T> ComputedValue<T> completed(String name, @Nullable T value) {
        return new ComputedValue<>(name, () -> value, CompletableFuture.completedFuture(value));
    }

    /**
     * Create an already-completed ComputedValue with the default name. No worker thread is started.
     */
    public static <T> ComputedValue<T> completed(@Nullable T value) {
        return completed("value", value);
    }

    /**
     * Non-blocking. If the value is available, returns it; otherwise returns the provided placeholder.
     */
    public T renderNowOr(T placeholder) {
        return tryGet().orElse(placeholder);
    }

    /**
     * No-op: computations start at construction time for non-preseeded values.
     */
    public void start() {
        // already started in constructor
    }

    /**
     * Non-blocking probe. Empty if not completed, or if completed exceptionally.
     */
    public Optional<T> tryGet() {
        var f = futureRef;
        if (!f.isDone()) {
            return Optional.empty();
        }
        return Optional.ofNullable(f.join());
    }

    /**
     * Await the value with a bounded timeout. If called on the Swing EDT, returns Optional.empty() immediately.
     * Never blocks the EDT.
     */
    public Optional<T> await(Duration timeout) {
        if (SwingUtilities.isEventDispatchThread()) {
            logger.warn("ComputedValue.await() called on Swing EDT for {}", name);
            return Optional.empty();
        }
        try {
            var v = futureRef.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(v);
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Register a completion callback. The handler is invoked exactly once, with either the computed value
     * (and null throwable) or with a throwable (and null value) if the computation failed.
     * If the value is already available at registration time, the handler is invoked immediately.
     *
     * Returns a Subscription that can be disposed to remove the handler before completion.
     */
    public Subscription onComplete(BiConsumer<? super T, ? super Throwable> handler) {
        synchronized (this) {
            // double-check after acquiring the lock
            /* no-op */
            if (futureRef.isDone()) {
                T v = null;
                Throwable ex = null;
                try {
                    v = futureRef.join();
                } catch (CancellationException | CompletionException t) {
                    ex = t.getCause() != null ? t.getCause() : t;
                }
                handler.accept(v, ex);
                return () -> {
                    /* no-op */
                };
            }

            listeners.add(handler);
            return () -> {
                synchronized (ComputedValue.this) {
                    listeners.remove(handler);
                }
            };
        }
    }

    /**
     * Disposable token for onComplete registrations.
     */
    public interface Subscription {
        void dispose();
    }

    private void notifyComplete(@Nullable T value, @Nullable Throwable ex) {
        List<BiConsumer<? super T, ? super Throwable>> toNotify = null;
        synchronized (this) {
            if (!listeners.isEmpty()) {
                toNotify = List.copyOf(listeners);
                listeners.clear();
            }
        }
        if (toNotify != null) {
            for (var h : toNotify) {
                h.accept(value, ex);
            }
        }
    }
}
