package ai.brokk.mcpserver;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.ArchitectAgent;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.ContextAgent;
import ai.brokk.context.ContextDelta;
import ai.brokk.context.ContextFragments.SummaryFragment;
import ai.brokk.project.MainProject;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.BuildTools;
import ai.brokk.util.Environment;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class BrokkExternalMcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkExternalMcpServer.class);

    private static final List<String> BASE_TOOL_NAMES = List.of(
            "scan",
            "code",
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

    private final ContextManager cm;

    public BrokkExternalMcpServer(ContextManager cm) {
        this.cm = cm;
    }

    private static class StdinCloseSignalInputStream extends FilterInputStream {
        private final Runnable onEof;

        protected StdinCloseSignalInputStream(InputStream in, Runnable onEof) {
            super(in);
            this.onEof = onEof;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value == -1) {
                onEof.run();
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int value = super.read(b, off, len);
            if (value == -1) {
                onEof.run();
            }
            return value;
        }
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        Path projectPath = Path.of(".").toAbsolutePath().normalize();

        try (var project = new MainProject(projectPath);
                var cm = new ContextManager(project)) {

            cm.createHeadless(true);

            McpJsonMapper mapper = McpJsonDefaults.getMapper();
            BrokkExternalMcpServer instance = new BrokkExternalMcpServer(cm);
            AtomicBoolean eofHandled = new AtomicBoolean();
            AtomicReference<McpSyncServer> serverRef = new AtomicReference<>();
            InputStream transportInput = new StdinCloseSignalInputStream(System.in, () -> {
                if (!eofHandled.compareAndSet(false, true)) {
                    return;
                }
                logger.info("System.in closed (EOF detected). Initiating MCP server shutdown.");
                var server = requireNonNull(serverRef.get());
                try {
                    server.closeGracefully();
                } catch (RuntimeException e) {
                    logger.warn("Error while closing MCP server after stdin EOF", e);
                }
                System.exit(0);
            });

            McpSyncServer mcpServer = McpServer.sync(
                            new StdioServerTransportProvider(mapper, transportInput, System.out))
                    .serverInfo("Brokk MCP Server", ai.brokk.BuildInfo.version)
                    .jsonMapper(mapper)
                    .requestTimeout(Duration.ofHours(10))
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

    public List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        SearchTools searchTools = new SearchTools(cm);
        ToolRegistry registry = ToolRegistry.fromBase(ToolRegistry.empty())
                .register(this)
                .register(searchTools)
                .register(new WorkspaceTools(cm.liveContext()))
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

        registry.setPreExecutionHook(() -> {
            logger.debug("Clearing Brokk session before tool execution");
            cm.dropWithHistorySemantics(List.of());
        });

        return LangChain4jMcpBridge.toolSpecificationsFrom(registry, toolNames);
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

    @Tool("Agentic scan for relevant files and classes. Returns a summary of recommended context.")
    public String scan(
            @P("The goal or prompt to scan for.") String goal,
            @P("Include test files in the results. Default: false.") boolean includeTests)
            throws InterruptedException {
        var scanModel = cm.getService().getScanModel();
        var agent = new ContextAgent(cm, scanModel, goal, new MutedConsoleIO(cm.getIo()));
        var recommendations = agent.getRecommendations(cm.liveContext());

        if (!recommendations.success()) {
            return "Scan failed to find recommendations.";
        }

        String result = recommendations.fragments().stream()
                .filter(f -> includeTests
                        || f.sourceFiles().join().stream()
                                .noneMatch(pf -> ContextManager.isTestFile(pf, cm.getAnalyzerUninterrupted())))
                .flatMap(f -> toSummaryFragments(f).stream())
                .map(f -> "## " + f.description().join() + ":\n" + f.text().join() + "\n\n")
                .collect(java.util.stream.Collectors.joining());

        cm.pushContext(ctx -> ctx.addFragments(recommendations.fragments()));
        return result;
    }

    @Tool("ALWAYS use the `code` tool to make changes to code; it is faster . Will search for relevant files if none are provided.")
    public String code(
            @P("The goal/prompt for the changes.") String goal,
            @P("Optional list of files to narrow the radius or edit directly.") List<String> files,
            @P("Defer build/verification. Set to true when your changes are an intermediate step.") boolean deferBuild)
            throws InterruptedException {
        if (!files.isEmpty()) {
            new WorkspaceTools(cm.liveContext()).addFilesToWorkspace(files);
        }

        var initialContext = cm.liveContext();

        TaskResult result;
        try (var scope = cm.beginTaskUngrouped(goal)) {
            if (cm.liveContext().getEditableFragments().findAny().isPresent()) {
                StreamingChatModel planModel = requireNonNull(cm.getService()
                        .getModel(
                                cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.ARCHITECT)));
                StreamingChatModel codeModel = requireNonNull(cm.getService()
                        .getModel(cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.CODE)));
                var agent = new ArchitectAgent(
                        cm, planModel, codeModel, goal, scope, cm.liveContext(), new MutedConsoleIO(cm.getIo()));
                agent.setDeferBuildForInitialCodeAgentCall(deferBuild);
                result = agent.executeWithScan(false);
            } else {
                StreamingChatModel planModel = requireNonNull(cm.getService()
                        .getModel(
                                cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.ARCHITECT)));
                var agent = new ai.brokk.agents.SearchAgent(
                        cm.liveContext(),
                        goal,
                        planModel,
                        SearchPrompts.Objective.CODE_ONLY,
                        scope,
                        new MutedConsoleIO(cm.getIo()),
                        ai.brokk.agents.SearchAgent.ScanConfig.defaults());
                result = agent.execute();
            }
            scope.append(result);
        }

        var finalContext = result.context();
        var stopDetails = result.stopDetails();
        var reason = stopDetails.reason();

        var delta = ContextDelta.between(initialContext, finalContext).join();
        var changedFragments = delta.getChangedFragments();
        var changedFragmentList = changedFragments.stream()
                .map(cf -> cf.shortDescription().join())
                .sorted()
                .collect(Collectors.joining(", "));
        var unifiedDiff = CodeAgent.cumulativeDiffForFragments(initialContext, finalContext, changedFragments);

        String explanation = stopDetails.explanation();
        if (reason == TaskResult.StopReason.BUILD_ERROR) {
            String buildError = finalContext.getBuildError();
            if (!buildError.isBlank() && !explanation.contains(buildError)) {
                explanation = (explanation.isBlank() ? "" : (explanation.stripTrailing() + "\n\n")) + buildError;
            }
        }

        String changedFragmentsText = changedFragmentList.isBlank() ? "(None)" : changedFragmentList;
        String diffSection = unifiedDiff.isBlank()
                ? "## Diff\n(No file changes)"
                : """
                ## Diff
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

                    # Changed fragments
                    %s

                    %s
                    """
                    .formatted(statusLine, changedFragmentsText, diffSection)
                    .stripIndent()
                    .stripTrailing();
        }

        var diffPresentation =
                unifiedDiff.isBlank() ? CodeAgent.DiffPresentation.NONE : CodeAgent.DiffPresentation.INLINE;
        String failureText = CodeAgent.formatPostFailureResponse(reason, explanation, diffPresentation, unifiedDiff);
        return """
                %s

                # Changed fragments
                %s

                %s
                """
                .formatted(failureText, changedFragmentsText, diffSection)
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

        StreamingChatModel planModel = requireNonNull(cm.getService()
                .getModel(cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.ARCHITECT)));
        StreamingChatModel codeModel = requireNonNull(cm.getService()
                .getModel(cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.CODE)));

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
}
