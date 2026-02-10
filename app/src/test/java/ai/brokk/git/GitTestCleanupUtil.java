package ai.brokk.git;

import ai.brokk.util.Environment;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RepositoryCache;

/** Utility class for cleaning up Git resources in tests, with special handling for Windows file handle issues. */
public class GitTestCleanupUtil {

    private static final long[] DELETE_RETRY_BACKOFF_MS = {200L, 500L, 1000L};

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
     * Forcefully deletes a directory tree, retrying on any IOException on all platforms.
     *
     * @param directory The directory to delete
     * @throws IOException if deletion fails after all retry attempts
     */
    public static void forceDeleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        IOException lastException = null;
        for (int attempt = 1; attempt <= DELETE_RETRY_BACKOFF_MS.length; attempt++) {
            try {
                deleteDirectoryRecursively(directory);
                return;
            } catch (IOException e) {
                lastException = e;

                if (attempt < DELETE_RETRY_BACKOFF_MS.length) {
                    System.err.println("Attempt " + attempt + " to delete " + directory + " failed: " + e.getMessage());

                    if (Environment.isWindows()) {
                        performWindowsFileHandleCleanup();
                    }

                    sleepBestEffort(DELETE_RETRY_BACKOFF_MS[attempt - 1]);
                }
            }
        }

        throw new IOException(
                "Failed to delete directory after " + DELETE_RETRY_BACKOFF_MS.length + " attempts", lastException);
    }

    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    boolean isFile = Files.exists(path) && !Files.isDirectory(path);
                    if (isFile && isPermissionLikeDeleteFailure(e)) {
                        try {
                            path.toFile().setWritable(true);
                        } catch (Exception ignored) {
                            // best-effort
                        }
                        try {
                            Files.deleteIfExists(path);
                            return;
                        } catch (IOException e2) {
                            throw new RuntimeException("Failed to delete: " + path, e2);
                        }
                    }
                    throw new RuntimeException("Failed to delete: " + path, e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw e;
        }
    }

    private static boolean isPermissionLikeDeleteFailure(IOException e) {
        return (e instanceof FileSystemException)
                || (e instanceof AccessDeniedException)
                || (e instanceof DirectoryNotEmptyException);
    }

    private static void sleepBestEffort(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during cleanup", ie);
        }
    }

    private static void performWindowsFileHandleCleanup() {
        // Force garbage collection to help release file handles
        System.gc();

        // Allow time for file handles to be released
        try {
            Thread.sleep(750);
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
