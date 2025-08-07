package io.github.jbellis.brokk.difftool.scroll;

import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive throttling strategy that dynamically switches between immediate
 * and frame-based throttling modes based on file complexity and scroll performance.
 *
 * This strategy optimizes the user experience by:
 * - Using immediate mode for simple files (best responsiveness)
 * - Switching to frame-based mode for complex files (prevents UI lag)
 * - Monitoring real-time performance to adapt dynamically
 */
public class AdaptiveThrottlingStrategy {
    private static final Logger logger = LogManager.getLogger(AdaptiveThrottlingStrategy.class);

    // Current throttling mode
    public enum ThrottlingMode {
        IMMEDIATE("Immediate - No throttling"),
        FRAME_BASED("Frame-based - Adaptive throttling");

        private final String description;

        ThrottlingMode(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private volatile ThrottlingMode currentMode = ThrottlingMode.IMMEDIATE;

    // File complexity metrics
    private int totalLines = 0;
    private int totalDeltas = 0;

    // Performance tracking
    private final AtomicLong lastMappingDuration = new AtomicLong(0);

    // Rapid scroll detection tracking
    private final AtomicLong lastEventTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger recentEventCount = new AtomicInteger(0);

    // Mode switch hysteresis to prevent oscillation
    private static final int MODE_SWITCH_COOLDOWN_MS = 2000;
    private volatile long lastModeSwitchTime = 0;

    /**
     * Initialize the strategy with file complexity metrics.
     */
    public void initialize(int totalLines, int totalDeltas) {
        this.totalLines = totalLines;
        this.totalDeltas = totalDeltas;

        // Initial mode determination based on static complexity
        ThrottlingMode initialMode = determineInitialMode();
        if (initialMode != currentMode) {
            switchMode(initialMode, "Initial complexity assessment");
        }
    }

    /**
     * Determine the initial throttling mode based on file complexity.
     */
    private ThrottlingMode determineInitialMode() {
        // Check static complexity thresholds
        if (totalLines > PerformanceConstants.ADAPTIVE_MODE_LINE_THRESHOLD) {
            return ThrottlingMode.FRAME_BASED;
        }

        if (totalDeltas >= PerformanceConstants.ADAPTIVE_MODE_DELTA_THRESHOLD) {
            return ThrottlingMode.FRAME_BASED;
        }

        // Default to immediate mode for simple files
        return ThrottlingMode.IMMEDIATE;
    }

    /**
     * Record a scroll event and adapt the throttling mode if needed.
     */
    public void recordScrollEvent(long mappingDurationMs) {
        lastMappingDuration.set(mappingDurationMs);

        // Update rapid scroll detection window
        updateRapidScrollWindow();

        // Check if we should switch modes based on performance
        evaluateAndAdaptMode();
    }

    /**
     * Update tracking for rapid scroll detection.
     */
    private void updateRapidScrollWindow() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastEvent = currentTime - lastEventTime.get();

        // Update last event time
        lastEventTime.set(currentTime);

        // If events are coming at reasonable intervals (not too slow),
        // increment the recent event count
        if (timeSinceLastEvent < 500) { // Less than 500ms between events = active scrolling
            recentEventCount.incrementAndGet();
        } else {
            // Reset counter for very slow events (> 500ms apart)
            recentEventCount.set(1);
        }
    }

    /**
     * Evaluate current performance and switch modes if necessary.
     */
    private void evaluateAndAdaptMode() {
        // Don't switch modes too frequently
        if (System.currentTimeMillis() - lastModeSwitchTime < MODE_SWITCH_COOLDOWN_MS) {
            return;
        }

        // Get current metrics
        long currentMappingDuration = lastMappingDuration.get();
        int eventsPerSecond = calculateEventsPerSecond();

        // Determine if we should switch modes
        var shouldUseFrameMode = false;
        var reason = (String) null;

        // Check performance threshold
        if (currentMappingDuration > PerformanceConstants.ADAPTIVE_MODE_PERFORMANCE_THRESHOLD_MS) {
            shouldUseFrameMode = true;
            reason = String.format("Slow mapping performance (%dms > %dms threshold)",
                                 currentMappingDuration,
                                 PerformanceConstants.ADAPTIVE_MODE_PERFORMANCE_THRESHOLD_MS);
        }

        // Check rapid scrolling
        if (eventsPerSecond > PerformanceConstants.ADAPTIVE_MODE_RAPID_SCROLL_THRESHOLD) {
            shouldUseFrameMode = true;
            reason = String.format("Rapid scrolling detected (%d events/sec > %d threshold)",
                                 eventsPerSecond,
                                 PerformanceConstants.ADAPTIVE_MODE_RAPID_SCROLL_THRESHOLD);
        }

        // Switch mode if needed
        ThrottlingMode targetMode = shouldUseFrameMode ? ThrottlingMode.FRAME_BASED : ThrottlingMode.IMMEDIATE;
        if (targetMode != currentMode) {
            switchMode(targetMode, reason != null ? reason : "Performance metrics normalized");
        }
    }

    /**
     * Calculate the current scroll events per second rate.
     */
    private int calculateEventsPerSecond() {
        int currentEventCount = recentEventCount.get();

        // If we have multiple events in sequence, estimate rate
        if (currentEventCount >= 3) {
            // For sequences of 3+ events, estimate based on recent activity
            if (currentEventCount > 10) {
                // Many events suggest rapid scrolling
                return Math.min(currentEventCount * 3, 80);
            } else {
                // Moderate number of events - give a reasonable rate
                return Math.max(currentEventCount, 5);
            }
        }

        // For very few events, return 0
        return 0;
    }

    /**
     * Switch to a new throttling mode.
     * The caller is responsible for acting on the mode change.
     */
    private void switchMode(ThrottlingMode newMode, String reason) {
        ThrottlingMode oldMode = currentMode;
        currentMode = newMode;
        lastModeSwitchTime = System.currentTimeMillis();

        logger.debug("Adaptive throttling switched from {} to {}: {}",
                   oldMode.getDescription(), newMode.getDescription(), reason);
    }

    /**
     * Get the current throttling mode.
     */
    public ThrottlingMode getCurrentMode() {
        return currentMode;
    }

    /**
     * Get current performance metrics for monitoring.
     */
    public AdaptiveMetrics getMetrics() {
        return new AdaptiveMetrics(
            currentMode,
            totalLines,
            totalDeltas,
            lastMappingDuration.get(),
            calculateEventsPerSecond(),
            System.currentTimeMillis() - lastModeSwitchTime
        );
    }

    /**
     * Reset the strategy (useful for testing).
     */
    public void reset() {
        currentMode = ThrottlingMode.IMMEDIATE;
        totalLines = 0;
        totalDeltas = 0;
        lastMappingDuration.set(0);
        lastEventTime.set(System.currentTimeMillis());
        recentEventCount.set(0);
        lastModeSwitchTime = 0;
    }

    /**
     * Force a specific mode (for testing or manual override).
     */
    public void forceMode(ThrottlingMode mode, String reason) {
        if (mode != currentMode) {
            switchMode(mode, "Manual override: " + reason);
        }
    }

    /**
     * Record for adaptive throttling metrics.
     */
    public record AdaptiveMetrics(
        ThrottlingMode currentMode,
        int totalLines,
        int totalDeltas,
        long lastMappingDurationMs,
        int eventsPerSecond,
        long timeSinceLastSwitch
    ) {
        public String getSummary() {
            return String.format("Mode: %s | Lines: %d | Deltas: %d | Mapping: %dms | Rate: %d/s",
                               currentMode.name(), totalLines, totalDeltas,
                               lastMappingDurationMs, eventsPerSecond);
        }
    }
}