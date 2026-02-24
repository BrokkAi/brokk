package ai.brokk.mcp;

import ai.brokk.ContextManager;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.ContextAgent;
import ai.brokk.mcpserver.LangChain4jMcpBridge;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolRegistry;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class BrokkExternalMcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkExternalMcpServer.class);

    public static int run(ContextManager cm) {
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider())
                .serverInfo("Brokk MCP Server", ai.brokk.BuildInfo.version)
                .tools(toolSpecifications(cm))
                .build();

        logger.info("Brokk MCP Stdio Server started.");
        // McpSyncServer in this SDK version is typically started by the transport or remains active until close
        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    public static List<McpServerFeatures.SyncToolSpecification> toolSpecifications(ContextManager cm) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();

        // 1. Agentic tools (stubs/hand-rolled)
        for (McpSchema.Tool tool : toolDiscoveryList()) {
            specs.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((exchange, request) -> handleToolCall(cm, request))
                    .build());
        }

        // 2. Search tools (via LangChain4j bridge)
        SearchTools searchTools = new SearchTools(cm);
        ToolRegistry registry = ToolRegistry.fromBase(ToolRegistry.empty())
                .register(searchTools)
                .build();

        specs.addAll(LangChain4jMcpBridge.toolSpecificationsFrom(searchTools, registry));

        return specs;
    }

    public static List<McpSchema.Tool> toolDiscoveryList() {
        return List.of(
                stubTool(
                        "scan",
                        "Agentic scan for relevant files and classes.",
                        Map.of("goal", "The user's goal", "includeTests", "Include test files")),
                stubTool(
                        "code",
                        "Implement changes asked for in goal.",
                        Map.of("goal", "The user's goal", "files", "List of files to edit")),
                stubTool("build", "Run build verification.", Map.of()),
                stubTool("merge", "Solve all merge conflicts.", Map.of()));
    }

    private static McpSchema.Tool stubTool(String name, String description, Map<String, String> params) {
        Map<String, Object> properties = new java.util.HashMap<>();
        List<String> required = new ArrayList<>();
        params.forEach((k, v) -> {
            properties.put(k, Map.of("type", k.equals("includeTests") ? "boolean" : "string", "description", v));
            if (!k.equals("includeTests")) {
                required.add(k);
            }
        });

        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(new McpSchema.JsonSchema("object", properties, required, false, null, null))
                .build();
    }

    public static McpSchema.CallToolResult handleToolCall(ContextManager cm, McpSchema.CallToolRequest request) {
        String name = request.name();
        Map<String, Object> args = request.arguments();

        try {
            return switch (name) {
                case "scan" -> {
                    String goal = (String) args.getOrDefault("goal", "");
                    boolean includeTests = Boolean.TRUE.equals(args.get("includeTests"));
                    var scanModel = cm.getService().getScanModel();
                    var agent = new ContextAgent(cm, scanModel, goal);
                    var result = agent.getRecommendations(cm.liveContext());
                    yield McpSchema.CallToolResult.builder()
                            .addTextContent(result.success() ? "Scan complete." : "Scan failed.")
                            .isError(!result.success())
                            .build();
                }
                case "code" -> {
                    String goal = (String) args.getOrDefault("goal", "");
                    var agent = new CodeAgent(
                            cm, cm.getService().getModel(cm.getProject().getModelConfig(null)));
                    var result = agent.execute(goal, Set.of());
                    yield McpSchema.CallToolResult.builder()
                            .addTextContent(result.stopDetails().explanation())
                            .isError(result.stopDetails().reason() != ai.brokk.TaskResult.StopReason.SUCCESS)
                            .build();
                }
                case "build" -> {
                    String error = BuildAgent.runVerification(cm);
                    yield McpSchema.CallToolResult.builder()
                            .addTextContent(error.isEmpty() ? "Build successful" : error)
                            .isError(!error.isEmpty())
                            .build();
                }
                case "merge" ->
                    McpSchema.CallToolResult.builder()
                            .addTextContent("Merge not yet implemented via MCP")
                            .isError(true)
                            .build();
                default ->
                    McpSchema.CallToolResult.builder()
                            .addTextContent("Tool " + name + " not yet implemented in this handler")
                            .isError(true)
                            .build();
            };
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error executing " + name + ": " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
