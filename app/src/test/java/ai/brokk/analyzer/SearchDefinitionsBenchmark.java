package ai.brokk.analyzer;

import static ai.brokk.testutil.TestProject.createTestProject;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SearchDefinitionsBenchmark {
    private static TestProject testProject;
    private static JavaAnalyzer analyzer;

    @BeforeAll
    static void setUp() throws IOException {
        testProject = createTestProject("testcode-java", Languages.JAVA);
        analyzer = new JavaAnalyzer(testProject);
    }

    @Test
    void benchmarkSearchDefinitions() {
        var codeUnits = analyzer.getAllDeclarations();
        System.out.println("Total CodeUnits: " + codeUnits.size());

        // Warmup
        for (int i = 0; i < 5; i++) {
            analyzer.searchDefinitions("TestClass");
        }

        // Benchmark simple search
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            Set<CodeUnit> results = analyzer.searchDefinitions("TestClass");
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf(
                "searchDefinitions('TestClass') x100: %.2f ms (%.2f μs per call)%n",
                elapsed / 1_000_000.0, elapsed / 100_000.0);

        // Benchmark autocomplete
        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            Set<CodeUnit> results = analyzer.autocompleteDefinitions("Test");
        }
        elapsed = System.nanoTime() - start;
        System.out.printf(
                "autocompleteDefinitions('Test') x100: %.2f ms (%.2f μs per call)%n",
                elapsed / 1_000_000.0, elapsed / 100_000.0);

        // Benchmark direct pattern matching (greedy vs non-greedy)
        Pattern greedyPattern = Pattern.compile("(?i).*TestClass.*");
        Pattern nonGreedyPattern = Pattern.compile("(?i).*?TestClass.*?");

        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            analyzer.searchDefinitions(greedyPattern);
        }
        long greedyTime = System.nanoTime() - start;
        System.out.printf(
                "Greedy pattern x100: %.2f ms (%.2f μs per call)%n", greedyTime / 1_000_000.0, greedyTime / 100_000.0);

        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            analyzer.searchDefinitions(nonGreedyPattern);
        }
        long nonGreedyTime = System.nanoTime() - start;
        System.out.printf(
                "Non-greedy pattern x100: %.2f ms (%.2f μs per call)%n",
                nonGreedyTime / 1_000_000.0, nonGreedyTime / 100_000.0);

        double speedup = (double) greedyTime / nonGreedyTime;
        System.out.printf("Speedup: %.2fx%n", speedup);
    }
}
