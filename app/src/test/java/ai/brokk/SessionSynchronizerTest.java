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
        List<UUID> downloadedIds = new ArrayList<>();

        @Override
        public List<RemoteSessionMeta> listRemoteSessions(String remote) {
            return remoteSessions;
        }

        @Override
        public byte[] getRemoteSessionContent(UUID id) {
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

    /** Simple stub for ContextManager to verify interactions during sync. */
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

    private Map<UUID, IContextManager> openContexts = new HashMap<>();

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
            protected Map<UUID, IContextManager> getOpenContextManagers() {
                return openContexts;
            }
        };
    }

    @AfterEach
    public void tearDown() {
        project.close();
    }

    @Test
    void testSessionSynchronizationScenarios() throws IOException, InterruptedException {
        // Shared timestamps
        String timeStr = "2023-10-01T12:00:00Z";
        long timeMillis = java.time.Instant.parse(timeStr).toEpochMilli();

        // --- Session A: Download (Remote exists, Local missing) ---
        UUID idA = UUID.randomUUID();
        String nameA = "Session A";
        RemoteSessionMeta metaA = new RemoteSessionMeta(
                idA.toString(), "u1", "o1", "remote", nameA, "private", timeStr, timeStr, timeStr, null);

        byte[] contentA = createValidSessionZip(idA, nameA, timeMillis);
        syncCallbacks.remoteSessions.add(metaA);
        syncCallbacks.remoteContent.put(idA, contentA);

        TestContextManager cmA = new TestContextManager(project, idA);
        openContexts.put(idA, cmA);

        // --- Session B: Upload (Local exists, Remote missing) ---
        UUID idB = UUID.randomUUID();
        String nameB = "Session B";
        // Create local session file manually to ensure it exists on disk
        Path zipB = sessionManager.getSessionHistoryPath(idB);
        Files.createDirectories(zipB.getParent());
        Files.write(zipB, createValidSessionZip(idB, nameB, timeMillis));
        // Register in cache
        SessionInfo infoB = new SessionInfo(idB, nameB, timeMillis, timeMillis);
        sessionManager.getSessionsCache().put(idB, infoB);

        // --- Session C: Delete Remote (Local tombstone exists, Remote exists) ---
        UUID idC = UUID.randomUUID();
        String nameC = "Session C";
        RemoteSessionMeta metaC = new RemoteSessionMeta(
                idC.toString(), "u1", "o1", "remote", nameC, "private", timeStr, timeStr, timeStr, null);
        syncCallbacks.remoteSessions.add(metaC);

        Path tombstoneC = sessionsDir.resolve(idC + ".tombstone");
        Files.writeString(tombstoneC, "deleted");

        // --- Session D: Delete Local (Local exists, Remote deleted) ---
        UUID idD = UUID.randomUUID();
        String nameD = "Session D";
        SessionInfo infoD = new SessionInfo(idD, nameD, timeMillis, timeMillis);
        sessionManager.getSessionsCache().put(idD, infoD);
        // Ensure local file exists (though deleteLocalSession handles if it doesn't)
        Path zipD = sessionManager.getSessionHistoryPath(idD);
        Files.createDirectories(zipD.getParent());
        Files.write(zipD, createValidSessionZip(idD, nameD, timeMillis));

        RemoteSessionMeta metaD = new RemoteSessionMeta(
                idD.toString(),
                "u1",
                "o1",
                "remote",
                nameD,
                "private",
                timeStr,
                timeStr,
                timeStr,
                "2023-10-02T12:00:00Z"); // Deleted later
        syncCallbacks.remoteSessions.add(metaD);

        // --- Session E: No-op (Local and Remote match) ---
        UUID idE = UUID.randomUUID();
        String nameE = "Session E";
        SessionInfo infoE = new SessionInfo(idE, nameE, timeMillis, timeMillis);
        sessionManager.getSessionsCache().put(idE, infoE);

        RemoteSessionMeta metaE = new RemoteSessionMeta(
                idE.toString(), "u1", "o1", "remote", nameE, "private", timeStr, timeStr, timeStr, null);
        syncCallbacks.remoteSessions.add(metaE);

        // --- Execute ---
        synchronizer.synchronize();

        // --- Assertions ---

        // Session A
        assertTrue(syncCallbacks.downloadedIds.contains(idA), "Session A should be downloaded");
        assertTrue(cmA.reloadCalled, "ContextManager for Session A should be reloaded");
        assertTrue(sessionManager.getSessionsCache().containsKey(idA), "Session A should be in local cache");

        // Session B
        assertTrue(syncCallbacks.uploadedIds.contains(idB), "Session B should be uploaded");

        // Session C
        assertTrue(syncCallbacks.deletedRemoteIds.contains(idC), "Session C remote should be deleted");
        assertFalse(Files.exists(tombstoneC), "Session C tombstone should be removed");

        // Session D
        assertFalse(sessionManager.getSessionsCache().containsKey(idD), "Session D should be removed from local cache");
        assertFalse(Files.exists(zipD), "Session D local file should be deleted");

        // Session E
        assertFalse(syncCallbacks.downloadedIds.contains(idE), "Session E should not be downloaded");
        assertFalse(syncCallbacks.uploadedIds.contains(idE), "Session E should not be uploaded");
        assertFalse(syncCallbacks.deletedRemoteIds.contains(idE), "Session E should not be deleted");
    }

    private byte[] createValidSessionZip(UUID id, String name, long modified) throws IOException {
        Path tempZip = tempDir.resolve("temp_" + id + ".zip");
        SessionInfo info = new SessionInfo(id, name, modified, modified);
        try (var fs = java.nio.file.FileSystems.newFileSystem(tempZip, Map.of("create", "true"))) {
            Path manifestPath = fs.getPath("manifest.json");
            String json = ai.brokk.project.AbstractProject.objectMapper.writeValueAsString(info);
            Files.writeString(manifestPath, json);
        }
        return Files.readAllBytes(tempZip);
    }
}
