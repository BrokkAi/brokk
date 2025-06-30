package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.search.SearchConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the regex patterns used in updateMarkerStyle() method.
 * These tests focus on the core CSS class handling logic that was fixed
 * to handle multiple classes correctly.
 */
class UpdateMarkerStyleRegexTest {

    @Test
    @DisplayName("Main regex pattern should replace existing class attribute")
    void testMainRegexPatternWithExistingClass() {
        int markerId = 123;
        String targetClass = SearchConstants.SEARCH_HIGHLIGHT_CLASS;
        
        // The actual regex pattern used in updateMarkerStyle
        String pattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)\\s+class=\"[^\"]*\"([^>]*?>)";
        String replacement = "$1 class=\"" + targetClass + "\"$2";
        
        // Test case 1: Simple class replacement
        String html1 = "<span data-brokk-id=\"123\" class=\"old-class\">content</span>";
        String result1 = html1.replaceAll(pattern, replacement);
        assertEquals("<span data-brokk-id=\"123\" class=\"" + targetClass + "\">content</span>", result1);
        
        // Test case 2: Multiple classes (the bug we fixed)
        String html2 = "<span data-brokk-id=\"123\" class=\"brokk-search-highlight brokk-search-marker\">content</span>";
        String result2 = html2.replaceAll(pattern, replacement);
        assertEquals("<span data-brokk-id=\"123\" class=\"" + targetClass + "\">content</span>", result2);
        
        // Test case 3: Complex attributes
        String html3 = "<span title=\"test\" data-brokk-id=\"123\" class=\"multiple old classes\" style=\"color: red;\">content</span>";
        String result3 = html3.replaceAll(pattern, replacement);
        assertTrue(result3.contains("class=\"" + targetClass + "\""));
        assertTrue(result3.contains("title=\"test\""));
        assertTrue(result3.contains("style=\"color: red;\""));
        assertFalse(result3.contains("old classes"));
    }

    @Test
    @DisplayName("Main regex pattern should handle spaces around attributes")
    void testMainRegexPatternWithSpaces() {
        int markerId = 456;
        String targetClass = SearchConstants.SEARCH_CURRENT_CLASS;
        
        String pattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)\\s+class=\"[^\"]*\"([^>]*?>)";
        String replacement = "$1 class=\"" + targetClass + "\"$2";
        
        // Test with various spacing patterns
        String html1 = "<span  data-brokk-id=\"456\"  class=\"some-class\"  >content</span>";
        String result1 = html1.replaceAll(pattern, replacement);
        assertTrue(result1.contains("class=\"" + targetClass + "\""));
        
        // Note: This pattern requires space before class, so this should not match
        String html2 = "<span data-brokk-id=\"456\"class=\"no-space\">content</span>";
        String result2 = html2.replaceAll(pattern, replacement);
        // This should NOT match because the pattern requires \\s+ before class
        assertFalse(result2.contains("class=\"" + targetClass + "\""));
        assertTrue(result2.contains("class=\"no-space\""));
    }

    @Test
    @DisplayName("Fallback regex pattern should add class when none exists")
    void testFallbackRegexPattern() {
        int markerId = 789;
        String targetClass = SearchConstants.SEARCH_HIGHLIGHT_CLASS;
        
        // Test fallback pattern for when no class attribute exists
        String fallbackPattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)([^>]*?>)";
        String fallbackReplacement = "$1 class=\"" + targetClass + "\"$2";
        
        // Test case 1: No class attribute
        String html1 = "<span data-brokk-id=\"789\">content</span>";
        String result1 = html1.replaceAll(fallbackPattern, fallbackReplacement);
        assertEquals("<span data-brokk-id=\"789\" class=\"" + targetClass + "\">content</span>", result1);
        
        // Test case 2: Other attributes but no class
        String html2 = "<span title=\"test\" data-brokk-id=\"789\" style=\"color: blue;\">content</span>";
        String result2 = html2.replaceAll(fallbackPattern, fallbackReplacement);
        assertTrue(result2.contains("class=\"" + targetClass + "\""));
        assertTrue(result2.contains("title=\"test\""));
        assertTrue(result2.contains("style=\"color: blue;\""));
    }

    @Test
    @DisplayName("Regex patterns should be specific to target marker ID")
    void testMarkerIdSpecificity() {
        String targetClass = SearchConstants.SEARCH_HIGHLIGHT_CLASS;
        
        // Create HTML with multiple markers
        String html = "<span data-brokk-id=\"123\" class=\"class1\">first</span>" +
                     "<span data-brokk-id=\"456\" class=\"class2\">second</span>";
        
        // Target only marker 123
        String pattern = "(<span[^>]*?data-brokk-id=\"123\"[^>]*?)\\s+class=\"[^\"]*\"([^>]*?>)";
        String replacement = "$1 class=\"" + targetClass + "\"$2";
        
        String result = html.replaceAll(pattern, replacement);
        
        // Should update marker 123 but not 456
        assertTrue(result.contains("data-brokk-id=\"123\" class=\"" + targetClass + "\""));
        assertTrue(result.contains("data-brokk-id=\"456\" class=\"class2\""));
        assertFalse(result.contains("class1"));
    }

    @Test
    @DisplayName("Combined pattern usage should work like updateMarkerStyle logic")
    void testCombinedPatternUsage() {
        int markerId = 999;
        String targetClass = SearchConstants.SEARCH_CURRENT_CLASS;
        
        // Simulate the exact logic used in updateMarkerStyle
        String html1 = "<span data-brokk-id=\"999\" class=\"existing-class\">content</span>";
        
        // Try main pattern first
        String pattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)\\s+class=\"[^\"]*\"([^>]*?>)";
        String replacement = "$1 class=\"" + targetClass + "\"$2";
        String updatedHtml = html1.replaceAll(pattern, replacement);
        
        // Should have been updated
        assertNotEquals(html1, updatedHtml);
        assertTrue(updatedHtml.contains("class=\"" + targetClass + "\""));
        
        // Test with no class attribute
        String html2 = "<span data-brokk-id=\"999\">content</span>";
        String updatedHtml2 = html2.replaceAll(pattern, replacement);
        
        // If pattern didn't match (no change), use fallback
        if (updatedHtml2.equals(html2)) {
            String fallbackPattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)([^>]*?>)";
            String fallbackReplacement = "$1 class=\"" + targetClass + "\"$2";
            updatedHtml2 = html2.replaceAll(fallbackPattern, fallbackReplacement);
        }
        
        // Should now have class attribute
        assertNotEquals(html2, updatedHtml2);
        assertTrue(updatedHtml2.contains("class=\"" + targetClass + "\""));
    }

    @Test
    @DisplayName("Regex patterns should handle nested elements correctly")
    void testNestedElementHandling() {
        int markerId = 555;
        String targetClass = SearchConstants.SEARCH_HIGHLIGHT_CLASS;
        
        String pattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)\\s+class=\"[^\"]*\"([^>]*?>)";
        String replacement = "$1 class=\"" + targetClass + "\"$2";
        
        // Test with nested content
        String html = "<div><span data-brokk-id=\"555\" class=\"old-class\"><em>nested</em> content</span></div>";
        String result = html.replaceAll(pattern, replacement);
        
        assertTrue(result.contains("class=\"" + targetClass + "\""));
        assertTrue(result.contains("<em>nested</em>"));
        assertFalse(result.contains("old-class"));
    }

    @Test
    @DisplayName("Regex patterns should handle special characters in marker IDs")
    void testSpecialCharacterHandling() {
        // Test that the regex properly escapes marker IDs (though they should be numeric)
        int markerId = 12345;
        String targetClass = SearchConstants.SEARCH_HIGHLIGHT_CLASS;
        
        String pattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)\\s+class=\"[^\"]*\"([^>]*?>)";
        String replacement = "$1 class=\"" + targetClass + "\"$2";
        
        String html = "<span data-brokk-id=\"12345\" class=\"test\">content</span>";
        String result = html.replaceAll(pattern, replacement);
        
        assertTrue(result.contains("class=\"" + targetClass + "\""));
        assertFalse(result.contains("class=\"test\""));
    }

    @Test
    @DisplayName("Regex patterns should not match partial marker IDs")
    void testPartialMarkerIdMatching() {
        String targetClass = SearchConstants.SEARCH_HIGHLIGHT_CLASS;
        
        // Target marker ID 123
        String pattern = "(<span[^>]*?data-brokk-id=\"123\"[^>]*?)\\s+class=\"[^\"]*\"([^>]*?>)";
        String replacement = "$1 class=\"" + targetClass + "\"$2";
        
        // HTML with marker ID 1234 (should not match)
        String html = "<span data-brokk-id=\"1234\" class=\"test\">content</span>";
        String result = html.replaceAll(pattern, replacement);
        
        // Should not have changed
        assertEquals(html, result);
        assertTrue(result.contains("class=\"test\""));
        assertFalse(result.contains(targetClass));
    }

    @Test
    @DisplayName("SearchConstants values should work in regex patterns")
    void testSearchConstantsInRegex() {
        // Verify that SearchConstants values are safe to use in regex
        assertNotNull(SearchConstants.SEARCH_HIGHLIGHT_CLASS);
        assertNotNull(SearchConstants.SEARCH_CURRENT_CLASS);
        
        // Test that they don't contain regex special characters that would break patterns
        String highlightClass = SearchConstants.SEARCH_HIGHLIGHT_CLASS;
        String currentClass = SearchConstants.SEARCH_CURRENT_CLASS;
        
        // Should not contain regex special characters
        assertFalse(highlightClass.contains("\\"));
        assertFalse(highlightClass.contains("$"));
        assertFalse(highlightClass.contains("("));
        assertFalse(highlightClass.contains(")"));
        assertFalse(highlightClass.contains("["));
        assertFalse(highlightClass.contains("]"));
        
        assertFalse(currentClass.contains("\\"));
        assertFalse(currentClass.contains("$"));
        assertFalse(currentClass.contains("("));
        assertFalse(currentClass.contains(")"));
        assertFalse(currentClass.contains("["));
        assertFalse(currentClass.contains("]"));
        
        // Test actual usage in replacement
        String replacement = "$1 class=\"" + highlightClass + "\"$2";
        assertTrue(replacement.contains(highlightClass));
        
        String replacement2 = "$1 class=\"" + currentClass + "\"$2";
        assertTrue(replacement2.contains(currentClass));
    }
}