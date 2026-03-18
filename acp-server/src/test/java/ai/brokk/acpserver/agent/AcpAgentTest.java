package ai.brokk.acpserver.agent;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.acpserver.spec.AcpSchema.InitializeRequest;
import ai.brokk.acpserver.spec.AcpSchema.InitializeResponse;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionRequest;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionResponse;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.PromptResponse;
import ai.brokk.acpserver.spec.AcpSchema.TextContent;
import ai.brokk.acpserver.transport.AcpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AcpAgentTest {

    private ObjectMapper mapper;
    private TestTransport transport;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        transport = new TestTransport();
    }

    @Test
    void builderRequiresAllHandlers() {
        var builder = AcpAgent.sync(transport);

        assertThrows(IllegalStateException.class, builder::build);

        builder.initializeHandler(req -> InitializeResponse.ok());
        assertThrows(IllegalStateException.class, builder::build);

        builder.newSessionHandler(req -> new NewSessionResponse("session-1", null, null));
        assertThrows(IllegalStateException.class, builder::build);

        builder.promptHandler((req, ctx) -> PromptResponse.endTurn());
        assertDoesNotThrow(builder::build);
    }

    @Test
    void initializeHandlerDispatch() throws Exception {
        AtomicReference<InitializeRequest> receivedRequest = new AtomicReference<>();

        AcpSyncAgent agent = AcpAgent.sync(transport)
                .initializeHandler(req -> {
                    receivedRequest.set(req);
                    return InitializeResponse.ok();
                })
                .newSessionHandler(req -> new NewSessionResponse("s1", null, null))
                .promptHandler((req, ctx) -> PromptResponse.endTurn())
                .build();

        JsonNode params = mapper.readTree("{\"protocolVersion\":1,\"capabilities\":{\"readTextFile\":true,\"writeTextFile\":false}}");
        transport.simulateRequest("initialize", params, 1, agent);

        assertNotNull(receivedRequest.get());
        assertEquals(1, receivedRequest.get().protocolVersion());

        assertEquals(1, transport.responses.size());
        var response = transport.responses.getFirst();
        assertEquals(1, response.id());
        assertInstanceOf(InitializeResponse.class, response.result());
    }

    @Test
    void newSessionHandlerDispatch() throws Exception {
        AtomicReference<NewSessionRequest> receivedRequest = new AtomicReference<>();

        AcpSyncAgent agent = AcpAgent.sync(transport)
                .initializeHandler(req -> InitializeResponse.ok())
                .newSessionHandler(req -> {
                    receivedRequest.set(req);
                    return new NewSessionResponse("session-123", null, null);
                })
                .promptHandler((req, ctx) -> PromptResponse.endTurn())
                .build();

        JsonNode params = mapper.readTree("{\"workingDirectory\":\"/test/path\",\"context\":[{\"type\":\"text\",\"text\":\"Hello\"}]}");
        transport.simulateRequest("session/new", params, 2, agent);

        assertNotNull(receivedRequest.get());
        assertEquals("/test/path", receivedRequest.get().workingDirectory());
        assertEquals(1, receivedRequest.get().context().size());
        assertEquals("Hello", receivedRequest.get().context().getFirst().text());

        assertEquals(1, transport.responses.size());
        var response = (NewSessionResponse) transport.responses.getFirst().result();
        assertEquals("session-123", response.sessionId());
    }

    @Test
    void promptHandlerDispatchWithContext() throws Exception {
        AtomicReference<PromptRequest> receivedRequest = new AtomicReference<>();
        AtomicReference<SyncPromptContext> receivedContext = new AtomicReference<>();

        AcpSyncAgent agent = AcpAgent.sync(transport)
                .initializeHandler(req -> InitializeResponse.ok())
                .newSessionHandler(req -> new NewSessionResponse("s1", null, null))
                .promptHandler((req, ctx) -> {
                    receivedRequest.set(req);
                    receivedContext.set(ctx);
                    ctx.sendMessage("Processing...");
                    ctx.sendThought("Thinking about this...");
                    return PromptResponse.endTurn();
                })
                .build();

        JsonNode params = mapper.readTree("{\"sessionId\":\"session-abc\",\"messages\":[{\"type\":\"text\",\"text\":\"Hello agent\"}]}");
        transport.simulateRequest("session/prompt", params, 3, agent);

        assertNotNull(receivedRequest.get());
        assertEquals("session-abc", receivedRequest.get().sessionId());
        assertEquals(1, receivedRequest.get().messages().size());
        assertInstanceOf(TextContent.class, receivedRequest.get().messages().getFirst());

        assertNotNull(receivedContext.get());
        assertEquals("session-abc", receivedContext.get().sessionId());

        // Verify streaming notifications were sent
        assertEquals(2, transport.notifications.size());
        assertEquals("session/update", transport.notifications.get(0).method());
        assertEquals("session/update", transport.notifications.get(1).method());

        // Verify response
        assertEquals(1, transport.responses.size());
        assertInstanceOf(PromptResponse.class, transport.responses.getFirst().result());
    }

    @Test
    void unknownMethodThrowsException() throws Exception {
        AcpSyncAgent agent = AcpAgent.sync(transport)
                .initializeHandler(req -> InitializeResponse.ok())
                .newSessionHandler(req -> new NewSessionResponse("s1", null, null))
                .promptHandler((req, ctx) -> PromptResponse.endTurn())
                .build();

        JsonNode params = mapper.createObjectNode();
        
        assertThrows(AcpProtocolException.class, () -> {
            transport.simulateRequest("unknown/method", params, 4, agent);
        });
    }

    @Test
    void syncPromptContextSendsCorrectNotifications() {
        SyncPromptContext ctx = new SyncPromptContext("test-session", transport);

        ctx.sendMessage("Hello world");
        ctx.sendThought("Reasoning step 1");

        assertEquals(2, transport.notifications.size());
        
        // Both should be session/update notifications
        assertEquals("session/update", transport.notifications.get(0).method());
        assertEquals("session/update", transport.notifications.get(1).method());
    }

    /**
     * Test transport that records calls for verification.
     */
    static class TestTransport implements AcpTransport {
        final List<ResponseRecord> responses = new ArrayList<>();
        final List<NotificationRecord> notifications = new ArrayList<>();
        private @Nullable MessageHandler handler;

        @Override
        public void start(MessageHandler handler) {
            this.handler = handler;
        }

        void simulateRequest(String method, JsonNode params, Object id, AcpSyncAgent agent) throws Exception {
            // Access the handler through reflection-free approach: 
            // We need to invoke the handler that was registered during start()
            if (handler == null) {
                // Start the agent in a way that registers the handler but doesn't block
                agent.run();
            }
            if (handler != null) {
                Object result = handler.handle(method, params, id);
                if (id != null && result != null) {
                    responses.add(new ResponseRecord(id, result));
                }
            }
        }

        @Override
        public void sendResponse(Object id, Object result) {
            responses.add(new ResponseRecord(id, result));
        }

        @Override
        public void sendErrorResponse(Object id, int code, String message) {
            responses.add(new ResponseRecord(id, new ErrorResult(code, message)));
        }

        @Override
        public void sendNotification(String method, Object params) {
            notifications.add(new NotificationRecord(method, params));
        }

        @Override
        public void close() {
            // no-op
        }

        record ResponseRecord(Object id, Object result) {}
        record NotificationRecord(String method, Object params) {}
        record ErrorResult(int code, String message) {}
    }
}
