package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-style tests for ASK mode pre-scan behavior.
 *
 * These tests start an in-process HeadlessExecutorMain on an ephemeral port, create a session,
 * submit ASK-mode jobs with pre-scan options, and poll job events to assert that:
 *  - the Context Engine pre-scan message is emitted when preScan=true, and
 *  - ASK mode remains read-only (no CodeAgent/build/commit events) even if codeModel is provided.
 *
 * NOTE: These tests assume the environment or project default provides a usable scan model.
 * If model resolution fails in your environment, adapt tests to initialize model stubs or
 * provide configured model names via environment.
 */
public class AskModeSearchAgentTest {

    private Path tempWorkspace;
    private HeadlessExecutorMain executor;
    private OkHttpClient httpClient;
    private ObjectMapper mapper;
    private String authToken;

    @BeforeEach
    public void setUp() throws Exception {
        tempWorkspace = Files.createTempDirectory("brokk-test-workspace-");

        // Create a minimal .git structure so the project detects a Git repository implementation.
        // This prevents ClassCastException in contexts that expect a Git-backed repo during scans.
        var gitDir = tempWorkspace.resolve(".git");
        Files.createDirectories(gitDir.resolve("refs").resolve("heads"));
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/master\n");
        Files.createFile(gitDir.resolve("refs").resolve("heads").resolve("master"));

        var execId = UUID.randomUUID();
        // Configure tests to use localhost proxy and a dummy Brokk key to avoid external network/calls in CI.
        // This mirrors other integration tests which use LOCALHOST proxy + dummy key so model creation
        // doesn't require a real Brokk API key.
        MainProject.setLlmProxySetting(MainProject.LlmProxySetting.LOCALHOST);
        MainProject.setBrokkKey("brk+" + UUID.randomUUID() + "+test");

        var project = new MainProject(tempWorkspace);
        // Use a TestService provider to avoid external network calls and model lookups in CI.
        var contextManager = new ContextManager(project, TestService.provider(project));

        // random token
        authToken = UUID.randomUUID().toString();

        // Start executor bound to ephemeral port
        executor = new HeadlessExecutorMain(execId, "127.0.0.1:0", authToken, contextManager);
        executor.start();

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .readTimeout(java.time.Duration.ofSeconds(30))
                .build();

        mapper = new ObjectMapper();
    }

    @AfterEach
    public void tearDown() {
        // Restore global settings to avoid cross-test leakage of proxy/key configuration.
        MainProject.setLlmProxySetting(MainProject.LlmProxySetting.BROKK);
        MainProject.setBrokkKey("");

        if (executor != null) {
            try {
                executor.stop(0);
            } catch (Exception e) {
                // ignore
            }
        }
        if (tempWorkspace != null) {
            try {
                deleteRecursively(tempWorkspace);
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        if (Files.isDirectory(p)) {
            try (var stream = Files.list(p)) {
                stream.forEach(child -> {
                    try {
                        deleteRecursively(child);
                    } catch (IOException e) {
                        // ignore
                    }
                });
            }
        }
        Files.deleteIfExists(p);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + executor.getPort();
    }

    private String createSession() throws IOException {
        var uri = baseUrl() + "/v1/sessions";
        var body = mapper.createObjectNode();
        body.put("name", "test-session-" + System.currentTimeMillis());
        var req = new Request.Builder()
                .url(uri)
                .post(RequestBody.create(
                        mapper.writeValueAsString(body), MediaType.get("application/json; charset=utf-8")))
                .header("Authorization", "Bearer " + authToken)
                .build();
        try (var resp = httpClient.newCall(req).execute()) {
            if (resp.code() != 201) {
                throw new IOException("Failed to create session: " + resp.code() + " " + resp.body());
            }
            var node = mapper.readTree(resp.body().string());
            return node.get("sessionId").asText();
        }
    }

    private String submitJob(String sessionId, Map<String, Object> jobPayload) throws IOException {
        var uri = baseUrl() + "/v1/jobs";
        var json = mapper.createObjectNode();
        json.put("sessionId", sessionId);
        json.put(
                "taskInput",
                jobPayload.getOrDefault("taskInput", "Ask pre-scan test").toString());
        json.put("autoCommit", (Boolean) jobPayload.getOrDefault("autoCommit", false));
        json.put("autoCompress", (Boolean) jobPayload.getOrDefault("autoCompress", false));
        json.put(
                "plannerModel",
                jobPayload.getOrDefault("plannerModel", "gemini-2.0-flash").toString());
        if (jobPayload.containsKey("scanModel")) {
            json.put("scanModel", jobPayload.get("scanModel").toString());
        }
        if (jobPayload.containsKey("codeModel")) {
            json.put("codeModel", jobPayload.get("codeModel").toString());
        }
        if (jobPayload.containsKey("preScan")) {
            json.put("preScan", (Boolean) jobPayload.get("preScan"));
        }
        var tags = mapper.createObjectNode();
        Map<?, ?> tagsMap = (Map<?, ?>) jobPayload.getOrDefault("tags", Map.of("mode", "ASK"));
        for (var e : tagsMap.entrySet()) {
            tags.put(e.getKey().toString(), e.getValue().toString());
        }
        json.set("tags", tags);

        var req = new Request.Builder()
                .url(uri)
                .post(RequestBody.create(
                        mapper.writeValueAsString(json), MediaType.get("application/json; charset=utf-8")))
                .header("Authorization", "Bearer " + authToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .build();

        try (var resp = httpClient.newCall(req).execute()) {
            if (resp.code() != 201 && resp.code() != 200) {
                throw new IOException("Failed to submit job: " + resp.code() + " " + resp.body());
            }
            var node = mapper.readTree(resp.body().string());
            return node.get("jobId").asText();
        }
    }

    private String pollEventsUntilContains(String jobId, String matchText, Duration timeout)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long after = -1;

        // keep a small ring of recent events for debug output on timeout
        var recentEvents = new java.util.ArrayDeque<com.fasterxml.jackson.databind.JsonNode>(32);

        while (System.currentTimeMillis() < deadline) {
            var eventsUrl = baseUrl() + "/v1/jobs/" + jobId + "/events?after=" + after + "&limit=100";
            var req = new Request.Builder()
                    .url(eventsUrl)
                    .get()
                    .header("Authorization", "Bearer " + authToken)
                    .build();
            try (var resp = httpClient.newCall(req).execute()) {
                if (resp.code() == 200 && resp.body() != null) {
                    var bodyStr = resp.body().string();
                    var tree = mapper.readTree(bodyStr);
                    var events = tree.get("events");
                    if (events != null && events.isArray()) {
                        for (var ev : events) {
                            // keep recent events for debugging
                            recentEvents.addLast(ev);
                            if (recentEvents.size() > 32) recentEvents.removeFirst();

                            // Prefer NOTIFICATION events (string payloads) for deterministic checks
                            String type = ev.has("type") && ev.get("type").isTextual()
                                    ? ev.get("type").asText()
                                    : "";

                            var data = ev.get("data");
                            String text = null;

                            if ("NOTIFICATION".equals(type)) {
                                // For NOTIFICATION we expect a string payload; prefer asText()
                                if (data != null) {
                                    if (data.isTextual()) {
                                        text = data.asText();
                                    } else {
                                        // fallback to JSON string representation if not textual
                                        text = data.toString();
                                    }
                                }
                            } else {
                                // Other event types (e.g., LLM_TOKEN) may have object payloads.
                                if (data != null) {
                                    if (data.isTextual()) {
                                        text = data.asText();
                                    } else if (data.has("token")
                                            && data.get("token").isTextual()) {
                                        text = data.get("token").asText();
                                    } else if (data.has("message")
                                            && data.get("message").isTextual()) {
                                        text = data.get("message").asText();
                                    } else {
                                        text = data.toString();
                                    }
                                }
                            }

                            if (text != null && text.contains(matchText)) {
                                return text;
                            }

                            var seq = ev.get("seq");
                            if (seq != null && seq.isNumber()) {
                                after = seq.asLong();
                            }
                        }
                    }
                } else if (resp.code() == 404) {
                    throw new IOException("Job not found: " + jobId);
                }
            } catch (IOException ioe) {
                // transient network/read error: ignore and retry until deadline
            }
            Thread.sleep(500L);
        }

        // Timeout reached: dump recent events to stderr to aid debugging
        try {
            var list = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>(recentEvents);
            System.err.println("[AskModeSearchAgentTest] Timeout waiting for '" + matchText + "' for job " + jobId);
            try {
                System.err.println("[AskModeSearchAgentTest] Last events: "
                        + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list));
            } catch (Exception e) {
                System.err.println("[AskModeSearchAgentTest] Unable to serialize recent events: " + e.getMessage());
            }
        } catch (Throwable t) {
            // best-effort debug output; never fail the test method from here
            System.err.println(
                    "[AskModeSearchAgentTest] Timeout and failed to record recent events: " + t.getMessage());
        }

        return null;
    }

    private boolean eventsContain(String jobId, String matchText, Duration timeout)
            throws IOException, InterruptedException {
        return pollEventsUntilContains(jobId, matchText, timeout) != null;
    }

    /**
     * Test that ASK mode with preScan=true and an explicit scanModel emits the Context Engine pre-scan message
     * and does not emit write/CodeAgent/commit events (i.e., is read-only).
     */
    @Test
    public void testAskModeWithPreScan_UsesScanModelAndIsReadOnly() throws Exception {
        String sessionId = createSession();

        var payload = Map.<String, Object>of(
                "taskInput",
                "Summarize repository structure for pre-scan test",
                "plannerModel",
                "gemini-2.0-flash",
                "scanModel",
                "gemini-2.0-flash",
                "preScan",
                true,
                "autoCommit",
                false,
                "autoCompress",
                false,
                "tags",
                Map.of("mode", "ASK"));

        String jobId = submitJob(sessionId, payload);

        // Wait for the Context Engine pre-scan message
        boolean sawPreScan = eventsContain(jobId, "Brokk Context Engine", Duration.ofSeconds(60));
        assertTrue(sawPreScan, "Expected pre-scan Context Engine message in events stream");

        // Ensure no CodeAgent nor commit messages appear in the stream within a short window
        // (If code generation happened, we would see Code Agent messages or commits)
        boolean sawCodeAgent = eventsContain(jobId, "Code Agent", Duration.ofSeconds(5));
        boolean sawCommit = eventsContain(jobId, "commit", Duration.ofSeconds(5));
        assertFalse(sawCodeAgent, "ASK with pre-scan must not invoke Code Agent (read-only)");
        assertFalse(sawCommit, "ASK with pre-scan must not perform commits (read-only)");
    }

    /**
     * Test that ASK mode with preScan=true but without an explicit scanModel falls back to the project's default scan model
     * and emits the Context Engine messages.
     */
    @Test
    public void testAskModeWithPreScan_DefaultsToProjectScanModelWhenOmitted() throws Exception {
        String sessionId = createSession();

        var payload = Map.<String, Object>of(
                "taskInput",
                "Find references to AuthenticationManager across repo",
                "plannerModel",
                "gemini-2.0-flash",
                // scanModel omitted to exercise project default
                "preScan",
                true,
                "autoCommit",
                false,
                "autoCompress",
                false,
                "tags",
                Map.of("mode", "ASK"));

        String jobId = submitJob(sessionId, payload);

        // Wait for Context Engine pre-scan message
        boolean sawPreScan = eventsContain(jobId, "Brokk Context Engine", Duration.ofSeconds(60));
        assertTrue(
                sawPreScan,
                "Expected pre-scan Context Engine message when scanModel omitted (defaults to project scan model)");
    }

    /**
     * Test that ASK mode ignores codeModel even when preScan=true (i.e., codeModel does not cause code-generation).
     */
    @Test
    public void testAskModeIgnoresCodeModelEvenWhenPreScanTrue() throws Exception {
        String sessionId = createSession();

        var payload = Map.<String, Object>of(
                "taskInput",
                "Explain UserService responsibilities",
                "plannerModel",
                "gemini-2.0-flash",
                "scanModel",
                "gemini-2.0-flash",
                "codeModel",
                "gemini-2.0-flash", // intentionally provided; should be ignored for ASK
                "preScan",
                true,
                "autoCommit",
                false,
                "autoCompress",
                false,
                "tags",
                Map.of("mode", "ASK"));

        String jobId = submitJob(sessionId, payload);

        // Wait for pre-scan message
        boolean sawPreScan = eventsContain(jobId, "Brokk Context Engine", Duration.ofSeconds(60));
        assertTrue(sawPreScan, "Expected pre-scan Context Engine message in events stream");

        // Ensure no code-generation events appear
        boolean sawCodeAgent = eventsContain(jobId, "Code Agent", Duration.ofSeconds(5));
        assertFalse(sawCodeAgent, "codeModel must be ignored for ASK; no Code Agent invocation expected");
    }

    /**
     * New test:
     * Submits an ASK job with preScan=true and asserts:
     *  1) both Context Engine NOTIFICATIONs (start and completion) appear in the event stream,
     *  2) no follow-on SearchAgent search/inspection tool activity is recorded after pre-scan,
     *  3) an answer is produced (job reaches COMPLETED),
     *  4) no CodeAgent/commit events occur.
     */
    @Test
    public void testAskModePreScan_OnlyInitialScanThenAnswer_NoFurtherSearch() throws Exception {
        String sessionId = createSession();

        var payload = Map.<String, Object>of(
                "taskInput",
                "Summarize repository structure for fast-ask test",
                "plannerModel",
                "gemini-2.0-flash",
                "scanModel",
                "gemini-2.0-flash",
                "preScan",
                true,
                "autoCommit",
                false,
                "autoCompress",
                false,
                "tags",
                Map.of("mode", "ASK"));

        String jobId = submitJob(sessionId, payload);

        // 1) Assert pre-scan start notification appears
        boolean sawPreScanStart =
                eventsContain(jobId, "Brokk Context Engine: analyzing repository context...", Duration.ofSeconds(60));
        assertTrue(sawPreScanStart, "Expected pre-scan START Context Engine notification");

        // 1b) Assert pre-scan completion notification appears
        boolean sawPreScanComplete = eventsContain(
                jobId,
                "Brokk Context Engine: complete — contextual insights added to Workspace.",
                Duration.ofSeconds(60));
        assertTrue(sawPreScanComplete, "Expected pre-scan COMPLETE Context Engine notification");

        // 2) Ensure no follow-on search/inspection tool activity is recorded after pre-scan.
        // These tool names represent typical search/inspection calls exposed by SearchAgent/WorkspaceTools.
        var forbiddenSearchTools = new String[] {
            "searchSymbols",
            "getSymbolLocations",
            "getClassSkeletons",
            "getClassSources",
            "getMethodSources",
            "getUsages",
            "searchSubstrings",
            "searchFilenames",
            "getFileContents",
            "getFileSummaries"
        };
        for (String tool : forbiddenSearchTools) {
            boolean sawTool = eventsContain(jobId, tool, Duration.ofSeconds(5));
            assertFalse(sawTool, "Did not expect search/inspection tool activity after pre-scan: " + tool);
        }

        // 4) Also ensure no CodeAgent or commit messages appear (read-only)
        boolean sawCodeAgent = eventsContain(jobId, "Code Agent", Duration.ofSeconds(5));
        boolean sawCommit = eventsContain(jobId, "commit", Duration.ofSeconds(5));
        assertFalse(sawCodeAgent, "ASK with pre-scan must not invoke Code Agent (read-only)");
        assertFalse(sawCommit, "ASK with pre-scan must not perform commits (read-only)");

        // 3) Finally, assert that the job completes (indirect evidence that an answer was produced).
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        boolean completed = false;
        while (System.currentTimeMillis() < deadline) {
            var uri = baseUrl() + "/v1/jobs/" + jobId;
            var req = new Request.Builder()
                    .url(uri)
                    .get()
                    .header("Authorization", "Bearer " + authToken)
                    .build();
            try (var resp = httpClient.newCall(req).execute()) {
                if (resp.code() == 200 && resp.body() != null) {
                    var node = mapper.readTree(resp.body().string());
                    if (node.has("status")) {
                        // Some installations may return "status" or "state"; tolerate both
                        String state = node.has("status")
                                ? node.get("status").asText()
                                : node.get("state").asText();
                        if ("COMPLETED".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state)) {
                            completed = true;
                            break;
                        }
                    } else if (node.has("state")) {
                        String state = node.get("state").asText();
                        if ("COMPLETED".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state)) {
                            completed = true;
                            break;
                        }
                    }
                }
            } catch (IOException ioe) {
                // transient: ignore and retry until deadline
            }
            Thread.sleep(500L);
        }
        assertTrue(completed, "Expected job to reach COMPLETED state (answer produced) within timeout");
    }
}
