package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.SessionManager.CostEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SessionManagerCostLedgerTest {

    @TempDir
    Path tempDir;

    @Test
    void testRecordAndReadCostEvents() throws Exception {
        SessionManager sessionManager = new SessionManager(tempDir);
        String sessionName = "Test Session";
        SessionManager.SessionInfo info = sessionManager.newSession(sessionName);
        UUID sessionId = info.id();

        CostEvent event1 = new CostEvent(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                sessionId,
                "Task 1",
                "CODE",
                "gpt-4",
                "standard",
                100,
                50,
                0,
                20,
                0.005);

        CostEvent event2 = new CostEvent(
                UUID.randomUUID().toString(),
                System.currentTimeMillis() + 100,
                sessionId,
                "Task 2",
                "ARCHITECT",
                "claude-3",
                "priority",
                200,
                0,
                50,
                100,
                0.015);

        sessionManager.recordCostEvent(sessionId, event1);
        sessionManager.recordCostEvent(sessionId, event2);

        // Wait for async persistence tasks to complete
        sessionManager
                .getSessionExecutorByKey()
                .submit(sessionId.toString(), () -> null)
                .get(5, TimeUnit.SECONDS);

        // Read back within same manager instance
        List<CostEvent> readEvents = sessionManager.readCostEvents(sessionId);
        assertEquals(2, readEvents.size());
        assertEquals(event1, readEvents.get(0));
        assertEquals(event2, readEvents.get(1));

        // Read back from a fresh manager instance to verify persistence
        SessionManager sessionManager2 = new SessionManager(tempDir);
        List<CostEvent> readEvents2 = sessionManager2.readCostEvents(sessionId);
        assertEquals(2, readEvents2.size());
        assertEquals(event1, readEvents2.get(0));
        assertEquals(event2, readEvents2.get(1));
    }

    @Test
    void testReadCostEventsMergedAndDeduplicated() throws Exception {
        SessionManager sessionManager1 = new SessionManager(tempDir);
        UUID sessionId = sessionManager1.newSession("Merge Test").id();

        CostEvent event1 = new CostEvent(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                sessionId,
                "Op 1",
                "CODE",
                "gpt-4",
                "standard",
                10,
                0,
                0,
                5,
                0.001);

        // Record and ensure event1 is persisted before creating the second manager
        sessionManager1.recordCostEvent(sessionId, event1);
        sessionManager1
                .getSessionExecutorByKey()
                .submit(sessionId.toString(), () -> null)
                .get(5, TimeUnit.SECONDS);

        // Create a second manager instance that has event1 on disk but not in memory
        SessionManager sessionManager2 = new SessionManager(tempDir);

        // Record a second event in manager2; it is in memory but not yet persisted
        CostEvent event2 = new CostEvent(
                UUID.randomUUID().toString(),
                System.currentTimeMillis() + 10,
                sessionId,
                "Op 2",
                "CODE",
                "gpt-4",
                "standard",
                20,
                0,
                0,
                10,
                0.002);
        sessionManager2.recordCostEvent(sessionId, event2);

        // Read BEFORE manager2 finishes persisting event2.
        // Must return both event1 (from disk) and event2 (from memory cache), in that order.
        List<CostEvent> mergedEvents = sessionManager2.readCostEvents(sessionId);
        assertEquals(2, mergedEvents.size(), "Should merge persisted and cached events");
        assertEquals(List.of(event1, event2), mergedEvents);

        // Wait for manager2 to finish persistence so event2 is now on disk AND in memory cache
        sessionManager2
                .getSessionExecutorByKey()
                .submit(sessionId.toString(), () -> null)
                .get(5, TimeUnit.SECONDS);

        // Read again: event2 is now in both persisted and cached; dedup must prevent double-counting
        List<CostEvent> dedupedEvents = sessionManager2.readCostEvents(sessionId);
        assertEquals(2, dedupedEvents.size(), "Should not duplicate events after persistence completes");
        assertEquals(List.of(event1, event2), dedupedEvents);
    }

    @Test
    void testLegacyManifestCostMigratesToSingleLedgerEntry() throws Exception {
        SessionManager sessionManager = new SessionManager(tempDir);
        SessionManager.SessionInfo sessionInfo = sessionManager.newSession("Legacy Migration");
        UUID sessionId = sessionInfo.id();

        SessionManager.SessionInfo legacyInfo = new SessionManager.SessionInfo(
                sessionId,
                sessionInfo.name(),
                sessionInfo.created(),
                sessionInfo.modified(),
                sessionInfo.version(),
                5.99);
        sessionManager.getSessionsCache().put(sessionId, legacyInfo);

        CostEvent newEvent = new CostEvent(
                UUID.randomUUID().toString(),
                System.currentTimeMillis() + 1,
                sessionId,
                "new op",
                "CODE",
                "gpt-4",
                "standard",
                10,
                0,
                0,
                5,
                1.25);
        sessionManager.recordCostEvent(sessionId, newEvent);

        List<CostEvent> events = sessionManager.readCostEvents(sessionId);
        long legacyEvents = events.stream()
                .filter(e -> "LEGACY_MIGRATION".equals(e.operationType()))
                .count();
        assertEquals(1, legacyEvents, "Expected exactly one legacy migration event");
        assertEquals(7.24, sessionManager.getTotalSessionCost(sessionId), 0.001);

        sessionManager
                .getSessionExecutorByKey()
                .submit(sessionId.toString(), () -> null)
                .get(5, TimeUnit.SECONDS);

        SessionManager reloaded = new SessionManager(tempDir);
        List<CostEvent> reloadedEvents = reloaded.readCostEvents(sessionId);
        long reloadedLegacyEvents = reloadedEvents.stream()
                .filter(e -> "LEGACY_MIGRATION".equals(e.operationType()))
                .count();
        assertEquals(1, reloadedLegacyEvents, "Legacy migration must remain idempotent after reload");
        assertTrue(
                reloadedEvents.stream().anyMatch(e -> e.equals(newEvent)),
                "Reloaded ledger should contain the new post-migration event");
        assertEquals(7.24, reloaded.getTotalSessionCost(sessionId), 0.001);
    }
}
