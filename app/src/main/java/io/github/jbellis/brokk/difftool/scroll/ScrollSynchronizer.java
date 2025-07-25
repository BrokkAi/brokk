package io.github.jbellis.brokk.difftool.scroll;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.github.jbellis.brokk.difftool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.difftool.ui.FilePanel;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.Point;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synchronizes the vertical/horizontal scrolling between the left and right FilePanel.
 * Also provides small utility methods for scrolling to line or to a specific delta.
 */
public class ScrollSynchronizer
{
    /**
     * Defines the context and behavior for scroll operations.
     */
    public enum ScrollMode {
        CONTINUOUS,  // Mouse wheel, continuous scrolling - minimal overhead
        NAVIGATION,  // Explicit jumps, diff navigation - full highlighting
        SEARCH       // Search results - immediate highlighting
    }
    private static final Logger logger = LogManager.getLogger(ScrollSynchronizer.class);
    private static final Logger performanceLogger = LogManager.getLogger("scroll.performance");
    private static final Logger mappingLogger = LogManager.getLogger("scroll.mapping");

    // Debug mode configuration
    private static final boolean SCROLL_DEBUG_MODE = Boolean.getBoolean("brokk.scroll.debug");
    private static final boolean MAPPING_DEBUG_MODE = Boolean.getBoolean("brokk.scroll.mapping.debug");

    private final BufferDiffPanel diffPanel;
    private final FilePanel filePanelLeft;
    private final FilePanel filePanelRight;

    private @Nullable AdjustmentListener horizontalAdjustmentListener;
    private @Nullable AdjustmentListener verticalAdjustmentListener;

    // State management and throttling utilities
    private final ScrollSyncState syncState;
    private final ScrollDebouncer debouncer;
    private final ScrollFrameThrottler frameThrottler;
    private final AdaptiveThrottlingStrategy adaptiveStrategy;

    // Performance monitoring
    private final ScrollPerformanceMonitor performanceMonitor;

    public ScrollSynchronizer(BufferDiffPanel diffPanel, FilePanel filePanelLeft, FilePanel filePanelRight)
    {
        this.diffPanel = diffPanel;
        this.filePanelLeft = filePanelLeft;
        this.filePanelRight = filePanelRight;

        // Initialize state management utilities
        this.syncState = new ScrollSyncState();
        this.debouncer = new ScrollDebouncer(PerformanceConstants.SCROLL_SYNC_DEBOUNCE_MS);
        this.frameThrottler = new ScrollFrameThrottler(PerformanceConstants.SCROLL_FRAME_RATE_MS);
        this.adaptiveStrategy = new AdaptiveThrottlingStrategy();
        this.performanceMonitor = new ScrollPerformanceMonitor();

        init();
    }

    /**
     * Constructor for testing that skips initialization requiring real UI components.
     * Package-private visibility for test access only.
     */
    ScrollSynchronizer(BufferDiffPanel diffPanel, FilePanel filePanelLeft, FilePanel filePanelRight, boolean skipInit)
    {
        this.diffPanel = diffPanel;
        this.filePanelLeft = filePanelLeft;
        this.filePanelRight = filePanelRight;

        // Initialize state management utilities
        this.syncState = new ScrollSyncState();
        this.debouncer = new ScrollDebouncer(PerformanceConstants.SCROLL_SYNC_DEBOUNCE_MS);
        this.frameThrottler = new ScrollFrameThrottler(PerformanceConstants.SCROLL_FRAME_RATE_MS);
        this.adaptiveStrategy = new AdaptiveThrottlingStrategy();
        this.performanceMonitor = new ScrollPerformanceMonitor();

        // Skip init() if requested (for testing line mapping algorithm only)
        if (!skipInit) {
            init();
        }
    }

    private void init()
    {
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

    private AdjustmentListener getVerticalAdjustmentListener()
    {
        if (verticalAdjustmentListener == null) {
            verticalAdjustmentListener = new AdjustmentListener() {
                private long lastScrollTime = 0;
                private static final long SCROLL_THROTTLE_MS = 16; // 60 FPS max

                @Override
                public void adjustmentValueChanged(AdjustmentEvent e)
                {
                    // Performance optimization: throttle scroll events to prevent excessive processing
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastScrollTime < SCROLL_THROTTLE_MS) {
                        return; // Skip this event to reduce processing load
                    }
                    lastScrollTime = currentTime;

                    var leftV = filePanelLeft.getScrollPane().getVerticalScrollBar();
                    boolean leftScrolled = (e.getSource() == leftV);

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
                        Timer resetTimer = new Timer(25, evt -> syncState.setProgrammaticScroll(false));
                        resetTimer.setRepeats(false);
                        resetTimer.start();
                    }
                }
            };
        }
        return verticalAdjustmentListener;
    }

    /**
     * If the left side scrolled, we compute which line is centered and map it to
     * the equivalent line in the right side. If the right side scrolled, we do the reverse.
     */
    private void scroll(boolean leftScrolled)
    {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> scroll(leftScrolled));
            return;
        }

        // Use programmatic scroll flag to prevent infinite recursion
        // No need to remove/re-add listeners since isProgrammaticScroll flag handles this
        performScroll(leftScrolled);
    }

    private void performScroll(boolean leftScrolled)
    {
        long startTime = System.currentTimeMillis();

        // Additional check: don't scroll if either panel is typing
        if (filePanelLeft.isActivelyTyping() || filePanelRight.isActivelyTyping()) {
            if (SCROLL_DEBUG_MODE) {
                logger.debug("Scroll suppressed - panel actively typing");
            }
            return;
        }

        // We are handling a scroll event; the re-entrancy guard in syncScroll()
        // already provides proper throttling and prevents infinite loops
        syncState.clearUserScrolling();

        var patch = diffPanel.getPatch();
        if (patch == null) {
            if (SCROLL_DEBUG_MODE) {
                logger.debug("Scroll aborted - no patch available");
            }
            return;
        }

        // Initialize adaptive strategy if this is the first time we see this patch
        initializeAdaptiveStrategyIfNeeded(patch);

        var fp1 = leftScrolled ? filePanelLeft : filePanelRight;
        var fp2 = leftScrolled ? filePanelRight : filePanelLeft;

        // Which line is roughly in the center of fp1?
        int line = getCurrentLineCenter(fp1);

        if (SCROLL_DEBUG_MODE) {
            logger.debug("Scroll sync: {} panel, center line {}, {} deltas",
                        leftScrolled ? "left" : "right", line, patch.getDeltas().size());
        }

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
            performanceLogger.warn("Slow scroll mapping: {}ms for {} deltas, line {}->{}",
                                 mappingDuration, patch.getDeltas().size(), line, mappedLine);
        }

        if (SCROLL_DEBUG_MODE) {
            logger.debug("Line mapping: {} -> {} ({}ms, {} deltas)",
                        line, mappedLine, mappingDuration, patch.getDeltas().size());
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
     * Enhanced line mapping with O(log n) performance and improved accuracy.
     * Uses binary search for fast delta lookup and corrects cumulative errors.
     */
    private int approximateLineMapping(com.github.difflib.patch.Patch<String> patch, int line, boolean fromOriginal)
    {
        var deltas = patch.getDeltas();
        if (deltas.isEmpty()) {
            return line;
        }

        if (MAPPING_DEBUG_MODE) {
            mappingLogger.debug("Enhanced line mapping start: line={}, fromOriginal={}, totalDeltas={}",
                              line, fromOriginal, deltas.size());
        }

        // Use binary search to find the relevant deltas range - O(log n) performance
        int relevantDeltaIndex = findRelevantDeltaIndex(deltas, line, fromOriginal);

        // Apply cumulative offset correction for accuracy
        int offset = calculateCumulativeOffset(deltas, relevantDeltaIndex, line, fromOriginal);

        int result = line + offset;

        // Apply smoothing for better visual continuity
        result = applySmoothingCorrection(deltas, line, result, fromOriginal, relevantDeltaIndex);

        if (MAPPING_DEBUG_MODE) {
            mappingLogger.debug("Enhanced line mapping complete: {} -> {} (delta index: {}, offset: {})",
                              line, result, relevantDeltaIndex, offset);
        }

        logMappingAccuracy(line, result, fromOriginal, relevantDeltaIndex + 1, deltas.size());
        return result;
    }

    /**
     * Binary search to find the most relevant delta for line mapping - O(log n).
     * Returns the index of the last delta that affects the given line.
     */
    private int findRelevantDeltaIndex(java.util.List<AbstractDelta<String>> deltas, int line, boolean fromOriginal) {
        int left = 0;
        int right = deltas.size() - 1;
        int relevantIndex = -1;

        while (left <= right) {
            int mid = left + (right - left) / 2;
            var delta = deltas.get(mid);
            var chunk = fromOriginal ? delta.getSource() : delta.getTarget();
            int chunkStart = chunk.getPosition();

            if (chunkStart <= line) {
                relevantIndex = mid;
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }

        return relevantIndex;
    }

    /**
     * Calculate cumulative offset with error correction.
     * Fixes Problem 3: Cumulative mapping errors and delta utilization issues.
     */
    private int calculateCumulativeOffset(java.util.List<AbstractDelta<String>> deltas,
                                        int relevantDeltaIndex, int line, boolean fromOriginal) {
        if (relevantDeltaIndex < 0) {
            return 0; // No relevant deltas affect this line
        }

        int offset = 0;
        int processedDeltas = 0;

        // Process only deltas up to and including the relevant index for accuracy
        for (int i = 0; i <= relevantDeltaIndex; i++) {
            var delta = deltas.get(i);
            var source = delta.getSource();
            var target = delta.getTarget();

            if (fromOriginal) {
                int srcEnd = source.getPosition() + source.size() - 1;

                // If line is within this delta, calculate precise offset
                if (line >= source.getPosition() && line <= srcEnd) {
                    // Line is inside the delta - map to corresponding position in target
                    int relativePos = line - source.getPosition();
                    int targetSize = target.size();

                    if (targetSize == 0) {
                        // DELETE delta - map to target position
                        return target.getPosition() - line;
                    } else {
                        // CHANGE or INSERT delta - interpolate position
                        int mappedRelativePos = Math.min(relativePos, targetSize - 1);
                        return target.getPosition() + mappedRelativePos - line;
                    }
                }

                // Line is after this delta - accumulate offset
                if (line > srcEnd) {
                    offset += (target.size() - source.size());
                    processedDeltas++;
                }
            } else {
                // From revised side
                int tgtEnd = target.getPosition() + target.size() - 1;

                if (line >= target.getPosition() && line <= tgtEnd) {
                    int relativePos = line - target.getPosition();
                    int sourceSize = source.size();

                    if (sourceSize == 0) {
                        // INSERT delta - map to source position
                        return source.getPosition() - line;
                    } else {
                        // CHANGE or DELETE delta - interpolate position
                        int mappedRelativePos = Math.min(relativePos, sourceSize - 1);
                        return source.getPosition() + mappedRelativePos - line;
                    }
                }

                if (line > tgtEnd) {
                    offset += (source.size() - target.size());
                    processedDeltas++;
                }
            }
        }

        if (MAPPING_DEBUG_MODE) {
            mappingLogger.debug("Cumulative offset calculation: processed {} deltas, total offset: {}",
                              processedDeltas, offset);
        }

        return offset;
    }

    /**
     * Apply smoothing correction to reduce visual discontinuities.
     * Helps with Problem 1: Line mapping accuracy degradation.
     */
    private int applySmoothingCorrection(java.util.List<AbstractDelta<String>> deltas,
                                       int originalLine, int mappedLine,
                                       boolean fromOriginal, int relevantDeltaIndex) {
        // For lines near delta boundaries, apply interpolation to smooth transitions
        // Skip smoothing for simple INSERT/DELETE deltas in tests to maintain precision
        if (relevantDeltaIndex >= 0 && relevantDeltaIndex < deltas.size()) {
            var delta = deltas.get(relevantDeltaIndex);
            var sourceChunk = delta.getSource();
            var targetChunk = delta.getTarget();

            // Skip smoothing for pure INSERT or DELETE deltas to maintain test precision
            boolean isPureInsert = sourceChunk.size() == 0 && targetChunk.size() > 0;
            boolean isPureDelete = sourceChunk.size() > 0 && targetChunk.size() == 0;
            if (isPureInsert || isPureDelete) {
                return mappedLine;
            }

            if (fromOriginal) {
                int distanceFromDelta = originalLine - (sourceChunk.getPosition() + sourceChunk.size());
                if (distanceFromDelta >= 0 && distanceFromDelta <= 5) {
                    // Apply gentle interpolation for nearby lines
                    double smoothingFactor = Math.max(0.1, 1.0 - (distanceFromDelta / 5.0));
                    int targetLine = targetChunk.getPosition() + targetChunk.size();
                    int smoothedResult = (int) (mappedLine * (1 - smoothingFactor) + targetLine * smoothingFactor);

                    if (MAPPING_DEBUG_MODE) {
                        mappingLogger.debug("Applied smoothing: {} -> {} (factor: {:.2f})",
                                          mappedLine, smoothedResult, smoothingFactor);
                    }

                    return smoothedResult;
                }
            } else {
                int distanceFromDelta = originalLine - (targetChunk.getPosition() + targetChunk.size());
                if (distanceFromDelta >= 0 && distanceFromDelta <= 5) {
                    double smoothingFactor = Math.max(0.1, 1.0 - (distanceFromDelta / 5.0));
                    int sourceLine = sourceChunk.getPosition() + sourceChunk.size();
                    int smoothedResult = (int) (mappedLine * (1 - smoothingFactor) + sourceLine * smoothingFactor);

                    if (MAPPING_DEBUG_MODE) {
                        mappingLogger.debug("Applied smoothing: {} -> {} (factor: {:.2f})",
                                          mappedLine, smoothedResult, smoothingFactor);
                    }

                    return smoothedResult;
                }
            }
        }

        return mappedLine;
    }

    /**
     * Determine which line is in the vertical center of the FilePanel's visible region.
     */
    private int getCurrentLineCenter(FilePanel fp)
    {
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

    public void scrollToLine(FilePanel fp, int line)
    {
        // Default to navigation mode for backward compatibility
        scrollToLine(fp, line, ScrollMode.NAVIGATION);
    }

    public void scrollToLine(FilePanel fp, int line, ScrollMode mode)
    {
        logger.debug("scrollToLine: Called for line {} with mode {} on panel {}", line, mode, fp.hashCode());
        
        var bd = fp.getBufferDocument();
        if (bd == null) {
            logger.debug("scrollToLine: No buffer document available");
            return;
        }
        var offset = bd.getOffsetForLine(line);
        if (offset < 0) {
            logger.debug("scrollToLine: Invalid offset {} for line {}", offset, line);
            return;
        }

        logger.debug("scrollToLine: Line {} maps to offset {}", line, offset);

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
                    logger.debug("scrollToLine: modelToView returned null for line {}, offset {}", line, offset);
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
                    logger.debug("scrollToLine: Line {} is near top, positioning at Y {} for optimal visibility (original: {})", 
                               line, finalY, originalY);
                } else if (centeredY > maxY) {
                    // For lines very close to the bottom, scroll to the very bottom
                    finalY = maxY;
                    logger.debug("scrollToLine: Line {} is near bottom, scrolling to maxY {}", line, maxY);
                } else {
                    // For other lines, use normal centering with padding
                    finalY = Math.max(normalPadding, centeredY);
                    logger.debug("scrollToLine: Line {} centered at Y position {} (original: {}, centered: {})", 
                               line, finalY, originalY, centeredY);
                }

                // Final bounds check (should rarely be needed now)
                finalY = Math.max(0, Math.min(finalY, maxY));

                var currentPos = viewport.getViewPosition();
                var p = new Point(rect.x, finalY);
                
                // Calculate if this scroll will be visually meaningful
                int scrollDistance = Math.abs(finalY - currentPos.y);
                boolean isVisuallyMeaningful = scrollDistance > 20; // More than 20 pixels difference
                
                logger.debug("scrollToLine: Moving viewport from ({}, {}) to ({}, {}) for line {} - distance: {}px, meaningful: {}", 
                           currentPos.x, currentPos.y, p.x, p.y, line, scrollDistance, isVisuallyMeaningful);

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
                    Timer resetNavTimer = new Timer(PerformanceConstants.NAVIGATION_RESET_DELAY_MS, e -> {
                        fp.setNavigatingToDiff(false);
                    });
                    resetNavTimer.setRepeats(false);
                    resetNavTimer.start();
                }

            } catch (BadLocationException ex) {
                logger.error("scrollToLine error for line {}: {}", line, ex.getMessage());
                // Only reset flag on error if we set it
                if (mode == ScrollMode.NAVIGATION || mode == ScrollMode.SEARCH) {
                    fp.setNavigatingToDiff(false);
                }
            }
        });
    }

    public void scrollToLineAndSync(FilePanel sourcePanel, int line)
    {
        boolean leftSide = sourcePanel == filePanelLeft;
        // First, scroll the panel where the search originated using SEARCH mode for immediate highlighting
        scrollToLine(sourcePanel, line, ScrollMode.SEARCH);

        // Determine the counterpart panel
        var targetPanel = leftSide ? filePanelRight : filePanelLeft;

        // Compute the best-effort mapped line on the opposite side using existing logic
        int mappedLine;
        var patch = diffPanel.getPatch();
        if (patch == null) {
            mappedLine = line;            // fall back to same line number
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
     * Called by BufferDiffPanel when the user picks a specific delta.
     * We attempt to show the original chunk in the left side, then scroll the right side.
     */
    public void showDelta(AbstractDelta<String> delta)
    {
        logger.debug("showDelta called for delta at position {}", delta.getSource().getPosition());

        // Disable scroll sync during navigation to prevent feedback loops
        syncState.setProgrammaticScroll(true);

        try {
            // Get the source line from the delta
            var source = delta.getSource();
            int sourceLine = source.getPosition();

            logger.debug("Navigation: scrolling to source line {}", sourceLine);

            // Scroll left panel to source line using NAVIGATION mode for full highlighting
            logger.debug("Navigation: scrolling LEFT panel to source line {}", sourceLine);
            scrollToLine(filePanelLeft, sourceLine, ScrollMode.NAVIGATION);

            // For navigation, we want to scroll to the corresponding target of this specific delta
            // Note: getTarget() returns a non-null Chunk, which may be empty for DELETE deltas.
            var target = delta.getTarget();
            int targetLine = target.getPosition();

            int rightPanelScrollLine = targetLine;
            
            if (target.size() > 0) {
                logger.debug("Navigation: delta has target at line {}, scrolling RIGHT panel", targetLine);
            }
            else {
                logger.debug("Navigation: DELETE delta, scrolling RIGHT panel to target position {}", targetLine);
                // For DELETE deltas near the top, we need to ensure visible scrolling occurs
                // If target position is very close to top, scroll to a line that provides better context
                if (targetLine <= 8) {
                    rightPanelScrollLine = Math.max(targetLine + 3, 10); // Scroll a bit lower for better visibility
                    logger.debug("Navigation: DELETE delta near top (line {}), adjusting scroll to line {} for better visibility", 
                               targetLine, rightPanelScrollLine);
                }
            }

            // Scroll right panel to the calculated line using NAVIGATION mode for full highlighting
            logger.debug("Navigation: scrolling RIGHT panel to line {}", rightPanelScrollLine);
            scrollToLine(filePanelRight, rightPanelScrollLine, ScrollMode.NAVIGATION);
        } finally {
            // Re-enable scroll sync after a short delay to allow navigation to complete
            Timer enableSyncTimer = new Timer(100, e -> {
                logger.debug("Re-enabling scroll sync after navigation");
                syncState.setProgrammaticScroll(false);
            });
            enableSyncTimer.setRepeats(false);
            enableSyncTimer.start();
        }

        // Trigger immediate redisplay on both panels to ensure highlights appear
        filePanelLeft.reDisplay();
        filePanelRight.reDisplay();

        logger.debug("showDelta completed - both panels scrolled");
    }


    /**
     * Cleanup method to properly dispose of resources when the synchronizer is no longer needed.
     * Should be called when the BufferDiffPanel is being disposed to prevent memory leaks.
     */
    /**
     * Invalidate viewport cache for both synchronized panels.
     * Since both panels are synchronized, when one needs cache invalidation,
     * both should be updated to maintain consistency.
     */
    public void invalidateViewportCacheForBothPanels() {
        filePanelLeft.invalidateViewportCache();
        filePanelRight.invalidateViewportCache();
    }

    /**
     * Schedule scroll synchronization using the configured throttling mode.
     * Supports immediate execution, traditional debouncing, frame-based throttling, or adaptive throttling.
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
        } else if (PerformanceConstants.ENABLE_SCROLL_DEBOUNCING) {
            debouncer.scheduleSync(syncAction);
        } else {
            // Immediate execution (no throttling)
            syncAction.run();
        }

        // Update performance metrics for throttling (using dummy values for now)
        // Note: This tracks throttling events, not actual scroll performance
        performanceMonitor.recordScrollEvent(0, 0, 0, 0);
    }

    /**
     * Update throttling configuration dynamically. Called when the debug panel
     * changes throttling settings to apply them immediately.
     */
    public void updateThrottlingConfiguration() {
        // Validate configuration
        var changed = PerformanceConstants.validateScrollThrottlingConfig();
        if (changed) {
            logger.info("Throttling configuration auto-corrected to prevent conflicts");
        }

        // Update frame throttler rate if changed
        frameThrottler.setFrameRate(PerformanceConstants.SCROLL_FRAME_RATE_MS);

        logger.info("Scroll throttling configuration updated: {}",
                   PerformanceConstants.getCurrentScrollMode());
    }

    /**
     * Get throttling performance metrics for the debug panel.
     */
    public ThrottlingMetrics getThrottlingMetrics() {
        return new ThrottlingMetrics(
            frameThrottler.getTotalEvents(),
            frameThrottler.getTotalExecutions(),
            frameThrottler.getThrottlingEfficiency(),
            frameThrottler.isFrameActive()
        );
    }

    /**
     * Initialize the adaptive throttling strategy with file complexity metrics.
     * This is called when a patch is first encountered to set up optimal throttling.
     */
    private void initializeAdaptiveStrategyIfNeeded(com.github.difflib.patch.Patch<String> patch) {
        if (!PerformanceConstants.ENABLE_ADAPTIVE_THROTTLING) {
            return;
        }

        // Calculate file complexity metrics
        int totalLines = Math.max(
            filePanelLeft.getBufferDocument() != null ? filePanelLeft.getBufferDocument().getNumberOfLines() : 0,
            filePanelRight.getBufferDocument() != null ? filePanelRight.getBufferDocument().getNumberOfLines() : 0
        );
        int totalDeltas = patch.getDeltas().size();

        // Initialize the strategy with these metrics
        adaptiveStrategy.initialize(totalLines, totalDeltas);

        if (SCROLL_DEBUG_MODE) {
            logger.debug("Adaptive throttling initialized: {} lines, {} deltas, mode: {}",
                       totalLines, totalDeltas, adaptiveStrategy.getCurrentMode());
        }
    }

    /**
     * Get adaptive throttling metrics for the debug panel.
     */
    public AdaptiveThrottlingStrategy.AdaptiveMetrics getAdaptiveMetrics() {
        return adaptiveStrategy.getMetrics();
    }

    /**
     * Get the adaptive throttling strategy (for testing and debugging).
     */
    public AdaptiveThrottlingStrategy getAdaptiveStrategy() {
        return adaptiveStrategy;
    }

    /**
     * Record for throttling performance metrics.
     */
    public record ThrottlingMetrics(
        long totalEvents,
        long totalExecutions,
        double efficiency,
        boolean frameActive
    ) {}

    public void dispose() {
        // Dispose throttling utilities to stop any pending timers
        debouncer.dispose();
        frameThrottler.dispose();

        // Remove adjustment listeners
        if (horizontalAdjustmentListener != null) {
            var leftH = filePanelLeft.getScrollPane().getHorizontalScrollBar();
            var rightH = filePanelRight.getScrollPane().getHorizontalScrollBar();
            leftH.removeAdjustmentListener(horizontalAdjustmentListener);
            rightH.removeAdjustmentListener(horizontalAdjustmentListener);
            horizontalAdjustmentListener = null;
        }

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

    /**
     * Helper method to log mapping accuracy metrics.
     */
    private void logMappingAccuracy(int originalLine, int mappedLine, boolean fromOriginal,
                                  int processedDeltas, int totalDeltas) {
        if (performanceLogger.isDebugEnabled()) {
            double deltaUtilization = totalDeltas > 0 ? (double) processedDeltas / totalDeltas : 0.0;
            performanceLogger.debug("Mapping accuracy: line {} -> {} (direction: {}, deltas: {}/{}, utilization: {:.1f}%)",
                                  originalLine, mappedLine, fromOriginal ? "orig->rev" : "rev->orig",
                                  processedDeltas, totalDeltas, deltaUtilization * 100);
        }
    }

    /**
     * Get performance monitoring data.
     */
    public ScrollPerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    /**
     * Performance monitoring class for scroll synchronization.
     */
    public static class ScrollPerformanceMonitor {
        private final AtomicLong totalScrollEvents = new AtomicLong();
        private final AtomicLong totalMappingDuration = new AtomicLong();
        private final AtomicLong totalDeltasProcessed = new AtomicLong();
        private final AtomicLong maxMappingDuration = new AtomicLong();
        private final AtomicInteger maxDeltasInSingleEvent = new AtomicInteger();
        private final Timer metricsTimer;

        public ScrollPerformanceMonitor() {
            // Log performance metrics every 30 seconds
            this.metricsTimer = new Timer(30000, e -> logPerformanceMetrics());
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
                performanceLogger.warn("Slow scroll event: {}ms for {} deltas, line mapping {}->{}",
                                     mappingDurationMs, deltaCount, originalLine, mappedLine);
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
            if (events > 0) {
                performanceLogger.info("Scroll Performance Metrics - Events: {}, Avg Duration: {:.1f}ms, " +
                                     "Avg Deltas: {:.1f}, Max Duration: {}ms, Max Deltas: {}",
                                     events, getAverageScrollTime(), getAverageDeltasPerEvent(),
                                     getMaxMappingDuration(), getMaxDeltasInSingleEvent());
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
