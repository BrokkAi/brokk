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

public final class ImportPageRanker {

    private ImportPageRanker() {}

    private static final int REACH_DEPTH = 2;
    private static final int BACKGROUND_BUDGET = 64;

    private static Set<ProjectFile> buildCandidateSet(IAnalyzer analyzer, Map<ProjectFile, Double> seedWeights) {
        LinkedHashSet<ProjectFile> candidates = new LinkedHashSet<>();
        ArrayDeque<ProjectFile> frontier = new ArrayDeque<>();

        for (ProjectFile pf : seedWeights.keySet()) {
            if (candidates.add(pf)) {
                frontier.add(pf);
            }
        }

        for (int depth = 0; depth < REACH_DEPTH && !frontier.isEmpty(); depth++) {
            ArrayDeque<ProjectFile> next = new ArrayDeque<>();
            while (!frontier.isEmpty()) {
                ProjectFile pf = frontier.removeFirst();
                for (ProjectFile dep : importedFilesFor(analyzer, pf)) {
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

    public static List<IAnalyzer.FileRelevance> getRelatedFilesByImports(
            IAnalyzer analyzer, Map<ProjectFile, Double> seedWeights, int k, boolean reversed) {
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(seedWeights, "seedWeights");
        if (k <= 0) return List.of();

        // Normalize seeds (ignore zero/negative)
        Map<ProjectFile, Double> positiveSeeds = new HashMap<>();
        for (var e : seedWeights.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0.0d) {
                positiveSeeds.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }
        if (positiveSeeds.isEmpty()) {
            return List.of();
        }

        // Build small candidate universe by import reach from seeds
        Set<ProjectFile> candidates = buildCandidateSet(analyzer, positiveSeeds);

        // Build adjacency map restricted to candidate set
        Map<ProjectFile, Set<ProjectFile>> adjacency = new HashMap<>();
        for (ProjectFile pf : candidates) {
            adjacency.computeIfAbsent(pf, __ -> new LinkedHashSet<>());
            Set<ProjectFile> imported = importedFilesFor(analyzer, pf);
            for (ProjectFile dep : imported) {
                if (!candidates.contains(dep)) continue;
                adjacency.computeIfAbsent(dep, __ -> new LinkedHashSet<>());
                if (reversed) {
                    adjacency.get(dep).add(pf); // reverse edge: dep -> pf (importers of dep)
                } else {
                    adjacency.get(pf).add(dep); // normal edge: pf -> dep (imports)
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
        }

        // Personalized PageRank
        double alpha = 0.85d;
        double epsilon = 1.0e-6d;
        int maxIters = 100;

        double[] rank = new double[n];
        System.arraycopy(v, 0, rank, 0, n); // start from teleport vector

        double[] next = new double[n];
        double uniform = 1.0d / n;
        for (int iter = 0; iter < maxIters; iter++) {
            // Teleport component
            for (int i = 0; i < n; i++) {
                next[i] = (1.0d - alpha) * v[i];
            }

            // Link-follow component
            double danglingMass = 0.0d;
            for (int i = 0; i < n; i++) {
                if (outdeg[i] == 0) {
                    danglingMass += rank[i];
                } else {
                    double share = alpha * rank[i] / outdeg[i];
                    for (int j : neighbors[i]) {
                        next[j] += share;
                    }
                }
            }

            // Distribute dangling mass uniformly
            if (danglingMass != 0.0d) {
                double add = alpha * danglingMass * uniform;
                for (int i = 0; i < n; i++) {
                    next[i] += add;
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

            if (diff < epsilon) {
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
}
