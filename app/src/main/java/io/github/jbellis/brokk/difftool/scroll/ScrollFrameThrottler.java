package io.github.jbellis.brokk.difftool.scroll;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frame-based throttling utility for scroll operations that provides immediate
 * response for single events while limiting execution frequency during rapid scrolling.
 *
 * Unlike traditional debouncing which delays all events, frame-based throttling:
 * - Executes the first event immediately (0ms delay)
 * - During rapid scrolling, executes at most once per frame interval
 * - Only executes the latest action when the frame completes
 *
 * This approach provides excellent user experience with immediate feedback for
 * single scroll events while maintaining performance during rapid scrolling.
 */
public final class ScrollFrameThrottler {
    private static final Logger logger = LogManager.getLogger(ScrollFrameThrottler.class);

    private final AtomicInteger frameIntervalMs = new AtomicInteger(16); // Default 60fps
    private @Nullable Timer frameTimer;
    private @Nullable Runnable latestAction = null;
    private boolean hasEvent = false;
    private boolean frameActive = false;
    private boolean disposed = false;
    private final Object lock = new Object();

    // Performance metrics
    private final AtomicLong totalEvents = new AtomicLong(0);
    private final AtomicLong totalExecutions = new AtomicLong(0);
    private final AtomicLong lastExecutionTime = new AtomicLong(0);

    public ScrollFrameThrottler(int frameIntervalMs) {
        this.frameIntervalMs.set(frameIntervalMs);
    }

    /**
     * Submits an action for frame-based execution.
     *
     * @param action The action to execute
     */
    public void submit(Runnable action) {
        totalEvents.incrementAndGet();

        synchronized (lock) {
            if (disposed) {
                // Execute immediately but don't start framing
                executeAction(action);
                return;
            }

            if (!frameActive) {
                // Execute immediately and start framing
                executeAction(action);
                startFrameTimer();
            } else {
                // Queue for frame-end execution
                latestAction = action;
                hasEvent = true;
            }
        }
    }

    /**
     * Updates the frame rate interval. Changes take effect on the next frame.
     *
     * @param intervalMs Frame interval in milliseconds (min 1ms, max 1000ms)
     */
    public void setFrameRate(int intervalMs) {
        int clampedInterval = Math.max(1, Math.min(1000, intervalMs));
        int oldInterval = frameIntervalMs.getAndSet(clampedInterval);

        if (oldInterval != clampedInterval) {
            logger.debug("Frame rate changed from {}ms to {}ms ({} FPS)",
                       oldInterval, clampedInterval, 1000.0 / clampedInterval);
        }
    }

    /**
     * Gets the current frame rate interval in milliseconds.
     */
    public int getFrameRate() {
        return frameIntervalMs.get();
    }

    /**
     * Checks if a frame is currently active (waiting for frame boundary).
     */
    public boolean isFrameActive() {
        synchronized (lock) {
            return frameActive;
        }
    }

    /**
     * Gets the total number of events submitted.
     */
    public long getTotalEvents() {
        return totalEvents.get();
    }

    /**
     * Gets the total number of actions executed.
     */
    public long getTotalExecutions() {
        return totalExecutions.get();
    }

    /**
     * Gets the time of the last execution in milliseconds since epoch.
     */
    public long getLastExecutionTime() {
        return lastExecutionTime.get();
    }

    /**
     * Calculates the throttling efficiency as a percentage.
     *
     * @return Percentage of events that were throttled (not executed immediately)
     */
    public double getThrottlingEfficiency() {
        long events = totalEvents.get();
        long executions = totalExecutions.get();

        if (events == 0) {
            return 0.0;
        }

        return (double) (events - executions) / events * 100.0;
    }

    /**
     * Resets all performance metrics.
     */
    public void resetMetrics() {
        totalEvents.set(0);
        totalExecutions.set(0);
        lastExecutionTime.set(0);
        logger.debug("Performance metrics reset");
    }

    /**
     * Cancels any pending frame timer and queued actions.
     */
    public void cancel() {
        synchronized (lock) {
            stopFrameTimer();
            latestAction = null;
            hasEvent = false;
            frameActive = false;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Frame throttler cancelled");
        }
    }

    /**
     * Stops the throttler and cleans up resources.
     */
    public void dispose() {
        synchronized (lock) {
            disposed = true;
            stopFrameTimer();
            latestAction = null;
            hasEvent = false;
            frameActive = false;
        }
        logger.debug("Frame throttler disposed");
    }

    private void startFrameTimer() {
        assert Thread.holdsLock(lock);

        frameActive = true;

        if (frameTimer != null) {
            frameTimer.stop();
        }

        frameTimer = new Timer(frameIntervalMs.get(), this::onFrameEnd);
        frameTimer.setRepeats(false);
        frameTimer.start();
    }

    private void stopFrameTimer() {
        assert Thread.holdsLock(lock);

        if (frameTimer != null) {
            frameTimer.stop();
            frameTimer = null;
        }
        frameActive = false;
    }

    private void onFrameEnd(ActionEvent e) {
        synchronized (lock) {
            if (hasEvent && latestAction != null) {
                var actionToExecute = latestAction;
                latestAction = null;
                hasEvent = false;

                // Execute the latest action
                executeAction(actionToExecute);

                // Continue framing if we expect more events soon
                startFrameTimer();
            } else {
                // No pending events, stop framing
                stopFrameTimer();
            }
        }
    }

    private void executeAction(Runnable action) {
        try {
            action.run();
            totalExecutions.incrementAndGet();
            lastExecutionTime.set(System.currentTimeMillis());
        } catch (Exception e) {
            logger.error("Error executing throttled action", e);
        }
    }
}