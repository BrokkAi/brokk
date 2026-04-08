package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.HistoryIo;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

        // 4. Legacy manifest cost is ignored when there is no ledger
        UUID legacyId = SessionManager.newSessionId();
        SessionManager.SessionInfo legacyInfo =
                new SessionManager.SessionInfo(legacyId, "legacy", 1000L, 2000L, "4.0", 5.99);
        sessionManager.getSessionsCache().put(legacyId, legacyInfo);

        assertEquals(0.0, sessionManager.getTotalSessionCost(legacyId), 0.001);
    }

    @Test
    void logRequestOnly_writes_request_json_without_response_log() throws Exception {
        var project = new TestProject(tempDir);
        var cm = new TestContextManager(
                project, new TestConsoleIO(), java.util.Set.of(), new ai.brokk.testutil.TestAnalyzer());
        var llm = new Llm(
                new AbstractService.OfflineStreamingModel(),
                "test request log only",
                TaskResult.Type.CODE,
                cm,
                false,
                false,
                false,
                false);

        llm.logRequestOnly(List.of(new UserMessage("hello")));

        var historyDir = Llm.getHistoryBaseDir(tempDir);
        var requestFiles = Files.walk(historyDir)
                .filter(path -> path.getFileName().toString().endsWith("request.json"))
                .toList();
        var responseLogs = Files.walk(historyDir)
                .filter(path -> path.getFileName().toString().endsWith(".log"))
                .toList();

        assertEquals(1, requestFiles.size());
        assertTrue(Files.readString(requestFiles.getFirst()).contains("hello"));
        assertEquals(List.of(), responseLogs);
    }
}
