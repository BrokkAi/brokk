package ai.brokk.analyzer;

import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.project.ICoreProject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
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
    private static final Set<String> JS_LOG_BARE_NAMES = Set.of("log", "warn", "error", "exception");
    private static final Set<String> JS_LOG_RECEIVER_NAMES = Set.of("log", "logger", "console");
    private static final Set<String> JS_LOG_METHOD_NAMES = Set.of("log", "warn", "error", "exception");

    private final Cache<ModulePathKey, Optional<ProjectFile>> moduleResolutionCache =
            Caffeine.newBuilder().maximumSize(10_000).build();

    protected JsTsAnalyzer(ICoreProject project, Language language) {
        super(project, language);
    }

    protected JsTsAnalyzer(ICoreProject project, Language language, ProgressListener listener) {
        super(project, language, listener);
    }

    protected JsTsAnalyzer(ICoreProject project, Language language, AnalyzerState state, ProgressListener listener) {
        this(project, language, state, listener, null);
    }

    protected JsTsAnalyzer(
            ICoreProject project,
            Language language,
            AnalyzerState state,
            ProgressListener listener,
            @Nullable AnalyzerCache cache) {
        super(project, language, state, listener, cache);
    }

    @Override
    public Optional<CommentDensityStats> commentDensity(CodeUnit cu) {
        checkStale("commentDensity");
        String ext = cu.source().extension();
        if (!"js".equals(ext) && !"jsx".equals(ext) && !"ts".equals(ext) && !"tsx".equals(ext)) {
            return Optional.empty();
        }
        Map<String, CommentLineBreakdown> counts = collectCommentLineBreakdown(cu.source(), COMMENT_NODE_TYPES);
        return Optional.of(buildRollUpStats(cu, counts));
    }

    @Override
    public List<CommentDensityStats> commentDensityByTopLevel(ProjectFile file) {
        checkStale("commentDensityByTopLevel");
        String ext = file.extension();
        if (!"js".equals(ext) && !"jsx".equals(ext) && !"ts".equals(ext) && !"tsx".equals(ext)) {
            return List.of();
        }
        Map<String, CommentLineBreakdown> counts = collectCommentLineBreakdown(file, COMMENT_NODE_TYPES);
        List<CommentDensityStats> rows = new ArrayList<>();
        for (CodeUnit top : getTopLevelDeclarations(file)) {
            rows.add(buildRollUpStats(top, counts));
        }
        return List.copyOf(rows);
    }

    @Override
    public List<ExceptionHandlingSmell> findExceptionHandlingSmells(ProjectFile file, ExceptionSmellWeights weights) {
        checkStale("findExceptionHandlingSmells");
        ExceptionSmellWeights resolvedWeights = weights != null ? weights : ExceptionSmellWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file,
                            source -> detectExceptionHandlingSmells(file, root, source, resolvedWeights),
                            List.of());
                },
                List.of());
    }

    private List<ExceptionHandlingSmell> detectExceptionHandlingSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, ExceptionSmellWeights weights) {
        var catches = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(CATCH_CLAUSE), catches);
        return catches.stream()
                .map(catchClause -> analyzeCatchClause(file, catchClause, sourceContent, weights))
                .flatMap(Optional::stream)
                .sorted(java.util.Comparator.comparingInt(ExceptionHandlingSmell::score)
                        .reversed()
                        .thenComparing(f -> f.file().toString())
                        .thenComparing(ExceptionHandlingSmell::enclosingFqName))
                .toList();
    }

    private Optional<ExceptionHandlingSmell> analyzeCatchClause(
            ProjectFile file, TSNode catchClause, SourceContent sourceContent, ExceptionSmellWeights weights) {
        TSNode bodyNode = catchClause.getChildByFieldName("body");
        if (bodyNode == null) {
            bodyNode = catchClause.getNamedChildren().stream()
                    .filter(child -> STATEMENT_BLOCK.equals(child.getType()))
                    .findFirst()
                    .orElse(null);
        }
        if (bodyNode == null) {
            return Optional.empty();
        }

        int bodyStatements = countBodyExpressions(bodyNode);
        String bodyText = sourceContent.substringFrom(bodyNode);
        boolean hasAnyComment = bodyText.contains("//") || bodyText.contains("/*");
        boolean emptyBody = bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean throwPresent = hasDescendantOfType(bodyNode, THROW_STATEMENT);
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyBody(bodyNode, sourceContent) && !throwPresent;

        String catchType = extractCatchType(catchClause, sourceContent);
        int score = 0;
        var reasons = new ArrayList<String>();
        if (catchType.equals("<untyped>") || catchType.equals("any") || catchType.equals("<unknown>")) {
            score += weights.genericExceptionWeight();
            reasons.add("generic-catch:" + catchType);
        } else if (catchType.contains("Error") || catchType.contains("Exception")) {
            score += weights.genericRuntimeExceptionWeight();
            reasons.add("generic-catch:" + catchType);
        }
        if (emptyBody) {
            score += weights.emptyBodyWeight();
            reasons.add("empty-body");
        }
        if (commentOnlyBody) {
            score += weights.commentOnlyBodyWeight();
            reasons.add("comment-only-body");
        }
        if (smallBody) {
            score += weights.smallBodyWeight();
            reasons.add("small-body:" + bodyStatements);
        }
        if (logOnly) {
            score += weights.logOnlyWeight();
            reasons.add("log-only-body");
        }

        int creditStatements = Math.min(bodyStatements, Math.max(0, weights.meaningfulBodyStatementThreshold()));
        int bodyCredit = Math.max(0, weights.meaningfulBodyCreditPerStatement()) * creditStatements;
        if (bodyCredit > 0) {
            score -= bodyCredit;
            reasons.add("meaningful-body-credit:" + bodyCredit);
        }
        if (score <= 0) {
            return Optional.empty();
        }

        String enclosing = enclosingCodeUnit(
                        file,
                        catchClause.getStartPoint().getRow(),
                        catchClause.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        return Optional.of(new ExceptionHandlingSmell(
                file,
                enclosing,
                catchType,
                score,
                bodyStatements,
                List.copyOf(reasons),
                compactCatchExcerpt(sourceContent.substringFrom(catchClause))));
    }

    private static int countBodyExpressions(TSNode bodyNode) {
        int expressions = 0;
        for (int i = 0; i < bodyNode.getNamedChildCount(); i++) {
            TSNode child = bodyNode.getNamedChild(i);
            if (child != null && CATCH_BODY_MEANINGFUL_STATEMENT_TYPES.contains(child.getType())) {
                expressions++;
            }
        }
        return expressions;
    }

    private static String extractCatchType(TSNode catchClause, SourceContent sourceContent) {
        TSNode parameterNode = catchClause.getChildByFieldName("parameter");
        if (parameterNode == null) {
            return "<untyped>";
        }
        String parameterText = sourceContent.substringFrom(parameterNode).strip();
        int colon = parameterText.indexOf(':');
        if (colon < 0) {
            return "<untyped>";
        }
        String type = parameterText.substring(colon + 1).strip();
        return type.isEmpty() ? "<untyped>" : type;
    }

    private static boolean isLikelyLogOnlyBody(TSNode bodyNode, SourceContent sourceContent) {
        TSNode statement = firstNonCommentNamedChild(bodyNode, COMMENT_NODE_TYPES);
        if (statement == null || !EXPRESSION_STATEMENT.equals(statement.getType())) {
            return false;
        }
        TSNode call = findFirstNamedDescendant(statement, CALL_EXPRESSION);
        if (call == null) {
            return false;
        }
        TSNode functionNode = call.getChildByFieldName("function");
        if (functionNode == null) {
            return false;
        }
        if (IDENTIFIER.equals(functionNode.getType())) {
            String bare = sourceContent.substringFrom(functionNode).strip().toLowerCase(Locale.ROOT);
            return JS_LOG_BARE_NAMES.contains(bare);
        }
        if (!MEMBER_EXPRESSION.equals(functionNode.getType())) {
            return false;
        }
        TSNode objectNode = functionNode.getChildByFieldName("object");
        TSNode propertyNode = functionNode.getChildByFieldName("property");
        if (objectNode == null || propertyNode == null) {
            return false;
        }
        String receiver = sourceContent.substringFrom(objectNode).strip().toLowerCase(Locale.ROOT);
        String method = sourceContent.substringFrom(propertyNode).strip().toLowerCase(Locale.ROOT);
        boolean loggerLikeReceiver = JS_LOG_RECEIVER_NAMES.contains(receiver)
                || JS_LOG_RECEIVER_NAMES.stream().anyMatch(name -> receiver.endsWith("." + name));
        boolean loggerLikeMethod = JS_LOG_METHOD_NAMES.contains(method);
        return loggerLikeReceiver && loggerLikeMethod;
    }

    private static String compactCatchExcerpt(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    private CommentDensityStats buildRollUpStats(CodeUnit cu, Map<String, CommentLineBreakdown> counts) {
        CommentLineBreakdown own = counts.getOrDefault(cu.fqName(), new CommentLineBreakdown(0, 0));
        int span = rangesOf(cu).stream()
                .mapToInt(r -> r.endLine() - r.startLine() + 1)
                .sum();
        String path = cu.source().toString();
        if (!cu.isClass()) {
            return new CommentDensityStats(
                    cu.fqName(),
                    path,
                    own.headerLines(),
                    own.inlineLines(),
                    span,
                    own.headerLines(),
                    own.inlineLines(),
                    span);
        }
        int rh = own.headerLines();
        int ri = own.inlineLines();
        int rs = span;
        for (CodeUnit ch : getDirectChildren(cu)) {
            CommentDensityStats child = buildRollUpStats(ch, counts);
            rh += child.rolledUpHeaderCommentLines();
            ri += child.rolledUpInlineCommentLines();
            rs += child.rolledUpSpanLines();
        }
        return new CommentDensityStats(cu.fqName(), path, own.headerLines(), own.inlineLines(), span, rh, ri, rs);
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
    protected void extractImports(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        // Handle ES6 imports
        TSNode importNode = capturedNodesForMatch.get(CaptureNames.IMPORT_DECLARATION);

        if (importNode != null) {
            String rawSnippet = sourceContent.substringFrom(importNode).strip();
            if (!rawSnippet.isEmpty()) {
                localImportInfos.add(new ImportInfo(rawSnippet, false, null, null));
            }
        }

        // CommonJS require extraction
        extractCommonJsRequireImport(capturedNodesForMatch, sourceContent, localImportInfos);
    }

    @Override
    protected FileAnalysisAccumulator createModulesFromImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            FileAnalysisAccumulator acc) {
        if (localImportStatements.isEmpty()) {
            return acc;
        }

        String moduleShortName = file.getFileName();
        CodeUnit moduleCU = CodeUnit.module(file, modulePackageName, moduleShortName);

        if (acc.getByFqName(moduleCU.fqName()) != null) {
            return acc;
        }

        String importBlockSignature = String.join("\n", localImportStatements);
        var moduleRange = new Range(
                rootNode.getStartByte(),
                rootNode.getEndByte(),
                rootNode.getStartPoint().getRow(),
                rootNode.getEndPoint().getRow(),
                rootNode.getStartByte());

        acc.addTopLevel(moduleCU)
                .addSignature(moduleCU, importBlockSignature)
                .addRange(moduleCU, moduleRange)
                .setHasBody(moduleCU, true)
                .addSymbolIndex(moduleCU.identifier(), moduleCU)
                .addSymbolIndex(moduleCU.shortName(), moduleCU);
        return acc;
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

    @Override
    public boolean couldImportFile(List<ImportInfo> imports, ProjectFile target) {
        for (ImportInfo imp : imports) {
            Optional<String> modulePathOpt = extractModulePathFromImport(imp.rawSnippet());
            if (modulePathOpt.isEmpty()) {
                continue;
            }

            String modulePath = modulePathOpt.get();

            // External/node_modules imports (not starting with . or ..) cannot be project files
            if (!modulePath.startsWith("./") && !modulePath.startsWith("../")) {
                continue;
            }

            if (couldModulePathMatchTarget(modulePath, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a relative module path could resolve to the given target file.
     * This is a conservative text-based check that may have false positives but no false negatives.
     *
     * @param modulePath the module path from the import (e.g., "./utils/helper", "../models/User")
     * @param target the target ProjectFile to check
     * @return true if the module path could potentially resolve to the target
     */
    private boolean couldModulePathMatchTarget(String modulePath, ProjectFile target) {
        // Normalize the module path by removing leading ./ and ../ segments
        // We keep track of ".." but for matching purposes we just need the semantic path
        String normalizedPath = modulePath;
        while (normalizedPath.startsWith("./") || normalizedPath.startsWith("../")) {
            if (normalizedPath.startsWith("./")) {
                normalizedPath = normalizedPath.substring(2);
            } else if (normalizedPath.startsWith("../")) {
                normalizedPath = normalizedPath.substring(3);
            }
        }

        if (normalizedPath.isEmpty()) {
            return false;
        }

        // Get the target's relative path as a string with forward slashes
        String targetPath = target.getRelPath().toString().replace('\\', '/');

        // Check if the target path ends with the normalized import path
        // We need to check several variations:
        // 1. Direct match with extension: utils/helper matches utils/helper.ts
        // 2. Index file match: utils matches utils/index.ts

        // Strip known extensions from the import path if present
        String importBasePath = normalizedPath;
        for (String ext : KNOWN_EXTENSIONS) {
            if (importBasePath.endsWith(ext)) {
                importBasePath = importBasePath.substring(0, importBasePath.length() - ext.length());
                break;
            }
        }

        // Strip extension from target to get base path
        String targetBasePath = targetPath;
        for (String ext : KNOWN_EXTENSIONS) {
            if (targetBasePath.endsWith(ext)) {
                targetBasePath = targetBasePath.substring(0, targetBasePath.length() - ext.length());
                break;
            }
        }

        // Check 1: Direct path match (e.g., "utils/helper" matches "src/utils/helper")
        if (targetBasePath.endsWith(importBasePath)) {
            // Make sure we're matching a complete path segment
            int matchStart = targetBasePath.length() - importBasePath.length();
            if (matchStart == 0 || targetBasePath.charAt(matchStart - 1) == '/') {
                return true;
            }
        }

        // Check 2: Index file match (e.g., "utils" matches "utils/index")
        if (targetBasePath.endsWith("/index")) {
            String dirPath = targetBasePath.substring(0, targetBasePath.length() - "/index".length());
            if (dirPath.endsWith(importBasePath)) {
                int matchStart = dirPath.length() - importBasePath.length();
                if (matchStart == 0 || dirPath.charAt(matchStart - 1) == '/') {
                    return true;
                }
            }
        }

        return false;
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
        // Check for both legacy and new split-query capture names
        TSNode requireCallNode = capturedNodesForMatch.get(REQUIRE_CALL_CAPTURE_NAME);
        if (requireCallNode == null) {
            requireCallNode = capturedNodesForMatch.get("module.require_call");
        }

        if (requireCallNode == null) {
            return;
        }

        // Identify the require function identifier to verify it's a 'require' call
        TSNode requireFuncNode = capturedNodesForMatch.get(REQUIRE_FUNC_CAPTURE_NAME);
        if (requireFuncNode == null) {
            requireFuncNode = capturedNodesForMatch.get("_require_func");
        }
        if (requireFuncNode == null) {
            requireFuncNode = capturedNodesForMatch.get("require_func");
        }

        boolean isRequire = false;
        if (requireFuncNode != null) {
            String funcName = sourceContent.substringFrom(requireFuncNode).strip();
            isRequire = "require".equals(funcName);
        } else {
            String text = sourceContent.substringFrom(requireCallNode).trim();
            isRequire = text.startsWith("require") || text.contains("require(");
        }

        if (isRequire) {
            TSNode nodeToCapture = requireCallNode;

            // Search upwards for the containing statement to capture the full 'require' assignment/usage
            TSNode current = requireCallNode;
            while (current != null) {
                String type = current.getType();
                if ("lexical_declaration".equals(type)
                        || "variable_declaration".equals(type)
                        || "expression_statement".equals(type)
                        || "variable_declarator".equals(type)) {
                    nodeToCapture = current;
                    // If we found a declarator, try one more step for the full declaration
                    TSNode parent = current.getParent();
                    if (parent != null
                            && Optional.ofNullable(parent.getType()).orElse("").contains("declaration")) {
                        nodeToCapture = parent;
                    }
                    break;
                }
                if ("program".equals(type)) {
                    break;
                }
                current = current.getParent();
            }

            String requireText = sourceContent.substringFrom(nodeToCapture).strip();
            if (!requireText.isEmpty()) {
                localImportInfos.add(new ImportInfo(requireText, false, null, null));
            }
        }
    }

    @Override
    protected boolean isConstructor(
            CodeUnit candidate, @Nullable CodeUnit enclosingClass, @Nullable String captureName) {
        return "constructor".equals(candidate.identifier());
    }

    @Override
    public int computeCyclomaticComplexity(CodeUnit cu) {
        Integer result = withTreeOf(
                cu.source(),
                tree -> {
                    List<Range> ranges = rangesOf(cu);
                    if (ranges.isEmpty()) return 1;

                    Range firstRange = ranges.getFirst();
                    TSNode root = tree.getRootNode();
                    if (root == null) return 1;
                    TSNode cuNode = root.getDescendantForByteRange(firstRange.startByte(), firstRange.endByte());

                    if (cuNode == null) return 1;

                    int complexity = 1;
                    Deque<TSNode> stack = new ArrayDeque<>();
                    stack.push(cuNode);

                    while (!stack.isEmpty()) {
                        TSNode node = stack.pop();
                        String type = node.getType();

                        if (type != null) {
                            switch (type) {
                                case IF_STATEMENT,
                                        FOR_STATEMENT,
                                        FOR_IN_STATEMENT,
                                        WHILE_STATEMENT,
                                        DO_STATEMENT,
                                        CATCH_CLAUSE,
                                        TERNARY_EXPRESSION -> complexity++;
                                case SWITCH_CASE -> {
                                    // Increment for 'case ...:', but not for 'default:'
                                    if (node.getChildByFieldName("value") != null) {
                                        complexity++;
                                    }
                                }
                                case BINARY_EXPRESSION -> {
                                    TSNode operatorNode = node.getChildByFieldName("operator");
                                    if (operatorNode != null) {
                                        String operator = operatorNode.getType(); // In JS/TS grammar,
                                        // operators are often
                                        // their own types
                                        if (operator != null
                                                && (operator.equals("&&")
                                                        || operator.equals("||")
                                                        || operator.equals("??"))) {
                                            complexity++;
                                        }
                                    }
                                }
                                default -> {}
                            }
                        }

                        for (int i = 0; i < node.getChildCount(); i++) {
                            TSNode child = node.getChild(i);
                            if (child != null) {
                                stack.push(child);
                            }
                        }
                    }

                    return complexity;
                },
                1);
        return result != null ? result : 1;
    }
}
