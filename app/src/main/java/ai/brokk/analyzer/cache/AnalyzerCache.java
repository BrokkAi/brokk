package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.treesitter.TSTree;

/**
 * Composes all analyzer-specific caches into a single helper object.
 */
public final class AnalyzerCache {

    private final SimpleCache<ProjectFile, TSTree> trees;
    private final SimpleCache<CodeUnit, List<String>> rawSupertypes;
    private final BidirectionalCache<ProjectFile, Set<CodeUnit>, Set<ProjectFile>> imports;
    private final BidirectionalCache<CodeUnit, List<CodeUnit>, Set<CodeUnit>> typeHierarchy;

    public AnalyzerCache() {
        this.trees = new CaffeineSimpleCache<>(1000);
        this.rawSupertypes = new CaffeineSimpleCache<>(5000);
        this.imports = new ConcurrentBidirectionalCache<>(
                (cache, resolved) -> {
                    // Logic for reverse population is handled by the caller during resolve
                },
                Collections::emptySet);
        this.typeHierarchy = new ConcurrentBidirectionalCache<>(
                (cache, supers) -> {
                    // Logic for reverse population is handled by the caller during resolve
                },
                Collections::emptyList);
    }

    public SimpleCache<ProjectFile, TSTree> trees() {
        return trees;
    }

    public SimpleCache<CodeUnit, List<String>> rawSupertypes() {
        return rawSupertypes;
    }

    public BidirectionalCache<ProjectFile, Set<CodeUnit>, Set<ProjectFile>> imports() {
        return imports;
    }

    public BidirectionalCache<CodeUnit, List<CodeUnit>, Set<CodeUnit>> typeHierarchy() {
        return typeHierarchy;
    }

    /**
     * Returns true only if ALL caches are empty.
     */
    public boolean isEmpty() {
        return trees.isEmpty() && rawSupertypes.isEmpty() && imports.isEmpty() && typeHierarchy.isEmpty();
    }

    /**
     * Returns a snapshot of the current state of all caches.
     */
    public CacheSnapshot snapshot() {
        return new CacheSnapshot(trees, rawSupertypes, imports, typeHierarchy);
    }

    /**
     * Record containing the current state of all caches.
     */
    public record CacheSnapshot(
            SimpleCache<ProjectFile, TSTree> trees,
            SimpleCache<CodeUnit, List<String>> rawSupertypes,
            BidirectionalCache<ProjectFile, Set<CodeUnit>, Set<ProjectFile>> imports,
            BidirectionalCache<CodeUnit, List<CodeUnit>, Set<CodeUnit>> typeHierarchy) {}
}
