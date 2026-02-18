package ai.brokk.analyzer.usages;

import ai.brokk.AbstractService;
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
import ai.brokk.util.FileUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
public final class LlmUsageAnalyzer implements UsageAnalyzer {
    private static final Logger logger = LogManager.getLogger(LlmUsageAnalyzer.class);

    private final IProject project;
    private final IAnalyzer analyzer;
    private final AbstractService service;
    private final @Nullable Llm llm;
    private final int maxUsages;

    public LlmUsageAnalyzer(
            IProject project, IAnalyzer analyzer, AbstractService service, @Nullable Llm llm, int maxUsages) {
        this.project = project;
        this.analyzer = analyzer;
        this.service = service;
        this.llm = llm;
        this.maxUsages = maxUsages;
    }

    @Override
    public FuzzyResult findUsages(List<CodeUnit> overloads, Set<ProjectFile> candidateFiles)
            throws InterruptedException {
        var target = overloads.getFirst();
        final String identifier = target.identifier();
        Language lang = Languages.fromExtension(target.source().extension());

        var templates = lang.getSearchPatterns(target.kind());
        var searchPatterns = templates.stream()
                .map(template -> template.replace("$ident", Pattern.quote(identifier)))
                .collect(Collectors.toSet());

        var matchingCodeUnits =
                analyzer.searchDefinitions("\\b%s\\b".formatted(Pattern.quote(identifier)), false).stream()
                        .filter(cu -> cu.identifier().equals(identifier))
                        .collect(Collectors.toSet());
        var isUnique = matchingCodeUnits.size() == 1;

        var hits = extractUsageHits(candidateFiles, searchPatterns).stream()
                .filter(h -> !h.enclosing().equals(target))
                .collect(Collectors.toSet());

        if (isUnique) {
            return new FuzzyResult.Success(Map.of(target, hits));
        } else if (hits.size() > maxUsages) {
            return new FuzzyResult.TooManyCallsites(target.shortName(), hits.size(), maxUsages);
        }

        if (llm != null && !hits.isEmpty()) {
            var alternatives = matchingCodeUnits.stream()
                    .filter(cu -> !cu.fqName().equals(target.fqName()))
                    .collect(Collectors.toSet());

            var hierarchyProvider = analyzer.as(TypeHierarchyProvider.class);
            Collection<CodeUnit> polymorphicMatches = hierarchyProvider
                    .map(provider -> provider.getPolymorphicMatches(target, analyzer))
                    .orElse(List.of());
            boolean hierarchySupported = hierarchyProvider.isPresent();

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
            return new FuzzyResult.Ambiguous(target.shortName(), matchingCodeUnits, resultHitsByOverload);
        }

        return new FuzzyResult.Ambiguous(target.shortName(), matchingCodeUnits, Map.of(target, hits));
    }

    private Set<UsageHit> extractUsageHits(Set<ProjectFile> candidateFiles, Set<String> searchPatterns) {
        var hits = new ConcurrentHashMap<UsageHit, Boolean>();
        final var patterns = searchPatterns.stream().map(Pattern::compile).toList();

        candidateFiles.parallelStream().forEach(file -> {
            try {
                if (!file.isText()) return;
                var contentOpt = file.read();
                if (contentOpt.isEmpty()) return;
                var content = contentOpt.get();
                if (content.isEmpty()) return;

                var lines = content.split("\\R", -1);
                var lineStarts = FileUtil.computeLineStarts(content);

                for (var pattern : patterns) {
                    var matcher = pattern.matcher(content);
                    while (matcher.find()) {
                        int start = matcher.start();
                        int end = matcher.end();
                        int startByte = content.substring(0, start).getBytes(StandardCharsets.UTF_8).length;
                        int endByte = startByte + matcher.group().getBytes(StandardCharsets.UTF_8).length;

                        if (!analyzer.isAccessExpression(file, startByte, endByte)) continue;

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
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to extract usage hits from {}: {}", file, e.toString());
            }
        });
        return Set.copyOf(hits.keySet());
    }

    public static Map<CodeUnit, Set<UsageHit>> filterByConfidence(Map<CodeUnit, Set<UsageHit>> allHitsByOverload) {
        return allHitsByOverload.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream()
                        .filter(h -> h.confidence() >= 0.1)
                        .collect(Collectors.toSet())))
                .entrySet()
                .stream()
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
