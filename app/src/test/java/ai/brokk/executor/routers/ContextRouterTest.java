package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.context.ContextFragment;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.MainProject;
import ai.brokk.tasks.TaskList;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContextRouter contextRouter;
    private Path projectRoot;

    private ContextManager contextManager;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        projectRoot = tempDir;
        var project = new MainProject(tempDir);
        contextManager = new ContextManager(project);
        contextRouter = new ContextRouter(contextManager);
    }

    @Test
    void handleGetTaskList_noTaskList_returnsEmpty() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/tasklist");
        contextRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.containsKey("bigPicture"));
        assertEquals(null, body.get("bigPicture"));
        assertTrue(body.get("tasks") instanceof List);
        assertTrue(((List<?>) body.get("tasks")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleGetTaskList_withTasks_returnsBigPictureAndTasks() throws Exception {
        var task1 = new TaskList.TaskItem("T1", "Text 1", false);
        var task2 = new TaskList.TaskItem("T2", "Text 2", true);
        var tasks = List.of(task1, task2);

        // createOrReplaceTaskList returns a new context; we must push it to make it live
        contextManager.pushContext(ctx -> contextManager.createOrReplaceTaskList(ctx, "The Big Picture", tasks));

        var exchange = TestHttpExchange.request("GET", "/v1/tasklist");
        contextRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertEquals("The Big Picture", body.get("bigPicture"));
        List<Map<String, Object>> returnedTasks = (List<Map<String, Object>>) body.get("tasks");
        assertEquals(2, returnedTasks.size());

        assertEquals("T1", returnedTasks.get(0).get("title"));
        assertEquals(false, returnedTasks.get(0).get("done"));
        assertEquals("T2", returnedTasks.get(1).get("title"));
        assertEquals(true, returnedTasks.get(1).get("done"));
        assertTrue(!((String) returnedTasks.get(0).get("id")).isBlank());
    }

    @Test
    void handleGetContext_tokensTrue_returnsExpectedKeys() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/context?tokens=true");
        contextRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertTrue(body.containsKey("fragments"), "Should contain fragments key");
        assertTrue(body.containsKey("usedTokens"), "Should contain usedTokens key");
        assertTrue(body.containsKey("maxTokens"), "Should contain maxTokens key");
        assertTrue(body.containsKey("tokensEstimated"), "Should contain tokensEstimated key");

        assertEquals(Boolean.TRUE, body.get("tokensEstimated"));
        assertTrue(body.get("fragments") instanceof List, "fragments should be a List");
        assertTrue(body.get("usedTokens") instanceof Number, "usedTokens should be a Number");
        assertTrue(body.get("maxTokens") instanceof Number, "maxTokens should be a Number");
    }

    @Test
    void handlePostContextFiles_allPathsInvalid_returns400WithDetailedMessage() throws Exception {
        var absoluteOutsideWorkspace =
                projectRoot.resolveSibling("outside-workspace").toString();
        Map<String, Object> body =
                Map.of("relativePaths", List.of(absoluteOutsideWorkspace, "../outside/workspace", "nonexistent.txt"));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/context/files", body);
        contextRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());

        String msg = payload.message();
        // (a) Verify it includes the specific reasons
        assertTrue(msg.contains("invalid:"), "Message should contain 'invalid:' marker");
        assertTrue(msg.contains("absolute path not allowed"), msg);
        assertTrue(msg.contains("escapes workspace"), msg);
        assertTrue(msg.contains("not a regular file or does not exist"), msg);

        // (b) Verify it doesn't end with a trailing colon/empty list if entries exist
        assertTrue(!msg.endsWith("invalid: "), "Message should contain the invalid entries after the colon");
    }

    @Test
    void handleGetContextFragment_pasteText_returnsEmbeddedResourceFields() throws Exception {
        contextManager.addPastedTextFragment("hello from chip");
        var fragmentId = contextManager.liveContext().getAllFragmentsInDisplayOrder().stream()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PASTE_TEXT)
                .map(ContextFragment::id)
                .findFirst()
                .orElseThrow();

        var exchange = TestHttpExchange.request("GET", "/v1/context/fragments/" + fragmentId);
        contextRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals(fragmentId, body.get("id"));
        assertEquals("brokk://context/fragment/" + fragmentId, body.get("uri"));
        assertEquals("text/plain", body.get("mimeType"));

        String text = (String) body.get("text");
        assertTrue(text.contains("hello from chip"), "Text should contain pasted content, but was: " + text);
        assertTrue(!text.equals("(binary fragment)"), "Text should not be the placeholder");

        // Best-effort cleanup of workspace-local state created during the test (e.g., .brokk/sessions),
        // so the test runner can delete temporary directories reliably.
        try {
            var brokkDir = projectRoot.resolve(".brokk");
            if (java.nio.file.Files.exists(brokkDir)) {
                try (var stream = java.nio.file.Files.walk(brokkDir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            java.nio.file.Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // Ignore - cleanup is best-effort to avoid interfering with test assertions.
                        }
                    });
                }
            }
        } catch (Exception ignored) {
            // Swallow cleanup errors; they should not affect test outcomes.
        }
    }

    @Test
    void handleGetContextFragment_unknownId_returns404() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/context/fragments/not-real");
        contextRouter.handle(exchange);

        assertEquals(404, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.NOT_FOUND, payload.code());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostContextUndoRedo_updatesContext() throws Exception {
        // Arrange: add two pasted text fragments to create history
        contextManager.addPastedTextFragment("first");
        contextManager.addPastedTextFragment("second");

        // Get initial context fragments size
        var before = TestHttpExchange.request("GET", "/v1/context");
        contextRouter.handle(before);
        assertEquals(200, before.responseCode());
        Map<String, Object> beforeBody = MAPPER.readValue(before.responseBodyBytes(), new TypeReference<>() {});
        var fragments = (List<Map<String, Object>>) beforeBody.get("fragments");
        int initialSize = fragments.size();

        // Act: undo
        var undoEx = TestHttpExchange.request("POST", "/v1/context/undo");
        contextRouter.handle(undoEx);
        assertEquals(200, undoEx.responseCode());
        Map<String, Object> undoBody = MAPPER.readValue(undoEx.responseBodyBytes(), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, undoBody.get("applied"));

        // Assert: context has fewer fragments after undo
        var afterUndo = TestHttpExchange.request("GET", "/v1/context");
        contextRouter.handle(afterUndo);
        assertEquals(200, afterUndo.responseCode());
        Map<String, Object> afterUndoBody = MAPPER.readValue(afterUndo.responseBodyBytes(), new TypeReference<>() {});
        var fragmentsAfterUndo = (List<Map<String, Object>>) afterUndoBody.get("fragments");
        assertTrue(fragmentsAfterUndo.size() < initialSize, "Expected fewer fragments after undo");

        // Act: redo
        var redoEx = TestHttpExchange.request("POST", "/v1/context/redo");
        contextRouter.handle(redoEx);
        assertEquals(200, redoEx.responseCode());
        Map<String, Object> redoBody = MAPPER.readValue(redoEx.responseBodyBytes(), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, redoBody.get("applied"));

        // Assert: context restored to initial size
        var afterRedo = TestHttpExchange.request("GET", "/v1/context");
        contextRouter.handle(afterRedo);
        assertEquals(200, afterRedo.responseCode());
        Map<String, Object> afterRedoBody = MAPPER.readValue(afterRedo.responseBodyBytes(), new TypeReference<>() {});
        var fragmentsAfterRedo = (List<Map<String, Object>>) afterRedoBody.get("fragments");
        assertEquals(initialSize, fragmentsAfterRedo.size(), "Expected fragments restored after redo");
    }

    @Test
    void handlePostContextUndo_noHistory_returnsAppliedFalse() throws Exception {
        var ex = TestHttpExchange.request("POST", "/v1/context/undo");
        contextRouter.handle(ex);

        assertEquals(200, ex.responseCode());
        Map<String, Object> body = MAPPER.readValue(ex.responseBodyBytes(), new TypeReference<>() {});
        assertEquals(Boolean.FALSE, body.get("applied"));
        assertEquals("NO_UNDO_AVAILABLE", body.get("reason"));
    }

    @Test
    void handlePostContextRedo_noHistory_returnsAppliedFalse() throws Exception {
        var ex = TestHttpExchange.request("POST", "/v1/context/redo");
        contextRouter.handle(ex);

        assertEquals(200, ex.responseCode());
        Map<String, Object> body = MAPPER.readValue(ex.responseBodyBytes(), new TypeReference<>() {});
        assertEquals(Boolean.FALSE, body.get("applied"));
        assertEquals("NO_REDO_AVAILABLE", body.get("reason"));
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
