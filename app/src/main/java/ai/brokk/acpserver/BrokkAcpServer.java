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
import ai.brokk.acpserver.spec.AcpSchema.CancelRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextAddFilesRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextAddFilesResponse;
import ai.brokk.acpserver.spec.AcpSchema.ContextDropRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextDropResponse;
import ai.brokk.acpserver.spec.AcpSchema.ContextFragmentInfo;
import ai.brokk.acpserver.spec.AcpSchema.ContextGetRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextGetResponse;
import ai.brokk.acpserver.spec.AcpSchema.ConversationEntry;
import ai.brokk.acpserver.spec.AcpSchema.ConversationMessage;
import ai.brokk.acpserver.spec.AcpSchema.GetConversationRequest;
import ai.brokk.acpserver.spec.AcpSchema.GetConversationResponse;
import ai.brokk.acpserver.spec.AcpSchema.InitializeRequest;
import ai.brokk.acpserver.spec.AcpSchema.InitializeResponse;
import ai.brokk.acpserver.spec.AcpSchema.ModelInfo;
import ai.brokk.acpserver.spec.AcpSchema.ModelsListRequest;
import ai.brokk.acpserver.spec.AcpSchema.ModelsListResponse;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionRequest;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionResponse;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.PromptResponse;
import ai.brokk.acpserver.spec.AcpSchema.SessionInfoDto;
import ai.brokk.acpserver.spec.AcpSchema.SessionSwitchRequest;
import ai.brokk.acpserver.spec.AcpSchema.SessionSwitchResponse;
import ai.brokk.acpserver.spec.AcpSchema.SessionsListRequest;
import ai.brokk.acpserver.spec.AcpSchema.SessionsListResponse;
import ai.brokk.acpserver.spec.AcpSchema.StopReason;
import ai.brokk.acpserver.spec.AcpSchema.TextContent;
import ai.brokk.acpserver.transport.StdioAcpAgentTransport;
import ai.brokk.agents.CodeAgent;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.AiMessage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

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

    @Nullable private volatile MainProject project;
    @Nullable private volatile ContextManager cm;
    private volatile boolean initializing;
    @Nullable private volatile Path initializingPath;
    @Nullable private volatile String initError;

    @Nullable
    private AcpSyncAgent agent;

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
        agent = AcpAgent.sync(new StdioAcpAgentTransport())
                .initializeHandler(this::handleInitialize)
                .newSessionHandler(this::handleNewSession)
                .promptHandler(this::handlePrompt)
                .modelsListHandler(this::handleModelsList)
                .contextGetHandler(this::handleContextGet)
                .contextAddFilesHandler(this::handleContextAddFiles)
                .contextDropHandler(this::handleContextDrop)
                .sessionsListHandler(this::handleSessionsList)
                .cancelHandler(this::handleCancel)
                .sessionSwitchHandler(this::handleSwitchSession)
                .getConversationHandler(this::handleGetConversation)
                .build();
        return agent;
    }

    private void handleCancel(CancelRequest req) {
        logger.info("Received cancel request for session {}", req.sessionId());
        var a = agent;
        if (a != null) {
            a.interruptPrompt();
        }
    }

    private void startProjectInitialization(Path projectPath) {
        close();
        initializing = true;
        initializingPath = projectPath;
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
        initializing = false;
        initializingPath = null;
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
        // Wait for project initialization to complete (up to 60 seconds)
        if (initializing) {
            logger.info("Waiting for project initialization to complete...");
            long deadline = System.currentTimeMillis() + 60_000;
            while (initializing && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AcpProtocolException(
                            AcpProtocolException.INTERNAL_ERROR, "Interrupted while waiting for initialization.");
                }
            }
            if (initializing) {
                throw new AcpProtocolException(
                        AcpProtocolException.INTERNAL_ERROR, "Session initialization timed out after 60 seconds.");
            }
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
            // Don't restart if already initializing the same path
            if (!initializing || !requestedPath.equals(initializingPath)) {
                startProjectInitialization(requestedPath);
            }
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
        try (var progressIo = new AcpProgressConsole(ctx, originalIo)) {
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
                try {
                    activeCm.setIo(originalIo);
                } catch (Exception e) {
                    logger.error("Failed to restore original IO", e);
                }
            }
        }
    }

    private ModelsListResponse handleModelsList(ModelsListRequest req) {
        logger.debug("Received models/list request");
        if (cm == null) {
            return new ModelsListResponse(List.of());
        }
        var modelMap = cm.getService().getAvailableModels();
        var models = modelMap.entrySet().stream()
                .map(e -> new ModelInfo(e.getKey(), e.getValue()))
                .toList();
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

    private SessionSwitchResponse handleSwitchSession(SessionSwitchRequest req) {
        logger.debug("Received session/switch request for {}", req.sessionId());
        var activeCm = requireSession();

        if (req.sessionId() == null || req.sessionId().isBlank()) {
            throw new AcpProtocolException(AcpProtocolException.INVALID_PARAMS, "sessionId is required");
        }

        UUID sessionId;
        try {
            sessionId = UUID.fromString(req.sessionId());
        } catch (IllegalArgumentException e) {
            throw new AcpProtocolException(
                    AcpProtocolException.INVALID_PARAMS, "Invalid sessionId: " + req.sessionId());
        }

        try {
            activeCm.switchSessionAsync(sessionId).get(30, TimeUnit.SECONDS);
            logger.info("Switched to session: {}", sessionId);
            return new SessionSwitchResponse("ok", sessionId.toString());
        } catch (Exception e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.error("Error switching to session {}", sessionId, cause);
            throw new AcpProtocolException(
                    AcpProtocolException.INTERNAL_ERROR, "Failed to switch session: " + cause.getMessage());
        }
    }

    private GetConversationResponse handleGetConversation(GetConversationRequest req) {
        logger.debug("Received context/get-conversation request");
        var activeCm = requireSession();
        var taskHistory = activeCm.liveContext().getTaskHistory();
        var entries = new ArrayList<ConversationEntry>();

        for (var task : taskHistory) {
            String taskType = task.meta() != null ? task.meta().type().displayName() : null;

            var log = task.mopLog();
            List<ConversationMessage> messages = null;
            String summary = null;

            if (log != null) {
                messages = new ArrayList<>();
                for (var msg : log.messages()) {
                    String role = msg.type().name().toLowerCase(Locale.ROOT);
                    String text = Messages.getText(msg);
                    String reasoning = null;
                    if (msg instanceof AiMessage ai
                            && ai.reasoningContent() != null
                            && !ai.reasoningContent().isBlank()) {
                        reasoning = ai.reasoningContent();
                    }
                    messages.add(new ConversationMessage(role, text, reasoning));
                }
            } else if (task.summary() != null) {
                summary = task.summary();
            }

            entries.add(new ConversationEntry(task.sequence(), task.isCompressed(), taskType, messages, summary));
        }

        return new GetConversationResponse(entries);
    }
}
