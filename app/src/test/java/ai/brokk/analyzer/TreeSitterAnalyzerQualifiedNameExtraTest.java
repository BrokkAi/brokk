package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Additional unit tests for TreeSitterAnalyzer.isLikelyQualifiedSimpleName to cover more separator cases.
 * Mirrors the style of TreeSitterAnalyzerQualifiedNameTest.
 */
public class TreeSitterAnalyzerQualifiedNameExtraTest {

    @Test
    public void positiveCases_additionalHierarchySeparators() {
        // Additional hierarchical separator forms
        assertTrue(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("pkg::Class"));
        assertTrue(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("a$b"));
        // Compound example mixing package and inner-class marker
        assertTrue(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("com.example.Outer$Inner"));
    }

    /**
     * Negative cases: simple identifiers and empty inputs should not be detected as qualified names.
     * Null inputs are intentionally excluded from this test.
     */
    @Test
    public void negativeCases_simpleAndEmpty() {
        assertFalse(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("Chrome"));
        assertFalse(TreeSitterAnalyzer.isLikelyQualifiedSimpleName(""));
    }
}
