package ai.brokk.acpserver.agent;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.acpserver.spec.AcpSchema.*;
import ai.brokk.acpserver.transport.AcpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AcpSyncAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AcpAgent.SyncAgentBuilder baseBuilder(MockAcpTransport transport) {
        return AcpAgent.sync(transport)
                .initializeHandler(req -> InitializeResponse.ok())
                .newSessionHandler(req -> new NewSessionResponse("s1", null, null))
                .promptHandler((req, ctx) -> PromptResponse.endTurn())
                .modelsListHandler(req -> new ModelsListResponse(List.of()))
                .contextGetHandler(req -> new ContextGetResponse(List.of()))
                .contextAddFilesHandler(req -> new ContextAddFilesResponse(List.of()))
                .contextDropHandler(req -> new ContextDropResponse(List.of()))
                .sessionsListHandler(req -> new SessionsListResponse(List.of()));
    }

    @Test
    void builderRequiresInitializeHandler() {
        var transport = new MockAcpTransport();
        var builder = AcpAgent.sync(transport)
                .newSessionHandler(req -> new NewSessionResponse("s1", null, null))
                .promptHandler((req, ctx) -> PromptResponse.endTurn())
                .modelsListHandler(req -> new ModelsListResponse(List.of()))
                .contextGetHandler(req -> new ContextGetResponse(List.of()))
                .contextAddFilesHandler(req -> new ContextAddFilesResponse(List.of()))
                .contextDropHandler(req -> new ContextDropResponse(List.of()))
                .sessionsListHandler(req -> new SessionsListResponse(List.of()));

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void builderRequiresNewSessionHandler() {
        var transport = new MockAcpTransport();
        var builder = AcpAgent.sync(transport)
                .initializeHandler(req -> InitializeResponse.ok())
                .promptHandler((req, ctx) -> PromptResponse.endTurn())
                .modelsListHandler(req -> new ModelsListResponse(List.of()))
                .contextGetHandler(req -> new ContextGetResponse(List.of()))
                .contextAddFilesHandler(req -> new ContextAddFilesResponse(List.of()))
                .contextDropHandler(req -> new ContextDropResponse(List.of()))
                .sessionsListHandler(req -> new SessionsListResponse(List.of()));

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void builderRequiresPromptHandler() {
        var transport = new MockAcpTransport();
        var builder = AcpAgent.sync(transport)
                .initializeHandler(req -> InitializeResponse.ok())
                .newSessionHandler(req -> new NewSessionResponse("s1", null, null))
                .modelsListHandler(req -> new ModelsListResponse(List.of()))
                .contextGetHandler(req -> new ContextGetResponse(List.of()))
                .contextAddFilesHandler(req -> new ContextAddFilesResponse(List.of()))
                .contextDropHandler(req -> new ContextDropResponse(List.of()))
                .sessionsListHandler(req -> new SessionsListResponse(List.of()));

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void dispatchesInitializeRequest() throws Exception {
        var transport = new MockAcpTransport();
        var initCalled = new boolean[] {false};

        baseBuilder(transport)
                .initializeHandler(req -> {
                    initCalled[0] = true;
                    assertEquals(1, req.protocolVersion());
                    return InitializeResponse.ok();
                })
                .build()
                .run();

        JsonNode params = mapper.readTree("{\"protocolVersion\":1}");
        Object result = transport.simulateRequest("initialize", params, 1);

        assertTrue(initCalled[0]);
        assertInstanceOf(InitializeResponse.class, result);
    }

    @Test
    void dispatchesNewSessionRequest() throws Exception {
        var transport = new MockAcpTransport();
        var sessionCalled = new boolean[] {false};

        baseBuilder(transport)
                .newSessionHandler(req -> {
                    sessionCalled[0] = true;
                    assertEquals("/workspace", req.workingDirectory());
                    return new NewSessionResponse("session-123", null, null);
                })
                .build()
                .run();

        JsonNode params = mapper.readTree("{\"workingDirectory\":\"/workspace\",\"context\":[]}");
        Object result = transport.simulateRequest("session/new", params, 2);

        assertTrue(sessionCalled[0]);
        assertInstanceOf(NewSessionResponse.class, result);
        assertEquals("session-123", ((NewSessionResponse) result).sessionId());
    }

    @Test
    void dispatchesPromptRequest() throws Exception {
        var transport = new MockAcpTransport();
        var promptCalled = new boolean[] {false};

        baseBuilder(transport)
                .promptHandler((req, ctx) -> {
                    promptCalled[0] = true;
                    assertEquals("session-abc", req.sessionId());
                    ctx.sendMessage("Hello!");
                    return PromptResponse.endTurn();
                })
                .build()
                .run();

        JsonNode params =
                mapper.readTree("{\"sessionId\":\"session-abc\",\"messages\":[{\"type\":\"text\",\"text\":\"Hi\"}]}");
        Object result = transport.simulateRequest("session/prompt", params, 3);

        assertTrue(promptCalled[0]);
        assertInstanceOf(PromptResponse.class, result);
        assertEquals(1, transport.getSentNotifications().size());
    }

    @Test
    void throwsOnUnknownMethod() throws Exception {
        var transport = new MockAcpTransport();

        baseBuilder(transport).build().run();

        var ex = assertThrows(AcpProtocolException.class, () -> transport.simulateRequest("unknown/method", null, 4));
        assertEquals(AcpProtocolException.METHOD_NOT_FOUND, ex.code());
    }

    @Test
    void dispatchesSessionSwitchRequest() throws Exception {
        var transport = new MockAcpTransport();
        var switchCalled = new boolean[] {false};

        baseBuilder(transport)
                .sessionSwitchHandler(req -> {
                    switchCalled[0] = true;
                    assertEquals("session-uuid", req.sessionId());
                    return new SessionSwitchResponse("ok", req.sessionId());
                })
                .build()
                .run();

        JsonNode params = mapper.readTree("{\"sessionId\":\"session-uuid\"}");
        Object result = transport.simulateRequest("session/switch", params, 5);

        assertTrue(switchCalled[0]);
        assertInstanceOf(SessionSwitchResponse.class, result);
        assertEquals("ok", ((SessionSwitchResponse) result).status());
    }

    @Test
    void dispatchesGetConversationRequest() throws Exception {
        var transport = new MockAcpTransport();
        var convCalled = new boolean[] {false};

        baseBuilder(transport)
                .getConversationHandler(req -> {
                    convCalled[0] = true;
                    return new GetConversationResponse(List.of());
                })
                .build()
                .run();

        Object result = transport.simulateRequest("context/get-conversation", null, 6);

        assertTrue(convCalled[0]);
        assertInstanceOf(GetConversationResponse.class, result);
    }

    @Test
    void sessionSwitchWithoutHandlerThrows() throws Exception {
        var transport = new MockAcpTransport();

        baseBuilder(transport).build().run();

        JsonNode params = mapper.readTree("{\"sessionId\":\"session-uuid\"}");
        var ex = assertThrows(AcpProtocolException.class, () -> transport.simulateRequest("session/switch", params, 7));
        assertEquals(AcpProtocolException.METHOD_NOT_FOUND, ex.code());
    }

    @Test
    void getConversationWithoutHandlerThrows() throws Exception {
        var transport = new MockAcpTransport();

        baseBuilder(transport).build().run();

        var ex = assertThrows(
                AcpProtocolException.class, () -> transport.simulateRequest("context/get-conversation", null, 8));
        assertEquals(AcpProtocolException.METHOD_NOT_FOUND, ex.code());
    }

    @Test
    void syncPromptContextSendsThought() throws Exception {
        var transport = new MockAcpTransport();
        var ctx = new SyncPromptContext("test-session", transport);

        ctx.sendThought("Thinking...");

        assertEquals(1, transport.getSentNotifications().size());
        var notification = transport.getSentNotifications().get(0);
        assertEquals("session/update", notification.method());
    }

    // Mock transport for testing
    private static class MockAcpTransport implements AcpTransport {
        private MessageHandler handler;
        private final List<SentNotification> sentNotifications = new ArrayList<>();

        @Override
        public void start(MessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void sendResponse(Object id, Object result) {
            // No-op for tests
        }

        @Override
        public void sendErrorResponse(Object id, int code, String message) {
            // No-op for tests
        }

        @Override
        public void sendNotification(String method, Object params) {
            sentNotifications.add(new SentNotification(method, params));
        }

        @Override
        public void close() {
            // No-op for tests
        }

        public Object simulateRequest(String method, JsonNode params, Object id) throws Exception {
            return handler.handle(method, params, id);
        }

        public List<SentNotification> getSentNotifications() {
            return sentNotifications;
        }

        record SentNotification(String method, Object params) {}
    }
}
