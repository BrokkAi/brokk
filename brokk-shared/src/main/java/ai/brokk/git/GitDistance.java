package ai.brokk.git;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
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

/** Provides the logic to perform Git-centric distance calculations for SearchTools and ranking flows. */
public final class GitDistance {
    private static final Logger logger = LogManager.getLogger(GitDistance.class);
    private static final int COMMITS_TO_PROCESS = 1_000;
    private static final int LARGE_SEED_THRESHOLD = 100;

    /** Represents an edge between two files in the co-occurrence graph. */
    public record FileEdge(ProjectFile src, ProjectFile dst) {}

    private GitDistance() {}

    /**
     * Given seed files and weights, return related files from the most recent COMMITS_TO_PROCESS commits.
     */
    public static List<IAnalyzer.FileRelevance> getRelatedFiles(
            IGitRepo repo, Map<ProjectFile, Double> seedWeights, int k) throws InterruptedException {
        if (seedWeights.isEmpty()) {
            return List.of();
        }

        boolean anyTracked = seedWeights.keySet().stream().anyMatch(pf -> repo.isTracked(pf.getRelPath()));
        if (!anyTracked) {
            return List.of();
        }

        try {
            return computeConditionalScores(repo, seedWeights, k);
        } catch (UnsupportedOperationException e) {
            return List.of();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<IAnalyzer.FileRelevance> computeConditionalScores(
            IGitRepo repo, Map<ProjectFile, Double> seedWeights, int k)
            throws GitAPIException, InterruptedException {
        var baselineCommits = repo.listCommitsDetailed(repo.getCurrentBranch(), COMMITS_TO_PROCESS);
        final int n = baselineCommits.size();
        if (n == 0) {
            return List.of();
        }

        if (n >= COMMITS_TO_PROCESS || seedWeights.size() > LARGE_SEED_THRESHOLD) {
            logger.debug("GitDistance processing large dataset: commits={}, seeds={}", n, seedWeights.size());
        }

        var canonicalizer = repo.buildCanonicalizer(baselineCommits);
        var fileDocFreq = new ConcurrentHashMap<ProjectFile, Integer>();
        var jointMass = new ConcurrentHashMap<FileEdge, Double>();
        var seedCommitCount = new ConcurrentHashMap<ProjectFile, Integer>();

        try (var pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
            pool.submit(() -> baselineCommits.parallelStream().forEach(commit -> {
                        List<IGitRepo.ModifiedFile> changed;
                        try {
                            changed = repo.listFilesChangedInCommit(commit.id());
                        } catch (GitAPIException e) {
                            throw new RuntimeException("Error processing commit: " + commit.id(), e);
                        }
                        if (changed.isEmpty()) {
                            return;
                        }

                        var changedFiles = changed.stream()
                                .map(IGitRepo.ModifiedFile::file)
                                .map(pf -> canonicalizer.canonicalize(commit.id(), pf))
                                .distinct()
                                .filter(ProjectFile::exists)
                                .toList();

                        if (changedFiles.isEmpty()) {
                            return;
                        }

                        for (var f : changedFiles) {
                            fileDocFreq.merge(f, 1, Integer::sum);
                        }

                        var seedsInCommit = changedFiles.stream()
                                .filter(seedWeights::containsKey)
                                .collect(Collectors.toSet());
                        if (seedsInCommit.isEmpty()) {
                            return;
                        }

                        for (var seed : seedsInCommit) {
                            seedCommitCount.merge(seed, 1, Integer::sum);
                        }

                        final double commitPairMass = 1.0 / changedFiles.size();
                        for (var seed : seedsInCommit) {
                            for (var target : changedFiles) {
                                if (seedWeights.containsKey(target)) {
                                    continue;
                                }
                                jointMass.merge(new FileEdge(seed, target), commitPairMass, Double::sum);
                            }
                        }
                    }))
                    .get();
        } catch (ExecutionException e) {
            throw new RuntimeException("Error computing conditional scores in parallel", e);
        }

        if (jointMass.isEmpty()) {
            return List.of();
        }

        var scores = new HashMap<ProjectFile, Double>();
        for (var entry : jointMass.entrySet()) {
            var seed = entry.getKey().src();
            var target = entry.getKey().dst();
            double joint = entry.getValue();

            int seedsDenom = seedCommitCount.getOrDefault(seed, 0);
            if (seedsDenom == 0) {
                continue;
            }

            double pYGivenSeed = joint / seedsDenom;
            int dfTarget = Math.max(1, fileDocFreq.getOrDefault(target, 0));
            double idfTarget = Math.log1p((double) n / (double) dfTarget);
            double wSeed = seedWeights.getOrDefault(seed, 0.0);
            if (wSeed == 0.0) {
                continue;
            }

            double contribution = wSeed * pYGivenSeed * idfTarget;
            if (Double.isFinite(contribution) && contribution != 0.0) {
                scores.merge(target, contribution, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .map(e -> new IAnalyzer.FileRelevance(e.getKey(), e.getValue()))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(k)
                .toList();
    }

    @VisibleForTesting
    public static List<IAnalyzer.FileRelevance> getMostImportantFilesScored(IGitRepo repo, int k)
            throws GitAPIException, InterruptedException {
        try {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), COMMITS_TO_PROCESS);
            var scores = computeImportanceScores(repo, commits);
            logger.trace("Computed importance scores for getMostImportantFilesScored: {}", scores);

            return scores.entrySet().stream()
                    .map(e -> new IAnalyzer.FileRelevance(e.getKey(), e.getValue()))
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .limit(k)
                    .toList();
        } catch (UnsupportedOperationException e) {
            return List.of();
        }
    }

    public static List<ProjectFile> getMostImportantFiles(IGitRepo repo, int k)
            throws GitAPIException, InterruptedException {
        return getMostImportantFilesScored(repo, k).stream()
                .map(IAnalyzer.FileRelevance::file)
                .toList();
    }

    /**
     * Sorts a collection of files by their importance using Git history analysis.
     */
    public static List<ProjectFile> sortByImportance(Collection<ProjectFile> files, IGitRepo repo)
            throws InterruptedException {
        Map<ProjectFile, Double> scores;
        try {
            var commits = repo.getFileHistories(files, Integer.MAX_VALUE);
            scores = computeImportanceScores(repo, commits);
            logger.trace("Computed importance scores for sortByImportance: {}", scores);
        } catch (UnsupportedOperationException e) {
            return List.copyOf(files);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }

        return files.stream()
                .sorted((a, b) -> {
                    double sb = scores.getOrDefault(b, Double.NEGATIVE_INFINITY);
                    double sa = scores.getOrDefault(a, Double.NEGATIVE_INFINITY);
                    return Double.compare(sb, sa);
                })
                .toList();
    }

    private static Map<ProjectFile, Double> computeImportanceScores(IGitRepo repo, List<CommitInfo> commits)
            throws GitAPIException, InterruptedException {
        if (commits.isEmpty()) {
            return Map.of();
        }

        var tLatest = commits.getFirst().date();
        var halfLife = Duration.ofDays(30);
        double halfLifeMillis = halfLife.toMillis();
        var scores = new ConcurrentHashMap<ProjectFile, Double>();

        try (var pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
            pool.submit(() -> commits.parallelStream().forEach(commit -> {
                        List<IGitRepo.ModifiedFile> changedModifiedFiles;
                        try {
                            changedModifiedFiles = repo.listFilesChangedInCommit(commit.id());
                        } catch (GitAPIException e) {
                            throw new RuntimeException("Error processing commit: " + commit.id(), e);
                        }
                        if (changedModifiedFiles.isEmpty()) {
                            return;
                        }

                        var tC = commit.date();
                        var age = Duration.between(tC, tLatest);
                        double ageMillis = age.toMillis();
                        double weight = Math.pow(2, -(ageMillis / halfLifeMillis));

                        for (var modifiedFile : changedModifiedFiles) {
                            scores.merge(modifiedFile.file(), weight, Double::sum);
                        }
                    }))
                    .get();
        } catch (ExecutionException e) {
            throw new RuntimeException("Error computing file importance scores in parallel", e);
        }

        return scores;
    }
}
