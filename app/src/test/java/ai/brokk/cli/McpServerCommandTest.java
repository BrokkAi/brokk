package ai.brokk.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.mcp.BrokkMcpStdioServer;
import io.modelcontextprotocol.spec.McpSchema;
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
}
