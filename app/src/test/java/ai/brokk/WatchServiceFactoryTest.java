package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchServiceFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void testConfigurationForcesLegacy() throws Exception {
        var service = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "legacy", "linux");

        assertNotNull(service);
        assertTrue(
                service instanceof LegacyProjectWatchService,
                "Should create LegacyProjectWatchService when configured as 'legacy'");
    }

    @Test
    void testConfigurationForcesNative() throws Exception {
        var service = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "native", "linux");

        assertNotNull(service);
        assertTrue(
                service instanceof NativeProjectWatchService,
                "Should create NativeProjectWatchService when configured as 'native'");
    }

    @Test
    void testConfigurationCaseInsensitive() throws Exception {
        var legacyService = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "LEGACY", "linux");
        assertTrue(
                legacyService instanceof LegacyProjectWatchService,
                "Should handle case-insensitive configuration values");

        var nativeService = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "NATIVE", "linux");
        assertTrue(
                nativeService instanceof NativeProjectWatchService,
                "Should handle case-insensitive configuration values");
    }

    @Test
    void testPlatformDetectionMacOS() throws Exception {
        var service = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "unknown", "mac os x");

        assertNotNull(service);
        assertTrue(service instanceof NativeProjectWatchService, "Should create NativeProjectWatchService for macOS");
    }

    @Test
    void testPlatformDetectionLinux() throws Exception {
        var service = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "unknown", "linux");

        assertNotNull(service);
        assertTrue(service instanceof NativeProjectWatchService, "Should create NativeProjectWatchService for Linux");
    }

    @Test
    void testPlatformDetectionWindows() throws Exception {
        var service = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "unknown", "windows 10");

        assertNotNull(service);
        assertTrue(service instanceof NativeProjectWatchService, "Should create NativeProjectWatchService for Windows");
    }

    @Test
    void testDefaultBehaviorUnknownOS() throws Exception {
        // When OS is unknown and no configuration is set, should default to native
        var service = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "unknown", "unknown-os");

        assertNotNull(service);
        assertTrue(
                service instanceof NativeProjectWatchService,
                "Should default to NativeProjectWatchService for unknown OS");
    }

    @Test
    void testDefaultBehaviorNoConfiguration() throws Exception {
        // When configuration defaults to "legacy" (as per current implementation)
        var service = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "legacy", "linux");

        assertNotNull(service);
        assertTrue(
                service instanceof LegacyProjectWatchService,
                "Should use legacy when default configuration is 'legacy'");
    }

    @Test
    void testWithGitRepoAndGlobalGitignore() throws Exception {
        // Create a git repo structure
        var gitRepo = tempDir.resolve("repo");
        var gitDir = gitRepo.resolve(".git");
        Files.createDirectories(gitDir);

        var globalGitignore = tempDir.resolve(".gitignore_global");
        Files.writeString(globalGitignore, "*.log\n");

        var service =
                WatchServiceFactory.createInternal(gitRepo, gitRepo, globalGitignore, List.of(), "legacy", "linux");

        assertNotNull(service);
        assertTrue(service instanceof LegacyProjectWatchService);
    }

    @Test
    void testMultipleListeners() throws Exception {
        var listener1 = new TestListener();
        var listener2 = new TestListener();

        var service = WatchServiceFactory.createInternal(
                tempDir, null, null, List.of(listener1, listener2), "native", "linux");

        assertNotNull(service);
        assertTrue(service instanceof NativeProjectWatchService);
    }

    @Test
    void testNullGitRepoRoot() throws Exception {
        var service = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "legacy", "linux");

        assertNotNull(service);
        assertTrue(service instanceof LegacyProjectWatchService);
    }

    @Test
    void testNullGlobalGitignore() throws Exception {
        var gitRepo = tempDir.resolve("repo");
        Files.createDirectories(gitRepo.resolve(".git"));

        var service = WatchServiceFactory.createInternal(gitRepo, gitRepo, null, List.of(), "native", "linux");

        assertNotNull(service);
        assertTrue(service instanceof NativeProjectWatchService);
    }

    @Test
    void testConfigurationOverridesPlatform() throws Exception {
        // Even on macOS (which prefers native), "legacy" configuration should override
        var service = WatchServiceFactory.createInternal(tempDir, null, null, List.of(), "legacy", "mac os x");

        assertNotNull(service);
        assertTrue(
                service instanceof LegacyProjectWatchService, "Configuration should override platform-based selection");
    }

    /**
     * Test listener implementation for testing purposes
     */
    private static class TestListener implements IWatchService.Listener {
        @Override
        public void onFilesChanged(IWatchService.EventBatch batch) {
            // No-op for testing
        }

        @Override
        public void onNoFilesChangedDuringPollInterval() {
            // No-op for testing
        }
    }
}
