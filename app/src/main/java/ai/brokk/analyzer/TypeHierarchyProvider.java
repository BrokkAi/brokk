package ai.brokk.analyzer;

import java.util.*;

/**
 * Capability provider for analyzers that support type hierarchy resolution.
 *
 * <p>Implementation of this interface indicates the analyzer can resolve inheritance
 * relationships (supertypes and subtypes) between {@link CodeUnit}s.
 */
public interface TypeHierarchyProvider extends CapabilityProvider {

    /**
     * Returns the direct supertypes/basetypes (non-transitive) for the given CodeUnit.
     * Implementations should return only the immediate ancestors.
     */
    List<CodeUnit> getDirectAncestors(CodeUnit cu);

    /**
     * Returns the direct subtypes/descendants (non-transitive) for the given CodeUnit.
     * Implementations should return only the immediate descendants.
     */
    Set<CodeUnit> getDirectDescendants(CodeUnit cu);

    /**
     * Returns the transitive set of supertypes/basetypes for the given CodeUnit.
     * This is computed via a fixed-point iterative traversal using getDirectAncestors:
     * - Direct ancestors are listed first, followed by their ancestors in discovery order (BFS).
     * - Duplicates are removed by fqName.
     * - Cycles are handled gracefully via a visited set.
     */
    default List<CodeUnit> getAncestors(CodeUnit cu) {
        // Seed with direct ancestors
        List<CodeUnit> direct = getDirectAncestors(cu);
        if (direct.isEmpty()) {
            return List.of();
        }

        // Fixed-point traversal: BFS over direct ancestors
        var result = new ArrayList<CodeUnit>(direct.size());
        var visited = new LinkedHashSet<String>(Math.max(16, direct.size() * 2));
        var queue = new ArrayDeque<CodeUnit>(direct.size());

        for (var d : direct) {
            if (visited.add(d.fqName())) {
                result.add(d);
                queue.add(d);
            }
        }

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            List<CodeUnit> parents = getDirectAncestors(current);
            if (parents.isEmpty()) continue;

            for (var p : parents) {
                String key = p.fqName();
                if (visited.add(key)) {
                    result.add(p);
                    queue.addLast(p);
                }
            }
        }

        return result;
    }

    /**
     * Returns the transitive set of subtypes/descendants for the given CodeUnit.
     * This is computed via a fixed-point iterative traversal using getDirectDescendants:
     * - Direct descendants are listed first, followed by their descendants in discovery order (BFS).
     * - Duplicates are removed by fqName.
     * - Cycles are handled gracefully via a visited set.
     */
    default List<CodeUnit> getDescendants(CodeUnit cu) {
        // Seed with direct descendants
        Set<CodeUnit> direct = getDirectDescendants(cu);
        if (direct.isEmpty()) {
            return List.of();
        }

        // Fixed-point traversal: BFS over direct descendants
        var result = new ArrayList<CodeUnit>(direct.size());
        var visited = new LinkedHashSet<String>(Math.max(16, direct.size() * 2));
        var queue = new ArrayDeque<CodeUnit>(direct.size());

        for (var d : direct) {
            if (visited.add(d.fqName())) {
                result.add(d);
                queue.add(d);
            }
        }

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            Set<CodeUnit> children = getDirectDescendants(current);
            if (children.isEmpty()) continue;

            for (var child : children) {
                String key = child.fqName();
                if (visited.add(key)) {
                    result.add(child);
                    queue.addLast(child);
                }
            }
        }

        return result;
    }
}
