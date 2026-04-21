package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.ICoreProject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * Shared usage-finding facade that contains the core "business logic" for usage resolution.
 *
 * <p>App-layer wiring (LLM selection, model services, UI integrations) should wrap this class rather than duplicating
 * usage finding logic outside {@code :brokk-shared}.
 */
public final class CoreUsageFinder {
    public static final int DEFAULT_MAX_FILES = 1000;
    public static final int DEFAULT_MAX_USAGES = 1000;

    private final ICoreProject project;
    private final IAnalyzer analyzer;
    private final CandidateFileProvider fallbackCandidateProvider;
    private final UsageAnalyzer fallbackUsageAnalyzer;
    private final @Nullable Predicate<ProjectFile> fileFilter;

    private record Configuration(CandidateFileProvider candidateProvider, UsageAnalyzer usageAnalyzer) {}

    public record QueryResult(Set<ProjectFile> candidateFiles, FuzzyResult result) {}

    public static CandidateFileProvider createDefaultProvider() {
        return createFallbackProvider(new ImportGraphCandidateProvider(), new TextSearchCandidateProvider());
    }

    public static CandidateFileProvider createFallbackProvider(
            CandidateFileProvider graphProvider, CandidateFileProvider textProvider) {
        return (target, analyzer) -> {
            var candidates = graphProvider.findCandidates(target, analyzer);
            if (candidates.isEmpty() && !analyzer.isEmpty()) {
                return textProvider.findCandidates(target, analyzer);
            }
            return candidates;
        };
    }

    public CoreUsageFinder(
            ICoreProject project,
            IAnalyzer analyzer,
            CandidateFileProvider candidateProvider,
            UsageAnalyzer usageAnalyzer,
            @Nullable Predicate<ProjectFile> fileFilter) {
        this.project = project;
        this.analyzer = analyzer;
        this.fallbackCandidateProvider = candidateProvider;
        this.fallbackUsageAnalyzer = usageAnalyzer;
        this.fileFilter = fileFilter;
    }

    public CoreUsageFinder(ICoreProject project, IAnalyzer analyzer, UsageAnalyzer usageAnalyzer) {
        this(project, analyzer, createDefaultProvider(), usageAnalyzer, null);
    }

    private Configuration getConfiguration(CodeUnit target) {
        Language lang = Languages.fromExtension(target.source().extension());
        if (lang.contains(Languages.JAVA)) {
            return new Configuration(createDefaultProvider(), new JdtUsageAnalyzerStrategy(project));
        }
        return new Configuration(fallbackCandidateProvider, fallbackUsageAnalyzer);
    }

    public QueryResult query(List<CodeUnit> overloads, int maxFiles, int maxUsages) throws InterruptedException {
        if (overloads.isEmpty()) {
            return new QueryResult(Set.of(), new FuzzyResult.Success(Map.of()));
        }

        var target = overloads.getFirst();

        Configuration config = getConfiguration(target);
        Set<ProjectFile> candidateFiles = config.candidateProvider().findCandidates(target, analyzer);

        if (fileFilter != null) {
            candidateFiles = candidateFiles.stream().filter(fileFilter).collect(Collectors.toSet());
        }

        if (candidateFiles.size() > maxFiles) {
            candidateFiles = candidateFiles.stream().limit(maxFiles).collect(Collectors.toSet());
        }

        var result = config.usageAnalyzer().findUsages(overloads, candidateFiles, maxUsages);
        return new QueryResult(candidateFiles, result);
    }

    public FuzzyResult findUsages(List<CodeUnit> overloads, int maxFiles, int maxUsages) throws InterruptedException {
        return query(overloads, maxFiles, maxUsages).result();
    }

    public FuzzyResult findUsages(List<CodeUnit> overloads) throws InterruptedException {
        return findUsages(overloads, DEFAULT_MAX_FILES, DEFAULT_MAX_USAGES);
    }
}
