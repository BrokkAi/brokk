package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.testutil.TestProject;
import ai.brokk.tools.CodeUnitExtractor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link FuzzyMatcher} using real-world data loaded via {@link CodeUnitExtractor}.
 */
public class FuzzyMatcherIntegrationTest {

    private static final List<CodeUnit> CODE_UNITS = new ArrayList<>();
    private static CodeUnitExtractor.ExtractedCodeUnits extracted;

    @BeforeAll
    static void loadData() throws Exception {
        Path projectRoot = CodeUnitExtractor.resolveProjectSourceRoot();
        TestProject project = new TestProject(projectRoot);

        extracted = CodeUnitExtractor.extract(project);
        CODE_UNITS.addAll(extracted.getCodeUnits());

        if (CODE_UNITS.isEmpty()) {
            fail("Unable to load integration test data");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (extracted != null) {
            extracted.close();
        }
    }

    @Test
    void testMatchesOnIdentifiersAndShortNames() {
        // Use a name known to be in synthetic or real data
        String query = "FuzzyMatcher";
        FuzzyMatcher matcher = new FuzzyMatcher(query);

        boolean found =
                CODE_UNITS.stream().anyMatch(cu -> matcher.matches(cu.identifier()) || matcher.matches(cu.shortName()));

        assertTrue(found, "Should match '" + query + "' identifier or shortName in the dataset.");
    }

    @Test
    void testCamelHumpMatching() {
        // Find a suitable CamelCase candidate from the data
        CodeUnit candidate = CODE_UNITS.stream()
                .filter(cu -> cu.shortName().length() > 5
                        && !cu.shortName().equals(cu.shortName().toLowerCase()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No CamelCase names found in dataset"));

        String shortName = candidate.shortName();
        // Generate a CamelHump pattern (e.g., "ContextManager" -> "CM")
        StringBuilder pattern = new StringBuilder();
        for (char c : shortName.toCharArray()) {
            if (Character.isUpperCase(c)) pattern.append(c);
        }

        if (pattern.isEmpty()) {
            pattern.append(shortName.substring(0, 1).toUpperCase());
        }

        FuzzyMatcher matcher = new FuzzyMatcher(pattern.toString());
        assertTrue(
                matcher.matches(shortName),
                "FuzzyMatcher should match '" + pattern + "' against '" + shortName + "' via CamelHumps");
    }

    @Test
    void testScoreValidity() {
        String query = "Fuzzy";
        FuzzyMatcher matcher = new FuzzyMatcher(query);

        CodeUnit exact = CODE_UNITS.stream()
                .filter(cu -> cu.shortName().equalsIgnoreCase(query))
                .findFirst()
                .orElse(null);

        if (exact != null) {
            int score = matcher.score(exact.shortName());
            assertNotEquals(Integer.MAX_VALUE, score, "Score for valid match should not be MAX_VALUE");
            assertTrue(score <= 0, "Exact or prefix matches should have very low (good) scores");
        }
    }

    @Test
    void testHierarchicalMatching() {
        // Find an FQN with at least one dot
        CodeUnit cuWithDot = CODE_UNITS.stream()
                .filter(cu -> cu.fqName().contains("."))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No hierarchical FQNs found in dataset"));

        String fqName = cuWithDot.fqName();
        // Take a prefix that includes a dot
        int dotIdx = fqName.indexOf('.');
        String query = fqName.substring(0, dotIdx + 2); // e.g., "ai.b"

        FuzzyMatcher matcher = new FuzzyMatcher(query);
        assertTrue(matcher.matches(fqName), "Should match partial FQN '" + query + "' against '" + fqName + "'");
    }
}
