package ai.brokk.analyzer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for TreeSitterAnalyzer.isLikelyQualifiedSimpleName.
 *
 * These tests call the package-visible static detector directly and do not depend on Tree-sitter or the analyzer
 * runtime. Positive cases cover a variety of hierarchical separators; negative cases cover simple identifiers,
 * single-letter names, and empty/whitespace/null inputs.
 */
public class TreeSitterAnalyzerQualifiedNameTest {

    @Test
    public void positiveCases_shouldDetectLikelyQualifiedNames() {
        assertTrue(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("com.foo.Chrome"));
        assertTrue(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("ns::Type"));
        assertTrue(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("pkg.Class$Inner"));
        assertTrue(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("obj->field"));
    }

    @Test
    public void negativeCases_simpleAndAnonymousNamesShouldNotBeDetected() {
        assertFalse(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("Chrome"));
        assertFalse(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("_Foo123"));
        assertFalse(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("T"));
        assertFalse(TreeSitterAnalyzer.isLikelyQualifiedSimpleName(""));
        assertFalse(TreeSitterAnalyzer.isLikelyQualifiedSimpleName("   "));
        assertFalse(TreeSitterAnalyzer.isLikelyQualifiedSimpleName(null));
    }
}
