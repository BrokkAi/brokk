package ai.brokk.mcpserver;

import ai.brokk.ContextManager;
import ai.brokk.MutedConsoleIO;
import ai.brokk.project.MainProject;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Lightweight CLI for one-shot Brokk tool execution.
 *
 * <p>Initializes the project analyzer, runs a single tool, prints the result to stdout, and exits.
 * Designed to be called from hook scripts so they can use real Brokk code intelligence.
 *
 * <p>Usage: java ... BrokkQueryCli <toolName> <jsonArgs>
 *
 * <p>Example: java ... BrokkQueryCli searchSymbols '{"patterns":["BrokkExternalMcpServer"]}'
 */
@NullMarked
public class BrokkQueryCli {
    private static final Logger logger = LogManager.getLogger(BrokkQueryCli.class);

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        if (args.length < 2) {
            System.err.println("Usage: brokk query <toolName> '<jsonArgs>'");
            System.err.println("Example: brokk query searchSymbols '{\"patterns\":[\"MyClass\"]}'");
            System.exit(1);
        }

        String toolName = args[0];
        String jsonArgs = args[1];

        Path projectPath = BrokkPureMcpServer.resolveProjectRoot(Path.of("."));
        logger.debug("BrokkQueryCli: tool={} project={}", toolName, projectPath);

        MainProject mainProject = null;
        ContextManager cm = null;
        try {
            mainProject = new MainProject(projectPath);
            cm = new ContextManager(mainProject);
            cm.createHeadless(true, new MutedConsoleIO(cm.getIo()));

            SearchTools searchTools = new SearchTools(cm);
            ToolRegistry registry = ToolRegistry.fromBase(ToolRegistry.empty())
                    .register(searchTools)
                    .build();

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("query")
                    .name(toolName)
                    .arguments(jsonArgs)
                    .build();

            ToolExecutionResult result = registry.executeTool(request);
            System.out.print(result.resultText());

            System.exit(result.status() == ToolExecutionResult.Status.SUCCESS ? 0 : 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            logger.error("BrokkQueryCli failed", e);
            System.exit(1);
        } finally {
            if (cm != null) {
                cm.close();
            }
            if (mainProject != null) {
                mainProject.close();
            }
        }
    }
}
