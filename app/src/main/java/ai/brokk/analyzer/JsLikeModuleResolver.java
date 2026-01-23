package ai.brokk.analyzer;

import com.github.benmanes.caffeine.cache.Cache;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

public interface JsLikeModuleResolver {
    record ModulePathKey(ProjectFile importingFile, String modulePath) {}

    Cache<ModulePathKey, Optional<ProjectFile>> getModuleResolutionCache();

    default Set<CodeUnit> resolveImportsWithCache(
            IAnalyzer analyzer, ProjectFile file, List<String> importStatements) {
        Path root = analyzer.getProject().getRoot();
        Set<Path> absolutePaths = analyzer.getProject().getAllFiles().stream()
                .map(ProjectFile::absPath)
                .collect(Collectors.toSet());

        return importStatements.stream()
                .map(JavascriptAnalyzer::extractModulePathFromImport)
                .flatMap(Optional::stream)
                .map(path -> resolveModulePath(getModuleResolutionCache(), root, absolutePaths, file, path))
                .filter(Objects::nonNull)
                .flatMap(resolvedFile -> analyzer.getDeclarations(resolvedFile).stream())
                .collect(Collectors.toSet());
    }

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
