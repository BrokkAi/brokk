package io.github.jbellis.brokk.gui.mop;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolLookupService {
    private static final Logger logger = LoggerFactory.getLogger(SymbolLookupService.class);

    public record SymbolLookupResult(String symbolName, boolean exists) {}

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
            symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false)));
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
                symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false)));
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
                symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false)));
                return results;
            }

            logger.debug("Using analyzer: {}", analyzer.getClass().getSimpleName());

            for (var symbolName : symbolNames) {
                logger.debug("Checking symbol: '{}'", symbolName);
                var exists = checkSymbolExists(analyzer, symbolName);
                logger.debug("Symbol '{}' exists: {}", symbolName, exists);
                results.put(symbolName, new SymbolLookupResult(symbolName, exists));
            }

        } catch (Exception e) {
            logger.warn("Error during symbol lookup", e);
            symbolNames.forEach(name -> results.put(name, new SymbolLookupResult(name, false)));
        }

        logger.debug("Symbol lookup completed with {} results", results.size());
        return results;
    }

    private static boolean checkSymbolExists(IAnalyzer analyzer, String symbolName) {
        if (symbolName.trim().isEmpty()) {
            return false;
        }

        var trimmed = symbolName.trim();
        logger.debug("Checking symbol existence: '{}'", trimmed);

        try {
            // First try exact FQN match
            var definition = analyzer.getDefinition(trimmed);
            logger.debug("getDefinition('{}') result: {}", trimmed, definition.isPresent() ? definition.get() : "not found");
            if (definition.isPresent()) {
                return true;
            }

            // Then try pattern search
            logger.debug("Trying searchDefinitions for '{}'", trimmed);
            var searchResults = analyzer.searchDefinitions(trimmed);
            logger.debug("searchDefinitions('{}') returned {} results", trimmed, searchResults.size());

            if (!searchResults.isEmpty()) {
                logger.debug("Search results for '{}': {}", trimmed,
                    searchResults.stream().limit(5).map(cu -> cu.fqName()).toList());
                return true;
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

            return false;

        } catch (Exception e) {
            logger.debug("Error checking symbol existence for '{}': {}", trimmed, e.getMessage());
            return false;
        }
    }
}
