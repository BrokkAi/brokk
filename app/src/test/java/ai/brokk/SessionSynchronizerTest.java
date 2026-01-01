package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.Service.RemoteSessionMeta;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSynchronizerTest {

    @TempDir
    Path tempDir;

    private IProject project;
    private SessionManager sessionManager;
    private SessionSynchronizer synchronizer;
    private Path sessionsDir;
    private FakeSyncCallbacks syncCallbacks;

    private static class FakeSyncCallbacks implements SessionSynchronizer.SyncCallbacks {
        List<RemoteSessionMeta> remoteSessions = new ArrayList<>();
        Map<UUID, byte[]> remoteContent = new HashMap<>();
        List<UUID> deletedRemoteIds = new ArrayList<>();
        List<UUID> uploadedIds = new ArrayList<>();

        @Override
        public List<RemoteSessionMeta> listRemoteSessions(String remote) {
            return remoteSessions;
        }

        @Override
        public byte[] getRemoteSessionContent(UUID id) {
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

    /** Simple stub for ContextManager to verify interactions during sync. */
    private static class TestContextManager extends ContextManager {
        boolean reloadCalled = false;
        boolean createSessionCalled = false;
        private UUID currentSessionId;

        public TestContextManager(IProject project, UUID currentSessionId) {
            super(project);
            this.currentSessionId = currentSessionId;
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

    private Map<UUID, ContextManager> openContexts = new HashMap<>();

    @BeforeEach
    void setup() throws IOException {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        project = new MainProject(projectDir);

        sessionManager = project.getSessionManager();
        sessionsDir = sessionManager.getSessionsDir();
        Files.createDirectories(sessionsDir);

        syncCallbacks = new FakeSyncCallbacks();
        synchronizer = new SessionSynchronizer(project, syncCallbacks) {
            @Override
            protected Map<UUID, ContextManager> getOpenContextManagers() {
                return openContexts;
            }
        };
    }

    @AfterEach
    public void tearDown() {
        project.close();
    }

    @Test
    void uploadLocalOnlySession() {
        String name = "Local Session";
        SessionInfo info = sessionManager.newSession(name);
        sessionManager
                .getSessionExecutorByKey()
                .awaitCompletion(info.id().toString())
                .join();

        synchronizer.synchronize();

        assertTrue(syncCallbacks.uploadedIds.contains(info.id()), "Local session should have been uploaded");
    }

    @Test
    void downloadRemoteOnlySession() throws IOException {
        UUID remoteId = UUID.randomUUID();
        String name = "Remote Session";

        byte[] zipBytes = createValidSessionZip(remoteId, name);

        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                remoteId.toString(),
                "u1",
                "o1",
                "test-remote-repo",
                name,
                "private",
                "2023-01-01T00:00:00Z",
                "2023-01-01T00:00:00Z",
                "2023-01-01T00:00:00Z",
                null);
        syncCallbacks.remoteSessions.add(remoteMeta);
        syncCallbacks.remoteContent.put(remoteId, zipBytes);

        synchronizer.synchronize();

        Path expectedZip = sessionsDir.resolve(remoteId + ".zip");
        assertTrue(Files.exists(expectedZip), "Remote session should be downloaded");
        assertTrue(sessionManager.getSessionsCache().containsKey(remoteId), "Cache should be updated after download");
    }

    @Test
    void dontRedownloadLocallyDeletedSessions() throws IOException {
        UUID deletedId = UUID.randomUUID();
        Path tombstone = sessionsDir.resolve(deletedId + ".tombstone");
        Files.writeString(tombstone, "deleted");

        // Stale metadata: shows session exists even though we are about to delete it
        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                deletedId.toString(),
                "u1",
                "o1",
                "test-remote-repo",
                "Stale Meta",
                "private",
                "2023-01-01T00:00:00Z",
                "2023-01-01T00:00:00Z",
                "2023-01-01T00:00:00Z",
                null);
        syncCallbacks.remoteSessions.add(remoteMeta);

        List<UUID> downloadedIds = new ArrayList<>();
        FakeSyncCallbacks trackingCallbacks = new FakeSyncCallbacks() {
            {
                remoteSessions = syncCallbacks.remoteSessions;
                remoteContent = syncCallbacks.remoteContent;
                deletedRemoteIds = syncCallbacks.deletedRemoteIds;
            }

            @Override
            public byte[] getRemoteSessionContent(UUID id) {
                downloadedIds.add(id);
                return new byte[0];
            }
        };

        synchronizer = new SessionSynchronizer(project, trackingCallbacks);
        synchronizer.synchronize();

        assertTrue(trackingCallbacks.deletedRemoteIds.contains(deletedId), "Remote delete should have been called");
        assertFalse(Files.exists(tombstone), "Tombstone should be removed");
        assertFalse(downloadedIds.contains(deletedId), "Should NOT have attempted to download the deleted session");
    }

    @Test
    void tombstoneTriggersRemoteDelete() throws IOException {
        UUID deletedId = UUID.randomUUID();
        Path tombstone = sessionsDir.resolve(deletedId + ".tombstone");
        Files.writeString(tombstone, "deleted");

        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                deletedId.toString(),
                "u1",
                "o1",
                "test-remote-repo",
                "To Delete",
                "private",
                "2023-01-01T00:00:00Z",
                "2023-01-01T00:00:00Z",
                "2023-01-01T00:00:00Z",
                null);
        syncCallbacks.remoteSessions.add(remoteMeta);

        synchronizer.synchronize();

        assertTrue(syncCallbacks.deletedRemoteIds.contains(deletedId), "Remote delete should be called for tombstone");
        assertFalse(Files.exists(tombstone), "Tombstone should be removed after processing");
    }

    @Test
    void skipUnreadableSessions() throws IOException {
        UUID unreadableId = UUID.randomUUID();
        Path unreadableDir = sessionsDir.resolve(SessionManager.UNREADABLE_SESSIONS_DIR);
        Files.createDirectories(unreadableDir);
        Files.writeString(unreadableDir.resolve(unreadableId + ".zip"), "corrupt");

        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                unreadableId.toString(),
                "u1",
                "o1",
                "test-remote-repo",
                "Unreadable",
                "private",
                "2023-01-01T00:00:00Z",
                "2023-01-01T00:00:00Z",
                "2023-01-01T00:00:00Z",
                null);
        syncCallbacks.remoteSessions.add(remoteMeta);

        synchronizer.synchronize();

        assertFalse(
                Files.exists(sessionsDir.resolve(unreadableId + ".zip")),
                "Unreadable session should not be re-downloaded");
    }

    @Test
    void openSessionRefreshesAfterDownload() throws IOException {
        UUID sessionId = UUID.randomUUID();
        SessionInfo localInfo = new SessionInfo(sessionId, "Open Session", 100, 100);
        sessionManager.getSessionsCache().put(sessionId, localInfo);
        
        TestContextManager cm = new TestContextManager(project, sessionId);
        openContexts.put(sessionId, cm);

        byte[] newContent = createValidSessionZip(sessionId, "Open Session Updated");
        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                sessionId.toString(), "u1", "o1", "remote", "Open Session", "private", 
                "2023-01-02T00:00:00Z", "2023-01-02T00:00:00Z", "2023-01-02T00:00:00Z", null);
        
        syncCallbacks.remoteSessions.add(remoteMeta);
        syncCallbacks.remoteContent.put(sessionId, newContent);

        synchronizer.synchronize();

        assertTrue(cm.reloadCalled, "Context should have been reloaded");
        Path zipPath = sessionsDir.resolve(sessionId + ".zip");
        assertArrayEquals(newContent, Files.readAllBytes(zipPath));
    }
    
    @Test
    void openSessionResetsAfterDeletion() throws IOException {
        UUID sessionId = UUID.randomUUID();
        SessionInfo localInfo = new SessionInfo(sessionId, "Open Session", 100, 100);
        sessionManager.getSessionsCache().put(sessionId, localInfo);
        
        TestContextManager cm = new TestContextManager(project, sessionId);
        openContexts.put(sessionId, cm);

        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                sessionId.toString(), "u1", "o1", "remote", "Open Session", "private", 
                "2023-01-02T00:00:00Z", "2023-01-02T00:00:00Z", "2023-01-02T00:00:00Z", 
                "2023-01-03T00:00:00Z"); // Deleted
        syncCallbacks.remoteSessions.add(remoteMeta);

        synchronizer.synchronize();

        assertTrue(cm.createSessionCalled, "Context should have created new session after deletion");
        assertFalse(sessionManager.getSessionsCache().containsKey(sessionId));
    }

    @Test
    void skipDownloadIfModifiedLocally() throws IOException {
        UUID id = UUID.randomUUID();
        SessionInfo localInfo = new SessionInfo(id, "Session", 100, 100);
        sessionManager.getSessionsCache().put(id, localInfo);
        
        // Remote is newer
        RemoteSessionMeta remoteMeta = new RemoteSessionMeta(
                id.toString(), "u1", "o1", "remote", "Session", "private", 
                "2023-01-02T00:00:00Z", "2023-01-02T00:00:00Z", "2023-01-02T00:00:00Z", null);
        
        // Planner logic test: create action manually
        SessionSynchronizer.SyncAction action = new SessionSynchronizer.SyncAction(
                id, SessionSynchronizer.ActionType.DOWNLOAD, localInfo, remoteMeta);
        
        // Simulate local modification occurring after planning
        SessionInfo modifiedLocal = new SessionInfo(id, "Session", 200, 200);
        sessionManager.getSessionsCache().put(id, modifiedLocal);
        
        SessionSynchronizer.SyncExecutor executor = synchronizer.new SyncExecutor();
        SessionSynchronizer.SyncResult result = executor.execute(
                List.of(action), syncCallbacks, openContexts, "remote");
        
        assertTrue(result.skipped().contains(action), "Action should be skipped");
        assertEquals(0, result.succeeded().size());
    }

    @Test
    void fetchLatestChangesFirst() throws IOException {
        // Create 3 remote sessions with different modification times
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        String time1 = "2023-01-01T00:00:00Z"; // oldest
        String time2 = "2023-06-15T00:00:00Z"; // middle
        String time3 = "2023-12-31T00:00:00Z"; // newest

        byte[] zip1 = createValidSessionZip(id1, "Session 1");
        byte[] zip2 = createValidSessionZip(id2, "Session 2");
        byte[] zip3 = createValidSessionZip(id3, "Session 3");

        syncCallbacks.remoteSessions.add(new RemoteSessionMeta(
                id1.toString(), "u1", "o1", "test-remote", "Session 1", "private", time1, time1, time1, null));
        syncCallbacks.remoteSessions.add(new RemoteSessionMeta(
                id2.toString(), "u1", "o1", "test-remote", "Session 2", "private", time2, time2, time2, null));
        syncCallbacks.remoteSessions.add(new RemoteSessionMeta(
                id3.toString(), "u1", "o1", "test-remote", "Session 3", "private", time3, time3, time3, null));

        syncCallbacks.remoteContent.put(id1, zip1);
        syncCallbacks.remoteContent.put(id2, zip2);
        syncCallbacks.remoteContent.put(id3, zip3);

        // Track download order
        List<UUID> downloadOrder = new ArrayList<>();
        FakeSyncCallbacks customCallbacks = new FakeSyncCallbacks() {
            {
                remoteSessions = syncCallbacks.remoteSessions;
                remoteContent = syncCallbacks.remoteContent;
            }

            @Override
            public byte[] getRemoteSessionContent(UUID id) {
                downloadOrder.add(id);
                return remoteContent.getOrDefault(id, new byte[0]);
            }
        };
        synchronizer = new SessionSynchronizer(project, customCallbacks);

        synchronizer.synchronize();

        // Verify download order: newest first (id3), then middle (id2), then oldest (id1)
        assertEquals(3, downloadOrder.size(), "All 3 sessions should be downloaded");
        assertEquals(id3, downloadOrder.get(0), "Newest session should be downloaded first");
        assertEquals(id2, downloadOrder.get(1), "Middle session should be downloaded second");
        assertEquals(id1, downloadOrder.get(2), "Oldest session should be downloaded last");
    }

    private byte[] createValidSessionZip(UUID id, String name) throws IOException {
        Path tempZip = tempDir.resolve("temp_" + id + ".zip");
        SessionInfo info = new SessionInfo(id, name, 1000L, 1000L);
        try (var fs = java.nio.file.FileSystems.newFileSystem(tempZip, Map.of("create", "true"))) {
            Path manifestPath = fs.getPath("manifest.json");
            String json = ai.brokk.project.AbstractProject.objectMapper.writeValueAsString(info);
            Files.writeString(manifestPath, json);
        }
        return Files.readAllBytes(tempZip);
    }
}
