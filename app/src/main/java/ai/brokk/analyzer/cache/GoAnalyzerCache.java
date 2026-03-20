package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.ProjectFile;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Set;

/**
 * Go-specific cache extending the base analyzer cache.
 *
 * <p>Adds persistence for import path to package name resolutions, which is expensive
 * in Go as it may require scanning the filesystem or reading multiple files to find
 * the package declaration.
 */
public final class GoAnalyzerCache extends AnalyzerCache {

    private final Cache<String, String> importPathToPackageNameCache;

    public GoAnalyzerCache() {
        super();
        this.importPathToPackageNameCache =
                Caffeine.newBuilder().maximumSize(10_000).build();
    }

    public GoAnalyzerCache(GoAnalyzerCache previous, Set<ProjectFile> changedFiles) {
        super(previous, changedFiles);
        this.importPathToPackageNameCache =
                Caffeine.newBuilder().maximumSize(10_000).build();

        // Selective Invalidation: Copy previous entries but evict those matching changed files.
        // This prevents stale package names while preserving resolution for unaffected imports.
        previous.importPathToPackageNameCache.asMap().forEach((importPath, packageName) -> {
            boolean affected = false;
            for (ProjectFile pf : changedFiles) {
                String relPath = pf.getRelPath().toString().replace('\\', '/');
                if (relPath.contains("/" + importPath + "/") || relPath.startsWith(importPath + "/")) {
                    affected = true;
                    break;
                }
            }
            if (!affected) {
                this.importPathToPackageNameCache.put(importPath, packageName);
            }
        });
    }

    public Cache<String, String> importPathToPackageNameCache() {
        return importPathToPackageNameCache;
    }
}
