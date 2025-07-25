package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.DeltaType;
import org.jetbrains.annotations.Nullable;

/**
 * Small, pure helper for deciding whether a {@link AbstractDelta} should be
 * highlighted in a given viewport.
 */
public final class DiffHighlightUtil {
    private DiffHighlightUtil() {}

    /**
     * Result of viewport intersection check with optional warning message.
     */
    public record IntersectionResult(boolean intersects, @Nullable String warning) {}

    /**
     * Returns the chunk for the specified side without any fallback logic.
     */
    public static @Nullable Chunk<String> getRelevantChunk(AbstractDelta<String> delta, boolean originalSide) {
        return originalSide ? delta.getSource() : delta.getTarget();
    }

    /**
     * Returns the chunk to use for highlighting purposes. For DELETE deltas on the revised side,
     * creates a fallback chunk at the insertion point to show where content was removed.
     */
    public static @Nullable Chunk<String> getChunkForHighlight(AbstractDelta<String> delta, boolean originalSide) {
        var chunk = getRelevantChunk(delta, originalSide);

        // For DELETE deltas on revised side, create a fallback chunk at the target position
        // to show a line indicator where the content was removed
        if (chunk != null && chunk.size() == 0 && !originalSide) {
            if (delta.getType() == DeltaType.DELETE) {
                // Create a zero-size chunk at the target position for visual indication
                return new Chunk<>(delta.getTarget().getPosition(), java.util.List.of());
            }
        }

        return chunk;
    }

    /**
     * Decide if a delta's chunk is visible in the viewport [startLine,endLine].
     */
    public static IntersectionResult isChunkVisible(AbstractDelta<String> delta,
                                                    int startLine,
                                                    int endLine,
                                                    boolean originalSide) {
        if (startLine > endLine)
            return new IntersectionResult(false, "Invalid range: startLine " + startLine + " > endLine " + endLine);

        Chunk<String> chunk = getChunkForHighlight(delta, originalSide);
        if (chunk == null)
            return new IntersectionResult(false, null);   // nothing to draw on this side

        int pos  = chunk.getPosition();
        int size = chunk.size();

        if (pos < 0)
            return new IntersectionResult(false, "Invalid negative position: " + pos);

        int deltaEnd   = pos + Math.max(1, size) - 1;
        boolean intersects = !(deltaEnd < startLine || pos > endLine);
        return new IntersectionResult(intersects, null);
    }
}