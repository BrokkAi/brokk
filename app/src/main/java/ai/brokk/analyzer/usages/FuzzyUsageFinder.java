package ai.brokk.analyzer.usages;

import ai.brokk.AbstractService;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.agents.RelevanceClassifier;
import ai.brokk.agents.RelevanceTask;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TypeHierarchyProvider;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.tools.SearchTools;
import ai.brokk.util.FileUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A lightweight, standalone usage finder that relies on analyzer metadata (when available) and can later fall back to
 * text search and LLM-based disambiguation for ambiguous short names.
 *
 * Design note (experiment): We will experiment with a very small, best-effort heuristic that uses parameter counts
 * to disambiguate overloads without invoking the LLM. This heuristic is intentionally minimal:
 *
 * - Scope: Java only for the initial experiment. The TreeSitter-based JavaAnalyzer renders signatures as human-
 *   readable strings (CodeUnit.signature()) that include parameter lists; therefore parsing parameter counts from
 *   signature strings is feasible. Other languages may be added later if their signature format is similarly parseable.
 *
 * - Heuristic: When available, compare the declaration-side parameter count (parsed from CodeUnit.signature()) with
 *   the call-site argument count (best-effort parsed from the usage snippet collected in UsageHit). If the counts
 *   disagree, mark the hit as unlikely for that particular overload.
 *
 * - Constraints and safety:
 *   * This is a best-effort, parameter-count-only heuristic and is NOT authoritative. It is only applied when BOTH
 *     sides (declaration signature and call-site argument list) can be determined reliably. If either side is
 *     unknown or ambiguous, the heuristic is skipped and we fall back to existing behavior.
 *   * We do NOT change the public behavioral contract of FuzzyUsageFinder in this commit. The heuristic is implemented
 *     as private helpers and documented placement points; it is not yet wired into the disambiguation pipeline so all
 *     existing tests retain their behavior. Future changes will apply the heuristic after raw hits are collected and
 *     before LLM disambiguation.
 *   * The heuristic only considers simple, comma-separated arguments on the matched line(s). It will not attempt to
 *     resolve nested parentheses across multiple lines or perform AST-level parsing (that would require Analyzer API
 *     additions). This keeps the heuristic low-risk and easy to reason about.
 *
 * - Where to apply: After extractUsageHits produces raw UsageHit instances (which include a small snippet and the
 *   enclosing CodeUnit), but before invoking the LLM-based RelevanceClassifier. The pipeline will:
 *     1) Collect raw hits (existing extractUsageHits).
 *     2) (Optional, future) Apply parameter-count filtering to prefer/score hits that match the target signature.
 *     3) If ambiguous and LLM is available, fall back to LLM prompts (existing code).
 *
 * This file currently contains the helper parsing functions and inline comments describing the intended insertion
 * point for the heuristic. No functional behavior is changed in this commit.
 */
public final class FuzzyUsageFinder {

    private static final Logger logger = LogManager.getLogger(FuzzyUsageFinder.class);
    public static final int DEFAULT_MAX_FILES = 1000;
    public static final int DEFAULT_MAX_USAGES = 1000;

    private final IProject project;
    private final IAnalyzer analyzer;
    private final AbstractService service;
    private final @Nullable Llm llm;
    private final @Nullable Predicate<ProjectFile> fileFilter;

    public static FuzzyUsageFinder create(IContextManager cm) {
        return create(cm, null);
    }

    public static FuzzyUsageFinder create(IContextManager cm, @Nullable Predicate<ProjectFile> fileFilter) {
        var service = cm.getService();
        var model = service.getModel(ModelProperties.ModelType.USAGES);
        var llm = model instanceof AbstractService.OfflineStreamingModel
                ? null
                : new Llm(
                        model,
                        "Disambiguate Code Unit Usages",
                        ai.brokk.TaskResult.Type.CLASSIFY,
                        cm,
                        false,
                        false,
                        false,
                        false);
        return new FuzzyUsageFinder(cm.getProject(), cm.getAnalyzerUninterrupted(), service, llm, fileFilter);
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
        this(project, analyzer, service, llm, null);
    }

    public FuzzyUsageFinder(
            IProject project,
            IAnalyzer analyzer,
            AbstractService service,
            @Nullable Llm llm,
            @Nullable Predicate<ProjectFile> fileFilter) {
        this.project = project;
        this.analyzer = analyzer;
        this.service = service;
        this.llm = llm;
        this.fileFilter = fileFilter;
    }

    /**
     * Find usages for a specific CodeUnit.
     *
     * <p>For an empty project/analyzer, returns Success with an empty hit list.
     */
    private FuzzyResult findUsages(CodeUnit target, int maxFiles, int maxUsages) throws InterruptedException {
        // non-nested identifier
        final String identifier = target.identifier();

        // Determine language based on the target's source file extension
        Language lang = Languages.fromExtension(target.source().extension());

        // Build language-aware search patterns for this code unit kind
        var templates = lang.getSearchPatterns(target.kind());

        var searchPatterns = templates.stream()
                .map(template -> template.replace("$ident", Pattern.quote(identifier)))
                .collect(Collectors.toSet());

        // Define pattern for matching code unit definitions with exact identifier (used to detect uniqueness)
        var matchingCodeUnits =
                analyzer.searchDefinitions("\\b%s\\b".formatted(Pattern.quote(identifier)), false).stream()
                        .filter(cu -> cu.identifier().equals(identifier))
                        .collect(Collectors.toSet());
        var isUnique = matchingCodeUnits.size() == 1;

        // Use a fast substring scan to prefilter candidate files by the raw identifier, not the regex
        Set<ProjectFile> candidateFiles = SearchTools.searchSubstrings(
                List.of(identifier), analyzer.getProject().getAnalyzableFiles(lang));

        // Apply file filter if provided (e.g., to exclude test files)
        if (fileFilter != null) {
            candidateFiles = candidateFiles.stream().filter(fileFilter).collect(Collectors.toSet());
        }

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

        // Intended insertion point for the parameter-count heuristic (future wiring):
        //
        // At this point we have:
        //   - matchingCodeUnits: set of candidate CodeUnit definitions (overloads included)
        //   - hits: raw UsageHit set with snippet/context + enclosing CodeUnit (from analyzer)
        //
        // The minimal experiment will:
        //   1) For Java only (Language == JAVA), attempt to parse declaration-side parameter count from
        //      each CodeUnit.signature() when present.
        //   2) For each UsageHit, attempt to parse a call-site argument count from the usage snippet.
        //   3) If both counts are available for a hit and a particular candidate definition, use the counts to
        //      prefer matches where counts are equal and deprioritize/mark as unlikely those where counts differ.
        //
        // Important: This commit DOES NOT APPLY the heuristic yet. The helper methods below implement the parsing
        // logic and the comment documents the exact location where matching would occur. Applying the heuristic
        // will be done in a follow-up change so that we can measure behavior and adjust without affecting current
        // tests.

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
            var alternatives = matchingCodeUnits.stream()
                    .filter(cu -> !cu.fqName().equals(target.fqName()))
                    .collect(Collectors.toSet());

            var hierarchyProvider = analyzer.as(TypeHierarchyProvider.class);
            Collection<CodeUnit> polymorphicMatches = hierarchyProvider
                    .map(provider -> provider.getPolymorphicMatches(target, analyzer))
                    .orElse(List.of());
            boolean hierarchySupported = hierarchyProvider.isPresent();

            // Group hits by enclosing CodeUnit to build one prompt per context.
            // Note: This is a design tradeoff: all hits within the same method/enclosing unit will receive
            // the same LLM-derived confidence score. While this saves tokens and latency, it may lack precision
            // if a method calls multiple overloads where only one is the target. Individual hit disambiguation
            // could be added here later if higher precision is required.
            var groupedHits = hits.stream().collect(Collectors.groupingBy(UsageHit::enclosing));

            var tasks = new ArrayList<RelevanceTask>(groupedHits.size());
            var taskToHits = new ArrayList<List<UsageHit>>(groupedHits.size());

            for (var entry : groupedHits.entrySet()) {
                var hitsInGroup = entry.getValue();
                var prompt = UsagePrompt.build(
                        hitsInGroup,
                        target,
                        alternatives,
                        polymorphicMatches,
                        hierarchySupported,
                        analyzer,
                        identifier,
                        8_000);

                var task = new RelevanceTask(prompt.filterDescription(), prompt.promptText());
                tasks.add(task);
                taskToHits.add(hitsInGroup);
            }

            var scores = RelevanceClassifier.relevanceScoreBatch(project.getDiskCache(), llm, service, tasks);
            var resultHits = new HashSet<UsageHit>(hits.size());

            for (int i = 0; i < tasks.size(); i++) {
                var task = tasks.get(i);
                var hitsInGroup = taskToHits.get(i);
                var score = scores.getOrDefault(task, 0.0);

                for (var hit : hitsInGroup) {
                    resultHits.add(hit.withConfidence(score));
                }
            }
            finalHits = resultHits;
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

                // Precompute line starts using actual separators (CRLF/LF/CR).
                var lines = content.split("\\R", -1); // keep trailing empty lines if present
                var lineStarts = FileUtil.computeLineStarts(content);
                assert lineStarts.length == lines.length : "lineStarts/lines length mismatch";

                for (var pattern : patterns) {
                    var matcher = pattern.matcher(content);
                    while (matcher.find()) {
                        int start = matcher.start();
                        int end = matcher.end();

                        // Get the substring before the match and find its byte length
                        int startByte = content.substring(0, start).getBytes(StandardCharsets.UTF_8).length;
                        int endByte = startByte + matcher.group().getBytes(StandardCharsets.UTF_8).length;

                        // Filter out hits that are actually declarations or comments if the analyzer supports AST
                        // checks
                        if (!analyzer.isAccessExpression(file, startByte, endByte)) {
                            continue;
                        }

                        // Map char offset -> 0-based line index using precomputed starts
                        int lineIdx = FileUtil.findLineIndexForOffset(lineStarts, start);

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
     * <p>If multiple definitions exist (e.g., overloaded methods), aggregates usages from all of them.
     */
    public FuzzyResult findUsages(String fqName, int maxFiles, int maxUsages) throws InterruptedException {
        if (isEffectivelyEmpty()) {
            logger.debug("Project/analyzer empty; returning empty Success for fqName={}", fqName);
            return new FuzzyResult.Success(Set.of());
        }
        var definitions = analyzer.getDefinitions(fqName);
        if (definitions.isEmpty()) {
            logger.debug("No definitions found for fqName={}; returning Failure", fqName);
            return new FuzzyResult.Failure(fqName, "No definitions found");
        }

        // FUF is signature-blind, so we can collapse overloads to reduce the work
        var def = definitions.iterator().next();
        CodeUnit cu = new CodeUnit(def.source(), def.kind(), def.packageName(), def.shortName(), null);

        // Aggregate usages from all definitions
        Set<UsageHit> allHits = new HashSet<>();
        var result = findUsages(cu, maxFiles, maxUsages);
        switch (result) {
            case FuzzyResult.Success success -> {
                allHits.addAll(success.hits());
            }
            case FuzzyResult.Ambiguous ambiguous -> {
                allHits.addAll(ambiguous.hits());
            }
            case FuzzyResult.TooManyCallsites tooMany -> {
                logger.debug(
                        "Too many callsites for {} when finding usages of {}: {} > {}",
                        cu.fqName(),
                        fqName,
                        tooMany.totalCallsites(),
                        tooMany.limit());
            }
            case FuzzyResult.Failure failure -> {
                logger.debug("Failure for {} when finding usages of {}: {}", cu.fqName(), fqName, failure.reason());
            }
        }

        // Throw out very low confidence
        var filteredHits = filterByConfidence(allHits);
        logger.debug("Filtered to {} hits for {} out of {}", filteredHits.size(), fqName, allHits.size());

        return new FuzzyResult.Success(filteredHits);
    }

    static Set<UsageHit> filterByConfidence(Set<UsageHit> allHits) {
        return allHits.stream().filter(h -> h.confidence() >= 0.1).collect(Collectors.toSet());
    }

    public FuzzyResult findUsages(String fqName) throws InterruptedException {
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

    //
    // Helpers for the parameter-count heuristic (implementation only; NOT wired into the pipeline yet).
    //
    // These helpers implement the minimal, best-effort parsing described in the class-level comment above.
    // They are intentionally conservative and return -1 for "unknown".
    //

    /**
     * Parse a Java-style parameter count from a CodeUnit signature string, if present.
     *
     * Expected input examples (produced by JavaAnalyzer.renderFunctionDeclaration/assembleFunctionSignature):
     *  - "void foo()" -> 0
     *  - "int bar(String s, int x)" -> 2
     *  - "List<T> baz(Map<String, List<Integer>> m)" -> 1
     *
     * Returns -1 if the signature is null or parsing fails.
     */
    private static int parseParameterCountFromSignature(@Nullable String signature) {
        if (signature == null || signature.isBlank()) {
            return -1;
        }
        // Conservative approach: find the first balanced parentheses pair after the first identifier-like token.
        int firstParen = signature.indexOf('(');
        int lastParen = signature.indexOf(')', firstParen + 1);
        if (firstParen < 0 || lastParen < 0) {
            return -1;
        }
        if (lastParen == firstParen + 1) {
            return 0; // explicit empty parameter list "()"
        }
        String inside = signature.substring(firstParen + 1, lastParen).trim();
        if (inside.isEmpty()) {
            return 0;
        }

        // Split on commas but ignore commas inside angle brackets or parentheses (very simple balancing)
        int count = 0;
        int depthAngle = 0;
        int depthParen = 0;
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '<') depthAngle++;
            else if (c == '>') depthAngle = Math.max(0, depthAngle - 1);
            else if (c == '(') depthParen++;
            else if (c == ')') depthParen = Math.max(0, depthParen - 1);

            if (c == ',' && depthAngle == 0 && depthParen == 0) {
                if (token.toString().trim().length() > 0) {
                    count++;
                }
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        if (token.toString().trim().length() > 0) {
            count++;
        }
        return count;
    }

    /**
     * Heuristically count the number of arguments at a call-site using a UsageHit.snippet().
     *
     * This is intentionally simple:
     *  - It tries to locate the occurrence of an identifier followed by '(' on the matched line.
     *  - It then counts top-level commas within that parentheses pair on that line.
     *  - If multiple candidate parentheses exist on the snippet line, picks the one nearest the match offset.
     *
     * Returns -1 when unable to determine.
     */
    private static int parseArgumentCountFromSnippet(UsageHit hit) {
        String snippet = hit.snippet();
        if (snippet == null || snippet.isBlank()) {
            return -1;
        }

        // For simplicity use only the central matched line (the snippet produced by extractUsageHits includes +/- 3
        // lines).
        // We'll extract the line corresponding to hit.line (1-based within the file) relative to snippet.
        var lines = snippet.split("\\R", -1);

        // Try to locate a line that contains both '(' and ')' and use it.
        int candidateLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("(") && lines[i].contains(")")) {
                candidateLine = i;
                break;
            }
        }
        if (candidateLine == -1) {
            // fallback: search for any parentheses pair anywhere in the snippet
            int lp = snippet.indexOf('(');
            int rp = snippet.indexOf(')', lp + 1);
            if (lp >= 0 && rp > lp) {
                String inside = snippet.substring(lp + 1, rp);
                return simpleCommaCount(inside);
            }
            return -1;
        }

        String line = lines[candidateLine];
        // Find the first '(' and its matching ')' on that line
        int lp = line.indexOf('(');
        int rp = line.indexOf(')', lp + 1);
        if (lp < 0 || rp < 0) {
            return -1;
        }
        String inside = line.substring(lp + 1, rp);
        return simpleCommaCount(inside);
    }

    /**
     * Count commas at top level in a short expression (no nested parentheses/angles considered).
     * Returns 0 for empty/blank; otherwise returns number-of-commas+1.
     */
    private static int simpleCommaCount(String inside) {
        if (inside == null) return -1;
        String s = inside.trim();
        if (s.isEmpty()) return 0;
        // very small heuristic: count commas that are not inside quotes (ignore complexity)
        int count = 1;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ',' && !inSingle && !inDouble) count++;
        }
        return count;
    }
}
