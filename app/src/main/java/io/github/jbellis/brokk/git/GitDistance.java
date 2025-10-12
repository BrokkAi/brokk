package io.github.jbellis.brokk.git;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.VisibleForTesting;

/** Provides the logic to perform a Git-centric distance calculations for given type declarations. */
public final class GitDistance {
    private static final int COMMITS_TO_PROCESS = 1_000;

    /** Represents an edge between two CodeUnits in the co-occurrence graph. */
    public record FileEdge(ProjectFile src, ProjectFile dst) {}

    /**
     * Point-wise Mutual Information (PMI) distance.
     *
     * <p>p(X,Y) = |C(X) ∩ C(Y)| / |Commits| p(X) = |C(X)| / |Commits| PMI = log2( p(X,Y) / (p(X)·p(Y)) )
     *
     * @return a sorted list of files with relevance scores. If no seed weights are given,an empty result.
     */
    public static List<IAnalyzer.FileRelevance> getPMI(
            GitRepo repo, Map<ProjectFile, Double> seedWeights, int k, boolean reversed) {

        if (seedWeights.isEmpty()) {
            return List.of();
        }

        try {
            return computePmiScores(repo, seedWeights, k, reversed);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Using Git history, determines the most important files by analyzing their change frequency, considering both the
     * number of commits and the recency of those changes. This approach uses a weighted analysis of the Git history
     * where the weight of changes decays exponentially over time.
     *
     * <p>The formula for a file's score is:
     *
     * <p>S_file = sum_{c in commits} 2^(-(t_latest - t_c) / half-life)
     *
     * <p>Where:
     *
     * <ul>
     *   <li>t_c is the timestamp of commit c.
     *   <li>t_latest is the timestamp of the latest commit in the repository.
     *   <li>half-life is a constant (30 days) that determines how quickly the weight of changes decays.
     * </ul>
     *
     * @param repo the Git repository wrapper.
     * @param k the maximum number of files to return.
     * @return a sorted list of the most important files with their relevance scores.
     */
    @VisibleForTesting
    public static List<IAnalyzer.FileRelevance> getMostImportantFilesScored(GitRepo repo, int k)
            throws GitAPIException {
        var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), COMMITS_TO_PROCESS);
        if (commits.isEmpty()) {
            return List.of();
        }

        var scores = computeImportanceScores(repo, commits);

        return scores.entrySet().stream()
                .map(e -> new IAnalyzer.FileRelevance(e.getKey(), e.getValue()))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(k)
                .toList();
    }

    public static List<ProjectFile> getMostImportantFiles(GitRepo repo, int k) throws GitAPIException {
        return getMostImportantFilesScored(repo, k).stream()
                .map(IAnalyzer.FileRelevance::file)
                .toList();
    }

    /**
     * Fetches commits that modified any of the specified files, limited to maxResults.
     *
     * @param repo the Git repository wrapper.
     * @param files the collection of files to fetch commits for.
     * @param maxResults the maximum number of commits to fetch per file.
     * @return a sorted list of unique commits (newest first).
     */
    private static List<CommitInfo> getCommitsForFiles(GitRepo repo, Collection<ProjectFile> files, int maxResults) {
        if (files.isEmpty()) {
            return List.of();
        }

        try (var pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
            return pool.submit(() -> files.parallelStream()
                            .flatMap(file -> {
                                try {
                                    return repo.getFileHistory(file, maxResults).stream();
                                } catch (GitAPIException e) {
                                    throw new RuntimeException("Error getting file history for " + file, e);
                                }
                            })
                            .distinct()
                            .sorted((a, b) -> b.date().compareTo(a.date()))
                            .toList())
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error getting file history in parallel", e);
        }
    }

    /**
     * Sorts a collection of files by their importance using Git history analysis. The importance is determined by
     * analyzing change frequency and recency across the pooled commit histories of all provided files.
     *
     * <p>This method first collects all commits that modified any of the input files, then applies the same
     * time-weighted scoring algorithm as {@link #getMostImportantFilesScored} to rank them.
     *
     * @param files the collection of files to sort by importance.
     * @param repo the Git repository wrapper.
     * @return the input files sorted by importance (most important first).
     */
    public static List<ProjectFile> sortByImportance(Collection<ProjectFile> files, GitRepo repo) {
        if (files.isEmpty()) {
            return List.of();
        }

        var commits = getCommitsForFiles(repo, files, Integer.MAX_VALUE);

        Map<ProjectFile, Double> scores;
        try {
            scores = computeImportanceScores(repo, commits);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }

        return files.stream()
                .filter(scores::containsKey)
                .sorted((a, b) -> Double.compare(requireNonNull(scores.get(b)), requireNonNull(scores.get(a))))
                .toList();
    }

    private static Map<ProjectFile, Double> computeImportanceScores(GitRepo repo, List<CommitInfo> commits)
            throws GitAPIException {
        if (commits.isEmpty()) {
            return Map.of();
        }

        var t_latest = commits.getFirst().date();
        var halfLife = Duration.ofDays(30);
        double halfLifeMillis = halfLife.toMillis();

        var scores = new ConcurrentHashMap<ProjectFile, Double>();

        try (var pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
            pool.submit(() -> commits.parallelStream().forEach(commit -> {
                        try {
                            var changedFiles = repo.listFilesChangedInCommit(commit.id());
                            if (changedFiles.isEmpty()) {
                                return;
                            }

                            var t_c = commit.date();
                            var age = Duration.between(t_c, t_latest);
                            double ageMillis = age.toMillis();

                            double weight = Math.pow(2, -(ageMillis / halfLifeMillis));

                            for (var file : changedFiles) {
                                scores.merge(file, weight, Double::sum);
                            }

                        } catch (GitAPIException e) {
                            throw new RuntimeException("Error processing commit: " + commit.id(), e);
                        }
                    }))
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error computing file importance scores in parallel", e);
        }

        return scores;
    }

    private static List<IAnalyzer.FileRelevance> computePmiScores(
            GitRepo repo, Map<ProjectFile, Double> seedWeights, int k, boolean reversed) throws GitAPIException {
        var commits = getCommitsForFiles(repo, seedWeights.keySet(), COMMITS_TO_PROCESS);
        var totalCommits = commits.size();
        if (totalCommits == 0) return List.of();

        var fileCounts = new ConcurrentHashMap<ProjectFile, Integer>();
        var jointCounts = new ConcurrentHashMap<FileEdge, Integer>();

        try (var pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
            pool.submit(() -> commits.parallelStream().forEach(commit -> {
                        try {
                            var changedFiles = repo.listFilesChangedInCommit(commit.id());
                            if (changedFiles.isEmpty()) return;

                            // individual counts
                            for (var f : changedFiles) {
                                fileCounts.merge(f, 1, Integer::sum);
                            }

                            // joint counts
                            var seedsInCommit = changedFiles.stream()
                                    .filter(seedWeights::containsKey)
                                    .collect(Collectors.toSet());
                            if (seedsInCommit.isEmpty()) return;

                            for (var seed : seedsInCommit) {
                                for (var cu : changedFiles) {
                                    jointCounts.merge(new FileEdge(seed, cu), 1, Integer::sum);
                                }
                            }
                        } catch (GitAPIException e) {
                            throw new RuntimeException("Error processing commit: " + commit.id(), e);
                        }
                    }))
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error computing PMI scores in parallel", e);
        }

        if (jointCounts.isEmpty()) return List.of();

        var scores = new HashMap<ProjectFile, Double>();
        for (var entry : jointCounts.entrySet()) {
            var seed = entry.getKey().src();
            var target = entry.getKey().dst();
            var joint = entry.getValue();

            final var countSeed = requireNonNull(fileCounts.get(seed));
            final int countTarget = requireNonNull(fileCounts.get(target));
            if (countSeed == 0 || countTarget == 0 || joint == 0) continue;

            double pmi = Math.log((double) joint * totalCommits / ((double) countSeed * countTarget)) / Math.log(2);

            double weight = seedWeights.getOrDefault(seed, 0.0);
            if (weight == 0.0) continue;

            scores.merge(target, weight * pmi, Double::sum);
        }

        return scores.entrySet().stream()
                .map(e -> new IAnalyzer.FileRelevance(e.getKey(), e.getValue()))
                .sorted((a, b) ->
                        reversed ? Double.compare(a.score(), b.score()) : Double.compare(b.score(), a.score()))
                .limit(k)
                .toList();
    }
}
