package ai.brokk.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Tracks active git clone operations and provides cleanup mechanisms for partial clones. Uses JVM shutdown hooks
 * (following the pattern in LspServer) and marker files for robustness.
 */
public class CloneOperationTracker {
    private static final Logger logger = LogManager.getLogger(CloneOperationTracker.class);
    private static final Set<Path> activeClones = Collections.synchronizedSet(new HashSet<>());

    @Nullable
    private static Thread cloneCleanupHook;

    // Marker file constants
    public static final String CLONE_IN_PROGRESS_MARKER = ".brokk-clone-in-progress";
    public static final String CLONE_COMPLETE_MARKER = ".brokk-clone-complete";

    /** Register a clone operation. Creates shutdown hook if this is the first active clone. */
    public static synchronized void registerCloneOperation(Path targetPath) {
        activeClones.add(targetPath);
        logger.debug("Registered clone operation: {} (total active: {})", targetPath, activeClones.size());

        // Create shutdown hook if this is the first active clone
        if (cloneCleanupHook == null) {
            cloneCleanupHook = new Thread(
                    () -> {
                        logger.warn(
                                "JVM shutting down with {} active clone operations, cleaning up", activeClones.size());
                        cleanupActiveClones();
                    },
                    "clone-cleanup-hook");
            Runtime.getRuntime().addShutdownHook(cloneCleanupHook);
            logger.debug("Created clone cleanup shutdown hook");
        }
    }

    /** Unregister a clone operation. Removes shutdown hook if no more active clones. */
    public static synchronized void unregisterCloneOperation(Path targetPath) {
        activeClones.remove(targetPath);
        logger.debug("Unregistered clone operation: {} (total active: {})", targetPath, activeClones.size());

        // Remove shutdown hook if no more active clones
        if (activeClones.isEmpty() && cloneCleanupHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(cloneCleanupHook);
                cloneCleanupHook = null;
                logger.debug("Removed clone cleanup shutdown hook");
            } catch (IllegalStateException e) {
                // Hook already running or JVM shutting down - ignore
                logger.debug("Could not remove shutdown hook (JVM likely shutting down)");
            }
        }
    }

    /** Cleanup active clones during JVM shutdown. */
    private static void cleanupActiveClones() {
        for (Path targetPath : activeClones) {
            try {
                if (Files.exists(targetPath)) {
                    logger.info("Cleaning up partial clone on shutdown: {}", targetPath);
                    FileUtil.deleteRecursively(targetPath);
                }
            } catch (Exception e) {
                logger.error("Failed to cleanup clone during shutdown: {}", targetPath, e);
            }
        }
    }

    /** Clean up orphaned clone operations from previous application runs. Call this during application startup. */
    public static void cleanupOrphanedClones(Path dependenciesRoot) {
        if (!Files.exists(dependenciesRoot)) {
            return;
        }

        logger.debug("Scanning for orphaned clone operations in: {}", dependenciesRoot);

        try (var stream = Files.list(dependenciesRoot)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Path inProgressMarker = dir.resolve(CLONE_IN_PROGRESS_MARKER);
                Path completeMarker = dir.resolve(CLONE_COMPLETE_MARKER);

                if (Files.exists(inProgressMarker) && !Files.exists(completeMarker)) {
                    try {
                        logger.info("Found orphaned clone operation from previous session: {}", dir);
                        FileUtil.deleteRecursively(dir);
                    } catch (Exception e) {
                        logger.warn("Failed to cleanup orphaned clone operation: {}", dir, e);
                    }
                }
            });
        } catch (IOException e) {
            logger.warn("Error scanning for orphaned clone operations in {}", dependenciesRoot, e);
        }
    }

    /** Create marker file indicating clone operation is in progress. */
    public static void createInProgressMarker(Path targetPath, String repoUrl, String branch) throws IOException {
        Path marker = targetPath.resolve(CLONE_IN_PROGRESS_MARKER);
        String content = String.format("%s%n%d%n%s", repoUrl, System.currentTimeMillis(), branch);
        Files.writeString(marker, content);
    }

    /** Create marker file indicating clone operation completed successfully. */
    public static void createCompleteMarker(Path targetPath, String repoUrl, String branch) throws IOException {
        // Remove in-progress marker
        Files.deleteIfExists(targetPath.resolve(CLONE_IN_PROGRESS_MARKER));

        // Create complete marker
        Path marker = targetPath.resolve(CLONE_COMPLETE_MARKER);
        String content = String.format("%s%n%d%n%s", repoUrl, System.currentTimeMillis(), branch);
        Files.writeString(marker, content);
    }
}
