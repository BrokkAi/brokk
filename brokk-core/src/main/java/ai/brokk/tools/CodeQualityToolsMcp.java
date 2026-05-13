package ai.brokk.tools;

import ai.brokk.ICodeIntelligence;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CommentDensityStats;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.CandidateFileProvider;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.analyzer.usages.RegexUsageAnalyzer;
import ai.brokk.analyzer.usages.UsageAnalyzer;
import ai.brokk.analyzer.usages.UsageAnalyzerSelector;
import ai.brokk.analyzer.usages.UsageFinder;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitSecretScanner;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.ICoreProject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Read-only static analysis helpers for code quality, adapted for the MCP server.
 * Uses {@link ICodeIntelligence} instead of IContextManager, so no LLM or console dependencies.
 */
public class CodeQualityToolsMcp {
    private static final String COMMENT_DENSITY_JAVA_ONLY =
            "Comment density is only available for Java symbols in this analyzer snapshot.";

    private static final int DEFAULT_SECRET_MAX_FINDINGS = 100;
    private static final int DEFAULT_SECRET_MAX_COMMITS = 2000;

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

    // -- computeCognitiveComplexity --

    public String computeCognitiveComplexity(List<String> filePaths, int threshold) {
        int limit = threshold > 0 ? threshold : 15;
        IAnalyzer analyzer = intelligence.getAnalyzer();
        var lines = new ArrayList<String>();
        lines.add("Cognitive complexity (threshold: " + limit + "):");
        boolean foundAny = false;

        for (String path : filePaths) {
            ProjectFile file = intelligence.toFile(path);
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
        lines.add("- " + cu.fqName() + ": " + complexity);
        return true;
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
                filesShown++;
                continue;
            }
            if (!"java".equals(file.extension())) {
                lines.add("### `" + path + "`");
                lines.add("(not a Java file; skipped)");
                lines.add("");
                filesShown++;
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

    // -- reportLongMethodAndGodObjectSmells --

    public String reportLongMethodAndGodObjectSmells(
            List<String> filePaths,
            int maxFindings,
            int maxFiles,
            int longMethodSpanLines,
            int highComplexityThreshold,
            int godObjectSpanLines,
            int godObjectDirectChildren,
            int godObjectFunctions,
            int helperSprawlFunctions,
            int helperSprawlWorkflowLines) {

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

        IAnalyzer analyzer = intelligence.getAnalyzer();
        var files = filePaths.stream()
                .map(intelligence::toFile)
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
        lines.add("- Weights: " + formatSizeWeights(weights));
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

    // -- reportTestAssertionSmells --

    public String reportTestAssertionSmells(
            List<String> filePaths,
            int minScore,
            int maxFindings,
            int noAssertionWeight,
            int tautologicalAssertionWeight,
            int constantTruthWeight,
            int constantEqualityWeight,
            int nullnessOnlyWeight,
            int shallowAssertionOnlyWeight,
            int overspecifiedLiteralWeight,
            int anonymousTestDoubleWeight,
            int repeatedAnonymousTestDoubleWeight,
            int meaningfulAssertionCredit,
            int meaningfulAssertionCreditCap,
            int largeLiteralLengthThreshold) {

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

        IAnalyzer analyzer = intelligence.getAnalyzer();
        var findings = new ArrayList<IAnalyzer.TestAssertionSmell>();
        for (String path : filePaths) {
            ProjectFile file = intelligence.toFile(path);
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
        lines.add("- Weights: %s".formatted(formatTestAssertionWeights(weights)));
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

    // -- reportDeadCodeAndUnusedAbstractionSmells --

    public String reportDeadCodeAndUnusedAbstractionSmells(
            List<String> filePaths,
            List<String> fqNames,
            int minScore,
            int maxFindings,
            int maxInputFiles,
            int maxCandidateSymbols,
            int maxUsageCandidateFiles,
            int maxUsagesPerSymbol) {

        int threshold = minScore > 0 ? minScore : 8;
        int findingsCap = maxFindings > 0 ? maxFindings : 40;
        int inputFileCap = maxInputFiles > 0 ? maxInputFiles : 25;
        int candidateCap = maxCandidateSymbols > 0 ? maxCandidateSymbols : 200;
        int usageFileCap = maxUsageCandidateFiles > 0 ? maxUsageCandidateFiles : UsageFinder.DEFAULT_MAX_FILES;
        int usageCap = maxUsagesPerSymbol > 0 ? maxUsagesPerSymbol : 100;

        IAnalyzer analyzer = intelligence.getAnalyzer();
        ICoreProject project = intelligence.getProject();
        var files = filePaths.stream()
                .map(intelligence::toFile)
                .filter(ProjectFile::exists)
                .limit(inputFileCap)
                .toList();
        var selectedFiles = Set.copyOf(files);
        var findings = new ArrayList<DeadCodeFinding>();
        var skipped = new ArrayList<String>();
        CandidateFileProvider batchCandidateProvider = (target, analysis) -> analysis.getProject()
                .getAnalyzableFiles(Languages.fromExtension(target.source().extension()));
        UsageAnalyzer multiLangAnalyzer = (overloads, candidates, maxUsages) -> {
            if (overloads.isEmpty()) {
                return new FuzzyResult.Success(Map.of());
            }
            var strategy = UsageAnalyzerSelector.forTarget(overloads.getFirst(), analyzer, project);
            var result = strategy.findUsages(overloads, candidates, maxUsages);
            if (UsageAnalyzerSelector.shouldFallbackToRegex(result, strategy)) {
                return new RegexUsageAnalyzer(analyzer).findUsages(overloads, candidates, maxUsages);
            }
            return result;
        };
        var usageFinder = new UsageFinder(project, analyzer, batchCandidateProvider, multiLangAnalyzer, null);
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
                        .filter(CodeQualityToolsMcp::isDeadCodeCandidate)
                        .forEach(candidates::add);
            }
            return capCandidates(candidates, candidateCap, skipped);
        }

        for (ProjectFile file : files) {
            analyzer.getDeclarations(file).stream()
                    .filter(CodeQualityToolsMcp::isDeadCodeCandidate)
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
                .max(Comparator.comparingInt(CodeQualityToolsMcp::spanLines));
        if (rangeOpt.isEmpty()) {
            skipped.add("`%s`: no declaration range available".formatted(candidate.fqName()));
            return Optional.empty();
        }

        FuzzyResult usageResult;
        UsageFinder.QueryResult queryResult;
        try {
            queryResult = usageFinder.query(List.of(candidate), candidateProvider, usageFileCap, usageCap);
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

    private static String displayPath(ProjectFile file) {
        return file.toString().replace('\\', '/');
    }

    private static int spanLines(IAnalyzer.Range range) {
        if (range.isEmpty()) {
            return 0;
        }
        return Math.max(1, range.endLine() - range.startLine() + 1);
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

    // -- reportSecretLikeCode --

    public String reportSecretLikeCode(
            int maxFindings, int maxCommits, boolean includeHistoryOnly, boolean includeLowConfidence)
            throws GitAPIException, IOException {

        int findingsCap = maxFindings > 0 ? maxFindings : DEFAULT_SECRET_MAX_FINDINGS;
        int commitCap = maxCommits > 0 ? maxCommits : DEFAULT_SECRET_MAX_COMMITS;
        IGitRepo repo = intelligence.getRepo();

        if (!(repo instanceof GitRepo gitRepo)) {
            return "Secret-like code scan requires a JGit-backed repository.";
        }

        var report = new GitSecretScanner(gitRepo, gitRepo.getGit().getRepository())
                .scan(commitCap, includeHistoryOnly, includeLowConfidence);
        return formatSecretScanReport(report, findingsCap);
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

    private static String formatSecretScanReport(GitSecretScanner.SecretScanReport report, int maxFindings) {
        var shown = report.findings()
                .subList(0, Math.min(maxFindings, report.findings().size()));
        boolean truncated = report.findings().size() > shown.size();
        var lines = new ArrayList<String>();
        lines.add("## brokk-secret-scan");
        lines.add("");
        lines.add("- Repository: `%s`".formatted(sanitizeTableCell(report.repository())));
        lines.add("- Current/default ref scanned: `%s`".formatted(sanitizeTableCell(report.defaultRefDisplayName())));
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

    // Package-private for unit testing.
    static String sanitizeTableCell(String value) {
        // Backticks would let attacker-controlled text (e.g. committed file paths or scanned secret excerpts)
        // escape from an inline code span and inject Markdown for downstream LLM consumers.
        // Control characters (newlines, tabs, etc.) would break the single-line table-row format.
        return value.replace("|", "\\|").replace("`", "'").replaceAll("\\p{Cntrl}", " ");
    }

    private static String formatSizeWeights(IAnalyzer.MaintainabilitySizeSmellWeights w) {
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

    private static String formatTestAssertionWeights(IAnalyzer.TestAssertionWeights w) {
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
