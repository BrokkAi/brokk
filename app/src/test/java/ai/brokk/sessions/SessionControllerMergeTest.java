package ai.brokk.sessions;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.util.Json;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SessionControllerMergeTest {

    @TempDir
    Path tempDir;

    private Git git;
    private Path repoPath;
    private SessionRegistry registry;
    private SessionController controller;

    @BeforeEach
    void setUp() throws Exception {
        repoPath = tempDir.resolve("test-repo");
        Files.createDirectories(repoPath);

        git = Git.init().setDirectory(repoPath.toFile()).call();

        git.getRepository().getConfig().setString("user", null, "name", "Test User");
        git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
        git.getRepository().getConfig().save();

        var initialFile = repoPath.resolve("README.md");
        Files.writeString(initialFile, "Initial content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("Initial commit").setSign(false).call();

        registry = new SessionRegistry();
        controller = new SessionController(registry);
    }

    @AfterEach
    void tearDown() {
        if (git != null) {
            git.close();
        }
    }

    @Test
    void testMergeNonConflicting() throws Exception {
        var sessionBranch = "feature-branch";
        git.branchCreate().setName(sessionBranch).call();
        git.checkout().setName(sessionBranch).call();

        var featureFile = repoPath.resolve("feature.txt");
        Files.writeString(featureFile, "Feature content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("feature.txt").call();
        git.commit().setMessage("Add feature").setSign(false).call();

        git.checkout().setName("master").call();

        var sessionId = UUID.randomUUID();
        var dummyProcess = new ProcessBuilder(
                        System.getProperty("java.home") + "/bin/java", "-version")
                .start();
        var sessionInfo = new SessionInfo(
                sessionId,
                "Test Session",
                repoPath,
                sessionBranch,
                8080,
                "test-token",
                dummyProcess,
                System.currentTimeMillis(),
                System.currentTimeMillis());
        registry.create(sessionInfo);

        var requestBody = Json.toJson(Map.of("mode", "merge", "close", false));
        var exchange = new FakeHttpExchange("POST", "/api/sessions/" + sessionId + "/merge", requestBody);

        controller.handleMerge(exchange, sessionId.toString());

        assertEquals(200, exchange.getResponseCode(), "Should return 200 for successful merge");

        var responseBody = exchange.getResponseBodyAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = Json.getMapper().readValue(responseBody, Map.class);

        var status = (String) response.get("status");
        assertTrue(
                "merged".equals(status) || "up_to_date".equals(status),
                "Status should be merged or up_to_date, got: " + status);
        assertFalse((Boolean) response.get("conflicts"), "Should not have conflicts");
    }

    @Test
    void testMergeWithConflicts() throws Exception {
        var sessionBranch = "conflict-branch";
        git.branchCreate().setName(sessionBranch).call();
        git.checkout().setName(sessionBranch).call();

        var conflictFile = repoPath.resolve("conflict.txt");
        Files.writeString(conflictFile, "Session version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("conflict.txt").call();
        git.commit().setMessage("Session changes").setSign(false).call();

        git.checkout().setName("master").call();
        Files.writeString(conflictFile, "Master version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("conflict.txt").call();
        git.commit().setMessage("Master changes").setSign(false).call();

        var sessionId = UUID.randomUUID();
        var dummyProcess = new ProcessBuilder(
                        System.getProperty("java.home") + "/bin/java", "-version")
                .start();
        var sessionInfo = new SessionInfo(
                sessionId,
                "Test Session",
                repoPath,
                sessionBranch,
                8080,
                "test-token",
                dummyProcess,
                System.currentTimeMillis(),
                System.currentTimeMillis());
        registry.create(sessionInfo);

        var requestBody = Json.toJson(Map.of("mode", "merge", "close", false));
        var exchange = new FakeHttpExchange("POST", "/api/sessions/" + sessionId + "/merge", requestBody);

        controller.handleMerge(exchange, sessionId.toString());

        assertEquals(409, exchange.getResponseCode(), "Should return 409 for merge conflicts");

        var responseBody = exchange.getResponseBodyAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = Json.getMapper().readValue(responseBody, Map.class);

        assertTrue((Boolean) response.get("conflicts"), "Should have conflicts");
        assertEquals("MERGE_COMMIT", response.get("mode"), "Should report merge mode");
    }

    private static class FakeHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayInputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int responseCode = -1;

        FakeHttpExchange(String method, String path, String requestBodyContent) {
            this.method = method;
            this.uri = URI.create(path);
            this.requestBody = new ByteArrayInputStream(requestBodyContent.getBytes(StandardCharsets.UTF_8));
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
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}

        @Override
        public InputStream getRequestBody() {
            return requestBody;
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
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
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

        String getResponseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }
    }
}
