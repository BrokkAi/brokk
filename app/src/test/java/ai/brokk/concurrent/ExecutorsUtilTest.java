package ai.brokk.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExecutorsUtilTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void newVirtualThreadExecutor_whenInterruptedDuringAcquire_throwsCancellationException()
            throws InterruptedException {
        // Create an executor with 1 permit and exhaust it
        int maxThreads = 1;
        var executor = ExecutorsUtil.newVirtualThreadExecutor("test-vt-", maxThreads);

        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch finishFirstTask = new CountDownLatch(1);

        executor.execute(() -> {
            firstTaskStarted.countDown();
            try {
                finishFirstTask.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(firstTaskStarted.await(2, TimeUnit.SECONDS), "First task should have started");

        // Now attempt to submit a second task from a thread we can interrupt.
        // The factory should block on permits.acquire().
        AtomicReference<Throwable> caughtException = new AtomicReference<>();
        CountDownLatch secondTaskAttempted = new CountDownLatch(1);

        Thread waiterThread = new Thread(() -> {
            try {
                secondTaskAttempted.countDown();
                executor.execute(() -> {});
            } catch (Throwable t) {
                caughtException.set(t);
            }
        });

        waiterThread.start();
        assertTrue(secondTaskAttempted.await(2, TimeUnit.SECONDS));

        // Give it a moment to be stuck in acquire()
        Thread.sleep(100);

        // Interrupt the thread attempting to create the new virtual thread
        waiterThread.interrupt();
        waiterThread.join(2000);

        Throwable result = caughtException.get();
        assertNotNull(result, "Should have caught an exception");
        assertTrue(
                result instanceof CancellationException,
                "Expected CancellationException but got: " + result.getClass().getName());
        assertEquals("Interrupted while acquiring virtual-thread permit", result.getMessage());

        // Cleanup
        finishFirstTask.countDown();
        executor.shutdown();
    }
}
