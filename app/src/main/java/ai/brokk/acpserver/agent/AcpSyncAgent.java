package ai.brokk.acpserver.agent;

import ai.brokk.acpserver.spec.AcpSchema.CancelRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextAddFilesRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextAddFilesResponse;
import ai.brokk.acpserver.spec.AcpSchema.ContextDropRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextDropResponse;
import ai.brokk.acpserver.spec.AcpSchema.ContextGetRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextGetResponse;
import ai.brokk.acpserver.spec.AcpSchema.GetConversationRequest;
import ai.brokk.acpserver.spec.AcpSchema.GetConversationResponse;
import ai.brokk.acpserver.spec.AcpSchema.InitializeRequest;
import ai.brokk.acpserver.spec.AcpSchema.InitializeResponse;
import ai.brokk.acpserver.spec.AcpSchema.ModelsListRequest;
import ai.brokk.acpserver.spec.AcpSchema.ModelsListResponse;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionRequest;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionResponse;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.PromptResponse;
import ai.brokk.acpserver.spec.AcpSchema.SessionSwitchRequest;
import ai.brokk.acpserver.spec.AcpSchema.SessionSwitchResponse;
import ai.brokk.acpserver.spec.AcpSchema.SessionsListRequest;
import ai.brokk.acpserver.spec.AcpSchema.SessionsListResponse;
import ai.brokk.acpserver.transport.AcpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Synchronous ACP agent implementation with async prompt support.
 * <p>
 * Handles incoming JSON-RPC requests by dispatching to registered handlers.
 * The {@code session/prompt} handler runs on a separate thread so the main
 * transport loop remains free to process {@code session/cancel} requests.
 */
public class AcpSyncAgent {

    private static final Logger logger = LogManager.getLogger(AcpSyncAgent.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AcpTransport transport;
    private final Function<InitializeRequest, InitializeResponse> initializeHandler;
    private final Function<NewSessionRequest, NewSessionResponse> newSessionHandler;
    private final BiFunction<PromptRequest, SyncPromptContext, PromptResponse> promptHandler;
    private final Function<ModelsListRequest, ModelsListResponse> modelsListHandler;
    private final Function<ContextGetRequest, ContextGetResponse> contextGetHandler;
    private final Function<ContextAddFilesRequest, ContextAddFilesResponse> contextAddFilesHandler;
    private final Function<ContextDropRequest, ContextDropResponse> contextDropHandler;
    private final Function<SessionsListRequest, SessionsListResponse> sessionsListHandler;
    private final Consumer<CancelRequest> cancelHandler;
    private final @Nullable Function<SessionSwitchRequest, SessionSwitchResponse> sessionSwitchHandler;
    private final @Nullable Function<GetConversationRequest, GetConversationResponse> getConversationHandler;

    private volatile @Nullable Thread promptThread;

    AcpSyncAgent(
            AcpTransport transport,
            Function<InitializeRequest, InitializeResponse> initializeHandler,
            Function<NewSessionRequest, NewSessionResponse> newSessionHandler,
            BiFunction<PromptRequest, SyncPromptContext, PromptResponse> promptHandler,
            Function<ModelsListRequest, ModelsListResponse> modelsListHandler,
            Function<ContextGetRequest, ContextGetResponse> contextGetHandler,
            Function<ContextAddFilesRequest, ContextAddFilesResponse> contextAddFilesHandler,
            Function<ContextDropRequest, ContextDropResponse> contextDropHandler,
            Function<SessionsListRequest, SessionsListResponse> sessionsListHandler,
            Consumer<CancelRequest> cancelHandler,
            @Nullable Function<SessionSwitchRequest, SessionSwitchResponse> sessionSwitchHandler,
            @Nullable Function<GetConversationRequest, GetConversationResponse> getConversationHandler) {
        this.transport = transport;
        this.initializeHandler = initializeHandler;
        this.newSessionHandler = newSessionHandler;
        this.promptHandler = promptHandler;
        this.modelsListHandler = modelsListHandler;
        this.contextGetHandler = contextGetHandler;
        this.contextAddFilesHandler = contextAddFilesHandler;
        this.contextDropHandler = contextDropHandler;
        this.sessionsListHandler = sessionsListHandler;
        this.cancelHandler = cancelHandler;
        this.sessionSwitchHandler = sessionSwitchHandler;
        this.getConversationHandler = getConversationHandler;
    }

    /**
     * Starts the agent and blocks until the transport closes.
     */
    public void run() {
        transport.start(this::handleMessage);
    }

    /**
     * Signals the agent to stop.
     */
    public void shutdown() {
        transport.close();
    }

    private Object handleMessage(String method, @Nullable JsonNode params, @Nullable Object id) {
        return switch (method) {
            case "initialize" -> {
                if (params == null) {
                    throw new AcpProtocolException(
                            AcpProtocolException.INVALID_PARAMS, "Missing params for initialize");
                }
                var req = parseAs(params, InitializeRequest.class);
                yield initializeHandler.apply(req);
            }
            case "session/new" -> {
                if (params == null) {
                    throw new AcpProtocolException(
                            AcpProtocolException.INVALID_PARAMS, "Missing params for session/new");
                }
                var req = parseAs(params, NewSessionRequest.class);
                yield newSessionHandler.apply(req);
            }
            case "session/prompt" -> {
                if (params == null) {
                    throw new AcpProtocolException(
                            AcpProtocolException.INVALID_PARAMS, "Missing params for session/prompt");
                }
                var req = parseAs(params, PromptRequest.class);
                var ctx = new SyncPromptContext(req.sessionId(), transport);
                // Run prompt synchronously — the transport handles threading if needed
                try {
                    promptThread = Thread.currentThread();
                    yield promptHandler.apply(req, ctx);
                } finally {
                    promptThread = null;
                }
            }
            case "session/cancel" -> {
                var req = params != null ? parseAs(params, CancelRequest.class) : new CancelRequest(null);
                cancelHandler.accept(req);
                yield null;
            }
            case "models/list" -> {
                var req = params != null ? parseAs(params, ModelsListRequest.class) : new ModelsListRequest();
                yield modelsListHandler.apply(req);
            }
            case "context/get" -> {
                var req = params != null ? parseAs(params, ContextGetRequest.class) : new ContextGetRequest();
                yield contextGetHandler.apply(req);
            }
            case "context/add-files" -> {
                if (params == null) {
                    throw new AcpProtocolException(
                            AcpProtocolException.INVALID_PARAMS, "Missing params for context/add-files");
                }
                var req = parseAs(params, ContextAddFilesRequest.class);
                yield contextAddFilesHandler.apply(req);
            }
            case "context/drop" -> {
                if (params == null) {
                    throw new AcpProtocolException(
                            AcpProtocolException.INVALID_PARAMS, "Missing params for context/drop");
                }
                var req = parseAs(params, ContextDropRequest.class);
                yield contextDropHandler.apply(req);
            }
            case "sessions/list" -> {
                var req = params != null ? parseAs(params, SessionsListRequest.class) : new SessionsListRequest();
                yield sessionsListHandler.apply(req);
            }
            case "session/switch" -> {
                if (sessionSwitchHandler == null) {
                    throw new AcpProtocolException("Method not found: " + method);
                }
                if (params == null) {
                    throw new AcpProtocolException(
                            AcpProtocolException.INVALID_PARAMS, "Missing params for session/switch");
                }
                var req = parseAs(params, SessionSwitchRequest.class);
                yield sessionSwitchHandler.apply(req);
            }
            case "context/get-conversation" -> {
                if (getConversationHandler == null) {
                    throw new AcpProtocolException("Method not found: " + method);
                }
                var req = params != null ? parseAs(params, GetConversationRequest.class) : new GetConversationRequest();
                yield getConversationHandler.apply(req);
            }
            default -> throw new AcpProtocolException("Method not found: " + method);
        };
    }

    /**
     * Interrupts the thread currently executing a prompt, if any.
     */
    public void interruptPrompt() {
        var thread = promptThread;
        if (thread != null) {
            logger.info("Interrupting prompt thread");
            thread.interrupt();
        }
    }

    private <T> T parseAs(JsonNode params, Class<T> type) {
        try {
            return mapper.treeToValue(params, type);
        } catch (Exception e) {
            throw new AcpProtocolException(
                    AcpProtocolException.INVALID_PARAMS, "Failed to parse parameters: " + e.getMessage());
        }
    }
}
