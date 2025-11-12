package ai.brokk.executor.manager.exec;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.executor.manager.provision.SessionSpec;
import ai.brokk.executor.manager.provision.WorktreeProvisioner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutorPoolTest {

    @TempDir
    Path tempDir;

    private Path repoPath;
    private Path worktreeBaseDir;
    private String executorClasspath;
    private ExecutorPool pool;

    @BeforeEach
    void setUp() throws Exception {
        repoPath = tempDir.resolve("test-repo");
        worktreeBaseDir = tempDir.resolve("worktrees");
        executorClasspath = resolveExecutorClasspath();

        Files.createDirectories(repoPath);
        Files.createDirectories(worktreeBaseDir);

        initGitRepo(repoPath);

        var provisioner = new WorktreeProvisioner(worktreeBaseDir);
        pool = new ExecutorPool(provisioner, executorClasspath);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownAll();
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

    private String resolveExecutorClasspath() {
        var classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Cannot resolve java.class.path");
        }
        return classpath;
    }

    @Test
    void testSpawnExecutorAndVerifyHealthEndpoints() throws Exception {
        var sessionId = UUID.randomUUID();
        var spec = new SessionSpec(sessionId, repoPath, null);

        var handle = pool.spawn(spec);

        assertNotNull(handle);
        assertEquals(sessionId, handle.sessionId());
        assertNotNull(handle.execId());
        assertNotNull(handle.authToken());
        assertTrue(handle.port() > 0);
        assertTrue(handle.process().isAlive());

        var baseUrl = "http://" + handle.host() + ":" + handle.port();
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        var liveRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/health/live"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        var liveResponse = client.send(liveRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, liveResponse.statusCode());
        assertTrue(liveResponse.body().contains("execId"));
        assertTrue(liveResponse.body().contains(handle.execId().toString()));

        var readyRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/health/ready"))
        .GET()
        .timeout(Duration.ofSeconds(5))
        .build();
        
        var readyResponse = client.send(readyRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, readyResponse.statusCode());
    }

    @Test
    void testSpawnIsIdempotent() throws Exception {
        var sessionId = UUID.randomUUID();
        var spec = new SessionSpec(sessionId, repoPath, null);

        var handle1 = pool.spawn(spec);
        var handle2 = pool.spawn(spec);

        assertEquals(handle1.execId(), handle2.execId());
        assertEquals(handle1.port(), handle2.port());
        assertEquals(handle1.authToken(), handle2.authToken());
    }

    @Test
    void testShutdownStopsExecutor() throws Exception {
        var sessionId = UUID.randomUUID();
        var spec = new SessionSpec(sessionId, repoPath, null);

        var handle = pool.spawn(spec);
        assertTrue(handle.process().isAlive());

        boolean shutdownResult = pool.shutdown(sessionId);
        assertTrue(shutdownResult);

        Thread.sleep(1000);

        assertFalse(handle.process().isAlive());
        assertNull(pool.get(sessionId));
    }

    @Test
    void testShutdownAllStopsAllExecutors() throws Exception {
        var sessionId1 = UUID.randomUUID();
        var sessionId2 = UUID.randomUUID();
        var spec1 = new SessionSpec(sessionId1, repoPath, null);
        var spec2 = new SessionSpec(sessionId2, repoPath, null);

        var handle1 = pool.spawn(spec1);
        var handle2 = pool.spawn(spec2);

        assertEquals(2, pool.size());

        pool.shutdownAll();

        Thread.sleep(1000);

        assertFalse(handle1.process().isAlive());
        assertFalse(handle2.process().isAlive());
        assertEquals(0, pool.size());
    }

    @Test
    void testConstructorRejectsNullProvisioner() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutorPool(null, executorClasspath));
    }

    @Test
    void testConstructorRejectsNullOrBlankClasspath() throws IOException {
        var provisioner = new WorktreeProvisioner(worktreeBaseDir);
        assertThrows(IllegalArgumentException.class, () -> new ExecutorPool(provisioner, null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutorPool(provisioner, ""));
        assertThrows(IllegalArgumentException.class, () -> new ExecutorPool(provisioner, "   "));
    }

    @Test
    void testEvictIdleExecutors() throws Exception {
        var sessionId = UUID.randomUUID();
        var spec = new SessionSpec(sessionId, repoPath, null);
        var handle = pool.spawn(spec);
        assertNotNull(handle);
        
        Thread.sleep(50);
        
        int evicted = pool.evictIdle(java.time.Duration.ofMillis(1));
        assertEquals(1, evicted);
        assertEquals(0, pool.size());
        
        Thread.sleep(1000);
        
        assertFalse(handle.process().isAlive());
    }
}
