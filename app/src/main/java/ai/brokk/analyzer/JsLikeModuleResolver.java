package ai.brokk.analyzer;

import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.REQUIRE_CALL_CAPTURE_NAME;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.REQUIRE_FUNC_CAPTURE_NAME;

import com.github.benmanes.caffeine.cache.Cache;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;

public interface JsLikeModuleResolver {

    default Set<String> relevantImportsForJsLike(IAnalyzer analyzer, CodeUnit cu) {
        ImportAnalysisProvider provider = analyzer.as(ImportAnalysisProvider.class).orElse(null);
        if (provider == null) {
            return Set.of();
        }

        return analyzer.getSource(cu, false)
                .map(source -> {
                    Set<String> codeIdentifiers = extractTypeIdentifiers(source);
                    List<ImportInfo> imports = provider.importInfoOf(cu.source());

                    return imports.stream()
                            .filter(imp -> importMatchesAnyIdentifier(imp.rawSnippet(), codeIdentifiers))
                            .map(ImportInfo::rawSnippet)
                            .collect(Collectors.toSet());
                })
                .orElseGet(Set::of);
    }

    /**
     * Checks if an import statement contains any identifier that matches the given set.
     */
    default boolean importMatchesAnyIdentifier(String importStatement, Set<String> codeIdentifiers) {
        Set<String> importIdentifiers = extractIdentifiersFromImport(importStatement);
        for (String id : importIdentifiers) {
            if (codeIdentifiers.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts all identifiers (names and aliases) from an import statement string.
     */
    Set<String> extractIdentifiersFromImport(String importStatement);

    /**
     * Extracts all type/value identifiers from a source string.
     */
    Set<String> extractTypeIdentifiers(String source);
    record ModulePathKey(ProjectFile importingFile, String modulePath) {}

    List<String> KNOWN_EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx");

    Cache<ModulePathKey, Optional<ProjectFile>> getModuleResolutionCache();

    default Set<CodeUnit> resolveImportsWithCache(IAnalyzer analyzer, ProjectFile file, List<String> importStatements) {
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
                                resolveJavaScriptLikeModulePath(projectRoot, absolutePaths, importingFile, modulePath)))
                .orElse(null);
    }

    /**
     * Resolves a relative module path (starting with "./" or "../") to a ProjectFile within the project.
     * Returns null for non-relative specifiers (e.g., bare module names like "react").
     *
     * <p>Note: The containment check uses lexical path comparison (startsWith), not filesystem-level
     * resolution. Symlinks inside the repository that point outside the project root are allowed
     * and will resolve successfully. This is intentional for performance and flexibility reasons.
     *
     * @param projectRoot the project root path
     * @param importingFile the file containing the import statement
     * @param modulePath the module specifier from the import/require
     * @return the resolved ProjectFile, or null if not resolvable within the project
     */
    static @Nullable ProjectFile resolveJavaScriptLikeModulePath(
            Path projectRoot, Set<Path> absolutePaths, ProjectFile importingFile, String modulePath) {
        if (!modulePath.startsWith("./") && !modulePath.startsWith("../")) {
            return null;
        }

        Path parentDir = importingFile.absPath().getParent();
        if (parentDir == null) {
            return null;
        }

        Path resolvedPath = parentDir.resolve(modulePath).normalize();
        String fileName = resolvedPath.getFileName().toString();

        // 1. If the path already has a known extension, try it directly first
        if (KNOWN_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            if (absolutePaths.contains(resolvedPath) && resolvedPath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(resolvedPath));
            }
        }

        // 2. Strip any known extension to get the base name, then try all extension variants
        String baseName = fileName;
        for (String ext : KNOWN_EXTENSIONS) {
            if (baseName.endsWith(ext)) {
                baseName = baseName.substring(0, baseName.length() - ext.length());
                break;
            }
        }
        Path basePath = resolvedPath.resolveSibling(baseName);

        // Try base path (extensionless) and all known extensions
        List<String> fileExtensions =
                Stream.concat(Stream.of(""), KNOWN_EXTENSIONS.stream()).toList();
        for (String ext : fileExtensions) {
            Path candidatePath = ext.isEmpty() ? basePath : basePath.resolveSibling(baseName + ext);

            if (absolutePaths.contains(candidatePath) && candidatePath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(candidatePath));
            }
        }

        // 3. Try directory index files
        List<String> indexFiles = List.of("index.js", "index.jsx", "index.ts", "index.tsx");
        for (String indexFile : indexFiles) {
            Path candidatePath = resolvedPath.resolve(indexFile);
            if (absolutePaths.contains(candidatePath) && candidatePath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(candidatePath));
            }
        }

        return null;
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
            List<ImportInfo> localImportInfos) {
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
                    localImportInfos.add(new ImportInfo(requireText, false, "", ""));
                }
            }
        }
    }
}
