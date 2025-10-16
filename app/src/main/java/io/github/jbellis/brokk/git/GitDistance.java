package io.github.jbellis.brokk.git;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.VisibleForTesting;

/** Provides the logic to perform a Git-centric distance calculations for given type declarations. */
public final class GitDistance {
    private static final Logger logger = LogManager.getLogger(GitDistance.class);
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

    @VisibleForTesting
    public static List<IAnalyzer.FileRelevance> getMostImportantFilesScored(GitRepo repo, int k)
            throws GitAPIException {
        var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), COMMITS_TO_PROCESS);
        var scores = computeImportanceScores(repo, commits);
        logger.trace("Computed importance scores for getMostImportantFilesScored: {}", scores);

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
    public static List<ProjectFile> sortByImportance(Collection<ProjectFile> files, IGitRepo repo) {
        if (!(repo instanceof GitRepo gr)) {
            return List.copyOf(files);
        }

        Map<ProjectFile, Double> scores;
        try {
            var commits = ((GitRepo) repo).getFileHistories(files, Integer.MAX_VALUE);
            scores = computeImportanceScores(gr, commits);
            logger.trace("Computed importance scores for sortByImportance: {}", scores);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }

        // Score-less files sort to the end
        return files.stream()
                .sorted((a, b) -> {
                    double sb = scores.getOrDefault(b, Double.NEGATIVE_INFINITY);
                    double sa = scores.getOrDefault(a, Double.NEGATIVE_INFINITY);
                    return Double.compare(sb, sa);
                })
                .toList();
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
     */
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
                        List<ProjectFile> changedFiles;
                        try {
                            changedFiles = repo.listFilesChangedInCommit(commit.id());
                        } catch (GitAPIException e) {
                            throw new RuntimeException("Error processing commit: " + commit.id(), e);
                        }
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
                    }))
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error computing file importance scores in parallel", e);
        }

        return scores;
    }

    private static List<IAnalyzer.FileRelevance> computePmiScores(
            GitRepo repo, Map<ProjectFile, Double> seedWeights, int k, boolean reversed) throws GitAPIException {
        var commits = repo.getFileHistories(seedWeights.keySet(), COMMITS_TO_PROCESS);
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

    public static void main(String[] args) throws GitAPIException {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: GitDistance <path-to-git-repo>");
            System.exit(1);
        }

        var repoPath = Path.of(args[0]);
        logger.info("Analyzing most important files for repository: {}", repoPath);

        var repo = new GitRepo(repoPath);
        var results = getMostImportantFilesScored(repo, 20);
        results.forEach(fr -> System.out.printf("%s\t%.6f%n", fr.file().getFileName(), fr.score()));
    }
}
