package ai.brokk.tools;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;

/**
 * Tools for performing "Forensic Audits" of code quality, focusing on complexity and semantics.
 */
public class SlopScanTools {

    private final IContextManager contextManager;

    public SlopScanTools(IContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Tool(
            """
            Computes the heuristic cyclomatic complexity for methods in the specified files.
            Identifies methods that are potentially 'slop' due to excessive branching.
            Returns a report of methods exceeding the provided threshold.
            """)
    public String computeCyclomaticComplexity(
            @P("List of file paths relative to the project root.") List<String> filePaths,
            @P("Complexity threshold above which a method is flagged (default 10).") int threshold) {

        int limit = threshold > 0 ? threshold : 10;
        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        StringBuilder report = new StringBuilder("Cyclomatic Complexity Report (Threshold: " + limit + "):\n");
        boolean foundAny = false;

        for (String path : filePaths) {
            ProjectFile file = contextManager.toFile(path);
            if (!file.exists()) continue;

            List<CodeUnit> declarations = analyzer.getTopLevelDeclarations(file);
            for (CodeUnit cu : declarations) {
                foundAny |= analyzeUnitComplexity(analyzer, cu, limit, report);
            }
        }

        return foundAny ? report.toString() : "No methods exceeded the complexity threshold of " + limit;
    }

    private boolean analyzeUnitComplexity(IAnalyzer analyzer, CodeUnit cu, int threshold, StringBuilder report) {
        boolean flagged = false;
        if (cu.isFunction()) {
            int complexity = analyzer.computeCyclomaticComplexity(cu);

            if (complexity > threshold) {
                String finding = String.format(
                        "[SLOP_FINDING] High Complexity: %s (CC: %d) in %s", cu.fqName(), complexity, cu.source());
                contextManager.getIo().showNotification(IConsoleIO.NotificationRole.INFO, finding);
                report.append("- ")
                        .append(cu.fqName())
                        .append(": ")
                        .append(complexity)
                        .append("\n");
                flagged = true;
            }
        }

        for (CodeUnit child : analyzer.getDirectChildren(cu)) {
            flagged |= analyzeUnitComplexity(analyzer, child, threshold, report);
        }
        return flagged;
    }

    @Tool(
            """
            Analyzes comments in the specified files to distinguish between 'How' (redundant) vs 'Why' (semantic) comments.
            Identifies 'How' comments as slop that should be refactored into cleaner code.
            """)
    public String analyzeCommentSemantics(
            @P("List of file paths relative to the project root.") List<String> filePaths) {

        StringBuilder report = new StringBuilder("Comment Semantics Analysis:\n");
        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();

        for (String path : filePaths) {
            ProjectFile file = contextManager.toFile(path);
            if (!file.exists()) continue;

            String content = file.read().orElse("");
            List<String> howComments = analyzer.findPotentialHowComments(content);

            if (!howComments.isEmpty()) {
                report.append("File: ").append(path).append("\n");
                for (String comment : howComments) {
                    String finding = "[SLOP_FINDING] Redundant 'How' Comment in " + path + ": " + comment;
                    contextManager.getIo().showNotification(IConsoleIO.NotificationRole.INFO, finding);
                    report.append("  - ").append(comment).append("\n");
                }
            }
        }

        return report.length() > 27 ? report.toString() : "No redundant 'How' comments detected.";
    }
}
