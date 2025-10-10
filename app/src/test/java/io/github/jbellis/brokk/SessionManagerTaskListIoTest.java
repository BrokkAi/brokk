package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManagerTaskListIoTest {

    @TempDir
    Path tempDir;

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
                    sessionManager
                            .writeTaskList(
                                    sessionId1,
                                    new TaskListData(List.of(new TaskListEntryDto("session1_task_" + taskId, false))))
                            .get(5, TimeUnit.SECONDS);
                    writeCounts.get(sessionId1).incrementAndGet();
                } catch (Exception e) {
                    fail("Write task for session 1 failed: " + e.getMessage());
                }
            }));
            futures.add(concurrentTestExecutor.submit(() -> {
                try {
                    sessionManager
                            .writeTaskList(
                                    sessionId2,
                                    new TaskListData(List.of(new TaskListEntryDto("session2_task_" + taskId, true))))
                            .get(5, TimeUnit.SECONDS);
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
        assertEquals(
                "session1_task_" + (numWrites - 1),
                finalData1.tasks().getFirst().text());
        assertFalse(finalData1.tasks().getFirst().done());

        // Verify final state for session 2
        TaskListData finalData2 = sessionManager.readTaskList(sessionId2).get(5, TimeUnit.SECONDS);
        assertNotNull(finalData2);
        assertEquals(1, finalData2.tasks().size());
        assertEquals(
                "session2_task_" + (numWrites - 1),
                finalData2.tasks().getFirst().text());
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

    @Test
    void concurrentSaveThenLoadSameSession() throws Exception {
        concurrentTestExecutor = Executors.newFixedThreadPool(4);
        MainProject project = new MainProject(tempDir);
        SessionManager sessionManager = project.getSessionManager();
        SessionManager.SessionInfo sessionInfo = sessionManager.newSession("Test Session");
        UUID sessionId = sessionInfo.id();

        int numWrites = 100;
        List<Future<?>> futures = new ArrayList<>();
        List<Integer> finalReadValues = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numWrites; i++) {
            final int writeValue = i;
            // Submit writes
            futures.add(concurrentTestExecutor.submit(() -> {
                try {
                    sessionManager
                            .writeTaskList(
                                    sessionId,
                                    new TaskListData(List.of(new TaskListEntryDto("task_write_" + writeValue, false))))
                            .get(5, TimeUnit.SECONDS); // Wait for each write to complete
                } catch (Exception e) {
                    fail("Write task failed: " + e.getMessage());
                }
            }));

            // Concurrently submit reads; reads should always see the latest committed write
            futures.add(concurrentTestExecutor.submit(() -> {
                try {
                    TaskListData data = sessionManager.readTaskList(sessionId).get(5, TimeUnit.SECONDS);
                    assertNotNull(data);
                    if (!data.tasks().isEmpty()) {
                        String taskText = data.tasks().getFirst().text();
                        assertTrue(taskText.startsWith("task_write_"));
                        finalReadValues.add(Integer.parseInt(taskText.substring("task_write_".length())));
                    }
                } catch (Exception e) {
                    fail("Read task failed: " + e.getMessage());
                }
            }));
        }

        // Wait for all operations to complete
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        // Verify the final state is correct
        TaskListData finalData = sessionManager.readTaskList(sessionId).get(5, TimeUnit.SECONDS);
        assertNotNull(finalData);
        assertEquals(1, finalData.tasks().size());
        assertEquals(
                "task_write_" + (numWrites - 1), finalData.tasks().getFirst().text());

        // All values read should be less than or equal to the highest value written so far at the time of read.
        // Given SerialByKeyExecutor, this means any read will see a state from a completed write.
        // The most important part is that the FINAL state is correct.
        List<Integer> orderedReads =
                finalReadValues.stream().sorted().distinct().toList();
        assertFalse(orderedReads.isEmpty());

        if (!finalReadValues.isEmpty()) {
            OptionalInt maxRead =
                    finalReadValues.stream().mapToInt(Integer::intValue).max();
            assertTrue(maxRead.isPresent());
            assertTrue(maxRead.getAsInt() <= (numWrites - 1), "Read value should not exceed the final written value");
            OptionalInt minRead =
                    finalReadValues.stream().mapToInt(Integer::intValue).min();
            assertTrue(minRead.isPresent());
            assertTrue(minRead.getAsInt() >= 0, "Read value should not be less than zero");
        }

        project.close();
    }

    @Test
    void parallelDifferentSessions() throws Exception {
        concurrentTestExecutor = Executors.newFixedThreadPool(4);
        MainProject project = new MainProject(tempDir);
        SessionManager sessionManager = project.getSessionManager();

        UUID sessionId1 = sessionManager.newSession("Parallel Session 1").id();
        UUID sessionId2 = sessionManager.newSession("Parallel Session 2").id();
        UUID sessionId3 = sessionManager.newSession("Parallel Session 3").id();

        int numOperationsPerSession = 50;
        CountDownLatch startLatch = new CountDownLatch(3);
        CountDownLatch endLatch = new CountDownLatch(3 * numOperationsPerSession);

        // Define the session task as a Function that returns a Runnable
        Function<UUID, Function<Integer, Function<CountDownLatch, Runnable>>> sessionTaskCreator =
                (sessionId) -> (startValue) -> (latch) -> () -> {
                    try {
                        startLatch.countDown();
                        assertTrue(startLatch.await(5, TimeUnit.SECONDS), "All sessions should start concurrently");
                        for (int i = 0; i < numOperationsPerSession; i++) {
                            final int value = startValue + i;
                            // Interleave writes and reads
                            sessionManager
                                    .writeTaskList(
                                            sessionId,
                                            new TaskListData(List.of(new TaskListEntryDto("task_" + value, false))))
                                    .get(5, TimeUnit.SECONDS);

                            TaskListData data =
                                    sessionManager.readTaskList(sessionId).get(5, TimeUnit.SECONDS);
                            assertNotNull(data);
                            assertEquals(1, data.tasks().size());
                            String taskText = data.tasks().getFirst().text();
                            assertTrue(taskText.startsWith("task_"));
                            assertEquals(value, Integer.parseInt(taskText.substring("task_".length())));
                            latch.countDown();
                        }
                    } catch (Exception e) {
                        fail("Session task failed for " + sessionId + ": " + e.getMessage());
                    }
                };

        CompletableFuture<Void> future1 = CompletableFuture.runAsync(
                sessionTaskCreator.apply(sessionId1).apply(100).apply(endLatch), concurrentTestExecutor);
        CompletableFuture<Void> future2 = CompletableFuture.runAsync(
                sessionTaskCreator.apply(sessionId2).apply(200).apply(endLatch), concurrentTestExecutor);
        CompletableFuture<Void> future3 = CompletableFuture.runAsync(
                sessionTaskCreator.apply(sessionId3).apply(300).apply(endLatch), concurrentTestExecutor);

        CompletableFuture.allOf(future1, future2, future3).get(15, TimeUnit.SECONDS);

        // Verify final state for each session
        TaskListData finalData1 = sessionManager.readTaskList(sessionId1).get(5, TimeUnit.SECONDS);
        assertEquals(
                "task_" + (100 + numOperationsPerSession - 1),
                finalData1.tasks().getFirst().text());
        TaskListData finalData2 = sessionManager.readTaskList(sessionId2).get(5, TimeUnit.SECONDS);
        assertEquals(
                "task_" + (200 + numOperationsPerSession - 1),
                finalData2.tasks().getFirst().text());
        TaskListData finalData3 = sessionManager.readTaskList(sessionId3).get(5, TimeUnit.SECONDS);
        assertEquals(
                "task_" + (300 + numOperationsPerSession - 1),
                finalData3.tasks().getFirst().text());

        project.close();
    }
}
