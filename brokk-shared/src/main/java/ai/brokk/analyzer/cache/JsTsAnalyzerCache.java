package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.javascript.JsTsModuleResolution;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** JS/TS-specific analyzer caches for module and path resolution. */
public final class JsTsAnalyzerCache extends AnalyzerCache {
    private final Cache<String, Optional<String>> importModuleSpecifierCache;
    private final Cache<ModulePathKey, JsTsAnalyzer.ResolutionOutcome> moduleResolutionCache;
    private final Cache<ModulePathFromBaseKey, Optional<ProjectFile>> moduleResolutionFromBaseCache;

    public JsTsAnalyzerCache() {
        super();
        this.importModuleSpecifierCache =
                Caffeine.newBuilder().maximumSize(20_000).build();
        this.moduleResolutionCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.moduleResolutionFromBaseCache =
                Caffeine.newBuilder().maximumSize(20_000).build();
    }

    public JsTsAnalyzerCache(JsTsAnalyzerCache previous, Set<ProjectFile> changedFiles) {
        super(previous, changedFiles);
        this.importModuleSpecifierCache =
                Caffeine.newBuilder().maximumSize(20_000).build();
        this.moduleResolutionCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.moduleResolutionFromBaseCache =
                Caffeine.newBuilder().maximumSize(20_000).build();

        previous.importModuleSpecifierCache.asMap().forEach(this.importModuleSpecifierCache::put);
        if (changedFiles.isEmpty()) {
            previous.moduleResolutionCache.asMap().forEach(this.moduleResolutionCache::put);
            previous.moduleResolutionFromBaseCache.asMap().forEach(this.moduleResolutionFromBaseCache::put);
            return;
        }

        var changedAbsPaths = changedFiles.stream().map(ProjectFile::absPath).collect(Collectors.toSet());
        previous.moduleResolutionFromBaseCache.asMap().forEach((key, resolved) -> {
            if (!changedFilesCouldAffect(key, changedAbsPaths)
                    && resolved.filter(file -> !changedFiles.contains(file)).isPresent()) {
                this.moduleResolutionFromBaseCache.put(key, resolved);
            }
        });
        previous.moduleResolutionCache.asMap().forEach((key, outcome) -> {
            if (!key.modulePath().startsWith("./") && !key.modulePath().startsWith("../")) {
                return;
            }
            if (changedFiles.contains(key.importingFile())) {
                return;
            }
            var resolved = outcome.resolved();
            if (resolved.isEmpty() || changedFiles.contains(resolved.get())) {
                return;
            }
            Path parent = key.importingFile().absPath().getParent();
            if (parent == null
                    || changedFilesCouldAffect(new ModulePathFromBaseKey(parent, key.modulePath()), changedAbsPaths)) {
                return;
            }
            this.moduleResolutionCache.put(key, outcome);
        });
    }

    public Cache<String, Optional<String>> importModuleSpecifierCache() {
        return importModuleSpecifierCache;
    }

    public Cache<ModulePathKey, JsTsAnalyzer.ResolutionOutcome> moduleResolutionCache() {
        return moduleResolutionCache;
    }

    public Cache<ModulePathFromBaseKey, Optional<ProjectFile>> moduleResolutionFromBaseCache() {
        return moduleResolutionFromBaseCache;
    }

    public record ModulePathKey(ProjectFile importingFile, String modulePath) {}

    public record ModulePathFromBaseKey(Path baseDir, String modulePath) {}

    private static boolean changedFilesCouldAffect(ModulePathFromBaseKey key, Set<Path> changedAbsPaths) {
        return JsTsModuleResolution.candidatePaths(key.baseDir(), key.modulePath()).stream()
                .anyMatch(changedAbsPaths::contains);
    }
}
