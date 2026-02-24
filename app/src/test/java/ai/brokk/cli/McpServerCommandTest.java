package ai.brokk.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.mcp.BrokkMcpStdioServer;
import ai.brokk.project.MainProject;
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
    void toolDiscoveryList_containsExpectedTools() {
        Set<String> names = BrokkMcpStdioServer.toolDiscoveryList().stream()
                .map(McpSchema.Tool::name)
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of(
                "scan",
                "find_symbols",
                "find_usages",
                "list_identifiers",
                "fetch_summary",
                "fetch_source",
                "code",
                "build",
                "merge")));
    }

    @Test
    void stubHandler_returnsNotImplemented() {
        McpSchema.CallToolResult result =
                BrokkMcpStdioServer.stubHandler(null, new McpSchema.CallToolRequest("unknown", Map.of()));

        assertEquals(false, result.isError());
        assertEquals(1, result.content().size());
        assertTrue(result.content().getFirst() instanceof McpSchema.TextContent text
                && "not implemented".equals(text.text()));
    }

    @Test
    void handleToolCall_unimplementedTool_returnsError() {
        McpSchema.CallToolResult result =
                BrokkMcpStdioServer.handleToolCall(null, new McpSchema.CallToolRequest("unknown_tool", Map.of()));

        assertTrue(result.isError());
        assertTrue(result.content().getFirst() instanceof McpSchema.TextContent text
                && text.text().contains("not yet implemented"));
    }

    @Test
    void handleToolCall_searchTools_delegation() throws Exception {
        Path tempDir = Files.createTempDirectory("mcp-test");
        try (var project = new MainProject(tempDir)) {
            ContextManager cm = new ContextManager(project);
            cm.createHeadless(ai.brokk.agents.BuildAgent.BuildDetails.EMPTY, false, new CliConsole());

            // Test find_symbols
            var symbolsResult = BrokkMcpStdioServer.handleToolCall(
                    cm,
                    new McpSchema.CallToolRequest(
                            "find_symbols", Map.of("patterns", List.of(".*"), "goal", "test symbols")));
            assertFalse(symbolsResult.isError());
            assertTrue(symbolsResult.content().getFirst() instanceof McpSchema.TextContent);

            // Test find_usages
            var usagesResult = BrokkMcpStdioServer.handleToolCall(
                    cm,
                    new McpSchema.CallToolRequest(
                            "find_usages", Map.of("targets", List.of("java.lang.Object"), "goal", "test usages")));
            assertFalse(usagesResult.isError());
            assertTrue(usagesResult.content().getFirst() instanceof McpSchema.TextContent);

            // Test list_identifiers
            var identifiersResult = BrokkMcpStdioServer.handleToolCall(
                    cm, new McpSchema.CallToolRequest("list_identifiers", Map.of("dir", ".", "goal", "test list")));
            assertFalse(identifiersResult.isError());
            assertTrue(identifiersResult.content().getFirst() instanceof McpSchema.TextContent);
        }
    }
}
