package ai.brokk.tools;

import ai.brokk.ICodeIntelligence;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CommentDensityStats;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Read-only static analysis helpers for code quality, adapted for the MCP server.
 * Uses {@link ICodeIntelligence} instead of IContextManager, so no LLM or console dependencies.
 */
public class CodeQualityToolsMcp {
    private static final String COMMENT_DENSITY_JAVA_ONLY =
            "Comment density is only available for Java symbols in this analyzer snapshot.";

    private final ICodeIntelligence intelligence;

    public CodeQualityToolsMcp(ICodeIntelligence intelligence) {
        this.intelligence = intelligence;
    }

    // -- computeCyclomaticComplexity --

    public String computeCyclomaticComplexity(List<String> filePaths, int threshold) {
        int limit = threshold > 0 ? threshold : 10;
        IAnalyzer analyzer = intelligence.getAnalyzer();
        var lines = new ArrayList<String>();
        lines.add("Cyclomatic complexity (threshold: " + limit + "):");
        boolean foundAny = false;

        for (String path : filePaths) {
            ProjectFile file = intelligence.toFile(path);
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
                lines.add("- " + cu.fqName() + ": " + complexity + " (in " + cu.source() + ")");
                flagged = true;
            }
        }
        for (CodeUnit child : analyzer.getDirectChildren(cu)) {
            flagged |= analyzeUnitComplexity(analyzer, child, threshold, lines);
        }
        return flagged;
    }

    // -- reportCommentDensityForCodeUnit --

    public String reportCommentDensityForCodeUnit(String fqName, int maxLines) {
        if (fqName.isBlank()) {
            return "Missing fqName.";
        }
        int cap = maxLines > 0 ? maxLines : 120;
        IAnalyzer analyzer = intelligence.getAnalyzer();
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

    // -- reportCommentDensityForFiles --

    public String reportCommentDensityForFiles(List<String> filePaths, int maxTopLevelRows, int maxFiles) {
        int rowCap = maxTopLevelRows > 0 ? maxTopLevelRows : 60;
        int fileCap = maxFiles > 0 ? maxFiles : 25;
        IAnalyzer analyzer = intelligence.getAnalyzer();
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
            ProjectFile file = intelligence.toFile(path);
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

    // -- reportExceptionHandlingSmells --

    public String reportExceptionHandlingSmells(
            List<String> filePaths,
            int minScore,
            int maxFindings,
            int genericThrowableWeight,
            int genericExceptionWeight,
            int genericRuntimeExceptionWeight,
            int emptyBodyWeight,
            int commentOnlyBodyWeight,
            int smallBodyWeight,
            int logOnlyBodyWeight,
            int meaningfulBodyCreditPerStatement,
            int meaningfulBodyStatementThreshold,
            int smallBodyMaxStatements) {

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

        IAnalyzer analyzer = intelligence.getAnalyzer();
        var findings = new ArrayList<IAnalyzer.ExceptionHandlingSmell>();
        for (String path : filePaths) {
            ProjectFile file = intelligence.toFile(path);
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
        lines.add("- Weights: %s".formatted(formatExceptionWeights(weights)));
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

    // -- reportStructuralCloneSmells --

    public String reportStructuralCloneSmells(
            List<String> filePaths,
            int minScore,
            int minNormalizedTokens,
            int shingleSize,
            int minSharedShingles,
            int astSimilarityPercent,
            int maxFindings) {

        var defaults = IAnalyzer.CloneSmellWeights.defaults();
        int threshold = minScore > 0 ? minScore : defaults.minSimilarityPercent();
        int findingsCap = maxFindings > 0 ? maxFindings : 80;
        var weights = new IAnalyzer.CloneSmellWeights(
                minNormalizedTokens > 0 ? minNormalizedTokens : defaults.minNormalizedTokens(),
                threshold,
                shingleSize > 0 ? shingleSize : defaults.shingleSize(),
                minSharedShingles > 0 ? minSharedShingles : defaults.minSharedShingles(),
                astSimilarityPercent > 0 ? astSimilarityPercent : defaults.astSimilarityPercent());

        IAnalyzer analyzer = intelligence.getAnalyzer();
        List<ProjectFile> files = filePaths.stream()
                .map(intelligence::toFile)
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

    // NOTE: analyzeGitHotspots is not available in brokk-core because GitHotspotAnalyzer
    // lives in the app module with dependencies (ai.brokk.concurrent.*) not available here.
    // It could be added if GitHotspotAnalyzer is moved to brokk-core or brokk-shared.

    // -- Formatting helpers --

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

    private static String formatExceptionWeights(IAnalyzer.ExceptionSmellWeights w) {
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
}
