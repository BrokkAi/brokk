package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.usages.FuzzyUsageFinder;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.project.IProject;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility methods for FuzzyUsageFinder tests.
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
     * Creates a FuzzyUsageFinder with a TestService and no LLM.
     */
    public static FuzzyUsageFinder newFinder(IProject project, IAnalyzer analyzer) {
        return new FuzzyUsageFinder(project, analyzer, new TestService(project), null);
    }
}
