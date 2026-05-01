package ai.brokk.executor.staticanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticAnalysisLeadExpansionServiceTest {
    @Test
    void expandLeads_findsUsageConnectedFiles(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/User.java", "package p; class User { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "completed",
                response.state(),
                response.events().getLast().outcome().message());
        assertFalse(response.seeds().isEmpty());
        var seed = response.seeds().getFirst();
        assertEquals("src/main/java/p/User.java", seed.file());
        assertEquals("usage_expansion", seed.selection().kind());
        assertEquals(
                List.of("reportExceptionHandlingSmells", "reportCommentDensityForFiles", "computeCognitiveComplexity"),
                seed.suggestedTools());
        assertEquals("usage_connectivity", seed.selection().signals().getFirst().kind());
    }

    @Test
    void expandLeads_suggestsTestAssertionToolForTestFiles(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        var testFile =
                javaFile(root, "src/test/java/p/TargetTest.java", "package p; class TargetTest { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        analyzer.setContainsTests(testFile, true);
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "completed",
                response.state(),
                response.events().getLast().outcome().message());
        var seed = response.seeds().getFirst();
        assertEquals("src/test/java/p/TargetTest.java", seed.file());
        assertEquals(
                List.of(
                        "reportExceptionHandlingSmells",
                        "reportCommentDensityForFiles",
                        "computeCognitiveComplexity",
                        "reportTestAssertionSmells"),
                seed.suggestedTools());
    }

    @Test
    void expandLeads_dedupesKnownFiles(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/User.java", "package p; class User { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java", "src/main/java/p/User.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "skipped",
                response.state(),
                response.events().getLast().outcome().message());
        assertTrue(response.seeds().isEmpty());
    }

    @Test
    void expandLeads_normalizesKnownFileSeparators(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/User.java", "package p; class User { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src\\main\\java\\p\\Target.java", "src\\main\\java\\p\\User.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "skipped",
                response.state(),
                response.events().getLast().outcome().message());
        assertTrue(response.seeds().isEmpty());
    }

    private static StaticAnalysisLeadExpansionService service(Path root, TestAnalyzer analyzer) {
        return new StaticAnalysisLeadExpansionService(
                new TestContextManager(new TestProject(root, Languages.JAVA), new TestConsoleIO(), Set.of(), analyzer));
    }

    private static ProjectFile javaFile(Path root, String relPath, String source) throws Exception {
        var file = new ProjectFile(root, relPath);
        Files.createDirectories(file.absPath().getParent());
        Files.writeString(file.absPath(), source);
        return file;
    }
}
