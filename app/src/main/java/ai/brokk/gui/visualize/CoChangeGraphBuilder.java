package ai.brokk.gui.visualize;

import ai.brokk.IProject;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import ai.brokk.git.ICommitInfo;
import ai.brokk.gui.Chrome;
import ai.brokk.util.ExecutorServiceUtil;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

                        // Limit to past month (last 30 days)
                        var cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
                        var recent = commits.stream()
                                .filter(c -> !c.date().isBefore(cutoff))
                                .toList();

                        int max = recent.size();
                        var initialMsg = "Scanning last 30 days on " + branchName
                                + ((compareToBranch != null && !compareToBranch.isBlank()) ? " vs " + compareToBranch : "");
                        progressConsumer.accept(new Progress(initialMsg, 0, max));

                        // Get tracked files to filter out deleted/untracked
                        Set<ProjectFile> tracked = new HashSet<>(repo.getTrackedFiles());
                        if (logger.isDebugEnabled()) {
                            logger.debug(
                                    "Co-change scan inputs: totalCommits={}, recentCommits(30d)={}, trackedFiles={}",
                                    commits.size(),
                                    recent.size(),
                                    tracked.size());
                        }

                        // Build the graph from the collected commits
                        return buildGraphFromCommits(recent, tracked, progressConsumer, isCancelled);
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

        // Materialize edges, keeping only those with weight >= median
        Map<Graph.Pair, Edge> edges = new HashMap<>();

        int rawEdgeCount = weightMap.size();
        int medianThreshold;
        if (rawEdgeCount == 0) {
            medianThreshold = Integer.MAX_VALUE; // no edges to keep
        } else {
            var weights = new ArrayList<Integer>(weightMap.values());
            weights.sort(Integer::compareTo); // ascending
            // Use upper median for even counts so that we keep at most half if distribution is uniform
            medianThreshold = weights.get((int) (0.99 * weights.size()));
        }

        for (var e : weightMap.entrySet()) {
            int w = e.getValue();
            if (w >= medianThreshold) {
                var pair = e.getKey();
                edges.put(pair, new Edge(pair.a(), pair.b(), w));
            }
        }

        // Initialize node positions to a non-degenerate layout (golden-angle spiral)
        // This avoids zero-length direction vectors in the first physics step.
        {
            var i = 1;
            final double golden = Math.PI * (3 - Math.sqrt(5));
            final double spacing = 60.0; // pixels between successive rings
            for (var nd : nodes.values()) {
                double r = spacing * Math.sqrt(i);
                double theta = i * golden;
                nd.x = r * Math.cos(theta);
                nd.y = r * Math.sin(theta);
                nd.vx = 0.0;
                nd.vy = 0.0;
                i++;
            }
        }

        if (logger.isDebugEnabled()) {
            // Compute isolated nodes (no edges)
            Map<ProjectFile, Integer> degree = new HashMap<>();
            for (var ed : edges.values()) {
                degree.merge(ed.a(), 1, Integer::sum);
                degree.merge(ed.b(), 1, Integer::sum);
            }
            long isolated = nodes.keySet().stream().filter(f -> degree.getOrDefault(f, 0) == 0).count();
            int maxWeight = edges.values().stream().mapToInt(Edge::weight).max().orElse(0);

            // Bounding box and a small sample of positions
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            int atOrigin = 0;
            var sample = new ArrayList<String>(3);
            for (var nd : nodes.values()) {
                if (nd.x < minX) minX = nd.x;
                if (nd.x > maxX) maxX = nd.x;
                if (nd.y < minY) minY = nd.y;
                if (nd.y > maxY) maxY = nd.y;
                if (nd.x == 0.0 && nd.y == 0.0) atOrigin++;
                if (sample.size() < 3) {
                    sample.add(nd.file + "=(" + String.format("%.2f", nd.x) + "," + String.format("%.2f", nd.y) + ")");
                }
            }
            logger.debug(
                    "Co-change graph summary: nodes={}, edges={}, isolatedNodes={}, maxEdgeWeight={}, initBBox=[{}..{}]x[{}..{}], atOrigin={}," +
                    " samples={}",
                    nodes.size(),
                    edges.size(),
                    isolated,
                    maxWeight,
                    (minX == Double.POSITIVE_INFINITY ? 0.0 : minX),
                    (maxX == Double.NEGATIVE_INFINITY ? 0.0 : maxX),
                    (minY == Double.POSITIVE_INFINITY ? 0.0 : minY),
                    (maxY == Double.NEGATIVE_INFINITY ? 0.0 : maxY),
                    atOrigin,
                    sample);
        }

        return new Graph(nodes, edges);
    }
}
