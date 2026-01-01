package ai.brokk.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Wrapper around CompletableFuture that supports Subscription activities.
 * This is useful when displaying ContextFragments in the Swing GUI, since we can cancel the Subscription
 * if the Fragment is removed before its computation completes.
 */
public final class ComputedValue<T> {
    private static final Logger logger = LogManager.getLogger(ComputedValue.class);

    private final String name;
    private final CompletableFuture<T> futureRef;

    // listeners registered via onComplete; guarded by 'this'
    private final List<BiConsumer<? super T, ? super Throwable>> listeners = new ArrayList<>();

    /**
     * @param name   used for identification/logging; not null/blank
     * @param future the underlying future (may already be running or completed)
     */
    public ComputedValue(String name, CompletableFuture<T> future) {
        this.name = name.isBlank() ? "value" : name;
        this.futureRef = future;
        future.whenComplete(this::notifyComplete);
    }

    public ComputedValue(CompletableFuture<T> future) {
        this("value", future);
    }

    /**
     * Create an already-completed ComputedValue with a custom name. No worker thread is started.
     */
    public static <T> ComputedValue<T> completed(String name, @Nullable T value) {
        return new ComputedValue<>(name, CompletableFuture.completedFuture(value));
    }

    /**
     * Create an already-completed ComputedValue with the default name. No worker thread is started.
     */
    public static <T> ComputedValue<T> completed(@Nullable T value) {
        return completed("value", value);
    }

    /**
     * Project this ComputedValue by applying a synchronous transformation function.
     * The resulting CV completes when this CV completes, with the mapper applied to the result.
     * No additional threads are spawned.
     */
    public <U> ComputedValue<U> map(Function<? super T, ? extends U> mapper) {
        return new ComputedValue<>(name + "-map", futureRef.thenApply(mapper));
    }

    /**
     * Project this ComputedValue by applying an async transformation function.
     * The resulting CV completes when the nested CV completes.
     * No additional threads are spawned.
     */
    public <U> ComputedValue<U> flatMap(Function<? super T, ComputedValue<U>> mapper) {
        return new ComputedValue<>(
                name + "-flatMap", futureRef.thenCompose(v -> mapper.apply(v).future()));
    }

    /**
     * Returns the underlying future.
     */
    public CompletableFuture<T> future() {
        return futureRef;
    }

    /**
     * Blocks until the computation is determined.
     */
    @Blocking
    public T join() throws CancellationException, CompletionException {
        try {
            assert !SwingUtilities.isEventDispatchThread();
        } catch (AssertionError e) {
            // Using exception to get the stacktrace
            logger.error("May not block on the EDT thread!", e);
        }

        try {
            return futureRef.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("Taking longer than 5 seconds to compute value! Waiting now indefinitely....", e);
            return futureRef.join();
        } catch (ExecutionException | InterruptedException e) {
            throw new CompletionException(e);
        }
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
     * Non-blocking probe. Empty if not completed, or if completed exceptionally.
     */
    public Optional<T> tryGet() {
        if (!futureRef.isDone()) {
            return Optional.empty();
        }
        try {
            //noinspection OptionalOfNullableMisuse (this may in fact be null)
            return Optional.ofNullable(futureRef.join());
        } catch (CancellationException | CompletionException ex) {
            return Optional.empty();
        }
    }

    /**
     * Await the value with a bounded timeout. If called on the Swing EDT, returns Optional.empty() immediately.
     * May block the calling thread.
     */
    @Blocking
    public Optional<T> await(Duration timeout) {
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
     * <p>
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
