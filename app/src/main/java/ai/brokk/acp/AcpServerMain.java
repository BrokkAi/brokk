package ai.brokk.acp;

import ai.brokk.ContextManager;
import ai.brokk.cli.CliArgParser;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import java.nio.file.Path;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Entry point for the native Java ACP server.
 *
 * <p>Replaces the three-layer stack (Python ACP bridge -> HTTP -> HeadlessExecutorMain)
 * with a single Java process that speaks ACP natively over stdio.
 *
 * <p>Usage: {@code java AcpServerMain --workspace-dir /path/to/repo [--vendor X] [--brokk-api-key KEY] [--proxy-setting BROKK|LOCALHOST|STAGING]}
 */
public final class AcpServerMain {
    private static final Logger logger = LogManager.getLogger(AcpServerMain.class);

    private static final Set<String> VALID_ARGS =
            Set.of("workspace-dir", "brokk-api-key", "proxy-setting", "vendor", "help");

    private static final Set<String> SENSITIVE_ARGS = Set.of("brokk-api-key");

    private AcpServerMain() {}

    public static void main(String[] args) {
        // Catch errors (NoClassDefFoundError, OutOfMemoryError) that would otherwise exit silently
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("Uncaught exception in thread " + t.getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        });

        try {
            System.setProperty("java.awt.headless", "true");

            var parseResult = CliArgParser.parse(args, VALID_ARGS);
            var parsedArgs = parseResult.args();

            if (!parseResult.invalidKeys().isEmpty()) {
                parseResult.invalidKeys().forEach(k -> System.err.println("Warning: unknown argument --" + k));
            }

            // Log args with sensitive values redacted
            var redacted = CliArgParser.redactSensitiveArgs(parsedArgs, SENSITIVE_ARGS);
            logger.info("AcpServerMain starting with: {}", CliArgParser.formatForLogging(redacted));

            if (parsedArgs.containsKey("help")) {
                System.err.println("Usage: java AcpServerMain --workspace-dir <path> [options]");
                System.err.println("  --workspace-dir <path>     Path to workspace directory (required)");
                System.err.println(
                        "  --brokk-api-key <key>      Brokk API key override (optional, prefer env BROKK_API_KEY)");
                System.err.println("  --proxy-setting <setting>  LLM proxy: BROKK, LOCALHOST, STAGING (optional)");
                System.err.println("  --vendor <vendor>          Model vendor preference (optional)");
                System.exit(0);
            }

            // Workspace dir is required
            var workspaceDirStr = CliArgParser.getConfigValue(parsedArgs, "workspace-dir", "WORKSPACE_DIR");
            if (workspaceDirStr == null) {
                System.err.println("Error: --workspace-dir is required (or set WORKSPACE_DIR env var)");
                System.exit(1);
            }
            var workspaceDir = Path.of(workspaceDirStr);

            // Build project and apply configuration
            var project = new MainProject(workspaceDir);
            CliArgParser.applyVendorPreference(parsedArgs.get("vendor"), project);
            CliArgParser.applyHeadlessOverrides(parsedArgs);

            // Create ContextManager and initialize headless.
            // Use a silent console (not HeadlessConsole) because stdout is reserved for JSON-RPC.
            // HeadlessConsole writes to System.out which would corrupt the protocol stream.
            var contextManager = new ContextManager(project);
            var initThread = new Thread(
                    () -> {
                        contextManager.createHeadless(false, new MemoryConsole() {});
                        logger.info("ContextManager headless initialization complete");
                    },
                    "AcpServer-init");
            initThread.setDaemon(true);
            initThread.start();

            // Create job infrastructure
            var jobStore = new JobStore(workspaceDir.resolve(".brokk"));
            var jobRunner = new JobRunner(contextManager, jobStore);

            // Wait for init
            initThread.join();

            // Create and start ACP agent
            var agent = new BrokkAcpAgent(contextManager, jobRunner, jobStore);
            var transport = new StdioAcpAgentTransport();
            var support = AcpAgentSupport.create(agent).transport(transport).build();

            // Register shutdown hook -- close transport first (stop accepting requests),
            // then job infrastructure, then context manager
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                logger.info("Shutdown hook triggered, cleaning up...");
                                try {
                                    support.close();
                                } catch (Exception e) {
                                    logger.warn("Error closing ACP transport", e);
                                }
                                try {
                                    jobRunner.shutdown();
                                } catch (Exception e) {
                                    logger.warn("Error shutting down JobRunner", e);
                                }
                                try {
                                    contextManager.close();
                                } catch (Exception e) {
                                    logger.warn("Error closing ContextManager", e);
                                }
                                logger.info("Shutdown complete");
                            },
                            "AcpServer-shutdown"));

            logger.info("ACP server started, listening on stdio");

            // Block until the transport terminates (stdin EOF or graceful close).
            // StdioAcpAgentTransport reads from System.in and terminates on EOF,
            // which serves as the parent-death signal (same mechanism as HeadlessExecutorMain).
            support.run();

        } catch (Exception e) {
            logger.fatal("AcpServerMain failed to start", e);
            System.err.println("Fatal: " + e.getMessage());
            System.exit(1);
        }
    }
}
