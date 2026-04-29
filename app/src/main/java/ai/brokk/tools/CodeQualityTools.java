package ai.brokk.tools;

import ai.brokk.IAppContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CommentDensityStats;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.CandidateFileProvider;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.git.GitHotspotAnalyzer;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitSecretScanner;
import ai.brokk.git.IGitRepo;
import ai.brokk.usages.UsageFinder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;

/**
 * Read-only static analysis helpers for code quality.
 * Intended for {@link ai.brokk.executor.agents.CustomAgentExecutor custom agents} and similar tool loops.
 */
public class CodeQualityTools {
    private static final String FINDING_PREFIX = "[CODE_QUALITY]";

    private static final String COMMENT_DENSITY_UNAVAILABLE =
            "Comment density is unavailable for this symbol in the current analyzer snapshot.";

    private static final int DEFAULT_SECRET_MAX_FINDINGS = 100;
    private static final int DEFAULT_SECRET_MAX_COMMITS = 2000;

    private final IAppContextManager contextManager;

    public CodeQualityTools(IAppContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Tool(
            """
            Computes heuristic cyclomatic complexity for methods in the given files.
            Flags methods above the threshold (typical default 10) for review or refactor.
            Returns a markdown-friendly report of flagged methods.""")
    public String computeCyclomaticComplexity(
            @P("File paths relative to the project root.") List<String> filePaths,
            @P("Complexity threshold; methods above this are flagged. Use 0 or negative for default (10).")
                    int threshold) {

        int limit = threshold > 0 ? threshold : 10;
        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        var lines = new ArrayList<String>();
        lines.add("Cyclomatic complexity (threshold: " + limit + "):");
        boolean foundAny = false;

        for (String path : filePaths) {
            ProjectFile file = contextManager.toFile(path);
            if (!file.exists()) continue;

            List<CodeUnit> declarations = analyzer.getTopLevelDeclarations(file);
            for (CodeUnit cu : declarations) {
                foundAny |= analyzeUnitComplexity(analyzer, cu, limit, lines);
            }
        }

        return foundAny ? String.join("\n", lines) : "No methods exceeded the complexity threshold of " + limit + ".";
    }

    private boolean analyzeUnitComplexity(IAnalyzer analyzer, CodeUnit cu, int threshold, List<String> lines) {
        boolean flagged = false;
        if (cu.isFunction()) {
            int complexity = analyzer.computeCyclomaticComplexity(cu);

            if (complexity > threshold) {
                String finding = "%s High complexity: %s (CC: %d) in %s"
                        .formatted(FINDING_PREFIX, cu.fqName(), complexity, cu.source());
                contextManager.getIo().showNotification(IConsoleIO.NotificationRole.INFO, finding);
                lines.add("- " + cu.fqName() + ": " + complexity);
                flagged = true;
            }
        }

        for (CodeUnit child : analyzer.getDirectChildren(cu)) {
            flagged |= analyzeUnitComplexity(analyzer, child, threshold, lines);
        }
        return flagged;
    }

    @Tool(
            """
            Computes cognitive complexity for methods in the given files.
            Flags methods above the threshold (typical default 15) for maintainability-focused review or refactor.
            Returns a markdown-friendly report of flagged methods.""")
    public String computeCognitiveComplexity(
            @P("File paths relative to the project root.") List<String> filePaths,
            @P("Complexity threshold; methods above this are flagged. Use 0 or negative for default (15).")
                    int threshold) {

        int limit = threshold > 0 ? threshold : 15;
        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        var lines = new ArrayList<String>();
        lines.add("Cognitive complexity (threshold: " + limit + "):");
        boolean foundAny = false;

        for (String path : filePaths) {
            ProjectFile file = contextManager.toFile(path);
            if (!file.exists()) continue;

            var complexities = analyzer.computeCognitiveComplexities(file);
            for (var entry : complexities.entrySet()) {
                foundAny |= analyzeUnitCognitiveComplexity(entry.getKey(), entry.getValue(), limit, lines);
            }
        }

        return foundAny
                ? String.join("\n", lines)
                : "No methods exceeded the cognitive complexity threshold of " + limit + ".";
    }

    private boolean analyzeUnitCognitiveComplexity(CodeUnit cu, int complexity, int threshold, List<String> lines) {
        if (cu.isSynthetic() || complexity <= threshold) {
            return false;
        }

        String finding = "%s High cognitive complexity: %s (CogC: %d) in %s"
                .formatted(FINDING_PREFIX, cu.fqName(), complexity, cu.source());
        contextManager.getIo().showNotification(IConsoleIO.NotificationRole.INFO, finding);
        lines.add("- " + cu.fqName() + ": " + complexity);
        return true;
    }

    @Tool(
            """
            Reports long methods/functions, god objects/modules, and helper sprawl in the given files.
            Uses analyzer code-unit hierarchy and declaration ranges, then ranks bounded findings by maintainability impact.
            Includes file path, symbol, line range, size/responsibility signals, and concise rationale.""")
    public String reportLongMethodAndGodObjectSmells(
            @P("File paths relative to the project root.") List<String> filePaths,
            @P("Maximum findings to return; values <= 0 default to 20.") int maxFindings,
            @P("Maximum existing files to analyze; values <= 0 default to 25.") int maxFiles,
            @P("Long method/function line threshold; values <= 0 use default.") int longMethodSpanLines,
            @P("High cyclomatic complexity threshold; values <= 0 use default.") int highComplexityThreshold,
            @P("God object/module line threshold; values <= 0 use default.") int godObjectSpanLines,
            @P("God object/module direct-child threshold; values <= 0 use default.") int godObjectDirectChildren,
            @P("God object/module function-count threshold; values <= 0 use default.") int godObjectFunctions,
            @P("Helper-sprawl function-count threshold; values <= 0 use default.") int helperSprawlFunctions,
            @P("Helper-sprawl workflow line threshold; values <= 0 use default.") int helperSprawlWorkflowLines) {

        int cap = maxFindings > 0 ? maxFindings : 20;
        int fileCap = maxFiles > 0 ? maxFiles : 25;
        var defaults = IAnalyzer.MaintainabilitySizeSmellWeights.defaults();
        var weights = new IAnalyzer.MaintainabilitySizeSmellWeights(
                pickPositive(longMethodSpanLines, defaults.longMethodSpanLines()),
                pickPositive(highComplexityThreshold, defaults.highComplexityThreshold()),
                pickPositive(godObjectSpanLines, defaults.godObjectSpanLines()),
                pickPositive(godObjectDirectChildren, defaults.godObjectDirectChildren()),
                pickPositive(godObjectFunctions, defaults.godObjectFunctions()),
                pickPositive(helperSprawlFunctions, defaults.helperSprawlFunctions()),
                pickPositive(helperSprawlWorkflowLines, defaults.helperSprawlWorkflowLines()),
                defaults.fileModuleLeewayMultiplier());
        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        var files = filePaths.stream()
                .map(contextManager::toFile)
                .filter(ProjectFile::exists)
                .limit(fileCap)
                .toList();
        var selectedFiles = new HashSet<>(files);
        var findings = files.stream()
                .flatMap(file -> analyzer.findLongMethodAndGodObjectSmells(file, weights).stream())
                .filter(smell -> selectedFiles.contains(smell.codeUnit().source()))
                .sorted(IAnalyzer.maintainabilitySizeSmellComparator())
                .limit(cap)
                .toList();

        var lines = new ArrayList<String>();
        lines.add("Long method and god object smells (max findings: " + cap + "):");
        lines.add("- Files analyzed cap: " + fileCap);
        lines.add("- Weights: " + formatWeights(weights));
        if (findings.isEmpty()) {
            lines.add("");
            lines.add("No long method or god object smells found.");
            return String.join("\n", lines);
        }
        for (var smell : findings) {
            CodeUnit cu = smell.codeUnit();
            var range = smell.range();
            int displayStartLine = range.startLine() + 1;
            int displayEndLine = range.endLine() + 1;
            String finding = "%s Maintainability size smell: %s (score: %d) in %s:%d-%d"
                    .formatted(
                            FINDING_PREFIX, cu.fqName(), smell.score(), cu.source(), displayStartLine, displayEndLine);
            contextManager.getIo().showNotification(IConsoleIO.NotificationRole.INFO, finding);
            lines.add("- `%s` in `%s:%d-%d` [score %d]"
                    .formatted(cu.fqName(), cu.source(), displayStartLine, displayEndLine, smell.score()));
            lines.add(
                    "  - Signals: own %d lines, descendants %d lines, direct children %d, functions %d, nested types %d, max function %d lines, max CC %d"
                            .formatted(
                                    smell.ownSpanLines(),
                                    smell.descendantSpanLines(),
                                    smell.directChildCount(),
                                    smell.functionCount(),
                                    smell.nestedTypeCount(),
                                    smell.maxFunctionSpanLines(),
                                    smell.maxCyclomaticComplexity()));
            lines.add("  - Rationale: " + String.join("; ", smell.reasons()));
        }
        return String.join("\n", lines);
    }

    @Blocking
    @Tool(
            """
            Reports likely generated-code residue: unused declarations and one-call abstractions in the given files.
            Uses analyzer declarations plus symbol/reference usage analysis where available, not text-only matching.
            Pass fqNames to target specific symbols; leave fqNames empty to scan declarations in the bounded files.""")
    public String reportDeadCodeAndUnusedAbstractionSmells(
            @P("File paths relative to the project root.") List<String> filePaths,
            @P("Optional fully qualified symbol names to analyze. Empty means discover candidates from filePaths.")
                    List<String> fqNames,
            @P("Minimum score to include a finding; values <= 0 default to 8.") int minScore,
            @P("Maximum findings to emit; values <= 0 default to 40.") int maxFindings,
            @P("Maximum existing files to scan for candidate declarations; values <= 0 default to 25.")
                    int maxInputFiles,
            @P("Maximum candidate symbols to analyze; values <= 0 default to 200.") int maxCandidateSymbols,
            @P("Maximum usage-candidate files to inspect; values <= 0 default to the usage finder default.")
                    int maxUsageCandidateFiles,
            @P(
                            "Maximum usage hits per symbol before usage lookup returns a guardrail result; values <= 0 default to 100.")
                    int maxUsagesPerSymbol) {

        int threshold = minScore > 0 ? minScore : 8;
        int findingsCap = maxFindings > 0 ? maxFindings : 40;
        int inputFileCap = maxInputFiles > 0 ? maxInputFiles : 25;
        int candidateCap = maxCandidateSymbols > 0 ? maxCandidateSymbols : 200;
        int usageFileCap = maxUsageCandidateFiles > 0 ? maxUsageCandidateFiles : UsageFinder.DEFAULT_MAX_FILES;
        int usageCap = maxUsagesPerSymbol > 0 ? maxUsagesPerSymbol : 100;

        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        var files = filePaths.stream()
                .map(contextManager::toFile)
                .filter(ProjectFile::exists)
                .limit(inputFileCap)
                .toList();
        var selectedFiles = Set.copyOf(files);
        var findings = new ArrayList<DeadCodeFinding>();
        var skipped = new ArrayList<String>();
        UsageFinder usageFinder = UsageFinder.create(contextManager);
        CandidateFileProvider batchCandidateProvider = (target, analysis) -> analysis.getProject()
                .getAnalyzableFiles(Languages.fromExtension(target.source().extension()));
        var candidateSelection = deadCodeCandidates(analyzer, files, fqNames, selectedFiles, candidateCap, skipped);

        for (CodeUnit candidate : candidateSelection.candidates()) {
            Optional<DeadCodeFinding> finding = analyzeDeadCodeCandidate(
                    analyzer, usageFinder, batchCandidateProvider, candidate, usageFileCap, usageCap, skipped);
            finding.filter(f -> f.score() >= threshold).ifPresent(findings::add);
        }

        var filtered = findings.stream()
                .sorted(Comparator.comparingInt(DeadCodeFinding::totalUsageCount)
                        .thenComparing(
                                Comparator.comparingInt(DeadCodeFinding::score).reversed())
                        .thenComparing(f -> displayPath(f.file()), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(DeadCodeFinding::symbol, String.CASE_INSENSITIVE_ORDER))
                .limit(findingsCap)
                .toList();

        var lines = new ArrayList<String>();
        lines.add("## Dead code and unused abstraction smells");
        lines.add("");
        lines.add("- Min score: %d".formatted(threshold));
        lines.add("- Input files analyzed cap: %d".formatted(inputFileCap));
        lines.add("- Candidate symbol cap: %d%s"
                .formatted(candidateCap, candidateSelection.truncated() ? " (truncated)" : ""));
        lines.add("- Usage candidate file cap: %d".formatted(usageFileCap));
        lines.add("- Usage cap per symbol: %d".formatted(usageCap));
        lines.add("- Candidate symbols analyzed: %d"
                .formatted(candidateSelection.candidates().size()));
        lines.add("- Findings shown: %d of %d".formatted(filtered.size(), findings.size()));
        if (!skipped.isEmpty()) {
            lines.add("- Skipped symbols: %d".formatted(skipped.size()));
        }
        lines.add("");

        if (filtered.isEmpty()) {
            lines.add("No dead code or unused abstraction smells met minScore " + threshold + ".");
            if (!skipped.isEmpty()) {
                lines.add("");
                lines.add("Skipped evidence:");
                skipped.stream().limit(10).forEach(skip -> lines.add("- " + skip));
            }
            return String.join("\n", lines);
        }

        lines.add(
                "| Score | Confidence | Kind | Symbol | File | Total Usages | External Usages | Evidence | Rationale |");
        lines.add(
                "|------:|-----------:|------|--------|------|-------------:|----------------:|----------|-----------|");
        for (DeadCodeFinding finding : filtered) {
            String location = "%s:%d-%d".formatted(displayPath(finding.file()), finding.startLine(), finding.endLine());
            lines.add("| %d | %.2f | `%s` | `%s` | `%s` | %d | %d | `%s` | `%s` |"
                    .formatted(
                            finding.score(),
                            finding.confidence(),
                            finding.kind(),
                            sanitizeTableCell(finding.symbol()),
                            sanitizeTableCell(location),
                            finding.totalUsageCount(),
                            finding.externalUsageCount(),
                            sanitizeTableCell(finding.evidence()),
                            sanitizeTableCell(finding.rationale())));

            String notification = "%s Dead/unused abstraction smell: %s (score: %d) in %s"
                    .formatted(FINDING_PREFIX, finding.symbol(), finding.score(), location);
            contextManager.getIo().showNotification(IConsoleIO.NotificationRole.INFO, notification);
        }
        if (findings.size() > filtered.size()) {
            lines.add("");
            lines.add("- Note: output truncated; increase maxFindings to see more.");
        }
        if (!skipped.isEmpty()) {
            lines.add("");
            lines.add("Skipped evidence:");
            skipped.stream().limit(10).forEach(skip -> lines.add("- " + skip));
            if (skipped.size() > 10) {
                lines.add("- ... " + (skipped.size() - 10) + " more skipped symbols");
            }
        }
        return String.join("\n", lines);
    }

    private CandidateSelection deadCodeCandidates(
            IAnalyzer analyzer,
            List<ProjectFile> files,
            List<String> fqNames,
            Set<ProjectFile> selectedFiles,
            int candidateCap,
            List<String> skipped) {
        var candidates = new LinkedHashSet<CodeUnit>();
        var targets =
                fqNames.stream().map(String::strip).filter(s -> !s.isBlank()).toList();
        if (!targets.isEmpty()) {
            for (String fqName : targets) {
                var definitions = analyzer.getDefinitions(fqName);
                if (definitions.isEmpty()) {
                    skipped.add("`%s`: no definition found".formatted(fqName));
                    continue;
                }
                definitions.stream()
                        .filter(cu -> selectedFiles.isEmpty() || selectedFiles.contains(cu.source()))
                        .filter(CodeQualityTools::isDeadCodeCandidate)
                        .forEach(candidates::add);
            }
            return capCandidates(candidates, candidateCap, skipped);
        }

        for (ProjectFile file : files) {
            analyzer.getDeclarations(file).stream()
                    .filter(CodeQualityTools::isDeadCodeCandidate)
                    .forEach(candidates::add);
        }
        return capCandidates(candidates, candidateCap, skipped);
    }

    private static CandidateSelection capCandidates(Set<CodeUnit> candidates, int candidateCap, List<String> skipped) {
        boolean truncated = candidates.size() > candidateCap;
        if (truncated) {
            skipped.add("candidate symbol cap reached: analyzed first %d of %d candidates"
                    .formatted(candidateCap, candidates.size()));
        }
        return new CandidateSelection(candidates.stream().limit(candidateCap).toList(), truncated);
    }

    private static boolean isDeadCodeCandidate(CodeUnit cu) {
        return !cu.isSynthetic() && !cu.isAnonymous() && (cu.isFunction() || cu.isClass() || cu.isField());
    }

    private Optional<DeadCodeFinding> analyzeDeadCodeCandidate(
            IAnalyzer analyzer,
            UsageFinder usageFinder,
            CandidateFileProvider candidateProvider,
            CodeUnit candidate,
            int usageFileCap,
            int usageCap,
            List<String> skipped) {
        var rangeOpt = analyzer.rangesOf(candidate).stream()
                .filter(range -> !range.isEmpty())
                .max(Comparator.comparingInt(CodeQualityTools::spanLines));
        if (rangeOpt.isEmpty()) {
            skipped.add("`%s`: no declaration range available".formatted(candidate.fqName()));
            return Optional.empty();
        }

        FuzzyResult usageResult;
        UsageFinder.UsageQueryResult queryResult;
        try {
            queryResult = usageFinder.queryUsages(candidate, candidateProvider, usageFileCap, usageCap);
            usageResult = queryResult.result();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            skipped.add("`%s`: usage analysis interrupted".formatted(candidate.fqName()));
            return Optional.empty();
        }

        if (queryResult.candidateFilesTruncated()) {
            skipped.add("`%s`: usage candidate files exceeded cap %d; evidence is inconclusive"
                    .formatted(candidate.fqName(), usageFileCap));
            return Optional.empty();
        }

        var either = usageResult.toEither();
        if (either.hasErrorMessage()) {
            skipped.add("`%s`: %s".formatted(candidate.fqName(), either.getErrorMessage()));
            return Optional.empty();
        }

        Optional<CodeUnit> definingOwner = analyzer.parentOf(candidate).or(() -> Optional.of(candidate));
        var usageHits = either.getUsages().stream()
                .filter(hit -> !hit.enclosing().equals(candidate))
                .sorted(Comparator.comparing((UsageHit h) -> displayPath(h.file()))
                        .thenComparingInt(UsageHit::line)
                        .thenComparingInt(UsageHit::startOffset))
                .toList();
        var externalHits = either.getUsages().stream()
                .filter(hit -> !hit.enclosing().equals(candidate))
                .filter(hit -> isExternalUsage(analyzer, definingOwner, hit))
                .sorted(Comparator.comparing((UsageHit h) -> displayPath(h.file()))
                        .thenComparingInt(UsageHit::line)
                        .thenComparingInt(UsageHit::startOffset))
                .toList();
        int usageCount = usageHits.size();
        if (usageCount > 1) {
            return Optional.empty();
        }

        var range = rangeOpt.orElseThrow();
        int declarationLines = spanLines(range);
        int score = usageCount == 0 ? 30 + Math.min(20, declarationLines / 4) : 12 + Math.min(12, declarationLines / 8);
        double confidence = usageCount == 0 ? 0.95 : 0.75;
        String evidence = usageCount == 0
                ? "no non-self usages found"
                : "only usage: %s:%d in %s%s"
                        .formatted(
                                displayPath(usageHits.getFirst().file()),
                                usageHits.getFirst().line(),
                                usageHits.getFirst().enclosing().fqName(),
                                externalHits.isEmpty() ? " (same owner)" : "");
        String rationale = usageCount == 0
                ? "symbol has no usage evidence and may be generated residue"
                : "symbol has only one caller and may be a low-value abstraction";

        return Optional.of(new DeadCodeFinding(
                score,
                confidence,
                candidate.kind().name().toLowerCase(Locale.ROOT),
                candidate.fqName(),
                candidate.source(),
                range.startLine() + 1,
                range.endLine() + 1,
                usageCount,
                externalHits.size(),
                evidence,
                rationale));
    }

    private static boolean isExternalUsage(IAnalyzer analyzer, Optional<CodeUnit> definingOwner, UsageHit hit) {
        if (definingOwner.isEmpty()) {
            return true;
        }
        CodeUnit hitOwner = analyzer.parentOf(hit.enclosing()).orElse(hit.enclosing());
        return !hitOwner.equals(definingOwner.get());
    }

    private record CandidateSelection(List<CodeUnit> candidates, boolean truncated) {}

    private record DeadCodeFinding(
            int score,
            double confidence,
            String kind,
            String symbol,
            ProjectFile file,
            int startLine,
            int endLine,
            int totalUsageCount,
            int externalUsageCount,
            String evidence,
            String rationale) {}

    @Tool(
            """
            Comment density for one symbol identified by fully qualified name (same resolution as getDefinitions).
            Works for analyzers that provide comment-density stats (for example Java, JavaScript, or TypeScript).
            Reports header vs inline comment line counts, declaration span lines, and rolled-up totals for class-like units.
            For semantic review, follow up with getFileContents or getMethodSources. Output is truncated to maxLines.""")
    public String reportCommentDensityForCodeUnit(
            @P("Fully qualified name (e.g. com.example.MyClass or com.example.MyClass.method).") String fqName,
            @P("Maximum output lines; values <= 0 default to 120.") int maxLines) {

        if (fqName.isBlank()) {
            return "Missing fqName.";
        }
        int cap = maxLines > 0 ? maxLines : 120;
        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        String key = fqName.strip();
        var defs = analyzer.getDefinitions(key);
        if (defs.isEmpty()) {
            return "No definition found for: " + key;
        }
        Optional<CommentDensityStats> stats = analyzer.commentDensity(key);
        if (stats.isEmpty()) {
            return COMMENT_DENSITY_UNAVAILABLE;
        }
        return truncateToLineCap(formatCommentDensityForUnit(stats.orElseThrow()), cap);
    }

    @Tool(
            """
            Comment density tables for the given source files: one section per file and one row per top-level declaration.
            Includes own and rolled-up header/inline line counts. Bounded by maxFiles and maxTopLevelRows total across all files.""")
    public String reportCommentDensityForFiles(
            @P("File paths relative to the project root.") List<String> filePaths,
            @P("Maximum declaration rows across all files; values <= 0 default to 60.") int maxTopLevelRows,
            @P("Maximum files to include; values <= 0 default to 25.") int maxFiles) {

        int rowCap = maxTopLevelRows > 0 ? maxTopLevelRows : 60;
        int fileCap = maxFiles > 0 ? maxFiles : 25;
        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        var lines = new ArrayList<String>();
        lines.add("## Comment density by file");
        lines.add("");
        int filesShown = 0;
        int rowsEmitted = 0;
        boolean rowsTruncated = false;
        boolean filesTruncated = false;

        outer:
        for (String path : filePaths) {
            if (filesShown >= fileCap) {
                filesTruncated = true;
                break;
            }
            ProjectFile file = contextManager.toFile(path);
            if (!file.exists()) {
                lines.add("- Missing file (skipped): `" + path + "`");
                filesShown++;
                continue;
            }
            List<CommentDensityStats> stats = analyzer.commentDensityByTopLevel(file);
            if (stats.isEmpty()) {
                lines.add("### `" + path + "`");
                lines.add(COMMENT_DENSITY_UNAVAILABLE);
                lines.add("");
                filesShown++;
                continue;
            }
            filesShown++;
            lines.add("### `" + path + "`");
            lines.add("| Declaration | Hdr | Inl | Span | Roll H | Roll I | Roll S |");
            lines.add("|-------------|-----|-----|------|--------|--------|--------|");
            for (CommentDensityStats s : stats) {
                if (rowsEmitted >= rowCap) {
                    rowsTruncated = true;
                    break outer;
                }
                lines.add("| `%s` | %d | %d | %d | %d | %d | %d |"
                        .formatted(
                                sanitizeTableCell(s.fqName()),
                                s.headerCommentLines(),
                                s.inlineCommentLines(),
                                s.spanLines(),
                                s.rolledUpHeaderCommentLines(),
                                s.rolledUpInlineCommentLines(),
                                s.rolledUpSpanLines()));
                rowsEmitted++;
            }
            lines.add("");
        }
        var footer = new ArrayList<String>();
        footer.add("");
        footer.add("- Files shown: %d (cap %d%s)"
                .formatted(filesShown, fileCap, filesTruncated ? ", list truncated" : ""));
        footer.add("- Declaration rows: %d (cap %d%s)"
                .formatted(rowsEmitted, rowCap, rowsTruncated ? ", table truncated" : ""));
        if (rowsTruncated || filesTruncated) {
            footer.add("- Note: narrow the path list or increase caps to see more.");
        }
        lines.addAll(footer);
        return String.join("\n", lines);
    }

    @Tool(
            """
            Detects suspicious exception handlers using weighted heuristics designed for high-recall triage.
            Scores generic catches and tiny/empty handlers, then subtracts credit for richer handling bodies.
            Use minScore, maxFindings, and weight parameters to tune precision/recall.""")
    public String reportExceptionHandlingSmells(
            @P("File paths relative to the project root.") List<String> filePaths,
            @P("Minimum score to include a finding; values <= 0 default to 4.") int minScore,
            @P("Maximum findings to emit; values <= 0 default to 80.") int maxFindings,
            @P("Weight for catching Throwable; values < 0 use default.") int genericThrowableWeight,
            @P("Weight for catching Exception; values < 0 use default.") int genericExceptionWeight,
            @P("Weight for catching RuntimeException; values < 0 use default.") int genericRuntimeExceptionWeight,
            @P("Weight for empty catch bodies; values < 0 use default.") int emptyBodyWeight,
            @P("Weight for comment-only catch bodies; values < 0 use default.") int commentOnlyBodyWeight,
            @P("Weight for small catch bodies; values < 0 use default.") int smallBodyWeight,
            @P("Weight for log-only catch bodies; values < 0 use default.") int logOnlyBodyWeight,
            @P("Score credit subtracted per catch statement in the body; values < 0 use default.")
                    int meaningfulBodyCreditPerStatement,
            @P("Maximum statements that earn meaningful-body credit; values < 0 use default.")
                    int meaningfulBodyStatementThreshold,
            @P("Maximum statement count considered a small body; values < 0 use default.") int smallBodyMaxStatements) {

        int threshold = minScore > 0 ? minScore : 4;
        int findingsCap = maxFindings > 0 ? maxFindings : 80;
        var defaults = IAnalyzer.ExceptionSmellWeights.defaults();
        var weights = new IAnalyzer.ExceptionSmellWeights(
                pickWeight(genericThrowableWeight, defaults.genericThrowableWeight()),
                pickWeight(genericExceptionWeight, defaults.genericExceptionWeight()),
                pickWeight(genericRuntimeExceptionWeight, defaults.genericRuntimeExceptionWeight()),
                pickWeight(emptyBodyWeight, defaults.emptyBodyWeight()),
                pickWeight(commentOnlyBodyWeight, defaults.commentOnlyBodyWeight()),
                pickWeight(smallBodyWeight, defaults.smallBodyWeight()),
                pickWeight(logOnlyBodyWeight, defaults.logOnlyWeight()),
                pickWeight(meaningfulBodyCreditPerStatement, defaults.meaningfulBodyCreditPerStatement()),
                pickWeight(meaningfulBodyStatementThreshold, defaults.meaningfulBodyStatementThreshold()),
                pickWeight(smallBodyMaxStatements, defaults.smallBodyMaxStatements()));

        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        var findings = new ArrayList<IAnalyzer.ExceptionHandlingSmell>();
        for (String path : filePaths) {
            ProjectFile file = contextManager.toFile(path);
            if (!file.exists()) {
                continue;
            }
            findings.addAll(analyzer.findExceptionHandlingSmells(file, weights));
        }

        var filtered = findings.stream()
                .filter(f -> f.score() >= threshold)
                .sorted(Comparator.comparingInt(IAnalyzer.ExceptionHandlingSmell::score)
                        .reversed()
                        .thenComparing(f -> f.file().toString())
                        .thenComparing(IAnalyzer.ExceptionHandlingSmell::enclosingFqName))
                .toList();

        if (filtered.isEmpty()) {
            return "No exception-handling smells met minScore " + threshold + ".";
        }
        int shown = Math.min(findingsCap, filtered.size());
        var lines = new ArrayList<String>();
        lines.add("## Exception handling smells");
        lines.add("");
        lines.add("- Min score: %d".formatted(threshold));
        lines.add("- Findings shown: %d of %d".formatted(shown, filtered.size()));
        lines.add("- Weights: %s".formatted(formatWeights(weights)));
        lines.add("");
        lines.add("| Score | Catch Type | Statements | Symbol | File | Reasons | Excerpt |");
        lines.add("|------:|------------|-----------:|--------|------|---------|---------|");
        for (IAnalyzer.ExceptionHandlingSmell finding : filtered.subList(0, shown)) {
            String reasons = sanitizeTableCell(String.join(", ", finding.reasons()));
            String catchType = sanitizeTableCell(finding.catchType());
            String symbol = sanitizeTableCell(finding.enclosingFqName());
            String file = sanitizeTableCell(finding.file().toString());
            lines.add("| %d | `%s` | %d | `%s` | `%s` | %s | `%s` |"
                    .formatted(
                            finding.score(),
                            catchType,
                            finding.bodyStatementCount(),
                            symbol,
                            file,
                            "`" + reasons + "`",
                            sanitizeTableCell(finding.excerpt())));
        }
        if (filtered.size() > shown) {
            lines.add("");
            lines.add("- Note: output truncated; increase maxFindings to see more.");
        }
        return String.join("\n", lines);
    }

    @Tool(
            """
            Detects duplicated implementation patterns across functions using normalized token similarity.
            Uses analyzer-provided structural clone smells (with AST refinement) for high-recall triage.
            Tune similarity and size thresholds to reduce noise.""")
    public String reportStructuralCloneSmells(
            @P("File paths relative to the project root.") List<String> filePaths,
            @P("Minimum similarity score (0-100) to include; values <= 0 default to 60.") int minScore,
            @P("Minimum normalized token count; values <= 0 default to 12.") int minNormalizedTokens,
            @P("Shingle size used for token overlap; values <= 0 default to 2.") int shingleSize,
            @P("Minimum shared shingles before scoring; values <= 0 default to 3.") int minSharedShingles,
            @P("AST refinement threshold (0-100); values <= 0 default to 70.") int astSimilarityPercent,
            @P("Maximum findings to emit; values <= 0 default to 80.") int maxFindings) {

        var defaults = IAnalyzer.CloneSmellWeights.defaults();
        int threshold = minScore > 0 ? minScore : defaults.minSimilarityPercent();
        int findingsCap = maxFindings > 0 ? maxFindings : 80;
        var weights = new IAnalyzer.CloneSmellWeights(
                minNormalizedTokens > 0 ? minNormalizedTokens : defaults.minNormalizedTokens(),
                threshold,
                shingleSize > 0 ? shingleSize : defaults.shingleSize(),
                minSharedShingles > 0 ? minSharedShingles : defaults.minSharedShingles(),
                astSimilarityPercent > 0 ? astSimilarityPercent : defaults.astSimilarityPercent());

        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        List<ProjectFile> files = filePaths.stream()
                .map(contextManager::toFile)
                .filter(ProjectFile::exists)
                .toList();
        var findings = new ArrayList<>(analyzer.findStructuralCloneSmells(files, weights));
        var deduped = new LinkedHashMap<String, IAnalyzer.CloneSmell>();
        for (IAnalyzer.CloneSmell finding : findings) {
            String left = finding.file() + "#" + finding.enclosingFqName();
            String right = finding.peerFile() + "#" + finding.peerEnclosingFqName();
            String key = left.compareTo(right) <= 0 ? left + "||" + right : right + "||" + left;
            deduped.merge(
                    key, finding, (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
        }
        var filtered = deduped.values().stream()
                .filter(f -> f.score() >= threshold)
                .sorted(Comparator.comparingInt(IAnalyzer.CloneSmell::score)
                        .reversed()
                        .thenComparing(f -> f.file().toString())
                        .thenComparing(IAnalyzer.CloneSmell::enclosingFqName)
                        .thenComparing(f -> f.peerFile().toString())
                        .thenComparing(IAnalyzer.CloneSmell::peerEnclosingFqName))
                .toList();
        if (filtered.isEmpty()) {
            return "No structural clone smells met minScore " + threshold + ".";
        }

        int shown = Math.min(findingsCap, filtered.size());
        var lines = new ArrayList<String>();
        lines.add("## Structural clone smells");
        lines.add("");
        lines.add("- Min score: %d".formatted(threshold));
        lines.add("- Findings shown: %d of %d".formatted(shown, filtered.size()));
        lines.add("- Weights: minTokens=%d, shingleSize=%d, minShared=%d, astThreshold=%d"
                .formatted(
                        weights.minNormalizedTokens(),
                        weights.shingleSize(),
                        weights.minSharedShingles(),
                        weights.astSimilarityPercent()));
        lines.add("");
        lines.add("| Score | Tokens | Symbol | Peer Symbol | Reasons | Excerpt |");
        lines.add("|------:|-------:|--------|-------------|---------|---------|");
        for (IAnalyzer.CloneSmell finding : filtered.subList(0, shown)) {
            lines.add("| %d | %d | `%s` (%s) | `%s` (%s) | `%s` | `%s` |"
                    .formatted(
                            finding.score(),
                            finding.normalizedTokenCount(),
                            sanitizeTableCell(finding.enclosingFqName()),
                            sanitizeTableCell(finding.file().toString()),
                            sanitizeTableCell(finding.peerEnclosingFqName()),
                            sanitizeTableCell(finding.peerFile().toString()),
                            sanitizeTableCell(String.join(", ", finding.reasons())),
                            sanitizeTableCell(finding.excerpt())));
        }
        if (filtered.size() > shown) {
            lines.add("");
            lines.add("- Note: output truncated; increase maxFindings to see more.");
        }
        return String.join("\n", lines);
    }

    @Blocking
    @Tool(
            """
            Scans non-test text files for secret-looking strings, including current/default-branch files and git history.
            Findings are heuristic and redacted for LLM triage. Use maxFindings/maxCommits to bound output and work.""")
    public String reportSecretLikeCode(
            @P("Maximum findings to emit; values <= 0 default to 100.") int maxFindings,
            @P("Maximum commits to walk from HEAD; values <= 0 default to 2000.") int maxCommits,
            @P("Include findings that only appear in history and are not present in the current/default branch.")
                    boolean includeHistoryOnly,
            @P("Include lower-confidence short credential-like assignments.") boolean includeLowConfidence)
            throws GitAPIException, IOException {

        int findingsCap = maxFindings > 0 ? maxFindings : DEFAULT_SECRET_MAX_FINDINGS;
        int commitCap = maxCommits > 0 ? maxCommits : DEFAULT_SECRET_MAX_COMMITS;
        IGitRepo projectRepo = contextManager.getProject().getRepo();

        if (!(projectRepo instanceof GitRepo gitRepo)) {
            return "Secret-like code scan requires a JGit-backed repository.";
        }

        var report = new GitSecretScanner(gitRepo).scan(commitCap, includeHistoryOnly, includeLowConfidence);
        return formatSecretScanReport(report, findingsCap);
    }

    @Tool(
            """
            Detects low-value or brittle test assertion smells using language-aware weighted heuristics.
            Uses analyzer test-marker detection as a fast filter, then scores tautological assertions,
            shallow assertion-only tests, oversized exact literals, snapshots, and Java anonymous test doubles.""")
    public String reportTestAssertionSmells(
            @P("File paths relative to the project root.") List<String> filePaths,
            @P("Minimum score to include a finding; values <= 0 default to 4.") int minScore,
            @P("Maximum findings to emit; values <= 0 default to 80.") int maxFindings,
            @P("Weight for tests with no assertion-equivalent calls; values < 0 use default.") int noAssertionWeight,
            @P("Weight for self-comparison or tautological assertions; values < 0 use default.")
                    int tautologicalAssertionWeight,
            @P("Weight for assertTrue(true), assertFalse(false), and similar constants; values < 0 use default.")
                    int constantTruthWeight,
            @P("Weight for comparing two constant expressions; values < 0 use default.") int constantEqualityWeight,
            @P("Weight for nullness-only assertions; values < 0 use default.") int nullnessOnlyWeight,
            @P("Weight for tests whose assertions are all shallow; values < 0 use default.")
                    int shallowAssertionOnlyWeight,
            @P("Weight for oversized exact string literals; values < 0 use default.") int overspecifiedLiteralWeight,
            @P("Weight for inline anonymous test doubles; values < 0 use default.") int anonymousTestDoubleWeight,
            @P("Weight for repeated anonymous test double shapes; values < 0 use default.")
                    int repeatedAnonymousTestDoubleWeight,
            @P("Score credit subtracted per meaningful assertion; values < 0 use default.")
                    int meaningfulAssertionCredit,
            @P("Maximum meaningful assertions that earn credit; values < 0 use default.")
                    int meaningfulAssertionCreditCap,
            @P("String literal length considered oversized; values < 0 use default.") int largeLiteralLengthThreshold) {

        int threshold = minScore > 0 ? minScore : 4;
        int findingsCap = maxFindings > 0 ? maxFindings : 80;
        var defaults = IAnalyzer.TestAssertionWeights.defaults();
        var weights = new IAnalyzer.TestAssertionWeights(
                pickWeight(noAssertionWeight, defaults.noAssertionWeight()),
                pickWeight(tautologicalAssertionWeight, defaults.tautologicalAssertionWeight()),
                pickWeight(constantTruthWeight, defaults.constantTruthWeight()),
                pickWeight(constantEqualityWeight, defaults.constantEqualityWeight()),
                pickWeight(nullnessOnlyWeight, defaults.nullnessOnlyWeight()),
                pickWeight(shallowAssertionOnlyWeight, defaults.shallowAssertionOnlyWeight()),
                pickWeight(overspecifiedLiteralWeight, defaults.overspecifiedLiteralWeight()),
                pickWeight(anonymousTestDoubleWeight, defaults.anonymousTestDoubleWeight()),
                pickWeight(repeatedAnonymousTestDoubleWeight, defaults.repeatedAnonymousTestDoubleWeight()),
                pickWeight(meaningfulAssertionCredit, defaults.meaningfulAssertionCredit()),
                pickWeight(meaningfulAssertionCreditCap, defaults.meaningfulAssertionCreditCap()),
                pickWeight(largeLiteralLengthThreshold, defaults.largeLiteralLengthThreshold()));

        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        var findings = new ArrayList<IAnalyzer.TestAssertionSmell>();
        for (String path : filePaths) {
            ProjectFile file = contextManager.toFile(path);
            if (!file.exists() || !analyzer.containsTests(file)) {
                continue;
            }
            findings.addAll(analyzer.findTestAssertionSmells(file, weights));
        }

        var filtered = findings.stream()
                .filter(f -> f.score() >= threshold)
                .sorted(Comparator.comparingInt(IAnalyzer.TestAssertionSmell::score)
                        .reversed()
                        .thenComparing(f -> f.file().toString())
                        .thenComparing(IAnalyzer.TestAssertionSmell::enclosingFqName)
                        .thenComparing(IAnalyzer.TestAssertionSmell::assertionKind))
                .toList();

        if (filtered.isEmpty()) {
            return "No test assertion smells met minScore " + threshold + ".";
        }
        int shown = Math.min(findingsCap, filtered.size());
        var lines = new ArrayList<String>();
        lines.add("## Test assertion smells");
        lines.add("");
        lines.add("- Min score: %d".formatted(threshold));
        lines.add("- Findings shown: %d of %d".formatted(shown, filtered.size()));
        lines.add("- Weights: %s".formatted(formatWeights(weights)));
        lines.add("");
        lines.add("| Score | Kind | Assertions | Symbol | File | Reasons | Excerpt |");
        lines.add("|------:|------|-----------:|--------|------|---------|---------|");
        for (IAnalyzer.TestAssertionSmell finding : filtered.subList(0, shown)) {
            String reasons = sanitizeTableCell(String.join(", ", finding.reasons()));
            String kind = sanitizeTableCell(finding.assertionKind());
            String symbol = sanitizeTableCell(finding.enclosingFqName());
            String file = sanitizeTableCell(finding.file().toString());
            lines.add("| %d | `%s` | %d | `%s` | `%s` | %s | `%s` |"
                    .formatted(
                            finding.score(),
                            kind,
                            finding.assertionCount(),
                            symbol,
                            file,
                            "`" + reasons + "`",
                            sanitizeTableCell(finding.excerpt())));
        }
        if (filtered.size() > shown) {
            lines.add("");
            lines.add("- Note: output truncated; increase maxFindings to see more.");
        }
        return String.join("\n", lines);
    }

    private static String formatCommentDensityForUnit(CommentDensityStats s) {
        var lines = new ArrayList<String>();
        lines.add("## Comment density");
        lines.add("");
        lines.add("- Symbol: `%s`".formatted(s.fqName()));
        lines.add("- File: `%s`".formatted(s.relativePath()));
        lines.add("- Own: header %d, inline %d, span %d"
                .formatted(s.headerCommentLines(), s.inlineCommentLines(), s.spanLines()));
        lines.add("- Rolled-up: header %d, inline %d, span %d"
                .formatted(s.rolledUpHeaderCommentLines(), s.rolledUpInlineCommentLines(), s.rolledUpSpanLines()));
        return String.join("\n", lines);
    }

    private static String formatSecretScanReport(GitSecretScanner.SecretScanReport report, int maxFindings) {
        var shown = report.findings()
                .subList(0, Math.min(maxFindings, report.findings().size()));
        boolean truncated = report.findings().size() > shown.size();
        var lines = new ArrayList<String>();
        lines.add("## brokk-secret-scan");
        lines.add("");
        lines.add("- Repository: `%s`".formatted(report.repository()));
        lines.add("- Current/default ref scanned: `%s`".formatted(report.defaultRefDisplayName()));
        if (report.defaultRefFallback()) {
            lines.add("- Note: default branch could not be determined; fell back to `HEAD`.");
        }
        lines.add("- History commits scanned: %d (cap %d)".formatted(report.commitsScanned(), report.maxCommits()));
        lines.add("- Missing git entries skipped: %d".formatted(report.missingEntriesSkipped()));
        lines.add("- Non-text or oversized blobs skipped: %d".formatted(report.nonTextEntriesSkipped()));
        lines.add("- Findings shown: %d of %d%s"
                .formatted(shown.size(), report.findings().size(), truncated ? " (truncated)" : ""));
        lines.add("");

        if (shown.isEmpty()) {
            lines.add("No secret-like code found.");
            return String.join("\n", lines);
        }

        lines.add("| Location | Confidence | Rule | File:Line | First Seen | Last Seen | Redacted Excerpt |");
        lines.add("|----------|------------|------|-----------|------------|-----------|------------------|");
        for (GitSecretScanner.SecretFinding finding : shown) {
            String firstSeen = finding.firstSeenCommit().isBlank() ? "-" : finding.firstSeenCommit();
            String lastSeen = finding.lastSeenCommit().isBlank() ? "-" : finding.lastSeenCommit();
            lines.add("| %s | %s | `%s` | `%s:%d` | `%s` | `%s` | `%s` |"
                    .formatted(
                            finding.location(),
                            finding.confidence(),
                            sanitizeTableCell(finding.rule()),
                            sanitizeTableCell(finding.path()),
                            finding.line(),
                            firstSeen,
                            lastSeen,
                            sanitizeTableCell(finding.sample())));
        }
        return String.join("\n", lines);
    }

    private static String truncateToLineCap(String text, int maxLines) {
        List<String> lines = text.lines().toList();
        if (lines.size() <= maxLines) {
            return text;
        }
        return String.join("\n", lines.subList(0, maxLines)) + "\n\n... (" + (lines.size() - maxLines)
                + " more lines omitted)";
    }

    private static int pickWeight(int candidate, int fallback) {
        return candidate >= 0 ? candidate : fallback;
    }

    private static int pickPositive(int candidate, int fallback) {
        return candidate > 0 ? candidate : fallback;
    }

    private static String sanitizeTableCell(String value) {
        return value.replace("|", "\\|");
    }

    private static String displayPath(ProjectFile file) {
        return file.toString().replace('\\', '/');
    }

    private static int spanLines(IAnalyzer.Range range) {
        if (range.isEmpty()) {
            return 0;
        }
        return Math.max(1, range.endLine() - range.startLine() + 1);
    }

    private static String formatWeights(IAnalyzer.MaintainabilitySizeSmellWeights w) {
        return "longMethodLines=%d, highComplexity=%d, godObjectLines=%d, godObjectDirectChildren=%d,"
                        .formatted(
                                w.longMethodSpanLines(),
                                w.highComplexityThreshold(),
                                w.godObjectSpanLines(),
                                w.godObjectDirectChildren())
                + " godObjectFunctions=%d, helperSprawlFunctions=%d, helperSprawlWorkflowLines=%d, fileModuleLeeway=%dx"
                        .formatted(
                                w.godObjectFunctions(),
                                w.helperSprawlFunctions(),
                                w.helperSprawlWorkflowLines(),
                                w.fileModuleLeewayMultiplier());
    }

    private static String formatWeights(IAnalyzer.ExceptionSmellWeights w) {
        return "Throwable=%d, Exception=%d, RuntimeException=%d, empty=%d, commentOnly=%d, small=%d, logOnly=%d,"
                        .formatted(
                                w.genericThrowableWeight(),
                                w.genericExceptionWeight(),
                                w.genericRuntimeExceptionWeight(),
                                w.emptyBodyWeight(),
                                w.commentOnlyBodyWeight(),
                                w.smallBodyWeight(),
                                w.logOnlyWeight())
                + " creditPerStmt=%d, creditCap=%d, smallBodyMax=%d"
                        .formatted(
                                w.meaningfulBodyCreditPerStatement(),
                                w.meaningfulBodyStatementThreshold(),
                                w.smallBodyMaxStatements());
    }

    private static String formatWeights(IAnalyzer.TestAssertionWeights w) {
        return "noAssertion=%d, tautological=%d, constantTruth=%d, constantEquality=%d, nullnessOnly=%d,"
                        .formatted(
                                w.noAssertionWeight(),
                                w.tautologicalAssertionWeight(),
                                w.constantTruthWeight(),
                                w.constantEqualityWeight(),
                                w.nullnessOnlyWeight())
                + " shallowOnly=%d, overspecifiedLiteral=%d, anonymousTestDouble=%d, repeatedAnonymousTestDouble=%d,"
                        .formatted(
                                w.shallowAssertionOnlyWeight(),
                                w.overspecifiedLiteralWeight(),
                                w.anonymousTestDoubleWeight(),
                                w.repeatedAnonymousTestDoubleWeight())
                + " meaningfulCredit=%d, meaningfulCreditCap=%d, largeLiteralLength=%d"
                        .formatted(
                                w.meaningfulAssertionCredit(),
                                w.meaningfulAssertionCreditCap(),
                                w.largeLiteralLengthThreshold());
    }

    @Blocking
    @Tool(
            """
            Git churn and complexity hotspots: correlates recent commit activity with cyclomatic complexity per file.
            Bounded to control context size: use maxFiles and maxCommits, and an optional time window (sinceDays or ISO instants).
            Returns a compact markdown summary.""")
    public String analyzeGitHotspots(
            @P("Days back from now for the window start when sinceIso is empty; values <= 0 default to 7.")
                    int sinceDays,
            @P("Optional ISO-8601 start instant; when non-blank, overrides sinceDays.") String sinceIso,
            @P("Optional ISO-8601 exclusive end instant; empty means no upper bound.") String untilIso,
            @P("Maximum commits to walk; values <= 0 default to 500.") int maxCommits,
            @P("Maximum files to return (top by churn); values <= 0 default to 75; hard cap 500.") int maxFiles)
            throws GitAPIException, IOException {

        var repo = contextManager.getProject().getRepo();
        if (!(repo instanceof GitRepo gitRepo)) {
            return "Git hotspot analysis requires a JGit-backed repository.";
        }

        Instant since;
        if (!sinceIso.isBlank()) {
            since = Instant.parse(sinceIso.strip());
        } else {
            int days = sinceDays > 0 ? sinceDays : 7;
            since = Instant.now().minus(days, ChronoUnit.DAYS);
        }

        Instant until = null;
        if (!untilIso.isBlank()) {
            until = Instant.parse(untilIso.strip());
        }

        int commits = maxCommits > 0 ? maxCommits : 500;
        int filesCap = maxFiles > 0 ? maxFiles : 75;

        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        var report = new GitHotspotAnalyzer(gitRepo, analyzer).analyze(since, until, commits, filesCap);

        return formatHotspotReportMarkdown(report);
    }

    private static String formatHotspotReportMarkdown(GitHotspotAnalyzer.HotspotReport report) {
        var lines = new ArrayList<String>();
        lines.add("## Git hotspots");
        lines.add("");
        lines.add("- Repository: `%s`".formatted(report.repository()));
        lines.add("- Timeframe: %s".formatted(report.timeframe()));
        lines.add("- Analyzed commits: %d".formatted(report.analyzedCommits()));
        lines.add("- Unique files (before cap): %d".formatted(report.totalUniqueFiles()));
        lines.add("- Truncated: %s".formatted(report.truncated()));
        lines.add("");

        if (report.files().isEmpty()) {
            lines.add("No file hotspots in this window.");
            return String.join("\n", lines);
        }

        lines.add("| Path | Churn | Complexity | Category | Authors |");
        lines.add("|------|-------|------------|----------|---------|");
        for (var f : report.files()) {
            String authors = f.topAuthors().stream()
                    .map(a -> a.name() + "(" + a.commits() + ")")
                    .collect(Collectors.joining(", "));
            lines.add("| `%s` | %d | %d | %s | %s |"
                    .formatted(
                            sanitizeTableCell(f.path()),
                            f.churn(),
                            f.complexity(),
                            sanitizeTableCell(f.category().toString()),
                            sanitizeTableCell(authors)));
        }
        return String.join("\n", lines);
    }
}
