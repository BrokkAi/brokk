package ai.brokk.acp;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.TypeRef;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

/** Explicit ACP method router for Brokk's native Java ACP server. */
final class BrokkAcpRuntime implements AutoCloseable {
    private final AcpAgentTransport transport;
    private final BrokkAcpAgent agent;
    private final AtomicReference<NegotiatedCapabilities> clientCapabilities = new AtomicReference<>();
    private final AcpAgentSession session;

    BrokkAcpRuntime(AcpAgentTransport transport, BrokkAcpAgent agent) {
        this.transport = transport;
        this.agent = agent;
        this.session = new AcpAgentSession(Duration.ofMinutes(5), transport, requestHandlers(), notificationHandlers());
        agent.setSessionUpdateSender(
                (sessionId, update) -> AcpRequestContext.sendSessionUpdate(session, sessionId, update));
    }

    void run() {
        transport.awaitTermination().block();
    }

    private Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers() {
        var handlers = new HashMap<String, AcpAgentSession.RequestHandler<?>>();
        handlers.put(AcpSchema.METHOD_INITIALIZE, params -> {
            var request = unmarshal(params, new TypeRef<AcpSchema.InitializeRequest>() {});
            clientCapabilities.set(NegotiatedCapabilities.fromClient(request.clientCapabilities()));
            return Mono.fromCallable(() -> agent.initialize());
        });
        handlers.put(AcpSchema.METHOD_AUTHENTICATE, params -> Mono.fromCallable(agent::authenticate));
        handlers.put(AcpSchema.METHOD_SESSION_NEW, params -> {
            var request = unmarshal(params, new TypeRef<AcpSchema.NewSessionRequest>() {});
            return Mono.fromCallable(() -> agent.newSession(request));
        });
        handlers.put(AcpSchema.METHOD_SESSION_LOAD, params -> {
            var request = unmarshal(params, new TypeRef<AcpSchema.LoadSessionRequest>() {});
            return Mono.fromCallable(() -> agent.loadSession(request));
        });
        handlers.put(AcpProtocol.METHOD_SESSION_LIST, params -> {
            var request = unmarshal(params, new TypeRef<AcpProtocol.ListSessionsRequest>() {});
            return Mono.fromCallable(() -> agent.listSessions(request));
        });
        handlers.put(AcpProtocol.METHOD_SESSION_RESUME, params -> {
            var request = unmarshal(params, new TypeRef<AcpProtocol.ResumeSessionRequest>() {});
            return Mono.fromCallable(() -> agent.resumeSession(request));
        });
        handlers.put(AcpProtocol.METHOD_SESSION_CLOSE, params -> {
            var request = unmarshal(params, new TypeRef<AcpProtocol.CloseSessionRequest>() {});
            return Mono.fromCallable(() -> agent.closeSession(request));
        });
        handlers.put(AcpProtocol.METHOD_SESSION_FORK, params -> {
            var request = unmarshal(params, new TypeRef<AcpProtocol.ForkSessionRequest>() {});
            return Mono.fromCallable(() -> agent.forkSession(request));
        });
        handlers.put(AcpSchema.METHOD_SESSION_PROMPT, params -> {
            var request = unmarshal(params, new TypeRef<AcpSchema.PromptRequest>() {});
            var context = new AcpRequestContext(session, request.sessionId(), clientCapabilities.get());
            return Mono.fromCallable(() -> agent.prompt(request, context));
        });
        handlers.put(AcpSchema.METHOD_SESSION_SET_MODE, params -> {
            var request = unmarshal(params, new TypeRef<AcpSchema.SetSessionModeRequest>() {});
            return Mono.fromCallable(() -> agent.setMode(request));
        });
        handlers.put(AcpSchema.METHOD_SESSION_SET_MODEL, params -> {
            var request = unmarshal(params, new TypeRef<AcpSchema.SetSessionModelRequest>() {});
            return Mono.fromCallable(() -> agent.setModel(request));
        });
        return handlers;
    }

    private Map<String, AcpAgentSession.NotificationHandler> notificationHandlers() {
        return Map.of(AcpSchema.METHOD_SESSION_CANCEL, params -> {
            var notification = unmarshal(params, new TypeRef<AcpSchema.CancelNotification>() {});
            return Mono.fromRunnable(() -> agent.cancel(notification));
        });
    }

    private <T> T unmarshal(Object params, TypeRef<T> typeRef) {
        return transport.unmarshalFrom(params, typeRef);
    }

    @Override
    public void close() {
        session.close();
    }
}
