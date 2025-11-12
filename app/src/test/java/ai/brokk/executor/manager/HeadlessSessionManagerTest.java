package ai.brokk.executor.manager;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessSessionManagerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path repoPath;
    private Path worktreeBaseDir;
    private HeadlessSessionManager manager;
    private String authToken;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        repoPath = tempDir.resolve("test-repo");
        worktreeBaseDir = tempDir.resolve("worktrees");

        Files.createDirectories(repoPath);
        Files.createDirectories(worktreeBaseDir);

        initGitRepo(repoPath);

        authToken = "test-manager-token";
        var executorClasspath = System.getProperty("java.class.path");

        manager = new HeadlessSessionManager(
                UUID.randomUUID(), "127.0.0.1:0", authToken, 2, worktreeBaseDir, executorClasspath);

        manager.start();
        baseUrl = "http://127.0.0.1:" + manager.getPort();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop(2);
        }
    }

    private void initGitRepo(Path repoPath) throws Exception {
        exec(repoPath, "git", "init");
        exec(repoPath, "git", "config", "user.email", "test@example.com");
        exec(repoPath, "git", "config", "user.name", "Test User");

        var brokkDir = repoPath.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Files.writeString(brokkDir.resolve("project.properties"), "# test project\n");

        exec(repoPath, "git", "add", ".brokk/project.properties");
        exec(repoPath, "git", "commit", "-m", "Initial commit");
    }

    private void exec(Path workingDir, String... command) throws Exception {
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDir.toFile());
        processBuilder.redirectErrorStream(true);

        var process = processBuilder.start();
        var output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", command) + "\nOutput: " + output);
        }
    }

    @Test
    void testCreateSession_Success() throws Exception {
        var requestBody = Map.of(
                "name", "Test Session",
                "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(201, conn.getResponseCode());

        var response = OBJECT_MAPPER.readValue(conn.getInputStream(), new TypeReference<Map<String, Object>>() {});

        assertTrue(response.containsKey("sessionId"));
        assertTrue(response.containsKey("state"));
        assertTrue(response.containsKey("token"));

        assertEquals("ready", response.get("state"));

        var sessionId = (String) response.get("sessionId");
        assertDoesNotThrow(() -> UUID.fromString(sessionId));

        var token = (String) response.get("token");
        assertNotNull(token);
        assertFalse(token.isBlank());

        conn.disconnect();
    }

    @Test
    void testCreateSession_MissingName() throws Exception {
        var requestBody = Map.of("repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(400, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testCreateSession_MissingRepoPath() throws Exception {
        var requestBody = Map.of("name", "Test Session");

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(400, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testCreateSession_PoolAtCapacity() throws Exception {
        Map<String, Object> requestBody1 = Map.of("name", "Session 1", "repoPath", repoPath.toString());
        Map<String, Object> requestBody2 = Map.of("name", "Session 2", "repoPath", repoPath.toString());
        var requestBody3 = Map.of("name", "Session 3", "repoPath", repoPath.toString());

        createSessionRequest(requestBody1);
        createSessionRequest(requestBody2);

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody3));
        }

        assertEquals(429, conn.getResponseCode());

        var retryAfter = conn.getHeaderField("Retry-After");
        assertNotNull(retryAfter);
        assertTrue(Integer.parseInt(retryAfter) > 0);

        conn.disconnect();
    }

    @Test
    void testCreateSession_Unauthorized() throws Exception {
        var requestBody = Map.of("name", "Test Session", "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(401, conn.getResponseCode());
        conn.disconnect();
    }

    private void createSessionRequest(Map<String, Object> requestBody) throws Exception {
        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(201, conn.getResponseCode());
        conn.disconnect();
    }
}
