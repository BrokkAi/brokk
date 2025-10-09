package io.github.jbellis.brokk.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Groups non-text conflicts by connecting entries that share any of: - indexPath - ourPath - theirPath
 *
 * <p>This uses a union-find (disjoint-set) approach to connect rename chains across sides, producing independent groups
 * that are safe to resolve in parallel.
 */
public final class NonTextGrouper {

    private NonTextGrouper() {}

    public static List<List<Map.Entry<MergeAgent.FileConflict, MergeAgent.NonTextMetadata>>> group(
            List<Map.Entry<MergeAgent.FileConflict, MergeAgent.NonTextMetadata>> in) {
        int n = in.size();
        if (n == 0) {
            return List.of();
        }

        var dsu = new Dsu(n);
        var repByPath = new LinkedHashMap<String, Integer>(); // preserves first-seen order

        for (int i = 0; i < n; i++) {
            var meta = in.get(i).getValue();

            // Collect all relevant non-null path tokens for this entry
            var tokens = new LinkedHashSet<String>();
            if (meta.indexPath() != null && !meta.indexPath().isBlank()) tokens.add(meta.indexPath());
            if (meta.ourPath() != null && !meta.ourPath().isBlank()) tokens.add(meta.ourPath());
            if (meta.theirPath() != null && !meta.theirPath().isBlank()) tokens.add(meta.theirPath());

            for (var path : tokens) {
                Integer prev = repByPath.putIfAbsent(path, i);
                if (prev != null) {
                    dsu.union(i, prev);
                }
            }
        }

        // Build grouped output preserving input order within each group
        var groupsByRoot =
                new LinkedHashMap<Integer, List<Map.Entry<MergeAgent.FileConflict, MergeAgent.NonTextMetadata>>>();
        for (int i = 0; i < n; i++) {
            int root = dsu.find(i);
            groupsByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(in.get(i));
        }
        return new ArrayList<>(groupsByRoot.values());
    }

    private static final class Dsu {
        private final int[] parent;
        private final int[] rank;

        Dsu(int n) {
            this.parent = new int[n];
            this.rank = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
                rank[i] = 0;
            }
        }

        int find(int x) {
            if (parent[x] != x) {
                parent[x] = find(parent[x]);
            }
            return parent[x];
        }

        void union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra == rb) return;
            if (rank[ra] < rank[rb]) {
                parent[ra] = rb;
            } else if (rank[ra] > rank[rb]) {
                parent[rb] = ra;
            } else {
                parent[rb] = ra;
                rank[ra]++;
            }
        }
    }
}
