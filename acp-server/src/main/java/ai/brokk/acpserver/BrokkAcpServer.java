package ai.brokk.acpserver;

import static java.util.Objects.requireNonNull;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.acpserver.agent.AcpAgent;
import ai.brokk.acpserver.agent.AcpSyncAgent;
import ai.brokk.acpserver.agent.SyncPromptContext;
import ai.brokk.acpserver.spec.AcpSchema.InitializeRequest;
import ai.brokk.acpserver.spec.AcpSchema.InitializeResponse;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionRequest;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionResponse;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.PromptResponse;
import ai.brokk.acpserver.spec.AcpSchema.StopReason;
import ai.brokk.acpserver.spec.AcpSchema.TextContent;
import ai.brokk.acpserver.transport.StdioAcpAgentTransport;
import ai.brokk.agents.CodeAgent;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * ACP (Agent Client Protocol) server entry point for Brokk.
 * <p>
 * This provides MCP-like tool access via the Agent Client Protocol,
 * allowing IDE integrations to interact with Brokk's agentic tools.
 */
@NullMarked
public class BrokkAcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkAcpServer.class);

    private final ContextManager cm;
    private final Map<String, UUID> sessionToBrokkSession = new HashMap<>();

    public BrokkAcpServer(ContextManager cm) {
        this.cm = cm;
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        Path projectPath = Path.of(".").toAbsolutePath().normalize();

        try (var project = new MainProject(projectPath);
                var cm = new ContextManager(project)) {

            cm.createHeadless(true, new MutedConsoleIO(cm.getIo()));

            BrokkAcpServer instance = new BrokkAcpServer(cm);

            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    System.out.println("Brokk ACP Server v" + BuildInfo.version);
                    System.out.println("Provides Agent Client Protocol (ACP) access to Brokk's agentic tools.");
                    System.out.println();
                    System.out.println("Usage: Run this server as a subprocess, communicating via stdin/stdout.");
                    System.exit(0);
                }
            }

            AcpSyncAgent agent = instance.buildAgent();
            logger.info("Brokk ACP Server started");

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                logger.info("Brokk ACP Server shutting down");
                            },
                            "BrokkACP-Server-ShutdownHook"));

            agent.run();
        } catch (Exception e) {
            logger.error("Failed to start Brokk ACP Server", e);
            System.exit(1);
        }
    }

    /**
     * Builds the ACP agent with all handlers configured.
     */
    public AcpSyncAgent buildAgent() {
        return AcpAgent.sync(new StdioAcpAgentTransport())
                .initializeHandler(this::handleInitialize)
                .newSessionHandler(this::handleNewSession)
                .promptHandler(this::handlePrompt)
                .build();
    }

    private InitializeResponse handleInitialize(InitializeRequest req) {
        logger.debug("Received initialize request with protocol version {}", req.protocolVersion());
        return InitializeResponse.ok();
    }

    private NewSessionResponse handleNewSession(NewSessionRequest req) {
        String sessionId = UUID.randomUUID().toString();
        logger.debug("Creating new ACP session {} for working directory {}", sessionId, req.workingDirectory());

        // Map the ACP session to a Brokk session
        sessionToBrokkSession.put(sessionId, cm.getCurrentSessionId());

        // Clear workspace for fresh session
        cm.dropWithHistorySemantics(java.util.List.of());

        return new NewSessionResponse(sessionId, null, null);
    }

    private PromptResponse handlePrompt(PromptRequest req, SyncPromptContext ctx) {
        logger.debug("Received prompt request for session {}", req.sessionId());

        // Extract text from messages
        String instructions = req.messages().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .collect(Collectors.joining("\n\n"));

        if (instructions.isBlank()) {
            ctx.sendMessage("No instructions provided in the prompt.");
            return PromptResponse.endTurn();
        }

        // Create ACP progress console and swap it in
        IConsoleIO originalIo = cm.getIo();
        IConsoleIO progressIo = new AcpProgressConsole(ctx, originalIo);
        cm.setIo(progressIo);

        try {
            // Clear workspace before processing
            cm.dropWithHistorySemantics(java.util.List.of());

            // Execute using CodeAgent (similar to MCP's callCodeAgent)
            var model = requireNonNull(
                    cm.getService().getModel(cm.getProject().getModelConfig(ModelProperties.ModelType.CODE)));
            var ca = new CodeAgent(cm, model);

            TaskResult result =
                    ca.execute(instructions, java.util.EnumSet.noneOf(CodeAgent.Option.class), java.util.List.of());

            var stopDetails = result.stopDetails();
            var reason = stopDetails.reason();

            // Build response metadata
            Map<String, Object> meta = new HashMap<>();
            meta.put("stopReason", reason.name());
            if (!stopDetails.explanation().isBlank()) {
                meta.put("explanation", stopDetails.explanation());
            }

            // Send final status message
            if (reason == TaskResult.StopReason.SUCCESS) {
                ctx.sendMessage("\n\nTask completed successfully.");
                return new PromptResponse(StopReason.END_TURN, meta);
            } else {
                String explanation = stopDetails.explanation();
                if (reason == TaskResult.StopReason.BUILD_ERROR) {
                    String buildError = result.context().getBuildError();
                    if (!buildError.isBlank() && !explanation.contains(buildError)) {
                        explanation = (explanation.isBlank() ? "" : (explanation + "\n\n")) + buildError;
                    }
                }
                ctx.sendMessage(
                        "\n\nTask stopped: " + reason.name() + (explanation.isBlank() ? "" : "\n" + explanation));
                return new PromptResponse(StopReason.END_TURN, meta);
            }
        } catch (Exception e) {
            logger.error("Error processing prompt", e);
            ctx.sendMessage("\n\nError: " + e.getMessage());
            return new PromptResponse(StopReason.ERROR, Map.of("error", e.getMessage()));
        } finally {
            cm.setIo(originalIo);
        }
    }

    /**
     * Returns the tool registry for this server, similar to MCP server.
     */
    public ToolRegistry getToolRegistry() {
        SearchTools searchTools = new SearchTools(cm);
        return ToolRegistry.fromBase(ToolRegistry.empty()).register(searchTools).build();
    }
}
