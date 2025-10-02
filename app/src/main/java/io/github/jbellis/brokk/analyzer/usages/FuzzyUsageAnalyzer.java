package io.github.jbellis.brokk.analyzer.usages;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer;
import io.github.jbellis.brokk.tools.SearchTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
        var matchingCodeUnits = analyzer.searchDefinitions(searchPattern);
        var isUnique = matchingCodeUnits.size() == 1;
        final Set<ProjectFile> candidateFiles = SearchTools.searchSubstrings(
                List.of(searchPattern), analyzer.getProject().getAllFiles());

        if (maxCallsites < candidateFiles.size()) {
            // Case 1: Too many call sites
            return new FuzzyResult.TooManyCallsites(target.shortName(), candidateFiles.size(), maxCallsites);
        }

        // Extract raw usage hits from candidate files using the provided pattern
        var hits = extractUsageHits(candidateFiles, searchPattern);
        logger.debug(
                "Extracted {} usage hits for {} from {} candidate files",
                hits.size(),
                target.fqName(),
                candidateFiles.size());

        if (isUnique) {
            // Case 2: This is a uniquely named code unit, no need to check with LLM.
            return new FuzzyResult.Success(hits);
        }

        if (llm != null) {
            // Case 3: This symbol is not unique among code units, disambiguate with LLM if possible
            // (LLM-based classification to be implemented in follow-up)
        }

        // Case 4: If still ambiguous, return result describing it as such
        return new FuzzyResult.Ambiguous(target.shortName(), matchingCodeUnits, hits);
    }

    /**
     * Extract raw usage hits from the given files by applying the Java regex searchPattern.
     *
     * <ul>
     *   <li>Emits one UsageHit per regex match occurrence.
     *   <li>Line numbers are 1-based.
     *   <li>Snippet contains 3 lines above and 3 lines below the matched line (when available).
     *   <li>Confidence is 1.0 by default; LLM will adjust if needed later.
     * </ul>
     */
    private List<UsageHit> extractUsageHits(Set<ProjectFile> candidateFiles, String searchPattern) {
        var hits = new ArrayList<UsageHit>();
        final var pattern = Pattern.compile(searchPattern);

        candidateFiles.parallelStream().forEach(file -> {
            try {
                if (!file.isText()) {
                    return;
                }
                var contentOpt = file.read();
                if (contentOpt.isEmpty()) {
                    return;
                }
                var content = contentOpt.get();
                if (content.isEmpty()) {
                    return;
                }

                // Precompute line starts for fast offset->line mapping
                var lines = content.split("\\R", -1); // keep trailing empty lines if present
                int[] lineStarts = new int[lines.length];
                int running = 0;
                for (int i = 0; i < lines.length; i++) {
                    lineStarts[i] = running;
                    running += lines[i].length() + 1; // +1 for the '\n' separator
                }

                var matcher = pattern.matcher(content);
                while (matcher.find()) {
                    int start = matcher.start();
                    int end = matcher.end();

                    // Binary search for the line index such that lineStarts[idx] <= start < next
                    int lo = 0, hi = lineStarts.length - 1, lineIdx = 0;
                    while (lo <= hi) {
                        int mid = (lo + hi) >>> 1;
                        if (lineStarts[mid] <= start) {
                            lineIdx = mid;
                            lo = mid + 1;
                        } else {
                            hi = mid - 1;
                        }
                    }

                    int startLine = Math.max(0, lineIdx - 3);
                    int endLine = Math.min(lines.length - 1, lineIdx + 3);
                    var snippet = IntStream.rangeClosed(startLine, endLine)
                            .mapToObj(i -> lines[i])
                            .collect(Collectors.joining("\n"));

                    hits.add(new UsageHit(file, lineIdx + 1, start, end, 1.0, snippet));
                }
            } catch (Exception e) {
                logger.warn("Failed to extract usage hits from {}: {}", file, e.toString());
            }
        });

        return hits;
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
