package ai.brokk.git;

import ai.brokk.analyzer.ProjectFile;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Shared cache holder for branch-added GitRepo query accelerators. */
public final class GitRepoCaches {
    public record FileHistoryLookup(Map<ProjectFile, List<CommitInfo>> cachedResults, Set<ProjectFile> missingFiles) {}

    private record FileHistoryCacheKey(String headCommitId, ProjectFile file, int maxResults) {}

    private record CommitListCacheKey(String headCommitId, String branchName, int maxResults) {}

    private record CanonicalizerCacheKey(String headCommitId, List<String> commitIds) {}

    private final Cache<FileHistoryCacheKey, List<CommitInfo>> fileHistoriesCache =
            Caffeine.newBuilder().maximumSize(4096).build();
    private final Cache<CommitListCacheKey, List<CommitInfo>> commitListCache =
            Caffeine.newBuilder().maximumSize(256).build();
    private final Cache<String, List<IGitRepo.ModifiedFile>> changedFilesByCommitCache =
            Caffeine.newBuilder().maximumSize(4096).build();
    private final Cache<CanonicalizerCacheKey, GitCanonicalizer> canonicalizerCache =
            Caffeine.newBuilder().maximumSize(64).build();

    public void invalidateHeadScopedCaches() {
        commitListCache.invalidateAll();
        canonicalizerCache.invalidateAll();
    }

    public @Nullable List<CommitInfo> getCommitList(String headCommitId, String branchName, int maxResults) {
        return commitListCache.getIfPresent(new CommitListCacheKey(headCommitId, branchName, maxResults));
    }

    public void putCommitList(String headCommitId, String branchName, int maxResults, List<CommitInfo> commits) {
        commitListCache.put(new CommitListCacheKey(headCommitId, branchName, maxResults), List.copyOf(commits));
    }

    public @Nullable List<IGitRepo.ModifiedFile> getChangedFilesByCommit(String commitId) {
        return changedFilesByCommitCache.getIfPresent(commitId);
    }

    public void putChangedFilesByCommit(String commitId, List<IGitRepo.ModifiedFile> changedFiles) {
        changedFilesByCommitCache.put(commitId, List.copyOf(changedFiles));
    }

    public FileHistoryLookup lookupFileHistories(String headCommitId, Collection<ProjectFile> files, int maxResults) {
        var cachedResults = new LinkedHashMap<ProjectFile, List<CommitInfo>>();
        var missingFiles = new LinkedHashSet<ProjectFile>();
        for (var file : files) {
            var cached = fileHistoriesCache.getIfPresent(new FileHistoryCacheKey(headCommitId, file, maxResults));
            if (cached != null) {
                cachedResults.put(file, cached);
            } else {
                missingFiles.add(file);
            }
        }
        return new FileHistoryLookup(cachedResults, missingFiles);
    }

    public void putFileHistories(String headCommitId, Map<ProjectFile, List<CommitInfo>> results, int maxResults) {
        for (var entry : results.entrySet()) {
            var cachedValue = List.copyOf(entry.getValue());
            fileHistoriesCache.put(new FileHistoryCacheKey(headCommitId, entry.getKey(), maxResults), cachedValue);
        }
    }

    public @Nullable GitCanonicalizer getCanonicalizer(String headCommitId, List<CommitInfo> commits) {
        return canonicalizerCache.getIfPresent(
                new CanonicalizerCacheKey(headCommitId, commits.stream().map(CommitInfo::id).toList()));
    }

    public void putCanonicalizer(String headCommitId, List<CommitInfo> commits, GitCanonicalizer canonicalizer) {
        canonicalizerCache.put(
                new CanonicalizerCacheKey(headCommitId, commits.stream().map(CommitInfo::id).toList()), canonicalizer);
    }
}
