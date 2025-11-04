package ai.brokk.gui.visualize;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.ExecutorServiceUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Force-directed 2D layout for a co-change Graph.
 *
 * - Springs on edges, natural length 80px, strength proportional to sqrt(weight)
 * - Inverse-distance repulsion using the nearest-by-X 20 and nearest-by-Y 20 neighbors (union)
 * - 20 simulation steps with simple Euler integration and damping
 *
 * Initialization and constraints:
 * - Node positions are initialized randomly within a circle of radius equal to the visualization window height.
 * - Per-step node movement (delta) is clamped to at most one-tenth of that radius.
 *
 * Mutates Node positions/velocities in-place (in Graph.nodes.values()).
 */
public final class CoChangePhysicsLayout {
    private static final Logger logger = LogManager.getLogger(CoChangePhysicsLayout.class);

    // Simulation constants
    private static final int NUM_STEPS = 20;
    private static final double NATURAL_LENGTH = 500.0; // pixels
    private static final double SPRING_K = 0.05; // base spring constant (scaled by sqrt(weight))
    private static final double REPULSION_K = 1000.0; // inverse-distance repulsion constant
    private static final double DAMPING = 0.85; // velocity damping per step
    private static final int MAX_NEIGHBORS_AXIS = 20; // nearest-by-x and nearest-by-y
    private static final double EPS = 1e-6; // numerical stability

    /** Progress payload for async reporting. */
    public record Progress(String message, int value, int max) {}

    /**
     * Backward-compatible entrypoint that defaults the view height to 600 px.
     */
    public CompletableFuture<Graph> runAsync(Graph graph, Consumer<Progress> progressConsumer) {
        return runAsync(graph, progressConsumer, 600.0);
    }

    /**
     * Run the layout with a visualization height that defines:
     * - initialization radius (equal to viewHeight)
     * - per-step movement clamp (1/10 of viewHeight)
     */
    public CompletableFuture<Graph> runAsync(Graph graph, Consumer<Progress> progressConsumer, double viewHeight) {
        var maxConcurrency = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = ExecutorServiceUtil.newVirtualThreadExecutor("cochange-layout-vt-", maxConcurrency);

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        run(graph, progressConsumer, executor, Math.max(1.0, viewHeight));
                        return graph;
                    } finally {
                        executor.shutdown();
                    }
                },
                executor);
    }

    private void run(Graph graph, Consumer<Progress> progressConsumer, ExecutorService executor, double viewHeight) {
        if (graph.nodes.isEmpty()) {
            progressConsumer.accept(new Progress("No nodes to layout", NUM_STEPS, NUM_STEPS));
            return;
        }

        // Stable node indexing
        var nodeList = new ArrayList<Node>(graph.nodes.values());
        final int n = nodeList.size();

        // Map ProjectFile -> node index
        var indexByFile = IntStream.range(0, n)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(i -> nodeList.get(i).file, i -> i));

        // Build adjacency lists from edges
        @SuppressWarnings("unchecked")
        List<Adj>[] adj = new ArrayList[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();

        for (var e : graph.edges.values()) {
            Integer ia = indexByFile.get(e.a());
            Integer ib = indexByFile.get(e.b());
            if (ia == null || ib == null || ia.equals(ib)) continue;
            adj[ia].add(new Adj(ib, e.weight()));
            adj[ib].add(new Adj(ia, e.weight()));
        }

        // Simulation config derived from view height
        final double initRadius = viewHeight; // origin-centered
        final double maxDelta = initRadius / 10.0;

        // Initialize state: random points uniformly within a circle of radius = viewHeight
        var x = new double[n];
        var y = new double[n];
        var vx = new double[n];
        var vy = new double[n];
        var rnd = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            double theta = rnd.nextDouble(0.0, Math.PI * 2.0);
            // r distribution: sqrt for uniform disk
            double r = Math.sqrt(rnd.nextDouble()) * initRadius;
            x[i] = r * Math.cos(theta);
            y[i] = r * Math.sin(theta);
            vx[i] = 0.0;
            vy[i] = 0.0;
        }

        if (logger.isDebugEnabled()) {
            var stats = computeStats(x, y, vx, vy, graph, indexByFile);
            logger.debug(
                    "Layout start: nodes={}, edges={}, initRadius={}, bbox=[{}..{}]x[{}..{}], meanSpeed={}, meanEdgeLen={}",
                    n,
                    graph.edges.size(),
                    initRadius,
                    stats.minX(),
                    stats.maxX(),
                    stats.minY(),
                    stats.maxY(),
                    stats.meanSpeed(),
                    stats.meanEdgeLen());
        }

        for (int step = 0; step < NUM_STEPS; step++) {
            // Per-step, create effectively-final snapshots for lambdas
            final double[] xIn = x;
            final double[] yIn = y;
            final double[] vxIn = vx;
            final double[] vyIn = vy;

            // Sort indices by x and y for nearest-neighbor selection
            final int[] byX = IntStream.range(0, n)
                    .boxed()
                    .sorted((a, b) -> Double.compare(xIn[a], xIn[b]))
                    .mapToInt(Integer::intValue)
                    .toArray();
            final int[] byY = IntStream.range(0, n)
                    .boxed()
                    .sorted((a, b) -> Double.compare(yIn[a], yIn[b]))
                    .mapToInt(Integer::intValue)
                    .toArray();

            final int[] rankX = new int[n];
            final int[] rankY = new int[n];
            for (int i = 0; i < n; i++) {
                rankX[byX[i]] = i;
                rankY[byY[i]] = i;
            }

            // Per-step output arrays (also effectively final to lambdas)
            final double[] outX = new double[n];
            final double[] outY = new double[n];
            final double[] outVx = new double[n];
            final double[] outVy = new double[n];

            // Parallel per-node force calculation
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    double fx = 0.0;
                    double fy = 0.0;

                    // Springs for edges
                    for (var a : adj[idx]) {
                        int j = a.j();
                        double dx = xIn[j] - xIn[idx];
                        double dy = yIn[j] - yIn[idx];
                        double dist = Math.sqrt(dx * dx + dy * dy) + EPS;

                        double extension = dist - NATURAL_LENGTH;
                        double k = SPRING_K * Math.sqrt(Math.max(1, a.weight()));
                        double fMag = k * extension;

                        fx += fMag * (dx / dist);
                        fy += fMag * (dy / dist);
                    }

                    // Repulsion: union of nearest-by-x and nearest-by-y neighbors
                    Set<Integer> repNeighbors = new LinkedHashSet<>(MAX_NEIGHBORS_AXIS * 4);
                    addAxisNeighbors(repNeighbors, idx, byX, rankX, MAX_NEIGHBORS_AXIS, n);
                    addAxisNeighbors(repNeighbors, idx, byY, rankY, MAX_NEIGHBORS_AXIS, n);

                    for (int j : repNeighbors) {
                        if (j == idx) continue;
                        double dx = xIn[j] - xIn[idx];
                        double dy = yIn[j] - yIn[idx];
                        double dist = Math.sqrt(dx * dx + dy * dy) + EPS;
                        double fMag = -(REPULSION_K / dist); // push away
                        fx += fMag * (dx / dist);
                        fy += fMag * (dy / dist);
                    }

                    // Integrate with damping
                    double nvx = (vxIn[idx] + fx) * DAMPING;
                    double nvy = (vyIn[idx] + fy) * DAMPING;

                    // Clamp per-step delta to maxDelta relative to current position
                    double stepDist = Math.hypot(nvx, nvy);
                    if (stepDist > maxDelta) {
                        double scale = maxDelta / (stepDist + EPS);
                        nvx *= scale;
                        nvy *= scale;
                    }

                    double nx = xIn[idx] + nvx;
                    double ny = yIn[idx] + nvy;

                    outVx[idx] = nvx;
                    outVy[idx] = nvy;
                    outX[idx] = nx;
                    outY[idx] = ny;
                }));
            }

            for (var f : futures) {
                try {
                    f.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.warn("Layout interrupted at step {}", step + 1);
                    return;
                } catch (ExecutionException ee) {
                    throw new RuntimeException("Error computing physics step", ee.getCause());
                }
            }

            // Use step outputs as next state (rebind for next iteration)
            x = outX;
            y = outY;
            vx = outVx;
            vy = outVy;

            if (logger.isDebugEnabled() && ((step + 1) % 5 == 0 || step == 0)) {
                var statsStep = computeStats(x, y, vx, vy, graph, indexByFile);
                logger.debug(
                        "Layout step {}: bbox=[{}..{}]x[{}..{}], meanSpeed={}, meanEdgeLen={}",
                        (step + 1),
                        statsStep.minX(),
                        statsStep.maxX(),
                        statsStep.minY(),
                        statsStep.maxY(),
                        statsStep.meanSpeed(),
                        statsStep.meanEdgeLen());
            }

            progressConsumer.accept(new Progress("Layout step " + (step + 1) + "/" + NUM_STEPS, step + 1, NUM_STEPS));
        }

        // Commit final state back to nodes
        for (int i = 0; i < n; i++) {
            var nd = nodeList.get(i);
            nd.x = x[i];
            nd.y = y[i];
            nd.vx = vx[i];
            nd.vy = vy[i];
        }

        if (logger.isDebugEnabled()) {
            var stats = computeStats(x, y, vx, vy, graph, indexByFile);
            logger.debug(
                    "Layout done: steps={}, bbox=[{}..{}]x[{}..{}], meanSpeed={}, meanEdgeLen={}",
                    NUM_STEPS,
                    stats.minX(),
                    stats.maxX(),
                    stats.minY(),
                    stats.maxY(),
                    stats.meanSpeed(),
                    stats.meanEdgeLen());
        }
    }

    private static void addAxisNeighbors(Set<Integer> out, int idx, int[] sorted, int[] rank, int k, int n) {
        int r = rank[idx];
        for (int d = 1; d <= k; d++) {
            int left = r - d;
            int right = r + d;
            if (left >= 0) out.add(sorted[left]);
            if (right < n) out.add(sorted[right]);
            if (left < 0 && right >= n) break;
        }
    }

    private record Adj(int j, int weight) {}

    private record DebugStats(
            double minX, double maxX, double minY, double maxY, double meanSpeed, double meanEdgeLen) {}

    private static DebugStats computeStats(
            double[] x, double[] y, double[] vx, double[] vy, Graph graph, Map<ProjectFile, Integer> indexByFile) {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double sumSpeed = 0.0;
        int n = x.length;

        for (int i = 0; i < n; i++) {
            double xi = x[i], yi = y[i];
            if (xi < minX) minX = xi;
            if (xi > maxX) maxX = xi;
            if (yi < minY) minY = yi;
            if (yi > maxY) maxY = yi;
            double speed = Math.hypot(vx[i], vy[i]);
            sumSpeed += speed;
        }
        double meanSpeed = n > 0 ? (sumSpeed / n) : 0.0;

        double sumLen = 0.0;
        int edgeCount = 0;
        for (var e : graph.edges.values()) {
            Integer ia = indexByFile.get(e.a());
            Integer ib = indexByFile.get(e.b());
            if (ia == null || ib == null) continue;
            double dx = x[ia] - x[ib];
            double dy = y[ia] - y[ib];
            sumLen += Math.hypot(dx, dy);
            edgeCount++;
        }
        double meanEdgeLen = edgeCount > 0 ? (sumLen / edgeCount) : 0.0;

        return new DebugStats(
                minX == Double.POSITIVE_INFINITY ? 0.0 : minX,
                maxX == Double.NEGATIVE_INFINITY ? 0.0 : maxX,
                minY == Double.POSITIVE_INFINITY ? 0.0 : minY,
                maxY == Double.NEGATIVE_INFINITY ? 0.0 : maxY,
                meanSpeed,
                meanEdgeLen);
    }
}
