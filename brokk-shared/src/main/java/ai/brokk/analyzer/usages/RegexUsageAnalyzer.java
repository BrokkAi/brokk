package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.util.FileUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A shared, dependency-light usage analyzer that extracts usage hits via regex and analyzer heuristics.
 *
 * <p>This intentionally does not use any app-layer services or LLM scoring.
 */
public final class RegexUsageAnalyzer implements UsageAnalyzer {
    private static final Logger logger = LogManager.getLogger(RegexUsageAnalyzer.class);

    private final IAnalyzer analyzer;

    public RegexUsageAnalyzer(IAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public FuzzyResult findUsages(List<CodeUnit> overloads, Set<ProjectFile> candidateFiles, int maxUsages)
            throws InterruptedException {
        if (overloads.isEmpty()) {
            return new FuzzyResult.Success(Map.of());
        }
        assertSameIdentifier(overloads);

        var queryPlan = QueryPlan.create(overloads, analyzer);
        var hitsByTarget = extractUsageHits(candidateFiles, queryPlan, maxUsages);

        for (var target : overloads) {
            var hits = hitsByTarget.getOrDefault(target, Set.of());
            if (hits.size() > maxUsages) {
                logger.debug("Too many usage hits for {}: {} > {}", target.fqName(), hits.size(), maxUsages);
                return new FuzzyResult.TooManyCallsites(target.shortName(), hits.size(), maxUsages);
            }
        }

        if (queryPlan.isUnique()) {
            return new FuzzyResult.Success(hitsByTarget);
        }

        var target = overloads.getFirst();
        return new FuzzyResult.Ambiguous(target.shortName(), queryPlan.matchingCodeUnits(), hitsByTarget);
    }

    private Map<CodeUnit, Set<UsageHit>> extractUsageHits(
            Set<ProjectFile> candidateFiles, QueryPlan queryPlan, int maxUsages) throws InterruptedException {
        var hitsByTarget = new ConcurrentHashMap<CodeUnit, Set<UsageHit>>();
        queryPlan.targets().forEach(target -> hitsByTarget.put(target, ConcurrentHashMap.newKeySet()));
        var exceededTargets = ConcurrentHashMap.<CodeUnit>newKeySet();

        var tasks = candidateFiles.stream()
                .<Callable<Void>>map(file -> () -> {
                    try {
                        if (!file.isText()) return null;
                        var contentOpt = file.read();
                        if (contentOpt.isEmpty()) return null;
                        var content = contentOpt.get();
                        if (content.isEmpty()) return null;
                        var sourceContent = SourceContent.of(content);
                        var sourceText = sourceContent.text();
                        if (sourceText.isEmpty()) return null;

                        var scan = new FileScanContext(sourceContent);

                        for (var searchPattern : queryPlan.searchPatterns()) {
                            var matcher = searchPattern.pattern().matcher(sourceText);
                            while (matcher.find()) {
                                int start = matcher.start();
                                int end = matcher.end();
                                int startByte = scan.charOffsetToUtf8ByteOffset(start);
                                int endByte = scan.charOffsetToUtf8ByteOffset(end);

                                if (!analyzer.isAccessExpression(file, startByte, endByte)) continue;

                                int lineIdx = scan.lineIndex(start);
                                var snippet = scan.snippetAround(lineIdx);

                                var range = new IAnalyzer.Range(startByte, endByte, lineIdx, lineIdx, lineIdx);
                                var enclosingCodeUnit = analyzer.enclosingCodeUnit(file, range);

                                enclosingCodeUnit.ifPresent(codeUnit -> {
                                    for (var target : searchPattern.targets()) {
                                        if (codeUnit.equals(target) || exceededTargets.contains(target)) {
                                            continue;
                                        }
                                        var targetHits = hitsByTarget.computeIfAbsent(
                                                target, ignored -> ConcurrentHashMap.newKeySet());
                                        if (targetHits.size() > maxUsages) {
                                            exceededTargets.add(target);
                                            continue;
                                        }
                                        targetHits.add(
                                                new UsageHit(file, lineIdx + 1, start, end, codeUnit, 1.0, snippet));
                                        if (targetHits.size() > maxUsages) {
                                            exceededTargets.add(target);
                                        }
                                    }
                                });
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to extract usage hits from {}: {}", file, e.toString());
                    }
                    return null;
                })
                .toList();
        var futures = UsageAnalysisExecutors.ioExecutor().invokeAll(tasks);
        for (var future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Regex usage scan failed", e.getCause());
            }
        }
        return hitsByTarget.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    private static void assertSameIdentifier(List<CodeUnit> overloads) {
        var identifier = overloads.getFirst().identifier();
        assert overloads.stream().allMatch(target -> target.identifier().equals(identifier))
                : "RegexUsageAnalyzer.findUsages expects same-identifier overloads";
    }

    private record QueryPlan(
            List<CodeUnit> targets,
            List<SearchPattern> searchPatterns,
            Set<CodeUnit> matchingCodeUnits,
            boolean isUnique) {
        private static QueryPlan create(List<CodeUnit> targets, IAnalyzer analyzer) {
            var targetsByPattern = new HashMap<String, Set<CodeUnit>>();
            var identifier = targets.getFirst().identifier();
            var matchingCodeUnits =
                    analyzer.searchDefinitions("\\b%s\\b".formatted(Pattern.quote(identifier)), false).stream()
                            .filter(cu -> cu.identifier().equals(identifier))
                            .collect(Collectors.toSet());

            for (var target : targets) {
                Language lang = Languages.fromExtension(target.source().extension());
                var templates = lang.getSearchPatterns(target.kind());
                for (String template : templates) {
                    var searchPattern = template.replace("$ident", Pattern.quote(target.identifier()));
                    targetsByPattern
                            .computeIfAbsent(searchPattern, ignored -> new HashSet<>())
                            .add(target);
                }
            }

            var searchPatterns = targetsByPattern.entrySet().stream()
                    .map(entry -> new SearchPattern(Pattern.compile(entry.getKey()), Set.copyOf(entry.getValue())))
                    .toList();
            return new QueryPlan(
                    List.copyOf(targets), searchPatterns, Set.copyOf(matchingCodeUnits), matchingCodeUnits.size() == 1);
        }
    }

    private record SearchPattern(Pattern pattern, Set<CodeUnit> targets) {}

    private static final class FileScanContext {
        private final SourceContent content;
        private @org.jetbrains.annotations.Nullable String[] lines;
        private int @org.jetbrains.annotations.Nullable [] lineStarts;

        private FileScanContext(SourceContent content) {
            this.content = content;
        }

        private int charOffsetToUtf8ByteOffset(int charOffset) {
            return content.charPositionToByteOffset(charOffset);
        }

        private int lineIndex(int charOffset) {
            return FileUtil.findLineIndexForOffset(lineStarts(), charOffset);
        }

        private String snippetAround(int lineIdx) {
            var snippetLines = lines();
            int startLine = Math.max(0, lineIdx - 3);
            int endLine = Math.min(snippetLines.length - 1, lineIdx + 3);
            return IntStream.rangeClosed(startLine, endLine)
                    .mapToObj(i -> snippetLines[i])
                    .collect(Collectors.joining("\n"));
        }

        private String[] lines() {
            if (lines == null) {
                lines = content.text().split("\\R", -1);
            }
            return lines;
        }

        private int[] lineStarts() {
            if (lineStarts == null) {
                lineStarts = FileUtil.computeLineStarts(content.text());
            }
            return lineStarts;
        }
    }
}
