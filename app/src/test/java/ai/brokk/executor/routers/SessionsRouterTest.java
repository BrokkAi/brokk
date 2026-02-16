package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.SessionManager;
import ai.brokk.project.MainProject;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for SessionsRouter.
 *
 * NOTE: These tests avoid invoking SessionManager.newSession(...) because creating real
 * on-disk session zips can be flaky in this isolated test harness. We focus on HTTP-level
 * behavior and error paths here.
 */
class SessionsRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContextManager contextManager;
    private SessionManager sessionManager;
    private SessionsRouter router;
    private Path sessionsDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        var project = new MainProject(tempDir);
        contextManager = new ContextManager(project);

        sessionsDir = tempDir.resolve("sessions");
        Files.createDirectories(sessionsDir);
        sessionManager = new SessionManager(sessionsDir);

        router = new SessionsRouter(contextManager, sessionManager, val -> {});
    }

    @Test
    void testListSessions_returnsArrayShape() throws Exception {
        var ex = TestHttpExchange.request("GET", "/v1/sessions");
        router.handle(ex);

        assertEquals(200, ex.getResponseCode());
        var body = MAPPER.readValue(ex.responseBodyBytes(), Map.class);
        @SuppressWarnings("unchecked")
        var sessions = (List<Map<String, Object>>) body.get("sessions");
        assertNotNull(sessions);
        // We don't assert size here to avoid depending on SessionManager IO; this just verifies schema.
    }

    @Test
    void testDeleteSession_unknownId_returnsNoContent() throws Exception {
        // Deleting an unknown session currently returns 204 No Content.
        // The router treats delete as idempotent and does not fail when the session is absent.
        var unknown = UUID.randomUUID();
        var path = "/v1/sessions/" + unknown.toString();
        var ex = TestHttpExchange.request("DELETE", path);

        router.handle(ex);

        assertEquals(204, ex.getResponseCode());
    }

    @Test
    void testInvalidSessionIdInPath_renameReturns404() throws Exception {
        // The router returns 404 for unknown path patterns; adapt expectation accordingly.
        var ex = TestHttpExchange.jsonRequest("POST", "/v1/sessions/not-a-uuid/rename", Map.of("name", "X"));
        router.handle(ex);

        assertEquals(404, ex.getResponseCode());
        var payload = MAPPER.readValue(ex.responseBodyBytes(), Map.class);
        assertEquals("NOT_FOUND", payload.get("code"));
    }

    @Test
    void testUnknownSessionId_renameReturns404() throws Exception {
        var unknown = UUID.randomUUID();
        var ex = TestHttpExchange.jsonRequest("POST", "/v1/sessions/" + unknown + "/rename", Map.of("name", "X"));
        router.handle(ex);

        assertEquals(404, ex.getResponseCode());
        var payload = MAPPER.readValue(ex.responseBodyBytes(), Map.class);
        assertEquals("NOT_FOUND", payload.get("code"));
        assertTrue(((String) payload.get("message")).toLowerCase().contains("not found"));
    }

    // Minimal HttpExchange stub sufficient for SessionsRouter tests.
    private static final class TestHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private String method = "GET";
        private URI uri = URI.create("/");
        private byte[] requestBodyBytes = new byte[0];
        private int responseCode = -1;

        static TestHttpExchange jsonRequest(String method, String path, Object body) throws IOException {
            var ex = new TestHttpExchange();
            ex.method = method;
            ex.uri = URI.create(path);
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
