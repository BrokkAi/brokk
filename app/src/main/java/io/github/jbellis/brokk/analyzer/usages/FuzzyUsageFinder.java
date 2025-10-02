package io.github.jbellis.brokk.analyzer.usages;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.agents.RelevanceClassifier;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.tools.SearchTools;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A lightweight, standalone usage finder that relies on analyzer metadata (when available) and can later fall back to
 * text search and LLM-based disambiguation for ambiguous short names.
 */
public final class FuzzyUsageFinder {

    private static final Logger logger = LogManager.getLogger(FuzzyUsageFinder.class);

    private final IProject project;
    private final IAnalyzer analyzer;
    private final @Nullable Llm llm;

    public static FuzzyUsageFinder create(IContextManager ctx) {
        var quickestModel = ctx.getService().quickestModel();
        Llm llm;
        try {
            llm = new Llm(quickestModel, "Disambiguate Code Unit Usages", ctx, false, false);
        } catch (Exception e) {
            logger.error("Could not create LLM due to exception. Proceeding without LLM capabilities.", e);
            llm = null;
        }
        return new FuzzyUsageFinder(ctx.getProject(), ctx.getAnalyzerUninterrupted(), llm);
    }

    /**
     * Construct a FuzzyUsageFinder.
     *
     * @param project the project providing files and configuration
     * @param analyzer the analyzer providing declarations/definitions
     * @param llm optional LLM for future disambiguation
     */
    public FuzzyUsageFinder(IProject project, IAnalyzer analyzer, @Nullable Llm llm) {
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
        // non-nested identifier
        var shortName = target.identifier().replace("$", ".");
        if (shortName.contains(".")) {
            // shortName format is "Class.member" or "simpleFunction"
            int lastDot = shortName.lastIndexOf('.');
            shortName = lastDot >= 0 ? shortName.substring(lastDot + 1) : shortName;
        }
        final String identifier = shortName;
        // matches identifier around word boundaries and around common structures
        var searchPattern = "\\b" + identifier + "(?:\\.\\w+|\\(.*\\))?";
        var matchingCodeUnits = analyzer.searchDefinitions(searchPattern).stream()
                .filter(cu -> cu.identifier().equals(identifier))
                .toList();
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

        var scoredHits = new HashSet<>(hits);
        if (llm != null) {
            // Case 3: This symbol is not unique among code units, disambiguate with LLM if possible
            hits.forEach(hit -> {
                var prompt = UsagePromptBuilder.buildPrompt(hit, target, analyzer, identifier, 8_000);
                try {
                    var score =
                            RelevanceClassifier.relevanceScore(llm, prompt.filterDescription(), prompt.candidateText());
                    scoredHits.add(hit.withConfidence(score));
                } catch (InterruptedException e) {
                    logger.error(
                            "Unable to classify relevance of {} with {} due to exception. Assuming score of 1.0.",
                            hit,
                            llm,
                            e);
                    scoredHits.add(hit);
                }
            });
        }
        return new FuzzyResult.Ambiguous(
                target.shortName(), matchingCodeUnits, scoredHits.stream().toList());
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

                    // Get the substring before the match and find its byte length
                    int startByte = content.substring(0, start).getBytes(StandardCharsets.UTF_8).length;
                    int endByte = startByte + matcher.group().getBytes(StandardCharsets.UTF_8).length;

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

                    var range = new IAnalyzer.Range(startByte, endByte, lineIdx, lineIdx, lineIdx);
                    var enclosingCodeUnit = analyzer.enclosingCodeUnit(file, range);

                    if (enclosingCodeUnit != null) {
                        hits.add(new UsageHit(file, lineIdx + 1, start, end, enclosingCodeUnit, 1.0, snippet));
                    } else {
                        logger.warn(
                                "Unable to find enclosing code unit for {} in {}. Not registering hit.",
                                searchPattern,
                                file);
                    }
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
