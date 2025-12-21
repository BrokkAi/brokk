package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.nio.file.Path;
import java.util.List;
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
        var analyzer = new TestAnalyzer(List.of(), java.util.Map.of());
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        StreamingChatModel model = cm.getService().getModel(ai.brokk.project.ModelProperties.ModelType.SUMMARIZE);
        var agent = new ContextAgent(cm, model, "test");

        var testFile = cm.toFile("src/test/java/pkg/FooTest.java");
        List<ContextFragment> fragments =
                agent.assembleFragmentsForTesting(Set.of(), Set.of(testFile), Set.of(), Set.of());

        var summaryFragments = fragments.stream()
                .filter(f -> f instanceof ContextFragments.SummaryFragment)
                .map(f -> (ContextFragments.SummaryFragment) f)
                .toList();

        assertEquals(1, summaryFragments.size());
        assertEquals(
                ContextFragment.SummaryType.FILE_SKELETONS,
                summaryFragments.getFirst().getSummaryType());
        assertEquals(testFile.toString(), summaryFragments.getFirst().getTargetIdentifier());
    }

    @Test
    void duplicatesBetweenFilesAndTestsPreferProjectPathFragment_andDoNotDuplicate() throws InterruptedException {
        Path root = tempDir.toAbsolutePath();
        var analyzer = new TestAnalyzer(List.of(), java.util.Map.of());
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        StreamingChatModel model = cm.getService().quickestModel();
        var agent = new ContextAgent(cm, model, "test");

        var samePath = cm.toFile("src/shared/Dupe.java");
        List<ContextFragment> fragments =
                agent.assembleFragmentsForTesting(Set.of(samePath), Set.of(samePath), Set.of(), Set.of());

        var summaryFileSkeletonCount = fragments.stream()
                .filter(f -> f instanceof ContextFragments.SummaryFragment sf
                        && sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS
                        && sf.getTargetIdentifier().equals(samePath.toString()))
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
        var analyzer = new TestAnalyzer(List.of(), java.util.Map.of());
        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        StreamingChatModel model = cm.getService().quickestModel();
        var agent = new ContextAgent(cm, model, "test");

        var existing = cm.toFile("src/already/InWorkspace.java");
        List<ContextFragment> fragments =
                agent.assembleFragmentsForTesting(Set.of(existing), Set.of(existing), Set.of(), Set.of(existing));

        assertTrue(fragments.isEmpty());
    }

    @Test
    void filterAnonymousSummaries_removesAnonEntries() {
        var file = new ai.brokk.analyzer.ProjectFile(tempDir, "src/pkg/Foo.java");

        var regular = ai.brokk.analyzer.CodeUnit.cls(file, "pkg", "Foo");
        var anon = ai.brokk.analyzer.CodeUnit.cls(file, "pkg", "Foo$anon$1");

        var input = new java.util.LinkedHashMap<ai.brokk.analyzer.CodeUnit, String>();
        input.put(regular, "class Foo {}");
        input.put(anon, "class Foo$anon$1 {}");

        var out = input.entrySet().stream()
                .filter(e -> !e.getKey().isAnonymous())
                .collect(Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue,
                        (v1, v2) -> v1,
                        java.util.LinkedHashMap::new));

        assertTrue(out.containsKey(regular));
        assertFalse(out.containsKey(anon));
        assertTrue(out.values().stream().noneMatch(s -> s.contains("$anon$")));
        assertEquals(1, out.size());
    }
}
