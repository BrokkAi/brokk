package ai.brokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class McpUtilsAsyncTest {

    @Test
    void callToolAsync_http_failure_wraps_ioe_with_expected_message() throws Exception {
        HttpMcpServer server =
                new HttpMcpServer("HTTP Fail", URI.create("http://127.0.0.1:1").toURL(), null, null);

        IOException ex = assertThrows(
                IOException.class,
                () -> McpUtils.callToolAsync(server, "any-tool", Map.of(), null));

        assertEquals(
                "Failed to fetch tools. Ensure the server is a stateless, streamable HTTP MCP server.",
                ex.getMessage());
    }

    @Test
    void callToolAsync_stdio_failure_wraps_ioe_with_expected_message() throws Exception {
        StdioMcpServer server = new StdioMcpServer(
                "STDIO Fail",
                "definitely-not-a-real-mcp-binary-xyz-123",
                List.of(),
                Map.of(),
                null);

        IOException ex = assertThrows(
                IOException.class,
                () -> McpUtils.callToolAsync(server, "any-tool", Map.of(), null));

        assertEquals("Failed to fetch tools.", ex.getMessage());
    }

    @Test
    void callToolAsync_unsupported_server_type_throws_meaningful_error() {
        McpServer unsupported = new McpServer() {
            @Override
            public String name() {
                return "Unsupported";
            }

            @Override
            public java.util.List<String> tools() {
                return null;
            }
        };

        IOException ex = assertThrows(
                IOException.class,
                () -> McpUtils.callToolAsync(unsupported, "any-tool", Map.of(), null));

        assertTrue(ex.getMessage().startsWith("Unsupported MCP server type: "));
    }
}
