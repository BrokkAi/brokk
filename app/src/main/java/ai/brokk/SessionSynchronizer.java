package ai.brokk;

import ai.brokk.Service.RemoteSessionMeta;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.context.ContextHistory;
import ai.brokk.project.IProject;
import ai.brokk.util.HistoryIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Handles bidirectional synchronization between local and remote sessions.
 */
class SessionSynchronizer {
    private static final Logger logger = LogManager.getLogger(SessionSynchronizer.class);
    private static final String TMP_DIR = "tmp";

    private final SessionManager sessionManager;
    private final IContextManager contextManager;
    private final IProject project;
    private final Path sessionsDir;
    private final SyncCallbacks syncCallbacks;

    interface SyncCallbacks {
        List<RemoteSessionMeta> listRemoteSessions(String remote) throws IOException;

        byte[] getRemoteSessionContent(UUID id) throws IOException;

        void writeRemoteSession(UUID id, String remote, String name, long modifiedAt, byte[] contentZip)
                throws IOException;

        void deleteRemoteSession(UUID id) throws IOException;
    }

    private static class DefaultSyncCallbacks implements SyncCallbacks {
        @Override
        public List<RemoteSessionMeta> listRemoteSessions(String remote) throws IOException {
            return Service.listRemoteSessions(remote);
        }

        @Override
        public byte[] getRemoteSessionContent(UUID id) throws IOException {
            return Service.getRemoteSessionContent(id);
        }

        @Override
        public void writeRemoteSession(UUID id, String remote, String name, long modifiedAt, byte[] contentZip)
                throws IOException {
            Service.writeRemoteSession(id, remote, name, modifiedAt, contentZip);
        }

        @Override
        public void deleteRemoteSession(UUID id) throws IOException {
            Service.deleteRemoteSession(id);
        }
    }

    SessionSynchronizer(IContextManager contextManager) {
        this(contextManager, new DefaultSyncCallbacks());
    }

    SessionSynchronizer(IContextManager contextManager, SyncCallbacks syncCallbacks) {
        this.contextManager = contextManager;
        this.project = contextManager.getProject();
        this.syncCallbacks = syncCallbacks;
        this.sessionManager = project.getSessionManager();
        this.sessionsDir = sessionManager.getSessionsDir();
    }

    enum ActionType {
        DOWNLOAD,
        UPLOAD,
        DELETE_REMOTE,
        DELETE_LOCAL,
        NO_OP
    }

    record SyncAction(
            UUID sessionId, ActionType type, @Nullable SessionInfo localInfo, @Nullable RemoteSessionMeta remoteMeta) {}

    record SyncResult(List<UUID> succeeded, Map<UUID, Exception> failed, List<SyncAction> skipped) {
        public SyncResult() {
            this(new ArrayList<>(), new HashMap<>(), new ArrayList<>());
        }
    }

    static class SyncPlanner {
        List<SyncAction> plan(
                Map<UUID, SessionInfo> localSessions,
                List<RemoteSessionMeta> remoteSessions,
                Set<UUID> tombstones,
                Set<UUID> unreadableSessionIds) {

            Map<UUID, RemoteSessionMeta> remoteMap = remoteSessions.stream()
                    .collect(Collectors.toMap(RemoteSessionMeta::uuid, Function.identity(), (a, b) -> a));

            Set<UUID> allIds = new HashSet<>();
            allIds.addAll(localSessions.keySet());
            allIds.addAll(remoteMap.keySet());
            allIds.addAll(tombstones);

            List<SyncAction> actions = new ArrayList<>();

            for (UUID id : allIds) {
                // Tombstones: Local deletion that needs to be propagated to remote
                if (tombstones.contains(id)) {
                    RemoteSessionMeta remote = remoteMap.get(id);
                    if (remote != null && remote.deletedAt() == null) {
                        actions.add(new SyncAction(id, ActionType.DELETE_REMOTE, localSessions.get(id), remote));
                    }
                    continue;
                }

                SessionInfo local = localSessions.get(id);
                RemoteSessionMeta remote = remoteMap.get(id);

                if (remote == null) {
                    // Local exists but remote missing (or we are uploading a new session)
                    if (local != null) {
                        actions.add(new SyncAction(id, ActionType.UPLOAD, local, null));
                    }
                } else {
                    // Remote exists
                    if (remote.deletedAt() != null) {
                        // Remote deleted
                        if (local != null) {
                            if (remote.deletedAtMillis() > local.modified()) {
                                actions.add(new SyncAction(id, ActionType.DELETE_LOCAL, local, remote));
                            } else {
                                actions.add(new SyncAction(id, ActionType.UPLOAD, local, remote));
                            }
                        }
                    } else {
                        // Remote active
                        if (unreadableSessionIds.contains(id)) {
                            continue;
                        }

                        if (local == null) {
                            actions.add(new SyncAction(id, ActionType.DOWNLOAD, null, remote));
                        } else {
                            long localMod = local.modified();
                            long remoteMod = remote.modifiedAtMillis();

                            if (remoteMod > localMod) {
                                actions.add(new SyncAction(id, ActionType.DOWNLOAD, local, remote));
                            } else if (localMod > remoteMod) {
                                actions.add(new SyncAction(id, ActionType.UPLOAD, local, remote));
                            }
                        }
                    }
                }
            }

            // Sort actions: Delete Remote -> Download/Delete Local (Newest Remote First) -> Upload
            return actions.stream()
                    .sorted(Comparator.comparing((SyncAction a) -> {
                                if (a.type() == ActionType.DELETE_REMOTE) return 0;
                                if (a.type() == ActionType.DOWNLOAD || a.type() == ActionType.DELETE_LOCAL) return 1;
                                return 2;
                            })
                            .thenComparing((SyncAction a) -> {
                                if (a.remoteMeta() != null)
                                    return -a.remoteMeta().modifiedAtMillis();
                                return 0L;
                            }))
                    .toList();
        }
    }

    class SyncExecutor {
        SyncResult execute(
                List<SyncAction> actions,
                SyncCallbacks callbacks,
                Map<UUID, IContextManager> openContextManagers,
                String remoteProject)
                throws InterruptedException {
            SyncResult result = new SyncResult();

            for (SyncAction action : actions) {
                UUID id = action.sessionId();
                try {
                    switch (action.type()) {
                        case DELETE_REMOTE -> handleDeleteRemote(action, callbacks, result);
                        case DELETE_LOCAL -> handleDeleteLocal(action, openContextManagers, result);
                        case DOWNLOAD -> handleDownload(action, callbacks, openContextManagers, result);
                        case UPLOAD -> handleUpload(action, callbacks, remoteProject, result);
                        case NO_OP -> {}
                    }
                } catch (ExecutionException executionException) {
                    Throwable cause = executionException.getCause();
                    Exception ex = (cause instanceof Exception e) ? e : new Exception(cause);
                    result.failed.put(id, ex);
                    logger.warn("Action {} failed for session {}: {}", action.type(), id, ex.getMessage());
                } catch (IOException e) {
                    result.failed.put(id, e);
                    logger.warn("Action {} failed for session {}: {}", action.type(), id, e.getMessage());
                }
            }
            return result;
        }

        private void handleDeleteRemote(SyncAction action, SyncCallbacks callbacks, SyncResult result)
                throws IOException, ExecutionException, InterruptedException {
            UUID id = action.sessionId();
            callbacks.deleteRemoteSession(id);
            // Delete tombstone after successful remote delete
            sessionManager
                    .getSessionExecutorByKey()
                    .submit(id.toString(), (Callable<Void>) () -> {
                        Path tombstone = sessionsDir.resolve(id + ".tombstone");
                        Files.deleteIfExists(tombstone);
                        return null;
                    })
                    .get();
            result.succeeded.add(id);
            logger.debug("Deleted session {} from remote via tombstone", id);
        }

        private void handleDeleteLocal(
                SyncAction action, Map<UUID, IContextManager> openContextManagers, SyncResult result)
                throws ExecutionException, InterruptedException {
            UUID id = action.sessionId();
            sessionManager
                    .getSessionExecutorByKey()
                    .submit(id.toString(), (Callable<Void>) () -> {
                        if (isLocalModified(action, result)) return null;

                        IContextManager cm = openContextManagers.get(id);
                        deleteLocalSession(id);
                        if (cm != null) {
                            cm.createSessionAsync(ContextManager.DEFAULT_SESSION_NAME)
                                    .join();
                        }
                        result.succeeded.add(id);
                        return null;
                    })
                    .get();
        }

        private void handleDownload(
                SyncAction action,
                SyncCallbacks callbacks,
                Map<UUID, IContextManager> openContextManagers,
                SyncResult result)
                throws IOException, ExecutionException, InterruptedException {
            UUID id = action.sessionId();
            RemoteSessionMeta remoteMeta = action.remoteMeta();
            if (remoteMeta == null) {
                throw new IllegalArgumentException("Cannot download session without remote metadata");
            }

            if (isLocalModified(action, result)) return;

            byte[] content = callbacks.getRemoteSessionContent(id);

            sessionManager
                    .getSessionExecutorByKey()
                    .submit(id.toString(), (Callable<Void>) () -> {
                        if (isLocalModified(action, result)) return null;

                        mergeAndSave(id, content, action.localInfo(), remoteMeta);
                        IContextManager cm = openContextManagers.get(id);
                        if (cm != null) {
                            cm.reloadCurrentSessionAsync();
                        }
                        result.succeeded.add(id);
                        return null;
                    })
                    .get();
        }

        private void handleUpload(SyncAction action, SyncCallbacks callbacks, String remoteProject, SyncResult result)
                throws IOException, ExecutionException, InterruptedException {
            UUID id = action.sessionId();
            if (isLocalModified(action, result)) return;

            byte[] remoteContent = null;
            if (action.remoteMeta() != null) {
                try {
                    remoteContent = callbacks.getRemoteSessionContent(id);
                } catch (IOException e) {
                    logger.warn(
                            "Failed to fetch remote content for merge during upload of session {}. Proceeding with overwrite.",
                            id,
                            e);
                }
            }
            final byte[] contentToMerge = remoteContent;

            @SuppressWarnings("ArrayRecordComponent")
            record UploadSnapshot(String name, long modified, byte[] bytes) {}

            UploadSnapshot snapshot = sessionManager
                    .getSessionExecutorByKey()
                    .submit(id.toString(), (Callable<UploadSnapshot>) () -> {
                        if (isLocalModified(action, result)) return null;

                        if (contentToMerge != null) {
                            mergeAndSave(
                                    id,
                                    contentToMerge,
                                    action.localInfo(),
                                    Objects.requireNonNull(action.remoteMeta()));
                        }

                        Path localPath = sessionManager.getSessionHistoryPath(id);
                        if (!Files.exists(localPath)) return null;

                        SessionInfo info =
                                sessionManager.readSessionInfoFromZip(localPath).orElse(null);
                        if (info == null) return null;

                        byte[] bytes = Files.readAllBytes(localPath);
                        return new UploadSnapshot(info.name(), info.modified(), bytes);
                    })
                    .get();

            if (snapshot != null) {
                callbacks.writeRemoteSession(id, remoteProject, snapshot.name(), snapshot.modified(), snapshot.bytes());
                result.succeeded.add(id);
                logger.debug("Uploaded session {} to remote", id);
            }
        }

        private boolean isLocalModified(SyncAction action, SyncResult result) {
            if (action.localInfo() == null) return false;
            SessionInfo current = sessionManager.getSessionsCache().get(action.sessionId());
            if (current != null && current.modified() > action.localInfo().modified()) {
                result.skipped.add(action);
                logger.info(
                        "Skipping {} for session {} because local copy was modified",
                        action.type(),
                        action.sessionId());
                return true;
            }
            return false;
        }
    }

    public void synchronize() throws IOException, InterruptedException {
        String remoteProject = project.getRemoteProjectName();
        Files.createDirectories(sessionsDir);

        SyncPlanner planner = new SyncPlanner();
        SyncExecutor executor = new SyncExecutor();
        SyncResult result;
        int iteration = 0;

        do {
            iteration++;
            if (iteration > 10) {
                logger.warn("Sync loop iteration limit reached, stopping.");
                break;
            }

            // Fetch state
            List<RemoteSessionMeta> remoteSessions = syncCallbacks.listRemoteSessions(remoteProject);
            Map<UUID, SessionInfo> localSessions = new HashMap<>(sessionManager.getSessionsCache());

            Set<UUID> tombstones = new HashSet<>();
            try (Stream<Path> stream = Files.list(sessionsDir)) {
                tombstones.addAll(stream.filter(path -> path.toString().endsWith(".tombstone"))
                        .map(path -> {
                            String fileName = path.getFileName().toString();
                            try {
                                return UUID.fromString(
                                        fileName.substring(0, fileName.length() - ".tombstone".length()));
                            } catch (IllegalArgumentException e) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()));
            }

            Set<UUID> unreadableIds = new HashSet<>();
            Path unreadableDir = sessionsDir.resolve(SessionManager.UNREADABLE_SESSIONS_DIR);
            if (Files.exists(unreadableDir)) {
                try (Stream<Path> stream = Files.list(unreadableDir)) {
                    unreadableIds.addAll(stream.filter(path -> path.toString().endsWith(".zip"))
                            .map(path -> {
                                String fileName = path.getFileName().toString();
                                try {
                                    return UUID.fromString(fileName.substring(0, fileName.length() - ".zip".length()));
                                } catch (IllegalArgumentException e) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet()));
                }
            }

            Map<UUID, IContextManager> openContextManagers = getOpenContextManagers();

            // Plan
            List<SyncAction> actions = planner.plan(localSessions, remoteSessions, tombstones, unreadableIds);
            if (actions.isEmpty()) {
                break;
            }

            // Execute
            result = executor.execute(actions, syncCallbacks, openContextManagers, remoteProject);

        } while (!result.skipped().isEmpty());
    }

    protected Map<UUID, IContextManager> getOpenContextManagers() {
        return Brokk.getProjectAndWorktreeChromes(project).stream()
                .map(c -> (IContextManager) c.getContextManager())
                .filter(cm -> cm.getCurrentSessionId() != null)
                .collect(Collectors.toMap(IContextManager::getCurrentSessionId, Function.identity(), (a, b) -> a));
    }

    private void deleteLocalSession(UUID id) {
        sessionManager.getSessionsCache().remove(id);
        Path historyZipPath = sessionManager.getSessionHistoryPath(id);
        if (Files.exists(historyZipPath)) {
            try {
                Files.delete(historyZipPath);
                logger.info("Deleted local session {} as it was deleted on remote", id);
            } catch (IOException e) {
                logger.error("Failed to delete local session {}: {}", id, e.getMessage());
            }
        }
    }

    private void mergeAndSave(
            UUID id, byte[] remoteContent, @Nullable SessionInfo localInfo, RemoteSessionMeta remoteMeta)
            throws IOException {
        Path tmpDir = sessionsDir.resolve(TMP_DIR);
        Files.createDirectories(tmpDir);
        Path remoteZipPath = Files.createTempFile(tmpDir, "remote-" + id, ".zip");
        Files.write(remoteZipPath, remoteContent);

        try {
            ContextHistory remoteHistory = HistoryIo.readZip(remoteZipPath, contextManager);

            Path localZipPath = sessionManager.getSessionHistoryPath(id);
            ContextHistory localHistory = null;
            if (Files.exists(localZipPath)) {
                try {
                    localHistory = HistoryIo.readZip(localZipPath, contextManager);
                } catch (Exception e) {
                    logger.warn("Could not read local history for merge, overwriting with remote", e);
                }
            }

            ContextHistory merged;
            long newModified;

            if (localHistory == null) {
                merged = remoteHistory;
                newModified = remoteMeta.modifiedAtMillis();
            } else {
                if (ContextHistory.areDiverged(localHistory, remoteHistory)) {
                    long localTime = localInfo != null ? localInfo.modified() : 0;
                    long remoteTime = remoteMeta.modifiedAtMillis();
                    if (localTime >= remoteTime) {
                        merged = ContextHistory.merge(remoteHistory, localHistory);
                    } else {
                        merged = ContextHistory.merge(localHistory, remoteHistory);
                    }
                    // Diverged merge results in a new modification
                    newModified = System.currentTimeMillis();
                } else {
                    long localTime = localInfo != null ? localInfo.modified() : 0;
                    long remoteTime = remoteMeta.modifiedAtMillis();
                    if (localTime >= remoteTime) {
                        merged = localHistory;
                        newModified = localTime;
                    } else {
                        merged = remoteHistory;
                        newModified = remoteTime;
                    }
                }
            }

            // Write merged to local
            Files.createDirectories(localZipPath.getParent());

            if (localHistory != null && merged == localHistory) {
                logger.debug("Session {} merged history matches local history; skipping zip rewrite.", id);
            } else if (merged == remoteHistory) {
                logger.debug("Session {} merged history matches remote history; copying remote zip.", id);
                Files.copy(remoteZipPath, localZipPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                HistoryIo.writeZip(merged, localZipPath);
            }

            // Update manifest
            String name = localInfo != null ? localInfo.name() : remoteMeta.name();
            long created = localInfo != null ? localInfo.created() : System.currentTimeMillis();
            SessionInfo newInfo = new SessionInfo(id, name, created, newModified);

            sessionManager.writeSessionInfoToZip(localZipPath, newInfo);
            sessionManager.getSessionsCache().put(id, newInfo);
            logger.info(
                    "Saved session {} (merged={})",
                    id,
                    localHistory != null && merged != localHistory && merged != remoteHistory);

        } finally {
            try {
                Files.deleteIfExists(remoteZipPath);
            } catch (IOException e) {
                logger.warn("Failed to delete temp remote zip: {}", remoteZipPath, e);
            }
        }
    }
}
