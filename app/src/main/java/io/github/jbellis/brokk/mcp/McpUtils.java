package io.github.jbellis.brokk.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class McpUtils {

    private static final Logger logger = LogManager.getLogger(McpUtils.class);

    public static List<String> fetchTools(URL url, @Nullable String bearerToken) {
        var transportBuilder = HttpClientSseClientTransport.builder(url.toString());
        if (bearerToken != null) {
            transportBuilder.customizeRequest(request -> request.header("Authorization", "Bearer " + bearerToken));
        }

        final McpSyncClient client = McpClient.sync(transportBuilder.build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        try {
            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            return toolsResult.tools().stream().map(McpSchema.Tool::name).collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Failed to fetch tools from MCP server at {}: {}", url, e.getMessage());
            return Collections.emptyList();
        } finally {
            client.closeGracefully();
        }
    }
}
