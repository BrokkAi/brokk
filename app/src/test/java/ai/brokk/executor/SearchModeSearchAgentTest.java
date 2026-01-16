package ai.brokk.executor;

import static ai.brokk.testutil.ExecutorTestUtil.awaitJobCompletion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.Service;
import ai.brokk.agents.TestScriptedLanguageModel;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.util.FileUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for SEARCH mode ensuring SearchAgent is used and no writes occur.
 * Verifies that SEARCH mode:
 * - Uses SearchAgent with ANSWER_ONLY objective
 * - Produces no code diffs
 * - Emits search/summary events, not code edits
 * - Maintains read-only semantics
 */
class SearchModeSearchAgentTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final StreamingChatModel DUMMY_MODEL =
            new TestScriptedLanguageModel("TestScriptedLanguageModel: dummy response");

    private static final class CapturingService extends AbstractService {
        volatile @Nullable Service.ModelConfig lastConfig;
        volatile @Nullable OpenAiChatRequestParameters.Builder lastParametersOverride;
        final AtomicInteger getModelCallCount = new AtomicInteger(0);

        private final Map<ModelType, StreamingChatModel> modelOverrides = new HashMap<>();

        private CapturingService(MainProject project) {
            super(project);
        }

        public void setModel(ModelType modelType, StreamingChatModel model) {
            modelOverrides.put(modelType, model);
        }

        @Override
        public @Nullable StreamingChatModel getModel(ModelType modelType) {
            var model = modelOverrides.get(modelType);
            if (model != null) {
                return model;
            }
            return modelOverrides.get(ModelType.SCAN);
        }

        @Override
        public StreamingChatModel getScanModel() {
            var scan = modelOverrides.get(ModelType.SCAN);
            assert scan != null;
            return scan;
        }

        @Override
        public String nameOf(StreamingChatModel model) {
            return "stub-model";
        }

        @Override
        public boolean isLazy(StreamingChatModel model) {
            return false;
        }

        @Override
        public boolean isReasoning(StreamingChatModel model) {
            return false;
        }

        @Override
        public boolean requiresEmulatedTools(StreamingChatModel model) {
            return false;
        }

        @Override
        public boolean supportsJsonSchema(StreamingChatModel model) {
            return true;
        }

        @Override
        public @Nullable StreamingChatModel getModel(
                Service.ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {
            lastConfig = config;
            lastParametersOverride = parametersOverride;
            getModelCallCount.incrementAndGet();
            return modelOverrides.get(ModelType.SCAN);
        }

        @Override
        public float getUserBalance() {
            return 0;
        }

        @Override
        public JsonNode reportClientException(
                String stacktrace, String clientVersion, Map<String, String> optionalFields) {
            return objectMapper.createObjectNode();
        }

        @Override
        public void sendFeedback(
                String category, String feedbackText, boolean includeDebugLog, java.io.File screenshotFile) {
            throw new UnsupportedOperationException();
        }
    }

    private HeadlessExecutorMain executor;
    private int port;
    private String authToken = "test-secret-token";
    private String baseUrl;
    private Path tempDir;
    private Path workspaceDir;

    private CapturingService capturingService;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("search-mode-test-");
        workspaceDir = tempDir.resolve("workspace");
        var sessionsDir = tempDir.resolve("sessions");
        Files.createDirectories(workspaceDir);
        Files.createDirectories(sessionsDir);

        // Create a minimal .brokk/project.properties file for MainProject
        var brokkDir = workspaceDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        // Ensure llm-history directory exists so LLM logging (tests) can write history files without error
        Files.createDirectories(brokkDir.resolve("llm-history"));
        var propsFile = brokkDir.resolve("project.properties");
        Files.writeString(propsFile, "# Minimal properties for test\n");

        var execId = UUID.randomUUID();
        var project = new MainProject(workspaceDir);

        capturingService = new CapturingService(project);
        capturingService.setModel(ModelType.SCAN, DUMMY_MODEL);

        Service.Provider provider = new Service.Provider() {
            private AbstractService svc = capturingService;

            @Override
            public AbstractService get() {
                return svc;
            }

            @Override
            public void reinit(ai.brokk.project.IProject p) {
                assert p instanceof MainProject;
                capturingService = new CapturingService((MainProject) p);
                capturingService.setModel(ModelType.SCAN, DUMMY_MODEL);
                svc = capturingService;
            }
        };

        var cm = new ContextManager(project, provider);
        executor = new HeadlessExecutorMain(
                execId,
                "127.0.0.1:0", // Ephemeral port
                authToken,
                cm);

        executor.start();

        port = executor.getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void cleanup() {
        if (executor != null) {
            executor.stop(2);
        }
        FileUtil.deleteRecursively(tempDir);
    }

    @Test
    void testPostJobsAcceptsValidReasoningLevelAndTemperature_PersistsToJobStore() throws Exception {
        uploadSession();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "Validate reasoningLevel and temperature",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash",
                "reasoningLevel",
                "medium",
                "reasoningLevelCode",
                "high",
                "temperature",
                0.5,
                "tags",
                Map.of("mode", "SEARCH"));

        var idempotencyKey = "search-test-valid-overrides-" + UUID.randomUUID();
        var response = postJob(jobSpec, idempotencyKey);
        assertEquals(201, response.statusCode());

        var jobId = extractJobIdFromCreateResponse(response.body());
        assertNotNull(jobId);
        assertFalse(jobId.isBlank());

        var store = new JobStore(workspaceDir.resolve(".brokk").resolve("jobs"));
        var persisted = store.loadSpec(jobId);
        assertNotNull(persisted);

        assertEquals("MEDIUM", persisted.reasoningLevel());
        assertEquals("HIGH", persisted.reasoningLevelCode());
        assertEquals(0.5, persisted.temperature());
    }

    @Test
    void testPostJobsRejectsUnknownReasoningLevel() throws Exception {
        uploadSession();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "Validate unknown reasoningLevel",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash",
                "reasoningLevel",
                "BANANAS",
                "tags",
                Map.of("mode", "SEARCH"));

        var idempotencyKey = "search-test-bad-reasoning-" + UUID.randomUUID();
        var response = postJob(jobSpec, idempotencyKey);

        assertEquals(400, response.statusCode());
        assertTrue(
                response.body().contains("reasoningLevel must be one of"),
                "Expected validation message substring; got: " + response.body());
    }

    @Test
    void testPostJobsRejectsOutOfRangeTemperature() throws Exception {
        uploadSession();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "Validate out of range temperature",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash",
                "temperature",
                2.5,
                "tags",
                Map.of("mode", "SEARCH"));

        var idempotencyKey = "search-test-bad-temperature-" + UUID.randomUUID();
        var response = postJob(jobSpec, idempotencyKey);

        assertEquals(400, response.statusCode());
        assertTrue(
                response.body().contains("temperature must be between 0.0 and 2.0"),
                "Expected validation message substring; got: " + response.body());
    }

    @Test
    void testPostJobs_WithNullOverrides_UsesDefaultModelConfigAndNoParametersOverride() throws Exception {
        uploadSession();

        var jobSpec = new HashMap<String, Object>();
        jobSpec.put("sessionId", UUID.randomUUID().toString());
        jobSpec.put("taskInput", "Null overrides should not change model config");
        jobSpec.put("autoCommit", false);
        jobSpec.put("autoCompress", false);
        jobSpec.put("plannerModel", "gemini-2.0-flash");
        jobSpec.put("reasoningLevel", null);
        jobSpec.put("temperature", null);
        jobSpec.put("tags", Map.of("mode", "SEARCH"));

        var idempotencyKey = "search-test-null-overrides-" + UUID.randomUUID();
        var response = postJob(jobSpec, idempotencyKey);
        assertEquals(201, response.statusCode());

        var jobId = extractJobIdFromCreateResponse(response.body());
        assertNotNull(jobId);

        var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (capturingService.lastConfig == null && System.nanoTime() < deadlineNanos) {
            Thread.sleep(25);
        }

        var capturedConfig = capturingService.lastConfig;
        assertNotNull(capturedConfig, "Expected JobRunner to resolve a model via Service.getModel(...)");

        var expectedBaseConfig = Service.ModelConfig.from(capturingService.getScanModel(), capturingService);

        assertNull(capturingService.lastParametersOverride);
        assertEquals(expectedBaseConfig, capturedConfig);
        assertTrue(capturingService.getModelCallCount.get() > 0);

        var cancelUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/cancel").toURL();
        var cancelConn = (HttpURLConnection) cancelUrl.openConnection();
        cancelConn.setRequestMethod("POST");
        cancelConn.setRequestProperty("Authorization", "Bearer " + authToken);

        try {
            var status = cancelConn.getResponseCode();
            assertTrue(
                    status == 200 || status == 202 || status == 409,
                    "Cancel should succeed or be a no-op depending on state; got: " + status);
        } finally {
            cancelConn.disconnect();
        }
    }

    @Test
    void testSearchModeUsesSearchAgent_ReadsOnly() throws Exception {
        // Upload a minimal session
        uploadSession();

        // Create a SEARCH mode job
        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "What is the structure of this project?",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash",
                "tags",
                Map.of("mode", "SEARCH"));

        var jobId = createJobWithSpec(jobSpec, "search-test-read-only");

        // Wait for job to complete
        awaitJobCompletion(baseUrl, jobId, authToken, Duration.ofSeconds(30));

        // Poll for events to ensure job has processed
        var eventsUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/events?after=-1&limit=1000")
                .toURL();
        var eventsConn = (HttpURLConnection) eventsUrl.openConnection();
        eventsConn.setRequestMethod("GET");
        eventsConn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(200, eventsConn.getResponseCode());
        try (InputStream is = eventsConn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var eventsData = OBJECT_MAPPER.readValue(response, new TypeReference<Map<String, Object>>() {});
            var events = (List<?>) eventsData.get("events");

            assertNotNull(events, "Events should not be null");
            assertTrue(events.size() > 0, "SEARCH mode should produce events");

            var eventTypes = events.stream()
                    .map(e -> ((Map<?, ?>) e).get("type"))
                    .map(Object::toString)
                    .toList();

            assertTrue(
                    eventTypes.stream().anyMatch(t -> t.contains("LLM_TOKEN")),
                    "Expected SEARCH mode to stream LLM tokens (TestScriptedLanguageModel emits partial responses); got: "
                            + eventTypes);
            assertFalse(
                    eventTypes.stream().anyMatch(t -> t.contains("CODE_EDIT")),
                    "SEARCH mode should not produce CODE_EDIT events; got: " + eventTypes);
        }
        eventsConn.disconnect();

        // Verify no git diff was created (read-only semantics)
        var diffUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/diff").toURL();
        var diffConn = (HttpURLConnection) diffUrl.openConnection();
        diffConn.setRequestMethod("GET");
        diffConn.setRequestProperty("Authorization", "Bearer " + authToken);

        try {
            // Expect 200 or 409 (no git); if 409, that's fine (no git repo)
            var statusCode = diffConn.getResponseCode();
            assertTrue(
                    statusCode == 200 || statusCode == 409,
                    "Diff endpoint should succeed or report no git; got: " + statusCode);

            if (statusCode == 200) {
                try (InputStream is = diffConn.getInputStream()) {
                    var diffText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(
                            diffText.isEmpty() || diffText.isBlank(),
                            "SEARCH mode should produce no diff (read-only); got: " + diffText);
                }
            }
        } finally {
            diffConn.disconnect();
        }
    }

    @Test
    void testSearchModeWithExplicitScanModel() throws Exception {
        uploadSession();

        String explicitScanModel = "gpt-4o";

        // Create a SEARCH job with explicit scanModel
        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "Find usages of AuthenticationManager",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash",
                "scanModel",
                explicitScanModel,
                "tags",
                Map.of("mode", "SEARCH"));

        var jobId = createJobWithSpec(jobSpec, "search-test-scan-model");

        // Wait for the model resolution to occur in JobRunner
        var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (capturingService.lastConfig == null && System.nanoTime() < deadlineNanos) {
            Thread.sleep(50);
        }

        var capturedConfig = capturingService.lastConfig;
        assertNotNull(capturedConfig, "Expected JobRunner to resolve a scan model via Service.getModel(...)");
        assertEquals(explicitScanModel, capturedConfig.name());

        // Cancel the job now that we've verified model resolution
        var cancelUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/cancel").toURL();
        var cancelConn = (HttpURLConnection) cancelUrl.openConnection();
        cancelConn.setRequestMethod("POST");
        cancelConn.setRequestProperty("Authorization", "Bearer " + authToken);
        try {
            int status = cancelConn.getResponseCode();
            assertTrue(status == 200 || status == 202 || status == 409);
        } finally {
            cancelConn.disconnect();
        }
    }

    @Test
    void testSearchModeIgnoresCodeModel() throws Exception {
        uploadSession();

        String explicitCodeModel = "claude-3-5-sonnet";
        String expectedScanModel = "stub-model"; // default scan model name in capturingService

        // Create SEARCH job with explicit codeModel (should be ignored)
        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "List the main files in this project",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash",
                "codeModel",
                explicitCodeModel,
                "tags",
                Map.of("mode", "SEARCH"));

        var jobId = createJobWithSpec(jobSpec, "search-test-ignore-code-model");

        // Wait for the model resolution to occur in JobRunner
        var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (capturingService.lastConfig == null && System.nanoTime() < deadlineNanos) {
            Thread.sleep(50);
        }

        var capturedConfig = capturingService.lastConfig;
        assertNotNull(capturedConfig, "Expected JobRunner to resolve a model via Service.getModel(...)");

        // Assert that the resolved model is NOT the code model, but the default scan model
        assertEquals(expectedScanModel, capturedConfig.name());

        cancelJob(jobId);
    }

    private void cancelJob(String jobId) throws IOException {
        var cancelUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/cancel").toURL();
        var cancelConn = (HttpURLConnection) cancelUrl.openConnection();
        cancelConn.setRequestMethod("POST");
        cancelConn.setRequestProperty("Authorization", "Bearer " + authToken);
        try {
            int status = cancelConn.getResponseCode();
            assertTrue(status == 200 || status == 202 || status == 409);
        } finally {
            cancelConn.disconnect();
        }
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private record HttpResponse(int statusCode, String body) {}

    private byte[] createEmptyZip() throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(out)) {
            var fragmentsEntry = new ZipEntry("fragments-v4.json");
            zos.putNextEntry(fragmentsEntry);
            zos.write("{\"version\": 1, \"referenced\": {}, \"virtual\": {}, \"task\": {}}"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            var contextsEntry = new ZipEntry("contexts.jsonl");
            zos.putNextEntry(contextsEntry);
            // Must have at least one valid context entry or HistoryIo throws "No contexts found"
            zos.write(
                    "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"editable\":[],\"readonly\":[],\"virtuals\":[],\"pinned\":[],\"tasks\":[],\"parsedOutputId\":null}\n"
                            .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    private void uploadSession() throws Exception {
        var sessionZip = createEmptyZip();
        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/zip");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(sessionZip);
        }

        assertEquals(201, conn.getResponseCode());
        conn.disconnect();
    }

    private HttpResponse postJob(Map<String, Object> jobSpec, String idempotencyKey) throws Exception {
        var url = URI.create(baseUrl + "/v1/jobs").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Idempotency-Key", idempotencyKey);
        conn.setDoOutput(true);

        var json = toJson(jobSpec);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        var status = conn.getResponseCode();
        var bodyStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        var body = "";
        if (bodyStream != null) {
            try (InputStream is = bodyStream) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        conn.disconnect();
        return new HttpResponse(status, body);
    }

    private String extractJobIdFromCreateResponse(String responseBody) throws Exception {
        JsonNode node = OBJECT_MAPPER.readTree(responseBody);
        JsonNode jobIdNode = node.get("jobId");
        if (jobIdNode == null || !jobIdNode.isTextual()) {
            throw new IllegalStateException("Response did not contain textual jobId: " + responseBody);
        }
        return jobIdNode.asText();
    }

    private String createJobWithSpec(Map<String, Object> jobSpec, String idempotencyKey) throws Exception {
        var response = postJob(jobSpec, idempotencyKey);
        assertEquals(201, response.statusCode());
        return extractJobIdFromCreateResponse(response.body());
    }

    private String toJson(Object obj) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(obj);
    }
}
