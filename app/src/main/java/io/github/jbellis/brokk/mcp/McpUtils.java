package io.github.jbellis.brokk.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class McpUtils {

    private static final Logger logger = LogManager.getLogger(McpUtils.class);

    public static List<McpSchema.Tool> fetchTools(URL url, @Nullable String bearerToken) throws IOException {
        var transportBuilder = HttpClientStreamableHttpTransport.builder(url.toString())
                .resumableStreams(true)
                .openConnectionOnStartup(true);
        if (bearerToken != null) {
            final String token;
            if (!bearerToken.startsWith("Bearer ")) token = "Bearer " + bearerToken;
            else token = bearerToken;
            transportBuilder.customizeRequest(request -> request.header("Authorization", token));
        }

        final McpSyncClient client = McpClient.sync(transportBuilder.build())
                .loggingConsumer(logger::debug)
                .capabilities(McpSchema.ClientCapabilities.builder()
                        .elicitation()
                        .sampling()
                        .build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        try {
            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            return toolsResult.tools();
        } catch (Exception e) {
            logger.error("Failed to fetch tools from MCP server at {}: {}", url, e.getMessage());
            throw new IOException(
                    "Failed to fetch tools. Brokk supports Streamable HTTP servers that are stateless. Error: "
                            + e.getMessage(),
                    e);
        } finally {
            client.closeGracefully();
        }
    }
}
