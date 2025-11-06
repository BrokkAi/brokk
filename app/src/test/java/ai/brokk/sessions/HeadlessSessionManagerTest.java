package ai.brokk.sessions;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HeadlessSessionManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void testSpawnExecutorAndShutdown() throws Exception {
        var manager = new HeadlessSessionManager();
        var sessionId = UUID.randomUUID();
        var worktreePath = tempDir.resolve("worktree");
        var sessionsDir = tempDir.resolve(".brokk").resolve("sessions");
        Files.createDirectories(worktreePath);
        Files.createDirectories(sessionsDir);

        var sessionInfo = manager.spawnExecutor(sessionId, worktreePath, sessionsDir);

        assertNotNull(sessionInfo, "SessionInfo should not be null");
        assertTrue(sessionInfo.port() > 0, "Port should be assigned");
        assertNotNull(sessionInfo.authToken(), "Auth token should be generated");
        assertFalse(sessionInfo.authToken().isEmpty(), "Auth token should not be empty");

        manager.shutdownExecutor(sessionInfo);

        var terminated = sessionInfo.process().waitFor(5, TimeUnit.SECONDS);
        assertTrue(terminated, "Process should terminate within timeout");
    }

    @Test
    void testShutdownAlreadyTerminatedProcessDoesNotThrow() throws Exception {
        var manager = new HeadlessSessionManager();

        var javaHome = System.getProperty("java.home");
        var javaBin = Path.of(javaHome, "bin", "java").toString();
        var process = new ProcessBuilder(javaBin, "-version").start();
        var exited = process.waitFor(5, TimeUnit.SECONDS);
        assertTrue(exited, "Java -version should exit quickly");

        var sessionId = UUID.randomUUID();
        var worktreePath = tempDir.resolve("dummy-worktree");
        var sessionInfo = new SessionInfo(
                sessionId,
                "Test Session",
                worktreePath,
                "session/test",
                8080,
                "dummy-token",
                process,
                System.currentTimeMillis(),
                System.currentTimeMillis());

        assertDoesNotThrow(() -> manager.shutdownExecutor(sessionInfo), "Should not throw for already terminated process");
    }
}
