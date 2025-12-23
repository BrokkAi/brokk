package ai.brokk;

import ai.brokk.Service.RemoteSessionMeta;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
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

        boolean promptCopySessionBeforeDownload(ContextManager cm, String title, String message);

        boolean promptCopySessionBeforeDeletion(ContextManager cm, String title, String message);
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

        @Override
        public boolean promptCopySessionBeforeDownload(ContextManager cm, String title, String message) {
            return promptCopySessionBeforeChanging(cm, title, message);
        }

        @Override
        public boolean promptCopySessionBeforeDeletion(ContextManager cm, String title, String message) {
            return promptCopySessionBeforeChanging(cm, title, message);
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

    public void synchronize() {
        try {
            String key = MainProject.getBrokkKey();
            if (key.isBlank()) {
                return;
            }

            String remoteProject = project.getRemoteProjectName();
            if (remoteProject.isBlank()) {
                return;
            }

            List<RemoteSessionMeta> remoteMetas = syncCallbacks.listRemoteSessions(remoteProject);
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
                            sessionManager
                                    .getSessionExecutorByKey()
                                    .submit(id.toString(), () -> {
                                        try {
                                            syncCallbacks.deleteRemoteSession(id);
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
            Map<UUID, SessionInfo> localSessions = new HashMap<>(sessionManager.getSessionsCache());

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
                    sessionManager
                            .getSessionExecutorByKey()
                            .submit(id.toString(), () -> uploadSession(id, remoteProject))
                            .join();
                }
            }

            // 3. Upload local sessions that are completely missing from remote
            for (SessionInfo localInfo : localSessions.values()) {
                if (!remoteSessionsMap.containsKey(localInfo.id())) {
                    sessionManager
                            .getSessionExecutorByKey()
                            .submit(localInfo.id().toString(), () -> uploadSession(localInfo.id(), remoteProject))
                            .join();
                }
            }

        } catch (Exception e) {
            logger.warn("synchronizeRemoteSessions failed.", e);
        }
    }

    void handleOpenSessionDownload(ContextManager cm, SessionInfo localInfo, UUID id) {
        String sessionName = localInfo.name();
        boolean accept = syncCallbacks.promptCopySessionBeforeDownload(
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

    void handleOpenSessionDeletion(ContextManager cm, SessionInfo localInfo, UUID id) {
        String sessionName = localInfo.name();
        boolean accept = syncCallbacks.promptCopySessionBeforeDeletion(
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
        sessionManager
                .getSessionExecutorByKey()
                .submit(id.toString(), () -> downloadSession(id))
                .join();
    }

    private void deleteLocalSessionAndWait(UUID id) {
        sessionManager
                .getSessionExecutorByKey()
                .submit(id.toString(), () -> deleteLocalSession(id))
                .join();
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

    private boolean shouldDownloadRemoteSession(@Nullable SessionInfo localInfo, RemoteSessionMeta meta) {
        Path unreadablePath =
                sessionsDir.resolve(SessionManager.UNREADABLE_SESSIONS_DIR).resolve(meta.id() + ".zip");
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
            byte[] content = syncCallbacks.getRemoteSessionContent(id);
            Path localPath = sessionManager.getSessionHistoryPath(id);
            Files.createDirectories(localPath.getParent());
            Files.write(localPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            sessionManager.readSessionInfoFromZip(localPath).ifPresent(si -> {
                sessionManager.getSessionsCache().put(si.id(), si);
                logger.info("Downloaded session {} from remote", id);
            });
        } catch (IOException e) {
            logger.warn("Failed to download session {}: {}", id, e.getMessage());
        }
    }

    private void uploadSession(UUID id, String remoteUrl) {
        Path zipPath = sessionManager.getSessionHistoryPath(id);
        try {
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
            syncCallbacks.writeRemoteSession(id, remoteUrl, name, modifiedAt, bytes);
            logger.debug("Uploaded session {} to remote", id);
        } catch (IOException e) {
            logger.warn("Failed to upload session {}: {}", id, e.getMessage());
        }
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
}
