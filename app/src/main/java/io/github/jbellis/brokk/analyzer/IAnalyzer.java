package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jetbrains.annotations.Nullable;

public interface IAnalyzer {
    /** Record representing a code unit relevance result with a code unit and its score. */
    record FileRelevance(ProjectFile file, double score) implements Comparable<FileRelevance> {
        @Override
        public int compareTo(FileRelevance other) {
            int scoreComparison = Double.compare(other.score, this.score);
            return scoreComparison != 0 ? scoreComparison : this.file.absPath().compareTo(other.file.absPath());
        }
    }

    // Basics
    default boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    default <T extends CapabilityProvider> Optional<T> as(Class<T> capability) {
        return capability.isInstance(this) ? Optional.of(capability.cast(this)) : Optional.empty();
    }

    default List<CodeUnit> topLevelCodeUnitsOf(ProjectFile file) {
        throw new UnsupportedOperationException();
    }
    /** Returns the set of languages this analyzer understands. */
    default Set<Language> languages() {
        return Set.of();
    }

    /**
     * Update the Analyzer for create/modify/delete activity against `changedFiles`. This is O(M) in the number of
     * changed files.
     */
    default IAnalyzer update(Set<ProjectFile> changedFiles) {
        // should always be supported; UOE here is for convenience in mocking
        throw new UnsupportedOperationException();
    }

    /**
     * Scan for changes across all files in the Analyzer. This involves hashing each file so it is O(N) in the total
     * number of files and relatively heavyweight.
     */
    default IAnalyzer update() {
        // should always be supported; UOE here is for convenience in mocking
        throw new UnsupportedOperationException();
    }

    // Summarization

    /** The project this analyzer targets */
    default IProject getProject() {
        throw new UnsupportedOperationException();
    }

    default List<CodeUnit> getMembersInClass(String fqClass) {
        throw new UnsupportedOperationException();
    }

    /** All top-level declarations in the project. */
    default List<CodeUnit> getAllDeclarations() {
        throw new UnsupportedOperationException();
    }

    /** The metrics around the codebase as determined by the analyzer. */
    default CodeBaseMetrics getMetrics() {
        final var declarations = getAllDeclarations();
        final var codeUnits = declarations.stream().map(CodeUnit::source).distinct();
        return new CodeBaseMetrics((int) codeUnits.count(), declarations.size());
    }

    /** Gets top-level declarations in a given file. */
    default Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        throw new UnsupportedOperationException();
    }

    default Optional<ProjectFile> getFileFor(String fqName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Finds a single CodeUnit definition matching the exact symbol name.
     *
     * @param fqName The exact, case-sensitive FQ name of the class, method, or field. Symbols are checked in that
     *     order, so if you have a field and a method with the same name, the method will be returned.
     * @return An Optional containing the CodeUnit if exactly one match is found, otherwise empty.
     */
    default Optional<CodeUnit> getDefinition(String fqName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Checks if a definition is available for the given fully-qualified name without creating the CodeUnit object. This
     * is a fast way to test if {@link #getDefinition(String)} would return a non-empty Optional.
     *
     * @param fqName The exact, case-sensitive FQ name of the class, method, or field
     * @return true if a definition is available, false otherwise
     */
    default boolean isDefinitionAvailable(String fqName) {
        return getDefinition(fqName).isPresent();
    }

    /**
     * Gets the source code for the entire given class. Implementations may return Optional.empty() when the analyzer
     * cannot provide source text for the requested FQCN.
     */
    default Optional<String> getClassSource(String fqcn) {
        throw new UnsupportedOperationException();
    }

    /**
     * Searches for a (Java) regular expression in the defined identifiers. We manipulate the provided pattern as
     * follows: val preparedPattern = if pattern.contains(".*") then pattern else s".*${Regex.quote(pattern)}.*"val
     * ciPattern = "(?i)" + preparedPattern // case-insensitive substring match
     */
    default List<CodeUnit> searchDefinitions(String pattern) {
        // Validate pattern
        if (pattern.isEmpty()) {
            return List.of();
        }

        // Prepare case-insensitive regex pattern
        var preparedPattern = pattern.contains(".*") ? pattern : ".*" + Pattern.quote(pattern) + ".*";
        var ciPattern = "(?i)" + preparedPattern;

        // Try to compile the pattern
        Pattern compiledPattern;
        try {
            compiledPattern = Pattern.compile(ciPattern);
        } catch (PatternSyntaxException e) {
            // Fallback to simple case-insensitive substring matching
            var fallbackPattern = pattern.toLowerCase(Locale.ROOT);
            return searchDefinitionsImpl(pattern, fallbackPattern, null);
        }

        // Delegate to implementation-specific method
        return searchDefinitionsImpl(pattern, null, compiledPattern);
    }

    /**
     * Provides a search facility that is based on auto-complete logic based on (non-regex) user-input. By default, this
     * hands over to {@link IAnalyzer#searchDefinitions(String)} surrounded by wildcards.
     *
     * @param query the search query
     * @return a list of candidates where their fully qualified names may match the query.
     */
    default List<CodeUnit> autocompleteDefinitions(String query) {
        if (query.isEmpty()) {
            return List.of();
        }

        // Base: current behavior (case-insensitive substring via searchDefinitions)
        List<CodeUnit> baseResults = searchDefinitions(".*" + query + ".*");

        // Fuzzy: if short query, over-approximate by inserting ".*" between characters
        List<CodeUnit> fuzzyResults = List.of();
        if (query.length() < 5) {
            StringBuilder sb = new StringBuilder("(?i)");
            sb.append(".*");
            for (int i = 0; i < query.length(); i++) {
                sb.append(Pattern.quote(String.valueOf(query.charAt(i))));
                if (i < query.length() - 1) sb.append(".*");
            }
            sb.append(".*");
            fuzzyResults = searchDefinitions(sb.toString());
        }

        if (fuzzyResults.isEmpty()) {
            return baseResults;
        }

        // Deduplicate by fqName, preserve insertion order (base first, then fuzzy)
        LinkedHashMap<String, CodeUnit> byFqName = new LinkedHashMap<>();
        for (CodeUnit cu : baseResults) byFqName.put(cu.fqName(), cu);
        for (CodeUnit cu : fuzzyResults) byFqName.putIfAbsent(cu.fqName(), cu);

        return new ArrayList<>(byFqName.values());
    }

    /**
     * Implementation-specific search method called by the default searchDefinitions. Subclasses should implement this
     * method to provide their specific search logic.
     *
     * <p><b>Performance Warning:</b> The default implementation iterates over all declarations in the project, which
     * can be very slow for large codebases. Production-ready implementations should override this method with a more
     * efficient approach, such as using an index.
     *
     * @param originalPattern The original search pattern provided by the user
     * @param fallbackPattern The lowercase fallback pattern (null if not using fallback)
     * @param compiledPattern The compiled regex pattern (null if using fallback)
     * @return List of matching CodeUnits
     */
    default List<CodeUnit> searchDefinitionsImpl(
            String originalPattern, @Nullable String fallbackPattern, @Nullable Pattern compiledPattern) {
        // Default implementation using getAllDeclarations
        if (fallbackPattern != null) {
            return getAllDeclarations().stream()
                    .filter(cu -> cu.fqName().toLowerCase(Locale.ROOT).contains(fallbackPattern))
                    .toList();
        } else if (compiledPattern != null) {
            return getAllDeclarations().stream()
                    .filter(cu -> compiledPattern.matcher(cu.fqName()).find())
                    .toList();
        } else {
            return getAllDeclarations().stream()
                    .filter(cu -> cu.fqName().toLowerCase(Locale.ROOT).contains(originalPattern))
                    .toList();
        }
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
     *
     * See getSymbols(java.util.Set) for how this method is used in symbol collection.
     */
    default List<CodeUnit> directChildren(CodeUnit cu) {
        return List.of();
    }

    /** Extracts the unqualified symbol name from a fully-qualified name and adds it to the output set. */
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
            work.addAll(directChildren(cu));
        }
        return symbols;
    }

    /**
     * Extracts the class/module/type name from a method/member reference like "MyClass.myMethod". This is a heuristic
     * method that may produce false positives/negatives.
     *
     * @param reference The reference string to analyze (e.g., "MyClass.myMethod", "package::Class::method")
     * @return Optional containing the extracted class/module name, empty if none found
     */
    default Optional<String> extractClassName(String reference) {
        return Optional.empty();
    }

    /** @return the import snippets for the given file where other code units may be referred to by. */
    default List<String> importStatementsOf(ProjectFile file) {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the nearest enclosing code unit of the range within the file. Returns null if none exists or range is
     *     invalid.
     */
    default Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range) {
        throw new UnsupportedOperationException();
    }

    record Range(int startByte, int endByte, int startLine, int endLine, int commentStartByte) {
        public boolean isEmpty() {
            return startLine == endLine && startByte == endByte;
        }

        public boolean isContainedWithin(Range other) {
            return startByte >= other.startByte && endByte <= other.endByte;
        }
    }
}
