package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class HeadlessExecutorMainIssueJobTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH_TOKEN = "test-token";

    private HeadlessExecutorMain executor;
    private HttpClient httpClient;
    private String baseUrl;
    private Path workspaceDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        workspaceDir = tempDir;
        var project = new MainProject(workspaceDir);
        var contextManager = new ContextManager(project);
        executor = new HeadlessExecutorMain(UUID.randomUUID(), "localhost:0", AUTH_TOKEN, contextManager);
        executor.start();
        baseUrl = "http://localhost:" + executor.getPort();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.stop(0);
        }
    }

    @Test
    void testPostIssueJob_MissingOwner() throws Exception {
        var payload = basePayload();
        payload.remove("owner");
        assertValidationError(payload, "owner is required");
    }

    @Test
    void testPostIssueJob_BlankOwner() throws Exception {
        var payload = basePayload();
        payload.put("owner", "  ");
        assertValidationError(payload, "owner is required");
    }

    @Test
    void testPostIssueJob_MissingRepo() throws Exception {
        var payload = basePayload();
        payload.remove("repo");
        assertValidationError(payload, "repo is required");
    }

    @Test
    void testPostIssueJob_InvalidIssueNumber() throws Exception {
        var payload = basePayload();
        payload.put("issueNumber", 0);
        assertValidationError(payload, "valid issueNumber is required");

        payload.put("issueNumber", -1);
        assertValidationError(payload, "valid issueNumber is required");
    }

    @Test
    void testPostIssueJob_BlankGithubToken() throws Exception {
        var payload = basePayload();
        payload.put("githubToken", "");
        assertValidationError(payload, "githubToken is required");
    }

    @Test
    void testPostIssueJob_BlankPlannerModel() throws Exception {
        var payload = basePayload();
        payload.put("plannerModel", " ");
        assertValidationError(payload, "plannerModel is required");
    }

    @Test
    void testPostIssueJob_MissingIdempotencyKey() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/jobs/issue"))
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(basePayload())))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        var error = MAPPER.readValue(response.body(), ErrorPayload.class);
        assertEquals("Idempotency-Key header is required", error.message());
    }

    @Test
    void testPostIssueJob_MaxIssueFixAttempts_DefaultWhenMissing() throws Exception {
        var payload = basePayload();
        String jobId = postIssueJobAndGetJobId(payload);
        var persisted = loadPersistedJobSpec(jobId);
        assertEquals(JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS, persisted.effectiveMaxIssueFixAttempts());
    }

    @Test
    void testPostIssueJob_MaxIssueFixAttempts_InvalidZero() throws Exception {
        var payload = basePayload();
        payload.put("maxIssueFixAttempts", 0);
        assertValidationError(payload, "maxIssueFixAttempts must be a positive integer");
    }

    @Test
    void testPostIssueJob_MaxIssueFixAttempts_InvalidNegative() throws Exception {
        var payload = basePayload();
        payload.put("maxIssueFixAttempts", -1);
        assertValidationError(payload, "maxIssueFixAttempts must be a positive integer");
    }

    @Test
    void testPostIssueJob_MaxIssueFixAttempts_ExplicitValue() throws Exception {
        var payload = basePayload();
        payload.put("maxIssueFixAttempts", 7);
        String jobId = postIssueJobAndGetJobId(payload);
        var persisted = loadPersistedJobSpec(jobId);
        assertEquals(7, persisted.effectiveMaxIssueFixAttempts());
    }

    private Map<String, Object> basePayload() {
        var payload = new HashMap<String, Object>();
        payload.put("owner", "some-owner");
        payload.put("repo", "some-repo");
        payload.put("issueNumber", 42);
        payload.put("githubToken", "ghp_tok");
        // Use a model name that is present in the test environment's discovered list.
        payload.put("plannerModel", "gpt-5-mini");
        return payload;
    }

    private void assertValidationError(Map<String, Object> payload, String expectedMessage) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/jobs/issue"))
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode(), "Expected 400 for payload: " + payload);
        var error = MAPPER.readValue(response.body(), ErrorPayload.class);
        assertEquals(expectedMessage, error.message());
    }

    private String postIssueJobAndGetJobId(Map<String, Object> payload) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/jobs/issue"))
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(
                response.statusCode() == 200 || response.statusCode() == 201,
                "Expected 200/201 for payload: " + payload + ", got " + response.statusCode() + ": " + response.body());

        var responseJson = MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        Object jobIdObj = responseJson.get("jobId");
        assertTrue(jobIdObj instanceof String, "Expected jobId in response: " + response.body());
        return (String) jobIdObj;
    }

    @Test
    void testPostIssueJob_SkipVerification_DefaultFalseWhenMissing() throws Exception {
        var payload = basePayload();
        String jobId = postIssueJobAndGetJobId(payload);
        var persisted = loadPersistedJobSpec(jobId);
        Assertions.assertFalse(persisted.skipVerification());
    }

    @Test
    void testPostIssueJob_SkipVerification_TrueWhenProvided() throws Exception {
        var payload = basePayload();
        payload.put("skipVerification", true);
        String jobId = postIssueJobAndGetJobId(payload);
        var persisted = loadPersistedJobSpec(jobId);
        Assertions.assertTrue(persisted.skipVerification());
    }

    // Helpers for testing generic /v1/jobs ISSUE-mode path
    private String postGenericIssueJobAndGetJobId(Map<String, Object> body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/jobs"))
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .header("Idempotency-Key", "gen-" + System.nanoTime())
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201, "Unexpected status: " + response);
        var responseJson = MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        Object jobIdObj = responseJson.get("jobId");
        assertTrue(jobIdObj instanceof String, "Expected jobId in response: " + response.body());
        return (String) jobIdObj;
    }

    private Map<String, Object> baseGenericIssueJobPayload() {
        var tags = new HashMap<String, Object>();
        tags.put("mode", "ISSUE");
        tags.put("github_token", "ghp_tok");
        tags.put("repo_owner", "some-owner");
        tags.put("repo_name", "some-repo");
        tags.put("issue_number", "42");

        var body = new HashMap<String, Object>();
        body.put("sessionId", UUID.randomUUID().toString());
        body.put("taskInput", "Issue via /v1/jobs");
        body.put("autoCommit", false);
        body.put("autoCompress", false);
        body.put("plannerModel", "gpt-5-mini");
        body.put("tags", tags);
        return body;
    }

    @Test
    void testGenericIssueJob_SkipVerification_DefaultFalseWhenMissing() throws Exception {
        var payload = baseGenericIssueJobPayload();
        String jobId = postGenericIssueJobAndGetJobId(payload);
        var persisted = loadPersistedJobSpec(jobId);
        Assertions.assertFalse(persisted.skipVerification());
    }

    @Test
    void testGenericIssueJob_SkipVerification_TrueWhenProvided() throws Exception {
        var payload = baseGenericIssueJobPayload();
        payload.put("skipVerification", true);
        String jobId = postGenericIssueJobAndGetJobId(payload);
        var persisted = loadPersistedJobSpec(jobId);
        Assertions.assertTrue(persisted.skipVerification());
    }

    private JobSpec loadPersistedJobSpec(String jobId) throws Exception {
        Path storeDir = workspaceDir.resolve(".brokk").resolve("jobs");
        var store = new JobStore(storeDir);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            try {
                var spec = store.loadSpec(jobId);
                if (spec != null) {
                    return spec;
                }
            } catch (Exception ignore) {
                // Best-effort: job may exist but not yet be fully flushed to disk.
            }

            Thread.sleep(25);
        }

        Assertions.fail("Expected persisted JobSpec for jobId=" + jobId + " in JobStore at " + storeDir);
        throw new IllegalStateException("unreachable");
    }
}
