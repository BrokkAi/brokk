package ai.brokk;

import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.concurrent.LoggingExecutorService;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.context.ContextHistory;
import ai.brokk.exception.GlobalExceptionHandler;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.project.AbstractProject;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.HistoryIo;
import ai.brokk.util.SerialByKeyExecutor;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.f4b6a3.uuid.UuidCreator;
import com.github.f4b6a3.uuid.util.UuidUtil;
import com.google.common.base.Splitter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public class SessionManager implements AutoCloseable {
    private static final String SESSIONS_FORMAT_VERSION = "4.0";

    /** Record representing session metadata for the sessions management system. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionInfo(UUID id, String name, long created, long modified, @Nullable String version) {

        public SessionInfo(UUID id, String name, long created, long modified) {
            this(id, name, created, modified, SESSIONS_FORMAT_VERSION);
        }

        @JsonIgnore
        public boolean isSessionModified() {
            return created != modified;
        }

        public Instant createdAt() {
            return Instant.ofEpochMilli(created);
        }

        public Instant lastModified() {
            return Instant.ofEpochMilli(modified);
        }
    }

    public record MinimalSessionInfo(UUID id, Instant createdAt, Instant lastModified) {}

    private static final Logger logger = LogManager.getLogger(SessionManager.class);

    public static final String UNREADABLE_SESSIONS_DIR = "unreadable";

    private static class SessionExecutorThreadFactory implements ThreadFactory {
        private static final ThreadLocal<Boolean> isSessionExecutorThread = ThreadLocal.withInitial(() -> false);
        private final ThreadFactory delegate;

        SessionExecutorThreadFactory() {
            this.delegate = Executors.defaultThreadFactory();
        }

        @Override
        public Thread newThread(Runnable r) {
            Runnable wrappedRunnable = () -> {
                isSessionExecutorThread.set(true);
                try {
                    r.run();
                } finally {
                    isSessionExecutorThread.remove();
                }
            };
            var t = delegate.newThread(wrappedRunnable);
            t.setDaemon(true);
            t.setName("session-io-" + t.threadId());
            return t;
        }

        static boolean isOnSessionExecutorThread() {
            return isSessionExecutorThread.get();
        }
    }

    private final ExecutorService sessionExecutor;
    private final SerialByKeyExecutor sessionExecutorByKey;
    private final Path sessionsDir;
    private final Map<UUID, SessionInfo> sessionsCache;

    private final Set<CompletableFuture<?>> inFlightForeignDownloads = ConcurrentHashMap.newKeySet();

    public SessionManager(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        // Use a CPU-aware pool size to better handle concurrent session I/O in tests and production
        int poolSize = Math.max(4, Runtime.getRuntime().availableProcessors());
        var delegateExecutor = Executors.newFixedThreadPool(poolSize, new SessionExecutorThreadFactory());
        Consumer<Throwable> exceptionHandler = th -> GlobalExceptionHandler.handle(th);
        sessionExecutor = new LoggingExecutorService(delegateExecutor, exceptionHandler);
        this.sessionExecutorByKey = new SerialByKeyExecutor(sessionExecutor);
        this.sessionsCache = loadSessions();
    }

    Map<UUID, SessionInfo> getSessionsCache() {
        return sessionsCache;
    }

    SerialByKeyExecutor getSessionExecutorByKey() {
        return sessionExecutorByKey;
    }

    private Map<UUID, SessionInfo> loadSessions() {
        var sessions = new ConcurrentHashMap<UUID, SessionInfo>();
        try {
            Files.createDirectories(sessionsDir);
            Files.createDirectories(foreignSessionsPath());
            try (var stream = Files.list(sessionsDir)) {
                stream.filter(path -> path.toString().endsWith(".zip"))
                        .forEach(zipPath -> readSessionInfoFromZip(zipPath).ifPresent(sessionInfo -> {
                            if (isVersionSupported(sessionInfo.version())) {
                                sessions.put(sessionInfo.id(), sessionInfo);
                            }
                        }));
            }
        } catch (IOException e) {
            logger.error("Error listing session zip files in {}", sessionsDir, e);
        }
        return sessions;
    }

    private Path foreignSessionsPath() {
        return sessionsDir.resolve("foreign");
    }

    public List<SessionInfo> listSessions() {
        var sessions = new ArrayList<>(sessionsCache.values());
        sessions.sort(Comparator.comparingLong(SessionInfo::modified).reversed());
        return sessions;
    }

    public List<SessionInfo> filterSessions(Instant minBound, Instant maxBound) {
        return listSessions().stream()
                .filter(s -> s.lastModified().isAfter(minBound) && s.createdAt().isBefore(maxBound))
                .toList();
    }

    public List<MinimalSessionInfo> filterForeignSessions(Instant minBound, Instant maxBound) {
        Path foreignDir = foreignSessionsPath();

        record Candidate(Path zipPath, UUID sessionId, Instant createdAt) {}

        try (var stream = Files.list(foreignDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .flatMap(p ->
                            parseUuidFromFilename(p).stream().map(id -> new Candidate(p, id, UuidUtil.getInstant(id))))
                    .filter(c -> c.createdAt().isAfter(minBound.minus(Duration.ofDays(7)))
                            && c.createdAt().isBefore(maxBound))
                    .flatMap(c -> {
                        Instant modified;
                        try {
                            modified = Instant.ofEpochMilli(
                                    Files.getLastModifiedTime(c.zipPath()).toMillis());
                        } catch (IOException e) {
                            logger.warn(
                                    "Error reading mtime for foreign session {}: {}",
                                    c.zipPath().getFileName(),
                                    e.getMessage());
                            return Stream.empty();
                        }

                        return Stream.of(new MinimalSessionInfo(c.sessionId(), c.createdAt(), modified));
                    })
                    .filter(s ->
                            s.lastModified().isAfter(minBound) && s.createdAt().isBefore(maxBound))
                    .toList();
        } catch (IOException e) {
            if (!(e instanceof FileNotFoundException)) {
                logger.warn("Error listing foreign sessions in {}: {}", foreignDir, e.getMessage());
            }
            return List.of();
        }
    }

    public SessionInfo newSession(String name) {
        var sessionId = newSessionId();
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

    static UUID newSessionId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    public boolean renameSession(UUID sessionId, String newName) {
        SessionInfo oldInfo = sessionsCache.get(sessionId);
        if (oldInfo != null) {
            var updatedInfo = new SessionInfo(
                    oldInfo.id(), newName, oldInfo.created(), System.currentTimeMillis(), oldInfo.version());
            sessionsCache.put(sessionId, updatedInfo);
            sessionExecutorByKey.submit(sessionId.toString(), () -> {
                try {
                    Path sessionHistoryPath = getSessionHistoryPath(sessionId);
                    writeSessionInfoToZip(sessionHistoryPath, updatedInfo);
                    logger.info("Renamed session {} to '{}'", sessionId, newName);
                } catch (IOException e) {
                    logger.error(
                            "Error writing updated manifest for renamed session {}: {}", sessionId, e.getMessage());
                }
            });
            return true;
        } else {
            logger.warn("Session ID {} not found in cache, cannot rename.", sessionId);
            return false;
        }
    }

    public void deleteSession(UUID sessionId) {
        sessionsCache.remove(sessionId);
        sessionExecutorByKey.submit(sessionId.toString(), () -> {
            Path historyZipPath = getSessionHistoryPath(sessionId);
            Path tombstonePath = getTombstonePath(sessionId);
            try {
                if (Files.exists(historyZipPath)) {
                    Files.move(historyZipPath, tombstonePath, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Marked session {} for deletion with tombstone.", sessionId);
                } else {
                    // Even if local zip is gone, create tombstone to ensure remote deletion
                    AtomicWrites.save(tombstonePath, "deleted");
                }
            } catch (IOException e) {
                logger.error("Error creating tombstone for session {}: {}", sessionId, e.getMessage());
            }
        });
    }

    private Path getTombstonePath(UUID sessionId) {
        return sessionsDir.resolve(sessionId.toString() + ".tombstone");
    }

    /**
     * Moves a session zip into the 'unreadable' subfolder under the sessions directory. Removes the session from the
     * in-memory cache and performs the file move asynchronously.
     *
     * Note: If called from within a SessionManager executor thread, executes directly to avoid deadlock.
     * Otherwise, submits to the executor and waits for completion.
     */
    public void moveSessionToUnreadable(UUID sessionId) {
        sessionsCache.remove(sessionId);

        // Check for re-entrancy: if we're already on a SessionManager executor thread, execute directly
        if (SessionExecutorThreadFactory.isOnSessionExecutorThread()) {
            moveSessionToUnreadableSync(sessionId);
        } else {
            var future = sessionExecutorByKey.submit(sessionId.toString(), () -> {
                moveSessionToUnreadableSync(sessionId);
                return null;
            });
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Synchronously moves a session zip to the unreadable folder. Must only be called from the executor thread
     * for this session or from a context where it's safe to block on I/O.
     */
    private void moveSessionToUnreadableSync(UUID sessionId) {
        Path historyZipPath = getSessionHistoryPath(sessionId);
        moveZipToUnreadableSync(historyZipPath, sessionId);
    }

    private void moveZipToUnreadableSync(Path zipPath, @Nullable UUID sessionId) {
        Path unreadableDir = sessionsDir.resolve(UNREADABLE_SESSIONS_DIR);
        try {
            Files.createDirectories(unreadableDir);
            Path targetPath = unreadableDir.resolve(zipPath.getFileName());
            Files.move(zipPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            if (sessionId != null) {
                logger.info("Moved session zip {} to {}", zipPath.getFileName(), unreadableDir);
            } else {
                logger.info("Moved unreadable session zip {} to {}", zipPath.getFileName(), unreadableDir);
            }
        } catch (IOException e) {
            if (sessionId != null) {
                logger.error("Error moving history zip for session {} to unreadable: {}", sessionId, e.getMessage());
            } else {
                logger.error("Error moving unreadable history zip {}: {}", zipPath.getFileName(), e.getMessage());
            }
        }
    }

    /** Detailed report of sessions quarantined during a scan. */
    public record QuarantineReport(
            Set<UUID> quarantinedSessionIds, List<String> quarantinedFilesWithoutUuid, int movedCount) {}

    /**
     * Scans the sessions directory for all .zip files, treating any with invalid UUID filenames or missing/invalid
     * manifest.json as unreadable and moving them to the 'unreadable' subfolder. Also attempts to load each valid-UUID
     * session's history to exercise migrations. Returns a report detailing what was quarantined.
     *
     * <p>This runs synchronously; intended to be invoked from a background task.
     */
    public QuarantineReport quarantineUnreadableSessions(IContextManager contextManager) {
        int moved = 0;
        var quarantinedIds = new HashSet<UUID>();
        var quarantinedNoUuid = new ArrayList<String>();

        try (var stream = Files.list(sessionsDir)) {
            for (Path zipPath :
                    stream.filter(p -> p.toString().endsWith(".zip")).toList()) {
                var maybeUuid = parseUuidFromFilename(zipPath);
                if (maybeUuid.isEmpty()) {
                    // Non-UUID filenames are unreadable by definition.
                    moveZipToUnreadable(zipPath);
                    quarantinedNoUuid.add(zipPath.getFileName().toString());
                    moved++;
                    continue;
                }

                var sessionId = maybeUuid.get();
                var info = readSessionInfoFromZip(zipPath);
                if (info.isEmpty()) {
                    moveSessionToUnreadable(sessionId);
                    quarantinedIds.add(sessionId);
                    moved++;
                    continue;
                }
                if (!isVersionSupported(info.get().version())) {
                    continue;
                }

                // Exercise migrations and quarantine if history read fails.
                try {
                    loadHistoryOrQuarantine(sessionId, contextManager);
                } catch (Exception e) {
                    quarantinedIds.add(sessionId);
                    moved++;
                    continue;
                }

                sessionsCache.putIfAbsent(sessionId, info.get());
            }
        } catch (IOException e) {
            logger.error("Error listing session zip files in {}: {}", sessionsDir, e.getMessage());
        }

        return new QuarantineReport(Set.copyOf(quarantinedIds), List.copyOf(quarantinedNoUuid), moved);
    }

    public SessionInfo copySession(UUID originalSessionId, String newSessionName) throws Exception {
        var newSessionId = newSessionId();
        var currentTime = System.currentTimeMillis();
        var newSessionInfo = new SessionInfo(newSessionId, newSessionName, currentTime, currentTime);

        var copyFuture = sessionExecutorByKey.submit(originalSessionId.toString(), () -> {
            try {
                Path originalHistoryPath = getSessionHistoryPath(originalSessionId);
                if (!Files.exists(originalHistoryPath)) {
                    throw new IOException(
                            "Original session %s not found, cannot copy".formatted(originalHistoryPath.getFileName()));
                }
                Path newHistoryPath = getSessionHistoryPath(newSessionId);
                Files.createDirectories(newHistoryPath.getParent());
                Files.copy(originalHistoryPath, newHistoryPath);
                logger.info(
                        "Copied session zip {} to {}", originalHistoryPath.getFileName(), newHistoryPath.getFileName());
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
                logger.info(
                        "Updated manifest.json in new session zip {} for session ID {}",
                        newHistoryPath.getFileName(),
                        newSessionId);
            } catch (Exception e) {
                logger.error("Failed to update manifest for new session {}:", newSessionName, e);
                throw new RuntimeException("Failed to update manifest for new session " + newSessionName, e);
            }
        });
        return newSessionInfo;
    }

    Path getSessionHistoryPath(UUID sessionId) {
        return sessionsDir.resolve(sessionId.toString() + ".zip");
    }

    private Optional<UUID> parseUuidFromFilename(Path zipPath) {
        var fileName = zipPath.getFileName().toString();
        if (!fileName.endsWith(".zip")) {
            return Optional.empty();
        }
        var idPart = fileName.substring(0, fileName.length() - 4);
        try {
            return Optional.of(UUID.fromString(idPart));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    Optional<SessionInfo> readSessionInfoFromZip(Path zipPath) {
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

    void writeSessionInfoToZip(Path zipPath, SessionInfo sessionInfo) throws IOException {
        try (var fs =
                FileSystems.newFileSystem(zipPath, Map.of("create", Files.notExists(zipPath) ? "true" : "false"))) {
            Path manifestPath = fs.getPath("manifest.json");
            String json = AbstractProject.objectMapper.writeValueAsString(sessionInfo);
            Files.writeString(manifestPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.error("Error writing manifest.json to {}: {}", zipPath.getFileName(), e.getMessage());
            throw e;
        }
    }

    private void moveZipToUnreadable(Path zipPath) {
        var future = sessionExecutorByKey.submit(zipPath.toString(), () -> moveZipToUnreadableSync(zipPath, null));
        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private ContextHistory loadHistoryOrQuarantine(UUID sessionId, IContextManager contextManager) throws IOException {
        Path zipPath = resolveSessionHistoryZipPath(sessionId);

        try {
            return HistoryIo.readZip(zipPath, contextManager);
        } catch (IOException | StackOverflowError e) {
            quarantineUnreadableSessionZip(sessionId, zipPath);
            throw e;
        }
    }

    public void saveHistory(ContextHistory ch, UUID sessionId) {
        // ContextHistory is mutable, take a copy before passing it to an async task
        var contextHistory = new ContextHistory(
                ch.getHistory(),
                ch.getResetEdges(),
                ch.getGitStates(),
                ch.getEntryInfos(),
                ch.getContextToGroupId(),
                ch.getGroupLabels());
        SessionInfo infoToSave = null;
        SessionInfo currentInfo = sessionsCache.get(sessionId);
        if (currentInfo != null) {
            if (!isSessionEmpty(currentInfo, contextHistory)) {
                infoToSave = new SessionInfo(
                        currentInfo.id(),
                        currentInfo.name(),
                        currentInfo.created(),
                        System.currentTimeMillis(),
                        currentInfo.version());
                sessionsCache.put(sessionId, infoToSave); // Update cache before async task
            } // else, session info is not modified, we are just adding an empty initial context (e.g. welcome message)
            // to the session
        } else {
            logger.warn(
                    "Session ID {} not found in cache. History content will be saved, but manifest cannot be updated.",
                    sessionId);
        }

        final SessionInfo finalInfoToSave = infoToSave;
        sessionExecutorByKey.submit(sessionId.toString(), () -> {
            try {
                Path sessionHistoryPath = getSessionHistoryPath(sessionId);

                // Rewrite history zip
                HistoryIo.writeZip(contextHistory, sessionHistoryPath);

                // Write manifest after the rewrite
                if (finalInfoToSave != null) {
                    writeSessionInfoToZip(sessionHistoryPath, finalInfoToSave);
                }
            } catch (IOException e) {
                logger.error(
                        "Error saving context history or updating manifest for session {}: {}",
                        sessionId,
                        e.getMessage());
            }
        });
    }

    /**
     * Checks if the session is empty. The session is considered empty if it has not been modified and if its history
     * has no contexts or only contains the initial empty context.
     */
    public static boolean isSessionEmpty(SessionInfo sessionInfo, @Nullable ContextHistory ch) {
        return !sessionInfo.isSessionModified() && isHistoryEmpty(ch);
    }

    /**
     * Checks if the history is empty. The history is considered empty if it has no contexts or only contains the
     * initial empty context.
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

    @Blocking
    @Nullable
    public ContextHistory loadHistory(UUID sessionId, IContextManager contextManager) {
        var future = sessionExecutorByKey.submit(
                sessionId.toString(), () -> loadHistoryOrQuarantine(sessionId, contextManager));

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.warn("Error waiting for session history to load for session {}:", sessionId, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // tryLoadHistoryOrQuarantine already quarantines on failure.
            return null;
        }
    }

    /**
     * Counts AI responses for a session without loading full history.
     * This is much faster than loadHistory() for just getting the count.
     * Supports both local and foreign sessions.
     */
    @Blocking
    public int countAiResponses(UUID sessionId) {
        try {
            Path zipPath = resolveSessionHistoryZipPath(sessionId);
            return HistoryIo.countAiResponses(zipPath);
        } catch (IOException e) {
            logger.warn("Failed to count AI responses for session {}", sessionId, e);
            return 0;
        }
    }

    /**
     * Counts incomplete tasks for a session without loading full history.
     * Returns TaskCounts with total and incomplete counts.
     */
    @Blocking
    public HistoryIo.TaskCounts countIncompleteTasks(UUID sessionId) {
        try {
            Path zipPath = resolveSessionHistoryZipPath(sessionId);
            return HistoryIo.countIncompleteTasks(zipPath);
        } catch (IOException e) {
            logger.warn("Failed to count incomplete tasks for session {}", sessionId, e);
            return new HistoryIo.TaskCounts(0, 0);
        }
    }

    /**
     * Counts both AI responses and tasks for a session in a single pass.
     * More efficient than calling countAiResponses() and countIncompleteTasks() separately.
     * Returns SessionCounts with both AI response count and task counts.
     */
    @Blocking
    public HistoryIo.SessionCounts countSessionStats(UUID sessionId) {
        try {
            Path zipPath = resolveSessionHistoryZipPath(sessionId);
            return HistoryIo.countSessionStats(zipPath);
        } catch (IOException e) {
            logger.warn("Failed to count session stats for session {}", sessionId, e);
            return new HistoryIo.SessionCounts(0, new HistoryIo.TaskCounts(0, 0));
        }
    }

    @Blocking
    @Nullable
    public ContextHistory loadHistoryAndRefresh(UUID sessionId, IContextManager contextManager) {
        var ch = loadHistory(sessionId, contextManager);
        if (ch == null) {
            return null;
        }

        var refreshed = ch.liveContext().copyAndRefresh();
        if (!refreshed.equals(ch.liveContext())) {
            ch.pushContext(refreshed);
        }

        return ch;
    }

    private Path resolveSessionHistoryZipPath(UUID sessionId) throws FileNotFoundException {
        var localPath = getSessionHistoryPath(sessionId);
        if (Files.exists(localPath)) {
            return localPath;
        }

        try {
            awaitForeignDownloads();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        var foreignPath = foreignSessionsPath().resolve(sessionId.toString() + ".zip");
        if (Files.exists(foreignPath)) {
            return foreignPath;
        }

        throw new FileNotFoundException("Session history zip not found for session " + sessionId);
    }

    private void quarantineUnreadableSessionZip(UUID sessionId, Path zipPath) {
        sessionsCache.remove(sessionId);

        if (SessionExecutorThreadFactory.isOnSessionExecutorThread()) {
            moveZipToUnreadableSync(zipPath, sessionId);
        } else {
            var future = sessionExecutorByKey.submit(sessionId.toString(), () -> {
                moveZipToUnreadableSync(zipPath, sessionId);
                return null;
            });
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Internal helpers for synchronous tasklist read/write. These avoid re-entrancy issues when called
     * inside the per-session serialized executor and allow saveHistory to preserve exact JSON.
     * Deprecated: Only used during migration from legacy JSON to fragment-backed storage.
     * Will be removed in a future release.
     **/
    @Deprecated
    private @Nullable String readTaskListJson(Path zipPath) throws IOException {
        if (!Files.exists(zipPath)) {
            return null;
        }
        try (var fs = FileSystems.newFileSystem(zipPath, Map.of())) {
            Path taskListPath = fs.getPath("tasklist.json");
            if (Files.exists(taskListPath)) {
                return Files.readString(taskListPath);
            }
            return null;
        }
    }

    /**
     * Asynchronously read the legacy task list (tasklist.json) from the session's zip.
     * Deprecated: Only used during migration from legacy JSON to fragment-backed storage.
     * Will be removed in a future release.
     *
     * <p>Concurrency: submitted via {@link SerialByKeyExecutor} using {@code sessionId.toString()} so reads of the same
     * session are ordered with respect to writes/reads for that session, while reads on different sessions may run in
     * parallel.
     *
     * <pre>{@code
     * // Serialized with other work for the same session:
     * sessionExecutorByKey.submit(sessionId.toString(), () -> {
     *     // ... open the session zip and read tasklist.json ...
     *     return new TaskListData(List.of());
     * });
     * }</pre>
     */
    @Deprecated
    public CompletableFuture<TaskList.TaskListData> readTaskList(UUID sessionId) {
        Path zipPath = getSessionHistoryPath(sessionId);
        return sessionExecutorByKey.submit(sessionId.toString(), () -> {
            if (!Files.exists(zipPath)) {
                return new TaskList.TaskListData(List.of());
            }
            try {
                String json = readTaskListJson(zipPath);
                if (json == null || json.isBlank()) {
                    return new TaskList.TaskListData(List.of());
                }
                var loaded = AbstractProject.objectMapper.readValue(json, TaskList.TaskListData.class);
                return new TaskList.TaskListData(List.copyOf(loaded.tasks()));
            } catch (IOException e) {
                logger.warn("Error reading task list for session {}: {}", sessionId, e.getMessage());
                return new TaskList.TaskListData(List.of());
            }
        });
    }

    /**
     * Asynchronously deletes the legacy tasklist.json from the session's zip file.
     * Deprecated: Only used during migration from legacy JSON to fragment-backed storage.
     * Will be removed in a future release.
     * This is a cleanup step after migrating to fragment-based storage, where the Task List
     * is stored as a StringFragment in Context. If the session zip or tasklist.json does not
     * exist, this operation is a no-op.
     * Concurrency: Executed via SerialByKeyExecutor using the session UUID string as the key,
     * ensuring per-session serialization and alignment with other session I/O.
     *
     * @param sessionId the session ID whose legacy task list is to be deleted
     * @return a CompletableFuture that completes when the deletion attempt has finished
     */
    @Deprecated
    public CompletableFuture<Void> deleteTaskList(UUID sessionId) {
        Path zipPath = getSessionHistoryPath(sessionId);
        return sessionExecutorByKey.submit(sessionId.toString(), () -> {
            if (!Files.exists(zipPath)) {
                // No zip to clean; treat as success
                return null;
            }
            try (var fs = FileSystems.newFileSystem(zipPath, Map.of())) {
                Path taskListPath = fs.getPath("tasklist.json");
                try {
                    Files.deleteIfExists(taskListPath);
                } catch (IOException e) {
                    logger.warn("Error deleting tasklist.json for session {}: {}", sessionId, e.getMessage());
                }
            } catch (IOException e) {
                logger.warn(
                        "Error opening session zip {} while deleting tasklist.json: {}",
                        zipPath.getFileName(),
                        e.getMessage());
            }
            return null;
        });
    }

    public static Optional<String> getActiveSessionTitle(Path worktreeRoot) {
        var wsPropsPath =
                worktreeRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.WORKSPACE_PROPERTIES_FILE);
        if (!Files.exists(wsPropsPath)) {
            return Optional.empty();
        }
        var props = new Properties();
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
        if (GitRepoFactory.hasGitRepo(worktreeRoot)) {
            try (var tempRepo = new GitRepo(worktreeRoot)) {
                masterRootPath = tempRepo.getGitTopLevel();
            } catch (Exception e) {
                logger.warn("Error determining git top level for {}: {}", worktreeRoot, e.getMessage());
                return Optional.empty();
            }
        } else {
            masterRootPath = worktreeRoot;
        }
        Path sessionZip = masterRootPath
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.SESSIONS_DIR)
                .resolve(sessionId + ".zip");
        if (!Files.exists(sessionZip)) {
            logger.trace("Session zip not found at {} for session ID {}", sessionZip, sessionId);
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

    public Path getSessionsDir() {
        return sessionsDir;
    }

    // deliberately fire-and-forget, if it doesn't work then Guided Review will still function
    public CompletableFuture<Service.RemoteSessionMeta> makePublicAsync(UUID sessionId) {
        return LoggingFuture.supplyCallableAsync(() -> Service.updateSessionSharing(sessionId, "public"));
    }

    @Blocking
    public Path downloadForeign(UUID sessionId) throws IOException {
        byte[] content = Service.getRemoteSessionContent(sessionId);

        Path foreignDir = foreignSessionsPath();
        Files.createDirectories(foreignDir);

        Path destination = foreignDir.resolve(sessionId + ".zip");
        AtomicWrites.save(destination, content);

        return destination;
    }

    /**
     * Downloads a foreign session asynchronously and tracks it so that UI actions can await completion.
     */
    @SuppressWarnings("CollectionUndefinedEquality") // Reference equality is intentional
    public CompletableFuture<Path> downloadForeignAsync(UUID sessionId) {
        var future = LoggingFuture.supplyCallableVirtual(() -> downloadForeign(sessionId));
        inFlightForeignDownloads.add(future);
        future.whenComplete((unused, throwable) -> inFlightForeignDownloads.remove(future));
        return future;
    }

    /**
     * Blocks until all currently in-flight foreign session downloads (triggered by PR checkouts) are complete,
     * up to a maximum of 5 seconds.
     */
    @Blocking
    public void awaitForeignDownloads() throws InterruptedException {
        var futures = inFlightForeignDownloads.toArray(new CompletableFuture[0]);
        if (futures.length > 0) {
            logger.debug("Waiting for {} in-flight foreign session downloads...", futures.length);
            try {
                CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.debug("Timeout while waiting for foreign session downloads");
            } catch (ExecutionException e) {
                logger.debug("Error while waiting for foreign session downloads", e);
            }
        }
    }

    private static boolean isVersionSupported(@Nullable String version) {
        if (version == null) {
            return true;
        }
        try {
            return compareVersions(version, SESSIONS_FORMAT_VERSION) <= 0;
        } catch (NumberFormatException e) {
            logger.warn("Cannot parse session format version '{}'", version);
            return false;
        }
    }

    static int compareVersions(String v1, String v2) throws NumberFormatException {
        List<String> parts1 = Splitter.on('.').splitToList(v1);
        List<String> parts2 = Splitter.on('.').splitToList(v2);
        int length = Math.max(parts1.size(), parts2.size());
        for (int i = 0; i < length; i++) {
            int p1 = i < parts1.size() ? Integer.parseInt(parts1.get(i)) : 0;
            int p2 = i < parts2.size() ? Integer.parseInt(parts2.get(i)) : 0;
            if (p1 < p2) {
                return -1;
            }
            if (p1 > p2) {
                return 1;
            }
        }
        return 0;
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
