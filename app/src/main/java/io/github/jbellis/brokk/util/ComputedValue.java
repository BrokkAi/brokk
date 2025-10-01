package io.github.jbellis.brokk.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * One-shot, self-materializing computed value.
 *
 * Characteristics:
 * - Autostarts a single, short-lived daemon thread (or executor task) upon construction for non-preseeded values.
 * - Predictable thread names: cv-<name>-<sequence>.
 * - Non-blocking probe via {@link #tryGet()}.
 * - Async access via {@link #future()}.
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

    private enum StartMode { AUTO, SUPPRESS }

    private final String name;
    private final Supplier<T> supplier;
    private final @Nullable Executor executor;

    private volatile CompletableFuture<T> futureRef;

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
        this(name, supplier, null);
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
        this(name, supplier, executor, StartMode.AUTO);
    }

    private ComputedValue(String name, Supplier<T> supplier, @Nullable Executor executor, StartMode mode) {
        this.name = name.isBlank() ? "value" : name;
        this.supplier = supplier;
        this.executor = executor;
        this.futureRef = new CompletableFuture<>();
        if (mode == StartMode.AUTO) {
            // Start exactly once at construction time
            var f = futureRef;
            String threadName = "cv-" + name + "-" + SEQ.incrementAndGet();
            Runnable task = () -> {
                try {
                    var value = this.supplier.get();
                    f.complete(value);
                    notifyComplete(value, null);
                } catch (Throwable ex) {
                    try {
                        f.completeExceptionally(ex);
                    } catch (Throwable ignore) {
                        // ignored
                    }
                    notifyComplete(null, ex);
                    logger.debug("ComputedValue supplier for {} failed: {}", name, ex.toString());
                }
            };
            if (this.executor != null) {
                try {
                    this.executor.execute(task);
                } catch (Throwable ex) {
                    // Fallback to dedicated thread if executor rejects
                    var t = new Thread(task, threadName);
                    t.setDaemon(true);
                    t.start();
                }
            } else {
                var t = new Thread(task, threadName);
                t.setDaemon(true);
                t.start();
            }
        }
    }

    /**
     * Create an already-completed ComputedValue with a custom name. No worker thread is started.
     */
    public static <T> ComputedValue<T> completed(String name, @Nullable T value) {
        var cv = new ComputedValue<>(name, () -> value, null, StartMode.SUPPRESS);
        synchronized (cv) {
            cv.futureRef = CompletableFuture.completedFuture(value);
        }
        return cv;
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
        try {
            return Optional.ofNullable(f.join());
        } catch (Throwable t) {
            // includes CompletionException for exceptional completion
            return Optional.empty();
        }
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
        var f = futureRef;
        try {
            T v = f.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(v);
        } catch (Exception e) {
            return Optional.empty();
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
        var f = futureRef;
        if (f.isDone()) {
            T v = null;
            Throwable ex = null;
            try {
                v = f.join();
            } catch (Throwable t) {
                ex = t.getCause() != null ? t.getCause() : t;
            }
            try {
                handler.accept(v, ex);
            } catch (Throwable t) {
                logger.debug("onComplete handler for {} raised: {}", name, t.toString());
            }
            return () -> { /* no-op */ };
        }

        synchronized (this) {
            // double-check after acquiring the lock
            f = futureRef;
            if (f.isDone()) {
                T v = null;
                Throwable ex = null;
                try {
                    v = f.join();
                } catch (Throwable t) {
                    ex = t.getCause() != null ? t.getCause() : t;
                }
                try {
                    handler.accept(v, ex);
                } catch (Throwable t) {
                    logger.debug("onComplete handler for {} raised: {}", name, t.toString());
                }
                return () -> { /* no-op */ };
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
                try {
                    h.accept(value, ex);
                } catch (Throwable t) {
                    logger.debug("onComplete handler for {} raised: {}", name, t.toString());
                }
            }
        }
    }

    /**
     * CompletableFuture view for async access. This never blocks the EDT by itself.
     */
    @VisibleForTesting
    CompletableFuture<T> future() {
        return futureRef;
    }
}
