package ai.brokk.analyzer;

import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.REQUIRE_CALL_CAPTURE_NAME;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.REQUIRE_FUNC_CAPTURE_NAME;

import ai.brokk.project.IProject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;

/**
 * Shared base class for JavaScript and TypeScript analyzers.
 * Centralizes module resolution and import analysis logic.
 */
public abstract class JsTsAnalyzer extends TreeSitterAnalyzer implements ImportAnalysisProvider {

    protected record ModulePathKey(ProjectFile importingFile, String modulePath) {}

    protected static final List<String> KNOWN_EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx");

    private static final Pattern ES6_IMPORT_PATTERN = Pattern.compile("from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern ES6_SIDE_EFFECT_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern CJS_REQUIRE_PATTERN = Pattern.compile("require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    private final Cache<ModulePathKey, Optional<ProjectFile>> moduleResolutionCache =
            Caffeine.newBuilder().maximumSize(10_000).build();

    protected JsTsAnalyzer(IProject project, Language language) {
        super(project, language);
    }

    protected JsTsAnalyzer(IProject project, Language language, ProgressListener listener) {
        super(project, language, listener);
    }

    protected JsTsAnalyzer(IProject project, Language language, AnalyzerState state, ProgressListener listener) {
        super(project, language, state, listener);
    }

    @Override
    public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
        return performImportedCodeUnitsOf(file);
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return performReferencingFilesOf(file);
    }

    @Override
    public Set<String> relevantImportsFor(CodeUnit cu) {
        return getSource(cu, false)
                .map(source -> {
                    Set<String> codeIdentifiers = extractTypeIdentifiers(source);
                    List<ImportInfo> imports = importInfoOf(cu.source());

                    return imports.stream()
                            .filter(imp -> importMatchesAnyIdentifier(imp.rawSnippet(), codeIdentifiers))
                            .map(ImportInfo::rawSnippet)
                            .collect(Collectors.toSet());
                })
                .orElseGet(Set::of);
    }

    private boolean importMatchesAnyIdentifier(String importStatement, Set<String> codeIdentifiers) {
        Set<String> importIdentifiers = extractIdentifiersFromImport(importStatement);
        for (String id : importIdentifiers) {
            if (codeIdentifiers.contains(id)) {
                return true;
            }
        }
        return false;
    }

    public abstract Set<String> extractIdentifiersFromImport(String importStatement);

    @Override
    public abstract Set<String> extractTypeIdentifiers(String source);

    @Override
    protected void extractImports(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        TSNode importNode = capturedNodesForMatch.get(getLanguageSyntaxProfile().importNodeType());
        if (importNode != null && !importNode.isNull()) {
            String rawSnippet = sourceContent.substringFrom(importNode);
            localImportInfos.add(new ImportInfo(rawSnippet, false, null, null));
        }
        extractCommonJsRequireImport(capturedNodesForMatch, sourceContent, localImportInfos);
    }

    @Override
    protected void createModulesFromImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            Map<String, CodeUnit> localCuByFqName,
            List<CodeUnit> localTopLevelCUs,
            Map<CodeUnit, List<String>> localSignatures,
            Map<CodeUnit, List<Range>> localSourceRanges,
            Map<CodeUnit, List<CodeUnit>> localChildren) {
        createModulesFromJavaScriptLikeImports(
                file,
                localImportStatements,
                rootNode,
                modulePackageName,
                localCuByFqName,
                localTopLevelCUs,
                localSignatures,
                localSourceRanges);
    }

    protected static void createModulesFromJavaScriptLikeImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            Map<String, CodeUnit> localCuByFqName,
            List<CodeUnit> localTopLevelCUs,
            Map<CodeUnit, List<String>> localSignatures,
            Map<CodeUnit, List<Range>> localSourceRanges) {
        if (!localImportStatements.isEmpty()) {
            String moduleShortName = file.getFileName();
            CodeUnit moduleCU = CodeUnit.module(file, modulePackageName, moduleShortName);

            if (!localCuByFqName.containsKey(moduleCU.fqName())) {
                localTopLevelCUs.addFirst(moduleCU);
                localCuByFqName.put(moduleCU.fqName(), moduleCU);
                String importBlockSignature = String.join("\n", localImportStatements);
                localSignatures
                        .computeIfAbsent(moduleCU, k -> new ArrayList<>())
                        .add(importBlockSignature);

                var moduleRange = new Range(
                        rootNode.getStartByte(),
                        rootNode.getEndByte(),
                        rootNode.getStartPoint().getRow(),
                        rootNode.getEndPoint().getRow(),
                        rootNode.getStartByte());
                localSourceRanges
                        .computeIfAbsent(moduleCU, k -> new ArrayList<>())
                        .add(moduleRange);
            }
        }
    }

    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        Path root = getProject().getRoot();
        Set<Path> absolutePaths =
                getProject().getAllFiles().stream().map(ProjectFile::absPath).collect(Collectors.toSet());

        return importStatements.stream()
                .map(JsTsAnalyzer::extractModulePathFromImport)
                .flatMap(Optional::stream)
                .map(path -> moduleResolutionCache.get(
                        new ModulePathKey(file, path),
                        key -> Optional.ofNullable(resolveJavaScriptLikeModulePath(root, absolutePaths, file, path))))
                .flatMap(Optional::stream)
                .flatMap(resolvedFile -> getDeclarations(resolvedFile).stream())
                .collect(Collectors.toSet());
    }

    protected static Optional<String> extractModulePathFromImport(String importStatement) {
        Matcher es6Matcher = ES6_IMPORT_PATTERN.matcher(importStatement);
        if (es6Matcher.find()) return Optional.of(es6Matcher.group(1));

        Matcher sideEffectMatcher = ES6_SIDE_EFFECT_IMPORT_PATTERN.matcher(importStatement);
        if (sideEffectMatcher.find()) return Optional.of(sideEffectMatcher.group(1));

        Matcher cjsMatcher = CJS_REQUIRE_PATTERN.matcher(importStatement);
        if (cjsMatcher.find()) return Optional.of(cjsMatcher.group(1));

        return Optional.empty();
    }

    protected static @Nullable ProjectFile resolveJavaScriptLikeModulePath(
            Path projectRoot, Set<Path> absolutePaths, ProjectFile importingFile, String modulePath) {
        if (!modulePath.startsWith("./") && !modulePath.startsWith("../")) {
            return null;
        }

        Path parentDir = importingFile.absPath().getParent();
        if (parentDir == null) return null;

        Path resolvedPath = parentDir.resolve(modulePath).normalize();
        String fileName = resolvedPath.getFileName().toString();

        if (KNOWN_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            if (absolutePaths.contains(resolvedPath) && resolvedPath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(resolvedPath));
            }
        }

        String baseName = fileName;
        for (String ext : KNOWN_EXTENSIONS) {
            if (baseName.endsWith(ext)) {
                baseName = baseName.substring(0, baseName.length() - ext.length());
                break;
            }
        }
        Path basePath = resolvedPath.resolveSibling(baseName);

        List<String> fileExtensions =
                Stream.concat(Stream.of(""), KNOWN_EXTENSIONS.stream()).toList();
        for (String ext : fileExtensions) {
            Path candidatePath = ext.isEmpty() ? basePath : basePath.resolveSibling(baseName + ext);
            if (absolutePaths.contains(candidatePath) && candidatePath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(candidatePath));
            }
        }

        List<String> indexFiles = List.of("index.js", "index.jsx", "index.ts", "index.tsx");
        for (String indexFile : indexFiles) {
            Path candidatePath = resolvedPath.resolve(indexFile);
            if (absolutePaths.contains(candidatePath) && candidatePath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(candidatePath));
            }
        }

        return null;
    }

    protected static void extractCommonJsRequireImport(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        TSNode requireCallNode = capturedNodesForMatch.get(REQUIRE_CALL_CAPTURE_NAME);
        TSNode requireFuncNode = capturedNodesForMatch.get(REQUIRE_FUNC_CAPTURE_NAME);
        if (requireCallNode != null
                && !requireCallNode.isNull()
                && requireFuncNode != null
                && !requireFuncNode.isNull()) {
            String funcName = sourceContent.substringFrom(requireFuncNode).strip();
            if ("require".equals(funcName)) {
                TSNode nodeToCapture = requireCallNode;
                TSNode parent = requireCallNode.getParent();
                if (parent != null && "variable_declarator".equals(parent.getType())) {
                    TSNode decl = parent.getParent();
                    if (decl != null
                            && ("lexical_declaration".equals(decl.getType())
                                    || "variable_declaration".equals(decl.getType()))) {
                        nodeToCapture = decl;
                    }
                }

                String requireText = sourceContent.substringFrom(nodeToCapture).strip();
                if (!requireText.isEmpty()) {
                    localImportInfos.add(new ImportInfo(requireText, false, "", ""));
                }
            }
        }
    }
}
