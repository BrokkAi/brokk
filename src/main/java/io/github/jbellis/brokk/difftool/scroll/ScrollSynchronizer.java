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

/**
 * Synchronizes the vertical/horizontal scrolling between the left and right FilePanel.
 * Also provides small utility methods for scrolling to line or to a specific delta.
 */
public class ScrollSynchronizer
{
    private static final Logger logger = LogManager.getLogger(ScrollSynchronizer.class);

    private final BufferDiffPanel diffPanel;
    private final FilePanel filePanelLeft;
    private final FilePanel filePanelRight;

    private @Nullable AdjustmentListener horizontalAdjustmentListener;
    private @Nullable AdjustmentListener verticalAdjustmentListener;

    // State management and debouncing utilities
    private final ScrollSyncState syncState;
    private final ScrollDebouncer debouncer;

    public ScrollSynchronizer(BufferDiffPanel diffPanel, FilePanel filePanelLeft, FilePanel filePanelRight)
    {
        this.diffPanel = diffPanel;
        this.filePanelLeft = filePanelLeft;
        this.filePanelRight = filePanelRight;

        // Initialize state management utilities
        this.syncState = new ScrollSyncState();
        this.debouncer = new ScrollDebouncer(PerformanceConstants.SCROLL_SYNC_DEBOUNCE_MS);

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

        // Skip init() if requested (for testing line mapping algorithm only)
        if (!skipInit) {
            init();
        }
    }

    private void init()
    {
        // Sync horizontal:
        var barLeftH = filePanelLeft.getScrollPane().getHorizontalScrollBar();
        var barRightH = filePanelRight.getScrollPane().getHorizontalScrollBar();
        barRightH.addAdjustmentListener(getHorizontalAdjustmentListener());
        barLeftH.addAdjustmentListener(getHorizontalAdjustmentListener());

        // Initialize horizontal scroll to show left side (line numbers)
        SwingUtilities.invokeLater(() -> {
            barLeftH.setValue(0);
            barRightH.setValue(0);
        });

        // Sync vertical:
        var barLeftV = filePanelLeft.getScrollPane().getVerticalScrollBar();
        var barRightV = filePanelRight.getScrollPane().getVerticalScrollBar();
        barRightV.addAdjustmentListener(getVerticalAdjustmentListener());
        barLeftV.addAdjustmentListener(getVerticalAdjustmentListener());
    }

    private AdjustmentListener getHorizontalAdjustmentListener()
    {
        if (horizontalAdjustmentListener == null) {
            horizontalAdjustmentListener = new AdjustmentListener() {
                boolean insideScroll;

                @Override
                public void adjustmentValueChanged(AdjustmentEvent e)
                {
                    if (insideScroll) return;

                    var leftH = filePanelLeft.getScrollPane().getHorizontalScrollBar();
                    var rightH = filePanelRight.getScrollPane().getHorizontalScrollBar();
                    var scFrom = (e.getSource() == leftH ? leftH : rightH);
                    var scTo = (scFrom == leftH ? rightH : leftH);

                    insideScroll = true;
                    scTo.setValue(scFrom.getValue());
                    insideScroll = false;
                }
            };
        }
        return horizontalAdjustmentListener;
    }

    private AdjustmentListener getVerticalAdjustmentListener()
    {
        if (verticalAdjustmentListener == null) {
            verticalAdjustmentListener = new AdjustmentListener() {
                boolean insideScroll;

                @Override
                public void adjustmentValueChanged(AdjustmentEvent e)
                {
                    if (insideScroll) return;

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

                    // Track user scrolling to prevent conflicts
                    syncState.recordUserScroll();

                    // Submit debounced scroll request
                    var request = new ScrollDebouncer.DebounceRequest<>(
                        leftScrolled,
                        scrollDirection -> scroll(scrollDirection),
                        syncState::clearUserScrolling
                    );
                    debouncer.submit(request);
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
        // Additional check: don't scroll if either panel is typing
        if (filePanelLeft.isActivelyTyping() || filePanelRight.isActivelyTyping()) {
            return;
        }

        // Check if sync should be suppressed using the state utility
        var suppressionResult = syncState.shouldSuppressSync(200);
        if (suppressionResult.shouldSuppress()) {
            return;
        }

        var patch = diffPanel.getPatch();
        if (patch == null) {
            return;
        }
        var fp1 = leftScrolled ? filePanelLeft : filePanelRight;
        var fp2 = leftScrolled ? filePanelRight : filePanelLeft;

        // Which line is roughly in the center of fp1?
        int line = getCurrentLineCenter(fp1);

        // Attempt naive line mapping using deltas:
        // We walk through the patch's deltas to figure out how many lines inserted/deleted up to `line`.
        // For simplicity, we just do “line” for the other side unless you want more advanced logic.
        // (In Phase 1, we had some old “DiffUtil.getRevisedLine()” logic. If you want that back, adapt it with patch.)
        int mappedLine = approximateLineMapping(patch, line, leftScrolled);

        // Mark as programmatic scroll to prevent feedback loop
        syncState.setProgrammaticScroll(true);
        try {
            scrollToLine(fp2, mappedLine);
        } finally {
            // Reset flag after a short delay to allow scroll to complete
            Timer resetTimer = new Timer(50, e -> syncState.setProgrammaticScroll(false));
            resetTimer.setRepeats(false);
            resetTimer.start();
        }
    }

    /**
     * Basic approximation of line mapping:
     * If leftScrolled==true, `line` is from the original side, we apply deltas up to that line
     * to see how many lines were inserted or removed, producing a revised line index.
     * If false, we do the reverse.
     */
    private int approximateLineMapping(com.github.difflib.patch.Patch<String> patch, int line, boolean fromOriginal)
    {
        int offset = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            var source = delta.getSource(); // original
            var target = delta.getTarget(); // revised
            int srcPos = source.getPosition();
            int tgtPos = target.getPosition();
            // The chunk ends at pos+size-1
            int srcEnd = srcPos + source.size() - 1;
            int tgtEnd = tgtPos + target.size() - 1;

            if (fromOriginal) {
                // If this delta is fully after 'line', stop
                if (srcPos > line) break;
                // If 'line' is inside this chunk
                if (line <= srcEnd) {
                    // If inside a "change" or "delete" chunk, map to start of target
                    return tgtPos + offset;
                }
                // Otherwise, line is beyond srcEnd, so add difference in lines to offset
                offset += (target.size() - source.size());
            } else {
                // from the revised side
                if (tgtPos > line) break;
                if (line <= tgtEnd) {
                    return srcPos + offset;
                }
                offset += (source.size() - target.size());
            }
        }
        return line + offset;
    }

    /**
     * Determine which line is in the vertical center of the FilePanel's visible region.
     */
    private int getCurrentLineCenter(FilePanel fp)
    {
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
        var bd = fp.getBufferDocument();
        if (bd == null) {
            return;
        }
        var offset = bd.getOffsetForLine(line);
        if (offset < 0) {
            return;
        }

        // Mark that we're navigating to ensure highlights appear
        fp.setNavigatingToDiff(true);

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
                    // For lines very close to the top, scroll to the very top
                    finalY = 0;
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
                Timer resetNavTimer = new Timer(PerformanceConstants.NAVIGATION_RESET_DELAY_MS, e -> {
                    fp.setNavigatingToDiff(false);
                });
                resetNavTimer.setRepeats(false);
                resetNavTimer.start();

            } catch (BadLocationException ex) {
                logger.error("scrollToLine error for line {}: {}", line, ex.getMessage());
                fp.setNavigatingToDiff(false); // Reset flag on error
            }
        });
    }

    public void scrollToLineAndSync(FilePanel sourcePanel, int line)
    {
        boolean leftSide = sourcePanel == filePanelLeft;
        // First, scroll the panel where the search originated
        scrollToLine(sourcePanel, line);

        // Determine the counterpart panel
        FilePanel targetPanel = leftSide ? filePanelRight : filePanelLeft;
        if (targetPanel == null) {
            return;
        }

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

        // Finally, scroll the counterpart panel
        scrollToLine(targetPanel, mappedLine);
    }

    /**
     * Called by BufferDiffPanel when the user picks a specific delta.
     * We attempt to show the original chunk in the left side, then scroll the right side.
     */
    public void showDelta(AbstractDelta<String> delta)
    {
        logger.debug("showDelta called for delta at position {}", delta.getSource().getPosition());

        // Mark both panels as navigating to ensure highlights appear on both sides
        filePanelLeft.setNavigatingToDiff(true);
        filePanelRight.setNavigatingToDiff(true);

        // Disable scroll sync during navigation to prevent feedback loops
        syncState.setProgrammaticScroll(true);

        try {
            // Get the source line from the delta
            var source = delta.getSource();
            int sourceLine = source.getPosition();

            logger.debug("Navigation: scrolling to source line {}", sourceLine);

            // Scroll left panel to source line
            scrollToLine(filePanelLeft, sourceLine);

            // For navigation, we want to scroll to the corresponding target of this specific delta
            var target = delta.getTarget();
            int targetLine;

            if (target != null && target.size() > 0) {
                // Normal case: target chunk exists, scroll to its position
                targetLine = target.getPosition();
                logger.debug("Navigation: delta has target at line {}, scrolling right panel", targetLine);
            } else {
                // DELETE case: target is empty, scroll to where the deletion would be
                // Use the target position (where the deletion happened in revised file)
                targetLine = target != null ? target.getPosition() : sourceLine;
                logger.debug("Navigation: DELETE delta, scrolling right panel to target position {}", targetLine);
            }

            scrollToLine(filePanelRight, targetLine);
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
    public void dispose() {
        // Dispose debouncer to stop any pending timers
        debouncer.dispose();

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
    }
}
