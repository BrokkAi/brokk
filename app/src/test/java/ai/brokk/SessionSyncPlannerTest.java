package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.Service.RemoteSessionMeta;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.SessionSynchronizer.ActionType;
import ai.brokk.SessionSynchronizer.SyncAction;
import ai.brokk.SessionSynchronizer.SyncInfo;
import ai.brokk.SessionSynchronizer.SyncPlanner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionSyncPlannerTest {

    private final SyncPlanner planner = new SyncPlanner();
    private final UUID sessionId = UUID.randomUUID();
    private final String remoteProject = "remote-project";

    private SessionInfo createLocal(UUID id, long modified) {
        return new SessionInfo(id, "Test Session", modified - 1000, modified);
    }

    private RemoteSessionMeta createRemote(UUID id, long modified) {
        return createRemote(id, modified, null);
    }

    private RemoteSessionMeta createRemote(UUID id, long modified, String deletedAt) {
        String ts = Instant.ofEpochMilli(modified).toString();
        // The record constructor order is: id, userId, orgId, remote, name, sharing, createdAt, updatedAt, modifiedAt,
        // deletedAt
        return new RemoteSessionMeta(
                id.toString(),
                "user",
                "org",
                remoteProject,
                "Test Session",
                "private",
                ts, // createdAt
                ts, // updatedAt
                ts, // modifiedAt
                deletedAt);
    }

    @Test
    void planUploadsLocalOnly() {
        SessionInfo local = createLocal(sessionId, 1000);
        List<SyncAction> actions =
                planner.plan(Map.of(sessionId, local), List.of(), Set.of(), Set.of(), new SyncInfo());

        assertEquals(1, actions.size());
        SyncAction action = actions.getFirst();
        assertEquals(sessionId, action.sessionId());
        assertEquals(ActionType.UPLOAD, action.type());
        assertEquals(local, action.localInfo());
    }

    @Test
    void plansDownloadRemoteOnly() {
        RemoteSessionMeta remote = createRemote(sessionId, 2000);
        List<SyncAction> actions = planner.plan(Map.of(), List.of(remote), Set.of(), Set.of(), new SyncInfo());

        assertEquals(1, actions.size());
        SyncAction action = actions.getFirst();
        assertEquals(sessionId, action.sessionId());
        assertEquals(ActionType.DOWNLOAD, action.type());
        assertEquals(remote, action.remoteMeta());
    }

    @Test
    void plansDeleteLocalIfRemoteDeleted() {
        SessionInfo local = createLocal(sessionId, 1000);
        // Remote deleted after local modification
        RemoteSessionMeta remote =
                createRemote(sessionId, 2000, Instant.ofEpochMilli(2000).toString());

        List<SyncAction> actions =
                planner.plan(Map.of(sessionId, local), List.of(remote), Set.of(), Set.of(), new SyncInfo());

        assertEquals(1, actions.size());
        assertEquals(ActionType.DELETE_LOCAL, actions.getFirst().type());
    }

    @Test
    void resurrectsLocalIfModifiedAfterRemoteDeletion() {
        SessionInfo local = createLocal(sessionId, 2000);
        // Remote deleted before local modification
        RemoteSessionMeta remote =
                createRemote(sessionId, 500, Instant.ofEpochMilli(1000).toString());

        List<SyncAction> actions =
                planner.plan(Map.of(sessionId, local), List.of(remote), Set.of(), Set.of(), new SyncInfo());

        assertEquals(1, actions.size());
        assertEquals(ActionType.UPLOAD, actions.getFirst().type());
        assertEquals(local, actions.getFirst().localInfo());
    }

    @Test
    void plansDeleteRemoteIfTombstone() {
        RemoteSessionMeta remote = createRemote(sessionId, 2000);
        List<SyncAction> actions = planner.plan(Map.of(), List.of(remote), Set.of(sessionId), Set.of(), new SyncInfo());

        assertEquals(1, actions.size());
        assertEquals(ActionType.DELETE_REMOTE, actions.getFirst().type());
    }

    @Test
    void resolvesConflictByTimestamp() {
        // Remote newer -> DOWNLOAD
        {
            SessionInfo local = createLocal(sessionId, 1000);
            RemoteSessionMeta remote = createRemote(sessionId, 2000);
            List<SyncAction> actions =
                    planner.plan(Map.of(sessionId, local), List.of(remote), Set.of(), Set.of(), new SyncInfo());
            assertEquals(1, actions.size());
            assertEquals(ActionType.DOWNLOAD, actions.getFirst().type());
        }

        // Local newer -> UPLOAD
        {
            SessionInfo local = createLocal(sessionId, 3000);
            RemoteSessionMeta remote = createRemote(sessionId, 2000);
            List<SyncAction> actions =
                    planner.plan(Map.of(sessionId, local), List.of(remote), Set.of(), Set.of(), new SyncInfo());
            assertEquals(1, actions.size());
            assertEquals(ActionType.UPLOAD, actions.getFirst().type());
        }

        // Equal -> No action
        {
            SessionInfo local = createLocal(sessionId, 2000);
            RemoteSessionMeta remote = createRemote(sessionId, 2000);
            List<SyncAction> actions =
                    planner.plan(Map.of(sessionId, local), List.of(remote), Set.of(), Set.of(), new SyncInfo());
            assertTrue(actions.isEmpty());
        }
    }

    @Test
    void ignoresUnreadableSessions() {
        RemoteSessionMeta remote = createRemote(sessionId, 2000);
        List<SyncAction> actions = planner.plan(Map.of(), List.of(remote), Set.of(), Set.of(sessionId), new SyncInfo());

        assertTrue(actions.isEmpty());
    }

    @Test
    void skipsOversizedSessionsForUpload() {
        UUID oversizedId = UUID.randomUUID();
        UUID normalId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();

        // Local-only session that is oversized - should NOT generate UPLOAD
        SessionInfo oversizedLocal = createLocal(oversizedId, 1000);

        // Local-only session that is normal - should generate UPLOAD
        SessionInfo normalLocal = createLocal(normalId, 1000);

        // Remote-only session - should generate DOWNLOAD (unaffected by oversized set)
        RemoteSessionMeta downloadRemote = createRemote(downloadId, 2000);

        // Local newer than remote but oversized - should NOT generate UPLOAD
        UUID conflictOversizedId = UUID.randomUUID();
        SessionInfo conflictLocal = createLocal(conflictOversizedId, 3000);
        RemoteSessionMeta conflictRemote = createRemote(conflictOversizedId, 2000);

        SyncInfo syncInfo = new SyncInfo(Set.of(oversizedId, conflictOversizedId));

        List<SyncAction> actions = planner.plan(
                Map.of(oversizedId, oversizedLocal, normalId, normalLocal, conflictOversizedId, conflictLocal),
                List.of(downloadRemote, conflictRemote),
                Set.of(),
                Set.of(),
                syncInfo);

        // Should have: 1 DOWNLOAD (downloadId), 1 UPLOAD (normalId)
        // Should NOT have: UPLOAD for oversizedId or conflictOversizedId
        assertEquals(2, actions.size());

        List<UUID> uploadIds = actions.stream()
                .filter(a -> a.type() == ActionType.UPLOAD)
                .map(SyncAction::sessionId)
                .toList();
        List<UUID> downloadIds = actions.stream()
                .filter(a -> a.type() == ActionType.DOWNLOAD)
                .map(SyncAction::sessionId)
                .toList();

        assertEquals(1, uploadIds.size());
        assertTrue(uploadIds.contains(normalId));
        assertFalse(uploadIds.contains(oversizedId));
        assertFalse(uploadIds.contains(conflictOversizedId));

        assertEquals(1, downloadIds.size());
        assertTrue(downloadIds.contains(downloadId));
    }

    @Test
    void prioritizesActions() {
        UUID idDeleteRemote = UUID.randomUUID();
        UUID idDownloadNew = UUID.randomUUID();
        UUID idDownloadOld = UUID.randomUUID();
        UUID idUpload = UUID.randomUUID();

        long base = 10000;

        // 1. DELETE_REMOTE
        RemoteSessionMeta remoteDelete = createRemote(idDeleteRemote, base);

        // 2. DOWNLOAD (Newer)
        SessionInfo localNew = createLocal(idDownloadNew, base);
        RemoteSessionMeta remoteNew = createRemote(idDownloadNew, base + 2000);

        // 3. DOWNLOAD (Older)
        SessionInfo localOld = createLocal(idDownloadOld, base);
        RemoteSessionMeta remoteOld = createRemote(idDownloadOld, base + 1000);

        // 4. UPLOAD
        SessionInfo localUpload = createLocal(idUpload, base);

        List<SyncAction> actions = planner.plan(
                Map.of(idDownloadNew, localNew, idDownloadOld, localOld, idUpload, localUpload),
                List.of(remoteDelete, remoteNew, remoteOld),
                Set.of(idDeleteRemote),
                Set.of(),
                new SyncInfo());

        assertEquals(4, actions.size());

        // Check order
        assertEquals(ActionType.DELETE_REMOTE, actions.get(0).type());
        assertEquals(idDeleteRemote, actions.get(0).sessionId());

        assertEquals(ActionType.DOWNLOAD, actions.get(1).type());
        assertEquals(idDownloadNew, actions.get(1).sessionId());

        assertEquals(ActionType.DOWNLOAD, actions.get(2).type());
        assertEquals(idDownloadOld, actions.get(2).sessionId());

        assertEquals(ActionType.UPLOAD, actions.get(3).type());
        assertEquals(idUpload, actions.get(3).sessionId());
    }
}
