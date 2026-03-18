package ai.brokk.analyzer;

import static ai.brokk.analyzer.csharp.CSharpTreeSitterNodeTypes.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.analyzer.csharp.CSharpTreeSitterNodeTypes;
import ai.brokk.project.IProject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCSharp;

/**
 * C# analyzer using Tree-sitter. Responsible for producing CodeUnits and skeletons for C# sources.
 */
public final class CSharpAnalyzer extends TreeSitterAnalyzer {
    static final Logger log = LoggerFactory.getLogger(CSharpAnalyzer.class);

    private static final LanguageSyntaxProfile CS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(
                    CLASS_DECLARATION,
                    INTERFACE_DECLARATION,
                    STRUCT_DECLARATION,
                    RECORD_DECLARATION,
                    RECORD_STRUCT_DECLARATION),
            Set.of(METHOD_DECLARATION, CONSTRUCTOR_DECLARATION, LOCAL_FUNCTION_STATEMENT),
            Set.of(FIELD_DECLARATION, PROPERTY_DECLARATION, EVENT_FIELD_DECLARATION),
            Set.of(CaptureNames.CONSTRUCTOR_DEFINITION),
            Set.of("attribute_list"),
            IMPORT_DECLARATION,
            "name",
            "body",
            "parameters",
            "type",
            "type_parameter_list", // typeParametersFieldName (C# generics)
            Map.of(
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.INTERFACE_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.STRUCT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.RECORD_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.METHOD_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.CONSTRUCTOR_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE),
            "",
            Set.of());

    public CSharpAnalyzer(IProject project) {
        this(project, ProgressListener.NOOP);
    }

    public CSharpAnalyzer(IProject project, ProgressListener listener) {
        super(project, Languages.C_SHARP, listener);
        log.debug("CSharpAnalyzer: Constructor called for project: {}", project);
    }

    private CSharpAnalyzer(
            IProject project, AnalyzerState prebuiltState, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.C_SHARP, prebuiltState, listener, cache);
    }

    public static CSharpAnalyzer fromState(IProject project, AnalyzerState state, ProgressListener listener) {
        return new CSharpAnalyzer(project, state, listener, null);
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new CSharpAnalyzer(getProject(), state, listener, previousCache);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterCSharp();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/c_sharp/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/c_sharp/imports.scm");
            case IDENTIFIERS -> Optional.empty();
        };
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
        CodeUnit result =
                switch (captureName) {
                    case CaptureNames.CLASS_DEFINITION,
                            CaptureNames.INTERFACE_DEFINITION,
                            CaptureNames.STRUCT_DEFINITION,
                            CaptureNames.RECORD_DEFINITION -> {
                        String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                        yield CodeUnit.cls(file, packageName, finalShortName);
                    }
                    case CaptureNames.FUNCTION_DEFINITION,
                            CaptureNames.METHOD_DEFINITION,
                            CaptureNames.CONSTRUCTOR_DEFINITION -> {
                        String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                        yield CodeUnit.fn(file, packageName, finalShortName);
                    }
                    case CaptureNames.FIELD_DEFINITION -> {
                        String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                        yield CodeUnit.field(file, packageName, finalShortName);
                    }
                    default -> {
                        log.warn(
                                "Unhandled capture name in CSharpAnalyzer.createCodeUnit: '{}' for simple name '{}', package '{}', classChain '{}' in file {}. Returning null.",
                                captureName,
                                simpleName,
                                packageName,
                                classChain,
                                file);
                        yield null;
                    }
                };
        log.trace("CSharpAnalyzer.createCodeUnit: returning {}", result);
        return result;
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // C# query explicitly captures attributes/annotations to ignore them
        var ignored = Set.of("annotation.definition");
        log.trace("CSharpAnalyzer: getIgnoredCaptures() returning: {}", ignored);
        return ignored;
    }

    @Override
    protected String bodyPlaceholder() {
        var placeholder = "{ … }";
        log.trace("CSharpAnalyzer: bodyPlaceholder() returning: {}", placeholder);
        return placeholder;
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
        // The 'indent' parameter is now "" when called from buildSignatureString.
        TSNode body = funcNode.getChildByFieldName("body");
        String signature;

        if (body != null && !body.isNull()) {
            int startByte = funcNode.getStartByte();
            int endByte = body.getStartByte();
            signature = sourceContent.substringFromBytes(startByte, endByte).stripTrailing();
        } else {
            TSNode paramsNode = funcNode.getChildByFieldName("parameters");
            if (paramsNode != null && !paramsNode.isNull()) {
                int startByte = funcNode.getStartByte();
                int endByte = paramsNode.getEndByte();
                signature = sourceContent.substringFromBytes(startByte, endByte).stripTrailing();
            } else {
                signature = sourceContent
                        .substringFrom(funcNode)
                        .lines()
                        .findFirst()
                        .orElse("")
                        .stripTrailing();
                log.trace(
                        "renderFunctionDeclaration for C# (node type {}): body and params not found, using fallback signature '{}'",
                        funcNode.getType(),
                        signature);
            }
        }
        return signature + " " + bodyPlaceholder();
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
        return signatureText + " {";
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        // C# namespaces are determined by traversing up from the definition node
        // to find enclosing namespace_declaration nodes.
        // The 'file' parameter is not used here as namespace is derived from AST content.
        List<String> namespaceParts = new ArrayList<>();
        TSNode current = definitionNode.getParent();

        while (current != null && !current.isNull() && !current.equals(rootNode)) {
            if (NAMESPACE_DECLARATION.equals(current.getType())) {
                // Find the identifier or qualified_name child as the name
                for (int i = 0; i < current.getChildCount(); i++) {
                    TSNode child = current.getChild(i);
                    String type = child.getType();
                    if ("identifier".equals(type) || "qualified_name".equals(type)) {
                        String nsPart = sourceContent.substringFrom(child);
                        namespaceParts.add(nsPart);
                        break;
                    }
                }
            }
            current = current.getParent();
        }
        Collections.reverse(namespaceParts);
        return String.join(".", namespaceParts);
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return CS_SYNTAX_PROFILE;
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
        String nodeType = fieldNode.getType();

        TSNode fieldDecl = null;
        TSNode varDecl = null;
        TSNode declarator = null;

        // If we captured the whole field_declaration, try to locate the variable_declaration child.
        if (FIELD_DECLARATION.equals(nodeType) || EVENT_FIELD_DECLARATION.equals(nodeType)) {
            fieldDecl = fieldNode;
            // Some grammars expose the variable_declaration via a field name, others as a plain child.
            varDecl = fieldNode.getChildByFieldName("declaration");
            if (varDecl == null || varDecl.isNull()) {
                for (int i = 0; i < fieldNode.getChildCount(); i++) {
                    TSNode child = fieldNode.getChild(i);
                    if (child != null
                            && !child.isNull()
                            && CSharpTreeSitterNodeTypes.VARIABLE_DECLARATION.equals(child.getType())) {
                        varDecl = child;
                        break;
                    }
                }
            }
            if (varDecl != null && !varDecl.isNull()) {
                declarator = findDeclarator(
                                varDecl,
                                simpleName,
                                sourceContent,
                                CSharpTreeSitterNodeTypes.VARIABLE_DECLARATOR,
                                "name")
                        .orElse(null);
            }
        } else if (CSharpTreeSitterNodeTypes.VARIABLE_DECLARATOR.equals(nodeType)) {
            // If the capture was the declarator itself, walk up to its variable_declaration and field_declaration
            declarator = fieldNode;
            varDecl = fieldNode.getParent();
            if (varDecl != null && CSharpTreeSitterNodeTypes.VARIABLE_DECLARATION.equals(varDecl.getType())) {
                fieldDecl = varDecl.getParent();
            }
        }

        if (fieldDecl != null && varDecl != null && declarator != null) {
            TSNode typeNode = varDecl.getChildByFieldName("type");
            if (typeNode != null && !typeNode.isNull()) {
                StringBuilder modifiersBuilder = new StringBuilder();
                for (int i = 0; i < fieldDecl.getChildCount(); i++) {
                    TSNode child = fieldDecl.getChild(i);
                    if (child == null || child.isNull() || child.getEndByte() > varDecl.getStartByte()) {
                        break;
                    }
                    String childType = child.getType();
                    if ("modifier".equals(childType)) {
                        String text = sourceContent.substringFrom(child).strip();
                        if (!text.isEmpty()) {
                            modifiersBuilder.append(text).append(" ");
                        }
                    }
                }

                String modifiers = modifiersBuilder.toString();
                String typeStr = sourceContent.substringFrom(typeNode).strip();

                TSNode nameNode = declarator.getChildByFieldName("name");
                String nameStr = nameNode != null && !nameNode.isNull()
                        ? sourceContent.substringFrom(nameNode).strip()
                        : simpleName;

                // Locate the initializer expression. In C#, it might be inside an equals_value_clause
                // or a direct child of the declarator depending on the grammar version/context.
                TSNode expression = null;
                TSNode valueClause = null;
                for (int i = 0; i < declarator.getChildCount(); i++) {
                    TSNode child = declarator.getChild(i);
                    if (child == null || !child.isNamed()) continue;
                    if ("equals_value_clause".equals(child.getType())) {
                        valueClause = child;
                        break;
                    }
                    // If we find a literal directly, use it
                    if (isLiteralType(child.getType())) {
                        expression = child;
                        break;
                    }
                }

                if (valueClause != null) {
                    expression = valueClause.getChildByFieldName("value");
                    if (expression == null || expression.isNull()) {
                        // Fallback: first named child in the clause that isn't the '=' operator
                        for (int i = 0; i < valueClause.getChildCount(); i++) {
                            TSNode child = valueClause.getChild(i);
                            if (child != null && child.isNamed() && !"=".equals(child.getType())) {
                                expression = child;
                                break;
                            }
                        }
                    }
                }

                String initializerStr = "";
                if (expression != null && !expression.isNull()) {
                    TSNode literalNode = findLiteralNode(expression);
                    if (literalNode != null) {
                        initializerStr = " = " + sourceContent.substringFrom(literalNode).strip();
                    }
                }

                String full = (modifiers + typeStr + " " + nameStr + initializerStr + ";").strip();
                return baseIndent + full;
            }
        }

        // Fallback: use provided signatureText
        String fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();
        if ((FIELD_DECLARATION.equals(nodeType) || EVENT_FIELD_DECLARATION.equals(nodeType))
                && !fullSignature.endsWith(";")) {
            fullSignature += ";";
        }
        return baseIndent + fullSignature;
    }

    private boolean isLiteralType(@Nullable String type) {
        if (type == null) return false;
        return type.endsWith("_literal")
                || type.equals("boolean_literal")
                || type.equals("integer_literal")
                || type.equals("real_literal")
                || type.equals("character_literal")
                || type.equals("string_literal")
                || type.equals("null_literal")
                || type.equals("true")
                || type.equals("false")
                || type.equals("null");
    }

    private @Nullable TSNode findLiteralNode(TSNode node) {
        if (node.isNull()) return null;
        String type = node.getType();

        // 1. Direct hit
        if (isLiteralType(type)) return node;

        // 2. Look through named children (handles wrapper nodes like 'literal' or 'parenthesized_expression')
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child == null || child.isNull() || !child.isNamed()) continue;

            if (isLiteralType(child.getType())) {
                return child;
            }

            // Recurse for nested wrappers (e.g. ((1)))
            TSNode found = findLiteralNode(child);
            if (found != null) return found;
        }

        return null;
    }

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForCSharp(reference);
    }

    @Override
    protected boolean isConstructor(CodeUnit candidate, @Nullable CodeUnit enclosingClass, String captureName) {
        return CaptureNames.CONSTRUCTOR_DEFINITION.equals(captureName);
    }

    @Override
    protected @Nullable CodeUnit createImplicitConstructor(CodeUnit enclosingClass, String classCaptureName) {
        return null;
    }

    @Override
    public Set<CodeUnit> testFilesToCodeUnits(Collection<ProjectFile> files) {
        var unitsInFiles = AnalyzerUtil.getTestDeclarationsWithLogging(this, files)
                .filter(CodeUnit::isClass)
                .collect(Collectors.toSet());

        return AnalyzerUtil.coalesceNestedUnits(this, unitsInFiles);
    }

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, tree.getRootNode());
                        TSQueryMatch match = new TSQueryMatch();

                        Set<String> testAttributes = Set.of(
                                "Test",
                                "Fact",
                                "Theory",
                                "TestCase",
                                "TestMethod",
                                "DataTestMethod",
                                "SetUp",
                                "TearDown");

                        while (cursor.nextMatch(match)) {
                            boolean hasTestMarker = false;
                            String capturedAttrName = null;

                            for (var capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                TSNode node = capture.getNode();
                                if (node == null || node.isNull()) continue;

                                if (TEST_MARKER.equals(captureName)) {
                                    hasTestMarker = true;
                                } else if ("test_attr".equals(captureName)) {
                                    capturedAttrName = sourceContent.substringFrom(node);
                                }
                            }

                            if (hasTestMarker && capturedAttrName != null) {
                                String normalizedName = capturedAttrName;
                                if (normalizedName.endsWith("Attribute")) {
                                    normalizedName =
                                            normalizedName.substring(0, normalizedName.length() - "Attribute".length());
                                }
                                final String finalName = normalizedName;
                                if (testAttributes.stream()
                                        .anyMatch(attr -> finalName.equals(attr) || finalName.endsWith("." + attr))) {
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                },
                false);
    }
}
