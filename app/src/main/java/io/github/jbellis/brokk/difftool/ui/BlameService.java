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
 * Async blame fetcher using JGit with per-path caching. Runs off-EDT; callers must use SwingUtilities.invokeLater for
 * UI updates. Failures return empty maps.
 */
public final class BlameService {
    private static final Logger logger = LogManager.getLogger(BlameService.class);

    public static final String NOT_COMMITTED_YET = "Not Committed Yet";

    public static final record BlameInfo(String author, String shortSha, long authorTime) {}

    private final Git git;
    private final Path repositoryRoot;

    // Cache keyed by "absolutePath" for current file or "absolutePath@@revision" for specific revisions
    private final ConcurrentMap<String, CompletableFuture<Map<Integer, BlameInfo>>> cache = new ConcurrentHashMap<>();

    // Track last error message per cache key for user feedback
    private final ConcurrentMap<String, String> lastErrors = new ConcurrentHashMap<>();

    /** Creates a BlameService using the provided Git instance. */
    public BlameService(Git git) {
        this.git = git;
        this.repositoryRoot = git.getRepository().getWorkTree().toPath();
    }

    /**
     * Async blame for working tree file. Results are cached. Returns empty map on error; use getLastError() for
     * details.
     *
     * @return Future with map of 1-based line number to BlameInfo
     */
    public CompletableFuture<Map<Integer, BlameInfo>> requestBlame(Path filePath) {
        logger.debug("Requesting blame for: {}", filePath);
        // Use absolute path string as cache key
        String cacheKey = filePath.toAbsolutePath().toString();
        return cache.computeIfAbsent(cacheKey, k -> startBlameTask(filePath));
    }

    /**
     * Async blame for file at specific revision. Cached per file+revision. Returns empty map on error; use
     * getLastErrorForRevision() for details.
     *
     * @param revision Git revision (e.g., "HEAD", "HEAD~1", commit sha)
     * @return Future with map of 1-based line number to BlameInfo
     */
    public CompletableFuture<Map<Integer, BlameInfo>> requestBlameForRevision(Path filePath, String revision) {
        logger.debug("Requesting blame for revision {} of: {}", revision, filePath);
        // Create cache key that includes revision
        String cacheKey = filePath.toAbsolutePath().toString() + "@@" + revision;
        return cache.computeIfAbsent(cacheKey, k -> startBlameTaskForRevision(filePath, revision));
    }

    /** Checks if file exists in the specified revision using TreeWalk. Returns false on any error. */
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

    /** Converts file path to repository-relative path. Returns null and records error if path is outside repository. */
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

                // Run JGit blame command with rename detection
                BlameResult blameResult = git.blame()
                        .setFilePath(relativePath)
                        .setFollowFileRenames(true)
                        .call();

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

                // Run JGit blame command for the specified revision with rename detection
                BlameResult blameResult = git.blame()
                        .setFilePath(relativePath)
                        .setStartCommit(revisionId)
                        .setFollowFileRenames(true)
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

    /** Clears cached blame for working tree file. Call after file modification to force refresh. */
    public void clearCacheFor(Path filePath) {
        cache.remove(filePath.toAbsolutePath().toString());
    }

    /** Clears all cached blame data. Use sparingly (e.g., after rebase or branch switch). */
    public void clearAllCache() {
        cache.clear();
    }

    /** Returns error message from last working tree blame request, or null if successful. */
    public @Nullable String getLastError(Path filePath) {
        return lastErrors.get(filePath.toAbsolutePath().toString());
    }

    /** Returns error message from last revision blame request, or null if successful. */
    public @Nullable String getLastErrorForRevision(Path filePath, String revision) {
        var cacheKey = filePath.toAbsolutePath().toString() + "@@" + revision;
        return lastErrors.get(cacheKey);
    }
}
