package ai.brokk.mcpserver;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.ContextAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextDelta;
import ai.brokk.context.ContextFragments.SummaryFragment;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.BuildTools;
import ai.brokk.util.Environment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.StreamingChatModel;
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
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class BrokkExternalMcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkExternalMcpServer.class);

    private static final List<String> BASE_TOOL_NAMES = List.of(
            "scan",
            "callCodeAgent",
            "runBuild",
            "configureBuild",
            "merge",
            "searchSymbols",
            "scanUsages",
            "skimDirectory",
            "getFileSummaries",
            "getClassSkeletons",
            "getClassSources",
            "getMethodSources",
            "getSymbolLocations");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ContextManager cm;
    private final @Nullable McpToolCallHistoryWriter mcpToolCallHistoryWriter;

    public BrokkExternalMcpServer(ContextManager cm) {
        this.cm = cm;
        this.mcpToolCallHistoryWriter =
                createMcpToolCallHistoryWriter(cm.getProject().getRoot());
    }

    private static @Nullable McpToolCallHistoryWriter createMcpToolCallHistoryWriter(Path projectRoot) {
        try {
            return new McpToolCallHistoryWriter(projectRoot.resolve(".brokk").resolve("mcp-history"));
        } catch (IOException e) {
            logger.warn("Failed to initialize MCP tool call history logging", e);
            return null;
        }
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        Path projectPath = Path.of(".").toAbsolutePath().normalize();

        try (var project = new MainProject(projectPath);
                var cm = new ContextManager(project)) {

            cm.createHeadless(true, new MutedConsoleIO(cm.getIo()));

            McpJsonMapper mapper = McpJsonDefaults.getMapper();
            BrokkExternalMcpServer instance = new BrokkExternalMcpServer(cm);
            AtomicReference<McpSyncServer> serverRef = new AtomicReference<>();

            McpSyncServer mcpServer = McpServer.sync(new StdioServerTransportProvider(mapper, System.in, System.out))
                    .serverInfo("Brokk MCP Server", ai.brokk.BuildInfo.version)
                    .jsonMapper(mapper)
                    .requestTimeout(Duration.ofHours(1))
                    .tools(instance.toolSpecifications())
                    .build();
            serverRef.set(mcpServer);
            logger.info("Brokk MCP Server started");
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                var server = requireNonNull(serverRef.get());
                                server.closeGracefully();
                            },
                            "BrokkMCP-Server-ShutdownHook"));

            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(0);
        } catch (Exception e) {
            logger.error("Failed to start Brokk MCP Server", e);
            System.exit(1);
        }
    }

    public static List<McpServerFeatures.SyncToolSpecification> toolSpecificationsFrom(
            ContextManager cm, ToolRegistry registry, Collection<String> toolNames) {
        return toolSpecificationsFrom(cm, registry, toolNames, null);
    }

    public static List<McpServerFeatures.SyncToolSpecification> toolSpecificationsFrom(
            ContextManager cm,
            ToolRegistry registry,
            Collection<String> toolNames,
            @Nullable McpToolCallHistoryWriter historyWriter) {
        return registry.getTools(toolNames).stream()
                .map(spec -> {
                    McpSchema.JsonSchema inputSchema = spec.parameters() == null
                            ? new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)
                            : toMcpSchema(spec.parameters());

                    McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                            .name(spec.name())
                            .description(spec.description())
                            .inputSchema(inputSchema)
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

                                // Write full raw MCP request to the log
                                var logFile = historyWriter != null
                                        ? historyWriter.writeRequest(spec.name(), serializeRequest(request))
                                        : null;

                                logger.debug("Clearing Brokk session before tool execution");
                                cm.dropWithHistorySemantics(List.of());

                                Object progressToken = request.progressToken();
                                if (progressToken != null) {
                                    exchange.progressNotification(new McpSchema.ProgressNotification(
                                            progressToken, 0.0, 1.0, "Starting " + spec.name()));
                                    if (historyWriter != null) {
                                        historyWriter.appendProgress(
                                                requireNonNull(logFile), 0.0, "Starting " + spec.name());
                                    }
                                }

                                ai.brokk.IConsoleIO originalIo = cm.getIo();
                                ai.brokk.IConsoleIO progressIo = progressToken != null
                                        ? new ProgressNotifyingConsole(exchange, progressToken, historyWriter, logFile)
                                        : new ai.brokk.MutedConsoleIO(originalIo);
                                cm.setIo(progressIo);

                                try {
                                    CompletableFuture<McpSchema.CallToolResult> future =
                                            CompletableFuture.supplyAsync(() -> {
                                                try {
                                                    ToolExecutionRequest lc4jRequest = ToolExecutionRequest.builder()
                                                            .id("1")
                                                            .name(spec.name())
                                                            .arguments(jsonArgs)
                                                            .build();

                                                    var result = registry.executeTool(lc4jRequest);
                                                    return McpSchema.CallToolResult.builder()
                                                            .addTextContent(result.resultText())
                                                            .isError(result.status()
                                                                    != ToolExecutionResult.Status.SUCCESS)
                                                            .build();
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new RuntimeException(e);
                                                }
                                            });

                                    McpSchema.CallToolResult callResult;
                                    try {
                                        callResult = future.get();
                                    } catch (java.util.concurrent.ExecutionException e) {
                                        Throwable cause = e.getCause();
                                        if (cause instanceof RuntimeException re) throw re;
                                        throw new RuntimeException(cause);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException(e);
                                    }

                                    // Append result to the log
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
                                } finally {
                                    cm.setIo(originalIo);
                                }
                            })
                            .build();
                })
                .collect(Collectors.toList());
    }

    private static String serializeRequest(McpSchema.CallToolRequest request) {
        try {
            return OBJECT_MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

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

    public List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        SearchTools searchTools = new SearchTools(cm);
        ToolRegistry registry = ToolRegistry.fromBase(ToolRegistry.empty())
                .register(this)
                .register(searchTools)
                .build();

        List<String> toolNames = new ArrayList<>(BASE_TOOL_NAMES);

        var allFiles = cm.getProject().getAllFiles();
        boolean hasXml = allFiles.stream().anyMatch(f -> f.toString().endsWith(".xml"));
        boolean hasJson = allFiles.stream().anyMatch(f -> f.toString().endsWith(".json"));

        if (hasXml) {
            toolNames.add("xpathQuery");
        }

        if (hasJson && !isJqOnPath()) {
            toolNames.add("jq");
        }

        return toolSpecificationsFrom(cm, registry, toolNames, mcpToolCallHistoryWriter);
    }

    private boolean isJqOnPath() {
        try {
            Environment.instance.runShellCommand(
                    "jq --version", cm.getProject().getRoot(), out -> {}, java.time.Duration.ofSeconds(2));
            return true;
        } catch (Environment.SubprocessException | InterruptedException e) {
            return false;
        }
    }

    @Tool(
            "Agentic scan for relevant files and classes. Returns a summary of recommended context. Use this to get an overview when beginning a new task.")
    public String scan(
            @P("The natural-language goal or prompt to scan for.") String goal,
            @P("Include test files in the results.") boolean includeTests)
            throws InterruptedException {
        var scanModel = cm.getService().getScanModel();
        var agent = new ContextAgent(cm, scanModel, goal, new MutedConsoleIO(cm.getIo()));
        var recommendations = agent.getRecommendations(cm.liveContext());

        if (!recommendations.success()) {
            return "Scan failed to find recommendations.";
        }

        String result = recommendations.fragments().stream()
                .flatMap(f -> toSummaryFragments(f).stream())
                .map(f -> "## " + f.description().join() + ":\n" + f.text().join() + "\n\n")
                .collect(java.util.stream.Collectors.joining());

        cm.pushContext(ctx -> ctx.addFragments(recommendations.fragments()));
        return result;
    }

    @Tool(
            """
            ALWAYS use the `callCodeAgent` tool to make changes to code; it is faster and more accurate than doing so by hand.
            Code Agent is as smart as you are, so you only have to
            describe what you want and it will perform the changes. However! Code Agent does not have access to your
            conversation history or your thinking process, and it starts fresh with each command, so your requests must
            be self-contained, complete, and unambiguous.
            When possible, prefer giving Code Agent complete tasks that allow it to verify the build and run tests, since it is
            easier to fix regressions when they are introduced rather than later. In general, the size of tasks should be
            bounded by your ability to accurately describe the changes you need at the current stage. If you give
            Code Agent a task that is too difficult, it will tell you what it changed and (if it can run build/tests)
            what problems it could not resolve.
            """)
    public String callCodeAgent(
            @P(
                            "Instructions for the changes. If there is context needed outside the files being edited, make sure to include it here.")
                    String instructions,
            @P("List of files to edit, including new files to create. Specify full project-relative paths.")
                    List<String> editFiles,
            @P("Set to true when the task is not expected to leave the project buildable/lint-able.")
                    boolean deferBuild,
            @P("List of filenames containing tests to run. Ignored when deferBuild=True.") List<String> testFiles) {
        if (editFiles.isEmpty()) {
            return "Code agent called with no files to edit";
        }
        var wst = new WorkspaceTools(cm.liveContext());
        wst.addFilesToWorkspace(editFiles);
        cm.pushContext(ctx -> wst.getContext());

        var initialContext = cm.liveContext();

        var model = requireNonNull(
                cm.getService().getModel(cm.getProject().getModelConfig(ModelProperties.ModelType.CODE)));
        var ca = new CodeAgent(cm, model);

        EnumSet<CodeAgent.Option> options =
                deferBuild ? EnumSet.of(CodeAgent.Option.DEFER_BUILD) : EnumSet.noneOf(CodeAgent.Option.class);

        List<ProjectFile> testFilesOverride = (testFiles == null || testFiles.isEmpty() || deferBuild)
                ? List.of()
                : testFiles.stream().map(cm::toFile).toList();

        TaskResult result = ca.execute(instructions, options, testFilesOverride);

        var finalContext = result.context();
        var stopDetails = result.stopDetails();
        var reason = stopDetails.reason();

        var delta = ContextDelta.between(initialContext, finalContext).join();
        var changedFragments = delta.getChangedFragments();
        var unifiedDiff = CodeAgent.cumulativeDiffForFragments(initialContext, finalContext, changedFragments);

        String explanation = stopDetails.explanation();
        if (reason == TaskResult.StopReason.BUILD_ERROR) {
            String buildError = finalContext.getBuildError();
            if (!buildError.isBlank() && !explanation.contains(buildError)) {
                explanation = (explanation.isBlank() ? "" : (explanation.stripTrailing() + "\n\n")) + buildError;
            }
        }

        String diffSection = unifiedDiff.isBlank()
                ? "# Diff\n(No file changes)"
                : """
                # Diff
                ```diff
                %s
                ```
                """
                        .formatted(unifiedDiff.strip())
                        .stripIndent()
                        .stripTrailing();

        if (reason == TaskResult.StopReason.SUCCESS) {
            String statusLine = deferBuild ? "Success (build deferred)." : "Success.";
            return """
                    # Status
                    %s

                    %s
                    """
                    .formatted(statusLine, diffSection)
                    .stripIndent()
                    .stripTrailing();
        }

        var diffPresentation =
                unifiedDiff.isBlank() ? CodeAgent.DiffPresentation.NONE : CodeAgent.DiffPresentation.INLINE;
        String failureText = CodeAgent.formatPostFailureResponse(reason, explanation, diffPresentation, unifiedDiff);
        return """
                %s

                %s
                """
                .formatted(failureText, diffSection)
                .stripIndent()
                .stripTrailing();
    }

    @Tool("Run build verification (compile and test) without making changes.")
    public String runBuild() throws InterruptedException {
        String error = BuildTools.runVerification(cm);
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
        var existingDetails = project.loadBuildDetails().orElse(BuildAgent.BuildDetails.EMPTY);

        // 1. Verify buildOnlyCmd
        ai.brokk.util.BuildVerifier.VerificationResult buildResult =
                ai.brokk.util.BuildVerifier.verify(project, buildOnlyCmd);
        if (!buildResult.success()) {
            return "Configuration failed: buildOnlyCmd ('" + buildOnlyCmd + "') failed with exit code "
                    + buildResult.exitCode() + ":\n" + buildResult.output();
        }

        // 2. Verify testSomeCmd
        // Find a test file to verify the template works
        var testFileOpt = project.getAllFiles().stream()
                .filter(f -> cm.getAnalyzerUninterrupted().containsTests(f))
                .findFirst();

        var bd = new BuildAgent.BuildDetails(
                buildOnlyCmd,
                existingDetails.testAllCommand(),
                testSomeCmd,
                existingDetails.exclusionPatterns(),
                existingDetails.environmentVariables(),
                existingDetails.maxBuildAttempts(),
                existingDetails.afterTaskListCommand());
        if (testFileOpt.isPresent()) {
            String interpolatedTestCmd = BuildTools.getBuildLintSomeCommand(cm, bd, List.of(testFileOpt.get()));

            ai.brokk.util.BuildVerifier.VerificationResult testResult =
                    ai.brokk.util.BuildVerifier.verify(project, interpolatedTestCmd);
            if (!testResult.success()) {
                return "Configuration failed: testSomeCmd verification failed using command '" + interpolatedTestCmd
                        + "'. Exit code " + testResult.exitCode() + ":\n" + testResult.output();
            }
        }

        // 3. Persist only if both succeeded
        project.setCodeAgentTestScope(ai.brokk.project.IProject.CodeAgentTestScope.WORKSPACE);
        project.setBuildDetails(bd);
        project.saveBuildDetails(bd);

        return "Build configuration verified (build and test) and updated.";
    }

    @Tool("Solve all merge/rebase/cherry-pick conflicts in the repository.")
    public String merge() throws InterruptedException, java.io.IOException, GitAPIException {
        var conflictOpt = ai.brokk.agents.ConflictInspector.inspectFromProject(cm.getProject());
        if (conflictOpt.isEmpty()) {
            return "Error: Repository is not in a merge conflict state.";
        }

        StreamingChatModel planModel = requireNonNull(
                cm.getService().getModel(cm.getProject().getModelConfig(ModelProperties.ModelType.ARCHITECT)));
        StreamingChatModel codeModel = requireNonNull(
                cm.getService().getModel(cm.getProject().getModelConfig(ModelProperties.ModelType.CODE)));

        TaskResult result;
        try (var scope = cm.beginTaskUngrouped("Merge")) {
            var mergeAgent = new ai.brokk.agents.MergeAgent(
                    cm,
                    planModel,
                    codeModel,
                    conflictOpt.get(),
                    scope,
                    ai.brokk.agents.MergeAgent.DEFAULT_MERGE_INSTRUCTIONS);
            result = mergeAgent.execute();
            scope.append(result);
        }
        return result.stopDetails().explanation();
    }

    private List<SummaryFragment> toSummaryFragments(ai.brokk.context.ContextFragment fragment) {
        var results = new ArrayList<SummaryFragment>();
        var files = fragment.sourceFiles().join();
        for (var file : files) {
            results.add(new SummaryFragment(
                    cm, file.toString(), ai.brokk.context.ContextFragment.SummaryType.FILE_SKELETONS));
        }
        var sources = fragment.sources().join();
        for (var codeUnit : sources) {
            if (codeUnit.isClass()) {
                results.add(new SummaryFragment(
                        cm, codeUnit.fqName(), ai.brokk.context.ContextFragment.SummaryType.CODEUNIT_SKELETON));
            }
        }
        return results;
    }

    private static final class ProgressNotifyingConsole extends ai.brokk.cli.MemoryConsole {
        private final io.modelcontextprotocol.server.McpSyncServerExchange exchange;
        private final Object progressToken;
        private final @Nullable McpToolCallHistoryWriter historyWriter;
        private final @Nullable Path logFile;
        private final AtomicReference<Double> currentProgress = new AtomicReference<>(0.0);

        ProgressNotifyingConsole(
                io.modelcontextprotocol.server.McpSyncServerExchange exchange,
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
        public void llmOutput(
                String token, dev.langchain4j.data.message.ChatMessageType type, ai.brokk.LlmOutputMeta meta) {
            super.llmOutput(token, type, meta);
            // No-op for tokens to avoid flooding progress notifications
        }
    }
}
