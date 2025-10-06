package io.github.jbellis.brokk.difftool.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * Small async blame fetcher using JGit. Results are cached per-path. Failures return an empty map.
 *
 * <p>Note: this is intentionally minimal and conservative — it runs JGit blame off the EDT and returns a
 * CompletableFuture that completes on the thread that finishes the work; callers must SwingUtilities.invokeLater when
 * updating UI.
 */
public final class BlameService {
    private static final Logger logger = LogManager.getLogger(BlameService.class);

    public static final String NOT_COMMITTED_YET = "Not Committed Yet";

    public static final record BlameInfo(String author, String shortSha, Long authorTime) {}

    private final Git git;
    private final Path repositoryRoot;

    // Cache keyed by "absolutePath" for current file or "absolutePath@@revision" for specific revisions
    private final ConcurrentMap<String, CompletableFuture<Map<Integer, BlameInfo>>> cache = new ConcurrentHashMap<>();

    // Track last error message per cache key for user feedback
    private final ConcurrentMap<String, String> lastErrors = new ConcurrentHashMap<>();

    /**
     * Creates a new BlameService using the provided Git instance.
     *
     * @param git The JGit Git instance to use for blame operations (non-null)
     */
    public BlameService(Git git) {
        this.git = git;
        this.repositoryRoot = git.getRepository().getWorkTree().toPath();
    }

    /**
     * Request blame for the given file path (working tree version).
     *
     * <p>This method asynchronously runs {@code git blame} on the file and caches the results. Subsequent requests for
     * the same file path will return the cached result.
     *
     * <p><b>Error Handling:</b> The returned future never completes exceptionally. On any error (file not found, git
     * command failure, etc.), it completes with an empty map. Use {@link #getLastError(Path)} to retrieve error
     * details.
     *
     * @param filePath The file path to blame (will be converted to absolute path for caching)
     * @return A future that completes with a map of line number (1-based) to {@link BlameInfo}, or an empty map on
     *     error. Never {@code null}.
     */
    public CompletableFuture<Map<Integer, BlameInfo>> requestBlame(Path filePath) {
        logger.debug("Requesting blame for: {}", filePath);
        // Use absolute path string as cache key
        String cacheKey = filePath.toAbsolutePath().toString();
        return cache.computeIfAbsent(cacheKey, k -> startBlameTask(filePath));
    }

    /**
     * Request blame for a specific git revision of a file.
     *
     * <p>This method asynchronously runs {@code git blame <revision>} on the file and caches the results. The cache key
     * includes both the file path and revision, so different revisions of the same file are cached separately.
     *
     * <p><b>Note:</b> The file need not exist in the working tree for revision-based blame to work, as long as it
     * exists in the specified revision.
     *
     * <p><b>Error Handling:</b> The returned future never completes exceptionally. On any error (revision not found,
     * file not in revision, git command failure, etc.), it completes with an empty map. Use
     * {@link #getLastErrorForRevision(Path, String)} to retrieve error details.
     *
     * @param filePath The file path (will be converted to absolute path for caching)
     * @param revision The git revision (e.g., "HEAD", "HEAD~1", "abc123", "main")
     * @return A future that completes with a map of line number (1-based) to {@link BlameInfo}, or an empty map on
     *     error. Never {@code null}.
     */
    public CompletableFuture<Map<Integer, BlameInfo>> requestBlameForRevision(Path filePath, String revision) {
        logger.debug("Requesting blame for revision {} of: {}", revision, filePath);
        // Create cache key that includes revision
        String cacheKey = filePath.toAbsolutePath().toString() + "@@" + revision;
        return cache.computeIfAbsent(cacheKey, k -> startBlameTaskForRevision(filePath, revision));
    }

    /**
     * Checks if a file exists in the specified git revision.
     *
     * <p>This method uses JGit's TreeWalk to efficiently check for file existence without reading the file content. It
     * handles path conversion, revision resolution, and returns false for any errors (missing revision, invalid path,
     * etc.).
     *
     * <p><b>Use case:</b> Before requesting blame for a specific revision, check if the file existed in that revision
     * to avoid unnecessary blame operations and error logging for newly added files.
     *
     * @param filePath The file path to check (will be converted to repository-relative path)
     * @param revision The git revision (e.g., "HEAD", "HEAD~1", "abc123", "main")
     * @return {@code true} if the file exists in that revision, {@code false} otherwise (including errors)
     */
    public boolean fileExistsInRevision(Path filePath, String revision) {
        try {
            String relativePath = getRepositoryRelativePath(
                    filePath, filePath.toAbsolutePath().toString());
            if (relativePath == null) {
                return false;
            }

            ObjectId revisionId = git.getRepository().resolve(revision);
            if (revisionId == null) {
                return false;
            }

            try (RevWalk revWalk = new RevWalk(git.getRepository())) {
                RevCommit commit = revWalk.parseCommit(revisionId);
                try (TreeWalk treeWalk = TreeWalk.forPath(git.getRepository(), relativePath, commit.getTree())) {
                    return treeWalk != null;
                }
            }
        } catch (Exception e) {
            logger.debug("File existence check failed for {} at {}: {}", filePath, revision, e.getMessage());
            return false;
        }
    }

    /**
     * Safely converts an absolute file path to a repository-relative path.
     *
     * <p>This method validates that the file is under the repository root and handles path relativization errors
     * gracefully. If the file is outside the repository or on a different filesystem, it returns {@code null} and
     * records an error message.
     *
     * @param filePath The file path to convert (will be converted to absolute and normalized)
     * @param cacheKey The cache key for error tracking
     * @return The repository-relative path as a string, or {@code null} if the path cannot be relativized
     */
    private @Nullable String getRepositoryRelativePath(Path filePath, String cacheKey) {
        try {
            Path absolutePath = filePath.toAbsolutePath().normalize();
            if (!absolutePath.startsWith(repositoryRoot)) {
                String error = "File is not under repository root: " + filePath;
                logger.warn(error);
                lastErrors.put(cacheKey, error);
                return null;
            }
            return repositoryRoot.relativize(absolutePath).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            String error = "Cannot relativize path: " + e.getMessage();
            logger.warn("{} for file: {}", error, filePath);
            lastErrors.put(cacheKey, error);
            return null;
        }
    }

    private CompletableFuture<Map<Integer, BlameInfo>> startBlameTask(Path filePath) {
        return CompletableFuture.supplyAsync(() -> {
            String cacheKey = filePath.toAbsolutePath().toString();
            try {
                // Convert to repository-relative path for JGit
                String relativePath = getRepositoryRelativePath(filePath, cacheKey);
                if (relativePath == null) {
                    return Map.<Integer, BlameInfo>of();
                }

                // Run JGit blame command
                BlameResult blameResult = git.blame().setFilePath(relativePath).call();

                if (blameResult == null) {
                    logger.warn("Blame returned null for file: {}", filePath);
                    lastErrors.put(cacheKey, "Blame returned no results");
                    return Map.<Integer, BlameInfo>of();
                }

                // Convert BlameResult to Map<Integer, BlameInfo>
                Map<Integer, BlameInfo> result = new HashMap<>();
                int lineCount = blameResult.getResultContents().size();

                for (int i = 0; i < lineCount; i++) {
                    RevCommit commit = blameResult.getSourceCommit(i);

                    if (commit == null) {
                        // Uncommitted line
                        result.put(i + 1, new BlameInfo(NOT_COMMITTED_YET, "", 0L));
                    } else {
                        PersonIdent author = commit.getAuthorIdent();
                        String authorName = author != null ? author.getName() : NOT_COMMITTED_YET;
                        String fullSha = commit.getName();
                        String shortSha = fullSha.length() >= 8 ? fullSha.substring(0, 8) : fullSha;
                        long authorTime =
                                author != null ? author.getWhenAsInstant().getEpochSecond() : 0L;

                        result.put(i + 1, new BlameInfo(authorName, shortSha, authorTime));
                    }
                }

                lastErrors.remove(cacheKey); // Clear any previous error on success
                return Map.copyOf(result);

            } catch (GitAPIException e) {
                logger.error("Git blame failed for {}: {}", filePath, e.getMessage(), e);
                lastErrors.put(cacheKey, e.getMessage() != null ? e.getMessage() : "Git command failed");
                return Map.<Integer, BlameInfo>of();
            } catch (Exception e) {
                logger.error("Blame failed for {}: {}", filePath, e.getMessage(), e);
                lastErrors.put(cacheKey, e.getMessage() != null ? e.getMessage() : "Unknown error");
                return Map.<Integer, BlameInfo>of();
            }
        });
    }

    private CompletableFuture<Map<Integer, BlameInfo>> startBlameTaskForRevision(Path filePath, String revision) {
        return CompletableFuture.supplyAsync(() -> {
            String cacheKey = filePath.toAbsolutePath().toString() + "@@" + revision;
            try {
                // Convert to repository-relative path for JGit
                String relativePath = getRepositoryRelativePath(filePath, cacheKey);
                if (relativePath == null) {
                    return Map.<Integer, BlameInfo>of();
                }

                // Resolve revision to ObjectId
                ObjectId revisionId = git.getRepository().resolve(revision);
                if (revisionId == null) {
                    logger.warn("Could not resolve revision {} for file: {}", revision, filePath);
                    lastErrors.put(cacheKey, "Could not resolve revision: " + revision);
                    return Map.<Integer, BlameInfo>of();
                }

                // Run JGit blame command for the specified revision
                BlameResult blameResult = git.blame()
                        .setFilePath(relativePath)
                        .setStartCommit(revisionId)
                        .call();

                if (blameResult == null) {
                    logger.warn("Blame returned null for revision {} of file: {}", revision, filePath);
                    lastErrors.put(cacheKey, "Blame returned no results for " + revision);
                    return Map.<Integer, BlameInfo>of();
                }

                // Convert BlameResult to Map<Integer, BlameInfo>
                Map<Integer, BlameInfo> result = new HashMap<>();
                int lineCount = blameResult.getResultContents().size();

                for (int i = 0; i < lineCount; i++) {
                    RevCommit commit = blameResult.getSourceCommit(i);

                    if (commit == null) {
                        // Uncommitted line
                        result.put(i + 1, new BlameInfo(NOT_COMMITTED_YET, "", 0L));
                    } else {
                        PersonIdent author = commit.getAuthorIdent();
                        String authorName = author != null ? author.getName() : NOT_COMMITTED_YET;
                        String fullSha = commit.getName();
                        String shortSha = fullSha.length() >= 8 ? fullSha.substring(0, 8) : fullSha;
                        long authorTime =
                                author != null ? author.getWhenAsInstant().getEpochSecond() : 0L;

                        result.put(i + 1, new BlameInfo(authorName, shortSha, authorTime));
                    }
                }

                lastErrors.remove(cacheKey); // Clear any previous error on success
                return Map.copyOf(result);

            } catch (GitAPIException e) {
                logger.error("Git blame for revision {} failed for {}: {}", revision, filePath, e.getMessage(), e);
                lastErrors.put(
                        cacheKey,
                        "Git command failed for " + revision + ": "
                                + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
                return Map.<Integer, BlameInfo>of();
            } catch (Exception e) {
                logger.error("Blame for revision {} failed for {}: {}", revision, filePath, e.getMessage(), e);
                lastErrors.put(cacheKey, e.getMessage() != null ? e.getMessage() : "Unknown error");
                return Map.<Integer, BlameInfo>of();
            }
        });
    }

    /**
     * Clear the cached blame data for a specific file path (working tree version only).
     *
     * <p>This removes only the working tree blame cache entry. To clear revision-specific blame, use
     * {@link #clearAllCache()} as there is no method to clear individual revisions.
     *
     * <p><b>Use case:</b> Call this when a file has been modified on disk and you want to force a fresh blame request
     * on the next call to {@link #requestBlame(Path)}.
     *
     * @param filePath The file path to clear from cache
     */
    public void clearCacheFor(Path filePath) {
        cache.remove(filePath.toAbsolutePath().toString());
    }

    /**
     * Clear all cached blame data (both working tree and all revisions).
     *
     * <p><b>Warning:</b> This clears the entire cache. Subsequent blame requests will need to re-run git blame
     * commands. Use sparingly, typically only when the repository state has changed significantly (e.g., after a rebase
     * or branch switch).
     */
    public void clearAllCache() {
        cache.clear();
    }

    /**
     * Get the last error message for a working tree blame request, if any.
     *
     * <p>This returns the error message from the most recent {@link #requestBlame(Path)} call for the given file. If
     * the most recent request succeeded, this returns {@code null}.
     *
     * @param filePath The file path to check for errors
     * @return The error message string, or {@code null} if no error occurred or blame succeeded
     */
    public @Nullable String getLastError(Path filePath) {
        return lastErrors.get(filePath.toAbsolutePath().toString());
    }

    /**
     * Get the last error message for a revision-specific blame request, if any.
     *
     * <p>This returns the error message from the most recent {@link #requestBlameForRevision(Path, String)} call for
     * the given file and revision. If the most recent request succeeded, this returns {@code null}.
     *
     * @param filePath The file path to check for errors
     * @param revision The git revision (must match the revision used in the blame request)
     * @return The error message string, or {@code null} if no error occurred or blame succeeded
     */
    public @Nullable String getLastErrorForRevision(Path filePath, String revision) {
        var cacheKey = filePath.toAbsolutePath().toString() + "@@" + revision;
        return lastErrors.get(cacheKey);
    }
}
