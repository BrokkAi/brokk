package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionsRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionsRouter sessionsRouter;
    private ContextManager contextManager;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        var project = new MainProject(tempDir);
        contextManager = new ContextManager(project);
        var sessionManager = project.getSessionManager();
        sessionsRouter = new SessionsRouter(contextManager, sessionManager, loaded -> {});
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleListSessions_returnsSessionsList() throws Exception {
        // Create a session first
        contextManager.createSessionAsync("Test Session").get(5, TimeUnit.SECONDS);

        var exchange = TestHttpExchange.request("GET", "/v1/sessions");
        sessionsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.containsKey("sessions"));
        assertTrue(body.containsKey("currentSessionId"));

        List<Map<String, Object>> sessions = (List<Map<String, Object>>) body.get("sessions");
        assertFalse(sessions.isEmpty(), "Should have at least one session");

        var firstSession = sessions.get(0);
        assertNotNull(firstSession.get("id"));
        assertNotNull(firstSession.get("name"));
        assertTrue(firstSession.containsKey("created"));
        assertTrue(firstSession.containsKey("modified"));
        assertTrue(firstSession.containsKey("current"));
    }

    @Test
    void handleSwitchSession_invalidId_returns400() throws Exception {
        var body = Map.of("sessionId", "not-a-uuid");
        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/sessions/switch", body);
        sessionsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
    }

    @Test
    void handleSwitchSession_blankId_returns400() throws Exception {
        var body = Map.of("sessionId", "");
        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/sessions/switch", body);
        sessionsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertTrue(payload.message().contains("sessionId is required"));
    }

    @Test
    void handleRenameSession_blankName_returns400() throws Exception {
        var body = Map.of("sessionId", "00000000-0000-0000-0000-000000000001", "name", "");
        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/sessions/rename", body);
        sessionsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertTrue(payload.message().contains("name is required"));
    }

    @Test
    void handleDeleteSession_invalidId_returns400() throws Exception {
        var exchange = TestHttpExchange.request("DELETE", "/v1/sessions/not-a-uuid");
        sessionsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
    }

    @Test
    void handleMethodNotAllowed_returns405() throws Exception {
        var exchange = TestHttpExchange.request("PATCH", "/v1/sessions");
        sessionsRouter.handle(exchange);

        assertEquals(405, exchange.responseCode());
    }

    private static final class TestHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private String method = "GET";
        private URI uri = URI.create("/");
        private byte[] requestBodyBytes = new byte[0];
        private int responseCode = -1;

        static TestHttpExchange jsonRequest(String method, String path, Object body) throws IOException {
            var ex = request(method, path);
            ex.requestHeaders.set("Content-Type", "application/json");
            ex.requestBodyBytes = MAPPER.writeValueAsBytes(body);
            return ex;
        }

        static TestHttpExchange request(String method, String path) {
            var ex = new TestHttpExchange();
            ex.method = method;
            ex.uri = URI.create(path);
            return ex;
        }

        int responseCode() {
            return responseCode;
        }

        byte[] responseBodyBytes() {
            return responseBody.toByteArray();
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(requestBodyBytes);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {}

        @Override
        public void setStreams(InputStream i, OutputStream o) {}

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
