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

        if (symbolNames.isEmpty() || contextManager == null) {
            return foundSymbols;
        }

        try {
            var analyzerWrapper = contextManager.getAnalyzerWrapper();
            if (!analyzerWrapper.isReady()) {
                return foundSymbols;
            }

            var analyzer = analyzerWrapper.getNonBlocking();
            if (analyzer == null || analyzer.isEmpty()) {
                return foundSymbols;
            }

            for (var symbolName : symbolNames) {
                var symbolInfo = checkSymbolExists(analyzer, symbolName);

                // Only add symbols that actually exist
                if (symbolInfo.exists() && symbolInfo.fqn() != null) {
                    foundSymbols.put(symbolName, symbolInfo.fqn());
                }
            }

        } catch (Exception e) {
            logger.warn("Error during optimized symbol lookup", e);
            // Return empty map on error instead of negative results
        }

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

        if (symbolNames.isEmpty() || contextManager == null) {
            if (completionCallback != null) {
                completionCallback.run();
            }
            return;
        }

        try {
            var analyzerWrapper = contextManager.getAnalyzerWrapper();
            if (!analyzerWrapper.isReady()) {
                if (completionCallback != null) {
                    completionCallback.run();
                }
                return;
            }

            var analyzer = analyzerWrapper.getNonBlocking();
            if (analyzer == null || analyzer.isEmpty()) {
                if (completionCallback != null) {
                    completionCallback.run();
                }
                return;
            }

            // Process each symbol individually and send result immediately
            for (var symbolName : symbolNames) {
                try {
                    var symbolInfo = checkSymbolExists(analyzer, symbolName);

                    // Send result immediately (both found and not found symbols)
                    if (symbolInfo.exists() && symbolInfo.fqn() != null) {
                        resultCallback.accept(symbolName, symbolInfo.fqn());
                    } else {
                        // Send null fqn for non-existent symbols so frontend knows they don't exist
                        resultCallback.accept(symbolName, null);
                    }
                } catch (Exception e) {
                    logger.warn("Error processing symbol '{}' in streaming lookup", symbolName, e);
                    // Send null result for failed lookups
                    resultCallback.accept(symbolName, null);
                }
            }

            // Streaming lookup completed silently

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

        try {
            // First try exact FQN match
            var definition = analyzer.getDefinition(trimmed);
            if (definition.isPresent() && definition.get().isClass()) {
                return new SymbolInfo(true, definition.get().fqName());
            }

            // Then try pattern search
            var searchResults = analyzer.searchDefinitions(trimmed);

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

                // Try class matching first (handles multiple classes with same name)
                var classMatches = findAllClassMatches(trimmed, projectSourceResults);
                if (!classMatches.isEmpty()) {
                    var commaSeparatedFqns =
                            classMatches.stream().map(CodeUnit::fqName).sorted().collect(Collectors.joining(","));
                    return new SymbolInfo(true, commaSeparatedFqns);
                }

                // Only return true for class symbols, not methods or fields
                return new SymbolInfo(false, null);
            }

            return new SymbolInfo(false, null);

        } catch (Exception e) {
            logger.trace("Error checking symbol existence for '{}': {}", trimmed, e.getMessage());
            return new SymbolInfo(false, null);
        }
    }

    /** Find the best match from search results, prioritizing exact matches over substring matches. */
    protected static CodeUnit findBestMatch(String searchTerm, List<CodeUnit> searchResults) {
        // Priority 1: Exact simple name match (class name without package)
        var exactSimpleNameMatches = searchResults.stream()
                .filter(cu -> getSimpleName(cu.fqName()).equals(searchTerm))
                .toList();

        if (!exactSimpleNameMatches.isEmpty()) {
            // If multiple exact matches, prefer the shortest FQN (more specific/direct)
            return exactSimpleNameMatches.stream()
                    .min(Comparator.comparing(cu -> cu.fqName().length()))
                    .orElseThrow(); // Safe since we check isEmpty() above
        }

        // Priority 2: FQN ends with the search term (e.g., searching "TreeSitterAnalyzer" matches
        // "io.foo.TreeSitterAnalyzer")
        var endsWithMatches = searchResults.stream()
                .filter(cu ->
                        cu.fqName().endsWith("." + searchTerm) || cu.fqName().equals(searchTerm))
                .toList();

        if (!endsWithMatches.isEmpty()) {
            return endsWithMatches.stream()
                    .min(Comparator.comparing(cu -> cu.fqName().length()))
                    .orElseThrow(); // Safe since we check isEmpty() above
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
                return reasonableMatches.stream()
                        .min(Comparator.comparing(cu -> cu.fqName().length()))
                        .orElseThrow();
            }
        }

        // If no reasonable matches, just return the shortest overall match
        return searchResults.stream()
                .min(Comparator.comparing(cu -> cu.fqName().length()))
                .orElseThrow(); // Safe since the caller checks searchResults is not empty
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

    /** Find all classes with exact simple name match for the given search term. */
    private static List<CodeUnit> findAllClassMatches(String searchTerm, List<CodeUnit> searchResults) {
        // Find all classes with exact simple name match
        return searchResults.stream()
                .filter(cu -> cu.isClass()) // Only classes, not methods or fields
                .filter(cu -> getSimpleName(cu.fqName()).equals(searchTerm))
                .toList();
    }
}
