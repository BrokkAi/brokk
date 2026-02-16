package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.context.Context;
import ai.brokk.context.ContextHistory;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for ContextHistoryRouter endpoints using a lightweight TestHttpExchange harness.
 */
class ContextHistoryRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContextHistoryRouter router;
    private FakeContextManager fakeContextManager;

    @BeforeEach
    void setUp() {
        fakeContextManager = new FakeContextManager();
        router = new ContextHistoryRouter(fakeContextManager);
    }

    @Test
    void postUndo_successfulUndo_returnsWasUndoneTrue() throws Exception {
        fakeContextManager.setHasUndo(true);

        TestHttpExchange exchange = TestHttpExchange.request("POST", "/v1/context/undo");
        router.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        var resp = MAPPER.readTree(exchange.responseBodyBytes());
        assertTrue(resp.get("wasUndone").asBoolean());
        assertFalse(resp.get("hasMoreUndo").asBoolean());
    }

    @Test
    void postUndo_methodNotAllowed_returns405() throws Exception {
        TestHttpExchange exchange = TestHttpExchange.request("GET", "/v1/context/undo");
        router.handle(exchange);

        assertEquals(405, exchange.getResponseCode());
        var err = MAPPER.readTree(exchange.responseBodyBytes());
        assertEquals("METHOD_NOT_ALLOWED", err.get("code").asText());
    }

    @Test
    void postRedo_noRedoAvailable_returnsWasRedoneFalse() throws Exception {
        fakeContextManager.setHasRedo(false);

        TestHttpExchange exchange = TestHttpExchange.request("POST", "/v1/context/redo");
        router.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        var resp = MAPPER.readTree(exchange.responseBodyBytes());
        assertFalse(resp.get("wasRedone").asBoolean());
        assertFalse(resp.get("hasMoreRedo").asBoolean());
    }

    @Test
    void postRedo_withRedoAvailable_triggersRedo_andReturnsTrue() throws Exception {
        fakeContextManager.setHasRedo(true);

        TestHttpExchange exchange = TestHttpExchange.request("POST", "/v1/context/redo");
        router.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        var resp = MAPPER.readTree(exchange.responseBodyBytes());
        assertTrue(resp.get("wasRedone").asBoolean());
    }

    @Test
    void postRedo_methodNotAllowed_returns405() throws Exception {
        TestHttpExchange exchange = TestHttpExchange.request("GET", "/v1/context/redo");
        router.handle(exchange);

        assertEquals(405, exchange.getResponseCode());
        var err = MAPPER.readTree(exchange.responseBodyBytes());
        assertEquals("METHOD_NOT_ALLOWED", err.get("code").asText());
    }

    @Test
    void unknownPath_returns404() throws Exception {
        TestHttpExchange exchange = TestHttpExchange.request("POST", "/v1/context/unknown");
        router.handle(exchange);

        assertEquals(404, exchange.getResponseCode());
        var err = MAPPER.readTree(exchange.responseBodyBytes());
        assertEquals("NOT_FOUND", err.get("code").asText());
    }

    /**
     * Minimal fake ContextManager to drive the router logic without requiring a full project.
     */
    static final class FakeContextManager extends ContextManager {
        private final AtomicBoolean hasUndo = new AtomicBoolean(false);
        private final AtomicBoolean hasRedo = new AtomicBoolean(false);
        private final FakeContextHistory fakeHistory;

        FakeContextManager() {
            super(new ai.brokk.project.IProject() {});
            this.fakeHistory = new FakeContextHistory(hasUndo, hasRedo);
        }

        void setHasUndo(boolean v) {
            hasUndo.set(v);
        }

        void setHasRedo(boolean v) {
            hasRedo.set(v);
        }

        @Override
        public Future<?> redoContextAsync() {
            return CompletableFuture.runAsync(() -> hasRedo.set(false));
        }

        @Override
        public Future<?> undoContextAsync() {
            return CompletableFuture.runAsync(() -> hasUndo.set(false));
        }

        @Override
        public ContextHistory getContextHistory() {
            return fakeHistory;
        }
    }

    /**
     * Minimal ContextHistory that just tracks undo/redo availability via atomic flags.
     */
    static final class FakeContextHistory extends ContextHistory {
        private final AtomicBoolean hasUndo;
        private final AtomicBoolean hasRedo;

        FakeContextHistory(AtomicBoolean hasUndo, AtomicBoolean hasRedo) {
            super(Context.EMPTY);
            this.hasUndo = hasUndo;
            this.hasRedo = hasRedo;
        }

        @Override
        public synchronized boolean hasUndoStates() {
            return hasUndo.get();
        }

        @Override
        public synchronized boolean hasRedoStates() {
            return hasRedo.get();
        }
    }

    /**
     * Lightweight HttpExchange for tests (pattern from ContextRouterTest).
     */
    private static final class TestHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private String method = "GET";
        private URI uri = URI.create("/");
        private byte[] requestBodyBytes = new byte[0];
        private int responseCode = -1;

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
