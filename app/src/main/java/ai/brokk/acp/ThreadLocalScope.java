package ai.brokk.acp;

import org.jetbrains.annotations.Nullable;

/**
 * Tiny helper for scoped {@link ThreadLocal} / {@link InheritableThreadLocal} swaps. Saves the
 * previous value, sets the new one, and returns an {@link AutoCloseable} that restores the prior
 * value (or removes the binding if there was none). Use with try-with-resources.
 */
final class ThreadLocalScope {

    private ThreadLocalScope() {}

    static <T> AutoCloseable install(InheritableThreadLocal<T> tl, @Nullable T value) {
        var previous = tl.get();
        if (value == null) {
            tl.remove();
        } else {
            tl.set(value);
        }
        return () -> {
            if (previous == null) {
                tl.remove();
            } else {
                tl.set(previous);
            }
        };
    }
}
