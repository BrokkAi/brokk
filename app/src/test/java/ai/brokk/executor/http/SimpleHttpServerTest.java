package ai.brokk.executor.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.executor.jobs.ErrorPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimpleHttpServerTest {

    private SimpleHttpServer server;
    private String authToken;
    private int port;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        authToken = "test-secret-token";
        // Use port 0 for ephemeral port selection
        server = new SimpleHttpServer("127.0.0.1", 0, authToken, 2);

        // Extract the actual port assigned via public API
        port = server.getPort();

        server.start();
    }

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(1);
        }
    }

    @Test
    void testAuthenticatedEndpoint_ValidToken() throws Exception {
        server.registerAuthenticatedContext("/test/auth", exchange -> {
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        });

        var url = new URI("http://127.0.0.1:" + port + "/test/auth").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(200, conn.getResponseCode());
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("ok"));
        }
        conn.disconnect();
    }

    @Test
    void testAuthenticatedEndpoint_MissingToken() throws Exception {
        server.registerAuthenticatedContext("/test/auth", exchange -> {
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        });

        var url = new URI("http://127.0.0.1:" + port + "/test/auth").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        // No Authorization header

        assertEquals(401, conn.getResponseCode());
        try (InputStream is = conn.getErrorStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var errorPayload = objectMapper.readValue(response, ErrorPayload.class);
            assertNotNull(errorPayload);
            assertEquals(ErrorPayload.Code.UNAUTHORIZED, errorPayload.code());
            assertEquals("Unauthorized", errorPayload.message());
        }
        conn.disconnect();
    }

    @Test
    void testAuthenticatedEndpoint_InvalidToken() throws Exception {
        server.registerAuthenticatedContext("/test/auth", exchange -> {
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        });

        var url = new URI("http://127.0.0.1:" + port + "/test/auth").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer invalid-token");

        assertEquals(401, conn.getResponseCode());
        try (InputStream is = conn.getErrorStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var errorPayload = objectMapper.readValue(response, ErrorPayload.class);
            assertNotNull(errorPayload);
            assertEquals(ErrorPayload.Code.UNAUTHORIZED, errorPayload.code());
            assertEquals("Unauthorized", errorPayload.message());
        }
        conn.disconnect();
    }

    @Test
    void testAuthenticatedEndpoint_MalformedAuthHeader() throws Exception {
        server.registerAuthenticatedContext("/test/auth", exchange -> {
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        });

        var url = new URI("http://127.0.0.1:" + port + "/test/auth").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Basic " + authToken); // Wrong scheme

        assertEquals(401, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testUnauthenticatedEndpoint_NoTokenRequired() throws Exception {
        server.registerUnauthenticatedContext("/test/public", exchange -> {
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "public"));
        });

        var url = new URI("http://127.0.0.1:" + port + "/test/public").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        // No Authorization header

        assertEquals(200, conn.getResponseCode());
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("public"));
        }
        conn.disconnect();
    }

    @Test
    void testParseJsonRequest() throws Exception {
        server.registerAuthenticatedContext("/test/json", exchange -> {
            var parsed = SimpleHttpServer.parseJsonRequest(exchange, Map.class);
            if (parsed != null && parsed.containsKey("key")) {
                SimpleHttpServer.sendJsonResponse(exchange, Map.of("received", "ok"));
            } else {
                SimpleHttpServer.sendJsonResponse(exchange, 400, ErrorPayload.validationError("Invalid JSON"));
            }
        });

        var url = new URI("http://127.0.0.1:" + port + "/test/json").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        var json = "{\"key\": \"value\"}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(200, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testStructuredErrorResponse() throws Exception {
        server.registerUnauthenticatedContext("/test/error", exchange -> {
            SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
        });

        var url = new URI("http://127.0.0.1:" + port + "/test/error").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(404, conn.getResponseCode());
        try (InputStream is = conn.getErrorStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var errorPayload = objectMapper.readValue(response, ErrorPayload.class);
            assertNotNull(errorPayload);
            assertEquals(ErrorPayload.Code.NOT_FOUND, errorPayload.code());
            assertEquals("Not found", errorPayload.message());
        }
        conn.disconnect();
    }
}
