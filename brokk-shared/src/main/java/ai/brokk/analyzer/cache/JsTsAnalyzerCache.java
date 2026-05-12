package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** JS/TS-specific analyzer caches for module and path resolution. */
public final class JsTsAnalyzerCache extends AnalyzerCache {
    private static final List<String> KNOWN_EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx");

    private final Cache<JsTsAnalyzer.ModulePathKey, JsTsAnalyzer.ResolutionOutcome> moduleResolutionCache;
    private final Cache<ModulePathFromBaseKey, Optional<ProjectFile>> moduleResolutionFromBaseCache;

    public JsTsAnalyzerCache() {
        super();
        this.moduleResolutionCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.moduleResolutionFromBaseCache =
                Caffeine.newBuilder().maximumSize(20_000).build();
    }

    public JsTsAnalyzerCache(JsTsAnalyzerCache previous, Set<ProjectFile> changedFiles) {
        super(previous, changedFiles);
        this.moduleResolutionCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.moduleResolutionFromBaseCache =
                Caffeine.newBuilder().maximumSize(20_000).build();

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

    public Cache<JsTsAnalyzer.ModulePathKey, JsTsAnalyzer.ResolutionOutcome> moduleResolutionCache() {
        return moduleResolutionCache;
    }

    public Cache<ModulePathFromBaseKey, Optional<ProjectFile>> moduleResolutionFromBaseCache() {
        return moduleResolutionFromBaseCache;
    }

    public record ModulePathFromBaseKey(Path baseDir, String modulePath) {}

    private static boolean changedFilesCouldAffect(ModulePathFromBaseKey key, Set<Path> changedAbsPaths) {
        Path resolvedPath = key.baseDir().resolve(key.modulePath()).normalize();
        String fileName = resolvedPath.getFileName().toString();

        if (KNOWN_EXTENSIONS.stream().anyMatch(fileName::endsWith) && changedAbsPaths.contains(resolvedPath)) {
            return true;
        }

        String baseName = fileName;
        for (String ext : KNOWN_EXTENSIONS) {
            if (baseName.endsWith(ext)) {
                baseName = baseName.substring(0, baseName.length() - ext.length());
                break;
            }
        }
        Path basePath = resolvedPath.resolveSibling(baseName);
        if (changedAbsPaths.contains(basePath)) {
            return true;
        }
        for (String ext : KNOWN_EXTENSIONS) {
            if (changedAbsPaths.contains(basePath.resolveSibling(baseName + ext))) {
                return true;
            }
        }

        return changedAbsPaths.stream()
                .anyMatch(path -> path.getParent() != null
                        && path.getParent().equals(resolvedPath)
                        && path.getFileName() != null
                        && path.getFileName().toString().startsWith("index.")
                        && KNOWN_EXTENSIONS.stream()
                                .anyMatch(ext -> path.getFileName().toString().equals("index" + ext)));
    }
}
