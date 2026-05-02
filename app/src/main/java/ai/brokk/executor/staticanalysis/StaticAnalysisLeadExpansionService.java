package ai.brokk.executor.staticanalysis;

import ai.brokk.IAppContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TestFileHeuristics;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.analyzer.usages.RegexUsageAnalyzer;
import ai.brokk.analyzer.usages.UsageFinder;
import ai.brokk.util.PathNormalizer;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class StaticAnalysisLeadExpansionService {
    private static final int MAX_SYMBOLS_PER_FILE = 8;
    private static final int MAX_USAGE_CANDIDATE_FILES = 250;
    private static final int MAX_USAGES_PER_SYMBOL = 80;
    private static final long MAX_TEXT_FALLBACK_FILE_BYTES = 512 * 1024;
    private static final String COMMENT_DENSITY_TOOL = "reportCommentDensityForFiles";
    private static final String COGNITIVE_COMPLEXITY_TOOL = "computeCognitiveComplexity";
    private static final String EXCEPTION_HANDLING_TOOL = "reportExceptionHandlingSmells";
    private static final String TEST_ASSERTION_TOOL = "reportTestAssertionSmells";

    private final IAppContextManager contextManager;

    public StaticAnalysisLeadExpansionService(IAppContextManager contextManager) {
        this.contextManager = contextManager;
    }

    public StaticAnalysisSeedDtos.Response expandLeads(StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest request) {
        var knownFiles = request.knownFiles().stream()
                .map(this::normalizePath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var frontierFiles = request.frontierFiles().stream()
                .map(this::normalizePath)
                .collect(Collectors.toCollection(ArrayList::new));
        if (frontierFiles.isEmpty()) {
            frontierFiles.addAll(knownFiles);
        }
        var events = new ArrayList<StaticAnalysisSeedDtos.Event>();
        events.add(event(
                request.scanId(),
                "started",
                List.of("usage_analysis"),
                frontierFiles,
                "STATIC_SEED_EXPANSION_STARTED",
                "Expanding Records Desk leads through deterministic usage analysis.",
                0,
                List.of()));

        var deadline =
                System.nanoTime() + Duration.ofMillis(request.maxDurationMs()).toNanos();

        var candidates = new LinkedHashMap<String, Candidate>();
        var capped = false;
        try {
            var analyzer = contextManager.getAnalyzer();
            var finder = new UsageFinder(
                    contextManager.getProject(),
                    analyzer,
                    UsageFinder.createDefaultProvider(),
                    new RegexUsageAnalyzer(analyzer),
                    null);

            var sourceRank = 0;
            for (var sourcePath : frontierFiles) {
                if (timedOut(deadline)) {
                    capped = true;
                    break;
                }
                sourceRank++;
                var sourceFile = contextManager.toFile(sourcePath);
                if (!sourceFile.exists()) {
                    continue;
                }
                var symbols = declarations(analyzer, sourceFile).stream()
                        .filter(cu -> !cu.fqName().isBlank())
                        .sorted(Comparator.comparing(CodeUnit::fqName))
                        .limit(MAX_SYMBOLS_PER_FILE)
                        .toList();
                var symbolCount = 0;
                for (var symbol : symbols) {
                    if (timedOut(deadline)) {
                        capped = true;
                        break;
                    }
                    symbolCount++;
                    Map<ProjectFile, Integer> usageCountByFile;
                    try {
                        var query = finder.query(List.of(symbol), MAX_USAGE_CANDIDATE_FILES, MAX_USAGES_PER_SYMBOL);
                        if (query.candidateFilesTruncated()) {
                            capped = true;
                        }
                        usageCountByFile = usageCountByFile(query.result());
                    } catch (RuntimeException e) {
                        usageCountByFile = textUsageCountByFile(analyzer, symbol, deadline);
                    }
                    for (var entry : usageCountByFile.entrySet()) {
                        var file = entry.getKey();
                        if (file.equals(sourceFile) || knownFiles.contains(externalPath(file))) {
                            continue;
                        }
                        var candidate = candidates.computeIfAbsent(externalPath(file), ignored -> new Candidate(file));
                        candidate.merge(
                                sourcePath,
                                sourceRank,
                                symbolCount,
                                entry.getValue(),
                                Math.min(0.88, 0.48 + (entry.getValue() * 0.04) + (symbolCount * 0.01)));
                    }
                    if (candidates.size() >= request.maxResults() * 2) {
                        capped = true;
                        break;
                    }
                }
                if (capped || candidates.size() >= request.maxResults() * 2) {
                    break;
                }
            }

            var ranked = new ArrayList<StaticAnalysisSeedDtos.SeedRecord>();
            var rank = 1;
            for (var candidate : candidates.values().stream()
                    .sorted(Comparator.comparingDouble(Candidate::score)
                            .reversed()
                            .thenComparing(candidate -> externalPath(candidate.file)))
                    .limit(request.maxResults())
                    .toList()) {
                ranked.add(candidate.toRecord(analyzer, rank++, externalPath(candidate.file)));
            }

            var state = capped || timedOut(deadline) ? "capped" : ranked.isEmpty() ? "skipped" : "completed";
            var code =
                    switch (state) {
                        case "completed" -> "STATIC_SEED_EXPANSION_COMPLETED";
                        case "capped" -> "STATIC_SEED_EXPANSION_CAPPED";
                        default -> "STATIC_SEED_EXPANSION_NO_RESULTS";
                    };
            var message =
                    switch (state) {
                        case "completed" -> "Usage expansion found additional Records Desk leads.";
                        case "capped" -> "Usage expansion returned partial Records Desk leads after reaching a cap.";
                        default -> "Usage expansion found no additional Records Desk leads.";
                    };
            events.add(event(
                    request.scanId(),
                    state,
                    List.of("usage_analysis"),
                    ranked.stream().map(StaticAnalysisSeedDtos.SeedRecord::file).toList(),
                    code,
                    message,
                    0,
                    List.of()));
            return new StaticAnalysisSeedDtos.Response(
                    request.scanId(), StaticAnalysisSeedDtos.PHASE_STATIC_SEED, state, ranked, List.of(), events);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            events.add(event(
                    request.scanId(),
                    "failed",
                    List.of("usage_analysis"),
                    List.of(),
                    "STATIC_SEED_EXPANSION_INTERRUPTED",
                    "Usage expansion was interrupted.",
                    0,
                    List.of()));
            return new StaticAnalysisSeedDtos.Response(
                    request.scanId(), StaticAnalysisSeedDtos.PHASE_STATIC_SEED, "failed", List.of(), List.of(), events);
        } catch (Exception e) {
            events.add(event(
                    request.scanId(),
                    "failed",
                    List.of("usage_analysis"),
                    List.of(),
                    "STATIC_SEED_EXPANSION_ERROR",
                    "Usage expansion failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    0,
                    List.of()));
            return new StaticAnalysisSeedDtos.Response(
                    request.scanId(), StaticAnalysisSeedDtos.PHASE_STATIC_SEED, "failed", List.of(), List.of(), events);
        }
    }

    private static List<CodeUnit> declarations(IAnalyzer analyzer, ProjectFile file) {
        var result = new ArrayList<CodeUnit>();
        var work = new ArrayDeque<>(analyzer.getTopLevelDeclarations(file));
        while (!work.isEmpty()) {
            var next = work.removeFirst();
            result.add(next);
            work.addAll(analyzer.getDirectChildren(next));
        }
        return result;
    }

    private static Map<ProjectFile, Integer> usageCountByFile(FuzzyResult result) {
        var either = result.toEither();
        if (!either.hasUsages()) {
            return Map.of();
        }
        var counts = new LinkedHashMap<ProjectFile, Integer>();
        for (var hit : either.getUsages()) {
            counts.merge(hit.file(), 1, Integer::sum);
        }
        return counts;
    }

    private Map<ProjectFile, Integer> textUsageCountByFile(IAnalyzer analyzer, CodeUnit symbol, long deadline)
            throws InterruptedException {
        var counts = new LinkedHashMap<ProjectFile, Integer>();
        var files = sourceFiles(analyzer).stream()
                .sorted(Comparator.comparing(this::externalPath))
                .limit(MAX_USAGE_CANDIDATE_FILES)
                .toList();
        for (var file : files) {
            if (timedOut(deadline) || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Static analysis lead expansion fallback timed out");
            }
            if (file.equals(symbol.source()) || !file.exists()) {
                continue;
            }
            if (file.size().orElse(0L) > MAX_TEXT_FALLBACK_FILE_BYTES) {
                continue;
            }
            try {
                var text = Files.readString(file.absPath());
                var count = text.split(Pattern.quote(symbol.identifier()), -1).length - 1;
                if (count > 0) {
                    counts.put(file, count);
                }
            } catch (Exception ignored) {
                // Ignore unreadable files during best-effort lead expansion.
            }
        }
        return counts;
    }

    private Set<ProjectFile> sourceFiles(IAnalyzer analyzer) {
        var files = new LinkedHashSet<>(analyzer.getAnalyzedFiles());
        var project = contextManager.getProject();
        project.getAnalyzerLanguages().stream()
                .flatMap(language -> project.getAnalyzableFiles(language).stream())
                .forEach(files::add);
        return files;
    }

    private static boolean timedOut(long deadlineNanos) {
        return System.nanoTime() >= deadlineNanos;
    }

    private String normalizePath(String path) {
        return PathNormalizer.canonicalizeForProject(
                path, contextManager.getProject().getRoot());
    }

    private String externalPath(ProjectFile file) {
        return PathNormalizer.canonicalizeForProject(
                file.toString(), contextManager.getProject().getRoot());
    }

    private static StaticAnalysisSeedDtos.Event event(
            String scanId,
            String state,
            List<String> tools,
            List<String> files,
            String code,
            String message,
            int findingCount,
            List<String> findingTypes) {
        return StaticAnalysisSeedDtos.event(
                scanId, state, tools, files, null, null, code, message, findingCount, findingTypes, List.of());
    }

    private static final class Candidate {
        private final ProjectFile file;
        private final Map<String, Object> signalValues = new LinkedHashMap<>();
        private double score;

        private Candidate(ProjectFile file) {
            this.file = file;
        }

        private double score() {
            return score;
        }

        private void merge(String sourceFile, int relatedRank, int symbolCount, int usageCount, double score) {
            this.score = Math.max(this.score, score);
            signalValues.put("sourceFile", sourceFile);
            signalValues.put("relatedRank", relatedRank);
            signalValues.merge("symbolCount", symbolCount, (a, b) -> Math.max((Integer) a, (Integer) b));
            signalValues.merge("usageCount", usageCount, (a, b) -> (Integer) a + (Integer) b);
        }

        private StaticAnalysisSeedDtos.SeedRecord toRecord(IAnalyzer analyzer, int rank, String filePath) {
            var suggestedTools = new ArrayList<String>();
            suggestedTools.add(EXCEPTION_HANDLING_TOOL);
            suggestedTools.add(COMMENT_DENSITY_TOOL);
            suggestedTools.add(COGNITIVE_COMPLEXITY_TOOL);
            if (TestFileHeuristics.isTestFile(file, analyzer)) {
                suggestedTools.add(TEST_ASSERTION_TOOL);
            }
            return new StaticAnalysisSeedDtos.SeedRecord(
                    filePath,
                    rank,
                    new StaticAnalysisSeedDtos.Selection(
                            "usage_expansion",
                            rank,
                            score,
                            List.of(new StaticAnalysisSeedDtos.Signal("usage_connectivity", signalValues))),
                    List.of(),
                    List.copyOf(suggestedTools));
        }
    }
}
