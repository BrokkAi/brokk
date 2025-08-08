package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ExecutionException;

import static java.util.Objects.requireNonNull;

/**
 * Provides the logic to perform a Git-centric distance calculations for given type declarations.
 */
public final class GitDistance {

    /**
     * Represents an edge between two CodeUnits in the co-occurrence graph.
     */
    public record CodeUnitEdge(CodeUnit src, CodeUnit dst) {
    }

    public static List<IAnalyzer.CodeUnitRelevance> getPagerank(
            IAnalyzer analyzer,
            Path projectRoot,
            Map<String, Double> seedClassWeights,
            int k,
            boolean reversed
    ) throws GitAPIException {
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a directory: " + projectRoot);
        } else if (!GitRepo.hasGitRepo(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a Git repository: " + projectRoot);
        }

        try (final var repo = new GitRepo(projectRoot)) {
            final var seedCodeUnitWeights = new HashMap<CodeUnit, Double>();

            seedClassWeights.forEach((fullName, weight) ->
                    analyzer.getDefinition(fullName).stream()
                            .filter(CodeUnit::isClass)
                            .forEach(cu -> seedCodeUnitWeights.put(cu, weight))
            );

            return getPagerank(repo, analyzer, seedCodeUnitWeights, k, reversed);
        }
    }

    private static List<IAnalyzer.CodeUnitRelevance> getPagerank(
            GitRepo repo,
            IAnalyzer analyzer,
            Map<CodeUnit, Double> seedCodeUnitWeights,
            int k,
            boolean reversed
    ) throws GitAPIException {
        final var currentBranch = repo.getCurrentBranch();

        // Build mapping from ProjectFile to CodeUnits covering the entire project
        final var fileToCodeUnits = new HashMap<ProjectFile, Set<CodeUnit>>();
        analyzer.getAllDeclarations()
                .forEach(cu -> fileToCodeUnits
                        .computeIfAbsent(cu.source(), f -> new HashSet<>())
                        .add(cu));

        // Build weighted adjacency graph of CodeUnit co-occurrences
        final var allCodeUnits = new HashSet<>(analyzer.getAllDeclarations());

        // Get all commits and build co-occurrence graph in parallel
        final var commits = repo.listCommitsDetailed(currentBranch);
        final var concurrentEdgeWeights = new ConcurrentHashMap<CodeUnitEdge, Integer>();

        // Create a custom ForkJoinPool to avoid the global common pool
        final var pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        final var result = new ArrayList<IAnalyzer.CodeUnitRelevance>();
        try {
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
                })).get();
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
                    pool.submit(() ->
                            allCodeUnits.parallelStream().forEach(codeUnit -> {
                                var fqName = codeUnit.fqName();
                                double newScore = (1.0 - damping) / numNodes;

                                // Add contributions from incoming edges
                                for (var entry : edgeWeights.entrySet()) {
                                    var edge = entry.getKey();
                                    if (edge.dst().fqName().equals(fqName)) {
                                        var fromNode = edge.src().fqName();
                                        var edgeWeight = entry.getValue();
                                        var fromOutgoingWeight = outgoingWeights.getOrDefault(fromNode, 1);
                                        newScore += damping * requireNonNull(scores.get(fromNode)) * edgeWeight / fromOutgoingWeight;
                                    }
                                }

                                // Seed contribution
                                newScore += seedCodeUnitWeights.getOrDefault(codeUnit, 0.0);

                                concurrentNewScores.put(fqName, newScore);
                            })
                    ).get();
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
                    .map(codeUnit -> new IAnalyzer.CodeUnitRelevance(codeUnit, requireNonNull(scores.get(codeUnit.fqName()))))
                    .sorted((a, b) -> reversed ?
                            Double.compare(a.score(), b.score()) :
                            Double.compare(b.score(), a.score()))
                    .limit(k).toList());
        } finally {
            pool.shutdown();
        }
        return result;
    }

    /**
     * Computes co-occurrence scores for CodeUnits based on how often they are changed
     * in the same commit with any of the seed CodeUnits.  Commits are down-weighted
     * by the inverse of their touched-file count to dampen noisy formatting commits.
     *
     * weight(X,Y) = sum( 1 / |files(commit)| )   for commits containing both X and Y
     *
     * @param analyzer          Analyzer used to map files to CodeUnits
     * @param projectRoot       Root of the (git) project
     * @param seedClassWeights  Fully-qualified seed class names and their weights
     * @param k                 Number of results to return
     * @param reversed          If true smallest scores first, otherwise largest first
     */
    public static List<IAnalyzer.CodeUnitRelevance> getInverseFileCountCooccurrence(
            IAnalyzer analyzer,
            Path projectRoot,
            Map<String, Double> seedClassWeights,
            int k,
            boolean reversed
    ) throws GitAPIException {
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a directory: " + projectRoot);
        } else if (!GitRepo.hasGitRepo(projectRoot)) {
            throw new IllegalArgumentException("Given project root is not a Git repository: " + projectRoot);
        }

        try (var repo = new GitRepo(projectRoot)) {
            // Map the seed FQNs to CodeUnits
            var seedCodeUnitWeights = new HashMap<CodeUnit, Double>();
            seedClassWeights.forEach((fqName, weight) ->
                    analyzer.getDefinition(fqName).stream()
                            .filter(CodeUnit::isClass)
                            .forEach(cu -> seedCodeUnitWeights.put(cu, weight))
            );
            if (seedCodeUnitWeights.isEmpty()) {
                return List.of();
            }

            // Build a complete mapping ProjectFile -> CodeUnits once up-front
            var fileToCodeUnits = new HashMap<ProjectFile, Set<CodeUnit>>();
            analyzer.getAllDeclarations()
                    .forEach(cu -> fileToCodeUnits
                            .computeIfAbsent(cu.source(), f -> new HashSet<>())
                            .add(cu));

            return computeCooccurrenceScores(repo, seedCodeUnitWeights, fileToCodeUnits, k, reversed);
        }
    }

    /**
     * Internal helper that walks commits in parallel, aggregates the inverse
     * file-count weighted co-occurrence contributions, and returns the top-k
     * CodeUnits.
     */
    private static List<IAnalyzer.CodeUnitRelevance> computeCooccurrenceScores(
            GitRepo repo,
            Map<CodeUnit, Double> seedCodeUnitWeights,
            Map<ProjectFile, Set<CodeUnit>> fileToCodeUnits,
            int k,
            boolean reversed
    ) throws GitAPIException {
        var currentBranch = repo.getCurrentBranch();
        var commits       = repo.listCommitsDetailed(currentBranch);

        var scores = new ConcurrentHashMap<CodeUnit, Double>();

        var pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        try {
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
            })).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error computing co-occurrence scores in parallel", e);
        } finally {
            pool.shutdown();
        }

        return scores.entrySet().stream()
                     .map(e -> new IAnalyzer.CodeUnitRelevance(e.getKey(), e.getValue()))
                     .sorted((a, b) -> reversed
                             ? Double.compare(a.score(), b.score())
                             : Double.compare(b.score(), a.score()))
                     .limit(k)
                     .toList();
    }

}
