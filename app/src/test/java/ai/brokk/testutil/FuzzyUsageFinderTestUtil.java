package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.usages.LlmUsageAnalyzer;
import ai.brokk.analyzer.usages.UsageFinder;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.project.IProject;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility methods for UsageFinder tests.
 */
public final class FuzzyUsageFinderTestUtil {

    private FuzzyUsageFinderTestUtil() {}

    /**
     * Extracts file names from usage hits.
     */
    public static Set<String> fileNamesFromHits(Set<UsageHit> hits) {
        return hits.stream()
                .map(hit -> hit.file().absPath().getFileName().toString())
                .collect(Collectors.toSet());
    }

    /**
     * Creates a UsageFinder with a TestService and no LLM.
     */
    public static UsageFinder newFinder(IProject project, IAnalyzer analyzer) {
        return new UsageFinder(
                project,
                analyzer,
                UsageFinder.createDefaultProvider(),
                new LlmUsageAnalyzer(project, analyzer, new TestService(project), null),
                null);
    }

    /**
     * Creates a UsageFinder for tests.
     */
    public static UsageFinder createForTest(IProject project, IAnalyzer analyzer) {
        return newFinder(project, analyzer);
    }
}
