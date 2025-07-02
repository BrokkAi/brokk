package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import org.jetbrains.annotations.Nullable;

/**
 * Small, pure helper for deciding whether a {@link AbstractDelta} should be
 * highlighted in a given viewport.  All state required is passed as parameters
 * so the methods are trivially unit–testable and 100 % thread-safe.
 */
public final class DiffHighlightUtil
{
    private DiffHighlightUtil() {}

    /**
     * Result of viewport intersection check with optional warning message.
     */
    public record IntersectionResult(boolean intersects, @Nullable String warning) {}

    /**
     * Returns the chunk for the specified side without any fallback logic.
     */
    public static @Nullable Chunk<String> getRelevantChunk(AbstractDelta<String> delta, boolean originalSide)
    {
        return originalSide ? delta.getSource() : delta.getTarget();
    }

    /**
     * Returns the chunk to use for highlighting purposes. For revised side DELETE deltas,
     * falls back to source chunk for positioning since target has size 0.
     */
    public static @Nullable Chunk<String> getChunkForHighlight(AbstractDelta<String> delta, boolean originalSide)
    {
        var chunk = getRelevantChunk(delta, originalSide);
        // For revised side DELETE deltas (empty target), use source chunk for positioning
        return (chunk != null && chunk.size() == 0 && !originalSide) ? delta.getSource() : chunk;
    }

    /**
     * Decide if a delta's chunk is visible in the viewport [startLine,endLine].
     *
     * This contains **no** Swing or document logic – it only needs the line
     * numbers – so it can safely be called from any thread.
     */
    public static IntersectionResult isChunkVisible(AbstractDelta<String> delta,
                                                    int startLine,
                                                    int endLine,
                                                    boolean originalSide)
    {
        if (startLine > endLine)
            return new IntersectionResult(false, "Invalid range: startLine " + startLine + " > endLine " + endLine);

        Chunk<String> chunk = getChunkForHighlight(delta, originalSide);
        if (chunk == null)
            return new IntersectionResult(false, null);   // nothing to draw on this side

        int pos  = chunk.getPosition();
        int size = chunk.size();

        if (pos < 0)
            return new IntersectionResult(false, "Invalid negative position: " + pos);

        int deltaStart = pos;
        int deltaEnd   = pos + Math.max(1, size) - 1;
        boolean intersects = !(deltaEnd < startLine || deltaStart > endLine);
        return new IntersectionResult(intersects, null);
    }
}