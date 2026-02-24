package ai.brokk.mcp;

import ai.brokk.ContextManager;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.ArchitectAgent;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.ContextAgent;
import ai.brokk.cli.CliConsole;
import ai.brokk.context.ContextFragments.SummaryFragment;
import ai.brokk.mcpserver.LangChain4jMcpBridge;
import ai.brokk.project.MainProject;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolRegistry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class BrokkExternalMcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkExternalMcpServer.class);

    private static final List<String> BASE_TOOL_NAMES = List.of(
            "scan",
            "code",
            "build",
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

    public static void main(String[] args) {
        Path projectPath = Path.of(".").toAbsolutePath().normalize();
        try (var project = new MainProject(projectPath);
                var cm = new ContextManager(project)) {

            // Initialize ContextManager the way BrokkCli does (headless)
            var bd = project.loadBuildDetails().orElse(BuildAgent.BuildDetails.EMPTY);
            cm.createHeadless(bd, false, new CliConsole());
            cm.dropWithHistorySemantics(List.of());

            McpJsonMapper mapper = McpJsonDefaults.getMapper();
            BrokkExternalMcpServer instance = new BrokkExternalMcpServer(cm);

            // Using Stdio transport instead of HTTP as HttpServerTransportProvider was not found
            McpServer.sync(new StdioServerTransportProvider(mapper))
                    .serverInfo("Brokk MCP Server", ai.brokk.BuildInfo.version)
                    .jsonMapper(mapper)
                    .tools(instance.toolSpecifications())
                    .build();

            logger.info("Brokk MCP Stdio Server started.");

            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

        return LangChain4jMcpBridge.toolSpecificationsFrom(registry, toolNames);
    }

    private boolean isJqOnPath() {
        try {
            ai.brokk.util.Environment.instance.runShellCommand(
                    "jq --version", cm.getProject().getRoot(), out -> {}, java.time.Duration.ofSeconds(2));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Tool("Agentic scan for relevant files and classes. Returns a summary of recommended context.")
    public String scan(
            @P("The goal or prompt to scan for.") String goal,
            @P("Include test files in the results. Default: false.") boolean includeTests) {
        try {
            var scanModel = cm.getService().getScanModel();
            var agent = new ContextAgent(cm, scanModel, goal, new MutedConsoleIO(cm.getIo()));
            var recommendations = agent.getRecommendations(cm.liveContext());

            if (!recommendations.success()) {
                return "Scan failed to find recommendations.";
            }

            StringBuilder sb = new StringBuilder();
            recommendations.fragments().stream()
                    .filter(f -> includeTests
                            || f.sourceFiles().join().stream()
                                    .noneMatch(pf -> ContextManager.isTestFile(pf, cm.getAnalyzerUninterrupted())))
                    .flatMap(f -> toSummaryFragments(f).stream())
                    .forEach(f -> {
                        sb.append("## ").append(f.description().join()).append(":\n");
                        sb.append(f.text().join()).append("\n\n");
                    });

            cm.pushContext(ctx -> ctx.addFragments(recommendations.fragments()));
            return sb.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Scan interrupted: " + e.getMessage();
        } catch (Exception e) {
            return "Scan failed: " + e.getMessage();
        }
    }

    @Tool("Implement changes asked for in the goal. Will search for relevant files if none are provided.")
    public String code(
            @P("The goal/prompt for the changes.") String goal,
            @P("Optional list of files to narrow the radius or edit directly.") List<String> files) {
        try {
            if (!files.isEmpty()) {
                new ai.brokk.tools.WorkspaceTools(cm.liveContext()).addFilesToWorkspace(files);
            }

            TaskResult result;
            try (var scope = cm.beginTaskUngrouped(goal)) {
                if (cm.liveContext().getEditableFragments().findAny().isPresent()) {
                    StreamingChatModel planModel = java.util.Objects.requireNonNull(cm.getService()
                            .getModel(cm.getProject()
                                    .getModelConfig(ai.brokk.project.ModelProperties.ModelType.ARCHITECT)));
                    StreamingChatModel codeModel = java.util.Objects.requireNonNull(cm.getService()
                            .getModel(cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.CODE)));
                    var agent = new ArchitectAgent(
                            cm, planModel, codeModel, goal, scope, cm.liveContext(), new MutedConsoleIO(cm.getIo()));
                    result = agent.executeWithScan(false);
                } else {
                    StreamingChatModel planModel = java.util.Objects.requireNonNull(cm.getService()
                            .getModel(cm.getProject()
                                    .getModelConfig(ai.brokk.project.ModelProperties.ModelType.ARCHITECT)));
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

            return result.stopDetails().explanation();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Code execution interrupted: " + e.getMessage();
        } catch (Exception e) {
            return "Code execution failed: " + e.getMessage();
        }
    }

    @Tool("Run build verification (compile and test) without making changes.")
    public String build() {
        try {
            String error = BuildAgent.runVerification(cm);
            return error.isEmpty() ? "Build successful" : "Build failed:\n" + error;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Build interrupted: " + e.getMessage();
        } catch (Exception e) {
            return "Build failed: " + e.getMessage();
        }
    }

    @Tool("Configure build and test commands for the project. These settings are persisted.")
    public String configureBuild(
            @P("Command to build or lint incrementally, e.g. 'mvn compile', 'cargo check'.") @Nullable
                    String buildOnlyCmd,
            @P("Command to run all tests. Use for global verification.") @Nullable String testAllCmd,
            @P("Mustache template for running specific tests. Supports {{#files}}, {{#classes}}, {{#fqclasses}}.")
                    @Nullable
                    String testSomeCmd) {
        try {
            var project = cm.getProject();
            var existingDetails = project.loadBuildDetails().orElse(BuildAgent.BuildDetails.EMPTY);

            String buildCmd = buildOnlyCmd != null ? buildOnlyCmd : existingDetails.buildLintCommand();
            String testAll = testAllCmd != null ? testAllCmd : existingDetails.testAllCommand();
            String testSome = testSomeCmd != null ? testSomeCmd : existingDetails.testSomeCommand();

            if (testAllCmd != null) {
                project.setCodeAgentTestScope(ai.brokk.project.IProject.CodeAgentTestScope.ALL);
            } else if (testSomeCmd != null) {
                project.setCodeAgentTestScope(ai.brokk.project.IProject.CodeAgentTestScope.WORKSPACE);
            }

            var bd = new BuildAgent.BuildDetails(
                    buildCmd,
                    testAll,
                    testSome,
                    existingDetails.exclusionPatterns(),
                    existingDetails.environmentVariables(),
                    existingDetails.maxBuildAttempts(),
                    existingDetails.afterTaskListCommand());

            project.setBuildDetails(bd);
            project.saveBuildDetails(bd);

            return "Build configuration updated.";
        } catch (Exception e) {
            return "Failed to configure build: " + e.getMessage();
        }
    }

    @Tool("Solve all merge/rebase/cherry-pick conflicts in the repository.")
    public String merge() {
        try {
            var conflictOpt = ai.brokk.agents.ConflictInspector.inspectFromProject(cm.getProject());
            if (conflictOpt.isEmpty()) {
                return "Error: Repository is not in a merge conflict state.";
            }

            StreamingChatModel planModel = java.util.Objects.requireNonNull(cm.getService()
                    .getModel(cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.ARCHITECT)));
            StreamingChatModel codeModel = java.util.Objects.requireNonNull(cm.getService()
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Merge interrupted: " + e.getMessage();
        } catch (Exception e) {
            return "Merge failed: " + e.getMessage();
        }
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
