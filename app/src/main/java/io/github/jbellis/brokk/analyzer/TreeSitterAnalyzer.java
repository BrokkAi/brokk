package io.github.jbellis.brokk.analyzer;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.IProject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque; // Added import
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
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
public abstract class TreeSitterAnalyzer
        implements IAnalyzer, SkeletonProvider, SourceCodeProvider, IncrementalUpdateProvider, TypeAliasProvider {
    protected static final Logger log = LoggerFactory.getLogger(TreeSitterAnalyzer.class);
    // Native library loading is assumed automatic by the io.github.bonede.tree_sitter library.

    // Common separators across languages to denote hierarchy or member access.
    // Includes: '.' (Java/others), '$' (Java nested classes), '::' (C++/C#/Ruby), '->' (PHP), etc.
    private static final Set<String> COMMON_HIERARCHY_SEPARATORS = Set.of(".", "$", "::", "->");

    private static boolean containsAnyHierarchySeparator(String s) {
        for (String sep : COMMON_HIERARCHY_SEPARATORS) {
            if (s.contains(sep)) {
                return true;
            }
        }
        return false;
    }

    /* ---------- instance state ---------- */
    private final ThreadLocal<TSLanguage> threadLocalLanguage = ThreadLocal.withInitial(this::createTSLanguage);
    private final ThreadLocal<TSQuery> query;
    private final Map<ProjectFile, List<CodeUnit>> topLevelDeclarations =
            new ConcurrentHashMap<>(); // package-private for testing
    private final Map<CodeUnit, List<CodeUnit>> childrenByParent =
            new ConcurrentHashMap<>(); // package-private for testing
    private final Map<CodeUnit, List<String>> signatures = new ConcurrentHashMap<>(); // package-private for testing
    private final Map<CodeUnit, List<Range>> sourceRanges = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<String, List<CodeUnit>> symbolIndex =
            new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);
    // SHA-1 hash of each analysed file, used to detect modifications
    private final Map<ProjectFile, String> fileHashes = new ConcurrentHashMap<>();
    private final Map<ProjectFile, TSTree> parsedTreeCache =
            new ConcurrentHashMap<>(); // Cache parsed trees to avoid redundant parsing
    // Ensures reads see a consistent view while updates mutate internal maps atomically
    private final ReentrantReadWriteLock stateRwLock = new ReentrantReadWriteLock();

    private final IProject project;
    private final Language language;
    protected final Set<Path> normalizedExcludedPaths;

    /** Frees memory from the parsed AST cache. */
    public void clearCaches() {
        withReadLock(parsedTreeCache::clear);
    }

    /** The number of cached AST entries. */
    public int cacheSize() {
        return withReadLock(parsedTreeCache::size);
    }

    /* ------------ read-lock helpers ------------ */

    /** Execute {@code supplier} under the read lock and return its result. */
    private <T> T withReadLock(Supplier<T> supplier) {
        var rl = stateRwLock.readLock();
        rl.lock();
        try {
            return supplier.get();
        } finally {
            rl.unlock();
        }
    }

    /** Execute {@code runnable} under the read lock. */
    private void withReadLock(Runnable runnable) {
        var rl = stateRwLock.readLock();
        rl.lock();
        try {
            runnable.run();
        } finally {
            rl.unlock();
        }
    }

    /**
     * A thread-safe way to interact with the "signatures" field.
     *
     * @param function the callback.
     */
    protected <R> R withSignatures(Function<Map<CodeUnit, List<String>>, R> function) {
        return withReadLock(() -> function.apply(signatures));
    }

    /**
     * A thread-safe way to interact with the "childrenByParent" field.
     *
     * @param function the callback.
     */
    protected <R> R withChildrenByParent(Function<Map<CodeUnit, List<CodeUnit>>, R> function) {
        return withReadLock(() -> function.apply(childrenByParent));
    }

    /**
     * A thread-safe way to interact with the "topLevelDeclarations" field.
     *
     * @param function the callback.
     */
    public <R> R withTopLevelDeclarations(Function<Map<ProjectFile, List<CodeUnit>>, R> function) {
        return withReadLock(() -> function.apply(topLevelDeclarations));
    }

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
            String identifierFieldName,
            String bodyFieldName,
            String parametersFieldName,
            String returnTypeFieldName,
            String typeParametersFieldName, // For generics on type aliases, classes, functions etc.
            Map<String, SkeletonType> captureConfiguration,
            String asyncKeywordNodeType,
            Set<String> modifierNodeTypes) {
        public LanguageSyntaxProfile {
            Objects.requireNonNull(classLikeNodeTypes);
            Objects.requireNonNull(functionLikeNodeTypes);
            Objects.requireNonNull(fieldLikeNodeTypes);
            Objects.requireNonNull(decoratorNodeTypes);
            Objects.requireNonNull(identifierFieldName);
            Objects.requireNonNull(bodyFieldName);
            Objects.requireNonNull(parametersFieldName);
            Objects.requireNonNull(returnTypeFieldName); // Can be empty string if not applicable
            Objects.requireNonNull(typeParametersFieldName); // Can be empty string if not applicable
            Objects.requireNonNull(captureConfiguration);
            Objects.requireNonNull(asyncKeywordNodeType); // Can be empty string if not applicable
            Objects.requireNonNull(modifierNodeTypes);
        }
    }

    public record Range(int startByte, int endByte, int startLine, int endLine) {}

    private record FileAnalysisResult(
            List<CodeUnit> topLevelCUs,
            Map<CodeUnit, List<CodeUnit>> children,
            Map<CodeUnit, List<String>> signatures,
            Map<CodeUnit, List<Range>> sourceRanges,
            Map<String, List<CodeUnit>> codeUnitsBySymbol,
            List<String> importStatements // Added for module-level imports
            ) {}

    /* ---------- constructor ---------- */
    protected TreeSitterAnalyzer(IProject project, Language language, Set<String> excludedFiles) {
        this.project = project;
        this.language = language;
        // tsLanguage field removed, getTSLanguage().get() will provide it via ThreadLocal

        this.normalizedExcludedPaths = excludedFiles.stream()
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

        project.getAllFiles().stream()
                .filter(pf -> {
                    // Normalize the file path once
                    var filePath = pf.absPath().toAbsolutePath().normalize();

                    // Check if file is under any excluded path
                    var excludedBy = normalizedExcludedPaths.stream()
                            .filter(filePath::startsWith)
                            .findFirst();

                    if (excludedBy.isPresent()) {
                        log.trace("Skipping excluded file due to rule {}: {}", excludedBy.get(), pf);
                        return false;
                    }

                    // Check extension
                    var pathStr = filePath.toString();
                    return validExtensions.stream().anyMatch(pathStr::endsWith);
                })
                .parallel()
                .forEach(pf -> {
                    totalFilesAttempted.incrementAndGet();
                    log.trace("Processing file: {}", pf);
                    // TSParser is not threadsafe, so we create a parser per thread
                    var localParser = new TSParser();
                    try {
                        if (!localParser.setLanguage(getTSLanguage())) {
                            log.error(
                                    "Failed to set language on thread-local TSParser for language {} in file {}",
                                    getTSLanguage().getClass().getSimpleName(),
                                    pf);
                            return; // Skip this file if parser setup fails
                        }
                        var analysisResult = analyzeFileDeclarations(pf, localParser);
                        if (!analysisResult.topLevelCUs().isEmpty()
                                || !analysisResult.signatures().isEmpty()
                                || !analysisResult.sourceRanges().isEmpty()) {
                            // Use the centralized ingestion logic so that the symbol index and codeUnitsBySymbol
                            // are populated consistently for initial project analysis as well as updates.
                            ingestAnalysisResult(pf, analysisResult);

                            log.trace(
                                    "Processed file {} via ingestAnalysisResult: {} top-level CUs, {} signatures, {} parent-child relationships, {} source range entries.",
                                    pf,
                                    analysisResult.topLevelCUs().size(),
                                    analysisResult.signatures().size(),
                                    analysisResult.children().size(),
                                    analysisResult.sourceRanges().size());
                        } else {
                            log.trace("analyzeFileDeclarations returned empty result for file: {}", pf);
                        }
                        successfullyProcessed.incrementAndGet();
                    } catch (OutOfMemoryError e) {
                        // Critical JVM issues that should terminate processing
                        throw e;
                    } catch (IOException e) {
                        failedFiles.incrementAndGet();
                        log.warn("IO error analyzing {}: {}", pf, e.getMessage());
                    } catch (Exception e) {
                        // Handle all other exceptions (including RuntimeException) by logging and continuing
                        failedFiles.incrementAndGet();
                        if (e instanceof RuntimeException) {
                            log.error("Runtime error analyzing {}: {}", pf, e.getMessage(), e);
                        } else {
                            log.warn("Error analyzing {}: {}", pf, e.getMessage(), e);
                        }
                    }
                });

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

        log.debug(
                "TreeSitter analysis complete - topLevelDeclarations: {}, childrenByParent: {}, signatures: {}",
                topLevelDeclarations.size(),
                childrenByParent.size(),
                signatures.size());
    }

    protected TreeSitterAnalyzer(IProject project, Language language) {
        this(project, language, Collections.emptySet());
    }

    /* ---------- Helper methods for accessing CodeUnits ---------- */

    /** All CodeUnits we know about (top-level + children). */
    private Stream<CodeUnit> allCodeUnits() {
        // Stream top-level declarations
        Stream<CodeUnit> topLevelStream = topLevelDeclarations.values().stream().flatMap(Collection::stream);

        // Stream parents from childrenByParent (they might not be in topLevelDeclarations if they are nested)
        Stream<CodeUnit> parentStream = childrenByParent.keySet().stream();

        // Stream children from childrenByParent
        Stream<CodeUnit> childrenStream = childrenByParent.values().stream().flatMap(Collection::stream);

        return Stream.of(topLevelStream, parentStream, childrenStream).flatMap(s -> s);
    }

    /** De-duplicate and materialise into a List once. */
    private List<CodeUnit> uniqueCodeUnitList() {
        return withReadLock(() -> allCodeUnits().distinct().toList());
    }

    /* ---------- IAnalyzer ---------- */
    @Override
    public boolean isEmpty() {
        return withReadLock(() -> topLevelDeclarations.isEmpty()
                && signatures.isEmpty()
                && childrenByParent.isEmpty()
                && sourceRanges.isEmpty());
    }

    @Override
    public Optional<String> getSkeletonHeader(String fqName) {
        return getSkeletonImpl(fqName, true);
    }

    @Override
    public List<CodeUnit> getMembersInClass(String fqClass) {
        Optional<CodeUnit> parent = uniqueCodeUnitList().stream()
                .filter(cu -> cu.fqName().equals(fqClass) && cu.isClass())
                .findFirst();
        return parent.map(p -> List.copyOf(childrenByParent.getOrDefault(p, List.of())))
                .orElse(List.of());
    }

    @Override
    public Optional<ProjectFile> getFileFor(String fqName) {
        return uniqueCodeUnitList().stream()
                .filter(cu -> cu.fqName().equals(fqName))
                .map(CodeUnit::source)
                .findFirst();
    }

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        return uniqueCodeUnitList().stream()
                .filter(cu -> cu.fqName().equals(fqName))
                .findFirst();
    }

    @Override
    public boolean isDefinitionAvailable(String fqName) {
        return withReadLock(() -> allCodeUnits().anyMatch(cu -> cu.fqName().equals(fqName)));
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        Set<CodeUnit> allClasses = new HashSet<>();
        topLevelDeclarations.values().forEach(allClasses::addAll);
        childrenByParent.values().forEach(allClasses::addAll); // Children lists
        allClasses.addAll(childrenByParent.keySet()); // Parent CUs themselves
        return allClasses.stream().filter(CodeUnit::isClass).distinct().toList();
    }

    @Override
    public List<CodeUnit> searchDefinitionsImpl(
            String originalPattern, @Nullable String fallbackPattern, Pattern compiledPattern) {
        // an explicit search for everything should return everything, not just classes
        if (originalPattern.equals(".*")) {
            return uniqueCodeUnitList();
        }

        if (fallbackPattern != null) {
            // Fallback to simple case-insensitive substring matching
            return uniqueCodeUnitList().stream()
                    .filter(cu -> cu.fqName().toLowerCase(Locale.ROOT).contains(fallbackPattern))
                    .toList();
        } else {
            // Primary search using compiled regex pattern
            return uniqueCodeUnitList().stream()
                    .filter(cu -> compiledPattern.matcher(cu.fqName()).find())
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
        // Normalize hierarchical separators so '.' and '$' are treated equivalently for matching.
        final String normalizedQuery = lowerCaseQuery.replace('$', '.');

        // Determine if this is a CamelCase-style query (all uppercase letters, length > 1)
        boolean isAllUpper = query.length() > 1 && query.chars().allMatch(Character::isUpperCase);
        Pattern camelCasePattern = null;
        if (isAllUpper) {
            // Case-insensitive camel-hump matching so symbols that may be stored in different case forms still match.
            camelCasePattern = Pattern.compile(
                    query.chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining("[a-z0-9_]*")),
                    Pattern.CASE_INSENSITIVE);
        }

        // If the query looks like a simple non-hierarchical prefix (no dots/dollars, not a camel all-upper pattern),
        // leverage the NavigableSet view from the symbolIndex for an efficient prefix scan.
        boolean usePrefixOptimization =
                !containsAnyHierarchySeparator(lowerCaseQuery) && !isAllUpper && query.length() >= 2;

        NavigableSet<String> keys = symbolIndex.navigableKeySet();

        if (usePrefixOptimization) {
            try {
                for (String symbol : keys.tailSet(query)) {
                    String symbolLower = symbol.toLowerCase(Locale.ROOT);
                    if (!symbolLower.startsWith(lowerCaseQuery)) break;
                    results.addAll(symbolIndex.getOrDefault(symbol, List.of()));
                }
            } catch (IllegalArgumentException e) {
                // Defensive fallback; fall through to the generic scan below if tailSet fails for some reason.
            }
        }

        // Generic over-approximate scan: accept any symbol that contains the query (case-insensitive), or matches
        // the camel-case heuristic. Skip symbols already handled by the prefix optimization to avoid redundant work.
        for (String symbol : keys) {
            String symbolLower = symbol.toLowerCase(Locale.ROOT);
            String normalizedSymbol = symbolLower.replace('$', '.');

            if (usePrefixOptimization && symbolLower.startsWith(lowerCaseQuery)) {
                // already collected by prefix scan
                continue;
            }

            boolean matches = false;

            if (symbolLower.contains(lowerCaseQuery) || normalizedSymbol.contains(normalizedQuery)) {
                matches = true;
            } else if (isAllUpper
                    && camelCasePattern != null
                    && camelCasePattern.matcher(symbol).find()) {
                matches = true;
            }

            if (matches) {
                results.addAll(symbolIndex.getOrDefault(symbol, List.of()));
            }
        }

        // ALSO: make sure to match against CodeUnit fully-qualified names (FQNs).
        // Some queries are hierarchical and mix '.'/'$' and might not be present as keys in the symbol index.
        // Normalize FQNs by mapping '$' -> '.' and do a case-insensitive contains check.
        for (CodeUnit cu : uniqueCodeUnitList()) {
            String fq = cu.fqName().toLowerCase(Locale.ROOT).replace('$', '.');
            if (fq.contains(normalizedQuery)) {
                results.add(cu);
            }
        }

        // Fallback for very short queries (single letter): ensure we include declarations whose FQNs contain the query.
        if (query.length() == 1) {
            String lc = lowerCaseQuery;
            uniqueCodeUnitList().stream()
                    .filter(cu -> cu.fqName().toLowerCase(Locale.ROOT).contains(lc))
                    .forEach(results::add);
        }

        return new ArrayList<>(results);
    }

    /**
     * Returns the top-level declarations organized by file. This method is primarily for testing to examine the raw
     * declarations before they are filtered by getAllDeclarations().
     *
     * @return Map from ProjectFile to List of CodeUnits declared at the top level in that file
     */
    public Map<ProjectFile, List<CodeUnit>> getTopLevelDeclarations() {
        return Map.copyOf(topLevelDeclarations);
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        List<CodeUnit> topCUs = topLevelDeclarations.getOrDefault(file, List.of());
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
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        List<CodeUnit> topCUs = topLevelDeclarations.getOrDefault(file, List.of());
        if (topCUs.isEmpty()) return Set.of();

        Set<CodeUnit> allDeclarationsInFile = new HashSet<>();
        Queue<CodeUnit> toProcess = new ArrayDeque<>(topCUs); // Changed to ArrayDeque
        Set<CodeUnit> visited = new HashSet<>(topCUs); // Track visited to avoid cycles and redundant processing

        while (!toProcess.isEmpty()) {
            CodeUnit current = toProcess.poll();
            allDeclarationsInFile.add(current); // Add all encountered CodeUnits

            childrenByParent.getOrDefault(current, List.of()).forEach(child -> {
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
        List<String> sigList = signatures.get(cu);
        if (sigList == null || sigList.isEmpty()) {
            // It's possible for some CUs (e.g., a namespace CU acting only as a parent) to not have direct textual
            // signatures.
            // This can be legitimate if they are primarily structural and their children form the content.
            log.trace(
                    "No direct signatures found for CU: {}. It might be a structural-only CU. Skipping direct rendering in skeleton reconstruction.",
                    cu);
            return;
        }

        for (String individualFullSignature : sigList) {
            if (individualFullSignature.isBlank()) {
                log.warn("Encountered null or blank signature in list for CU: {}. Skipping this signature.", cu);
                continue;
            }
            // Apply indent to each line of the current signature
            String[] signatureLines = individualFullSignature.split("\n", -1); // Use -1 limit
            for (String line : signatureLines) {
                sb.append(indent).append(line).append('\n');
            }
        }

        final List<CodeUnit> kids = childrenByParent.getOrDefault(cu, List.of()).stream()
                .filter(child -> !headerOnly || child.isField())
                .toList();
        // Only add children and closer if the CU can have them (e.g. class, or function that can nest)
        // For simplicity now, always check for children. Specific languages might refine this.
        if (!kids.isEmpty()
                || (cu.isClass() && !getLanguageSpecificCloser(cu).isEmpty())) { // also add closer for empty classes
            String childIndent = indent + getLanguageSpecificIndent();
            for (CodeUnit kid : kids) {
                reconstructSkeletonRecursive(kid, childIndent, headerOnly, sb);
            }
            if (headerOnly && cu.isClass()) {
                final var nonFieldKidsSize =
                        childrenByParent.getOrDefault(cu, List.of()).size() - kids.size();
                if (nonFieldKidsSize > 0) {
                    sb.append(childIndent).append("[...]").append("\n");
                }
            }
            String closer = getLanguageSpecificCloser(cu);
            if (!closer.isEmpty()) {
                sb.append(indent).append(closer).append('\n');
            }
        }
    }

    @Override
    public Optional<String> getSkeleton(String fqName) {
        return getSkeletonImpl(fqName, false);
    }

    public Optional<String> getSkeletonImpl(String fqName, Boolean headerOnly) {
        return withReadLock(() -> {
            Optional<CodeUnit> cuOpt = signatures.keySet().stream()
                    .filter(c -> c.fqName().equals(fqName))
                    .findFirst();
            if (cuOpt.isPresent()) {
                String skeleton = reconstructFullSkeleton(cuOpt.get(), headerOnly);
                log.trace("getSkeleton: fqName='{}', found=true", fqName);
                return Optional.of(skeleton);
            }
            log.trace("getSkeleton: fqName='{}', found=false", fqName);
            return Optional.empty();
        });
    }

    @Override
    public Optional<String> getClassSource(String fqName) {
        var cu = getDefinition(fqName)
                .filter(CodeUnit::isClass)
                .orElseThrow(() -> new SymbolNotFoundException("Class not found: " + fqName));

        var ranges = sourceRanges.get(cu);

        if (ranges == null || ranges.isEmpty()) {
            throw new SymbolNotFoundException("Source range not found for class: " + fqName);
        }

        // For classes, use the expanded range (index 1) that includes comments
        var range = ranges.size() > 1 ? ranges.get(1) : ranges.get(0);

        String src;
        try {
            src = cu.source().read();
        } catch (IOException e) {
            return Optional.empty();
        }

        var extractedSource = ASTTraversalUtils.safeSubstringFromByteOffsets(src, range.startByte(), range.endByte());

        return Optional.of(extractedSource);
    }

    @Override
    public Optional<String> getMethodSource(String fqName) {
        return getDefinition(fqName) // Finds the single CodeUnit representing this FQN (due to CodeUnit equality for
                // overloads)
                .filter(CodeUnit::isFunction)
                .flatMap(cu -> {
                    List<Range> rangesForOverloads = sourceRanges.get(cu);
                    if (rangesForOverloads == null || rangesForOverloads.isEmpty()) {
                        log.warn(
                                "No source ranges found for CU {} (fqName {}) although definition was found.",
                                cu,
                                fqName);
                        return Optional.empty();
                    }

                    String fileContent;
                    try {
                        fileContent = cu.source().read();
                    } catch (IOException e) {
                        log.warn("Could not read source for CU {} (fqName {}): {}", cu, fqName, e.getMessage());
                        return Optional.empty();
                    }

                    List<String> individualMethodSources = new ArrayList<>();
                    for (Range range : rangesForOverloads) {
                        // Range is already expanded with comments during parsing
                        String methodSource = ASTTraversalUtils.safeSubstringFromByteOffsets(
                                fileContent, range.startByte(), range.endByte());
                        if (!methodSource.isEmpty()) {
                            individualMethodSources.add(methodSource);
                        } else {
                            log.warn(
                                    "Could not extract valid method source for range [{}, {}] for CU {} (fqName {}). Skipping this range.",
                                    range.startByte(),
                                    range.endByte(),
                                    cu,
                                    fqName);
                        }
                    }

                    if (individualMethodSources.isEmpty()) {
                        log.warn(
                                "After processing ranges, no valid method sources found for CU {} (fqName {}).",
                                cu,
                                fqName);
                        return Optional.empty();
                    }
                    return Optional.of(String.join("\n\n", individualMethodSources));
                });
    }

    @Override
    public Optional<String> getSourceForCodeUnit(CodeUnit codeUnit) {
        if (codeUnit.isFunction()) {
            return getMethodSource(codeUnit.fqName());
        } else if (codeUnit.isClass()) {
            return getClassSource(codeUnit.fqName());
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
     * Gets the starting line number for the given CodeUnit for UI positioning purposes. This returns the original code
     * definition line (not expanded with comments) for better navigation.
     *
     * @param codeUnit The CodeUnit to get the line number for
     * @return The 0-based starting line number of the actual definition, or -1 if not found
     */
    public int getStartLineForCodeUnit(CodeUnit codeUnit) {

        // For classes with dual-range storage, use the original range (index 0)
        if (codeUnit.isClass()) {
            var ranges = sourceRanges.get(codeUnit);
            if (ranges != null && ranges.size() >= 2) {
                // Use original range (index 0) for UI positioning
                var startLine = ranges.get(0).startLine();
                return startLine;
            }
            // Fallback to AST parsing if dual-range not available
            return getOriginalStartLine(codeUnit);
        }

        // For functions and other types, use the stored range as-is
        var ranges = sourceRanges.get(codeUnit);
        if (ranges != null && !ranges.isEmpty()) {
            var startLine = ranges.get(0).startLine();
            return startLine;
        }
        return -1;
    }

    /**
     * Gets the original (non-comment-expanded) start line for a CodeUnit by re-parsing. This is used for UI positioning
     * to jump to the actual definition, not preceding comments.
     */
    private int getOriginalStartLine(CodeUnit codeUnit) {
        try {
            var cachedTree = parsedTreeCache.get(codeUnit.source());
            if (cachedTree == null) {
                return -1; // Can't determine without re-parsing
            }

            var rootNode = cachedTree.getRootNode();
            if (rootNode.isNull()) {
                return -1;
            }

            // Search for the node that matches this CodeUnit's FQN
            // This is a simplified search - in a full implementation you'd use the same
            // query logic as in analyzeFileDeclarations, but for now we'll use a heuristic

            // For classes/functions, find the declaration with matching name
            var shortName = codeUnit.shortName();
            var foundLine = findDeclarationLine(rootNode, shortName, codeUnit.isClass());
            return foundLine;

        } catch (Exception e) {
            log.debug("Could not determine original start line for {}: {}", codeUnit.fqName(), e.getMessage());
            return -1;
        }
    }

    /**
     * Recursively searches for a declaration node with the given name. Returns the start line of the declaration (not
     * expanded with comments).
     */
    private int findDeclarationLine(TSNode node, String targetName, boolean isClass) {
        if (node.isNull()) {
            return -1;
        }

        // Check if this node represents the target declaration
        var nodeType = node.getType();
        var isTargetType = isClass
                ? (nodeType.contains("class")
                        || nodeType.contains("struct")
                        || nodeType.contains("interface")
                        || nodeType.contains("enum"))
                : (nodeType.contains("function") || nodeType.contains("method"));

        if (isTargetType) {
            // Simple heuristic: check if node text contains the target name
            // In a full implementation, you'd use proper TreeSitter queries
            // Simple heuristic: we can't easily get node text without the source,
            // so we'll return the line if the node type seems right
            return node.getStartPoint().getRow();
        }

        // Recursively search child nodes
        var childCount = node.getChildCount();
        for (var i = 0; i < childCount; i++) {
            var child = node.getChild(i);
            var result = findDeclarationLine(child, targetName, isClass);
            if (result >= 0) {
                return result;
            }
        }

        return -1;
    }

    /* ---------- abstract hooks ---------- */

    /** Creates a new TSLanguage instance for the specific language. Called by ThreadLocal initializer. */
    protected abstract TSLanguage createTSLanguage();

    /**
     * Provides a thread-safe TSLanguage instance.
     *
     * @return A TSLanguage instance for the current thread.
     */
    protected TSLanguage getTSLanguage() {
        return threadLocalLanguage.get();
    }

    /**
     * Returns the cached parsed tree for the given file if available, or null if not cached. This method allows
     * subclasses to reuse already-parsed trees instead of re-parsing files.
     *
     * @param file The project file to get the cached tree for
     * @return The cached TSTree, or null if not available
     */
    protected @Nullable TSTree getCachedTree(ProjectFile file) {
        return parsedTreeCache.get(file);
    }

    /** Provides the language-specific syntax profile. */
    protected abstract LanguageSyntaxProfile getLanguageSyntaxProfile();

    /** Class-path resource for the query (e.g. {@code "treesitter/python.scm"}). */
    protected abstract String getQueryResource();

    /** Defines the general type of skeleton that should be built for a given capture. */
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
     * @param file The project file being analyzed.
     * @param definitionNode The TSNode representing the definition (e.g., class, function).
     * @param rootNode The root TSNode of the file's syntax tree.
     * @param src The source code of the file.
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
     * Builds the parent FQName from package name and class chain for parent-child relationship lookup. Override this
     * method to apply language-specific FQName correction logic.
     */
    protected String buildParentFqName(String packageName, String classChain) {
        return packageName.isEmpty() ? classChain : packageName + "." + classChain;
    }

    /** Captures that should be ignored entirely. */
    protected Set<String> getIgnoredCaptures() {
        return Set.of();
    }

    /** Language-specific indentation string, e.g., " " or " ". */
    protected String getLanguageSpecificIndent() {
        return "  ";
    } // Default

    /** Language-specific closing token for a class or namespace (e.g., "}"). Empty if none. */
    protected abstract String getLanguageSpecificCloser(CodeUnit cu);

    /** Get the project this analyzer is associated with. */
    protected IProject getProject() {
        return project;
    }

    /* ---------- core parsing ---------- */

    /** Analyzes a single file and extracts declaration information. */
    private FileAnalysisResult analyzeFileDeclarations(ProjectFile file, TSParser localParser) throws IOException {
        log.trace("analyzeFileDeclarations: Parsing file: {}", file);
        byte[] fileBytes = Files.readAllBytes(file.absPath());
        // Strip UTF-8 BOM if present (EF BB BF)
        if (fileBytes.length >= 3
                && (fileBytes[0] & 0xFF) == 0xEF
                && (fileBytes[1] & 0xFF) == 0xBB
                && (fileBytes[2] & 0xFF) == 0xBF) {
            byte[] bytesWithoutBom = new byte[fileBytes.length - 3];
            System.arraycopy(fileBytes, 3, bytesWithoutBom, 0, fileBytes.length - 3);
            fileBytes = bytesWithoutBom;
            log.trace("Stripped UTF-8 BOM from file: {}", file);
        }

        String src = new String(fileBytes, StandardCharsets.UTF_8);
        final byte[] finalFileBytes = fileBytes; // For use in lambdas

        // record (or refresh) the file content hash
        fileHashes.put(file, sha1Hex(fileBytes));

        List<CodeUnit> localTopLevelCUs = new ArrayList<>();
        Map<CodeUnit, List<CodeUnit>> localChildren = new HashMap<>();
        Map<CodeUnit, List<String>> localSignatures = new HashMap<>();
        Map<CodeUnit, List<Range>> localSourceRanges = new HashMap<>();
        Map<String, List<CodeUnit>> localCodeUnitsBySymbol = new HashMap<>();
        Map<String, CodeUnit> localCuByFqName = new HashMap<>(); // For parent lookup within the file
        List<String> localImportStatements = new ArrayList<>(); // For collecting import lines

        TSTree tree = localParser.parseString(null, src);
        // Cache the parsed tree for later use to avoid redundant parsing
        parsedTreeCache.put(file, tree);
        TSNode rootNode = tree.getRootNode();
        if (rootNode.isNull()) {
            log.warn("Parsing failed or produced null root node for {}", file);
            return new FileAnalysisResult(List.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
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
            // Handle module-level import statements first if present in this match
            TSNode importNode = capturedNodesForMatch.get("module.import_statement");
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

                    if (nameNode != null && !nameNode.isNull()) {
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
                    extractSimpleName(tempParent, src)
                            .ifPresent(
                                    parentName -> { // extractSimpleName is now non-static
                                        if (!parentName.isBlank()) enclosingClassNames.addFirst(parentName);
                                    });
                }
                tempParent = tempParent.getParent();
            }
            String classChain = String.join("$", enclosingClassNames);
            log.trace("Computed classChain for simpleName='{}': '{}'", simpleName, classChain);

            // Adjust simpleName and classChain for Go methods to correctly include the receiver type
            if (language == Language.GO && "method.definition".equals(primaryCaptureName)) {
                // The SCM query for Go methods captures `@method.receiver.type` and `@method.identifier`
                // `simpleName` at this point is from `@method.identifier` (e.g., "MyMethod")
                // We need to find the receiver type from the original captures for this match
                // The `capturedNodes` map (re-populated per match earlier in the loop) is not directly available here.
                // We need to re-access the specific captures for the current `match` associated with `node`.
                // This requires finding the original TSQueryMatch or passing its relevant parts.
                // For now, let's assume `node` is the `method_declaration` node, and we can query its children.
                // A more robust way would be to pass `capturedNodes` from the outer loop or re-query for this specific
                // `node`.

                TSNode receiverNode;
                // TSNode methodIdentifierNode = null; // This would be `node.getChildByFieldName("name")` for
                // method_declaration
                // or more reliably, the node associated with captureName.replace(".definition", ".name")
                // simpleName is already derived from method.identifier.

                // Re-evaluate captures specific to this `node` (method_definition)
                // This is a simplified re-querying logic. A more efficient approach might involve
                // passing the full `capturedNodes` map associated with the `match` that led to this `node`.
                TSQueryCursor an_cursor = new TSQueryCursor();
                TSQuery currentThreadQueryForNode = this.query.get(); // Get thread-specific query for this operation
                an_cursor.exec(currentThreadQueryForNode, node); // Execute query only on the current definition node
                TSQueryMatch an_match = new TSQueryMatch();
                Map<String, TSNode> localCaptures = new HashMap<>();
                if (an_cursor.nextMatch(an_match)) { // Should find one match for the definition node itself
                    for (TSQueryCapture capture : an_match.getCaptures()) {
                        String capName = currentThreadQueryForNode.getCaptureNameForId(capture.getIndex());
                        localCaptures.put(capName, capture.getNode());
                    }
                }

                receiverNode = localCaptures.get("method.receiver.type");

                if (receiverNode != null && !receiverNode.isNull()) {
                    String receiverTypeText = textSlice(receiverNode, fileBytes).trim();
                    if (receiverTypeText.startsWith("*")) {
                        receiverTypeText = receiverTypeText.substring(1).trim();
                    }
                    if (!receiverTypeText.isEmpty()) {
                        simpleName = receiverTypeText + "." + simpleName;
                        classChain = receiverTypeText; // For Go methods, classChain is the receiver type
                        log.trace("Adjusted Go method: simpleName='{}', classChain='{}'", simpleName, classChain);
                    } else {
                        log.warn(
                                "Go method: Receiver type text was empty for node {}. FQN might be incorrect.",
                                textSlice(receiverNode, fileBytes));
                    }
                } else {
                    log.warn(
                            "Go method: Could not find capture for @method.receiver.type for method '{}'. FQN might be incorrect.",
                            simpleName);
                }
            }

            CodeUnit cu = createCodeUnit(file, primaryCaptureName, simpleName, packageName, classChain);
            log.trace("createCodeUnit returned: {}", cu);

            if (cu == null) {
                log.warn("createCodeUnit returned null for node {} ({})", simpleName, primaryCaptureName);
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
                    buildSignatureString(node, simpleName, fileBytes, primaryCaptureName, modifierKeywords, file);
            log.trace(
                    "Built signature for '{}': [{}]",
                    simpleName,
                    signature.isBlank()
                            ? "BLANK"
                            : signature.lines().findFirst().orElse("EMPTY"));

            if (file.getFileName().equals("vars.py") && primaryCaptureName.equals("field.definition")) {
                log.trace(
                        "[vars.py DEBUG] Processing entry for vars.py field: Node Type='{}', SimpleName='{}', CaptureName='{}', PackageName='{}', ClassChain='{}'",
                        node.getType(),
                        simpleName,
                        primaryCaptureName,
                        packageName,
                        classChain);
                log.trace(
                        "[vars.py DEBUG] CU created: {}, Signature: [{}]",
                        cu,
                        signature.isBlank()
                                ? "BLANK_SIG"
                                : signature.lines().findFirst().orElse("EMPTY_SIG"));
            }

            if (signature.isBlank()) {
                // buildSignatureString might legitimately return blank for some nodes that don't form part of a textual
                // skeleton but create a CU.
                // However, if it's blank, it shouldn't be added to signatures map.
                log.trace(
                        "buildSignatureString returned empty/null for node {} ({}), simpleName {}. This CU might not have a direct textual signature.",
                        node.getType(),
                        primaryCaptureName,
                        simpleName);
                continue;
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
                    && (language == Language.TYPESCRIPT || language == Language.JAVASCRIPT)) {
                List<String> existingSignatures =
                        localSignatures.get(existingCUforKeyLookup); // Existing signatures for the *other* CU instance
                boolean newIsExported = signature.trim().startsWith("export");
                boolean oldIsExported = (existingSignatures != null && !existingSignatures.isEmpty())
                        && existingSignatures.getFirst().trim().startsWith("export"); // Check first existing

                if (newIsExported && !oldIsExported) {
                    log.warn(
                            "Replacing non-exported CU/signature list for {} with new EXPORTED signature.",
                            cu.fqName());
                    localSignatures.remove(existingCUforKeyLookup); // Remove old CU's signatures
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
                    node.getEndPoint().getRow());

            // For source extraction, we want expanded ranges (with comments)
            // For UI positioning, we want original ranges (actual definition)
            // Store original range first, then expanded range for classes/functions
            var ranges = localSourceRanges.computeIfAbsent(cu, k -> new ArrayList<>());

            if (cu.isClass()) {
                // For classes: [0] = original (for UI), [1] = expanded (for source extraction)
                ranges.add(originalRange); // Original position for navigation
                ranges.add(expandRangeWithComments(file, originalRange)); // Expanded for source extraction
            } else if (cu.isFunction()) {
                // For functions: use expanded range for source extraction (includes comments)
                ranges.add(expandRangeWithComments(file, originalRange));
            } else {
                // For fields/variables: use original range
                ranges.add(originalRange);
            }
            localCuByFqName.put(cu.fqName(), cu); // Add/overwrite current CU by its FQ name
            localChildren.putIfAbsent(cu, new ArrayList<>()); // Ensure every CU can be a parent

            if (classChain.isEmpty()) {
                localTopLevelCUs.add(cu);
            } else {
                // Parent's shortName is the classChain string itself.
                String parentFqName = buildParentFqName(cu.packageName(), classChain);
                CodeUnit parentCu = localCuByFqName.get(parentFqName);
                if (parentCu != null) {
                    List<CodeUnit> kids = localChildren.computeIfAbsent(parentCu, k -> new ArrayList<>());
                    if (!kids.contains(cu)) { // Prevent adding duplicate children
                        kids.add(cu);
                    }
                } else {
                    log.trace(
                            "Could not resolve parent CU for {} using parent FQ name candidate '{}' (derived from classChain '{}'). Treating as top-level for this file.",
                            cu,
                            parentFqName,
                            classChain);
                    localTopLevelCUs.add(cu); // Fallback
                }
            }
            log.trace("Stored/Updated info for CU: {}", cu);
        }

        // After processing all captures, if there were import statements, create a MODULE CodeUnit
        if (!localImportStatements.isEmpty()) {
            String modulePackageName =
                    determinePackageName(file, rootNode, rootNode, src); // Use rootNode for general package name
            // Use a consistent, unique short name for the module CU, based on filename.
            // This ensures module CUs from different files have distinct fqNames.
            String moduleShortName = file.getFileName();
            CodeUnit moduleCU = CodeUnit.module(file, modulePackageName, moduleShortName);

            // Check if a module CU with this FQ name already exists
            // or if this logic somehow runs twice for the same file.
            if (!localCuByFqName.containsKey(moduleCU.fqName())) {
                localTopLevelCUs.addFirst(moduleCU); // Add to the beginning for preferred order
                localCuByFqName.put(moduleCU.fqName(), moduleCU);
                // Join imports into a single multi-line signature string for the module CU
                String importBlockSignature = String.join("\n", localImportStatements);
                localSignatures
                        .computeIfAbsent(moduleCU, k -> new ArrayList<>())
                        .add(importBlockSignature);
                // Add a general range for the module CU (e.g. entire file or first import to last)
                // For simplicity, can use the range of the root node or skip detailed range for module CU.
                // Here, we'll use the root node's range as a placeholder.
                var moduleRange = new Range(
                        rootNode.getStartByte(),
                        rootNode.getEndByte(),
                        rootNode.getStartPoint().getRow(),
                        rootNode.getEndPoint().getRow());
                // Module CUs typically don't need comment expansion as they represent the whole file
                localSourceRanges
                        .computeIfAbsent(moduleCU, k -> new ArrayList<>())
                        .add(moduleRange);
                log.trace("Created MODULE CU for {} with {} import statements.", file, localImportStatements.size());
            } else {
                log.warn(
                        "Module CU for {} with fqName {} already exists. Skipping duplicate module CU creation.",
                        file,
                        moduleCU.fqName());
            }
        }

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

        return new FileAnalysisResult(
                Collections.unmodifiableList(localTopLevelCUs),
                finalLocalChildren,
                localSignatures,
                finalLocalSourceRanges,
                localCodeUnitsBySymbol,
                Collections.unmodifiableList(localImportStatements));
    }

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
            byte[] srcBytes,
            String primaryCaptureName,
            List<String> capturedModifierKeywords,
            ProjectFile file) {
        List<String> signatureLines = new ArrayList<>();
        var profile = getLanguageSyntaxProfile();
        SkeletonType skeletonType = getSkeletonTypeForCapture(primaryCaptureName); // Get skeletonType early

        // Convert to String for compatibility with existing method signatures
        String src = new String(srcBytes, StandardCharsets.UTF_8);

        TSNode nodeForContent = definitionNode;
        TSNode nodeForSignature = definitionNode; // Keep original for signature text slicing

        // 1. Handle language-specific structural unwrapping (e.g., export statements, Python's decorated_definition)
        // For JAVASCRIPT/TYPESCRIPT: unwrap for processing but keep original for signature
        if ((language == Language.TYPESCRIPT || language == Language.JAVASCRIPT)
                && "export_statement".equals(definitionNode.getType())) {
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
                                ((language == Language.TYPESCRIPT || language == Language.JAVASCRIPT)
                                        && ("lexical_declaration".equals(innerType)
                                                || "variable_declaration".equals(innerType)));
                    case FIELD_LIKE -> typeMatch = profile.fieldLikeNodeTypes().contains(innerType);
                    case ALIAS_LIKE ->
                        typeMatch = (project.getAnalyzerLanguages().contains(Language.TYPESCRIPT)
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
        if ((language == Language.TYPESCRIPT || language == Language.JAVASCRIPT)
                && ("lexical_declaration".equals(nodeForContent.getType())
                        || "variable_declaration".equals(nodeForContent.getType()))
                && (skeletonType == SkeletonType.FIELD_LIKE || skeletonType == SkeletonType.FUNCTION_LIKE)) {
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

        if (language == Language.PYTHON && "decorated_definition".equals(definitionNode.getType())) {
            // Python's decorated_definition: decorators and actual def are children.
            // Process decorators directly here and identify the actual content node.
            for (int i = 0; i < definitionNode.getNamedChildCount(); i++) {
                TSNode child = definitionNode.getNamedChild(i);
                if (profile.decoratorNodeTypes().contains(child.getType())) {
                    signatureLines.add(textSlice(child, srcBytes).stripLeading());
                } else if (profile.functionLikeNodeTypes().contains(child.getType())
                        || profile.classLikeNodeTypes().contains(child.getType())) {
                    nodeForContent = child;
                }
            }
        }
        // 2. Handle decorators for languages where they precede the definition
        //    (Skip if Python already handled its specific decorator structure)
        if (!(language == Language.PYTHON && "decorated_definition".equals(definitionNode.getType()))) {
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

    /** Renders the opening part of a class-like structure (e.g., "public class Foo {"). */
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
     * @param src The source code.
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
     * @param src The source code.
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
     * @param fieldNode The TSNode representing the field declaration.
     * @param src The source code.
     * @param exportPrefix The pre-determined export/visibility prefix (e.g., "export const ").
     * @param signatureText The core text of the field signature (e.g., "fieldName: type = value").
     * @param baseIndent The indentation string for this line.
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
     * @param src The source code.
     * @return The visibility or export prefix string.
     */
    protected String getVisibilityPrefix(TSNode node, String src) {
        return ""; // Default implementation returns an empty string
    }

    /**
     * Builds the function signature lines.
     *
     * @param funcNode The TSNode for the function definition.
     * @param providedNameOpt Optional pre-determined name (e.g. from a specific capture).
     * @param src Source code.
     * @param indent Indentation string.
     * @param lines List to add signature lines to.
     * @param exportPrefix Pre-determined export and modifier prefix (e.g., "export async").
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

        // exportPrefix already contains all modifiers including 'async' if present.
        // The asyncPrefix logic is removed as it's now part of the unified exportPrefix.
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

        // The asyncPrefix parameter is removed from assembleFunctionSignature
        String functionLine = assembleFunctionSignature(
                funcNode, src, exportPrefix, "", functionName, typeParamsText, paramsText, returnTypeText, indent);
        if (!functionLine.isBlank()) {
            lines.add(functionLine);
        }
    }

    /**
     * Retrieves extra comment lines to be added to a function's skeleton, typically before the body. Example: mutation
     * tracking comments.
     *
     * @param bodyNode The TSNode representing the function's body. Can be null.
     * @param src The source code.
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
     * @param funcNode The Tree-sitter node representing the function.
     * @param src The source code of the file.
     * @param exportAndModifierPrefix The combined export and modifier prefix (e.g., "export async ", "public static ").
     * @param asyncPrefix This parameter is deprecated and no longer used; async is part of exportAndModifierPrefix.
     *     Pass empty string.
     * @param functionName The name of the function.
     * @param paramsText The text content of the function's parameters.
     * @param returnTypeText The text content of the function's return type, or empty if none.
     * @param indent The base indentation string for this line.
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

    /** Finds decorator nodes immediately preceding a given node. */
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

    /** Extracts a substring from the source code based on node boundaries. */
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

    /** Extracts a substring from the source code based on byte offsets. */
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

    /** Helper method that correctly extracts UTF-8 byte slice into a String */
    private String textSliceFromBytes(int startByte, int endByte, byte[] bytes) {
        return textSliceFromBytesWithFile(startByte, endByte, bytes, null);
    }

    /** Helper method that correctly extracts UTF-8 byte slice into a String with optional file context */
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

    /**
     * Compute SHA-1 of the provided data and return it as lowercase hex. SHA-1 is guaranteed to be available on every
     * JVM.
     */
    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm unavailable", e);
        }
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
        return withReadLock(() -> childrenByParent.getOrDefault(cu, List.of()));
    }

    /* ---------- incremental updates ---------- */

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        if (changedFiles.isEmpty()) {
            return this;
        }

        var writeLock = stateRwLock.writeLock();
        writeLock.lock();
        try {
            for (var file : changedFiles) {
                // -------- cleanup ----------
                parsedTreeCache.remove(file);
                topLevelDeclarations.remove(file);
                fileHashes.remove(file);

                Predicate<CodeUnit> fromFile = cu -> cu.source().equals(file);

                var symbolsToPurge = new HashSet<String>();
                var symbolsToUpdate = new HashMap<String, List<CodeUnit>>();
                for (var entry : symbolIndex.entrySet()) {
                    var symbol = entry.getKey();
                    var cus = entry.getValue();
                    var remaining = cus.stream().filter(fromFile.negate()).toList();
                    if (remaining.isEmpty()) {
                        symbolsToPurge.add(symbol);
                    } else if (remaining.size() < cus.size()) {
                        symbolsToUpdate.put(symbol, remaining);
                    }
                }
                symbolsToUpdate.forEach(symbolIndex::put);
                symbolsToPurge.forEach(symbolIndex::remove);

                childrenByParent.keySet().removeIf(fromFile);
                signatures.keySet().removeIf(fromFile);
                sourceRanges.keySet().removeIf(fromFile);

                // remove children entries pointing to CodeUnits from the changed file
                childrenByParent.replaceAll((parent, kids) -> {
                    var filtered = kids.stream().filter(fromFile.negate()).toList();
                    return filtered.equals(kids) ? kids : List.copyOf(filtered);
                });

                // -------- re-analyse (if file still exists) ----------
                if (Files.exists(file.absPath())) {
                    try {
                        var localParser = new TSParser();
                        if (!localParser.setLanguage(getTSLanguage())) {
                            log.error("Cannot set TSLanguage for {}", file);
                            continue;
                        }
                        var analysisResult = analyzeFileDeclarations(file, localParser);
                        ingestAnalysisResult(file, analysisResult);
                    } catch (IOException e) {
                        log.warn("IO error re-analysing {}: {}", file, e.getMessage());
                    } catch (RuntimeException e) {
                        log.error("Runtime error re-analysing {}: {}", file, e.getMessage(), e);
                    }
                } else {
                    log.debug("File {} deleted; state cleaned.", file);
                }
            }
        } finally {
            writeLock.unlock();
        }
        return this;
    }

    /**
     * Full-project incremental update: detect created/modified/deleted files by comparing on-disk SHA-1 hashes to the
     * cached ones, then delegate to {@link #update(Set)}.
     */
    @Override
    public IAnalyzer update() {
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

        Set<ProjectFile> changed = new HashSet<>();

        // deleted or no-longer-relevant files
        for (ProjectFile known : new HashSet<>(fileHashes.keySet())) {
            if (!currentFiles.contains(known) || !Files.exists(known.absPath())) {
                changed.add(known);
            }
        }

        // new or modified files
        for (ProjectFile pf : currentFiles) {
            try {
                byte[] bytes = Files.readAllBytes(pf.absPath());
                String newHash = sha1Hex(bytes);
                String oldHash = fileHashes.get(pf);
                if (!newHash.equals(oldHash)) {
                    changed.add(pf);
                }
            } catch (IOException e) {
                log.warn("Could not hash {}: {}", pf, e.getMessage());
                changed.add(pf); // treat as changed; will retry next time
            }
        }

        // reuse the existing incremental logic
        return update(changed);
    }

    private void ingestAnalysisResult(ProjectFile pf, FileAnalysisResult analysisResult) {
        if (analysisResult.topLevelCUs().isEmpty()
                && analysisResult.signatures().isEmpty()
                && analysisResult.sourceRanges().isEmpty()) {
            return;
        }

        topLevelDeclarations.put(pf, analysisResult.topLevelCUs());

        analysisResult.codeUnitsBySymbol().forEach((symbol, cus) -> {
            symbolIndex.compute(symbol, (String s, @Nullable List<CodeUnit> existing) -> {
                if (existing == null) {
                    return List.copyOf(cus);
                }
                var merged = new ArrayList<>(existing);
                cus.stream().filter(c -> !merged.contains(c)).forEach(merged::add);
                return List.copyOf(merged);
            });
        });

        analysisResult
                .children()
                .forEach((parent, newKids) ->
                        childrenByParent.compute(parent, (CodeUnit p, @Nullable List<CodeUnit> existing) -> {
                            if (existing == null) return newKids;
                            var merged = new ArrayList<>(existing);
                            newKids.stream().filter(k -> !merged.contains(k)).forEach(merged::add);
                            return List.copyOf(merged);
                        }));

        analysisResult
                .signatures()
                .forEach((cu, newSigs) -> signatures.compute(cu, (CodeUnit c, @Nullable List<String> existing) -> {
                    if (existing == null) return List.copyOf(newSigs);
                    var merged = new ArrayList<>(existing);
                    newSigs.stream().filter(s -> !merged.contains(s)).forEach(merged::add);
                    return List.copyOf(merged);
                }));

        analysisResult
                .sourceRanges()
                .forEach((cu, newRanges) -> sourceRanges.compute(cu, (CodeUnit c, @Nullable List<Range> existing) -> {
                    if (existing == null) return List.copyOf(newRanges);
                    var merged = new ArrayList<>(existing);
                    merged.addAll(newRanges);
                    return List.copyOf(merged);
                }));
    }

    /* ---------- comment detection for source expansion ---------- */

    /** Checks if a Tree-Sitter node represents a comment. Supports common comment node types across languages. */
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

    /** Finds a Tree-Sitter node by its byte range within the given tree. */
    protected Optional<TSNode> findNodeByRange(TSTree tree, int startByte, int endByte) {
        TSNode root = tree.getRootNode();
        return findNodeByRangeRecursive(root, startByte, endByte);
    }

    private Optional<TSNode> findNodeByRangeRecursive(TSNode node, int targetStartByte, int targetEndByte) {
        // Check if this node matches the target range
        if (node.getStartByte() == targetStartByte && node.getEndByte() == targetEndByte) {
            return Optional.of(node);
        }

        // Check children
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                // Only recurse if the target range could be within this child
                if (child.getStartByte() <= targetStartByte && child.getEndByte() >= targetEndByte) {
                    Optional<TSNode> result = findNodeByRangeRecursive(child, targetStartByte, targetEndByte);
                    if (result.isPresent()) {
                        return result;
                    }
                }
            }
        }

        return Optional.empty();
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

    /** Checks if a node contains only whitespace (spaces, tabs, newlines). */
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
     * Expands a source range to include preceding comments. Re-parses the source file to get AST for comment detection.
     */
    protected Range expandRangeWithComments(ProjectFile file, Range originalRange) {
        try {
            // Re-parse the source file
            String src = file.read();

            TSParser parser = new TSParser();
            if (!parser.setLanguage(getTSLanguage())) {
                log.warn("Failed to set language for comment expansion in file {}", file);
                return originalRange;
            }

            TSTree tree = parser.parseString(null, src);

            // Find the declaration node by its range
            Optional<TSNode> declarationNode =
                    findNodeByRange(tree, originalRange.startByte(), originalRange.endByte());
            if (declarationNode.isEmpty()) {
                log.debug(
                        "Could not find declaration node for range [{}, {}] in file {}",
                        originalRange.startByte(),
                        originalRange.endByte(),
                        file);
                return originalRange;
            }

            // Find preceding comments
            List<TSNode> precedingComments = findPrecedingComments(declarationNode.get());
            if (precedingComments.isEmpty()) {
                return originalRange;
            }

            // Calculate new start byte from earliest comment
            int newStartByte = precedingComments.get(0).getStartByte();
            int newStartLine = precedingComments.get(0).getStartPoint().getRow();

            Range expandedRange =
                    new Range(newStartByte, originalRange.endByte(), newStartLine, originalRange.endLine());

            log.trace(
                    "Expanded range for file {} from [{}, {}] to [{}, {}] (added {} comment nodes)",
                    file,
                    originalRange.startByte(),
                    originalRange.endByte(),
                    expandedRange.startByte(),
                    expandedRange.endByte(),
                    precedingComments.size());

            return expandedRange;

        } catch (IOException e) {
            log.warn("IO error during comment expansion for file {}: {}", file, e.getMessage());
            return originalRange;
        } catch (Exception e) {
            log.warn("Error during comment expansion for file {}: {}", file, e.getMessage());
            return originalRange;
        }
    }
}
