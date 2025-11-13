package ai.brokk.gui.mop;

import ai.brokk.AnalyzerUtil;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.ClassNameExtractor;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.TypeAliasProvider;
import java.util.ArrayList;
import java.util.Collection;
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

    /** Structured result for symbol lookup with highlight range support and streaming metadata */
    public record SymbolLookupResult(
            @Nullable String fqn,
            List<HighlightRange> highlightRanges,
            boolean isPartialMatch,
            @Nullable String originalText,
            int confidence,
            long processingTimeMs) {

        public static SymbolLookupResult notFound(String originalText) {
            return new SymbolLookupResult(null, List.of(), false, originalText, 0, 0);
        }

        public static SymbolLookupResult notFound(String originalText, long processingTimeMs) {
            return new SymbolLookupResult(null, List.of(), false, originalText, 0, processingTimeMs);
        }

        public static SymbolLookupResult exactMatch(String fqn, String originalText) {
            return new SymbolLookupResult(
                    fqn, List.of(new HighlightRange(0, originalText.length())), false, originalText, 100, 0);
        }

        public static SymbolLookupResult exactMatch(String fqn, String originalText, long processingTimeMs) {
            return new SymbolLookupResult(
                    fqn,
                    List.of(new HighlightRange(0, originalText.length())),
                    false,
                    originalText,
                    100,
                    processingTimeMs);
        }

        public static SymbolLookupResult partialMatch(String fqn, String originalText, String extractedClassName) {
            // Calculate highlight range for the extracted class name within original text
            int classStart = originalText.indexOf(extractedClassName);
            if (classStart >= 0) {
                int classEnd = classStart + extractedClassName.length();
                int confidence = calculatePartialMatchConfidence(originalText, extractedClassName);
                return new SymbolLookupResult(
                        fqn, List.of(new HighlightRange(classStart, classEnd)), true, originalText, confidence, 0);
            }
            // Fallback: highlight entire text if we can't find the class name
            int confidence = calculatePartialMatchConfidence(originalText, extractedClassName);
            return new SymbolLookupResult(
                    fqn, List.of(new HighlightRange(0, originalText.length())), true, originalText, confidence, 0);
        }

        public static SymbolLookupResult partialMatch(
                String fqn, String originalText, String extractedClassName, long processingTimeMs) {
            // Calculate highlight range for the extracted class name within original text
            int classStart = originalText.indexOf(extractedClassName);
            if (classStart >= 0) {
                int classEnd = classStart + extractedClassName.length();
                int confidence = calculatePartialMatchConfidence(originalText, extractedClassName);
                return new SymbolLookupResult(
                        fqn,
                        List.of(new HighlightRange(classStart, classEnd)),
                        true,
                        originalText,
                        confidence,
                        processingTimeMs);
            }
            // Fallback: highlight entire text if we can't find the class name
            int confidence = calculatePartialMatchConfidence(originalText, extractedClassName);
            return new SymbolLookupResult(
                    fqn,
                    List.of(new HighlightRange(0, originalText.length())),
                    true,
                    originalText,
                    confidence,
                    processingTimeMs);
        }

        /** Create a result with custom confidence and metadata */
        public static SymbolLookupResult withMetadata(
                String fqn,
                List<HighlightRange> ranges,
                boolean isPartial,
                String originalText,
                int confidence,
                long processingTimeMs) {
            return new SymbolLookupResult(fqn, ranges, isPartial, originalText, confidence, processingTimeMs);
        }

        /** Calculate confidence score for partial matches based on extraction quality */
        private static int calculatePartialMatchConfidence(String originalText, String extractedClassName) {
            if (originalText.isEmpty() || extractedClassName.isEmpty()) {
                return 30; // Low confidence for empty inputs
            }

            // Higher confidence for exact substring match
            if (originalText.equals(extractedClassName)) {
                return 95; // Very high confidence for exact match
            }

            // Medium-high confidence for clear substring extraction
            if (originalText.contains(extractedClassName)) {
                double ratio = (double) extractedClassName.length() / originalText.length();
                return Math.min(90, 60 + (int) (ratio * 30)); // 60-90% confidence based on length ratio
            }

            // Lower confidence for fuzzy extraction
            return 40;
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

            // Generate unique batch ID for this lookup session
            var batchId = "batch_" + System.currentTimeMillis() + "_" + Math.random();
            var batchStartTime = System.nanoTime();

            // Process symbols with priority-based streaming
            logger.trace(
                    "[PERF][STREAM] Starting batch {} with {} symbols using analyzer: {}",
                    batchId,
                    symbolNames.size(),
                    analyzer.getClass().getSimpleName());

            // Create list for priority processing - exact matches first
            var symbolList = new ArrayList<>(symbolNames);
            var processedCount = 0;
            var foundCount = 0;

            for (var symbolName : symbolList) {
                var symbolStartTime = System.nanoTime();
                try {
                    var symbolResult = checkSymbolExists(analyzer, symbolName);
                    var symbolProcessingTime = (System.nanoTime() - symbolStartTime) / 1_000_000;

                    // Determine if this is a high-priority result (exact match, high confidence)
                    var isHighPriority = symbolResult.fqn() != null
                            && !symbolResult.isPartialMatch()
                            && symbolResult.confidence() >= 90;
                    var isFound = symbolResult.fqn() != null;

                    if (isFound) {
                        foundCount++;
                    }

                    logger.trace(
                            "[PERF][STREAM] Symbol '{}' processed in {}ms: found={}, confidence={}, priority={}",
                            symbolName,
                            symbolProcessingTime,
                            isFound,
                            symbolResult.confidence(),
                            isHighPriority ? "HIGH" : "NORMAL");

                    // Send result immediately with batch metadata
                    // Note: Frontend should handle priority hints for UI ordering
                    resultCallback.accept(symbolName, symbolResult);

                    processedCount++;

                    // Log batch progress periodically
                    if (processedCount % 5 == 0 || processedCount == symbolNames.size()) {
                        var batchElapsedTime = (System.nanoTime() - batchStartTime) / 1_000_000;
                        var avgTimePerSymbol = processedCount > 0 ? (double) batchElapsedTime / processedCount : 0.0;
                        logger.trace(
                                "[PERF][STREAM] Batch {} progress: {}/{} symbols processed in {}ms (avg {}ms/symbol, {} found)",
                                batchId,
                                processedCount,
                                symbolNames.size(),
                                batchElapsedTime,
                                String.format("%.1f", avgTimePerSymbol),
                                foundCount);
                    }

                } catch (Exception e) {
                    var symbolProcessingTime = (System.nanoTime() - symbolStartTime) / 1_000_000;
                    logger.trace(
                            "[PERF][STREAM] Error processing symbol '{}' in batch {} after {}ms",
                            symbolName,
                            batchId,
                            symbolProcessingTime,
                            e);

                    // Send not found result for failed lookups
                    var notFoundResult = SymbolLookupResult.notFound(symbolName, symbolProcessingTime);
                    resultCallback.accept(symbolName, notFoundResult);
                    processedCount++;
                }
            }

            var totalBatchTime = (System.nanoTime() - batchStartTime) / 1_000_000;
            var avgTimePerSymbol = symbolNames.size() > 0 ? (double) totalBatchTime / symbolNames.size() : 0.0;
            logger.trace(
                    "[PERF][STREAM] Batch {} completed: {} symbols processed in {}ms (avg {}ms/symbol, {} found, {}% success rate)",
                    batchId,
                    symbolNames.size(),
                    totalBatchTime,
                    String.format("%.1f", avgTimePerSymbol),
                    foundCount,
                    foundCount * 100 / Math.max(1, symbolNames.size()));

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
        var startTime = System.nanoTime();
        logger.trace(
                "[PERF][STREAM] Checking symbol existence for '{}' using {}",
                trimmed,
                analyzer.getClass().getSimpleName());

        try {
            // First try exact FQN match
            var definition = analyzer.getDefinition(trimmed);
            if (definition.isPresent() && definition.get().isClass()) {
                var processingTime = (System.nanoTime() - startTime) / 1_000_000;
                logger.trace(
                        "[PERF][STREAM] Found exact FQN match for '{}': {} in {}ms",
                        trimmed,
                        definition.get().fqName(),
                        processingTime);
                return SymbolLookupResult.exactMatch(definition.get().fqName(), trimmed, processingTime);
            }

            // Only try pattern search if exact FQN lookup failed
            logger.trace(
                    "[PERF][SEARCH] Exact FQN lookup failed, proceeding with expensive pattern search for '{}'",
                    trimmed);
            var searchResults = analyzer.searchDefinitions(trimmed);
            logger.trace("Pattern search for '{}' returned {} results", trimmed, searchResults.size());
            if (!searchResults.isEmpty()) {
                var classMatches = findAllClassMatches(trimmed, searchResults, analyzer);
                if (!classMatches.isEmpty()) {
                    var commaSeparatedFqns =
                            classMatches.stream().map(CodeUnit::fqName).sorted().collect(Collectors.joining(","));
                    var processingTime = (System.nanoTime() - startTime) / 1_000_000;
                    logger.trace(
                            "[PERF][STREAM] Found {} exact class matches for '{}': {} in {}ms",
                            classMatches.size(),
                            trimmed,
                            commaSeparatedFqns,
                            processingTime);
                    return SymbolLookupResult.exactMatch(commaSeparatedFqns, trimmed, processingTime);
                }
            }

            // Fallback: Try partial matching via class name extraction
            var extractedClassName = AnalyzerUtil.extractClassName(analyzer, trimmed);
            if (extractedClassName.isPresent()) {
                var rawClassName = extractedClassName.get();
                logger.trace("Extracted class name '{}' from '{}'", rawClassName, trimmed);

                var candidates = ClassNameExtractor.normalizeVariants(rawClassName);
                logger.trace(
                        "Generated {} candidate variants for '{}': {}", candidates.size(), rawClassName, candidates);
                for (var candidate : candidates) {
                    // Try exact FQN match for candidate
                    var classDefinition = analyzer.getDefinition(candidate);
                    if (classDefinition.isPresent() && classDefinition.get().isClass()) {
                        var processingTime = (System.nanoTime() - startTime) / 1_000_000;
                        logger.trace(
                                "[PERF][STREAM] Found partial match via FQN lookup for candidate '{}': {} in {}ms",
                                candidate,
                                classDefinition.get().fqName(),
                                processingTime);
                        logger.trace(
                                "[PERF][SEARCH] Skipping expensive pattern search for candidate '{}' - exact FQN match found",
                                candidate);
                        return SymbolLookupResult.partialMatch(
                                classDefinition.get().fqName(), trimmed, rawClassName, processingTime);
                    }

                    // Only try pattern search if exact FQN lookup failed for this candidate
                    logger.trace(
                            "[PERF][SEARCH] FQN lookup failed for candidate '{}', trying expensive pattern search",
                            candidate);
                    var classSearchResults = analyzer.searchDefinitions(candidate);
                    if (!classSearchResults.isEmpty()) {
                        var classMatches = findAllClassMatches(candidate, classSearchResults, analyzer);
                        if (!classMatches.isEmpty()) {
                            var commaSeparatedFqns = classMatches.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(","));
                            var processingTime = (System.nanoTime() - startTime) / 1_000_000;
                            logger.trace(
                                    "[PERF][STREAM] Found partial match via pattern search for candidate '{}': {} in {}ms",
                                    candidate,
                                    commaSeparatedFqns,
                                    processingTime);
                            return SymbolLookupResult.partialMatch(
                                    commaSeparatedFqns, trimmed, rawClassName, processingTime);
                        }
                    }
                }
            }

            var processingTime = (System.nanoTime() - startTime) / 1_000_000;
            logger.trace("[PERF][STREAM] No symbol found for '{}' in {}ms", trimmed, processingTime);
            return SymbolLookupResult.notFound(trimmed, processingTime);

        } catch (Exception e) {
            var processingTime = (System.nanoTime() - startTime) / 1_000_000;
            logger.trace(
                    "[PERF][STREAM] Error checking symbol existence for '{}' in {}ms: {}",
                    trimmed,
                    processingTime,
                    e.getMessage());
            return SymbolLookupResult.notFound(trimmed, processingTime);
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

    /**
     * Find all classes with exact simple name match for the given search term. Package-private for direct access by
     * tests.
     */
    static List<CodeUnit> findAllClassMatches(String searchTerm, List<CodeUnit> searchResults) {
        return searchResults.stream()
                .filter(CodeUnit::isClass) // classes only for this overload (tests target Java behavior)
                .filter(cu -> getSimpleName(cu.fqName()).equals(searchTerm))
                .toList();
    }

    /** Find all classes with exact simple name match for the given search term. */
    private static List<CodeUnit> findAllClassMatches(
            String searchTerm, Collection<CodeUnit> searchResults, IAnalyzer analyzer) {
        var typeAliasProvider = analyzer.as(TypeAliasProvider.class);
        return searchResults.stream()
                .filter(cu -> cu.isClass()
                        || (typeAliasProvider.isPresent()
                                && typeAliasProvider.get().isTypeAlias(cu)))
                .filter(cu -> getSimpleName(cu.fqName()).equals(searchTerm))
                .toList();
    }
}
