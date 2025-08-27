package io.github.jbellis.brokk.gui.mop;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
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

        logger.debug("Starting lookup for {} symbols", symbolNames.size());

        if (symbolNames.isEmpty()) {
            return results;
        }

        if (contextManager == null) {
            logger.trace("No context manager available for symbol lookup");
            symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false, null)));
            return results;
        }

        var project = contextManager.getProject();
        logger.trace("Using {} with project at {}", contextManager.getClass().getSimpleName(), project.getRoot());

        try {
            var analyzerWrapper = contextManager.getAnalyzerWrapper();
            logger.trace("AnalyzerWrapper ready: {}", analyzerWrapper.isReady());

            if (!analyzerWrapper.isReady()) {
                logger.trace("Analyzer not ready");
                symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false, null)));
                return results;
            }

            var analyzer = analyzerWrapper.getNonBlocking();

            if (analyzer == null || analyzer.isEmpty()) {
                logger.trace("No analyzer available for symbol lookup");
                symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false, null)));
                return results;
            }

            logger.trace("Using analyzer: {}", analyzer.getClass().getSimpleName());

            for (var symbolName : symbolNames) {
                var symbolInfo = checkSymbolExists(analyzer, symbolName);
                logger.debug("Symbol '{}' exists: {}", symbolName, symbolInfo.exists());
                results.put(symbolName, new SymbolLookupResult(symbolName, symbolInfo.exists(), symbolInfo.fqn()));
            }

        } catch (Exception e) {
            logger.warn("Error during symbol lookup", e);
            symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false, null)));
        }

        logger.debug("Symbol lookup completed with {} results", results.size());
        return results;
    }

    /**
     * Optimized symbol lookup that returns only existing symbols as a lean Map<String, String>. This reduces payload
     * size by ~90% by excluding non-existent symbols and removing redundant data.
     */
    public static Map<String, String> lookupSymbolsOptimized(
            Set<String> symbolNames, @Nullable IContextManager contextManager) {
        var foundSymbols = new HashMap<String, String>();

        logger.debug("Starting optimized lookup for {} symbols", symbolNames.size());

        if (symbolNames.isEmpty() || contextManager == null) {
            return foundSymbols;
        }

        var project = contextManager.getProject();
        logger.trace("Using {} with project at {}", contextManager.getClass().getSimpleName(), project.getRoot());

        try {
            var analyzerWrapper = contextManager.getAnalyzerWrapper();
            if (!analyzerWrapper.isReady()) {
                logger.trace("Analyzer not ready");
                return foundSymbols;
            }

            var analyzer = analyzerWrapper.getNonBlocking();
            if (analyzer == null || analyzer.isEmpty()) {
                logger.trace("No analyzer available for symbol lookup");
                return foundSymbols;
            }

            logger.trace("Using analyzer: {}", analyzer.getClass().getSimpleName());

            for (var symbolName : symbolNames) {
                var symbolInfo = checkSymbolExists(analyzer, symbolName);
                logger.debug("Symbol '{}' exists: {}", symbolName, symbolInfo.exists());

                // Only add symbols that actually exist
                if (symbolInfo.exists() && symbolInfo.fqn() != null) {
                    foundSymbols.put(symbolName, symbolInfo.fqn());
                }
            }

        } catch (Exception e) {
            logger.warn("Error during optimized symbol lookup", e);
            // Return empty map on error instead of negative results
        }

        logger.debug(
                "Optimized lookup completed: {} found out of {} requested", foundSymbols.size(), symbolNames.size());
        return foundSymbols;
    }

    /**
     * Streaming symbol lookup that sends results incrementally as they become available. This provides better perceived
     * performance by not waiting for the slowest symbol in a batch.
     *
     * @param symbolNames Set of symbol names to lookup
     * @param contextManager Context manager for accessing analyzer
     * @param resultCallback Called for each symbol result (symbolName, fqn). fqn is null for non-existent symbols
     * @param completionCallback Called when all symbols have been processed
     */
    public static void lookupSymbolsStreaming(
            Set<String> symbolNames,
            @Nullable IContextManager contextManager,
            BiConsumer<String, String> resultCallback,
            @Nullable Runnable completionCallback) {

        logger.debug("Starting streaming lookup for {} symbols", symbolNames.size());

        if (symbolNames.isEmpty() || contextManager == null) {
            if (completionCallback != null) {
                completionCallback.run();
            }
            return;
        }

        var project = contextManager.getProject();
        logger.trace("Using {} with project at {}", contextManager.getClass().getSimpleName(), project.getRoot());

        try {
            var analyzerWrapper = contextManager.getAnalyzerWrapper();
            if (!analyzerWrapper.isReady()) {
                logger.trace("Analyzer not ready");
                if (completionCallback != null) {
                    completionCallback.run();
                }
                return;
            }

            var analyzer = analyzerWrapper.getNonBlocking();
            if (analyzer == null || analyzer.isEmpty()) {
                logger.trace("No analyzer available for symbol lookup");
                if (completionCallback != null) {
                    completionCallback.run();
                }
                return;
            }

            logger.trace("Using analyzer: {}", analyzer.getClass().getSimpleName());

            int processedCount = 0;
            int foundCount = 0;

            // Process each symbol individually and send result immediately
            for (var symbolName : symbolNames) {
                try {
                    var symbolInfo = checkSymbolExists(analyzer, symbolName);
                    logger.debug("Symbol '{}' exists: {}", symbolName, symbolInfo.exists());

                    // Send result immediately (both found and not found symbols)
                    if (symbolInfo.exists() && symbolInfo.fqn() != null) {
                        resultCallback.accept(symbolName, symbolInfo.fqn());
                        foundCount++;
                    } else {
                        // Send null fqn for non-existent symbols so frontend knows they don't exist
                        resultCallback.accept(symbolName, null);
                    }

                    processedCount++;
                } catch (Exception e) {
                    logger.warn("Error processing symbol '{}' in streaming lookup", symbolName, e);
                    // Send null result for failed lookups
                    resultCallback.accept(symbolName, null);
                    processedCount++;
                }
            }

            logger.debug("Streaming lookup completed: {} found out of {} processed", foundCount, processedCount);

        } catch (Exception e) {
            logger.warn("Error during streaming symbol lookup", e);
        }

        // Signal completion
        if (completionCallback != null) {
            completionCallback.run();
        }
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
            logger.debug("getDefinition('{}') found: {}", trimmed, definition.isPresent());
            if (definition.isPresent()) {
                logger.debug(
                        "Found exact definition for '{}': {}",
                        trimmed,
                        definition.get().fqName());
                return new SymbolInfo(true, definition.get().fqName());
            }

            // Then try pattern search
            var searchResults = analyzer.searchDefinitions(trimmed);
            logger.debug("searchDefinitions('{}') returned {} results", trimmed, searchResults.size());

            if (!searchResults.isEmpty()) {
                logger.trace(
                        "Search results for '{}': {}",
                        trimmed,
                        searchResults.stream().limit(5).map(cu -> cu.fqName()).toList());

                // For Java, return all exact class matches; for other languages, find best match
                var projectSourceResults = filterToProjectSources(searchResults);
                if (projectSourceResults.isEmpty()) {
                    logger.trace("No project source matches found for '{}'", trimmed);
                    return new SymbolInfo(false, null);
                }

                if (isJavaProject()) {
                    var javaMatches = findAllJavaClassMatches(trimmed, projectSourceResults);
                    if (!javaMatches.isEmpty()) {
                        var commaSeparatedFqns = javaMatches.stream()
                                .map(CodeUnit::fqName)
                                .sorted()
                                .collect(Collectors.joining(","));
                        logger.trace("Java class matches for '{}': {}", trimmed, commaSeparatedFqns);
                        return new SymbolInfo(true, commaSeparatedFqns);
                    }
                } else {
                    // For non-Java languages, use the existing best match logic
                    var bestMatch = findBestMatch(trimmed, projectSourceResults);
                    logger.trace("Best match for '{}': {}", trimmed, bestMatch.fqName());
                    return new SymbolInfo(true, bestMatch.fqName());
                }
            }

            return new SymbolInfo(false, null);

        } catch (Exception e) {
            logger.trace("Error checking symbol existence for '{}': {}", trimmed, e.getMessage());
            return new SymbolInfo(false, null);
        }
    }

    /** Find the best match from search results, prioritizing exact matches over substring matches. */
    private static CodeUnit findBestMatch(String searchTerm, List<CodeUnit> searchResults) {
        logger.trace("Finding best match for '{}' among {} results", searchTerm, searchResults.size());

        // Priority 1: Exact simple name match (class name without package)
        var exactSimpleNameMatches = searchResults.stream()
                .filter(cu -> getSimpleName(cu.fqName()).equals(searchTerm))
                .toList();

        if (!exactSimpleNameMatches.isEmpty()) {
            logger.trace("Found {} exact simple name matches for '{}'", exactSimpleNameMatches.size(), searchTerm);
            // If multiple exact matches, prefer the shortest FQN (more specific/direct)
            var result = exactSimpleNameMatches.stream()
                    .min(Comparator.comparing(cu -> cu.fqName().length()))
                    .orElseThrow(); // Safe since we check isEmpty() above
            logger.trace("Selected exact match: '{}'", result.fqName());
            return result;
        }

        // Priority 2: FQN ends with the search term (e.g., searching "TreeSitterAnalyzer" matches
        // "io.foo.TreeSitterAnalyzer")
        var endsWithMatches = searchResults.stream()
                .filter(cu ->
                        cu.fqName().endsWith("." + searchTerm) || cu.fqName().equals(searchTerm))
                .toList();

        if (!endsWithMatches.isEmpty()) {
            logger.trace("Found {} 'ends with' matches for '{}'", endsWithMatches.size(), searchTerm);
            var result = endsWithMatches.stream()
                    .min(Comparator.comparing(cu -> cu.fqName().length()))
                    .orElseThrow(); // Safe since we check isEmpty() above
            logger.trace("Selected 'ends with' match: '{}'", result.fqName());
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
                logger.trace(
                        "Using selective fallback 'contains' matching for '{}' with {} reasonable matches",
                        searchTerm,
                        reasonableMatches.size());
                var result = reasonableMatches.stream()
                        .min(Comparator.comparing(cu -> cu.fqName().length()))
                        .orElseThrow();
                logger.trace("Selected selective fallback match: '{}'", result.fqName());
                return result;
            }
        }

        // If no reasonable matches, just return the shortest overall match but log a warning
        logger.trace("No reasonable matches for '{}', using unrestricted fallback", searchTerm);
        var result = searchResults.stream()
                .min(Comparator.comparing(cu -> cu.fqName().length()))
                .orElseThrow(); // Safe since the caller checks searchResults is not empty
        logger.trace("Selected unrestricted fallback match: '{}'", result.fqName());
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
        logger.trace("Finding all Java class matches for '{}' among {} results", searchTerm, searchResults.size());

        // For Java, find all classes with exact simple name match (ignoring case for class names)
        var exactMatches = searchResults.stream()
                .filter(cu -> cu.isClass()) // Only classes, not methods or fields
                .filter(cu -> getSimpleName(cu.fqName()).equals(searchTerm))
                .toList();

        logger.trace("Found {} exact Java class matches for '{}'", exactMatches.size(), searchTerm);
        exactMatches.forEach(cu -> logger.trace("  Match: {}", cu.fqName()));

        return exactMatches;
    }
}
