package ai.brokk.acpserver;

import static java.util.Objects.requireNonNull;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.acpserver.agent.AcpAgent;
import ai.brokk.acpserver.agent.AcpProtocolException;
import ai.brokk.acpserver.agent.AcpSyncAgent;
import ai.brokk.acpserver.agent.SyncPromptContext;
import ai.brokk.acpserver.spec.AcpSchema.ContextAddFilesRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextAddFilesResponse;
import ai.brokk.acpserver.spec.AcpSchema.ContextDropRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextDropResponse;
import ai.brokk.acpserver.spec.AcpSchema.ContextFragmentInfo;
import ai.brokk.acpserver.spec.AcpSchema.ContextGetRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextGetResponse;
import ai.brokk.acpserver.spec.AcpSchema.InitializeRequest;
import ai.brokk.acpserver.spec.AcpSchema.InitializeResponse;
import ai.brokk.acpserver.spec.AcpSchema.ModelsListRequest;
import ai.brokk.acpserver.spec.AcpSchema.ModelsListResponse;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionRequest;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionResponse;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.PromptResponse;
import ai.brokk.acpserver.spec.AcpSchema.SessionInfoDto;
import ai.brokk.acpserver.spec.AcpSchema.SessionsListRequest;
import ai.brokk.acpserver.spec.AcpSchema.SessionsListResponse;
import ai.brokk.acpserver.spec.AcpSchema.StopReason;
import ai.brokk.acpserver.spec.AcpSchema.TextContent;
import ai.brokk.acpserver.transport.StdioAcpAgentTransport;
import ai.brokk.agents.CodeAgent;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ACP (Agent Client Protocol) server entry point for Brokk.
 * <p>
 * This provides MCP-like tool access via the Agent Client Protocol,
 * allowing IDE integrations to interact with Brokk's agentic tools.
 * <p>
 * Per the ACP spec, the working directory is provided by the client
 * via {@code session/new}, not by the process cwd. Project initialization
 * is deferred until the first session is created.
 */
public class BrokkAcpServer {
    private static final Logger logger = LogManager.getLogger(BrokkAcpServer.class);

    private MainProject project;
    private ContextManager cm;
    private volatile boolean initializing;
    private volatile String initError;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("Brokk ACP Server v" + BuildInfo.version);
                System.out.println("Provides Agent Client Protocol (ACP) access to Brokk's agentic tools.");
                System.out.println();
                System.out.println("Usage: Run this server as a subprocess, communicating via stdin/stdout.");
                System.exit(0);
            }
        }

        var instance = new BrokkAcpServer();

        try {
            AcpSyncAgent agent = instance.buildAgent();
            logger.info("Brokk ACP Server started");

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                logger.info("Brokk ACP Server shutting down");
                                instance.close();
                            },
                            "BrokkACP-Server-ShutdownHook"));

            agent.run();
        } catch (Exception e) {
            logger.error("Failed to start Brokk ACP Server", e);
            System.exit(1);
        } finally {
            instance.close();
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
                .modelsListHandler(this::handleModelsList)
                .contextGetHandler(this::handleContextGet)
                .contextAddFilesHandler(this::handleContextAddFiles)
                .contextDropHandler(this::handleContextDrop)
                .sessionsListHandler(this::handleSessionsList)
                .build();
    }

    private void startProjectInitialization(Path projectPath) {
        close();
        initializing = true;
        initError = null;
        logger.info("Starting project initialization at {}", projectPath);
        var initThread = new Thread(
                () -> {
                    try {
                        var p = new MainProject(projectPath);
                        var c = new ContextManager(p);
                        c.createHeadless(true, new MutedConsoleIO(c.getIo()));
                        project = p;
                        cm = c;
                        logger.info("Project initialized at {}", projectPath);
                    } catch (Exception e) {
                        initError = e.getMessage();
                        logger.error("Failed to initialize project at {}", projectPath, e);
                    } finally {
                        initializing = false;
                    }
                },
                "BrokkACP-ProjectInit");
        initThread.setDaemon(true);
        initThread.start();
    }

    private void close() {
        if (cm != null) {
            try {
                cm.close();
            } catch (Exception e) {
                logger.warn("Error closing ContextManager", e);
            }
            cm = null;
        }
        if (project != null) {
            try {
                project.close();
            } catch (Exception e) {
                logger.warn("Error closing MainProject", e);
            }
            project = null;
        }
    }

    private ContextManager requireSession() {
        if (initializing) {
            throw new AcpProtocolException(
                    AcpProtocolException.INVALID_PARAMS, "Session is still initializing. Please wait.");
        }
        if (initError != null) {
            throw new AcpProtocolException(
                    AcpProtocolException.INVALID_PARAMS, "Session initialization failed: " + initError);
        }
        if (cm == null) {
            throw new AcpProtocolException(
                    AcpProtocolException.INVALID_PARAMS, "No active session. Call session/new first.");
        }
        return cm;
    }

    private InitializeResponse handleInitialize(InitializeRequest req) {
        logger.debug("Received initialize request with protocol version {}", req.protocolVersion());
        if (req.protocolVersion() != 1) {
            throw new AcpProtocolException(
                    AcpProtocolException.INVALID_PARAMS,
                    "Unsupported protocol version: " + req.protocolVersion() + ". Only version 1 is supported.");
        }
        return InitializeResponse.ok();
    }

    private NewSessionResponse handleNewSession(NewSessionRequest req) {
        String sessionId = UUID.randomUUID().toString();
        var workingDir = req.workingDirectory();
        logger.debug("Creating new ACP session {} for working directory {}", sessionId, workingDir);

        if (workingDir == null || workingDir.isBlank()) {
            throw new AcpProtocolException(
                    AcpProtocolException.INVALID_PARAMS, "workingDirectory is required in session/new");
        }

        var requestedPath = Path.of(workingDir).toAbsolutePath().normalize();

        // Initialize or re-initialize project if the directory changed
        if (cm == null
                || !cm.getProject().getRoot().toAbsolutePath().normalize().equals(requestedPath)) {
            startProjectInitialization(requestedPath);
        } else {
            cm.dropWithHistorySemantics(List.of());
        }

        return new NewSessionResponse(sessionId, null, null);
    }

    private PromptResponse handlePrompt(PromptRequest req, SyncPromptContext ctx) {
        var activeCm = requireSession();
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
        IConsoleIO originalIo = activeCm.getIo();
        IConsoleIO progressIo = new AcpProgressConsole(ctx, originalIo);
        activeCm.setIo(progressIo);

        try {
            // Execute using CodeAgent (similar to MCP's callCodeAgent)
            var model = requireNonNull(activeCm.getService()
                    .getModel(activeCm.getProject().getModelConfig(ModelProperties.ModelType.CODE)));
            var ca = new CodeAgent(activeCm, model);

            TaskResult result = ca.execute(instructions, EnumSet.noneOf(CodeAgent.Option.class), List.of());

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
            if (progressIo instanceof AcpProgressConsole acpConsole) {
                acpConsole.shutdown();
            }
            try {
                activeCm.setIo(originalIo);
            } catch (Exception e) {
                logger.error("Failed to restore original IO", e);
            }
        }
    }

    private ModelsListResponse handleModelsList(ModelsListRequest req) {
        logger.debug("Received models/list request");
        if (cm == null) {
            return new ModelsListResponse(Map.of());
        }
        var models = cm.getService().getAvailableModels();
        return new ModelsListResponse(models);
    }

    private ContextGetResponse handleContextGet(ContextGetRequest req) {
        logger.debug("Received context/get request");
        var activeCm = requireSession();
        var context = activeCm.liveContext();
        var fragments = context.getAllFragmentsInDisplayOrder().stream()
                .map(f -> new ContextFragmentInfo(
                        f.id(), f.getType().name(), f.shortDescription().join()))
                .toList();
        return new ContextGetResponse(fragments);
    }

    private ContextAddFilesResponse handleContextAddFiles(ContextAddFilesRequest req) {
        logger.debug(
                "Received context/add-files request with {} paths",
                req.relativePaths().size());
        var activeCm = requireSession();
        var files = req.relativePaths().stream().map(activeCm::toFile).toList();
        activeCm.addFiles(files);

        var addedIds = new ArrayList<String>();
        var context = activeCm.liveContext();
        for (var fragment : context.getAllFragmentsInDisplayOrder()) {
            var sourceFiles = fragment.sourceFiles().join();
            for (var file : files) {
                if (sourceFiles.contains(file)) {
                    addedIds.add(fragment.id());
                    break;
                }
            }
        }
        return new ContextAddFilesResponse(addedIds);
    }

    private ContextDropResponse handleContextDrop(ContextDropRequest req) {
        logger.debug(
                "Received context/drop request with {} fragment IDs",
                req.fragmentIds().size());
        var activeCm = requireSession();
        var droppedIds = new ArrayList<>(req.fragmentIds());
        activeCm.pushContext(ctx -> ctx.removeFragmentsByIds(req.fragmentIds()));
        return new ContextDropResponse(droppedIds);
    }

    private SessionsListResponse handleSessionsList(SessionsListRequest req) {
        logger.debug("Received sessions/list request");
        if (cm == null) {
            return new SessionsListResponse(List.of());
        }
        var sessionManager = cm.getProject().getSessionManager();
        var sessions = sessionManager.listSessions().stream()
                .map(s -> new SessionInfoDto(s.id().toString(), s.name(), s.created(), s.modified()))
                .toList();
        return new SessionsListResponse(sessions);
    }
}
