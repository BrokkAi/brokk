package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Manual integration tests for NativeProjectWatchService.
 *
 * These tests are disabled by default because they:
 * - Depend on timing and sleep calls
 * - Are platform-specific and may behave differently on different OSes
 * - Use real file system operations which are non-deterministic
 *
 * To run these tests manually for validation:
 * 1. Remove @Disabled annotation from the test method
 * 2. Run the specific test in your IDE or via gradle
 * 3. Re-enable @Disabled before committing
 *
 * These tests serve as:
 * - Documentation of expected behavior
 * - Manual validation tool during development
 * - Integration test for platform-specific functionality
 */
@Disabled("Manual test - requires timing/sleep and is non-deterministic")
class NativeProjectWatchServiceManualTest {

    @TempDir
    Path tempDir;

    @Test
    void manualTest_BasicFileChangeDetection() throws Exception {
        var listener = new TestListener();
        var service = new NativeProjectWatchService(tempDir, null, null, List.of(listener));

        try {
            service.start(CompletableFuture.completedFuture(null));

            // Give watcher time to start
            Thread.sleep(500);

            // Create a file
            var testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "initial content");

            // Wait for event
            boolean gotEvent = listener.awaitEvent(5, TimeUnit.SECONDS);
            assertTrue(gotEvent, "Should detect file creation");
            assertTrue(listener.hasFileEvent("test.txt"), "Should include created file");

            // Modify the file
            listener.reset();
            Files.writeString(testFile, "modified content");

            gotEvent = listener.awaitEvent(5, TimeUnit.SECONDS);
            assertTrue(gotEvent, "Should detect file modification");

            // Delete the file
            listener.reset();
            Files.delete(testFile);

            gotEvent = listener.awaitEvent(5, TimeUnit.SECONDS);
            assertTrue(gotEvent, "Should detect file deletion");

        } finally {
            service.close();
        }
    }

    @Test
    void manualTest_MultipleListeners() throws Exception {
        var listener1 = new TestListener();
        var listener2 = new TestListener();
        var service = new NativeProjectWatchService(tempDir, null, null, List.of(listener1, listener2));

        try {
            service.start(CompletableFuture.completedFuture(null));
            Thread.sleep(500);

            var testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "content");

            boolean got1 = listener1.awaitEvent(5, TimeUnit.SECONDS);
            boolean got2 = listener2.awaitEvent(5, TimeUnit.SECONDS);

            assertTrue(got1, "Listener 1 should receive event");
            assertTrue(got2, "Listener 2 should receive event");

        } finally {
            service.close();
        }
    }

    @Test
    void manualTest_PauseResume() throws Exception {
        var listener = new TestListener();
        var service = new NativeProjectWatchService(tempDir, null, null, List.of(listener));

        try {
            service.start(CompletableFuture.completedFuture(null));
            Thread.sleep(500);

            // Pause the service
            service.pause();

            // Create file while paused
            var testFile = tempDir.resolve("paused.txt");
            Files.writeString(testFile, "created while paused");

            // Should NOT get event while paused
            boolean gotEvent = listener.awaitEvent(2, TimeUnit.SECONDS);
            assertFalse(gotEvent, "Should not receive events while paused");

            // Resume
            service.resume();
            listener.reset();

            // Modify file after resume
            Files.writeString(testFile, "modified after resume");

            // Should get event after resume
            gotEvent = listener.awaitEvent(5, TimeUnit.SECONDS);
            assertTrue(gotEvent, "Should receive events after resume");

        } finally {
            service.close();
        }
    }

    @Test
    void manualTest_ExcludedDirectories() throws Exception {
        var listener = new TestListener();
        var service = new NativeProjectWatchService(tempDir, null, null, List.of(listener));

        try {
            service.start(CompletableFuture.completedFuture(null));
            Thread.sleep(500);

            // Create file in excluded directory (e.g., node_modules)
            var nodeModules = tempDir.resolve("node_modules");
            Files.createDirectories(nodeModules);
            Thread.sleep(500);
            listener.reset();

            var excludedFile = nodeModules.resolve("package.json");
            Files.writeString(excludedFile, "{}");

            // Should NOT get event for excluded directory
            boolean gotEvent = listener.awaitEvent(2, TimeUnit.SECONDS);
            assertFalse(gotEvent, "Should not watch excluded directories like node_modules");

            // Create file in non-excluded directory
            listener.reset();
            var includedFile = tempDir.resolve("included.txt");
            Files.writeString(includedFile, "content");

            // Should get event for included file
            gotEvent = listener.awaitEvent(5, TimeUnit.SECONDS);
            assertTrue(gotEvent, "Should watch non-excluded directories");

        } finally {
            service.close();
        }
    }

    @Test
    void manualTest_GitignoreDetection() throws Exception {
        // Create git repo structure
        var gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);

        var listener = new TestListener();
        var service = new NativeProjectWatchService(tempDir, tempDir, null, List.of(listener));

        try {
            service.start(CompletableFuture.completedFuture(null));
            Thread.sleep(500);

            // Modify .gitignore
            var gitignore = tempDir.resolve(".gitignore");
            Files.writeString(gitignore, "*.log\n");

            boolean gotEvent = listener.awaitEvent(5, TimeUnit.SECONDS);
            assertTrue(gotEvent, "Should detect .gitignore changes");

        } finally {
            service.close();
        }
    }

    /**
     * Test listener that tracks received events
     */
    private static class TestListener implements IWatchService.Listener {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final List<IWatchService.EventBatch> events = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch currentLatch = new CountDownLatch(1);

        @Override
        public void onFilesChanged(IWatchService.EventBatch batch) {
            events.add(batch);
            currentLatch.countDown();
        }

        @Override
        public void onNoFilesChangedDuringPollInterval() {
            // No-op
        }

        boolean awaitEvent(long timeout, TimeUnit unit) throws InterruptedException {
            return currentLatch.await(timeout, unit);
        }

        void reset() {
            events.clear();
            currentLatch = new CountDownLatch(1);
        }

        boolean hasFileEvent(String filename) {
            return events.stream()
                    .flatMap(batch -> batch.files.stream())
                    .anyMatch(file -> file.getRelPath().getFileName().toString().equals(filename));
        }
    }
}
