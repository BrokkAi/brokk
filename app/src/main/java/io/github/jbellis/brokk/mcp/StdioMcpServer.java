package io.github.jbellis.brokk.mcp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

@JsonTypeName("stdio")
public record StdioMcpServer(
        /**
         * Human-friendly display name for the MCP server.
         *
         * <p>This value is shown in UI lists and dialogs to help distinguish multiple configured servers. It is not
         * used for network communication.
         */
        String name,

        /**
         * Executable to launch the MCP server over stdio.
         *
         * <p>This is the binary or script name as it would be invoked from a shell, e.g. "node", "python", or an
         * absolute path to an executable.
         */
        String command,

        /**
         * Command-line arguments passed to the {@code command}.
         *
         * <p>Arguments are provided in order and without shell parsing; quoting or escaping should be handled by the
         * caller when constructing this list.
         */
        List<String> args,

        /**
         * Environment variables to set for the server process.
         *
         * <p>Keys and values are applied on top of the current process environment. Use this to configure things like
         * PATH or server-specific settings required by the MCP server.
         */
        Map<String, String> env,

        /**
         * Optional list of tool identifiers (names) advertised by the server.
         *
         * <p>This list may initially be {@code null} to indicate that discovery has not yet been performed. After a
         * discovery step, implementations may populate this field with the server-provided tool names so the UI and
         * agent tool registry can present and use them.
         */
        @Nullable List<String> tools)
        implements McpServer {}
