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
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.Environment;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

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

    @Test
    void analyzeBuildsAndPersistsAnalyzerState() throws Exception {
        Files.writeString(tempDir.resolve("A.java"), "public class A {}");
        try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern("A.java").call();
            git.commit().setSign(false).setMessage("Initial commit").call();
        }

        var exitCode = new CommandLine(new BprCli()).execute("--project", tempDir.toString(), "--analyze");

        assertEquals(0, exitCode);
        try (var project = MainProject.forTests(tempDir)) {
            assertTrue(Files.exists(Languages.JAVA.getStoragePath(project)));
        }
    }

    @Test
    void analyzeCannotBeCombinedWithOtherActions() {
        var exitCode = new CommandLine(new BprCli()).execute("--project", tempDir.toString(), "--analyze", "--build");
        assertEquals(1, exitCode);
    }

    @Test
    void parseCommaDelimitedPreservingDuplicates_preservesOrderAndDuplicates() throws Exception {
        Method method = BprCli.class.getDeclaredMethod("parseCommaDelimitedPreservingDuplicates", String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var parsed = (List<String>) method.invoke(null, "model-a, model-b,model-a,,model-c");

        assertEquals(List.of("model-a", "model-b", "model-a", "model-c"), parsed);
    }

    @Test
    void multiCodeModelRequiresSingleTurnMode() {
        Assumptions.assumeFalse(Environment.isBooleanFlagEnabled(System.getenv("BRK_CODEAGENT_SINGLE_TURN")));

        var exitCode = new CommandLine(new BprCli())
                .execute("--project", tempDir.toString(), "--code", "Fix it", "--codemodel", "model-a,model-b");

        assertEquals(1, exitCode);
    }

    @Test
    void codeGateModelRequiresMultiModelCodeRun() {
        var exitCode = new CommandLine(new BprCli())
                .execute(
                        "--project",
                        tempDir.toString(),
                        "--code",
                        "Fix it",
                        "--codemodel",
                        "model-a",
                        "--code-gate-model",
                        "model-a");

        assertEquals(1, exitCode);
    }

    @Test
    void writeSftGenManifest_writesRelativeCodeHistoryPath() throws Exception {
        Constructor<?> attemptCtor = Class.forName("ai.brokk.cli.BprCli$CodeModelAttempt")
                .getDeclaredConstructor(String.class, StreamingChatModel.class);
        attemptCtor.setAccessible(true);
        Object attempt = attemptCtor.newInstance("model-a", null);

        Constructor<?> entryCtor = Class.forName("ai.brokk.cli.BprCli$CodeAttemptManifestEntry")
                .getDeclaredConstructor(
                        int.class,
                        String.class,
                        String.class,
                        int.class,
                        String.class,
                        String.class,
                        String.class,
                        boolean.class);
        entryCtor.setAccessible(true);
        Object entry = entryCtor.newInstance(
                0,
                "model-a",
                ".brokk/llm-history/2026-01-01 Code Goal",
                0,
                "SUCCESS",
                null,
                "diff --git a/foo b/foo\n",
                true);

        Method writeManifest =
                BprCli.class.getDeclaredMethod("writeSftGenManifest", Path.class, String.class, List.class, List.class);
        writeManifest.setAccessible(true);
        writeManifest.invoke(null, tempDir, "model-a", List.of(attempt), List.of(entry));

        var manifestPath = tempDir.resolve(".brokk").resolve("sft_gen").resolve("attempts.json");
        assertTrue(Files.exists(manifestPath));
        var manifest = Files.readString(manifestPath);
        assertTrue(manifest.contains("\"requestedModels\""));
        assertTrue(manifest.contains("\"model-a\""));
        assertTrue(manifest.contains("\"codeHistoryDir\" : \".brokk/llm-history/2026-01-01 Code Goal\""));
    }
}
