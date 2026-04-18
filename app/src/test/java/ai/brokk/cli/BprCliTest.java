package ai.brokk.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BprCliTest {

    @TempDir
    Path tempDir;

    @Test
    void finalExitCode_uses_nonzero_code_for_single_turn_codeagent_failures() {
        assertEquals(0, BprCli.finalExitCode(true, true, TaskResult.StopReason.SUCCESS));
        assertEquals(2, BprCli.finalExitCode(true, true, TaskResult.StopReason.BUILD_ERROR));
        assertEquals(0, BprCli.finalExitCode(true, false, TaskResult.StopReason.BUILD_ERROR));
        assertEquals(0, BprCli.finalExitCode(false, true, TaskResult.StopReason.BUILD_ERROR));
    }

    @Test
    void addSummariesToTaskContextDropsFileAndClassSummariesCoveredByFullFile() throws Exception {
        var pf = new ProjectFile(tempDir, "src/p/Foo.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("package p; public class Foo {}");

        var otherFile = new ProjectFile(tempDir, "src/p/Bar.java");
        otherFile.write("package p; public class Bar {}");

        var analyzer = new TestAnalyzer(
                List.of(
                        new CodeUnit(pf, CodeUnitType.CLASS, "p", "Foo"),
                        new CodeUnit(otherFile, CodeUnitType.CLASS, "p", "Bar")),
                Map.of());
        var project = new TestProject(tempDir, Languages.JAVA);
        var cm = new TestContextManager(project, new TestConsoleIO(), java.util.Set.of(), analyzer);

        var workspace = new Context(cm).addFragments(new ContextFragments.ProjectPathFragment(pf, cm));
        List<ContextFragment> cachedFragments = List.of(
                new ContextFragments.SummaryFragment(cm, pf.toString(), ContextFragment.SummaryType.FILE_SKELETONS),
                new ContextFragments.SummaryFragment(cm, "p.Foo", ContextFragment.SummaryType.CODEUNIT_SKELETON),
                new ContextFragments.SummaryFragment(cm, "p.Bar", ContextFragment.SummaryType.CODEUNIT_SKELETON));

        var updated = BprCli.addSummariesToTaskContext(workspace, cachedFragments);
        var summaries = updated.allFragments()
                .filter(ContextFragments.SummaryFragment.class::isInstance)
                .map(ContextFragments.SummaryFragment.class::cast)
                .toList();

        assertEquals(1, summaries.size());
        var remaining = summaries.getFirst();
        assertEquals("p.Bar", remaining.getTargetIdentifier());
        assertTrue(updated.allFragments().anyMatch(ContextFragments.ProjectPathFragment.class::isInstance));
    }
}
