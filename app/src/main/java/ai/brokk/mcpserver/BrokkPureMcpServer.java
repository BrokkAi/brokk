package ai.brokk.mcpserver;

import static java.util.Objects.requireNonNull;

import ai.brokk.AnalyzerUtil;
import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.LlmOutputMeta;
import ai.brokk.MutedConsoleIO;
import ai.brokk.agents.ConflictInspector;
import ai.brokk.agents.MergeAgent;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.analyzer.usages.UsageFinder;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.git.GitRepo;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.BuildTools;
import ai.brokk.util.BuildVerifier;
import ai.brokk.util.Environment;
import ai.brokk.util.Lines;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Pure MCP server that exposes only non-LLM code intelligence tools.
 * LLM-dependent tools (scan, callCodeAgent, merge, explainCommit) are removed;
 * their functionality is replaced by Claude Code skills that use these pure tools
 * combined with Claude's own reasoning.
 */
@NullMarked
public class BrokkPureMcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkPureMcpServer.class);

    private static final List<String> PURE_TOOL_NAMES = List.of(
            "activateWorkspace",
            "getActiveWorkspace",
            "runBuild",
            "configureBuild",
            "getCommitDiff",
            "getConflictInfo",
            "searchSymbols",
            "scanUsages",
            "skimFiles",
            "getFileSummaries",
            "getClassSkeletons",
            "getSymbolLocations",
            "searchFileContents",
            "getClassSources",
            "getMethodSources");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReentrantReadWriteLock workspaceLock = new ReentrantReadWriteLock(true);
    private MainProject project;
    private ContextManager cm;
    private @Nullable McpToolCallHistoryWriter mcpToolCallHistoryWriter;
    private Path activeWorkspaceRoot;
    private WorkspaceActivationSource activeWorkspaceSource;

    private enum WorkspaceActivationSource {
        STARTUP,
        RUNTIME_OVERRIDE
    }

    private record WorkspaceState(
            Path workspaceRoot,
            MainProject project,
            ContextManager contextManager,
            @Nullable McpToolCallHistoryWriter historyWriter) {}

    public BrokkPureMcpServer(ContextManager cm) {
        this.project = mainProjectFrom(cm.getProject());
        this.cm = cm;
        this.activeWorkspaceRoot = cm.getProject().getRoot().toAbsolutePath().normalize();
        this.activeWorkspaceSource = WorkspaceActivationSource.STARTUP;
        this.mcpToolCallHistoryWriter = createMcpToolCallHistoryWriter(this.activeWorkspaceRoot);
    }

    private static @Nullable McpToolCallHistoryWriter createMcpToolCallHistoryWriter(Path projectRoot) {
        try {
            return new McpToolCallHistoryWriter(projectRoot.resolve(".brokk").resolve("mcp-history"));
        } catch (IOException e) {
            logger.warn("Failed to initialize MCP tool call history logging", e);
            return null;
        }
    }

    private static MainProject mainProjectFrom(IProject project) {
        assert project instanceof MainProject : "BrokkPureMcpServer requires MainProject";
        return (MainProject) project;
    }

    private static WorkspaceState createWorkspaceState(Path requestedWorkspacePath) {
        Path workspaceRoot = resolveProjectRoot(requestedWorkspacePath);
        MainProject mainProject = null;
        ContextManager contextManager = null;
        try {
            mainProject = new MainProject(workspaceRoot);
            contextManager = new ContextManager(mainProject);
            contextManager.createHeadless(true, new MutedConsoleIO(contextManager.getIo()));
            var historyWriter = createMcpToolCallHistoryWriter(workspaceRoot);
            return new WorkspaceState(workspaceRoot, mainProject, contextManager, historyWriter);
        } catch (RuntimeException e) {
            if (contextManager != null) {
                contextManager.close();
            }
            if (mainProject != null) {
                mainProject.close();
            }
            throw e;
        }
    }

    private void closeCurrentWorkspace() {
        workspaceLock.writeLock().lock();
        try {
            cm.close();
            project.close();
        } finally {
            workspaceLock.writeLock().unlock();
        }
    }

    private static void initiateStdinEofShutdown(
            AtomicReference<McpSyncServer> serverRef, AtomicBoolean shutdownInitiated) {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            return;
        }

        logger.info("stdin EOF detected; parent process gone, shutting down");
        try {
            var server = serverRef.get();
            if (server != null) {
                server.closeGracefully();
            }
        } catch (Exception e) {
            logger.warn("Error while closing MCP server after stdin EOF", e);
        } finally {
            System.exit(0);
        }
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        Path projectPath = resolveProjectRoot(Path.of("."));
        logger.info("Brokk Pure MCP Server starting");

        BrokkPureMcpServer instance = null;
        try {
            WorkspaceState startupWorkspace = createWorkspaceState(projectPath);
            instance = new BrokkPureMcpServer(startupWorkspace.contextManager());
            instance.project = startupWorkspace.project();
            instance.cm = startupWorkspace.contextManager();
            instance.activeWorkspaceRoot = startupWorkspace.workspaceRoot();
            instance.activeWorkspaceSource = WorkspaceActivationSource.STARTUP;
            instance.mcpToolCallHistoryWriter = startupWorkspace.historyWriter();

            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    System.out.println("Brokk Pure MCP Server v" + BuildInfo.version);
                    System.out.println("Provides Model Context Protocol (MCP) access to Brokk's pure code intelligence tools.");
                    System.out.println("No LLM inference required -- all analysis is done via parsing and indexing.");
                    System.out.println();
                    System.out.println("Available Tools:");
                    instance.toolSpecifications().forEach(spec -> {
                        System.out.printf(
                                "  - %-20s : %s%n",
                                spec.tool().name(), spec.tool().description());
                    });
                    System.exit(0);
                }
            }

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
                    .serverInfo("Brokk Pure MCP Server", BuildInfo.version)
                    .jsonMapper(mapper)
                    .requestTimeout(Duration.ofHours(1))
                    .tools(instance.toolSpecifications())
                    .build();
            serverRef.set(mcpServer);
            logger.info("Brokk Pure MCP Server started");
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                var server = requireNonNull(serverRef.get());
                                server.closeGracefully();
                            },
                            "BrokkPureMCP-Server-ShutdownHook"));

            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(0);
        } catch (Exception e) {
            logger.error("Failed to start Brokk Pure MCP Server", e);
            System.exit(1);
        } finally {
            if (instance != null) {
                instance.closeCurrentWorkspace();
            }
        }
    }

    static Path resolveProjectRoot(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        Path current = Files.isDirectory(resolved) ? resolved : requireNonNull(resolved.getParent());

        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path gitPath = candidate.resolve(".git");
            if (Files.isDirectory(gitPath) || Files.isRegularFile(gitPath)) {
                return candidate;
            }
        }

        return resolved;
    }

    // ---- Pure tool methods ----

    @Tool(
            """
            Set the active workspace for this running Brokk MCP server.
            Use this for clients that keep a global MCP server config and need to retarget tools to a new repository.
            The path must be absolute. The server normalizes to the nearest git root when present.
            """)
    public String activateWorkspace(
            @P("Absolute path to the desired workspace (directory path).") String workspacePath) {
        workspaceLock.writeLock().lock();
        try {
            Path rawPath;
            try {
                rawPath = Path.of(workspacePath);
            } catch (RuntimeException e) {
                throw new ToolRegistry.ToolCallException(
                        ToolExecutionResult.Status.REQUEST_ERROR, "workspacePath is invalid: " + workspacePath);
            }

            if (!rawPath.isAbsolute()) {
                throw new ToolRegistry.ToolCallException(
                        ToolExecutionResult.Status.REQUEST_ERROR, "workspacePath must be an absolute path");
            }

            Path requestedPath;
            try {
                requestedPath = rawPath.toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                throw new ToolRegistry.ToolCallException(
                        ToolExecutionResult.Status.REQUEST_ERROR, "workspacePath is invalid: " + workspacePath);
            }

            if (!Files.isDirectory(requestedPath)) {
                throw new ToolRegistry.ToolCallException(
                        ToolExecutionResult.Status.REQUEST_ERROR, "workspacePath must reference an existing directory");
            }

            Path normalizedRoot = resolveProjectRoot(requestedPath);
            boolean changed = !normalizedRoot.equals(activeWorkspaceRoot);
            if (changed) {
                WorkspaceState newWorkspace;
                try {
                    newWorkspace = createWorkspaceState(normalizedRoot);
                } catch (RuntimeException e) {
                    throw new ToolRegistry.ToolCallException(
                            ToolExecutionResult.Status.INTERNAL_ERROR,
                            "Failed to activate workspace: " + e.getMessage());
                }

                var previousCm = cm;
                var previousProject = project;

                cm = newWorkspace.contextManager();
                project = newWorkspace.project();
                mcpToolCallHistoryWriter = newWorkspace.historyWriter();
                activeWorkspaceRoot = newWorkspace.workspaceRoot();
                activeWorkspaceSource = WorkspaceActivationSource.RUNTIME_OVERRIDE;

                previousCm.close();
                previousProject.close();
            } else {
                activeWorkspaceSource = WorkspaceActivationSource.RUNTIME_OVERRIDE;
            }

            return activationStatusJson(activeWorkspaceRoot, changed, activeWorkspaceSource);
        } finally {
            workspaceLock.writeLock().unlock();
        }
    }

    @Tool("Return the currently active workspace root and activation source for this MCP server instance.")
    public String getActiveWorkspace() {
        workspaceLock.readLock().lock();
        try {
            return activationStatusJson(activeWorkspaceRoot, false, activeWorkspaceSource);
        } finally {
            workspaceLock.readLock().unlock();
        }
    }

    private static String activationStatusJson(Path root, boolean changed, WorkspaceActivationSource source) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "activeWorkspacePath",
                    root.toString(),
                    "changed",
                    changed,
                    "source",
                    source == WorkspaceActivationSource.STARTUP ? "startup" : "runtime_override"));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Tool(
            """
            Run build verification (compile and test) without making changes.
            Use to verify the build is still passing, or to check current build status.
            """)
    public String runBuild() throws InterruptedException {
        String error = cm.getProject().getBuildRunner().runVerification(cm);
        return error.isEmpty() ? "Build successful" : "Build failed:\n\n" + error;
    }

    @Tool(
            "Configure build and test commands for the project. You can assume it has already been configured unless you are told otherwise.")
    public String configureBuild(
            @P("Command to build or lint incrementally, e.g. 'mvn compile', 'cargo check'. Must not be null.")
                    String buildOnlyCmd,
            @P(
                            "Mustache template for running specific tests. Supports {{#files}}, {{#classes}}, {{#fqclasses}}. Must not be null.")
                    String testSomeCmd)
            throws InterruptedException {
        var project = cm.getProject();
        var existingDetails = project.loadBuildDetails().orElse(ai.brokk.agents.BuildAgent.BuildDetails.EMPTY);

        BuildVerifier.VerificationResult buildResult = BuildVerifier.verify(project, buildOnlyCmd);
        if (!buildResult.success()) {
            return "Configuration failed: buildOnlyCmd ('" + buildOnlyCmd + "') failed with exit code "
                    + buildResult.exitCode() + ":\n" + buildResult.output();
        }

        var testFileOpt = project.getAllFiles().stream()
                .filter(f -> cm.getAnalyzerUninterrupted().containsTests(f))
                .findFirst();

        var bd = new ai.brokk.agents.BuildAgent.BuildDetails(
                buildOnlyCmd,
                existingDetails.buildLintEnabled(),
                existingDetails.testAllCommand(),
                existingDetails.testAllEnabled(),
                testSomeCmd,
                true,
                existingDetails.exclusionPatterns(),
                existingDetails.environmentVariables(),
                existingDetails.maxBuildAttempts(),
                existingDetails.afterTaskListCommand(),
                existingDetails.modules());
        if (testFileOpt.isPresent()) {
            String interpolatedTestCmd = BuildTools.getBuildLintSomeCommand(cm, bd, List.of(testFileOpt.get()));

            BuildVerifier.VerificationResult testResult = BuildVerifier.verify(project, interpolatedTestCmd);
            if (!testResult.success()) {
                return "Configuration failed: testSomeCmd verification failed using command '" + interpolatedTestCmd
                        + "'. Exit code " + testResult.exitCode() + ":\n" + testResult.output();
            }
        }

        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.WORKSPACE);
        project.setBuildDetails(bd);
        project.saveBuildDetails(bd);

        return "Build configuration verified (build and test) and updated.";
    }

    @Tool(
            """
            Get the unified diff for a commit against its parent.
            Use this to inspect what changed in a specific commit without LLM summarization.
            Returns the raw diff output that you can analyze directly.
            """)
    public String getCommitDiff(
            @P("Commit id, SHA, or revision (e.g. 'HEAD', 'abc1234', 'HEAD~3').") String revision)
            throws GitAPIException {
        var repo = (GitRepo) cm.getProject().getRepo();
        var shortHash = repo.shortHash(revision);
        String parentRev = revision + "^";
        String diff;
        try {
            repo.resolveToObject(parentRev);
            diff = repo.getDiff(parentRev, revision);
        } catch (GitAPIException e) {
            diff = repo.getDiff(Constants.EMPTY_TREE_ID.getName(), revision);
        }

        if (diff.isBlank()) {
            return "No changes detected for " + shortHash + ".";
        }
        return "# Diff for " + shortHash + "\n\n```diff\n" + diff.strip() + "\n```";
    }

    @Tool(
            """
            Inspect the repository for merge, rebase, or cherry-pick conflicts.
            Returns structured conflict metadata including conflicting files, merge mode,
            and commit IDs for ours/theirs/base sides.
            Use this to understand conflicts before resolving them with Edit.
            Only returns data when the repository is in a conflict state.
            """)
    public String getConflictInfo() {
        var conflictOpt = ConflictInspector.inspectFromProject(cm.getProject());
        if (conflictOpt.isEmpty()) {
            return "Repository is not in a merge conflict state.";
        }

        var conflict = conflictOpt.get();
        try {
            var result = new HashMap<String, Object>();
            result.put("state", conflict.state().name());
            result.put("ourCommitId", conflict.ourCommitId());
            result.put("otherCommitId", conflict.otherCommitId());
            if (conflict.baseCommitId() != null) {
                result.put("baseCommitId", conflict.baseCommitId());
            }
            var files = conflict.files().stream()
                    .map(f -> {
                        var fileMap = new HashMap<String, Object>();
                        if (f.ourFile() != null) {
                            fileMap.put("ourFile", f.ourFile().toString());
                        }
                        if (f.ourContent() != null) {
                            fileMap.put("ourContent", f.ourContent());
                        }
                        if (f.theirFile() != null) {
                            fileMap.put("theirFile", f.theirFile().toString());
                        }
                        if (f.theirContent() != null) {
                            fileMap.put("theirContent", f.theirContent());
                        }
                        if (f.baseFile() != null) {
                            fileMap.put("baseFile", f.baseFile().toString());
                        }
                        if (f.baseContent() != null) {
                            fileMap.put("baseContent", f.baseContent());
                        }
                        return fileMap;
                    })
                    .toList();
            result.put("files", files);
            result.put("fileCount", conflict.files().size());
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Tool registration and MCP specification ----

    private ToolRegistry buildToolRegistryForCurrentWorkspace() {
        SearchTools searchTools = new SearchTools(cm);
        return ToolRegistry.fromBase(ToolRegistry.empty())
                .register(this)
                .register(searchTools)
                .build();
    }

    public List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        ToolRegistry registry = buildToolRegistryForCurrentWorkspace();
        List<String> toolNames = new ArrayList<>(PURE_TOOL_NAMES);
        var allFiles = cm.getProject().getAllFiles();
        boolean hasXml = allFiles.stream().anyMatch(f -> f.toString().endsWith(".xml"));
        boolean hasJson = allFiles.stream().anyMatch(f -> f.toString().endsWith(".json"));

        if (hasXml) {
            toolNames.add("xmlSkim");
            toolNames.add("xmlSelect");
        }

        if (hasJson && !isJqOnPath()) {
            toolNames.add("jq");
        }

        return registry.getTools(toolNames).stream()
                .map(spec -> {
                    McpSchema.JsonSchema inputSchema = spec.parameters() == null
                            ? new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)
                            : toMcpSchema(spec.parameters());

                    boolean destructive = registry.isToolAnnotated(spec.name(), Destructive.class);
                    McpSchema.ToolAnnotations toolAnnotations =
                            new McpSchema.ToolAnnotations(null, !destructive, destructive, null, null, null);

                    McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                            .name(spec.name())
                            .description(spec.description())
                            .inputSchema(inputSchema)
                            .annotations(toolAnnotations)
                            .build();

                    return McpServerFeatures.SyncToolSpecification.builder()
                            .tool(mcpTool)
                            .callHandler((exchange, request) -> {
                                var args = request.arguments() != null ? request.arguments() : Map.of();
                                String jsonArgs;
                                try {
                                    jsonArgs = OBJECT_MAPPER.writeValueAsString(args);
                                } catch (JsonProcessingException e) {
                                    throw new RuntimeException(e);
                                }

                                return runWithWorkspaceLock(() -> {
                                    var historyWriter = mcpToolCallHistoryWriter;
                                    var logFile = historyWriter != null
                                            ? historyWriter.writeRequest(spec.name(), serializeRequest(request))
                                            : null;

                                    Object progressToken = request.progressToken();
                                    if (progressToken != null) {
                                        exchange.progressNotification(new McpSchema.ProgressNotification(
                                                progressToken, 0.0, 1.0, "Starting " + spec.name()));
                                        if (historyWriter != null) {
                                            historyWriter.appendProgress(
                                                    requireNonNull(logFile), 0.0, "Starting " + spec.name());
                                        }
                                    }

                                    ContextManager currentCm = cm;
                                    ToolRegistry currentRegistry = buildToolRegistryForCurrentWorkspace();
                                    IConsoleIO originalIo = currentCm.getIo();
                                    IConsoleIO progressIo = progressToken != null
                                            ? new ProgressNotifyingConsole(
                                                    exchange, progressToken, historyWriter, logFile)
                                            : new MutedConsoleIO(originalIo);
                                    currentCm.setIo(progressIo);

                                    try {
                                        try {
                                            ToolExecutionRequest lc4jRequest = ToolExecutionRequest.builder()
                                                    .id("1")
                                                    .name(spec.name())
                                                    .arguments(jsonArgs)
                                                    .build();
                                            var result = currentRegistry.executeTool(lc4jRequest);
                                            String resultText = result.status() == ToolExecutionResult.Status.SUCCESS
                                                    ? maybeApplyMcpFormatting(
                                                            spec.name(), args, result.resultText(), currentCm)
                                                    : result.resultText();
                                            McpSchema.CallToolResult callResult = McpSchema.CallToolResult.builder()
                                                    .addTextContent(resultText)
                                                    .isError(result.status() != ToolExecutionResult.Status.SUCCESS)
                                                    .build();

                                            if (historyWriter != null) {
                                                String statusStr = callResult.isError() != null && callResult.isError()
                                                        ? "ERROR"
                                                        : "SUCCESS";
                                                String body = callResult.content().stream()
                                                        .filter(c -> c instanceof McpSchema.TextContent)
                                                        .map(c -> ((McpSchema.TextContent) c).text())
                                                        .collect(Collectors.joining("\n"));
                                                historyWriter.appendResult(requireNonNull(logFile), statusStr, body);
                                            }
                                            return callResult;
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new RuntimeException(e);
                                        }
                                    } finally {
                                        currentCm.setIo(originalIo);
                                    }
                                });
                            })
                            .build();
                })
                .collect(Collectors.toList());
    }

    private <T> T runWithWorkspaceLock(Supplier<T> supplier) {
        var lock = workspaceLock.writeLock();
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    private boolean isJqOnPath() {
        try {
            Environment.instance.runShellCommand(
                    "jq --version", cm.getProject().getRoot(), out -> {}, Duration.ofSeconds(2));
            return true;
        } catch (Environment.SubprocessException | InterruptedException e) {
            return false;
        }
    }

    // ---- MCP formatting helpers ----

    private static String maybeApplyMcpFormatting(
            String toolName, Map<?, ?> args, String rawResultText, ContextManager cm) {
        if ("getFileContents".equals(toolName)) {
            return formatGetFileContentsForMcp(args, rawResultText, cm);
        }
        if ("getClassSources".equals(toolName)) {
            return formatGetCodeSourcesForMcp(args, rawResultText, cm, "classNames", true);
        }
        if ("getMethodSources".equals(toolName)) {
            return formatGetCodeSourcesForMcp(args, rawResultText, cm, "methodNames", false);
        }
        if ("scanUsages".equals(toolName)) {
            return formatScanUsagesForMcp(args, rawResultText, cm);
        }
        return rawResultText;
    }

    private static String formatGetFileContentsForMcp(Map<?, ?> args, String rawResultText, ContextManager cm) {
        Object filenamesObj = args.get("filenames");
        if (!(filenamesObj instanceof List<?> filenamesList) || filenamesList.isEmpty()) {
            return rawResultText;
        }

        List<String> filenames = filenamesList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .distinct()
                .toList();
        if (filenames.isEmpty()) {
            return rawResultText;
        }

        StringBuilder result = new StringBuilder();
        int successCount = 0;
        for (String filename : filenames) {
            try {
                var file = cm.toFile(filename);
                if (!file.exists()) {
                    continue;
                }
                var contentOpt = file.read();
                if (contentOpt.isEmpty()) {
                    continue;
                }
                if (!result.isEmpty()) {
                    result.append("\n\n");
                }
                result.append("```")
                        .append(filename)
                        .append("\n")
                        .append(withLineNumbers(contentOpt.get()))
                        .append("\n```");
                successCount++;
            } catch (Exception e) {
                logger.warn("Failed to render line-numbered file content for {}", filename, e);
            }
        }

        if (successCount != filenames.size()) {
            return rawResultText;
        }

        return result.toString();
    }

    private static String formatGetCodeSourcesForMcp(
            Map<?, ?> args, String rawResultText, ContextManager cm, String argName, boolean classMode) {
        Object namesObj = args.get(argName);
        if (!(namesObj instanceof List<?> namesList) || namesList.isEmpty()) {
            return rawResultText;
        }

        List<String> names = namesList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .distinct()
                .filter(s -> !s.isBlank())
                .toList();
        if (names.isEmpty()) {
            return rawResultText;
        }

        var analyzer = cm.getAnalyzerUninterrupted();
        List<String> blocks = new ArrayList<>();
        Set<String> added = new HashSet<>();

        int processedCount = 0;
        int maxCount = 10;

        for (String name : names) {
            if (classMode && processedCount >= maxCount) {
                break;
            }

            var definitionOpt = analyzer.getDefinitions(name).stream()
                    .filter(cu -> classMode ? cu.isClass() : cu.isFunction())
                    .findFirst();
            if (definitionOpt.isEmpty()) {
                continue;
            }

            var cu = definitionOpt.get();
            if (!added.add(cu.fqName())) {
                continue;
            }
            if (classMode) {
                processedCount++;
            }

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
                blocks.add("```%s\n%s```".formatted(file, lines.text()));
            }
        }

        if (blocks.isEmpty()) {
            return rawResultText;
        }

        return String.join("\n\n", blocks);
    }

    private static String formatScanUsagesForMcp(Map<?, ?> args, String rawResultText, ContextManager cm) {
        Object symbolsObj = args.get("symbols");
        if (!(symbolsObj instanceof List<?> symbolsList) || symbolsList.isEmpty()) {
            return rawResultText;
        }

        boolean includeTests = args.get("includeTests") instanceof Boolean b && b;

        List<String> symbols = symbolsList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .distinct()
                .filter(s -> !s.isBlank())
                .toList();
        if (symbols.isEmpty()) {
            return rawResultText;
        }

        var analyzer = cm.getAnalyzerUninterrupted();
        Predicate<ProjectFile> fileFilter = includeTests ? null : file -> !ContextManager.isTestFile(file, analyzer);

        List<String> outputs = new ArrayList<>();
        for (String symbol : symbols) {
            final FuzzyResult usageResult;
            try {
                usageResult = UsageFinder.create(cm, fileFilter).findUsages(symbol);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return rawResultText;
            }
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
            StringBuilder out = new StringBuilder();
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
            return rawResultText;
        }
        return String.join("\n\n", outputs);
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

    static String withLineNumbers(String content) {
        var lines = splitLogicalLines(content);
        if (lines.isEmpty()) {
            return "";
        }

        int width = Integer.toString(lines.size()).length();
        return IntStream.range(0, lines.size())
                .mapToObj(i -> String.format("%" + width + "d: %s", i + 1, lines.get(i)))
                .collect(Collectors.joining("\n"));
    }

    private static List<String> splitLogicalLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();

        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '\n' || ch == '\r') {
                lines.add(content.substring(start, i));
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    // ---- MCP schema conversion helpers ----

    public static McpSchema.JsonSchema toMcpSchema(JsonObjectSchema lc4j) {
        Map<String, Object> properties = new HashMap<>();
        lc4j.properties().forEach((name, element) -> properties.put(name, convertElement(element)));

        return new McpSchema.JsonSchema(
                "object", properties, lc4j.required() != null ? lc4j.required() : List.of(), false, null, null);
    }

    private static Map<String, Object> convertElement(JsonSchemaElement element) {
        Map<String, Object> map = new HashMap<>();
        switch (element) {
            case JsonStringSchema jsonStringSchema -> map.put("type", "string");
            case JsonIntegerSchema jsonIntegerSchema -> map.put("type", "integer");
            case JsonNumberSchema jsonNumberSchema -> map.put("type", "number");
            case JsonBooleanSchema jsonBooleanSchema -> map.put("type", "boolean");
            case JsonArraySchema arraySchema -> {
                map.put("type", "array");
                map.put("items", convertElement(arraySchema.items()));
            }
            case JsonObjectSchema objectSchema -> {
                map.put("type", "object");
                Map<String, Object> nestedProps = new HashMap<>();
                objectSchema.properties().forEach((k, v) -> nestedProps.put(k, convertElement(v)));
                map.put("properties", nestedProps);
                if (objectSchema.required() != null) {
                    map.put("required", objectSchema.required());
                }
            }
            default -> map.put("type", "string");
        }

        String desc = element.description();
        if (desc != null && !desc.isBlank()) {
            map.put("description", desc);
        }
        return map;
    }

    private static String serializeRequest(McpSchema.CallToolRequest request) {
        try {
            return OBJECT_MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // ---- Progress notification console ----

    private static final class ProgressNotifyingConsole extends MemoryConsole {
        private final McpSyncServerExchange exchange;
        private final Object progressToken;
        private final @Nullable McpToolCallHistoryWriter historyWriter;
        private final @Nullable Path logFile;
        private final AtomicReference<Double> currentProgress = new AtomicReference<>(0.0);

        ProgressNotifyingConsole(
                McpSyncServerExchange exchange,
                Object progressToken,
                @Nullable McpToolCallHistoryWriter historyWriter,
                @Nullable Path logFile) {
            this.exchange = exchange;
            this.progressToken = progressToken;
            this.historyWriter = historyWriter;
            this.logFile = logFile;
        }

        private void sendProgress(String message) {
            double next = currentProgress.updateAndGet(p -> p + (1.0 - p) * 0.5);
            exchange.progressNotification(new McpSchema.ProgressNotification(progressToken, next, 1.0, message));
            if (historyWriter != null && logFile != null) {
                historyWriter.appendProgress(logFile, next, message);
            }
        }

        @Override
        public void toolError(String msg, String title) {
            super.toolError(msg, title);
            sendProgress(title + ": " + msg);
        }

        @Override
        public void backgroundOutput(String taskDescription) {
            sendProgress(taskDescription);
        }

        @Override
        public void backgroundOutput(String summary, String details) {
            sendProgress(summary + ": " + details);
        }

        @Override
        public void systemNotify(String message, String title, int messageType) {
            sendProgress(title + ": " + message);
        }

        @Override
        public void showNotification(NotificationRole role, String message) {
            super.showNotification(role, message);
            sendProgress(message);
        }

        @Override
        public void showTransientMessage(String message) {
            sendProgress(message);
        }

        @Override
        public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
            super.llmOutput(token, type, meta);
        }
    }
}
