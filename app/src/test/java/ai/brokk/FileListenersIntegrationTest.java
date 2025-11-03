package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IWatchService.EventBatch;
import ai.brokk.IWatchService.Listener;
import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end integration tests for the new file listeners architecture from issue #1575.
 *
 * Tests the complete flow:
 * 1. Create IWatchService (ProjectWatchService)
 * 2. Inject into AnalyzerWrapper
 * 3. AnalyzerWrapper registers itself as listener
 * 4. External component (simulating ContextManager) adds its own listener
 * 5. Both listeners receive events independently
 */
class FileListenersIntegrationTest {

    @TempDir
    Path tempDir;

    private AnalyzerWrapper analyzerWrapper;
    private ProjectWatchService watchService;

    @AfterEach
    void tearDown() {
        analyzerWrapper.close();
        watchService.close();
    }

    /**
     * End-to-end test of the new architecture:
     * ContextManager creates IWatchService → injects into AnalyzerWrapper → adds own listener → both receive events
     */
    @Test
    void testEndToEndArchitecture() throws Exception {
        // Setup: Create a Java project
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("Test.java"), "public class Test {}");
        var project = new TestProject(projectRoot, Languages.JAVA);

        // Step 1: Create IWatchService (like ContextManager does)
        watchService = new ProjectWatchService(projectRoot, null, null, List.of());

        // Step 2: Create AnalyzerWrapper with injected watch service (like ContextManager does)
        // Note: Pass null for analyzerListener to avoid git repo access in tests
        analyzerWrapper = new AnalyzerWrapper(project, null, watchService);

        // Step 3: Add external listener (like ContextManager does)
        var externalListener = new TestExternalListener();
        watchService.addListener(externalListener);

        // Step 4: Start watching
        watchService.start(java.util.concurrent.CompletableFuture.completedFuture(null));
        Thread.sleep(500); // Give watcher time to initialize

        // Step 5: Trigger a file change
        Files.writeString(projectRoot.resolve("NewFile.java"), "public class NewFile {}");

        // Step 6: Verify both listeners received the event
        assertTrue(
                externalListener.filesChangedLatch.await(5, TimeUnit.SECONDS),
                "External listener should receive file change event");
        assertTrue(
                externalListener.filesChangedCount.get() > 0,
                "External listener should have received at least one event");

        // The AnalyzerWrapper's listener is internal and harder to verify directly,
        // but we can verify the analyzer processed the change
        Thread.sleep(1000); // Give analyzer time to process
        var analyzer = analyzerWrapper.getNonBlocking();
        assertNotNull(analyzer, "Analyzer should be ready after processing file change");
    }

    /**
     * Test that AnalyzerWrapper and external listeners receive events independently.
     */
    @Test
    void testListenersReceiveEventsIndependently() throws Exception {
        // Setup
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("Test.java"), "public class Test {}");
        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create watch service and AnalyzerWrapper
        watchService = new ProjectWatchService(projectRoot, null, null, List.of());
        // Pass null for analyzerListener to avoid git repo access in tests
        analyzerWrapper = new AnalyzerWrapper(project, null, watchService);

        // Add two external listeners
        var listener1 = new TestExternalListener();
        var listener2 = new TestExternalListener();
        watchService.addListener(listener1);
        watchService.addListener(listener2);

        // Start watching
        watchService.start(java.util.concurrent.CompletableFuture.completedFuture(null));
        Thread.sleep(500);

        // Trigger event
        Files.writeString(projectRoot.resolve("NewFile.java"), "public class NewFile {}");

        // Verify both external listeners received the event
        assertTrue(listener1.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener1 should receive event");
        assertTrue(listener2.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener2 should receive event");

        assertTrue(listener1.filesChangedCount.get() > 0, "Listener1 should have events");
        assertTrue(listener2.filesChangedCount.get() > 0, "Listener2 should have events");
    }

    /**
     * Test that removing an external listener doesn't affect AnalyzerWrapper.
     */
    @Test
    void testRemovingExternalListenerDoesNotAffectAnalyzer() throws Exception {
        // Setup
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("Test.java"), "public class Test {}");
        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create watch service and AnalyzerWrapper
        watchService = new ProjectWatchService(projectRoot, null, null, List.of());
        // Pass null for analyzerListener to avoid git repo access in tests
        analyzerWrapper = new AnalyzerWrapper(project, null, watchService);

        // Add and then remove an external listener
        var externalListener = new TestExternalListener();
        watchService.addListener(externalListener);
        watchService.removeListener(externalListener);

        // Start watching
        watchService.start(java.util.concurrent.CompletableFuture.completedFuture(null));
        Thread.sleep(500);

        // Trigger event
        Files.writeString(projectRoot.resolve("NewFile.java"), "public class NewFile {}");

        // External listener should NOT receive event (it was removed)
        assertFalse(
                externalListener.filesChangedLatch.await(2, TimeUnit.SECONDS),
                "Removed listener should not receive events");

        // But AnalyzerWrapper should still work
        Thread.sleep(1000);
        var analyzer = analyzerWrapper.getNonBlocking();
        assertNotNull(analyzer, "Analyzer should still be working after external listener removed");
    }

    /**
     * Test that multiple components can access watch service via getWatchService().
     */
    @Test
    void testMultipleComponentsCanAccessWatchService() throws Exception {
        // Setup
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create watch service and AnalyzerWrapper
        watchService = new ProjectWatchService(projectRoot, null, null, List.of());
        analyzerWrapper = new AnalyzerWrapper(project, null, watchService);

        // Simulate two different components accessing the watch service
        var watchServiceFromComponent1 = analyzerWrapper.getWatchService();
        var watchServiceFromComponent2 = analyzerWrapper.getWatchService();

        // They should be the same instance
        assertSame(
                watchServiceFromComponent1,
                watchServiceFromComponent2,
                "getWatchService should return the same instance");

        // Both can add listeners
        var listener1 = new TestExternalListener();
        var listener2 = new TestExternalListener();
        watchServiceFromComponent1.addListener(listener1);
        watchServiceFromComponent2.addListener(listener2);

        // Start and trigger event
        watchService.start(java.util.concurrent.CompletableFuture.completedFuture(null));
        Thread.sleep(500);
        Files.writeString(projectRoot.resolve("Test.java"), "public class Test {}");

        // Both should receive events
        assertTrue(listener1.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener1 should receive event");
        assertTrue(listener2.filesChangedLatch.await(5, TimeUnit.SECONDS), "Listener2 should receive event");
    }

    /**
     * Test helper for simulating external listener (like ContextManager's fileWatchListener).
     */
    private static class TestExternalListener implements Listener {
        private final AtomicInteger filesChangedCount = new AtomicInteger(0);
        private final CountDownLatch filesChangedLatch = new CountDownLatch(1);

        @Override
        public void onFilesChanged(EventBatch batch) {
            filesChangedCount.incrementAndGet();
            filesChangedLatch.countDown();
        }

        @Override
        public void onNoFilesChangedDuringPollInterval() {}
    }
}
