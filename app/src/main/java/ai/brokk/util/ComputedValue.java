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
import java.util.concurrent.atomic.AtomicBoolean;
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
 * - Lazy by default when no Executor is provided; computation starts on first call to future()/start()/await()
 *   (never on tryGet()).
 * - If constructed with a non-null Executor, the computation autostarts exactly once on that executor.
 * - Predictable thread names when using the dedicated thread: cv-<name>-<sequence>.
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

    // Exposed for same-package tests; use future() in production call sites.
    final CompletableFuture<T> futureRef;

    private final @Nullable Executor executor;
    private final AtomicBoolean started = new AtomicBoolean(false);

    // listeners registered via onComplete; guarded by 'this'
    private final List<BiConsumer<? super T, ? super Throwable>> listeners = new ArrayList<>();

    /**
     * Create the computation with a predictable name for the thread.
     * Lazy by default (does not start until future()/start()/await() is called).
     *
     * @param name       used in the worker thread name; not null/blank
     * @param supplier   computation to run
     */
    ComputedValue(String name, Supplier<T> supplier) {
        this(name, supplier, null, new CompletableFuture<>());
    }

    /**
     * Create the computation with a predictable name for the thread.
     * If executor is non-null, the computation autostarts exactly once on that executor.
     *
     * @param name       used in the worker thread name; not null/blank
     * @param supplier   computation to run
     * @param executor   optional executor on which to run the supplier; if null, a dedicated daemon thread is used on start()
     */
    public ComputedValue(String name, Supplier<T> supplier, @Nullable Executor executor) {
        this(name, supplier, executor, new CompletableFuture<>());
        // Autostart when an executor is provided to preserve existing eager semantics for fragment computations.
        if (executor != null) {
            startInternal();
        }
    }

    private ComputedValue(String name, Supplier<T> supplier, @Nullable Executor executor, CompletableFuture<T> future) {
        this.name = name.isBlank() ? "value" : name;
        this.supplier = supplier;
        this.executor = executor;
        this.futureRef = future;
    }

    /**
     * Create an already-completed ComputedValue with a custom name. No worker thread is started.
     */
    public static <T> ComputedValue<T> completed(String name, @Nullable T value) {
        return new ComputedValue<>(name, () -> value, null, CompletableFuture.completedFuture(value));
    }

    /**
     * Create an already-completed ComputedValue with the default name. No worker thread is started.
     */
    public static <T> ComputedValue<T> completed(@Nullable T value) {
        return completed("value", value);
    }

    /**
     * Returns the underlying future, starting the computation if necessary.
     */
    public CompletableFuture<T> future() {
        startInternal();
        return futureRef;
    }

    /**
     * Non-blocking. If the value is available, returns it; otherwise returns the provided placeholder.
     */
    public T renderNowOr(T placeholder) {
        return tryGet().orElse(placeholder);
    }

    /**
     * Non-blocking. If the value is available, returns it; otherwise returns null.
     */
    public @Nullable T renderNowOrNull() {
        return tryGet().orElse(null);
    }

    /**
     * Explicitly start the computation (no-op if already started).
     */
    public void start() {
        startInternal();
    }

    private void startInternal() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
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

    /**
     * Non-blocking probe. Empty if not completed, or if completed exceptionally.
     */
    public Optional<T> tryGet() {
        var f = futureRef;
        if (!f.isDone()) {
            return Optional.empty();
        }
        try {
            //noinspection OptionalOfNullableMisuse (this may in fact be null)
            return Optional.ofNullable(f.join());
        } catch (CancellationException | CompletionException ex) {
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
        startInternal();
        try {
            var v = futureRef.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
            //noinspection OptionalOfNullableMisuse (this may in fact be null)
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
