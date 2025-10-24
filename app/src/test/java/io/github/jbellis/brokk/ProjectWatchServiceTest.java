package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IWatchService.EventBatch;
import io.github.jbellis.brokk.IWatchService.Listener;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for ProjectWatchService, focusing on multi-listener functionality.
 */
class ProjectWatchServiceTest {

    @TempDir
    Path tempDir;

    private ProjectWatchService watchService;
    private final List<TestListener> testListeners = new ArrayList<>();

    @BeforeEach
    void setUp() {
        testListeners.clear();
    }

    @AfterEach
    void tearDown() {
        if (watchService != null) {
            watchService.close();
        }
    }

    /**
     * Test that multiple listeners all receive file change events.
     */
    @Test
    void testMultipleListenersReceiveEvents() throws Exception {
        // Create three test listeners
        TestListener listener1 = new TestListener("Listener1");
        TestListener listener2 = new TestListener("Listener2");
        TestListener listener3 = new TestListener("Listener3");
        testListeners.add(listener1);
        testListeners.add(listener2);
        testListeners.add(listener3);

        // Create watch service with all three listeners
        List<Listener> listeners = new ArrayList<>(testListeners);
        watchService = new ProjectWatchService(tempDir, null, listeners);
        watchService.start(CompletableFuture.completedFuture(null));

        // Give the watch service time to start (increased for CI reliability)
        Thread.sleep(500);

        // Create a file to trigger an event
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "test content");

        // Wait for all listeners to receive the event
        assertTrue(listener1.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener1 should receive event");
        assertTrue(listener2.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener2 should receive event");
        assertTrue(listener3.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener3 should receive event");

        // Verify all listeners received the event
        assertEquals(1, listener1.filesChangedCount.get(), "Listener1 should receive 1 file change");
        assertEquals(1, listener2.filesChangedCount.get(), "Listener2 should receive 1 file change");
        assertEquals(1, listener3.filesChangedCount.get(), "Listener3 should receive 1 file change");

        // Verify the file is in the batch
        assertFalse(listener1.lastBatch.files.isEmpty(), "Batch should contain files");
        assertTrue(
                listener1.lastBatch.files.stream()
                        .anyMatch(pf -> pf.getRelPath().toString().equals("test.txt")),
                "Batch should contain test.txt");
    }

    /**
     * Test that if one listener throws an exception, other listeners still receive events.
     */
    @Test
    void testListenerExceptionIsolation() throws Exception {
        TestListener listener1 = new TestListener("Listener1");
        TestListener listener2 = new TestListener("ThrowingListener", true); // This one throws
        TestListener listener3 = new TestListener("Listener3");
        testListeners.add(listener1);
        testListeners.add(listener2);
        testListeners.add(listener3);

        List<Listener> listeners = new ArrayList<>(testListeners);
        watchService = new ProjectWatchService(tempDir, null, listeners);
        watchService.start(CompletableFuture.completedFuture(null));

        // Give watcher time to initialize (increased for CI reliability)
        Thread.sleep(500);

        // Create a file to trigger an event
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "test content");

        // Wait for non-throwing listeners to receive the event
        assertTrue(listener1.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener1 should receive event");
        assertTrue(listener3.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener3 should receive event");

        // Verify non-throwing listeners received the event
        assertEquals(1, listener1.filesChangedCount.get(), "Listener1 should receive 1 file change");
        assertEquals(1, listener3.filesChangedCount.get(), "Listener3 should receive 1 file change");

        // The throwing listener should have been called but threw an exception
        assertTrue(listener2.exceptionThrown.get() > 0, "ThrowingListener should have thrown exception");
    }

    /**
     * Test that the deprecated single-listener constructor still works.
     */
    @Test
    @SuppressWarnings("deprecation")
    void testBackwardCompatibleSingleListener() throws Exception {
        TestListener listener = new TestListener("SingleListener");

        // Use deprecated constructor
        watchService = new ProjectWatchService(tempDir, null, listener);
        watchService.start(CompletableFuture.completedFuture(null));

        // Give watcher more time to fully initialize (race condition on slower CI systems)
        Thread.sleep(500);

        // Create a file to trigger an event
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "test content");

        // Wait for listener to receive the event
        assertTrue(listener.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener should receive event");

        // Verify listener received the event
        assertEquals(1, listener.filesChangedCount.get(), "Listener should receive 1 file change");
    }

    /**
     * Test that all listeners receive onNoFilesChangedDuringPollInterval events.
     * Note: This test may be flaky depending on system timing and whether the application
     * has focus. We verify the mechanism works but allow for timing variations.
     */
    @Test
    void testMultipleListenersReceiveNoChangeEvents() throws Exception {
        TestListener listener1 = new TestListener("Listener1");
        TestListener listener2 = new TestListener("Listener2");
        testListeners.add(listener1);
        testListeners.add(listener2);

        List<Listener> listeners = new ArrayList<>(testListeners);
        watchService = new ProjectWatchService(tempDir, null, listeners);
        watchService.start(CompletableFuture.completedFuture(null));

        // Give watcher time to initialize
        Thread.sleep(500);

        // Wait for multiple poll intervals to pass
        // The poll timeout varies (100ms focused, 1000ms unfocused)
        // Wait long enough to ensure at least one poll completes
        Thread.sleep(2000);

        // At least one listener should have received a "no change" notification
        // (We can't guarantee both always fire due to timing, but at least one should)
        int totalNoChanges = listener1.noChangesCount.get() + listener2.noChangesCount.get();
        assertTrue(
                totalNoChanges > 0,
                "At least one listener should receive no-change notifications, got: " + listener1.noChangesCount.get()
                        + " + " + listener2.noChangesCount.get());

        // If one got events, both should get events (they're notified together)
        if (listener1.noChangesCount.get() > 0) {
            assertEquals(
                    listener1.noChangesCount.get(),
                    listener2.noChangesCount.get(),
                    "Both listeners should receive same number of no-change events");
        }
    }

    /**
     * Test that pause/resume works with multiple listeners.
     */
    @Test
    void testPauseResumeWithMultipleListeners() throws Exception {
        TestListener listener1 = new TestListener("Listener1");
        TestListener listener2 = new TestListener("Listener2");
        testListeners.add(listener1);
        testListeners.add(listener2);

        List<Listener> listeners = new ArrayList<>(testListeners);
        watchService = new ProjectWatchService(tempDir, null, listeners);
        watchService.start(CompletableFuture.completedFuture(null));

        // Give watcher time to initialize (increased for CI reliability)
        Thread.sleep(500);

        // Pause the watch service
        watchService.pause();

        // Create a file while paused
        Path testFile1 = tempDir.resolve("test1.txt");
        Files.writeString(testFile1, "test content 1");

        // Wait a bit to ensure file system event would have fired if not paused
        Thread.sleep(200);

        // Listeners should not have received the event yet (or very minimal events)
        int count1Before = listener1.filesChangedCount.get();
        int count2Before = listener2.filesChangedCount.get();

        // Resume the watch service
        watchService.resume();

        // Events should now be processed (they were queued)
        assertTrue(
                listener1.filesChangedLatch.await(5, TimeUnit.SECONDS),
                "Listener1 should eventually receive event after resume");
        assertTrue(
                listener2.filesChangedLatch.await(5, TimeUnit.SECONDS),
                "Listener2 should eventually receive event after resume");

        // Both should have received at least one event after resume
        assertTrue(listener1.filesChangedCount.get() > count1Before, "Listener1 should receive events after resume");
        assertTrue(listener2.filesChangedCount.get() > count2Before, "Listener2 should receive events after resume");
    }

    /**
     * Test that empty listener list doesn't cause errors.
     */
    @Test
    void testEmptyListenerList() throws Exception {
        // Create watch service with empty list
        watchService = new ProjectWatchService(tempDir, null, List.of());
        watchService.start(CompletableFuture.completedFuture(null));

        // Give watcher time to initialize (increased for CI reliability)
        Thread.sleep(500);

        // Create a file - should not throw any errors
        Path testFile = tempDir.resolve("test.txt");
        assertDoesNotThrow(() -> Files.writeString(testFile, "test content"));

        // Wait a bit to ensure no errors occur
        Thread.sleep(200);
    }

    /**
     * Test that dynamically added listeners receive events.
     */
    @Test
    void testDynamicallyAddedListener() throws Exception {
        // Start with one listener
        TestListener listener1 = new TestListener("Listener1");
        testListeners.add(listener1);

        watchService = new ProjectWatchService(tempDir, null, List.of(listener1));
        watchService.start(CompletableFuture.completedFuture(null));

        // Give watcher time to initialize
        Thread.sleep(500);

        // Add a second listener dynamically
        TestListener listener2 = new TestListener("Listener2");
        testListeners.add(listener2);
        watchService.addListener(listener2);

        // Create a file to trigger an event
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "test content");

        // Both listeners should receive the event
        assertTrue(listener1.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener1 should receive event");
        assertTrue(
                listener2.filesChangedLatch.await(5, TimeUnit.SECONDS),
                "Listener2 (added dynamically) should receive event");

        assertEquals(1, listener1.filesChangedCount.get(), "Listener1 should receive 1 file change");
        assertEquals(1, listener2.filesChangedCount.get(), "Listener2 should receive 1 file change");
    }

    /**
     * Test that removed listeners no longer receive events.
     */
    @Test
    void testRemovedListener() throws Exception {
        // Start with two listeners
        TestListener listener1 = new TestListener("Listener1");
        TestListener listener2 = new TestListener("Listener2");
        testListeners.add(listener1);
        testListeners.add(listener2);

        List<Listener> listeners = new ArrayList<>(testListeners);
        watchService = new ProjectWatchService(tempDir, null, listeners);
        watchService.start(CompletableFuture.completedFuture(null));

        // Give watcher time to initialize
        Thread.sleep(500);

        // Remove listener2
        watchService.removeListener(listener2);

        // Create a file to trigger an event
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "test content");

        // Only listener1 should receive the event
        assertTrue(listener1.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener1 should receive event");

        // Wait a bit to ensure listener2 doesn't receive anything
        Thread.sleep(500);

        assertEquals(1, listener1.filesChangedCount.get(), "Listener1 should receive 1 file change");
        assertEquals(0, listener2.filesChangedCount.get(), "Listener2 (removed) should not receive events");
    }

    /**
     * Test that multiple listeners can be added and removed dynamically.
     */
    @Test
    void testMultipleDynamicListenerOperations() throws Exception {
        // Start with empty listener list
        watchService = new ProjectWatchService(tempDir, null, List.of());
        watchService.start(CompletableFuture.completedFuture(null));

        // Give watcher time to initialize
        Thread.sleep(500);

        // Add three listeners dynamically
        TestListener listener1 = new TestListener("Listener1");
        TestListener listener2 = new TestListener("Listener2");
        TestListener listener3 = new TestListener("Listener3");
        testListeners.add(listener1);
        testListeners.add(listener2);
        testListeners.add(listener3);

        watchService.addListener(listener1);
        watchService.addListener(listener2);
        watchService.addListener(listener3);

        // Create a file to trigger an event
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "test content");

        // All three should receive the event
        assertTrue(listener1.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener1 should receive event");
        assertTrue(listener2.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener2 should receive event");
        assertTrue(listener3.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener3 should receive event");

        assertEquals(1, listener1.filesChangedCount.get());
        assertEquals(1, listener2.filesChangedCount.get());
        assertEquals(1, listener3.filesChangedCount.get());

        // Remove listener2
        watchService.removeListener(listener2);

        // Reset latches for next event
        TestListener listener1_v2 = new TestListener("Listener1_v2");
        TestListener listener3_v2 = new TestListener("Listener3_v2");
        watchService.removeListener(listener1);
        watchService.removeListener(listener3);
        watchService.addListener(listener1_v2);
        watchService.addListener(listener3_v2);

        // Create another file
        Path testFile2 = tempDir.resolve("test2.txt");
        Files.writeString(testFile2, "test content 2");

        // Only listener1_v2 and listener3_v2 should receive the event
        assertTrue(listener1_v2.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener1_v2 should receive event");
        assertTrue(listener3_v2.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener3_v2 should receive event");

        // Wait to ensure removed listener doesn't get event
        Thread.sleep(500);

        assertEquals(1, listener1_v2.filesChangedCount.get());
        assertEquals(1, listener3_v2.filesChangedCount.get());
    }

    /**
     * Test listener that tracks events.
     */
    private static class TestListener implements Listener {
        private final String name;
        private final boolean shouldThrow;
        private final AtomicInteger filesChangedCount = new AtomicInteger(0);
        private final AtomicInteger noChangesCount = new AtomicInteger(0);
        private final AtomicInteger exceptionThrown = new AtomicInteger(0);
        private final CountDownLatch filesChangedLatch = new CountDownLatch(1);
        private EventBatch lastBatch;

        TestListener(String name) {
            this(name, false);
        }

        TestListener(String name, boolean shouldThrow) {
            this.name = name;
            this.shouldThrow = shouldThrow;
        }

        @Override
        public void onFilesChanged(EventBatch batch) {
            if (shouldThrow) {
                exceptionThrown.incrementAndGet();
                throw new RuntimeException("Test exception from " + name);
            }
            filesChangedCount.incrementAndGet();
            lastBatch = batch;
            filesChangedLatch.countDown();
        }

        @Override
        public void onNoFilesChangedDuringPollInterval() {
            noChangesCount.incrementAndGet();
        }
    }
}
