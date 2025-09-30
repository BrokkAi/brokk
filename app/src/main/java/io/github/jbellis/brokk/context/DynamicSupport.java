package io.github.jbellis.brokk.context;

import io.github.jbellis.brokk.util.ComputedValue;
import java.time.Duration;
import java.util.Optional;

/**
 * Utilities for rendering or waiting on DynamicFragment values without blocking the EDT.
 */
public final class DynamicSupport {
    private DynamicSupport() {}

    /**
     * Non-blocking. If the value is available, returns it; otherwise returns the provided placeholder.
     */
    public static String renderNowOr(String placeholder, ComputedValue<String> cv) {
        return cv.tryGet().orElse(placeholder);
    }

    /**
     * Best-effort bounded wait (never blocks the Swing EDT). Returns Optional.empty() on timeout or failure.
     */
    public static Optional<String> await(Duration timeout, ComputedValue<String> cv) {
        return cv.await(timeout);
    }
}
