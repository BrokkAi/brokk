package ai.brokk.acpserver.agent;

import ai.brokk.acpserver.spec.AcpSchema.InitializeRequest;
import ai.brokk.acpserver.spec.AcpSchema.InitializeResponse;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionRequest;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionResponse;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.PromptResponse;
import ai.brokk.acpserver.transport.AcpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Synchronous ACP agent implementation.
 * <p>
 * Handles incoming JSON-RPC requests by dispatching to registered handlers.
 * The agent blocks on {@link #run()} until the transport closes.
 */
public class AcpSyncAgent {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AcpTransport transport;
    private final Function<InitializeRequest, InitializeResponse> initializeHandler;
    private final Function<NewSessionRequest, NewSessionResponse> newSessionHandler;
    private final BiFunction<PromptRequest, SyncPromptContext, PromptResponse> promptHandler;

    AcpSyncAgent(
            AcpTransport transport,
            Function<InitializeRequest, InitializeResponse> initializeHandler,
            Function<NewSessionRequest, NewSessionResponse> newSessionHandler,
            BiFunction<PromptRequest, SyncPromptContext, PromptResponse> promptHandler) {
        this.transport = transport;
        this.initializeHandler = initializeHandler;
        this.newSessionHandler = newSessionHandler;
        this.promptHandler = promptHandler;
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
                yield promptHandler.apply(req, ctx);
            }
            default -> throw new AcpProtocolException("Method not found: " + method);
        };
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
