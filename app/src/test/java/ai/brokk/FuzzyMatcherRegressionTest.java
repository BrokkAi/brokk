package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Regression test for FuzzyMatcher search issues.
 * See: https://github.com/BrokkAi/brokk/issues/2228
 */
class FuzzyMatcherRegressionTest {

    @Test
    void testSlidingWindowCacheSearch() {
        var matcher = new FuzzyMatcher("SlidingWindowCache");

        // Should match the actual class
        assertTrue(
                matcher.matches("ai.brokk.util.SlidingWindowCache"), "Should match ai.brokk.util.SlidingWindowCache");
        assertTrue(matcher.matches("SlidingWindowCache"), "Should match SlidingWindowCache");
        assertTrue(matcher.matches("SlidingWindowCacheTest"), "Should match SlidingWindowCacheTest");

        // Should NOT match unrelated jgit classes
        assertFalse(matcher.matches("org.eclipse.jgit.Something"), "Should NOT match org.eclipse.jgit.Something");
        assertFalse(
                matcher.matches("org.eclipse.jgit.internal.storage.file.WindowCache"),
                "Should NOT match WindowCache (missing Sliding)");

        // Edge case: does match if all characters present
        boolean matchesWindowCache = matcher.matches("org.eclipse.jgit.internal.storage.file.WindowCache");
        if (matchesWindowCache) {
            // If it matches, score should be very poor (high value)
            int score = matcher.score("org.eclipse.jgit.internal.storage.file.WindowCache");
            int goodScore = matcher.score("ai.brokk.util.SlidingWindowCache");
            assertTrue(score > goodScore, "WindowCache should have worse score than SlidingWindowCache");
        }
    }

    @Test
    void testSearchResultOrdering() {
        var matcher = new FuzzyMatcher("SlidingWindowCache");

        String exact = "SlidingWindowCache";
        String qualified = "ai.brokk.util.SlidingWindowCache";
        String test = "SlidingWindowCacheTest";

        int exactScore = matcher.score(exact);
        int qualifiedScore = matcher.score(qualified);
        int testScore = matcher.score(test);

        // Exact match at start should be best (most negative)
        assertTrue(exactScore < qualifiedScore, "Exact match should be better than qualified");
        assertTrue(exactScore < testScore, "Exact match should be better than test variant");
    }
}
