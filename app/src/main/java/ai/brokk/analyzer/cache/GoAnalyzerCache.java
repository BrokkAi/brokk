package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.ProjectFile;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/**
 * Go-specific cache extending the base analyzer cache.
 *
 * <p>Adds persistence for import path to package name resolutions, which is expensive
 * in Go as it may require scanning the filesystem or reading multiple files to find
 * the package declaration.
 */
@NullMarked
public final class GoAnalyzerCache extends AnalyzerCache {

    private final Cache<String, String> importPathToPackageNameCache;

    public GoAnalyzerCache() {
        super();
        this.importPathToPackageNameCache =
                Caffeine.newBuilder().maximumSize(10_000).build();
    }

    public GoAnalyzerCache(GoAnalyzerCache previous, Set<ProjectFile> changedFiles) {
        super(previous, changedFiles);
        // We transfer the whole cache; entries for changed files will be overwritten
        // if the package name changes, but usually, import paths are stable.
        this.importPathToPackageNameCache =
                Caffeine.newBuilder().maximumSize(10_000).build();
        this.importPathToPackageNameCache.putAll(previous.importPathToPackageNameCache.asMap());
    }

    public Cache<String, String> importPathToPackageNameCache() {
        return importPathToPackageNameCache;
    }
}
