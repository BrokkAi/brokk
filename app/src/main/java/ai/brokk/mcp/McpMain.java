package ai.brokk.mcp;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@org.jspecify.annotations.NullMarked
@Command(name = "brokk-mcp-server", mixinStandardHelpOptions = true, description = "Brokk external MCP HTTP server")
public class McpMain implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(McpMain.class);

    @Option(
            names = {"--port", "-p"},
            description = "Port to listen on (default: 3001)",
            defaultValue = "3001")
    private int port;

    @Option(
            names = {"--idle"},
            description = "Seconds of idle time (no connections) before shutting down (default: 300)",
            defaultValue = "300")
    private int idleSeconds;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new McpMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        Path projectPath = Path.of(".").toAbsolutePath().normalize();
        try (var project = new MainProject(projectPath);
                var contextManager = new ContextManager(project)) {
            contextManager.createHeadless(false);
            var server = new BrokkExternalMcpServer(contextManager);
            return server.run(port, idleSeconds);
        } catch (Exception e) {
            logger.error("Failed to start Brokk MCP Server", e);
            return 1;
        }
    }
}
