package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeQualityToolsCognitiveComplexityTest {

    @Test
    void computeCognitiveComplexityReportsOnlyMethodsAboveThreshold(@TempDir Path root) throws IOException {
        ProjectFile file = new ProjectFile(root, "Example.java");
        Files.writeString(file.absPath(), "class Example {}");

        var complex = new CodeUnit(file, CodeUnitType.FUNCTION, "Example", "complex", "()", false);
        var simple = new CodeUnit(file, CodeUnitType.FUNCTION, "Example", "simple", "()", false);
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(complex);
        analyzer.addDeclaration(simple);
        analyzer.setCognitiveComplexity(complex, 16);
        analyzer.setCognitiveComplexity(simple, 2);

        var project = new TestProject(root, Languages.JAVA);
        var tools = new CodeQualityTools(new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer));

        String report = tools.computeCognitiveComplexity(List.of("Example.java"), 15);

        assertTrue(report.contains("Cognitive complexity (threshold: 15):"), report);
        assertTrue(report.contains("- Example.complex: 16"), report);
        assertFalse(report.contains("Example.simple"), report);
    }

    @Test
    void computeCognitiveComplexityReportsNoFindings(@TempDir Path root) throws IOException {
        ProjectFile file = new ProjectFile(root, "Example.java");
        Files.writeString(file.absPath(), "class Example {}");

        var method = new CodeUnit(file, CodeUnitType.FUNCTION, "Example", "method", "()", false);
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(method);
        analyzer.setCognitiveComplexity(method, 3);

        var project = new TestProject(root, Languages.JAVA);
        var tools = new CodeQualityTools(new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer));

        String report = tools.computeCognitiveComplexity(List.of("Example.java"), 15);

        assertTrue(report.contains("No methods exceeded the cognitive complexity threshold of 15."), report);
    }

    @Test
    void computeCognitiveComplexityUsesBatchResultAndSkipsSyntheticFunctions(@TempDir Path root) throws IOException {
        ProjectFile file = new ProjectFile(root, "Example.java");
        Files.writeString(file.absPath(), "class Example {}");

        var complex = new CodeUnit(file, CodeUnitType.FUNCTION, "Example", "complex", "()", false);
        var synthetic = new CodeUnit(file, CodeUnitType.FUNCTION, "Example", "$anon", "()", true);
        var analyzer = new BatchOnlyAnalyzer(Map.of(complex, 16, synthetic, 99));
        analyzer.addDeclaration(complex);
        analyzer.addDeclaration(synthetic);

        var project = new TestProject(root, Languages.JAVA);
        var tools = new CodeQualityTools(new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer));

        String report = tools.computeCognitiveComplexity(List.of("Example.java"), 15);

        assertTrue(report.contains("- Example.complex: 16"), report);
        assertFalse(report.contains("$anon"), report);
        assertTrue(analyzer.batchCalled, "Expected file-level batch API to be used");
        assertFalse(analyzer.singleMethodCalled, "Expected per-method API not to be used by the tool");
    }

    @Test
    void computeCognitiveComplexityReportsJavaScriptFindings() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        """
                function complex(a, b, c) {
                    if (a) {
                        for (const item in b) {
                            if (item.ready && c) {
                                return item;
                            }
                        }
                    }
                    return null;
                }

                function simple() {
                    return 1;
                }
                """,
                        "src/sample.js")
                .build()) {
            var contextManager = new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer());
            var tools = new CodeQualityTools(contextManager);

            String report = tools.computeCognitiveComplexity(List.of("src/sample.js"), 4);

            assertTrue(report.contains("Cognitive complexity (threshold: 4):"), report);
            assertTrue(report.contains("- src.complex: 7"), report);
            assertFalse(report.contains("src.simple"), report);
        }
    }

    private static final class BatchOnlyAnalyzer extends TestAnalyzer {
        private final Map<CodeUnit, Integer> complexities;
        private boolean batchCalled;
        private boolean singleMethodCalled;

        private BatchOnlyAnalyzer(Map<CodeUnit, Integer> complexities) {
            this.complexities = new LinkedHashMap<>(complexities);
        }

        @Override
        public Map<CodeUnit, Integer> computeCognitiveComplexities(ProjectFile file) {
            batchCalled = true;
            return complexities;
        }

        @Override
        public int computeCognitiveComplexity(CodeUnit cu) {
            singleMethodCalled = true;
            return super.computeCognitiveComplexity(cu);
        }
    }
}
