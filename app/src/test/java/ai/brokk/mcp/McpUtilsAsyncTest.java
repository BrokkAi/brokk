package ai.brokk.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.util.Json;
import io.modelcontextprotocol.json.McpJsonDefaults;
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

        IOException ex =
                assertThrows(IOException.class, () -> McpUtils.callToolAsync(server, "any-tool", Map.of(), null));

        assertTrue(ex.getMessage().startsWith("Failed to call tool 'any-tool' from"));
        assertTrue(ex.getMessage().contains("Ensure the server is a stateless, streamable HTTP MCP server."));
    }

    @Test
    void callToolAsync_stdio_failure_wraps_ioe_with_expected_message() throws Exception {
        StdioMcpServer server =
                new StdioMcpServer("STDIO Fail", "definitely-not-a-real-mcp-binary-xyz-123", List.of(), Map.of(), null);

        IOException ex =
                assertThrows(IOException.class, () -> McpUtils.callToolAsync(server, "any-tool", Map.of(), null));

        assertTrue(ex.getMessage().startsWith("Failed to call tool 'any-tool':"));
    }

    @Test
    void callToolAsync_unsupported_server_type_throws_meaningful_error() {
        McpServer unsupported = new McpServer() {
            @Override
            public String name() {
                return "Unsupported";
            }

            @Override
            public List<String> tools() {
                return null;
            }
        };

        IOException ex =
                assertThrows(IOException.class, () -> McpUtils.callToolAsync(unsupported, "any-tool", Map.of(), null));

        assertTrue(ex.getMessage().startsWith("Unsupported MCP server type: "));
    }

    @Test
    void stdio_json_newline_escaping_regression() throws Exception {
        McpSchema.JSONRPCRequest message =
                new McpSchema.JSONRPCRequest("2.0", "initialize", "id-1", Map.of("protocolVersion", "2024-11-05"));

        // 1. Demonstrate that ai.brokk.util.Json.getMapper() serializes with actual \n due to INDENT_OUTPUT
        String brokkJson = Json.getMapper().writeValueAsString(message);
        assertTrue(brokkJson.contains("\n"), "Brokk JSON should be pretty-printed with newlines");

        // 2. Demonstrate that applying the escaping used in StdioClientTransport to indented JSON yields invalid JSON
        String escapedBrokkJson =
                brokkJson.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");

        assertThrows(
                Exception.class,
                () -> McpSchema.deserializeJsonRpcMessage(McpJsonDefaults.getMapper(), escapedBrokkJson),
                "Mangled JSON (escaped structural newlines) should fail to deserialize");

        // 3. Demonstrate that McpJsonDefaults.getMapper() produces JSON without newlines and parses successfully
        String sdkJson = McpJsonDefaults.getMapper().writeValueAsString(message);
        assertFalse(sdkJson.contains("\n"), "SDK default JSON should not contain newlines");

        McpSchema.JSONRPCMessage parsed = McpSchema.deserializeJsonRpcMessage(McpJsonDefaults.getMapper(), sdkJson);
        assertNotNull(parsed);
    }
}
