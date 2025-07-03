package io.github.jbellis.brokk;

import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.util.HistoryIo;
import io.github.jbellis.brokk.util.SerialByKeyExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SessionManager implements AutoCloseable
{
    /**
     * Record representing session metadata for the sessions management system.
     */
    public record SessionInfo(UUID id, String name, long created, long modified) {

        public boolean isModified() {
            return created != modified;
        }
    }

    private static final Logger logger = LogManager.getLogger(SessionManager.class);

    private final ExecutorService sessionExecutor;
    private final SerialByKeyExecutor sessionExecutorByKey;
    private final Path sessionsDir;
    private final Map<UUID, SessionInfo> sessionsCache;

    public SessionManager(Path sessionsDir)
    {
        this.sessionsDir = sessionsDir;
        this.sessionExecutor = Executors.newFixedThreadPool(3, r -> {
            var t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(false);
            t.setName("session-io-" + t.threadId());
            return t;
        });
        this.sessionExecutorByKey = new SerialByKeyExecutor(sessionExecutor);
        this.sessionsCache = loadSessions();
    }

    private Map<UUID, SessionInfo> loadSessions() {
        var sessions = new ConcurrentHashMap<UUID, SessionInfo>();
        try {
            Files.createDirectories(sessionsDir);
            try (var stream = Files.list(sessionsDir)) {
                stream.filter(path -> path.toString().endsWith(".zip"))
                      .forEach(zipPath -> readSessionInfoFromZip(zipPath).ifPresent(sessionInfo -> {
                          sessions.put(sessionInfo.id(), sessionInfo);
                      }));
            }
        } catch (IOException e) {
            logger.error("Error listing session zip files in {}: {}", sessionsDir, e.getMessage());
        }
        return sessions;
    }

    public List<SessionInfo> listSessions() {
        var sessions = new ArrayList<>(sessionsCache.values());
        sessions.sort(Comparator.comparingLong(SessionInfo::modified).reversed());
        return sessions;
    }

    public SessionInfo newSession(String name) {
        var sessionId = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();
        var newSessionInfo = new SessionInfo(sessionId, name, currentTime, currentTime);
        sessionsCache.put(sessionId, newSessionInfo);

        sessionExecutorByKey.submit(sessionId.toString(), () -> {
            Path sessionHistoryPath = getSessionHistoryPath(sessionId);
            try {
                Files.createDirectories(sessionHistoryPath.getParent());
                // 1. Create the zip with empty history first. This ensures the zip file exists.
                var emptyHistory = new ContextHistory(Context.EMPTY);
                HistoryIo.writeZip(emptyHistory, sessionHistoryPath);

                // 2. Now add/update manifest.json to the existing zip.
                writeSessionInfoToZip(sessionHistoryPath, newSessionInfo); // Should use create="false" as zip exists.
                logger.info("Created new session {} ({}) with manifest and empty history.", name, sessionId);
            } catch (IOException e) {
                logger.error("Error creating new session files for {} ({}): {}", name, sessionId, e.getMessage());
                throw new UncheckedIOException("Failed to create new session " + name, e);
            }
        });
        return newSessionInfo;
    }

    public void renameSession(UUID sessionId, String newName) {
        SessionInfo oldInfo = sessionsCache.get(sessionId);
        if (oldInfo != null) {
            var updatedInfo = new SessionInfo(oldInfo.id(), newName, oldInfo.created(), System.currentTimeMillis());
            sessionsCache.put(sessionId, updatedInfo);
            sessionExecutorByKey.submit(sessionId.toString(), () -> {
                try {
                    Path sessionHistoryPath = getSessionHistoryPath(sessionId);
                    writeSessionInfoToZip(sessionHistoryPath, updatedInfo);
                    logger.info("Renamed session {} to '{}'", sessionId, newName);
                } catch (IOException e) {
                    logger.error("Error writing updated manifest for renamed session {}: {}", sessionId, e.getMessage());
                }
            });
        } else {
            logger.warn("Session ID {} not found in cache, cannot rename.", sessionId);
        }
    }

    public void deleteSession(UUID sessionId) {
        sessionsCache.remove(sessionId);
        sessionExecutorByKey.submit(sessionId.toString(), () -> {
            Path historyZipPath = getSessionHistoryPath(sessionId);
            try {
                boolean deleted = Files.deleteIfExists(historyZipPath);
                if (deleted) {
                    logger.info("Deleted session zip: {}", historyZipPath.getFileName());
                } else {
                    logger.warn("Session zip {} not found for deletion, or already deleted.", historyZipPath.getFileName());
                }
            } catch (IOException e) {
                logger.error("Error deleting history zip for session {}: {}", sessionId, e.getMessage());
            }
        });
    }

    public SessionInfo copySession(UUID originalSessionId, String newSessionName) throws Exception {
        var newSessionId = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();
        var newSessionInfo = new SessionInfo(newSessionId, newSessionName, currentTime, currentTime);

        var copyFuture = sessionExecutorByKey.submit(originalSessionId.toString(), () -> {
            try {
                Path originalHistoryPath = getSessionHistoryPath(originalSessionId);
                if (!Files.exists(originalHistoryPath)) {
                    throw new IOException("Original session %s not found, cannot copy".formatted(originalHistoryPath.getFileName()));
                }
                Path newHistoryPath = getSessionHistoryPath(newSessionId);
                Files.createDirectories(newHistoryPath.getParent());
                Files.copy(originalHistoryPath, newHistoryPath);
                logger.info("Copied session zip {} to {}", originalHistoryPath.getFileName(), newHistoryPath.getFileName());
            } catch (Exception e) {
                logger.error("Failed to copy session from {} to new session {}:", originalSessionId, newSessionName, e);
                throw new RuntimeException("Failed to copy session " + originalSessionId, e);
            }
        });
        copyFuture.get(); // Wait for copy to complete

        sessionsCache.put(newSessionId, newSessionInfo);
        sessionExecutorByKey.submit(newSessionId.toString(), () -> {
            try {
                Path newHistoryPath = getSessionHistoryPath(newSessionId);
                writeSessionInfoToZip(newHistoryPath, newSessionInfo);
                logger.info("Updated manifest.json in new session zip {} for session ID {}", newHistoryPath.getFileName(), newSessionId);
            } catch (Exception e) {
                logger.error("Failed to update manifest for new session {}:", newSessionName, e);
                throw new RuntimeException("Failed to update manifest for new session " + newSessionName, e);
            }
        });
        return newSessionInfo;
    }

    private Path getSessionHistoryPath(UUID sessionId) {
        return sessionsDir.resolve(sessionId.toString() + ".zip");
    }

    private Optional<SessionInfo> readSessionInfoFromZip(Path zipPath) {
        if (!Files.exists(zipPath)) return Optional.empty();
        try (var fs = FileSystems.newFileSystem(zipPath, Map.of())) {
            Path manifestPath = fs.getPath("manifest.json");
            if (Files.exists(manifestPath)) {
                String json = Files.readString(manifestPath);
                return Optional.of(AbstractProject.objectMapper.readValue(json, SessionInfo.class));
            }
        } catch (IOException e) {
            logger.warn("Error reading manifest.json from {}: {}", zipPath.getFileName(), e.getMessage());
        }
        return Optional.empty();
    }

    private void writeSessionInfoToZip(Path zipPath, SessionInfo sessionInfo) throws IOException {
        try (var fs = FileSystems.newFileSystem(zipPath, Map.of("create", Files.notExists(zipPath) ? "true" : "false"))) {
            Path manifestPath = fs.getPath("manifest.json");
            String json = AbstractProject.objectMapper.writeValueAsString(sessionInfo);
            Files.writeString(manifestPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.error("Error writing manifest.json to {}: {}", zipPath.getFileName(), e.getMessage());
            throw e;
        }
    }

    public void saveHistory(ContextHistory ch, UUID sessionId) {
        // ContextHistory is mutable, take a copy before passing it to an async task
        var contextHistory = new ContextHistory(ch.getHistory(), ch.getResetEdges());
        SessionInfo infoToSave = null;
        SessionInfo currentInfo = sessionsCache.get(sessionId);
        if (currentInfo != null) {
            if (!isSessionEmpty(currentInfo, contextHistory)) {
                infoToSave = new SessionInfo(currentInfo.id(), currentInfo.name(), currentInfo.created(), System.currentTimeMillis());
                sessionsCache.put(sessionId, infoToSave); // Update cache before async task
            } // else, session info is not modified, we are just adding an empty initial context (e.g. welcome message) to the session
        } else {
            logger.warn("Session ID {} not found in cache. History content will be saved, but manifest cannot be updated.", sessionId);
        }

        final SessionInfo finalInfoToSave = infoToSave;
        sessionExecutorByKey.submit(sessionId.toString(), () -> {
            try {
                Path sessionHistoryPath = getSessionHistoryPath(sessionId);
                HistoryIo.writeZip(contextHistory, sessionHistoryPath);
                if (finalInfoToSave != null) {
                    writeSessionInfoToZip(sessionHistoryPath, finalInfoToSave);
                }
            } catch (IOException e) {
                logger.error("Error saving context history or updating manifest for session {}: {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * Checks if the session is empty. The session is considered empty if it has not been modified and
     * if its history has no contexts or only contains the initial empty context.
     */
    public static boolean isSessionEmpty(SessionInfo sessionInfo, @Nullable ContextHistory ch) {
        return !sessionInfo.isModified() && isHistoryEmpty(ch);
    }

    /**
     * Checks if the history is empty.
     * The history is considered empty if it has no contexts or only contains
     * the initial empty context.
     */
    private static boolean isHistoryEmpty(@Nullable ContextHistory history) {
        if (history == null || history.getHistory().isEmpty()) {
            return true;
        }

        // Check if the history only has the initial empty context
        if (history.getHistory().size() == 1) {
            return history.getHistory().getFirst().isEmpty();
        }

        return false;
    }

    @Nullable
    public ContextHistory loadHistory(UUID sessionId, IContextManager contextManager) {
        var future = sessionExecutorByKey.submit(sessionId.toString(), () -> {
            try {
                var sessionHistoryPath = getSessionHistoryPath(sessionId);
                ContextHistory ch = HistoryIo.readZip(sessionHistoryPath, contextManager);
                if (ch == null) {
                    return null;
                }
                // Resetting nextId based on loaded fragments.
                // Only consider numeric IDs for dynamic fragments.
                // Hashes will not parse to int and will be skipped by this logic.
                int maxNumericId = 0;
                for (Context ctx : ch.getHistory()) {
                    for (ContextFragment fragment : ctx.allFragments().toList()) {
                        try {
                            maxNumericId = Math.max(maxNumericId, Integer.parseInt(fragment.id()));
                        } catch (NumberFormatException e) {
                            // Ignore non-numeric IDs (hashes)
                        }
                    }
                    for (TaskEntry taskEntry : ctx.getTaskHistory()) {
                        if (taskEntry.log() != null) {
                            try {
                                // TaskFragment IDs are hashes, so this typically won't contribute to maxNumericId.
                                // If some TaskFragments had numeric IDs historically, this would catch them.
                                maxNumericId = Math.max(maxNumericId, Integer.parseInt(taskEntry.log().id()));
                            } catch (NumberFormatException e) {
                                // Ignore non-numeric IDs
                            }
                        }
                    }
                }
                // ContextFragment.nextId is an AtomicInteger, its value is the *next* ID to be assigned.
                // If maxNumericId found is, say, 10, nextId should be set to 10 so that getAndIncrement() yields 11.
                // If setNextId ensures nextId will be value+1, then passing maxNumericId is correct.
                // Current ContextFragment.setNextId: if (value >= nextId.get()) { nextId.set(value); }
                // Then nextId.getAndIncrement() will use `value` and then increment it.
                // So we should set it to maxNumericId found.
                if (maxNumericId > 0) { // Only set if we found any numeric IDs
                    ContextFragment.setMinimumId(maxNumericId + 1);
                    logger.debug("Restored dynamic fragment ID counter based on max numeric ID: {}", maxNumericId);
                }
                return ch;
            } catch (IOException e) {
                logger.error("Error loading context history for session {}: {}", sessionId, e.getMessage());
                return null;
            }
        });

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.warn("Error waiting for session history to load for session {}:", sessionId, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    public static Optional<String> getActiveSessionTitle(Path worktreeRoot) {
        var wsPropsPath = worktreeRoot.resolve(".brokk").resolve("workspace.properties");
        if (!Files.exists(wsPropsPath)) {
            return Optional.empty();
        }
        var props = new java.util.Properties();
        try (var reader = Files.newBufferedReader(wsPropsPath)) {
            props.load(reader);
        } catch (IOException e) {
            logger.warn("Error reading workspace properties at {}: {}", wsPropsPath, e.getMessage());
            return Optional.empty();
        }
        String sessionIdStr = props.getProperty("lastActiveSession");
        if (sessionIdStr == null || sessionIdStr.isBlank()) {
            return Optional.empty();
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionIdStr.trim());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid session UUID '{}' in workspace properties at {}", sessionIdStr, wsPropsPath);
            return Optional.empty();
        }
        Path masterRootPath;
        if (GitRepo.hasGitRepo(worktreeRoot)) {
            try (var tempRepo = new GitRepo(worktreeRoot)) {
                masterRootPath = tempRepo.getGitTopLevel();
            } catch (Exception e) {
                logger.warn("Error determining git top level for {}: {}", worktreeRoot, e.getMessage());
                return Optional.empty();
            }
        } else {
            masterRootPath = worktreeRoot;
        }
        Path sessionZip = masterRootPath.resolve(".brokk").resolve("sessions").resolve(sessionId + ".zip");
        if (!Files.exists(sessionZip)) {
            logger.debug("Session zip not found at {} for session ID {}", sessionZip, sessionId);
            return Optional.empty();
        }
        try (var fs = FileSystems.newFileSystem(sessionZip, Map.of())) {
            Path manifestPath = fs.getPath("manifest.json");
            if (Files.exists(manifestPath)) {
                String json = Files.readString(manifestPath);
                var sessionInfo = AbstractProject.objectMapper.readValue(json, SessionInfo.class);
                return Optional.of(sessionInfo.name());
            }
        } catch (IOException e) {
            logger.warn("Error reading session manifest from {}: {}", sessionZip.getFileName(), e.getMessage());
        }
        return Optional.empty();
    }


    @Override
    public void close() {
        sessionExecutor.shutdown();
        try {
            if (!sessionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Session IO tasks did not finish in 30 seconds, forcing shutdown.");
                sessionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sessionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
