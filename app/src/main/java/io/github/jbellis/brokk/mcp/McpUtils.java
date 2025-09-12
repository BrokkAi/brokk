package io.github.jbellis.brokk.mcp;

import io.github.jbellis.brokk.util.Environment;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
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

    private static McpClientTransport buildTransport(String cmd, List<String> arguments, Map<String, String> env) {
        // Expand leading env-var references in provided values (e.g., $HOME, ${HOME})
        Map<String, String> resolvedEnv = Environment.expandEnvMap(env);
        final var params =
                ServerParameters.builder(cmd).args(arguments).env(resolvedEnv).build();

        return new StdioClientTransport(params);
    }

    private static McpSyncClient buildSyncClient(URL url, @Nullable String bearerToken) {
        final var transport = buildTransport(url, bearerToken);
        return McpClient.sync(transport)
                .loggingConsumer(logger::debug)
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static McpSyncClient buildSyncClient(String command, List<String> arguments, Map<String, String> env) {
        final var transport = buildTransport(command, arguments, env);
        return McpClient.sync(transport)
                .loggingConsumer(logger::debug)
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static <T> T withMcpSyncClient(
            URL url, @Nullable String bearerToken, @Nullable Path projectRoot, Function<McpSyncClient, T> function) {
        final var client = buildSyncClient(url, bearerToken);
        try {
            client.initialize();
            if (projectRoot != null) {
                client.addRoot(new McpSchema.Root(projectRoot.toUri().toString(), "Project root path."));
            }
            return function.apply(client);
        } finally {
            client.closeGracefully();
        }
    }

    private static <T> T withMcpSyncClient(
            String command,
            List<String> arguments,
            Map<String, String> env,
            @Nullable Path projectRoot,
            Function<McpSyncClient, T> function) {
        final var client = buildSyncClient(command, arguments, env);
        try {
            client.initialize();
            if (projectRoot != null) {
                client.addRoot(new McpSchema.Root(projectRoot.toUri().toString(), "Project root path."));
            }
            return function.apply(client);
        } finally {
            client.closeGracefully();
        }
    }

    public static List<McpSchema.Tool> fetchTools(McpServer server) throws IOException {
        if (server instanceof HttpMcpServer httpMcpServer) {
            return fetchTools(httpMcpServer.url(), httpMcpServer.bearerToken(), null);
        } else if (server instanceof StdioMcpServer stdioMcpServer) {
            return fetchTools(stdioMcpServer.command(), stdioMcpServer.args(), stdioMcpServer.env(), null);
        } else {
            return Collections.emptyList();
        }
    }

    public static List<McpSchema.Tool> fetchTools(URL url, @Nullable String bearerToken) throws IOException {
        return fetchTools(url, bearerToken, null);
    }

    public static List<McpSchema.Tool> fetchTools(URL url, @Nullable String bearerToken, @Nullable Path projectRoot)
            throws IOException {
        try {
            return withMcpSyncClient(url, bearerToken, projectRoot, client -> {
                McpSchema.ListToolsResult toolsResult = client.listTools();
                return toolsResult.tools();
            });
        } catch (Exception e) {
            logger.error("Failed to fetch tools from MCP server at {}: {}", url, e.getMessage());
            throw new IOException(
                    "Failed to fetch tools. Ensure the server is a stateless, streamable HTTP MCP server.", e);
        }
    }

    public static List<McpSchema.Tool> fetchTools(
            String command, List<String> arguments, Map<String, String> env, @Nullable Path projectRoot)
            throws IOException {
        try {
            return withMcpSyncClient(command, arguments, env, projectRoot, client -> {
                McpSchema.ListToolsResult toolsResult = client.listTools();
                return toolsResult.tools();
            });
        } catch (Exception e) {
            logger.error(
                    "Failed to fetch tools from MCP server on command '{} {}': {}",
                    command,
                    String.join(" ", arguments),
                    e.getMessage());
            throw new IOException("Failed to fetch tools.", e);
        }
    }

    public static McpSchema.CallToolResult callTool(
            McpServer server, String toolName, Map<String, Object> arguments, @Nullable Path projectRoot)
            throws IOException {
        if (server instanceof HttpMcpServer httpMcpServer) {
            final URL url = httpMcpServer.url();
            try {
                return withMcpSyncClient(
                        url,
                        httpMcpServer.bearerToken(),
                        projectRoot,
                        client -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments)));
            } catch (Exception e) {
                logger.error("Failed to call tool '{}' from MCP server at {}: {}", toolName, url, e.getMessage());
                throw new IOException(
                        "Failed to fetch tools. Ensure the server is a stateless, streamable HTTP MCP server.", e);
            }
        } else if (server instanceof StdioMcpServer stdioMcpServer) {
            try {
                return withMcpSyncClient(
                        stdioMcpServer.command(),
                        stdioMcpServer.args(),
                        stdioMcpServer.env(),
                        projectRoot,
                        client -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments)));
            } catch (Exception e) {
                logger.error(
                        "Failed to call tool '{}' from MCP server on command '{} {}': {} ",
                        toolName,
                        stdioMcpServer.command(),
                        String.join(" ", stdioMcpServer.args()),
                        e.getMessage());
                throw new IOException("Failed to fetch tools.", e);
            }
        } else {
            throw new IOException(
                    "Unsupported MCP server type: " + server.getClass().getName());
        }
    }
}
