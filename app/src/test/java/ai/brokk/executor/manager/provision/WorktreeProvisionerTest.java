package ai.brokk.executor.manager.provision;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeProvisionerTest {

    @TempDir
    Path tempDir;

    private Path repoPath;
    private Path worktreeBaseDir;

    @BeforeEach
    void setUp() throws Exception {
        repoPath = tempDir.resolve("test-repo");
        worktreeBaseDir = tempDir.resolve("worktrees");

        Files.createDirectories(repoPath);
        Files.createDirectories(worktreeBaseDir);

        initGitRepo(repoPath);
    }

    private void initGitRepo(Path repoPath) throws Exception {
        exec(repoPath, "git", "init");
        exec(repoPath, "git", "config", "user.email", "test@example.com");
        exec(repoPath, "git", "config", "user.name", "Test User");

        var testFile = repoPath.resolve("test.txt");
        Files.writeString(testFile, "Hello, World!");

        exec(repoPath, "git", "add", "test.txt");
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
    void testProvisionCreatesWorktree() throws Exception {
        var provisioner = new WorktreeProvisioner(worktreeBaseDir);
        var sessionId = UUID.randomUUID();
        var spec = new SessionSpec(sessionId, repoPath, null);

        var workspacePath = provisioner.provision(spec);

        assertNotNull(workspacePath);
        assertTrue(Files.exists(workspacePath));
        assertTrue(Files.isDirectory(workspacePath));

        var testFile = workspacePath.resolve("test.txt");
        assertTrue(Files.exists(testFile));
        assertEquals("Hello, World!", Files.readString(testFile));
    }

    @Test
    void testProvisionIsIdempotent() throws Exception {
        var provisioner = new WorktreeProvisioner(worktreeBaseDir);
        var sessionId = UUID.randomUUID();
        var spec = new SessionSpec(sessionId, repoPath, null);

        var workspacePath1 = provisioner.provision(spec);
        var workspacePath2 = provisioner.provision(spec);

        assertEquals(workspacePath1, workspacePath2);
    }

    @Test
    void testTeardownRemovesWorktree() throws Exception {
        var provisioner = new WorktreeProvisioner(worktreeBaseDir);
        var sessionId = UUID.randomUUID();
        var spec = new SessionSpec(sessionId, repoPath, null);

        var workspacePath = provisioner.provision(spec);
        assertTrue(Files.exists(workspacePath));

        provisioner.teardown(sessionId);

        assertFalse(Files.exists(workspacePath));
    }

    @Test
    void testTeardownIsIdempotent() throws Exception {
        var provisioner = new WorktreeProvisioner(worktreeBaseDir);
        var sessionId = UUID.randomUUID();
        var spec = new SessionSpec(sessionId, repoPath, null);

        var workspacePath = provisioner.provision(spec);
        provisioner.teardown(sessionId);

        assertDoesNotThrow(() -> provisioner.teardown(sessionId));
    }

    @Test
    void testHealthcheckSucceedsWhenBaseDirExists() throws Exception {
        var provisioner = new WorktreeProvisioner(worktreeBaseDir);

        assertTrue(provisioner.healthcheck());
    }

    @Test
    void testHealthcheckFailsWhenBaseDirDoesNotExist() throws Exception {
        var nonExistentDir = worktreeBaseDir.resolve("does-not-exist");
        var provisioner = new WorktreeProvisioner(nonExistentDir);

        assertFalse(provisioner.healthcheck());
    }

    @Test
    void testProvisionWithSpecificRef() throws Exception {
        exec(repoPath, "git", "checkout", "-b", "test-branch");
        var branchFile = repoPath.resolve("branch.txt");
        Files.writeString(branchFile, "Branch content");
        exec(repoPath, "git", "add", "branch.txt");
        exec(repoPath, "git", "commit", "-m", "Branch commit");
        exec(repoPath, "git", "checkout", "master");

        var provisioner = new WorktreeProvisioner(worktreeBaseDir);
        var sessionId = UUID.randomUUID();
        var spec = new SessionSpec(sessionId, repoPath, "test-branch");

        var workspacePath = provisioner.provision(spec);

        var branchFileInWorktree = workspacePath.resolve("branch.txt");
        assertTrue(Files.exists(branchFileInWorktree));
        assertEquals("Branch content", Files.readString(branchFileInWorktree));
    }

    @Test
    void testConstructorRejectsNullBaseDir() {
        assertThrows(IllegalArgumentException.class, () -> new WorktreeProvisioner(null));
    }

    @Test
    void testSessionSpecRejectsNullSessionId() {
        assertThrows(IllegalArgumentException.class, () -> new SessionSpec(null, repoPath, null));
    }

    @Test
    void testSessionSpecRejectsNullRepoPath() {
        assertThrows(IllegalArgumentException.class, () -> new SessionSpec(UUID.randomUUID(), null, null));
    }
}
