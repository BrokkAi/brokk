package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragment.SummaryFragment;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ContextAgentSummariesIntegrationTest {

    @Test
    public void summariesExcludeAnonymousUnits_andIncludeNamedSkeletons() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        ProjectFile pf = new ProjectFile(root, "src/Foo.java");
        CodeUnit foo = CodeUnit.cls(pf, "pkg", "Foo");
        CodeUnit anon = CodeUnit.cls(pf, "pkg", "Outer$anon$1");

        TestAnalyzer analyzer = new TestAnalyzer(List.of(foo, anon), Map.of()) {
            @Override
            public Optional<String> getSkeleton(CodeUnit cu) {
                return Optional.of("class " + cu.identifier() + " {}");
            }

            @Override
            public Optional<String> getSkeletonHeader(CodeUnit cu) {
                return Optional.of("class " + cu.identifier() + " {}\n");
            }
        };

        var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

        List<ContextFragment> fragments =
                TestContextAgentUtils.createSummaryFragments(cm, analyzer, List.of(foo, anon), List.of(), Set.of());

        List<SummaryFragment> summaryFragments = fragments.stream()
                .filter(f -> f instanceof SummaryFragment)
                .map(f -> (SummaryFragment) f)
                .toList();

        String combined = SummaryFragment.combinedText(summaryFragments);

        assertTrue(combined.contains("class Foo"));
        assertFalse(combined.contains("$anon$"));
    }
}
