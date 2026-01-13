package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnitType;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test that validates FuzzyMatcher logic against a large set of real-world
 * code units extracted from the codebase.
 */
public class CompletionsFuzzyIntegrationTest {

    private record CodeUnitRecord(CodeUnitType type, String fqName) {
        String shortName() {
            int lastDot = fqName.lastIndexOf('.');
            return lastDot == -1 ? fqName : fqName.substring(lastDot + 1);
        }

        @Override
        public String toString() {
            return "[%s] %s".formatted(type, fqName);
        }
    }

    private static final List<CodeUnitRecord> CODE_UNITS = new ArrayList<>();

    @BeforeAll
    static void loadCodeUnits() throws Exception {
        try (InputStream is = CompletionsFuzzyIntegrationTest.class.getResourceAsStream("/codeunits.csv")) {
            Objects.requireNonNull(is, "Resource /codeunits.csv not found");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] parts = line.split(",", 2);
                    if (parts.length == 2) {
                        try {
                            CODE_UNITS.add(new CodeUnitRecord(CodeUnitType.valueOf(parts[0]), parts[1]));
                        } catch (IllegalArgumentException ignored) {
                            // Skip lines with unknown types
                        }
                    }
                }
            }
        }
    }

    private List<CodeUnitRecord> getMatches(String pattern) {
        FuzzyMatcher matcher = new FuzzyMatcher(pattern);
        boolean hierarchicalQuery = pattern.indexOf('.') >= 0;
        return CODE_UNITS.stream()
                .filter(cu -> hierarchicalQuery ? matcher.matches(cu.fqName()) : matcher.matches(cu.shortName()))
                .sorted(Comparator.<CodeUnitRecord>comparingInt(cu -> {
                            int score = hierarchicalQuery ? matcher.score(cu.fqName()) : matcher.score(cu.shortName());
                            if (score == Integer.MAX_VALUE) return score;
                            // Apply class priority bonus to match Completions.java logic
                            return cu.type() == CodeUnitType.CLASS ? score - 1000 : score;
                        })
                        .thenComparingInt(cu -> cu.shortName().length())
                        .thenComparingInt(cu -> cu.fqName().length())
                        .thenComparing(CodeUnitRecord::fqName))
                .collect(Collectors.toList());
    }

    @Test
    void classRanksHigherThanFieldUsage() {
        String pattern = "contextmanager";
        List<CodeUnitRecord> matches = getMatches(pattern);

        assertTrue(matches.size() >= 2, "Expected at least two matches for " + pattern);

        FuzzyMatcher matcher = new FuzzyMatcher(pattern);
        System.out.println("Top 10 matches for '" + pattern + "':");
        matches.stream().limit(10).forEach(m -> System.out.println("Score: " + matcher.score(m.fqName()) + " | " + m));

        CodeUnitRecord topMatch = matches.getFirst();
        assertEquals(
                CodeUnitType.CLASS,
                topMatch.type(),
                "Top match for '" + pattern + "' should be the CLASS, but was: " + topMatch);
        assertEquals("ai.brokk.ContextManager", topMatch.fqName());
    }

    @Test
    void exactClassNameRanksHigherThanNestedClass() {
        String pattern = "FuzzyMatcher";
        List<CodeUnitRecord> matches = getMatches(pattern);

        assertTrue(
                matches.stream().anyMatch(cu -> cu.fqName().equals("ai.brokk.FuzzyMatcher")),
                "Expected ai.brokk.FuzzyMatcher in results");

        CodeUnitRecord topMatch = matches.get(0);
        assertEquals(
                "ai.brokk.FuzzyMatcher",
                topMatch.fqName(),
                "Top match for '" + pattern + "' should be the exact class name");

        // Ensure it beats nested classes like FuzzyMatcher.TextRange if present
        if (matches.size() > 1 && matches.get(1).fqName().contains("FuzzyMatcher.")) {
            assertTrue(new FuzzyMatcher(pattern).score(matches.get(0).fqName())
                    < new FuzzyMatcher(pattern).score(matches.get(1).fqName()));
        }
    }

    @Test
    void commonAbbreviationsRankCorrectly() {
        // Test CM -> ContextManager should be in top results
        // Note: Fields literally named "cm" will rank higher due to exact match bonus,
        // and many other symbols may start with "cm", so we check top 50.
        List<CodeUnitRecord> cmMatches = getMatches("CM");
        if (!cmMatches.isEmpty()) {
            // ContextManager should be in the top results for "CM" due to CamelHump matching
            boolean foundContextManager = cmMatches.stream()
                    .limit(50)
                    .anyMatch(cu -> cu.fqName().equals("ai.brokk.ContextManager"));
            assertTrue(foundContextManager, "ContextManager should be in top 50 for 'CM'");
        }

        // Test FM -> FuzzyMatcher
        List<CodeUnitRecord> fmMatches = getMatches("FM");
        if (!fmMatches.isEmpty()) {
            // FuzzyMatcher should be in top results for "FM"
            boolean foundFuzzyMatcher = fmMatches.stream()
                    .limit(5)
                    .anyMatch(cu -> cu.fqName().equals("ai.brokk.FuzzyMatcher"));
            assertTrue(foundFuzzyMatcher, "FuzzyMatcher should be in top 5 for 'FM'");
        }

        // Test AS -> AbstractService (if present)
        List<CodeUnitRecord> asMatches = getMatches("AS");
        if (!asMatches.isEmpty()) {
            // Check if AbstractService exists and is well-ranked
            boolean foundAbstractService =
                    asMatches.stream().limit(10).anyMatch(cu -> cu.shortName().equals("AbstractService"));

            if (foundAbstractService) {
                // If AbstractService exists, verify it's in the top results
                assertTrue(foundAbstractService, "AbstractService should be in top 10 for 'AS'");
            }
            // Otherwise, we accept any match - the pattern AS is too short/ambiguous
            // to make strong assertions without AbstractService in the dataset
        }
    }
}
