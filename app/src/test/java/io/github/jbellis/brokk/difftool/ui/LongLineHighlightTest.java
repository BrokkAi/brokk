package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for highlighting very long lines like source map JSON files.
 * Tests that highlighting works correctly for lines with >10K characters.
 */
class LongLineHighlightTest {
    
    @Test
    void testVeryLongLineHighlighting() {
        // Create a very long line similar to source map files (>10K chars)
        String longJson = "{\"version\":3,\"file\":\"test.js\",\"sources\":[\"../src/test.js\"],\"sourcesContent\":[\"" +
                "a".repeat(15000) + // 15K characters to exceed the 10K limit in JMHighlightPainter
                "\"],\"names\":[],\"mappings\":\"AAAA\"}";
        
        List<String> original = List.of("");  // Empty file
        List<String> revised = List.of(longJson);  // Single very long line
        
        Patch<String> patch = DiffUtils.diff(original, revised);
        assertEquals(1, patch.getDeltas().size(), "Should have exactly one delta");
        
        AbstractDelta<String> delta = patch.getDeltas().getFirst();
        
        // Original side should highlight the empty line deletion
        var originalResult = DiffHighlightUtil.isChunkVisible(delta, 0, 10, /*originalSide=*/true);
        assertTrue(originalResult.intersects(), "Original side should highlight empty line deletion");
        
        // Revised side should highlight the entire long line addition
        var revisedResult = DiffHighlightUtil.isChunkVisible(delta, 0, 10, /*originalSide=*/false);
        assertTrue(revisedResult.intersects(), "Revised side should highlight long line addition");
        
        // Check that the revised chunk position and size are correct
        var revisedChunk = DiffHighlightUtil.getChunkForHighlight(delta, false);
        assertNotNull(revisedChunk, "Revised chunk should not be null for long line");
        assertEquals(0, revisedChunk.getPosition(), "Revised chunk should start at line 0");
        assertEquals(1, revisedChunk.size(), "Revised chunk should be 1 line (even if very long)");
    }
    
    @Test
    void testLongLineViewportIntersection() {
        // Test that long lines still intersect properly with different viewports
        String longLine = "x".repeat(20000); // 20K character line
        
        List<String> original = List.of("short");
        List<String> revised = List.of(longLine);
        
        Patch<String> patch = DiffUtils.diff(original, revised);
        AbstractDelta<String> delta = patch.getDeltas().getFirst();
        
        // Should intersect regardless of line length
        var result1 = DiffHighlightUtil.isChunkVisible(delta, 0, 0, /*originalSide=*/false);
        assertTrue(result1.intersects(), "Should intersect viewport containing the line");
        
        var result2 = DiffHighlightUtil.isChunkVisible(delta, 1, 5, /*originalSide=*/false);
        assertFalse(result2.intersects(), "Should not intersect viewport not containing the line");
    }
    
    @Test 
    void testPerformanceThresholdDetection() {
        // Test that our highlighting logic works even with content that might trigger
        // performance safeguards in JMHighlightPainter
        String massiveContent = "z".repeat(50000); // 50K chars - definitely over performance thresholds
        
        List<String> original = List.of("small");
        List<String> revised = List.of(massiveContent);
        
        Patch<String> patch = DiffUtils.diff(original, revised);
        AbstractDelta<String> delta = patch.getDeltas().getFirst();
        
        // Logic should still work correctly even if UI might use fallback rendering
        var chunk = DiffHighlightUtil.getChunkForHighlight(delta, false);
        assertNotNull(chunk, "Should get chunk even for massive content");
        assertEquals(1, chunk.size(), "Should still be recognized as single line change");
        
        var visibility = DiffHighlightUtil.isChunkVisible(delta, 0, 1, false);
        assertTrue(visibility.intersects(), "Should still calculate visibility correctly");
        assertNull(visibility.warning(), "Should not have warnings for valid range");
    }
}