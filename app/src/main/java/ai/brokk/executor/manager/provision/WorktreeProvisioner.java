package ai.brokk.executor.manager.provision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provisioner that creates isolated git worktrees for each session.
 * Worktrees are created under a base directory, with one subdirectory per session.
 */
public final class WorktreeProvisioner implements Provisioner {
    private static final Logger logger = LogManager.getLogger(WorktreeProvisioner.class);

    private final Path baseDir;
    private final Map<UUID, Path> activeWorktrees = new ConcurrentHashMap<>();

    public WorktreeProvisioner(Path baseDir) {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir must not be null");
        }
        this.baseDir = baseDir;
    }

    @Override
    public Path provision(SessionSpec spec) throws ProvisionException {
        var sessionId = spec.sessionId();

        var existingPath = activeWorktrees.get(sessionId);
        if (existingPath != null) {
            logger.debug("Session {} already provisioned at {}", sessionId, existingPath);
            return existingPath;
        }

        var worktreePath = baseDir.resolve(sessionId.toString());

        if (Files.exists(worktreePath)) {
            logger.info("Reusing existing worktree directory for session {} at {}", sessionId, worktreePath);
            activeWorktrees.put(sessionId, worktreePath);
            return worktreePath;
        }

        logger.info(
                "Provisioning worktree for session {} from repo {} (ref={})", sessionId, spec.repoPath(), spec.ref());

        try {
            var processBuilder = new ProcessBuilder();
            processBuilder.command().add("git");
            processBuilder.command().add("-C");
            processBuilder.command().add(spec.repoPath().toString());
            processBuilder.command().add("worktree");
            processBuilder.command().add("add");
            processBuilder.command().add(worktreePath.toString());

            if (spec.ref() != null && !spec.ref().isBlank()) {
                processBuilder.command().add(spec.ref());
            }

            processBuilder.redirectErrorStream(true);

            logger.debug("Executing: {}", processBuilder.command());
            var process = processBuilder.start();

            var output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new ProvisionException("git worktree add failed with exit code " + exitCode + ": " + output);
            }

            logger.info("Provisioned worktree for session {} at {}", sessionId, worktreePath);
            activeWorktrees.put(sessionId, worktreePath);
            return worktreePath;
        } catch (IOException | InterruptedException e) {
            throw new ProvisionException("Failed to provision worktree for session " + sessionId, e);
        }
    }

    @Override
    public boolean healthcheck() {
        if (!Files.exists(baseDir)) {
            logger.warn("Healthcheck failed: base directory does not exist: {}", baseDir);
            return false;
        }

        if (!Files.isDirectory(baseDir)) {
            logger.warn("Healthcheck failed: base directory is not a directory: {}", baseDir);
            return false;
        }

        if (!Files.isWritable(baseDir)) {
            logger.warn("Healthcheck failed: base directory is not writable: {}", baseDir);
            return false;
        }

        return true;
    }

    @Override
    public void teardown(UUID sessionId) throws ProvisionException {
        var worktreePath = activeWorktrees.remove(sessionId);

        if (worktreePath == null) {
            worktreePath = baseDir.resolve(sessionId.toString());
            if (!Files.exists(worktreePath)) {
                logger.debug("Session {} not found; nothing to tear down", sessionId);
                return;
            }
            logger.info("Tearing down untracked worktree for session {} at {}", sessionId, worktreePath);
        } else {
            logger.info("Tearing down worktree for session {} at {}", sessionId, worktreePath);
        }

        try {
            var processBuilder = new ProcessBuilder();
            processBuilder.command("git", "worktree", "remove", worktreePath.toString(), "--force");
            processBuilder.redirectErrorStream(true);

            logger.debug("Executing: {}", processBuilder.command());
            var process = processBuilder.start();

            var output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                logger.warn(
                        "git worktree remove failed with exit code {} for session {}: {}", exitCode, sessionId, output);
            }

            if (Files.exists(worktreePath)) {
                logger.info("Recursively deleting worktree directory for session {} at {}", sessionId, worktreePath);
                deleteRecursively(worktreePath);
            }

            logger.info("Teardown complete for session {}", sessionId);
        } catch (IOException | InterruptedException e) {
            throw new ProvisionException("Failed to tear down worktree for session " + sessionId, e);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (var child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }

        Files.delete(path);
    }
}
