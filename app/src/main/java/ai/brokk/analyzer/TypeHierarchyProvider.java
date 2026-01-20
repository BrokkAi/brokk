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
        return traverseHierarchy(cu, this::getDirectAncestors);
    }

    /**
     * Returns the transitive set of subtypes/descendants for the given CodeUnit.
     * This is computed via a fixed-point iterative traversal using getDirectDescendants:
     * - Direct descendants are listed first, followed by their descendants in discovery order (BFS).
     * - Duplicates are removed by fqName.
     * - Cycles are handled gracefully via a visited set.
     */
    default List<CodeUnit> getDescendants(CodeUnit cu) {
        return traverseHierarchy(cu, this::getDirectDescendants);
    }

    /**
     * Helper method to perform BFS traversal of a code unit hierarchy in either direction.
     *
     * @param cu the starting code unit
     * @param directionFn a function that returns the next level of related code units
     *                     (either direct ancestors or direct descendants)
     * @return a list of transitive related code units, ordered by discovery (BFS),
     *         with duplicates removed by fqName and cycles handled gracefully
     */
    private List<CodeUnit> traverseHierarchy(CodeUnit cu, java.util.function.Function<CodeUnit, ? extends java.util.Collection<CodeUnit>> directionFn) {
        // Seed with initial direction
        var direct = directionFn.apply(cu);
        if (direct.isEmpty()) {
            return List.of();
        }

        // Fixed-point traversal: BFS
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
            var next = directionFn.apply(current);
            if (next.isEmpty()) continue;

            for (var item : next) {
                String key = item.fqName();
                if (visited.add(key)) {
                    result.add(item);
                    queue.addLast(item);
                }
            }
        }

        return result;
    }
}
