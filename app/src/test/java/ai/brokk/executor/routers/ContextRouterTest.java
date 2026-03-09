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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void tearDown() {
        if (contextManager != null) {
            contextManager.close();
        }
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
    @SuppressWarnings("unchecked")
    void handlePostTaskList_replacesTaskList() throws Exception {
        var body = Map.of(
                "bigPicture",
                "Updated Goal",
                "tasks",
                List.of(
                        Map.of("title", "First", "text", "First", "done", false),
                        Map.of("title", "Second", "text", "Second", "done", true)));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/tasklist", body);
        contextRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> response = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("Updated Goal", response.get("bigPicture"));

        List<Map<String, Object>> returnedTasks = (List<Map<String, Object>>) response.get("tasks");
        assertEquals(2, returnedTasks.size());
        assertEquals("First", returnedTasks.get(0).get("title"));
        assertEquals(false, returnedTasks.get(0).get("done"));
        assertEquals("Second", returnedTasks.get(1).get("title"));
        assertEquals(true, returnedTasks.get(1).get("done"));
        assertTrue(!((String) returnedTasks.get(0).get("id")).isBlank());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostTaskList_worksWhenFragmentAbsent() throws Exception {
        // 1. Initial state: GET /v1/tasklist returns empty
        var get1 = TestHttpExchange.request("GET", "/v1/tasklist");
        contextRouter.handle(get1);
        assertEquals(200, get1.responseCode());
        var body1 = MAPPER.readValue(get1.responseBodyBytes(), new TypeReference<Map<String, Object>>() {});
        assertEquals(null, body1.get("bigPicture"));
        assertTrue(((List<?>) body1.get("tasks")).isEmpty());

        // 2. POST /v1/tasklist with empty tasks (explicitly clearing/confirming absence)
        var postEmpty = Map.of("bigPicture", "Still Empty", "tasks", List.of());
        var postEx1 = TestHttpExchange.jsonRequest("POST", "/v1/tasklist", postEmpty);
        contextRouter.handle(postEx1);
        assertEquals(200, postEx1.responseCode());

        var get2 = TestHttpExchange.request("GET", "/v1/tasklist");
        contextRouter.handle(get2);
        var body2 = MAPPER.readValue(get2.responseBodyBytes(), new TypeReference<Map<String, Object>>() {});
        // withTaskList(empty) removes fragment, so getTaskListDataOrEmpty returns (null, [])
        assertEquals(null, body2.get("bigPicture"));
        assertTrue(((List<?>) body2.get("tasks")).isEmpty());

        // 3. POST /v1/tasklist with non-empty list
        var postData = Map.of(
                "bigPicture", "New Goal", "tasks", List.of(Map.of("title", "Task 1", "text", "Do it", "done", false)));
        var postEx2 = TestHttpExchange.jsonRequest("POST", "/v1/tasklist", postData);
        contextRouter.handle(postEx2);
        assertEquals(200, postEx2.responseCode());

        // 4. Verify GET reflects new tasks
        var get3 = TestHttpExchange.request("GET", "/v1/tasklist");
        contextRouter.handle(get3);
        var body3 = MAPPER.readValue(get3.responseBodyBytes(), new TypeReference<Map<String, Object>>() {});
        assertEquals("New Goal", body3.get("bigPicture"));
        var tasks3 = (List<Map<String, Object>>) body3.get("tasks");
        assertEquals(1, tasks3.size());
        assertEquals("Task 1", tasks3.get(0).get("title"));
    }

    @Test
    void handlePostTaskList_nullTasks_returnsValidationError() throws Exception {
        var body = new HashMap<String, Object>();
        body.put("bigPicture", "Goal");
        body.put("tasks", null);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/tasklist", body);
        contextRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());
        assertTrue(payload.message().contains("tasks must not be null"));
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
        assertTrue(body.containsKey("branch"), "Should contain branch key");

        assertEquals(Boolean.TRUE, body.get("tokensEstimated"));
        assertTrue(body.get("branch") instanceof String, "branch should be a String");
        assertTrue(body.get("fragments") instanceof List, "fragments should be a List");
        assertTrue(body.get("usedTokens") instanceof Number, "usedTokens should be a Number");
        assertTrue(body.get("maxTokens") instanceof Number, "maxTokens should be a Number");

        assertTrue(body.containsKey("totalCost"), "Should contain totalCost key");
        assertTrue(body.get("totalCost") instanceof Number, "totalCost should be a Number");
        assertEquals(0.0, ((Number) body.get("totalCost")).doubleValue(), 1e-9);
    }

    @Test
    void handleGetContext_includesSessionTotalCost() throws Exception {
        // Arrange: create a real session and record some cost
        var sessionManager = contextManager.getProject().getSessionManager();
        var info = sessionManager.newSession("Cost Session");
        contextManager.updateActiveSession(info.id());

        sessionManager.addToTotalCost(info.id(), 1.25);
        sessionManager.addToTotalCost(info.id(), 0.75);

        // Act
        var exchange = TestHttpExchange.request("GET", "/v1/context?tokens=false");
        contextRouter.handle(exchange);

        // Assert
        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.containsKey("totalCost"));
        double totalCost = ((Number) body.get("totalCost")).doubleValue();
        assertEquals(2.0, totalCost, 1e-9);
    }

    @Test
    void handleGetContext_totalCostDrivenByAccountingPathNotJustNotifications() throws Exception {
        // This test ensures that totalCost in the /v1/context response is driven by the
        // session's accumulated cost, which is updated via IConsoleIO.recordCost,
        // even if no visible COST notification was emitted (e.g. suppressed or internal model).

        var sessionManager = contextManager.getProject().getSessionManager();
        var info = sessionManager.newSession("Accounting Session");
        contextManager.updateActiveSession(info.id());

        // Simulate cost being recorded via the accounting path (recordCost)
        // In the executor, HeadlessHttpConsole routes this to sessionManager.addToTotalCost
        // independently of NOTIFICATION events.
        sessionManager.addToTotalCost(info.id(), 0.50);

        var exchange = TestHttpExchange.request("GET", "/v1/context");
        contextRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        double totalCost = ((Number) body.get("totalCost")).doubleValue();
        assertEquals(0.50, totalCost, 1e-9);
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
    }

    @Test
    void handleGetContextFragment_unknownId_returns404() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/context/fragments/not-real");
        contextRouter.handle(exchange);

        assertEquals(404, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.NOT_FOUND, payload.code());
    }
}
