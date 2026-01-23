package ai.brokk.analyzer;

import com.github.benmanes.caffeine.cache.Cache;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;

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

    /**
     * Extracts CommonJS require() import statements from Tree-sitter query captures.
     * This is a shared helper for JavaScript-like languages since the #eq? predicate
     * doesn't work in JNI Tree-sitter, requiring Java-side filtering.
     *
     * @param capturedNodesForMatch map of capture names to captured nodes for the current match
     * @param sourceContent the source code content
     * @param localImportStatements list to add extracted import statements to
     */
    static void extractCommonJsRequireImport(
            Map<String, TSNode> capturedNodesForMatch,
            SourceContent sourceContent,
            List<String> localImportStatements) {
        // Use the constant names directly as strings since they're simple capture names
        String REQUIRE_CALL_CAPTURE_NAME = "require.call";
        String REQUIRE_FUNC_CAPTURE_NAME = "require.func";

        TSNode requireCallNode = capturedNodesForMatch.get(REQUIRE_CALL_CAPTURE_NAME);
        TSNode requireFuncNode = capturedNodesForMatch.get(REQUIRE_FUNC_CAPTURE_NAME);
        if (requireCallNode != null
                && !requireCallNode.isNull()
                && requireFuncNode != null
                && !requireFuncNode.isNull()) {
            String funcName = sourceContent.substringFrom(requireFuncNode).strip();
            if ("require".equals(funcName)) {
                String requireText =
                        sourceContent.substringFrom(requireCallNode).strip();
                if (!requireText.isEmpty()) {
                    localImportStatements.add(requireText);
                }
            }
        }
    }
}
