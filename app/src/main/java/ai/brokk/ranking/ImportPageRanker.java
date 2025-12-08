package ai.brokk.ranking;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
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

        // Build universe of files: all sources known to analyzer + seeds
        Set<ProjectFile> universe = new LinkedHashSet<>();
        analyzer.getAllDeclarations().forEach(cu -> universe.add(cu.source()));
        universe.addAll(positiveSeeds.keySet());

        // Build adjacency map from imports
        Map<ProjectFile, Set<ProjectFile>> adjacency = new HashMap<>();
        for (ProjectFile pf : universe) {
            Set<ProjectFile> imported = importedFilesFor(analyzer, pf);
            // Ensure node exists
            adjacency.computeIfAbsent(pf, __ -> new LinkedHashSet<>());
            for (ProjectFile dep : imported) {
                // Only keep dependencies with known files; otherwise include and let them be dangling nodes
                adjacency.computeIfAbsent(dep, __ -> new LinkedHashSet<>());
                if (reversed) {
                    adjacency.get(dep).add(pf); // reverse edge: dep -> pf (importers of dep)
                } else {
                    adjacency.get(pf).add(dep); // normal edge: pf -> dep (imports)
                }
            }
        }

        // Include any neighbor nodes that weren't in universe explicitly
        Set<ProjectFile> allNodes = new LinkedHashSet<>(adjacency.keySet());
        adjacency.values().forEach(allNodes::addAll);
        for (ProjectFile pf : allNodes) {
            adjacency.computeIfAbsent(pf, __ -> new LinkedHashSet<>());
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
        double[] v = new double[nodes.size()];
        for (var e : positiveSeeds.entrySet()) {
            Integer idx = indexByFile.get(e.getKey());
            if (idx != null) {
                v[idx] = e.getValue() / totalSeed;
            } else {
                // Add unseen seed as dangling node
                nodes.add(e.getKey());
                int newIdx = nodes.size() - 1;
                indexByFile.put(e.getKey(), newIdx);
                v = extendVector(v, newIdx + 1);
                v[newIdx] = e.getValue() / totalSeed;
                adjacency.put(e.getKey(), new LinkedHashSet<>());
            }
        }

        // Ensure adjacency covers any newly added nodes
        for (ProjectFile pf : nodes) {
            adjacency.computeIfAbsent(pf, __ -> new LinkedHashSet<>());
        }

        // Prepare outdegree and neighbor arrays
        int n = nodes.size();
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

    private static double[] extendVector(double[] v, int newSize) {
        double[] nv = new double[newSize];
        System.arraycopy(v, 0, nv, 0, Math.min(v.length, newSize));
        return nv;
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
            // Try exact definitions first
            addDefinitions(analyzer.getDefinitions(imp), out);
            // If nothing found, broaden search
            if (out.isEmpty()) {
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
