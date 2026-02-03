package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.Service.RemoteSessionMeta;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.SessionSynchronizer.ActionType;
import ai.brokk.SessionSynchronizer.SyncAction;
import ai.brokk.SessionSynchronizer.SyncCallbacks;
import ai.brokk.SessionSynchronizer.SyncExecutor;
import ai.brokk.SessionSynchronizer.SyncInfo;
import ai.brokk.SessionSynchronizer.SyncResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextHistory;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.util.HistoryIo;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
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
    private SyncExecutor syncExecutor;
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
        String now = Instant.now().toString();

        RemoteSessionMeta remoteMeta =
                new RemoteSessionMeta(id.toString(), "u1", "o1", "remote", name, "private", now, now, now, null);

        byte[] content = createValidSessionZip(id, name, modified);
        callbacks.remoteContent.put(id, content);

        SyncAction action = new SyncAction(id, ActionType.DOWNLOAD, null, remoteMeta);

        TestContextManager cm = new TestContextManager(projectStub, id);

        SyncResult result = syncExecutor.execute(List.of(action), callbacks, Map.of(), REMOTE_PROJECT);

        assertTrue(result.failed().isEmpty());
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.succeeded().contains(id));

        assertTrue(callbacks.downloadedIds.contains(id));

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

        String now = Instant.now().toString();
        RemoteSessionMeta remoteMeta =
                new RemoteSessionMeta(id.toString(), "u1", "o1", "remote", name, "private", now, now, now, null);

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

        String now = Instant.now().toString();
        RemoteSessionMeta remoteMeta =
                new RemoteSessionMeta(id.toString(), "u1", "o1", "remote", "name", "private", now, now, now, null);

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
        String now = Instant.now().toString();
        RemoteSessionMeta remoteMeta =
                new RemoteSessionMeta(id.toString(), "u1", "o1", "remote", "name", "private", now, now, now, null);

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
    void testExecute_Merge_Diverged() throws IOException, InterruptedException {
        UUID id = UUID.randomUUID();
        String name = "Diverged Session";
        long remoteTime = 1000L;
        long localTime = 2000L; // Local is newer

        // 1. Create Remote History with one context
        Context remoteCtx =
                new Context(new TestContextManager(projectStub, id)).withBuildResult(false, "Remote changes");
        ContextHistory remoteHistory = new ContextHistory(remoteCtx);
        byte[] remoteBytes = createValidSessionZip(id, name, remoteTime, remoteHistory);

        String remoteTimeStr = Instant.ofEpochMilli(remoteTime).toString();
        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                id.toString(),
                "u1",
                "o1",
                "remote",
                name,
                "private",
                remoteTimeStr,
                remoteTimeStr,
                remoteTimeStr,
                null);
        callbacks.remoteContent.put(id, remoteBytes);

        // 2. Create Local History with different context (diverged)
        Context localCtx = new Context(new TestContextManager(projectStub, id)).withBuildResult(false, "Local changes");
        ContextHistory localHistory = new ContextHistory(localCtx);
        Path zipPath = sessionManager.getSessionHistoryPath(id);
        Files.createDirectories(zipPath.getParent());
        Files.write(zipPath, createValidSessionZip(id, name, localTime, localHistory));

        SessionInfo localInfo = new SessionInfo(id, name, localTime, localTime);
        sessionManager.getSessionsCache().put(id, localInfo);

        // 3. Action is UPLOAD because local is newer
        SyncAction action = new SyncAction(id, ActionType.UPLOAD, localInfo, remoteMeta);

        // 4. Execute
        TestContextManager cm = new TestContextManager(projectStub, id);
        SyncResult result = syncExecutor.execute(List.of(action), callbacks, Map.of(id, cm), REMOTE_PROJECT);

        assertTrue(result.succeeded().contains(id));
        assertTrue(callbacks.uploadedIds.contains(id));

        // 5. Verify Local Zip contains merged history
        ContextHistory merged = HistoryIo.readZip(zipPath, new TestContextManager(projectStub, id));
        // Expect merged history to contain both divergences
        assertTrue(merged.getHistory().size() > 1, "Merged history should contain multiple contexts");

        assertTrue(cm.reloadCalled, "reloadCurrentSessionAsync should be called on diverged merge");
    }

    @Test
    void testExecuteUpload_413PersistsOversizedSession() throws IOException, InterruptedException {
        UUID id = UUID.randomUUID();
        String name = "Large Session";
        long modified = System.currentTimeMillis();

        // Create local session file
        Path zipPath = sessionManager.getSessionHistoryPath(id);
        Files.createDirectories(zipPath.getParent());
        Files.write(zipPath, createValidSessionZip(id, name, modified));

        SessionInfo localInfo = new SessionInfo(id, name, modified, modified);
        sessionManager.getSessionsCache().put(id, localInfo);

        // Configure callback to throw 413
        callbacks.exceptionOnUpload413 = true;

        SyncAction action = new SyncAction(id, ActionType.UPLOAD, localInfo, null);

        SyncResult result = syncExecutor.execute(List.of(action), callbacks, Collections.emptyMap(), REMOTE_PROJECT);

        // Action should fail
        assertTrue(result.failed().containsKey(id));
        assertFalse(result.succeeded().contains(id));

        // Session should be persisted in sync_info.json
        SyncInfo syncInfo = sessionSynchronizer.readSyncInfo();
        assertTrue(syncInfo.oversizedSessionIds().contains(id));

        // Verify file exists with correct structure
        Path syncInfoPath = sessionsDir.resolve("sync").resolve("sync_info.json");
        assertTrue(Files.exists(syncInfoPath));
        String content = Files.readString(syncInfoPath);
        assertTrue(content.contains(id.toString()));
    }

    @Test
    void testExecute_ConcurrentActions() throws IOException, InterruptedException {
        // 1. Download
        UUID dlId = UUID.randomUUID();
        String now = Instant.now().toString();
        RemoteSessionMeta dlMeta = new RemoteSessionMeta(
                dlId.toString(), "u1", "o1", "remote", "Download", "private", now, now, now, null);
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
        return createValidSessionZip(id, name, modified, new ContextHistory(Context.EMPTY));
    }

    private byte[] createValidSessionZip(UUID id, String name, long modified, ContextHistory history)
            throws IOException {
        Path tempZip = tempDir.resolve("temp_" + id + ".zip");
        // Ensure parent dir exists
        Files.createDirectories(tempZip.getParent());

        // Write history using HistoryIo
        HistoryIo.writeZip(history, tempZip);

        // Add manifest.json
        SessionInfo info = new SessionInfo(id, name, modified, modified);
        // HistoryIo.writeZip might close the file system, so we open it again to append manifest
        try (var fs = FileSystems.newFileSystem(tempZip, Map.of("create", "false"))) {
            Path manifestPath = fs.getPath("manifest.json");
            String json = AbstractProject.objectMapper.writeValueAsString(info);
            Files.writeString(manifestPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        return Files.readAllBytes(tempZip);
    }

    // --- Additional timeout / IO handling tests ---

    @Test
    void synchronize_handlesSocketTimeoutOnListRemoteSessions_viaSynchronizer() {
        // Arrange: create callbacks that throw SocketTimeoutException on listing.
        class TimeoutCallbacks implements SyncCallbacks {
            int listAttempts = 0;
            boolean getCalled = false;
            boolean writeCalled = false;
            boolean deleteCalled = false;

            @Override
            public List<RemoteSessionMeta> listRemoteSessions(String remote) throws IOException {
                listAttempts++;
                throw new java.net.SocketTimeoutException("simulated timeout");
            }

            @Override
            public byte[] getRemoteSessionContent(UUID id) throws IOException {
                getCalled = true;
                return new byte[0];
            }

            @Override
            public void writeRemoteSession(UUID id, String remote, String name, long modifiedAt, byte[] contentZip)
                    throws IOException {
                writeCalled = true;
            }

            @Override
            public void deleteRemoteSession(UUID id) {
                deleteCalled = true;
            }
        }

        TimeoutCallbacks callbacks = new TimeoutCallbacks();
        TestContextManager cm = new TestContextManager(projectStub, UUID.randomUUID());
        SessionSynchronizer localSync = new SessionSynchronizer(cm, callbacks) {
            @Override
            protected Map<UUID, IContextManager> getOpenContextManagers() {
                return Map.of();
            }
        };

        // Act & Assert: should not throw and should not invoke per-session callbacks
        assertDoesNotThrow(() -> localSync.synchronize());
        // The retry policy uses LIST_MAX_ATTEMPTS = 2 (initial try + 1 retry)
        assertEquals(2, callbacks.listAttempts, "listRemoteSessions should be attempted exactly twice on timeout");
        assertFalse(callbacks.getCalled, "getRemoteSessionContent must not be called when listing fails");
        assertFalse(callbacks.writeCalled, "writeRemoteSession must not be called when listing fails");
        assertFalse(callbacks.deleteCalled, "deleteRemoteSession must not be called when listing fails");
        // Also assert no sessions added locally
        assertTrue(sessionManager.getSessionsCache().isEmpty(), "No sessions should be added on listing failure");
    }

    @Test
    void synchronize_retriesAfterTimeoutAndThenSucceeds_viaSynchronizer() throws IOException, InterruptedException {
        // Arrange: callbacks that fail once then succeed
        final UUID remoteId = UUID.randomUUID();
        final String timeStr = Instant.now().toString();
        final RemoteSessionMeta meta = new RemoteSessionMeta(
                remoteId.toString(),
                "u1",
                "o1",
                REMOTE_PROJECT,
                "Recovered Session",
                "private",
                timeStr,
                timeStr,
                timeStr,
                null);

        class FlakyCallbacks implements SyncCallbacks {
            int attempts = 0;
            List<RemoteSessionMeta> remoteSessions = new ArrayList<>();
            Map<UUID, byte[]> remoteContent = new HashMap<>();
            List<UUID> downloadedIds = new ArrayList<>();
            List<UUID> uploadedIds = new ArrayList<>();
            List<UUID> deletedIds = new ArrayList<>();

            @Override
            public List<RemoteSessionMeta> listRemoteSessions(String remote) throws IOException {
                attempts++;
                if (attempts == 1) {
                    // First attempt simulates transient timeout
                    throw new java.net.SocketTimeoutException("simulated transient timeout");
                } else {
                    // Subsequent attempts succeed and return one remote-only session
                    return List.of(meta);
                }
            }

            @Override
            public byte[] getRemoteSessionContent(UUID id) throws IOException {
                downloadedIds.add(id);
                try {
                    // Reuse helper to build a valid small session zip
                    return createValidSessionZip(id, "Recovered Session", System.currentTimeMillis());
                } catch (IOException e) {
                    throw new IOException("failed to create test zip", e);
                }
            }

            @Override
            public void writeRemoteSession(UUID id, String remote, String name, long modifiedAt, byte[] contentZip)
                    throws IOException {
                uploadedIds.add(id);
            }

            @Override
            public void deleteRemoteSession(UUID id) throws IOException {
                deletedIds.add(id);
            }
        }

        FlakyCallbacks callbacks = new FlakyCallbacks();
        TestContextManager cm = new TestContextManager(projectStub, UUID.randomUUID());
        SessionSynchronizer localSync = new SessionSynchronizer(cm, callbacks) {
            @Override
            protected Map<UUID, IContextManager> getOpenContextManagers() {
                return Map.of();
            }
        };

        // Sanity check: ensure remote session is not already present locally
        assertFalse(sessionManager.getSessionsCache().containsKey(remoteId));

        // Act: should retry once and then succeed
        assertDoesNotThrow(() -> localSync.synchronize());

        // Verify it retried exactly twice (first failed, second succeeded)
        assertEquals(2, callbacks.attempts, "listRemoteSessions should be attempted twice (timeout then success)");

        // Verify the remote-only session was downloaded and persisted locally
        assertTrue(callbacks.downloadedIds.contains(remoteId), "Remote session should have been downloaded");
        assertTrue(
                sessionManager.getSessionsCache().containsKey(remoteId), "Downloaded session should be in local cache");

        // No uploads or deletes should have been invoked for this scenario
        assertTrue(callbacks.uploadedIds.isEmpty(), "No uploads expected");
        assertTrue(callbacks.deletedIds.isEmpty(), "No deletes expected");
    }

    // --- Stubs ---

    private static class FakeSyncCallbacks implements SyncCallbacks {
        List<RemoteSessionMeta> remoteSessions = new ArrayList<>();
        Map<UUID, byte[]> remoteContent = new HashMap<>();
        List<UUID> deletedRemoteIds = new ArrayList<>();
        List<UUID> uploadedIds = new ArrayList<>();
        List<UUID> downloadedIds = new ArrayList<>();

        boolean exceptionOnDownload = false;
        boolean exceptionOnUpload413 = false;

        @Override
        public List<RemoteSessionMeta> listRemoteSessions(String remote) {
            return remoteSessions;
        }

        @Override
        public byte[] getRemoteSessionContent(UUID id) throws IOException {
            if (exceptionOnDownload) throw new ServiceHttpException(500, "Mock Error", "Download failed");
            downloadedIds.add(id);
            return remoteContent.getOrDefault(id, new byte[0]);
        }

        @Override
        public void writeRemoteSession(UUID id, String remote, String name, long modifiedAt, byte[] contentZip)
                throws IOException {
            if (exceptionOnUpload413) {
                throw new ServiceHttpException(413, "Payload Too Large", "Session content exceeds maximum size");
            }
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
