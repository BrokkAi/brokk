package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.SessionManager.CostEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
                System.currentTimeMillis(), sessionId, "Task 1", "CODE", "gpt-4", "standard", 100, 50, 0, 20, 0.005);

        CostEvent event2 = new CostEvent(
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
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

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
}
