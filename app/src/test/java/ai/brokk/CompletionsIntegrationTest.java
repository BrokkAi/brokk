package ai.brokk;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestProject;
import ai.brokk.tools.CodeUnitExtractor;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link Completions} that validates symbol ranking
 * against real-world data using a {@link TestAnalyzer}.
 */
public class CompletionsIntegrationTest {

    private static final TestAnalyzer ANALYZER = new TestAnalyzer();
    private static CodeUnitExtractor.ExtractedCodeUnits extracted;

    @BeforeAll
    static void setup() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path projectRoot = root.resolve("src/main");
        TestProject project = new TestProject(projectRoot);

        extracted = CodeUnitExtractor.extract(project);
        List<CodeUnit> units = extracted.getCodeUnits();
        for (CodeUnit unit : units) {
            ANALYZER.addDeclaration(unit);
        }

        if (ANALYZER.getAllDeclarations().isEmpty()) {
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
    void testContextManagerRanking() {
        // Test lower-case full match
        assertTopMatch("contextmanager", "ai.brokk.ContextManager", CodeUnitType.CLASS);

        // Prefix typing scenario (the UX case)
        assertWithinTopN("contextman", "ai.brokk.ContextManager", CodeUnitType.CLASS, 5);

        // Test abbreviation match
        assertWithinTopN("cm", "ai.brokk.ContextManager", CodeUnitType.CLASS, 5);
    }

    @Test
    void testContextManagerPrefixRanking() {
        String query = "contextman";
        List<CodeUnit> results = Completions.completeSymbols(query, ANALYZER);
        FuzzyMatcher matcher = new FuzzyMatcher(query);

        System.out.println("Top 10 results for '" + query + "':");
        for (int i = 0; i < Math.min(10, results.size()); i++) {
            CodeUnit cu = results.get(i);
            int baseScore = matcher.score(cu.identifier());
            // Mirroring internal Completions scoring logic for debug visibility
            int typeBonus = (cu.kind() == CodeUnitType.CLASS) ? -10000 : 0;
            int depthBonus = (int) cu.fqName().chars().filter(ch -> ch == '.').count() * 10;
            int finalScore = baseScore == Integer.MAX_VALUE ? Integer.MAX_VALUE : baseScore + typeBonus + depthBonus;

            System.out.printf("%2d. [%s] %-40s (Score: %d)%n", i + 1, cu.kind(), cu.fqName(), finalScore);
        }

        assertWithinTopN(query, "ai.brokk.ContextManager", CodeUnitType.CLASS, 5);
    }

    private void assertTopMatch(String query, String expectedFqn, CodeUnitType expectedType) {
        List<CodeUnit> results = Completions.completeSymbols(query, ANALYZER);

        assertTrue(!results.isEmpty(), "Expected at least one result for query: " + query);

        CodeUnit top = results.getFirst();

        // Build debug output
        StringBuilder debugOutput = new StringBuilder();
        debugOutput.append("Results for '").append(query).append("':\n");
        results.stream().limit(5).forEach(r -> debugOutput.append("  ").append(r.kind()).append(" ").append(r.fqName()).append("\n"));

        assertEquals(
                expectedFqn, top.fqName(), String.format("Query '%s' should have ranked %s first\n%s", query, expectedFqn, debugOutput));
        assertEquals(
                expectedType,
                top.kind(),
                String.format("Query '%s' top match should be of type %s\n%s", query, expectedType, debugOutput));
    }

    private void assertWithinTopN(String query, String expectedFqn, CodeUnitType expectedType, int n) {
        List<CodeUnit> results = Completions.completeSymbols(query, ANALYZER);

        assertTrue(!results.isEmpty(), "Expected at least one result for query: " + query);

        // Build debug output
        StringBuilder debugOutput = new StringBuilder();
        debugOutput.append("Results for '").append(query).append("' (top ").append(n).append("):\n");
        results.stream().limit(n).forEach(r -> debugOutput.append("  ").append(r.kind()).append(" ").append(r.fqName()).append("\n"));

        int limit = Math.min(n, results.size());
        int idx = IntStream.range(0, limit)
                .filter(i -> expectedFqn.equals(results.get(i).fqName()))
                .findFirst()
                .orElse(-1);

        assertTrue(idx >= 0, String.format("Query '%s' should have ranked %s within top %d\n%s", query, expectedFqn, n, debugOutput));

        assertEquals(
                expectedType,
                results.get(idx).kind(),
                String.format(
                        "Query '%s' match %s within top %d should be of type %s\n%s", query, expectedFqn, n, expectedType, debugOutput));
    }
}
