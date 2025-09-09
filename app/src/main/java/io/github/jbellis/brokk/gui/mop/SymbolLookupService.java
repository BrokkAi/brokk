package io.github.jbellis.brokk.gui.mop;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.ClassNameExtractor;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolLookupService {
    private static final Logger logger = LoggerFactory.getLogger(SymbolLookupService.class);

    /** Structured result for symbol lookup with highlight range support */
    public record SymbolLookupResult(
            @Nullable String fqn,
            List<HighlightRange> highlightRanges,
            boolean isPartialMatch,
            @Nullable String originalText) {
        public static SymbolLookupResult notFound(String originalText) {
            return new SymbolLookupResult(null, List.of(), false, originalText);
        }

        public static SymbolLookupResult exactMatch(String fqn, String originalText) {
            return new SymbolLookupResult(
                    fqn, List.of(new HighlightRange(0, originalText.length())), false, originalText);
        }

        public static SymbolLookupResult partialMatch(String fqn, String originalText, String extractedClassName) {
            // Calculate highlight range for the extracted class name within original text
            int classStart = originalText.indexOf(extractedClassName);
            if (classStart >= 0) {
                int classEnd = classStart + extractedClassName.length();
                return new SymbolLookupResult(
                        fqn, List.of(new HighlightRange(classStart, classEnd)), true, originalText);
            }
            // Fallback: highlight entire text if we can't find the class name
            return new SymbolLookupResult(
                    fqn, List.of(new HighlightRange(0, originalText.length())), true, originalText);
        }
    }

    /** Character range for highlighting [start, end) */
    public record HighlightRange(int start, int end) {}

    /**
     * Streaming symbol lookup that sends results incrementally as they become available. This provides better perceived
     * performance by not waiting for the slowest symbol in a batch.
     *
     * @param symbolNames Set of symbol names to lookup
     * @param contextManager Context manager for accessing analyzer
     * @param resultCallback Called for each symbol result (symbolName, SymbolLookupResult). Result contains fqn,
     *     highlight ranges, and partial match info
     * @param completionCallback Called when all symbols have been processed
     */
    public static void lookupSymbols(
            Set<String> symbolNames,
            @Nullable IContextManager contextManager,
            BiConsumer<String, SymbolLookupResult> resultCallback,
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
                    var symbolResult = checkSymbolExists(analyzer, symbolName);

                    // Send result immediately (always send the SymbolLookupResult)
                    resultCallback.accept(symbolName, symbolResult);
                } catch (Exception e) {
                    logger.warn("Error processing symbol '{}' in streaming lookup", symbolName, e);
                    // Send not found result for failed lookups
                    var notFoundResult = SymbolLookupResult.notFound(symbolName);
                    resultCallback.accept(symbolName, notFoundResult);
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

    private static SymbolLookupResult checkSymbolExists(IAnalyzer analyzer, String symbolName) {
        if (symbolName.trim().isEmpty()) {
            return SymbolLookupResult.notFound(symbolName);
        }

        var trimmed = symbolName.trim();

        try {
            // First try exact FQN match
            var definition = analyzer.getDefinition(trimmed);
            if (definition.isPresent() && definition.get().isClass()) {
                return SymbolLookupResult.exactMatch(definition.get().fqName(), trimmed);
            }

            // Then try pattern search for exact matches
            var searchResults = analyzer.searchDefinitions(trimmed);
            if (!searchResults.isEmpty()) {
                var classMatches = findAllClassMatches(trimmed, searchResults);
                if (!classMatches.isEmpty()) {
                    var commaSeparatedFqns =
                            classMatches.stream().map(CodeUnit::fqName).sorted().collect(Collectors.joining(","));
                    return SymbolLookupResult.exactMatch(commaSeparatedFqns, trimmed);
                }
            }

            // Fallback: Try partial matching via class name extraction
            var extractedClassName = analyzer.extractClassName(trimmed);
            if (extractedClassName.isPresent()) {
                var rawClassName = extractedClassName.get();

                var candidates = ClassNameExtractor.normalizeVariants(rawClassName);
                for (var candidate : candidates) {
                    // Try exact FQN match for candidate
                    var classDefinition = analyzer.getDefinition(candidate);
                    if (classDefinition.isPresent() && classDefinition.get().isClass()) {
                        return SymbolLookupResult.partialMatch(
                                classDefinition.get().fqName(), trimmed, rawClassName);
                    }

                    // Try pattern search for candidate
                    var classSearchResults = analyzer.searchDefinitions(candidate);
                    if (!classSearchResults.isEmpty()) {
                        var classMatches = findAllClassMatches(candidate, classSearchResults);
                        if (!classMatches.isEmpty()) {
                            var commaSeparatedFqns = classMatches.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(","));
                            return SymbolLookupResult.partialMatch(commaSeparatedFqns, trimmed, rawClassName);
                        }
                    }
                }
            }

            return SymbolLookupResult.notFound(trimmed);

        } catch (Exception e) {
            logger.trace("Error checking symbol existence for '{}': {}", trimmed, e.getMessage());
            return SymbolLookupResult.notFound(trimmed);
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

    /** Find all classes with exact simple name match for the given search term. */
    private static List<CodeUnit> findAllClassMatches(String searchTerm, List<CodeUnit> searchResults) {
        // Find all classes with exact simple name match
        return searchResults.stream()
                .filter(CodeUnit::isClass) // Only classes, not methods or fields
                .filter(cu -> getSimpleName(cu.fqName()).equals(searchTerm))
                .toList();
    }
}
