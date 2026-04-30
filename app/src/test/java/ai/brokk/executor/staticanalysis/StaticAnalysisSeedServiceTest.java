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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticAnalysisSeedServiceTest {
    @Test
    void fetchSeeds_returnsRankedSizeComplexitySeed(@TempDir Path root) throws Exception {
        var file = javaFile(root, "src/main/java/Complex.java", "class Complex { void run() {} }");
        var method = new CodeUnit(file, CodeUnitType.FUNCTION, "Complex", "run", "()", false);
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(method);
        analyzer.setComplexity(method, 22);
        var service = service(root, analyzer);

        var response = service.fetchSeeds(new StaticAnalysisSeedDtos.NormalizedRequest("scan-1", 5, 15_000, false));

        assertEquals("completed", response.state());
        assertEquals(1, response.seeds().size());
        var seed = response.seeds().getFirst();
        assertEquals("src/main/java/Complex.java", seed.file());
        assertEquals("size_complexity", seed.selection().kind());
        assertEquals(1, seed.rank());
        assertEquals(1, seed.selection().rank());
        assertFalse(seed.selection().signals().isEmpty());
        assertTrue(seed.suggestedTools().contains("reportLongMethodAndGodObjectSmells"));
        assertTrue(seed.suggestedAgents().contains("code-quality-size-sprawl"));
    }

    @Test
    void fetchSeeds_returnsWeightedSampleFallbackWhenNoSmellSignals(@TempDir Path root) throws Exception {
        var file = javaFile(root, "src/main/java/Simple.java", "class Simple {}");
        var cu = new CodeUnit(file, CodeUnitType.CLASS, "", "Simple", null, false);
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(cu);
        var service = service(root, analyzer);

        var response = service.fetchSeeds(new StaticAnalysisSeedDtos.NormalizedRequest("scan-1", 5, 15_000, false));

        assertEquals("completed", response.state());
        assertEquals(1, response.seeds().size());
        assertEquals("weighted_sample", response.seeds().getFirst().selection().kind());
        assertTrue(response.seeds().getFirst().suggestedTools().isEmpty());
    }

    @Test
    void fetchSeeds_skipsWhenAnalyzerHasNoFiles(@TempDir Path root) {
        var service = service(root, new TestAnalyzer());

        var response = service.fetchSeeds(new StaticAnalysisSeedDtos.NormalizedRequest("scan-1", 5, 15_000, false));

        assertEquals("skipped", response.state());
        assertTrue(response.seeds().isEmpty());
        assertEquals(
                "STATIC_SEED_NO_INPUTS", response.events().getLast().outcome().code());
    }

    private static StaticAnalysisSeedService service(Path root, TestAnalyzer analyzer) {
        return new StaticAnalysisSeedService(
                new TestContextManager(new TestProject(root, Languages.JAVA), new TestConsoleIO(), Set.of(), analyzer));
    }

    private static ProjectFile javaFile(Path root, String relPath, String source) throws Exception {
        var file = new ProjectFile(root, relPath);
        Files.createDirectories(file.absPath().getParent());
        Files.writeString(file.absPath(), source);
        return file;
    }
}
