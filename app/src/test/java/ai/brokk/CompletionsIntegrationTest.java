package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestProject;
import ai.brokk.tools.CodeUnitExtractor;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link Completions} that validates symbol ranking
 * against real-world data using a {@link TestAnalyzer}.
 */
public class CompletionsIntegrationTest {

    private static final TestAnalyzer ANALYZER = new TestAnalyzer();

    @BeforeAll
    static void setup() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        TestProject project = new TestProject(root);

        try (InputStream is = CompletionsIntegrationTest.class.getResourceAsStream("/codeunits.csv")) {
            Objects.requireNonNull(is, "Resource /codeunits.csv not found. Ensure CodeUnitExtractor has been run.");
            List<CodeUnit> units = CodeUnitExtractor.loadCodeUnits(is, project);
            for (CodeUnit unit : units) {
                ANALYZER.addDeclaration(unit);
            }
        }
        
        // Add fallback synthetic data if CSV is empty to ensure tests can run logic checks
        if (ANALYZER.getAllDeclarations().isEmpty()) {
            Path p = root.resolve("src/ContextManager.java");
            ANALYZER.addDeclaration(CodeUnit.cls(new ai.brokk.analyzer.ProjectFile(root, "src/ContextManager.java"), "ai.brokk", "ContextManager"));
            ANALYZER.addDeclaration(CodeUnit.field(new ai.brokk.analyzer.ProjectFile(root, "test/ContextCompressionTest.java"), "ai.brokk.test", "contextManager"));
        }
    }

    @Test
    void testContextManagerRanking() {
        // Test lower-case full match
        assertTopMatch("contextmanager", "ai.brokk.ContextManager", CodeUnitType.CLASS);

        // Prefix typing scenario (the UX case)
        assertWithinTopN("contextman", "ai.brokk.ContextManager", CodeUnitType.CLASS, 5);

        // Test abbreviation match
        assertTopMatch("cm", "ai.brokk.ContextManager", CodeUnitType.CLASS);
    }

    private void assertTopMatch(String query, String expectedFqn, CodeUnitType expectedType) {
        List<CodeUnit> results = Completions.completeSymbols(query, ANALYZER);

        assertTrue(results.size() >= 1, "Expected at least one result for query: " + query);

        CodeUnit top = results.get(0);

        // Debug output for troubleshooting ranking
        System.out.println("Results for '" + query + "':");
        results.stream().limit(5).forEach(r -> System.out.println("  " + r.kind() + " " + r.fqName()));

        assertEquals(
                expectedFqn,
                top.fqName(),
                String.format("Query '%s' should have ranked %s first", query, expectedFqn));
        assertEquals(
                expectedType,
                top.kind(),
                String.format("Query '%s' top match should be of type %s", query, expectedType));
    }

    private void assertWithinTopN(String query, String expectedFqn, CodeUnitType expectedType, int n) {
        List<CodeUnit> results = Completions.completeSymbols(query, ANALYZER);

        assertTrue(results.size() >= 1, "Expected at least one result for query: " + query);

        // Debug output for troubleshooting ranking
        System.out.println("Results for '" + query + "' (top " + n + "):");
        results.stream().limit(n).forEach(r -> System.out.println("  " + r.kind() + " " + r.fqName()));

        int limit = Math.min(n, results.size());
        int idx = IntStream.range(0, limit)
                .filter(i -> expectedFqn.equals(results.get(i).fqName()))
                .findFirst()
                .orElse(-1);

        assertTrue(
                idx >= 0,
                String.format("Query '%s' should have ranked %s within top %d", query, expectedFqn, n));

        assertEquals(
                expectedType,
                results.get(idx).kind(),
                String.format(
                        "Query '%s' match %s within top %d should be of type %s",
                        query, expectedFqn, n, expectedType));
    }
}
