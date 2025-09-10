package io.github.jbellis.brokk.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class McpUtils {

    private static final Logger logger = LogManager.getLogger(McpUtils.class);

    private static McpClientTransport buildTransport(URL url, @Nullable String bearerToken) {
        var transportBuilder = HttpClientStreamableHttpTransport.builder(url.toString())
                .resumableStreams(true)
                .openConnectionOnStartup(true);
        if (bearerToken != null) {
            final String token;
            if (!bearerToken.startsWith("Bearer ")) token = "Bearer " + bearerToken;
            else token = bearerToken;
            transportBuilder.customizeRequest(request -> request.header("Authorization", token));
        }
        return transportBuilder.build();
    }

    private static McpSyncClient buildSyncClient(URL url, @Nullable String bearerToken) {
        final var transport = buildTransport(url, bearerToken);
        return McpClient.sync(transport)
                .loggingConsumer(logger::debug)
                .capabilities(McpSchema.ClientCapabilities.builder()
                        .elicitation()
                        .sampling()
                        .build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static <T> T withMcpSyncClient(URL url, @Nullable String bearerToken, Function<McpSyncClient, T> function) {
        final var client = buildSyncClient(url, bearerToken);
        try {
            client.initialize();
            return function.apply(client);
        } finally {
            client.closeGracefully();
        }
    }

    public static List<McpSchema.Tool> fetchTools(URL url, @Nullable String bearerToken) throws IOException {
        try {
            return withMcpSyncClient(url, bearerToken, client -> {
                McpSchema.ListToolsResult toolsResult = client.listTools();
                return toolsResult.tools();
            });
        } catch (Exception e) {
            logger.error("Failed to fetch tools from MCP server at {}: {}", url, e.getMessage());
            throw new IOException(
                    "Failed to fetch tools. Ensure the server is a stateless, streamable HTTP MCP server.", e);
        }
    }

    public static McpSchema.CallToolResult callTool(
            McpServer server, String toolName, Map<String, Object> arguments, @Nullable String bearerToken)
            throws IOException {
        final URL url = server.url();
        try {
            return withMcpSyncClient(
                    url, bearerToken, client -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments)));
        } catch (Exception e) {
            logger.error("Failed to call tool '{}' from MCP server at {}: {}", toolName, url, e.getMessage());
            throw new IOException(
                    "Failed to fetch tools. Ensure the server is a stateless, streamable HTTP MCP server.", e);
        }
    }
}
