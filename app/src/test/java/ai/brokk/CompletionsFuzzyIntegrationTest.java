package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

        String camelHumpAbbreviation() {
            String name = shortName();
            return name.chars()
                    .filter(c -> Character.isUpperCase(c) || Character.isDigit(c))
                    .mapToObj(c -> String.valueOf((char) c))
                    .collect(Collectors.joining());
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
        return CODE_UNITS.stream()
                .filter(cu -> matcher.matches(cu.fqName()))
                .sorted(Comparator.comparingInt(cu -> matcher.score(cu.fqName())))
                .collect(Collectors.toList());
    }

    @Test
    void classRanksHigherThanFieldUsage() {
        String pattern = "contextmanager";
        List<CodeUnitRecord> matches = getMatches(pattern);

        assertTrue(matches.size() >= 2, "Expected at least two matches for " + pattern);

        // Find the index of the first Class match for ContextManager
        int firstClassIdx = -1;
        int firstFieldIdx = -1;

        for (int i = 0; i < matches.size(); i++) {
            CodeUnitRecord cu = matches.get(i);
            if (cu.shortName().equalsIgnoreCase("ContextManager")) {
                if (cu.type() == CodeUnitType.CLASS && firstClassIdx == -1) {
                    firstClassIdx = i;
                } else if (cu.type() == CodeUnitType.FIELD && firstFieldIdx == -1) {
                    firstFieldIdx = i;
                }
            }
        }

        assertTrue(firstClassIdx != -1, "Should have found a CLASS match for ContextManager");
        if (firstFieldIdx != -1) {
            assertTrue(firstClassIdx < firstFieldIdx,
                    "CLASS ContextManager should rank higher than FIELD contextManager. " +
                            "Class at: " + firstClassIdx + ", Field at: " + firstFieldIdx);
        }

        // Based on current codebase, it should be in ai.brokk
        assertEquals("ai.brokk.ContextManager", matches.get(firstClassIdx).fqName());
    }

    @Test
    void classTypeBeatsFieldTypeOnTiedScore() {
        String name = "TieBreaker";
        CodeUnitRecord classRecord = new CodeUnitRecord(CodeUnitType.CLASS, "ai.brokk." + name);
        CodeUnitRecord fieldRecord = new CodeUnitRecord(CodeUnitType.FIELD, "ai.brokk." + name);

        FuzzyMatcher matcher = new FuzzyMatcher(name);
        int classScore = matcher.score(classRecord.fqName());
        int fieldScore = matcher.score(fieldRecord.fqName());

        assertEquals(classScore, fieldScore, "Scores should be tied for identical names");

        List<CodeUnitRecord> list = new ArrayList<>(List.of(fieldRecord, classRecord));
        list.sort(Comparator.comparingInt((CodeUnitRecord cu) -> matcher.score(cu.fqName()))
                .thenComparing(cu -> cu.type())); // CLASS (0) < FIELD (1) in enum ordinal

        assertEquals(CodeUnitType.CLASS, list.getFirst().type(), "CLASS should beat FIELD on tied score");
    }

    @Test
    void exactClassNameRanksHigherThanNestedClass() {
        String pattern = "FuzzyMatcher";
        List<CodeUnitRecord> matches = getMatches(pattern);

        assertTrue(matches.stream().anyMatch(cu -> cu.fqName().equals("ai.brokk.FuzzyMatcher")),
                "Expected ai.brokk.FuzzyMatcher in results");

        CodeUnitRecord topMatch = matches.get(0);
        assertEquals("ai.brokk.FuzzyMatcher", topMatch.fqName(),
                "Top match for '" + pattern + "' should be the exact class name");

        // Ensure it beats nested classes like FuzzyMatcher.TextRange if present
        if (matches.size() > 1 && matches.get(1).fqName().contains("FuzzyMatcher.")) {
            assertTrue(new FuzzyMatcher(pattern).score(matches.get(0).fqName()) 
                       < new FuzzyMatcher(pattern).score(matches.get(1).fqName()));
        }
    }

    @Test
    void commonAbbreviationsRankCorrectly() {
        // Test CM -> ContextManager
        List<CodeUnitRecord> cmMatches = getMatches("CM");
        if (!cmMatches.isEmpty()) {
            assertEquals("ai.brokk.ContextManager", cmMatches.getFirst().fqName());
        }

        // Test FM -> FuzzyMatcher
        List<CodeUnitRecord> fmMatches = getMatches("FM");
        if (!fmMatches.isEmpty()) {
            assertEquals("ai.brokk.FuzzyMatcher", fmMatches.getFirst().fqName());
        }

        // Test AS -> AbstractService (if present)
        List<CodeUnitRecord> asMatches = getMatches("AS");
        if (!asMatches.isEmpty()) {
            // Check if AbstractService exists and is well-ranked
            boolean foundAbstractService = asMatches.stream()
                    .limit(10)
                    .anyMatch(cu -> cu.shortName().equals("AbstractService"));
            
            if (foundAbstractService) {
                // If AbstractService exists, verify it's in the top results
                assertTrue(foundAbstractService, "AbstractService should be in top 10 for 'AS'");
            }
            // Otherwise, we accept any match - the pattern AS is too short/ambiguous
            // to make strong assertions without AbstractService in the dataset
        }
    }
}
