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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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
 *   * We DO NOT change the public behavioral contract of FuzzyUsageFinder. The heuristic is applied conservatively and
 *     does not remove hits when argument counts cannot be determined. Polymorphic and non-function symbols are not
 *     negatively impacted by this heuristic.
 *
 * - Where applied: After extractUsageHits produces raw UsageHit instances (which include a small snippet and the
 *   enclosing CodeUnit), but before invoking the LLM-based RelevanceClassifier. The pipeline will:
 *     1) Collect raw hits (existing extractUsageHits).
 *     2) Apply parameter-count filtering to exclude obvious mismatches (when reliable counts are available).
 *     3) If ambiguous and LLM is available, fall back to LLM prompts (existing code).
 *
 * The helper parsing functions and the filtering step are implemented below. The heuristic is conservative and
 * preserves existing flows when insufficient information is available.
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
     * Find usages for a list of overloads.
     *
     * <p>For an empty project/analyzer, returns Success with an empty hit list.
     */
    private FuzzyResult findUsages(List<CodeUnit> overloads, int maxFiles, int maxUsages) throws InterruptedException {
        assert !overloads.isEmpty() : "overloads must not be empty";
        var target = overloads.getFirst();

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

        // If the target is signature-qualified, we check uniqueness against the exact fqName.
        // This prevents overloads from triggering ambiguity flows when the user requested a specific signature.
        boolean targetIsSignatureQualified = target.fqName().contains("(");
        var fqNameMatches = targetIsSignatureQualified ? analyzer.getDefinitions(target.fqName()) : List.<CodeUnit>of();

        // Note: isUnique is computed later AFTER parameter-count filtering so that the heuristic can reduce candidates
        // before we decide uniqueness vs ambiguous/LLM flows.

        // Use a fast substring scan to prefilter candidate files by the raw identifier, not the regex
        Set<ProjectFile> filesToSearch = analyzer.getProject().getAnalyzableFiles(lang);
        var patterns = SearchTools.compilePatterns(List.of(identifier));
        Set<ProjectFile> candidateFiles =
                SearchTools.findFilesContainingPatterns(patterns, filesToSearch).matches();

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
                .filter(h -> !h.enclosing().equals(target))
                .collect(Collectors.toSet());

        logger.debug(
                "Extracted {} usage hits for {} from {} candidate files",
                hits.size(),
                target.fqName(),
                candidateFiles.size());

        //
        // New: Parameter-count based heuristic filtering (conservative).
        //
        // - Only applies when the target is a function-like unit AND we can obtain declaration-side parameter counts.
        // - For each UsageHit we attempt to estimate the call-site argument count. If the argument count is
        //   determinable and the declaration-side parameter-count set is non-empty, we exclude hits whose argument
        //   count does not match any declaration parameter count.
        // - If the argument count is unknown, or we couldn't extract any reliable declaration parameter counts, we
        //   leave hits unfiltered to preserve prior behavior.
        //
        // This filtering is intentionally conservative and runs BEFORE uniqueness/LLM decisions so downstream logic
        // sees a reduced (but still correct) candidate set.
        try {
            if (target.isFunction()) {
                var declParamCounts = overloads.stream()
                        .map(CodeUnit::signature)
                        .mapToInt(FuzzyUsageFinder::parseParameterCountFromSignature)
                        .filter(count -> count >= 0)
                        .boxed()
                        .collect(Collectors.toSet());

                // If we are searching for a specific signature, we only care about that signature's count.
                if (targetIsSignatureQualified) {
                    int targetCount = parseParameterCountFromSignature(target.signature());
                    if (targetCount >= 0) {
                        declParamCounts = Set.of(targetCount);
                    }
                }

                if (!declParamCounts.isEmpty() && !hits.isEmpty()) {
                    Set<UsageHit> filtered = new HashSet<>();
                    for (var hit : hits) {
                        OptionalInt optArgCount = estimateArgumentCount(hit, target);
                        if (optArgCount.isPresent()) {
                            int argCount = optArgCount.getAsInt();
                            if (declParamCounts.contains(argCount)) {
                                filtered.add(hit);
                            } else {
                                // Exclude obvious mismatches
                                logger.debug(
                                        "Excluding hit at {}:{} for {} due to arg count mismatch (arg={}, candidates={})",
                                        hit.file(),
                                        hit.line(),
                                        target.fqName(),
                                        argCount,
                                        declParamCounts);
                            }
                        } else {
                            // Unknown arg count => keep the hit (conservative)
                            filtered.add(hit);
                        }
                    }
                    // Replace hits with filtered set
                    hits = filtered;
                    logger.debug(
                            "After parameter-count filtering: {} hits remain for {}", hits.size(), target.fqName());
                } else {
                    logger.debug(
                            "Skipping parameter-count filtering for {}: declCountsEmpty={} hitsEmpty={}",
                            target.fqName(),
                            declParamCounts.isEmpty(),
                            hits.isEmpty());
                }
            }
        } catch (Exception e) {
            // Be conservative: on unexpected errors we skip heuristic and proceed with original hits.
            logger.debug("Parameter-count heuristic failed for {}: {}", target.fqName(), e.toString());
        }

        // Now compute uniqueness after filtering so that downstream flows (LLM / Success) operate on the pruned set.
        // A target is unique if there is only one definition for its identifier,
        // OR if the target is signature-qualified and has exactly one matching definition.
        var isUnique = matchingCodeUnits.size() == 1 || (targetIsSignatureQualified && fqNameMatches.size() == 1);

        if (isUnique) {
            // Case 2: This is a uniquely named code unit, no need to check with LLM.
            logger.debug("Found {} hits for unique code unit {}", hits.size(), target);
            return new FuzzyResult.Success(Map.of(target, hits));
        } else if (hits.size() > maxUsages) {
            // Case 3: Too many call sites to disambiguate with the LLM
            logger.debug(
                    "Too many call sites to disambiguate with the LLM {}: {} usage locations matched",
                    target,
                    hits.size());
            return new FuzzyResult.TooManyCallsites(target.shortName(), hits.size(), maxUsages);
        }

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
                        8_000,
                        overloads);

                var task = new RelevanceTask(prompt.filterDescription(), prompt.promptText(), overloads.size());
                tasks.add(task);
                taskToHits.add(hitsInGroup);
            }

            var scores = RelevanceClassifier.relevanceScoreBatch(project.getDiskCache(), llm, service, tasks);
            var resultHitsByOverload = new HashMap<CodeUnit, Set<UsageHit>>(overloads.size());

            for (int i = 0; i < tasks.size(); i++) {
                var task = tasks.get(i);
                var hitsInGroup = taskToHits.get(i);
                var overloadScores = scores.getOrDefault(task, Collections.nCopies(overloads.size(), 0.0));

                // Find the overload with the highest score
                int bestIdx = 0;
                double bestScore = overloadScores.getFirst();
                for (int j = 1; j < overloadScores.size(); j++) {
                    if (overloadScores.get(j) > bestScore) {
                        bestScore = overloadScores.get(j);
                        bestIdx = j;
                    }
                }

                var bestOverload = overloads.get(bestIdx);
                for (var hit : hitsInGroup) {
                    resultHitsByOverload
                            .computeIfAbsent(bestOverload, k -> new HashSet<>())
                            .add(hit.withConfidence(bestScore));
                }
            }

            logger.debug(
                    "Found {} disambiguated hits across {} overloads",
                    resultHitsByOverload.values().stream().mapToInt(Set::size).sum(),
                    resultHitsByOverload.size());
            return new FuzzyResult.Ambiguous(target.shortName(), matchingCodeUnits, resultHitsByOverload);
        }

        return new FuzzyResult.Ambiguous(target.shortName(), matchingCodeUnits, Map.of(target, hits));
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
            return new FuzzyResult.Success(Map.of());
        }

        String lookupName = fqName;
        String requestedSignature = null;
        int parenIdx = fqName.indexOf('(');
        if (parenIdx > 0 && fqName.endsWith(")")) {
            lookupName = fqName.substring(0, parenIdx);
            requestedSignature = fqName.substring(parenIdx);
        }

        var definitions = analyzer.getDefinitions(lookupName);
        if (definitions.isEmpty()) {
            logger.debug("No definitions found for fqName={}; returning Failure", lookupName);
            return new FuzzyResult.Failure(fqName, "No definitions found");
        }

        List<CodeUnit> overloads;
        if (requestedSignature != null) {
            final String finalSig = requestedSignature;
            overloads = definitions.stream()
                    .filter(def -> def.signature() != null && def.signature().equals(finalSig))
                    .toList();

            if (overloads.isEmpty()) {
                logger.debug("No definitions found matching signature {} for {}", finalSig, lookupName);
                return new FuzzyResult.Failure(fqName, "No definitions found matching signature");
            }
        } else {
            overloads = List.copyOf(definitions);
        }

        var result = findUsages(overloads, maxFiles, maxUsages);
        Map<CodeUnit, Set<UsageHit>> allHitsByOverload =
                switch (result) {
                    case FuzzyResult.Success success -> success.hitsByOverload();
                    case FuzzyResult.Ambiguous ambiguous -> ambiguous.hitsByOverload();
                    case FuzzyResult.TooManyCallsites tooMany -> {
                        logger.debug(
                                "Too many callsites for {} ({}): {} > {}",
                                fqName,
                                overloads.getFirst().kind(),
                                tooMany.totalCallsites(),
                                tooMany.limit());
                        yield Map.of();
                    }
                    case FuzzyResult.Failure failure -> {
                        logger.debug("Failure when finding usages of {}: {}", fqName, failure.reason());
                        yield Map.of();
                    }
                };

        // Throw out very low confidence
        var filteredHitsByOverload = filterByConfidence(allHitsByOverload);
        int totalBefore =
                allHitsByOverload.values().stream().mapToInt(Set::size).sum();
        int totalAfter =
                filteredHitsByOverload.values().stream().mapToInt(Set::size).sum();
        logger.debug("Filtered to {} hits for {} out of {}", totalAfter, fqName, totalBefore);

        return new FuzzyResult.Success(filteredHitsByOverload);
    }

    static Map<CodeUnit, Set<UsageHit>> filterByConfidence(Map<CodeUnit, Set<UsageHit>> allHitsByOverload) {
        return allHitsByOverload.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream()
                        .filter(h -> h.confidence() >= 0.1)
                        .collect(Collectors.toSet())))
                .entrySet()
                .stream()
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
     * NEW HELPER:
     *
     * Estimate the number of arguments passed at the call corresponding to a UsageHit.
     *
     * - Returns OptionalInt.empty() when unknown/unreliable.
     * - Applies only for function-like target units and for languages we support (initially Java).
     * - Uses only the snippet text previously captured; does not read the file or call analyzer APIs that trigger I/O.
     *
     * Heuristic (Java):
     *  - Look for the target's identifier in the snippet and try to find a '(' following that occurrence.
     *  - Extract the parenthesized text on the same line (or nearby) and count top-level commas respecting simple
     *    nesting for angle brackets and parentheses and string quoting.
     *  - Be conservative: if parentheses appear unbalanced or we cannot confidently locate the argument list,
     *    return empty.
     */
    private OptionalInt estimateArgumentCount(UsageHit hit, CodeUnit target) {
        // Only attempt for function-like code unit kinds.
        if (!target.isFunction()) {
            return OptionalInt.empty();
        }

        // Only handle Java for now.
        Language lang = Languages.fromExtension(hit.file().extension());
        if (lang != Languages.JAVA) {
            return OptionalInt.empty();
        }

        String snippet = hit.snippet();
        if (snippet.isBlank()) {
            return OptionalInt.empty();
        }

        String identifier = target.identifier();
        if (identifier.isBlank()) {
            return OptionalInt.empty();
        }

        // Find occurrences of the identifier in the snippet. Choose the occurrence that has a '(' following it
        // reasonably soon. Be conservative about method references ("::") and field access (".").
        int bestParenPos = -1;
        int bestDistance = Integer.MAX_VALUE;
        String[] lines = snippet.split("\\R", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int from = 0;
            while (true) {
                int pos = line.indexOf(identifier, from);
                if (pos < 0) break;
                // ignore occurrences that are part of a longer identifier (alnum or $ after or before)
                boolean leftOk = pos == 0 || !Character.isJavaIdentifierPart(line.charAt(pos - 1));
                int afterIdx = pos + identifier.length();
                boolean rightOk = afterIdx >= line.length() || !Character.isJavaIdentifierPart(line.charAt(afterIdx));
                if (!leftOk || !rightOk) {
                    from = pos + 1;
                    continue;
                }
                // check if next non-space chars include '(' (skip dot, spaces, generics)
                int scan = afterIdx;
                // Skip spaces
                while (scan < line.length() && Character.isWhitespace(line.charAt(scan))) scan++;

                // If '::' method reference is found near the identifier, we cannot estimate argument count.
                // Search slightly before and after for '::'
                boolean isMethodRef =
                        (scan + 1 < line.length() && line.charAt(scan) == ':' && line.charAt(scan + 1) == ':')
                                || (pos >= 2 && line.substring(pos - 2, pos).equals("::"));
                if (isMethodRef) {
                    return OptionalInt.empty();
                }

                // Find '(' after pos in the same line
                int parenPos = -1;
                for (int k = afterIdx; k < line.length(); k++) {
                    char c = line.charAt(k);
                    if (c == '(') {
                        parenPos = k;
                        break;
                    }
                    // stop scanning if we hit a semicolon or comment start which suggests no call here
                    if (c == ';' || c == '{' || c == '}' || c == '/') {
                        break;
                    }
                }
                if (parenPos >= 0) {
                    // Prefer the occurrence closest to the center of the snippet (heuristic)
                    int center = snippet.length() / 2;
                    int globalPos = computeGlobalPos(lines, li, pos);
                    int distance = Math.abs(globalPos - center);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestParenPos = computeGlobalPos(lines, li, parenPos);
                    }
                }
                from = pos + 1;
            }
        }

        // If not found a '(' following identifier on same lines, try a broader search for any parentheses pair in
        // snippet
        if (bestParenPos < 0) {
            int lp = snippet.indexOf('(');
            int rp = -1;
            if (lp >= 0) rp = snippet.indexOf(')', lp + 1);
            if (lp >= 0 && rp > lp) {
                String inside = snippet.substring(lp + 1, rp);
                int cnt = countTopLevelArgs(inside);
                if (cnt >= 0) return OptionalInt.of(cnt);
            }
            return OptionalInt.empty();
        }

        // Now extract the parenthesized content starting from bestParenPos within the snippet
        int relLp = bestParenPos;
        // find matching ')' considering nested parentheses within snippet bounds
        int depth = 0;
        int rp = -1;
        for (int i = relLp; i < snippet.length(); i++) {
            char c = snippet.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    rp = i;
                    break;
                }
            } else if (c == '"' || c == '\'') {
                // Skip strings naively
                char quote = c;
                i++;
                while (i < snippet.length()) {
                    char cc = snippet.charAt(i);
                    if (cc == '\\') {
                        i++; // skip escaped char
                    } else if (cc == quote) {
                        break;
                    }
                    i++;
                }
            }
        }
        if (rp < 0) {
            // Unbalanced or not within snippet
            return OptionalInt.empty();
        }
        String inside = snippet.substring(relLp + 1, rp);
        int cnt = countTopLevelArgs(inside);
        if (cnt < 0) return OptionalInt.empty();
        return OptionalInt.of(cnt);
    }

    private static int countTopLevelArgs(String inside) {
        int len = inside.length();
        int depthParen = 0;
        int depthAngle = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int args = 0;
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < len; i++) {
            char c = inside.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                token.append(c);
                continue;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                token.append(c);
                continue;
            }
            if (inSingle || inDouble) {
                if (c == '\\') {
                    token.append(c);
                    if (i + 1 < len) {
                        token.append(inside.charAt(i + 1));
                        i++;
                    }
                    continue;
                } else {
                    token.append(c);
                    continue;
                }
            }
            if (c == '(') {
                depthParen++;
                token.append(c);
            } else if (c == ')') {
                if (depthParen > 0) depthParen--;
                token.append(c);
            } else if (c == '<') {
                depthAngle++;
                token.append(c);
            } else if (c == '>') {
                if (depthAngle > 0) depthAngle--;
                token.append(c);
            } else if (c == ',' && depthParen == 0 && depthAngle == 0) {
                // top-level separator
                if (token.toString().trim().length() > 0) {
                    args++;
                }
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        if (token.toString().trim().length() > 0) {
            args++;
        }
        // If the inside was empty or only whitespace, return 0
        if (args == 0 && inside.trim().isEmpty()) return 0;
        return args;
    }

    private static int computeGlobalPos(String[] lines, int lineIndex, int col) {
        int pos = 0;
        for (int i = 0; i < lineIndex; i++) {
            pos += lines[i].length() + 1; // +1 for the newline
        }
        return pos + col;
    }
}
