package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SkeletonProvider;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragment.SummaryFragment;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class ContextAgentSummariesIntegrationTest {

    public static List<ContextFragment> createSummaryFragments(
            IContextManager cm,
            IAnalyzer analyzer,
            Collection<CodeUnit> classes,
            Collection<ProjectFile> files,
            Set<ProjectFile> existingFiles) {
        Set<CodeUnit> filtered =
                classes.stream().filter(cu -> !cu.isAnonymous()).collect(Collectors.toCollection(LinkedHashSet::new));

        Map<CodeUnit, String> summaries = filtered.stream()
                .map(cu -> {
                    final String skeleton = analyzer.as(SkeletonProvider.class)
                            .flatMap(skp -> skp.getSkeleton(cu))
                            .orElse("");
                    return Map.entry(cu, skeleton);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));

        List<ContextFragment> summaryFragments = summaries.keySet().stream()
                .map(cu -> (ContextFragment)
                        new SummaryFragment(cm, cu.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON))
                .toList();

        List<ContextFragment> pathFragments = files.stream()
                .filter(pf -> !existingFiles.contains(pf))
                .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f, cm))
                .toList();

        List<ContextFragment> out = new ArrayList<>(summaryFragments.size() + pathFragments.size());
        out.addAll(summaryFragments);
        out.addAll(pathFragments);
        return out;
    }

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

        List<ContextFragment> fragments = createSummaryFragments(cm, analyzer, List.of(foo, anon), List.of(), Set.of());

        List<SummaryFragment> summaryFragments = fragments.stream()
                .filter(f -> f instanceof SummaryFragment)
                .map(f -> (SummaryFragment) f)
                .toList();

        String combined = SummaryFragment.combinedText(summaryFragments);

        assertTrue(combined.contains("class Foo"));
        assertFalse(combined.contains("$anon$"));
    }
}
