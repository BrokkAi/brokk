package ai.brokk.analyzer.usages;

import ai.brokk.AbstractService;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class FuzzyUsageFinder {

    private static final Logger logger = LogManager.getLogger(FuzzyUsageFinder.class);
    private static final boolean FUZZY_USAGES_ONLY = System.getenv("BRK_FUZZY_USAGES_ONLY") != null;
    public static final int DEFAULT_MAX_FILES = 1000;
    public static final int DEFAULT_MAX_USAGES = 1000;

    private final IProject project;
    private final IAnalyzer analyzer;
    private final CandidateFileProvider fallbackCandidateProvider;
    private final UsageAnalyzer fallbackUsageAnalyzer;
    private final @Nullable Predicate<ProjectFile> fileFilter;

    private record Configuration(CandidateFileProvider candidateProvider, UsageAnalyzer usageAnalyzer) {}

    public static FuzzyUsageFinder create(IContextManager cm) {
        return create(cm, null);
    }

    public static FuzzyUsageFinder create(IContextManager cm, @Nullable Predicate<ProjectFile> fileFilter) {
        var service = cm.getService();
        var model = service.getModel(ModelProperties.ModelType.USAGES);
        var llm = model instanceof AbstractService.OfflineStreamingModel
                ? null
                : new Llm(
                        model,
                        "Disambiguate Code Unit Usages",
                        ai.brokk.TaskResult.Type.CLASSIFY,
                        cm,
                        false,
                        false,
                        false,
                        false);

        var project = cm.getProject();
        var analyzer = cm.getAnalyzerUninterrupted();
        var llmAnalyzer = new LlmUsageAnalyzer(project, analyzer, service, llm, DEFAULT_MAX_USAGES);

        return new FuzzyUsageFinder(project, analyzer, createDefaultProvider(), llmAnalyzer, fileFilter);
    }

    private Configuration getConfiguration(CodeUnit target) {
        Language lang = Languages.fromExtension(target.source().extension());
        if (!FUZZY_USAGES_ONLY && lang.contains(Languages.JAVA)) {
            return new Configuration(new TextSearchCandidateProvider(), new JdtUsageAnalyzerStrategy(project));
        }
        return new Configuration(fallbackCandidateProvider, fallbackUsageAnalyzer);
    }

    public static CandidateFileProvider createDefaultProvider() {
        return createFallbackProvider(new ImportGraphCandidateProvider(), new TextSearchCandidateProvider());
    }

    static CandidateFileProvider createFallbackProvider(
            CandidateFileProvider graphProvider, CandidateFileProvider textProvider) {
        return (target, analyzer) -> {
            var candidates = graphProvider.findCandidates(target, analyzer);
            if (candidates.isEmpty() && !analyzer.isEmpty()) {
                return textProvider.findCandidates(target, analyzer);
            }
            return candidates;
        };
    }

    /**
     * Construct a FuzzyUsageFinder with default candidate discovery.
     *
     * @param project the project providing files and configuration
     * @param analyzer the analyzer providing declarations/definitions
     * @param llmAnalyzer other llm-based analyzer for disambiguation
     */
    public FuzzyUsageFinder(IProject project, IAnalyzer analyzer, UsageAnalyzer llmAnalyzer) {
        this(project, analyzer, createDefaultProvider(), llmAnalyzer, null);
    }

    /**
     * Construct a FuzzyUsageFinder.
     *
     * @param project the project providing files and configuration
     * @param analyzer the analyzer providing declarations/definitions
     * @param candidateProvider the strategy for finding candidate files
     * @param llmAnalyzer the analyzer for finding usages (typically LLM-based)
     */
    public FuzzyUsageFinder(
            IProject project, IAnalyzer analyzer, CandidateFileProvider candidateProvider, UsageAnalyzer llmAnalyzer) {
        this(project, analyzer, candidateProvider, llmAnalyzer, null);
    }

    public FuzzyUsageFinder(
            IProject project,
            IAnalyzer analyzer,
            CandidateFileProvider candidateProvider,
            UsageAnalyzer llmAnalyzer,
            @Nullable Predicate<ProjectFile> fileFilter) {
        this.project = project;
        this.analyzer = analyzer;
        this.fallbackCandidateProvider = candidateProvider;
        this.fallbackUsageAnalyzer = llmAnalyzer;
        this.fileFilter = fileFilter;
    }

    /**
     * Find usages for a list of overloads.
     *
     * <p>For an empty project/analyzer, returns Success with an empty hit list.
     */
    private FuzzyResult findUsages(List<CodeUnit> overloads, int maxFiles) throws InterruptedException {
        assert !overloads.isEmpty() : "overloads must not be empty";
        var target = overloads.getFirst();

        Configuration config = getConfiguration(target);

        // Identify candidate files using the selected provider
        Set<ProjectFile> candidateFiles = config.candidateProvider().findCandidates(target, analyzer);

        // Apply file filter if provided (e.g., to exclude test files)
        if (fileFilter != null) {
            candidateFiles = candidateFiles.stream().filter(fileFilter).collect(Collectors.toSet());
        }

        if (maxFiles < candidateFiles.size()) {
            // Case 1: Too many call sites
            logger.debug("Too many call sites found for {}: {} files matched", target, candidateFiles.size());
            return new FuzzyResult.TooManyCallsites(target.shortName(), candidateFiles.size(), maxFiles);
        }

        // Delegate search and disambiguation to the UsageAnalyzer
        return config.usageAnalyzer().findUsages(overloads, candidateFiles);
    }

    /**
     * Find usages by fully-qualified name.
     *
     * <p>For an empty project/analyzer, returns Success with an empty hit list.
     * <p>If multiple definitions exist (e.g., overloaded methods), aggregates usages from all of them.
     */
    public FuzzyResult findUsages(String fqName, int maxFiles) throws InterruptedException {
        if (isEffectivelyEmpty()) {
            logger.debug("Project/analyzer empty; returning empty Success for fqName={}", fqName);
            return new FuzzyResult.Success(Map.of());
        }
        var definitions = analyzer.getDefinitions(fqName);
        if (definitions.isEmpty()) {
            logger.debug("No definitions found for fqName={}; returning Failure", fqName);
            return new FuzzyResult.Failure(fqName, "No definitions found");
        }

        // Build overloads list from all definitions (preserving signatures for the LLM prompt)
        var overloads = List.copyOf(definitions);

        var result = findUsages(overloads, maxFiles);
        Map<CodeUnit, Set<UsageHit>> allHitsByOverload =
                switch (result) {
                    case FuzzyResult.Success success -> success.hitsByOverload();
                    case FuzzyResult.Ambiguous ambiguous -> ambiguous.hitsByOverload();
                    case FuzzyResult.TooManyCallsites tooMany -> {
                        logger.debug(
                                "Too many callsites for {} ({}): {} > {}",
                                fqName,
                                overloads.getFirst().kind(),
                                tooMany.totalCallsites(),
                                tooMany.limit());
                        yield Map.of();
                    }
                    case FuzzyResult.Failure failure -> {
                        logger.debug("Failure when finding usages of {}: {}", fqName, failure.reason());
                        yield Map.of();
                    }
                };

        // Throw out very low confidence
        var filteredHitsByOverload = LlmUsageAnalyzer.filterByConfidence(allHitsByOverload);
        int totalBefore =
                allHitsByOverload.values().stream().mapToInt(Set::size).sum();
        int totalAfter =
                filteredHitsByOverload.values().stream().mapToInt(Set::size).sum();
        logger.debug("Filtered to {} hits for {} out of {}", totalAfter, fqName, totalBefore);

        return new FuzzyResult.Success(filteredHitsByOverload);
    }

    public FuzzyResult findUsages(String fqName) throws InterruptedException {
        return findUsages(fqName, DEFAULT_MAX_FILES);
    }

    private boolean isEffectivelyEmpty() {
        // Analyzer says empty or project has no files considered by analyzer
        if (analyzer.isEmpty()) {
            return true;
        }
        var files = project.getAllFiles();
        return files.isEmpty();
    }
}
