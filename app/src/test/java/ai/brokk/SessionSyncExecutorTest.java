package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.Service.RemoteSessionMeta;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.SessionSynchronizer.ActionType;
import ai.brokk.SessionSynchronizer.SyncAction;
import ai.brokk.SessionSynchronizer.SyncResult;
import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSyncExecutorTest {

    @TempDir
    Path tempDir;

    private SessionManager sessionManager;
    private SessionSynchronizer sessionSynchronizer;
    private SessionSynchronizer.SyncExecutor syncExecutor;
    private FakeSyncCallbacks callbacks;
    private Path sessionsDir;
    private static final String REMOTE_PROJECT = "remote-proj";

    private IProject projectStub;

    @BeforeEach
    void setup() throws IOException {
        sessionsDir = tempDir.resolve("sessions");
        Files.createDirectories(sessionsDir);

        sessionManager = new SessionManager(sessionsDir);

        projectStub = new IProject() {
            @Override
            public SessionManager getSessionManager() {
                return sessionManager;
            }

            @Override
            public String getRemoteProjectName() {
                return REMOTE_PROJECT;
            }
        };

        callbacks = new FakeSyncCallbacks();
        TestContextManager cm = new TestContextManager(projectStub, UUID.randomUUID());
        sessionSynchronizer = new SessionSynchronizer(cm, callbacks);
        syncExecutor = sessionSynchronizer.new SyncExecutor();
    }

    @AfterEach
    void tearDown() {
        sessionManager.close();
    }

    @Test
    void testExecuteDownload_Successful() throws IOException, InterruptedException {
        UUID id = UUID.randomUUID();
        String name = "Remote Session";
        long modified = System.currentTimeMillis();

        RemoteSessionMeta remoteMeta =
                new RemoteSessionMeta(id.toString(), "u1", "o1", "remote", name, "private", "now", "now", "now", null);

        byte[] content = createValidSessionZip(id, name, modified);
        callbacks.remoteContent.put(id, content);

        SyncAction action = new SyncAction(id, ActionType.DOWNLOAD, null, remoteMeta);

        TestContextManager cm = new TestContextManager(projectStub, id);
        Map<UUID, IContextManager> contextManagers = Map.of(id, cm);

        SyncResult result = syncExecutor.execute(List.of(action), callbacks, contextManagers, REMOTE_PROJECT);

        assertTrue(result.failed().isEmpty());
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.succeeded().contains(id));

        assertTrue(callbacks.downloadedIds.contains(id));
        assertTrue(cm.reloadCalled);

        Path localZip = sessionManager.getSessionHistoryPath(id);
        assertTrue(Files.exists(localZip));
    }

    @Test
    void testExecuteDownload_SkippedIfLocalModified() throws InterruptedException {
        UUID id = UUID.randomUUID();
        String name = "Session";
        long oldModified = 1000L;
        long newModified = 2000L;

        // 1. Setup "original" local info (the one in the plan)
        SessionInfo oldInfo = new SessionInfo(id, name, oldModified, oldModified);

        // 2. Setup "current" local info (simulating concurrent edit)
        SessionInfo currentInfo = new SessionInfo(id, name, oldModified, newModified);
        sessionManager.getSessionsCache().put(id, currentInfo);

        RemoteSessionMeta remoteMeta =
                new RemoteSessionMeta(id.toString(), "u1", "o1", "remote", name, "private", "now", "now", "now", null);

        SyncAction action = new SyncAction(id, ActionType.DOWNLOAD, oldInfo, remoteMeta);

        SyncResult result = syncExecutor.execute(List.of(action), callbacks, Collections.emptyMap(), REMOTE_PROJECT);

        assertTrue(result.succeeded().isEmpty());
        assertTrue(result.skipped().contains(action));
        assertFalse(callbacks.downloadedIds.contains(id));
    }

    @Test
    void testExecuteUpload_Successful() throws IOException, InterruptedException {
        UUID id = UUID.randomUUID();
        String name = "Local Session";
        long modified = System.currentTimeMillis();

        // Create local session file
        Path zipPath = sessionManager.getSessionHistoryPath(id);
        Files.createDirectories(zipPath.getParent());
        Files.write(zipPath, createValidSessionZip(id, name, modified));

        SessionInfo localInfo = new SessionInfo(id, name, modified, modified);
        sessionManager.getSessionsCache().put(id, localInfo);

        SyncAction action = new SyncAction(id, ActionType.UPLOAD, localInfo, null);

        SyncResult result = syncExecutor.execute(List.of(action), callbacks, Collections.emptyMap(), REMOTE_PROJECT);

        assertTrue(result.succeeded().contains(id));
        assertTrue(callbacks.uploadedIds.contains(id));
    }

    @Test
    void testExecuteDeleteRemote_Successful() throws IOException, InterruptedException {
        UUID id = UUID.randomUUID();

        // Create tombstone
        Path tombstone = sessionsDir.resolve(id + ".tombstone");
        Files.writeString(tombstone, "deleted", StandardOpenOption.CREATE);

        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                id.toString(), "u1", "o1", "remote", "name", "private", "now", "now", "now", null);

        SyncAction action = new SyncAction(id, ActionType.DELETE_REMOTE, null, remoteMeta);

        SyncResult result = syncExecutor.execute(List.of(action), callbacks, Collections.emptyMap(), REMOTE_PROJECT);

        assertTrue(result.succeeded().contains(id));
        assertTrue(callbacks.deletedRemoteIds.contains(id));
        assertFalse(Files.exists(tombstone));
    }

    @Test
    void testExecuteDeleteLocal_Successful() throws IOException, InterruptedException {
        UUID id = UUID.randomUUID();
        String name = "ToDelete";
        long modified = System.currentTimeMillis();

        // Setup local session
        Path zipPath = sessionManager.getSessionHistoryPath(id);
        Files.createDirectories(zipPath.getParent());
        Files.write(zipPath, createValidSessionZip(id, name, modified));

        SessionInfo localInfo = new SessionInfo(id, name, modified, modified);
        sessionManager.getSessionsCache().put(id, localInfo);

        TestContextManager cm = new TestContextManager(projectStub, id);
        Map<UUID, IContextManager> contextManagers = Map.of(id, cm);

        SyncAction action = new SyncAction(id, ActionType.DELETE_LOCAL, localInfo, null);

        SyncResult result = syncExecutor.execute(List.of(action), callbacks, contextManagers, REMOTE_PROJECT);

        assertTrue(result.succeeded().contains(id));
        assertFalse(sessionManager.getSessionsCache().containsKey(id));
        assertFalse(Files.exists(zipPath));
        assertTrue(cm.createSessionCalled); // Session recreated as empty if it was open
    }

    @Test
    void testExecute_Exceptions() throws InterruptedException {
        UUID id = UUID.randomUUID();
        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                id.toString(), "u1", "o1", "remote", "name", "private", "now", "now", "now", null);

        SyncAction action = new SyncAction(id, ActionType.DOWNLOAD, null, remoteMeta);

        callbacks.exceptionOnDownload = true;

        SyncResult result = syncExecutor.execute(List.of(action), callbacks, Collections.emptyMap(), REMOTE_PROJECT);

        assertTrue(result.succeeded().isEmpty());
        assertTrue(result.failed().containsKey(id));
        Exception failure = result.failed().get(id);
        if (failure instanceof RuntimeException && failure.getCause() instanceof IOException) {
            failure = (Exception) failure.getCause();
        }
        assertInstanceOf(IOException.class, failure);
    }

    @Test
    void testExecute_ConcurrentActions() throws IOException, InterruptedException {
        // 1. Download
        UUID dlId = UUID.randomUUID();
        RemoteSessionMeta dlMeta = new RemoteSessionMeta(
                dlId.toString(), "u1", "o1", "remote", "Download", "private", "now", "now", "now", null);
        callbacks.remoteContent.put(dlId, createValidSessionZip(dlId, "Download", 1000L));
        SyncAction dlAction = new SyncAction(dlId, ActionType.DOWNLOAD, null, dlMeta);

        // 2. Upload
        UUID upId = UUID.randomUUID();
        Path upZip = sessionManager.getSessionHistoryPath(upId);
        Files.createDirectories(upZip.getParent());
        Files.write(upZip, createValidSessionZip(upId, "Upload", 2000L));
        SessionInfo upInfo = new SessionInfo(upId, "Upload", 2000L, 2000L);
        sessionManager.getSessionsCache().put(upId, upInfo);
        SyncAction upAction = new SyncAction(upId, ActionType.UPLOAD, upInfo, null);

        // 3. Delete Remote
        UUID delId = UUID.randomUUID();
        Files.writeString(sessionsDir.resolve(delId + ".tombstone"), "deleted");
        SyncAction delAction = new SyncAction(delId, ActionType.DELETE_REMOTE, null, null);

        List<SyncAction> actions = List.of(dlAction, upAction, delAction);

        SyncResult result = syncExecutor.execute(actions, callbacks, Collections.emptyMap(), REMOTE_PROJECT);

        assertTrue(result.succeeded().contains(dlId));
        assertTrue(result.succeeded().contains(upId));
        assertTrue(result.succeeded().contains(delId));

        assertTrue(callbacks.downloadedIds.contains(dlId));
        assertTrue(callbacks.uploadedIds.contains(upId));
        assertTrue(callbacks.deletedRemoteIds.contains(delId));
    }

    private byte[] createValidSessionZip(UUID id, String name, long modified) throws IOException {
        Path tempZip = tempDir.resolve("temp_" + id + ".zip");
        SessionInfo info = new SessionInfo(id, name, modified, modified);
        try (var fs = FileSystems.newFileSystem(tempZip, Map.of("create", "true"))) {
            Path manifestPath = fs.getPath("manifest.json");
            String json = ai.brokk.project.AbstractProject.objectMapper.writeValueAsString(info);
            Files.writeString(manifestPath, json);
        }
        return Files.readAllBytes(tempZip);
    }

    // --- Stubs ---

    private static class FakeSyncCallbacks implements SessionSynchronizer.SyncCallbacks {
        List<RemoteSessionMeta> remoteSessions = new ArrayList<>();
        Map<UUID, byte[]> remoteContent = new HashMap<>();
        List<UUID> deletedRemoteIds = new ArrayList<>();
        List<UUID> uploadedIds = new ArrayList<>();
        List<UUID> downloadedIds = new ArrayList<>();

        boolean exceptionOnDownload = false;

        @Override
        public List<RemoteSessionMeta> listRemoteSessions(String remote) {
            return remoteSessions;
        }

        @Override
        public byte[] getRemoteSessionContent(UUID id) throws IOException {
            if (exceptionOnDownload) throw new IOException("Download failed");
            downloadedIds.add(id);
            return remoteContent.getOrDefault(id, new byte[0]);
        }

        @Override
        public void writeRemoteSession(UUID id, String remote, String name, long modifiedAt, byte[] contentZip) {
            uploadedIds.add(id);
        }

        @Override
        public void deleteRemoteSession(UUID id) {
            deletedRemoteIds.add(id);
        }
    }

    private static class TestContextManager implements IContextManager {
        boolean reloadCalled = false;
        boolean createSessionCalled = false;
        private final IProject project;
        private UUID currentSessionId;

        public TestContextManager(IProject project, UUID currentSessionId) {
            this.project = project;
            this.currentSessionId = currentSessionId;
        }

        @Override
        public IProject getProject() {
            return project;
        }

        @Override
        public void reloadCurrentSessionAsync() {
            this.reloadCalled = true;
        }

        @Override
        public CompletableFuture<Void> createSessionAsync(String name) {
            this.createSessionCalled = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public UUID getCurrentSessionId() {
            return currentSessionId;
        }
    }
}
