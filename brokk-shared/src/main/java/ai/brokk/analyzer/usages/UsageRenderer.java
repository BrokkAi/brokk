package ai.brokk.analyzer.usages;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Formats usage-analysis results for context fragments and MCP search tools. */
public final class UsageRenderer {
    private UsageRenderer() {}

    public enum Mode {
        FULL,
        SAMPLE
    }

    public record Output(String text, Set<ProjectFile> files, boolean hasUsages, int hitCount) {}

    public static Output render(
            IAnalyzer analyzer, String targetIdentifier, List<CodeUnit> overloads, FuzzyResult result, Mode mode) {
        if (overloads.isEmpty()) {
            throw new IllegalArgumentException("overloads must not be empty");
        }

        var either = result.toEither();
        if (!either.hasUsages()) {
            return new Output(either.getErrorMessage(), Set.of(), false, 0);
        }

        CodeUnit definingOwner = analyzer.parentOf(overloads.getFirst()).orElse(overloads.getFirst());
        var hits = either.getUsages().stream()
                .filter(hit -> {
                    var owner = analyzer.parentOf(hit.enclosing()).orElse(hit.enclosing());
                    return !owner.equals(definingOwner);
                })
                .sorted(Comparator.comparing((UsageHit hit) -> hit.enclosing().fqName())
                        .thenComparing(hit -> hit.file().toString())
                        .thenComparingInt(UsageHit::line))
                .toList();

        var callSites = hits.stream()
                .map(hit -> "- `%s` (%s:%d)"
                        .formatted(hit.enclosing().fqName(), hit.file().getRelPath(), hit.line()))
                .collect(Collectors.joining("\n"));

        List<AnalyzerUtil.CodeWithSource> sources =
                switch (mode) {
                    case SAMPLE -> AnalyzerUtil.sampleUsages(analyzer, hits);
                    case FULL ->
                        AnalyzerUtil.processUsages(
                                analyzer,
                                hits.stream()
                                        .map(UsageHit::enclosing)
                                        .distinct()
                                        .toList());
                };
        var sourceText = sources.stream().map(AnalyzerUtil.CodeWithSource::code).collect(Collectors.joining("\n\n"));
        var sourcePrefix =
                switch (mode) {
                    case SAMPLE -> "\n\nExamples:\n\n";
                    case FULL -> sourceText.isEmpty() ? "" : "\n";
                };
        var files = hits.stream().map(UsageHit::file).collect(Collectors.toSet());
        var text = "# Usages of %s\n\nCall sites (%d):\n%s%s%s"
                .formatted(targetIdentifier, hits.size(), callSites, sourcePrefix, sourceText)
                .trim();

        return new Output(text, files, true, hits.size());
    }
}
