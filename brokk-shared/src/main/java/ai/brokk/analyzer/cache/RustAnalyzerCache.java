package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Rust-specific cache extending the base analyzer cache.
 *
 * <p>Persists file package names and snapshot-level inline module resolution data to avoid repeated all-file
 * Tree-sitter scans during Rust usage analysis.
 */
public final class RustAnalyzerCache extends AnalyzerCache {
    private final Cache<ProjectFile, String> packageNamesByFileCache;
    private final Cache<ProjectFile, Map<String, Set<CodeUnit>>> definitionsByFileAndNameCache;
    private final Cache<ProjectFile, Map<RustAnalyzer.MemberKey, CodeUnit>> exactMembersByFileCache;
    private final Cache<ProjectFile, Map<RustAnalyzer.AssociatedFunctionKey, Boolean>> selfLikeAssociatedFunctionsCache;
    private final Cache<ProjectFile, Map<RustAnalyzer.FieldKey, RustAnalyzer.RustTypeRef>> structFieldTypesCache;
    private final Cache<ProjectFile, RustAnalyzer.RustUsageFacts> usageFactsByFileCache;
    private @Nullable Map<String, RustAnalyzer.InlineModuleResolution> inlineModuleIndex;
    private @Nullable RustAnalyzer.RustUsageCandidateIndex usageCandidateIndex;

    public RustAnalyzerCache() {
        super();
        this.packageNamesByFileCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.definitionsByFileAndNameCache =
                Caffeine.newBuilder().maximumSize(10_000).build();
        this.exactMembersByFileCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.selfLikeAssociatedFunctionsCache =
                Caffeine.newBuilder().maximumSize(10_000).build();
        this.structFieldTypesCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.usageFactsByFileCache = Caffeine.newBuilder().maximumSize(10_000).build();
    }

    public RustAnalyzerCache(RustAnalyzerCache previous, Set<ProjectFile> changedFiles) {
        super(previous, changedFiles);
        this.packageNamesByFileCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.definitionsByFileAndNameCache =
                Caffeine.newBuilder().maximumSize(10_000).build();
        this.exactMembersByFileCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.selfLikeAssociatedFunctionsCache =
                Caffeine.newBuilder().maximumSize(10_000).build();
        this.structFieldTypesCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.usageFactsByFileCache = Caffeine.newBuilder().maximumSize(10_000).build();

        previous.packageNamesByFileCache.asMap().forEach((file, packageName) -> {
            if (!changedFiles.contains(file)) {
                this.packageNamesByFileCache.put(file, packageName);
            }
        });
        previous.definitionsByFileAndNameCache.asMap().forEach((file, index) -> {
            if (!changedFiles.contains(file)) {
                this.definitionsByFileAndNameCache.put(file, Map.copyOf(index));
            }
        });
        previous.exactMembersByFileCache.asMap().forEach((file, index) -> {
            if (!changedFiles.contains(file)) {
                this.exactMembersByFileCache.put(file, Map.copyOf(index));
            }
        });
        previous.selfLikeAssociatedFunctionsCache.asMap().forEach((file, index) -> {
            if (!changedFiles.contains(file)) {
                this.selfLikeAssociatedFunctionsCache.put(file, Map.copyOf(index));
            }
        });
        previous.structFieldTypesCache.asMap().forEach((file, index) -> {
            if (!changedFiles.contains(file)) {
                this.structFieldTypesCache.put(file, Map.copyOf(index));
            }
        });
        previous.usageFactsByFileCache.asMap().forEach((file, facts) -> {
            if (!changedFiles.contains(file)) {
                this.usageFactsByFileCache.put(file, facts);
            }
        });
        if (changedFiles.isEmpty()) {
            this.inlineModuleIndex = previous.inlineModuleIndex;
            this.usageCandidateIndex = previous.usageCandidateIndex;
        }
    }

    public Cache<ProjectFile, String> packageNamesByFileCache() {
        return packageNamesByFileCache;
    }

    public Cache<ProjectFile, Map<String, Set<CodeUnit>>> definitionsByFileAndNameCache() {
        return definitionsByFileAndNameCache;
    }

    public Cache<ProjectFile, Map<RustAnalyzer.MemberKey, CodeUnit>> exactMembersByFileCache() {
        return exactMembersByFileCache;
    }

    public Cache<ProjectFile, Map<RustAnalyzer.AssociatedFunctionKey, Boolean>> selfLikeAssociatedFunctionsCache() {
        return selfLikeAssociatedFunctionsCache;
    }

    public Cache<ProjectFile, Map<RustAnalyzer.FieldKey, RustAnalyzer.RustTypeRef>> structFieldTypesCache() {
        return structFieldTypesCache;
    }

    public Cache<ProjectFile, RustAnalyzer.RustUsageFacts> usageFactsByFileCache() {
        return usageFactsByFileCache;
    }

    public @Nullable Map<String, RustAnalyzer.InlineModuleResolution> inlineModuleIndex() {
        return inlineModuleIndex;
    }

    public void inlineModuleIndex(Map<String, RustAnalyzer.InlineModuleResolution> inlineModuleIndex) {
        this.inlineModuleIndex = inlineModuleIndex;
    }

    public @Nullable RustAnalyzer.RustUsageCandidateIndex usageCandidateIndex() {
        return usageCandidateIndex;
    }

    public void usageCandidateIndex(RustAnalyzer.RustUsageCandidateIndex usageCandidateIndex) {
        this.usageCandidateIndex = usageCandidateIndex;
    }
}
