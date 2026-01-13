package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessExecutorMainIssueJobTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH_TOKEN = "test-token";

    private HeadlessExecutorMain executor;
    private HttpClient httpClient;
    private String baseUrl;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        var project = new MainProject(tempDir);
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

    private Map<String, Object> basePayload() {
        var payload = new HashMap<String, Object>();
        payload.put("owner", "some-owner");
        payload.put("repo", "some-repo");
        payload.put("issueNumber", 42);
        payload.put("githubToken", "ghp_tok");
        payload.put("plannerModel", "gpt-4o");
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
}
