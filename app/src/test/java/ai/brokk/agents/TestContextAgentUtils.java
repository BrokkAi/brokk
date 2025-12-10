package ai.brokk.agents;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SkeletonProvider;
import ai.brokk.context.ContextFragment;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Test-side helper to build SummaryFragments without invoking LLMs or modifying production code.
 */
public final class TestContextAgentUtils {
    private TestContextAgentUtils() {}

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
                .map(cu -> (ContextFragment) new ContextFragment.SummaryFragment(
                        cm, cu.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON))
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
}
