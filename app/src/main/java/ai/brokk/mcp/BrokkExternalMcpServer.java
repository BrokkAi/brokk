package ai.brokk.mcp;

import ai.brokk.ContextManager;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.ArchitectAgent;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.ContextAgent;
import ai.brokk.context.ContextFragments.SummaryFragment;
import ai.brokk.mcpserver.LangChain4jMcpBridge;
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
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class BrokkExternalMcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkExternalMcpServer.class);

    private final ContextManager cm;

    public BrokkExternalMcpServer(ContextManager cm) {
        this.cm = cm;
    }

    public static int run(ContextManager cm) {
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        BrokkExternalMcpServer instance = new BrokkExternalMcpServer(cm);
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(mapper))
                .serverInfo("Brokk MCP Server", ai.brokk.BuildInfo.version)
                .jsonMapper(mapper)
                .tools(instance.toolSpecifications())
                .build();

        logger.info("Brokk MCP Stdio Server started.");
        // The SDK's Stdio transport starts processing when the server is built or upon explicit start if async.
        // For sync server with stdio, we just need to keep the process alive.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    public List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        SearchTools searchTools = new SearchTools(cm);
        ToolRegistry registry = ToolRegistry.fromBase(ToolRegistry.empty())
                .register(this)
                .register(searchTools)
                .build();

        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        specs.addAll(LangChain4jMcpBridge.toolSpecificationsFrom(this, registry));
        specs.addAll(LangChain4jMcpBridge.toolSpecificationsFrom(searchTools, registry));
        return specs;
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
            if (files != null && !files.isEmpty()) {
                new ai.brokk.tools.WorkspaceTools(cm.liveContext()).addFilesToWorkspace(files);
            }

            TaskResult result;
            try (var scope = cm.beginTaskUngrouped(goal)) {
                if (cm.liveContext().getEditableFragments().findAny().isPresent()) {
                    StreamingChatModel planModel = cm.getService()
                            .getModel(cm.getProject()
                                    .getModelConfig(ai.brokk.project.ModelProperties.ModelType.ARCHITECT));
                    StreamingChatModel codeModel = cm.getService()
                            .getModel(cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.CODE));
                    var agent = new ArchitectAgent(
                            cm, planModel, codeModel, goal, scope, cm.liveContext(), new MutedConsoleIO(cm.getIo()));
                    result = agent.executeWithScan(false);
                } else {
                    StreamingChatModel planModel = cm.getService()
                            .getModel(cm.getProject()
                                    .getModelConfig(ai.brokk.project.ModelProperties.ModelType.ARCHITECT));
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

    @Tool("Solve all merge/rebase/cherry-pick conflicts in the repository.")
    public String merge() {
        try {
            var conflictOpt = ai.brokk.agents.ConflictInspector.inspectFromProject(cm.getProject());
            if (conflictOpt.isEmpty()) {
                return "Error: Repository is not in a merge conflict state.";
            }

            StreamingChatModel planModel = cm.getService()
                    .getModel(cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.ARCHITECT));
            StreamingChatModel codeModel = cm.getService()
                    .getModel(cm.getProject().getModelConfig(ai.brokk.project.ModelProperties.ModelType.CODE));

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
