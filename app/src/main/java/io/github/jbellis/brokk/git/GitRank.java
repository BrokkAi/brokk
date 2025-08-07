package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Provides the logic to perform a Git-centric PageRank calculation for given type declarations.
 */
public final class GitRank {

    /**
     * Represents an edge between two CodeUnits in the co-occurrence graph.
     */
    public record CodeUnitEdge(CodeUnit src, CodeUnit dst) {
    }

    public static List<IAnalyzer.PageRankResult> getPagerank(
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
            final var codeUnitToProjectFile = new HashMap<CodeUnit, ProjectFile>();
            final var seedCodeUnitWeights = new HashMap<CodeUnit, Double>();

            seedClassWeights.forEach((fullName, weight) ->
                    analyzer.getDefinition(fullName).stream()
                            .filter(CodeUnit::isClass)
                            .forEach(cu -> seedCodeUnitWeights.put(cu, weight))
            );

            analyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isClass)
                    .forEach(cu -> analyzer.getFileFor(cu.fqName())
                            .ifPresent(projectFile -> codeUnitToProjectFile.put(cu, projectFile))
                    );

            return getPagerank(repo, codeUnitToProjectFile, seedCodeUnitWeights, k, reversed);
        }
    }

    private static List<IAnalyzer.PageRankResult> getPagerank(
            GitRepo repo,
            Map<CodeUnit, ProjectFile> codeUnitToProjectFile,
            Map<CodeUnit, Double> seedCodeUnitWeights,
            int k,
            boolean reversed
    ) throws GitAPIException {
        final var currentBranch = repo.getCurrentBranch();

        // Build reverse mapping from ProjectFile to CodeUnits
        final var fileToCodeUnits = new HashMap<ProjectFile, Set<CodeUnit>>();
        codeUnitToProjectFile.forEach((codeUnit, projectFile) ->
                fileToCodeUnits.computeIfAbsent(projectFile, f -> new HashSet<>()).add(codeUnit)
        );

        // Build weighted adjacency graph of CodeUnit co-occurrences
        final var edgeWeights = new HashMap<CodeUnitEdge, Integer>(); // CodeUnit edge -> weight
        final var allCodeUnits = new HashSet<>(codeUnitToProjectFile.keySet());

        // Get all commits and build co-occurrence graph
        final var commits = repo.listCommitsDetailed(currentBranch);

        for (var commit : commits) {
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
                        edgeWeights.merge(edgeKey, 1, Integer::sum);
                    }
                }
            }
        }

        // Run PageRank algorithm
        final var damping = 0.85;
        final var maxIterations = 50;
        final var epsilon = 1e-6;

        // Initialize PageRank scores
        final var scores = new HashMap<String, Double>();
        final var newScores = new HashMap<String, Double>();
        final var numNodes = allCodeUnits.size();
        final var initialScore = 1.0 / numNodes;

        for (var codeUnit : allCodeUnits) {
            var seedWeight = seedCodeUnitWeights.getOrDefault(codeUnit, 0.0);
            scores.put(codeUnit.fqName(), initialScore + seedWeight);
            newScores.put(codeUnit.fqName(), 0.0);
        }

        // Compute outgoing edge counts for each node
        final var outgoingWeights = new HashMap<String, Integer>();
        for (var entry : edgeWeights.entrySet()) {
            final var fromNode = entry.getKey().src().fqName();
            outgoingWeights.merge(fromNode, entry.getValue(), Integer::sum);
        }

        // Iteratively update PageRank scores
        for (int iter = 0; iter < maxIterations; iter++) {
            var maxDiff = 0.0;

            for (var codeUnit : allCodeUnits) {
                final var fqName = codeUnit.fqName();
                var newScore = (1.0 - damping) / numNodes;

                // Add contributions from incoming edges
                for (var entry : edgeWeights.entrySet()) {
                    final var edge = entry.getKey();
                    if (edge.dst().fqName().equals(fqName)) {
                        final var fromNode = edge.src().fqName();
                        final var edgeWeight = entry.getValue();
                        final var fromOutgoingWeight = outgoingWeights.getOrDefault(fromNode, 1);
                        newScore += damping * requireNonNull(scores.get(fromNode)) * edgeWeight / fromOutgoingWeight;
                    }
                }

                // Add seed contribution
                var seedWeight = seedCodeUnitWeights.getOrDefault(codeUnit, 0.0);
                newScore += seedWeight;

                newScores.put(fqName, newScore);
                maxDiff = Math.max(maxDiff, Math.abs(newScore - requireNonNull(scores.get(fqName))));
            }

            // Swap score maps
            scores.clear();
            scores.putAll(newScores);
            newScores.clear();
            newScores.putAll(scores);

            if (maxDiff < epsilon) {
                break;
            }
        }

        // Create results and sort by score
        return allCodeUnits.stream()
                .map(codeUnit -> new IAnalyzer.PageRankResult(codeUnit, requireNonNull(scores.get(codeUnit.fqName()))))
                .sorted((a, b) -> reversed ?
                        Double.compare(a.score(), b.score()) :
                        Double.compare(b.score(), a.score()))
                .limit(k)
                .collect(Collectors.toList());
    }

}
