package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.util.Environment;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RepositoryCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for cleaning up Git resources in tests, with special handling for Windows file handle issues.
 */
public class GitTestCleanupUtil {

    static {
        if (Environment.isWindows()) {
            // Disable JGit's memory mapping on Windows to prevent file-locking
            // issues that cause temp-dir deletion to fail in tests.
            System.setProperty("jgit.usemmap", "false");
        }
    }

    /**
     * Performs robust cleanup of Git repositories and their resources, with Windows-specific handling.
     *
     * @param gitRepo The GitRepo instance to close (may be null)
     * @param gitInstances Git instances to close (may contain nulls)
     */
    public static void cleanupGitResources(GitRepo gitRepo, Git... gitInstances) {
        // Close GitRepo first, which should close its internal Git and Repository instances
        closeWithErrorHandling("GitRepo", () -> {
            if (gitRepo != null) {
                gitRepo.close();
            }
        });

        // Close Git instances - may be redundant but ensures cleanup on Windows
        for (int i = 0; i < gitInstances.length; i++) {
            var git = gitInstances[i];
            final int index = i;
            closeWithErrorHandling("Git[" + index + "]", () -> {
                if (git != null) {
                    git.close();
                }
            });
        }

        // Clear any cached JGit repositories to release mmapped pack files,
        // preventing Windows file-handle leaks that block temp-dir deletion.
        closeWithErrorHandling("RepositoryCache.clear", RepositoryCache::clear);

        // Windows-specific cleanup: first trigger GC, then eagerly delete the
        // repository/work-tree directories to release any lingering mmapped pack
        // files that would otherwise prevent JUnit from deleting the temp dir.
        if (Environment.isWindows()) {
            performWindowsFileHandleCleanup();

            // Gather unique directories associated with the Git resources
            Set<Path> dirsToDelete = new HashSet<>();
            if (gitRepo != null) {
                dirsToDelete.add(gitRepo.getGitTopLevel());
            }
            for (Git git : gitInstances) {
                if (git != null) {
                    var repo = git.getRepository();
                    Path dir = repo.isBare()
                             ? repo.getDirectory().toPath()
                             : repo.getWorkTree().toPath();
                    dirsToDelete.add(dir);
                }
            }

            // Attempt to remove each directory (best-effort, logged on failure)
            for (Path dir : dirsToDelete) {
                closeWithErrorHandling("forceDeleteDirectory(" + dir + ")", () -> {
                    try {
                        forceDeleteDirectory(dir);
                    } catch (IOException e) {
                        // ignore
                    }
                });
            }
        }
    }

    /**
     * Forcefully deletes a directory tree, with multiple attempts on Windows to handle file locking.
     *
     * @param directory The directory to delete
     * @throws IOException if deletion fails after all retry attempts
     */
    public static void forceDeleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        if (Environment.isWindows()) {
            // On Windows, try multiple times with increasing delays
            IOException lastException = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    deleteDirectoryRecursively(directory);
                    return; // Success
                } catch (IOException e) {
                    lastException = e;
                    System.err.println("Attempt " + attempt + " to delete " + directory + " failed: " + e.getMessage());

                    if (attempt < 3) {
                        // Force cleanup and wait before retry
                        performWindowsFileHandleCleanup();
                        try {
                            Thread.sleep(1000 * attempt); // Increasing delay: 1s, 2s
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted during cleanup", ie);
                        }
                    }
                }
            }
            throw new IOException("Failed to delete directory after 3 attempts", lastException);
        } else {
            // On Unix systems, single attempt should be sufficient
            deleteDirectoryRecursively(directory);
        }
    }

    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(path -> {
                      try {
                          Files.delete(path);
                      } catch (IOException e) {
                          throw new RuntimeException("Failed to delete: " + path, e);
                      }
                  });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
    }

    private static void performWindowsFileHandleCleanup() {
        // Force garbage collection to help release file handles
        System.gc();

        // Allow time for file handles to be released
        try {
            Thread.sleep(750); // Increased from 500ms for more robust cleanup
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeWithErrorHandling(String resourceName, Runnable closeAction) {
        try {
            closeAction.run();
        } catch (Exception e) {
            // Log but don't fail test cleanup
            System.err.println("Error closing " + resourceName + ": " + e.getMessage());
        }
    }
}
