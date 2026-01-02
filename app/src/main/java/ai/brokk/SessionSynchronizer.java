package ai.brokk;

import ai.brokk.Service.RemoteSessionMeta;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.gui.Chrome;
import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.concurrent.CompletionException;
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

    private final SessionManager sessionManager;
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

    SessionSynchronizer(IProject project) {
        this(project, new DefaultSyncCallbacks());
    }

    SessionSynchronizer(IProject project, SyncCallbacks syncCallbacks) {
        this.project = project;
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
            UUID sessionId,
            ActionType type,
            @Nullable SessionInfo localInfo,
            @Nullable RemoteSessionMeta remoteMeta) {}

    record SyncResult(
            List<UUID> succeeded, Map<UUID, Exception> failed, List<SyncAction> skipped) {
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
                            actions.add(new SyncAction(id, ActionType.DELETE_LOCAL, local, remote));
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
                    }).thenComparing((SyncAction a) -> {
                        if (a.remoteMeta() != null) return -a.remoteMeta().modifiedAtMillis();
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
                String remoteProject) {
            SyncResult result = new SyncResult();

            for (SyncAction action : actions) {
                UUID id = action.sessionId();
                try {
                    sessionManager.getSessionExecutorByKey().submit(id.toString(), (Callable<Void>) () -> {
                        switch (action.type()) {
                            case DELETE_REMOTE -> {
                                callbacks.deleteRemoteSession(id);
                                // Delete tombstone after successful remote delete
                                Path tombstone = sessionsDir.resolve(id + ".tombstone");
                                Files.deleteIfExists(tombstone);
                                result.succeeded.add(id);
                                logger.debug("Deleted session {} from remote via tombstone", id);
                            }
                            case DELETE_LOCAL -> {
                                if (isLocalModified(action, result)) return null;

                                IContextManager cm = openContextManagers.get(id);
                                deleteLocalSession(id);
                                if (cm != null) {
                                    cm.createSessionAsync(ContextManager.DEFAULT_SESSION_NAME).join();
                                }
                                result.succeeded.add(id);
                            }
                            case DOWNLOAD -> {
                                if (isLocalModified(action, result)) return null;

                                downloadSession(id, callbacks);
                                IContextManager cm = openContextManagers.get(id);
                                if (cm != null) {
                                    cm.reloadCurrentSessionAsync();
                                }
                                result.succeeded.add(id);
                            }
                            case UPLOAD -> {
                                uploadSession(id, remoteProject, callbacks);
                                result.succeeded.add(id);
                            }
                            case NO_OP -> {}
                        }
                        return null;
                    }).join();
                } catch (CompletionException e) {
                    Throwable cause = e.getCause();
                    Exception ex = (cause instanceof Exception) ? (Exception) cause : new Exception(cause);
                    result.failed.put(id, ex);
                    logger.warn("Action {} failed for session {}: {}", action.type(), id, ex.getMessage());
                }
            }
            return result;
        }

        private boolean isLocalModified(SyncAction action, SyncResult result) {
            if (action.localInfo() == null) return false;
            SessionInfo current = sessionManager.getSessionsCache().get(action.sessionId());
            if (current != null && current.modified() > action.localInfo().modified()) {
                result.skipped.add(action);
                logger.info("Skipping {} for session {} because local copy was modified", action.type(), action.sessionId());
                return true;
            }
            return false;
        }
    }

    public void synchronize() throws IOException {
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
                                return UUID.fromString(fileName.substring(0, fileName.length() - ".tombstone".length()));
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
                    unreadableIds.addAll(stream
                            .filter(path -> path.toString().endsWith(".zip"))
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

    private void downloadSession(UUID id, SyncCallbacks callbacks) throws IOException {
        byte[] content = callbacks.getRemoteSessionContent(id);
        Path localPath = sessionManager.getSessionHistoryPath(id);
        Files.createDirectories(localPath.getParent());
        Files.write(localPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        sessionManager.readSessionInfoFromZip(localPath).ifPresent(si -> {
            sessionManager.getSessionsCache().put(si.id(), si);
            logger.info("Downloaded session {} from remote", id);
        });
    }

    private void uploadSession(UUID id, String remoteUrl, SyncCallbacks callbacks) throws IOException {
        Path zipPath = sessionManager.getSessionHistoryPath(id);
        if (!Files.exists(zipPath)) {
            return;
        }

        SessionInfo info = sessionManager.readSessionInfoFromZip(zipPath).orElse(null);
        if (info == null) {
            return;
        }

        String name = info.name();
        long modifiedAt = info.modified();
        byte[] bytes = Files.readAllBytes(zipPath);
        callbacks.writeRemoteSession(id, remoteUrl, name, modifiedAt, bytes);
        logger.debug("Uploaded session {} to remote", id);
    }
}
