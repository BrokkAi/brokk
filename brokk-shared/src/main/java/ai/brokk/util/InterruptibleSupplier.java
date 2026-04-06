package ai.brokk.util;

import org.jspecify.annotations.NullMarked;

/**
 * A functional interface that represents a supplier of results that can throw an {@link InterruptedException}.
 *
 * @param <T> the type of results supplied by this supplier
 */
@NullMarked
@FunctionalInterface
public interface InterruptibleSupplier<T> {
    /**
     * Gets a result.
     *
     * @return a result
     * @throws InterruptedException if interrupted while computing the result
     */
    T get() throws InterruptedException;
}
