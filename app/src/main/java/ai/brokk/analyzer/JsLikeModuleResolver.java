package ai.brokk.analyzer;

import com.github.benmanes.caffeine.cache.Cache;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public interface JsLikeModuleResolver {
    record ModulePathKey(ProjectFile importingFile, String modulePath) {}

    Cache<ModulePathKey, Optional<ProjectFile>> getModuleResolutionCache();

    static @Nullable ProjectFile resolveModulePath(
            Cache<ModulePathKey, Optional<ProjectFile>> cache,
            Path projectRoot,
            Set<Path> absolutePaths,
            ProjectFile importingFile,
            String modulePath) {
        return cache.get(
                        new ModulePathKey(importingFile, modulePath),
                        key -> Optional.ofNullable(
                                JavascriptAnalyzer.resolveJavaScriptLikeModulePath(
                                        projectRoot, absolutePaths, importingFile, modulePath)))
                .orElse(null);
    }
}
