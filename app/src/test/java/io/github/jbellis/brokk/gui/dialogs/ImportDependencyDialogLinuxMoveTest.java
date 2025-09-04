package io.github.jbellis.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the directory move used by ImportDependencyDialog.performGitImport:
 * - Create a non-empty "cloned" directory in a temp location (/dev/shm preferred on Linux).
 * - Move it into a temp "dependencies" directory within the project root.
 *
 * On Linux, when source and destination are on different filesystems (e.g., tmpfs -> ext4),
 * Files.move(source, dest, REPLACE_EXISTING) may fail with DirectoryNotEmptyException
 * due to JDK-8201407. This test expects the move to succeed; thus it will fail on Linux CI,
 * demonstrating the bug.
 */
public class ImportDependencyDialogLinuxMoveTest {
    @Test
    void movingNonEmptyDirectoryFromTempToProjectRoot_shouldSucceed_butFailsOnLinuxDueToJdk8201407() throws Exception {
        var projectRoot = Paths.get(".").toAbsolutePath().normalize();
        var destParent = Files.createTempDirectory(projectRoot, "brokk-deps-");
        var dest = destParent.resolve("repo");
        Path source = null;
        try {
            // Prefer /dev/shm on Linux to increase the likelihood of cross-filesystem conditions
            var shm = Paths.get("/dev/shm");
            Path base = Files.isDirectory(shm) && Files.isWritable(shm) ? shm : null;

            source = (base != null)
                    ? Files.createTempDirectory(base, "brokk-git-clone-")
                    : Files.createTempDirectory("brokk-git-clone-");

            // Make the source directory non-empty
            Files.writeString(source.resolve("README.md"), "brokk");

            FileStore srcStore = Files.getFileStore(source);
            FileStore dstStore = Files.getFileStore(destParent);

            System.out.printf(
                    "Repro move: src=%s (store=%s), dest=%s (store=%s)%n",
                    source, srcStore.name(), dest, dstStore.name());

            // Mirror dialog move: Files.move(tempDir, targetPath, REPLACE_EXISTING)
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);

            // If move "succeeds" (same FS), assert the file is present at destination.
            assertTrue(
                    Files.exists(dest.resolve("README.md")),
                    "Move completed but README.md missing at destination");
        } catch (FileSystemException e) {
            // Let the exception propagate (test fails), which demonstrates the bug on Linux.
            throw e;
        } finally {
            // Cleanup best-effort to not pollute CI workspace
            try {
                if (dest != null && Files.exists(dest)) deleteRecursively(dest);
            } catch (IOException ignore) {
            }
            try {
                if (destParent != null && Files.exists(destParent)) deleteRecursively(destParent);
            } catch (IOException ignore) {
            }
            try {
                if (source != null && Files.exists(source)) deleteRecursively(source);
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
