package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.executor.JobReservation;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobStore;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobsRouterValidationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JobsRouter jobsRouter;
    private Path jobStoreDir;
    private List<String> fsSnapshotBefore;

    private ContextManager contextManager;
    private JobRunner jobRunner;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        // Real (lightweight) production objects; this test never reaches execution paths that need headless init.
        var project = MainProject.forTests(tempDir);
        contextManager = new ContextManager(project);

        jobStoreDir = tempDir.resolve("job-store");
        Files.createDirectories(jobStoreDir);
        var jobStore = new JobStore(jobStoreDir);

        jobRunner = new JobRunner(contextManager, jobStore);
        var jobReservation = new JobReservation();
        // Ensure the reservation is clear
        String currentJob = jobReservation.current();
        if (currentJob != null) {
            jobReservation.releaseIfOwner(currentJob);
        }

        CompletableFuture<Void> headlessInit = new CompletableFuture<>();
        headlessInit.complete(null);

        jobsRouter = new JobsRouter(contextManager, jobStore, jobRunner, jobReservation, headlessInit);

        // Snapshot filesystem state after construction; invalid requests must not create anything new.
        fsSnapshotBefore = snapshotTree(jobStoreDir);
    }

    @AfterEach
    void tearDown() {
        if (jobRunner != null) {
            jobRunner.shutdown();
        }
        if (contextManager != null) {
            contextManager.close();
        }
    }

    @Test
    void postJobs_invalidReasoningLevelCode_withNullReasoningLevel_returns400_andDoesNotCreateJob() throws Exception {
        Map<String, Object> body = Map.of(
                "taskInput", "test task",
                "plannerModel", "gpt-4",
                "reasoningLevelCode", "INVALID_LEVEL");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/jobs", body);
        exchange.getRequestHeaders().set("Idempotency-Key", "test-key");

        jobsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());
        assertTrue(payload.message().contains("reasoningLevelCode must be one of"), payload.message());

        assertEquals(fsSnapshotBefore, snapshotTree(jobStoreDir), "JobStore dir changed; job may have been created");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TUI Session", "New Session", "Session"})
    void postJobs_withDefaultSessionName_triggersAutoRename(String defaultName) throws Exception {
        var sm = jobsRouter.contextManager.getProject().getSessionManager();
        var session = sm.newSession(defaultName);
        UUID sessionId = session.id();
        String taskInput = "Rename task for " + defaultName;

        Map<String, Object> body = Map.of("taskInput", taskInput, "plannerModel", "gpt-4");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/jobs", body);
        exchange.getRequestHeaders().set("Idempotency-Key", UUID.randomUUID().toString());
        exchange.getRequestHeaders().set("X-Session-Id", sessionId.toString());

        jobsRouter.handle(exchange);
        assertEquals(201, exchange.responseCode());

        // Renaming is async, poll for result
        boolean renamed = false;
        for (int i = 0; i < 100; i++) {
            var updated = sm.getSessionsCache().get(sessionId);
            if (updated != null && updated.name().equals(taskInput)) {
                renamed = true;
                break;
            }
            Thread.sleep(20);
        }
        assertTrue(renamed, "Session '" + defaultName + "' should have been auto-renamed to '" + taskInput + "'");
    }

    @Test
    void postJobs_withCustomSessionName_doesNotAutoRename() throws Exception {
        var sm = jobsRouter.contextManager.getProject().getSessionManager();
        var session = sm.newSession("My Custom Name");
        UUID sessionId = session.id();

        Map<String, Object> body = Map.of(
                "taskInput", "New task in custom session",
                "plannerModel", "gpt-4");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/jobs", body);
        exchange.getRequestHeaders().set("Idempotency-Key", "no-rename-key");
        exchange.getRequestHeaders().set("X-Session-Id", sessionId.toString());

        jobsRouter.handle(exchange);

        assertEquals(201, exchange.responseCode());
        Thread.sleep(200); // Give async rename a chance to (not) happen

        var updated = sm.getSessionsCache().get(sessionId);
        assertEquals("My Custom Name", updated.name());
    }

    @Test
    void postJobs_invalidTemperatureCode_withNullTemperature_returns400_andDoesNotCreateJob() throws Exception {
        Map<String, Object> body = Map.of(
                "taskInput", "test task",
                "plannerModel", "gpt-4",
                "temperatureCode", 5.0);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/jobs", body);
        exchange.getRequestHeaders().set("Idempotency-Key", "test-key");

        jobsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());
        assertTrue(payload.message().contains("temperatureCode must be between 0.0 and 2.0"), payload.message());

        assertEquals(fsSnapshotBefore, snapshotTree(jobStoreDir), "JobStore dir changed; job may have been created");
    }

    private static List<String> snapshotTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                    .map(p -> {
                        var rel = root.relativize(p).toString();
                        if (rel.isEmpty()) {
                            rel = ".";
                        }
                        try {
                            if (Files.isDirectory(p)) {
                                return "D " + rel;
                            }
                            var size = Files.size(p);
                            // Include a small content fingerprint so we catch "file created then truncated" cases.
                            byte[] bytes = Files.readAllBytes(p);
                            int prefixLen = Math.min(bytes.length, 64);
                            var fp = HexFormat.of().formatHex(bytes, 0, prefixLen);
                            return "F " + rel + " size=" + size + " fp64=" + fp;
                        } catch (IOException e) {
                            // If we can't read a file, still record its presence deterministically.
                            return "X " + rel + " err=" + e.getClass().getSimpleName();
                        }
                    })
                    .toList();
        }
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    /**
     * Minimal HttpExchange stub sufficient for SimpleHttpServer.sendJsonResponse and JobsRouter validation paths.
     */
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
