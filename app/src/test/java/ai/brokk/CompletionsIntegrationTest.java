package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.Completions.ScoredCodeUnit;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestProject;
import ai.brokk.tools.CodeUnitExtractor;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link Completions} that validates symbol ranking
 * against real-world data using a {@link TestAnalyzer}.
 */
public class CompletionsIntegrationTest {

    private static final TestAnalyzer ANALYZER = new TestAnalyzer();
    private static CodeUnitExtractor.ExtractedCodeUnits extracted;

    @BeforeAll
    static void setup() throws Exception {
        Path projectRoot = CodeUnitExtractor.resolveProjectSourceRoot();
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
        List<CodeUnit> candidates = ANALYZER.getAllDeclarations();

        // Use the actual Completions scoring logic
        List<ScoredCodeUnit> scored = Completions.scoreCodeUnits(query, candidates);

        // Sort by score (ascending) using the same tie-breakers as the production code
        var top = scored.stream().sorted(Completions.scoredCodeUnitComparator()).toList();

        // Build debug output
        StringBuilder debugOutput = new StringBuilder();
        debugOutput.append("Top 10 scored results for '").append(query).append("':\n");
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            ScoredCodeUnit sc = top.get(i);
            debugOutput.append(String.format(
                    "%2d. [%s] %-40s (Score: %d)%n",
                    i + 1, sc.codeUnit().kind(), sc.codeUnit().fqName(), sc.score()));
        }

        assertFalse(top.isEmpty(), "Expected at least one scored result for query: " + query + "\n" + debugOutput);

        // Verify ContextManager ranks in top 5
        int rank = -1;
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            if ("ai.brokk.ContextManager".equals(top.get(i).codeUnit().fqName())) {
                rank = i;
                break;
            }
        }

        assertTrue(
                rank >= 0,
                "Query '" + query + "' should have ranked ai.brokk.ContextManager within top 5\n" + debugOutput);
        assertEquals(
                CodeUnitType.CLASS,
                top.get(rank).codeUnit().kind(),
                "Matched unit should be of type CLASS\n" + debugOutput);
    }

    private void assertTopMatch(String query, String expectedFqn, CodeUnitType expectedType) {
        List<CodeUnit> results = Completions.completeSymbols(query, ANALYZER);

        assertTrue(!results.isEmpty(), "Expected at least one result for query: " + query);

        CodeUnit top = results.getFirst();

        // Build debug output
        StringBuilder debugOutput = new StringBuilder();
        debugOutput.append("Results for '").append(query).append("':\n");
        results.stream().limit(5).forEach(r -> debugOutput
                .append("  ")
                .append(r.kind())
                .append(" ")
                .append(r.fqName())
                .append("\n"));

        assertEquals(
                expectedFqn,
                top.fqName(),
                String.format("Query '%s' should have ranked %s first\n%s", query, expectedFqn, debugOutput));
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
        debugOutput
                .append("Results for '")
                .append(query)
                .append("' (top ")
                .append(n)
                .append("):\n");
        results.stream().limit(n).forEach(r -> debugOutput
                .append("  ")
                .append(r.kind())
                .append(" ")
                .append(r.fqName())
                .append("\n"));

        int limit = Math.min(n, results.size());
        int idx = IntStream.range(0, limit)
                .filter(i -> expectedFqn.equals(results.get(i).fqName()))
                .findFirst()
                .orElse(-1);

        assertTrue(
                idx >= 0,
                String.format(
                        "Query '%s' should have ranked %s within top %d\n%s", query, expectedFqn, n, debugOutput));

        assertEquals(
                expectedType,
                results.get(idx).kind(),
                String.format(
                        "Query '%s' match %s within top %d should be of type %s\n%s",
                        query, expectedFqn, n, expectedType, debugOutput));
    }
}
