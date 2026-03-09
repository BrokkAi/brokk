package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Composes all analyzer-specific caches into a single helper object.
 *
 * <p>Caches in this class are designed to be transferable across analyzer snapshots.
 * The {@link #AnalyzerCache(AnalyzerCache, Set)} constructor enables incremental updates
 * by transferring only those entries that remain valid given a set of changed files.
 */
public final class AnalyzerCache {

    private final SimpleCache<ProjectFile, SourceContent> sources;
    private final SimpleCache<ProjectFile, Set<String>> typeIdentifiers;
    private final SimpleCache<CodeUnit, List<String>> rawSupertypes;
    private final BidirectionalCache<ProjectFile, Set<CodeUnit>, Set<ProjectFile>> imports;
    private final BidirectionalCache<CodeUnit, List<CodeUnit>, Set<CodeUnit>> typeHierarchy;

    public AnalyzerCache() {
        this.sources = new CaffeineSimpleCache<>(1000);
        this.typeIdentifiers = new CaffeineSimpleCache<>(10000);
        this.rawSupertypes = new CaffeineSimpleCache<>(5000);
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
        previous.sources.forEach((file, source) -> {
            if (!changedFiles.contains(file)) {
                this.sources.put(file, source);
            }
        });

        previous.typeIdentifiers.forEach((file, ids) -> {
            if (!changedFiles.contains(file)) {
                this.typeIdentifiers.put(file, Set.copyOf(ids));
            }
        });

        previous.rawSupertypes.forEach((cu, supers) -> {
            if (!changedFiles.contains(cu.source())) {
                this.rawSupertypes.put(cu, List.copyOf(supers));
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

    public SimpleCache<ProjectFile, SourceContent> sources() {
        return sources;
    }

    public SimpleCache<ProjectFile, Set<String>> typeIdentifiers() {
        return typeIdentifiers;
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
        return sources.isEmpty()
                && typeIdentifiers.isEmpty()
                && rawSupertypes.isEmpty()
                && imports.isEmpty()
                && typeHierarchy.isEmpty();
    }

    /**
     * Returns a snapshot of the current state of all caches.
     */
    public CacheSnapshot snapshot() {
        return new CacheSnapshot(sources, typeIdentifiers, rawSupertypes, imports, typeHierarchy);
    }

    /**
     * Record containing the current state of all caches.
     */
    public record CacheSnapshot(
            SimpleCache<ProjectFile, SourceContent> sources,
            SimpleCache<ProjectFile, Set<String>> typeIdentifiers,
            SimpleCache<CodeUnit, List<String>> rawSupertypes,
            BidirectionalCache<ProjectFile, Set<CodeUnit>, Set<ProjectFile>> imports,
            BidirectionalCache<CodeUnit, List<CodeUnit>, Set<CodeUnit>> typeHierarchy) {}
}
