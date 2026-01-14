package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.project.IProject;
import ai.brokk.tools.CodeUnitExtractor;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link FuzzyMatcher} using real-world data loaded via {@link CodeUnitExtractor}.
 */
public class FuzzyMatcherIntegrationTest {

    private static final List<CodeUnit> CODE_UNITS = new ArrayList<>();

    @BeforeAll
    static void loadData() throws Exception {
        InputStream is = FuzzyMatcherIntegrationTest.class.getResourceAsStream("/codeunits.csv");
        Path absoluteRoot = Path.of("").toAbsolutePath();

        // Minimal project to satisfy loadCodeUnits requirements
        IProject mockProject = new IProject() {
            @Override public Path getRoot() { return absoluteRoot; }
            @Override public void close() {}
        };

        if (is != null) {
            try (is) {
                CODE_UNITS.addAll(CodeUnitExtractor.loadCodeUnits(is, mockProject));
            }
        }

        // If dataset is still empty (missing resource or empty file), add synthetic data for logic testing
        if (CODE_UNITS.isEmpty()) {
            CODE_UNITS.add(CodeUnit.cls(new ai.brokk.analyzer.ProjectFile(absoluteRoot, "FuzzyMatcher.java"), "ai.brokk", "FuzzyMatcher"));
            CODE_UNITS.add(CodeUnit.cls(new ai.brokk.analyzer.ProjectFile(absoluteRoot, "ContextManager.java"), "ai.brokk", "ContextManager"));
            CODE_UNITS.add(CodeUnit.fn(new ai.brokk.analyzer.ProjectFile(absoluteRoot, "Util.java"), "ai.brokk", "calculateScore"));
        }
    }

    @Test
    void testMatchesOnIdentifiersAndShortNames() {
        // Use a name known to be in synthetic or real data
        String query = "FuzzyMatcher";
        FuzzyMatcher matcher = new FuzzyMatcher(query);
        
        boolean found = CODE_UNITS.stream()
                .anyMatch(cu -> matcher.matches(cu.identifier()) || matcher.matches(cu.shortName()));
        
        assertTrue(found, "Should match '" + query + "' identifier or shortName in the dataset.");
    }

    @Test
    void testCamelHumpMatching() {
        // Find a suitable CamelCase candidate from the data
        CodeUnit candidate = CODE_UNITS.stream()
                .filter(cu -> cu.shortName().length() > 5 && !cu.shortName().equals(cu.shortName().toLowerCase()))
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
        assertTrue(matcher.matches(shortName), 
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
