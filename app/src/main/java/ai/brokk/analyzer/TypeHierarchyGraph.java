package ai.brokk.analyzer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;

/**
 * Encapsulates the type hierarchy relationships within a project.
 *
 * <p>The forward map (supertypes) tracks the direct ancestors of a given CodeUnit.
 * The reverse map (subtypes) tracks the direct descendants of a given CodeUnit.
 */
public record TypeHierarchyGraph(
        PMap<CodeUnit, List<CodeUnit>> supertypes, PMap<CodeUnit, Set<CodeUnit>> subtypes) {

    public static TypeHierarchyGraph empty() {
        return new TypeHierarchyGraph(HashTreePMap.empty(), HashTreePMap.empty());
    }

    public static TypeHierarchyGraph from(
            Map<CodeUnit, List<CodeUnit>> supertypes, Map<CodeUnit, Set<CodeUnit>> subtypes) {
        return new TypeHierarchyGraph(HashTreePMap.from(supertypes), HashTreePMap.from(subtypes));
    }

    /**
     * Returns the list of direct supertypes for the given CodeUnit.
     */
    public List<CodeUnit> supertypesOf(CodeUnit codeUnit) {
        return supertypes.getOrDefault(codeUnit, List.of());
    }

    /**
     * Returns the set of direct subtypes for the given CodeUnit.
     */
    public Set<CodeUnit> subtypesOf(CodeUnit codeUnit) {
        return subtypes.getOrDefault(codeUnit, Set.of());
    }
}
