package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.brokk.ContextManager;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RepoRouter repoRouter;
    private Path projectRoot;
    private ContextManager contextManager;

    @AfterEach
    void tearDown() throws Exception {
        if (contextManager != null) {
            contextManager.close();
        }
    }

    private void initGitRepoAndRecreateContext(@TempDir Path tempDir, boolean modifyAfterCommit) throws Exception {
        projectRoot = tempDir;
        try (var git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            var testFile = projectRoot.resolve("initial.txt");
            Files.writeString(testFile, "initial content");
            git.add().addFilepattern("initial.txt").call();
            git.commit().setMessage("Initial commit").call();

            if (modifyAfterCommit) {
                Files.writeString(testFile, "modified content");
            }
        }

        if (contextManager != null) {
            contextManager.close();
        }
        var project = new MainProject(projectRoot);
        contextManager = new ContextManager(project);
        repoRouter = new RepoRouter(contextManager);
    }

    @Test
    void handlePostCommit_noChanges_returnsNoChangesStatus(@TempDir Path tempDir) throws Exception {
        initGitRepoAndRecreateContext(tempDir, false);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/commit", Map.of());
        repoRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("no_changes", body.get("status"));
    }

    @Test
    void handlePostCommit_withChanges_returnsCommitMetadata(@TempDir Path tempDir) throws Exception {
        initGitRepoAndRecreateContext(tempDir, true);

        var exchange =
                TestHttpExchange.jsonRequest("POST", "/v1/repo/commit", Map.of("message", "Test commit message"));
        repoRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertNotNull(body.get("commitId"));
        assertFalse(((String) body.get("commitId")).isBlank());
        assertEquals("Test commit message", body.get("firstLine"));
    }

    @Test
    void handlePostCommit_blankMessage_usesDefaultMessage(@TempDir Path tempDir) throws Exception {
        initGitRepoAndRecreateContext(tempDir, true);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/commit", Map.of("message", ""));
        repoRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertNotNull(body.get("commitId"));
        assertFalse(((String) body.get("commitId")).isBlank());
        assertEquals("Manual commit", body.get("firstLine"));
    }

    @Test
    void handlePostCommit_omittedMessage_usesDefaultMessage(@TempDir Path tempDir) throws Exception {
        initGitRepoAndRecreateContext(tempDir, true);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/commit", Map.of());
        repoRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertNotNull(body.get("commitId"));
        assertFalse(((String) body.get("commitId")).isBlank());
        assertEquals("Manual commit", body.get("firstLine"));
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
