package ai.brokk.executor.staticanalysis;

import ai.brokk.IAppContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitHotspotAnalyzer;
import ai.brokk.git.GitRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class StaticAnalysisSeedService {
    private static final Logger logger = LogManager.getLogger(StaticAnalysisSeedService.class);

    private static final int GIT_HOTSPOT_WINDOW_DAYS = 30;
    private static final int GIT_HOTSPOT_MAX_COMMITS = 200;
    private static final int COMPLEXITY_SIGNAL_THRESHOLD = 12;
    private static final int LARGE_FILE_BYTES_THRESHOLD = 16 * 1024;
    private static final int PREVIEW_FILE_CAP = 10;
    private static final int PREVIEW_FINDING_CAP = 12;
    private static final String LONG_METHOD_TOOL = "reportLongMethodAndGodObjectSmells";
    private static final String SIZE_SPRAWL_AGENT = "code-quality-size-sprawl";

    private final IAppContextManager contextManager;

    public StaticAnalysisSeedService(IAppContextManager contextManager) {
        this.contextManager = contextManager;
    }

    public StaticAnalysisSeedDtos.Response fetchSeeds(StaticAnalysisSeedDtos.NormalizedRequest request) {
        var events = new ArrayList<StaticAnalysisSeedDtos.Event>();
        events.add(event(
                request.scanId(),
                "started",
                List.of(),
                List.of(),
                null,
                "STATIC_SEED_STARTED",
                "Fetching deterministic static analysis seed files.",
                0,
                List.of()));

        var deadline =
                System.nanoTime() + Duration.ofMillis(request.maxDurationMs()).toNanos();
        var candidates = new LinkedHashMap<String, Candidate>();
        var capped = false;

        try {
            capped |= addWeightedSampleSeeds(request, candidates, deadline);
            capped |= addGitHotspots(request, candidates, deadline);
            capped |= addSizeComplexitySeeds(request, candidates, deadline);
            capped |= addUsageExpansionSeeds(request, candidates, deadline);

            var seeds = candidates.values().stream()
                    .sorted(Comparator.comparingDouble(Candidate::score)
                            .reversed()
                            .thenComparing(candidate -> candidate.file.toString()))
                    .limit(request.targetSeedCount())
                    .toList();

            var records = new ArrayList<StaticAnalysisSeedDtos.SeedRecord>();
            var rank = 1;
            for (var seed : seeds) {
                records.add(seed.toRecord(rank++));
            }
            var previews = addPreviewFindings(records, deadline);

            if (records.isEmpty()) {
                events.add(event(
                        request.scanId(),
                        "skipped",
                        List.of(),
                        List.of(),
                        null,
                        "STATIC_SEED_NO_INPUTS",
                        "No analyzer, git, context, or source-file inputs were available for static seed selection.",
                        0,
                        List.of()));
                return new StaticAnalysisSeedDtos.Response(
                        request.scanId(),
                        StaticAnalysisSeedDtos.PHASE_STATIC_SEED,
                        "skipped",
                        List.of(),
                        List.of(),
                        events);
            }

            var state = capped || timedOut(deadline) ? "capped" : "completed";
            events.add(event(
                    request.scanId(),
                    state,
                    List.of("analyzeGitHotspots", LONG_METHOD_TOOL),
                    records.stream()
                            .map(StaticAnalysisSeedDtos.SeedRecord::file)
                            .toList(),
                    null,
                    capped || timedOut(deadline) ? "STATIC_SEED_CAPPED" : "STATIC_SEED_COMPLETED",
                    capped || timedOut(deadline)
                            ? "Static analysis seed selection returned partial results after reaching a cap."
                            : "Static analysis seed selection completed.",
                    previews.size(),
                    previews.isEmpty() ? List.of() : List.of("maintainability_size")));
            return new StaticAnalysisSeedDtos.Response(
                    request.scanId(), StaticAnalysisSeedDtos.PHASE_STATIC_SEED, state, records, previews, events);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            events.add(event(
                    request.scanId(),
                    "failed",
                    List.of(),
                    List.of(),
                    null,
                    "STATIC_SEED_INTERRUPTED",
                    "Static analysis seed selection was interrupted.",
                    0,
                    List.of()));
            return new StaticAnalysisSeedDtos.Response(
                    request.scanId(), StaticAnalysisSeedDtos.PHASE_STATIC_SEED, "failed", List.of(), List.of(), events);
        } catch (Exception e) {
            events.add(event(
                    request.scanId(),
                    "failed",
                    List.of(),
                    List.of(),
                    null,
                    "STATIC_SEED_PROVIDER_ERROR",
                    "Static analysis seed selection failed: " + e.getMessage(),
                    0,
                    List.of()));
            return new StaticAnalysisSeedDtos.Response(
                    request.scanId(), StaticAnalysisSeedDtos.PHASE_STATIC_SEED, "failed", List.of(), List.of(), events);
        }
    }

    private boolean addGitHotspots(
            StaticAnalysisSeedDtos.NormalizedRequest request, Map<String, Candidate> candidates, long deadline) {
        if (timedOut(deadline)) return true;
        if (!(contextManager.getRepo() instanceof GitRepo gitRepo)) return false;

        try {
            var report = new GitHotspotAnalyzer(gitRepo, contextManager.getAnalyzer())
                    .analyze(
                            Instant.now().minus(Duration.ofDays(GIT_HOTSPOT_WINDOW_DAYS)),
                            null,
                            GIT_HOTSPOT_MAX_COMMITS,
                            Math.max(request.targetSeedCount() * 2, request.targetSeedCount()));
            var maxChurn = report.files().stream()
                    .mapToInt(GitHotspotAnalyzer.FileHotspotInfo::churn)
                    .max()
                    .orElse(1);
            for (var file : report.files()) {
                if (timedOut(deadline)) return true;
                var score = 0.35 + (0.45 * file.churn() / Math.max(1.0, maxChurn));
                var candidate = candidate(candidates, file.path(), score, "git_hotspot");
                candidate.addSignal(
                        "git_churn",
                        Map.of(
                                "windowDays", GIT_HOTSPOT_WINDOW_DAYS,
                                "commitCount", file.churn(),
                                "uniqueAuthors", file.uniqueAuthors()));
                if (file.complexity() > 0) {
                    candidate.addSignal("complexity", Map.of("maxCyclomaticComplexity", file.complexity()));
                    if (file.complexity() >= COMPLEXITY_SIGNAL_THRESHOLD) {
                        candidate.addTool(LONG_METHOD_TOOL);
                        candidate.addAgent(SIZE_SPRAWL_AGENT);
                    }
                }
                candidate.addAgent("code-quality-git-hotspots");
            }
            return report.truncated();
        } catch (Exception ignored) {
            logger.debug("Static seed git hotspot source was unavailable", ignored);
            return false;
        }
    }

    private boolean addSizeComplexitySeeds(
            StaticAnalysisSeedDtos.NormalizedRequest request, Map<String, Candidate> candidates, long deadline) {
        if (timedOut(deadline)) return true;
        IAnalyzer analyzer = contextManager.getAnalyzer();
        var files = sourceFiles(analyzer).stream()
                .sorted(Comparator.comparing(ProjectFile::toString))
                .limit(Math.max(request.targetSeedCount() * 4L, request.targetSeedCount()))
                .toList();
        for (var file : files) {
            if (timedOut(deadline)) return true;
            var maxComplexity = maxCyclomaticComplexity(analyzer, file);
            var sizeBytes = file.size().orElse(0L);
            if (maxComplexity < COMPLEXITY_SIGNAL_THRESHOLD && sizeBytes < LARGE_FILE_BYTES_THRESHOLD) continue;

            var score = Math.min(0.95, 0.45 + (maxComplexity / 50.0) + (sizeBytes / 200_000.0));
            var candidate = candidate(candidates, file.toString(), score, "size_complexity");
            if (maxComplexity > 0) {
                candidate.addSignal("complexity", Map.of("maxCyclomaticComplexity", maxComplexity));
            }
            if (sizeBytes > 0) {
                candidate.addSignal("size", Map.of("bytes", sizeBytes));
            }
            candidate.addTool(LONG_METHOD_TOOL);
            candidate.addAgent(SIZE_SPRAWL_AGENT);
        }
        return false;
    }

    private List<StaticAnalysisSeedDtos.Preview> addPreviewFindings(
            List<StaticAnalysisSeedDtos.SeedRecord> records, long deadline) {
        if (timedOut(deadline)) return List.of();
        IAnalyzer analyzer = contextManager.getAnalyzer();
        var weights = IAnalyzer.MaintainabilitySizeSmellWeights.defaults();
        var previews = new ArrayList<StaticAnalysisSeedDtos.Preview>();
        var analyzedFiles = 0;
        for (var seed : records) {
            if (timedOut(deadline) || previews.size() >= PREVIEW_FINDING_CAP || analyzedFiles >= PREVIEW_FILE_CAP) {
                break;
            }
            if (!seed.suggestedTools().contains(LONG_METHOD_TOOL)) {
                continue;
            }
            var file = contextManager.toFile(seed.file());
            if (!file.exists()) {
                continue;
            }
            analyzedFiles++;
            var findings = analyzer.findLongMethodAndGodObjectSmells(file, weights);
            for (var finding : findings) {
                if (timedOut(deadline) || previews.size() >= PREVIEW_FINDING_CAP) {
                    break;
                }
                previews.add(toPreview(seed, finding));
            }
        }
        return previews;
    }

    private StaticAnalysisSeedDtos.Preview toPreview(
            StaticAnalysisSeedDtos.SeedRecord seed, IAnalyzer.MaintainabilitySizeSmell finding) {
        var cu = finding.codeUnit();
        var range = finding.range();
        var lineStart = range.isEmpty() ? null : range.startLine() + 1;
        var lineEnd = range.isEmpty() ? null : range.endLine() + 1;
        var signalValues = new LinkedHashMap<String, Object>();
        signalValues.put("ownSpanLines", finding.ownSpanLines());
        signalValues.put("descendantSpanLines", finding.descendantSpanLines());
        signalValues.put("directChildCount", finding.directChildCount());
        signalValues.put("functionCount", finding.functionCount());
        signalValues.put("maxFunctionSpanLines", finding.maxFunctionSpanLines());
        signalValues.put("maxCyclomaticComplexity", finding.maxCyclomaticComplexity());
        signalValues.put("reasons", finding.reasons());
        var selectionKind = seed.selection() == null ? null : seed.selection().kind();
        var title = "Maintainability preview";
        var message = "%s scored %d in %s".formatted(cu.fqName(), finding.score(), cu.source());
        return new StaticAnalysisSeedDtos.Preview(
                UUID.randomUUID().toString(),
                cu.source().toString(),
                LONG_METHOD_TOOL,
                finding.score(),
                title,
                message,
                cu.fqName(),
                lineStart,
                lineEnd,
                selectionKind,
                List.of(new StaticAnalysisSeedDtos.Signal("maintainability_size", signalValues)),
                seed.suggestedAgents().isEmpty() ? List.of(SIZE_SPRAWL_AGENT) : seed.suggestedAgents());
    }

    private boolean addUsageExpansionSeeds(
            StaticAnalysisSeedDtos.NormalizedRequest request, Map<String, Candidate> candidates, long deadline)
            throws InterruptedException {
        if (timedOut(deadline)) return true;
        if (contextManager.liveContext().allFragments().findAny().isEmpty()) return false;
        var related = contextManager.liveContext().getMostRelevantFiles(Math.min(10, request.targetSeedCount()));
        var rank = 1;
        for (var file : related) {
            if (timedOut(deadline)) return true;
            var candidate = candidate(candidates, file.toString(), 0.55 - (rank * 0.01), "usage_expansion");
            candidate.addSignal("usage_connectivity", Map.of("relatedRank", rank));
            rank++;
        }
        return false;
    }

    private boolean addWeightedSampleSeeds(
            StaticAnalysisSeedDtos.NormalizedRequest request, Map<String, Candidate> candidates, long deadline) {
        var needed = request.targetSeedCount() - candidates.size();
        if (needed <= 0) return false;
        var capped = timedOut(deadline);
        var files = sourceFiles(contextManager.getAnalyzer()).stream()
                .sorted(Comparator.comparing(ProjectFile::toString))
                .limit(needed)
                .toList();
        var rank = 1;
        for (var file : files) {
            var candidate = candidate(candidates, file.toString(), 0.2 - (rank * 0.001), "weighted_sample");
            candidate.addSignal("size", Map.of("bytes", file.size().orElse(0L)));
            rank++;
        }
        return capped || timedOut(deadline);
    }

    private Set<ProjectFile> sourceFiles(IAnalyzer analyzer) {
        var analyzedFiles = analyzer.getAnalyzedFiles();
        if (!analyzedFiles.isEmpty()) {
            return analyzedFiles;
        }
        var project = contextManager.getProject();
        return project.getAnalyzerLanguages().stream()
                .flatMap(language -> project.getAnalyzableFiles(language).stream())
                .collect(Collectors.toSet());
    }

    private static int maxCyclomaticComplexity(IAnalyzer analyzer, ProjectFile file) {
        try {
            return analyzer.getTopLevelDeclarations(file).stream()
                    .flatMap(cu -> flatten(analyzer, cu).stream())
                    .filter(CodeUnit::isFunction)
                    .mapToInt(analyzer::computeCyclomaticComplexity)
                    .max()
                    .orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<CodeUnit> flatten(IAnalyzer analyzer, CodeUnit root) {
        var result = new ArrayList<CodeUnit>();
        result.add(root);
        for (var child : analyzer.getDirectChildren(root)) {
            result.addAll(flatten(analyzer, child));
        }
        return result;
    }

    private Candidate candidate(Map<String, Candidate> candidates, String path, double score, String selectionKind) {
        return candidates
                .computeIfAbsent(
                        path,
                        ignored -> new Candidate(
                                new ProjectFile(contextManager.getProject().getRoot(), path)))
                .merge(score, selectionKind);
    }

    private static boolean timedOut(long deadlineNanos) {
        return System.nanoTime() >= deadlineNanos;
    }

    private static StaticAnalysisSeedDtos.Event event(
            String scanId,
            String state,
            List<String> tools,
            List<String> files,
            @Nullable StaticAnalysisSeedDtos.Selection selection,
            String code,
            String message,
            int findingCount,
            List<String> findingTypes) {
        return new StaticAnalysisSeedDtos.Event(
                UUID.randomUUID().toString(),
                scanId,
                StaticAnalysisSeedDtos.PHASE_STATIC_SEED,
                state,
                tools,
                files,
                selection,
                null,
                new StaticAnalysisSeedDtos.Outcome(code, message, findingCount, findingTypes),
                List.of());
    }

    private static final class Candidate {
        private final ProjectFile file;
        private String selectionKind = "weighted_sample";
        private double score;
        private final Map<String, StaticAnalysisSeedDtos.Signal> signals = new LinkedHashMap<>();
        private final List<String> suggestedAgents = new ArrayList<>();
        private final List<String> suggestedTools = new ArrayList<>();

        private Candidate(ProjectFile file) {
            this.file = file;
        }

        private Candidate merge(double score, String selectionKind) {
            if (score > this.score) {
                this.score = score;
                this.selectionKind = selectionKind;
            }
            return this;
        }

        private double score() {
            return score;
        }

        private void addSignal(String kind, Map<String, Object> values) {
            signals.putIfAbsent(kind, new StaticAnalysisSeedDtos.Signal(kind, values));
        }

        private void addAgent(String agent) {
            if (!suggestedAgents.contains(agent)) {
                suggestedAgents.add(agent);
            }
        }

        private void addTool(String tool) {
            if (!suggestedTools.contains(tool)) {
                suggestedTools.add(tool);
            }
        }

        private StaticAnalysisSeedDtos.SeedRecord toRecord(int rank) {
            return new StaticAnalysisSeedDtos.SeedRecord(
                    file.toString(),
                    rank,
                    new StaticAnalysisSeedDtos.Selection(selectionKind, rank, score, List.copyOf(signals.values())),
                    List.copyOf(suggestedAgents),
                    List.copyOf(suggestedTools));
        }
    }
}
