package ai.brokk;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Factory for creating IWatchService implementations.
 * Selects the appropriate implementation based on platform and configuration.
 *
 * The factory prefers the native implementation (using platform-specific file
 * watching APIs) but can fall back to the legacy Java WatchService implementation.
 *
 * Users can override the selection using the system property:
 * -Dbrokk.watchservice.impl=native  (force native)
 * -Dbrokk.watchservice.impl=legacy  (force legacy)
 */
public class WatchServiceFactory {
    private static final Logger logger = LogManager.getLogger(WatchServiceFactory.class);

    /**
     * System property to force a specific implementation.
     * Values: "native" or "legacy"
     */
    private static final String WATCH_SERVICE_IMPL_PROPERTY = "brokk.watchservice.impl";

    /**
     * Create a watch service using the best available implementation for the platform.
     *
     * Selection logic:
     * 1. Check system property override (brokk.watchservice.impl)
     * 2. Platform detection (macOS strongly prefers native for FSEvents)
     * 3. Default to native with automatic fallback to legacy on errors
     *
     * @param root The root directory to watch
     * @param gitRepoRoot The git repository root (may be null)
     * @param globalGitignorePath The global gitignore file path (may be null)
     * @param listeners Listeners to notify of file changes
     * @return An appropriate IWatchService implementation
     */
    public static IWatchService create(
            Path root,
            @Nullable Path gitRepoRoot,
            @Nullable Path globalGitignorePath,
            List<IWatchService.Listener> listeners) {
        String implProp = getImplementationPreference();
        String os = getOsName();
        return createInternal(root, gitRepoRoot, globalGitignorePath, listeners, implProp, os);
    }

    /**
     * Get the implementation preference from system property or environment variable.
     * Package-private for testing.
     */
    static String getImplementationPreference() {
        String implProp = System.getProperty(WATCH_SERVICE_IMPL_PROPERTY);
        if (implProp == null) {
            implProp = System.getenv("BROKK_WATCHSERVICE_IMPL");
        }
        if (implProp == null) {
            // Default to legacy if no preference is set for now.  Will change later once native is more stable.
            implProp = "legacy";
        }
        return implProp;
    }

    /**
     * Get the OS name for platform detection.
     * Package-private for testing.
     */
    static String getOsName() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT);
    }

    /**
     * Internal creation method that can be tested without modifying global state.
     * Package-private for testing.
     */
    static IWatchService createInternal(
            Path root,
            @Nullable Path gitRepoRoot,
            @Nullable Path globalGitignorePath,
            List<IWatchService.Listener> listeners,
            String implProp,
            String os) {

        if ("legacy".equalsIgnoreCase(implProp)) {
            logger.info("Using legacy watch service (forced by configuration)");
            return new LegacyProjectWatchService(root, gitRepoRoot, globalGitignorePath, listeners);
        }
        if ("native".equalsIgnoreCase(implProp)) {
            logger.info("Using native watch service (forced by configuration)");
            return createNativeWithFallback(root, gitRepoRoot, globalGitignorePath, listeners);
        }

        // Platform-based selection
        if (os.contains("mac")) {
            // macOS benefits most from native FSEvents implementation
            logger.info("Detected macOS, using native watch service (FSEvents)");
            return createNativeWithFallback(root, gitRepoRoot, globalGitignorePath, listeners);
        } else if (os.contains("linux")) {
            // Linux: native implementation provides optimizations
            logger.info("Detected Linux, using native watch service (inotify optimized)");
            return createNativeWithFallback(root, gitRepoRoot, globalGitignorePath, listeners);
        } else if (os.contains("win")) {
            // Windows: both implementations work well, prefer native for consistency
            logger.info("Detected Windows, using native watch service");
            return createNativeWithFallback(root, gitRepoRoot, globalGitignorePath, listeners);
        }

        // Default to native with fallback
        logger.info("Using native watch service (default)");
        return createNativeWithFallback(root, gitRepoRoot, globalGitignorePath, listeners);
    }

    /**
     * Try to create native implementation, fall back to legacy on error.
     */
    private static IWatchService createNativeWithFallback(
            Path root,
            @Nullable Path gitRepoRoot,
            @Nullable Path globalGitignorePath,
            List<IWatchService.Listener> listeners) {
        try {
            return new NativeProjectWatchService(root, gitRepoRoot, globalGitignorePath, listeners);
        } catch (Exception e) {
            logger.error("Failed to create native watch service, falling back to legacy", e);
            return new LegacyProjectWatchService(root, gitRepoRoot, globalGitignorePath, listeners);
        }
    }
}
