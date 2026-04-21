package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.FileUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

        if (hits.size() > maxUsages) {
            logger.debug("Too many usage hits for {}: {} > {}", target.fqName(), hits.size(), maxUsages);
            return new FuzzyResult.TooManyCallsites(target.shortName(), hits.size(), maxUsages);
        }

        if (isUnique) {
            return new FuzzyResult.Success(Map.of(target, hits));
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

                        enclosingCodeUnit.ifPresent(codeUnit ->
                                hits.put(new UsageHit(file, lineIdx + 1, start, end, codeUnit, 1.0, snippet), true));
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to extract usage hits from {}: {}", file, e.toString());
            }
        });
        return Set.copyOf(hits.keySet());
    }
}
