package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for multi-line INSERT highlighting scenarios like the actionscript file
 * where an empty line is replaced with multiple lines of content.
 */
class MultiLineInsertTest {
    
    @Test
    void testMultiLineChangeHighlighting() {
        // Simulate the actionscript case: empty line -> 7 lines of content
        List<String> original = List.of("\n");  // Just a newline
        List<String> revised = List.of(
            "const e = Object.freeze(JSON.parse(`{\"displayName\":\"ActionScript\"...`)), t = [",
            "  e", 
            "];",
            "export {",
            "  t as default", 
            "};",
            "//# sourceMappingURL=actionscript-3-DZzbMeqX.mjs.map"
        );
        
        Patch<String> patch = DiffUtils.diff(original, revised);
        assertEquals(1, patch.getDeltas().size(), "Should have exactly one delta");
        
        AbstractDelta<String> delta = patch.getDeltas().getFirst();
        // This should be a CHANGE delta (empty line replaced with multiple lines)
        
        // Original side should highlight the deleted empty line
        var originalResult = DiffHighlightUtil.isChunkVisible(delta, 0, 10, /*originalSide=*/true);
        assertTrue(originalResult.intersects(), "Original side should highlight the deleted line");
        
        // Revised side should highlight all 7 new lines
        var revisedResult = DiffHighlightUtil.isChunkVisible(delta, 0, 10, /*originalSide=*/false);
        assertTrue(revisedResult.intersects(), "Revised side should highlight all added lines");
        
        // Check that the revised chunk covers all 7 lines
        var revisedChunk = DiffHighlightUtil.getChunkForHighlight(delta, false);
        assertNotNull(revisedChunk, "Revised chunk should not be null");
        assertEquals(0, revisedChunk.getPosition(), "Revised chunk should start at line 0");
        assertEquals(7, revisedChunk.size(), "Revised chunk should cover all 7 lines");
    }
    
    @Test
    void testMultiLineInsertViewportCoverage() {
        // Test viewport intersection for multi-line inserts
        List<String> original = List.of("A");
        List<String> revised = List.of("A", "B", "C", "D", "E");  // 4 lines added
        
        Patch<String> patch = DiffUtils.diff(original, revised);
        AbstractDelta<String> delta = patch.getDeltas().getFirst();
        
        // Test different viewport ranges
        var result1 = DiffHighlightUtil.isChunkVisible(delta, 0, 2, /*originalSide=*/false);
        assertTrue(result1.intersects(), "Should intersect viewport 0-2");
        
        var result2 = DiffHighlightUtil.isChunkVisible(delta, 2, 4, /*originalSide=*/false);
        assertTrue(result2.intersects(), "Should intersect viewport 2-4");
        
        var result3 = DiffHighlightUtil.isChunkVisible(delta, 5, 10, /*originalSide=*/false);
        assertFalse(result3.intersects(), "Should not intersect viewport 5-10");
    }
}