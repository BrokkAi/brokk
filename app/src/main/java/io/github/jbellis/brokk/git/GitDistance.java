package io.github.jbellis.brokk.git;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Period;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import org.eclipse.jgit.api.errors.GitAPIException;

/** Provides the logic to perform a Git-centric distance calculations for given type declarations. */
public final class GitDistance {

    /** Represents an edge between two CodeUnits in the co-occurrence graph. */
    public record CodeUnitEdge(CodeUnit src, CodeUnit dst) {}

    /**
     * Convenience wrapper around {@link #getRecentCommits(GitRepo, int)} that chooses a dynamic limit equal to 30% of
     * the total commit count on the current branch (rounded up, with a minimum of 250).
     *
     * <p>This percentage-based cap scales with repository size, ensuring we neither ignore too much history in large
     * projects nor overwhelm small ones with an arbitrary fixed number.
     *
     * @param repo the Git repository wrapper
     * @return commits that are at most 90 days old, or — if fewer than the 30% cap — additional older commits until
     *     that cap is reached
     */
    private static List<CommitInfo> getRecentCommits(GitRepo repo) throws GitAPIException {
        final var currentBranch = repo.getCurrentBranch();
        final var commits = repo.listCommitsDetailed(currentBranch);
        if (commits.isEmpty()) {
            return commits;
        }

        final int dynamicLimit = Math.max(250, (int) Math.ceil(commits.size() * 0.30));
        // Re-use the 90-day trimming logic in the overload
        return getRecentCommits(repo, dynamicLimit);
    }

    /**
     * Keep every commit whose author/commit date is within the last 90 days. If we still have fewer than the desired
     * limit add the next-oldest commits until we hit the limit or exhaust the list.
     *
     * @param repo the Git repository wrapper.
     * @param resultLimit the maximum number of results if more commits are at least 90 days old.
     * @return commits from the current branch that are either at most 90 days old, or are enough to make up a result of
     *     at most `resultLimit`.
     */
    private static List<CommitInfo> getRecentCommits(GitRepo repo, int resultLimit) throws GitAPIException {
        final var currentBranch = repo.getCurrentBranch();
        final var commits = repo.listCommitsDetailed(currentBranch);
        if (commits.isEmpty()) {
            return commits;
        }

        final var ninetyDaysAgo = Instant.now().minus(Period.ofDays(90));
        final var recent = commits.stream()
                .takeWhile(ci -> !ci.date().isBefore(ninetyDaysAgo))
                .toList();

        if (recent.size() >= resultLimit) {
            // Already have enough recent commits; trim to at most `resultLimit`.
            return recent.subList(0, resultLimit);
        }

        // Not enough recent commits; include older ones until the total reaches the cap.
        int needed = Math.min(resultLimit, commits.size());
        return commits.subList(0, needed);
    }

    public static List<IAnalyzer.CodeUnitRelevance> getPagerank(
            IAnalyzer analyzer, Path projectRoot, Map<String, Double> seedClassWeights, int k, boolean reversed)
            throws GitAPIException {
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a directory: " + projectRoot);
        } else if (!GitRepo.hasGitRepo(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a Git repository: " + projectRoot);
        }

        try (final var repo = new GitRepo(projectRoot)) {
            final var seedCodeUnitWeights = new HashMap<CodeUnit, Double>();

            seedClassWeights.forEach((fullName, weight) -> analyzer.getDefinition(fullName).stream()
                    .filter(CodeUnit::isClass)
                    .forEach(cu -> seedCodeUnitWeights.put(cu, weight)));

            return getPagerank(repo, analyzer, seedCodeUnitWeights, k, reversed);
        }
    }

    private static List<IAnalyzer.CodeUnitRelevance> getPagerank(
            GitRepo repo, IAnalyzer analyzer, Map<CodeUnit, Double> seedCodeUnitWeights, int k, boolean reversed)
            throws GitAPIException {
        // Build mapping from ProjectFile to CodeUnits covering the entire project
        final var fileToCodeUnits = new HashMap<ProjectFile, Set<CodeUnit>>();
        analyzer.getAllDeclarations().forEach(cu -> fileToCodeUnits
                .computeIfAbsent(cu.source(), f -> new HashSet<>())
                .add(cu));

        // Build weighted adjacency graph of CodeUnit co-occurrences
        final var allCodeUnits = new HashSet<>(analyzer.getAllDeclarations());

        // Get all commits and build co-occurrence graph in parallel
        final var commits = getRecentCommits(repo);
        final var concurrentEdgeWeights = new ConcurrentHashMap<CodeUnitEdge, Integer>();

        // Create a custom ForkJoinPool to avoid the global common pool
        final var result = new ArrayList<IAnalyzer.CodeUnitRelevance>();
        try (var pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors()))) {
            try {
                pool.submit(() -> commits.parallelStream().forEach(commit -> {
                            try {
                                final var changedFiles = repo.listFilesChangedInCommit(commit.id());
                                final var codeUnitsInCommit = new HashSet<CodeUnit>();

                                // Find all CodeUnits that were changed in this commit
                                for (var file : changedFiles) {
                                    final var codeUnitsInFile = fileToCodeUnits.get(file);
                                    if (codeUnitsInFile != null) {
                                        codeUnitsInCommit.addAll(codeUnitsInFile);
                                    }
                                }

                                // Add edges between all pairs of CodeUnits in this commit
                                for (var from : codeUnitsInCommit) {
                                    for (var to : codeUnitsInCommit) {
                                        if (!from.equals(to)) {
                                            final var edgeKey = new CodeUnitEdge(from, to);
                                            concurrentEdgeWeights.merge(edgeKey, 1, Integer::sum);
                                        }
                                    }
                                }
                            } catch (GitAPIException e) {
                                throw new RuntimeException("Error processing commit: " + commit.id(), e);
                            }
                        }))
                        .get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Error processing commits in parallel", e);
            }

            // Transfer results to the original map
            // CodeUnit edge -> weight
            final var edgeWeights = new HashMap<>(concurrentEdgeWeights);

            // Run PageRank algorithm
            final var damping = 0.85;
            final var maxIterations = 50;
            final var epsilon = 1e-6;

            // Initialize PageRank scores
            final var scores = new HashMap<String, Double>();
            /* newScores will be created inside each iteration using a thread-safe map */
            final var numNodes = allCodeUnits.size();
            final var initialScore = 1.0 / numNodes;

            for (var codeUnit : allCodeUnits) {
                var seedWeight = seedCodeUnitWeights.getOrDefault(codeUnit, 0.0);
                scores.put(codeUnit.fqName(), initialScore + seedWeight);
            }

            // Compute outgoing edge counts for each node
            final var outgoingWeights = new HashMap<String, Integer>();
            for (var entry : edgeWeights.entrySet()) {
                final var fromNode = entry.getKey().src().fqName();
                outgoingWeights.merge(fromNode, entry.getValue(), Integer::sum);
            }

            // Iteratively update PageRank scores, computing each iteration in parallel
            for (int iter = 0; iter < maxIterations; iter++) {
                final var concurrentNewScores = new ConcurrentHashMap<String, Double>();

                try {
                    pool.submit(() -> allCodeUnits.parallelStream().forEach(codeUnit -> {
                                var fqName = codeUnit.fqName();
                                double newScore = (1.0 - damping) / numNodes;

                                // Add contributions from incoming edges
                                for (var entry : edgeWeights.entrySet()) {
                                    var edge = entry.getKey();
                                    if (edge.dst().fqName().equals(fqName)) {
                                        var fromNode = edge.src().fqName();
                                        var edgeWeight = entry.getValue();
                                        var fromOutgoingWeight = outgoingWeights.getOrDefault(fromNode, 1);
                                        newScore += damping
                                                * requireNonNull(scores.get(fromNode))
                                                * edgeWeight
                                                / fromOutgoingWeight;
                                    }
                                }

                                // Seed contribution
                                newScore += seedCodeUnitWeights.getOrDefault(codeUnit, 0.0);

                                concurrentNewScores.put(fqName, newScore);
                            }))
                            .get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException("Error computing PageRank in parallel", e);
                }

                var maxDiff = concurrentNewScores.entrySet().stream()
                        .mapToDouble(e -> Math.abs(e.getValue() - requireNonNull(scores.get(e.getKey()))))
                        .max()
                        .orElse(0.0);

                scores.clear();
                scores.putAll(concurrentNewScores);

                if (maxDiff < epsilon) {
                    break;
                }
            }

            // Create results and sort by score
            result.addAll(allCodeUnits.stream()
                    .map(codeUnit ->
                            new IAnalyzer.CodeUnitRelevance(codeUnit, requireNonNull(scores.get(codeUnit.fqName()))))
                    .sorted((a, b) ->
                            reversed ? Double.compare(a.score(), b.score()) : Double.compare(b.score(), a.score()))
                    .limit(k)
                    .toList());
        }
        return result;
    }

    /**
     * Computes co-occurrence scores for CodeUnits based on how often they are changed in the same commit with any of
     * the seed CodeUnits. Commits are down-weighted by the inverse of their touched-file count to dampen noisy
     * formatting commits.
     *
     * <p>weight(X,Y) = sum( 1 / |files(commit)| ) for commits containing both X and Y
     *
     * @param analyzer Analyzer used to map files to CodeUnits
     * @param projectRoot Root of the (git) project
     * @param seedClassWeights Fully-qualified seed class names and their weights
     * @param k Number of results to return
     * @param reversed If true smallest scores first, otherwise largest first
     */
    public static List<IAnalyzer.CodeUnitRelevance> getInverseFileCountCooccurrence(
            IAnalyzer analyzer, Path projectRoot, Map<String, Double> seedClassWeights, int k, boolean reversed)
            throws GitAPIException {
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a directory: " + projectRoot);
        } else if (!GitRepo.hasGitRepo(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a Git repository: " + projectRoot);
        }

        try (var repo = new GitRepo(projectRoot)) {
            // Map the seed FQNs to CodeUnits
            var seedCodeUnitWeights = new HashMap<CodeUnit, Double>();
            seedClassWeights.forEach((fqName, weight) -> analyzer.getDefinition(fqName).stream()
                    .filter(CodeUnit::isClass)
                    .forEach(cu -> seedCodeUnitWeights.put(cu, weight)));
            if (seedCodeUnitWeights.isEmpty()) {
                return List.of();
            }

            // Build a complete mapping ProjectFile -> CodeUnits once up-front
            var fileToCodeUnits = new HashMap<ProjectFile, Set<CodeUnit>>();
            analyzer.getAllDeclarations().forEach(cu -> fileToCodeUnits
                    .computeIfAbsent(cu.source(), f -> new HashSet<>())
                    .add(cu));

            return computeCooccurrenceScores(repo, seedCodeUnitWeights, fileToCodeUnits, k, reversed);
        }
    }

    /**
     * Internal helper that walks commits in parallel, aggregates the inverse file-count weighted co-occurrence
     * contributions, and returns the top-k CodeUnits.
     */
    private static List<IAnalyzer.CodeUnitRelevance> computeCooccurrenceScores(
            GitRepo repo,
            Map<CodeUnit, Double> seedCodeUnitWeights,
            Map<ProjectFile, Set<CodeUnit>> fileToCodeUnits,
            int k,
            boolean reversed)
            throws GitAPIException {
        var commits = getRecentCommits(repo);

        var scores = new ConcurrentHashMap<CodeUnit, Double>();

        try (var pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors()))) {
            pool.submit(() -> commits.parallelStream().forEach(commit -> {
                        try {
                            var changedFiles = repo.listFilesChangedInCommit(commit.id());
                            if (changedFiles.isEmpty()) return;

                            var codeUnitsInCommit = new HashSet<CodeUnit>();
                            for (var file : changedFiles) {
                                var units = fileToCodeUnits.get(file);
                                if (units != null) codeUnitsInCommit.addAll(units);
                            }
                            if (codeUnitsInCommit.isEmpty()) return;

                            var seedsInCommit = codeUnitsInCommit.stream()
                                    .filter(seedCodeUnitWeights::containsKey)
                                    .toList();
                            if (seedsInCommit.isEmpty()) return;

                            double commitWeight = 1.0 / (double) changedFiles.size();

                            for (var seedCU : seedsInCommit) {
                                var seedWeight = requireNonNull(seedCodeUnitWeights.get(seedCU));
                                double contribution = commitWeight * seedWeight;
                                for (var cu : codeUnitsInCommit) {
                                    // include seed unit as well to rank it alongside co-occurring units
                                    scores.merge(cu, contribution, Double::sum);
                                }
                            }
                        } catch (GitAPIException e) {
                            throw new RuntimeException("Error processing commit: " + commit.id(), e);
                        }
                    }))
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error computing co-occurrence scores in parallel", e);
        }

        return scores.entrySet().stream()
                .map(e -> new IAnalyzer.CodeUnitRelevance(e.getKey(), e.getValue()))
                .sorted((a, b) ->
                        reversed ? Double.compare(a.score(), b.score()) : Double.compare(b.score(), a.score()))
                .limit(k)
                .toList();
    }

    /**
     * Point-wise Mutual Information (PMI) distance.
     *
     * <p>p(X,Y) = |C(X) ∩ C(Y)| / |Commits| p(X) = |C(X)| / |Commits| PMI = log2( p(X,Y) / (p(X)·p(Y)) )
     *
     * @see GitDistance#getInverseFileCountCooccurrence
     */
    public static List<IAnalyzer.CodeUnitRelevance> getPMI(
            IAnalyzer analyzer, Path projectRoot, Map<String, Double> seedClassWeights, int k, boolean reversed)
            throws GitAPIException {
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a directory: " + projectRoot);
        } else if (!GitRepo.hasGitRepo(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a Git repository: " + projectRoot);
        }

        if (seedClassWeights.isEmpty()) return List.of();

        try (var repo = new GitRepo(projectRoot)) {
            // Map seed FQNs to CodeUnits
            var seedCodeUnitWeights = new HashMap<CodeUnit, Double>();
            seedClassWeights.forEach((fqName, weight) -> analyzer.getDefinition(fqName).stream()
                    .filter(CodeUnit::isClass)
                    .forEach(cu -> seedCodeUnitWeights.put(cu, weight)));
            if (seedCodeUnitWeights.isEmpty()) return List.of();

            // Build file → CodeUnits map once
            var fileToCodeUnits = new HashMap<ProjectFile, Set<CodeUnit>>();
            analyzer.getAllDeclarations().forEach(cu -> fileToCodeUnits
                    .computeIfAbsent(cu.source(), f -> new HashSet<>())
                    .add(cu));

            return computePmiScores(repo, seedCodeUnitWeights, fileToCodeUnits, k, reversed);
        }
    }

    private static List<IAnalyzer.CodeUnitRelevance> computePmiScores(
            GitRepo repo,
            Map<CodeUnit, Double> seedCodeUnitWeights,
            Map<ProjectFile, Set<CodeUnit>> fileToCodeUnits,
            int k,
            boolean reversed)
            throws GitAPIException {
        var commits = getRecentCommits(repo);
        var totalCommits = commits.size();
        if (totalCommits == 0) return List.of();

        var unitCounts = new ConcurrentHashMap<CodeUnit, Integer>();
        var jointCounts = new ConcurrentHashMap<CodeUnitEdge, Integer>();

        try (var pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors()))) {
            pool.submit(() -> commits.parallelStream().forEach(commit -> {
                        try {
                            var changedFiles = repo.listFilesChangedInCommit(commit.id());
                            if (changedFiles.isEmpty()) return;

                            var unitsInCommit = new HashSet<CodeUnit>();
                            for (var file : changedFiles) {
                                var cuSet = fileToCodeUnits.get(file);
                                if (cuSet != null) unitsInCommit.addAll(cuSet);
                            }
                            if (unitsInCommit.isEmpty()) return;

                            // individual counts
                            for (var cu : unitsInCommit) {
                                unitCounts.merge(cu, 1, Integer::sum);
                            }

                            // joint counts with seeds
                            var seedsInCommit = unitsInCommit.stream()
                                    .filter(seedCodeUnitWeights::containsKey)
                                    .toList();
                            if (seedsInCommit.isEmpty()) return;

                            for (var seed : seedsInCommit) {
                                for (var cu : unitsInCommit) {
                                    jointCounts.merge(new CodeUnitEdge(seed, cu), 1, Integer::sum);
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

        var scores = new HashMap<CodeUnit, Double>();
        for (var entry : jointCounts.entrySet()) {
            var seed = entry.getKey().src();
            var target = entry.getKey().dst();
            var joint = entry.getValue();

            final var countSeed = requireNonNull(unitCounts.get(seed));
            final int countTarget = requireNonNull(unitCounts.get(target));
            if (countSeed == 0 || countTarget == 0 || joint == 0) continue;

            double pmi = Math.log((double) joint * totalCommits / ((double) countSeed * countTarget)) / Math.log(2);

            double weight = seedCodeUnitWeights.getOrDefault(seed, 0.0);
            if (weight == 0.0) continue;

            scores.merge(target, weight * pmi, Double::sum);
        }

        return scores.entrySet().stream()
                .map(e -> new IAnalyzer.CodeUnitRelevance(e.getKey(), e.getValue()))
                .sorted((a, b) ->
                        reversed ? Double.compare(a.score(), b.score()) : Double.compare(b.score(), a.score()))
                .limit(k)
                .toList();
    }
}
