package ai.brokk.ranking;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.ProjectFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ranks related files using import relationships and Personalized PageRank (PPR).
 *
 * <p>The graph is built from file-to-file import edges. Seeds provide a teleport vector to PPR,
 * returning the top-k related files (excluding the seeds).
 */
public final class ImportPageRanker {

    private ImportPageRanker() {}

    private static final Logger log = LoggerFactory.getLogger(ImportPageRanker.class);

    private static final double ALPHA = 0.85d;
    private static final double CONVERGENCE_EPSILON = 1.0e-6d;
    private static final int MAX_ITERS = 75;
    private static final int IMPORT_DEPTH = 2;
    private static final int LARGE_GRAPH_NODE_THRESHOLD = 10_000;
    private static final long LARGE_GRAPH_EDGE_THRESHOLD = 500_000L;

    private record Graph(Map<ProjectFile, Set<ProjectFile>> forward, Map<ProjectFile, Set<ProjectFile>> reverse) {}

    private static Graph buildGraph(IAnalyzer analyzer, Set<ProjectFile> seeds) {
        Map<ProjectFile, Set<ProjectFile>> forward = new HashMap<>();
        Map<ProjectFile, Set<ProjectFile>> reverse = new HashMap<>();
        Map<ProjectFile, Set<ProjectFile>> importCache = new HashMap<>();
        Map<ProjectFile, Set<ProjectFile>> reverseCache = new HashMap<>();
        ArrayDeque<ProjectFile> frontier = new ArrayDeque<>();

        for (ProjectFile pf : seeds) {
            if (!forward.containsKey(pf)) {
                forward.put(pf, new LinkedHashSet<>());
                reverse.put(pf, new LinkedHashSet<>());
                frontier.add(pf);
            }
        }

        for (int depth = 0; depth < IMPORT_DEPTH && !frontier.isEmpty(); depth++) {
            ArrayDeque<ProjectFile> next = new ArrayDeque<>();
            while (!frontier.isEmpty()) {
                ProjectFile pf = frontier.removeFirst();

                for (ProjectFile target : importedFilesFor(analyzer, pf, importCache)) {
                    if (!forward.containsKey(target)) {
                        forward.put(target, new LinkedHashSet<>());
                        reverse.put(target, new LinkedHashSet<>());
                        next.add(target);
                    }
                    Objects.requireNonNull(forward.get(pf)).add(target);
                    Objects.requireNonNull(reverse.get(target)).add(pf);
                }

                for (ProjectFile source : referencingFilesFor(analyzer, pf, reverseCache)) {
                    if (!forward.containsKey(source)) {
                        forward.put(source, new LinkedHashSet<>());
                        reverse.put(source, new LinkedHashSet<>());
                        next.add(source);
                    }
                    Objects.requireNonNull(forward.get(source)).add(pf);
                    Objects.requireNonNull(reverse.get(pf)).add(source);
                }
            }
            frontier = next;
        }

        return new Graph(forward, reverse);
    }

    public static List<IAnalyzer.FileRelevance> getRelatedFilesByImports(
            IAnalyzer analyzer, Map<ProjectFile, Double> seedWeights, int k, boolean reversed) {
        if (k <= 0) return List.of();

        Map<ProjectFile, Double> positiveSeeds = new HashMap<>();
        for (var e : seedWeights.entrySet()) {
            if (e.getValue() > 0.0d) {
                positiveSeeds.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }
        if (positiveSeeds.isEmpty()) {
            return List.of();
        }

        Graph graph = buildGraph(analyzer, positiveSeeds.keySet());
        Map<ProjectFile, Set<ProjectFile>> adjacency = reversed ? graph.reverse() : graph.forward();

        List<ProjectFile> nodes = new ArrayList<>(adjacency.keySet());
        Map<ProjectFile, Integer> indexByFile = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            indexByFile.put(nodes.get(i), i);
        }

        double totalSeed =
                positiveSeeds.values().stream().mapToDouble(Double::doubleValue).sum();
        int n = nodes.size();
        if (n == 0 || totalSeed <= 0.0d) {
            return List.of();
        }

        double[] v = new double[n];
        for (var e : positiveSeeds.entrySet()) {
            Integer idx = indexByFile.get(e.getKey());
            if (idx != null) {
                v[idx] = e.getValue() / totalSeed;
            }
        }

        int[][] neighbors = new int[n][];
        int[] outdeg = new int[n];
        long edgeCount = 0L;
        for (int i = 0; i < n; i++) {
            ProjectFile pf = nodes.get(i);
            Set<ProjectFile> outs = adjacency.getOrDefault(pf, Set.of());
            int[] outIdx = outs.stream()
                    .map(indexByFile::get)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .toArray();
            neighbors[i] = outIdx;
            outdeg[i] = outIdx.length;
            edgeCount += outIdx.length;
        }

        if (n > LARGE_GRAPH_NODE_THRESHOLD || edgeCount > LARGE_GRAPH_EDGE_THRESHOLD) {
            log.debug("ImportPageRanker large graph: nodes={}, edges={}, seeds={}", n, edgeCount, positiveSeeds.size());
        }

        double[] rank = new double[n];
        System.arraycopy(v, 0, rank, 0, n);

        double[] next = new double[n];
        for (int iter = 0; iter < MAX_ITERS; iter++) {
            for (int i = 0; i < n; i++) {
                next[i] = (1.0d - ALPHA) * v[i];
            }

            double danglingMass = 0.0d;
            for (int i = 0; i < n; i++) {
                if (outdeg[i] == 0) {
                    danglingMass += rank[i];
                } else {
                    double share = ALPHA * rank[i] / outdeg[i];
                    for (int j : neighbors[i]) {
                        next[j] += share;
                    }
                }
            }

            if (Math.abs(danglingMass) > 1.0e-10d) {
                double add = ALPHA * danglingMass;
                for (int i = 0; i < n; i++) {
                    next[i] += add * v[i];
                }
            }

            double diff = 0.0d;
            for (int i = 0; i < n; i++) {
                diff += Math.abs(next[i] - rank[i]);
            }

            double[] tmp = rank;
            rank = next;
            next = tmp;

            if (diff < CONVERGENCE_EPSILON) {
                break;
            }
        }

        Set<ProjectFile> seedFiles = positiveSeeds.keySet();
        List<IAnalyzer.FileRelevance> ranked = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ProjectFile pf = nodes.get(i);
            if (seedFiles.contains(pf)) continue;
            double score = rank[i];
            if (score > 0.0d) {
                ranked.add(new IAnalyzer.FileRelevance(pf, score));
            }
        }

        ranked.sort(Comparator.<IAnalyzer.FileRelevance>comparingDouble(IAnalyzer.FileRelevance::score)
                .reversed()
                .thenComparing(fr -> fr.file().toString().toLowerCase(Locale.ROOT)));

        if (ranked.size() <= k) {
            return ranked;
        }
        return ranked.subList(0, k);
    }

    private static Set<ProjectFile> importedFilesFor(
            IAnalyzer analyzer, ProjectFile file, Map<ProjectFile, Set<ProjectFile>> cache) {
        Set<ProjectFile> cached = cache.get(file);
        if (cached != null) {
            return cached;
        }

        Set<CodeUnit> importedUnits = analyzer.as(ImportAnalysisProvider.class)
                .map(p -> p.importedCodeUnitsOf(file))
                .orElse(Set.of());
        Set<ProjectFile> resolved;

        if (!importedUnits.isEmpty()) {
            resolved = toFiles(importedUnits);
        } else {
            resolved = new LinkedHashSet<>();
            List<String> imports = analyzer.importStatementsOf(file);
            for (String imp : imports) {
                int before = resolved.size();
                addDefinitions(analyzer.getDefinitions(imp), resolved);
                if (resolved.size() == before) {
                    addDefinitions(analyzer.searchDefinitions(imp), resolved);
                }
            }
        }

        cache.put(file, resolved);
        return resolved;
    }

    private static Set<ProjectFile> toFiles(Collection<CodeUnit> codeUnits) {
        Set<ProjectFile> files = new LinkedHashSet<>();
        for (CodeUnit cu : codeUnits) {
            files.add(cu.source());
        }
        return files;
    }

    private static void addDefinitions(Collection<CodeUnit> defs, Set<ProjectFile> out) {
        for (CodeUnit cu : defs) {
            out.add(cu.source());
        }
    }

    private static Set<ProjectFile> referencingFilesFor(
            IAnalyzer analyzer, ProjectFile file, Map<ProjectFile, Set<ProjectFile>> cache) {
        Set<ProjectFile> cached = cache.get(file);
        if (cached != null) {
            return cached;
        }

        Set<ProjectFile> resolved = analyzer.as(ImportAnalysisProvider.class)
                .map(p -> p.referencingFilesOf(file))
                .orElse(Set.of());
        cache.put(file, resolved);
        return resolved;
    }
}
