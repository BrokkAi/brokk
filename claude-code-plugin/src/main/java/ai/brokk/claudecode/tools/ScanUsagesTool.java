package ai.brokk.claudecode.tools;

import ai.brokk.AnalyzerUtil;
import ai.brokk.ContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.analyzer.usages.TextSearchCandidateProvider;
import ai.brokk.analyzer.usages.UsageFinder;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.project.MainProject;
import ai.brokk.util.Lines;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * MCP tool that exposes Brokk's scanUsages functionality.
 * Lazily initializes analyzers per project directory.
 */
public class ScanUsagesTool {
    private static final Logger logger = LogManager.getLogger(ScanUsagesTool.class);

    private record ProjectContext(MainProject project, IAnalyzer analyzer) {}

    private final ConcurrentHashMap<Path, ProjectContext> contextCache = new ConcurrentHashMap<>();

    public McpServerFeatures.SyncToolSpecification specification() {
        var inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "symbols", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Fully qualified symbol names to find usages for (e.g. 'com.example.MyClass.myMethod')"),
                        "includeTests", Map.of(
                                "type", "boolean",
                                "description", "Include test file call sites (default: false)"),
                        "projectRoot", Map.of(
                                "type", "string",
                                "description", "Project root directory (defaults to current working directory)")),
                List.of("symbols"),
                false,
                null,
                null);

        var tool = McpSchema.Tool.builder()
                .name("scanUsages")
                .description("Find usages/call sites of symbols (classes, methods, functions) across a codebase. "
                        + "Returns file locations, enclosing code units, and source snippets for each usage.")
                .inputSchema(inputSchema)
                .annotations(new McpSchema.ToolAnnotations(null, true, false, null, null, null))
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        return handleCall(request.arguments() != null ? request.arguments() : Map.of());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("Operation interrupted")
                                .isError(true)
                                .build();
                    } catch (Exception e) {
                        logger.error("scanUsages failed", e);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("Error: " + e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }

    private McpSchema.CallToolResult handleCall(Map<String, Object> args) throws InterruptedException {
        // Parse symbols
        var symbolsRaw = args.get("symbols");
        if (!(symbolsRaw instanceof List<?> symbolsList)) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error: 'symbols' parameter is required and must be an array of strings")
                    .isError(true)
                    .build();
        }

        List<String> symbols = symbolsList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .distinct()
                .filter(s -> !s.isBlank())
                .toList();
        if (symbols.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error: 'symbols' must contain at least one non-empty string")
                    .isError(true)
                    .build();
        }

        boolean includeTests = args.get("includeTests") instanceof Boolean b && b;
        Path projectRoot = resolveProjectRoot(args.get("projectRoot"));

        var ctx = getOrCreateContext(projectRoot);
        var analyzer = ctx.analyzer();

        @Nullable Predicate<ProjectFile> fileFilter = includeTests
                ? null
                : file -> !ContextManager.isTestFile(file, analyzer);

        var usageFinder = new UsageFinder(
                ctx.project(),
                analyzer,
                new TextSearchCandidateProvider(),
                null,
                fileFilter);

        List<String> outputs = new ArrayList<>();
        for (String symbol : symbols) {
            var usageResult = usageFinder.findUsages(symbol);
            var either = usageResult.toEither();
            if (either.hasErrorMessage()) {
                outputs.add(either.getErrorMessage());
                continue;
            }

            Optional<CodeUnit> definingOwner = analyzer.getDefinitions(symbol).stream()
                    .findFirst()
                    .map(def -> analyzer.parentOf(def).orElse(def));

            List<UsageHit> externalHits = either.getUsages().stream()
                    .filter(hit -> isExternalUsage(analyzer, definingOwner, hit))
                    .sorted(Comparator.comparing((UsageHit h) -> h.file().toString())
                            .thenComparingInt(UsageHit::line)
                            .thenComparingInt(UsageHit::startOffset))
                    .toList();
            if (externalHits.isEmpty()) {
                outputs.add("No usages found for: " + symbol);
                continue;
            }

            List<UsageHit> hitsLimited = externalHits.stream().limit(50).toList();
            var out = new StringBuilder();
            out.append("# Usages of ").append(symbol).append("\n\n");
            out.append("Call sites (")
                    .append(externalHits.size())
                    .append(externalHits.size() > 50 ? ", showing first 50" : "")
                    .append("):\n");
            hitsLimited.forEach(hit -> out.append("- `")
                    .append(hit.enclosing().fqName())
                    .append("` (")
                    .append(hit.file())
                    .append(":")
                    .append(hit.line())
                    .append(")\n"));

            out.append("\nExamples:\n\n");
            List<String> exampleBlocks = sourceBlocksForCodeUnits(
                    analyzer,
                    AnalyzerUtil.sampleUsages(analyzer, externalHits).stream()
                            .map(AnalyzerUtil.CodeWithSource::source)
                            .toList());
            if (exampleBlocks.isEmpty()) {
                out.append("(source unavailable)\n");
            } else {
                out.append(String.join("\n\n", exampleBlocks)).append("\n");
            }
            outputs.add(out.toString().stripTrailing());
        }

        if (outputs.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("No results")
                    .build();
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(String.join("\n\n", outputs))
                .build();
    }

    private static List<String> sourceBlocksForCodeUnits(IAnalyzer analyzer, List<CodeUnit> units) {
        List<String> blocks = new ArrayList<>();
        for (CodeUnit cu : units) {
            var file = cu.source();
            var contentOpt = file.read();
            if (contentOpt.isEmpty()) {
                continue;
            }

            String content = contentOpt.get();
            List<IAnalyzer.Range> ranges = analyzer.rangesOf(cu).stream()
                    .sorted(Comparator.comparingInt(IAnalyzer.Range::startByte))
                    .toList();
            if (ranges.isEmpty()) {
                continue;
            }

            for (var range : ranges) {
                var lines = Lines.range(content, range.startLine() + 1, range.endLine() + 1);
                if (lines.lineCount() == 0) {
                    continue;
                }
                blocks.add("```%s\n%s\n```".formatted(file, lines.text()));
            }
        }
        return blocks;
    }

    private static boolean isExternalUsage(IAnalyzer analyzer, Optional<CodeUnit> definingOwner, UsageHit hit) {
        if (definingOwner.isEmpty()) {
            return true;
        }
        CodeUnit hitOwner = analyzer.parentOf(hit.enclosing()).orElse(hit.enclosing());
        return !hitOwner.equals(definingOwner.get());
    }

    private ProjectContext getOrCreateContext(Path projectRoot) {
        return contextCache.computeIfAbsent(projectRoot, root -> {
            logger.info("Initializing analyzer for project: {}", root);
            var project = new MainProject(root);
            Language langHandle = Languages.aggregate(project.getAnalyzerLanguages());

            IAnalyzer analyzer;
            try {
                analyzer = langHandle.loadAnalyzer(project, (completed, total, phase) -> {
                    if (total > 0) {
                        logger.info("Analyzer progress: {} [{}/{}]", phase, completed, total);
                    }
                });
                logger.info("Loaded cached analyzer for {}", root);
            } catch (Exception e) {
                logger.info("No cached analyzer found, creating fresh for {}", root);
                analyzer = langHandle.createAnalyzer(project, (completed, total, phase) -> {
                    if (total > 0) {
                        logger.info("Analyzer progress: {} [{}/{}]", phase, completed, total);
                    }
                });
                langHandle.saveAnalyzer(analyzer, project);
            }
            return new ProjectContext(project, analyzer);
        });
    }

    private static Path resolveProjectRoot(@Nullable Object projectRootArg) {
        if (projectRootArg instanceof String s && !s.isBlank()) {
            return Path.of(s).toAbsolutePath().normalize();
        }
        // Default to cwd
        Path cwd = Path.of("").toAbsolutePath().normalize();
        // Walk up to find git root
        for (Path candidate = cwd; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(".git")) || Files.isRegularFile(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return cwd;
    }
}
