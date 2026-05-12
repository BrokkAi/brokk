package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.ProjectFile;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** JS/TS-specific analyzer caches for module and path resolution. */
public final class JsTsAnalyzerCache extends AnalyzerCache {
    private static final List<String> KNOWN_EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx");

    private final Cache<ModulePathKey, ResolutionOutcome> moduleResolutionCache;
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

    public Cache<ModulePathKey, ResolutionOutcome> moduleResolutionCache() {
        return moduleResolutionCache;
    }

    public Cache<ModulePathFromBaseKey, Optional<ProjectFile>> moduleResolutionFromBaseCache() {
        return moduleResolutionFromBaseCache;
    }

    public record ModulePathKey(ProjectFile importingFile, String modulePath) {}

    public record ModulePathFromBaseKey(Path baseDir, String modulePath) {}

    public record ResolutionOutcome(Optional<ProjectFile> resolved, Optional<String> externalFrontier) {
        public static ResolutionOutcome resolved(ProjectFile file) {
            return new ResolutionOutcome(Optional.of(file), Optional.empty());
        }

        public static ResolutionOutcome external(String specifier) {
            return new ResolutionOutcome(Optional.empty(), Optional.of(specifier));
        }

        public static ResolutionOutcome empty() {
            return new ResolutionOutcome(Optional.empty(), Optional.empty());
        }
    }

    private static boolean changedFilesCouldAffect(ModulePathFromBaseKey key, Set<Path> changedAbsPaths) {
        return candidatePaths(key.baseDir(), key.modulePath()).stream().anyMatch(changedAbsPaths::contains);
    }

    public static List<Path> candidatePaths(Path baseDir, String modulePath) {
        Path resolvedPath = baseDir.resolve(modulePath).normalize();
        String fileName = resolvedPath.getFileName().toString();
        var candidates = new ArrayList<Path>();

        if (KNOWN_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            candidates.add(resolvedPath);
        }
        String baseName = fileName;
        for (String ext : KNOWN_EXTENSIONS) {
            if (baseName.endsWith(ext)) {
                baseName = baseName.substring(0, baseName.length() - ext.length());
                break;
            }
        }
        Path basePath = resolvedPath.resolveSibling(baseName);
        candidates.add(basePath);
        for (String ext : KNOWN_EXTENSIONS) {
            candidates.add(basePath.resolveSibling(baseName + ext));
        }
        KNOWN_EXTENSIONS.stream()
                .map(ext -> resolvedPath.resolve("index" + ext))
                .forEach(candidates::add);
        return List.copyOf(candidates);
    }
}
