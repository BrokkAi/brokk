package ai.brokk.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class McpServerCommandTest {

    @Test
    void toolDiscoveryList_containsExpectedTools() {
        Set<String> names = BrokkCli.McpServerCommand.toolDiscoveryList().stream()
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
                BrokkCli.McpServerCommand.stubHandler(null, new McpSchema.CallToolRequest("scan", java.util.Map.of()));

        assertEquals(false, result.isError());
        assertEquals(1, result.content().size());
        assertTrue(result.content().getFirst() instanceof McpSchema.TextContent text
                && "not implemented".equals(text.text()));
    }
}
