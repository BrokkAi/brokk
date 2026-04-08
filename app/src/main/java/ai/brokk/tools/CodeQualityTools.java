package ai.brokk.tools;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CommentDensityStats;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitHotspotAnalyzer;
import ai.brokk.git.GitRepo;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;

/**
 * Read-only static analysis helpers for code quality.
 * Intended for {@link ai.brokk.executor.agents.CustomAgentExecutor custom agents} and similar tool loops.
 */
public class CodeQualityTools {

    private static final String FINDING_PREFIX = "[CODE_QUALITY]";

    private static final String COMMENT_DENSITY_JAVA_ONLY =
            "Comment density is only available for Java symbols in this analyzer snapshot.";

    private final IContextManager contextManager;

    public CodeQualityTools(IContextManager contextManager) {
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
            Java comment density for one symbol identified by fully qualified name (same resolution as getDefinitions).
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
        CodeUnit cu = defs.stream()
                .filter(d -> "java".equals(d.source().extension()))
                .findFirst()
                .orElse(defs.getFirst());
        Optional<CommentDensityStats> stats = analyzer.commentDensity(cu);
        if (stats.isEmpty()) {
            return COMMENT_DENSITY_JAVA_ONLY;
        }
        return truncateToLineCap(formatCommentDensityForUnit(stats.get()), cap);
    }

    @Tool(
            """
            Java comment density tables for the given source files: one section per file and one row per top-level declaration.
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
                continue;
            }
            if (!"java".equals(file.extension())) {
                lines.add("### `" + path + "`");
                lines.add("(not a Java file; skipped)");
                lines.add("");
                continue;
            }
            List<CommentDensityStats> stats = analyzer.commentDensityByTopLevel(file);
            if (stats.isEmpty()) {
                lines.add("### `" + path + "`");
                lines.add(COMMENT_DENSITY_JAVA_ONLY);
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
            Detects suspicious Java catch blocks using weighted heuristics designed for high-recall triage.
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
            deduped.putIfAbsent(key, finding);
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

    private static String sanitizeTableCell(String value) {
        return value.replace("|", "\\|");
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
