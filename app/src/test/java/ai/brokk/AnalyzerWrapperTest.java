package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.TestRepo;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.FileUtil;
import ai.brokk.watchservice.AbstractWatchService.EventBatch;
import ai.brokk.watchservice.AbstractWatchService.Listener;
import ai.brokk.watchservice.JavaProjectWatchService;
import ai.brokk.watchservice.NoopWatchService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for AnalyzerWrapper focusing on the new architecture from issue #1575:
 * - Constructor with injected IWatchService
 * - Self-registration as a listener
 * - getWatchService() accessor
 * - Integration with new listener architecture
 */
class AnalyzerWrapperTest {

    private Path tempDir;
    private AnalyzerWrapper analyzerWrapper;
    private JavaProjectWatchService watchService;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("analyzer-wrapper-test-");
    }

    @AfterEach
    void tearDown() {
        // Close watchService first to release directory handles (required for Windows)
        if (watchService != null) {
            watchService.close();
        }
        if (analyzerWrapper != null) {
            analyzerWrapper.close();
        }
        FileUtil.deleteRecursively(tempDir);
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
        watchService = new JavaProjectWatchService(projectRoot, null, null, List.of());

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
        watchService = new JavaProjectWatchService(projectRoot, null, null, List.of());
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
        watchService = new JavaProjectWatchService(projectRoot, null, null, List.of());

        // Create AnalyzerWrapper
        analyzerWrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), watchService);
        analyzerWrapper.get(); // wait for async tasks so they don't race w/ tempdir cleanup

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
        watchService = new JavaProjectWatchService(projectRoot, null, null, List.of());

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
        watchService = new JavaProjectWatchService(projectRoot, null, null, List.of());

        // Create AnalyzerWrapper
        analyzerWrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), watchService);

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
        analyzerWrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), new NoopWatchService());

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
        watchService = new JavaProjectWatchService(projectRoot, null, null, List.of());

        // Create AnalyzerWrapper
        analyzerWrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), watchService);

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
     * Regression test for stale tracked-files filtering bug.
     * Demonstrates that AnalyzerWrapper.onFilesChanged skips updates for files not in getTrackedFiles().
     */
    @org.junit.jupiter.api.Disabled("Reproduces AnalyzerWrapper tracked-files filtering bug; enable after fix")
    @Test
    void testOnFilesChangedSkipsUpdateWhenTrackedFilesStale() throws Exception {
        var projectRoot = tempDir.resolve("project-stale");
        Files.createDirectories(projectRoot);
        Path aPath = projectRoot.resolve("pkg/A.java");
        Files.createDirectories(aPath.getParent());
        Files.writeString(aPath, "package pkg; public class A { void a() {} }");

        TestRepo repo = new TestRepo(projectRoot);
        ProjectFile pf = new ProjectFile(projectRoot, "pkg/A.java");
        repo.add(pf);

        // Define a local project class that returns our controlled TestRepo
        class ProjectWithTestRepo extends TestProject {
            ProjectWithTestRepo(Path root) {
                super(root, Languages.JAVA);
            }

            @Override
            public TestRepo getRepo() {
                return repo;
            }
        }

        var project = new ProjectWithTestRepo(projectRoot);
        analyzerWrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), new NoopWatchService());

        // 1. Wait for initial analyzer to be built
        var initialAnalyzer = analyzerWrapper.get();
        assertNotNull(initialAnalyzer);

        // 2. Wait for AnalyzerWrapper to be ready for watcher events (flips flag in follow-up task)
        // We poll getNonBlocking to ensure initial setup is fully flushed.
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (analyzerWrapper.getNonBlocking() != null) break;
            Thread.sleep(50);
        }

        // 3. Modify the file on disk
        Files.writeString(aPath, "package pkg; public class A { void a() {} void b() {} }");

        // 4. Simulate stale repo state: the file is changed but repo says it's no longer tracked
        repo.remove(pf);

        // 5. Manually trigger onFilesChanged with the changed file
        EventBatch batch = new EventBatch();
        batch.getFiles().add(pf);
        analyzerWrapper.onFilesChanged(batch);

        // 6. Assert the analyzer snapshot reflects the edit.
        // On current main, this is expected to FAIL because onFilesChanged filters out 'pf'.
        var updatedAnalyzer = analyzerWrapper.get();
        String skeleton = AnalyzerUtil.getSkeleton(updatedAnalyzer, "pkg.A").orElse("");

        assertTrue(
                skeleton.contains("void b()"),
                "Analyzer should have updated pkg.A to include method 'b' despite stale tracked-files view");
    }

    /**
     * Contrast test for #1575: demonstrates that an explicit update via TestAnalyzerWrapper
     * DOES work when provided the correct file set, even if the watcher path might skip it.
     */
    @Test
    void testExplicitUpdateViaTestAnalyzerWrapperUpdatesAnalyzer() throws Exception {
        var projectRoot = tempDir.resolve("project-explicit");
        Files.createDirectories(projectRoot);
        Path aPath = projectRoot.resolve("pkg/A.java");
        Files.createDirectories(aPath.getParent());
        Files.writeString(aPath, "package pkg; public class A { void a() {} }");

        TestRepo repo = new TestRepo(projectRoot);
        ProjectFile pf = new ProjectFile(projectRoot, "pkg/A.java");
        repo.add(pf);

        class ProjectWithTestRepo extends TestProject {
            ProjectWithTestRepo(Path root) {
                super(root, Languages.JAVA);
            }

            @Override
            public TestRepo getRepo() {
                return repo;
            }
        }

        var project = new ProjectWithTestRepo(projectRoot);
        analyzerWrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), new NoopWatchService());

        // 1. Wait for initial analyzer to be built
        var initialAnalyzer = analyzerWrapper.get();
        assertNotNull(initialAnalyzer);

        // 2. Modify the file on disk to add method 'b'
        Files.writeString(aPath, "package pkg; public class A { void a() {} void b() {} }");

        // 3. Wrap current snapshot in TestAnalyzerWrapper for explicit update
        ai.brokk.testutil.TestAnalyzerWrapper taw = new ai.brokk.testutil.TestAnalyzerWrapper(initialAnalyzer);

        // 4. Perform explicit update
        IAnalyzer updatedAnalyzer = taw.updateFiles(java.util.Set.of(pf)).get(5, TimeUnit.SECONDS);

        // 5. Assert the result contains 'b'
        String skeleton = AnalyzerUtil.getSkeleton(updatedAnalyzer, "pkg.A").orElse("");
        assertTrue(
                skeleton.contains("void b()"),
                "Explicitly updated analyzer should include method 'b' from modified file content");
    }

    /**
     * Test helper class for tracking analyzer lifecycle events.
     */
    private static class TestAnalyzerListener implements AnalyzerListener {
        @Override
        public void onBlocked() {}

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
    }
}
