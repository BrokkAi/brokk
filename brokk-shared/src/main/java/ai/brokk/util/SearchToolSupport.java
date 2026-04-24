package ai.brokk.util;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitDistance;
import ai.brokk.git.IGitRepo;
import ai.brokk.ranking.ImportPageRanker;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/** Shared SearchTools support for file ranking, related-content expansion, and Git-backed caching. */
public final class SearchToolSupport {
    private record FileRankingCacheKey(String cacheScope, List<ProjectFile> files) {}

    private record GitCommitSearchCacheKey(String currentCommitId, String pattern, int limit) {}

    private final Logger logger;
    private final Cache<FileRankingCacheKey, List<ProjectFile>> prioritizedFilesCache =
            Caffeine.newBuilder().maximumSize(64).build();
    private final Cache<GitCommitSearchCacheKey, IGitRepo.SearchCommitsResult> searchCommitsCache =
            Caffeine.newBuilder().maximumSize(128).build();
    private final Cache<String, List<ProjectFile>> changedFilesByCommitCache =
            Caffeine.newBuilder().maximumSize(2048).build();

    public SearchToolSupport(Logger logger) {
        this.logger = logger;
    }

    public List<ProjectFile> prioritizeFilesForSelection(Collection<ProjectFile> files, @Nullable IGitRepo gitRepo) {
        var alphabeticalFiles = files.stream().sorted().toList();
        if (gitRepo == null) {
            return alphabeticalFiles;
        }

        @Nullable String cacheScope;
        try {
            cacheScope = gitRepo.getCurrentCommitId();
            var cacheKey = new FileRankingCacheKey(cacheScope, alphabeticalFiles);
            var cached = prioritizedFilesCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve SearchTools ranking cache scope; ranking without cache", e);
            cacheScope = null;
        }

        try {
            var rankedFiles = GitDistance.sortByImportance(alphabeticalFiles, gitRepo);
            if (cacheScope != null) {
                prioritizedFilesCache.put(new FileRankingCacheKey(cacheScope, alphabeticalFiles), rankedFiles);
            }
            return rankedFiles;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while ranking files for SearchTools; falling back to alphabetical order");
            return alphabeticalFiles;
        } catch (RuntimeException e) {
            logger.debug("Failed to rank files for SearchTools; falling back to alphabetical order", e);
            return alphabeticalFiles;
        }
    }

    public List<ProjectFile> selectFilesForDisplay(Collection<ProjectFile> files, int limit) {
        var alphabeticalFiles = files.stream().sorted().toList();
        if (alphabeticalFiles.size() <= limit) {
            return alphabeticalFiles;
        }
        return alphabeticalFiles.stream().limit(limit).toList();
    }

    public String appendRelatedContent(
            String output,
            Collection<ProjectFile> resultFiles,
            int topK,
            IAnalyzer analyzer,
            @Nullable IGitRepo gitRepo) {
        var relatedFiles = getMostRelevantFilesForResults(resultFiles, topK, analyzer, gitRepo);
        if (relatedFiles.isEmpty()) {
            return output;
        }

        String relatedSection = relatedFiles.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"));
        return output + "\n\n## Related Content\n" + relatedSection;
    }

    public IGitRepo.SearchCommitsResult getCachedCommitSearchResult(IGitRepo gitRepo, String pattern, int limit)
            throws GitAPIException {
        var cacheKey = new GitCommitSearchCacheKey(gitRepo.getCurrentCommitId(), pattern, limit);
        var cached = searchCommitsCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        var computed = gitRepo.searchCommits(pattern, limit);
        searchCommitsCache.put(cacheKey, computed);
        return computed;
    }

    public List<ProjectFile> getCachedChangedFilesForCommit(IGitRepo gitRepo, String commitId)
            throws GitAPIException {
        var cached = changedFilesByCommitCache.getIfPresent(commitId);
        if (cached != null) {
            return cached;
        }

        var changedFiles = gitRepo.listFilesChangedInCommit(commitId).stream()
                .map(IGitRepo.ModifiedFile::file)
                .toList();
        changedFilesByCommitCache.put(commitId, changedFiles);
        return changedFiles;
    }

    private List<ProjectFile> getMostRelevantFilesForResults(
            Collection<ProjectFile> resultFiles,
            int topK,
            IAnalyzer analyzer,
            @Nullable IGitRepo gitRepo) {
        if (topK <= 0) {
            return List.of();
        }

        var ineligibleSources = new LinkedHashSet<>(resultFiles);
        if (ineligibleSources.isEmpty()) {
            return List.of();
        }

        Map<ProjectFile, Double> weightedSeeds = ineligibleSources.stream()
                .collect(Collectors.toMap(file -> file, file -> 1.0d, Double::sum, HashMap::new));

        Set<ProjectFile> relevantFiles = new LinkedHashSet<>();
        if (gitRepo != null) {
            try {
                relevantFiles.addAll(filterResults(
                        GitDistance.getRelatedFiles(gitRepo, weightedSeeds, topK).stream()
                                .map(IAnalyzer.FileRelevance::file)
                                .toList(),
                        ineligibleSources));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while computing Git-based related files for {}", ineligibleSources, e);
                return List.of();
            } catch (RuntimeException e) {
                logger.warn("Failed to compute Git-based related files; falling back to imports.", e);
            }
        }

        if (relevantFiles.size() < topK) {
            int remaining = topK - relevantFiles.size();
            var importResults = ImportPageRanker.getRelatedFilesByImports(analyzer, weightedSeeds, topK, false);
            filterResults(
                            importResults.stream().map(IAnalyzer.FileRelevance::file).toList(), ineligibleSources)
                    .stream()
                    .filter(file -> !relevantFiles.contains(file))
                    .limit(remaining)
                    .forEach(relevantFiles::add);
        }

        return List.copyOf(relevantFiles);
    }

    private static List<ProjectFile> filterResults(Collection<ProjectFile> results, Set<ProjectFile> ineligibleSources) {
        return results.stream().filter(file -> !ineligibleSources.contains(file)).toList();
    }
}
