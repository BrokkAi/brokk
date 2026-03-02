package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextRoutingIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HeadlessExecutorMain executor;
    private ContextManager contextManager;
    private String authToken = "test-token-" + UUID.randomUUID();
    private HttpClient client;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws IOException {
        var project = MainProject.forTests(tempDir);
        contextManager = new ContextManager(project);

        // Start executor on ephemeral port
        executor = new HeadlessExecutorMain(UUID.randomUUID(), "127.0.0.1:0", authToken, contextManager);
        executor.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.stop(0);
        }
    }

    @Test
    void testGetContext_isRegistered() throws Exception {
        var uri = URI.create("http://127.0.0.1:" + executor.getPort() + "/v1/context");
        var request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + authToken)
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.discarding());

        // We assert that it is NOT a 404.
        // It might be 503 (if session not loaded) or 200, but not 404.
        assertNotEquals(
                HttpURLConnection.HTTP_NOT_FOUND,
                response.statusCode(),
                "Endpoint /v1/context should be registered and not return 404");
    }

    @Test
    void testGetTaskList_isRegistered() throws Exception {
        var uri = URI.create("http://127.0.0.1:" + executor.getPort() + "/v1/tasklist");
        var request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + authToken)
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.discarding());

        assertNotEquals(
                HttpURLConnection.HTTP_NOT_FOUND,
                response.statusCode(),
                "Endpoint /v1/tasklist should be registered and not return 404");
    }

    @Test
    void testPostTaskList_isRegistered() throws Exception {
        var uri = URI.create("http://127.0.0.1:" + executor.getPort() + "/v1/tasklist");
        var body = "{\"bigPicture\":\"Goal\",\"tasks\":[]}";
        var request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.discarding());

        assertNotEquals(
                HttpURLConnection.HTTP_NOT_FOUND,
                response.statusCode(),
                "Endpoint POST /v1/tasklist should be registered and not return 404");
    }

    @Test
    void testGetModels_isRegistered() throws Exception {
        var uri = URI.create("http://127.0.0.1:" + executor.getPort() + "/v1/models");
        var request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + authToken)
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.discarding());

        assertNotEquals(
                HttpURLConnection.HTTP_NOT_FOUND,
                response.statusCode(),
                "Endpoint /v1/models should be registered and not return 404");
    }

    @Test
    void testListSessionsUsesProjectSessionManagerTitles() throws Exception {
        var createUri = URI.create("http://127.0.0.1:" + executor.getPort() + "/v1/sessions");
        var createRequest = HttpRequest.newBuilder(createUri)
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Initial Name\"}"))
                .build();

        var createResponse = client.send(createRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpURLConnection.HTTP_CREATED, createResponse.statusCode());

        var createdPayload =
                OBJECT_MAPPER.readValue(createResponse.body(), new TypeReference<Map<String, Object>>() {});
        var sessionId = UUID.fromString((String) createdPayload.get("sessionId"));

        contextManager.getProject().getSessionManager().renameSession(sessionId, "Renamed In ContextManager");

        var listRequest = HttpRequest.newBuilder(createUri)
                .header("Authorization", "Bearer " + authToken)
                .GET()
                .build();
        var listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpURLConnection.HTTP_OK, listResponse.statusCode());

        var listPayload = OBJECT_MAPPER.readValue(listResponse.body(), new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        var sessions = (List<Map<String, Object>>) listPayload.get("sessions");
        var renamedSession = sessions.stream()
                .filter(s -> sessionId.toString().equals(s.get("id")))
                .findFirst();

        assertTrue(renamedSession.isPresent(), "Expected session in /v1/sessions response");
        assertEquals("Renamed In ContextManager", renamedSession.get().get("name"));
    }
}
