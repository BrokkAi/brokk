package io.github.jbellis.brokk.analyzer.usages;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer;
import io.github.jbellis.brokk.tools.SearchTools;
import java.util.List;
import java.util.Set;
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
    private final TreeSitterAnalyzer analyzer;
    private final @Nullable Llm llm;

    /**
     * Construct a FuzzyUsageAnalyzer.
     *
     * @param project the project providing files and configuration
     * @param analyzer the analyzer providing declarations/definitions
     * @param llm optional LLM for future disambiguation
     */
    public FuzzyUsageAnalyzer(IProject project, TreeSitterAnalyzer analyzer, @Nullable Llm llm) {
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
    private FuzzyResult findUsages(CodeUnit target, int maxCallsites) {
        var identifier = target.identifier();
        // matches identifier around word boundaries and around common structures
        var searchPattern = "\\b" + identifier + "(?:\\.\\w+|\\(.*\\))?";
        var isUnique = analyzer.searchDefinitions(searchPattern).size() == 1;
        final Set<ProjectFile> candidateFiles = SearchTools.searchSubstrings(
                List.of(searchPattern), analyzer.getProject().getAllFiles());

        if (maxCallsites < candidateFiles.size()) {
            // Case 1: Too many call sites
            return new FuzzyResult.TooManyCallsites(target.shortName(), candidateFiles.size(), maxCallsites);
        }

        if (isUnique) {
            // Case 2: This is a uniquely named code unit, no need to check with LLM.
        }

        if (llm != null) {
            // Case 3: This symbol is not unique among code units, disambiguate with LLM if possible
        }

        // Case 4: If still ambiguous, return result describing it as such
        return new FuzzyResult.Ambiguous(target.shortName(), List.of());
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
        var maybeCodeUnit = analyzer.getDefinition(fqName);
        if (maybeCodeUnit.isEmpty()) {
            logger.warn("Unable to find code unit for fqName={}", fqName);
            return new FuzzyResult.Failure(fqName, "Unable to find associated code unit for the given name");
        }
        return findUsages(maybeCodeUnit.get(), maxCallsites);
    }

    private boolean isEffectivelyEmpty() {
        // Analyzer says empty or project has no files considered by analyzer
        if (analyzer.isEmpty()) {
            return true;
        }
        var files = project.getAllFiles();
        return files.isEmpty();
    }
}
