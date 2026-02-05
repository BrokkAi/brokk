package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.treesitter.TSTree;

/**
 * Composes all analyzer-specific caches into a single helper object.
 *
 * <p>Caches in this class are designed to be transferable across analyzer snapshots.
 * The {@link #AnalyzerCache(AnalyzerCache, Set)} constructor enables incremental updates
 * by transferring only those entries that remain valid given a set of changed files.
 */
public final class AnalyzerCache {

    private final SimpleCache<ProjectFile, TSTree> trees;
    private final SimpleCache<CodeUnit, List<String>> rawSupertypes;
    private final SimpleCache<CodeUnit, List<String>> signatures;
    private final BidirectionalCache<ProjectFile, Set<CodeUnit>, Set<ProjectFile>> imports;
    private final BidirectionalCache<CodeUnit, List<CodeUnit>, Set<CodeUnit>> typeHierarchy;

    public AnalyzerCache() {
        this.trees = new CaffeineSimpleCache<>(1000);
        this.rawSupertypes = new CaffeineSimpleCache<>(5000);
        this.signatures = new CaffeineSimpleCache<>(5000);
        this.imports = new CaffeineBidirectionalCache<>(
                10000,
                (cache, resolved) -> {
                    // Logic for reverse population is handled by the caller during resolve
                },
                Collections::emptySet);
        this.typeHierarchy = new CaffeineBidirectionalCache<>(
                10000,
                (cache, supers) -> {
                    // Logic for reverse population is handled by the caller during resolve
                },
                Collections::emptyList);
    }

    /**
     * Transfer constructor for incremental updates.
     *
     * <p>Copies valid entries from the {@code previous} cache that are not affected by
     * the {@code changedFiles}. For bidirectional caches (imports and typeHierarchy),
     * only the forward mappings are transferred. Reverse mappings are left empty and
     * will be repopulated lazily as the new analyzer snapshot performs lookups.
     *
     * @param previous the cache from the preceding analyzer snapshot
     * @param changedFiles the set of files that were modified, added, or removed
     */
    public AnalyzerCache(AnalyzerCache previous, Set<ProjectFile> changedFiles) {
        this();
        previous.trees.forEach((file, tree) -> {
            if (!changedFiles.contains(file)) {
                this.trees.put(file, tree);
            }
        });

        previous.rawSupertypes.forEach((cu, supers) -> {
            if (!changedFiles.contains(cu.source())) {
                this.rawSupertypes.put(cu, List.copyOf(supers));
            }
        });

        previous.signatures.forEach((cu, sigs) -> {
            if (!changedFiles.contains(cu.source())) {
                // Ensure we create a defensive copy even if the source list is already unmodifiable.
                // List.copyOf may return the same instance for already-unmodifiable lists, so wrap first.
                List<String> copied = List.copyOf(new java.util.ArrayList<>(sigs));
                this.signatures.put(cu, copied);
            }
        });

        previous.imports.forEachForward((file, units) -> {
            if (!changedFiles.contains(file)) {
                this.imports.putForward(file, Set.copyOf(units));
            }
        });

        previous.typeHierarchy.forEachForward((cu, supers) -> {
            if (!changedFiles.contains(cu.source())) {
                this.typeHierarchy.putForward(cu, List.copyOf(supers));
            }
        });
    }

    public SimpleCache<ProjectFile, TSTree> trees() {
        return trees;
    }

    public SimpleCache<CodeUnit, List<String>> rawSupertypes() {
        return rawSupertypes;
    }

    public SimpleCache<CodeUnit, List<String>> signatures() {
        return signatures;
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
        return trees.isEmpty()
                && rawSupertypes.isEmpty()
                && signatures.isEmpty()
                && imports.isEmpty()
                && typeHierarchy.isEmpty();
    }

    /**
     * Returns a snapshot of the current state of all caches.
     */
    public CacheSnapshot snapshot() {
        return new CacheSnapshot(trees, rawSupertypes, signatures, imports, typeHierarchy);
    }

    /**
     * Record containing the current state of all caches.
     */
    public record CacheSnapshot(
            SimpleCache<ProjectFile, TSTree> trees,
            SimpleCache<CodeUnit, List<String>> rawSupertypes,
            SimpleCache<CodeUnit, List<String>> signatures,
            BidirectionalCache<ProjectFile, Set<CodeUnit>, Set<ProjectFile>> imports,
            BidirectionalCache<CodeUnit, List<CodeUnit>, Set<CodeUnit>> typeHierarchy) {}
}
