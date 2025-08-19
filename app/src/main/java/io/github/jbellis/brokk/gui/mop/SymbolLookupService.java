package io.github.jbellis.brokk.gui.mop;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolLookupService {
    private static final Logger logger = LoggerFactory.getLogger(SymbolLookupService.class);

    public record SymbolLookupResult(String symbolName, boolean exists, @Nullable String fqn) {}

    private record SymbolInfo(boolean exists, @Nullable String fqn) {}

    public static Map<String, SymbolLookupResult> lookupSymbols(
            Set<String> symbolNames, @Nullable IContextManager contextManager) {
        var results = new HashMap<String, SymbolLookupResult>();

        logger.debug("Starting lookup for {} symbols: {}", symbolNames.size(), symbolNames);

        if (symbolNames.isEmpty()) {
            logger.debug("No symbols to lookup, returning empty results");
            return results;
        }

        if (contextManager == null) {
            logger.debug("No context manager available for symbol lookup");
            symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false, null)));
            return results;
        }

        var project = contextManager.getProject();
        logger.debug(
                "ContextManager type: {}, project: {} at {}",
                contextManager.getClass().getSimpleName(),
                project.getClass().getSimpleName(),
                project.getRoot());

        try {
            logger.debug("Getting analyzer wrapper...");
            var analyzerWrapper = contextManager.getAnalyzerWrapper();
            logger.debug(
                    "AnalyzerWrapper type: {}, ready: {}",
                    analyzerWrapper.getClass().getSimpleName(),
                    analyzerWrapper.isReady());

            if (!analyzerWrapper.isReady()) {
                logger.debug("Analyzer not ready, returning false for all symbols");
                symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false, null)));
                return results;
            }

            logger.debug("Getting non-blocking analyzer...");
            var analyzer = analyzerWrapper.getNonBlocking();

            logger.debug(
                    "Analyzer available: {}, empty: {}",
                    analyzer != null,
                    analyzer != null ? analyzer.isEmpty() : "N/A");

            if (analyzer == null || analyzer.isEmpty()) {
                logger.debug("No analyzer available for symbol lookup");
                symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false, null)));
                return results;
            }

            logger.debug("Using analyzer: {}", analyzer.getClass().getSimpleName());

            for (var symbolName : symbolNames) {
                logger.debug("Checking symbol: '{}'", symbolName);
                var symbolInfo = checkSymbolExists(analyzer, symbolName);
                logger.debug("Symbol '{}' exists: {}, FQN: {}", symbolName, symbolInfo.exists(), symbolInfo.fqn());
                results.put(symbolName, new SymbolLookupResult(symbolName, symbolInfo.exists(), symbolInfo.fqn()));
            }

        } catch (Exception e) {
            logger.warn("Error during symbol lookup", e);
            symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false, null)));
        }

        logger.debug("Symbol lookup completed with {} results", results.size());
        return results;
    }

    private static SymbolInfo checkSymbolExists(IAnalyzer analyzer, String symbolName) {
        if (symbolName.trim().isEmpty()) {
            return new SymbolInfo(false, null);
        }

        var trimmed = symbolName.trim();
        logger.debug("Checking symbol existence: '{}'", trimmed);

        try {
            // First try exact FQN match
            var definition = analyzer.getDefinition(trimmed);
            logger.debug(
                    "getDefinition('{}') result: {}", trimmed, definition.isPresent() ? definition.get() : "not found");
            if (definition.isPresent()) {
                return new SymbolInfo(true, definition.get().fqName());
            }

            // Then try pattern search
            logger.debug("Trying searchDefinitions for '{}'", trimmed);
            var searchResults = analyzer.searchDefinitions(trimmed);
            logger.debug("searchDefinitions('{}') returned {} results", trimmed, searchResults.size());

            // Enhanced debug logging for TSParser specifically
            if ("TSParser".equals(trimmed)) {
                logger.info("=== ENHANCED DEBUG FOR TSParser ===");
                logger.info("Found {} search results for 'TSParser':", searchResults.size());
                for (int i = 0; i < searchResults.size(); i++) {
                    var result = searchResults.get(i);
                    logger.info(
                            "  {}. FQN: '{}', Simple: '{}'", i + 1, result.fqName(), getSimpleName(result.fqName()));
                }
                logger.info("=== END TSParser DEBUG ===");
            }

            if (!searchResults.isEmpty()) {
                logger.debug(
                        "Search results for '{}': {}",
                        trimmed,
                        searchResults.stream().limit(5).map(cu -> cu.fqName()).toList());

                // For Java, return all exact class matches; for other languages, find best match
                var projectSourceResults = filterToProjectSources(searchResults);
                if (projectSourceResults.isEmpty()) {
                    logger.debug("No project source matches found for '{}'", trimmed);
                    return new SymbolInfo(false, null);
                }

                if (isJavaProject()) {
                    var javaMatches = findAllJavaClassMatches(trimmed, projectSourceResults);
                    if (!javaMatches.isEmpty()) {
                        var commaSeparatedFqns = javaMatches.stream()
                                .map(CodeUnit::fqName)
                                .sorted()
                                .collect(Collectors.joining(","));
                        logger.debug("Java class matches for '{}': {}", trimmed, commaSeparatedFqns);
                        return new SymbolInfo(true, commaSeparatedFqns);
                    }
                } else {
                    // For non-Java languages, use the existing best match logic
                    var bestMatch = findBestMatch(trimmed, projectSourceResults);
                    logger.debug("Best match for '{}': {}", trimmed, bestMatch.fqName());
                    return new SymbolInfo(true, bestMatch.fqName());
                }
            }

            // Debug: Sample some FQN names from analyzer to see what's available
            var allDeclarations = analyzer.getAllDeclarations();
            logger.debug("Total declarations in analyzer: {}", allDeclarations.size());
            if (!allDeclarations.isEmpty()) {
                var sampleFqNames = allDeclarations.stream()
                        .limit(10)
                        .map(cu -> cu.fqName())
                        .toList();
                logger.debug("Sample FQN names in analyzer: {}", sampleFqNames);

                // Specifically check for TreeSitterAnalyzer
                var treeSitterMatches = allDeclarations.stream()
                        .filter(cu -> cu.fqName().contains("TreeSitter"))
                        .map(cu -> cu.fqName())
                        .toList();
                logger.debug("FQN names containing 'TreeSitter': {}", treeSitterMatches);
            }

            return new SymbolInfo(false, null);

        } catch (Exception e) {
            logger.debug("Error checking symbol existence for '{}': {}", trimmed, e.getMessage());
            return new SymbolInfo(false, null);
        }
    }

    /** Find the best match from search results, prioritizing exact matches over substring matches. */
    private static CodeUnit findBestMatch(String searchTerm, List<CodeUnit> searchResults) {
        logger.debug("Finding best match for '{}' among {} results", searchTerm, searchResults.size());

        // Priority 1: Exact simple name match (class name without package)
        var exactSimpleNameMatches = searchResults.stream()
                .filter(cu -> getSimpleName(cu.fqName()).equals(searchTerm))
                .toList();

        if (!exactSimpleNameMatches.isEmpty()) {
            logger.debug("Found {} exact simple name matches for '{}'", exactSimpleNameMatches.size(), searchTerm);
            // If multiple exact matches, prefer the shortest FQN (more specific/direct)
            var result = exactSimpleNameMatches.stream()
                    .min(Comparator.comparing(cu -> cu.fqName().length()))
                    .orElseThrow(); // Safe since we check isEmpty() above
            logger.debug("Selected exact match: '{}'", result.fqName());
            return result;
        }

        // Priority 2: FQN ends with the search term (e.g., searching "TreeSitterAnalyzer" matches
        // "io.foo.TreeSitterAnalyzer")
        var endsWithMatches = searchResults.stream()
                .filter(cu ->
                        cu.fqName().endsWith("." + searchTerm) || cu.fqName().equals(searchTerm))
                .toList();

        if (!endsWithMatches.isEmpty()) {
            logger.debug("Found {} 'ends with' matches for '{}'", endsWithMatches.size(), searchTerm);
            var result = endsWithMatches.stream()
                    .min(Comparator.comparing(cu -> cu.fqName().length()))
                    .orElseThrow(); // Safe since we check isEmpty() above
            logger.debug("Selected 'ends with' match: '{}'", result.fqName());
            return result;
        }

        // Priority 3: Contains the search term - but be more restrictive to avoid misleading matches
        // Only use fallback if the search term is reasonably short (likely a real symbol name)
        // and the match seems reasonable (not drastically longer than the search term)
        if (searchTerm.length() >= 3) { // Only for reasonable length search terms
            var reasonableMatches = searchResults.stream()
                    .filter(cu -> {
                        var fqn = cu.fqName();
                        // Reject matches where the search term is much shorter than the class name
                        // This prevents "TSParser" from matching "EditBlockConflictsParser"
                        var simpleName = getSimpleName(fqn);
                        return simpleName.length() <= searchTerm.length() * 3; // Allow up to 3x longer
                    })
                    .toList();

            if (!reasonableMatches.isEmpty()) {
                logger.debug(
                        "Using selective fallback 'contains' matching for '{}' with {} reasonable matches",
                        searchTerm,
                        reasonableMatches.size());
                var result = reasonableMatches.stream()
                        .min(Comparator.comparing(cu -> cu.fqName().length()))
                        .orElseThrow();
                logger.debug("Selected selective fallback match: '{}'", result.fqName());
                return result;
            }
        }

        // If no reasonable matches, just return the shortest overall match but log a warning
        logger.debug("No reasonable matches for '{}', using unrestricted fallback", searchTerm);
        var result = searchResults.stream()
                .min(Comparator.comparing(cu -> cu.fqName().length()))
                .orElseThrow(); // Safe since the caller checks searchResults is not empty
        logger.debug("Selected unrestricted fallback match: '{}'", result.fqName());
        return result;
    }

    /** Extract the simple class name from a fully qualified name. */
    private static String getSimpleName(String fqName) {
        int lastDot = fqName.lastIndexOf('.');
        return lastDot >= 0 ? fqName.substring(lastDot + 1) : fqName;
    }

    /** Filter search results to only include symbols from project sources, not JAR dependencies. */
    private static List<CodeUnit> filterToProjectSources(List<CodeUnit> searchResults) {
        // For now, return all results. In the future, we can add logic to filter out
        // symbols from JAR files by checking if their ProjectFile path is within the project root.
        // This requires access to the project root path, which we don't have in this context yet.
        return searchResults;
    }

    /** Check if the current project is a Java project based on analyzer type or file extensions. */
    private static boolean isJavaProject() {
        // For now, assume Java project. In the future, this can be determined by:
        // - Checking analyzer type (JavaAnalyzer, JavaTreeSitterAnalyzer)
        // - Checking for presence of Java files in project
        // - Project configuration
        return true;
    }

    /** Find all Java classes with exact simple name match for the given search term. */
    private static List<CodeUnit> findAllJavaClassMatches(String searchTerm, List<CodeUnit> searchResults) {
        logger.debug("Finding all Java class matches for '{}' among {} results", searchTerm, searchResults.size());

        // For Java, find all classes with exact simple name match (ignoring case for class names)
        var exactMatches = searchResults.stream()
                .filter(cu -> cu.isClass()) // Only classes, not methods or fields
                .filter(cu -> getSimpleName(cu.fqName()).equals(searchTerm))
                .toList();

        logger.debug("Found {} exact Java class matches for '{}'", exactMatches.size(), searchTerm);
        exactMatches.forEach(cu -> logger.debug("  Match: {}", cu.fqName()));

        return exactMatches;
    }
}
