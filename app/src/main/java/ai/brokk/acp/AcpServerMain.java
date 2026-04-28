package ai.brokk.acp;

import ai.brokk.ContextManager;
import ai.brokk.cli.CliArgParser;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

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

            // Workspace dir is now optional: ACP's session/new carries a per-session cwd that
            // each session uses. The CLI flag (or WORKSPACE_DIR env) becomes the default for
            // sessions that omit cwd, falling back to the process working directory if neither
            // is set.
            var workspaceDirStr = CliArgParser.getConfigValue(parsedArgs, "workspace-dir", "WORKSPACE_DIR");
            var defaultWorkspaceDir = workspaceDirStr != null
                    ? Path.of(workspaceDirStr).toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            logger.info("Default workspace dir: {}", defaultWorkspaceDir);

            CliArgParser.applyHeadlessOverrides(parsedArgs);
            var vendorOverride = parsedArgs.get("vendor");

            // Bundles are created lazily per-session-cwd. The factory captures the vendor
            // preference / headless config so each new bundle inherits the same baseline.
            BrokkAcpAgent.WorkspaceBundleFactory factory = root -> createWorkspaceBundle(root, vendorOverride);

            // Create and start ACP agent. The default workspace seeds list-sessions for clients
            // that haven't issued a session/new yet, but is otherwise decorative.
            var agent = new BrokkAcpAgent(defaultWorkspaceDir, factory);
            var jsonMapper = McpJsonDefaults.getMapper();
            patchAcpDuplicateKeyBug(jsonMapper);
            var transport = new StdioAcpAgentTransport(jsonMapper);
            var runtime = new BrokkAcpRuntime(transport, agent);

            // Register shutdown hook -- close transport first (stop accepting requests),
            // then close every active bundle (cancels active jobs + closes its ContextManager).
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                logger.info("Shutdown hook triggered, cleaning up...");
                                try {
                                    runtime.close();
                                } catch (Exception e) {
                                    logger.warn("Error closing ACP transport", e);
                                }
                                try {
                                    agent.closeAllBundles();
                                } catch (Exception e) {
                                    logger.warn("Error closing workspace bundles", e);
                                }
                                logger.info("Shutdown complete");
                            },
                            "AcpServer-shutdown"));

            logger.info("ACP server started, listening on stdio");

            // Block until the transport terminates (stdin EOF or graceful close).
            // StdioAcpAgentTransport reads from System.in and terminates on EOF,
            // which serves as the parent-death signal (same mechanism as HeadlessExecutorMain).
            runtime.run();

        } catch (Exception e) {
            logger.fatal("AcpServerMain failed to start", e);
            System.err.println("Fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Materializes a workspace bundle for {@code root}. Builds a fresh {@link MainProject},
     * applies the vendor preference, runs headless context-manager initialization on a daemon
     * thread (joining before returning), and constructs the per-bundle {@link JobStore} +
     * {@link JobRunner}. Called lazily by {@link BrokkAcpAgent#newSession} when a session names
     * a {@code cwd} that hasn't been seen before.
     */
    private static BrokkAcpAgent.WorkspaceBundle createWorkspaceBundle(Path root, @Nullable String vendorOverride) {
        logger.info("Materializing workspace bundle for {}", root);
        try {
            var project = new MainProject(root);
            if (vendorOverride != null) {
                CliArgParser.applyVendorPreference(vendorOverride, project);
            }
            var contextManager = new ContextManager(project);
            var initThread = new Thread(
                    () -> {
                        contextManager.createHeadless(false, new MemoryConsole() {});
                        logger.info("ContextManager headless init complete for {}", root);
                    },
                    "AcpServer-init-" + root.getFileName());
            initThread.setDaemon(true);
            initThread.start();
            initThread.join();
            var jobStore = new JobStore(root.resolve(".brokk"));
            var jobRunner = new JobRunner(contextManager, jobStore);
            return new BrokkAcpAgent.WorkspaceBundle(contextManager, jobRunner, jobStore, root);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while initializing workspace " + root, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize workspace " + root, e);
        }
    }

    /**
     * Workaround for the upstream acp-core schema's duplicate-key serialization bug.
     *
     * <p>The {@code SessionUpdate}, {@code ContentBlock}, and {@code ToolCallContent} interfaces
     * declare their discriminated unions with {@code @JsonTypeInfo(visible=true, property=…)} AND
     * the subtype records each carry a property of the same name. Jackson dutifully emits both,
     * producing duplicate JSON keys (e.g. {@code "sessionUpdate":"tool_call","sessionUpdate":"tool_call"}).
     * Zed's serde-based ACP layer enforces strict tagged-enum decoding and rejects every such
     * notification silently — observable in {@code ~/Library/Logs/Zed/Zed.log} as
     * {@code "duplicate field `sessionUpdate`"}. Strip the record-level property from
     * serialization so only the type-info path emits the discriminator.
     *
     * <p>Reads still work because {@code @JsonTypeInfo(visible=true)} feeds the discriminator
     * into the canonical record constructor argument; we only suppress the writer side.
     */
    static void patchAcpDuplicateKeyBug(io.modelcontextprotocol.json.McpJsonMapper jsonMapper) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper;
        try {
            // Both io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper and
            // .jackson2.JacksonMcpJsonMapper expose getObjectMapper(); reflect to avoid
            // hard-coding either internal class.
            var getter = jsonMapper.getClass().getMethod("getObjectMapper");
            objectMapper = (com.fasterxml.jackson.databind.ObjectMapper) getter.invoke(jsonMapper);
        } catch (ReflectiveOperationException e) {
            logger.warn(
                    "Could not access underlying ObjectMapper on {}; ACP output may contain duplicate JSON keys",
                    jsonMapper.getClass().getName(),
                    e);
            return;
        }
        objectMapper.registerModule(
                new SimpleModule("AcpDuplicateKeyFix").setSerializerModifier(new BeanSerializerModifier() {
                    @Override
                    public java.util.List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> changeProperties(
                            com.fasterxml.jackson.databind.SerializationConfig config,
                            com.fasterxml.jackson.databind.BeanDescription beanDesc,
                            java.util.List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> properties) {
                        var bean = beanDesc.getBeanClass();
                        if (AcpSchema.SessionUpdate.class.isAssignableFrom(bean)) {
                            properties.removeIf(p -> "sessionUpdate".equals(p.getName()));
                        }
                        if (AcpSchema.ContentBlock.class.isAssignableFrom(bean)
                                || AcpSchema.ToolCallContent.class.isAssignableFrom(bean)) {
                            properties.removeIf(p -> "type".equals(p.getName()));
                        }
                        return properties;
                    }
                }));
    }
}
