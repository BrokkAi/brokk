package ai.brokk.analyzer;

import static ai.brokk.analyzer.php.PhpTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.project.IProject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;
import org.treesitter.*;

public final class PhpAnalyzer extends TreeSitterAnalyzer {
    // PHP_LANGUAGE field removed, createTSLanguage will provide new instances.

    private static final LanguageSyntaxProfile PHP_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(CLASS_DECLARATION, INTERFACE_DECLARATION, TRAIT_DECLARATION), // classLikeNodeTypes
            Set.of(FUNCTION_DEFINITION, METHOD_DECLARATION), // functionLikeNodeTypes
            Set.of(PROPERTY_DECLARATION, CONST_DECLARATION), // fieldLikeNodeTypes (capturing the whole declaration)
            Set.of(), // constructorNodeTypes are just `function.definition` so we need to check name
            Set.of(ATTRIBUTE_LIST), // decoratorNodeTypes (PHP attributes are grouped in attribute_list)
            IMPORT_DECLARATION,
            "name", // identifierFieldName
            "body", // bodyFieldName (applies to functions/methods, class body is declaration_list)
            "parameters", // parametersFieldName
            "return_type", // returnTypeFieldName (for return type declaration)
            "", // typeParametersFieldName (PHP doesn't have generics)
            Map.of( // captureConfiguration
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.INTERFACE_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.TRAIT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE,
                    CaptureNames.ATTRIBUTE_DEFINITION,
                            SkeletonType.UNSUPPORTED // Attributes are handled by getPrecedingDecorators
                    ),
            "", // asyncKeywordNodeType (PHP has no async/await keywords for functions)
            Set.of(
                    VISIBILITY_MODIFIER,
                    STATIC_MODIFIER,
                    ABSTRACT_MODIFIER,
                    FINAL_MODIFIER,
                    READONLY_MODIFIER) // modifierNodeTypes
            );

    @Nullable
    private final Map<ProjectFile, String> fileScopedPackageNames = new ConcurrentHashMap<>();

    private static final String NAMESPACE_QUERY_STR = "(namespace_definition name: (namespace_name) @nsname)";

    public PhpAnalyzer(IProject project) {
        this(project, ProgressListener.NOOP);
    }

    public PhpAnalyzer(IProject project, ProgressListener listener) {
        super(project, Languages.PHP, listener);
    }

    private PhpAnalyzer(
            IProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.PHP, state, listener, cache);
    }

    public static PhpAnalyzer fromState(IProject project, AnalyzerState state, ProgressListener listener) {
        return new PhpAnalyzer(project, state, listener, null);
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new PhpAnalyzer(getProject(), state, listener, previousCache);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterPhp();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/php/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/php/imports.scm");
            case IDENTIFIERS -> Optional.empty();
        };
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return PHP_SYNTAX_PROFILE;
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
        return switch (captureName) {
            case CaptureNames.CLASS_DEFINITION, CaptureNames.INTERFACE_DEFINITION, CaptureNames.TRAIT_DEFINITION -> {
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case CaptureNames.FUNCTION_DEFINITION -> { // Covers global functions and class methods
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case CaptureNames.FIELD_DEFINITION -> { // Covers class properties, class constants, and global constants
                String finalShortName;
                if (classChain.isEmpty()) { // Global constant
                    finalShortName = "_module_." + simpleName;
                } else { // Class property or class constant
                    finalShortName = classChain + "." + simpleName;
                }
                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                // Attributes are handled by decorator logic, not direct CUs.
                // Namespace definitions are used by determinePackageName.
                // The "namespace.name" capture from the main query is now part of getIgnoredCaptures
                // as namespace processing is handled by computeFilePackageName with its own query.
                if (!CaptureNames.ATTRIBUTE_DEFINITION.equals(captureName)
                        && !CaptureNames.NAMESPACE_DEFINITION.equals(captureName)
                        && // Main query's namespace.definition
                        !"namespace.name".equals(captureName)) { // Main query's namespace.name
                    log.debug(
                            "Ignoring capture in PhpAnalyzer: {} with name: {} and classChain: {}",
                            captureName,
                            simpleName,
                            classChain);
                }
                yield null; // Explicitly yield null
            }
        };
    }

    private String computeFilePackageName(ProjectFile file, TSNode rootNode, SourceContent sourceContent) {
        try (TSQuery query = new TSQuery(getTSLanguage(), NAMESPACE_QUERY_STR)) {
            return runNamespaceQuery(query, rootNode, sourceContent);
        } catch (Exception e) {
            log.error(
                    "Failed to compile namespace query for PhpAnalyzer in computeFilePackageName for file {}: {}",
                    file,
                    e.getMessage(),
                    e);
            return "";
        }
    }

    private String runNamespaceQuery(TSQuery query, TSNode rootNode, SourceContent sourceContent) {
        try (TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(query, rootNode);
            TSQueryMatch match = new TSQueryMatch();

            if (cursor.nextMatch(match)) {
                for (TSQueryCapture capture : match.getCaptures()) {
                    if ("nsname".equals(query.getCaptureNameForId(capture.getIndex()))) {
                        TSNode nameNode = capture.getNode();
                        if (nameNode != null) {
                            return sourceContent.substringFrom(nameNode).replace('\\', '.');
                        }
                    }
                }
            }
        }
        // Fallback to manual scan if query fails or no match, though query is preferred
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            TSNode current = rootNode.getChild(i);
            if (current != null && NAMESPACE_DEFINITION.equals(current.getType())) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null) {
                    return sourceContent.substringFrom(nameNode).replace('\\', '.');
                }
            }
            if (current != null
                    && !PHP_TAG.equals(current.getType())
                    && !NAMESPACE_DEFINITION.equals(current.getType())
                    && !DECLARE_STATEMENT.equals(current.getType())
                    && i > 5) {
                break; // Stop searching after a few top-level elements
            }
        }
        return ""; // No namespace found
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        // definitionNode is not used here as package is file-scoped.

        // If this.fileScopedPackageNames is null, it means this method is being called
        // from the superclass (TreeSitterAnalyzer) constructor, before this PhpAnalyzer
        // instance's fields (like fileScopedPackageNames or phpNamespaceQueryInstance) have been initialized.
        // In this specific scenario, we cannot use the instance cache for fileScopedPackageNames.
        // We must compute the package name directly. computeFilePackageName will handle query initialization.
        if (this.fileScopedPackageNames == null) {
            log.trace("PhpAnalyzer.determinePackageName called during super-constructor for file: {}", file);
            return computeFilePackageName(file, rootNode, sourceContent);
        }

        // If fileScopedPackageNames is not null, the PhpAnalyzer instance is (likely) fully initialized,
        // and we can use the caching mechanism.
        return fileScopedPackageNames.computeIfAbsent(file, f -> computeFilePackageName(f, rootNode, sourceContent));
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        if (cu.isClass()) { // CodeUnit.cls is used for class, interface, trait
            boolean isEmptyCuBody = childrenOf(cu).isEmpty();
            if (isEmptyCuBody) {
                return ""; // Closer already handled by renderClassHeader for empty bodies
            }
            return "}";
        }
        return "";
    }

    @Override
    protected String getLanguageSpecificIndent() {
        return "  ";
    }

    private String extractModifiers(TSNode methodNode, SourceContent sourceContent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < methodNode.getChildCount(); i++) {
            TSNode child = methodNode.getChild(i);
            if (child == null || child.isNull()) continue;
            String type = child.getType();

            if (PHP_SYNTAX_PROFILE.decoratorNodeTypes().contains(type)) { // This is an attribute
                sb.append(sourceContent.substringFrom(child)).append("\n");
            } else if (PHP_SYNTAX_PROFILE.modifierNodeTypes().contains(type)) { // This is a keyword modifier
                sb.append(sourceContent.substringFrom(child)).append(" ");
            } else if (FUNCTION_KEYWORD.equals(type)) { // Stop when the 'function' keyword token itself is encountered
                break;
            }
            // Other child types (e.g., comments, other anonymous tokens before 'function') are skipped.
        }
        return sb.toString();
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
        if (fieldNode.isNull()) {
            return super.formatFieldSignature(
                    fieldNode, sourceContent, exportPrefix, signatureText, simpleName, baseIndent, file);
        }

        // For PHP properties and constants, the initializer is inside property_element or const_element
        // but the captured node might be the parent declaration.
        TSNode elementNode = fieldNode;
        if (PROPERTY_DECLARATION.equals(fieldNode.getType())) {
            for (int i = 0; i < fieldNode.getNamedChildCount(); i++) {
                TSNode child = fieldNode.getNamedChild(i);
                if (PROPERTY_ELEMENT.equals(child.getType())) {
                    elementNode = child;
                    break;
                }
            }
        } else if (CONST_DECLARATION.equals(fieldNode.getType())) {
            for (int i = 0; i < fieldNode.getNamedChildCount(); i++) {
                TSNode child = fieldNode.getNamedChild(i);
                if (CONST_ELEMENT.equals(child.getType())) {
                    elementNode = child;
                    break;
                }
            }
        }

        if (elementNode == null || elementNode.isNull()) {
            return baseIndent + signatureText;
        }

        // Look for initializers
        TSNode valueNode = null;
        if (PROPERTY_ELEMENT.equals(elementNode.getType())) {
            valueNode = elementNode.getChildByFieldName("default_value");
            if (valueNode == null || valueNode.isNull()) {
                if (elementNode.getNamedChildCount() > 1) {
                    valueNode = elementNode.getNamedChild(elementNode.getNamedChildCount() - 1);
                }
            }
        } else if (CONST_ELEMENT.equals(elementNode.getType())) {
            valueNode = elementNode.getChildByFieldName("value");
            if (valueNode == null || valueNode.isNull()) {
                if (elementNode.getNamedChildCount() > 1) {
                    valueNode = elementNode.getNamedChild(elementNode.getNamedChildCount() - 1);
                }
            }
        }

        if (valueNode != null
                && !valueNode.isNull()
                && PROPERTY_INITIALIZER.equals(valueNode.getType())
                && valueNode.getNamedChildCount() > 0) {
            valueNode = valueNode.getNamedChild(0);
        }

        if (valueNode == null || valueNode.isNull() || isLiteralType(valueNode.getType())) {
            return super.formatFieldSignature(
                    fieldNode, sourceContent, exportPrefix, signatureText, simpleName, baseIndent, file);
        }

        // Truncate non-literals. Reconstruct the signature without the initializer.
        int eqIndex = signatureText.indexOf('=');
        if (eqIndex > 0) {
            return super.formatFieldSignature(
                    fieldNode,
                    sourceContent,
                    exportPrefix,
                    signatureText.substring(0, eqIndex).strip(),
                    simpleName,
                    baseIndent,
                    file);
        }

        return super.formatFieldSignature(
                fieldNode, sourceContent, exportPrefix, signatureText, simpleName, baseIndent, file);
    }

    private boolean isLiteralType(String type) {
        return type.endsWith("_literal")
                || INTEGER.equals(type)
                || FLOAT.equals(type)
                || STRING.equals(type)
                || ENCAPSED_STRING.equals(type)
                || STRING_VALUE.equals(type)
                || BOOLEAN.equals(type)
                || BOOLEAN_LITERAL.equals(type)
                || NULL.equals(type)
                || NULL_LITERAL.equals(type);
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
        TSNode bodyNode = classNode.getChildByFieldName(PHP_SYNTAX_PROFILE.bodyFieldName());
        boolean isEmptyBody =
                (bodyNode == null || bodyNode.getNamedChildCount() == 0); // bodyNode.isNull() check removed
        String suffix = isEmptyBody ? " { }" : " {";

        return signatureText.stripTrailing() + suffix;
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
        // Attributes that are children of the funcNode (e.g., PHP attributes on methods)
        // are collected by extractModifiers.
        // exportPrefix and asyncPrefix are "" for PHP. indent is also "" at this stage from base.
        String modifiers = extractModifiers(funcNode, sourceContent);

        String ampersand = "";
        TSNode referenceModifierNode = null;
        // Iterate children to find reference_modifier, as its position can vary slightly.
        for (int i = 0; i < funcNode.getChildCount(); i++) {
            TSNode child = funcNode.getChild(i);
            // Check for Java null before calling getType or other methods on child
            if (child != null && REFERENCE_MODIFIER.equals(child.getType())) {
                referenceModifierNode = child;
                break;
            }
        }

        if (referenceModifierNode != null) { // No need for !referenceModifierNode.isNull()
            ampersand = sourceContent.substringFrom(referenceModifierNode).trim();
        }

        String formattedReturnType = "";
        if (!returnTypeText.isEmpty()) {
            formattedReturnType = ": " + returnTypeText.strip();
        }

        String ampersandPart = ampersand.isEmpty() ? "" : ampersand;

        String mainSignaturePart = String.format(
                        "%sfunction %s%s%s%s", modifiers, ampersandPart, functionName, paramsText, formattedReturnType)
                .stripTrailing();

        TSNode bodyNode = funcNode.getChildByFieldName(PHP_SYNTAX_PROFILE.bodyFieldName());
        // If bodyNode is null or not a compound statement, it's an abstract/interface method.
        if (bodyNode != null && !bodyNode.isNull() && COMPOUND_STATEMENT.equals(bodyNode.getType())) {
            return mainSignaturePart + " { " + bodyPlaceholder() + " }";
        } else {
            // Abstract method or interface method (no body, ends with ';')
            return mainSignaturePart + ";";
        }
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, SourceContent sourceContent) {
        return "";
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // namespace.definition and namespace.name from the main query are ignored
        // as namespace processing is now handled by computeFilePackageName.
        // attribute.definition is handled by decorator logic in base class.
        return Set.of(CaptureNames.NAMESPACE_DEFINITION, "namespace.name", CaptureNames.ATTRIBUTE_DEFINITION);
    }

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, tree.getRootNode());
                        TSQueryMatch match = new TSQueryMatch();

                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                if (!TEST_MARKER.equals(captureName)) {
                                    continue;
                                }

                                TSNode node = capture.getNode();
                                if (node == null || node.isNull()) {
                                    continue;
                                }

                                String nodeType = node.getType();

                                // Name-based detection: @test_marker is captured on the function/method name node.
                                if (NAME.equals(nodeType)) {
                                    TSNode parent = node.getParent();
                                    if (parent == null || parent.isNull()) {
                                        continue;
                                    }

                                    String parentType = parent.getType();
                                    if (!FUNCTION_DEFINITION.equals(parentType)
                                            && !METHOD_DECLARATION.equals(parentType)) {
                                        continue;
                                    }

                                    String nameText = sourceContent.substringFrom(node);
                                    if (nameText.toLowerCase(Locale.ROOT).startsWith("test")) {
                                        return true;
                                    }
                                    continue;
                                }

                                // Docblock/Comment-based detection: @test_marker is also captured on comment nodes.
                                if (COMMENT.equals(nodeType)) {
                                    String commentText = sourceContent.substringFrom(node);
                                    if (!commentText.contains(TEST_TAG_AT_TEST)) {
                                        continue;
                                    }

                                    // Prefer AST adjacency: treat the comment as "belonging to" the next declaration
                                    // sibling.
                                    TSNode next = node.getNextSibling();
                                    while (next != null && !next.isNull() && isWhitespaceOnlyNode(next)) {
                                        next = next.getNextSibling();
                                    }

                                    if (next == null || next.isNull()) {
                                        continue;
                                    }

                                    String nextType = next.getType();
                                    if (FUNCTION_DEFINITION.equals(nextType) || METHOD_DECLARATION.equals(nextType)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                    return false;
                },
                false);
    }

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForPhp(reference);
    }

    @Override
    protected boolean isConstructor(CodeUnit candidate, @Nullable CodeUnit enclosingClass, String captureName) {
        return "__construct".equals(candidate.identifier());
    }
}
