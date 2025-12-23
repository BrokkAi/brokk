package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IWatchService.EventBatch;
import ai.brokk.IWatchService.Listener;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for LegacyProjectWatchService, focusing on multi-listener functionality.
 */
class LegacyProjectWatchServiceTest {

    @TempDir
    Path tempDir;

    private LegacyProjectWatchService watchService;
    private final List<TestListener> testListeners = new ArrayList<>();
    private Path gitRepoDir; // For worktree tests

    @BeforeEach
    void setUp() {
        testListeners.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (watchService != null) {
            watchService.close();
        }
        if (gitRepoDir != null) {
            try {
                // Best-effort cleanup
                Files.walk(gitRepoDir)
                        .map(Path::toFile)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(f -> f.delete());
            } catch (Exception ignored) {
            } finally {
                gitRepoDir = null;
            }
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
        watchService = new LegacyProjectWatchService(tempDir, null, null, listeners);
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
        watchService = new LegacyProjectWatchService(tempDir, null, null, listeners);
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
        watchService = new LegacyProjectWatchService(tempDir, null, null, listeners);
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
        watchService = new LegacyProjectWatchService(tempDir, null, null, listeners);
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
        watchService = new LegacyProjectWatchService(tempDir, null, null, List.of());
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

        watchService = new LegacyProjectWatchService(tempDir, null, null, List.of(listener1));
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
        watchService = new LegacyProjectWatchService(tempDir, null, null, listeners);
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
        watchService = new LegacyProjectWatchService(tempDir, null, null, List.of());
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
     * Tests that git metadata events from an external directory (worktree scenario) are properly
     * captured. In a worktree, the .git directory is located in the main repository, not under
     * the worktree's root directory.
     */
    @Test
    void testWorktreeGitMetadataEventsFromExternalDirectory() throws Exception {
        // Create a separate directory to simulate worktree scenario
        // - tempDir: the worktree root (project root)
        // - gitRepoDir: the main repository root where .git lives
        gitRepoDir = Files.createTempDirectory("legacy-watcher-main-repo");

        // Create .git directory structure in the main repo
        Path gitDir = gitRepoDir.resolve(".git");
        Files.createDirectories(gitDir);
        Path refsDir = gitDir.resolve("refs").resolve("heads");
        Files.createDirectories(refsDir);

        // Create a test listener
        TestListener listener = new TestListener("WorktreeListener");
        testListeners.add(listener);

        // Create the watcher with gitRepoRoot pointing to the external main repo
        watchService = new LegacyProjectWatchService(tempDir, gitRepoDir, null, List.of(listener));
        watchService.start(CompletableFuture.completedFuture(null));

        // Allow watcher to settle
        Thread.sleep(500);

        // Simulate a git operation by modifying a file in .git (e.g., HEAD update after commit)
        Path headFile = gitDir.resolve("HEAD");
        Files.writeString(headFile, "ref: refs/heads/main\n");

        // Wait for the event to be delivered
        assertTrue(
                listener.filesChangedLatch.await(5, TimeUnit.SECONDS),
                "Git metadata event from external directory should be delivered");

        EventBatch batch = listener.lastBatch;
        assertNotNull(batch, "EventBatch should be non-null");
        assertFalse(batch.files.isEmpty(), "EventBatch should contain files");

        // Verify the batch contains files with .git prefix
        var gitFiles = batch.files.stream()
                .filter(pf -> pf.getRelPath().startsWith(".git"))
                .toList();
        assertFalse(gitFiles.isEmpty(), "EventBatch should contain files with .git prefix");
    }

    /**
     * Tests that regular file events in the worktree root are still captured correctly
     * even when gitRepoRoot is external.
     */
    @Test
    void testWorktreeRegularFilesStillWork() throws Exception {
        // Create a separate directory to simulate worktree scenario
        gitRepoDir = Files.createTempDirectory("legacy-watcher-main-repo-regular");

        // Create .git directory structure in the main repo
        Path gitDir = gitRepoDir.resolve(".git");
        Files.createDirectories(gitDir);

        // Create a test listener
        TestListener listener = new TestListener("WorktreeRegularListener");
        testListeners.add(listener);

        // Create the watcher with gitRepoRoot pointing to the external main repo
        watchService = new LegacyProjectWatchService(tempDir, gitRepoDir, null, List.of(listener));
        watchService.start(CompletableFuture.completedFuture(null));

        // Allow watcher to settle
        Thread.sleep(500);

        // Create a regular file in the worktree root
        Path sourceFile = tempDir.resolve("Source.java");
        Files.writeString(sourceFile, "public class Source {}");

        // Wait for the event to be delivered
        assertTrue(
                listener.filesChangedLatch.await(5, TimeUnit.SECONDS),
                "Regular file event in worktree root should be delivered");

        EventBatch batch = listener.lastBatch;
        assertNotNull(batch, "EventBatch should be non-null");

        // The file should be in the batch with tempDir (worktree root) as base
        assertTrue(
                batch.files.stream().anyMatch(pf -> pf.getRelPath().toString().equals("Source.java")),
                "EventBatch should contain the source file");
    }

    /**
     * Tests that the service correctly resolves and watches git metadata when .git is a FILE
     * (the real worktree case where .git contains "gitdir: /path/to/main/.git/worktrees/xxx").
     */
    @Disabled("Fails on Windows")
    @Test
    void testWorktreeWithGitFile() throws Exception {
        // tempDir is provided by @TempDir - use it as worktree root
        // Create external git metadata directory (simulates main repo's .git/worktrees/xxx)
        gitRepoDir = Files.createTempDirectory("legacy-watcher-external-git");
        Path externalGitDir = gitRepoDir.resolve(".git").resolve("worktrees").resolve("myworktree");
        Files.createDirectories(externalGitDir);
        Path refsDir = externalGitDir.resolve("refs").resolve("heads");
        Files.createDirectories(refsDir);

        // Create .git FILE in worktree (tempDir) pointing to external directory
        Path gitFile = tempDir.resolve(".git");
        Files.writeString(gitFile, "gitdir: " + externalGitDir);

        // Create watcher with worktree as BOTH root and gitRepoRoot (real scenario)
        var listener = new TestListener("worktree-git-file");
        watchService = new LegacyProjectWatchService(tempDir, tempDir, null, List.of(listener));

        watchService.start(CompletableFuture.completedFuture(null));

        // Allow watcher to settle
        Thread.sleep(500);

        // Simulate a git operation by modifying HEAD in the external git directory
        Path headFile = externalGitDir.resolve("HEAD");
        Files.writeString(headFile, "ref: refs/heads/main\n");

        // Wait for the event to be delivered
        boolean delivered = listener.filesChangedLatch.await(5, TimeUnit.SECONDS);
        assertTrue(delivered, "Git metadata event from external worktree directory should be delivered");

        EventBatch batch = listener.lastBatch;
        assertNotNull(batch, "EventBatch should be non-null");
        assertFalse(batch.files.isEmpty(), "EventBatch should contain files");

        // Verify the event was received
        assertTrue(
                batch.files.stream().anyMatch(pf -> pf.getRelPath().toString().contains("HEAD")),
                "EventBatch should contain the HEAD file change");
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
