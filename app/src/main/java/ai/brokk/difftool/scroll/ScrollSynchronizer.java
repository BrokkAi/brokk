package ai.brokk.difftool.scroll;

import ai.brokk.difftool.performance.PerformanceConstants;
import ai.brokk.difftool.ui.BufferDiffPanel;
import ai.brokk.difftool.ui.FilePanel;
import ai.brokk.gui.SwingUtil;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.Patch;
import java.awt.Point;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Synchronizes the vertical/horizontal scrolling between the left and right FilePanel. Also provides small utility
 * methods for scrolling to line or to a specific delta.
 */
public class ScrollSynchronizer {
    /** Defines the context and behavior for scroll operations. */
    public enum ScrollMode {
        CONTINUOUS, // Mouse wheel, continuous scrolling - minimal overhead
        NAVIGATION, // Explicit jumps, diff navigation - full highlighting
        SEARCH // Search results - immediate highlighting
    }

    private static final Logger logger = LogManager.getLogger(ScrollSynchronizer.class);
    private static final Logger performanceLogger = LogManager.getLogger("scroll.performance");

    private final BufferDiffPanel diffPanel;
    private final FilePanel filePanelLeft;
    private final FilePanel filePanelRight;

    private @Nullable AdjustmentListener verticalAdjustmentListener;

    // State management and throttling utilities
    private final ScrollSyncState syncState;
    private final ScrollFrameThrottler frameThrottler;
    private final AdaptiveThrottlingStrategy adaptiveStrategy;

    // Performance monitoring
    private final ScrollPerformanceMonitor performanceMonitor;

    // Line mapping algorithms
    private final LineMapper lineMapper;

    // Reusable timers to avoid frequent instantiation
    private final Timer programmaticScrollResetTimer;
    private final Timer navigationResetTimer;
    private final Timer enableSyncTimer;

    public ScrollSynchronizer(BufferDiffPanel diffPanel, FilePanel filePanelLeft, FilePanel filePanelRight) {
        this.diffPanel = diffPanel;
        this.filePanelLeft = filePanelLeft;
        this.filePanelRight = filePanelRight;

        // Initialize state management utilities
        this.syncState = new ScrollSyncState();
        this.frameThrottler = new ScrollFrameThrottler(PerformanceConstants.SCROLL_FRAME_RATE_MS);
        this.adaptiveStrategy = new AdaptiveThrottlingStrategy();
        this.performanceMonitor = new ScrollPerformanceMonitor();
        this.lineMapper = new LineMapper();

        // Initialize reusable timers
        this.programmaticScrollResetTimer = new Timer(25, e -> syncState.setProgrammaticScroll(false));
        this.programmaticScrollResetTimer.setRepeats(false);

        this.navigationResetTimer = new Timer(PerformanceConstants.NAVIGATION_RESET_DELAY_MS, e -> {
            // Reset navigation flags for both panels
            filePanelLeft.setNavigatingToDiff(false);
            filePanelRight.setNavigatingToDiff(false);
        });
        this.navigationResetTimer.setRepeats(false);

        this.enableSyncTimer = new Timer(100, e -> {
            logger.trace("Re-enabling scroll sync after navigation");
            syncState.setProgrammaticScroll(false);
        });
        this.enableSyncTimer.setRepeats(false);

        init();
    }

    /**
     * Constructor for testing that skips initialization requiring real UI components. Package-private visibility for
     * test access only.
     */
    ScrollSynchronizer(BufferDiffPanel diffPanel, FilePanel filePanelLeft, FilePanel filePanelRight, boolean skipInit) {
        this.diffPanel = diffPanel;
        this.filePanelLeft = filePanelLeft;
        this.filePanelRight = filePanelRight;

        // Initialize state management utilities
        this.syncState = new ScrollSyncState();
        this.frameThrottler = new ScrollFrameThrottler(PerformanceConstants.SCROLL_FRAME_RATE_MS);
        this.adaptiveStrategy = new AdaptiveThrottlingStrategy();
        this.performanceMonitor = new ScrollPerformanceMonitor();
        this.lineMapper = new LineMapper();

        // Initialize reusable timers
        this.programmaticScrollResetTimer = new Timer(25, e -> syncState.setProgrammaticScroll(false));
        this.programmaticScrollResetTimer.setRepeats(false);

        this.navigationResetTimer = new Timer(PerformanceConstants.NAVIGATION_RESET_DELAY_MS, e -> {
            // Reset navigation flags for both panels
            filePanelLeft.setNavigatingToDiff(false);
            filePanelRight.setNavigatingToDiff(false);
        });
        this.navigationResetTimer.setRepeats(false);

        this.enableSyncTimer = new Timer(100, e -> {
            logger.trace("Re-enabling scroll sync after navigation");
            syncState.setProgrammaticScroll(false);
        });
        this.enableSyncTimer.setRepeats(false);

        // Skip init() if requested (for testing line mapping algorithm only)
        if (!skipInit) {
            init();
        }
    }

    private void init() {
        // Sync horizontal scrollbars by sharing the same model.
        var barLeftH = filePanelLeft.getScrollPane().getHorizontalScrollBar();
        var barRightH = filePanelRight.getScrollPane().getHorizontalScrollBar();
        barRightH.setModel(barLeftH.getModel());

        // Initialize horizontal scroll to show left side (line numbers)
        SwingUtilities.invokeLater(() -> {
            barLeftH.setValue(0);
        });

        // Sync vertical:
        var barLeftV = filePanelLeft.getScrollPane().getVerticalScrollBar();
        var barRightV = filePanelRight.getScrollPane().getVerticalScrollBar();
        var listener = getVerticalAdjustmentListener();
        barRightV.addAdjustmentListener(listener);
        barLeftV.addAdjustmentListener(listener);
    }

    private AdjustmentListener getVerticalAdjustmentListener() {
        if (verticalAdjustmentListener == null) {
            verticalAdjustmentListener = new AdjustmentListener() {
                private long lastScrollTime = 0;
                private static final long SCROLL_THROTTLE_MS = 16; // 60 FPS max

                @Override
                public void adjustmentValueChanged(AdjustmentEvent e) {
                    // Performance optimization: throttle scroll events to prevent excessive processing
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastScrollTime < SCROLL_THROTTLE_MS) {
                        return; // Skip this event to reduce processing load
                    }
                    lastScrollTime = currentTime;

                    var leftV = filePanelLeft.getScrollPane().getVerticalScrollBar();
                    var leftScrolled = (e.getSource() == leftV);

                    // Skip if this is a programmatic scroll we initiated
                    if (syncState.isProgrammaticScroll()) {
                        return;
                    }

                    // Suppress scroll sync if either panel is actively typing to prevent flickering
                    if (filePanelLeft.isActivelyTyping() || filePanelRight.isActivelyTyping()) {
                        return;
                    }

                    // Only process on value adjusting to reduce noise
                    if (e.getValueIsAdjusting()) {
                        return;
                    }

                    // Track user scrolling to prevent conflicts
                    syncState.recordUserScroll();

                    // Use configured throttling mode for optimal performance and user experience
                    scheduleScrollSync(() -> syncScroll(leftScrolled));

                    // Reset the scrolling state so subsequent events are processed
                    syncState.clearUserScrolling();
                }

                private void syncScroll(boolean leftScrolled) {
                    syncState.setProgrammaticScroll(true);
                    try {
                        scroll(leftScrolled);
                    } finally {
                        // Optimized reset timing for better responsiveness
                        programmaticScrollResetTimer.restart();
                    }
                }
            };
        }
        return verticalAdjustmentListener;
    }

    /**
     * If the left side scrolled, we compute which line is centered and map it to the equivalent line in the right side.
     * If the right side scrolled, we do the reverse.
     */
    private void scroll(boolean leftScrolled) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> scroll(leftScrolled));
            return;
        }

        // Use programmatic scroll flag to prevent infinite recursion
        // No need to remove/re-add listeners since isProgrammaticScroll flag handles this
        performScroll(leftScrolled);
    }

    private void performScroll(boolean leftScrolled) {
        long startTime = System.currentTimeMillis();

        // Additional check: don't scroll if either panel is typing
        if (filePanelLeft.isActivelyTyping() || filePanelRight.isActivelyTyping()) {
            return;
        }

        // We are handling a scroll event; the re-entrancy guard in syncScroll()
        // already provides proper throttling and prevents infinite loops
        syncState.clearUserScrolling();

        var patch = diffPanel.getPatch();
        if (patch == null) {
            return;
        }

        // Initialize adaptive strategy if this is the first time we see this patch
        initializeAdaptiveStrategyIfNeeded(patch);

        var fp1 = leftScrolled ? filePanelLeft : filePanelRight;
        var fp2 = leftScrolled ? filePanelRight : filePanelLeft;

        // Which line is roughly in the center of fp1?
        int line = getCurrentLineCenter(fp1);

        // Attempt naive line mapping using deltas
        int mappedLine = approximateLineMapping(patch, line, leftScrolled);

        // Log performance metrics
        long mappingDuration = System.currentTimeMillis() - startTime;
        performanceMonitor.recordScrollEvent(mappingDuration, patch.getDeltas().size(), line, mappedLine);

        // Record adaptive strategy metrics
        if (PerformanceConstants.ENABLE_ADAPTIVE_THROTTLING) {
            adaptiveStrategy.recordScrollEvent(mappingDuration);
        }

        if (mappingDuration > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS) {
            performanceLogger.warn(
                    "Slow scroll mapping: {}ms for {} deltas, line {}->{}",
                    mappingDuration,
                    patch.getDeltas().size(),
                    line,
                    mappedLine);
        }

        // Use CONTINUOUS mode for regular scroll synchronization to avoid navigation overhead
        scrollToLine(fp2, mappedLine, ScrollMode.CONTINUOUS);

        // Log total scroll sync duration
        long totalDuration = System.currentTimeMillis() - startTime;
        if (totalDuration > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS) {
            performanceLogger.warn("Slow scroll sync: {}ms total", totalDuration);
        }
    }

    /**
     * Enhanced line mapping with O(log n) performance and improved accuracy. Delegates to LineMapper for all
     * algorithmic operations.
     */
    private int approximateLineMapping(Patch<String> patch, int line, boolean fromOriginal) {
        return lineMapper.mapLine(patch, line, fromOriginal);
    }

    /** Determine which line is in the vertical center of the FilePanel's visible region. */
    private int getCurrentLineCenter(FilePanel fp) {
        assert SwingUtilities.isEventDispatchThread() : "getCurrentLineCenter must be called on EDT";
        var editor = fp.getEditor();
        var viewport = fp.getScrollPane().getViewport();
        var p = viewport.getViewPosition();
        // We shift p.y by half the viewport height to approximate center
        p.y += (viewport.getSize().height / 2);

        int offset = editor.viewToModel2D(p);
        var bd = fp.getBufferDocument();
        return bd == null ? 0 : bd.getLineForOffset(offset);
    }

    public void scrollToLine(FilePanel fp, int line) {
        // Default to navigation mode for backward compatibility
        scrollToLine(fp, line, ScrollMode.NAVIGATION);
    }

    public void scrollToLine(FilePanel fp, int line, ScrollMode mode) {
        scrollToLineInternal(fp, line, mode);
    }

    private void scrollToLineInternal(FilePanel fp, int line, ScrollMode mode) {
        var bd = fp.getBufferDocument();
        if (bd == null) {
            return;
        }
        var offset = bd.getOffsetForLine(line);
        if (offset < 0) {
            return;
        }

        // Only set navigation flag for explicit navigation or search, not continuous scrolling
        if (mode == ScrollMode.NAVIGATION || mode == ScrollMode.SEARCH) {
            fp.setNavigatingToDiff(true);
        }

        // Use invokeLater to ensure we're on EDT and that any pending layout is complete
        SwingUtilities.invokeLater(() -> {
            try {
                var viewport = fp.getScrollPane().getViewport();
                var editor = fp.getEditor();
                var rect = SwingUtil.modelToView(editor, offset);
                if (rect == null) {
                    return;
                }

                // Try to center the line, but with better handling for edge lines
                int originalY = rect.y;
                int viewportHeight = viewport.getSize().height;
                int normalPadding = Math.min(100, viewportHeight / 8); // Normal padding

                // Calculate document bounds
                var scrollPane = fp.getScrollPane();
                var maxY = scrollPane.getVerticalScrollBar().getMaximum() - viewportHeight;

                // Try to center, but handle edge cases specially
                int centeredY = originalY - (viewportHeight / 2);
                int finalY;

                if (centeredY < 0) {
                    // For lines very close to the top, ensure they're positioned optimally
                    // Instead of scrolling to 0, position the line at 1/3 from top for better visibility
                    finalY = Math.max(0, originalY - (viewportHeight / 3));
                } else if (centeredY > maxY) {
                    // For lines very close to the bottom, scroll to the very bottom
                    finalY = maxY;
                } else {
                    // For other lines, use normal centering with padding
                    finalY = Math.max(normalPadding, centeredY);
                }

                // Final bounds check (should rarely be needed now)
                finalY = Math.max(0, Math.min(finalY, maxY));

                var p = new Point(rect.x, finalY);

                // Set viewport position
                viewport.setViewPosition(p);

                // Force a repaint to ensure the scroll is visible
                viewport.repaint();

                // Only invalidate viewport cache if not actively typing to prevent flicker
                if (!fp.isActivelyTyping()) {
                    fp.invalidateViewportCache();
                }

                // Trigger immediate redisplay to show highlights
                fp.reDisplay();

                // Reset navigation flag after a minimal delay to allow highlighting to complete
                // Only reset if we set it (for navigation/search modes)
                if (mode == ScrollMode.NAVIGATION || mode == ScrollMode.SEARCH) {
                    navigationResetTimer.restart();
                }

            } catch (BadLocationException ex) {
                logger.error("scrollToLine error for line {}: {}", line, ex.getMessage());
                // Only reset flag on error if we set it
                if (mode == ScrollMode.NAVIGATION || mode == ScrollMode.SEARCH) {
                    // Reset both panels immediately on error
                    filePanelLeft.setNavigatingToDiff(false);
                    filePanelRight.setNavigatingToDiff(false);
                }
            }
        });
    }

    public void scrollToLineAndSync(FilePanel sourcePanel, int line) {
        var leftSide = sourcePanel == filePanelLeft;
        // First, scroll the panel where the search originated using SEARCH mode for immediate highlighting
        scrollToLine(sourcePanel, line, ScrollMode.SEARCH);

        // Determine the counterpart panel
        var targetPanel = leftSide ? filePanelRight : filePanelLeft;

        // Compute the best-effort mapped line on the opposite side using existing logic
        int mappedLine;
        var patch = diffPanel.getPatch();
        if (patch == null) {
            mappedLine = line; // fall back to same line number
        } else {
            mappedLine = approximateLineMapping(patch, line, leftSide);

            // Clamp to document bounds for safety
            var targetDoc = targetPanel.getBufferDocument();
            if (targetDoc != null) {
                int maxLine = Math.max(0, targetDoc.getNumberOfLines() - 1);
                mappedLine = Math.max(0, Math.min(mappedLine, maxLine));
            }
        }

        // Finally, scroll the counterpart panel using CONTINUOUS mode to avoid navigation overhead
        scrollToLine(targetPanel, mappedLine, ScrollMode.CONTINUOUS);
    }

    /**
     * Called by BufferDiffPanel when the user picks a specific delta. We attempt to show the original chunk in the left
     * side, then scroll the right side.
     */
    public void showDelta(AbstractDelta<String> delta) {
        logger.trace(
                "showDelta called for delta at position {}", delta.getSource().getPosition());

        // Disable scroll sync during navigation to prevent feedback loops
        syncState.setProgrammaticScroll(true);

        try {
            // Get the source and target chunks from the delta
            var source = delta.getSource();
            var target = delta.getTarget();

            int sourceLine = source.getPosition();
            int targetLine = target.getPosition();

            // Calculate chunk centers for better visual alignment
            int sourceCenterLine = calculateChunkCenter(source);
            int targetCenterLine = calculateChunkCenter(target);

            logger.trace(
                    "Navigation: delta chunks - source: pos={} size={} center={}, target: pos={} size={} center={}",
                    sourceLine,
                    source.size(),
                    sourceCenterLine,
                    targetLine,
                    target.size(),
                    targetCenterLine);

            // Determine the right panel scroll line with enhanced centering logic
            int rightPanelScrollLine = targetCenterLine;

            if (target.size() > 0) {
                logger.trace("Navigation: delta has target content, using center line {}", targetCenterLine);
            } else {
                logger.trace("Navigation: DELETE delta, target position {}", targetLine);
                // For DELETE deltas, use the target position but apply the same near-top adjustment
                rightPanelScrollLine = targetLine;
                if (targetLine <= 8) {
                    rightPanelScrollLine = Math.max(targetLine + 3, 10);
                    logger.trace(
                            "Navigation: DELETE delta near top, adjusting scroll to line {} for better visibility",
                            rightPanelScrollLine);
                }
            }

            // Scroll both panels
            scrollToLine(filePanelLeft, sourceCenterLine, ScrollMode.NAVIGATION);
            logger.trace("Navigation: scrolled LEFT panel to source center line {}", sourceCenterLine);

            scrollToLine(filePanelRight, rightPanelScrollLine, ScrollMode.NAVIGATION);
            logger.trace("Navigation: scrolled RIGHT panel to line {}", rightPanelScrollLine);
        } finally {
            // Re-enable sync after a short delay to allow scroll operations to complete
            enableSyncTimer.restart();
        }

        // Trigger immediate redisplay on both panels to ensure highlights appear
        filePanelLeft.reDisplay();
        filePanelRight.reDisplay();

        logger.trace("showDelta completed - both panels scrolled with enhanced centering");
    }

    /**
     * Calculate the center line of a chunk for optimal visual alignment. Handles different chunk sizes and edge cases.
     *
     * @param chunk the source or target chunk from a delta
     * @return the line number representing the visual center of the chunk
     */
    private int calculateChunkCenter(Chunk<String> chunk) {
        int position = chunk.getPosition();
        int size = chunk.size();

        if (size <= 0) {
            // Empty chunk (typically for INSERT/DELETE operations)
            return position;
        }

        if (size == 1) {
            // Single line chunk - the line itself is the center
            return position;
        }

        // Multi-line chunk - calculate the middle line
        // For even-sized chunks, this will bias toward the earlier line
        // (e.g., for a 4-line chunk, we use line 1 instead of line 2)
        int centerOffset = (size - 1) / 2;
        int centerLine = position + centerOffset;

        logger.trace(
                "Chunk center calculation: position={}, size={}, centerOffset={}, centerLine={}",
                position,
                size,
                centerOffset,
                centerLine);

        return centerLine;
    }

    /**
     * Cleanup method to properly dispose of resources when the synchronizer is no longer needed. Should be called when
     * the BufferDiffPanel is being disposed to prevent memory leaks.
     */
    /**
     * Invalidate viewport cache for both synchronized panels. Since both panels are synchronized, when one needs cache
     * invalidation, both should be updated to maintain consistency.
     */
    public void invalidateViewportCacheForBothPanels() {
        filePanelLeft.invalidateViewportCache();
        filePanelRight.invalidateViewportCache();
    }

    /**
     * Schedule scroll synchronization using the configured throttling mode. Supports immediate execution, traditional
     * debouncing, frame-based throttling, or adaptive throttling.
     */
    private void scheduleScrollSync(Runnable syncAction) {
        // Validate configuration to prevent conflicts
        PerformanceConstants.validateScrollThrottlingConfig();

        if (PerformanceConstants.ENABLE_ADAPTIVE_THROTTLING) {
            // Adaptive throttling determines the best mode automatically
            var currentMode = adaptiveStrategy.getCurrentMode();
            if (currentMode == AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED) {
                frameThrottler.submit(syncAction);
            } else {
                // Immediate execution for simple files
                syncAction.run();
            }
        } else if (PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING) {
            frameThrottler.submit(syncAction);
        } else {
            // Immediate execution (no throttling)
            syncAction.run();
        }

        // Trigger any side effects that might be needed for UI updates
        // Note: Full metrics are recorded in performScroll() with actual values
        performanceMonitor.recordScrollEvent(0, 0, 0, 0);
    }

    /**
     * Update throttling configuration dynamically. Called when the debug panel changes throttling settings to apply
     * them immediately.
     */
    public void updateThrottlingConfiguration() {
        // Validate configuration
        var changed = PerformanceConstants.validateScrollThrottlingConfig();
        if (changed) {
            logger.info("Throttling configuration auto-corrected to prevent conflicts");
        }

        // Update frame throttler rate if changed
        frameThrottler.setFrameRate(PerformanceConstants.SCROLL_FRAME_RATE_MS);

        logger.info("Scroll throttling configuration updated: {}", PerformanceConstants.getCurrentScrollMode());
    }

    /** Get throttling performance metrics for the debug panel. */
    public ThrottlingMetrics getThrottlingMetrics() {
        return new ThrottlingMetrics(
                frameThrottler.getTotalEvents(),
                frameThrottler.getTotalExecutions(),
                frameThrottler.getThrottlingEfficiency(),
                frameThrottler.isFrameActive());
    }

    /**
     * Initialize the adaptive throttling strategy with file complexity metrics. This is called when a patch is first
     * encountered to set up optimal throttling.
     */
    private void initializeAdaptiveStrategyIfNeeded(Patch<String> patch) {
        if (!PerformanceConstants.ENABLE_ADAPTIVE_THROTTLING) {
            return;
        }

        // Calculate file complexity metrics
        int totalLines = Math.max(
                filePanelLeft.getBufferDocument() != null
                        ? filePanelLeft.getBufferDocument().getNumberOfLines()
                        : 0,
                filePanelRight.getBufferDocument() != null
                        ? filePanelRight.getBufferDocument().getNumberOfLines()
                        : 0);
        int totalDeltas = patch.getDeltas().size();

        // Initialize the strategy with these metrics
        adaptiveStrategy.initialize(totalLines, totalDeltas);
    }

    /** Get adaptive throttling metrics for the debug panel. */
    public AdaptiveThrottlingStrategy.AdaptiveMetrics getAdaptiveMetrics() {
        return adaptiveStrategy.getMetrics();
    }

    /** Get the adaptive throttling strategy (for testing and debugging). */
    public AdaptiveThrottlingStrategy getAdaptiveStrategy() {
        return adaptiveStrategy;
    }

    /**
     * Temporarily disable scroll synchronization during document operations. This prevents interference when applying
     * diff deltas that modify document content.
     *
     * @param inProgress true to disable sync, false to re-enable
     */
    void setProgrammaticScrollMode(boolean inProgress) {
        syncState.setProgrammaticScroll(inProgress);
    }

    /**
     * Creates an AutoCloseable resource that disables scroll synchronization for the duration of a try-with-resources
     * block. Synchronization is re-enabled via SwingUtilities.invokeLater when the block is exited.
     *
     * @return an AutoCloseable to manage the programmatic scroll state.
     */
    public AutoCloseable programmaticSection() {
        setProgrammaticScrollMode(true);
        return () -> SwingUtilities.invokeLater(() -> setProgrammaticScrollMode(false));
    }

    /** Check if a programmatic scroll operation is currently in progress. */
    public boolean isProgrammaticScroll() {
        return syncState.isProgrammaticScroll();
    }

    /** Record for throttling performance metrics. */
    public record ThrottlingMetrics(long totalEvents, long totalExecutions, double efficiency, boolean frameActive) {}

    public void dispose() {
        // Dispose throttling utilities to stop any pending timers
        frameThrottler.dispose();

        // Stop reusable timers
        programmaticScrollResetTimer.stop();
        navigationResetTimer.stop();
        enableSyncTimer.stop();

        // Remove adjustment listeners

        if (verticalAdjustmentListener != null) {
            var leftV = filePanelLeft.getScrollPane().getVerticalScrollBar();
            var rightV = filePanelRight.getScrollPane().getVerticalScrollBar();
            leftV.removeAdjustmentListener(verticalAdjustmentListener);
            rightV.removeAdjustmentListener(verticalAdjustmentListener);
            verticalAdjustmentListener = null;
        }

        // Dispose performance monitor
        performanceMonitor.dispose();
    }

    /** Get performance monitoring data. */
    public ScrollPerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    /** Performance monitoring class for scroll synchronization. */
    public static class ScrollPerformanceMonitor {
        private final AtomicLong totalScrollEvents = new AtomicLong();
        private final AtomicLong totalMappingDuration = new AtomicLong();
        private final AtomicLong totalDeltasProcessed = new AtomicLong();
        private final AtomicLong maxMappingDuration = new AtomicLong();
        private final AtomicInteger maxDeltasInSingleEvent = new AtomicInteger();
        private final Timer metricsTimer;

        public ScrollPerformanceMonitor() {
            // Log performance metrics every 5 minutes (less frequent)
            this.metricsTimer = new Timer(300000, e -> logPerformanceMetrics());
            this.metricsTimer.setRepeats(true);
            this.metricsTimer.start();
        }

        public void recordScrollEvent(long mappingDurationMs, int deltaCount, int originalLine, int mappedLine) {
            totalScrollEvents.incrementAndGet();
            totalMappingDuration.addAndGet(mappingDurationMs);
            totalDeltasProcessed.addAndGet(deltaCount);

            // Update maximums
            maxMappingDuration.updateAndGet(current -> Math.max(current, mappingDurationMs));
            maxDeltasInSingleEvent.updateAndGet(current -> Math.max(current, deltaCount));

            // Log individual slow events
            if (mappingDurationMs > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS) {
                performanceLogger.warn(
                        "Slow scroll event: {}ms for {} deltas, line mapping {}->{}",
                        mappingDurationMs,
                        deltaCount,
                        originalLine,
                        mappedLine);
            }
        }

        public double getAverageScrollTime() {
            long events = totalScrollEvents.get();
            return events > 0 ? (double) totalMappingDuration.get() / events : 0.0;
        }

        public double getAverageDeltasPerEvent() {
            long events = totalScrollEvents.get();
            return events > 0 ? (double) totalDeltasProcessed.get() / events : 0.0;
        }

        public long getTotalScrollEvents() {
            return totalScrollEvents.get();
        }

        public long getMaxMappingDuration() {
            return maxMappingDuration.get();
        }

        public int getMaxDeltasInSingleEvent() {
            return maxDeltasInSingleEvent.get();
        }

        private void logPerformanceMetrics() {
            long events = totalScrollEvents.get();
            if (events > 10) { // Only log if there's been significant activity
                double avgDuration = getAverageScrollTime();
                long maxDuration = getMaxMappingDuration();
                // Only log if there are performance concerns (slow average or max times)
                if (avgDuration > 5.0 || maxDuration > 50) {
                    performanceLogger.info(
                            "Scroll performance: {} events, avg {}ms, max {}ms",
                            events,
                            String.format("%.1f", avgDuration),
                            maxDuration);
                }
            }
        }

        public void dispose() {
            metricsTimer.stop();
            // Log final metrics
            logPerformanceMetrics();
        }

        public void reset() {
            totalScrollEvents.set(0);
            totalMappingDuration.set(0);
            totalDeltasProcessed.set(0);
            maxMappingDuration.set(0);
            maxDeltasInSingleEvent.set(0);
        }
    }
}
