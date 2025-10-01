package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.sessions.TaskListStore.TaskEntryDto;
import io.github.jbellis.brokk.sessions.TaskListStore.TaskListData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManagerTaskListIoTest {

    @TempDir Path tempDir;
    private static ExecutorService concurrentTestExecutor;

    @BeforeEach
    void setup() {
        // Ensure the directory for sessions is clean before each test
        Path sessionsDir = tempDir.resolve(".brokk").resolve("sessions");
        try {
            if (Files.exists(sessionsDir)) {
                Files.walk(sessionsDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (Exception e) {
                                // Log, but continue
                            }
                        });
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to clean sessions directory", e);
        }
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        if (concurrentTestExecutor != null) {
            concurrentTestExecutor.shutdown();
            if (!concurrentTestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                concurrentTestExecutor.shutdownNow();
            }
        }
    }

    @Test
    void concurrentSameKeySerializes() throws Exception {
        concurrentTestExecutor = Executors.newFixedThreadPool(4);
        MainProject project = new MainProject(tempDir);
        SessionManager sessionManager = project.getSessionManager();
        SessionManager.SessionInfo sessionInfo = sessionManager.newSession("Test Session");
        UUID sessionId = sessionInfo.id();

        List<String> executionOrder = new ArrayList<>();

        int numWrites = 100;
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < numWrites; i++) {
            final int taskId = i;
            futures.add(concurrentTestExecutor.submit(() -> {
                try {
                    sessionManager.writeTaskList(sessionId, new TaskListData(List.of(
                            new TaskEntryDto("task_" + taskId, taskId % 2 == 0)
                    ))).get(5, TimeUnit.SECONDS); // Wait for each write to complete
                    executionOrder.add(String.valueOf(taskId));
                } catch (Exception e) {
                    fail("Write task failed: " + e.getMessage());
                }
            }));
        }

        // Wait for all writes to be submitted and completed via SessionManager's internal executor
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        // Verify execution order (should be sequential for the same session key)
        assertEquals(numWrites, executionOrder.size());
        for (int i = 0; i < numWrites; i++) {
            assertEquals(String.valueOf(i), executionOrder.get(i), "Writes for the same session should be serialized");
        }

        // Verify the final state is correct
        TaskListData finalData = sessionManager.readTaskList(sessionId).get(5, TimeUnit.SECONDS);
        assertNotNull(finalData);
        assertEquals(1, finalData.tasks().size());
        assertEquals("task_" + (numWrites - 1), finalData.tasks().getFirst().text());
        assertEquals((numWrites - 1) % 2 == 0, finalData.tasks().getFirst().done());

        project.close();
    }

    @Test
    void concurrentDifferentKeysParallel() throws Exception {
        concurrentTestExecutor = Executors.newFixedThreadPool(4);
        MainProject project = new MainProject(tempDir);
        SessionManager sessionManager = project.getSessionManager();

        UUID sessionId1 = sessionManager.newSession("Session 1").id();
        UUID sessionId2 = sessionManager.newSession("Session 2").id();

        int numWrites = 50;
        List<Future<?>> futures = new ArrayList<>();
        ConcurrentHashMap<UUID, AtomicInteger> writeCounts = new ConcurrentHashMap<>();
        writeCounts.put(sessionId1, new AtomicInteger(0));
        writeCounts.put(sessionId2, new AtomicInteger(0));

        // Submit interleaved writes for different sessions
        for (int i = 0; i < numWrites; i++) {
            final int taskId = i;
            futures.add(concurrentTestExecutor.submit(() -> {
                try {
                    sessionManager.writeTaskList(sessionId1, new TaskListData(List.of(
                            new TaskEntryDto("session1_task_" + taskId, false)
                    ))).get(5, TimeUnit.SECONDS);
                    writeCounts.get(sessionId1).incrementAndGet();
                } catch (Exception e) {
                    fail("Write task for session 1 failed: " + e.getMessage());
                }
            }));
            futures.add(concurrentTestExecutor.submit(() -> {
                try {
                    sessionManager.writeTaskList(sessionId2, new TaskListData(List.of(
                            new TaskEntryDto("session2_task_" + taskId, true)
                    ))).get(5, TimeUnit.SECONDS);
                    writeCounts.get(sessionId2).incrementAndGet();
                } catch (Exception e) {
                    fail("Write task for session 2 failed: " + e.getMessage());
                }
            }));
        }

        // Wait for all writes to complete
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        // Verify all writes completed for both sessions
        assertEquals(numWrites, writeCounts.get(sessionId1).get());
        assertEquals(numWrites, writeCounts.get(sessionId2).get());

        // Verify final state for session 1
        TaskListData finalData1 = sessionManager.readTaskList(sessionId1).get(5, TimeUnit.SECONDS);
        assertNotNull(finalData1);
        assertEquals(1, finalData1.tasks().size());
        assertEquals("session1_task_" + (numWrites - 1), finalData1.tasks().getFirst().text());
        assertFalse(finalData1.tasks().getFirst().done());

        // Verify final state for session 2
        TaskListData finalData2 = sessionManager.readTaskList(sessionId2).get(5, TimeUnit.SECONDS);
        assertNotNull(finalData2);
        assertEquals(1, finalData2.tasks().size());
        assertEquals("session2_task_" + (numWrites - 1), finalData2.tasks().getFirst().text());
        assertTrue(finalData2.tasks().getFirst().done());

        project.close();
    }

    @Test
    void readNonExistentTaskList() throws Exception {
        MainProject project = new MainProject(tempDir);
        SessionManager sessionManager = project.getSessionManager();
        UUID sessionId = SessionManager.newSessionId(); // A session that doesn't exist on disk

        TaskListData data = sessionManager.readTaskList(sessionId).get(5, TimeUnit.SECONDS);
        assertNotNull(data);
        assertTrue(data.tasks().isEmpty(), "Reading non-existent task list should return empty data");

        project.close();
    }
}
