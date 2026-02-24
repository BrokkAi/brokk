package ai.brokk.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.mcp.BrokkExternalMcpServer;
import ai.brokk.project.MainProject;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class McpServerCommandTest {

    @Test
    void toolDiscoveryList_containsExpectedAgenticTools() {
        Set<String> names = BrokkExternalMcpServer.toolDiscoveryList().stream()
                .map(McpSchema.Tool::name)
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of("scan", "code", "build", "merge")));
    }

    @Test
    void toolSpecifications_containsSearchTools() throws Exception {
        Path tempDir = Files.createTempDirectory("mcp-test-specs");
        try (var project = new MainProject(tempDir)) {
            ContextManager cm = new ContextManager(project);
            List<McpServerFeatures.SyncToolSpecification> specs = BrokkExternalMcpServer.toolSpecifications(cm);
            Set<String> names = specs.stream().map(s -> s.tool().name()).collect(Collectors.toSet());

            assertTrue(names.containsAll(Set.of(
                    "scan",
                    "code",
                    "build",
                    "merge",
                    "searchSymbols",
                    "scanUsages",
                    "skimDirectory",
                    "getFileSummaries")));
        }
    }

    @Test
    void handleToolCall_unimplementedTool_returnsError() {
        McpSchema.CallToolResult result =
                BrokkExternalMcpServer.handleToolCall(null, new McpSchema.CallToolRequest("unknown_tool", Map.of()));

        assertTrue(result.isError());
        assertTrue(result.content().getFirst() instanceof McpSchema.TextContent text
                && text.text().contains("not yet implemented"));
    }

    @Test
    void handleToolCall_agenticTools_delegation() throws Exception {
        Path tempDir = Files.createTempDirectory("mcp-test-agentic");
        try (var project = new MainProject(tempDir)) {
            ContextManager cm = new ContextManager(project);
            cm.createHeadless(ai.brokk.agents.BuildAgent.BuildDetails.EMPTY, false, new CliConsole());

            // Test build (empty build command should fail or return error message)
            var buildResult =
                    BrokkExternalMcpServer.handleToolCall(cm, new McpSchema.CallToolRequest("build", Map.of()));
            // It might be an error if no build command is configured
            assertTrue(buildResult.content().getFirst() instanceof McpSchema.TextContent);
        }
    }
}
