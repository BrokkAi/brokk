package ai.brokk.claudecode;

import ai.brokk.claudecode.tools.ScanUsagesTool;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone MCP server exposing Brokk's code intelligence tools.
 * No Brokk app runtime or API keys required -- Claude Code's LLM provides the reasoning,
 * this plugin provides structural code analysis.
 *
 * <p>Communicates via stdio JSON-RPC (standard MCP transport).
 */
public class BrokkMcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkMcpServer.class);
    private static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        try {
            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    System.out.println("Brokk Code Intelligence MCP Server v" + VERSION);
                    System.out.println("Provides code intelligence tools (scanUsages, etc.) via MCP.");
                    System.out.println();
                    System.out.println("Usage: java -jar claude-code-plugin-all.jar");
                    System.out.println("  Communicates via stdio (JSON-RPC). Launched by Claude Code as a subprocess.");
                    System.exit(0);
                }
            }

            var scanUsagesTool = new ScanUsagesTool();

            List<McpServerFeatures.SyncToolSpecification> tools = List.of(scanUsagesTool.specification());

            McpJsonMapper mapper = McpJsonDefaults.getMapper();
            AtomicReference<McpSyncServer> serverRef = new AtomicReference<>();
            AtomicBoolean shutdownInitiated = new AtomicBoolean(false);

            // Wrap System.in to detect EOF (parent process death) and exit cleanly
            InputStream stdinWrapper = new FilterInputStream(System.in) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b == -1) {
                        initiateShutdown(serverRef, shutdownInitiated);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = super.read(buf, off, len);
                    if (n == -1) {
                        initiateShutdown(serverRef, shutdownInitiated);
                    }
                    return n;
                }
            };

            McpSyncServer mcpServer = McpServer.sync(new StdioServerTransportProvider(mapper, stdinWrapper, System.out))
                    .serverInfo("Brokk Code Intelligence", VERSION)
                    .jsonMapper(mapper)
                    .requestTimeout(Duration.ofMinutes(10))
                    .tools(tools)
                    .build();
            serverRef.set(mcpServer);
            logger.info("Brokk Code Intelligence MCP Server started");

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                var server = serverRef.get();
                                if (server != null) {
                                    server.closeGracefully();
                                }
                            },
                            "BrokkPlugin-ShutdownHook"));

            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(0);
        } catch (Exception e) {
            logger.error("Failed to start Brokk Code Intelligence MCP Server", e);
            System.exit(1);
        }
    }

    private static void initiateShutdown(
            AtomicReference<McpSyncServer> serverRef, AtomicBoolean shutdownInitiated) {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            return;
        }
        logger.info("stdin EOF detected; parent process gone, shutting down");
        var server = serverRef.get();
        if (server != null) {
            server.closeGracefully();
        }
        System.exit(0);
    }
}
