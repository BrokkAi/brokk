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

    private record AssertionSignal(
            String kind,
            int score,
            boolean shallow,
            boolean meaningful,
            int startByte,
            List<String> reasons,
            String excerpt) {}

    private record TestSmellCandidate(TestAssertionSmell smell, int startByte) {
        int score() {
            return smell.score();
        }
    }

    @Override
    public List<TestAssertionSmell> findTestAssertionSmells(ProjectFile file, TestAssertionWeights weights) {
        checkStale("findTestAssertionSmells");
        if (!containsTests(file)) {
            return List.of();
        }

        TestAssertionWeights resolvedWeights = weights != null ? weights : TestAssertionWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file, source -> detectTestAssertionSmells(file, root, source, resolvedWeights), List.of());
                },
                List.of());
    }

    private List<TestAssertionSmell> detectTestAssertionSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, TestAssertionWeights weights) {
        List<TSNode> testFunctions = testFunctionsOf(root, sourceContent);
        if (testFunctions.isEmpty()) {
            return List.of();
        }

        var candidates = new ArrayList<TestSmellCandidate>();
        for (TSNode testFn : testFunctions) {
            var signals = detectGoAssertionSignals(testFn, sourceContent, weights);
            int assertionCount = signals.size();

            String enclosing = enclosingCodeUnit(
                            file,
                            testFn.getStartPoint().getRow(),
                            testFn.getEndPoint().getRow())
                    .map(CodeUnit::fqName)
                    .orElse(file.toString());

            if (assertionCount == 0) {
                var smell = new TestAssertionSmell(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_NO_ASSERTIONS,
                        weights.noAssertionWeight(),
                        0,
                        List.of(TEST_ASSERTION_KIND_NO_ASSERTIONS),
                        sourceContent.substringFrom(testFn));
                candidates.add(new TestSmellCandidate(smell, testFn.getStartByte()));
                continue;
            }

            for (AssertionSignal signal : signals) {
                if (signal.score() <= 0) continue;
                candidates.add(new TestSmellCandidate(
                        new TestAssertionSmell(
                                file,
                                enclosing,
                                signal.kind(),
                                signal.score(),
                                assertionCount,
                                List.copyOf(signal.reasons()),
                                signal.excerpt()),
                        signal.startByte()));
            }

            boolean allShallow = signals.stream().allMatch(AssertionSignal::shallow);
            if (allShallow) {
                int score = weights.shallowAssertionOnlyWeight()
                        - meaningfulAssertionCredit(signals, weights, AssertionSignal::meaningful);
                if (score > 0) {
                    var smell = new TestAssertionSmell(
                            file,
                            enclosing,
                            TEST_ASSERTION_KIND_SHALLOW_ONLY,
                            score,
                            assertionCount,
                            List.of(TEST_ASSERTION_KIND_SHALLOW_ONLY),
                            sourceContent.substringFrom(testFn));
                    candidates.add(new TestSmellCandidate(smell, testFn.getStartByte()));
                }
            }
        }

        return candidates.stream()
                .sorted(Comparator.comparingInt(TestSmellCandidate::score)
                        .reversed()
                        .thenComparing(c -> c.smell().file().toString())
                        .thenComparing(c -> c.smell().enclosingFqName())
                        .thenComparingInt(TestSmellCandidate::startByte))
                .map(TestSmellCandidate::smell)
                .toList();
    }

    private static int meaningfulAssertionCredit(
            List<AssertionSignal> assertions,
            TestAssertionWeights weights,
            java.util.function.Predicate<AssertionSignal> predicate) {
        long count = assertions.stream().filter(predicate).count();
        int creditable = Math.min((int) count, Math.max(0, weights.meaningfulAssertionCreditCap()));
        return Math.max(0, weights.meaningfulAssertionCredit()) * creditable;
    }

    private List<TSNode> testFunctionsOf(TSNode root, SourceContent sourceContent) {
        // Reuse the same semantic criteria as containsTestMarkers(), but return the matching function nodes.
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    List<TSNode> out = new ArrayList<>();
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, root, sourceContent.text());
                        TSQueryMatch match = new TSQueryMatch();

                        while (cursor.nextMatch(match)) {
                            boolean sawTestMarker = false;
                            TSNode nameNode = null;
                            TSNode paramsNode = null;
                            TSNode functionNode = null;

                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                TSNode node = capture.getNode();
                                if (node == null) {
                                    continue;
                                }

                                switch (captureName) {
                                    case TEST_MARKER -> {
                                        sawTestMarker = true;
                                        functionNode = node;
                                    }
                                    case CAPTURE_TEST_CANDIDATE_NAME -> nameNode = node;
                                    case CAPTURE_TEST_CANDIDATE_PARAMS -> paramsNode = node;
                                }

                                if (sawTestMarker && nameNode != null && paramsNode != null) {
                                    break;
                                }
                            }

                            if (!sawTestMarker || nameNode == null || paramsNode == null || functionNode == null) {
                                continue;
                            }

                            String funcName =
                                    sourceContent.substringFrom(nameNode).trim();
                            if (!funcName.startsWith(TEST_FUNCTION_PREFIX)) {
                                continue;
                            }

                            TSNode parent = nameNode.getParent();
                            if (parent != null) {
                                TSNode typeParams =
                                        parent.getChildByFieldName(GO_SYNTAX_PROFILE.typeParametersFieldName());
                                if (typeParams != null) {
                                    continue;
                                }
                            }

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
                                out.add(functionNode);
                            }
                        }
                    }
                    return out;
                },
                List.of());
    }

    private List<AssertionSignal> detectGoAssertionSignals(
            TSNode testFn, SourceContent sourceContent, TestAssertionWeights weights) {
        var ifStatements = new ArrayList<TSNode>();
        collectNodesByType(testFn, Set.of(IF_STATEMENT), ifStatements);
        if (ifStatements.isEmpty()) {
            return List.of();
        }

        var signals = new ArrayList<AssertionSignal>();
        for (TSNode ifStmt : ifStatements) {
            var errorCalls = new ArrayList<TSNode>();
            collectNodesByType(ifStmt, Set.of(CALL_EXPRESSION), errorCalls);
            boolean hasErrorCall = false;
            for (TSNode call : errorCalls) {
                String callText = sourceContent.substringFrom(call).strip();
                if (callText.contains("t.Errorf") || callText.contains("t.Fatalf")) {
                    hasErrorCall = true;
                    break;
                }
            }
            if (!hasErrorCall) {
                continue;
            }

            TSNode condition = ifStmt.getChildByFieldName("condition");
            if (condition == null) {
                var condCandidates = new ArrayList<TSNode>();
                collectNodesByType(ifStmt, Set.of(BINARY_EXPRESSION, EXPRESSION), condCandidates);
                if (condCandidates.isEmpty()) {
                    continue;
                }
                condition = condCandidates.getFirst();
            }

            var signal = classifyGoAssertionFromConditionAndMessage(ifStmt, condition, sourceContent, weights);
            if (signal != null && signal.score() > 0) {
                signals.add(signal);
            }
        }

        return signals;
    }

    private @Nullable AssertionSignal classifyGoAssertionFromConditionAndMessage(
            TSNode ifStmt, TSNode condition, SourceContent sourceContent, TestAssertionWeights weights) {
        int score = 0;
        boolean shallow = false;
        boolean meaningful = true;
        String kind = "";
        var reasons = new ArrayList<String>();

        TSNode left = condition.getChildByFieldName("left");
        TSNode right = condition.getChildByFieldName("right");
        if (left == null || right == null) {
            var exprs = new ArrayList<TSNode>();
            collectNodesByType(condition, Set.of(EXPRESSION), exprs);
            if (exprs.size() >= 2) {
                left = exprs.getFirst();
                right = exprs.get(1);
            }
        }
        if (left == null || right == null) {
            // Can't classify reliably.
            return null;
        }

        boolean leftNil = isGoNilExpr(left);
        boolean rightNil = isGoNilExpr(right);
        boolean leftConst = isGoConstantExpr(left);
        boolean rightConst = isGoConstantExpr(right);

        if (leftConst && rightConst) {
            score += weights.constantEqualityWeight();
            kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
            reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
            meaningful = false;
        } else if (sameGoExpr(left, right, sourceContent)) {
            score += weights.tautologicalAssertionWeight();
            kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
            reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
            meaningful = false;
        } else if (leftNil || rightNil) {
            score += weights.nullnessOnlyWeight();
            kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
            reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
            shallow = true;
            meaningful = false;
        }

        boolean overspecified =
                containsLargeGoStringLiteral(ifStmt, sourceContent, weights.largeLiteralLengthThreshold());
        if (overspecified) {
            score += weights.overspecifiedLiteralWeight();
            kind = TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL;
            reasons.add(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL);
            meaningful = false;
            shallow = false;
        }

        if (score <= 0) {
            return null;
        }

        return new AssertionSignal(
                kind,
                score,
                shallow,
                meaningful,
                ifStmt.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(ifStmt));
    }

    private static boolean isGoNilExpr(TSNode expr) {
        // Tree-sitter represents the `nil` keyword as its own named node type: "nil".
        if (NIL_LITERAL.equals(expr.getType())) {
            return true;
        }
        // Be defensive: nil may be wrapped in parens or other expression nodes.
        return hasDescendantOfType(expr, NIL_LITERAL);
    }

    private static boolean isGoConstantExpr(TSNode expr) {
        // nil/true/false are represented as dedicated named node types in the Go grammar.
        if (NIL_LITERAL.equals(expr.getType())
                || TRUE_LITERAL.equals(expr.getType())
                || FALSE_LITERAL.equals(expr.getType())) {
            return true;
        }
        return hasDescendantOfType(expr, NIL_LITERAL)
                || hasDescendantOfType(expr, TRUE_LITERAL)
                || hasDescendantOfType(expr, FALSE_LITERAL)
                || hasDescendantOfType(expr, STRING_LITERAL)
                || hasDescendantOfType(expr, INTEGER_LITERAL)
                || hasDescendantOfType(expr, FLOAT_LITERAL)
                || hasDescendantOfType(expr, BOOLEAN_LITERAL);
    }

    private static boolean sameGoExpr(TSNode left, TSNode right, SourceContent sourceContent) {
        TSNode l = unwrapGoParenthesized(left);
        TSNode r = unwrapGoParenthesized(right);
        if (l == null || r == null) return false;
        String lType = l.getType();
        String rType = r.getType();
        if (lType == null || rType == null || !lType.equals(rType)) return false;

        String type = lType;
        if (type.equals(IDENTIFIER) || type.equals(SELECTOR_EXPRESSION)) {
            // Compare CST shape for common cases (including chained selectors).
            return sameGoSelectorOrIdentifier(l, r, sourceContent);
        }

        if (type.equals(CALL_EXPRESSION)) {
            return sameGoCallExpr(l, r, sourceContent);
        }

        if (type.equals(BINARY_EXPRESSION)) {
            return sameGoBinaryExpr(l, r, sourceContent);
        }

        // Conservative fallback: trimmed CST text.
        return sourceContent
                .substringFrom(l)
                .strip()
                .equals(sourceContent.substringFrom(r).strip());
    }

    private static TSNode unwrapGoParenthesized(TSNode n) {
        TSNode current = n;
        // Parentheses may wrap expressions; unwrap up to 2 layers.
        for (int i = 0; i < 2; i++) {
            if (current == null) return n;
            if (!PARENTHESIZED_EXPRESSION.equals(current.getType())) break;
            if (current.getNamedChildCount() == 0) break;
            TSNode child = current.getNamedChild(0);
            if (child == null) break;
            current = child;
        }
        return current;
    }

    private static boolean sameGoSelectorOrIdentifier(TSNode left, TSNode right, SourceContent sourceContent) {
        // selector_expression has fields `operand` and `field`.
        String leftType = left.getType();
        String rightType = right.getType();
        if (SELECTOR_EXPRESSION.equals(leftType) && SELECTOR_EXPRESSION.equals(rightType)) {
            TSNode lOperand = left.getChildByFieldName("operand");
            TSNode lField = left.getChildByFieldName("field");
            TSNode rOperand = right.getChildByFieldName("operand");
            TSNode rField = right.getChildByFieldName("field");
            if (lOperand == null || lField == null || rOperand == null || rField == null) {
                return false;
            }
            return sameGoExpr(lOperand, rOperand, sourceContent) && sameGoExpr(lField, rField, sourceContent);
        }

        // identifier (or leaf selectors) fallback to exact token text.
        return sourceContent
                .substringFrom(left)
                .strip()
                .equals(sourceContent.substringFrom(right).strip());
    }

    private static boolean sameGoCallExpr(TSNode left, TSNode right, SourceContent sourceContent) {
        TSNode lFunc = left.getChildByFieldName("function");
        TSNode rFunc = right.getChildByFieldName("function");
        TSNode lArgsNode = left.getChildByFieldName("arguments");
        TSNode rArgsNode = right.getChildByFieldName("arguments");
        if (lFunc == null || rFunc == null || lArgsNode == null || rArgsNode == null) return false;

        if (!sameGoExpr(lFunc, rFunc, sourceContent)) return false;

        // Compare arguments by their top-level named children.
        int lCount = lArgsNode.getNamedChildCount();
        int rCount = rArgsNode.getNamedChildCount();
        if (lCount != rCount) return false;
        for (int i = 0; i < lCount; i++) {
            TSNode lArg = lArgsNode.getNamedChild(i);
            TSNode rArg = rArgsNode.getNamedChild(i);
            if (lArg == null || rArg == null) return false;
            if (!sameGoExpr(lArg, rArg, sourceContent)) return false;
        }
        return true;
    }

    private static boolean sameGoBinaryExpr(TSNode left, TSNode right, SourceContent sourceContent) {
        TSNode lLeft = left.getChildByFieldName("left");
        TSNode lRight = left.getChildByFieldName("right");
        TSNode rLeft = right.getChildByFieldName("left");
        TSNode rRight = right.getChildByFieldName("right");
        if (lLeft == null || lRight == null || rLeft == null || rRight == null) return false;
        if (!sameGoExpr(lLeft, rLeft, sourceContent)) return false;
        if (!sameGoExpr(lRight, rRight, sourceContent)) return false;

        return firstUnnamedChildType(left).equals(firstUnnamedChildType(right));
    }

    private static String firstUnnamedChildType(TSNode node) {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null) continue;
            if (!child.isNamed()) {
                String t = child.getType();
                return t == null ? "" : t;
            }
        }
        return "";
    }

    private static boolean containsLargeGoStringLiteral(TSNode root, SourceContent sourceContent, int threshold) {
        var lits = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(STRING_LITERAL), lits);
        for (TSNode lit : lits) {
            if (sourceContent.substringFrom(lit).length() >= threshold) {
                return true;
            }
        }
        return false;
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
}
