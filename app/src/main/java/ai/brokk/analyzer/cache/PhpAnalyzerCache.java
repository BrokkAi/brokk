package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.ProjectFile;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Set;

/**
 * PHP-specific cache extending the base analyzer cache.
 *
 * <p>Persists file-scoped package names (namespaces) across snapshots to avoid
 * re-parsing the AST for unchanged files during incremental updates.
 */
public final class PhpAnalyzerCache extends AnalyzerCache {

    private final Cache<ProjectFile, String> fileScopedPackageNamesCache;

    public PhpAnalyzerCache() {
        super();
        this.fileScopedPackageNamesCache =
                Caffeine.newBuilder().maximumSize(10_000).build();
    }

    public PhpAnalyzerCache(PhpAnalyzerCache previous, Set<ProjectFile> changedFiles) {
        super(previous, changedFiles);
        this.fileScopedPackageNamesCache =
                Caffeine.newBuilder().maximumSize(10_000).build();
        previous.fileScopedPackageNamesCache.asMap().forEach((file, packageName) -> {
            if (!changedFiles.contains(file)) {
                this.fileScopedPackageNamesCache.put(file, packageName);
            }
        });
    }

    public Cache<ProjectFile, String> fileScopedPackageNamesCache() {
        return fileScopedPackageNamesCache;
    }
}
