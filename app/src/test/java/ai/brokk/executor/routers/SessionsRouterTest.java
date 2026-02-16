package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.SessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for SessionsRouter.
 */
class SessionsRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionsRouter router;
    private TestContextManager fakeCm;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        sessionManager = new SessionManager(tmp.resolve("sessions"));
        fakeCm = new TestContextManager();
        router = new SessionsRouter(fakeCm, sessionManager, v -> {});
    }

    @Test
    void testGetSessions_returnsListWithStructure() throws Exception {
        var s1 = sessionManager.newSession("One");
        var s2 = sessionManager.newSession("Two");

        // mark s2 as current
        fakeCm.setCurrentId(s2.id());

        TestHttpExchange ex = TestHttpExchange.request("GET", "/v1/sessions");
        router.handle(ex);

        assertEquals(200, ex.getResponseCode());
        JsonNode root = MAPPER.readTree(ex.responseBodyBytes());
        assertTrue(root.has("sessions"));
        JsonNode arr = root.get("sessions");
        assertTrue(arr.isArray());
        assertTrue(arr.size() >= 2);

        boolean foundActive = false;
        for (JsonNode item : arr) {
            assertTrue(item.has("id"));
            assertTrue(item.has("name"));
            assertTrue(item.has("created"));
            assertTrue(item.has("modified"));
            assertTrue(item.has("active"));
            assertTrue(item.has("versionSupported"));
            if (item.get("id").asText().equals(s2.id().toString())) {
                assertTrue(item.get("active").asBoolean());
                foundActive = true;
            }
        }
        assertTrue(foundActive, "Expected one item to be active matching currentId");
    }

    @Test
    void testDeleteSession_existingId_invokesContextManagerAndReturns204() throws Exception {
        var s = sessionManager.newSession("ToDelete");
        UUID id = s.id();

        TestHttpExchange ex = TestHttpExchange.request("DELETE", "/v1/sessions/" + id.toString());
        router.handle(ex);

        assertEquals(204, ex.getResponseCode());
        assertTrue(fakeCm.deleted.contains(id));
    }

    @Test
    void testDeleteSession_invalidUuid_returns400() throws Exception {
        TestHttpExchange ex = TestHttpExchange.request("DELETE", "/v1/sessions/not-a-uuid");
        router.handle(ex);

        assertEquals(400, ex.getResponseCode());
        JsonNode err = MAPPER.readTree(ex.responseBodyBytes());
        assertEquals("VALIDATION_ERROR", err.get("code").asText());
    }

    @Test
    void testDeleteSession_nonexistentId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        TestHttpExchange ex = TestHttpExchange.request("DELETE", "/v1/sessions/" + id.toString());
        router.handle(ex);

        assertEquals(404, ex.getResponseCode());
        JsonNode err = MAPPER.readTree(ex.responseBodyBytes());
        assertEquals("NOT_FOUND", err.get("code").asText());
    }

    @Test
    void testGetSessions_methodNotAllowedForPostOnList() throws Exception {
        TestHttpExchange ex = TestHttpExchange.request(
                "POST", "/v1/sessions/" + UUID.randomUUID().toString());
        router.handle(ex);

        assertEquals(405, ex.getResponseCode());
        JsonNode err = MAPPER.readTree(ex.responseBodyBytes());
        assertEquals("METHOD_NOT_ALLOWED", err.get("code").asText());
    }

    /**
     * Minimal fake ContextManager providing only what the SessionsRouter needs.
     */
    static final class TestContextManager extends ContextManager {
        private UUID currentId;
        final List<UUID> deleted = new ArrayList<>();

        TestContextManager() {
            super(new ai.brokk.project.IProject() {});
        }

        void setCurrentId(UUID id) {
            this.currentId = id;
        }

        @Override
        public UUID getCurrentSessionId() {
            return currentId;
        }

        @Override
        public CompletableFuture<Void> deleteSessionAsync(UUID sessionIdToDelete) {
            deleted.add(sessionIdToDelete);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Lightweight HttpExchange for tests (pattern from other router tests).
     */
    private static final class TestHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private String method = "GET";
        private URI uri = URI.create("/");
        private byte[] requestBodyBytes = new byte[0];
        private int responseCode = -1;

        static TestHttpExchange jsonRequest(String method, String path, Object body) throws Exception {
            TestHttpExchange ex = new TestHttpExchange();
            ex.method = method;
            ex.uri = URI.create(path);
            ex.requestHeaders.add("Content-Type", "application/json");
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            ex.requestBodyBytes = bytes;
            return ex;
        }

        static TestHttpExchange request(String method, String path) {
            TestHttpExchange ex = new TestHttpExchange();
            ex.method = method;
            ex.uri = URI.create(path);
            if ("POST".equals(method) || "PUT".equals(method)) {
                ex.requestHeaders.add("Content-Type", "application/json");
            }
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
            return new ByteArrayInputStream(requestBodyBytes == null ? new byte[0] : requestBodyBytes);
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
