package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ReferenceHit;
import ai.brokk.analyzer.usages.ResolvedReceiverCandidate;
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
public class AnalyzerCache {

    private final SimpleCache<ProjectFile, SourceContent> sources;
    private final SimpleCache<ProjectFile, Set<String>> typeIdentifiers;
    private final SimpleCache<CodeUnit, List<String>> rawSupertypes;
    private final BidirectionalCache<ProjectFile, Set<CodeUnit>, Set<ProjectFile>> imports;
    private final BidirectionalCache<CodeUnit, List<CodeUnit>, Set<CodeUnit>> typeHierarchy;
    private final SimpleCache<ProjectFile, ExportIndex> exportIndex;
    private final SimpleCache<ProjectFile, ImportBinder> importBinder;
    private final SimpleCache<ProjectFile, Set<ReferenceCandidate>> references;
    private final SimpleCache<ProjectFile, Set<ResolvedReceiverCandidate>> receiverCandidates;
    private final SimpleCache<CodeUnit, Set<ReferenceHit>> usages;

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
        this.exportIndex = new CaffeineSimpleCache<>(10_000);
        this.importBinder = new CaffeineSimpleCache<>(10_000);
        this.references = new CaffeineSimpleCache<>(10_000);
        this.receiverCandidates = new CaffeineSimpleCache<>(10_000);
        this.usages = new CaffeineSimpleCache<>(10_000);
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

        previous.exportIndex.forEach((file, idx) -> {
            if (!changedFiles.contains(file)) {
                this.exportIndex.put(file, idx);
            }
        });

        previous.importBinder.forEach((file, binder) -> {
            if (!changedFiles.contains(file)) {
                this.importBinder.put(file, binder);
            }
        });

        previous.references.forEach((file, refs) -> {
            if (!changedFiles.contains(file)) {
                this.references.put(file, Set.copyOf(refs));
            }
        });

        previous.receiverCandidates.forEach((file, resolved) -> {
            if (!changedFiles.contains(file)) {
                this.receiverCandidates.put(file, Set.copyOf(resolved));
            }
        });

        previous.usages.forEach((cu, hits) -> {
            if (!changedFiles.contains(cu.source())) {
                this.usages.put(cu, Set.copyOf(hits));
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

    public SimpleCache<ProjectFile, ExportIndex> exportIndex() {
        return exportIndex;
    }

    public SimpleCache<ProjectFile, ImportBinder> importBinder() {
        return importBinder;
    }

    public SimpleCache<ProjectFile, Set<ReferenceCandidate>> references() {
        return references;
    }

    public SimpleCache<ProjectFile, Set<ResolvedReceiverCandidate>> receiverCandidates() {
        return receiverCandidates;
    }

    public SimpleCache<CodeUnit, Set<ReferenceHit>> usages() {
        return usages;
    }

    /**
     * Returns true only if ALL caches are empty.
     */
    public boolean isEmpty() {
        return sources.isEmpty()
                && typeIdentifiers.isEmpty()
                && rawSupertypes.isEmpty()
                && imports.isEmpty()
                && typeHierarchy.isEmpty()
                && exportIndex.isEmpty()
                && importBinder.isEmpty()
                && references.isEmpty()
                && receiverCandidates.isEmpty()
                && usages.isEmpty();
    }

    /**
     * Returns a snapshot of the current state of all caches.
     */
    public CacheSnapshot snapshot() {
        return new CacheSnapshot(
                sources,
                typeIdentifiers,
                rawSupertypes,
                imports,
                typeHierarchy,
                exportIndex,
                importBinder,
                references,
                receiverCandidates,
                usages);
    }

    /**
     * Record containing the current state of all caches.
     */
    public record CacheSnapshot(
            SimpleCache<ProjectFile, SourceContent> sources,
            SimpleCache<ProjectFile, Set<String>> typeIdentifiers,
            SimpleCache<CodeUnit, List<String>> rawSupertypes,
            BidirectionalCache<ProjectFile, Set<CodeUnit>, Set<ProjectFile>> imports,
            BidirectionalCache<CodeUnit, List<CodeUnit>, Set<CodeUnit>> typeHierarchy,
            SimpleCache<ProjectFile, ExportIndex> exportIndex,
            SimpleCache<ProjectFile, ImportBinder> importBinder,
            SimpleCache<ProjectFile, Set<ReferenceCandidate>> references,
            SimpleCache<ProjectFile, Set<ResolvedReceiverCandidate>> receiverCandidates,
            SimpleCache<CodeUnit, Set<ReferenceHit>> usages) {}
}
