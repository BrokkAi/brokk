package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.Service.RemoteSessionMeta;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.SessionSynchronizer.SyncCallbacks;
import ai.brokk.context.Context;
import ai.brokk.context.ContextHistory;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.util.HistoryIo;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static class FakeSyncCallbacks implements SyncCallbacks {
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
        TestContextManager cm = new TestContextManager(project, UUID.randomUUID());
        synchronizer = new SessionSynchronizer(cm, syncCallbacks) {
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
        long timeMillis = Instant.parse(timeStr).toEpochMilli();

        // --- Session A: Download (Remote exists, Local missing) ---
        UUID idA = UUID.randomUUID();
        String nameA = "Session A";
        RemoteSessionMeta metaA = new RemoteSessionMeta(
                idA.toString(), "u1", "o1", "remote", nameA, "private", timeStr, timeStr, timeStr, null);

        byte[] contentA = createValidSessionZip(idA, nameA, timeMillis);
        syncCallbacks.remoteSessions.add(metaA);
        syncCallbacks.remoteContent.put(idA, contentA);

        TestContextManager cmA = new TestContextManager(project, idA);

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
        assertFalse(cmA.reloadCalled, "ContextManager for Session A should not be reloaded");
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

        Context context = new Context(new TestContextManager(project, id));
        ContextHistory history = new ContextHistory(context);
        HistoryIo.writeZip(history, tempZip);

        SessionInfo info = new SessionInfo(id, name, modified, modified);
        try (var fs = FileSystems.newFileSystem(tempZip, (ClassLoader) null)) {
            Path manifestPath = fs.getPath("manifest.json");
            String json = AbstractProject.objectMapper.writeValueAsString(info);
            Files.writeString(manifestPath, json);
        }
        return Files.readAllBytes(tempZip);
    }

    // ==================== ContextManager sync scheduling tests ====================

    /**
     * A test subclass of ContextManager that allows injecting a fake SessionSynchronizer.
     */
    private static class TestableContextManager extends ContextManager {
        private final SessionSynchronizer fakeSynchronizer;

        TestableContextManager(IProject project, SessionSynchronizer fakeSynchronizer) {
            super(project);
            this.fakeSynchronizer = fakeSynchronizer;
        }

        @Override
        SessionSynchronizer createSessionSynchronizer() {
            return fakeSynchronizer;
        }
    }

    /**
     * A fake SessionSynchronizer that tracks calls and can block on a latch.
     */
    private static class BlockingSynchronizer extends SessionSynchronizer {
        private final CountDownLatch blockLatch;
        private final CountDownLatch startedLatch;
        private final AtomicInteger syncCount = new AtomicInteger(0);
        private final AtomicInteger concurrentSyncs = new AtomicInteger(0);
        private final AtomicInteger maxConcurrentSyncs = new AtomicInteger(0);
        private final Set<String> workerThreadNames = ConcurrentHashMap.newKeySet();

        BlockingSynchronizer(IContextManager contextManager, CountDownLatch blockLatch, CountDownLatch startedLatch) {
            super(contextManager);
            this.blockLatch = blockLatch;
            this.startedLatch = startedLatch;
        }

        @Override
        public void synchronize() throws IOException, InterruptedException {
            workerThreadNames.add(Thread.currentThread().getName());
            int current = concurrentSyncs.incrementAndGet();
            maxConcurrentSyncs.updateAndGet(max -> Math.max(max, current));

            syncCount.incrementAndGet();
            startedLatch.countDown();

            try {
                blockLatch.await(30, TimeUnit.SECONDS);
            } finally {
                concurrentSyncs.decrementAndGet();
            }
        }

        int getSyncCount() {
            return syncCount.get();
        }

        int getMaxConcurrentSyncs() {
            return maxConcurrentSyncs.get();
        }

        Set<String> getWorkerThreadNames() {
            return workerThreadNames;
        }
    }

    @Test
    void testOrderedSyncSubmissionOnSharedExecutor() throws Exception {
        // Setup: Create a MainProject with a real background executor
        Path projectDir = tempDir.resolve("sync-order-project");
        Files.createDirectories(projectDir);
        MainProject mainProject = MainProject.forTests(projectDir);

        CountDownLatch firstSyncBlockLatch = new CountDownLatch(1);
        CountDownLatch firstSyncStartedLatch = new CountDownLatch(1);
        CountDownLatch secondSyncStartedLatch = new CountDownLatch(1);

        // Track when the second sync actually starts
        AtomicInteger secondSyncStartCount = new AtomicInteger(0);

        // Create a synchronizer that blocks on the first call
        var fakeSynchronizer =
                new BlockingSynchronizer(
                        new TestContextManager(mainProject, UUID.randomUUID()),
                        firstSyncBlockLatch,
                        firstSyncStartedLatch) {
                    @Override
                    public void synchronize() throws IOException, InterruptedException {
                        int callNum = getSyncCount();
                        super.synchronize();
                        if (callNum >= 1) {
                            secondSyncStartCount.incrementAndGet();
                            secondSyncStartedLatch.countDown();
                        }
                    }
                };

        try {
            TestableContextManager cm = new TestableContextManager(mainProject, fakeSynchronizer);
            cm.setSessionsSyncActiveForTest(true);

            // Submit two sync requests
            CompletableFuture<Void> first = cm.syncSessionsAsync();
            CompletableFuture<Void> second = cm.syncSessionsAsync();

            // Wait for first sync to start
            assertTrue(firstSyncStartedLatch.await(5, TimeUnit.SECONDS), "First sync should start");

            // Verify second sync hasn't started yet (serialization)
            Thread.sleep(100); // Brief wait to ensure ordering
            assertEquals(0, secondSyncStartCount.get(), "Second sync should not start while first is blocked");

            // Release the first sync
            firstSyncBlockLatch.countDown();

            // Wait for both to complete
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            // Verify serialization: max concurrent should be 1
            assertEquals(1, fakeSynchronizer.getMaxConcurrentSyncs(), "Max concurrent syncs should be 1 (serialized)");

            // Verify worker threads come from project background executor
            for (String threadName : fakeSynchronizer.getWorkerThreadNames()) {
                assertTrue(
                        threadName.startsWith("MainProject-"),
                        "Worker thread should be from MainProject executor, was: " + threadName);
            }

        } finally {
            firstSyncBlockLatch.countDown(); // Ensure cleanup
            mainProject.close();
        }
    }

    @Test
    void testShutdownSafetyAndFinalSyncHandling() throws Exception {
        // Setup: Create a MainProject with a real background executor
        Path projectDir = tempDir.resolve("sync-shutdown-project");
        Files.createDirectories(projectDir);
        MainProject mainProject = MainProject.forTests(projectDir);

        CountDownLatch syncBlockLatch = new CountDownLatch(1);
        CountDownLatch syncStartedLatch = new CountDownLatch(1);
        AtomicInteger totalSyncCalls = new AtomicInteger(0);

        var fakeSynchronizer =
                new BlockingSynchronizer(
                        new TestContextManager(mainProject, UUID.randomUUID()), syncBlockLatch, syncStartedLatch) {
                    @Override
                    public void synchronize() throws IOException, InterruptedException {
                        totalSyncCalls.incrementAndGet();
                        super.synchronize();
                    }
                };

        try {
            TestableContextManager cm = new TestableContextManager(mainProject, fakeSynchronizer);
            cm.setSessionsSyncActiveForTest(true);

            // Start closeAsync - this should trigger the final sync
            CompletableFuture<Void> closeFuture = cm.closeAsync(5000);

            // Wait for the final sync to start
            assertTrue(syncStartedLatch.await(5, TimeUnit.SECONDS), "Final sync should start during close");
            assertTrue(cm.isClosing(), "ContextManager should be marked as closing");

            // Verify that a normal syncSessionsAsync() is skipped while closing
            int syncCountBeforeNormalCall = totalSyncCalls.get();
            CompletableFuture<Void> normalSyncDuringClose = cm.syncSessionsAsync();
            normalSyncDuringClose.get(1, TimeUnit.SECONDS); // Should complete immediately (no-op)
            assertEquals(
                    syncCountBeforeNormalCall, totalSyncCalls.get(), "Normal sync should be skipped while closing");

            // Verify background executor is NOT yet shut down while final sync is blocked
            assertFalse(
                    mainProject.getBackgroundExecutor().isShutdown(),
                    "Background executor should not be shut down while final sync is blocked");

            // Verify close future is not yet complete
            assertFalse(closeFuture.isDone(), "Close future should not complete before final sync is released");

            // Release the final sync
            syncBlockLatch.countDown();

            // Wait for close to complete
            closeFuture.get(10, TimeUnit.SECONDS);

            // Verify background executor is shut down after close completes
            assertTrue(
                    mainProject.getBackgroundExecutor().isShutdown(),
                    "Background executor should be shut down after close completes");

        } finally {
            syncBlockLatch.countDown(); // Ensure cleanup
        }
    }
}
