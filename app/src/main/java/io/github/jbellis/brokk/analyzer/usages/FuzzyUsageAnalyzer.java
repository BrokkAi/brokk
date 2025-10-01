package io.github.jbellis.brokk.analyzer.usages;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * FuzzyUsageAnalyzer
 *
 * <p>A lightweight, standalone usage finder that relies on analyzer metadata (when available) and can later fall back
 * to text search and LLM-based disambiguation for ambiguous short names.
 *
 * <p>POC scope: - API only: result types and entry points - Behavior for empty projects: return an empty Success result
 */
public final class FuzzyUsageAnalyzer {

    private static final Logger logger = LogManager.getLogger(FuzzyUsageAnalyzer.class);

    private final IProject project;
    private final IAnalyzer analyzer;
    private final @Nullable Llm llm;

    /**
     * Construct a FuzzyUsageAnalyzer.
     *
     * @param project the project providing files and configuration
     * @param analyzer the analyzer providing declarations/definitions
     * @param llm optional LLM for future disambiguation
     */
    public FuzzyUsageAnalyzer(IProject project, IAnalyzer analyzer, @Nullable Llm llm) {
        this.project = requireNonNull(project, "project");
        this.analyzer = requireNonNull(analyzer, "analyzer");
        this.llm = llm; // optional
        logger.debug("Initialized FuzzyUsageAnalyzer (llmPresent={}): {}", llm != null, this);
    }

    /**
     * Find usages for a specific CodeUnit.
     *
     * <p>For an empty project/analyzer, returns Success with an empty hit list.
     */
    public FuzzyResult findUsages(CodeUnit target, int maxCallsites) {
        requireNonNull(target, "target");
        if (isEffectivelyEmpty()) {
            logger.debug("Project/analyzer empty; returning empty Success for {}", target);
            return new FuzzyResult.Success(List.of());
        }
        // POC step-1: API only – full logic (range map, text search, LLM) to be added in follow-up tasks.
        return new FuzzyResult.Success(List.of());
    }

    /**
     * Find usages by fully-qualified name.
     *
     * <p>For an empty project/analyzer, returns Success with an empty hit list.
     */
    public FuzzyResult findUsages(String fqName, int maxCallsites) {
        requireNonNull(fqName, "fqName");
        if (isEffectivelyEmpty()) {
            logger.debug("Project/analyzer empty; returning empty Success for fqName={}", fqName);
            return new FuzzyResult.Success(List.of());
        }
        // POC step-1: API only – full logic (range map, text search, LLM) to be added in follow-up tasks.
        return new FuzzyResult.Success(List.of());
    }

    private boolean isEffectivelyEmpty() {
        // Analyzer says empty or project has no files considered by analyzer
        if (analyzer.isEmpty()) {
            return true;
        }
        var files = project.getAllFiles();
        return files.isEmpty();
    }

    /**
     * Immutable metadata describing a usage occurrence.
     *
     * <p>- file: the file containing the usage - line: 1-based line number - startOffset/endOffset: character offsets
     * within the file content (inclusive/exclusive) - enclosing: best-effort enclosing CodeUnit for the usage -
     * confidence: [0.0, 1.0], 1.0 for exact/unique matches; may be lower when disambiguated - snippet: short text
     * snippet around the usage location
     */
    public record UsageHit(
            ProjectFile file,
            int line,
            int startOffset,
            int endOffset,
            CodeUnit enclosing,
            double confidence,
            String snippet) {}
}
