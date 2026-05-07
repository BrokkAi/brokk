package ai.brokk.usages;

import ai.brokk.AbstractService;
import ai.brokk.IAppContextManager;
import ai.brokk.Llm;
import ai.brokk.OfflineService;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.CandidateFileProvider;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.analyzer.usages.JdtUsageAnalyzerStrategy;
import ai.brokk.analyzer.usages.JsTsExportUsageGraphStrategy;
import ai.brokk.analyzer.usages.PythonExportUsageGraphStrategy;
import ai.brokk.analyzer.usages.RustExportUsageGraphStrategy;
import ai.brokk.analyzer.usages.UsageAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A usage finder facade that delegates to language-specific strategies (e.g., JDT for Java, LLM/Fuzzy for others).
 */
public final class UsageFinder {

    private static final Logger log = LoggerFactory.getLogger(UsageFinder.class);
    private static final boolean FUZZY_USAGES_ONLY = System.getenv("BRK_FUZZY_USAGES_ONLY") != null;
    public static final int DEFAULT_MAX_FILES = ai.brokk.analyzer.usages.UsageFinder.DEFAULT_MAX_FILES;
    public static final int DEFAULT_MAX_USAGES = ai.brokk.analyzer.usages.UsageFinder.DEFAULT_MAX_USAGES;

    private final IProject project;
    private final IAnalyzer analyzer;
    private final CandidateFileProvider fallbackCandidateProvider;
    private final UsageAnalyzer fallbackUsageAnalyzer;
    private final @Nullable Predicate<ProjectFile> fileFilter;

    private record Configuration(
            CandidateFileProvider candidateProvider, UsageAnalyzer usageAnalyzer, boolean allowFallback) {}

    public record UsageQueryResult(
            Set<ProjectFile> candidateFiles, boolean candidateFilesTruncated, FuzzyResult result) {}

    public static UsageFinder create(IAppContextManager cm) {
        return create(cm, null);
    }

    public static UsageFinder create(IAppContextManager cm, @Nullable Predicate<ProjectFile> fileFilter) {
        var service = cm.getService();
        var model = service.getModel(ModelProperties.ModelType.USAGES);
        var llm = model instanceof AbstractService.OfflineStreamingModel
                ? null
                : new Llm(
                        model,
                        "Disambiguate Code Unit Usages",
                        TaskResult.Type.CLASSIFY,
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
        if (FUZZY_USAGES_ONLY) {
            log.debug("Usage lookup for {} forced to fuzzy path by BRK_FUZZY_USAGES_ONLY", target.fqName());
            return new Configuration(fallbackCandidateProvider, fallbackUsageAnalyzer, true);
        }
        Language lang = Languages.fromExtension(target.source().extension());
        if (lang.contains(Languages.JAVASCRIPT) || lang.contains(Languages.TYPESCRIPT)) {
            var graphStrategy = new JsTsExportUsageGraphStrategy(analyzer);
            if (graphStrategy.canHandle(target)) {
                log.debug("Usage lookup for {} routed to JS/TS export graph", target.fqName());
                return new Configuration(createDefaultProvider(), graphStrategy, true);
            }
            log.debug("Usage lookup for {} not seedable by JS/TS export graph, using fuzzy fallback", target.fqName());
            return new Configuration(fallbackCandidateProvider, fallbackUsageAnalyzer, true);
        }
        if (lang.contains(Languages.JAVA)) {
            log.debug("Usage lookup for {} routed to JDT strategy", target.fqName());
            return new Configuration(new TextSearchCandidateProvider(), new JdtUsageAnalyzerStrategy(project), true);
        }
        if (lang.contains(Languages.PYTHON)) {
            var graphStrategy = new PythonExportUsageGraphStrategy(analyzer);
            if (graphStrategy.canHandle(target)) {
                log.debug("Usage lookup for {} routed to Python export graph", target.fqName());
                return new Configuration(createDefaultProvider(), graphStrategy, true);
            }
            log.debug("Usage lookup for {} not seedable by Python export graph, using fuzzy fallback", target.fqName());
            return new Configuration(fallbackCandidateProvider, fallbackUsageAnalyzer, true);
        }
        if (lang.contains(Languages.RUST)) {
            var graphStrategy = new RustExportUsageGraphStrategy(analyzer);
            if (graphStrategy.canHandle(target)) {
                log.debug("Usage lookup for {} routed to Rust export graph", target.fqName());
                return new Configuration((ignoredTarget, ignoredAnalyzer) -> Set.of(), graphStrategy, true);
            }
            log.debug("Usage lookup for {} not seedable by Rust export graph, using fuzzy fallback", target.fqName());
            return new Configuration(fallbackCandidateProvider, fallbackUsageAnalyzer, true);
        }
        log.debug("Usage lookup for {} using default fallback strategy", target.fqName());
        return new Configuration(fallbackCandidateProvider, fallbackUsageAnalyzer, true);
    }

    public static CandidateFileProvider createDefaultProvider() {
        return ai.brokk.analyzer.usages.UsageFinder.createDefaultProvider();
    }

    public static CandidateFileProvider createFallbackProvider(
            CandidateFileProvider graphProvider, CandidateFileProvider textProvider) {
        return ai.brokk.analyzer.usages.UsageFinder.createFallbackProvider(graphProvider, textProvider);
    }

    public UsageFinder(
            IProject project,
            IAnalyzer analyzer,
            CandidateFileProvider candidateProvider,
            @Nullable UsageAnalyzer usageAnalyzer,
            @Nullable Predicate<ProjectFile> fileFilter) {
        this.project = project;
        this.analyzer = analyzer;
        this.fallbackCandidateProvider = candidateProvider;
        this.fallbackUsageAnalyzer = usageAnalyzer != null
                ? usageAnalyzer
                : new LlmUsageAnalyzer(project, analyzer, new OfflineService(project), null);
        this.fileFilter = fileFilter;
    }

    private UsageQueryResult queryUsages(
            List<CodeUnit> overloads,
            int maxFiles,
            int maxUsages,
            @Nullable CandidateFileProvider explicitCandidateProvider,
            boolean allowFallback)
            throws InterruptedException {
        assert !overloads.isEmpty() : "overloads must not be empty";
        var target = overloads.getFirst();

        Configuration config = getConfiguration(target);
        if (explicitCandidateProvider != null) {
            config = new Configuration(explicitCandidateProvider, config.usageAnalyzer(), allowFallback);
        }
        var coreFinder = new ai.brokk.analyzer.usages.UsageFinder(
                project, analyzer, config.candidateProvider(), config.usageAnalyzer(), fileFilter);
        var query = explicitCandidateProvider == null
                ? coreFinder.query(overloads, maxFiles, maxUsages)
                : coreFinder.query(overloads, explicitCandidateProvider, maxFiles, maxUsages);

        var candidateFiles = query.candidateFiles();
        var result = query.result();

        log.debug(
                "Primary usage analysis for {} via {} considered {} candidate files and produced {}",
                target.fqName(),
                config.usageAnalyzer().getClass().getSimpleName(),
                candidateFiles.size(),
                summarizeResult(result));

        boolean suspiciouslyEmpty = result instanceof FuzzyResult.Success success
                && success.hits().isEmpty()
                && (!candidateFiles.isEmpty() || config.usageAnalyzer() instanceof JsTsExportUsageGraphStrategy);

        if (config.allowFallback()
                && (result instanceof FuzzyResult.Failure || suspiciouslyEmpty)
                && config.usageAnalyzer().getClass() != fallbackUsageAnalyzer.getClass()) {
            log.warn(
                    "Primary usage analysis {} for {}, falling back to fuzzy analyzer",
                    suspiciouslyEmpty ? "returned no hits" : "failed",
                    target.fqName());
            var fallbackFinder = new ai.brokk.analyzer.usages.UsageFinder(
                    project, analyzer, fallbackCandidateProvider, fallbackUsageAnalyzer, fileFilter);
            var fallbackQuery = fallbackFinder.query(overloads, maxFiles, maxUsages);
            return new UsageQueryResult(
                    fallbackQuery.candidateFiles(), fallbackQuery.candidateFilesTruncated(), fallbackQuery.result());
        }

        return new UsageQueryResult(candidateFiles, query.candidateFilesTruncated(), result);
    }

    private UsageQueryResult queryUsages(List<CodeUnit> overloads, int maxFiles, int maxUsages)
            throws InterruptedException {
        return queryUsages(overloads, maxFiles, maxUsages, null, true);
    }

    private static String summarizeResult(FuzzyResult result) {
        return switch (result) {
            case FuzzyResult.Success success -> success.hits().size() + " hits";
            case FuzzyResult.Ambiguous ambiguous ->
                "ambiguous(" + ambiguous.candidateTargets().size() + ")";
            case FuzzyResult.TooManyCallsites tooMany -> "too-many(" + tooMany.totalCallsites() + ")";
            case FuzzyResult.Failure failure -> "failure(" + failure.reason() + ")";
        };
    }

    public FuzzyResult findUsages(String fqName, int maxFiles, int maxUsages) throws InterruptedException {
        if (isEffectivelyEmpty()) {
            return new FuzzyResult.Success(Map.of());
        }
        Set<CodeUnit> definitions = analyzer.getDefinitions(fqName);
        if (definitions.isEmpty()) {
            definitions = analyzer.searchDefinitions(fqName).stream()
                    .filter(codeUnit -> codeUnit.identifier().equals(fqName)
                            || codeUnit.shortName().equals(fqName))
                    .collect(Collectors.toSet());
        }
        if (definitions.isEmpty()) {
            return new FuzzyResult.Failure(fqName, "No definitions found");
        }

        var overloads = List.copyOf(definitions);
        var result = queryUsages(overloads, maxFiles, maxUsages).result();

        return switch (result) {
            case FuzzyResult.Success success ->
                new FuzzyResult.Success(LlmUsageAnalyzer.filterByConfidence(success.hitsByOverload()));
            case FuzzyResult.Ambiguous ambiguous ->
                new FuzzyResult.Ambiguous(
                        ambiguous.shortName(),
                        ambiguous.candidateTargets(),
                        LlmUsageAnalyzer.filterByConfidence(ambiguous.hitsByOverload()));
            case FuzzyResult.TooManyCallsites tooMany -> tooMany;
            case FuzzyResult.Failure failure -> failure;
        };
    }

    public FuzzyResult findUsages(String fqName) throws InterruptedException {
        return findUsages(fqName, DEFAULT_MAX_FILES, DEFAULT_MAX_USAGES);
    }

    public UsageQueryResult queryUsages(CodeUnit target, int maxFiles, int maxUsages) throws InterruptedException {
        if (isEffectivelyEmpty()) {
            return new UsageQueryResult(Set.of(), false, new FuzzyResult.Success(Map.of()));
        }
        var result = queryUsages(List.of(target), maxFiles, maxUsages);
        return switch (result.result()) {
            case FuzzyResult.Success success ->
                new UsageQueryResult(
                        result.candidateFiles(),
                        result.candidateFilesTruncated(),
                        new FuzzyResult.Success(LlmUsageAnalyzer.filterByConfidence(success.hitsByOverload())));
            case FuzzyResult.Ambiguous ambiguous ->
                new UsageQueryResult(
                        result.candidateFiles(),
                        result.candidateFilesTruncated(),
                        new FuzzyResult.Ambiguous(
                                ambiguous.shortName(),
                                ambiguous.candidateTargets(),
                                LlmUsageAnalyzer.filterByConfidence(ambiguous.hitsByOverload())));
            case FuzzyResult.TooManyCallsites tooMany -> result;
            case FuzzyResult.Failure failure -> result;
        };
    }

    public UsageQueryResult queryUsages(
            CodeUnit target, CandidateFileProvider candidateProvider, int maxFiles, int maxUsages)
            throws InterruptedException {
        if (isEffectivelyEmpty()) {
            return new UsageQueryResult(Set.of(), false, new FuzzyResult.Success(Map.of()));
        }
        var result = queryUsages(List.of(target), maxFiles, maxUsages, candidateProvider, false);
        return switch (result.result()) {
            case FuzzyResult.Success success ->
                new UsageQueryResult(
                        result.candidateFiles(),
                        result.candidateFilesTruncated(),
                        new FuzzyResult.Success(LlmUsageAnalyzer.filterByConfidence(success.hitsByOverload())));
            case FuzzyResult.Ambiguous ambiguous ->
                new UsageQueryResult(
                        result.candidateFiles(),
                        result.candidateFilesTruncated(),
                        new FuzzyResult.Ambiguous(
                                ambiguous.shortName(),
                                ambiguous.candidateTargets(),
                                LlmUsageAnalyzer.filterByConfidence(ambiguous.hitsByOverload())));
            case FuzzyResult.TooManyCallsites tooMany -> result;
            case FuzzyResult.Failure failure -> result;
        };
    }

    public FuzzyResult findUsages(CodeUnit target, int maxFiles, int maxUsages) throws InterruptedException {
        return queryUsages(target, maxFiles, maxUsages).result();
    }

    private boolean isEffectivelyEmpty() {
        if (analyzer.isEmpty()) {
            return true;
        }
        var files = project.getAllFiles();
        return files.isEmpty();
    }
}
