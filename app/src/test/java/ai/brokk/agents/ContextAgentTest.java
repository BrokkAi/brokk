package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.project.ModelProperties;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ContextAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void testRecommendationsBecomeFileSkeletonSummaryFragments() throws InterruptedException {
        Path root = tempDir.toAbsolutePath();
        var analyzer = new TestAnalyzer(List.of(), Map.of());
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        StreamingChatModel model = cm.getService().getModel(ModelProperties.ModelType.SUMMARIZE);
        var agent = new ContextAgent(cm, model, "test");

        var testFile = cm.toFile("src/test/java/pkg/FooTest.java");
        List<ContextFragment> fragments = agent.createResult(
                new ContextAgent.LlmRecommendation(Set.of(), Set.of(testFile), Set.of(), null), Set.of());

        var summaryFragments = fragments.stream()
                .filter(f -> f instanceof ContextFragments.SummaryFragment)
                .map(f -> (ContextFragments.SummaryFragment) f)
                .toList();

        assertEquals(1, summaryFragments.size());
        assertEquals(
                ContextFragment.SummaryType.FILE_SKELETONS,
                summaryFragments.getFirst().getSummaryType());
        assertEquals(
                testFile.getRelPath().toString(), summaryFragments.getFirst().getTargetIdentifier());
    }

    @Test
    void duplicatesBetweenFilesAndTestsPreferProjectPathFragment_andDoNotDuplicate() throws InterruptedException {
        Path root = tempDir.toAbsolutePath();
        var analyzer = new TestAnalyzer(List.of(), Map.of());
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        StreamingChatModel model = cm.getService().quickestModel();
        var agent = new ContextAgent(cm, model, "test");

        var samePath = cm.toFile("src/shared/Dupe.java");
        List<ContextFragment> fragments = agent.createResult(
                new ContextAgent.LlmRecommendation(Set.of(samePath), Set.of(samePath), Set.of(), null), Set.of());

        var summaryFileSkeletonCount = fragments.stream()
                .filter(f -> f instanceof ContextFragments.SummaryFragment sf
                        && sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS
                        && sf.getTargetIdentifier().equals(samePath.getRelPath().toString()))
                .count();
        assertEquals(0, summaryFileSkeletonCount);

        var projectPathFragments = fragments.stream()
                .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                .map(f -> (ContextFragments.ProjectPathFragment) f)
                .filter(pf -> pf.file().equals(samePath))
                .toList();

        assertEquals(1, projectPathFragments.size());
        assertInstanceOf(ContextFragments.ProjectPathFragment.class, projectPathFragments.getFirst());
    }

    @Test
    void alreadyExistingWorkspacePathsAreNotReadded_asTestOrFile() throws InterruptedException {
        Path root = tempDir.toAbsolutePath();
        var analyzer = new TestAnalyzer(List.of(), Map.of());
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        StreamingChatModel model = cm.getService().quickestModel();
        var agent = new ContextAgent(cm, model, "test");

        var existing = cm.toFile("src/already/InWorkspace.java");
        List<ContextFragment> fragments = agent.createResult(
                new ContextAgent.LlmRecommendation(Set.of(existing), Set.of(existing), Set.of(), null),
                Set.of(existing));

        assertTrue(fragments.isEmpty());
    }

    @Test
    void filterAnonymousSummaries_removesAnonEntries() {
        var file = new ProjectFile(tempDir, "src/pkg/Foo.java");

        var regular = CodeUnit.cls(file, "pkg", "Foo");
        var anon = CodeUnit.cls(file, "pkg", "Foo$anon$1");

        var input = new LinkedHashMap<CodeUnit, String>();
        input.put(regular, "class Foo {}");
        input.put(anon, "class Foo$anon$1 {}");

        var out = input.entrySet().stream()
                .filter(e -> !e.getKey().isAnonymous())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new));

        assertTrue(out.containsKey(regular));
        assertFalse(out.containsKey(anon));
        assertTrue(out.values().stream().noneMatch(s -> s.contains("$anon$")));
        assertEquals(1, out.size());
    }

    @Test
    void goalContainingPercent_doesNotCrashPromptBuilding() throws InterruptedException {
        Path root = tempDir.toAbsolutePath();
        var analyzer = new TestAnalyzer(List.of(), Map.of());
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        StreamingChatModel model = cm.getService().quickestModel();

        assertDoesNotThrow(() -> new ContextAgent(cm, model, "100% sure: use %s and %d literally"));
    }

    @Test
    void unanalyzedPrompt_cappedAndAnnotatedWhenOverMaxLines() throws Exception {
        Path root = tempDir.toAbsolutePath();

        // Create a file that will be UNANALYZED (TestAnalyzer returns no top-level declarations).
        ProjectFile big = new ProjectFile(root, "src/un/analyzed/Big.txt");
        big.create();
        String content = java.util.stream.IntStream.rangeClosed(1, 312)
                .mapToObj(i -> "line " + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        big.write(content);

        var analyzer = new TestAnalyzer(List.of(), Map.of());
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        var model = new TestScriptedLanguageModel(
                // Ensure the tool gets called; we do not care what it recommends for this test.
                "I will call the tool now.");
        var agent = new ContextAgent(cm, model, "test goal");

        // Trigger the UNANALYZED path by having an empty workspace and only 1 non-analyzable file.
        agent.getRecommendations(cm.liveContext(), true);

        var seen = model.seenRequests();
        assertFalse(seen.isEmpty());

        String prompt = seen.getLast().messages().toString();

        assertTrue(prompt.contains("truncated=\"true\" total_lines=\"312\" top_shown=\"25\" bottom_shown=\"25\""));

        assertTrue(prompt.contains("line 1"));
        assertTrue(prompt.contains("line 25"));
        assertTrue(prompt.contains("line 288"));
        assertTrue(prompt.contains("line 312"));

        assertTrue(prompt.contains("----- BRK_OMITTED 262 LINES -----"));
        assertFalse(prompt.contains("line 26"));
        assertFalse(prompt.contains("line 287"));
    }

    @Test
    void unanalyzedPrompt_notAnnotatedWhenAtOrBelowMaxLines() throws Exception {
        Path root = tempDir.toAbsolutePath();

        ProjectFile small = new ProjectFile(root, "src/un/analyzed/Small.txt");
        small.create();
        String content = java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(i -> "line " + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        small.write(content);

        var analyzer = new TestAnalyzer(List.of(), Map.of());
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        var model = new TestScriptedLanguageModel("Calling tool.");
        var agent = new ContextAgent(cm, model, "test goal");

        agent.getRecommendations(cm.liveContext(), true);

        var seen = model.seenRequests();
        assertFalse(seen.isEmpty());
        String prompt = seen.getLast().messages().toString();

        assertTrue(prompt.contains("<file path='src/un/analyzed/Small.txt'>"));
        assertFalse(prompt.contains("truncated=\"true\""));
        assertFalse(prompt.contains("BRK_OMITTED"));
        assertTrue(prompt.contains("line 1"));
        assertTrue(prompt.contains("line 50"));
    }
}
