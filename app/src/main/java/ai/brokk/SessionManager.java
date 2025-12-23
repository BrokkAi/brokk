package ai.brokk;

import ai.brokk.Service.RemoteSessionMeta;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextHistory;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.HistoryIo;
import ai.brokk.util.SerialByKeyExecutor;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.f4b6a3.uuid.UuidCreator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public class SessionManager implements AutoCloseable {
    /** Record representing session metadata for the sessions management system. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionInfo(UUID id, String name, long created, long modified) {

        @JsonIgnore
        public boolean isSessionModified() {
            return created != modified;
        }
    }

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
    private final IProject project;

    public SessionManager(IProject project, Path sessionsDir) {
        this.project = project;
        this.sessionsDir = sessionsDir;
        // Use a CPU-aware pool size to better handle concurrent session I/O in tests and production
        int poolSize = Math.max(4, Runtime.getRuntime().availableProcessors());
        this.sessionExecutor = Executors.newFixedThreadPool(poolSize, new SessionExecutorThreadFactory());
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
                    logger.error(
                            "Error writing updated manifest for renamed session {}: {}", sessionId, e.getMessage());
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
            Path tombstonePath = getTombstonePath(sessionId);
            try {
                if (Files.exists(historyZipPath)) {
                    Files.move(historyZipPath, tombstonePath, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Marked session {} for deletion with tombstone.", sessionId);
                } else {
                    // Even if local zip is gone, create tombstone to ensure remote deletion
                    Files.writeString(
                            tombstonePath, "deleted", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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
        Path unreadableDir = sessionsDir.resolve(UNREADABLE_SESSIONS_DIR);
        try {
            Files.createDirectories(unreadableDir);
            Path targetPath = unreadableDir.resolve(historyZipPath.getFileName());
            Files.move(historyZipPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Moved session zip {} to {}", historyZipPath.getFileName(), unreadableDir);
        } catch (IOException e) {
            logger.error("Error moving history zip for session {} to unreadable: {}", sessionId, e.getMessage());
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
    public QuarantineReport quarantineUnreadableSessions(ContextManager contextManager) {
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

    private Path getSessionHistoryPath(UUID sessionId) {
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
        var future = sessionExecutorByKey.submit(zipPath.toString(), () -> {
            Path unreadableDir = sessionsDir.resolve(UNREADABLE_SESSIONS_DIR);
            try {
                Files.createDirectories(unreadableDir);
                Path targetPath = unreadableDir.resolve(zipPath.getFileName());
                Files.move(zipPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Moved unreadable session zip {} to {}", zipPath.getFileName(), unreadableDir);
            } catch (IOException e) {
                logger.error("Error moving unreadable history zip {}: {}", zipPath.getFileName(), e.getMessage());
            }
        });
        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private ContextHistory loadHistoryOrQuarantine(UUID sessionId, IContextManager contextManager) throws Exception {
        try {
            return loadHistoryInternal(sessionId, contextManager);
        } catch (Exception | StackOverflowError e) {
            moveSessionToUnreadable(sessionId);
            throw new Exception("Cannot read session history.", e);
        }
    }

    public void saveHistory(ContextHistory ch, UUID sessionId) {
        // ContextHistory is mutable, take a copy before passing it to an async task
        var contextHistory =
                new ContextHistory(ch.getHistory(), ch.getResetEdges(), ch.getGitStates(), ch.getEntryInfos());
        SessionInfo infoToSave = null;
        SessionInfo currentInfo = sessionsCache.get(sessionId);
        if (currentInfo != null) {
            if (!isSessionEmpty(currentInfo, contextHistory)) {
                infoToSave = new SessionInfo(
                        currentInfo.id(), currentInfo.name(), currentInfo.created(), System.currentTimeMillis());
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
     */
    @Blocking
    public int countAiResponses(UUID sessionId) {
        var zipPath = getSessionHistoryPath(sessionId);
        try {
            return HistoryIo.countAiResponses(zipPath);
        } catch (IOException e) {
            logger.warn("Failed to count AI responses for session {}", sessionId, e);
            return 0;
        }
    }

    @Blocking
    @Nullable
    public ContextHistory loadHistoryAndRefresh(UUID sessionId, IContextManager contextManager) {
        var ch = loadHistory(sessionId, contextManager);
        if (ch == null) {
            return null;
        }

        var refreshed = ch.liveContext().copyAndRefresh("Load External Changes");
        if (!refreshed.equals(ch.liveContext())) {
            ch.pushContext(refreshed);
        }

        return ch;
    }

    private static boolean promptCopySessionBeforeChanging(ContextManager cm, String title, String message) {
        Integer choice = SwingUtil.runOnEdt(
                () -> cm.getIo()
                        .showConfirmDialog(message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE),
                JOptionPane.NO_OPTION);
        if (choice == null) {
            logger.debug("Prompt for remote session decision failed; defaulting to 'Copy current'");
            return true;
        }
        return choice == JOptionPane.YES_OPTION;
    }

    public void synchronizeRemoteSessions() {
        try {
            String key = project.getMainProject().getBrokkKey();
            if (key.isBlank()) {
                return;
            }

            String remoteProject = project.getRemoteProjectName();
            if (remoteProject.isBlank()) {
                return;
            }

            List<RemoteSessionMeta> remoteMetas = Service.listRemoteSessions(remoteProject);
            Files.createDirectories(sessionsDir);

            Map<UUID, RemoteSessionMeta> remoteSessionsMap = remoteMetas.stream()
                    .collect(Collectors.toMap(meta -> UUID.fromString(meta.id()), meta -> meta, (a, b) -> a));

            // 1. Process tombstones (local deletions that need to go to remote)
            try (var stream = Files.list(sessionsDir)) {
                List<Path> tombstones = stream.filter(path -> path.toString().endsWith(".tombstone"))
                        .toList();

                for (Path tombstone : tombstones) {
                    String fileName = tombstone.getFileName().toString();
                    String idStr = fileName.substring(0, fileName.length() - ".tombstone".length());
                    try {
                        UUID id = UUID.fromString(idStr);
                        RemoteSessionMeta remoteMeta = remoteSessionsMap.get(id);
                        // Only call remote delete if it's not already marked deleted there
                        if (remoteMeta != null && remoteMeta.deletedAt() == null) {
                            sessionExecutorByKey
                                    .submit(id.toString(), () -> {
                                        try {
                                            Service.deleteRemoteSession(id);
                                            logger.debug("Deleted session {} from remote via tombstone", id);
                                        } catch (IOException e) {
                                            logger.warn(
                                                    "Failed to delete session {} from remote: {}", id, e.getMessage());
                                        }
                                    })
                                    .join();
                        }
                        Files.delete(tombstone);
                    } catch (IllegalArgumentException | IOException e) {
                        logger.warn("Error processing tombstone {}: {}", tombstone, e.getMessage());
                    }
                }
            }

            // 2. Bidirectional sync
            Map<UUID, SessionInfo> localSessions = new HashMap<>(sessionsCache);

            for (var entry : remoteSessionsMap.entrySet()) {
                UUID id = entry.getKey();
                RemoteSessionMeta remoteMeta = entry.getValue();
                SessionInfo localInfo = localSessions.get(id);

                // Find if this session is currently active in any Chrome/ContextManager
                Optional<ContextManager> activeCm = Brokk.getProjectAndWorktreeChromes(project).stream()
                        .map(Chrome::getContextManager)
                        .filter(cm -> id.equals(cm.getCurrentSessionId()))
                        .findFirst();

                if (remoteMeta.deletedAt() != null) {
                    // Session deleted on remote
                    if (localInfo != null || activeCm.isPresent()) {
                        if (activeCm.isPresent()) {
                            handleOpenSessionDeletion(
                                    activeCm.get(),
                                    localInfo != null ? localInfo : new SessionInfo(id, remoteMeta.name(), 0, 0),
                                    id);
                        } else {
                            deleteLocalSessionAndWait(id);
                        }
                    }
                } else if (shouldDownloadRemoteSession(localInfo, remoteMeta)) {
                    // Remote is newer or local doesn't exist
                    if (activeCm.isPresent()
                            && localInfo != null
                            && localInfo.modified() < remoteMeta.modifiedAtMillis()) {
                        handleOpenSessionDownload(activeCm.get(), localInfo, id);
                    } else {
                        downloadSessionAndWait(id);
                    }
                } else if (localInfo != null && localInfo.modified() > remoteMeta.modifiedAtMillis()) {
                    // Local is newer
                    sessionExecutorByKey
                            .submit(id.toString(), () -> uploadSession(id, remoteProject))
                            .join();
                }
            }

            // 3. Upload local sessions that are completely missing from remote
            for (SessionInfo localInfo : localSessions.values()) {
                if (!remoteSessionsMap.containsKey(localInfo.id())) {
                    sessionExecutorByKey
                            .submit(localInfo.id().toString(), () -> uploadSession(localInfo.id(), remoteProject))
                            .join();
                }
            }

        } catch (Exception e) {
            logger.warn("synchronizeRemoteSessions failed: {}", e.getMessage());
        }
    }

    private void handleOpenSessionDownload(ContextManager cm, SessionInfo localInfo, UUID id) {
        String sessionName = localInfo.name();
        boolean accept = promptCopySessionBeforeChanging(
                cm,
                "Remote Session Updated",
                "Session '" + sessionName + "' (" + id + ") was modified on the remote.\n"
                        + "Copy the current session first before updating it?");
        if (accept) {
            cm.copySessionAsync(id, sessionName).join();
        }
        downloadSessionAndWait(id);
        cm.reloadCurrentSessionAsync();
    }

    private void handleOpenSessionDeletion(ContextManager cm, SessionInfo localInfo, UUID id) {
        String sessionName = localInfo.name();
        boolean accept = promptCopySessionBeforeChanging(
                cm,
                "Remote Session Deleted",
                "Session '" + sessionName + "' (" + id + ") was deleted on the remote.\n"
                        + "Copy the current session first before deleting it?");
        if (accept) {
            cm.copySessionAsync(id, sessionName).join();
            deleteLocalSessionAndWait(id);
        } else {
            deleteLocalSessionAndWait(id);
            cm.createSessionAsync(ContextManager.DEFAULT_SESSION_NAME).join();
        }
        cm.reloadCurrentSessionAsync();
    }

    private void downloadSessionAndWait(UUID id) {
        sessionExecutorByKey.submit(id.toString(), () -> downloadSession(id)).join();
    }

    private void deleteLocalSessionAndWait(UUID id) {
        sessionExecutorByKey.submit(id.toString(), () -> deleteLocalSession(id)).join();
    }

    private void deleteLocalSession(UUID id) {
        sessionsCache.remove(id);
        Path historyZipPath = getSessionHistoryPath(id);
        if (Files.exists(historyZipPath)) {
            try {
                Files.delete(historyZipPath);
                logger.info("Deleted local session {} as it was deleted on remote", id);
            } catch (IOException e) {
                logger.error("Failed to delete local session {}: {}", id, e.getMessage());
            }
        }
    }

    private boolean shouldDownloadRemoteSession(@Nullable SessionInfo localInfo, RemoteSessionMeta meta) {
        Path unreadablePath = sessionsDir.resolve(UNREADABLE_SESSIONS_DIR).resolve(meta.id() + ".zip");
        if (Files.exists(unreadablePath)) {
            return false;
        }
        if (meta.deletedAt() != null) {
            return false;
        }
        if (localInfo == null) {
            return true;
        }
        return meta.modifiedAtMillis() > localInfo.modified();
    }

    private void downloadSession(UUID id) {
        try {
            byte[] content = Service.getRemoteSessionContent(id);
            Path localPath = getSessionHistoryPath(id);
            Files.createDirectories(localPath.getParent());
            Files.write(localPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            readSessionInfoFromZip(localPath).ifPresent(si -> {
                sessionsCache.put(si.id(), si);
                logger.info("Downloaded session {} from remote", id);
            });
        } catch (IOException e) {
            logger.warn("Failed to download session {}: {}", id, e.getMessage());
        }
    }

    private void uploadSession(UUID id, String remoteUrl) {
        Path zipPath = getSessionHistoryPath(id);
        try {
            if (!Files.exists(zipPath)) {
                return;
            }

            SessionInfo info = readSessionInfoFromZip(zipPath).orElse(null);
            if (info == null) {
                return;
            }

            String name = info.name();
            long modifiedAt = info.modified();
            byte[] bytes = Files.readAllBytes(zipPath);
            Service.writeRemoteSession(id, remoteUrl, name, modifiedAt, bytes);
            logger.debug("Uploaded session {} to remote", id);
        } catch (IOException e) {
            logger.warn("Failed to upload session {}: {}", id, e.getMessage());
        }
    }

    private ContextHistory loadHistoryInternal(UUID sessionId, IContextManager contextManager) throws IOException {
        var sessionHistoryPath = getSessionHistoryPath(sessionId);
        ContextHistory ch = HistoryIo.readZip(sessionHistoryPath, contextManager);

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
                        maxNumericId = Math.max(
                                maxNumericId, Integer.parseInt(taskEntry.log().id()));
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
            ContextFragments.setMinimumId(maxNumericId + 1);
            logger.debug("Restored dynamic fragment ID counter based on max numeric ID: {}", maxNumericId);
        }
        return ch;
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

                // Ensure backward compatibility: normalize any tasks that might have null/missing titles
                // from old JSON format to use empty string
                var normalizedTasks = loaded.tasks().stream()
                        .map(task -> {
                            @SuppressWarnings("NullAway") // Defensive check for deserialized data
                            var titleValue = task.title();
                            if (titleValue == null) {
                                // Old JSON without title field; provide empty string default
                                return new TaskList.TaskItem("", task.text(), task.done());
                            }
                            return task;
                        })
                        .toList();

                return new TaskList.TaskListData(List.copyOf(normalizedTasks));
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

    @Override
    public void close() {
        try {
            synchronizeRemoteSessions();
        } catch (Exception e) {
            logger.warn("Failed to synchronize sessions during close: {}", e.getMessage());
        }
        sessionExecutor.shutdown();
        try {
            if (!sessionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Session IO tasks did not finish in 5 seconds, forcing shutdown.");
                sessionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sessionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
