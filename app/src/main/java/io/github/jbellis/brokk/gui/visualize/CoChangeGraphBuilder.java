package io.github.jbellis.brokk.gui.visualize;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.ICommitInfo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.util.ExecutorServiceUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a co-change graph for project files by scanning commit history.
 */
public class CoChangeGraphBuilder {
    private static final Logger logger = LogManager.getLogger(CoChangeGraphBuilder.class);

    /** Simple progress payload for async reporting. */
    public record Progress(String message, int value, int max) {}

    private final IProject project;
    private final IGitRepo repo;

    public CoChangeGraphBuilder(Chrome chrome) {
        this.project = chrome.getProject();
        this.repo = project.getRepo();
        logger.debug("CoChangeGraphBuilder initialized for project {}", project.getRoot());
    }

    /**
     * Builds the co-change graph asynchronously.
     *
     * @param progressConsumer receives progress updates
     * @param isCancelled returns true to cancel
     * @param branch the branch to analyze; if null/blank, uses current branch
     * @param compareToBranch optional comparison branch (currently unused)
     * @return a future that completes with the computed Graph
     */
    public CompletableFuture<Graph> buildAsync(
            Consumer<Progress> progressConsumer,
            Supplier<Boolean> isCancelled,
            @Nullable String branch,
            @Nullable String compareToBranch) {

        var maxConcurrency = Math.max(1, Runtime.getRuntime().availableProcessors());
        var executor = ExecutorServiceUtil.newVirtualThreadExecutor("cochange-vt-", maxConcurrency);

        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        if (isCancelled.get()) {
                            throw new CancellationException("Cancelled before start");
                        }

                        // Determine branch name
                        var branchName = (branch == null || branch.isBlank()) ? repo.getCurrentBranch() : branch;
                        logger.debug("Starting co-change scan on branch='{}', compareTo='{}'", branchName, compareToBranch);

                        // Require GitRepo for detailed commit listing
                        if (!(repo instanceof GitRepo gitRepo)) {
                            logger.warn("Project repo is not a GitRepo instance; returning empty graph");
                            progressConsumer.accept(new Progress("Unsupported repository type", 0, 0));
                            return new Graph(Map.of(), Map.of());
                        }

                        var commits = gitRepo.listCommitsDetailed(branchName);
                        int max = commits.size();
                        var initialMsg = "Scanning commits on " + branchName
                                + ((compareToBranch != null && !compareToBranch.isBlank()) ? " vs " + compareToBranch : "");
                        progressConsumer.accept(new Progress(initialMsg, 0, max));

                        // Get tracked files to filter out deleted/untracked
                        Set<ProjectFile> tracked = new HashSet<>(repo.getTrackedFiles());

                        // Build the graph from the collected commits
                        return buildGraphFromCommits(commits, tracked, progressConsumer, isCancelled);
                    } catch (GitAPIException e) {
                        logger.error("Failed to build co-change graph: {}", e.getMessage(), e);
                        throw new CompletionException(e);
                    }
                }, executor)
                .whenComplete((res, ex) -> executor.shutdown());
    }

    /**
     * Builds a Graph from commits and a tracked file set.
     * Package-private for unit tests. Reports progress per-commit and honors cancellation.
     */
    static Graph buildGraphFromCommits(
            List<? extends ICommitInfo> commits,
            Set<ProjectFile> tracked,
            Consumer<Progress> progressConsumer,
            Supplier<Boolean> isCancelled) {

        // Accumulate weights for unordered file pairs
        Map<Graph.Pair, Integer> weightMap = new HashMap<>();
        Map<ProjectFile, Node> nodes = new HashMap<>();

        int max = commits.size();
        int idx = 0;

        for (var commit : commits) {
            if (isCancelled.get()) {
                throw new CancellationException("Cancelled during commit scan");
            }

            List<ProjectFile> changed;
            try {
                changed = commit.changedFiles();
            } catch (GitAPIException e) {
                throw new CompletionException(e);
            }

            // filter to tracked; de-duplicate within a single commit
            var uniqueChanged = new ArrayList<ProjectFile>(new LinkedHashSet<>(
                    changed.stream().filter(tracked::contains).toList()
            ));

            // ensure nodes exist for any changed file
            for (var pf : uniqueChanged) {
                nodes.computeIfAbsent(pf, f -> new Node(f, 0.0, 0.0, 0.0, 0.0, 0.0));
            }

            // pairwise increments for co-changes
            int sz = uniqueChanged.size();
            for (int i = 0; i < sz; i++) {
                for (int j = i + 1; j < sz; j++) {
                    var a = uniqueChanged.get(i);
                    var b = uniqueChanged.get(j);
                    var key = new Graph.Pair(a, b);
                    weightMap.merge(key, 1, Integer::sum);
                }
            }

            idx++;
            progressConsumer.accept(new Progress("Processed " + idx + " / " + max + " commits", idx, max));
        }

        // Materialize edges
        Map<Graph.Pair, Edge> edges = new HashMap<>();
        for (var e : weightMap.entrySet()) {
            var pair = e.getKey();
            edges.put(pair, new Edge(pair.a(), pair.b(), e.getValue()));
        }

        return new Graph(nodes, edges);
    }
}
