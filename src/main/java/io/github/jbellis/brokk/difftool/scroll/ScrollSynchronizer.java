package io.github.jbellis.brokk.difftool.scroll;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.github.jbellis.brokk.difftool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.difftool.ui.FilePanel;
import io.github.jbellis.brokk.gui.SwingUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.text.BadLocationException;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;

/**
 * Synchronizes the vertical/horizontal scrolling between the left and right FilePanel.
 * Also provides small utility methods for scrolling to line or to a specific delta.
 */
public class ScrollSynchronizer
{
    private final BufferDiffPanel diffPanel;
    private final FilePanel filePanelLeft;
    private final FilePanel filePanelRight;

    private @Nullable AdjustmentListener horizontalAdjustmentListener;
    private @Nullable AdjustmentListener verticalAdjustmentListener;

    public ScrollSynchronizer(BufferDiffPanel diffPanel, FilePanel filePanelLeft, FilePanel filePanelRight)
    {
        this.diffPanel = diffPanel;
        this.filePanelLeft = filePanelLeft;
        this.filePanelRight = filePanelRight;
        init();
    }

    private void init()
    {
        // Sync horizontal:
        var barLeftH = filePanelLeft.getScrollPane().getHorizontalScrollBar();
        var barRightH = filePanelRight.getScrollPane().getHorizontalScrollBar();
        barRightH.addAdjustmentListener(getHorizontalAdjustmentListener());
        barLeftH.addAdjustmentListener(getHorizontalAdjustmentListener());

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
                    insideScroll = true;
                    scroll(leftScrolled);
                    insideScroll = false;
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

        scrollToLine(fp2, mappedLine);
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
            Chunk<String> source = delta.getSource(); // original
            Chunk<String> target = delta.getTarget(); // revised
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
        var viewport = fp.getScrollPane().getViewport();
        var editor = fp.getEditor();
        try {
            var rect = SwingUtil.modelToView(editor, offset);
            if (rect == null) return;

            // We want to place the line near the center
            rect.y -= (viewport.getSize().height / 2);
            if (rect.y < 0) rect.y = 0;

            var p = rect.getLocation();
            viewport.setViewPosition(p);
        } catch (BadLocationException ex) {
            // This usually means the offset is invalid for the document model
            throw new RuntimeException(ex);
        }
    }

    /**
     * Called by BufferDiffPanel when the user picks a specific delta.
     * We attempt to show the original chunk in the left side, then scroll the right side.
     */
    public void showDelta(AbstractDelta<String> delta)
    {
        // We assume we want to scroll the left side. The 'source' chunk is the original side.
        var source = delta.getSource();
        scrollToLine(filePanelLeft, source.getPosition());
        // That triggers the verticalAdjustmentListener to sync the right side.
    }

    public void toNextDelta(boolean next)
    {
        // Moved to BufferDiffPanel. This is not used here any more.
    }
}
