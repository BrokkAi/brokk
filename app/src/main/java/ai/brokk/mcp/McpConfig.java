package ai.brokk.mcp;

import java.util.List;

/** Container for MCP servers configured for a project. */
public record McpConfig(List<McpServer> servers) {
    public static final McpConfig EMPTY = new McpConfig(List.of());

    public McpConfig() {
        this(List.of());
    }
}
