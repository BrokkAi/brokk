package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.ICoreProject;
import java.util.List;
import java.util.Set;

/** Selects the usage analyzer strategy shared by context fragments and MCP tools. */
public final class UsageAnalyzerSelector {
    public static final int DEFAULT_MAX_USAGES = 500;

    private UsageAnalyzerSelector() {}

    public static UsageAnalyzer forTarget(CodeUnit target, IAnalyzer analyzer, ICoreProject project) {
        var language = Languages.fromExtension(target.source().extension());
        if (language.contains(Languages.JAVA)) {
            return new JdtUsageAnalyzerStrategy(project);
        }
        if (language.contains(Languages.JAVASCRIPT) || language.contains(Languages.TYPESCRIPT)) {
            var graph = new JsTsExportUsageGraphStrategy(analyzer);
            if (graph.canHandle(target)) {
                return graph;
            }
        }
        return new RegexUsageAnalyzer(analyzer);
    }

    public static FuzzyResult findUsages(
            UsageAnalyzer usageAnalyzer, IAnalyzer analyzer, List<CodeUnit> overloads, Set<ProjectFile> candidates)
            throws InterruptedException {
        var result = usageAnalyzer.findUsages(overloads, candidates, DEFAULT_MAX_USAGES);
        if (shouldFallbackToRegex(result, usageAnalyzer)) {
            return new RegexUsageAnalyzer(analyzer).findUsages(overloads, candidates, DEFAULT_MAX_USAGES);
        }
        return result;
    }

    public static boolean shouldFallbackToRegex(FuzzyResult result, UsageAnalyzer usageAnalyzer) {
        if (!(usageAnalyzer instanceof JsTsExportUsageGraphStrategy)) {
            return false;
        }
        return switch (result) {
            case FuzzyResult.Success success -> success.hits().isEmpty();
            case FuzzyResult.Ambiguous ambiguous -> ambiguous.hits().isEmpty();
            case FuzzyResult.Failure ignored -> true;
            case FuzzyResult.TooManyCallsites ignored -> false;
        };
    }
}
