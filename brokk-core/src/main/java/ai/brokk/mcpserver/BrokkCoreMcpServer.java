package ai.brokk.mcpserver;

import static java.util.Objects.requireNonNull;

import ai.brokk.ICodeIntelligence;
import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.project.CoreProject;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone MCP server providing pure code intelligence tools.
 * No LLM dependencies - uses tree-sitter analysis only.
 */
public class BrokkCoreMcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkCoreMcpServer.class);
    private static final String VERSION = "0.1.0";

    private final ReentrantReadWriteLock workspaceLock = new ReentrantReadWriteLock(true);
    private CoreProject project;
    private ICodeIntelligence intelligence;
    private SearchTools searchTools;
    private Path activeWorkspaceRoot;

    public BrokkCoreMcpServer(CoreProject project, ICodeIntelligence intelligence, SearchTools searchTools) {
        this.project = project;
        this.intelligence = intelligence;
        this.searchTools = searchTools;
        this.activeWorkspaceRoot = project.getRoot();
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("Brokk Core MCP Server v" + VERSION);
                System.out.println("Provides code intelligence tools via Model Context Protocol.");
                System.out.println("No LLM dependencies - pure tree-sitter analysis.");
                System.exit(0);
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
            var server = new BrokkCoreMcpServer(coreProject, coreIntelligence, coreSearchTools);

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
                        + "Requires fully qualified symbol names -- call searchSymbols first if you only have a partial name.",
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
                "getFileSummaries",
                "Returns class skeletons (fields + method signatures, no bodies) for all classes in the "
                        + "specified files. Supports glob patterns: '*' matches one directory, '**' matches recursively.",
                schema(
                        Map.of("filePaths", arrayProp("File paths relative to project root. Supports glob patterns.")),
                        List.of("filePaths")),
                (exchange, request) -> withReadLock(() -> {
                    var filePaths = stringListArg(request, "filePaths");
                    return textResult(searchTools.getFileSummaries(filePaths));
                })));

        specs.add(tool(
                "getClassSources",
                "Returns full source code of classes. Max 10 classes. "
                        + "Prefer getFileSummaries or getMethodSources when possible.",
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
                "Search for regex patterns in file contents with context lines.",
                schema(
                        Map.of(
                                "patterns", arrayProp("Regex patterns to search for."),
                                "filepath", stringProp("File path or glob pattern to restrict search to."),
                                "caseInsensitive", boolProp("Case-insensitive matching."),
                                "multiline", boolProp("Enable multiline matching."),
                                "contextLines", intProp("Number of context lines around each match."),
                                "maxFiles", intProp("Maximum number of files to search.")),
                        List.of("patterns", "filepath")),
                (exchange, request) -> withReadLock(() -> {
                    var patterns = stringListArg(request, "patterns");
                    var filepath = stringArg(request, "filepath");
                    var caseInsensitive = boolArg(request, "caseInsensitive", false);
                    var multiline = boolArg(request, "multiline", false);
                    var contextLines = intArg(request, "contextLines", 2);
                    var maxFiles = intArg(request, "maxFiles", 20);
                    return textResult(searchTools.searchFileContents(
                            patterns, filepath, caseInsensitive, multiline, contextLines, maxFiles));
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

    private static McpServerFeatures.SyncToolSpecification tool(
            String name, String description, McpSchema.JsonSchema inputSchema, ToolHandler handler) {
        var mcpTool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(mcpTool)
                .callHandler((exchange, request) -> {
                    try {
                        return handler.handle(exchange, request);
                    } catch (Exception e) {
                        logger.error("Error executing tool {}", name, e);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("Error: " + e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();
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
