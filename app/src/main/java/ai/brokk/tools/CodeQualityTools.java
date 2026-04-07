package ai.brokk.tools;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.ArrayList;
import java.util.List;

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
}
