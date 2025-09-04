package io.github.jbellis.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jbellis.brokk.AbstractProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;

/**
 * Tests the same-filesystem staging directory approach used by ImportDependencyDialog.performGitImport to avoid
 * cross-filesystem move issues (JDK-8201407).
 *
 * <p>This test verifies that our fix works: by creating staging directories within the project's .brokk folder, both
 * source and destination are on the same filesystem, eliminating the DirectoryNotEmptyException that occurs when moving
 * non-empty directories across filesystem boundaries.
 */
public class ImportDependencyDialogSameFilesystemStagingTest {
    @Test
    void sameFilesystemStagingDirectoryMove_shouldSucceedOnAllPlatforms() throws Exception {
        var projectRoot = Paths.get(".").toAbsolutePath().normalize();

        // Create .brokk directory structure like the production code
        var brokkRoot = projectRoot.resolve(AbstractProject.BROKK_DIR);
        Files.createDirectories(brokkRoot);

        // Create staging directory within .brokk (same filesystem as destination)
        var stagingDir = Files.createTempDirectory(brokkRoot, "git-staging-");
        var destParent = Files.createTempDirectory(projectRoot, "brokk-deps-");
        var dest = destParent.resolve("repo");

        try {
            // Make the staging directory non-empty (like a cloned repo)
            Files.writeString(stagingDir.resolve("README.md"), "brokk test content");
            Files.createDirectories(stagingDir.resolve("src"));
            Files.writeString(stagingDir.resolve("src").resolve("Main.java"), "public class Main {}");

            System.out.printf("Same-filesystem move: staging=%s, dest=%s%n", stagingDir, dest);

            // Mirror production dialog move: Files.move(stagingDir, targetPath, REPLACE_EXISTING)
            Files.move(stagingDir, dest, StandardCopyOption.REPLACE_EXISTING);

            // Assert the move succeeded and files are present
            assertTrue(Files.exists(dest.resolve("README.md")), "README.md should be present at destination");
            assertTrue(
                    Files.exists(dest.resolve("src").resolve("Main.java")),
                    "Main.java should be present at destination");
        } finally {
            // Cleanup best-effort to not pollute CI workspace
            try {
                if (Files.exists(dest)) deleteRecursively(dest);
            } catch (IOException ignore) {
            }
            try {
                if (Files.exists(destParent)) deleteRecursively(destParent);
            } catch (IOException ignore) {
            }
            try {
                if (Files.exists(stagingDir)) deleteRecursively(stagingDir);
            } catch (IOException ignore) {
            }
            try {
                if (Files.exists(brokkRoot)) deleteRecursively(brokkRoot);
            } catch (IOException ignore) {
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                        }
                    });
        }
    }
}
