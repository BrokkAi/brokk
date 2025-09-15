package io.github.jbellis.brokk.mcp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.net.URL;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a configured MCP server for the application.
 *
 * <p>An MCP server can host a set of "tools" (remote capabilities) that Brokk's agents may discover and invoke. This
 * record captures the essential configuration needed to contact and authenticate to such a server and to track which
 * tools have been discovered.
 *
 * <p>Typical lifecycle:
 *
 * <ol>
 *   <li>Create an entry with {@code name} and {@code url} (token optional).
 *   <li>Later, a discovery step may populate {@code tools} with the identifiers advertised by the remote server.
 *   <li>The {@code bearerToken}, when present, is used to authenticate requests; it is expected to be normalized to the
 *       form {@code "Bearer <token>"}.
 * </ol>
 */
@JsonTypeName("http")
public record HttpMcpServer(
        /**
         * Human-friendly display name for the MCP server.
         *
         * <p>This value is shown in UI lists and dialogs to help distinguish multiple configured servers. It is not
         * used for network communication.
         */
        String name,

        /**
         * Base URL of the MCP server, including scheme (for example {@code https://mcp.example.com}).
         *
         * <p>The application will use this URL as the endpoint for tool discovery and for invoking tools offered by the
         * server. The URL should include a valid scheme and host.
         */
        URL url,

        /**
         * Optional list of tool identifiers (names) advertised by the server.
         *
         * <p>This list may initially be {@code null} to indicate that discovery has not yet been performed. After a
         * discovery step, implementations may populate this field with the server-provided tool names so the UI and
         * agent tool registry can present and use them.
         */
        @Nullable List<String> tools,

        /**
         * Optional bearer token used to authenticate requests to the MCP server.
         *
         * <p>If present, this value is expected to be in the normalized form {@code "Bearer <token>"} (the UI
         * normalizes raw tokens by prefixing {@code "Bearer "} when necessary). A {@code null} or empty value indicates
         * that no bearer-token authentication should be used.
         */
        @Nullable String bearerToken)
        implements McpServer {}
