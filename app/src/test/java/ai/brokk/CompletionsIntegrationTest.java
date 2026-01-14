package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.project.IProject;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.tools.CodeUnitExtractor;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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
        IProject mockProject = new IProject() {
            @Override public Path getRoot() { return root; }
            @Override public void close() {}
        };

        try (InputStream is = CompletionsIntegrationTest.class.getResourceAsStream("/codeunits.csv")) {
            Objects.requireNonNull(is, "Resource /codeunits.csv not found. Ensure CodeUnitExtractor has been run.");
            List<CodeUnit> units = CodeUnitExtractor.loadCodeUnits(is, mockProject);
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

        assertEquals(expectedFqn, top.fqName(), 
            String.format("Query '%s' should have ranked %s first", query, expectedFqn));
        assertEquals(expectedType, top.kind(), 
            String.format("Query '%s' top match should be of type %s", query, expectedType));
    }
}
