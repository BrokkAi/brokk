package io.github.jbellis.brokk.util;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SerialByKeyExecutorTest {

    private static final ExecutorService executorService = Executors.newFixedThreadPool(3);
    private static final SerialByKeyExecutor serialByKeyExecutor = new SerialByKeyExecutor(executorService);

    @AfterAll
    static void afterAll() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testSerialExecutionWithSameKey() throws Exception {
        var executionOrder = new CopyOnWriteArrayList<String>();
        var key = "test-key";

        // Create three callables that record their execution order
        var future1 = serialByKeyExecutor.submit(key, () -> {
            executionOrder.add("task1");
            // Add a small delay to make timing issues more obvious if they exist
            Thread.sleep(100);
            return "result1";
        });

        var future2 = serialByKeyExecutor.submit(key, () -> {
            executionOrder.add("task2");
            // Add a small delay to make timing issues more obvious if they exist
            Thread.sleep(50);
            return "result2";
        });

        var future3 = serialByKeyExecutor.submit(key, () -> {
            executionOrder.add("task3");
            return "result3";
        });

        // Wait for all tasks to complete and verify results
        assertEquals("result1", future1.get(1, TimeUnit.SECONDS));
        assertEquals("result2", future2.get(1, TimeUnit.SECONDS));
        assertEquals("result3", future3.get(1, TimeUnit.SECONDS));

        // Verify they executed in order
        assertEquals(List.of("task1", "task2", "task3"), executionOrder);

        // Verify the key is no longer active
        assertEquals(0, serialByKeyExecutor.getActiveKeyCount());
    }

    @Test
    void testConcurrentSubmissionWithDifferentKeys() throws Exception {
        var executionOrder = new CopyOnWriteArrayList<String>();
        var task1Started = new CountDownLatch(1);
        var task2Started = new CountDownLatch(1);

        // Submit tasks with different keys. We use latches to ensure they are running in parallel.
        // Each task signals it has started, then waits for the other to start.
        var future1 = serialByKeyExecutor.submit("key1", () -> {
            executionOrder.add("key1-started");
            task1Started.countDown();
            // Wait for task 2 to start before we proceed. This will timeout if tasks are not parallel.
            if (!task2Started.await(500, TimeUnit.MILLISECONDS)) {
                executionOrder.add("key1-timed-out");
            }
            executionOrder.add("key1-finished");
            return "key1-result";
        });

        var future2 = serialByKeyExecutor.submit("key2", () -> {
            executionOrder.add("key2-started");
            task2Started.countDown();
            // Wait for task 1 to start before we proceed. This will timeout if tasks are not parallel.
            if (!task1Started.await(500, TimeUnit.MILLISECONDS)) {
                executionOrder.add("key2-timed-out");
            }
            executionOrder.add("key2-finished");
            return "key2-result";
        });

        // Wait for completion
        assertEquals("key1-result", future1.get(1, TimeUnit.SECONDS));
        assertEquals("key2-result", future2.get(1, TimeUnit.SECONDS));

        // Because the tasks wait for each other to start, we can be sure they ran in parallel.
        // The first two elements must be the "started" messages, in some order.
        // The last two must be the "finished" messages. No timeouts should have occurred.
        assertEquals(4, executionOrder.size());
        assertTrue(executionOrder.subList(0, 2).containsAll(List.of("key1-started", "key2-started")));
        assertTrue(executionOrder.subList(2, 4).containsAll(List.of("key1-finished", "key2-finished")));

        // All keys should be inactive now
        assertEquals(0, serialByKeyExecutor.getActiveKeyCount());
    }

    @Test
    void testRunnableSubmission() throws Exception {
        var executionOrder = new CopyOnWriteArrayList<String>();
        var key = "runnable-key";
        var cleanupLatch = new CountDownLatch(1);

        var future1 = serialByKeyExecutor.submit(key, () -> {
            executionOrder.add("runnable1");
        });

        var future2 = serialByKeyExecutor.submit(key, () -> {
            executionOrder.add("runnable2");
        });

        // Add a cleanup detection callback to the last future
        future2.whenComplete((res, err) -> {
            // Poll until cleanup is complete, then signal
            executorService.execute(() -> {
                for (int i = 0; i < 100; i++) {
                    if (serialByKeyExecutor.getActiveKeyCount() == 0) {
                        cleanupLatch.countDown();
                        return;
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                cleanupLatch.countDown(); // Signal even if cleanup didn't complete
            });
        });

        // Wait for all tasks to complete
        CompletableFuture.allOf(future1, future2).get(1, TimeUnit.SECONDS);

        // Verify results
        assertNull(future1.getNow(null));
        assertNull(future2.getNow(null));

        // Verify serial execution
        assertEquals(List.of("runnable1", "runnable2"), executionOrder);

        // Wait for cleanup to complete
        assertTrue(cleanupLatch.await(2, TimeUnit.SECONDS), "Cleanup should complete within 2 seconds");
        assertEquals(0, serialByKeyExecutor.getActiveKeyCount());
    }

    @Test
    void testExceptionDoesNotBlockQueue() throws Exception {
        var executionOrder = new CopyOnWriteArrayList<String>();
        var key = "exception-key-callable";
        var testException = new RuntimeException("test exception");

        // Task 1 will throw an exception
        var future1 = serialByKeyExecutor.submit(key, () -> {
            // Add a small delay to make timing issues more obvious if they exist
            Thread.sleep(100);
            throw testException;
        });

        // Task 2 should execute even though task 1 failed
        var future2 = serialByKeyExecutor.submit(key, () -> {
            executionOrder.add("task2");
            return "result2";
        });

        // Verify future1 completed exceptionally
        var ex = assertThrows(java.util.concurrent.ExecutionException.class, () -> future1.get(5, TimeUnit.SECONDS));
        assertInstanceOf(java.lang.RuntimeException.class, ex.getCause());
        assertEquals(testException, ex.getCause());

        // Wait for future2 and verify its result
        assertEquals("result2", future2.get(5, TimeUnit.SECONDS));

        // Verify execution order
        assertEquals(List.of("task2"), executionOrder);

        // Verify key is no longer active
        assertEquals(0, serialByKeyExecutor.getActiveKeyCount());
    }

}
