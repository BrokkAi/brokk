package io.github.jbellis.brokk.git;

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
     * Given seed files and weights, return related files from the most recent COMMITS_TO_PROCESS commits ranked by:
     *   score(y) = sum_over_seeds [ weight(seed) * P(y|seed) * idf(y) ]
     *
     * where:
     *   - P(y|seed) ≈ (sum over baseline commits containing {seed & y} of 1/numFilesChanged(commit))
     *                 / (number of baseline commits containing seed)
     *   - idf(y) = log( N / count(y) ), N = number of baseline commits in window,
     *              count(y) = number of baseline commits where y changed
     *
     * Notes:
     *   - 'reversed' flips sort order only.
     */
    public static List<IAnalyzer.FileRelevance> getRelatedFiles(
            GitRepo repo, Map<ProjectFile, Double> seedWeights, int k, boolean reversed) throws InterruptedException {

        if (seedWeights.isEmpty()) return List.of();

        try {
            return computeConditionalScores(repo, seedWeights, k, reversed);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<IAnalyzer.FileRelevance> computeConditionalScores(
            GitRepo repo, Map<ProjectFile, Double> seedWeights, int k, boolean reversed)
            throws GitAPIException, InterruptedException {

        // Baseline universe: recent commits on the current branch
        var baselineCommits = repo.listCommitsDetailed(repo.getCurrentBranch(), COMMITS_TO_PROCESS);
        final int N = baselineCommits.size();
        if (N == 0) return List.of();

        // Canonicalize paths within this baseline window
        var canonicalizer = repo.buildCanonicalizer(baselineCommits);

        // Unweighted doc frequency per file: in how many baseline commits did file appear?
        var fileDocFreq = new ConcurrentHashMap<ProjectFile, Integer>();

        // For conditional numerator: joint mass across (seed -> target),
        // where each commit contributes 1/numFilesChanged to *every* pair in that commit that involves a seed.
        var jointMass = new ConcurrentHashMap<FileEdge, Double>();

        // For conditional denominator: how many baseline commits contain the seed (unweighted)
        var seedCommitCount = new ConcurrentHashMap<ProjectFile, Integer>();

        try (var pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
            pool.submit(() -> baselineCommits.parallelStream().forEach(commit -> {
                        List<IGitRepo.ModifiedFile> changed;
                        try {
                            changed = repo.listFilesChangedInCommit(commit.id());
                        } catch (GitAPIException e) {
                            throw new RuntimeException("Error processing commit: " + commit.id(), e);
                        }
                        if (changed.isEmpty()) return;

                        // Canonicalize "as-of-commit" paths to current names; drop non-existing
                        var changedFiles = changed.stream()
                                .map(IGitRepo.ModifiedFile::file)
                                .map(pf -> canonicalizer.canonicalize(commit.id(), pf))
                                .distinct()
                                .filter(ProjectFile::exists)
                                .toList();

                        if (changedFiles.isEmpty()) return;

                        // Unweighted docfreq for IDF
                        for (var f : changedFiles) {
                            fileDocFreq.merge(f, 1, Integer::sum);
                        }

                        // Seeds present in this commit
                        var seedsInCommit = changedFiles.stream()
                                .filter(seedWeights::containsKey)
                                .collect(Collectors.toSet());
                        if (seedsInCommit.isEmpty()) return;

                        // Denominator for P(y|seed): count baseline commits containing the seed (unweighted)
                        for (var seed : seedsInCommit) {
                            seedCommitCount.merge(seed, 1, Integer::sum);
                        }

                        // Size-aware contribution: each commit contributes 1/|Δ| to any (seed, target) it contains
                        final double commitPairMass = 1.0 / changedFiles.size();

                        for (var seed : seedsInCommit) {
                            for (var target : changedFiles) {
                                if (target.equals(seed)) continue;
                                jointMass.merge(new FileEdge(seed, target), commitPairMass, Double::sum);
                            }
                        }
                    }))
                    .get();
        } catch (ExecutionException e) {
            throw new RuntimeException("Error computing conditional scores in parallel", e);
        }

        if (jointMass.isEmpty()) return List.of();

        // Aggregate: score(y) = sum_seeds weight(seed) * P(y|seed) * idf(y)
        var scores = new HashMap<ProjectFile, Double>();
        for (var entry : jointMass.entrySet()) {
            var seed = entry.getKey().src();
            var target = entry.getKey().dst();
            double joint = entry.getValue();

            int seedsDenom = seedCommitCount.getOrDefault(seed, 0);
            if (seedsDenom == 0) continue;

            // Conditional probability estimate with size-aware numerator and unweighted denominator
            double p_y_given_seed = joint / seedsDenom;

            // IDF using unweighted doc frequency (avoid divide-by-zero via guard)
            int dfTarget = Math.max(1, fileDocFreq.getOrDefault(target, 0));
            double idfTarget = Math.log1p((double) N / (double) dfTarget);

            double wSeed = seedWeights.getOrDefault(seed, 0.0);
            if (wSeed == 0.0) continue;

            double contribution = wSeed * p_y_given_seed * idfTarget;
            if (Double.isFinite(contribution) && contribution != 0.0) {
                scores.merge(target, contribution, Double::sum);
            }
        }

        // Build and sort results
        return scores.entrySet().stream()
                .map(e -> new IAnalyzer.FileRelevance(e.getKey(), e.getValue()))
                .sorted((a, b) ->
                        reversed ? Double.compare(a.score(), b.score()) : Double.compare(b.score(), a.score()))
                .limit(k)
                .toList();
    }

    @VisibleForTesting
    public static List<IAnalyzer.FileRelevance> getMostImportantFilesScored(GitRepo repo, int k)
            throws GitAPIException, InterruptedException {
        var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), COMMITS_TO_PROCESS);
        var scores = computeImportanceScores(repo, commits);
        logger.trace("Computed importance scores for getMostImportantFilesScored: {}", scores);

        return scores.entrySet().stream()
                .map(e -> new IAnalyzer.FileRelevance(e.getKey(), e.getValue()))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(k)
                .toList();
    }

    public static List<ProjectFile> getMostImportantFiles(GitRepo repo, int k)
            throws GitAPIException, InterruptedException {
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
    public static List<ProjectFile> sortByImportance(Collection<ProjectFile> files, IGitRepo repo)
            throws InterruptedException {
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
            throws GitAPIException, InterruptedException {
        if (commits.isEmpty()) {
            return Map.of();
        }

        var t_latest = commits.getFirst().date();
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

                        var t_c = commit.date();
                        var age = Duration.between(t_c, t_latest);
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

    public static void main(String[] args) throws GitAPIException, InterruptedException {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: GitDistance <path-to-git-repo>");
            System.exit(1);
        }

        var repoPath = Path.of(args[0]);
        logger.info("Analyzing most important files for repository: {}", repoPath);

        var repo = new GitRepo(repoPath);
        var important = getMostImportantFilesScored(repo, 20);
        for (IAnalyzer.FileRelevance fr : important) {
            var related = getRelatedFiles(repo, Map.of(fr.file(), 1.0), 5, false);
            System.out.printf("%s\t%.6f%n", fr.file().getFileName(), fr.score());
            for (IAnalyzer.FileRelevance r : related) {
                System.out.printf("\t%s\t%.6f%n", r.file().getFileName(), r.score());
            }
        }
    }
}
