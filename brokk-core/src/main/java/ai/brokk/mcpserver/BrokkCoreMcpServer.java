package ai.brokk.mcpserver;

import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;

import ai.brokk.ICodeIntelligence;
import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.project.CoreProject;
import ai.brokk.tools.CodeQualityToolsMcp;
import ai.brokk.tools.SearchTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Standalone MCP server providing pure code intelligence tools.
 * No LLM dependencies - uses tree-sitter analysis only.
 */
public class BrokkCoreMcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkCoreMcpServer.class);
    private static final String VERSION = "0.1.0";
    private static final String MCP_HISTORY_PATH_FLAG = "--mcp-history-path";

    private final ReentrantReadWriteLock workspaceLock = new ReentrantReadWriteLock(true);
    private CoreProject project;
    private ICodeIntelligence intelligence;
    private SearchTools searchTools;
    private CodeQualityToolsMcp codeQualityTools;
    private Path activeWorkspaceRoot;
    private final @Nullable McpToolCallHistoryWriter mcpToolCallHistoryWriter;

    public BrokkCoreMcpServer(CoreProject project, ICodeIntelligence intelligence, SearchTools searchTools) {
        this(project, intelligence, searchTools, null);
    }

    public BrokkCoreMcpServer(
            CoreProject project,
            ICodeIntelligence intelligence,
            SearchTools searchTools,
            @Nullable Path mcpHistoryPath) {
        this.project = project;
        this.intelligence = intelligence;
        this.searchTools = searchTools;
        this.codeQualityTools = new CodeQualityToolsMcp(intelligence);
        this.activeWorkspaceRoot = project.getRoot();
        this.mcpToolCallHistoryWriter = mcpHistoryPath != null ? createMcpToolCallHistoryWriter(mcpHistoryPath) : null;
    }

    private static @Nullable McpToolCallHistoryWriter createMcpToolCallHistoryWriter(Path historyRootDirectory) {
        try {
            return new McpToolCallHistoryWriter(historyRootDirectory);
        } catch (IOException e) {
            logger.warn("Failed to initialize MCP tool call history logging", e);
            return null;
        }
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        @Nullable Path mcpHistoryPath = null;

        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("Brokk Core MCP Server v" + VERSION);
                System.out.println("Provides code intelligence tools via Model Context Protocol.");
                System.out.println("No LLM dependencies - pure tree-sitter analysis.");
                System.out.println("Options:");
                System.out.println("  " + MCP_HISTORY_PATH_FLAG
                        + " <path>  Write MCP request/response logs under the given directory.");
                System.exit(0);
            } else if (MCP_HISTORY_PATH_FLAG.equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(MCP_HISTORY_PATH_FLAG + " requires a path argument");
                }
                mcpHistoryPath = Path.of(args[++i]).toAbsolutePath().normalize();
            } else if (arg.startsWith(MCP_HISTORY_PATH_FLAG + "=")) {
                mcpHistoryPath = Path.of(arg.substring((MCP_HISTORY_PATH_FLAG + "=").length()))
                        .toAbsolutePath()
                        .normalize();
            }
        }

        Path projectPath = resolveProjectRoot(Path.of("."));
        logger.info("Brokk Core MCP Server starting for {}", projectPath);

        CoreProject coreProject = null;
        try {
            coreProject = new CoreProject(projectPath);
            logger.info("CoreProject initialized, detecting languages...");

            var langHandle = Languages.aggregate(coreProject.getAnalyzerLanguages());
            logger.info("Detected languages: {}", langHandle.name());

            IAnalyzer analyzer;
            if (langHandle == Languages.NONE) {
                logger.warn("No analyzable languages found in {}", projectPath);
                analyzer = new DisabledAnalyzer(coreProject);
            } else {
                logger.info("Building analyzer for {}...", langHandle.name());
                analyzer = langHandle.createAnalyzer(coreProject, (current, total, phase) -> {
                    if (total > 0 && current % 100 == 0) {
                        logger.info("Analyzing [{}]: {}/{} files", phase, current, total);
                    }
                });
                var metrics = analyzer.getMetrics();
                logger.info(
                        "Analyzer ready: {} declarations across {} files",
                        metrics.numberOfDeclarations(),
                        metrics.numberOfCodeUnits());
            }

            var coreIntelligence = new StandaloneCodeIntelligence(coreProject, analyzer);
            var coreSearchTools = new SearchTools(coreIntelligence);
            var server = new BrokkCoreMcpServer(coreProject, coreIntelligence, coreSearchTools, mcpHistoryPath);

            McpJsonMapper mapper = McpJsonDefaults.getMapper();
            AtomicReference<McpSyncServer> serverRef = new AtomicReference<>();
            AtomicBoolean shutdownInitiated = new AtomicBoolean(false);

            InputStream stdinWrapper = new FilterInputStream(System.in) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b == -1) {
                        initiateStdinEofShutdown(serverRef, shutdownInitiated);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = super.read(buf, off, len);
                    if (n == -1) {
                        initiateStdinEofShutdown(serverRef, shutdownInitiated);
                    }
                    return n;
                }
            };

            McpSyncServer mcpServer = McpServer.sync(new StdioServerTransportProvider(mapper, stdinWrapper, System.out))
                    .serverInfo("Brokk Core MCP Server", VERSION)
                    .jsonMapper(mapper)
                    .requestTimeout(Duration.ofHours(1))
                    .tools(server.toolSpecifications())
                    .build();
            serverRef.set(mcpServer);
            logger.info("Brokk Core MCP Server started successfully");

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                var s = serverRef.get();
                                if (s != null) {
                                    s.closeGracefully();
                                }
                            },
                            "BrokkCoreMCP-ShutdownHook"));

            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(0);
        } catch (Exception e) {
            logger.error("Failed to start Brokk Core MCP Server", e);
            System.exit(1);
        } finally {
            if (coreProject != null) {
                coreProject.close();
            }
        }
    }

    static Path resolveProjectRoot(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        Path current = Files.isDirectory(resolved) ? resolved : requireNonNull(resolved.getParent());
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(".git")) || Files.isRegularFile(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return resolved;
    }

    private static void initiateStdinEofShutdown(
            AtomicReference<McpSyncServer> serverRef, AtomicBoolean shutdownInitiated) {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            return;
        }
        logger.info("stdin EOF detected; shutting down");
        try {
            var s = serverRef.get();
            if (s != null) {
                s.closeGracefully();
            }
        } catch (Exception e) {
            logger.warn("Error closing MCP server after stdin EOF", e);
        } finally {
            System.exit(0);
        }
    }

    private void activateWorkspace(String workspacePath) {
        Path newRoot = resolveProjectRoot(Path.of(workspacePath));
        workspaceLock.writeLock().lock();
        try {
            if (newRoot.equals(activeWorkspaceRoot)) {
                logger.info("Workspace already active: {}", newRoot);
                return;
            }
            logger.info("Switching workspace from {} to {}", activeWorkspaceRoot, newRoot);

            project.close();

            var newProject = new CoreProject(newRoot);
            var langHandle = Languages.aggregate(newProject.getAnalyzerLanguages());
            IAnalyzer newAnalyzer;
            if (langHandle == Languages.NONE) {
                newAnalyzer = new DisabledAnalyzer(newProject);
            } else {
                newAnalyzer = langHandle.createAnalyzer(newProject, (current, total, phase) -> {});
            }

            this.project = newProject;
            this.intelligence = new StandaloneCodeIntelligence(newProject, newAnalyzer);
            this.searchTools = new SearchTools(this.intelligence);
            this.codeQualityTools = new CodeQualityToolsMcp(this.intelligence);
            this.activeWorkspaceRoot = newRoot;
            logger.info("Workspace switched to {}", newRoot);
        } finally {
            workspaceLock.writeLock().unlock();
        }
    }

    // -- Tool specifications --

    List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        var specs = new ArrayList<McpServerFeatures.SyncToolSpecification>();

        specs.add(tool(
                "activateWorkspace",
                "Set the active workspace for this MCP server. The path must be absolute. "
                        + "The server normalizes to the nearest git root.",
                schema(
                        Map.of("workspacePath", stringProp("Absolute path to the desired workspace directory.")),
                        List.of("workspacePath")),
                (exchange, request) -> {
                    activateWorkspace(stringArg(request, "workspacePath"));
                    return textResult("Workspace activated: " + activeWorkspaceRoot);
                }));

        specs.add(tool(
                "getActiveWorkspace",
                "Return the currently active workspace root.",
                schema(Map.of(), List.of()),
                (exchange, request) -> textResult(activeWorkspaceRoot.toString())));

        specs.add(tool(
                "searchSymbols",
                "Find where classes, functions, fields, and modules are defined. "
                        + "Returns human-readable declaration signatures with line numbers grouped by file and kind. "
                        + "Patterns are case-insensitive regex with implicit ^ and $, so use wildcarding: "
                        + ".*Foo.*, Abstract.*, [a-z]*DAO.",
                schema(
                        Map.of(
                                "patterns", arrayProp("Case-insensitive regex patterns to search for code symbols."),
                                "includeTests", boolProp("Include test files in results."),
                                "limit", intProp("Maximum number of matching files to return (capped at 100).")),
                        List.of("patterns", "includeTests", "limit")),
                (exchange, request) -> withReadLock(() -> {
                    var patterns = stringListArg(request, "patterns");
                    var includeTests = boolArg(request, "includeTests", false);
                    var limit = intArg(request, "limit", 50);
                    return textResult(searchTools.searchSymbols(patterns, includeTests, limit));
                })));

        specs.add(tool(
                "scanUsages",
                "Find where and how a symbol is used/called/accessed across the codebase. "
                        + "Requires exact symbol names, usually fully qualified. "
                        + "Use searchSymbols to identify candidate declarations when you only have a partial name.",
                schema(
                        Map.of(
                                "symbols", arrayProp("Fully qualified symbol names to find usages for."),
                                "includeTests", boolProp("Include call sites in test files.")),
                        List.of("symbols", "includeTests")),
                (exchange, request) -> withReadLock(() -> {
                    var symbols = stringListArg(request, "symbols");
                    var includeTests = boolArg(request, "includeTests", false);
                    return textResult(searchTools.scanUsages(symbols, includeTests));
                })));

        specs.add(tool(
                "getSummaries",
                "Understand the API surface and nearby structure of classes or files without reading full implementations. "
                        + "Accepts fully qualified class names, workspace-relative file paths, and file globs in one call. "
                        + "File targets for supported framework template DSLs may return structured template summaries. "
                        + "Use this to inspect fields, signatures, neighboring types, and package-level structure before deciding which classes or methods need full source. "
                        + "Do not use it for concrete method-body behavior, control flow, or line-level evidence; switch to getMethodSources or getFileContents for that. "
                        + "Example output: public class BillingService { Payment authorize(Order order); }",
                schema(Map.of("targets", arrayProp("Class names, file paths, or glob patterns.")), List.of("targets")),
                (exchange, request) -> withReadLock(() -> {
                    var targets = stringListArg(request, "targets");
                    return textResult(searchTools.getSummaries(targets));
                })));

        specs.add(tool(
                "getClassSources",
                "Returns full source code of classes. Max 10 classes. "
                        + "Prefer getSummaries or getMethodSources when possible.",
                schema(
                        Map.of("classNames", arrayProp("Fully qualified class names to retrieve source for; max 10.")),
                        List.of("classNames")),
                (exchange, request) -> withReadLock(() -> {
                    var classNames = stringListArg(request, "classNames");
                    return textResult(searchTools.getClassSources(classNames));
                })));

        specs.add(tool(
                "getMethodSources",
                "Returns full source code of specific methods/functions by fully qualified name. "
                        + "Preferred over getClassSources when you only need 1-2 methods.",
                schema(
                        Map.of("methodNames", arrayProp("Fully qualified method names to retrieve sources for.")),
                        List.of("methodNames")),
                (exchange, request) -> withReadLock(() -> {
                    var methodNames = stringListArg(request, "methodNames");
                    return textResult(searchTools.getMethodSources(methodNames));
                })));

        specs.add(tool(
                "getClassSkeletons",
                "Returns class skeletons (fields + method signatures) for specific classes by fully qualified name.",
                schema(
                        Map.of("classNames", arrayProp("Fully qualified class names to retrieve skeletons for.")),
                        List.of("classNames")),
                (exchange, request) -> withReadLock(() -> {
                    var classNames = stringListArg(request, "classNames");
                    return textResult(searchTools.getClassSkeletons(classNames));
                })));

        specs.add(tool(
                "getSymbolLocations",
                "Returns file locations for given fully qualified symbol names.",
                schema(Map.of("symbols", arrayProp("Fully qualified symbol names to locate.")), List.of("symbols")),
                (exchange, request) -> withReadLock(() -> {
                    var symbols = stringListArg(request, "symbols");
                    return textResult(searchTools.getSymbolLocations(symbols));
                })));

        specs.add(tool(
                "findFilenames",
                "Search for files by name pattern. Patterns are case-insensitive and match anywhere in the filename.",
                schema(
                        Map.of(
                                "patterns", arrayProp("Filename patterns to search for."),
                                "limit", intProp("Maximum results to return.")),
                        List.of("patterns", "limit")),
                (exchange, request) -> withReadLock(() -> {
                    var patterns = stringListArg(request, "patterns");
                    var limit = intArg(request, "limit", 50);
                    return textResult(searchTools.findFilenames(patterns, limit));
                })));

        specs.add(tool(
                "findFilesContaining",
                "Find files containing text matching the given regex patterns.",
                schema(
                        Map.of(
                                "patterns", arrayProp("Regex patterns to search for in file contents."),
                                "limit", intProp("Maximum results to return.")),
                        List.of("patterns", "limit")),
                (exchange, request) -> withReadLock(() -> {
                    var patterns = stringListArg(request, "patterns");
                    var limit = intArg(request, "limit", 50);
                    return textResult(searchTools.findFilesContaining(patterns, limit));
                })));

        specs.add(tool(
                "searchFileContents",
                "Search for regex patterns in file contents with optional filtering to declarations, usages, or all. "
                        + "In analyzed files, searchType=all also shows lower-signal related lines such as imports; "
                        + "usage hits are grouped beneath their enclosing symbol with bounded symbol-local context; "
                        + "searchType=declarations or usages hides related lines. Un-analyzed files always behave as all.",
                schema(
                        Map.of(
                                "patterns",
                                arrayProp("Regex patterns to search for."),
                                "filepath",
                                stringProp("File path or glob pattern to restrict search to."),
                                "searchType",
                                stringProp(
                                        "Which analyzed-code hits to show: declarations, usages, or all. Imports and other related lines only appear with all, and usage hits are grouped under their enclosing symbol."),
                                "caseInsensitive",
                                boolProp("Case-insensitive matching."),
                                "multiline",
                                boolProp("Enable multiline matching."),
                                "contextLines",
                                intProp("Number of context lines around each match."),
                                "maxFiles",
                                intProp("Maximum number of files to search.")),
                        List.of("patterns", "filepath")),
                (exchange, request) -> withReadLock(() -> {
                    var patterns = stringListArg(request, "patterns");
                    var filepath = stringArg(request, "filepath");
                    var searchType = stringArgOrDefault(request, "searchType", "all");
                    var caseInsensitive = boolArg(request, "caseInsensitive", false);
                    var multiline = boolArg(request, "multiline", false);
                    var contextLines = intArg(request, "contextLines", 2);
                    var maxFiles = intArg(request, "maxFiles", 20);
                    return textResult(searchTools.searchFileContents(
                            patterns, filepath, searchType, caseInsensitive, multiline, contextLines, maxFiles));
                })));

        specs.add(tool(
                "searchGitCommitMessages",
                "Search git commit messages for a pattern.",
                schema(
                        Map.of(
                                "pattern", stringProp("Regex pattern to search commit messages for."),
                                "limit", intProp("Maximum commits to return.")),
                        List.of("pattern", "limit")),
                (exchange, request) -> withReadLock(() -> {
                    var pattern = stringArg(request, "pattern");
                    var limit = intArg(request, "limit", 20);
                    return textResult(searchTools.searchGitCommitMessages(pattern, limit));
                })));

        specs.add(tool(
                "getGitLog",
                "Get git log for a file or directory path.",
                schema(
                        Map.of(
                                "path", stringProp("File or directory path to get git log for."),
                                "limit", intProp("Maximum log entries to return.")),
                        List.of("path", "limit")),
                (exchange, request) -> withReadLock(() -> {
                    var path = stringArg(request, "path");
                    var limit = intArg(request, "limit", 20);
                    return textResult(searchTools.getGitLog(path, limit));
                })));

        specs.add(tool(
                "getFileContents",
                "Read the full contents of one or more files.",
                schema(Map.of("filenames", arrayProp("Relative file paths to read.")), List.of("filenames")),
                (exchange, request) -> withReadLock(() -> {
                    var filenames = stringListArg(request, "filenames");
                    return textResult(searchTools.getFileContents(filenames));
                })));

        specs.add(tool(
                "listFiles",
                "List files in a directory, showing the tree structure.",
                schema(
                        Map.of("directoryPath", stringProp("Directory path relative to project root.")),
                        List.of("directoryPath")),
                (exchange, request) -> withReadLock(() -> {
                    var directoryPath = stringArg(request, "directoryPath");
                    return textResult(searchTools.listFiles(directoryPath));
                })));

        specs.add(tool(
                "skimFiles",
                "Get a quick overview of files showing top-level declarations without full source.",
                schema(Map.of("filePaths", arrayProp("File paths to skim.")), List.of("filePaths")),
                (exchange, request) -> withReadLock(() -> {
                    var filePaths = stringListArg(request, "filePaths");
                    return textResult(searchTools.skimFiles(filePaths));
                })));

        specs.add(tool(
                "jq",
                "Query JSON files using jq filter expressions.",
                schema(
                        Map.of(
                                "filepath", stringProp("Path or glob pattern for JSON files."),
                                "filter", stringProp("jq filter expression."),
                                "maxFiles", intProp("Maximum number of files to process."),
                                "matchesPerFile", intProp("Maximum matches per file.")),
                        List.of("filepath", "filter", "maxFiles", "matchesPerFile")),
                (exchange, request) -> withReadLock(() -> {
                    var filepath = stringArg(request, "filepath");
                    var filter = stringArg(request, "filter");
                    var maxFiles = intArg(request, "maxFiles", 10);
                    var matchesPerFile = intArg(request, "matchesPerFile", 50);
                    return textResult(searchTools.jq(filepath, filter, maxFiles, matchesPerFile));
                })));

        specs.add(tool(
                "xmlSkim",
                "Get a structural overview of XML/HTML files showing element hierarchy.",
                schema(
                        Map.of(
                                "filepath", stringProp("Path or glob pattern for XML/HTML files."),
                                "maxFiles", intProp("Maximum number of files to process.")),
                        List.of("filepath", "maxFiles")),
                (exchange, request) -> withReadLock(() -> {
                    var filepath = stringArg(request, "filepath");
                    var maxFiles = intArg(request, "maxFiles", 10);
                    return textResult(searchTools.xmlSkim(filepath, maxFiles));
                })));

        specs.add(tool(
                "xmlSelect",
                "Query XML/HTML files using XPath expressions.",
                schema(
                        Map.of(
                                "filepath", stringProp("Path or glob pattern for XML/HTML files."),
                                "xpath", stringProp("XPath expression to evaluate."),
                                "output", stringProp("Output format: 'text' (default) or 'xml'."),
                                "attrName", stringProp("Attribute name to extract (empty for element text)."),
                                "maxFiles", intProp("Maximum number of files to process."),
                                "matchesPerFile", intProp("Maximum matches per file.")),
                        List.of("filepath", "xpath")),
                (exchange, request) -> withReadLock(() -> {
                    var filepath = stringArg(request, "filepath");
                    var xpath = stringArg(request, "xpath");
                    var output = stringArgOrDefault(request, "output", "text");
                    var attrName = stringArgOrDefault(request, "attrName", "");
                    var maxFiles = intArg(request, "maxFiles", 10);
                    var matchesPerFile = intArg(request, "matchesPerFile", 50);
                    return textResult(
                            searchTools.xmlSelect(filepath, xpath, output, attrName, maxFiles, matchesPerFile));
                })));

        // -- Code quality analysis tools --

        specs.add(tool(
                "computeCyclomaticComplexity",
                "Computes heuristic cyclomatic complexity for methods in the given files. "
                        + "Flags methods above the threshold (typical default 10) for review or refactor. "
                        + "Returns a markdown-friendly report of flagged methods.",
                schema(
                        Map.of(
                                "filePaths", arrayProp("File paths relative to the project root."),
                                "threshold",
                                        intProp(
                                                "Complexity threshold; methods above this are flagged. Use 0 or negative for default (10).")),
                        List.of("filePaths")),
                (exchange, request) -> withReadLock(() -> {
                    var filePaths = stringListArg(request, "filePaths");
                    var threshold = intArg(request, "threshold", 0);
                    return textResult(codeQualityTools.computeCyclomaticComplexity(filePaths, threshold));
                })));

        specs.add(tool(
                "reportCommentDensityForCodeUnit",
                "Java comment density for one symbol identified by fully qualified name. "
                        + "Reports header vs inline comment line counts, declaration span lines, and rolled-up totals for class-like units.",
                schema(
                        Map.of(
                                "fqName",
                                        stringProp(
                                                "Fully qualified name (e.g. com.example.MyClass or com.example.MyClass.method)."),
                                "maxLines", intProp("Maximum output lines; values <= 0 default to 120.")),
                        List.of("fqName")),
                (exchange, request) -> withReadLock(() -> {
                    var fqName = stringArg(request, "fqName");
                    var maxLines = intArg(request, "maxLines", 0);
                    return textResult(codeQualityTools.reportCommentDensityForCodeUnit(fqName, maxLines));
                })));

        specs.add(tool(
                "reportCommentDensityForFiles",
                "Java comment density tables for the given source files: one section per file and one row per top-level declaration. "
                        + "Includes own and rolled-up header/inline line counts.",
                schema(
                        Map.of(
                                "filePaths", arrayProp("File paths relative to the project root."),
                                "maxTopLevelRows",
                                        intProp(
                                                "Maximum declaration rows across all files; values <= 0 default to 60."),
                                "maxFiles", intProp("Maximum files to include; values <= 0 default to 25.")),
                        List.of("filePaths")),
                (exchange, request) -> withReadLock(() -> {
                    var filePaths = stringListArg(request, "filePaths");
                    var maxTopLevelRows = intArg(request, "maxTopLevelRows", 0);
                    var maxFiles = intArg(request, "maxFiles", 0);
                    return textResult(
                            codeQualityTools.reportCommentDensityForFiles(filePaths, maxTopLevelRows, maxFiles));
                })));

        specs.add(tool(
                "reportExceptionHandlingSmells",
                "Detects suspicious exception handlers using weighted heuristics designed for high-recall triage. "
                        + "Scores generic catches and tiny/empty handlers, then subtracts credit for richer handling bodies. "
                        + "Use minScore, maxFindings, and weight parameters to tune precision/recall.",
                schema(
                        Map.ofEntries(
                                entry("filePaths", arrayProp("File paths relative to the project root.")),
                                entry(
                                        "minScore",
                                        intProp("Minimum score to include a finding; values <= 0 default to 4.")),
                                entry("maxFindings", intProp("Maximum findings to emit; values <= 0 default to 80.")),
                                entry(
                                        "genericThrowableWeight",
                                        intProp("Weight for catching Throwable; values < 0 use default.")),
                                entry(
                                        "genericExceptionWeight",
                                        intProp("Weight for catching Exception; values < 0 use default.")),
                                entry(
                                        "genericRuntimeExceptionWeight",
                                        intProp("Weight for catching RuntimeException; values < 0 use default.")),
                                entry(
                                        "emptyBodyWeight",
                                        intProp("Weight for empty catch bodies; values < 0 use default.")),
                                entry(
                                        "commentOnlyBodyWeight",
                                        intProp("Weight for comment-only catch bodies; values < 0 use default.")),
                                entry(
                                        "smallBodyWeight",
                                        intProp("Weight for small catch bodies; values < 0 use default.")),
                                entry(
                                        "logOnlyBodyWeight",
                                        intProp("Weight for log-only catch bodies; values < 0 use default.")),
                                entry(
                                        "meaningfulBodyCreditPerStatement",
                                        intProp(
                                                "Score credit subtracted per catch statement in the body; values < 0 use default.")),
                                entry(
                                        "meaningfulBodyStatementThreshold",
                                        intProp(
                                                "Maximum statements that earn meaningful-body credit; values < 0 use default.")),
                                entry(
                                        "smallBodyMaxStatements",
                                        intProp(
                                                "Maximum statement count considered a small body; values < 0 use default."))),
                        List.of("filePaths")),
                (exchange, request) -> withReadLock(() -> {
                    var filePaths = stringListArg(request, "filePaths");
                    return textResult(codeQualityTools.reportExceptionHandlingSmells(
                            filePaths,
                            intArg(request, "minScore", -1),
                            intArg(request, "maxFindings", -1),
                            intArg(request, "genericThrowableWeight", -1),
                            intArg(request, "genericExceptionWeight", -1),
                            intArg(request, "genericRuntimeExceptionWeight", -1),
                            intArg(request, "emptyBodyWeight", -1),
                            intArg(request, "commentOnlyBodyWeight", -1),
                            intArg(request, "smallBodyWeight", -1),
                            intArg(request, "logOnlyBodyWeight", -1),
                            intArg(request, "meaningfulBodyCreditPerStatement", -1),
                            intArg(request, "meaningfulBodyStatementThreshold", -1),
                            intArg(request, "smallBodyMaxStatements", -1)));
                })));

        specs.add(tool(
                "reportStructuralCloneSmells",
                "Detects duplicated implementation patterns across functions using normalized token similarity. "
                        + "Uses analyzer-provided structural clone smells (with AST refinement) for high-recall triage. "
                        + "Tune similarity and size thresholds to reduce noise.",
                schema(
                        Map.of(
                                "filePaths", arrayProp("File paths relative to the project root."),
                                "minScore",
                                        intProp(
                                                "Minimum similarity score (0-100) to include; values <= 0 default to 60."),
                                "minNormalizedTokens",
                                        intProp("Minimum normalized token count; values <= 0 default to 12."),
                                "shingleSize",
                                        intProp("Shingle size used for token overlap; values <= 0 default to 2."),
                                "minSharedShingles",
                                        intProp("Minimum shared shingles before scoring; values <= 0 default to 3."),
                                "astSimilarityPercent",
                                        intProp("AST refinement threshold (0-100); values <= 0 default to 70."),
                                "maxFindings", intProp("Maximum findings to emit; values <= 0 default to 80.")),
                        List.of("filePaths")),
                (exchange, request) -> withReadLock(() -> {
                    var filePaths = stringListArg(request, "filePaths");
                    return textResult(codeQualityTools.reportStructuralCloneSmells(
                            filePaths,
                            intArg(request, "minScore", -1),
                            intArg(request, "minNormalizedTokens", -1),
                            intArg(request, "shingleSize", -1),
                            intArg(request, "minSharedShingles", -1),
                            intArg(request, "astSimilarityPercent", -1),
                            intArg(request, "maxFindings", -1)));
                })));

        // NOTE: analyzeGitHotspots is not exposed here because GitHotspotAnalyzer
        // lives in the app module with dependencies not available in brokk-core.

        return specs;
    }

    // -- Locking helper --

    @FunctionalInterface
    private interface LockedSupplier {
        McpSchema.CallToolResult get() throws Exception;
    }

    private McpSchema.CallToolResult withReadLock(LockedSupplier supplier) throws Exception {
        workspaceLock.readLock().lock();
        try {
            return supplier.get();
        } finally {
            workspaceLock.readLock().unlock();
        }
    }

    // -- Tool spec builders --

    @FunctionalInterface
    private interface ToolHandler {
        McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
                throws Exception;
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name, String description, McpSchema.JsonSchema inputSchema, ToolHandler handler) {
        var mcpTool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(mcpTool)
                .callHandler((exchange, request) -> {
                    var historyWriter = mcpToolCallHistoryWriter;
                    var logFile =
                            historyWriter != null ? historyWriter.writeRequest(name, serializeRequest(request)) : null;
                    try {
                        if (historyWriter != null && logFile != null) {
                            historyWriter.appendProgress(logFile, 0.0, "Starting " + name);
                        }
                        var result = handler.handle(exchange, request);
                        appendLoggedResult(historyWriter, logFile, result);
                        return result;
                    } catch (Exception e) {
                        logger.error("Error executing tool {}", name, e);
                        var errorResult = McpSchema.CallToolResult.builder()
                                .addTextContent("Error: " + e.getMessage())
                                .isError(true)
                                .build();
                        appendLoggedResult(historyWriter, logFile, errorResult);
                        return errorResult;
                    }
                })
                .build();
    }

    private static String serializeRequest(McpSchema.CallToolRequest request) {
        try {
            return McpJsonDefaults.getMapper().writeValueAsString(request);
        } catch (IOException e) {
            return "{}";
        }
    }

    private static void appendLoggedResult(
            @Nullable McpToolCallHistoryWriter historyWriter, @Nullable Path logFile, McpSchema.CallToolResult result) {
        if (historyWriter == null || logFile == null) {
            return;
        }
        String status = result.isError() != null && result.isError() ? "ERROR" : "SUCCESS";
        historyWriter.appendProgress(logFile, 1.0, "Completed");
        historyWriter.appendResult(logFile, status, renderTextContent(result));
    }

    private static String renderTextContent(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining("\n"));
    }

    private static McpSchema.JsonSchema schema(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema("object", properties, required, false, null, null);
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> boolProp(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> intProp(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> arrayProp(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
    }

    private static McpSchema.CallToolResult textResult(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).build();
    }

    // -- Argument extraction --

    private static String stringArgOrDefault(McpSchema.CallToolRequest request, String name, String defaultValue) {
        var args = request.arguments() != null ? request.arguments() : Map.<String, Object>of();
        var value = args.get(name);
        return value != null ? value.toString() : defaultValue;
    }

    private static String stringArg(McpSchema.CallToolRequest request, String name) {
        var args = request.arguments() != null ? request.arguments() : Map.<String, Object>of();
        var value = args.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return value.toString();
    }

    private static boolean boolArg(McpSchema.CallToolRequest request, String name, boolean defaultValue) {
        var args = request.arguments() != null ? request.arguments() : Map.<String, Object>of();
        var value = args.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static int intArg(McpSchema.CallToolRequest request, String name, int defaultValue) {
        var args = request.arguments() != null ? request.arguments() : Map.<String, Object>of();
        var value = args.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static List<String> stringListArg(McpSchema.CallToolRequest request, String name) {
        var args = request.arguments() != null ? request.arguments() : Map.<String, Object>of();
        var value = args.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        throw new IllegalArgumentException("Argument " + name + " must be an array");
    }
}
