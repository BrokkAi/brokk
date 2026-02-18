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

/**
 * A usage finder facade that delegates to language-specific strategies (e.g., JDT for Java, LLM/Fuzzy for others).
 */
public final class UsageFinder {

    private static final Logger logger = LogManager.getLogger(UsageFinder.class);
    private static final boolean FUZZY_USAGES_ONLY = System.getenv("BRK_FUZZY_USAGES_ONLY") != null;
    public static final int DEFAULT_MAX_FILES = 1000;

    private final IProject project;
    private final IAnalyzer analyzer;
    private final CandidateFileProvider fallbackCandidateProvider;
    private final UsageAnalyzer fallbackUsageAnalyzer;
    private final @Nullable Predicate<ProjectFile> fileFilter;

    private record Configuration(CandidateFileProvider candidateProvider, UsageAnalyzer usageAnalyzer) {}

    public static UsageFinder create(IContextManager cm) {
        return create(cm, null);
    }

    public static UsageFinder create(IContextManager cm, @Nullable Predicate<ProjectFile> fileFilter) {
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
        var llmAnalyzer = new LlmUsageAnalyzer(project, analyzer, service, llm);

        return new UsageFinder(project, analyzer, createDefaultProvider(), llmAnalyzer, fileFilter);
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
     * @deprecated Use {@link #create(IContextManager)} or the full constructor.
     */
    @Deprecated
    public UsageFinder(IProject project, IAnalyzer analyzer, UsageAnalyzer llmAnalyzer) {
        this(project, analyzer, createDefaultProvider(), llmAnalyzer, null);
    }

    public UsageFinder(
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

    private FuzzyResult findUsages(List<CodeUnit> overloads, int maxFiles) throws InterruptedException {
        assert !overloads.isEmpty() : "overloads must not be empty";
        var target = overloads.getFirst();

        Configuration config = getConfiguration(target);
        Set<ProjectFile> candidateFiles = config.candidateProvider().findCandidates(target, analyzer);

        if (fileFilter != null) {
            candidateFiles = candidateFiles.stream().filter(fileFilter).collect(Collectors.toSet());
        }

        if (maxFiles < candidateFiles.size()) {
            return new FuzzyResult.TooManyCallsites(target.shortName(), candidateFiles.size(), maxFiles);
        }

        return config.usageAnalyzer().findUsages(overloads, candidateFiles);
    }

    public FuzzyResult findUsages(String fqName, int maxFiles) throws InterruptedException {
        if (isEffectivelyEmpty()) {
            return new FuzzyResult.Success(Map.of());
        }
        var definitions = analyzer.getDefinitions(fqName);
        if (definitions.isEmpty()) {
            return new FuzzyResult.Failure(fqName, "No definitions found");
        }

        var overloads = List.copyOf(definitions);
        var result = findUsages(overloads, maxFiles);

        Map<CodeUnit, Set<UsageHit>> allHitsByOverload =
                switch (result) {
                    case FuzzyResult.Success success -> success.hitsByOverload();
                    case FuzzyResult.Ambiguous ambiguous -> ambiguous.hitsByOverload();
                    case FuzzyResult.TooManyCallsites tooMany -> Map.of();
                    case FuzzyResult.Failure failure -> Map.of();
                };

        var filteredHitsByOverload = LlmUsageAnalyzer.filterByConfidence(allHitsByOverload);
        return new FuzzyResult.Success(filteredHitsByOverload);
    }

    public FuzzyResult findUsages(String fqName) throws InterruptedException {
        return findUsages(fqName, DEFAULT_MAX_FILES);
    }

    private boolean isEffectivelyEmpty() {
        if (analyzer.isEmpty()) {
            return true;
        }
        var files = project.getAllFiles();
        return files.isEmpty();
    }
}
