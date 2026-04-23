package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.project.IProject;
import ai.brokk.usages.UsageFinder;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test utility for usage finder tests.
 */
public final class UsageFinderTestUtil {

    private UsageFinderTestUtil() {}

    public static UsageFinder createForTest(IProject project, IAnalyzer analyzer) {
        return new UsageFinder(project, analyzer, UsageFinder.createDefaultProvider(), null, null);
    }

    public static UsageFinder newFinder(IProject project, IAnalyzer analyzer) {
        return createForTest(project, analyzer);
    }

    public static Set<String> fileNamesFromHits(Collection<UsageHit> hits) {
        return hits.stream().map(hit -> hit.file().getFileName()).collect(Collectors.toSet());
    }
}
