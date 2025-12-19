package ai.brokk.ranking;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
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
 * <p>
 * The graph is built from file-to-file import edges. Seeds provide a teleport vector to PPR,
 * returning the top-k related files (excluding the seeds).
 */
public final class ImportPageRanker {

    private ImportPageRanker() {}

    private static final Logger log = LoggerFactory.getLogger(ImportPageRanker.class);

    /** Damping factor for PageRank. */
    private static final double ALPHA = 0.85d;
    /** Convergence tolerance for L1 difference between iterations. */
    private static final double CONVERGENCE_EPSILON = 1.0e-6d;
    /** Maximum PageRank iterations. */
    private static final int MAX_ITERS = 75;
    /** Import reach depth when constructing the candidate set. */
    private static final int IMPORT_DEPTH = 2;
    /** Optional background set size to avoid tiny graphs. */
    private static final int BACKGROUND_BUDGET = 0;
    /** Node threshold above which we log a warning about graph size. */
    private static final int LARGE_GRAPH_NODE_THRESHOLD = 2000;

    /**
     * Builds a candidate set by starting from the seed files and expanding via import relationships
     * up to IMPORT_DEPTH. Optionally mixes in a small background set for stability.
     */
    private static Set<ProjectFile> buildCandidateSet(
            IAnalyzer analyzer, Map<ProjectFile, Double> seedWeights, boolean reversed) {
        LinkedHashSet<ProjectFile> candidates = new LinkedHashSet<>();
        ArrayDeque<ProjectFile> frontier = new ArrayDeque<>();

        for (ProjectFile pf : seedWeights.keySet()) {
            if (candidates.add(pf)) {
                frontier.add(pf);
            }
        }

        for (int depth = 0; depth < IMPORT_DEPTH && !frontier.isEmpty(); depth++) {
            ArrayDeque<ProjectFile> next = new ArrayDeque<>();
            while (!frontier.isEmpty()) {
                ProjectFile pf = frontier.removeFirst();
                Collection<ProjectFile> neighbors =
                        reversed ? importersOf(analyzer, pf) : importedFilesFor(analyzer, pf);
                for (ProjectFile dep : neighbors) {
                    if (candidates.add(dep)) {
                        next.add(dep);
                    }
                }
            }
            frontier = next;
        }

        if (BACKGROUND_BUDGET > 0) {
            LinkedHashSet<ProjectFile> background = new LinkedHashSet<>();
            for (CodeUnit cu : analyzer.getAllDeclarations()) {
                ProjectFile src = cu.source();
                if (!candidates.contains(src)) {
                    background.add(src);
                    if (background.size() >= BACKGROUND_BUDGET) break;
                }
            }
            candidates.addAll(background);
        }

        return candidates;
    }

    /**
     * Compute the top-k related files using import-based Personalized PageRank.
     *
     * @param analyzer the analyzer providing import data and declarations.
     * @param seedWeights map of seed files to positive weights (teleport vector); zero/negative are ignored.
     * @param k number of results to return.
     * @param reversed if true, reverse edges to rank importers instead of imports.
     * @return ranked list of related files (excluding the seeds).
     */
    public static List<IAnalyzer.FileRelevance> getRelatedFilesByImports(
            IAnalyzer analyzer, Map<ProjectFile, Double> seedWeights, int k, boolean reversed) {
        if (k <= 0) return List.of();

        // Normalize seeds (ignore zero/negative)
        Map<ProjectFile, Double> positiveSeeds = new HashMap<>();
        for (var e : seedWeights.entrySet()) {
            if (e.getValue() > 0.0d) {
                positiveSeeds.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }
        if (positiveSeeds.isEmpty()) {
            return List.of();
        }

        // Build small candidate universe by import reach from seeds
        Set<ProjectFile> candidates = buildCandidateSet(analyzer, positiveSeeds, reversed);

        // Build adjacency map restricted to candidate set.
        Map<ProjectFile, Set<ProjectFile>> adjacency = new HashMap<>();
        for (ProjectFile pf : candidates) {
            adjacency.put(pf, new LinkedHashSet<>());
        }

        // Build adjacency map restricted to candidate set.
        // We iterate over every file in the candidate set (pf) and its imports (target).
        // This defines the ground truth of the import graph.
        // The PageRank 'flow' direction is then determined by the 'reversed' flag:
        // - Normal (reversed=false): Rank flows from Importer to Imported (pf -> target).
        // - Importers (reversed=true): Rank flows from Imported back to Importer (target -> pf).
        for (ProjectFile pf : candidates) {
            for (ProjectFile target : importedFilesFor(analyzer, pf)) {
                if (candidates.contains(target)) {
                    if (reversed) {
                        // Mass flows from the file being imported back to the importer
                        adjacency.get(target).add(pf);
                    } else {
                        // Mass flows from the importer to the file being imported
                        adjacency.get(pf).add(target);
                    }
                }
            }
        }

        // Index nodes
        List<ProjectFile> nodes = new ArrayList<>(adjacency.keySet());
        Map<ProjectFile, Integer> indexByFile = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            indexByFile.put(nodes.get(i), i);
        }

        // Teleport vector from seeds, normalized
        double totalSeed =
                positiveSeeds.values().stream().mapToDouble(Double::doubleValue).sum();
        int n = nodes.size();
        if (n == 0) {
            return List.of();
        }
        if (totalSeed <= 0.0d) {
            return List.of();
        }
        double[] v = new double[n];
        for (var e : positiveSeeds.entrySet()) {
            Integer idx = indexByFile.get(e.getKey());
            if (idx != null) {
                v[idx] = e.getValue() / totalSeed;
            }
        }

        // Prepare outdegree and neighbor arrays
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

        if (n > LARGE_GRAPH_NODE_THRESHOLD && log.isWarnEnabled()) {
            log.warn(
                    "ImportPageRanker large graph: nodes={}, edges={}, candidates={}, seeds={}",
                    n,
                    edgeCount,
                    candidates.size(),
                    positiveSeeds.size());
        }

        // Personalized PageRank

        double[] rank = new double[n];
        System.arraycopy(v, 0, rank, 0, n); // start from teleport vector

        double[] next = new double[n];
        double uniform = 1.0d / n;
        for (int iter = 0; iter < MAX_ITERS; iter++) {
            // Teleport component
            for (int i = 0; i < n; i++) {
                next[i] = (1.0d - ALPHA) * v[i];
            }

            // Link-follow component
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

            // Distribute dangling mass back to the teleport vector (Personalized PageRank variation).
            // This ensures that mass doesn't drift away from the seeds in small graphs with sinks.
            if (Math.abs(danglingMass) > 1.0e-10d) {
                double add = ALPHA * danglingMass;
                for (int i = 0; i < n; i++) {
                    next[i] += add * v[i];
                }
            }

            // Check convergence (L1 norm)
            double diff = 0.0d;
            for (int i = 0; i < n; i++) {
                diff += Math.abs(next[i] - rank[i]);
            }
            // Swap arrays
            double[] tmp = rank;
            rank = next;
            next = tmp;

            if (diff < CONVERGENCE_EPSILON) {
                break;
            }
        }

        // Build results, excluding seed files
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

        // Sort by score desc, then by path for stability
        ranked.sort(Comparator.<IAnalyzer.FileRelevance>comparingDouble(IAnalyzer.FileRelevance::score)
                .reversed()
                .thenComparing(fr -> fr.file().toString().toLowerCase(Locale.ROOT)));

        if (ranked.size() <= k) {
            return ranked;
        }
        return ranked.subList(0, k);
    }


    /**
     * Resolve imported files for the given source file using the most accurate analyzer APIs available.
     */
    private static Set<ProjectFile> importedFilesFor(IAnalyzer analyzer, ProjectFile file) {
        // Prefer TreeSitterAnalyzer if available for accurate resolution
        if (analyzer instanceof TreeSitterAnalyzer tsa) {
            Set<CodeUnit> cus = tsa.importedCodeUnitsOf(file);
            return toFiles(cus);
        }

        // Fallback using import statements + definition lookup
        Set<ProjectFile> out = new LinkedHashSet<>();
        List<String> imports = analyzer.importStatementsOf(file);
        for (String imp : imports) {
            int before = out.size();
            addDefinitions(analyzer.getDefinitions(imp), out);
            if (out.size() == before) {
                addDefinitions(analyzer.searchDefinitions(imp), out);
            }
        }
        return out;
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

    /**
     * Resolve files that import the given file.
     */
    private static Set<ProjectFile> importersOf(IAnalyzer analyzer, ProjectFile file) {
        // Scan all declarations to find files that import the target file.
        // This is necessary because IAnalyzer does not provide a direct reverse-lookup for imports.
        Set<ProjectFile> importers = new LinkedHashSet<>();
        for (CodeUnit cu : analyzer.getAllDeclarations()) {
            ProjectFile src = cu.source();
            if (importedFilesFor(analyzer, src).contains(file)) {
                importers.add(src);
            }
        }
        return importers;
    }
}
