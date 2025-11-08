package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IWatchService.EventBatch;
import ai.brokk.IWatchService.Listener;
import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for AnalyzerWrapper focusing on the new architecture from issue #1575:
 * - Constructor with injected IWatchService
 * - Self-registration as a listener
 * - getWatchService() accessor
 * - Integration with new listener architecture
 */
class AnalyzerWrapperTest {

    @TempDir
    Path tempDir;

    private AnalyzerWrapper analyzerWrapper;
    private LegacyProjectWatchService watchService;

    @AfterEach
    void tearDown() {
        if (analyzerWrapper != null) {
            analyzerWrapper.close();
        }
        if (watchService != null) {
            watchService.close();
        }
    }

    /**
     * Test that the new 3-parameter constructor correctly accepts an injected IWatchService.
     */
    @Test
    void testConstructorWithInjectedWatchService() throws Exception {
        // Create a simple Java project
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("Test.java"), "public class Test {}");

        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create watch service
        watchService = new LegacyProjectWatchService(projectRoot, null, null, List.of());

        // Create AnalyzerWrapper with injected watch service
        var listener = new TestAnalyzerListener();
        analyzerWrapper = new AnalyzerWrapper(project, listener, watchService);

        // Verify AnalyzerWrapper was created successfully
        assertNotNull(analyzerWrapper, "AnalyzerWrapper should be created");

        // Wait for initial analyzer build
        var analyzer = analyzerWrapper.get();
        assertNotNull(analyzer, "Analyzer should be built");
    }

    /**
     * Test that AnalyzerWrapper registers itself as a listener via addListener().
     */
    @Test
    void testSelfRegistrationAsListener() throws Exception {
        // Create a simple Java project
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("Test.java"), "public class Test {}");

        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create watch service with no initial listeners
        watchService = new LegacyProjectWatchService(projectRoot, null, null, List.of());
        watchService.start(CompletableFuture.completedFuture(null));

        // Give watcher time to initialize
        Thread.sleep(500);

        // Track listener events
        var analyzerListener = new TestAnalyzerListener();

        // Create AnalyzerWrapper - it should register itself
        analyzerWrapper = new AnalyzerWrapper(project, analyzerListener, watchService);

        // Give time for registration and initial build
        Thread.sleep(500);

        // Create a file to trigger an event
        Files.writeString(projectRoot.resolve("NewFile.java"), "public class NewFile {}");

        // Wait for the listener to receive notification
        // Note: AnalyzerWrapper filters for language-relevant files, so .java files should trigger
        Thread.sleep(2000); // Give time for file event and processing

        // The analyzer should have received events (verified implicitly by no exceptions)
        // We can't easily verify the exact call count, but we can verify the analyzer is working
        assertNotNull(analyzerWrapper.getNonBlocking(), "Analyzer should be ready after file change");
    }

    /**
     * Test that getWatchService() returns the injected watch service.
     */
    @Test
    void testGetWatchService() throws Exception {
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create watch service
        watchService = new LegacyProjectWatchService(projectRoot, null, null, List.of());

        // Create AnalyzerWrapper
        analyzerWrapper = new AnalyzerWrapper(project, null, watchService);

        // Verify getWatchService returns the same instance
        var returnedWatchService = analyzerWrapper.getWatchService();
        assertSame(watchService, returnedWatchService, "getWatchService should return the injected watch service");
    }

    /**
     * Test that callers can use getWatchService() to add their own listeners.
     */
    @Test
    void testGetWatchServiceAllowsAddingListeners() throws Exception {
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("Test.java"), "public class Test {}");
        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create watch service
        watchService = new LegacyProjectWatchService(projectRoot, null, null, List.of());

        // Create AnalyzerWrapper
        analyzerWrapper = new AnalyzerWrapper(project, new TestAnalyzerListener(), watchService);

        // Add a custom listener via getWatchService()
        var customListener = new TestFileWatchListener();
        analyzerWrapper.getWatchService().addListener(customListener);

        // Start watching
        watchService.start(CompletableFuture.completedFuture(null));
        Thread.sleep(500);

        // Create a file to trigger an event
        Files.writeString(projectRoot.resolve("NewFile.java"), "public class NewFile {}");

        // Wait for listener to be notified
        assertTrue(
                customListener.filesChangedLatch.await(5, TimeUnit.SECONDS),
                "Custom listener should receive file change event");
        assertTrue(customListener.filesChangedCount.get() > 0, "Custom listener should have received events");
    }

    /**
     * Test that callers can use getWatchService() to pause/resume.
     */
    @Test
    void testGetWatchServiceAllowsPauseResume() throws Exception {
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create watch service
        watchService = new LegacyProjectWatchService(projectRoot, null, null, List.of());

        // Create AnalyzerWrapper
        analyzerWrapper = new AnalyzerWrapper(project, null, watchService);

        // Use getWatchService() to pause
        analyzerWrapper.getWatchService().pause();

        // Verify isPaused returns true
        assertTrue(analyzerWrapper.getWatchService().isPaused(), "Watch service should be paused");

        // Use getWatchService() to resume
        analyzerWrapper.getWatchService().resume();

        // Verify isPaused returns false
        assertFalse(analyzerWrapper.getWatchService().isPaused(), "Watch service should not be paused");
    }

    /**
     * Test AnalyzerWrapper with null IWatchService (headless mode).
     */
    @Test
    void testConstructorWithNullWatchService() throws Exception {
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("Test.java"), "public class Test {}");
        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create AnalyzerWrapper with null watch service (headless mode)
        analyzerWrapper = new AnalyzerWrapper(project, null, (IWatchService) null);

        // Verify AnalyzerWrapper was created successfully
        assertNotNull(analyzerWrapper, "AnalyzerWrapper should be created with null watch service");

        // Verify getWatchService returns a stub (not null)
        var stubWatchService = analyzerWrapper.getWatchService();
        assertNotNull(stubWatchService, "getWatchService should return a stub, not null");

        // Verify stub methods don't throw exceptions
        assertDoesNotThrow(() -> stubWatchService.pause(), "Stub pause should not throw");
        assertDoesNotThrow(() -> stubWatchService.resume(), "Stub resume should not throw");
        assertFalse(stubWatchService.isPaused(), "Stub isPaused should return false");

        // Verify analyzer still builds
        var analyzer = analyzerWrapper.get();
        assertNotNull(analyzer, "Analyzer should build even with null watch service");
    }

    /**
     * Test isPaused() method returns correct state.
     */
    @Test
    void testIsPausedReturnsCorrectState() throws Exception {
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        var project = new TestProject(projectRoot, Languages.JAVA);

        // Create watch service
        watchService = new LegacyProjectWatchService(projectRoot, null, null, List.of());

        // Create AnalyzerWrapper
        analyzerWrapper = new AnalyzerWrapper(project, null, watchService);

        // Initially not paused
        assertFalse(analyzerWrapper.isPause(), "Should not be paused initially");

        // Pause
        analyzerWrapper.pause();
        assertTrue(analyzerWrapper.isPause(), "Should be paused after calling pause()");

        // Resume
        analyzerWrapper.resume();
        assertFalse(analyzerWrapper.isPause(), "Should not be paused after calling resume()");
    }

    /**
     * Test helper class for tracking analyzer lifecycle events.
     */
    private static class TestAnalyzerListener implements AnalyzerListener {
        @Override
        public void onBlocked() {}

        @Override
        public void afterFirstBuild(String msg) {}

        @Override
        public void onTrackedFileChange() {}

        @Override
        public void onRepoChange() {}

        @Override
        public void beforeEachBuild() {}

        @Override
        public void afterEachBuild(boolean externalRequest) {}
    }

    /**
     * Test helper class for tracking file watch events.
     */
    private static class TestFileWatchListener implements Listener {
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
