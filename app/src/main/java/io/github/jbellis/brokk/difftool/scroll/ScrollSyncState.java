package io.github.jbellis.brokk.difftool.scroll;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe state management for scroll synchronization.
 * Tracks user scrolling state, programmatic scrolling, and timing information
 * to prevent race conditions and scroll jumping issues.
 */
public final class ScrollSyncState {
    private final AtomicBoolean isUserScrolling = new AtomicBoolean(false);
    private final AtomicBoolean isProgrammaticScroll = new AtomicBoolean(false);
    private final AtomicBoolean hasPendingScroll = new AtomicBoolean(false);
    private final AtomicLong lastUserScrollTime = new AtomicLong(0);

    public void recordUserScroll() {
        isUserScrolling.set(true);
        lastUserScrollTime.set(System.currentTimeMillis());
    }

    public void clearUserScrolling() {
        isUserScrolling.set(false);
    }

    public void setProgrammaticScroll(boolean inProgress) {
        isProgrammaticScroll.set(inProgress);
    }

    public void setPendingScroll(boolean pending) {
        hasPendingScroll.set(pending);
    }

    /**
     * Atomically checks and clears the pending scroll flag.
     * @return true if there was a pending scroll, false otherwise
     */
    public boolean getAndClearPendingScroll() {
        return hasPendingScroll.getAndSet(false);
    }

    public boolean isUserScrolling() {
        return isUserScrolling.get();
    }

    public boolean isProgrammaticScroll() {
        return isProgrammaticScroll.get();
    }

    public boolean hasPendingScroll() {
        return hasPendingScroll.get();
    }

    public long getTimeSinceLastUserScroll() {
        long lastTime = lastUserScrollTime.get();
        return lastTime == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastTime;
    }

    /**
     * Checks if user scrolling is active within the specified time window.
     * @param windowMs time window in milliseconds
     * @return true if user scrolled within the window
     */
    public boolean isUserScrollingWithin(long windowMs) {
        return isUserScrolling() || getTimeSinceLastUserScroll() < windowMs;
    }

    /**
     * Result of checking whether scroll sync should be suppressed.
     */
    public record SyncSuppressionResult(boolean shouldSuppress, @Nullable String reason) {
        public static SyncSuppressionResult allow() {
            return new SyncSuppressionResult(false, null);
        }

        public static SyncSuppressionResult suppress(String reason) {
            return new SyncSuppressionResult(true, reason);
        }
    }

    /**
     * Comprehensive check for whether scroll synchronization should be suppressed.
     * @param userScrollWindowMs time window for considering user scrolling active
     */
    public SyncSuppressionResult shouldSuppressSync(long userScrollWindowMs) {
        if (isProgrammaticScroll()) {
            return SyncSuppressionResult.suppress("programmatic scroll in progress");
        }

        if (isUserScrollingWithin(userScrollWindowMs)) {
            long timeSince = getTimeSinceLastUserScroll();
            return SyncSuppressionResult.suppress("user scrolling active (" + timeSince + "ms ago)");
        }

        return SyncSuppressionResult.allow();
    }
}