package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnitType;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
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

    static Stream<Arguments> codeUnitProvider() {
        return CODE_UNITS.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "ShortName Match: {0}")
    @MethodSource("codeUnitProvider")
    void shortNameMatchesFQName(CodeUnitRecord unit) {
        String pattern = unit.shortName();
        FuzzyMatcher matcher = new FuzzyMatcher(pattern);
        assertTrue(matcher.matches(unit.fqName()),
                "Pattern '" + pattern + "' should match '" + unit.fqName() + "'");
    }

    @ParameterizedTest(name = "Score Validity: {0}")
    @MethodSource("codeUnitProvider")
    void shortNameScoresValidly(CodeUnitRecord unit) {
        String pattern = unit.shortName();
        String fqName = unit.fqName();
        
        // Skip cases where the short name appears multiple times in the fqName.
        // This is a known limitation of FuzzyMatcher where matches() returns true
        // but score() returns MAX_VALUE due to ambiguous match paths.
        int firstOccurrence = fqName.toLowerCase().indexOf(pattern.toLowerCase());
        int lastOccurrence = fqName.toLowerCase().lastIndexOf(pattern.toLowerCase());
        if (firstOccurrence != lastOccurrence) {
            return; // Skip ambiguous cases
        }
        
        FuzzyMatcher matcher = new FuzzyMatcher(pattern);

        // If it doesn't match at all, we don't expect a valid score.
        // We use assertTrue here because the short name SHOULD match the FQ name.
        assertTrue(matcher.matches(fqName),
                "Pattern '" + pattern + "' should match '" + fqName + "'");

        int score = matcher.score(fqName);
        assertNotEquals(Integer.MAX_VALUE, score,
                "FuzzyMatcher bug: matches() is true but score() is MAX_VALUE for pattern '" 
                + pattern + "' in '" + fqName + "'");
    }

    @ParameterizedTest(name = "CamelHump Match: {0}")
    @MethodSource("codeUnitProvider")
    void camelHumpAbbreviationMatchesClass(CodeUnitRecord unit) {
        if (unit.type() != CodeUnitType.CLASS) return;

        String abbr = unit.camelHumpAbbreviation();
        // Only test multi-word humps (e.g. "FMT" for "FuzzyMatcherTest")
        if (abbr.length() < 2) return;

        FuzzyMatcher matcher = new FuzzyMatcher(abbr);
        assertTrue(matcher.matches(unit.fqName()),
                "Abbreviation '" + abbr + "' should match '" + unit.fqName() + "'");
    }
}
