package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Small, pure helper for deciding whether a {@link AbstractDelta} should be
 * highlighted in a given viewport.  All state required is passed as parameters
 * so the methods are trivially unit–testable and 100 % thread-safe.
 */
public final class DiffHighlightUtil
{
    private static final Logger logger = LogManager.getLogger(DiffHighlightUtil.class);

    private DiffHighlightUtil() {}

    /**
     * Returns the *relevant* chunk for the requested side.  For the revised
     * side a {@code null} target chunk (DELETE deltas) falls back to the
     * source so the caller can still compute a sensible location.
     */
    public static @Nullable Chunk<String> getChunk(AbstractDelta<String> delta, boolean originalSide)
    {
        if (originalSide)
            return delta.getSource();

        // Revised side – prefer target but fall back to source so deleted lines
        // still get a highlight placeholder on the right.
        Chunk<String> target = delta.getTarget();
        return target != null ? target : delta.getSource();
    }

    /**
     * Decide if a delta’s chunk intersects the viewport [startLine,endLine].
     *
     * This contains **no** Swing or document logic – it only needs the line
     * numbers – so it can safely be called from any thread.
     */
    public static boolean deltaIntersectsViewport(AbstractDelta<String> delta,
                                                  int startLine,
                                                  int endLine,
                                                  boolean originalSide)
    {
        Chunk<String> chunk = getChunk(delta, originalSide);
        if (chunk == null)
            return false;   // nothing to draw on this side

        int pos  = chunk.getPosition();
        int size = chunk.size();

        if (pos < 0)
        {
            logger.warn("Delta chunk has invalid (negative) position {} – skipping highlight", pos);
            return false;
        }

        int deltaStart = pos;
        int deltaEnd   = pos + Math.max(1, size) - 1;
        return !(deltaEnd < startLine || deltaStart > endLine);
    }
}
