package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
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
    void unanalyzedPrompt_cappedAndAnnotatedWhenOverMaxLines_exactFormat() throws Exception {
        Path root = tempDir.toAbsolutePath();

        ProjectFile big = new ProjectFile(root, "src/un/analyzed/Big.txt");
        big.create();

        String content = java.util.stream.IntStream.range(0, 312)
                .mapToObj(i -> "LINE_%03d".formatted(i))
                .collect(java.util.stream.Collectors.joining("\n"));
        big.write(content);

        var capped = ContextAgent.capUnanalyzedTextForPrompt(content);
        String rendered = ContextAgent.renderFileForPrompt(big, capped);

        assertTrue(
                rendered.startsWith(
                        "<file path='src/un/analyzed/Big.txt' truncated=\"true\" total_lines=\"312\" top_shown=\"25\" bottom_shown=\"25\">"),
                rendered);

        assertTrue(rendered.contains("LINE_000"));
        assertTrue(rendered.contains("LINE_024"));

        assertTrue(rendered.contains("LINE_287"));
        assertTrue(rendered.contains("LINE_311"));

        assertTrue(rendered.contains("----- OMITTED 262 LINES -----"));

        assertFalse(rendered.contains("LINE_100"));
    }

    @Test
    void unanalyzedPrompt_notAnnotatedWhenAtOrBelowMaxLines_exactFormat() throws Exception {
        Path root = tempDir.toAbsolutePath();

        ProjectFile small = new ProjectFile(root, "src/un/analyzed/Small.txt");
        small.create();

        String content = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> "LINE_%03d".formatted(i))
                .collect(java.util.stream.Collectors.joining("\n"));
        small.write(content);

        var capped = ContextAgent.capUnanalyzedTextForPrompt(content);
        String rendered = ContextAgent.renderFileForPrompt(small, capped);

        assertTrue(rendered.startsWith("<file path='src/un/analyzed/Small.txt'>\n"), rendered);
        assertFalse(rendered.contains("truncated=\"true\""));
        assertFalse(rendered.contains("OMITTED"));
        assertTrue(rendered.contains("LINE_000"));
        assertTrue(rendered.contains("LINE_049"));
    }

    @Test
    void unanalyzedPrompt_handlesCrLfLineBreaksAndDoesNotLeakCarriageReturns() throws Exception {
        Path root = tempDir.toAbsolutePath();

        ProjectFile crlf = new ProjectFile(root, "src/un/analyzed/CrLf.txt");
        crlf.create();

        String content = java.util.stream.IntStream.range(0, 56)
                .mapToObj(i -> "LINE_%03d".formatted(i))
                .collect(java.util.stream.Collectors.joining("\r\n"));
        crlf.write(content);

        var capped = ContextAgent.capUnanalyzedTextForPrompt(content);
        String rendered = ContextAgent.renderFileForPrompt(crlf, capped);

        assertTrue(
                rendered.startsWith(
                        "<file path='src/un/analyzed/CrLf.txt' truncated=\"true\" total_lines=\"56\" top_shown=\"25\" bottom_shown=\"25\">"),
                rendered);

        assertTrue(rendered.contains("----- OMITTED 6 LINES -----"), rendered);

        assertTrue(rendered.contains("LINE_000"));
        assertTrue(rendered.contains("LINE_024"));
        assertTrue(rendered.contains("LINE_031"));
        assertTrue(rendered.contains("LINE_055"));

        assertFalse(rendered.contains("LINE_030"));
        assertFalse(rendered.contains("\r"), rendered);
    }

    @Test
    void unanalyzedPrompt_handlesCrOnlyLineBreaks() throws Exception {
        Path root = tempDir.toAbsolutePath();

        ProjectFile cr = new ProjectFile(root, "src/un/analyzed/CrOnly.txt");
        cr.create();

        String content = java.util.stream.IntStream.range(0, 56)
                .mapToObj(i -> "LINE_%03d".formatted(i))
                .collect(java.util.stream.Collectors.joining("\r"));
        cr.write(content);

        var capped = ContextAgent.capUnanalyzedTextForPrompt(content);
        String rendered = ContextAgent.renderFileForPrompt(cr, capped);

        assertTrue(
                rendered.startsWith(
                        "<file path='src/un/analyzed/CrOnly.txt' truncated=\"true\" total_lines=\"56\" top_shown=\"25\" bottom_shown=\"25\">"),
                rendered);

        assertTrue(rendered.contains("----- OMITTED 6 LINES -----"), rendered);

        assertTrue(rendered.contains("LINE_000"));
        assertTrue(rendered.contains("LINE_024"));
        assertTrue(rendered.contains("LINE_031"));
        assertTrue(rendered.contains("LINE_055"));

        assertFalse(rendered.contains("LINE_030"));
        assertFalse(rendered.contains("\r"), rendered);
    }

    @Test
    void unanalyzedPrompt_trailingNewlineCountsAsExtraLineAndMayTriggerTruncation() {
        String content = java.util.stream.IntStream.range(0, 50)
                        .mapToObj(i -> "LINE_%03d".formatted(i))
                        .collect(java.util.stream.Collectors.joining("\n"))
                + "\n";

        var capped = ContextAgent.capUnanalyzedTextForPrompt(content);
        assertTrue(capped.truncated());
        assertEquals(51, capped.totalLines());
        assertTrue(capped.promptText().contains("----- OMITTED 1 LINES -----"), capped.promptText());
        assertTrue(capped.promptText().contains("LINE_000"));
        assertTrue(capped.promptText().contains("LINE_049"));
    }

    @Test
    void unanalyzedPrompt_emptyContentIsNotTruncated() {
        var capped = ContextAgent.capUnanalyzedTextForPrompt("");
        assertFalse(capped.truncated());
        assertEquals("", capped.promptText());
    }

    @Test
    void unanalyzedPrompt_oneHugeLine_isCappedAndMarked() {
        String huge = "A".repeat(200_000);

        var capped = ContextAgent.capUnanalyzedTextForPrompt(huge);

        assertFalse(capped.truncated(), "Line count is 1 so it should not be line-truncated by count.");
        assertTrue(capped.promptText().contains("[TRUNCATED "), capped.promptText());
        assertFalse(capped.promptText().contains(huge), "Prompt must not contain the full huge payload.");
        assertTrue(capped.promptText().length() < 20_000, "Capped prompt should be far smaller than input.");
    }

    @Test
    void unanalyzedPrompt_hugeLineInHead_worksWithOmittedLinesDelimiter() {
        String hugeLine = "B".repeat(200_000);

        String content = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> "LINE_%03d".formatted(i))
                .collect(java.util.stream.Collectors.joining("\n"));

        content = hugeLine + "\n" + content;

        var capped = ContextAgent.capUnanalyzedTextForPrompt(content);

        assertTrue(capped.truncated());
        assertTrue(capped.promptText().contains("----- OMITTED "), capped.promptText());
        assertTrue(capped.promptText().contains("[TRUNCATED "), capped.promptText());
        assertFalse(capped.promptText().contains(hugeLine), "Prompt must not contain the full huge payload.");
    }

    @Test
    void testGetRecommendations_splitsTestsFromPrimaryCandidatesAndProducesTestSummaries() throws Exception {
        Path root = tempDir.toAbsolutePath();
        var analyzer = new TestAnalyzer();
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        // Create production file
        ProjectFile prodFile = new ProjectFile(root, "src/main/java/pkg/Foo.java");
        prodFile.create();
        prodFile.write("package pkg; public class Foo {}");
        // Register in analyzer so it is "analyzed"
        CodeUnit fooClass = CodeUnit.cls(prodFile, "pkg", "Foo");
        analyzer.addDeclaration(fooClass);
        analyzer.setSkeleton(fooClass, "public class Foo {}");

        // Create test file
        ProjectFile testFile = new ProjectFile(root, "src/test/java/pkg/FooTest.java");
        testFile.create();
        testFile.write("package pkg; public class FooTest {}");

        StreamingChatModel model = cm.getService().quickestModel();
        var agent = new ContextAgent(cm, model, "Add logging to Foo");
        Context context = new Context(cm);

        var result = agent.getRecommendations(context, true);

        // Structural assertions: even if LLM fails and returns empty, guarantees about representation must hold.
        var fragments = result.fragments();

        // 1. Any ProjectPathFragment found MUST NOT be a test file.
        boolean hasTestAsPath = fragments.stream()
                .anyMatch(f -> f instanceof ContextFragments.ProjectPathFragment pf
                        && pf.file().getRelPath().toString().replace('\\', '/').contains("src/test/"));
        assertFalse(hasTestAsPath, "Test files should NEVER be added as full ProjectPathFragment");

        // 2. Any FILE_SKELETONS SummaryFragment found MUST be a test file.
        var fileSkeletonSummaries = fragments.stream()
                .filter(f -> f instanceof ContextFragments.SummaryFragment sf
                        && sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS)
                .map(f -> (ContextFragments.SummaryFragment) f)
                .toList();

        for (var sf : fileSkeletonSummaries) {
            String path = sf.getTargetIdentifier().replace('\\', '/');
            assertTrue(
                    path.contains("src/test/"),
                    "FILE_SKELETONS summaries should only be used for test files, but found: " + path);
        }
    }

    @Test
    void testGetRecommendations_withoutTests_doesNotProduceTestSummaries() throws Exception {
        Path root = tempDir.toAbsolutePath();
        var analyzer = new TestAnalyzer();
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        ProjectFile prodFile = new ProjectFile(root, "src/main/java/pkg/Foo.java");
        prodFile.create();
        prodFile.write("class Foo {}");

        StreamingChatModel model = cm.getService().quickestModel();
        var agent = new ContextAgent(cm, model, "Refactor Foo");
        Context context = new Context(cm);

        var result = agent.getRecommendations(context, true);

        boolean hasTestSummary = result.fragments().stream()
                .anyMatch(f -> f instanceof ContextFragments.SummaryFragment sf
                        && sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS);

        assertFalse(hasTestSummary, "Should not produce test summaries when no tests exist");
    }

    @Test
    void testGetRecommendations_manyTests_handlesLargeTestSets() throws Exception {
        Path root = tempDir.toAbsolutePath();
        var analyzer = new TestAnalyzer();
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        // One prod file
        new ProjectFile(root, "src/main/java/pkg/App.java").create();

        // 60 test files to trigger chunking/filename pruning logic
        for (int i = 0; i < 60; i++) {
            new ProjectFile(root, "src/test/java/pkg/Test" + i + ".java").create();
        }

        StreamingChatModel model = cm.getService().quickestModel();
        var agent = new ContextAgent(cm, model, "Fix all tests");
        Context context = new Context(cm);

        // Should complete without error even if LLM calls fail due to auth/environment
        var result = agent.getRecommendations(context, true);

        // Verify result is structurally sound
        var fragments = result.fragments();

        // Tests should never be full paths
        boolean hasTestAsPath = fragments.stream()
                .anyMatch(f -> f instanceof ContextFragments.ProjectPathFragment pf
                        && pf.file().getRelPath().toString().replace('\\', '/').contains("src/test/"));
        assertFalse(hasTestAsPath, "Large set of tests should still never result in full ProjectPathFragments");

        // If any test summaries were produced (LLM succeeded), verify they are indeed tests
        fragments.stream()
                .filter(f -> f instanceof ContextFragments.SummaryFragment sf
                        && sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS)
                .map(f -> (ContextFragments.SummaryFragment) f)
                .forEach(sf -> {
                    String path = sf.getTargetIdentifier().replace('\\', '/');
                    assertTrue(path.contains("src/test/"), "FILE_SKELETONS summary found for non-test: " + path);
                });
    }
}
