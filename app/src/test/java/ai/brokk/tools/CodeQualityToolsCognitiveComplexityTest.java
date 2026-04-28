package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
