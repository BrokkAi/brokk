package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.github.BackgroundGitHubAuth;
import ai.brokk.github.DeviceFlowModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.brokk.project.MainProject;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubAuthRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ContextManager contextManager;
    private GitHubAuthRouter router;

    private DeviceFlowModels.DeviceCodeResponse mockResponse;
    private AtomicBoolean starterCalled;
    private AtomicBoolean logoutCalled;
    private BackgroundGitHubAuth.AuthStatus currentStatus;
    private boolean isConnected;
    private boolean isAuthInProgress;
    private String username;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var project = new MainProject(tempDir);
        contextManager = new ContextManager(project);

        starterCalled = new AtomicBoolean(false);
        logoutCalled = new AtomicBoolean(false);
        mockResponse = new DeviceFlowModels.DeviceCodeResponse(
                "dev_123", "user_123", "https://github.com/login/device", "https://github.com/login/device/123", 900, 5);
        currentStatus = new BackgroundGitHubAuth.AuthStatus("IDLE", "Ready");
        isConnected = false;
        isAuthInProgress = false;
        username = null;

        router = new GitHubAuthRouter(
                contextManager,
                () -> mockResponse,
                (resp, cm) -> starterCalled.set(true),
                () -> isConnected,
                () -> isAuthInProgress,
                () -> currentStatus,
                () -> username,
                () -> logoutCalled.set(true));
    }

    @Test
    void handlePostStart_success() throws Exception {
        var exchange = TestHttpExchange.request("POST", "/v1/github/oauth/start");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        assertTrue(starterCalled.get());

        JsonNode body = MAPPER.readTree(exchange.responseBodyBytes());
        assertEquals("started", body.get("status").asText());
        assertEquals(mockResponse.getPreferredVerificationUri(), body.get("verificationUri").asText());
        assertEquals("user_123", body.get("userCode").asText());
        assertTrue(body.get("hasCompleteUri").asBoolean());
    }

    @Test
    void handleGetStatus_connected() throws Exception {
        isConnected = true;
        username = "brokk-user";
        currentStatus = new BackgroundGitHubAuth.AuthStatus("SUCCESS", "All good");

        var exchange = TestHttpExchange.request("GET", "/v1/github/oauth/status");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        JsonNode body = MAPPER.readTree(exchange.responseBodyBytes());
        assertTrue(body.get("connected").asBoolean());
        assertEquals("SUCCESS", body.get("state").asText());
        assertEquals("brokk-user", body.get("username").asText());
    }

    @Test
    void handleGetStatus_disconnected() throws Exception {
        isConnected = false;
        currentStatus = new BackgroundGitHubAuth.AuthStatus("EXPIRED", "Code expired");

        var exchange = TestHttpExchange.request("GET", "/v1/github/oauth/status");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        JsonNode body = MAPPER.readTree(exchange.responseBodyBytes());
        assertFalse(body.get("connected").asBoolean());
        assertEquals("EXPIRED", body.get("state").asText());
        assertFalse(body.has("username"));
    }

    @Test
    void handleDeleteAuthorization_success() throws Exception {
        var exchange = TestHttpExchange.request("DELETE", "/v1/github/oauth/authorization");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        assertTrue(logoutCalled.get());
        JsonNode body = MAPPER.readTree(exchange.responseBodyBytes());
        assertEquals("disconnected", body.get("status").asText());
    }

    @Test
    void handleWrongMethod() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/github/oauth/start");
        router.handle(exchange);
        assertEquals(405, exchange.responseCode());
    }

    @Test
    void handleUnknownPath() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/github/oauth/unknown");
        router.handle(exchange);
        assertEquals(404, exchange.responseCode());
    }
}
