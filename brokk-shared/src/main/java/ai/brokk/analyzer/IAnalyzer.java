package ai.brokk.analyzer;

import ai.brokk.AnalyzerUtil;
import ai.brokk.project.ICoreProject;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * Core analyzer interface providing code intelligence capabilities.
 *
 * <p><b>API Pattern:</b> Capability providers (e.g., {@link ImportAnalysisProvider}, {@link TypeHierarchyProvider})
 * accept {@link CodeUnit} parameters. When you have a CodeUnit, call provider methods directly. When you only have a
 * String FQN, use {@link ai.brokk.AnalyzerUtil} convenience methods to convert and delegate.
 */
public interface IAnalyzer {
    // Common separators across languages to denote hierarchy or member access.
    // Includes: '.' (Java/others), '$' (Java nested classes), '::' (C++/C#/Ruby), '->' (PHP), etc.
    Set<String> COMMON_HIERARCHY_SEPARATORS = Set.of(".", "$", "::", "->");

    /**
     * Record representing a code unit relevance result with a code unit and its score.
     */
    record FileRelevance(ProjectFile file, double score) implements Comparable<FileRelevance> {
        @Override
        public int compareTo(FileRelevance other) {
            int scoreComparison = Double.compare(other.score, this.score);
            return scoreComparison != 0 ? scoreComparison : this.file.absPath().compareTo(other.file.absPath());
        }
    }

    enum SourcePathKind {
        FILE,
        DIRECTORY
    }

    record SourceLookupAlias(String lookupName, String sourcePathSuffix, SourcePathKind sourcePathKind) {
        public SourceLookupAlias {
            sourcePathSuffix = normalizePath(sourcePathSuffix);
        }

        public static SourceLookupAlias anySource(String lookupName) {
            return new SourceLookupAlias(lookupName, "", SourcePathKind.FILE);
        }

        public static SourceLookupAlias sourceFile(String lookupName, String sourcePathSuffix) {
            return new SourceLookupAlias(lookupName, sourcePathSuffix, SourcePathKind.FILE);
        }

        public static SourceLookupAlias sourceDirectory(String lookupName, String sourcePathSuffix) {
            return new SourceLookupAlias(lookupName, sourcePathSuffix, SourcePathKind.DIRECTORY);
        }

        public SourceLookupAlias withLookupName(String newLookupName) {
            return new SourceLookupAlias(newLookupName, sourcePathSuffix, sourcePathKind);
        }

        public boolean matchesSource(CodeUnit candidate) {
            if (sourcePathSuffix.isBlank()) {
                return true;
            }

            String candidatePath =
                    switch (sourcePathKind) {
                        case FILE ->
                            normalizePath(candidate.source().getRelPath().toString());
                        case DIRECTORY ->
                            normalizePath(candidate.source().getParent().toString());
                    };
            return candidatePath.equals(sourcePathSuffix) || candidatePath.endsWith("/" + sourcePathSuffix);
        }

        private static String normalizePath(String path) {
            var normalized = path.replace('\\', '/');
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }
    }

    /**
     * Listener for progress updates during analyzer construction or update operations.
     * Implementations should be thread-safe as callbacks may come from worker threads.
     */
    @FunctionalInterface
    interface ProgressListener {
        ProgressListener NOOP = new NoopProgressListener();
        /**
         * Called to report progress during analyzer operations.
         *
         * @param completed Number of items completed
         * @param total     Total number of items to process
         * @param phase     Description of the current phase (e.g., "Parsing Java files")
         */
        void onProgress(int completed, int total, String phase);
    }

    class NoopProgressListener implements ProgressListener {
        @Override
        public void onProgress(int completed, int total, String phase) {
            // No-op
        }
    }

    // Basics
    List<CodeUnit> getTopLevelDeclarations(ProjectFile file);

    /**
     * Returns the set of all files currently represented in this analyzer snapshot.
     */
    default Set<ProjectFile> getAnalyzedFiles() {
        return Set.of();
    }

    /**
     * Determines if the given file contains tests using semantic analysis.
     *
     * <p><b>API Note:</b> This method should only be relied upon if the analyzer exposes the
     * {@link TestDetectionProvider} capability via {@link #as(Class)}. If the capability is
     * missing, a {@code false} return value indicates "unknown" or "unsupported" rather than
     * a definitive "no tests present". Callers should fall back to heuristics (e.g., filename
     * patterns) in such cases.
     *
     * @return true if the analyzer semantically confirms the file contains tests.
     */
    default boolean containsTests(ProjectFile file) {
        return false;
    }

    /**
     * Returns the set of languages this analyzer understands.
     */
    Set<Language> languages();

    /**
     * Update the Analyzer for create/modify/delete activity against `changedFiles`. This is O(M) in the number of
     * changed files. Assume this update is not in place, and rather use the returned
     * IAnalyzer.
     */
    IAnalyzer update(Set<ProjectFile> changedFiles);

    /**
     * Scan for changes across all files in the project. This involves comparing the last modified time of the file with
     * respect to this object's "last analyzed" time.  This is O(N) in the number of project files. Assume this update
     * is not in place, and rather use the returned IAnalyzer.
     */
    IAnalyzer update();

    // Summarization

    /**
     * The project this analyzer targets
     */
    ICoreProject getProject();

    default List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
        return getDirectChildren(classUnit);
    }

    /**
     * All top-level declarations in the project.
     */
    List<CodeUnit> getAllDeclarations();

    /**
     * The metrics around the codebase as determined by the analyzer.
     */
    default CodeBaseMetrics getMetrics() {
        final var declarations = getAllDeclarations();
        final var codeUnits = declarations.stream().map(CodeUnit::source).distinct();
        return new CodeBaseMetrics((int) codeUnits.count(), declarations.size());
    }

    /**
     * Gets all declarations in a given file.
     */
    Set<CodeUnit> getDeclarations(ProjectFile file);

    /**
     * Finds ALL CodeUnits matching the given fqName, returned in priority order.
     * For overloaded functions, returns all overloads ordered by language-specific prioritization.
     * First element is the preferred definition (e.g., .cpp implementation over .h declaration in C++).
     *
     * <p>To select a specific overload, filter the returned set by {@link CodeUnit#signature()}.
     *
     * <p><b>API Contract:</b> fqName is never unique - multiple CodeUnits may share the same fqName
     * (overloads, cross-file duplicates). Callers using {@code .findFirst()} get the highest-priority
     * definition per {@link #sortDefinitions}. For call graphs or navigation where the specific
     * overload matters, filter by signature or use the CodeUnit directly.
     *
     * @param fqName The exact, case-sensitive FQ name (without signature)
     * @return SequencedSet of all matching CodeUnits in priority order (may be empty)
     */
    SequencedSet<CodeUnit> getDefinitions(String fqName);

    default Collection<SourceLookupAlias> sourceLookupAliases(String requestedName) {
        return List.of(SourceLookupAlias.anySource(requestedName));
    }

    /**
     * Returns the enclosing class or module for the given CodeUnit.
     * If cu is already a class or module, returns itself.
     * If cu is a member (function, field), searches for the parent definition.
     */
    default Optional<CodeUnit> parentOf(CodeUnit cu) {
        String fqName = cu.fqName();
        int lastIdx = -1;
        // Find the last occurrence among any valid separators
        for (String sep : COMMON_HIERARCHY_SEPARATORS) {
            int idx = fqName.lastIndexOf(sep);
            if (idx > lastIdx) {
                lastIdx = idx;
            }
        }

        // Must find a separator, and it must not be the first character
        if (lastIdx <= 0) {
            return Optional.empty();
        }

        String candidateParent = fqName.substring(0, lastIdx);
        return getDefinitions(candidateParent).stream()
                .filter(parent -> parent.isClass() || parent.isModule())
                .findFirst();
    }

    /**
     * Returns the immediate children of the given CodeUnit for language-specific hierarchy traversal.
     *
     * <p>This method is used by the default getSymbols(java.util.Set) implementation to traverse the code unit
     * hierarchy and collect symbols from nested declarations. The specific parent-child relationships depend on the
     * target language:
     *
     * <ul>
     *   <li><strong>Classes:</strong> Return methods, fields, and nested classes
     *   <li><strong>Modules/Files:</strong> Return top-level declarations in the same file
     *   <li><strong>Functions/Methods:</strong> Typically return empty list (no children)
     *   <li><strong>Fields/Variables:</strong> Typically return empty list (no children)
     * </ul>
     *
     * <p><strong>Implementation Notes:</strong>
     *
     * <ul>
     *   <li>This method should be efficient as it may be called frequently during symbol resolution
     *   <li>Return an empty list rather than null for CodeUnits with no children
     *   <li>The returned list should contain only immediate children, not recursive descendants
     *   <li>Implementations should handle null input gracefully by returning an empty list
     * </ul>
     * <p>
     * See getSymbols(java.util.Set) for how this method is used in symbol collection.
     */
    List<CodeUnit> getDirectChildren(CodeUnit cu);

    /**
     * Extracts the class/module/type name from a method/member reference like "MyClass.myMethod". This is a heuristic
     * method that may produce false positives/negatives.
     * Package-private: external callers should use {@link ai.brokk.AnalyzerUtil#extractCallReceiver}.
     *
     * @param reference The reference string to analyze (e.g., "MyClass.myMethod", "package::Class::method")
     * @return Optional containing the extracted class/module name, empty if none found
     */
    Optional<String> extractCallReceiver(String reference);

    /**
     * @return the import snippets for the given file where other code units may be referred to by.
     */
    List<String> importStatementsOf(ProjectFile file);

    /**
     * @return the nearest enclosing code unit of the range within the file. Returns null if none exists or range is
     * invalid.
     */
    Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range);

    /**
     * @return the nearest enclosing code unit of the line range within the file. Returns empty if none exists.
     */
    Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, int startLine, int endLine);

    /**
     * Determines whether the code at the given byte range represents an actual access expression
     * (e.g., method call, type usage, field access) rather than a declaration, parameter name, or comment.
     *
     * @param file the file to check
     * @param startByte start byte offset
     * @param endByte end byte offset
     * @return true if the range is considered an access expression (default), false if it is clearly a declaration or comment.
     */
    default boolean isAccessExpression(ProjectFile file, int startByte, int endByte) {
        return true;
    }

    /**
     * Finds the nearest block-scoped declaration (parameter, local variable, etc.) for an identifier.
     *
     * <p>This method performs lexical scope analysis to determine if an identifier at the given
     * position is shadowed by a local declaration. It searches upward through enclosing blocks,
     * checking for parameters, local variables, catch parameters, for-loop variables, lambda
     * parameters, pattern variables, and try-with-resources variables.
     *
     * <p><b>Important:</b> This method does NOT resolve class-level field declarations. If no
     * block-scoped declaration shadows the identifier, this returns {@code Optional.empty()}.
     * The identifier may still refer to a field, imported symbol, or external reference — callers
     * must handle the empty case accordingly.
     *
     * <p>Primary use case: {@link #isAccessExpression} uses this to filter out local variable
     * and parameter usages from field/member access detection.
     *
     * @param file           the source file
     * @param startByte      the start byte of the identifier
     * @param endByte        the end byte of the identifier
     * @param identifierName the name of the identifier to resolve
     * @return information about the nearest block-scoped declaration, or empty if none found
     */
    default Optional<DeclarationInfo> findNearestDeclaration(
            ProjectFile file, int startByte, int endByte, String identifierName) {
        return Optional.empty();
    }

    /**
     * Kinds of declarations that can be found by {@link #findNearestDeclaration}.
     *
     * <p>Note: Not all kinds are returned by all language implementations.
     */
    enum DeclarationKind {
        PARAMETER,
        LOCAL_VARIABLE,
        /** Reserved for future use; not currently returned by any analyzer implementation. */
        FIELD,
        CATCH_PARAMETER,
        FOR_LOOP_VARIABLE,
        PATTERN_VARIABLE,
        RESOURCE_VARIABLE,
        UNKNOWN
    }

    record DeclarationInfo(DeclarationKind kind, String name, @Nullable CodeUnit enclosingUnit) {}

    record ExceptionSmellWeights(
            int genericThrowableWeight,
            int genericExceptionWeight,
            int genericRuntimeExceptionWeight,
            int emptyBodyWeight,
            int commentOnlyBodyWeight,
            int smallBodyWeight,
            int logOnlyWeight,
            int meaningfulBodyCreditPerStatement,
            int meaningfulBodyStatementThreshold,
            int smallBodyMaxStatements) {

        public static ExceptionSmellWeights defaults() {
            return new ExceptionSmellWeights(
                    5, // Throwable is usually over-broad
                    3, // Exception is broad and often swallows domain signals
                    2, // RuntimeException is still broad, but less severe than Exception
                    5, // Empty handler is the strongest smell
                    4, // Comment-only body still swallows
                    2, // Tiny bodies merit review
                    2, // Log-only can still be swallow-y
                    1, // Richer body reduces suspicion
                    6, // Credit plateaus after a moderate amount of handling
                    2 // 0-2 statements is considered a small body
                    );
        }
    }

    record ExceptionHandlingSmell(
            ProjectFile file,
            String enclosingFqName,
            String catchType,
            int score,
            int bodyStatementCount,
            List<String> reasons,
            String excerpt) {}

    record CloneSmellWeights(
            int minNormalizedTokens,
            int minSimilarityPercent,
            int shingleSize,
            int minSharedShingles,
            int astSimilarityPercent) {

        public static CloneSmellWeights defaults() {
            return new CloneSmellWeights(
                    12, // Keep small-but-real helper clones in scope.
                    60, // More tolerant to logging/guard/ceremony noise.
                    2, // Bigrams better tolerate scattered fluff statements.
                    3, // Allow meaningful overlap without requiring near identity.
                    70 // Structural refinement remains strong but less brittle.
                    );
        }
    }

    record CloneSmell(
            ProjectFile file,
            String enclosingFqName,
            ProjectFile peerFile,
            String peerEnclosingFqName,
            int score,
            int normalizedTokenCount,
            List<String> reasons,
            String excerpt,
            String peerExcerpt) {}

    record TestAssertionWeights(
            int noAssertionWeight,
            int tautologicalAssertionWeight,
            int constantTruthWeight,
            int constantEqualityWeight,
            int nullnessOnlyWeight,
            int shallowAssertionOnlyWeight,
            int overspecifiedLiteralWeight,
            int anonymousTestDoubleWeight,
            int repeatedAnonymousTestDoubleWeight,
            int meaningfulAssertionCredit,
            int meaningfulAssertionCreditCap,
            int largeLiteralLengthThreshold) {

        public static TestAssertionWeights defaults() {
            return new TestAssertionWeights(
                    5, // Test marker with no assertion-equivalent signal
                    6, // Self-comparison or otherwise tautological assertion
                    4, // assertTrue(true), assertFalse(false), etc.
                    4, // assertEquals(1, 1), assertSame(null, null), etc.
                    2, // assertNotNull/assertNull as the only assertion signal
                    2, // Only shallow assertion kinds such as nullness/type checks
                    2, // Large exact literals are often brittle review candidates
                    3, // Inline anonymous test double
                    5, // Repeated anonymous test double shape in the same file
                    1, // Stronger semantic assertions reduce suspicion
                    4, // Credit cap for meaningful assertions in one test
                    120 // Literal length considered large enough to review
                    );
        }
    }

    record TestAssertionSmell(
            ProjectFile file,
            String enclosingFqName,
            String assertionKind,
            int score,
            int assertionCount,
            List<String> reasons,
            String excerpt) {}

    record MaintainabilitySizeSmellWeights(
            int longMethodSpanLines,
            int highComplexityThreshold,
            int godObjectSpanLines,
            int godObjectDirectChildren,
            int godObjectFunctions,
            int helperSprawlFunctions,
            int helperSprawlWorkflowLines,
            int fileModuleLeewayMultiplier) {

        public static MaintainabilitySizeSmellWeights defaults() {
            return new MaintainabilitySizeSmellWeights(
                    80, // Long generated workflows become difficult to review.
                    10, // Matches the default cyclomatic complexity review threshold.
                    300, // Large class/module bodies are worth triage even before counting members.
                    20, // Many direct members suggest mixed responsibilities.
                    15, // Many functions under one parent suggest a god object/module.
                    10, // Helper sprawl threshold around a larger workflow.
                    60, // Workflow size large enough to make surrounding helpers suspicious.
                    2 // JS/TS modules and Python files are expected to be broader than class-like units.
                    );
        }
    }

    record MaintainabilitySizeSmell(
            CodeUnit codeUnit,
            Range range,
            int score,
            int ownSpanLines,
            int descendantSpanLines,
            int directChildCount,
            int functionCount,
            int nestedTypeCount,
            int maxFunctionSpanLines,
            int maxCyclomaticComplexity,
            List<String> reasons) {}

    record Range(int startByte, int endByte, int startLine, int endLine, int commentStartByte) {
        public boolean isEmpty() {
            return startLine == endLine && startByte == endByte;
        }

        public boolean isContainedWithin(Range other) {
            return startByte >= other.startByte && endByte <= other.endByte;
        }
    }

    List<Range> rangesOf(CodeUnit codeUnit);

    // Things most implementations won't have to override

    default boolean isEmpty() {
        return getAllDeclarations().isEmpty();
    }

    default <T extends CapabilityProvider> Optional<T> as(Class<T> capability) {
        return capability.isInstance(this) ? Optional.of(capability.cast(this)) : Optional.empty();
    }

    /**
     * Returns a comparator for prioritizing among multiple definitions with the same FQN.
     * Language-specific analyzers can override to provide custom ordering (e.g., preferring
     * .cpp implementations over .h declarations in C++).
     *
     * @return Comparator for definition prioritization (default returns no-op comparator)
     */
    default Comparator<CodeUnit> priorityComparator() {
        return Comparator.comparingInt(cu -> 0);
    }

    /**
     * Sorts a set of definitions by priority order.
     * Helper method for implementing getDefinitions() with consistent ordering.
     * Sorts by: language-specific priority -> source file -> fqName -> signature -> kind.
     *
     * @param definitions Unsorted set of definitions
     * @return SequencedSet with definitions in priority order (preserves uniqueness)
     */
    default SequencedSet<CodeUnit> sortDefinitions(Set<CodeUnit> definitions) {
        var sorted = definitions.stream()
                .sorted(priorityComparator()
                        .thenComparing((CodeUnit cu) -> cu.source().toString(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(CodeUnit::fqName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(
                                cu -> cu.signature() != null ? cu.signature() : "", String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(cu -> cu.kind().name()))
                .toList();

        // LinkedHashSet preserves insertion order (= sort order) while maintaining uniqueness
        return new LinkedHashSet<>(sorted);
    }

    default Set<CodeUnit> searchDefinitions(String pattern) {
        return searchDefinitions(pattern, true);
    }

    /**
     * Searches for a (Java) regular expression in the defined identifiers. We manipulate the provided pattern as
     * follows: val preparedPattern = if pattern.contains(".*") then pattern else s".*${Regex.quote(pattern)}.*"val
     * ciPattern = "(?i)" + preparedPattern // case-insensitive substring match
     */
    default Set<CodeUnit> searchDefinitions(String pattern, boolean autoQuote) {
        // Validate pattern
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("Search pattern may not be empty");
        }

        // Prepare case-insensitive regex pattern with non-greedy quantifiers
        if (autoQuote) {
            pattern = "(?i)" + (pattern.contains(".*") ? pattern : ".*?" + Pattern.quote(pattern) + ".*?");
        }

        Pattern compiledPattern = Pattern.compile(pattern);
        return searchDefinitions(compiledPattern);
    }

    default Set<CodeUnit> searchDefinitions(Pattern compiledPattern) {
        var matcher = compiledPattern.matcher("");
        return getAllDeclarations().stream()
                .filter(cu -> matcher.reset(cu.fqName()).find())
                .collect(Collectors.toSet());
    }

    /**
     * In order to preserve deterministic outcomes, we should sort the results. The rationale behind sorting by code
     * unit type in this way is guided by prioritizing "smaller" sets of units higher up in the tree. Modules are
     * typically not searched for, so these are put last.
     */
    static Comparator<CodeUnit> autocompleteDefinitionsSortComparator() {
        return Comparator.comparingInt((CodeUnit cu) -> switch (cu.kind()) {
                    case CLASS -> 0;
                    case FUNCTION -> 1;
                    case FIELD -> 2;
                    case MODULE -> 3;
                })
                .thenComparing(CodeUnit::fqName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CodeUnit::signature, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /**
     * Provides a search facility that is based on auto-complete logic based on (non-regex) user-input. By default, this
     * hands over to {@link IAnalyzer#searchDefinitions(String)} surrounded by wildcards.
     *
     * @param query the search query
     * @return a set of candidates where their fully qualified names may match the query.
     */
    default Set<CodeUnit> autocompleteDefinitions(String query) {
        if (query.isEmpty()) {
            return Set.of();
        }

        // Base: current behavior (case-insensitive substring via searchDefinitions)
        var baseResults = searchDefinitions(".*?" + query + ".*?");

        // Fuzzy: if short query, over-approximate by inserting ".*?" between characters
        Set<CodeUnit> fuzzyResults = Set.of();
        if (query.length() < 5) {
            StringBuilder sb = new StringBuilder("(?i)");
            sb.append(".*?");
            for (int i = 0; i < query.length(); i++) {
                sb.append(Pattern.quote(String.valueOf(query.charAt(i))));
                if (i < query.length() - 1) sb.append(".*?");
            }
            sb.append(".*?");
            fuzzyResults = searchDefinitions(sb.toString());
        }

        if (fuzzyResults.isEmpty()) {
            return baseResults;
        }

        // Merge results, preserving all overloads (fqName is not unique)
        LinkedHashMap<String, Set<CodeUnit>> byFqName = new LinkedHashMap<>();
        for (CodeUnit cu : baseResults)
            byFqName.computeIfAbsent(cu.fqName(), k -> new LinkedHashSet<>()).add(cu);
        for (CodeUnit cu : fuzzyResults)
            byFqName.computeIfAbsent(cu.fqName(), k -> new LinkedHashSet<>()).add(cu);

        return byFqName.values().stream()
                .flatMap(Set::stream)
                .filter(cu -> !cu.isSynthetic())
                .sorted(autocompleteDefinitionsSortComparator())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Extracts the unqualified symbol name from a fully-qualified name and adds it to the output set.
     */
    private static void addShort(String full, Set<String> out) {
        if (full.isEmpty()) return;

        // Optimized: scan from the end to find the last separator (faster than two indexOf calls)
        int idx = -1;
        for (int i = full.length() - 1; i >= 0; i--) {
            char c = full.charAt(i);
            if (c == '.' || c == '$') {
                idx = i;
                break;
            }
        }

        var shortName = idx >= 0 ? full.substring(idx + 1) : full;
        if (!shortName.isEmpty()) out.add(shortName);
    }

    /**
     * Gets a set of relevant symbol names (classes, methods, fields) defined within the given source CodeUnits.
     *
     * <p>
     *
     * <p>Almost all String representations in the Analyzer are fully-qualified, but these are not! In CodeUnit terms,
     * this returns identifiers -- just the symbol name itself, no class or package hierarchy.
     *
     * @param sources source files or classes to analyse
     * @return unqualified symbol names found within the sources
     */
    default Set<String> getSymbols(Set<CodeUnit> sources) {
        var visited = new HashSet<CodeUnit>();
        var work = new ArrayDeque<>(sources);
        var symbols = new HashSet<String>(); // Use regular HashSet for better performance

        while (!work.isEmpty()) {
            var cu = work.poll();
            if (!visited.add(cu)) continue;

            // 1) add the unit’s own short name
            addShort(cu.shortName(), symbols);

            // 2) recurse
            work.addAll(getDirectChildren(cu));
        }
        return symbols;
    }

    /**
     * Returns human-readable declaration signatures for a code unit, intended for display in search results.
     *
     * <p>Implementations with syntax-aware skeleton support should override this to return declaration text from
     * source. The default fallback is intentionally lightweight and may omit modifiers or inheritance details.
     */
    default List<String> getDisplaySignatures(CodeUnit codeUnit) {
        String functionDisplay = codeUnit.signature() != null
                ? codeUnit.identifier() + codeUnit.signature()
                : codeUnit.identifier() + "()";
        return List.of(
                switch (codeUnit.kind()) {
                    case CLASS -> "class " + codeUnit.identifier();
                    case FUNCTION -> functionDisplay;
                    case FIELD -> codeUnit.identifier();
                    case MODULE -> codeUnit.shortName();
                });
    }

    /**
     * Returns an analyzer that targets the given language if one is available. For single-analyzers, it will be the
     * analyzer instance itself if there is a match. For multi-analyzers, it will be a matching delegate, if any.
     *
     * @param language the language to return a supporting analyzer for.
     * @return the analyzer that targets the given language, empty otherwise.
     */
    default Optional<IAnalyzer> subAnalyzer(Language language) {
        // Default behaviour lends itself to single-analyzers. MultiAnalyzer overrides this as it has delegates.
        if (languages().contains(language)) {
            return Optional.of(this);
        } else {
            return Optional.empty();
        }
    }

    default String summarizeSymbols(ProjectFile file) {
        return summarizeSymbols(file, CodeUnitType.ALL);
    }

    default String summarizeSymbols(ProjectFile file, Set<CodeUnitType> types) {
        return summarizeSymbols(getTopLevelDeclarations(file), types, 0);
    }

    /**
     * Summarizes the given source text as though it were the contents of {@code file}, without requiring the analyzer
     * to update its project-wide snapshot.
     *
     * <p>The default implementation returns an empty string. Analyzers that can parse ad-hoc source text in isolation
     * should override this.
     */
    default String summarizeSymbols(ProjectFile file, String sourceText) {
        return "";
    }

    default String summarizeSymbols(Collection<CodeUnit> units, Set<CodeUnitType> types, int indent) {
        return summarizeSymbols(units, types, indent, Set.of());
    }

    private String summarizeSymbols(
            Collection<CodeUnit> units, Set<CodeUnitType> types, int indent, Set<CodeUnit> ancestorPath) {
        var indentStr = "  ".repeat(Math.max(0, indent));
        var sb = new StringBuilder();

        if (indent == 0 && !units.isEmpty()) {
            // Group by common prefix (package/module)
            Map<String, List<CodeUnit>> grouped = units.stream()
                    .filter(cu -> !cu.isAnonymous() && !cu.isModule())
                    .collect(Collectors.groupingBy(
                            cu -> {
                                String fqn = cu.fqName();
                                int lastDot = fqn.lastIndexOf('.');
                                return lastDot > 0 ? fqn.substring(0, lastDot) : "";
                            },
                            LinkedHashMap::new,
                            Collectors.toList()));

            for (var entry : grouped.entrySet()) {
                String groupPrefix = entry.getKey();
                List<CodeUnit> groupUnits = entry.getValue();

                if (!groupPrefix.isEmpty()) {
                    sb.append("# ").append(groupPrefix).append("\n");
                }

                for (var cu : groupUnits) {
                    renderSymbol(sb, cu, types, indent, indentStr, ancestorPath);
                }
            }
        } else {
            for (var cu : units) {
                if (cu.isAnonymous()) continue;
                renderSymbol(sb, cu, types, indent, indentStr, ancestorPath);
            }
        }
        return sb.toString().stripTrailing();
    }

    private void renderSymbol(
            StringBuilder sb,
            CodeUnit cu,
            Set<CodeUnitType> types,
            int indent,
            String indentStr,
            Set<CodeUnit> ancestorPath) {
        if (ancestorPath.contains(cu)) {
            return;
        }
        var pathForChildren = new HashSet<>(ancestorPath);
        pathForChildren.add(cu);
        // Use identifier for entries since the group header (if any) or nesting provides context
        sb.append(indentStr).append("- ").append(cu.identifier());

        var children = getDirectChildren(cu).stream()
                .filter(child -> types.contains(child.kind()))
                .toList();
        if (!children.isEmpty()) {
            sb.append("\n");
            sb.append(this.summarizeSymbols(children, types, indent + 1, pathForChildren));
        }
        sb.append("\n");
    }

    /**
     * Helper to convert a path to a Unix-style relative path string, suitable for build tool arguments.
     * Normalizes separators to '/' and ensures a "./" prefix for non-root paths (or "." for root).
     *
     * @param path the path to normalize
     * @return a Unix-style relative path string
     */
    static String toUnixRelativePath(Path path) {
        if (path.toString().isEmpty()) {
            return ".";
        }
        String unixPath = path.toString().replace('\\', '/');
        return unixPath.startsWith("./") ? unixPath : "./" + unixPath;
    }

    /**
     * Returns a distinct, sorted list of module or package identifiers corresponding to the given files.
     * Used for build/test command interpolation (e.g., {{#modules}} or {{#packages}}).
     *
     * @param files the files to extract modules from
     * @return list of module strings
     */
    default List<String> getTestModules(Collection<ProjectFile> files) {
        return files.stream()
                .flatMap(file -> getTopLevelDeclarations(file).stream())
                .map(CodeUnit::packageName)
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Return a summary of the given type or method.
     *
     * @param cu the code unit to get skeleton for
     * @return skeleton if available, empty otherwise
     */
    Optional<String> getSkeleton(CodeUnit cu);

    /**
     * Returns just the class signature and field declarations, without method details. Used in symbol usages lookup.
     * (Show the "header" of the class that uses the referenced symbol in a field declaration.)
     *
     * @param classUnit the class code unit to get header for
     * @return skeleton header if available, empty otherwise
     */
    Optional<String> getSkeletonHeader(CodeUnit classUnit);

    /**
     * Get skeletons for all top-level declarations in a file.
     * <p>
     * Note: The returned map contains only top-level {@link CodeUnit}s as keys. To get a skeleton
     * for a nested member, use {@link #getSkeleton(CodeUnit)} directly on the desired unit.
     *
     * @param file the file to get skeletons for
     * @return map of code units to their skeletons
     */
    default Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        final Map<CodeUnit, String> skeletons = new HashMap<>();
        for (CodeUnit symbol : getTopLevelDeclarations(file)) {
            getSkeleton(symbol).ifPresent(s -> skeletons.put(symbol, s));
        }
        return skeletons;
    }

    /**
     * Gets the source code for a given CodeUnit. Currently only supports classes and methods.
     *
     * @param codeUnit the code unit to get source for
     * @param includeComments whether to include preceding comments in the source
     * @return source code if found, empty otherwise
     */
    Optional<String> getSource(CodeUnit codeUnit, boolean includeComments);

    /**
     * Gets all source code versions for a given CodeUnit. For methods, this includes overloads.
     * For classes, this typically returns a singleton set.
     *
     * @param codeUnit the code unit to get sources for
     * @param includeComments whether to include preceding comments in the source
     * @return set of source code snippets, empty set if none found
     */
    Set<String> getSources(CodeUnit codeUnit, boolean includeComments);

    /**
     * Converts a collection of project files (typically test files) into a set of relevant
     * code units (classes or functions) for analysis or testing purposes.
     *
     * @param files the files to extract code units from
     * @return a set of code units found in the files, with inner classes coalesced
     */
    default Set<CodeUnit> testFilesToCodeUnits(Collection<ProjectFile> files) {
        var unitsInFiles = AnalyzerUtil.getTestDeclarationsWithLogging(this, files)
                .filter(cu -> cu.isClass() || cu.isFunction() || cu.isModule())
                .collect(Collectors.toSet());

        return AnalyzerUtil.coalesceNestedUnits(this, unitsInFiles);
    }

    // Heuristic for cyclomatic complexity: count keyword and symbolic decision points.
    Pattern COMPLEXITY_KEYWORDS = Pattern.compile("\\b(if|while|for|switch|case|catch)\\b");
    Pattern COMPLEXITY_OPERATORS = Pattern.compile("&&|\\|\\||\\?");

    /**
     * Computes the heuristic cyclomatic complexity for the given code unit.
     */
    default int computeCyclomaticComplexity(CodeUnit cu) {
        if (!cu.isFunction()) return 0;
        String source = getSource(cu, false).orElse("");
        int complexity = 1; // Base complexity
        Matcher keywordMatcher = COMPLEXITY_KEYWORDS.matcher(source);
        while (keywordMatcher.find()) {
            complexity++;
        }
        Matcher operatorMatcher = COMPLEXITY_OPERATORS.matcher(source);
        while (operatorMatcher.find()) {
            complexity++;
        }
        return complexity;
    }

    /**
     * Finds oversized functions, classes, and modules that are likely to carry generated-code maintainability debt.
     */
    default List<MaintainabilitySizeSmell> findLongMethodAndGodObjectSmells(ProjectFile file) {
        return findLongMethodAndGodObjectSmells(file, MaintainabilitySizeSmellWeights.defaults());
    }

    /**
     * Finds oversized functions, classes, and modules using configurable maintainability-size thresholds.
     */
    default List<MaintainabilitySizeSmell> findLongMethodAndGodObjectSmells(
            ProjectFile file, MaintainabilitySizeSmellWeights weights) {
        var findings = new ArrayList<MaintainabilitySizeSmell>();
        var visited = new HashSet<CodeUnit>();
        for (CodeUnit cu : getTopLevelDeclarations(file)) {
            collectMaintainabilitySizeSmells(cu, weights, true, visited, findings);
        }
        return findings.stream().sorted(maintainabilitySizeSmellComparator()).toList();
    }

    static Comparator<MaintainabilitySizeSmell> maintainabilitySizeSmellComparator() {
        return Comparator.comparingInt(MaintainabilitySizeSmell::score)
                .reversed()
                .thenComparing(smell -> smell.codeUnit().source().toString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(smell -> smell.codeUnit().fqName(), String.CASE_INSENSITIVE_ORDER);
    }

    private MaintainabilitySizeMetrics collectMaintainabilitySizeSmells(
            CodeUnit cu,
            MaintainabilitySizeSmellWeights weights,
            boolean topLevel,
            Set<CodeUnit> visited,
            List<MaintainabilitySizeSmell> findings) {
        if (!visited.add(cu)) {
            return MaintainabilitySizeMetrics.empty();
        }

        var range = primaryRangeOf(cu);
        boolean synthetic = cu.isSynthetic();
        int ownSpanLines = synthetic ? 0 : spanLines(range);
        int maxFunctionSpanLines = !synthetic && cu.isFunction() ? ownSpanLines : 0;
        int maxCyclomaticComplexity = !synthetic && cu.isFunction() ? computeCyclomaticComplexity(cu) : 0;
        int functionCount = !synthetic && cu.isFunction() ? 1 : 0;
        int nestedTypeCount = !synthetic && (cu.isClass() || cu.isModule()) ? 1 : 0;
        int descendantSpanLines = ownSpanLines;
        var children = getDirectChildren(cu);
        var nonSyntheticChildren =
                children.stream().filter(child -> !child.isSynthetic()).toList();

        for (CodeUnit child : children) {
            var childMetrics = collectMaintainabilitySizeSmells(child, weights, false, visited, findings);
            functionCount += childMetrics.functionCount();
            nestedTypeCount += childMetrics.nestedTypeCount();
            descendantSpanLines += childMetrics.descendantSpanLines();
            maxFunctionSpanLines = Math.max(maxFunctionSpanLines, childMetrics.maxFunctionSpanLines());
            maxCyclomaticComplexity = Math.max(maxCyclomaticComplexity, childMetrics.maxCyclomaticComplexity());
        }

        if (!synthetic && !range.isEmpty()) {
            var reasons = new ArrayList<String>();
            int score = 0;
            if (cu.isFunction()) {
                if (ownSpanLines >= weights.longMethodSpanLines()) {
                    score += ownSpanLines - weights.longMethodSpanLines() + 25;
                    reasons.add("long function spans " + ownSpanLines + " lines");
                }
                if (maxCyclomaticComplexity > weights.highComplexityThreshold()) {
                    score += (maxCyclomaticComplexity - weights.highComplexityThreshold()) * 5;
                    reasons.add("high cyclomatic complexity " + maxCyclomaticComplexity);
                }
            } else if (cu.isClass() || cu.isModule()) {
                int moduleLeewayMultiplier = isFileLevelModule(cu, topLevel) ? weights.fileModuleLeewayMultiplier() : 1;
                int godObjectSpanLines = weights.godObjectSpanLines() * moduleLeewayMultiplier;
                int godObjectDirectChildren = weights.godObjectDirectChildren() * moduleLeewayMultiplier;
                int godObjectFunctions = weights.godObjectFunctions() * moduleLeewayMultiplier;
                int helperSprawlFunctions = weights.helperSprawlFunctions() * moduleLeewayMultiplier;
                boolean responsibilityCluster = cu.isClass() || nonSyntheticChildren.size() > 1;
                if (ownSpanLines >= godObjectSpanLines) {
                    score += (ownSpanLines - godObjectSpanLines) / 4 + 20;
                    reasons.add(
                            "large " + cu.kind().name().toLowerCase(Locale.ROOT) + " spans " + ownSpanLines + " lines");
                }
                if (responsibilityCluster && nonSyntheticChildren.size() >= godObjectDirectChildren) {
                    score += (nonSyntheticChildren.size() - godObjectDirectChildren) * 2 + 15;
                    reasons.add("many direct members (" + nonSyntheticChildren.size() + ")");
                }
                if (responsibilityCluster && functionCount >= godObjectFunctions) {
                    score += (functionCount - godObjectFunctions) * 2 + 15;
                    reasons.add("many functions in one responsibility cluster (" + functionCount + ")");
                }
                if (responsibilityCluster
                        && functionCount >= helperSprawlFunctions
                        && maxFunctionSpanLines >= weights.helperSprawlWorkflowLines()) {
                    score += functionCount + maxFunctionSpanLines / 4;
                    reasons.add("helper sprawl around a " + maxFunctionSpanLines + "-line workflow");
                }
                if (maxCyclomaticComplexity > weights.highComplexityThreshold()) {
                    score += (maxCyclomaticComplexity - weights.highComplexityThreshold()) * 3;
                    reasons.add("contains high-complexity workflow (CC " + maxCyclomaticComplexity + ")");
                }
                if (score > 0 && nestedTypeCount > 1) {
                    reasons.add("nested type/module cluster (" + nestedTypeCount + ")");
                }
            }

            if (score > 0) {
                findings.add(new MaintainabilitySizeSmell(
                        cu,
                        range,
                        score,
                        ownSpanLines,
                        descendantSpanLines,
                        nonSyntheticChildren.size(),
                        functionCount,
                        nestedTypeCount,
                        maxFunctionSpanLines,
                        maxCyclomaticComplexity,
                        List.copyOf(reasons)));
            }
        }

        return new MaintainabilitySizeMetrics(
                descendantSpanLines, functionCount, nestedTypeCount, maxFunctionSpanLines, maxCyclomaticComplexity);
    }

    default boolean isFileLevelModule(CodeUnit cu, boolean topLevel) {
        return false;
    }

    private Range primaryRangeOf(CodeUnit cu) {
        return rangesOf(cu).stream()
                .filter(range -> !range.isEmpty())
                .max(Comparator.comparingInt(IAnalyzer::spanLines))
                .orElse(new Range(0, 0, 0, 0, 0));
    }

    private static int spanLines(Range range) {
        if (range.isEmpty()) {
            return 0;
        }
        return Math.max(1, range.endLine() - range.startLine() + 1);
    }

    record MaintainabilitySizeMetrics(
            int descendantSpanLines,
            int functionCount,
            int nestedTypeCount,
            int maxFunctionSpanLines,
            int maxCyclomaticComplexity) {
        static MaintainabilitySizeMetrics empty() {
            return new MaintainabilitySizeMetrics(0, 0, 0, 0, 0);
        }
    }

    /**
     * Computes the heuristic cognitive complexity for the given code unit.
     *
     * <p>Cognitive complexity starts at zero and grows with control-flow breaks and nested control flow. Language
     * analyzers should override this when they can use syntax trees; the default is unsupported.
     */
    default int computeCognitiveComplexity(CodeUnit cu) {
        return 0;
    }

    /**
     * Computes cognitive complexity for all functions in the given file. Implementations may override this to share
     * parse state across all functions in the file.
     */
    default Map<CodeUnit, Integer> computeCognitiveComplexities(ProjectFile file) {
        var complexities = new LinkedHashMap<CodeUnit, Integer>();
        var work = new ArrayDeque<>(getTopLevelDeclarations(file));
        while (!work.isEmpty()) {
            CodeUnit cu = work.pop();
            if (cu.isFunction()) {
                complexities.put(cu, computeCognitiveComplexity(cu));
            }
            work.addAll(getDirectChildren(cu));
        }
        return complexities;
    }

    /**
     * Comment density for a single declaration. Language-specific analyzers may override; default is unsupported.
     */
    default Optional<CommentDensityStats> commentDensity(CodeUnit cu) {
        return Optional.empty();
    }

    /**
     * Comment density for the first resolved declaration that supports it.
     */
    default Optional<CommentDensityStats> commentDensity(String fqName) {
        return getDefinitions(fqName).stream()
                .map(this::commentDensity)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Per-top-level declaration comment density for a file. Default is an empty list.
     */
    default List<CommentDensityStats> commentDensityByTopLevel(ProjectFile file) {
        return List.of();
    }

    /**
     * Returns suspicious exception handling sites for quality triage. The default implementation is unsupported.
     */
    default List<ExceptionHandlingSmell> findExceptionHandlingSmells(ProjectFile file, ExceptionSmellWeights weights) {
        return List.of();
    }

    /**
     * Returns suspicious low-value or brittle test assertion sites for quality triage.
     * The default implementation is unsupported.
     */
    default List<TestAssertionSmell> findTestAssertionSmells(ProjectFile file, TestAssertionWeights weights) {
        return List.of();
    }

    /**
     * Returns suspicious structural clones for quality triage. The default implementation is unsupported.
     */
    default List<CloneSmell> findStructuralCloneSmells(ProjectFile file, CloneSmellWeights weights) {
        return List.of();
    }

    /**
     * Returns suspicious structural clones for multiple files in one pass. Default implementation delegates to the
     * single-file API for compatibility.
     */
    default List<CloneSmell> findStructuralCloneSmells(List<ProjectFile> files, CloneSmellWeights weights) {
        return files.stream()
                .flatMap(file -> findStructuralCloneSmells(file, weights).stream())
                .toList();
    }
}
