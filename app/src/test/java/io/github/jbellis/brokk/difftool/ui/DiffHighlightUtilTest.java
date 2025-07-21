package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for {@link DiffHighlightUtil} which contains pure, thread–safe
 * helper logic extracted from {@code FilePanel}.
 */
class DiffHighlightUtilTest
{
    /**
     * Basic sanity-check that an INSERT delta on the *revised* side is detected
     * as intersecting when the viewport clearly covers the chunk.
     */
    @Test
    void isChunkVisible_insertRevisedSide()
    {
        List<String> original = List.of("A", "B");
        List<String> revised  = List.of("A", "B", "C");   // +1 line at pos 2

        Patch<String> patch = DiffUtils.diff(original, revised);
        assertEquals(1, patch.getDeltas().size(), "Should have exactly one delta");

        AbstractDelta<String> delta = patch.getDeltas().getFirst();

        // The inserted line is at position 2 (0-based).  Viewport 0-10 definitely covers it.
        var result = DiffHighlightUtil.isChunkVisible(delta, 0, 10, /*originalSide=*/false);
        assertTrue(result.intersects(), "Revised side should intersect viewport for INSERT delta");
        assertNull(result.warning(), "Should have no warning");
    }

    /**
     * A DELETE delta on the *original* side should be detected when the viewport
     * covers the source chunk even though the revised chunk is empty.
     */
    @Test
    void isChunkVisible_deleteOriginalSide()
    {
        List<String> original = List.of("A", "B", "C");
        List<String> revised  = List.of("A", "C");   // removed line 1 ("B")

        Patch<String> patch = DiffUtils.diff(original, revised);
        assertEquals(1, patch.getDeltas().size(), "Should have exactly one delta");

        AbstractDelta<String> delta = patch.getDeltas().getFirst();

        // Source chunk starts at 1.  Viewport 0-10 covers it.
        var result = DiffHighlightUtil.isChunkVisible(delta, 0, 10, /*originalSide=*/true);
        assertTrue(result.intersects(), "Original side should intersect viewport for DELETE delta");
        assertNull(result.warning(), "Should have no warning");
    }

    /**
     * A DELETE delta on the *revised* side should fall back to source chunk for positioning.
     */
    @Test
    void isChunkVisible_deleteRevisedSideFallback()
    {
        List<String> original = List.of("A", "B", "C");
        List<String> revised  = List.of("A", "C");   // removed line 1 ("B")

        Patch<String> patch = DiffUtils.diff(original, revised);
        AbstractDelta<String> delta = patch.getDeltas().getFirst();

        // Revised side should fall back to source chunk for positioning
        var result = DiffHighlightUtil.isChunkVisible(delta, 0, 10, /*originalSide=*/false);
        assertTrue(result.intersects(), "Revised side should fall back to source chunk positioning");
        assertNull(result.warning(), "Should have no warning");
    }

    /**
     * Verify that a delta outside the viewport is correctly reported as *not*
     * intersecting.
     */
    @Test
    void noIntersectionOutsideViewport()
    {
        List<String> original = List.of("A", "B", "C");
        List<String> revised  = List.of("A", "B", "C", "D");   // +1 at end

        Patch<String> patch = DiffUtils.diff(original, revised);
        AbstractDelta<String> delta = patch.getDeltas().getFirst();

        // Viewport is lines 0-1, but insertion is at 3.
        var result = DiffHighlightUtil.isChunkVisible(delta, 0, 1, /*originalSide=*/false);
        assertFalse(result.intersects(), "Delta should be outside viewport");
        assertNull(result.warning(), "Should have no warning");
    }

    /**
     * Test range validation - startLine > endLine should return warning.
     */
    @Test
    void invalidRange()
    {
        List<String> original = List.of("A", "B");
        List<String> revised  = List.of("A", "B", "C");

        Patch<String> patch = DiffUtils.diff(original, revised);
        AbstractDelta<String> delta = patch.getDeltas().getFirst();

        var result = DiffHighlightUtil.isChunkVisible(delta, 5, 3, false);
        assertFalse(result.intersects(), "Should not intersect with invalid range");
        assertNotNull(result.warning(), "Should have warning for invalid range");
        assertTrue(result.warning().contains("Invalid range"), "Warning should mention invalid range");
    }

    /**
     * Test chunk separation - getRelevantChunk vs getChunkForHighlight.
     */
    @Test
    void chunkMethods()
    {
        List<String> original = List.of("A", "B", "C");
        List<String> revised  = List.of("A", "C");   // DELETE delta

        Patch<String> patch = DiffUtils.diff(original, revised);
        AbstractDelta<String> delta = patch.getDeltas().getFirst();

        // Original side - both methods should return source
        var relevantOrig = DiffHighlightUtil.getRelevantChunk(delta, true);
        var highlightOrig = DiffHighlightUtil.getChunkForHighlight(delta, true);
        assertSame(relevantOrig, highlightOrig, "Original side chunks should be same");
        assertNotNull(relevantOrig, "Original chunk should exist");

        // Revised side - relevant should have size 0 (DELETE), highlight should fall back to source
        var relevantRev = DiffHighlightUtil.getRelevantChunk(delta, false);
        var highlightRev = DiffHighlightUtil.getChunkForHighlight(delta, false);
        assertNotNull(relevantRev, "Revised chunk should exist for DELETE");
        assertEquals(0, relevantRev.size(), "Revised chunk should have size 0 for DELETE");
        assertNotNull(highlightRev, "Highlight chunk should fall back to source");
        assertSame(relevantOrig, highlightRev, "Highlight should fall back to source chunk");
    }
}