package ai.brokk.analyzer;

import static ai.brokk.analyzer.go.GoTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.analyzer.cache.GoAnalyzerCache;
import ai.brokk.project.ICoreProject;
import com.google.common.base.Splitter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;
import org.treesitter.TreeSitterGo;

public final class GoAnalyzer extends TreeSitterAnalyzer implements ImportAnalysisProvider {
    static final Logger log = LoggerFactory.getLogger(GoAnalyzer.class); // Changed to package-private

    // Pattern to match both double-quoted and backtick-quoted import paths
    private static final Pattern IMPORT_PATH_PATTERN = Pattern.compile("\"([^\"]+)\"|`([^`]+)`");

    // Pattern to strip Go comments (line comments // and block comments /* */)
    private static final Pattern GO_COMMENT_PATTERN = Pattern.compile("//[^\r\n]*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/");

    private static final Set<String> COMMENT_NODE_TYPES = Set.of(COMMENT);
    private static final Set<String> GO_LOG_RECEIVER_NAMES = Set.of("log", "logger", "zap", "slog", "fmt");
    private static final Set<String> GO_LOG_METHOD_NAMES =
            Set.of("print", "printf", "println", "debug", "info", "warn", "warning", "error", "fatal", "panic");

    private static final LanguageSyntaxProfile GO_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(TYPE_SPEC, TYPE_ALIAS), // classLikeNodeTypes
            Set.of(FUNCTION_DECLARATION, METHOD_DECLARATION), // functionLikeNodeTypes
            Set.of(VAR_SPEC, CONST_SPEC), // fieldLikeNodeTypes
            Set.of(), // constructorNodeTypes
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

    public GoAnalyzer(ICoreProject project) {
        this(project, ProgressListener.NOOP);
    }

    public GoAnalyzer(ICoreProject project, ProgressListener listener) {
        this(project, listener, new GoAnalyzerCache());
    }

    private GoAnalyzer(ICoreProject project, ProgressListener listener, GoAnalyzerCache cache) {
        super(project, Languages.GO, listener, cache);
        checkVendorDirectory(project);
    }

    private void checkVendorDirectory(ICoreProject project) {
        boolean hasVendor = project.getAnalyzableFiles(Languages.GO).stream().anyMatch(pf -> {
            String relPath = pf.getRelPath().toString().replace('\\', '/');
            return relPath.startsWith("vendor/") || relPath.contains("/vendor/");
        });

        if (hasVendor) {
            log.warn("The 'vendor/' directory was detected in your Go project. "
                    + "Analyzing dependencies in 'vendor/' significantly increases heap memory usage and analysis time. "
                    + "It is highly recommended to exclude 'vendor/' from your project configuration.");
        }
    }

    private GoAnalyzer(
            ICoreProject project, AnalyzerState state, ProgressListener listener, @Nullable GoAnalyzerCache cache) {
        super(project, Languages.GO, state, listener, cache);
    }

    public static GoAnalyzer fromState(ICoreProject project, AnalyzerState state, ProgressListener listener) {
        return new GoAnalyzer(project, state, listener, new GoAnalyzerCache());
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new GoAnalyzer(getProject(), state, listener, (GoAnalyzerCache) previousCache);
    }

    @Override
    protected AnalyzerCache createEmptyCache() {
        return new GoAnalyzerCache();
    }

    @Override
    protected AnalyzerCache createFilteredCache(AnalyzerCache previous, Set<ProjectFile> changedFiles) {
        return new GoAnalyzerCache((GoAnalyzerCache) previous, changedFiles);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterGo();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/go/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/go/imports.scm");
            case IDENTIFIERS -> Optional.of("treesitter/go/identifiers.scm");
        };
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return GO_SYNTAX_PROFILE;
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        String result = withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, rootNode, sourceContent.text());
                        TSQueryMatch match = new TSQueryMatch();

                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                if (CaptureNames.PACKAGE_NAME.equals(captureName)) {
                                    TSNode node = capture.getNode();
                                    if (node != null) {
                                        return sourceContent.substringFrom(node).trim();
                                    }
                                }
                            }
                        }
                    }
                    return "";
                },
                "");

        if (result.isEmpty()) {
            log.warn("No package declaration found in Go file: {}", file);
        }
        return result;
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
            case CaptureNames.TYPE_DEFINITION -> {
                if (!isPackageLevelDeclaration(definitionNode)) {
                    log.trace("Skipping non-package-level Go type '{}' in file '{}'", simpleName, file.getFileName());
                    yield null;
                }
                if (skeletonType == SkeletonType.FIELD_LIKE) {
                    log.trace(
                            "Creating FIELD CodeUnit for Go type alias: File='{}', Pkg='{}', Name='{}'",
                            file.getFileName(),
                            packageName,
                            simpleName);
                    yield CodeUnit.field(file, packageName, "_module_." + simpleName);
                } else {
                    log.trace(
                            "Creating CLS CodeUnit for Go type: File='{}', Pkg='{}', Name='{}'",
                            file.getFileName(),
                            packageName,
                            simpleName);
                    yield CodeUnit.cls(file, packageName, simpleName);
                }
            }
            case CaptureNames.VARIABLE_DEFINITION, CaptureNames.CONSTANT_DEFINITION -> {
                if (!isPackageLevelDeclaration(definitionNode)) {
                    log.trace(
                            "Skipping non-package-level Go var/const '{}' in file '{}'",
                            simpleName,
                            file.getFileName());
                    yield null;
                }
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
                if (!isPackageLevelDeclaration(definitionNode)) {
                    log.trace(
                            "Skipping non-package-level Go struct field '{}' in file '{}'",
                            simpleName,
                            file.getFileName());
                    yield null;
                }
                if (classChain.isEmpty()) {
                    log.trace(
                            "Skipping Go struct field '{}' without named parent type in file '{}'",
                            simpleName,
                            file.getFileName());
                    yield null;
                }
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
                if (!isPackageLevelDeclaration(definitionNode)) {
                    log.trace(
                            "Skipping non-package-level Go interface method '{}' in file '{}'",
                            simpleName,
                            file.getFileName());
                    yield null;
                }
                if (classChain.isEmpty()) {
                    log.trace(
                            "Skipping Go interface method '{}' without named parent type in file '{}'",
                            simpleName,
                            file.getFileName());
                    yield null;
                }
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

    private static boolean isPackageLevelDeclaration(@Nullable TSNode definitionNode) {
        TSNode current = definitionNode;
        while (current != null) {
            String nodeType = current.getType();
            if (FUNCTION_DECLARATION.equals(nodeType)
                    || METHOD_DECLARATION.equals(nodeType)
                    || "func_literal".equals(nodeType)) {
                return false;
            }
            if ("source_file".equals(nodeType)) {
                return true;
            }
            current = current.getParent();
        }
        return true;
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
            if (receiverNode != null) {
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
    protected TSNode adjustSourceRangeNode(TSNode definitionNode, String captureName) {
        if (CaptureNames.TYPE_DEFINITION.equals(captureName)) {
            TSNode parent = definitionNode.getParent();
            if (parent != null && TYPE_DECLARATION.equals(parent.getType())) {
                return parent;
            }
        }
        return definitionNode;
    }

    @Override
    protected SkeletonType refineSkeletonType(
            String captureName, TSNode definitionNode, LanguageSyntaxProfile profile) {
        if (CaptureNames.TYPE_DEFINITION.equals(captureName)) {
            if (TYPE_ALIAS.equals(definitionNode.getType())) {
                return SkeletonType.FIELD_LIKE;
            }
        }
        return super.refineSkeletonType(captureName, definitionNode, profile);
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureTextParam,
            String baseIndent) {
        TSNode nameNode = classNode.getChildByFieldName("name");
        TSNode kindNode = classNode.getChildByFieldName("type");

        if (nameNode == null || kindNode == null) {
            log.warn(
                    "renderClassHeader for Go: name or kind node not found in type_spec {}. Falling back.",
                    sourceContent.substringFrom(classNode).lines().findFirst().orElse(""));
            return signatureTextParam + " {";
        }

        String nameText = sourceContent.substringFrom(nameNode);
        String kindNodeType = kindNode.getType();

        if (STRUCT_TYPE.equals(kindNodeType)) {
            return String.format("type %s struct {", nameText).strip();
        } else if (INTERFACE_TYPE.equals(kindNodeType)) {
            return String.format("type %s interface {", nameText).strip();
        } else {
            String kindSource = sourceContent.substringFrom(kindNode);
            return String.format("type %s %s {", nameText, kindSource).strip();
        }
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected List<CodeUnit> orderChildrenForSkeleton(CodeUnit parent, List<CodeUnit> children) {
        if (!parent.isClass() || children.size() < 2) {
            return children;
        }

        var ordered = new ArrayList<CodeUnit>(children.size());
        for (var child : children) {
            if (child.isField()) {
                ordered.add(child);
            }
        }
        for (var child : children) {
            if (!child.isField()) {
                ordered.add(child);
            }
        }
        return ordered;
    }

    @Override
    protected void postProcessFileAnalysis(
            ProjectFile file,
            FileAnalysisAccumulator acc,
            TSNode rootNode,
            SourceContent sourceContent,
            Map<CodeUnit, String> cuToCaptureName) {
        replicateAnonymousFieldSubtrees(acc, rootNode, sourceContent);

        for (var cu : List.copyOf(acc.topLevelCUs())) {
            if (!cu.isFunction()) {
                continue;
            }

            String shortName = cu.shortName();
            int lastDot = shortName.lastIndexOf('.');
            if (lastDot <= 0) {
                continue;
            }

            String parentShortName = shortName.substring(0, lastDot);
            String parentFqName =
                    cu.packageName().isEmpty() ? parentShortName : cu.packageName() + "." + parentShortName;
            CodeUnit parent = acc.getByFqName(parentFqName);
            if (parent == null || !parent.isClass()) {
                continue;
            }

            acc.moveTopLevelToChild(cu, parent);
        }
    }

    private void replicateAnonymousFieldSubtrees(
            FileAnalysisAccumulator acc, TSNode rootNode, SourceContent sourceContent) {
        try (var cursor = new TSTreeCursor(rootNode)) {
            while (true) {
                TSNode current = cursor.currentNode();
                if (current == null) {
                    return;
                }
                if (TYPE_SPEC.equals(current.getType()) && isPackageLevelDeclaration(current)) {
                    TSNode nameNode = current.getChildByFieldName("name");
                    TSNode typeNode = current.getChildByFieldName(FIELD_TYPE);
                    if (nameNode != null
                            && typeNode != null
                            && (STRUCT_TYPE.equals(typeNode.getType()) || INTERFACE_TYPE.equals(typeNode.getType()))) {
                        String typeName = sourceContent.substringFrom(nameNode).trim();
                        CodeUnit parent = acc.topLevelCUs().stream()
                                .filter(CodeUnit::isClass)
                                .filter(cu -> cu.shortName().equals(typeName))
                                .findFirst()
                                .orElse(null);
                        if (parent != null) {
                            replicateAnonymousTypeMembers(typeNode, List.of(parent), acc, sourceContent);
                        }
                    }
                }
                if (!gotoNextDepthFirst(cursor, true)) {
                    return;
                }
            }
        }
    }

    private void replicateAnonymousTypeMembers(
            TSNode typeNode, List<CodeUnit> parents, FileAnalysisAccumulator acc, SourceContent sourceContent) {
        if (parents.isEmpty()) {
            return;
        }
        if (STRUCT_TYPE.equals(typeNode.getType())) {
            for (TSNode fieldDecl : directMembers(typeNode, FIELD_DECLARATION)) {
                List<String> fieldNames = fieldNames(fieldDecl, sourceContent);
                if (fieldNames.isEmpty()) {
                    continue;
                }
                List<CodeUnit> fieldParents = ensureFieldChildren(parents, fieldNames, acc);
                TSNode nestedType = fieldDecl.getChildByFieldName(FIELD_TYPE);
                if (nestedType != null
                        && (STRUCT_TYPE.equals(nestedType.getType()) || INTERFACE_TYPE.equals(nestedType.getType()))) {
                    replicateAnonymousTypeMembers(nestedType, fieldParents, acc, sourceContent);
                }
            }
            return;
        }

        if (INTERFACE_TYPE.equals(typeNode.getType())) {
            for (TSNode methodElem : directMembers(typeNode, METHOD_ELEM)) {
                TSNode nameNode = methodElem.getChildByFieldName("name");
                if (nameNode == null) {
                    continue;
                }
                String methodName = sourceContent.substringFrom(nameNode).trim();
                if (methodName.isBlank()) {
                    continue;
                }
                ensureMethodChildren(parents, methodName, acc);
            }
        }
    }

    private List<TSNode> directMembers(TSNode typeNode, String memberType) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode child : typeNode.getNamedChildren()) {
            if (memberType.equals(child.getType())) {
                result.add(child);
                continue;
            }
            for (TSNode grandchild : child.getNamedChildren()) {
                if (memberType.equals(grandchild.getType())) {
                    result.add(grandchild);
                }
            }
        }
        return result;
    }

    private List<String> fieldNames(TSNode fieldDecl, SourceContent sourceContent) {
        return fieldDecl.getNamedChildren().stream()
                .filter(child -> FIELD_IDENTIFIER.equals(child.getType()))
                .map(sourceContent::substringFrom)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .toList();
    }

    private List<CodeUnit> ensureFieldChildren(
            List<CodeUnit> parents, List<String> fieldNames, FileAnalysisAccumulator acc) {
        List<CodeUnit> result = new ArrayList<>(parents.size() * fieldNames.size());
        for (CodeUnit parent : parents) {
            for (String fieldName : fieldNames) {
                CodeUnit child = existingChild(parent, fieldName, acc);
                if (child == null) {
                    CodeUnit template = templateChild(parents, fieldName, acc);
                    if (template == null) {
                        continue;
                    }
                    child = cloneChild(template, parent, fieldName, acc);
                }
                result.add(child);
            }
        }
        return result;
    }

    private void ensureMethodChildren(List<CodeUnit> parents, String methodName, FileAnalysisAccumulator acc) {
        for (CodeUnit parent : parents) {
            if (existingChild(parent, methodName, acc) != null) {
                continue;
            }
            CodeUnit template = templateChild(parents, methodName, acc);
            if (template != null) {
                cloneChild(template, parent, methodName, acc);
            }
        }
    }

    private @Nullable CodeUnit existingChild(CodeUnit parent, String childName, FileAnalysisAccumulator acc) {
        String childShortName = parent.shortName() + "." + childName;
        String childFqName =
                parent.packageName().isEmpty() ? childShortName : parent.packageName() + "." + childShortName;
        return acc.getByFqName(childFqName);
    }

    private @Nullable CodeUnit templateChild(List<CodeUnit> parents, String childName, FileAnalysisAccumulator acc) {
        for (CodeUnit parent : parents) {
            CodeUnit template = existingChild(parent, childName, acc);
            if (template != null) {
                return template;
            }
        }
        return null;
    }

    private CodeUnit cloneChild(CodeUnit template, CodeUnit parent, String childName, FileAnalysisAccumulator acc) {
        String clonedShortName = parent.shortName() + "." + childName;
        CodeUnit cloned = new CodeUnit(
                template.source(),
                template.kind(),
                template.packageName(),
                clonedShortName,
                template.signature(),
                template.isSynthetic());
        acc.addChild(parent, cloned).registerCodeUnit(cloned);
        acc.getSignatures(template).forEach(signature -> acc.addSignature(cloned, signature));
        acc.setHasBody(cloned, acc.getHasBody(template, false));
        acc.setIsTypeAlias(cloned, acc.getIsTypeAlias(template, false));
        return cloned;
    }

    @Override
    protected String buildClassChain(TSNode node, TSNode rootNode, SourceContent sourceContent) {
        Deque<String> segments = new ArrayDeque<>();
        TSNode current = node.getParent();
        TSNode immediateParent = current;
        while (current != null && !current.equals(rootNode)) {
            if (isClassLike(current)) {
                final TSNode parent = current;
                extractSimpleName(parent, sourceContent).ifPresent(name -> {
                    if (!name.isBlank()) {
                        segments.addFirst(determineClassChainSegmentName(parent.getType(), name));
                    }
                });
            } else if (FIELD_DECLARATION.equals(current.getType())
                    && !current.equals(immediateParent)
                    && hasEnclosingNamedType(current, rootNode)) {
                extractAnonymousStructContainerName(current, sourceContent).ifPresent(segments::addFirst);
            }
            current = current.getParent();
        }
        return String.join(".", segments);
    }

    private boolean hasEnclosingNamedType(TSNode node, TSNode rootNode) {
        TSNode current = node.getParent();
        while (current != null && !current.equals(rootNode)) {
            if (isClassLike(current)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    @Override
    protected String formatFieldSignature(
            TSNode fieldNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String simpleName,
            String baseIndent,
            ProjectFile file) {
        // Go struct fields are usually captured as field_identifiers (one match per name).
        // The parent field_declaration provides the shared type and optional tag.
        if (FIELD_IDENTIFIER.equals(fieldNode.getType())) {
            TSNode fieldDeclNode = fieldNode.getParent();
            while (fieldDeclNode != null && !FIELD_DECLARATION.equals(fieldDeclNode.getType())) {
                fieldDeclNode = fieldDeclNode.getParent();
            }

            if (fieldDeclNode != null
                    && FIELD_DECLARATION.equals(fieldDeclNode.getType())
                    && isInsideStructType(fieldDeclNode)) {
                String fieldName = sourceContent.substringFrom(fieldNode).trim();

                TSNode typeNode = fieldDeclNode.getChildByFieldName(FIELD_TYPE);
                TSNode tagNode = fieldDeclNode.getChildByFieldName("tag");

                String tagText = (tagNode != null)
                        ? " " + sourceContent.substringFrom(tagNode).trim()
                        : "";

                String typeText = summarizeAnonymousFieldType(typeNode, sourceContent)
                        .orElseGet(() -> typeNode != null
                                ? sourceContent.substringFrom(typeNode).trim()
                                : "");

                if (!typeText.isEmpty()) {
                    return (baseIndent + fieldName + " " + typeText + tagText).stripTrailing();
                }
            }
        } else if (FIELD_DECLARATION.equals(fieldNode.getType()) && isInsideStructType(fieldNode)) {
            // Defensive: if the query ever captures the whole field_declaration node, still render a single-field
            // signature based on simpleName (which is the field identifier for this CodeUnit).
            TSNode typeNode = fieldNode.getChildByFieldName(FIELD_TYPE);
            TSNode tagNode = fieldNode.getChildByFieldName("tag");

            String typeText = summarizeAnonymousFieldType(typeNode, sourceContent)
                    .orElseGet(() -> typeNode != null
                            ? sourceContent.substringFrom(typeNode).trim()
                            : "");
            String tagText = (tagNode != null)
                    ? " " + sourceContent.substringFrom(tagNode).trim()
                    : "";

            if (!typeText.isEmpty()) {
                return (baseIndent + simpleName + " " + typeText + tagText).stripTrailing();
            }
        }

        // Logic for package-level var/const/type-alias specs
        String fieldNodeType = fieldNode.getType();
        TSNode specNode = fieldNode;
        String identifier = simpleName;
        if (simpleName.contains("._module_.")) {
            identifier = simpleName.substring(simpleName.lastIndexOf('.') + 1);
        }

        if (VAR_DECLARATION.equals(fieldNodeType) || CONST_DECLARATION.equals(fieldNodeType)) {
            // Find the correct spec node containing this identifier
            for (TSNode child : fieldNode.getChildren()) {
                if (VAR_SPEC.equals(child.getType()) || CONST_SPEC.equals(child.getType())) {
                    TSNode childNameList = child.getChildByFieldName("name");
                    if (childNameList == null) {
                        childNameList = child;
                    }
                    for (TSNode nameNode : childNameList.getNamedChildren()) {
                        if ("identifier".equals(nameNode.getType())
                                && identifier.equals(
                                        sourceContent.substringFrom(nameNode).trim())) {
                            specNode = child;
                            fieldNodeType = specNode.getType();
                            break;
                        }
                    }
                    if (!Objects.equals(specNode, fieldNode)) {
                        break;
                    }
                }
            }
        }

        if (VAR_SPEC.equals(fieldNodeType) || CONST_SPEC.equals(fieldNodeType)) {
            // In some Go TS versions, identifiers are children of a 'name' field; in others, direct children.
            TSNode nameList = specNode.getChildByFieldName("name");
            if (nameList == null) {
                nameList = specNode; // Fallback to searching the spec node itself
            }

            TSNode valueList = specNode.getChildByFieldName("value");

            // Count identifiers to detect tuples/multi-assignments
            int identifierCount = 0;
            for (TSNode namedChild : nameList.getNamedChildren()) {
                if ("identifier".equals(namedChild.getType())) {
                    identifierCount++;
                }
            }

            // If there are multiple names or values (tuples), consider it a complex expression and truncate
            if (identifierCount > 1 || (valueList != null && valueList.getNamedChildCount() > 1)) {
                TSNode typeNode = specNode.getChildByFieldName("type");
                String typeStr = (typeNode != null)
                        ? " " + sourceContent.substringFrom(typeNode).trim()
                        : "";
                return (baseIndent + identifier + typeStr).trim();
            }

            // Single identifier case
            TSNode specificValueNode = null;
            if (valueList != null) {
                if ("expression_list".equals(valueList.getType())) {
                    if (valueList.getNamedChildCount() == 1) {
                        specificValueNode = valueList.getNamedChild(0);
                    }
                } else {
                    specificValueNode = valueList;
                }
            }

            boolean isLiteral = false;
            if (specificValueNode != null) {
                String valType = specificValueNode.getType();
                if (valType != null) {
                    String valText =
                            sourceContent.substringFrom(specificValueNode).trim();
                    isLiteral = (valType.endsWith("_literal")
                                    && !valType.equals("composite_literal")
                                    && !valType.equals("func_literal"))
                            || valType.equals("true")
                            || valType.equals("false")
                            || valText.equals("iota");
                }
            } else if (valueList == null && CONST_SPEC.equals(fieldNodeType)) {
                // In Go const blocks, missing values imply iota or inherited values.
                // We treat these as literals.
                isLiteral = true;
            }

            TSNode typeNode = specNode.getChildByFieldName("type");
            String typeStr = (typeNode != null)
                    ? " " + sourceContent.substringFrom(typeNode).trim()
                    : "";

            if (isLiteral) {
                String valuePart = (specificValueNode != null)
                        ? " = " + sourceContent.substringFrom(specificValueNode)
                        : (CONST_SPEC.equals(fieldNodeType) && valueList == null
                                ? " = iota" // Special case for rendering inherited const values in skeletons
                                : "");

                // Special case for test parity: if we have a type but no explicit value node in source (inherited
                // const)
                // just show name and type unless it's the iota line.
                if (specificValueNode == null && !typeStr.isEmpty()) {
                    return (baseIndent + identifier + typeStr).trim();
                }

                return (baseIndent + identifier + typeStr + valuePart).trim();
            } else {
                return (baseIndent + identifier + typeStr).trim();
            }
        }

        return (baseIndent + signatureText).trim();
    }

    private static boolean isInsideStructType(TSNode node) {
        TSNode current = node;
        while (current != null) {
            if (STRUCT_TYPE.equals(current.getType())) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private String summarizeInlineAnonymousType(TSNode typeNode, SourceContent sourceContent) {
        String typeKeyword = typeNode.getType();
        if (STRUCT_TYPE.equals(typeKeyword)) {
            return "struct { " + bodyPlaceholder() + " }";
        }
        if (INTERFACE_TYPE.equals(typeKeyword)) {
            return "interface { " + bodyPlaceholder() + " }";
        }
        return sourceContent.substringFrom(typeNode).trim();
    }

    private Optional<String> summarizeAnonymousFieldType(@Nullable TSNode typeNode, SourceContent sourceContent) {
        if (typeNode == null) {
            return Optional.empty();
        }
        return switch (typeNode.getType()) {
            case STRUCT_TYPE, INTERFACE_TYPE -> Optional.of(summarizeInlineAnonymousType(typeNode, sourceContent));
            default -> Optional.empty();
        };
    }

    private static Optional<String> extractAnonymousStructContainerName(TSNode fieldDeclaration, SourceContent source) {
        String name = null;
        boolean hasAnonymousStructType = false;

        for (TSNode child : fieldDeclaration.getNamedChildren()) {
            String childType = child.getType();
            if (STRUCT_TYPE.equals(childType) || INTERFACE_TYPE.equals(childType)) {
                hasAnonymousStructType = true;
            } else if (name == null && (FIELD_IDENTIFIER.equals(childType) || "identifier".equals(childType))) {
                String candidate = source.substringFrom(child).trim();
                if (!candidate.isBlank()) {
                    name = candidate;
                }
            }
        }

        return hasAnonymousStructType && name != null ? Optional.of(name) : Optional.empty();
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

        Map<String, TSNode> localCaptures = new HashMap<>();
        // Re-query the node to extract the receiver type from captures
        withCachedQuery(QueryType.DEFINITIONS, query -> {
            try (TSQueryCursor cursor = new TSQueryCursor()) {
                cursor.exec(query, node, sourceContent.text());
                TSQueryMatch match = new TSQueryMatch();

                if (cursor.nextMatch(match)) {
                    for (TSQueryCapture capture : match.getCaptures()) {
                        String capName = query.getCaptureNameForId(capture.getIndex());
                        localCaptures.put(capName, capture.getNode());
                    }
                }
            }
        });

        TSNode receiverNode = localCaptures.get("method.receiver.type");
        if (receiverNode != null) {
            String receiverTypeText = normalizeReceiverTypeText(
                    sourceContent.substringFrom(receiverNode).trim());
            if (!receiverTypeText.isEmpty()) {
                return Optional.of(receiverTypeText);
            } else {
                log.warn(
                        "Go method: Receiver type text was empty for node {}. FQN might be incorrect.",
                        sourceContent.substringFrom(receiverNode));
            }
        } else {
            log.warn("Go method: Could not find capture for @method.receiver.type. FQN might be incorrect.");
        }

        return Optional.empty();
    }

    private static String normalizeReceiverTypeText(String receiverTypeText) {
        if (receiverTypeText.startsWith("*")) {
            receiverTypeText = receiverTypeText.substring(1).trim();
        }
        int genericStart = receiverTypeText.indexOf('[');
        if (genericStart >= 0) {
            receiverTypeText = receiverTypeText.substring(0, genericStart).trim();
        }
        return receiverTypeText;
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
    public List<String> getTestModules(Collection<ProjectFile> files) {
        return getTestModulesStatic(files);
    }

    public static List<String> getTestModulesStatic(Collection<ProjectFile> files) {
        return files.stream()
                .map(file -> formatTestModule(file.getRelPath().getParent()))
                .distinct()
                .sorted()
                .toList();
    }

    public static String formatTestModule(@Nullable Path parent) {
        if (parent == null) return ".";

        // Normalize separators: backslashes -> forward slashes
        String unixPath = parent.toString().replace('\\', '/');

        // Consolidate root checks
        if (unixPath.isEmpty() || unixPath.equals(".") || unixPath.equals("./") || unixPath.equals("/")) {
            return ".";
        }

        // Ensure subdirectories start with "./" (Go requires this for local packages)
        if (unixPath.startsWith("./")) {
            return unixPath;
        }
        if (unixPath.startsWith("/")) {
            return "." + unixPath;
        }
        return "./" + unixPath;
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
    public boolean couldImportFile(List<ImportInfo> imports, ProjectFile target) {
        if (imports.isEmpty()) {
            return false;
        }

        // Normalize target path for comparison (use forward slashes)
        String targetPath = target.getRelPath().toString().replace('\\', '/');
        int lastSlash = targetPath.lastIndexOf('/');
        String targetDir = lastSlash == -1 ? "" : targetPath.substring(0, lastSlash);

        for (ImportInfo info : imports) {
            Matcher m = IMPORT_PATH_PATTERN.matcher(info.rawSnippet());
            if (m.find()) {
                // group(1) is double-quoted, group(2) is backtick-quoted
                String importPath = m.group(1) != null ? m.group(1) : m.group(2);

                // Go imports match based on the package path (directory).
                // If target is in root, targetDir is "".
                if (targetDir.isEmpty()) {
                    // If target is in root, it is importable if the import path is "."
                    // or if it matches the module name (which we don't know here, so we check
                    // if the import path doesn't look like a standard library path).
                    if (importPath.equals(".") || importPath.contains("/")) {
                        return true;
                    }
                    continue;
                }

                // We use conservative segment-based matching for non-root files:
                // 1. Exact match: "pkg/utils" matches "pkg/utils/file.go"
                // 2. Vanity/Module match: "github.com/org/repo/pkg/utils" matches "pkg/utils/file.go"
                // 3. Sub-package match: "pkg/utils" matches "src/pkg/utils/file.go"
                if (importPath.equals(targetDir)
                        || importPath.endsWith("/" + targetDir)
                        || targetDir.endsWith("/" + importPath)
                        || targetPath.contains("/" + importPath + "/")) {
                    return true;
                }
            }
        }

        return false;
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
        if (importNode == null) {
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
            if (prefix.startsWith("import")) {
                prefix = prefix.substring("import".length()).trim();
            }

            String identifier;
            String alias = null;

            if ("_".equals(prefix)) {
                identifier = "_"; // Blank import
            } else if (".".equals(prefix)) {
                identifier = "."; // Dot import
            } else if (!prefix.isEmpty()) {
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
            String rawSnippet = "import " + (prefix.isEmpty() ? "" : prefix + " ") + "\"" + path + "\"";

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

    /**
     * Extracts type and package identifiers from Go source.
     * <p>
     * Trade-off: High Precision. We target {@code type_identifier} for internal types and
     * {@code selector_expression} operands to identify imported package usage (e.g., 'fmt' in 'fmt.Println').
     */
    @Override
    public Set<String> extractTypeIdentifiers(String source) {
        try (TSTree tree = getTSParser().parseString(null, source)) {
            if (tree == null) {
                return Set.of();
            }

            var rootNode = tree.getRootNode();
            if (rootNode == null) return Set.of();
            SourceContent sourceContent = SourceContent.of(source);
            Set<String> identifiers = new HashSet<>();
            withCachedQuery(
                    QueryType.IDENTIFIERS,
                    query -> {
                        try (TSQueryCursor cursor = new TSQueryCursor()) {

                            cursor.exec(query, rootNode, sourceContent.text());

                            TSQueryMatch match = new TSQueryMatch();
                            while (cursor.nextMatch(match)) {
                                for (TSQueryCapture capture : match.getCaptures()) {
                                    TSNode node = capture.getNode();
                                    if (node != null) {
                                        identifiers.add(sourceContent
                                                .substringFrom(node)
                                                .trim());
                                    }
                                }
                            }
                        }
                        return true;
                    },
                    false);
            return identifiers;
        }
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
        if (!importedPackageNames.isEmpty()) {
            for (CodeUnit cu : snapshotState().codeUnitState().keySet()) {
                if (!cu.isModule() && importedPackageNames.contains(cu.packageName())) {
                    resolved.add(cu);
                }
            }
        }

        return Collections.unmodifiableSet(resolved);
    }

    private String resolveImportPathToPackageName(String importPath) {
        GoAnalyzerCache goCache = (GoAnalyzerCache) getCache();
        return goCache.importPathToPackageNameCache().get(importPath, path -> {
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
                        String pkgName = withTreeOf(
                                pf,
                                tree -> Optional.ofNullable(tree.getRootNode())
                                        .map(rootNode -> determinePackageName(pf, rootNode, rootNode, content.get()))
                                        .orElse(""),
                                "");
                        if (!pkgName.isEmpty()) {
                            return pkgName;
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
        var rootNode = tree.getRootNode();
        if (rootNode == null) return false;
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, rootNode, sourceContent.text());
                        TSQueryMatch match = new TSQueryMatch();

                        while (cursor.nextMatch(match)) {
                            boolean sawTestMarker = false;
                            TSNode nameNode = null;
                            TSNode paramsNode = null;

                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                TSNode node = capture.getNode();
                                if (node == null) {
                                    continue;
                                }

                                switch (captureName) {
                                    case TEST_MARKER -> sawTestMarker = true;
                                    case CAPTURE_TEST_CANDIDATE_NAME -> nameNode = node;
                                    case CAPTURE_TEST_CANDIDATE_PARAMS -> paramsNode = node;
                                }

                                if (sawTestMarker && nameNode != null && paramsNode != null) {
                                    break;
                                }
                            }

                            if (!sawTestMarker || nameNode == null || paramsNode == null) {
                                continue;
                            }

                            // 1. Check function name starts with "Test"
                            String funcName =
                                    sourceContent.substringFrom(nameNode).trim();
                            if (!funcName.startsWith(TEST_FUNCTION_PREFIX)) {
                                continue;
                            }

                            // 2. Go tests cannot be generic (no type parameters)
                            TSNode parent = nameNode.getParent();
                            if (parent != null) {
                                TSNode typeParams =
                                        parent.getChildByFieldName(GO_SYNTAX_PROFILE.typeParametersFieldName());
                                if (typeParams != null) {
                                    continue;
                                }
                            }

                            // 3. Inspect parameters: must have exactly one parameter of type testing.T or *testing.T
                            // In Go: "func Test(t *testing.T)" has 1 parameter_declaration with 1 identifier.
                            // "func Test(a, b *testing.T)" has 1 parameter_declaration with 2 identifiers.
                            // "func Test(a T1, b T2)" has 2 parameter_declarations.
                            int totalIdentifierCount = 0;
                            TSNode firstParamDecl = null;

                            for (TSNode child : paramsNode.getNamedChildren()) {
                                if (PARAMETER_DECLARATION.equals(child.getType())) {
                                    if (firstParamDecl == null) {
                                        firstParamDecl = child;
                                    }
                                    for (TSNode n : child.getNamedChildren()) {
                                        if ("identifier".equals(n.getType())) {
                                            totalIdentifierCount++;
                                        }
                                    }
                                }
                            }

                            if (firstParamDecl == null || totalIdentifierCount != 1) {
                                continue;
                            }

                            TSNode typeNode = firstParamDecl.getChildByFieldName(FIELD_TYPE);
                            // Fallback for types without field name (depending on TS version/grammar)
                            if (typeNode == null) {
                                for (TSNode child : firstParamDecl.getNamedChildren()) {
                                    String type = child.getType();
                                    if (POINTER_TYPE.equals(type)
                                            || QUALIFIED_TYPE.equals(type)
                                            || TYPE_IDENTIFIER.equals(type)) {
                                        typeNode = child;
                                        break;
                                    }
                                }
                            }

                            if (typeNode == null) {
                                continue;
                            }

                            String typeText =
                                    sourceContent.substringFrom(typeNode).trim();
                            if (TESTING_T.equals(typeText) || POINTER_TESTING_T.equals(typeText)) {
                                return true;
                            }
                        }
                    }
                    return false;
                },
                false);
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
        var findings = new ArrayList<SmellCandidate>();

        var defers = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(DEFER_STATEMENT), defers);
        for (TSNode defer : defers) {
            analyzeDeferRecoverHandler(file, defer, sourceContent, weights).ifPresent(findings::add);
        }

        var ifs = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(IF_STATEMENT), ifs);
        for (TSNode ifNode : ifs) {
            analyzeErrNotNilHandler(file, ifNode, sourceContent, weights).ifPresent(findings::add);
        }

        return findings.stream()
                .sorted(Comparator.comparingInt(SmellCandidate::score)
                        .reversed()
                        .thenComparing(c -> c.smell().file().toString())
                        .thenComparing(c -> c.smell().enclosingFqName())
                        .thenComparingInt(SmellCandidate::startByte))
                .map(SmellCandidate::smell)
                .toList();
    }

    private Optional<SmellCandidate> analyzeDeferRecoverHandler(
            ProjectFile file, TSNode deferNode, SourceContent sourceContent, ExceptionSmellWeights weights) {
        TSNode functionLiteral = findFirstNamedDescendant(deferNode, FUNCTION_LITERAL);
        if (functionLiteral == null) {
            return Optional.empty();
        }
        TSNode body = functionLiteral.getChildByFieldName("body");
        if (body == null) {
            body = findFirstNamedDescendant(functionLiteral, BLOCK);
        }
        if (body == null) {
            return Optional.empty();
        }

        // Find `if ... recover() ... { handler }` patterns inside the deferred function.
        var ifs = new ArrayList<TSNode>();
        collectNodesByType(body, Set.of(IF_STATEMENT), ifs);
        for (TSNode ifNode : ifs) {
            TSNode consequence = ifNode.getChildByFieldName("consequence");
            if (consequence == null) {
                consequence = findFirstNamedDescendant(ifNode, BLOCK);
            }
            if (consequence == null) {
                continue;
            }
            if (!ifConditionContainsRecoverCall(ifNode, consequence, sourceContent)) {
                continue;
            }
            return analyzeHandlerBody(
                    file,
                    consequence,
                    sourceContent,
                    weights,
                    weights.genericThrowableWeight(),
                    "generic-catch:recover()");
        }
        return Optional.empty();
    }

    private static boolean ifConditionContainsRecoverCall(
            TSNode ifNode, TSNode consequence, SourceContent sourceContent) {
        var calls = new ArrayList<TSNode>();
        collectNodesByType(ifNode, Set.of(CALL_EXPRESSION), calls);
        for (TSNode call : calls) {
            if (call.getStartByte() >= consequence.getStartByte()) {
                continue;
            }
            String callee = callExpressionCalleeName(call, sourceContent);
            if ("recover".equals(callee)) {
                return true;
            }
        }
        return false;
    }

    private Optional<SmellCandidate> analyzeErrNotNilHandler(
            ProjectFile file, TSNode ifNode, SourceContent sourceContent, ExceptionSmellWeights weights) {
        TSNode consequence = ifNode.getChildByFieldName("consequence");
        if (consequence == null) {
            consequence = findFirstNamedDescendant(ifNode, BLOCK);
        }
        if (consequence == null) {
            return Optional.empty();
        }
        if (!conditionLooksLikeErrNotNil(ifNode, consequence, sourceContent)) {
            return Optional.empty();
        }

        return analyzeHandlerBody(
                file, consequence, sourceContent, weights, weights.genericExceptionWeight(), "generic-catch:error");
    }

    private static boolean conditionLooksLikeErrNotNil(TSNode ifNode, TSNode consequence, SourceContent sourceContent) {
        // Keep this AST-guided: look for a binary expression "err != nil" that appears before the consequence block.
        var binaries = new ArrayList<TSNode>();
        collectNodesByType(ifNode, Set.of(BINARY_EXPRESSION), binaries);
        for (TSNode binary : binaries) {
            if (binary.getStartByte() >= consequence.getStartByte()) {
                continue;
            }
            TSNode left = binary.getChildByFieldName("left");
            TSNode right = binary.getChildByFieldName("right");
            if (left == null || right == null) {
                continue;
            }
            if (!IDENTIFIER.equals(left.getType()) || !NIL.equals(right.getType())) {
                continue;
            }
            if (!"err".equals(sourceContent.substringFrom(left).strip())) {
                continue;
            }
            // Tree-sitter-go doesn't expose operator as a named node; validate via the binary expression text.
            if (sourceContent.substringFrom(binary).contains("!=")) {
                return true;
            }
        }
        return false;
    }

    private Optional<SmellCandidate> analyzeHandlerBody(
            ProjectFile file,
            TSNode bodyNode,
            SourceContent sourceContent,
            ExceptionSmellWeights weights,
            int baseScore,
            String baseReason) {
        int bodyStatements = countHandlerStatements(bodyNode);
        String bodyText = sourceContent.substringFrom(bodyNode);
        boolean hasAnyComment = bodyText.contains("//") || bodyText.contains("/*");
        boolean emptyBody = bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean rethrowPresent = hasCallToIdent(bodyNode, "panic", sourceContent);
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyBody(bodyNode, sourceContent) && !rethrowPresent;

        int score = baseScore;
        var reasons = new ArrayList<String>();
        reasons.add(baseReason);
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
                        bodyNode.getStartPoint().getRow(),
                        bodyNode.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        String excerpt = compactExcerpt(
                sourceContent.substringFrom(bodyNode.getParent() != null ? bodyNode.getParent() : bodyNode));
        var smell = new ExceptionHandlingSmell(
                file,
                enclosing,
                baseReason.replace("generic-catch:", ""),
                score,
                bodyStatements,
                List.copyOf(reasons),
                excerpt);
        return Optional.of(new SmellCandidate(smell, bodyNode.getStartByte()));
    }

    private static int countHandlerStatements(TSNode bodyNode) {
        TSNode list = firstNamedChildOfType(bodyNode, STATEMENT_LIST);
        TSNode container = list != null ? list : bodyNode;
        int expressions = 0;
        for (int i = 0; i < container.getNamedChildCount(); i++) {
            TSNode child = container.getNamedChild(i);
            if (child == null) {
                continue;
            }
            if (COMMENT.equals(child.getType())) {
                continue;
            }
            expressions++;
        }
        return expressions;
    }

    private static boolean hasCallToIdent(TSNode root, String targetName, SourceContent sourceContent) {
        var calls = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(CALL_EXPRESSION), calls);
        return calls.stream().anyMatch(call -> targetName.equals(callExpressionCalleeName(call, sourceContent)));
    }

    private static String callExpressionCalleeName(TSNode call, SourceContent sourceContent) {
        TSNode fn = call.getChildByFieldName("function");
        if (fn == null && call.getNamedChildCount() > 0) {
            fn = call.getNamedChild(0);
        }
        if (fn == null) {
            return "";
        }
        if (IDENTIFIER.equals(fn.getType())) {
            return sourceContent.substringFrom(fn).strip();
        }
        if (!SELECTOR_EXPRESSION.equals(fn.getType())) {
            return "";
        }
        TSNode field = fn.getChildByFieldName("field");
        if (field != null) {
            return sourceContent.substringFrom(field).strip();
        }
        // Fallback: last identifier within selector expression
        TSNode ident = findFirstNamedDescendant(fn, IDENTIFIER);
        return ident == null ? "" : sourceContent.substringFrom(ident).strip();
    }

    private static boolean isLikelyLogOnlyBody(TSNode bodyNode, SourceContent sourceContent) {
        TSNode list = firstNamedChildOfType(bodyNode, STATEMENT_LIST);
        TSNode container = list != null ? list : bodyNode;
        TSNode statement = firstNonCommentNamedChild(container, COMMENT_NODE_TYPES);
        if (statement == null || !EXPRESSION_STATEMENT.equals(statement.getType())) {
            return false;
        }
        TSNode call = findFirstNamedDescendant(statement, CALL_EXPRESSION);
        if (call == null) {
            return false;
        }
        TSNode fn = call.getChildByFieldName("function");
        if (fn == null) {
            fn = call.getNamedChildCount() > 0 ? call.getNamedChild(0) : null;
        }
        if (fn == null) {
            return false;
        }
        if (IDENTIFIER.equals(fn.getType())) {
            String bare = sourceContent.substringFrom(fn).strip().toLowerCase(Locale.ROOT);
            return GO_LOG_METHOD_NAMES.contains(bare);
        }
        if (!SELECTOR_EXPRESSION.equals(fn.getType())) {
            return false;
        }
        TSNode receiver = fn.getChildByFieldName("operand");
        TSNode method = fn.getChildByFieldName("field");
        if (receiver == null || method == null) {
            return false;
        }
        String receiverText = sourceContent.substringFrom(receiver).strip().toLowerCase(Locale.ROOT);
        String methodText = sourceContent.substringFrom(method).strip().toLowerCase(Locale.ROOT);
        boolean receiverLike = GO_LOG_RECEIVER_NAMES.contains(receiverText)
                || GO_LOG_RECEIVER_NAMES.stream().anyMatch(name -> receiverText.endsWith("." + name));
        boolean methodLike = GO_LOG_METHOD_NAMES.contains(methodText);
        return receiverLike && methodLike;
    }

    private static @Nullable TSNode firstNamedChildOfType(TSNode node, String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null && type.equals(child.getType())) {
                return child;
            }
        }
        return null;
    }

    private static String compactExcerpt(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    private record SmellCandidate(ExceptionHandlingSmell smell, int startByte) {
        int score() {
            return smell.score();
        }
    }
}
