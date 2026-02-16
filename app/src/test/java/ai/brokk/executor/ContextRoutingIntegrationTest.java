package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextRoutingIntegrationTest {
    private HeadlessExecutorMain executor;
    private String authToken = "test-token-" + UUID.randomUUID();
    private HttpClient client;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws IOException {
        var project = MainProject.forTests(tempDir);
        var contextManager = new ContextManager(project);

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
}
