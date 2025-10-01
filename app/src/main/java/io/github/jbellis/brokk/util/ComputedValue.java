package io.github.jbellis.brokk.util;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * One-shot, self-materializing computed value.
 *
 * Characteristics:
 * - Spawns a single, short-lived daemon thread on first access (or eagerly, if configured).
 * - Predictable thread names: cv-&lt;name&gt;-&lt;sequence&gt;.
 * - Non-blocking probe via {@link #tryGet()}.
 * - Async access via {@link #future()}.
 * - Best-effort bounded wait via {@link #await(Duration)}. If invoked on the Swing EDT, returns Optional.empty()
 *   immediately (never blocks the EDT).
 * - {@link #reset()} is idempotent and causes the next access to recompute on a new thread.
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
    private final @Nullable Executor executor;

    // Guarded by 'this' during transitions
    private volatile boolean started = false;
    private volatile @Nullable CompletableFuture<T> futureRef = null;

    /**
     * Create and optionally eager-start the computation with a default name.
     *
     * @param supplier   computation to run
     * @param eagerStart if true, start immediately
     */
    public ComputedValue(Supplier<T> supplier, boolean eagerStart) {
        this("value", supplier, eagerStart, null);
    }

    /**
     * Create and optionally eager-start the computation with a predictable name for the thread.
     *
     * @param name       used in the worker thread name; not null/blank
     * @param supplier   computation to run
     * @param eagerStart if true, start immediately
     */
    public ComputedValue(String name, Supplier<T> supplier, boolean eagerStart) {
        this(name, supplier, eagerStart, null);
    }

    /**
     * Create and optionally eager-start the computation with a predictable name for the thread.
     *
     * @param name       used in the worker thread name; not null/blank
     * @param supplier   computation to run
     * @param eagerStart if true, start immediately
     * @param executor   optional executor on which to run the supplier; if null, a dedicated daemon thread is used
     */
    public ComputedValue(String name, Supplier<T> supplier, boolean eagerStart, @Nullable Executor executor) {
        this.name = name.isBlank() ? "value" : name;
        this.supplier = supplier;
        this.executor = executor;
        if (eagerStart) {
            ensureStarted();
        }
    }

    /**
     * Create an already-completed ComputedValue with a custom name. No worker thread is started.
     */
    public static <T> ComputedValue<T> completed(String name, @Nullable T value) {
        var cv = new ComputedValue<T>(name, () -> value, false);
        synchronized (cv) {
            cv.started = true;
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
     * Non-blocking probe. Empty if not completed, or if completed exceptionally.
     */
    public Optional<T> tryGet() {
        var f = futureRef;
        if (f == null || !f.isDone()) {
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
        ensureStarted();
        var f = futureRef;
        if (f == null) {
            return Optional.empty();
        }
        try {
            T v = f.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(v);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * CompletableFuture view for async access. This never blocks the EDT by itself.
     * The computation starts if not already started.
     */
    public CompletableFuture<T> future() {
        ensureStarted();
        return requireNonNull(futureRef);
    }

    /**
     * Reset the computation. Idempotent. Subsequent access recomputes on a new one-off thread.
     */
    public synchronized void reset() {
        started = false;
        futureRef = null;
    }

    private void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (this) {
            if (started) {
                return;
            }
            started = true;
            var f = new CompletableFuture<T>();
            futureRef = f;
            String threadName = "cv-" + name + "-" + SEQ.incrementAndGet();
            Runnable task = () -> {
                try {
                    var value = supplier.get();
                    f.complete(value);
                } catch (Throwable ex) {
                    try {
                        f.completeExceptionally(ex);
                    } catch (Throwable ignore) {
                        // ignored
                    }
                    logger.debug("ComputedValue supplier for {} failed: {}", name, ex.toString());
                }
            };
            if (executor != null) {
                try {
                    executor.execute(task);
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
}
