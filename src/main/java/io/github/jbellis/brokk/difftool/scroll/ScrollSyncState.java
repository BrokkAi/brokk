package io.github.jbellis.brokk.difftool.scroll;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe state management for scroll synchronization.
 * Tracks user scrolling state, programmatic scrolling, and timing information
 * to prevent race conditions and scroll jumping issues.
 */
public final class ScrollSyncState
{
    private final AtomicBoolean isUserScrolling = new AtomicBoolean(false);
    private final AtomicBoolean isProgrammaticScroll = new AtomicBoolean(false);
    private final AtomicBoolean hasPendingScroll = new AtomicBoolean(false);
    private final AtomicLong lastUserScrollTime = new AtomicLong(0);

    /**
     * Records that a user scroll event occurred.
     */
    public void recordUserScroll()
    {
        isUserScrolling.set(true);
        lastUserScrollTime.set(System.currentTimeMillis());
    }

    /**
     * Clears the user scrolling state.
     */
    public void clearUserScrolling()
    {
        isUserScrolling.set(false);
    }

    /**
     * Sets whether a programmatic scroll is in progress.
     */
    public void setProgrammaticScroll(boolean inProgress)
    {
        isProgrammaticScroll.set(inProgress);
    }

    /**
     * Sets whether there is a pending scroll operation.
     */
    public void setPendingScroll(boolean pending)
    {
        hasPendingScroll.set(pending);
    }

    /**
     * Atomically checks and clears the pending scroll flag.
     * @return true if there was a pending scroll, false otherwise
     */
    public boolean getAndClearPendingScroll()
    {
        return hasPendingScroll.getAndSet(false);
    }

    /**
     * Checks if user is currently scrolling.
     */
    public boolean isUserScrolling()
    {
        return isUserScrolling.get();
    }

    /**
     * Checks if a programmatic scroll is in progress.
     */
    public boolean isProgrammaticScroll()
    {
        return isProgrammaticScroll.get();
    }

    /**
     * Checks if there is a pending scroll operation.
     */
    public boolean hasPendingScroll()
    {
        return hasPendingScroll.get();
    }

    /**
     * Gets the time since the last user scroll in milliseconds.
     */
    public long getTimeSinceLastUserScroll()
    {
        long lastTime = lastUserScrollTime.get();
        return lastTime == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastTime;
    }

    /**
     * Checks if user scrolling is active within the specified time window.
     * @param windowMs time window in milliseconds
     * @return true if user scrolled within the window
     */
    public boolean isUserScrollingWithin(long windowMs)
    {
        return isUserScrolling() || getTimeSinceLastUserScroll() < windowMs;
    }

    /**
     * Result of checking whether scroll sync should be suppressed.
     */
    public record SyncSuppressionResult(boolean shouldSuppress, @Nullable String reason)
    {
        public static SyncSuppressionResult allow()
        {
            return new SyncSuppressionResult(false, null);
        }

        public static SyncSuppressionResult suppress(String reason)
        {
            return new SyncSuppressionResult(true, reason);
        }
    }

    /**
     * Comprehensive check for whether scroll synchronization should be suppressed.
     * @param userScrollWindowMs time window for considering user scrolling active
     * @return result indicating whether to suppress and why
     */
    public SyncSuppressionResult shouldSuppressSync(long userScrollWindowMs)
    {
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