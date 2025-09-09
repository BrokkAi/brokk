package io.github.jbellis.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.util.CloneOperationTracker;
import io.github.jbellis.brokk.util.FileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the direct clone approach with marker files and cleanup mechanisms. */
public class ImportDependencyDialogDirectCloneTest {
    private Path testRoot;
    private Path dependenciesRoot;

    @BeforeEach
    void setUp() throws IOException {
        testRoot = Files.createTempDirectory("brokk-direct-clone-test-");
        dependenciesRoot = testRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR);
        Files.createDirectories(dependenciesRoot);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(testRoot)) {
            FileUtil.deleteRecursively(testRoot);
        }
    }

    @Test
    void directCloneMarkerFiles_shouldCreateAndRemoveCorrectly() throws IOException {
        Path targetPath = dependenciesRoot.resolve("test-repo");
        Files.createDirectories(targetPath);

        // Test in-progress marker
        CloneOperationTracker.createInProgressMarker(targetPath, "https://github.com/test/repo.git", "default");
        assertTrue(Files.exists(targetPath.resolve(CloneOperationTracker.CLONE_IN_PROGRESS_MARKER)));
        assertFalse(Files.exists(targetPath.resolve(CloneOperationTracker.CLONE_COMPLETE_MARKER)));

        // Test complete marker (should remove in-progress)
        CloneOperationTracker.createCompleteMarker(targetPath, "https://github.com/test/repo.git", "default");
        assertFalse(Files.exists(targetPath.resolve(CloneOperationTracker.CLONE_IN_PROGRESS_MARKER)));
        assertTrue(Files.exists(targetPath.resolve(CloneOperationTracker.CLONE_COMPLETE_MARKER)));
    }

    @Test
    void orphanedCloneCleanup_shouldRemovePartialClones() throws IOException {
        // Create a directory with in-progress marker (simulating crashed clone)
        Path orphanedClone = dependenciesRoot.resolve("orphaned-repo");
        Files.createDirectories(orphanedClone);
        Files.writeString(orphanedClone.resolve("some-file.txt"), "partial content");
        CloneOperationTracker.createInProgressMarker(orphanedClone, "https://github.com/test/orphaned.git", "default");

        // Create a directory with complete marker (should not be removed)
        Path completeClone = dependenciesRoot.resolve("complete-repo");
        Files.createDirectories(completeClone);
        Files.writeString(completeClone.resolve("README.md"), "complete content");
        CloneOperationTracker.createCompleteMarker(completeClone, "https://github.com/test/complete.git", "default");

        // Run cleanup
        CloneOperationTracker.cleanupOrphanedClones(dependenciesRoot);

        // Verify results
        assertFalse(Files.exists(orphanedClone), "Orphaned clone should be removed");
        assertTrue(Files.exists(completeClone), "Complete clone should remain");
        assertTrue(Files.exists(completeClone.resolve("README.md")), "Complete clone content should remain");
    }

    @Test
    void cloneOperationTracking_shouldManageShutdownHooks() throws IOException {
        Path targetPath1 = dependenciesRoot.resolve("repo1");
        Path targetPath2 = dependenciesRoot.resolve("repo2");
        Files.createDirectories(targetPath1);
        Files.createDirectories(targetPath2);

        // Test registration
        CloneOperationTracker.registerCloneOperation(targetPath1);
        CloneOperationTracker.registerCloneOperation(targetPath2);

        // Test unregistration
        CloneOperationTracker.unregisterCloneOperation(targetPath1);
        CloneOperationTracker.unregisterCloneOperation(targetPath2);

        // No exceptions should be thrown - this tests the basic lifecycle
        assertTrue(true, "Registration and unregistration completed without errors");
    }
}
