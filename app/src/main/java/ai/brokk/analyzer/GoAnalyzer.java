package ai.brokk.analyzer;

import static ai.brokk.analyzer.go.GoTreeSitterNodeTypes.*;

import ai.brokk.project.IProject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Splitter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterGo;

public final class GoAnalyzer extends TreeSitterAnalyzer implements ImportAnalysisProvider {
    static final Logger log = LoggerFactory.getLogger(GoAnalyzer.class); // Changed to package-private

    private static final Cache<String, String> importPathToPackageNameCache =
            Caffeine.newBuilder().maximumSize(10_000).build();

    // Pattern to match both double-quoted and backtick-quoted import paths
    private static final Pattern IMPORT_PATH_PATTERN = Pattern.compile("\"([^\"]+)\"|`([^`]+)`");

    // Pattern to strip Go comments (line comments // and block comments /* */)
    private static final Pattern GO_COMMENT_PATTERN = Pattern.compile("//[^\r\n]*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/");

    private static final LanguageSyntaxProfile GO_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(TYPE_SPEC), // classLikeNodeTypes
            Set.of(FUNCTION_DECLARATION, METHOD_DECLARATION), // functionLikeNodeTypes
            Set.of("var_spec", "const_spec"), // fieldLikeNodeTypes
            Set.of(), // decoratorNodeTypes (Go doesn't have them in the typical sense)
            CaptureNames.IMPORT_DECLARATION, // importNodeType - matches @import.declaration capture in go.scm
            "name", // identifierFieldName (used as fallback if specific .name capture is missing)
            "body", // bodyFieldName (e.g. function_declaration.body -> block)
            "parameters", // parametersFieldName
            "result", // returnTypeFieldName (Go's grammar uses "result" for return types)
            "type_parameters", // typeParametersFieldName (Go generics)
            Map.of(
                    CaptureNames.FUNCTION_DEFINITION,
                    SkeletonType.FUNCTION_LIKE,
                    CaptureNames.TYPE_DEFINITION,
                    SkeletonType.CLASS_LIKE,
                    CaptureNames.VARIABLE_DEFINITION,
                    SkeletonType.FIELD_LIKE,
                    CaptureNames.CONSTANT_DEFINITION,
                    SkeletonType.FIELD_LIKE,
                    "struct.field.definition",
                    SkeletonType.FIELD_LIKE,
                    CaptureNames.METHOD_DEFINITION,
                    SkeletonType.FUNCTION_LIKE,
                    "interface.method.definition",
                    SkeletonType.FUNCTION_LIKE // Added for interface methods
                    ), // captureConfiguration
            "", // asyncKeywordNodeType (Go uses 'go' keyword, not an async modifier on func signature)
            Set.of() // modifierNodeTypes (Go visibility is by capitalization)
            );

    public GoAnalyzer(IProject project) {
        this(project, ProgressListener.NOOP);
    }

    public GoAnalyzer(IProject project, ProgressListener listener) {
        super(project, Languages.GO, listener);
    }

    private GoAnalyzer(IProject project, AnalyzerState state, ProgressListener listener) {
        super(project, Languages.GO, state, listener);
    }

    public static GoAnalyzer fromState(IProject project, AnalyzerState state, ProgressListener listener) {
        return new GoAnalyzer(project, state, listener);
    }

    @Override
    protected IAnalyzer newSnapshot(AnalyzerState state, ProgressListener listener) {
        return new GoAnalyzer(getProject(), state, listener);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterGo();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/go.scm";
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return GO_SYNTAX_PROFILE;
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        TSQuery query = getThreadLocalQuery();
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, rootNode);
        TSQueryMatch match = new TSQueryMatch();

        while (cursor.nextMatch(match)) {
            for (TSQueryCapture capture : match.getCaptures()) {
                if (CaptureNames.PACKAGE_DEFINITION.equals(query.getCaptureNameForId(capture.getIndex()))) {
                    TSNode pkgNode = capture.getNode();
                    if (pkgNode != null && !pkgNode.isNull()) {
                        // In Go, the package identifier is often the node itself if it's a 'package_identifier'
                        if ("package_identifier".equals(pkgNode.getType())) {
                            return sourceContent.substringFrom(pkgNode).trim();
                        }
                        // Fallback to 'name' field if the capture matched a parent node
                        TSNode nameNode = pkgNode.getChildByFieldName("name");
                        if (nameNode != null && !nameNode.isNull()) {
                            return sourceContent.substringFrom(nameNode).trim();
                        }
                        return sourceContent.substringFrom(pkgNode).trim();
                    }
                }
            }
        }
        log.warn("No package declaration found in Go file: {}", file);
        return "";
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file,
            String captureName,
            String simpleName,
            String packageName,
            String classChain,
            List<ScopeSegment> scopeChain,
            @Nullable TSNode definitionNode,
            SkeletonType skeletonType) {
        log.trace(
                "GoAnalyzer.createCodeUnit: File='{}', Capture='{}', SimpleName='{}', Package='{}', ClassChain='{}'",
                file.getFileName(),
                captureName,
                simpleName,
                packageName,
                classChain);

        return switch (captureName) {
            case CaptureNames.FUNCTION_DEFINITION -> {
                log.trace(
                        "Creating FN CodeUnit for Go function: File='{}', Pkg='{}', Name='{}'",
                        file.getFileName(),
                        packageName,
                        simpleName);
                yield CodeUnit.fn(file, packageName, simpleName);
            }
            case CaptureNames.TYPE_DEFINITION -> { // Covers struct_type and interface_type
                log.trace(
                        "Creating CLS CodeUnit for Go type: File='{}', Pkg='{}', Name='{}'",
                        file.getFileName(),
                        packageName,
                        simpleName);
                yield CodeUnit.cls(file, packageName, simpleName);
            }
            case CaptureNames.VARIABLE_DEFINITION, CaptureNames.CONSTANT_DEFINITION -> {
                // For package-level variables/constants, classChain should be empty.
                // We adopt a convention like "_module_.simpleName" for the short name's member part.
                if (!classChain.isEmpty()) {
                    log.warn(
                            "Expected empty classChain for package-level var/const '{}', but got '{}'. Proceeding with _module_ convention.",
                            simpleName,
                            classChain);
                }
                String fieldShortName = "_module_." + simpleName;
                log.trace(
                        "Creating FIELD CodeUnit for Go package-level var/const: File='{}', Pkg='{}', Name='{}', Resulting ShortName='{}'",
                        file.getFileName(),
                        packageName,
                        simpleName,
                        fieldShortName);
                yield CodeUnit.field(file, packageName, fieldShortName);
            }
            case CaptureNames.METHOD_DEFINITION -> {
                // simpleName is now expected to be ReceiverType.MethodName due to adjustments in TreeSitterAnalyzer
                // classChain is now expected to be ReceiverType
                log.trace(
                        "Creating FN CodeUnit for Go method: File='{}', Pkg='{}', Name='{}', ClassChain (Receiver)='{}'",
                        file.getFileName(),
                        packageName,
                        simpleName,
                        classChain);
                // CodeUnit.fn will create FQN = packageName + "." + simpleName (e.g., declpkg.MyStruct.GetFieldA)
                // The parent-child relationship will be established by TreeSitterAnalyzer using classChain.
                yield CodeUnit.fn(file, packageName, simpleName);
            }
            case "struct.field.definition" -> {
                // simpleName is FieldName (e.g., "FieldA")
                // classChain is StructName (e.g., "MyStruct")
                // We want the CodeUnit's shortName to be "StructName.FieldName" for uniqueness and parenting.
                String fieldShortName = classChain + "." + simpleName;
                log.trace(
                        "Creating FIELD CodeUnit for Go struct field: File='{}', Pkg='{}', Struct='{}', Field='{}', Resulting ShortName='{}'",
                        file.getFileName(),
                        packageName,
                        classChain,
                        simpleName,
                        fieldShortName);
                yield CodeUnit.field(file, packageName, fieldShortName);
            }
            case "interface.method.definition" -> {
                // simpleName is MethodName (e.g., "DoSomething")
                // classChain is InterfaceName (e.g., "MyInterface")
                // We want the CodeUnit's shortName to be "InterfaceName.MethodName".
                String methodShortName = classChain + "." + simpleName;
                log.trace(
                        "Creating FN CodeUnit for Go interface method: File='{}', Pkg='{}', Interface='{}', Method='{}', Resulting ShortName='{}'",
                        file.getFileName(),
                        packageName,
                        classChain,
                        simpleName,
                        methodShortName);
                yield CodeUnit.fn(file, packageName, methodShortName);
            }
            default -> {
                log.warn(
                        "Unhandled capture name in GoAnalyzer.createCodeUnit: '{}' for simple name '{}' in file '{}'. Returning null.",
                        captureName,
                        simpleName,
                        file.getFileName());
                yield null; // Explicitly yield null for unhandled cases
            }
        };
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            SourceContent sourceContent,
            String exportPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        log.trace(
                "GoAnalyzer.renderFunctionDeclaration for node type '{}', functionName '{}'. Params: '{}', Return: '{}'",
                funcNode.getType(),
                functionName,
                paramsText,
                returnTypeText);
        String rt = !returnTypeText.isEmpty() ? " " + returnTypeText : "";
        String signature;
        if (METHOD_DECLARATION.equals(funcNode.getType())) {
            TSNode receiverNode = funcNode.getChildByFieldName("receiver");
            String receiverText = "";
            if (receiverNode != null && !receiverNode.isNull()) {
                receiverText = sourceContent.substringFrom(receiverNode).trim();
            }
            // paramsText from formatParameterList already includes parentheses for regular functions
            // For methods, paramsText is for the method's own parameters, not the receiver.
            signature = String.format("func %s %s%s%s%s", receiverText, functionName, typeParamsText, paramsText, rt);
            return signature + " { " + bodyPlaceholder() + " }";
        } else if (METHOD_ELEM.equals(funcNode.getType())) { // Interface method
            // Interface methods don't have 'func', receiver, or body placeholder in their definition.
            // functionName is the method name.
            // paramsText is the parameters (e.g., "()", "(p int)").
            // rt is the return type (e.g., " string", " (int, error)").
            // exportPrefix and asyncPrefix are not applicable here as part of the signature string.
            signature = String.format("%s%s%s%s", functionName, typeParamsText, paramsText, rt);
            return signature; // No " { ... }"
        } else { // For function_declaration
            signature = String.format("func %s%s%s%s", functionName, typeParamsText, paramsText, rt);
            return signature + " { " + bodyPlaceholder() + " }";
        }
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureTextParam,
            String baseIndent) {
        // classNode is the type_declaration node.
        // We need to extract "type Name kind" (e.g., "type MyStruct struct").
        // The signatureTextParam passed from TreeSitterAnalyzer might be too broad (containing the whole body).
        TSNode typeSpecNode = null;
        for (int i = 0; i < classNode.getNamedChildCount(); i++) {
            TSNode child = classNode.getNamedChild(i);
            if (TYPE_SPEC.equals(child.getType())) {
                typeSpecNode = child;
                break;
            }
        }

        if (typeSpecNode == null || typeSpecNode.isNull()) {
            log.warn(
                    "renderClassHeader for Go: type_spec child not found in classNode (type_declaration {}). Falling back to potentially incorrect signatureTextParam.",
                    sourceContent.substringFrom(classNode).lines().findFirst().orElse(""));
            return signatureTextParam + " {";
        }

        TSNode nameNode = typeSpecNode.getChildByFieldName("name");
        TSNode kindNode = typeSpecNode.getChildByFieldName("type"); // This is the struct_type or interface_type node

        if (nameNode == null || nameNode.isNull() || kindNode == null || kindNode.isNull()) {
            log.warn(
                    "renderClassHeader for Go: name or kind node not found in type_spec for classNode {}. Falling back.",
                    sourceContent.substringFrom(classNode).lines().findFirst().orElse(""));
            return signatureTextParam + " {";
        }

        String nameText = sourceContent.substringFromBytes(nameNode.getStartByte(), nameNode.getEndByte());
        String kindText;
        String kindNodeType = kindNode.getType();

        if (STRUCT_TYPE.equals(kindNodeType)) {
            kindText = "struct";
        } else if (INTERFACE_TYPE.equals(kindNodeType)) {
            kindText = "interface";
        } else {
            log.warn(
                    "renderClassHeader for Go: Unhandled kind node type '{}' for classNode {}. Falling back.",
                    kindNodeType,
                    sourceContent
                            .substringFromBytes(classNode.getStartByte(), classNode.getEndByte())
                            .lines()
                            .findFirst()
                            .orElse(""));
            return signatureTextParam + " {";
        }

        // Go visibility is by capitalization, exportPrefix is not used here.
        String actualSignatureText =
                String.format("type %s %s", nameText, kindText).strip();
        log.trace(
                "GoAnalyzer.renderClassHeader for node {}. Constructed signature: '{}'",
                sourceContent
                        .substringFromBytes(classNode.getStartByte(), classNode.getEndByte())
                        .lines()
                        .findFirst()
                        .orElse(""),
                actualSignatureText);
        return actualSignatureText + " {";
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
    }

    @Override
    protected Optional<String> extractReceiverType(
            TSNode node, String primaryCaptureName, SourceContent sourceContent) {
        if (!"method.definition".equals(primaryCaptureName)) {
            return Optional.empty();
        }

        // Re-query the node to extract the receiver type from captures
        TSQueryCursor cursor = new TSQueryCursor();
        TSQuery currentThreadQuery = getThreadLocalQuery();
        cursor.exec(currentThreadQuery, node);
        TSQueryMatch match = new TSQueryMatch();
        Map<String, TSNode> localCaptures = new HashMap<>();

        if (cursor.nextMatch(match)) {
            for (TSQueryCapture capture : match.getCaptures()) {
                String capName = currentThreadQuery.getCaptureNameForId(capture.getIndex());
                localCaptures.put(capName, capture.getNode());
            }
        }

        TSNode receiverNode = localCaptures.get("method.receiver.type");
        if (receiverNode != null && !receiverNode.isNull()) {
            String receiverTypeText = sourceContent
                    .substringFromBytes(receiverNode.getStartByte(), receiverNode.getEndByte())
                    .trim();
            // Remove leading * for pointer receivers
            if (receiverTypeText.startsWith("*")) {
                receiverTypeText = receiverTypeText.substring(1).trim();
            }
            if (!receiverTypeText.isEmpty()) {
                return Optional.of(receiverTypeText);
            } else {
                log.warn(
                        "Go method: Receiver type text was empty for node {}. FQN might be incorrect.",
                        sourceContent.substringFromBytes(receiverNode.getStartByte(), receiverNode.getEndByte()));
            }
        } else {
            log.warn("Go method: Could not find capture for @method.receiver.type. FQN might be incorrect.");
        }

        return Optional.empty();
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        log.trace("Stage 0: getIgnoredCaptures called. Returning empty set.");
        return Set.of();
    }

    @Override
    protected boolean requiresSemicolons() {
        return false;
    }

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForGo(reference);
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
        var sourceOpt = getSource(cu, false);
        if (sourceOpt.isEmpty()) {
            return Set.of();
        }

        Set<String> extractedIdentifiers = extractTypeIdentifiers(sourceOpt.get());
        if (extractedIdentifiers.isEmpty()) {
            return Set.of();
        }

        List<ImportInfo> imports = importInfoOf(cu.source());
        if (imports.isEmpty()) {
            return Set.of();
        }

        Set<String> matchedImports = new LinkedHashSet<>();

        for (ImportInfo info : imports) {
            String identifier = info.identifier();
            String alias = info.alias();

            // Match by identifier (package name) or alias
            if (identifier != null && extractedIdentifiers.contains(identifier)) {
                matchedImports.add(info.rawSnippet());
            } else if (alias != null && extractedIdentifiers.contains(alias)) {
                matchedImports.add(info.rawSnippet());
            }
        }

        return Collections.unmodifiableSet(matchedImports);
    }

    @Override
    protected void extractImports(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        TSNode importNode = capturedNodesForMatch.get(GO_SYNTAX_PROFILE.importNodeType());
        if (importNode == null || importNode.isNull()) {
            return;
        }

        String fullSnippet = sourceContent.substringFrom(importNode).trim();
        if (fullSnippet.isEmpty()) {
            return;
        }

        // Go imports can be single: import "fmt"
        // Or grouped: import ( "fmt"\n "os" )
        // The import_declaration node in go.scm captures the whole block or single line.
        // We need to create one ImportInfo per import path, each with its own line as rawSnippet.
        String withoutComments = GO_COMMENT_PATTERN.matcher(fullSnippet).replaceAll("");

        // Split into lines to handle grouped imports - each import path gets its own ImportInfo
        for (String line : Splitter.on('\n').split(withoutComments)) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()
                    || trimmedLine.equals("import")
                    || trimmedLine.equals("(")
                    || trimmedLine.equals(")")) {
                continue;
            }

            Matcher pathMatcher = IMPORT_PATH_PATTERN.matcher(trimmedLine);
            if (!pathMatcher.find()) {
                continue;
            }

            String path = pathMatcher.group(1) != null ? pathMatcher.group(1) : pathMatcher.group(2);
            int pathStart = pathMatcher.start();

            // Look for alias or blank import prefix in this line
            String prefix = trimmedLine.substring(0, pathStart).trim();

            String identifier;
            String alias = null;

            if ("_".equals(prefix)) {
                identifier = "_"; // Blank import
            } else if (".".equals(prefix)) {
                identifier = "."; // Dot import
            } else if (!prefix.isEmpty() && !prefix.equals("import")) {
                alias = prefix;
                identifier = alias;
            } else {
                // Use simple heuristic during extractImports (last segment of path)
                // Full resolution happens later in resolveImports when state is available
                identifier = getPackageNameFromPath(path);
            }

            // Build a clean rawSnippet for this individual import.
            // Even if it was part of a group, we return it as a standalone "import ..." statement
            // so that relevantImportsFor can match it and fragments can prepend it.
            String rawSnippet = (alias != null ? alias + " " : "") + "\"" + path + "\"";
            if (!rawSnippet.startsWith("import")) {
                rawSnippet = "import " + rawSnippet;
            }

            localImportInfos.add(new ImportInfo(rawSnippet, false, identifier, alias));
        }
    }

    /**
     * Simple heuristic to get package name from import path.
     * Returns the last segment of the path (after the last '/').
     * This is used during extractImports when the analyzer state isn't ready yet.
     */
    private String getPackageNameFromPath(String importPath) {
        int lastSlash = importPath.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < importPath.length() - 1) {
            return importPath.substring(lastSlash + 1);
        }
        return importPath;
    }

    @Override
    protected Set<String> extractTypeIdentifiers(String source) {
        TSParser parser = getTSParser();
        TSTree tree = parser.parseString(null, source);
        if (tree == null || tree.getRootNode().isNull()) {
            return Collections.emptySet();
        }

        SourceContent sourceContent = SourceContent.of(source);
        TSQuery query = new TSQuery(
                getTSLanguage(), "[(type_identifier) @type (selector_expression operand: (identifier) @pkg)]");
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, tree.getRootNode());

        Set<String> identifiers = new HashSet<>();
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            for (TSQueryCapture capture : match.getCaptures()) {
                TSNode node = capture.getNode();
                if (node != null && !node.isNull()) {
                    identifiers.add(sourceContent.substringFrom(node).trim());
                }
            }
        }
        return identifiers;
    }

    /**
     * Resolves Go import statements into a set of {@link CodeUnit}s.
     * <p>
     * Go imports are package-based. This method extracts the import paths,
     * identifies the package name (usually the last segment), and resolves
     * it to the package's exported members.
     * Blank imports ('_') are skipped as they are for side-effects only.
     * <p>
     * Unlike Java, Go does not have explicit module CodeUnits. Instead, we find
     * all CodeUnits whose packageName matches the imported package.
     * <p>
     * Handles both double-quoted ("path") and backtick-quoted (`path`) import paths,
     * and ignores paths that appear inside comments.
     * <p>
     * NOTE: Regex-based comment stripping is necessary here because Tree-sitter node text
     * (the raw strings in {@code importStatements}) represents the original source bytes
     * between the node's start and end offsets, which includes comments and whitespace
     * contained within the node's range.
     */
    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        if (importStatements.isEmpty()) {
            return Set.of();
        }

        Set<String> importedPackageNames = new LinkedHashSet<>();

        for (String statement : importStatements) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("import")) continue;

            // Strip comments to prevent the path regex from matching quoted strings inside comments
            String withoutComments = GO_COMMENT_PATTERN.matcher(trimmed).replaceAll("");

            // Find all quoted paths in the statement (handles both single and grouped imports)
            Matcher m = IMPORT_PATH_PATTERN.matcher(withoutComments);
            while (m.find()) {
                if (!isBlankImport(withoutComments, m.start())) {
                    // group(1) is double-quoted, group(2) is backtick-quoted
                    String path = m.group(1) != null ? m.group(1) : m.group(2);
                    importedPackageNames.add(resolveImportPathToPackageName(path));
                }
            }
        }

        // Go doesn't create module CodeUnits like Java does.
        // Instead, find all CodeUnits whose packageName matches the imported package.
        Set<CodeUnit> resolved = new LinkedHashSet<>();
        for (String pkgName : importedPackageNames) {
            // Pattern ^pkgName\. matches fqNames starting with "pkgName."
            // since fqName = packageName + "." + shortName
            String pattern = "^" + Pattern.quote(pkgName) + "\\.";
            for (CodeUnit cu : searchDefinitions(pattern, false)) {
                if (!cu.isModule()) {
                    resolved.add(cu);
                }
            }
        }

        return Collections.unmodifiableSet(resolved);
    }

    private String resolveImportPathToPackageName(String importPath) {
        return importPathToPackageNameCache.get(importPath, path -> {
            // 1. Try to find actual source files in the project that match this import path.
            // Go import paths always use forward slashes.
            Set<ProjectFile> goFiles = getProject().getAnalyzableFiles(Languages.GO);

            for (ProjectFile pf : goFiles) {
                // Normalize the relative path to use forward slashes for comparison
                String relPath = pf.getRelPath().toString().replace('\\', '/');
                // We check if the file is inside a directory matching the import path.
                // e.g., import "mymodule/pkg" matches "vendor/mymodule/pkg/file.go"
                if (relPath.contains("/" + path + "/") || relPath.startsWith(path + "/")) {

                    // Read the file and determine its package name
                    Optional<SourceContent> content = SourceContent.read(pf);
                    if (content.isPresent()) {
                        TSTree tree = treeOf(pf);
                        if (tree != null) {
                            String pkgName =
                                    determinePackageName(pf, tree.getRootNode(), tree.getRootNode(), content.get());
                            if (!pkgName.isEmpty()) {
                                return pkgName;
                            }
                        }
                    }
                }
            }

            // 2. Fallback to last segment heuristic if no source found
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash != -1) {
                return path.substring(lastSlash + 1);
            }
            return path;
        });
    }

    private boolean isBlankImport(String text, int quoteStart) {
        // Look backwards from the start of the quoted path for a '_'
        // We look only at the immediate prefix on the same line or after the last space/newline/carriage return
        String prefix = text.substring(0, quoteStart).trim();
        int lastSpace = Math.max(Math.max(prefix.lastIndexOf(' '), prefix.lastIndexOf('\n')), prefix.lastIndexOf('\r'));
        String lastToken =
                lastSpace == -1 ? prefix : prefix.substring(lastSpace).trim();
        return "_".equals(lastToken);
    }

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        TSQuery query = getThreadLocalQuery();
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, tree.getRootNode());
        TSQueryMatch match = new TSQueryMatch();

        while (cursor.nextMatch(match)) {
            boolean sawTestMarker = false;
            TSNode nameNode = null;
            TSNode paramsNode = null;

            for (TSQueryCapture capture : match.getCaptures()) {
                String captureName = query.getCaptureNameForId(capture.getIndex());
                TSNode node = capture.getNode();
                if (node == null || node.isNull()) {
                    continue;
                }

                if (TEST_MARKER.equals(captureName)) {
                    sawTestMarker = true;
                } else if (CAPTURE_TEST_CANDIDATE_NAME.equals(captureName)) {
                    nameNode = node;
                } else if (CAPTURE_TEST_CANDIDATE_PARAMS.equals(captureName)) {
                    paramsNode = node;
                }

                if (sawTestMarker && nameNode != null && paramsNode != null) {
                    break;
                }
            }

            if (!sawTestMarker || nameNode == null || paramsNode == null) {
                continue;
            }

            // 1. Check function name starts with "Test"
            String funcName = sourceContent.substringFrom(nameNode).trim();
            if (!funcName.startsWith(TEST_FUNCTION_PREFIX)) {
                continue;
            }

            // 2. Go tests cannot be generic (no type parameters)
            TSNode parent = nameNode.getParent();
            if (parent != null && !parent.isNull()) {
                TSNode typeParams = parent.getChildByFieldName(GO_SYNTAX_PROFILE.typeParametersFieldName());
                if (typeParams != null && !typeParams.isNull()) {
                    continue;
                }
            }

            // 3. Inspect parameters: must have exactly one parameter of type testing.T or *testing.T
            // In Go: "func Test(t *testing.T)" has 1 parameter_declaration with 1 identifier.
            // "func Test(a, b *testing.T)" has 1 parameter_declaration with 2 identifiers.
            // "func Test(a T1, b T2)" has 2 parameter_declarations.
            int totalIdentifierCount = 0;
            TSNode firstParamDecl = null;

            for (int i = 0; i < paramsNode.getNamedChildCount(); i++) {
                TSNode child = paramsNode.getNamedChild(i);
                if (PARAMETER_DECLARATION.equals(child.getType())) {
                    if (firstParamDecl == null) {
                        firstParamDecl = child;
                    }
                    for (int j = 0; j < child.getNamedChildCount(); j++) {
                        if ("identifier".equals(child.getNamedChild(j).getType())) {
                            totalIdentifierCount++;
                        }
                    }
                }
            }

            if (totalIdentifierCount != 1 || firstParamDecl == null) {
                continue;
            }

            TSNode typeNode = firstParamDecl.getChildByFieldName(FIELD_TYPE);
            // Fallback for types without field name (depending on TS version/grammar)
            if (typeNode == null || typeNode.isNull()) {
                for (int i = 0; i < firstParamDecl.getNamedChildCount(); i++) {
                    TSNode child = firstParamDecl.getNamedChild(i);
                    String type = child.getType();
                    if (POINTER_TYPE.equals(type) || QUALIFIED_TYPE.equals(type) || TYPE_IDENTIFIER.equals(type)) {
                        typeNode = child;
                        break;
                    }
                }
            }

            if (typeNode == null || typeNode.isNull()) {
                continue;
            }

            String typeText = sourceContent.substringFrom(typeNode).trim();
            if (TESTING_T.equals(typeText) || POINTER_TESTING_T.equals(typeText)) {
                return true;
            }
        }

        return false;
    }
}
