package io.github.jbellis.brokk.mcp;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public sealed interface McpServer permits HttpMcpServer, StdioMcpServer {

    /**
     * Human-friendly display name for the MCP server.
     *
     * <p>This value is shown in UI lists and dialogs to help distinguish multiple configured servers. It is not used
     * during communication.
     */
    String name();

    /**
     * Optional list of tool identifiers (names) advertised by the server.
     *
     * <p>This list may initially be {@code null} to indicate that discovery has not yet been performed. After a
     * discovery step, implementations may populate this field with the server-provided tool names so the UI and agent
     * tool registry can present and use them.
     */
    @Nullable
    List<String> tools();
}
