package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.util.HistoryIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LlmTest {

    @TempDir
    Path tempDir;

    @Test
    void testTotalCostFromLedger() throws InterruptedException, IOException, ExecutionException, TimeoutException {
        Path testSessionDir = tempDir.resolve("cost-ledger-stats-" + System.currentTimeMillis());
        Files.createDirectories(testSessionDir);
        SessionManager sessionManager = new SessionManager(testSessionDir);

        var sessionInfo = sessionManager.newSession("cost-test");
        UUID sessionId = sessionInfo.id();

        // 1. Record events
        sessionManager.recordCostEvent(
                sessionId,
                new SessionManager.CostEvent(
                        UUID.randomUUID().toString(),
                        System.currentTimeMillis(),
                        sessionId,
                        "op1",
                        "type1",
                        "m1",
                        "t1",
                        10,
                        0,
                        0,
                        5,
                        0.50));
        sessionManager.recordCostEvent(
                sessionId,
                new SessionManager.CostEvent(
                        UUID.randomUUID().toString(),
                        System.currentTimeMillis(),
                        sessionId,
                        "op2",
                        "type2",
                        "m2",
                        "t1",
                        20,
                        0,
                        0,
                        10,
                        1.25));

        // 2. Verify in-memory aggregation works immediately (via readCostEvents cache path)
        assertEquals(1.75, sessionManager.totalCostFromLedger(sessionId), 0.001);

        // 3. Wait for async ledger writes and verify HistoryIo stats integration
        // We use the same sessionManager instance to ensure we don't have multiple managers competing for the same zip
        // Force all pending async writes for this session to complete
        sessionManager
                .getSessionExecutorByKey()
                .submit(sessionId.toString(), () -> null)
                .get(5, TimeUnit.SECONDS);

        Path zipPath = testSessionDir.resolve(sessionId + ".zip");
        var stats = HistoryIo.countSessionStats(zipPath);
        double diskCost = stats.totalCostUsd();
        assertEquals(1.75, diskCost, 0.001, "Cost from disk ledger should eventually reach 1.75");

        // 4. Legacy fallback: create a session with manifest cost but NO ledger
        UUID legacyId = SessionManager.newSessionId();
        SessionManager.SessionInfo legacyInfo =
                new SessionManager.SessionInfo(legacyId, "legacy", 1000L, 2000L, "4.0", 5.99);
        sessionManager.getSessionsCache().put(legacyId, legacyInfo);

        // No ledger file exists for legacyId, so it should fall back to manifest totalCost
        assertEquals(5.99, sessionManager.getTotalSessionCost(legacyId), 0.001);
    }
}
