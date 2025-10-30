package ai.brokk.analyzer;

import ai.brokk.IProject;
import ai.brokk.util.Environment;
import ai.brokk.util.ExecutorServiceUtil;
import ai.brokk.util.TextCanonicalizer;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.*;

/**
 * Generic, language-agnostic skeleton extractor backed by Tree-sitter. Stores summarized skeletons for top-level
 * definitions only.
 *
 * <p>Subclasses provide the language–specific bits: which Tree-sitter grammar, which file extensions, which query, and
 * how to map a capture to a {@link CodeUnit}.
 */
public abstract class TreeSitterAnalyzer implements IAnalyzer, SkeletonProvider, SourceCodeProvider, TypeAliasProvider {
    protected static final Logger log = LoggerFactory.getLogger(TreeSitterAnalyzer.class);
    // Native library loading is assumed automatic by the io.github.bonede.tree_sitter library.

    // Adaptive concurrency for I/O: derived from OS file-descriptor limits with conservative headroom.
    private static final int IO_VT_CAP = Environment.computeAdaptiveIoConcurrencyCap();
    // Semaphore further gates simultaneous file openings to avoid EMFILE even under short bursts.
    private static final Semaphore IO_FD_SEMAPHORE = new Semaphore(Math.max(8, IO_VT_CAP), true);
    private static final int MAX_IO_READ_RETRIES = 6; // exponential backoff attempts for EMFILE

    // Common separators across languages to denote hierarchy or member access.
    // Includes: '.' (Java/others), '$' (Java nested classes), '::' (C++/C#/Ruby), '->' (PHP), etc.
    private static final Set<String> COMMON_HIERARCHY_SEPARATORS = Set.of(".", "$", "::", "->");

    // Definition priority constants - lower values are preferred for sorting
    protected static final int PRIORITY_DEFAULT = 0;
    protected static final int PRIORITY_HIGH = -1;
    protected static final int PRIORITY_LOW = 1;

    // Comparator for sorting CodeUnit definitions by priority
    private final Comparator<CodeUnit> DEFINITION_COMPARATOR = Comparator.comparingInt(
                    (CodeUnit cu) -> firstStartByteForSelection(cu))
            .thenComparing(cu -> cu.source().toString(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(CodeUnit::fqName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(cu -> cu.kind().name());

    // ephemeral instance state
    private final ThreadLocal<TSLanguage> threadLocalLanguage = ThreadLocal.withInitial(this::createTSLanguage);
    private final ThreadLocal<TSParser> threadLocalParser = ThreadLocal.withInitial(() -> {
        var parser = new TSParser();
        if (!parser.setLanguage(getTSLanguage())) {
            log.error(
                    "Failed to set language on TSParser for {}",
                    getTSLanguage().getClass().getSimpleName());
        }
        return parser;
    });
    private final ThreadLocal<TSQuery> query;

    /**
     * Gets the thread-local query for use in subclass overrides.
     * @return the thread-local query instance
     */
    protected TSQuery getThreadLocalQuery() {
        return query.get();
    }

    // transferable snapshot of analyzer state
    private volatile AnalyzerState state;

    /**
     * Properties for a given {@link ProjectFile} for {@link TreeSitterAnalyzer}.
     *
     * @param topLevelCodeUnits the top-level code units.
     * @param parsedTree the corresponding parse tree.
     * @param importStatements imports found on this file.
     */
    public record FileProperties(
            List<CodeUnit> topLevelCodeUnits, @Nullable TSTree parsedTree, List<String> importStatements) {

        public static FileProperties empty() {
            return new FileProperties(Collections.emptyList(), null, Collections.emptyList());
        }
    }

    /**
     * Read-only index of symbol keys with efficient prefix scan.
     */
    record SymbolKeyIndex(NavigableSet<String> keys) {

        Iterable<String> tailFrom(String fromInclusive) {
            return () -> keys.tailSet(fromInclusive, true).iterator();
        }

        Iterable<String> all() {
            return keys;
        }

        int size() {
            return keys.size();
        }
    }

    protected record AnalyzerState(
            PMap<String, List<CodeUnit>> symbolIndex,
            PMap<CodeUnit, CodeUnitProperties> codeUnitState,
            PMap<ProjectFile, FileProperties> fileState,
            SymbolKeyIndex symbolKeyIndex,
            long snapshotEpochNanos) {}

    // Timestamp of the last successful full-project update (epoch nanos)
    private final AtomicLong lastUpdateEpochNanos = new AtomicLong(0L);
    // Over-approximation buffer for filesystem mtime comparisons (nanos)
    private static final long MTIME_EPSILON_NANOS = TimeUnit.MILLISECONDS.toNanos(300);

    private final IProject project;
    private final Language language;
    protected final Set<Path> normalizedExcludedPaths;

    /**
     * Stores information about a definition found by a query match, including associated modifier keywords and
     * decorators.
     */
    protected record DefinitionInfoRecord(
            String primaryCaptureName, String simpleName, List<String> modifierKeywords, List<TSNode> decoratorNodes) {}

    protected record LanguageSyntaxProfile(
            Set<String> classLikeNodeTypes,
            Set<String> functionLikeNodeTypes,
            Set<String> fieldLikeNodeTypes,
            Set<String> decoratorNodeTypes,
            String importNodeType,
            String identifierFieldName,
            String bodyFieldName,
            String parametersFieldName,
            String returnTypeFieldName,
            String typeParametersFieldName, // For generics on type aliases, classes, functions etc.
            Map<String, SkeletonType> captureConfiguration,
            String asyncKeywordNodeType,
            Set<String> modifierNodeTypes) {}

    private record FileAnalysisResult(
            List<CodeUnit> topLevelCUs,
            Map<CodeUnit, CodeUnitProperties> codeUnitState,
            Map<String, List<CodeUnit>> codeUnitsBySymbol,
            List<String> importStatements,
            TSTree parsedTree) {}

    // Timing metrics for constructor-run analysis are tracked via a local Timing record instance.
    private record ConstructionTiming(
            AtomicLong readStageNanos,
            AtomicLong parseStageNanos,
            AtomicLong processStageNanos,
            AtomicLong mergeStageNanos,
            AtomicLong readStageFirstStartNanos,
            AtomicLong readStageLastEndNanos,
            AtomicLong parseStageFirstStartNanos,
            AtomicLong parseStageLastEndNanos,
            AtomicLong processStageFirstStartNanos,
            AtomicLong processStageLastEndNanos,
            AtomicLong mergeStageFirstStartNanos,
            AtomicLong mergeStageLastEndNanos) {

        static ConstructionTiming create() {
            return new ConstructionTiming(
                    new AtomicLong(),
                    new AtomicLong(),
                    new AtomicLong(),
                    new AtomicLong(),
                    new AtomicLong(Long.MAX_VALUE),
                    new AtomicLong(0L),
                    new AtomicLong(Long.MAX_VALUE),
                    new AtomicLong(0L),
                    new AtomicLong(Long.MAX_VALUE),
                    new AtomicLong(0L),
                    new AtomicLong(Long.MAX_VALUE),
                    new AtomicLong(0L));
        }
    }

    /* ---------- constructor ---------- */
    protected TreeSitterAnalyzer(IProject project, Language language) {
        this.project = project;
        this.language = language;
        this.normalizedExcludedPaths = project.getExcludedDirectories().stream()
                .map(Path::of)
                .map(p -> p.isAbsolute()
                        ? p.normalize()
                        : project.getRoot().resolve(p).toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
        if (!this.normalizedExcludedPaths.isEmpty()) {
            log.debug("Normalized excluded paths: {}", this.normalizedExcludedPaths);
        }

        // Initialize query using a ThreadLocal for thread safety
        // The supplier will use the appropriate getQueryResource() from the subclass
        // and getTSLanguage() for the current thread.
        this.query = ThreadLocal.withInitial(() -> {
            String rawQueryString = loadResource(getQueryResource());
            return new TSQuery(getTSLanguage(), rawQueryString);
        });

        // Debug log using SLF4J
        log.debug(
                "Initializing TreeSitterAnalyzer for language: {}, query resource: {}",
                this.language,
                getQueryResource());

        var validExtensions = this.language.getExtensions();
        log.trace("Filtering project files for extensions: {}", validExtensions);

        // Track processing statistics for better diagnostics
        var totalFilesAttempted = new AtomicInteger(0);
        var successfullyProcessed = new AtomicInteger(0);
        var failedFiles = new AtomicInteger(0);

        // Collect files to process
        List<ProjectFile> filesToProcess = project.getAllFiles().stream()
                .filter(pf -> {
                    var filePath = pf.absPath().toAbsolutePath().normalize();

                    var excludedBy = normalizedExcludedPaths.stream()
                            .filter(filePath::startsWith)
                            .findFirst();

                    if (excludedBy.isPresent()) {
                        log.trace("Skipping excluded file due to rule {}: {}", excludedBy.get(), pf);
                        return false;
                    }

                    var extension = pf.extension();
                    return validExtensions.contains(extension);
                })
                .toList();

        var timing = ConstructionTiming.create();
        // Local mutable maps to accumulate analysis results, then snapshotted into immutable PMaps
        var localSymbolIndex = new ConcurrentHashMap<String, List<CodeUnit>>();
        var localCodeUnitState = new ConcurrentHashMap<CodeUnit, CodeUnitProperties>();
        var localFileState = new ConcurrentHashMap<ProjectFile, FileProperties>();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        // Executors: virtual threads for I/O/parsing, single-thread for ingestion
        try (var ioExecutor = ExecutorServiceUtil.newVirtualThreadExecutor("ts-io-", IO_VT_CAP);
                var parseExecutor = ExecutorServiceUtil.newFixedThreadExecutor(
                        Runtime.getRuntime().availableProcessors(), "ts-parse-");
                var ingestExecutor = ExecutorServiceUtil.newFixedThreadExecutor(
                        Runtime.getRuntime().availableProcessors(), "ts-ingest-")) {
            for (var pf : filesToProcess) {
                CompletableFuture<Void> future = CompletableFuture.supplyAsync(
                                () -> {
                                    totalFilesAttempted.incrementAndGet();
                                    return readFileBytes(pf, timing);
                                },
                                ioExecutor)
                        .thenApplyAsync(fileBytes -> analyzeFile(pf, fileBytes, timing), parseExecutor)
                        .thenAcceptAsync(
                                analysisResult -> mergeAnalysisResultIntoMaps(
                                        pf,
                                        analysisResult,
                                        timing,
                                        localSymbolIndex,
                                        localCodeUnitState,
                                        localFileState),
                                ingestExecutor)
                        .whenComplete((ignored, ex) -> {
                            if (ex == null) {
                                successfullyProcessed.incrementAndGet();
                            } else {
                                failedFiles.incrementAndGet();
                                Throwable cause = (ex instanceof CompletionException ce && ce.getCause() != null)
                                        ? ce.getCause()
                                        : ex;

                                if (cause instanceof UncheckedIOException uioe) {
                                    var ioe = uioe.getCause();
                                    log.warn(
                                            "IO error analyzing {}: {}",
                                            pf,
                                            ioe != null ? ioe.getMessage() : uioe.getMessage());
                                } else if (cause instanceof RuntimeException re) {
                                    log.error("Runtime error analyzing {}: {}", pf, re.getMessage(), re);
                                } else {
                                    log.warn("Error analyzing {}: {}", pf, cause.getMessage(), cause);
                                }
                            }
                        })
                        .exceptionally(ex -> null); // exceptions have been logged already, don't re-throw

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // Build immutable snapshot state from accumulated maps
        var snapshotInstant = Instant.now();
        long snapshotNanos = snapshotInstant.getEpochSecond() * 1_000_000_000L + snapshotInstant.getNano();

        // Precompute a read-only navigable index of symbol keys for efficient prefix scans
        var keySet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        keySet.addAll(localSymbolIndex.keySet());
        var symbolKeyIndex = new SymbolKeyIndex(Collections.unmodifiableNavigableSet(keySet));

        this.state = new AnalyzerState(
                HashTreePMap.from(localSymbolIndex),
                HashTreePMap.from(localCodeUnitState),
                HashTreePMap.from(localFileState),
                symbolKeyIndex,
                snapshotNanos);

        // Log summary of file processing results
        int totalAttempted = totalFilesAttempted.get();
        int successful = successfullyProcessed.get();
        int failed = failedFiles.get();

        if (failed > 0) {
            log.warn(
                    "File processing summary: {} attempted, {} successful, {} failed",
                    totalAttempted,
                    successful,
                    failed);
        } else {
            log.info("File processing summary: {} files processed successfully", successful);
        }

        // Wall-clock timings per stage (coverage windows; stages overlap)
        long readWall = wallDuration(timing.readStageFirstStartNanos(), timing.readStageLastEndNanos());
        long parseWall = wallDuration(timing.parseStageFirstStartNanos(), timing.parseStageLastEndNanos());
        long processWall = wallDuration(timing.processStageFirstStartNanos(), timing.processStageLastEndNanos());
        long mergeWall = wallDuration(timing.mergeStageFirstStartNanos(), timing.mergeStageLastEndNanos());

        // Total wall clock derived from stage coverage: min(firstStart) .. max(lastEnd)
        long totalStart = Math.min(
                Math.min(
                        timing.readStageFirstStartNanos().get(),
                        timing.parseStageFirstStartNanos().get()),
                Math.min(
                        timing.processStageFirstStartNanos().get(),
                        timing.mergeStageFirstStartNanos().get()));
        long totalEnd = Math.max(
                Math.max(
                        timing.readStageLastEndNanos().get(),
                        timing.parseStageLastEndNanos().get()),
                Math.max(
                        timing.processStageLastEndNanos().get(),
                        timing.mergeStageLastEndNanos().get()));
        long totalWall =
                (totalStart == Long.MAX_VALUE || totalEnd == 0L || totalEnd < totalStart) ? 0L : totalEnd - totalStart;

        log.debug(
                "[{}] Stage timing (wall clock coverage; stages overlap): Read Files={}, Parse Files={}, Process Files={}, Merge Results={}, Total={}",
                language.name(),
                formatSecondsMillis(readWall),
                formatSecondsMillis(parseWall),
                formatSecondsMillis(processWall),
                formatSecondsMillis(mergeWall),
                formatSecondsMillis(totalWall));

        log.debug(
                "[{}] TreeSitter analysis complete - codeUnits: {}, files: {}",
                language.name(),
                state.codeUnitState().size(),
                state.fileState().size());

        // Record time of initial analysis to support mtime-based incremental updates (nanos precision)
        var initInstant = Instant.now();
        long initNowNanos = initInstant.getEpochSecond() * 1_000_000_000L + initInstant.getNano();
        lastUpdateEpochNanos.set(initNowNanos);
    }

    /**
     * Secondary constructor for snapshot instances: does not perform initial project-wide analysis,
     * but installs the provided prebuilt AnalyzerState as-is.
     */
    protected TreeSitterAnalyzer(IProject project, Language language, AnalyzerState prebuiltState) {
        this.project = project;
        this.language = language;

        this.normalizedExcludedPaths = project.getExcludedDirectories().stream()
                .map(Path::of)
                .map(p -> p.isAbsolute()
                        ? p.normalize()
                        : project.getRoot().resolve(p).toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());

        this.query = ThreadLocal.withInitial(() -> {
            String rawQueryString = loadResource(getQueryResource());
            return new TSQuery(getTSLanguage(), rawQueryString);
        });

        this.state = prebuiltState;

        // Align last update watermark with snapshot's epoch for incremental detection semantics.
        this.lastUpdateEpochNanos.set(prebuiltState.snapshotEpochNanos());
        log.debug(
                "[{}] Snapshot TreeSitterAnalyzer created - codeUnits: {}, files: {}",
                language.name(),
                state.codeUnitState().size(),
                state.fileState().size());
    }

    /**
     * Frees memory from the parsed AST cache.
     */
    public void clearCaches() {
        var current = this.state;
        // Drop parsed trees by nulling them inside FileProperties
        var newFileState = current.fileState().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new FileProperties(
                                e.getValue().topLevelCodeUnits(),
                                null,
                                e.getValue().importStatements())));
        this.state = new AnalyzerState(
                current.symbolIndex(),
                current.codeUnitState(),
                HashTreePMap.from(newFileState),
                current.symbolKeyIndex(),
                current.snapshotEpochNanos());
    }

    /**
     * A snapshot-safe way to interact with the "codeUnitState" field.
     */
    protected <R> R withCodeUnitProperties(Function<Map<CodeUnit, CodeUnitProperties>, R> function) {
        var current = this.state;
        return function.apply(current.codeUnitState());
    }

    /**
     * A snapshot-safe way to interact with the "fileState" field.
     */
    public <R> R withFileProperties(Function<Map<ProjectFile, FileProperties>, R> function) {
        var current = this.state;
        return function.apply(current.fileState());
    }

    /* ---------- Helper methods for accessing CodeUnits ---------- */

    /**
     * All CodeUnits we know about (top-level + children).
     */
    private Stream<CodeUnit> allCodeUnits() {
        var current = this.state;
        Stream<CodeUnit> parentStream = current.codeUnitState().keySet().stream();
        Stream<CodeUnit> childrenStream =
                current.codeUnitState().values().stream().flatMap(x -> x.children().stream());
        return Stream.concat(parentStream, childrenStream).distinct();
    }

    /**
     * De-duplicate and materialize into a List once.
     */
    private List<CodeUnit> uniqueCodeUnitList() {
        return allCodeUnits().distinct().toList();
    }

    /* ---------- Helper methods for accessing various properties ---------- */

    private CodeUnitProperties codeUnitProperties(CodeUnit codeUnit) {
        return withCodeUnitProperties(props -> props.getOrDefault(codeUnit, CodeUnitProperties.empty()));
    }

    protected List<CodeUnit> childrenOf(CodeUnit codeUnit) {
        return codeUnitProperties(codeUnit).children();
    }

    protected List<String> signaturesOf(CodeUnit codeUnit) {
        return codeUnitProperties(codeUnit).signatures();
    }

    protected List<Range> rangesOf(CodeUnit codeUnit) {
        return codeUnitProperties(codeUnit).ranges();
    }

    private FileProperties fileProperties(ProjectFile file) {
        return withFileProperties(props -> props.getOrDefault(file, FileProperties.empty()));
    }

    @Override
    public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
        return fileProperties(file).topLevelCodeUnits();
    }

    @Override
    public List<String> importStatementsOf(ProjectFile file) {
        return fileProperties(file).importStatements();
    }

    protected @Nullable TSTree treeOf(ProjectFile file) {
        return fileProperties(file).parsedTree();
    }

    /* ---------- IAnalyzer ---------- */
    @Override
    public Set<Language> languages() {
        return Set.of(language);
    }

    @Override
    public Optional<String> getSkeletonHeader(CodeUnit cu) {
        return Optional.of(reconstructFullSkeleton(cu, true));
    }

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        final String normalizedFqName = normalizeFullName(fqName);

        List<CodeUnit> matches = uniqueCodeUnitList().stream()
                .filter(cu -> cu.fqName().equals(normalizedFqName))
                .toList();

        if (matches.isEmpty()) {
            return Optional.empty();
        }

        return matches.stream().sorted(DEFINITION_COMPARATOR).findFirst();
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return uniqueCodeUnitList().stream().filter(CodeUnit::isClass).toList();
    }

    @Override
    public List<CodeUnit> getSubDeclarations(CodeUnit cu) {
        return childrenOf(cu);
    }

    @Override
    public List<CodeUnit> searchDefinitionsImpl(
            String originalPattern, @Nullable String fallbackPattern, @Nullable Pattern compiledPattern) {
        // an explicit search for everything should return everything, not just classes
        if (originalPattern.equals(".*")) {
            return uniqueCodeUnitList();
        }
        var anonPredicate = new Predicate<CodeUnit>() {
            @Override
            public boolean test(CodeUnit codeUnit) {
                return !isAnonymousStructure(codeUnit.fqName());
            }
        };

        if (fallbackPattern != null) {
            // Fallback to simple case-insensitive substring matching
            return uniqueCodeUnitList().stream()
                    .filter(cu -> cu.fqName().toLowerCase(Locale.ROOT).contains(fallbackPattern))
                    .filter(anonPredicate)
                    .toList();
        } else if (compiledPattern != null) {
            // Primary search using compiled regex pattern
            return uniqueCodeUnitList().stream()
                    .filter(cu -> compiledPattern.matcher(cu.fqName()).find())
                    .filter(anonPredicate)
                    .toList();
        } else {
            return uniqueCodeUnitList().stream()
                    .filter(cu -> cu.fqName().toLowerCase(Locale.ROOT).contains(originalPattern))
                    .filter(anonPredicate)
                    .toList();
        }
    }

    @Override
    public List<CodeUnit> autocompleteDefinitions(String query) {
        if (query.isEmpty()) {
            return List.of();
        }

        var results = new LinkedHashSet<CodeUnit>();
        final String lowerCaseQuery = query.toLowerCase(Locale.ROOT);

        // CamelCase-style query detection (all uppercase letters, length > 1)
        boolean isAllUpper = query.length() > 1 && query.chars().allMatch(Character::isUpperCase);
        Pattern camelCasePattern = null;
        if (isAllUpper) {
            camelCasePattern = Pattern.compile(
                    query.chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining("[a-z0-9_]*")),
                    Pattern.CASE_INSENSITIVE);
        }

        // Prefix optimization when the query looks like a simple non-hierarchical prefix
        boolean usePrefixOptimization =
                !containsAnyHierarchySeparator(lowerCaseQuery) && !isAllUpper && query.length() >= 2;

        var current = this.state;

        if (usePrefixOptimization) {
            var keyIndex = current.symbolKeyIndex();
            try {
                for (String symbol : keyIndex.tailFrom(query)) {
                    String symbolLower = symbol.toLowerCase(Locale.ROOT);
                    if (!symbolLower.startsWith(lowerCaseQuery)) {
                        break; // stop when the prefix no longer matches
                    }
                    results.addAll(current.symbolIndex().getOrDefault(symbol, List.of()));
                }
            } catch (IllegalArgumentException e) {
                // Defensive fallback: if tail scan fails for any reason, ignore and continue with generic scan
                log.debug("Prefix optimization fallback for query '{}': {}", query, e.toString());
            }
        }

        // Generic scan: accept substring or CamelCase camel-hump matches.
        // Skip symbols already covered by the prefix optimization to avoid duplicate work.
        Iterable<String> allKeysIterable = current.symbolKeyIndex().all();
        for (String symbol : allKeysIterable) {
            String symbolLower = symbol.toLowerCase(Locale.ROOT);

            if (usePrefixOptimization && symbolLower.startsWith(lowerCaseQuery)) {
                continue; // already collected by prefix scan
            }

            boolean matches = false;
            if (symbolLower.contains(lowerCaseQuery)) {
                matches = true;
            } else if (isAllUpper
                    && camelCasePattern != null
                    && camelCasePattern.matcher(symbol).find()) {
                matches = true;
            }

            if (matches) {
                results.addAll(current.symbolIndex().getOrDefault(symbol, List.of()));
            }
        }

        // Fallback for very short queries (single letter): include any declarations with FQNs containing the query.
        if (query.length() == 1) {
            uniqueCodeUnitList().stream()
                    .filter(cu -> cu.fqName().toLowerCase(Locale.ROOT).contains(lowerCaseQuery))
                    .forEach(results::add);
        }

        return results.stream().filter(cu -> !isAnonymousStructure(cu.fqName())).toList();
    }

    /**
     * Returns the top-level declarations organized by file. This method is primarily for testing to examine the raw
     * declarations before they are filtered by getAllDeclarations().
     *
     * @return Map from ProjectFile to List of CodeUnits declared at the top level in that file
     */
    public Map<ProjectFile, List<CodeUnit>> getTopLevelDeclarations() {
        final Map<ProjectFile, List<CodeUnit>> result = new HashMap<>();
        var current = this.state;
        current.fileState().forEach((file, fileProperties) -> result.put(file, fileProperties.topLevelCodeUnits()));
        return Map.copyOf(result);
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        // Only process files relevant to this analyzer's language
        if (!isRelevantFile(file)) {
            return Map.of();
        }

        List<CodeUnit> topCUs = getTopLevelDeclarations(file);
        if (topCUs.isEmpty()) return Map.of();

        Map<CodeUnit, String> resultSkeletons = new HashMap<>();
        List<CodeUnit> sortedTopCUs = new ArrayList<>(topCUs);
        // Sort CUs: MODULE CUs (for imports) should ideally come first.
        // This simple sort puts them first if their fqName sorts before others.
        // A more explicit sort could check cu.isModule().
        Collections.sort(sortedTopCUs);

        for (CodeUnit cu : sortedTopCUs) {
            resultSkeletons.put(cu, reconstructFullSkeleton(cu, false));
        }
        log.trace("getSkeletons: file={}, count={}", file, resultSkeletons.size());
        return Collections.unmodifiableMap(resultSkeletons);
    }

    @Override
    public Set<CodeUnit> getDeclarations(ProjectFile file) {
        // Only process files relevant to this analyzer's language
        if (!isRelevantFile(file)) {
            return Set.of();
        }

        List<CodeUnit> topCUs = getTopLevelDeclarations(file);
        if (topCUs.isEmpty()) return Set.of();

        Set<CodeUnit> allDeclarationsInFile = new HashSet<>();
        Queue<CodeUnit> toProcess = new ArrayDeque<>(topCUs); // Changed to ArrayDeque
        Set<CodeUnit> visited = new HashSet<>(topCUs); // Track visited to avoid cycles and redundant processing

        while (!toProcess.isEmpty()) {
            CodeUnit current = toProcess.poll();
            allDeclarationsInFile.add(current); // Add all encountered CodeUnits

            childrenOf(current).forEach(child -> {
                if (visited.add(child)) { // Add to queue only if not visited
                    toProcess.add(child);
                }
            });
        }
        log.trace("getDeclarationsInFile: file={}, count={}", file, allDeclarationsInFile.size());
        return Collections.unmodifiableSet(allDeclarationsInFile);
    }

    private String reconstructFullSkeleton(CodeUnit cu, boolean headerOnly) {
        StringBuilder sb = new StringBuilder();
        reconstructSkeletonRecursive(cu, "", headerOnly, sb);
        return sb.toString().stripTrailing();
    }

    private void reconstructSkeletonRecursive(CodeUnit cu, String indent, boolean headerOnly, StringBuilder sb) {
        final List<String> sigList = signaturesOf(cu);

        if (sigList.isEmpty()) {
            // It's possible for some CUs (e.g., a namespace CU acting only as a parent) to not have direct textual
            // signatures.
            // This can be legitimate if they are primarily structural and their children form the content.
            log.trace(
                    "No direct signatures found for CU: {}. It might be a structural-only CU. Skipping direct rendering in skeleton reconstruction.",
                    cu);
            return;
        }

        for (var individualFullSignature : sigList) {
            if (individualFullSignature.isBlank()) {
                log.warn("Encountered null or blank signature in list for CU: {}. Skipping this signature.", cu);
                continue;
            }
            // Apply indent to each line of the current signature
            String[] signatureLines = individualFullSignature.split("\n", -1); // Use -1 limit
            for (var line : signatureLines) {
                sb.append(indent).append(line).append('\n');
            }
        }

        final List<CodeUnit> allChildren = childrenOf(cu);

        final var kids = allChildren.stream()
                .filter(child -> !headerOnly || child.isField())
                .toList();
        // Only add children and class closer.
        // Functions may have children (e.g., lambdas) but should NOT emit a closer in skeletons.
        if (!kids.isEmpty()
                || (cu.isClass() && !getLanguageSpecificCloser(cu).isEmpty())) { // also add closer for empty classes
            var childIndent = indent + getLanguageSpecificIndent();
            for (var kid : kids) {
                reconstructSkeletonRecursive(kid, childIndent, headerOnly, sb);
            }
            if (headerOnly && cu.isClass()) {
                final int nonFieldKidsSize = allChildren.size() - kids.size();
                if (nonFieldKidsSize > 0) {
                    sb.append(childIndent).append("[...]").append("\n");
                }
            }
            if (cu.isClass()) {
                var closer = getLanguageSpecificCloser(cu);
                if (!closer.isEmpty()) {
                    sb.append(indent).append(closer).append('\n');
                }
            }
        }
    }

    @Override
    public Optional<String> getSkeleton(CodeUnit cu) {
        var skeleton = reconstructFullSkeleton(cu, false);
        log.trace("getSkeleton: fqName='{}', found=true", cu.fqName());
        return Optional.of(skeleton);
    }

    private static boolean containsAnyHierarchySeparator(String s) {
        for (String sep : COMMON_HIERARCHY_SEPARATORS) {
            if (s.contains(sep)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Assuming the fqName is an entity nested within a method, a type, or is a method itself, will return the fqName of
     * the nearest method or type/class. This is useful with escaping lambdas to their parent method, or normalizing
     * full names with generic type arguments.
     *
     * @param fqName the fqName of a code unit.
     * @return the surrounding method or type, or the given fqName otherwise.
     */
    protected String normalizeFullName(String fqName) {
        // Should be overridden by the subclasses
        return fqName;
    }

    /**
     * Hook for language-specific preference when multiple CodeUnits share the same FQN. Lower values are preferred.
     * Default is PRIORITY_DEFAULT (no preference).
     */
    protected int definitionOverridePriority(CodeUnit cu) {
        return PRIORITY_DEFAULT;
    }

    /**
     * Returns the earliest startByte among recorded ranges for deterministic ordering.
     */
    private int firstStartByteForSelection(CodeUnit cu) {
        return rangesOf(cu).stream().mapToInt(Range::startByte).min().orElse(Integer.MAX_VALUE);
    }

    @Override
    public Optional<String> getClassSource(CodeUnit cu, boolean includeComments) {
        if (!cu.isClass()) {
            return Optional.empty();
        }

        var ranges = rangesOf(cu);
        if (ranges.isEmpty()) {
            return Optional.empty();
        }

        // For classes, expect one primary definition range (already expanded with comments)
        var range = ranges.getFirst();

        var srcOpt = cu.source().read();
        if (srcOpt.isEmpty()) {
            return Optional.empty();
        }
        String src = TextCanonicalizer.stripUtf8Bom(srcOpt.get());

        // Choose start byte based on includeComments parameter
        int extractStartByte = includeComments ? range.commentStartByte() : range.startByte();
        var extractedSource = ASTTraversalUtils.safeSubstringFromByteOffsets(src, extractStartByte, range.endByte());

        return Optional.of(extractedSource);
    }

    @Override
    public Set<String> getMethodSources(CodeUnit cu, boolean includeComments) {
        if (!cu.isFunction()) {
            return Collections.emptySet();
        }

        List<Range> rangesForOverloads = rangesOf(cu);
        if (rangesForOverloads.isEmpty()) {
            log.warn("No source ranges found for CU {} (fqName {}) although definition was found.", cu, cu.fqName());
            return Collections.emptySet();
        }

        var fileContentOpt = cu.source().read();
        if (fileContentOpt.isEmpty()) {
            log.warn("Could not read source for CU {} (fqName {}): {}", cu, cu.fqName(), "unreadable");
            return Collections.emptySet();
        }
        String fileContent = TextCanonicalizer.stripUtf8Bom(fileContentOpt.get());

        var methodSources = new LinkedHashSet<String>();
        for (Range range : rangesForOverloads) {
            // Choose start byte based on includeComments parameter
            int extractStartByte = includeComments ? range.commentStartByte() : range.startByte();
            String methodSource =
                    ASTTraversalUtils.safeSubstringFromByteOffsets(fileContent, extractStartByte, range.endByte());
            if (!methodSource.isEmpty()) {
                methodSources.add(methodSource);
            } else {
                log.warn(
                        "Could not extract valid method source for range [{}, {}] for CU {} (fqName {}). Skipping this range.",
                        extractStartByte,
                        range.endByte(),
                        cu,
                        cu.fqName());
            }
        }
        if (methodSources.isEmpty()) {
            log.warn("After processing ranges, no valid method sources found for CU {} (fqName {}).", cu, cu.fqName());
        }
        return methodSources;
    }

    @Override
    public Optional<String> getSourceForCodeUnit(CodeUnit codeUnit, boolean includeComments) {
        if (codeUnit.isFunction()) {
            Set<String> sources = getMethodSources(codeUnit, includeComments);
            if (sources.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(String.join("\n\n", sources));
        } else if (codeUnit.isClass()) {
            return getClassSource(codeUnit, includeComments);
        } else {
            return Optional.empty(); // Fields and other types not supported by default
        }
    }

    @Override
    public boolean isTypeAlias(CodeUnit cu) {
        // Default: languages that don't support or expose type aliases return false.
        return false;
    }

    /**
     * Gets the starting line number (0-based) for the given CodeUnit for UI positioning purposes. Returns the original
     * code definition line (not expanded with comments) for better navigation.
     *
     * @param codeUnit The CodeUnit to get the line number for
     * @return The 0-based starting line number of the actual definition, or -1 if not found
     */
    public int getStartLineForCodeUnit(CodeUnit codeUnit) {
        var ranges = rangesOf(codeUnit);
        if (ranges.isEmpty()) {
            return -1;
        }
        var range = ranges.getFirst();
        return range.startLine();
    }

    /* ---------- abstract hooks ---------- */

    /**
     * Creates a new TSLanguage instance for the specific language. Called by ThreadLocal initializer.
     */
    protected abstract TSLanguage createTSLanguage();

    /**
     * Provides a thread-safe TSLanguage instance.
     *
     * @return A TSLanguage instance for the current thread.
     */
    protected TSLanguage getTSLanguage() {
        return threadLocalLanguage.get();
    }

    protected TSParser getTSParser() {
        return threadLocalParser.get();
    }

    /**
     * Provides the language-specific syntax profile.
     */
    protected abstract LanguageSyntaxProfile getLanguageSyntaxProfile();

    /**
     * Class-path resource for the query (e.g. {@code "treesitter/python.scm"}).
     */
    protected abstract String getQueryResource();

    /**
     * Defines the general type of skeleton that should be built for a given capture.
     */
    public enum SkeletonType {
        CLASS_LIKE,
        FUNCTION_LIKE,
        FIELD_LIKE,
        ALIAS_LIKE,
        DECORATOR,
        MODULE_STATEMENT, // For individual import/directive lines if treated as CUs
        UNSUPPORTED
    }

    /**
     * Determines the {@link SkeletonType} for a given capture name. This allows subclasses to map their specific query
     * capture names (e.g., "class.definition", "method.declaration") to a general category for skeleton building.
     *
     * @param captureName The name of the capture from the Tree-sitter query.
     * @return The {@link SkeletonType} indicating how to process this capture for skeleton generation.
     */
    protected SkeletonType getSkeletonTypeForCapture(String captureName) {
        var profile = getLanguageSyntaxProfile();
        return profile.captureConfiguration().getOrDefault(captureName, SkeletonType.UNSUPPORTED);
    }

    /**
     * Translate a capture produced by the query into a {@link CodeUnit}. Return {@code null} to ignore this capture.
     */
    @Nullable
    protected abstract CodeUnit createCodeUnit(
            ProjectFile file, String captureName, String simpleName, String packageName, String classChain);

    /**
     * Determines the package or namespace name for a given definition.
     *
     * @param file           The project file being analyzed.
     * @param definitionNode The TSNode representing the definition (e.g., class, function).
     * @param rootNode       The root TSNode of the file's syntax tree.
     * @param src            The source code of the file.
     * @return The package or namespace name, or an empty string if not applicable.
     */
    protected abstract String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, String src);

    /**
     * Checks if the given AST node represents a class-like declaration (e.g., class, interface, struct) in the specific
     * language. Subclasses must implement this to guide class chain extraction.
     *
     * @param node The TSNode to check.
     * @return true if the node is a class-like declaration, false otherwise.
     */
    protected boolean isClassLike(TSNode node) {
        if (node.isNull()) {
            return false;
        }
        return getLanguageSyntaxProfile().classLikeNodeTypes().contains(node.getType());
    }

    /**
     * Builds the parent FQName from class chain for parent-child relationship lookup. Override this
     * method to apply language-specific FQName correction logic.
     */
    protected String buildParentFqName(CodeUnit cu, String classChain) {
        return Stream.of(cu.packageName(), classChain).filter(s -> !s.isBlank()).collect(Collectors.joining("."));
    }

    /**
     * Captures that should be ignored entirely.
     */
    protected Set<String> getIgnoredCaptures() {
        return Set.of();
    }

    /**
     * Language-specific indentation string, e.g., " " or " ".
     */
    protected String getLanguageSpecificIndent() {
        return "  ";
    } // Default

    /**
     * Checks if a node should be skipped for top-level processing.
     * Default implementation returns false (no skipping).
     * Language-specific analyzers can override this to filter out certain nodes.
     */
    protected boolean shouldSkipNode(TSNode node, String captureName, byte[] srcBytes) {
        return false;
    }

    /**
     * Determines whether a duplicate CodeUnit with the same FQN should replace the existing one.
     * Default behavior is to keep the first definition and reject duplicates.
     *
     * @param cu the new CodeUnit that would be a duplicate
     * @return true if duplicates should replace existing (Python "last wins"), false otherwise
     */
    protected boolean shouldReplaceOnDuplicate(CodeUnit cu) {
        return false;
    }

    /**
     * Determines whether decorators are wrapped in a parent node vs appearing as preceding siblings.
     * Python wraps decorators in a decorated_definition node containing both decorators and the definition.
     * Other languages (TypeScript, Java) have decorators as preceding sibling nodes.
     *
     * @return true if decorators are wrapped in a parent node, false if they precede the definition
     */
    protected boolean hasWrappingDecoratorNode() {
        return false;
    }

    /**
     * Extracts the actual definition node from a decorator-wrapping node and collects decorator text.
     * Only called if hasWrappingDecoratorNode() returns true.
     *
     * @param decoratedNode the wrapping node (e.g., Python's decorated_definition)
     * @param outDecoratorLines list to append decorator text to
     * @param srcBytes source code bytes
     * @param profile language syntax profile for identifying decorator and definition node types
     * @return the unwrapped definition node
     */
    protected TSNode extractContentFromDecoratedNode(
            TSNode decoratedNode, List<String> outDecoratorLines, byte[] srcBytes, LanguageSyntaxProfile profile) {
        return decoratedNode; // default: no unwrapping needed
    }

    /**
     * Determines whether export statements should be unwrapped to access the inner declaration.
     * JavaScript/TypeScript wrap exported declarations in export_statement nodes.
     *
     * @return true if this language uses export statement wrappers that need unwrapping
     */
    protected boolean shouldUnwrapExportStatements() {
        return false;
    }

    /**
     * Determines whether variable declarations need unwrapping to find specific declarators.
     * JavaScript/TypeScript use lexical_declaration (const/let) and variable_declaration (var)
     * which contain variable_declarator nodes that might hold arrow functions or const values.
     *
     * @param node the node to check
     * @param skeletonType the expected skeleton type
     * @return true if unwrapping is needed
     */
    protected boolean needsVariableDeclaratorUnwrapping(TSNode node, SkeletonType skeletonType) {
        return false;
    }

    /**
     * Determines whether multiple signatures with the same FQN should be merged.
     * JavaScript/TypeScript allow function overloads and prefer exported versions.
     *
     * @return true if signatures should be merged when FQNs match
     */
    protected boolean shouldMergeSignaturesForSameFqn() {
        return false;
    }

    /**
     * Extracts receiver type for method definitions in languages that support receivers.
     * Examples: Go methods, Rust impl blocks, C++ member functions.
     *
     * @param node the method definition node
     * @param primaryCaptureName the primary capture name (e.g., "method.definition")
     * @param fileBytes source code bytes
     * @return the receiver type name (with leading * removed for pointers), or empty if no receiver
     */
    protected Optional<String> extractReceiverType(TSNode node, String primaryCaptureName, byte[] fileBytes) {
        return Optional.empty();
    }

    /**
     * Adds a CodeUnit to the top-level list, applying language-specific duplicate handling.
     * Duplicate handling is controlled by shouldReplaceOnDuplicate().
     */
    private void addTopLevelCodeUnit(
            CodeUnit cu,
            List<CodeUnit> localTopLevelCUs,
            Map<CodeUnit, List<CodeUnit>> localChildren,
            Map<CodeUnit, List<String>> localSignatures,
            Map<CodeUnit, List<Range>> localSourceRanges,
            ProjectFile file) {

        boolean alreadyExists =
                localTopLevelCUs.stream().anyMatch(existing -> existing.fqName().equals(cu.fqName()));

        if (!alreadyExists) {
            localTopLevelCUs.add(cu);
        } else if (shouldReplaceOnDuplicate(cu)) {
            // Language allows duplicate replacement (e.g., Python's "last wins" semantics)
            CodeUnit oldCu = localTopLevelCUs.stream()
                    .filter(existing -> existing.fqName().equals(cu.fqName()))
                    .findFirst()
                    .orElse(null);

            localTopLevelCUs.removeIf(existing -> existing.fqName().equals(cu.fqName()));
            localTopLevelCUs.add(cu);

            // Recursively remove the old definition and all its descendants from all maps
            // This prevents orphaned children from appearing in the final result
            if (oldCu != null) {
                removeCodeUnitAndDescendants(oldCu, localChildren, localSignatures, localSourceRanges);
            }
        } else {
            // Unexpected duplicate for languages that don't allow replacement
            log.error(
                    "Unexpected duplicate top-level CodeUnit in file {}: {} (kind={})",
                    file.getFileName(),
                    cu.fqName(),
                    cu.kind());
        }
    }

    /**
     * Recursively removes a CodeUnit and all its descendants from the analysis maps.
     * Used when replacing Python duplicates to ensure children of the old definition don't appear in results.
     */
    private void removeCodeUnitAndDescendants(
            CodeUnit cu,
            Map<CodeUnit, List<CodeUnit>> localChildren,
            Map<CodeUnit, List<String>> localSignatures,
            Map<CodeUnit, List<Range>> localSourceRanges) {

        log.trace("Removing CodeUnit from maps: {} (kind={})", cu.fqName(), cu.kind());

        // Get children before removing from map
        List<CodeUnit> children = localChildren.get(cu);

        // Remove this CodeUnit from all maps
        localChildren.remove(cu);
        localSignatures.remove(cu);
        localSourceRanges.remove(cu);

        // Recursively remove all descendants
        if (children != null) {
            log.trace("  Removing {} children of {}", children.size(), cu.fqName());
            for (CodeUnit child : children) {
                removeCodeUnitAndDescendants(child, localChildren, localSignatures, localSourceRanges);
            }
        }
    }

    /**
     * Adds a child CodeUnit to its parent's children list.
     * Duplicate handling is controlled by shouldReplaceOnDuplicate().
     * Similar to addTopLevelCodeUnit but for nested elements (methods, class attributes, nested classes).
     */
    private void addChildCodeUnit(
            CodeUnit cu,
            CodeUnit parentCu,
            List<CodeUnit> kids,
            Map<CodeUnit, List<CodeUnit>> localChildren,
            Map<CodeUnit, List<String>> localSignatures,
            Map<CodeUnit, List<Range>> localSourceRanges,
            ProjectFile file) {

        if (!kids.contains(cu)) {
            kids.add(cu);
        } else if (shouldReplaceOnDuplicate(cu)) {
            // Language allows duplicate replacement (e.g., Python's "last wins" semantics)
            CodeUnit oldCu = kids.stream().filter(k -> k.equals(cu)).findFirst().orElse(null);

            if (oldCu != null) {
                kids.remove(oldCu);
                removeCodeUnitAndDescendants(oldCu, localChildren, localSignatures, localSourceRanges);
                kids.add(cu);
            }
        } else {
            // For languages that don't allow replacement, just skip the duplicate
            log.trace("Skipping duplicate child: {} in parent {}", cu.fqName(), parentCu.fqName());
        }
    }

    /**
     * Language-specific closing token for a class or namespace (e.g., "}"). Empty if none.
     */
    protected abstract String getLanguageSpecificCloser(CodeUnit cu);

    /**
     * Get the project this analyzer is associated with.
     */
    @Override
    public IProject getProject() {
        return project;
    }

    /* ---------- core parsing ---------- */

    /**
     * Analyzes a single file and extracts declaration information from provided bytes.
     */
    private FileAnalysisResult analyzeFileContent(
            ProjectFile file,
            byte[] fileBytes,
            TSParser localParser,
            @Nullable TreeSitterAnalyzer.ConstructionTiming timing) {
        log.trace("analyzeFileContent: Parsing file: {}", file);
        // Skip binary files early if pre-filtered upstream (readFileBytes returns empty for binary)
        if (fileBytes.length == 0) {
            log.debug("Skipping binary/empty file: {}", file);
            return new FileAnalysisResult(List.of(), Map.of(), Map.of(), List.of(), null);
        }

        fileBytes = TextCanonicalizer.stripUtf8Bom(fileBytes);
        String src = new String(fileBytes, StandardCharsets.UTF_8);

        final byte[] finalFileBytes = fileBytes; // For use in lambdas

        List<CodeUnit> localTopLevelCUs = new ArrayList<>();
        Map<CodeUnit, List<CodeUnit>> localChildren = new HashMap<>();
        Map<CodeUnit, List<String>> localSignatures = new HashMap<>();
        Map<CodeUnit, List<Range>> localSourceRanges = new HashMap<>();
        Map<String, List<CodeUnit>> localCodeUnitsBySymbol = new HashMap<>();
        Map<String, CodeUnit> localCuByFqName = new HashMap<>(); // For parent lookup within the file
        List<String> localImportStatements = new ArrayList<>(); // For collecting import lines

        long __parseStart = System.nanoTime();
        TSTree tree = localParser.parseString(null, src);
        long __parseEnd = System.nanoTime();
        if (timing != null) {
            timing.parseStageNanos().addAndGet(__parseEnd - __parseStart);
            timing.parseStageFirstStartNanos().accumulateAndGet(__parseStart, Math::min);
            timing.parseStageLastEndNanos().accumulateAndGet(__parseEnd, Math::max);
        }
        TSNode rootNode = tree.getRootNode();
        long __processStart = System.nanoTime();
        if (rootNode.isNull()) {
            log.warn("Parsing failed or produced null root node for {}", file);
            long __processEnd = System.nanoTime();
            if (timing != null) {
                timing.processStageNanos().addAndGet(__processEnd - __processStart);
                timing.processStageFirstStartNanos().accumulateAndGet(__processStart, Math::min);
                timing.processStageLastEndNanos().accumulateAndGet(__processEnd, Math::max);
            }
            return new FileAnalysisResult(List.of(), Map.of(), Map.of(), List.of(), tree);
        }
        // Log root node type
        String rootNodeType = rootNode.getType();
        log.trace("Root node type for {}: {}", file, rootNodeType);

        // Map to store potential top-level declaration nodes found during the query.
        // Value stores primary capture name, simple name, and sorted modifier keywords.
        Map<TSNode, DefinitionInfoRecord> declarationNodes = new HashMap<>();

        TSQueryCursor cursor = new TSQueryCursor();
        TSQuery currentThreadQuery = this.query.get(); // Get thread-specific query instance
        cursor.exec(currentThreadQuery, rootNode);

        TSQueryMatch match = new TSQueryMatch(); // Reusable match object
        while (cursor.nextMatch(match)) {
            log.trace("Match ID: {}", match.getId());
            Map<String, TSNode> capturedNodesForMatch = new HashMap<>();
            List<TSNode> modifierNodesForMatch = new ArrayList<>();
            List<TSNode> decoratorNodesForMatch = new ArrayList<>();

            for (TSQueryCapture capture : match.getCaptures()) {
                String captureName = currentThreadQuery.getCaptureNameForId(capture.getIndex());
                if (getIgnoredCaptures().contains(captureName)) continue;

                TSNode node = capture.getNode();
                if (node != null && !node.isNull()) {
                    if ("keyword.modifier".equals(captureName)) {
                        modifierNodesForMatch.add(node);
                    } else if ("decorator.definition".equals(captureName)) {
                        decoratorNodesForMatch.add(node);
                        log.trace(
                                "  Decorator: '{}', Node: {} '{}'",
                                captureName,
                                node.getType(),
                                textSlice(node, fileBytes)
                                        .lines()
                                        .findFirst()
                                        .orElse("")
                                        .trim());
                    } else {
                        // Store the first non-null node found for other capture names in this match
                        capturedNodesForMatch.putIfAbsent(captureName, node);
                        log.trace(
                                "  Capture: '{}', Node: {} '{}'",
                                captureName,
                                node.getType(),
                                textSlice(node, fileBytes)
                                        .lines()
                                        .findFirst()
                                        .orElse("")
                                        .trim());
                    }
                }
            }

            modifierNodesForMatch.sort(Comparator.comparingInt(TSNode::getStartByte));
            List<String> sortedModifierStrings = modifierNodesForMatch.stream()
                    .map(modNode -> textSlice(modNode, finalFileBytes).strip())
                    .toList();
            if (!sortedModifierStrings.isEmpty()) {
                log.trace("  Modifiers for this match: {}", sortedModifierStrings);
            }

            decoratorNodesForMatch.sort(Comparator.comparingInt(TSNode::getStartByte));

            // Handle import statements first if present in this match
            TSNode importNode =
                    capturedNodesForMatch.get(getLanguageSyntaxProfile().importNodeType());
            if (importNode != null && !importNode.isNull()) {
                String importText = textSlice(importNode, fileBytes).strip();
                if (!importText.isEmpty()) {
                    localImportStatements.add(importText);
                }
                // Continue to next match if this was primarily an import, or process other captures in same match
                // For now, assume an import statement match won't also be a primary .definition capture.
                // If it can, then this 'if' should not 'continue' but allow further processing.
            }

            // Process each potential definition found in the match
            for (var captureEntry : capturedNodesForMatch.entrySet()) {
                String captureName = captureEntry.getKey();
                TSNode definitionNode = captureEntry.getValue();

                if (captureName.endsWith(".definition")) { // Ensure we only process definition captures here
                    String simpleName;
                    String expectedNameCapture = captureName.replace(".definition", ".name");
                    TSNode nameNode = capturedNodesForMatch.get(expectedNameCapture);

                    if ("lambda.definition".equals(captureName)) {
                        // Lambdas have no explicit name capture; synthesize an anonymous name via extractSimpleName
                        simpleName = extractSimpleName(definitionNode, src).orElse(null);
                    } else if (nameNode != null && !nameNode.isNull()) {
                        simpleName = textSlice(nameNode, fileBytes);
                        if (simpleName.isBlank()) {
                            log.debug(
                                    "Name capture '{}' for definition '{}' in file {} resulted in a BLANK string. NameNode text: [{}], type: [{}]. Will attempt fallback.",
                                    expectedNameCapture,
                                    captureName,
                                    file,
                                    textSlice(nameNode, fileBytes),
                                    nameNode.getType());
                            simpleName = extractSimpleName(definitionNode, src).orElse(null);
                        }
                    } else {
                        log.debug(
                                "Expected name capture '{}' not found for definition '{}' in match for file {}. Current captures in this match: {}. Falling back to extractSimpleName on definition node.",
                                expectedNameCapture,
                                captureName,
                                file,
                                capturedNodesForMatch.keySet());
                        simpleName = extractSimpleName(definitionNode, src).orElse(null);
                    }

                    if (simpleName != null && !simpleName.isBlank()) {
                        declarationNodes.putIfAbsent(
                                definitionNode,
                                new DefinitionInfoRecord(
                                        captureName, simpleName, sortedModifierStrings, decoratorNodesForMatch));
                    } else {
                        if (simpleName == null) {
                            log.debug(
                                    "Could not determine simple name (NULL) for definition capture {} (Node Type [{}], Line {}) in file {}.",
                                    captureName,
                                    definitionNode.getType(),
                                    definitionNode.getStartPoint().getRow() + 1,
                                    file);
                        } else {
                            log.debug(
                                    "Determined simple name for definition capture {} (Node Type [{}], Line {}) in file {} is BLANK. Definition will be skipped.",
                                    captureName,
                                    definitionNode.getType(),
                                    definitionNode.getStartPoint().getRow() + 1,
                                    file);
                        }
                    }
                }
            }
        } // End main query loop

        // Sort declaration nodes by their start byte to process outer definitions before inner ones.
        List<Map.Entry<TSNode, DefinitionInfoRecord>> sortedDeclarationEntries = declarationNodes.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().getStartByte()))
                .toList();

        TSNode currentRootNode = tree.getRootNode(); // Used for namespace and class chain extraction

        for (var entry : sortedDeclarationEntries) {
            TSNode node = entry.getKey(); // This is the definitionNode for this entry
            DefinitionInfoRecord defInfo = entry.getValue();
            String primaryCaptureName = defInfo.primaryCaptureName();
            String simpleName = defInfo.simpleName();
            if (isClassLike(node)) {
                simpleName = determineClassName(node.getType(), simpleName);
            }
            List<String> modifierKeywords = defInfo.modifierKeywords();

            if (simpleName.isBlank()) {
                log.warn(
                        "Simple name was null/blank for node type {} (capture: {}) in file {}. Skipping.",
                        node.getType(),
                        primaryCaptureName,
                        file);
                continue;
            }

            log.trace(
                    "Processing definition: Name='{}', Capture='{}', Node Type='{}'",
                    simpleName,
                    primaryCaptureName,
                    node.getType());

            String packageName = determinePackageName(file, node, currentRootNode, src);
            List<String> enclosingClassNames = new ArrayList<>();
            TSNode tempParent = node.getParent();
            while (tempParent != null && !tempParent.isNull() && !tempParent.equals(currentRootNode)) {
                if (isClassLike(tempParent)) {
                    final var parent = tempParent;
                    extractSimpleName(tempParent, src)
                            .ifPresent(
                                    parentName -> { // extractSimpleName is now non-static
                                        if (!parentName.isBlank()) {
                                            var name = isClassLike(parent)
                                                    ? determineClassName(parent.getType(), parentName)
                                                    : parentName;
                                            enclosingClassNames.addFirst(name);
                                        }
                                    });
                }
                tempParent = tempParent.getParent();
            }
            String classChain = String.join(".", enclosingClassNames);
            log.trace("Computed classChain for simpleName='{}': '{}'", simpleName, classChain);

            // Adjust simpleName and classChain for methods with receivers (e.g., Go methods)
            Optional<String> receiverType = extractReceiverType(node, primaryCaptureName, fileBytes);
            if (receiverType.isPresent()) {
                String receiverTypeText = receiverType.get();
                simpleName = receiverTypeText + "." + simpleName;
                classChain = receiverTypeText; // For methods with receivers, classChain is the receiver type
                log.trace("Adjusted method with receiver: simpleName='{}', classChain='{}'", simpleName, classChain);
            }

            // Check if this node should be skipped for top-level processing
            if (shouldSkipNode(node, primaryCaptureName, fileBytes)) {
                log.trace(
                        "Skipping node {} ({}) in file {} due to language-specific filtering",
                        simpleName,
                        primaryCaptureName,
                        file.getFileName());
                continue;
            }

            CodeUnit cu = createCodeUnit(file, primaryCaptureName, simpleName, packageName, classChain);
            log.trace("createCodeUnit returned: {}", cu);

            if (cu == null) {
                log.trace(
                        "createCodeUnit returned null for node {} ({}) in file {}",
                        simpleName,
                        primaryCaptureName,
                        file.getFileName());
                continue;
            }

            localCodeUnitsBySymbol
                    .computeIfAbsent(cu.identifier(), k -> new ArrayList<>())
                    .add(cu);
            if (!cu.shortName().equals(cu.identifier())) {
                localCodeUnitsBySymbol
                        .computeIfAbsent(cu.shortName(), k -> new ArrayList<>())
                        .add(cu);
            }

            String signature =
                    buildSignatureString(node, simpleName, src, fileBytes, primaryCaptureName, modifierKeywords, file);
            log.trace(
                    "Built signature for '{}': [{}]",
                    simpleName,
                    signature.isBlank()
                            ? "BLANK"
                            : signature.lines().findFirst().orElse("EMPTY"));

            if (signature.isBlank()) {
                // buildSignatureString might legitimately return blank for some nodes that don't form part of a textual
                // skeleton but create a CU.
                // For example, Java lambdas intentionally return an empty signature to keep skeletons clean.
                // We still need the CU for navigation, so proceed without adding a signature.
                log.trace(
                        "buildSignatureString returned empty/null for node {} ({}), simpleName {}. Proceeding without signature.",
                        node.getType(),
                        primaryCaptureName,
                        simpleName);
            }

            // Handle potential duplicates (e.g. JS export and direct lexical declaration).
            // If `cu` is `equals()` to `existingCUforKeyLookup` (e.g., overloads), signatures are accumulated.
            // If they are not `equals()` but have same FQName, this logic might replace based on export preference.
            // can arise from both an exported and non-exported declaration, and we are now
            // collecting multiple signatures. For now, we assume `computeIfAbsent` for signatures handles accumulation,
            // and this "export" preference applies if different `CodeUnit` instances (which are not `equals()`)
            // somehow map to the same `fqName` in `localCuByFqName` before `cu` itself is unified.
            // If overloads result in CodeUnits that are `equals()`, this block is less relevant for them.
            CodeUnit existingCUforKeyLookup = localCuByFqName.get(cu.fqName());
            if (existingCUforKeyLookup != null
                    && !existingCUforKeyLookup.equals(cu)
                    && shouldMergeSignaturesForSameFqn()) {
                List<String> existingSignatures =
                        localSignatures.get(existingCUforKeyLookup); // Existing signatures for the *other* CU instance
                boolean newIsExported = signature.trim().startsWith("export");
                boolean oldIsExported = (existingSignatures != null && !existingSignatures.isEmpty())
                        && existingSignatures.getFirst().trim().startsWith("export"); // Check first existing

                if (newIsExported && !oldIsExported) {
                    log.warn(
                            "Replacing non-exported CU/signature list for {} with new EXPORTED signature.",
                            cu.fqName());
                    // Remove old CU from all maps to ensure clean replacement
                    localSignatures.remove(existingCUforKeyLookup);
                    localSourceRanges.remove(existingCUforKeyLookup);
                    localChildren.remove(existingCUforKeyLookup);
                    // The new signature for `cu` will be added below.
                } else if (!newIsExported && oldIsExported) {
                    log.trace(
                            "Keeping existing EXPORTED CU/signature list for {}. Discarding new non-exported signature for current CU.",
                            cu.fqName());
                    continue; // Skip adding this new signature if an exported one exists for a CU with the same FQName
                } else {
                    // Both exported or both non-exported - treat as duplicate
                    log.warn(
                            "Duplicate CU FQName {} (distinct instances). New signature will be added. Review if this is expected.",
                            cu.fqName());
                }
            }

            if (!signature.isBlank()) { // Only add non-blank signatures
                List<String> sigsForCu = localSignatures.computeIfAbsent(cu, k -> new ArrayList<>());
                if (!sigsForCu.contains(signature)) { // Avoid duplicate signature strings for the same CU
                    sigsForCu.add(signature);
                }
            }
            var originalRange = new Range(
                    node.getStartByte(),
                    node.getEndByte(),
                    node.getStartPoint().getRow(),
                    node.getEndPoint().getRow(),
                    node.getStartByte()); // commentStartByte initially same as startByte

            // Pre-expand range to include contiguous preceding comments and metadata for classes and functions.
            // Always include contiguous leading comments and attribute-like nodes for both classes and functions.
            var finalRange = (cu.isClass() || cu.isFunction()) ? expandRangeWithComments(node, false) : originalRange;

            localSourceRanges.computeIfAbsent(cu, k -> new ArrayList<>()).add(finalRange);
            localCuByFqName.put(cu.fqName(), cu); // Add/overwrite current CU by its FQ name
            localChildren.putIfAbsent(cu, new ArrayList<>()); // Ensure every CU can be a parent

            boolean attachedToParent = false;

            // Prefer attaching lambdas under their nearest function-like (method/ctor) parent when available
            if ("lambda.definition".equals(primaryCaptureName)) {
                var enclosingFnNameOpt = findEnclosingFunctionName(node, src);
                if (enclosingFnNameOpt.isPresent()) {
                    String enclosingFnName = enclosingFnNameOpt.get();
                    String methodFqName = classChain.isEmpty() ? enclosingFnName : (classChain + "." + enclosingFnName);
                    CodeUnit parentFnCu = localCuByFqName.get(methodFqName);
                    if (parentFnCu != null) {
                        List<CodeUnit> kids = localChildren.computeIfAbsent(parentFnCu, k -> new ArrayList<>());
                        addChildCodeUnit(cu, parentFnCu, kids, localChildren, localSignatures, localSourceRanges, file);
                        attachedToParent = true;
                    } else {
                        log.trace(
                                "Nearest function-like parent '{}' for lambda not found in local map. Falling back to class-level parent.",
                                methodFqName);
                    }
                }
            }

            if (!attachedToParent) {
                if (classChain.isEmpty()) {
                    // Top-level CU - use helper to handle duplicates appropriately
                    addTopLevelCodeUnit(cu, localTopLevelCUs, localChildren, localSignatures, localSourceRanges, file);
                } else {
                    // Parent's shortName is the classChain string itself.
                    String parentFqName = buildParentFqName(cu, classChain);
                    CodeUnit parentCu = localCuByFqName.get(parentFqName);
                    if (parentCu != null) {
                        List<CodeUnit> kids = localChildren.computeIfAbsent(parentCu, k -> new ArrayList<>());
                        addChildCodeUnit(cu, parentCu, kids, localChildren, localSignatures, localSourceRanges, file);
                    } else {
                        log.trace(
                                "Could not resolve parent CU for {} using parent FQ name candidate '{}' (derived from classChain '{}'). Treating as top-level for this file.",
                                cu,
                                parentFqName,
                                classChain);
                        // Fallback: add as top-level, but use helper to handle duplicates
                        addTopLevelCodeUnit(
                                cu, localTopLevelCUs, localChildren, localSignatures, localSourceRanges, file);
                    }
                }
            }
            log.trace("Stored/Updated info for CU: {}", cu);
        }

        // After processing all captures, if there were import statements, create a MODULE CodeUnit
        createModulesFromImports(
                file,
                localImportStatements,
                rootNode,
                determinePackageName(file, rootNode, rootNode, src),
                localCuByFqName,
                localTopLevelCUs,
                localSignatures,
                localSourceRanges);

        log.trace(
                "Finished analyzing {}: found {} top-level CUs (includes {} imports), {} total signatures, {} parent entries, {} source range entries.",
                file,
                localTopLevelCUs.size(),
                localImportStatements.size(),
                localSignatures.size(),
                localChildren.size(),
                localSourceRanges.size());

        // Make internal lists unmodifiable before returning in FileAnalysisResult
        Map<CodeUnit, List<CodeUnit>> finalLocalChildren = new HashMap<>();
        localChildren.forEach((p, kids) -> finalLocalChildren.put(p, Collections.unmodifiableList(kids)));

        Map<CodeUnit, List<Range>> finalLocalSourceRanges = new HashMap<>();
        localSourceRanges.forEach((c, ranges) -> finalLocalSourceRanges.put(c, Collections.unmodifiableList(ranges)));

        // Combine local maps into CodeUnitState entries
        Map<CodeUnit, CodeUnitProperties> localStates = new HashMap<>();
        var unionKeys = new HashSet<CodeUnit>();
        unionKeys.addAll(finalLocalChildren.keySet());
        unionKeys.addAll(localSignatures.keySet());
        unionKeys.addAll(finalLocalSourceRanges.keySet());
        for (var cu : unionKeys) {
            var kids = finalLocalChildren.getOrDefault(cu, List.of());
            var sigs = localSignatures.getOrDefault(cu, List.of());
            var rngs = finalLocalSourceRanges.getOrDefault(cu, List.of());
            localStates.put(cu, new CodeUnitProperties(List.copyOf(kids), List.copyOf(sigs), List.copyOf(rngs)));
        }

        // Deduplicate top-level CodeUnits to avoid downstream duplicate-key issues
        var duplicatesByCodeUnit =
                localTopLevelCUs.stream().collect(Collectors.groupingBy(cu -> cu, Collectors.counting()));
        var duplicatedCUs = duplicatesByCodeUnit.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .toList();
        if (!duplicatedCUs.isEmpty()) {
            var diagnostics = duplicatedCUs.stream()
                    .map(e -> String.format(
                            "fqName=%s, kind=%s, count=%d",
                            e.getKey().fqName(), e.getKey().kind(), e.getValue()))
                    .collect(Collectors.joining("; "));
            log.error("Unexpected duplicate top-level CodeUnits in file {}: [{}]", file, diagnostics);
        }
        var finalLocalTopLevelCUs = localTopLevelCUs.stream().distinct().toList();

        long __processEnd = System.nanoTime();
        if (timing != null) {
            timing.processStageNanos().addAndGet(__processEnd - __processStart);
            timing.processStageFirstStartNanos().accumulateAndGet(__processStart, Math::min);
            timing.processStageLastEndNanos().accumulateAndGet(__processEnd, Math::max);
        }
        return new FileAnalysisResult(
                Collections.unmodifiableList(finalLocalTopLevelCUs),
                Collections.unmodifiableMap(localStates),
                localCodeUnitsBySymbol,
                Collections.unmodifiableList(localImportStatements),
                tree);
    }

    /**
     * Useful for languages that separate the concept of instance and singleton classes that have the same names in
     * source code, but are identified by some suffix or other transformation on a lower level, e.g., Kotlin, Scala, Ruby.
     */
    protected String determineClassName(String captureName, String shortName) {
        return shortName;
    }

    /**
     * Useful for languages that have a module system, e.g., dynamic languages, to declare MODULE code units with.
     */
    protected void createModulesFromImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            Map<String, CodeUnit> localCuByFqName,
            List<CodeUnit> localTopLevelCUs,
            Map<CodeUnit, List<String>> localSignatures,
            Map<CodeUnit, List<Range>> localSourceRanges) {}

    /* ---------- Signature Building Logic ---------- */

    /**
     * Builds a signature string for a given definition node. This includes decorators and the main declaration line
     * (e.g., class header or function signature).
     *
     * @param simpleName The simple name of the definition, pre-determined by query captures.
     */
    private String buildSignatureString(
            TSNode definitionNode,
            String simpleName,
            String src,
            byte[] srcBytes,
            String primaryCaptureName,
            List<String> capturedModifierKeywords,
            ProjectFile file) {
        List<String> signatureLines = new ArrayList<>();
        var profile = getLanguageSyntaxProfile();
        SkeletonType skeletonType = getSkeletonTypeForCapture(primaryCaptureName); // Get skeletonType early

        TSNode nodeForContent = definitionNode;
        TSNode nodeForSignature = definitionNode; // Keep original for signature text slicing

        // 1. Handle language-specific structural unwrapping (e.g., export statements)
        // For JAVASCRIPT/TYPESCRIPT: unwrap for processing but keep original for signature
        if (shouldUnwrapExportStatements() && "export_statement".equals(definitionNode.getType())) {
            TSNode declarationInExport = definitionNode.getChildByFieldName("declaration");
            if (declarationInExport != null && !declarationInExport.isNull()) {
                // Check if the inner declaration's type matches what's expected for the skeletonType
                boolean typeMatch = false;
                String innerType = declarationInExport.getType();
                switch (skeletonType) {
                    case CLASS_LIKE -> typeMatch = profile.classLikeNodeTypes().contains(innerType);
                    case FUNCTION_LIKE ->
                        typeMatch = profile.functionLikeNodeTypes().contains(innerType)
                                ||
                                // Special case for TypeScript/JavaScript arrow functions in lexical declarations
                                (shouldUnwrapExportStatements()
                                        && ("lexical_declaration".equals(innerType)
                                                || "variable_declaration".equals(innerType)));
                    case FIELD_LIKE -> typeMatch = profile.fieldLikeNodeTypes().contains(innerType);
                    case ALIAS_LIKE ->
                        typeMatch = (project.getAnalyzerLanguages().contains(Languages.TYPESCRIPT)
                                && "type_alias_declaration".equals(innerType));
                    default -> {}
                }
                if (typeMatch) {
                    nodeForContent = declarationInExport; // Unwrap for processing
                    // Keep nodeForSignature as the original export_statement for text slicing
                } else {
                    log.warn(
                            "Export statement in {} wraps an unexpected declaration type '{}' for skeletonType '{}'. Using export_statement as nodeForContent. DefinitionNode: {}, SimpleName: {}",
                            definitionNode.getStartPoint().getRow() + 1,
                            innerType,
                            skeletonType,
                            definitionNode.getType(),
                            simpleName);
                }
            }
        }

        // Check if we need to find specific variable_declarator (this should run after export unwrapping)
        if (needsVariableDeclaratorUnwrapping(nodeForContent, skeletonType)
                && ("lexical_declaration".equals(nodeForContent.getType())
                        || "variable_declaration".equals(nodeForContent.getType()))) {
            // For lexical_declaration (const/let) or variable_declaration (var), find the specific variable_declarator
            // by name
            log.trace(
                    "Entering variable_declarator lookup for '{}' in nodeForContent '{}'",
                    simpleName,
                    nodeForContent.getType());
            boolean found = false;
            for (int i = 0; i < nodeForContent.getNamedChildCount(); i++) {
                TSNode child = nodeForContent.getNamedChild(i);
                log.trace(
                        "  Child[{}]: type='{}', text='{}'",
                        i,
                        child.getType(),
                        textSlice(child, srcBytes)
                                .lines()
                                .findFirst()
                                .orElse("")
                                .trim());
                if ("variable_declarator".equals(child.getType())) {
                    TSNode nameNode = child.getChildByFieldName(profile.identifierFieldName());
                    if (nameNode != null
                            && !nameNode.isNull()
                            && simpleName.equals(textSlice(nameNode, srcBytes).strip())) {
                        nodeForContent = child; // Use the specific variable_declarator
                        found = true;
                        log.trace("Found specific variable_declarator for '{}' in lexical_declaration", simpleName);
                        break;
                    }
                }
            }
            if (!found) {
                log.warn("Could not find variable_declarator for '{}' in {}", simpleName, nodeForContent.getType());
            } else {
                // Check if this variable_declarator contains an arrow function
                TSNode valueNode = nodeForContent.getChildByFieldName("value");
                if (valueNode != null && !valueNode.isNull() && "arrow_function".equals(valueNode.getType())) {
                    log.trace("Found arrow function in variable_declarator for '{}'", simpleName);
                }
            }
        }

        // 1. Handle decorators: check if language wraps them in a parent node or if they precede the definition
        if (hasWrappingDecoratorNode()) {
            // Language wraps decorators in a parent node (e.g., Python's decorated_definition)
            nodeForContent = extractContentFromDecoratedNode(definitionNode, signatureLines, srcBytes, profile);
        } else {
            // 2. Handle decorators for languages where they precede the definition as siblings
            List<TSNode> decorators =
                    getPrecedingDecorators(nodeForContent); // Decorators precede the actual content node
            for (TSNode decoratorNode : decorators) {
                signatureLines.add(textSlice(decoratorNode, srcBytes).stripLeading());
            }
        }

        // 3. Derive modifier keywords (export, static, async, etc.) using the pre-captured `capturedModifierKeywords`.
        //    These keywords are already sorted by start byte during `analyzeFileDeclarations`.
        String exportPrefix =
                capturedModifierKeywords.isEmpty() ? "" : String.join(" ", capturedModifierKeywords) + " ";

        // 4. Build main signature based on type, using nodeForContent and the derived exportPrefix.
        switch (skeletonType) {
            case CLASS_LIKE: {
                TSNode bodyNode = nodeForContent.getChildByFieldName(profile.bodyFieldName());
                String classSignatureText;
                if (bodyNode != null && !bodyNode.isNull()) {
                    // For export statements, use the original node to include the export keyword
                    if (nodeForSignature != nodeForContent) {
                        classSignatureText = textSlice(
                                        nodeForSignature.getStartByte(), bodyNode.getStartByte(), srcBytes)
                                .stripTrailing();
                    } else {
                        classSignatureText = textSlice(nodeForContent.getStartByte(), bodyNode.getStartByte(), srcBytes)
                                .stripTrailing();
                    }
                } else {
                    // For export statements, use the original node to include the export keyword
                    if (nodeForSignature != nodeForContent) {
                        classSignatureText = textSlice(
                                        nodeForSignature.getStartByte(), nodeForSignature.getEndByte(), srcBytes)
                                .stripTrailing();
                    } else {
                        classSignatureText = textSlice(
                                        nodeForContent.getStartByte(), nodeForContent.getEndByte(), srcBytes)
                                .stripTrailing();
                    }
                    // Attempt to remove trailing tokens like '{' or ';' if no body node found, to get a cleaner
                    // signature part
                    if (classSignatureText.endsWith("{"))
                        classSignatureText = classSignatureText
                                .substring(0, classSignatureText.length() - 1)
                                .stripTrailing();
                    else if (classSignatureText.endsWith(";"))
                        classSignatureText = classSignatureText
                                .substring(0, classSignatureText.length() - 1)
                                .stripTrailing();
                }

                // If exportPrefix is present and classSignatureText also starts with it,
                // remove it from classSignatureText to avoid duplication by renderClassHeader.
                if (!exportPrefix.isBlank() && classSignatureText.startsWith(exportPrefix.strip())) {
                    classSignatureText = classSignatureText
                            .substring(exportPrefix.strip().length())
                            .stripLeading();
                } else if (!exportPrefix.isBlank()
                        && classSignatureText.startsWith(exportPrefix)) { // Check with trailing space too
                    classSignatureText =
                            classSignatureText.substring(exportPrefix.length()).stripLeading();
                }

                String headerLine = assembleClassSignature(nodeForContent, src, exportPrefix, classSignatureText, "");
                if (!headerLine.isBlank()) signatureLines.add(headerLine);
                break;
            }
            case FUNCTION_LIKE: {
                log.trace(
                        "FUNCTION_LIKE: simpleName='{}', nodeForContent.type='{}', nodeForSignature.type='{}'",
                        simpleName,
                        nodeForContent.getType(),
                        nodeForSignature.getType());

                // Add extra comments determined from the function body
                TSNode bodyNodeForComments = nodeForContent.getChildByFieldName(profile.bodyFieldName());
                List<String> extraComments = getExtraFunctionComments(bodyNodeForComments, src, null);
                for (String comment : extraComments) {
                    if (!comment.isBlank()) {
                        signatureLines.add(
                                comment); // Comments are added without indent here; buildSkeletonRecursive adds indent.
                    }
                }
                // Pass determined exportPrefix to buildFunctionSkeleton
                // Always use nodeForContent for structural operations (finding body, etc.)
                // The export prefix is already included via the exportPrefix parameter
                buildFunctionSkeleton(nodeForContent, Optional.of(simpleName), src, "", signatureLines, exportPrefix);
                break;
            }
            case FIELD_LIKE: {
                // Always use nodeForContent which has been set to the specific variable_declarator
                log.trace(
                        "FIELD_LIKE: simpleName='{}', nodeForContent.type='{}', nodeForSignature.type='{}'",
                        simpleName,
                        nodeForContent.getType(),
                        nodeForSignature.getType());
                String fieldSignatureText = textSlice(nodeForContent, srcBytes).strip();

                // Strip export prefix if present to avoid duplication
                if (!exportPrefix.isEmpty() && !exportPrefix.isBlank()) {
                    String strippedExportPrefix = exportPrefix.strip();
                    log.trace(
                            "Checking for prefix duplication: exportPrefix='{}', fieldSignatureText='{}'",
                            strippedExportPrefix,
                            fieldSignatureText);

                    // Check for exact match first
                    if (fieldSignatureText.startsWith(strippedExportPrefix)) {
                        fieldSignatureText = fieldSignatureText
                                .substring(strippedExportPrefix.length())
                                .stripLeading();
                    } else {
                        // For TypeScript/JavaScript, check for partial duplicates like "export const" + "const ..."
                        List<String> exportTokens =
                                Splitter.on(Pattern.compile("\\s+")).splitToList(strippedExportPrefix);
                        List<String> fieldTokens =
                                Splitter.on(Pattern.compile("\\s+")).limit(2).splitToList(fieldSignatureText);

                        if (exportTokens.size() > 1 && !fieldTokens.isEmpty()) {
                            // Check if the last token of export prefix matches the first token of field signature
                            String lastExportToken = exportTokens.get(exportTokens.size() - 1);
                            String firstFieldToken = fieldTokens.get(0);

                            if (lastExportToken.equals(firstFieldToken)) {
                                // Remove the duplicate token from field signature
                                fieldSignatureText = fieldSignatureText
                                        .substring(firstFieldToken.length())
                                        .stripLeading();
                                log.trace("Removed duplicate token '{}' from field signature", firstFieldToken);
                            }
                        }
                    }
                }

                String fieldLine =
                        formatFieldSignature(nodeForContent, src, exportPrefix, fieldSignatureText, "", file);
                if (!fieldLine.isBlank()) signatureLines.add(fieldLine);
                break;
            }
            case ALIAS_LIKE: {
                // nodeForContent should be the type_alias_declaration node itself
                String typeParamsText = "";
                if (!profile.typeParametersFieldName().isEmpty()) {
                    TSNode typeParamsNode = nodeForContent.getChildByFieldName(profile.typeParametersFieldName());
                    if (typeParamsNode != null && !typeParamsNode.isNull()) {
                        typeParamsText = textSlice(typeParamsNode, src); // Raw text including < >
                    }
                }

                TSNode valueNode =
                        nodeForContent.getChildByFieldName("value"); // Standard field name for type alias value
                String valueText = "";
                if (valueNode != null && !valueNode.isNull()) {
                    valueText = textSlice(valueNode, srcBytes).strip();
                } else {
                    log.warn(
                            "Type alias '{}' (node type {}) in {} at line {} is missing its 'value' child. Resulting skeleton may be incomplete. Node text: {}",
                            simpleName,
                            nodeForContent.getType(),
                            project.getRoot().relativize(file.absPath()),
                            nodeForContent.getStartPoint().getRow() + 1,
                            textSlice(nodeForContent, srcBytes));
                    valueText = "any"; // Fallback or indicate error
                }

                String aliasSignature = (exportPrefix.stripTrailing() + " type " + simpleName + typeParamsText + " = "
                                + valueText)
                        .strip();
                if (!aliasSignature.endsWith(";")) {
                    aliasSignature += ";";
                }
                signatureLines.add(aliasSignature);
                break;
            }
            case MODULE_STATEMENT: {
                // For namespace declarations, extract just the namespace declaration line without the body
                String fullText = textSlice(definitionNode, srcBytes);
                List<String> lines = Splitter.on('\n').splitToList(fullText);
                String namespaceLine = lines.getFirst().strip(); // Get first line only

                // Remove trailing '{' if present to get clean namespace signature
                if (namespaceLine.endsWith("{")) {
                    namespaceLine = namespaceLine
                            .substring(0, namespaceLine.length() - 1)
                            .stripTrailing();
                }

                signatureLines.add(exportPrefix + namespaceLine);
                break;
            }
            case UNSUPPORTED:
            default:
                log.debug(
                        "Unsupported capture name '{}' for signature building (type {}). Using raw text slice (with prefix if any from modifiers): '{}'",
                        primaryCaptureName,
                        skeletonType,
                        exportPrefix + textSlice(definitionNode, srcBytes).stripLeading());
                signatureLines.add(
                        exportPrefix + textSlice(definitionNode, srcBytes).stripLeading()); // Add prefix here too
                break;
        }

        String result = String.join("\n", signatureLines).stripTrailing();
        log.trace(
                "buildSignatureString: DefNode={}, SimpleName={}, Capture='{}', nodeForContent={}, Modifiers='{}', Signature (first line): '{}'",
                definitionNode.getType(),
                simpleName,
                primaryCaptureName,
                nodeForContent.getType(),
                exportPrefix,
                (result.isEmpty() ? "EMPTY" : result.lines().findFirst().orElse("EMPTY")));
        return result;
    }

    /**
     * Renders the opening part of a class-like structure (e.g., "public class Foo {").
     */
    protected abstract String renderClassHeader(
            TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent);
    // renderClassFooter is removed, replaced by getLanguageSpecificCloser
    // buildClassMemberSkeletons is removed from this direct path; children are handled by recursive reconstruction.

    /* ---------- Granular Signature Rendering Callbacks (Formatting) ---------- */

    /**
     * Formats the parameter list for a function. Subclasses may override to provide language-specific formatting using
     * the full AST subtree. The default implementation simply returns the raw text of {@code parametersNode}.
     *
     * @param parametersNode The TSNode representing the parameter list.
     * @param src            The source code.
     * @return The formatted parameter list text.
     */
    protected String formatParameterList(TSNode parametersNode, String src) {
        return parametersNode.isNull() ? "" : textSlice(parametersNode, src);
    }

    // Removed deprecated formatParameterList(String)

    /**
     * Formats the return-type portion of a function signature. Subclasses may override to provide language-specific
     * formatting. The default implementation returns the raw text of {@code returnTypeNode} (or an empty string if the
     * node is null).
     *
     * @param returnTypeNode The TSNode representing the return type.
     * @param src            The source code.
     * @return The formatted return type text.
     */
    protected String formatReturnType(@Nullable TSNode returnTypeNode, String src) {
        return returnTypeNode == null || returnTypeNode.isNull() ? "" : textSlice(returnTypeNode, src);
    }

    // Removed deprecated formatReturnType(String)

    protected String formatHeritage(String signatureText) {
        return signatureText;
    }

    /* ---------- Granular Signature Rendering Callbacks (Assembly) ---------- */
    protected String assembleFunctionSignature(
            TSNode funcNode,
            String src,
            String exportPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        // Now directly use the AST-derived paramsText and returnTypeText
        return renderFunctionDeclaration(
                funcNode,
                src,
                exportPrefix,
                asyncPrefix,
                functionName,
                typeParamsText,
                paramsText,
                returnTypeText,
                indent);
    }

    protected String assembleClassSignature(
            TSNode classNode, String src, String exportPrefix, String classSignatureText, String baseIndent) {
        return renderClassHeader(classNode, src, exportPrefix, classSignatureText, baseIndent);
    }

    /**
     * Formats the complete signature for a field-like declaration. Subclasses must implement this to provide
     * language-specific formatting, including any necessary keywords, type annotations, and terminators (e.g.,
     * semicolon).
     *
     * @param fieldNode     The TSNode representing the field declaration.
     * @param src           The source code.
     * @param exportPrefix  The pre-determined export/visibility prefix (e.g., "export const ").
     * @param signatureText The core text of the field signature (e.g., "fieldName: type = value").
     * @param baseIndent    The indentation string for this line.
     * @return The fully formatted field signature line.
     */
    protected String formatFieldSignature(
            TSNode fieldNode,
            String src,
            String exportPrefix,
            String signatureText,
            String baseIndent,
            ProjectFile file) {
        var fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();
        if (requiresSemicolons() && !fullSignature.endsWith(";")) {
            fullSignature += ";";
        }
        return baseIndent + fullSignature;
    }

    /**
     * Whether this language requires semicolons after field declarations. Override in subclasses that don't use
     * semicolons (e.g., Python, Go).
     */
    protected boolean requiresSemicolons() {
        return true;
    }

    /**
     * Determines a visibility or export prefix (e.g., "export ", "public ") for a given node. Subclasses can override
     * this to provide language-specific logic. The default implementation returns an empty string.
     *
     * @param node The node to check for visibility/export modifiers.
     * @param src  The source code.
     * @return The visibility or export prefix string.
     */
    protected String getVisibilityPrefix(TSNode node, String src) {
        return ""; // Default implementation returns an empty string
    }

    /**
     * Builds the function signature lines.
     *
     * @param funcNode        The TSNode for the function definition.
     * @param providedNameOpt Optional pre-determined name (e.g. from a specific capture).
     * @param src             Source code.
     * @param indent          Indentation string.
     * @param lines           List to add signature lines to.
     * @param exportPrefix    Pre-determined export and modifier prefix (e.g., "export async").
     */
    protected void buildFunctionSkeleton(
            TSNode funcNode,
            Optional<String> providedNameOpt,
            String src,
            String indent,
            List<String> lines,
            String exportPrefix) {
        var profile = getLanguageSyntaxProfile();
        String functionName;
        TSNode nameNode = funcNode.getChildByFieldName(profile.identifierFieldName());

        if (nameNode != null && !nameNode.isNull()) {
            functionName = textSlice(nameNode, src);
        } else if (providedNameOpt.isPresent()) {
            functionName = providedNameOpt.get();
        } else {
            // Try to extract name using extractSimpleName as a last resort if the specific field isn't found/helpful
            // This could happen for anonymous functions or if identifierFieldName isn't 'name' and not directly on
            // funcNode.
            Optional<String> extractedNameOpt = extractSimpleName(funcNode, src);
            if (extractedNameOpt.isPresent()) {
                functionName = extractedNameOpt.get();
            } else {
                String funcNodeText = textSlice(funcNode, src);
                log.warn(
                        "Function node type {} has no name field '{}' and no name was provided or extracted. Raw text: {}",
                        funcNode.getType(),
                        profile.identifierFieldName(),
                        funcNodeText.lines().findFirst().orElse(""));
                lines.add(indent + funcNodeText);
                log.warn("-> Falling back to raw text slice for function skeleton due to missing name.");
                return;
            }
        }

        TSNode paramsNode = funcNode.getChildByFieldName(profile.parametersFieldName());
        TSNode returnTypeNode = null;
        if (!profile.returnTypeFieldName().isEmpty()) {
            returnTypeNode = funcNode.getChildByFieldName(profile.returnTypeFieldName());
        }
        TSNode bodyNode = funcNode.getChildByFieldName(profile.bodyFieldName());

        // Parameter node is usually essential for a valid function signature.
        if (paramsNode == null || paramsNode.isNull()) {
            // Allow functions without explicit parameter lists if the language syntax supports it (e.g. some JS/Go
            // forms)
            // but log it if it's unusual for the current node type based on typical expectations.
            // If paramsText ends up empty, renderFunctionDeclaration should handle it gracefully.
            log.trace(
                    "Parameters node (field '{}') not found for function node type '{}', name '{}'. Assuming empty parameter list.",
                    profile.parametersFieldName(),
                    funcNode.getType(),
                    functionName);
        }

        // Body node might be missing for abstract/interface methods.
        if (bodyNode == null || bodyNode.isNull()) {
            log.trace(
                    "Body node (field '{}') not found for function node type '{}', name '{}'. Renderer or placeholder logic must handle this.",
                    profile.bodyFieldName(),
                    funcNode.getType(),
                    functionName);
        }

        String paramsText = formatParameterList(paramsNode, src);
        String returnTypeText = formatReturnType(returnTypeNode, src);

        // Extract type parameters if available
        String typeParamsText = "";
        if (!profile.typeParametersFieldName().isEmpty()) {
            TSNode typeParamsNode = funcNode.getChildByFieldName(profile.typeParametersFieldName());
            if (typeParamsNode != null && !typeParamsNode.isNull()) {
                typeParamsText = textSlice(typeParamsNode, src); // Raw text including < >
            }
        }

        // Combine captured/export prefix with any modifier nodes present on the function node itself.
        var modifierTokens = new LinkedHashSet<String>();
        var trimmedExport = exportPrefix.strip();
        if (!trimmedExport.isEmpty()) {
            for (String tok :
                    Splitter.on(Pattern.compile("\\s+")).omitEmptyStrings().split(trimmedExport)) {
                modifierTokens.add(tok);
            }
        }

        for (int i = 0; i < funcNode.getChildCount(); i++) {
            TSNode child = funcNode.getChild(i);
            if (child == null || child.isNull()) continue;
            String t = child.getType();
            boolean isModifierType = profile.modifierNodeTypes().contains(t)
                    || (!profile.asyncKeywordNodeType().isEmpty() && t.equals(profile.asyncKeywordNodeType()));
            if (isModifierType) {
                String text = textSlice(child, src).strip();
                if (!text.isEmpty()) {
                    for (String tok : Splitter.on(Pattern.compile("\\s+"))
                            .omitEmptyStrings()
                            .split(text)) {
                        modifierTokens.add(tok);
                    }
                }
            }
        }
        String combinedPrefix = modifierTokens.isEmpty() ? "" : String.join(" ", modifierTokens) + " ";

        String functionLine = assembleFunctionSignature(
                funcNode, src, combinedPrefix, "", functionName, typeParamsText, paramsText, returnTypeText, indent);
        if (!functionLine.isBlank()) {
            lines.add(functionLine);
        }
    }

    /**
     * Retrieves extra comment lines to be added to a function's skeleton, typically before the body. Example: mutation
     * tracking comments.
     *
     * @param bodyNode   The TSNode representing the function's body. Can be null.
     * @param src        The source code.
     * @param functionCu The CodeUnit for the function. Can be null if not available.
     * @return A list of comment strings, or an empty list if none.
     */
    protected List<String> getExtraFunctionComments(TSNode bodyNode, String src, @Nullable CodeUnit functionCu) {
        return List.of(); // Default: no extra comments
    }

    protected abstract String bodyPlaceholder();

    /**
     * Renders the complete declaration line for a function, including any prefixes, name, parameters, return type, and
     * language-specific syntax like "def" or "function" keywords, colons, or braces. Implementations are responsible
     * for constructing the entire line, including indentation and any language-specific body placeholder if the
     * function body is not empty or trivial.
     *
     * @param funcNode                The Tree-sitter node representing the function.
     * @param src                     The source code of the file.
     * @param exportAndModifierPrefix The combined export and modifier prefix (e.g., "export async ", "public static ").
     * @param asyncPrefix             This parameter is deprecated and no longer used; async is part of exportAndModifierPrefix.
     *                                Pass empty string.
     * @param functionName            The name of the function.
     * @param paramsText              The text content of the function's parameters.
     * @param returnTypeText          The text content of the function's return type, or empty if none.
     * @param indent                  The base indentation string for this line.
     * @return The fully rendered function declaration line, or null/blank if it should not be added.
     */
    protected abstract String renderFunctionDeclaration(
            TSNode funcNode,
            String src,
            String exportAndModifierPrefix,
            String asyncPrefix, // Kept for signature compatibility, but ignored
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent);

    /**
     * Finds decorator nodes immediately preceding a given node.
     */
    private List<TSNode> getPrecedingDecorators(TSNode decoratedNode) {
        List<TSNode> decorators = new ArrayList<>();
        var decoratorNodeTypes = getLanguageSyntaxProfile().decoratorNodeTypes();
        if (decoratorNodeTypes.isEmpty()) {
            return decorators;
        }
        TSNode current = decoratedNode.getPrevSibling();
        while (current != null && !current.isNull() && decoratorNodeTypes.contains(current.getType())) {
            decorators.add(current);
            current = current.getPrevSibling();
        }
        Collections.reverse(decorators); // Decorators should be in source order
        return decorators;
    }

    /**
     * Extracts a substring from the source code based on node boundaries.
     */
    protected String textSlice(TSNode node, String src) {
        if (node.isNull()) return "";
        // Get the byte array representation of the source
        // This may be cached for better performance in a real implementation
        byte[] bytes;
        try {
            bytes = src.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Fallback in case of encoding error - use safe conversion method
            log.warn("Error getting bytes from source: {}. Falling back to safe substring conversion", e.getMessage());

            return ASTTraversalUtils.safeSubstringFromByteOffsets(src, node.getStartByte(), node.getEndByte());
        }

        // Extract using correct byte indexing
        return textSliceFromBytes(node.getStartByte(), node.getEndByte(), bytes);
    }

    /**
     * Extracts a substring from the source code based on byte offsets.
     */
    protected String textSlice(int startByte, int endByte, String src) {
        // Get the byte array representation of the source
        byte[] bytes;
        try {
            bytes = src.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Fallback in case of encoding error - use safe conversion method
            log.warn("Error getting bytes from source: {}. Falling back to safe substring conversion", e.getMessage());

            return ASTTraversalUtils.safeSubstringFromByteOffsets(src, startByte, endByte);
        }

        return textSliceFromBytes(startByte, endByte, bytes);
    }

    /**
     * OPTIMIZED: Extracts a substring from the source code based on node boundaries, using pre-computed byte array.
     * This avoids the expensive src.getBytes() call that was happening millions of times.
     */
    protected String textSlice(TSNode node, byte[] srcBytes) {
        if (node.isNull()) return "";
        return textSliceFromBytes(node.getStartByte(), node.getEndByte(), srcBytes);
    }

    /**
     * OPTIMIZED: Extracts a substring from the source code based on byte offsets, using pre-computed byte array. This
     * avoids the expensive src.getBytes() call that was happening millions of times.
     */
    protected String textSlice(int startByte, int endByte, byte[] srcBytes) {
        return textSliceFromBytes(startByte, endByte, srcBytes);
    }

    /**
     * Helper method that correctly extracts UTF-8 byte slice into a String
     */
    private String textSliceFromBytes(int startByte, int endByte, byte[] bytes) {
        return textSliceFromBytesWithFile(startByte, endByte, bytes, null);
    }

    /**
     * Helper method that correctly extracts UTF-8 byte slice into a String with optional file context
     */
    private String textSliceFromBytesWithFile(int startByte, int endByte, byte[] bytes, @Nullable ProjectFile file) {
        if (startByte < 0 || endByte > bytes.length || startByte > endByte) {
            if (file != null) {
                log.warn(
                        "Invalid byte range [{}, {}] for byte array of length {} in file {}",
                        startByte,
                        endByte,
                        bytes.length,
                        file.absPath());
            } else {
                log.warn("Invalid byte range [{}, {}] for byte array of length {}", startByte, endByte, bytes.length);
            }
            return "";
        }

        // Handle zero-width nodes (same start and end position) - valid case
        if (startByte == endByte) {
            return "";
        }

        int len = endByte - startByte;
        return new String(bytes, startByte, len, StandardCharsets.UTF_8);
    }

    /* ---------- helpers ---------- */

    private static String formatSecondsMillis(long nanos) {
        long seconds = nanos / 1_000_000_000L;
        long millis = (nanos % 1_000_000_000L) / 1_000_000L;
        return seconds + "s " + millis + "ms";
    }

    /**
     * Compute wall-clock duration from firstStart/lastEnd AtomicLongs, returning 0 if not recorded.
     */
    private static long wallDuration(AtomicLong firstStart, AtomicLong lastEnd) {
        long start = firstStart.get();
        long end = lastEnd.get();
        if (start == Long.MAX_VALUE || end == 0L || end < start) {
            return 0L;
        }
        return end - start;
    }

    /**
     * Fallback to extract a simple name from a declaration node when an explicit `.name` capture isn't found. Tries
     * finding a child node with field name specified in LanguageSyntaxProfile. Needs the source string `src` for
     * substring extraction.
     */
    protected Optional<String> extractSimpleName(TSNode decl, String src) {
        Optional<String> nameOpt = Optional.empty();
        String identifierFieldName = getLanguageSyntaxProfile().identifierFieldName();
        if (identifierFieldName.isEmpty()) {
            log.warn(
                    "Identifier field name is empty in LanguageSyntaxProfile for node type {} at line {}. Cannot extract simple name by field.",
                    decl.getType(),
                    decl.getStartPoint().getRow() + 1);
            return Optional.empty();
        }

        try {
            TSNode nameNode = decl.getChildByFieldName(identifierFieldName);
            if (nameNode != null && !nameNode.isNull()) {
                nameOpt = Optional.of(ASTTraversalUtils.safeSubstringFromByteOffsets(
                        src, nameNode.getStartByte(), nameNode.getEndByte()));
            } else {
                log.warn(
                        "getChildByFieldName('{}') returned null or isNull for node type {} at line {}",
                        identifierFieldName,
                        decl.getType(),
                        decl.getStartPoint().getRow() + 1);
            }
        } catch (Exception e) {
            final String snippet = ASTTraversalUtils.safeSubstringFromByteOffsets(
                    src, decl.getStartByte(), Math.min(decl.getEndByte(), decl.getStartByte() + 20));

            log.warn(
                    "Error extracting simple name using field '{}' from node type {} for node starting with '{}...': {}",
                    identifierFieldName,
                    decl.getType(),
                    snippet.isEmpty() ? "EMPTY" : snippet,
                    e.getMessage());
        }

        if (nameOpt.isEmpty()) {
            log.warn(
                    "extractSimpleName: Failed using getChildByFieldName('{}') for node type {} at line {}",
                    identifierFieldName,
                    decl.getType(),
                    decl.getStartPoint().getRow() + 1);
        }
        log.trace(
                "extractSimpleName: DeclNode={}, IdentifierField='{}', ExtractedName='{}'",
                decl.getType(),
                identifierFieldName,
                nameOpt.orElse("N/A"));
        return nameOpt;
    }

    /**
     * Finds the nearest enclosing function-like ancestor and returns its simple name. Uses the language syntax
     * profile's functionLikeNodeTypes to detect methods/constructors.
     */
    protected Optional<String> findEnclosingFunctionName(TSNode node, String src) {
        var profile = getLanguageSyntaxProfile();
        TSNode current = node.getParent();
        while (current != null && !current.isNull()) {
            if (profile.functionLikeNodeTypes().contains(current.getType())) {
                return extractSimpleName(current, src);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static String loadResource(String path) {
        try (InputStream in = TreeSitterAnalyzer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the immediate children of the given CodeUnit based on TreeSitter parsing results.
     *
     * <p>This implementation uses the pre-built {@code childrenByParent} map that was populated during AST parsing. The
     * parent-child relationships are determined by the TreeSitter grammar and capture queries for the specific
     * language.
     */
    @Override
    public List<CodeUnit> directChildren(CodeUnit cu) {
        return childrenOf(cu);
    }

    /* ---------- file filtering helpers ---------- */

    /**
     * Checks if a file is relevant to this analyzer based on its language extensions.
     *
     * @param file the file to check
     * @return true if the file extension matches this analyzer's language extensions
     */
    private boolean isRelevantFile(ProjectFile file) {
        var languageExtensions = this.language.getExtensions();
        return languageExtensions.contains(file.extension());
    }

    /**
     * Filters a set of files to only include those relevant to this analyzer.
     *
     * @param files the files to filter
     * @return a new set containing only files with extensions matching this analyzer's language
     */
    private Set<ProjectFile> filterRelevantFiles(Set<ProjectFile> files) {
        return files.stream().filter(this::isRelevantFile).collect(Collectors.toSet());
    }

    /* ---------- async stage helpers ---------- */

    private byte[] readFileBytes(ProjectFile pf, @Nullable ConstructionTiming timing) {
        long __readStart = System.nanoTime();
        try {
            if (pf.isBinary()) {
                log.trace("Detected binary file during read, skipping: {}", pf);
                return new byte[0];
            }

            int attempt = 0;
            while (true) {
                attempt++;
                try {
                    IO_FD_SEMAPHORE.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while acquiring IO permit", ie);
                }
                try {
                    return Files.readAllBytes(pf.absPath());
                } catch (IOException ioe) {
                    // Retry if we hit an EMFILE/too-many-open-files situation, otherwise rethrow
                    if (isTooManyOpenFiles(ioe) && attempt < MAX_IO_READ_RETRIES) {
                        long backoffMs = computeBackoffMillis(attempt);
                        log.debug(
                                "Too many open files while reading {} (attempt {}/{}). Backing off {} ms and retrying.",
                                pf,
                                attempt,
                                MAX_IO_READ_RETRIES,
                                backoffMs);
                        sleepQuietly(backoffMs);
                        continue;
                    }
                    throw new UncheckedIOException(ioe);
                } finally {
                    IO_FD_SEMAPHORE.release();
                }
            }
        } finally {
            long __readEnd = System.nanoTime();
            if (timing != null) {
                timing.readStageFirstStartNanos().accumulateAndGet(__readStart, Math::min);
                timing.readStageLastEndNanos().accumulateAndGet(__readEnd, Math::max);
                timing.readStageNanos().addAndGet(__readEnd - __readStart);
            }
        }
    }

    private static boolean isTooManyOpenFiles(IOException e) {
        // Check common paths: FileSystemException.getReason(), messages in cause chain, and EMFILE hints.
        if (e instanceof FileSystemException fse) {
            var reason = fse.getReason();
            if (reason != null) {
                String r = reason.toLowerCase(Locale.ROOT);
                if (r.contains("too many open files") || r.contains("emfile")) return true;
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(Locale.ROOT);
                if (m.contains("too many open files") || m.contains("emfile")) return true;
            }
        }
        return false;
    }

    private static long computeBackoffMillis(int attempt) {
        // Exponential backoff with jitter: 25, 50, 100, 200, 400, 800 ms (capped), plus up to 25ms jitter
        long base = 25L;
        long delay = Math.min(1000L, base << Math.max(0, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0L, base + 1);
        return delay + jitter;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private FileAnalysisResult analyzeFile(ProjectFile pf, byte[] fileBytes, ConstructionTiming timing) {
        log.trace("Processing file: {}", pf);
        var parser = getTSParser();
        return analyzeFileContent(pf, fileBytes, parser, timing);
    }

    private void mergeAnalysisResultIntoMaps(
            ProjectFile pf,
            FileAnalysisResult analysisResult,
            @Nullable ConstructionTiming timing,
            Map<String, List<CodeUnit>> targetSymbolIndex,
            Map<CodeUnit, CodeUnitProperties> targetCodeUnitState,
            Map<ProjectFile, FileProperties> targetFileState) {
        if (analysisResult.topLevelCUs().isEmpty()
                && analysisResult.codeUnitState().isEmpty()) {
            log.trace("analyzeFileDeclarations returned empty result for file: {}", pf);
            return;
        }
        long __mergeStart = System.nanoTime();

        // Merge symbol index
        analysisResult.codeUnitsBySymbol().forEach((symbol, cus) -> {
            targetSymbolIndex.compute(symbol, (s, existing) -> {
                if (existing == null || existing.isEmpty()) {
                    return List.copyOf(cus);
                }
                if (cus.isEmpty()) return existing;
                var merged = new ArrayList<CodeUnit>(existing.size() + cus.size());
                merged.addAll(existing);
                for (CodeUnit cu : cus) {
                    if (!merged.contains(cu)) merged.add(cu);
                }
                return List.copyOf(merged);
            });
        });

        // Merge code unit state
        analysisResult.codeUnitState().forEach((cu, newState) -> {
            targetCodeUnitState.compute(cu, (k, existing) -> {
                if (existing == null) {
                    return new CodeUnitProperties(newState.children(), newState.signatures(), newState.ranges());
                }
                List<CodeUnit> mergedKids = existing.children();
                var newKids = newState.children();
                if (!newKids.isEmpty()) {
                    var tmp = new ArrayList<CodeUnit>(existing.children().size() + newKids.size());
                    tmp.addAll(existing.children());
                    for (var kid : newKids) if (!tmp.contains(kid)) tmp.add(kid);
                    mergedKids = List.copyOf(tmp);
                }
                List<String> mergedSigs = existing.signatures();
                var newSigs = newState.signatures();
                if (!newSigs.isEmpty()) {
                    var tmp = new ArrayList<String>(existing.signatures().size() + newSigs.size());
                    tmp.addAll(existing.signatures());
                    for (var s : newSigs) if (!tmp.contains(s)) tmp.add(s);
                    mergedSigs = List.copyOf(tmp);
                }
                List<Range> mergedRanges = existing.ranges();
                var newRngs = newState.ranges();
                if (!newRngs.isEmpty()) {
                    var tmp = new ArrayList<Range>(existing.ranges().size() + newRngs.size());
                    tmp.addAll(existing.ranges());
                    for (var r : newRngs) if (!tmp.contains(r)) tmp.add(r);
                    mergedRanges = List.copyOf(tmp);
                }
                return new CodeUnitProperties(mergedKids, mergedSigs, mergedRanges);
            });
        });

        // Update file state
        targetFileState.put(
                pf,
                new FileProperties(
                        analysisResult.topLevelCUs(), analysisResult.parsedTree(), analysisResult.importStatements()));

        long __mergeEnd = System.nanoTime();
        if (timing != null) {
            timing.mergeStageNanos().addAndGet(__mergeEnd - __mergeStart);
            timing.mergeStageFirstStartNanos().accumulateAndGet(__mergeStart, Math::min);
            timing.mergeStageLastEndNanos().accumulateAndGet(__mergeEnd, Math::max);
        }
    }

    /* ---------- incremental updates ---------- */

    /**
     * Given a new state, construct a new immutable snapshot of the analyzer using this state.
     *
     * @param state the new state to construct with.
     * @return a new analyzer.
     */
    protected abstract IAnalyzer newSnapshot(AnalyzerState state);

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        if (changedFiles.isEmpty()) return this;

        long overallStartMs = System.currentTimeMillis();
        var relevantFiles = filterRelevantFiles(changedFiles);
        if (relevantFiles.isEmpty()) return this;

        int total = relevantFiles.size();
        var reanalyzedCount = new AtomicInteger(0);
        var deletedCount = new AtomicInteger(0);
        var cleanupNanos = new AtomicLong(0L);
        var reanalyzeNanos = new AtomicLong(0L);

        final var base = this.state;
        var newSymbolIndex = new ConcurrentHashMap<>(base.symbolIndex());
        var newCodeUnitState = new ConcurrentHashMap<>(base.codeUnitState());
        var newFileState = new ConcurrentHashMap<>(base.fileState());

        int parallelism = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), total));
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try (var executor = ExecutorServiceUtil.newFixedThreadExecutor(parallelism, "ts-update-")) {
            for (var file : relevantFiles) {
                futures.add(CompletableFuture.runAsync(
                        () -> {
                            long cleanupStart = System.nanoTime();

                            // Remove old entries for this file
                            Predicate<CodeUnit> fromFile = cu -> cu.source().equals(file);
                            newFileState.remove(file);
                            // Purge CodeUnitState entries for this file and prune children lists
                            newCodeUnitState.keySet().removeIf(fromFile);
                            newCodeUnitState.replaceAll((parent, state) -> {
                                var filteredKids = state.children().stream()
                                        .filter(fromFile.negate())
                                        .toList();
                                return filteredKids.equals(state.children())
                                        ? state
                                        : new CodeUnitProperties(
                                                List.copyOf(filteredKids), state.signatures(), state.ranges());
                            });
                            // Purge from symbol index
                            var symbolsToRemove = new ArrayList<String>();
                            newSymbolIndex.replaceAll((symbol, cus) -> {
                                var remaining =
                                        cus.stream().filter(fromFile.negate()).toList();
                                if (remaining.isEmpty()) symbolsToRemove.add(symbol);
                                return remaining;
                            });
                            for (var s : symbolsToRemove) newSymbolIndex.remove(s);

                            cleanupNanos.addAndGet(System.nanoTime() - cleanupStart);

                            // Re-analyze if file still exists
                            if (Files.exists(file.absPath())) {
                                long reanStart = System.nanoTime();
                                try {
                                    var parser = getTSParser();
                                    byte[] bytes = readFileBytes(file, null);
                                    var analysisResult = analyzeFileContent(file, bytes, parser, null);
                                    mergeAnalysisResultIntoMaps(
                                            file, analysisResult, null, newSymbolIndex, newCodeUnitState, newFileState);
                                    reanalyzedCount.incrementAndGet();
                                } catch (UncheckedIOException e) {
                                    log.warn("IO error re-analysing {}: {}", file, e.getMessage());
                                } catch (RuntimeException e) {
                                    log.error("Runtime error re-analysing {}: {}", file, e.getMessage(), e);
                                } finally {
                                    reanalyzeNanos.addAndGet(System.nanoTime() - reanStart);
                                }
                            } else {
                                deletedCount.incrementAndGet();
                                log.debug("File {} deleted; state cleaned.", file);
                            }
                        },
                        executor));
            }
            if (!futures.isEmpty())
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .join();
        }

        // Build new immutable snapshot and return a new analyzer instance
        var snapshotInstant = Instant.now();
        long snapshotNanos = snapshotInstant.getEpochSecond() * 1_000_000_000L + snapshotInstant.getNano();

        var nextKeySet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        nextKeySet.addAll(newSymbolIndex.keySet());
        var nextSymbolKeyIndex = new SymbolKeyIndex(Collections.unmodifiableNavigableSet(nextKeySet));

        var nextState = new AnalyzerState(
                HashTreePMap.from(newSymbolIndex),
                HashTreePMap.from(newCodeUnitState),
                HashTreePMap.from(newFileState),
                nextSymbolKeyIndex,
                snapshotNanos);

        long totalMs = System.currentTimeMillis() - overallStartMs;
        long cleanupMs = TimeUnit.NANOSECONDS.toMillis(cleanupNanos.get());
        long reanalyzeMs = TimeUnit.NANOSECONDS.toMillis(reanalyzeNanos.get());
        log.debug(
                "[{}] TreeSitter incremental update: relevantFiles={}, reanalyzed={}, deleted={}, cleanup={} ms, reanalyze={} ms, total={} ms",
                language.name(),
                total,
                reanalyzedCount.get(),
                deletedCount.get(),
                cleanupMs,
                reanalyzeMs,
                totalMs);

        return newSnapshot(nextState);
    }

    /**
     * Full-project incremental update: detect created/modified/deleted files using filesystem mtimes (nanos precision,
     * with a 300ms over-approximation buffer), then delegate to {@link #update(Set)}.
     */
    @Override
    public IAnalyzer update() {
        long detectStartMs = System.currentTimeMillis();

        // files currently on disk that this analyser is interested in
        Set<ProjectFile> currentFiles = project.getAllFiles().stream()
                .filter(pf -> {
                    Path abs = pf.absPath().toAbsolutePath().normalize();
                    if (normalizedExcludedPaths.stream().anyMatch(abs::startsWith)) {
                        return false;
                    }
                    String p = abs.toString();
                    boolean matches = language.getExtensions().stream().anyMatch(p::endsWith);
                    return matches;
                })
                .collect(Collectors.toSet());

        // Snapshot known files (those we've analyzed)
        var current = this.state;
        Set<ProjectFile> knownFiles = new HashSet<>(current.fileState().keySet());

        Set<ProjectFile> changed = new HashSet<>();
        long last = lastUpdateEpochNanos.get();
        long threshold = (last > MTIME_EPSILON_NANOS) ? (last - MTIME_EPSILON_NANOS) : 0L;

        // deleted or no-longer-relevant files
        for (ProjectFile known : knownFiles) {
            if (!currentFiles.contains(known) || !Files.exists(known.absPath())) {
                changed.add(known);
            }
        }

        // new or modified files (parallelized)
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
        var concurrentChanged = ConcurrentHashMap.<ProjectFile>newKeySet();

        try (var detectExecutor = ExecutorServiceUtil.newFixedThreadExecutor(parallelism, "ts-detect-")) {
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (ProjectFile pf : currentFiles) {
                if (!knownFiles.contains(pf)) {
                    // New file we have not seen before
                    concurrentChanged.add(pf);
                    continue;
                }

                futures.add(CompletableFuture.runAsync(
                        () -> {
                            try {
                                long mtimeNanos =
                                        Files.getLastModifiedTime(pf.absPath()).to(TimeUnit.NANOSECONDS);
                                if (mtimeNanos > threshold) {
                                    concurrentChanged.add(pf);
                                }
                            } catch (IOException e) {
                                log.warn("Could not stat {}: {}", pf, e.getMessage());
                                concurrentChanged.add(pf); // treat as changed; will retry next time
                            }
                        },
                        detectExecutor));
            }

            if (!futures.isEmpty()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .join();
            }
        }

        changed.addAll(concurrentChanged);

        long detectMs = System.currentTimeMillis() - detectStartMs;

        // reuse the existing incremental logic
        long updateStartMs = System.currentTimeMillis();
        var analyzer = update(changed);
        long updateMs = System.currentTimeMillis() - updateStartMs;

        long totalMs = detectMs + updateMs;
        log.debug(
                "[{}] TreeSitter full incremental scan: changed={} files, detect={} ms, update={} ms, total={} ms",
                language.name(),
                changed.size(),
                detectMs,
                updateMs,
                totalMs);

        return analyzer;
    }

    /* ---------- comment detection for source expansion ---------- */

    /**
     * Checks if a Tree-Sitter node represents a comment. Supports common comment node types across languages.
     */
    protected boolean isCommentNode(TSNode node) {
        if (node.isNull()) {
            return false;
        }
        String nodeType = node.getType();
        return nodeType.equals("comment")
                || nodeType.equals("line_comment")
                || nodeType.equals("block_comment")
                || nodeType.equals("doc_comment")
                || nodeType.equals("documentation_comment");
    }

    /**
     * Returns true if the node is considered leading metadata (comments or attribute-like nodes).
     */
    protected boolean isLeadingMetadataNode(TSNode node) {
        if (isCommentNode(node)) {
            return true;
        }
        String type = node.getType();
        return getLeadingMetadataNodeTypes().contains(type);
    }

    /**
     * Node types considered attribute-like metadata that should be included with leading comments. Default is empty;
     * language-specific analyzers should override to add their own metadata node types.
     */
    protected Set<String> getLeadingMetadataNodeTypes() {
        return Set.of();
    }

    /**
     * Finds all comment nodes that directly precede the given declaration node. Returns comments in source order
     * (earliest first).
     */
    protected List<TSNode> findPrecedingComments(TSNode declarationNode) {
        List<TSNode> comments = new ArrayList<>();
        TSNode current = declarationNode.getPrevSibling();

        while (current != null && !current.isNull()) {
            if (isCommentNode(current)) {
                comments.add(current);
            } else if (!isWhitespaceOnlyNode(current)) {
                // Stop at first non-comment, non-whitespace node
                break;
            }
            current = current.getPrevSibling();
        }

        // Reverse to get source order (earliest first)
        Collections.reverse(comments);
        return comments;
    }

    /**
     * Checks if a node contains only whitespace (spaces, tabs, newlines).
     */
    protected boolean isWhitespaceOnlyNode(TSNode node) {
        if (node.isNull()) {
            return false;
        }
        // Common whitespace node types in Tree-Sitter grammars
        String nodeType = node.getType();
        return nodeType.equals("whitespace")
                || nodeType.equals("newline")
                || nodeType.equals("\n")
                || nodeType.equals(" ");
    }

    /**
     * Expands a source range to include contiguous leading metadata (comments and attribute-like nodes) immediately
     * preceding the declaration node. Operates directly on the provided declaration node.
     */
    protected Range expandRangeWithComments(TSNode declarationNode, boolean ignoredIncludeOnlyDocLike) {
        var originalRange = new Range(
                declarationNode.getStartByte(),
                declarationNode.getEndByte(),
                declarationNode.getStartPoint().getRow(),
                declarationNode.getEndPoint().getRow(),
                declarationNode.getStartByte()); // initial commentStartByte equals start

        try {
            // Walk preceding siblings and collect contiguous leading metadata nodes (comments, attributes)
            List<TSNode> leading = new ArrayList<>();
            TSNode current = declarationNode.getPrevSibling();
            while (current != null && !current.isNull()) {
                if (isLeadingMetadataNode(current)) {
                    leading.add(current);
                    current = current.getPrevSibling();
                    continue;
                }
                break;
            }

            // No leading metadata; keep the original range
            if (leading.isEmpty()) {
                return originalRange;
            }

            // Reverse to get earliest-first
            Collections.reverse(leading);
            int newStartByte = leading.getFirst().getStartByte();

            Range expandedRange = new Range(
                    originalRange.startByte(),
                    originalRange.endByte(),
                    originalRange.startLine(),
                    originalRange.endLine(),
                    newStartByte);

            log.trace(
                    "Expanded range for node. Body range [{}, {}], comment range starts at {} (added {} preceding metadata nodes)",
                    originalRange.startByte(),
                    originalRange.endByte(),
                    expandedRange.commentStartByte(),
                    leading.size());

            return expandedRange;

        } catch (Exception e) {
            log.warn("Error during comment/metadata expansion for node: {}", e.getMessage());
            return originalRange;
        }
    }

    /**
     * @param fqName the full name of the code unit to run this check for.
     * @return true if the fqName seems like it belongs to a lambda function or anonymous class, false if otherwise.
     */
    protected boolean isAnonymousStructure(String fqName) {
        return false;
    }

    // Helper container to track depth alongside the matching CodeUnit
    private static final class CUWithDepth {
        final CodeUnit cu;
        final int depth;

        CUWithDepth(CodeUnit cu, int depth) {
            this.cu = cu;
            this.depth = depth;
        }
    }

    @Override
    public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range) {
        if (range.isEmpty()) return Optional.empty();

        CodeUnit best = null;
        int bestDepth = -1;

        // Start from top-level declarations to ensure deterministic traversal order
        for (var top : getTopLevelDeclarations(file)) {
            var res = findDeepestEnclosing(top, range, 0);
            if (res != null && res.depth > bestDepth) {
                best = res.cu;
                bestDepth = res.depth;
            }
        }

        return Optional.ofNullable(best);
    }

    private @Nullable CUWithDepth findDeepestEnclosing(CodeUnit current, Range range, int depth) {
        // If the range is not contained within this CU, skip
        boolean containsCurrent = rangesOf(current).stream().anyMatch(range::isContainedWithin);
        if (!containsCurrent) {
            return null;
        }

        CUWithDepth best = new CUWithDepth(current, depth);
        for (var child : childrenOf(current)) {
            var candidate = findDeepestEnclosing(child, range, depth + 1);
            if (candidate != null && candidate.depth > best.depth) {
                best = candidate;
            }
        }
        return best;
    }
}
