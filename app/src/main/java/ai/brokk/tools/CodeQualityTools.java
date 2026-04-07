package ai.brokk.tools;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitHotspotAnalyzer;
import ai.brokk.git.GitRepo;
import java.io.IOException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;

/**
 * Read-only static analysis helpers for code quality (complexity, comment heuristics).
 * Intended for {@link ai.brokk.executor.agents.CustomAgentExecutor custom agents} and similar tool loops.
 */
public class CodeQualityTools {

    private static final String FINDING_PREFIX = "[CODE_QUALITY]";

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
            Heuristically finds comments that look like redundant 'how' explanations vs semantic 'why' comments.
            Returns a report of candidate redundant comments for the given files.""")
    public String analyzeCommentSemantics(@P("File paths relative to the project root.") List<String> filePaths) {

        var lines = new ArrayList<String>();
        lines.add("Comment semantics:");
        boolean anyFile = false;
        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();

        for (String path : filePaths) {
            ProjectFile file = contextManager.toFile(path);
            if (!file.exists()) continue;

            String content = file.read().orElse("");
            List<String> howComments = analyzer.findPotentialHowComments(content);

            if (!howComments.isEmpty()) {
                anyFile = true;
                lines.add("File: " + path);
                for (String comment : howComments) {
                    String finding =
                            "%s Redundant 'how'-style comment in %s: %s".formatted(FINDING_PREFIX, path, comment);
                    contextManager.getIo().showNotification(IConsoleIO.NotificationRole.INFO, finding);
                    lines.add("  - " + comment);
                }
            }
        }

        return anyFile ? String.join("\n", lines) : "No redundant 'how'-style comments detected.";
    }

    @Blocking
    @Tool(
            """
            Git churn and complexity hotspots: correlates recent commit activity with cyclomatic complexity per file.
            Bounded to control context size: use maxFiles and maxCommits, and an optional time window (sinceDays or ISO instants).
            Returns a compact markdown summary.""")
    public String analyzeGitHotspots(
            @P("Days back from now for the window start when sinceIso is empty; values <= 0 default to 7.") int sinceDays,
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
        if (sinceIso != null && !sinceIso.isBlank()) {
            since = Instant.parse(sinceIso.strip());
        } else {
            int days = sinceDays > 0 ? sinceDays : 7;
            since = Instant.now().minus(days, ChronoUnit.DAYS);
        }

        Instant until = null;
        if (untilIso != null && !untilIso.isBlank()) {
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
                    .formatted(f.path(), f.churn(), f.complexity(), f.category(), authors));
        }
        return String.join("\n", lines);
    }
}
