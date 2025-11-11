package ai.brokk.analyzer.usages;

import ai.brokk.AbstractService;
import ai.brokk.IContextManager;
import ai.brokk.IProject;
import ai.brokk.Llm;
import ai.brokk.agents.RelevanceClassifier;
import ai.brokk.agents.RelevanceTask;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.tools.SearchTools;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    public static final int DEFAULT_MAX_FILES = 1000;
    public static final int DEFAULT_MAX_USAGES = 1000;

    private final IProject project;
    private final IAnalyzer analyzer;
    private final AbstractService service;
    private final @Nullable Llm llm;

    public static FuzzyUsageFinder create(IContextManager ctx) {
        var service = ctx.getService();
        var quickestModel = service.quickestModel();
        var llm = new Llm(quickestModel, "Disambiguate Code Unit Usages", ctx, false, false, false, false);
        return new FuzzyUsageFinder(ctx.getProject(), ctx.getAnalyzerUninterrupted(), service, llm);
    }

    /**
     * Construct a FuzzyUsageFinder.
     *
     * @param project the project providing files and configuration
     * @param analyzer the analyzer providing declarations/definitions
     * @param service the LLM service.
     * @param llm optional LLM for future disambiguation
     */
    public FuzzyUsageFinder(IProject project, IAnalyzer analyzer, AbstractService service, @Nullable Llm llm) {
        this.project = project;
        this.analyzer = analyzer;
        this.service = service;
        this.llm = llm; // optional
        logger.debug("Initialized FuzzyUsageAnalyzer (llmPresent={}): {}", llm != null, this);
    }

    /**
     * Find usages for a specific CodeUnit.
     *
     * <p>For an empty project/analyzer, returns Success with an empty hit list.
     */
    private FuzzyResult findUsages(CodeUnit target, int maxFiles, int maxUsages) {
        // non-nested identifier
        var shortName = target.identifier().replace("$", ".");
        if (shortName.contains(".")) {
            // shortName format is "Class.member" or "simpleFunction"
            int lastDot = shortName.lastIndexOf('.');
            shortName = lastDot >= 0 ? shortName.substring(lastDot + 1) : shortName;
        }
        final String identifier = shortName;

        // Determine language based on the target's source file extension
        Language lang = Languages.fromExtension(target.source().extension());

        // Build language-aware search patterns for this code unit kind
        var templates = lang.getSearchPatterns(target.kind());
        var searchPatterns = templates.stream()
                .map(template -> template.replace("$ident", Pattern.quote(identifier)))
                .collect(Collectors.toSet());

        // Define pattern for matching code unit definitions with exact shortName (used to detect uniqueness)
        var matchingCodeUnits =
                analyzer.searchDefinitions("\\b%s\\b".formatted(Pattern.quote(identifier)), false).stream()
                        .filter(cu -> cu.shortName().equals(identifier))
                        .collect(Collectors.toSet());
        var isUnique = matchingCodeUnits.size() == 1;

        // Use a fast substring scan to prefilter candidate files by the raw identifier, not the regex
        Set<ProjectFile> candidateFiles = SearchTools.searchSubstrings(
                List.of(identifier), analyzer.getProject().getAnalyzableFiles(lang));

        if (maxFiles < candidateFiles.size()) {
            // Case 1: Too many call sites
            logger.debug("Too many call sites found for {}: {} files matched", target, candidateFiles.size());
            return new FuzzyResult.TooManyCallsites(target.shortName(), candidateFiles.size(), maxFiles);
        }

        // Extract raw usage hits from candidate files using the provided patterns
        var hits = extractUsageHits(candidateFiles, searchPatterns).stream()
                .filter(h -> !h.enclosing().fqName().equals(target.fqName()))
                .collect(Collectors.toSet());

        logger.debug(
                "Extracted {} usage hits for {} from {} candidate files",
                hits.size(),
                target.fqName(),
                candidateFiles.size());

        if (isUnique) {
            // Case 2: This is a uniquely named code unit, no need to check with LLM.
            logger.debug("Found {} hits for unique code unit {}", hits.size(), target);
            return new FuzzyResult.Success(hits);
        } else if (hits.size() > maxUsages) {
            // Case 3: Too many call sites to disambiguate with the LLM
            logger.debug(
                    "Too many call sites to disambiguate with the LLM {}: {} usage locations matched",
                    target,
                    hits.size());
            return new FuzzyResult.TooManyCallsites(target.shortName(), hits.size(), maxUsages);
        }

        Set<UsageHit> finalHits = hits;
        if (llm != null && !hits.isEmpty()) {
            // Case 4: This symbol is not unique among code units, disambiguate with LLM if possible
            logger.debug("Disambiguating {} hits among {} code units", hits.size(), matchingCodeUnits.size());
            var unscoredHits = new HashSet<>(hits);
            var scoredHits = new HashSet<UsageHit>(hits.size());
            try {
                var tasks = new ArrayList<RelevanceTask>(hits.size());
                var mapping = new ArrayList<UsageHit>(hits.size());
                var alternatives = matchingCodeUnits.stream()
                        .filter(cu -> !cu.fqName().equals(target.fqName()))
                        .collect(Collectors.toList());
                for (var hit : hits) {
                    var prompt = UsagePromptBuilder.buildPrompt(hit, target, alternatives, analyzer, identifier, 8_000);
                    // Use the rich prompt text (includes <candidates>) as the candidate text for classification
                    tasks.add(new RelevanceTask(prompt.filterDescription(), prompt.promptText()));
                    mapping.add(hit);
                }

                if (UsageConfig.isBooleanUsageMode()) {
                    var decisions = RelevanceClassifier.relevanceBooleanBatch(llm, service, tasks);
                    for (int i = 0; i < tasks.size(); i++) {
                        var task = tasks.get(i);
                        var decision = decisions.getOrDefault(task, false);
                        var base = mapping.get(i);
                        var scored = base.withConfidence(decision ? 1.0 : 0.0);
                        scoredHits.add(scored);
                        unscoredHits.remove(base);
                    }
                } else {
                    var scores = RelevanceClassifier.relevanceScoreBatch(llm, service, tasks);
                    for (int i = 0; i < tasks.size(); i++) {
                        var task = tasks.get(i);
                        var score = scores.getOrDefault(task, 0.0);
                        var base = mapping.get(i);
                        var scored = base.withConfidence(score);
                        scoredHits.add(scored);
                        unscoredHits.remove(base);
                    }
                }
            } catch (InterruptedException e) {
                logger.error(
                        "Unable to batch classify relevance with {} due to exception. Leaving hits unscored.", llm, e);
                Thread.currentThread().interrupt();
            }
            var combined = new HashSet<UsageHit>(scoredHits.size() + unscoredHits.size());
            combined.addAll(scoredHits);
            combined.addAll(unscoredHits);
            finalHits = combined;
            logger.debug("Found {} disambiguated hits", finalHits.size());
        }

        return new FuzzyResult.Ambiguous(target.shortName(), matchingCodeUnits, finalHits);
    }

    /**
     * Extract raw usage hits from the given files by applying the provided regex searchPatterns.
     *
     * <ul>
     *   <li>Emits one UsageHit per regex match occurrence.
     *   <li>Line numbers are 1-based.
     *   <li>Snippet contains 3 lines above and 3 lines below the matched line (when available).
     *   <li>Confidence is 1.0 by default; LLM will adjust if needed later.
     * </ul>
     */
    private Set<UsageHit> extractUsageHits(Set<ProjectFile> candidateFiles, Set<String> searchPatterns) {
        var hits = new ConcurrentHashMap<UsageHit, Boolean>(); // no ConcurrentHashSet exists
        final var patterns = searchPatterns.stream().map(Pattern::compile).toList();

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

                for (var pattern : patterns) {
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

                        if (enclosingCodeUnit.isPresent()) {
                            hits.put(
                                    new UsageHit(file, lineIdx + 1, start, end, enclosingCodeUnit.get(), 1.0, snippet),
                                    true);
                        } else {
                            logger.warn(
                                    "Unable to find enclosing code unit for {} in {}. Not registering hit.",
                                    pattern.pattern(),
                                    file);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to extract usage hits from {}: {}", file, e.toString());
            }
        });

        return Set.copyOf(hits.keySet());
    }

    /**
     * Find usages by fully-qualified name.
     *
     * <p>For an empty project/analyzer, returns Success with an empty hit list.
     */
    public FuzzyResult findUsages(String fqName, int maxFiles, int maxUsages) {
        if (isEffectivelyEmpty()) {
            logger.debug("Project/analyzer empty; returning empty Success for fqName={}", fqName);
            return new FuzzyResult.Success(Set.of());
        }
        var maybeCodeUnit = analyzer.getDefinition(fqName);
        if (maybeCodeUnit.isEmpty()) {
            logger.warn("Unable to find code unit for fqName={}", fqName);
            return new FuzzyResult.Failure(fqName, "Unable to find associated code unit for the given name");
        }
        return findUsages(maybeCodeUnit.get(), maxFiles, maxUsages);
    }

    public FuzzyResult findUsages(String fqName) {
        return findUsages(fqName, DEFAULT_MAX_FILES, DEFAULT_MAX_USAGES);
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
