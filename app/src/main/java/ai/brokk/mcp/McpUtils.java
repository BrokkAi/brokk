package ai.brokk.mcp;

import static java.util.Objects.requireNonNull;

import ai.brokk.util.Environment;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
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
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Mono;

public class McpUtils {

    private static final Logger logger = LogManager.getLogger(McpUtils.class);

    /**
     * Timeout for MCP requests.
     * 60 seconds is chosen to accommodate long-running tools and avoid spurious timeouts,
     * while still providing a reasonable upper bound for requests in typical usage.
     * Used consistently for both sync and async clients.
     */
    private static final Duration MCP_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static McpClientTransport buildTransport(URL url, @Nullable String bearerToken) {
        final String baseUrl;
        if (url.getPort() == -1 || url.getPort() == url.getDefaultPort()) {
            baseUrl = url.getProtocol() + "://" + url.getHost();
        } else {
            baseUrl = url.getProtocol() + "://" + url.getHost() + ":" + url.getPort();
        }

        // only add /mpc when nothing is specified (some servers may not have i.e. /doc)
        String endpointPath = url.getPath();
        if (endpointPath == null || endpointPath.isEmpty()) {
            endpointPath = "/mcp";
        }
        String query = url.getQuery();
        String endpoint = (query != null && !query.isEmpty()) ? endpointPath + "?" + query : endpointPath;

        var transportBuilder = HttpClientStreamableHttpTransport.builder(baseUrl)
                .resumableStreams(true)
                .openConnectionOnStartup(false)
                .endpoint(endpoint);
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

    private static McpAsyncClient buildAsyncClient(URL url, @Nullable String bearerToken) {
        final var transport = buildTransport(url, bearerToken);
        return McpClient.async(transport)
                .loggingConsumer(msg -> {
                    logger.debug(msg);
                    return Mono.empty();
                })
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                .requestTimeout(MCP_REQUEST_TIMEOUT)
                .build();
    }

    private static McpAsyncClient buildAsyncClient(String command, List<String> arguments, Map<String, String> env) {
        final var transport = buildTransport(command, arguments, env);
        return McpClient.async(transport)
                .loggingConsumer(msg -> {
                    logger.debug(msg);
                    return Mono.empty();
                })
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                .requestTimeout(MCP_REQUEST_TIMEOUT)
                .build();
    }

    private static <T> T withMcpAsyncClient(
            URL url,
            @Nullable String bearerToken,
            @Nullable Path projectRoot,
            Function<McpAsyncClient, Mono<T>> function) {
        final var client = buildAsyncClient(url, bearerToken);
        try {
            client.initialize().block();
            if (projectRoot != null) {
                client.addRoot(new McpSchema.Root(projectRoot.toUri().toString(), "Project root path."))
                        .block();
            }
            return requireNonNull(function.apply(client).block());
        } finally {
            client.closeGracefully();
        }
    }

    private static <T> T withMcpAsyncClient(
            String command,
            List<String> arguments,
            Map<String, String> env,
            @Nullable Path projectRoot,
            Function<McpAsyncClient, Mono<T>> function) {
        final var client = buildAsyncClient(command, arguments, env);
        try {
            client.initialize().block();
            if (projectRoot != null) {
                client.addRoot(new McpSchema.Root(projectRoot.toUri().toString(), "Project root path."))
                        .block();
            }
            return requireNonNull(function.apply(client).block());
        } finally {
            client.closeGracefully();
        }
    }

    @Blocking
    public static List<McpSchema.Tool> fetchTools(McpServer server) throws IOException {
        if (server instanceof HttpMcpServer httpMcpServer) {
            return fetchTools(httpMcpServer.url(), httpMcpServer.bearerToken(), null);
        } else if (server instanceof StdioMcpServer stdioMcpServer) {
            return fetchTools(stdioMcpServer.command(), stdioMcpServer.args(), stdioMcpServer.env(), null);
        } else {
            return Collections.emptyList();
        }
    }

    @Blocking
    public static List<McpSchema.Tool> fetchTools(URL url, @Nullable String bearerToken) throws IOException {
        return fetchTools(url, bearerToken, null);
    }

    @Blocking
    public static List<McpSchema.Tool> fetchTools(URL url, @Nullable String bearerToken, @Nullable Path projectRoot)
            throws IOException {
        try {
            return withMcpAsyncClient(
                    url, bearerToken, projectRoot, client -> client.listTools().map(McpSchema.ListToolsResult::tools));
        } catch (Exception e) {
            Throwable rootCause = e;
            while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                rootCause = rootCause.getCause();
            }
            String rootMessage = rootCause.getMessage() != null
                    ? rootCause.getMessage()
                    : rootCause.getClass().getSimpleName();
            logger.error(
                    "Failed to fetch tools from MCP server at {}: {} (root cause: {})",
                    url,
                    e.getMessage(),
                    rootMessage);
            throw new IOException(
                    "Failed to fetch tools from " + url + ": " + rootMessage
                            + ". Ensure the server is a stateless, streamable HTTP MCP server.",
                    e);
        }
    }

    @Blocking
    public static List<McpSchema.Tool> fetchTools(
            String command, List<String> arguments, Map<String, String> env, @Nullable Path projectRoot)
            throws IOException {
        try {
            return withMcpAsyncClient(command, arguments, env, projectRoot, client -> client.listTools()
                    .map(McpSchema.ListToolsResult::tools));
        } catch (Exception e) {
            Throwable rootCause = e;
            while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                rootCause = rootCause.getCause();
            }
            String rootMessage = rootCause.getMessage() != null
                    ? rootCause.getMessage()
                    : rootCause.getClass().getSimpleName();
            logger.error(
                    "Failed to fetch tools from MCP server on command '{} {}': {} (root cause: {})",
                    command,
                    String.join(" ", arguments),
                    e.getMessage(),
                    rootMessage);
            throw new IOException("Failed to fetch tools: " + rootMessage, e);
        }
    }

    @Blocking
    public static McpSchema.CallToolResult callToolAsync(
            McpServer server, String toolName, Map<String, Object> arguments, @Nullable Path projectRoot)
            throws IOException {
        if (server instanceof HttpMcpServer httpMcpServer) {
            final URL url = httpMcpServer.url();
            try {
                return withMcpAsyncClient(
                        url,
                        httpMcpServer.bearerToken(),
                        projectRoot,
                        client -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments)));
            } catch (Exception e) {
                Throwable rootCause = e;
                while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                    rootCause = rootCause.getCause();
                }
                String rootMessage = rootCause.getMessage() != null
                        ? rootCause.getMessage()
                        : rootCause.getClass().getSimpleName();
                logger.error(
                        "Failed to call tool '{}' from MCP server at {}: {} (root cause: {})",
                        toolName,
                        url,
                        e.getMessage(),
                        rootMessage);
                throw new IOException(
                        "Failed to call tool '" + toolName + "' from " + url + ": " + rootMessage
                                + ". Ensure the server is a stateless, streamable HTTP MCP server.",
                        e);
            }
        } else if (server instanceof StdioMcpServer stdioMcpServer) {
            try {
                return withMcpAsyncClient(
                        stdioMcpServer.command(),
                        stdioMcpServer.args(),
                        stdioMcpServer.env(),
                        projectRoot,
                        client -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments)));
            } catch (Exception e) {
                Throwable rootCause = e;
                while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                    rootCause = rootCause.getCause();
                }
                String rootMessage = rootCause.getMessage() != null
                        ? rootCause.getMessage()
                        : rootCause.getClass().getSimpleName();
                logger.error(
                        "Failed to call tool '{}' from MCP server on command '{} {}': {} (root cause: {})",
                        toolName,
                        stdioMcpServer.command(),
                        String.join(" ", stdioMcpServer.args()),
                        e.getMessage(),
                        rootMessage);
                throw new IOException("Failed to call tool '" + toolName + "': " + rootMessage, e);
            }
        } else {
            throw new IOException(
                    "Unsupported MCP server type: " + server.getClass().getName());
        }
    }

    @Blocking
    public static McpSchema.CallToolResult callTool(
            McpServer server, String toolName, Map<String, Object> arguments, @Nullable Path projectRoot)
            throws IOException {
        return callToolAsync(server, toolName, arguments, projectRoot);
    }
}
