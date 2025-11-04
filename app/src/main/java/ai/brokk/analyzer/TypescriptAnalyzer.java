package ai.brokk.analyzer;

import static ai.brokk.analyzer.typescript.TypeScriptTreeSitterNodeTypes.*;

import ai.brokk.IProject;
import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterTypescript;

public final class TypescriptAnalyzer extends TreeSitterAnalyzer {
    private static final TSLanguage TS_LANGUAGE = new TreeSitterTypescript();

    // Compiled regex patterns for memory efficiency
    private static final Pattern TRAILING_SEMICOLON = Pattern.compile(";\\s*$");
    private static final Pattern ENUM_COMMA_CLEANUP = Pattern.compile(",\\s*\\r?\\n(\\s*})");
    private static final Pattern TYPE_ALIAS_LINE = Pattern.compile("(type |export type ).*=.*");

    // Fast lookups for type checks
    private static final Set<String> FUNCTION_NODE_TYPES =
            Set.of(FUNCTION_DECLARATION, GENERATOR_FUNCTION_DECLARATION, FUNCTION_SIGNATURE);

    // Class keyword mapping for fast lookup
    private static final Map<String, String> CLASS_KEYWORDS = Map.of(
            INTERFACE_DECLARATION, INTERFACE,
            ENUM_DECLARATION, ENUM,
            MODULE, NAMESPACE,
            INTERNAL_MODULE, NAMESPACE,
            AMBIENT_DECLARATION, NAMESPACE,
            ABSTRACT_CLASS_DECLARATION, ABSTRACT_CLASS);

    private static final LanguageSyntaxProfile TS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            // classLikeNodeTypes
            Set.of(
                    CLASS_DECLARATION,
                    INTERFACE_DECLARATION,
                    ENUM_DECLARATION,
                    ABSTRACT_CLASS_DECLARATION,
                    MODULE,
                    INTERNAL_MODULE),
            // functionLikeNodeTypes
            Set.of(
                    FUNCTION_DECLARATION,
                    METHOD_DEFINITION,
                    ARROW_FUNCTION,
                    GENERATOR_FUNCTION_DECLARATION,
                    FUNCTION_SIGNATURE,
                    METHOD_SIGNATURE,
                    ABSTRACT_METHOD_SIGNATURE), // function_signature for overloads, method_signature for interfaces,
            // abstract_method_signature for abstract classes
            // fieldLikeNodeTypes
            Set.of(
                    VARIABLE_DECLARATOR,
                    PUBLIC_FIELD_DEFINITION,
                    PROPERTY_SIGNATURE,
                    ENUM_MEMBER,
                    LEXICAL_DECLARATION,
                    VARIABLE_DECLARATION), // type_alias_declaration will be ALIAS_LIKE
            // decoratorNodeTypes
            Set.of(DECORATOR),
            // imports
            IMPORT_DECLARATION,
            // identifierFieldName
            "name",
            // bodyFieldName
            "body",
            // parametersFieldName
            "parameters",
            // returnTypeFieldName
            "return_type", // TypeScript has explicit return types
            // typeParametersFieldName
            "type_parameters", // Standard field name for type parameters in TS
            // captureConfiguration - using unified naming convention
            Map.of(
                    CaptureNames.TYPE_DEFINITION,
                    SkeletonType.CLASS_LIKE, // Classes, interfaces, enums, namespaces
                    CaptureNames.FUNCTION_DEFINITION,
                    SkeletonType.FUNCTION_LIKE, // Functions, methods
                    CaptureNames.VALUE_DEFINITION,
                    SkeletonType.FIELD_LIKE, // Variables, fields, constants
                    CaptureNames.TYPEALIAS_DEFINITION,
                    SkeletonType.ALIAS_LIKE, // Type aliases
                    CaptureNames.DECORATOR_DEFINITION,
                    SkeletonType.UNSUPPORTED, // Keep as UNSUPPORTED but handle differently
                    "keyword.modifier",
                    SkeletonType.UNSUPPORTED),
            // asyncKeywordNodeType
            "async", // TS uses 'async' keyword
            // modifierNodeTypes: Contains node types of keywords/constructs that act as modifiers.
            // Used in TreeSitterAnalyzer.buildSignatureString to gather modifiers by inspecting children.
            Set.of(
                    "export",
                    "default",
                    "declare",
                    "abstract",
                    "static",
                    "readonly",
                    "accessibility_modifier", // for public, private, protected
                    "async",
                    "const",
                    "let",
                    "var",
                    "override" // "override" might be via override_modifier
                    // Note: "public", "private", "protected" themselves are not node types here,
                    // but "accessibility_modifier" is the node type whose text content is one of these.
                    // "const", "let" are token types for the `kind` of a lexical_declaration, often its first child.
                    // "var" is a token type, often first child of variable_declaration.
                    ));

    public TypescriptAnalyzer(IProject project) {
        super(project, Languages.TYPESCRIPT);
    }

    private TypescriptAnalyzer(IProject project, AnalyzerState state) {
        super(project, Languages.TYPESCRIPT, state);
    }

    @Override
    protected IAnalyzer newSnapshot(AnalyzerState state) {
        return new TypescriptAnalyzer(getProject(), state);
    }

    private String cachedTextSliceStripped(TSNode node, String src) {
        return textSlice(node, src).strip();
    }

    @Override
    protected TSLanguage getTSLanguage() {
        return TS_LANGUAGE;
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/typescript.scm";
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return TS_SYNTAX_PROFILE;
    }

    @Override
    protected SkeletonType refineSkeletonType(
            String captureName, TSNode definitionNode, LanguageSyntaxProfile profile) {
        if (definitionNode == null || definitionNode.isNull()) {
            return super.refineSkeletonType(captureName, definitionNode, profile);
        }

        // Direct variable declarator with arrow function initializer
        if ("variable_declarator".equals(definitionNode.getType())) {
            TSNode valueNode = definitionNode.getChildByFieldName("value");
            if (valueNode != null && !valueNode.isNull() && "arrow_function".equals(valueNode.getType())) {
                return SkeletonType.FUNCTION_LIKE;
            }
        }

        // Declarations that contain variable_declarators (const/let/var)
        if ("lexical_declaration".equals(definitionNode.getType())
                || "variable_declaration".equals(definitionNode.getType())) {
            // Scan children for the declarator represented by this capture.
            // If any declarator has an arrow_function value, classify as FUNCTION_LIKE.
            for (int i = 0; i < definitionNode.getNamedChildCount(); i++) {
                TSNode child = definitionNode.getNamedChild(i);
                if (child != null && !child.isNull() && "variable_declarator".equals(child.getType())) {
                    TSNode valueNode = child.getChildByFieldName("value");
                    if (valueNode != null && !valueNode.isNull() && "arrow_function".equals(valueNode.getType())) {
                        return SkeletonType.FUNCTION_LIKE;
                    }
                }
            }
        }

        return super.refineSkeletonType(captureName, definitionNode, profile);
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file,
            String captureName,
            String simpleName,
            String packageName,
            String classChain,
            TSNode definitionNode,
            SkeletonType skeletonType) {
        String finalShortName;
        final String shortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;

        switch (skeletonType) {
            case CLASS_LIKE -> {
                finalShortName = shortName;
                return CodeUnit.cls(file, packageName, finalShortName);
            }
            case FUNCTION_LIKE -> {
                if (simpleName.equals("anonymous_arrow_function") || simpleName.isEmpty()) {
                    log.warn(
                            "Anonymous or unnamed function found for capture {} in file {}. ClassChain: {}. Will use placeholder or rely on extracted name.",
                            captureName,
                            file,
                            classChain);
                }
                finalShortName = shortName;
                return CodeUnit.fn(file, packageName, finalShortName);
            }
            case FIELD_LIKE -> {
                finalShortName = classChain.isEmpty() ? "_module_." + simpleName : classChain + "." + simpleName;
                return CodeUnit.field(file, packageName, finalShortName);
            }
            case ALIAS_LIKE -> {
                finalShortName = classChain.isEmpty() ? "_module_." + simpleName : classChain + "." + simpleName;
                return CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                log.debug(
                        "Ignoring capture in TypescriptAnalyzer: {} (mapped to type {}) with name: {} and classChain: {}",
                        captureName,
                        skeletonType,
                        simpleName,
                        classChain);
                throw new UnsupportedOperationException("Unsupported skeleton type: " + skeletonType);
            }
        }
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        return createCodeUnit(
                file, captureName, simpleName, packageName, classChain, null, getSkeletonTypeForCapture(captureName));
    }

    @Override
    protected String formatReturnType(@Nullable TSNode returnTypeNode, String src) {
        if (returnTypeNode == null || returnTypeNode.isNull()) {
            return "";
        }
        String text = cachedTextSliceStripped(returnTypeNode, src);
        // A type_annotation node in TS is typically ": type"
        // We only want the "type" part for the suffix.
        if (text.startsWith(":")) {
            return text.substring(1).strip();
        }
        return text; // Should not happen if TS grammar for return_type capture is specific to type_annotation
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // Initial implementation: directory-based, like JavaScript.
        // TODO: Enhance to detect 'namespace A.B.C {}' or 'module A.B.C {}' and use that.
        var projectRoot = getProject().getRoot();
        var filePath = file.absPath();
        var parentDir = filePath.getParent();

        if (parentDir == null || parentDir.equals(projectRoot)) {
            return ""; // File is in the project root
        }

        var relPath = projectRoot.relativize(parentDir);
        return relPath.toString().replace('/', '.').replace('\\', '.');
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            String src,
            String exportAndModifierPrefix,
            String ignoredAsyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        // Use text slicing approach for simpler rendering
        TSNode bodyNode =
                funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        boolean hasBody = bodyNode != null && !bodyNode.isNull() && bodyNode.getEndByte() > bodyNode.getStartByte();

        // For arrow functions, handle specially
        if (ARROW_FUNCTION.equals(funcNode.getType())) {
            String prefix = exportAndModifierPrefix.stripTrailing();
            String asyncPart = ignoredAsyncPrefix.isEmpty() ? "" : ignoredAsyncPrefix + " ";
            String returnTypeSuffix = !returnTypeText.isEmpty() ? ": " + returnTypeText.strip() : "";

            String signature = String.format(
                            "%s %s%s = %s%s%s =>",
                            prefix, functionName, typeParamsText, asyncPart, paramsText, returnTypeSuffix)
                    .stripLeading();
            return indent + signature + " " + bodyPlaceholder();
        }

        // For regular functions, use text slicing when possible
        if (hasBody) {
            String signature = textSlice(funcNode.getStartByte(), bodyNode.getStartByte(), src)
                    .strip();

            // Prepend export and other modifiers if not already present
            String prefix = exportAndModifierPrefix.stripTrailing();
            if (!prefix.isEmpty() && !signature.startsWith(prefix)) {
                // Check if any word in the prefix is already in the signature to avoid duplicates
                List<String> prefixWords = Splitter.on(Pattern.compile("\\s+")).splitToList(prefix);
                StringBuilder uniquePrefix = new StringBuilder();
                for (String word : prefixWords) {
                    if (!signature.contains(word)) {
                        uniquePrefix.append(word).append(" ");
                    }
                }
                if (uniquePrefix.length() > 0) {
                    signature = uniquePrefix.toString().stripTrailing() + " " + signature;
                }
            }

            return indent + signature + " " + bodyPlaceholder();
        }

        // For signatures without bodies, build minimal signature
        String prefix = exportAndModifierPrefix.stripTrailing();
        String keyword = getKeywordForFunction(funcNode, functionName);
        String returnTypeSuffix = !returnTypeText.isEmpty() ? ": " + returnTypeText.strip() : "";

        var parts = new ArrayList<String>();
        if (!prefix.isEmpty()) parts.add(prefix);
        if (!keyword.isEmpty()) parts.add(keyword);
        // For construct signatures, keyword is "new" and functionName is also "new", so skip functionName
        if (!functionName.isEmpty() && !keyword.equals(functionName)) {
            parts.add(functionName + typeParamsText);
        } else if (keyword.equals("constructor")) {
            parts.add(functionName + typeParamsText);
        } else if (keyword.isEmpty() && !functionName.isEmpty()) {
            parts.add(functionName + typeParamsText);
        } else if (keyword.equals(functionName) && !typeParamsText.isEmpty()) {
            // For construct signatures with type parameters, add them after the keyword
            parts.set(parts.size() - 1, keyword + typeParamsText);
        }

        // For construct signatures, we need a space before params
        boolean needsSpaceBeforeParams = CONSTRUCT_SIGNATURE.equals(funcNode.getType());

        String signature = String.join(" ", parts);
        if (!paramsText.isEmpty()) {
            signature += (needsSpaceBeforeParams && !signature.isEmpty() ? " " : "") + paramsText;
        }
        signature += returnTypeSuffix;

        // Add semicolon for:
        // - function signatures inside namespaces (but not those that start with "declare")
        // - ambient function declarations (those with "declare")
        // But NOT for export function overloads
        if ("function_signature".equals(funcNode.getType())) {
            if (prefix.contains("declare")
                    || // ambient declarations need semicolons
                    (isInNamespaceContext(funcNode)
                            && !prefix.contains("declare"))) { // namespace functions need semicolons
                signature += ";";
            }
            // Export function overloads don't need semicolons
        }

        return indent + signature;
    }

    private String getKeywordForFunction(TSNode funcNode, String functionName) {
        String nodeType = funcNode.getType();
        if (FUNCTION_NODE_TYPES.contains(nodeType)) {
            if ("function_signature".equals(nodeType) && isInNamespaceContext(funcNode)) {
                return "";
            }
            return "function";
        }
        if ("constructor".equals(functionName)) return "constructor";
        if ("construct_signature".equals(nodeType)) return "new";
        return "";
    }

    @Override
    protected String formatFieldSignature(
            TSNode fieldNode,
            String src,
            String exportPrefix,
            String signatureText,
            String baseIndent,
            ProjectFile file) {
        String fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();

        // Remove trailing semicolons
        fullSignature = TRAILING_SEMICOLON.matcher(fullSignature).replaceAll("");

        // Special handling for enum members - add comma instead of semicolon
        String suffix = "";
        if (!fieldNode.isNull()
                && fieldNode.getParent() != null
                && !fieldNode.getParent().isNull()
                && "enum_body".equals(fieldNode.getParent().getType())
                && ("property_identifier".equals(fieldNode.getType())
                        || "enum_assignment".equals(fieldNode.getType()))) {
            // Enum members get commas, not semicolons
            suffix = ",";
        }

        return baseIndent + fullSignature + suffix;
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode, String src, String exportAndModifierPrefix, String signatureText, String baseIndent) {
        // Use text slicing approach but include export prefix
        TSNode bodyNode =
                classNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        if (bodyNode != null && !bodyNode.isNull()) {
            String signature = textSlice(classNode.getStartByte(), bodyNode.getStartByte(), src)
                    .strip();

            // Prepend export and other modifiers if not already present
            String prefix = exportAndModifierPrefix.stripTrailing();
            if (!prefix.isEmpty() && !signature.startsWith(prefix)) {
                // Check if any word in the prefix is already in the signature to avoid duplicates
                List<String> prefixWords = Splitter.on(Pattern.compile("\\s+")).splitToList(prefix);
                StringBuilder uniquePrefix = new StringBuilder();
                for (String word : prefixWords) {
                    if (!signature.contains(word)) {
                        uniquePrefix.append(word).append(" ");
                    }
                }
                if (uniquePrefix.length() > 0) {
                    signature = uniquePrefix.toString().stripTrailing() + " " + signature;
                }
            }

            return baseIndent + signature + " {";
        }

        // Fallback for classes without bodies
        String classKeyword = CLASS_KEYWORDS.getOrDefault(classNode.getType(), "class");
        String prefix = exportAndModifierPrefix.stripTrailing();

        // For abstract classes, avoid duplicate "abstract" keyword
        if ("abstract class".equals(classKeyword)) {
            prefix = prefix.replaceAll("\\babstract\\b\\s*", "").strip();
        }

        String finalPrefix = prefix.isEmpty() ? "" : prefix + " ";
        String cleanSignature = signatureText.stripLeading();

        return baseIndent + finalPrefix + classKeyword + " " + cleanSignature + " {";
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        // TypeScript modifier extraction - check node itself and immediate parent for export/declare wrappers
        // Synthesizes modifiers when @keyword.modifier captures are not present.
        StringBuilder modifiers = new StringBuilder();

        TSNode nodeToCheck = node;

        // Check if the node itself is an export statement
        if ("export_statement".equals(node.getType())) {
            modifiers.append("export ");

            // Check for default export
            if (node.getChildCount() > 1) {
                TSNode secondChild = node.getChild(1);
                if (secondChild != null && "default".equals(cachedTextSliceStripped(secondChild, src))) {
                    modifiers.append("default ");
                }
            }

            // Get the declaration inside the export statement for further modifier checks
            TSNode declaration = node.getChildByFieldName("declaration");
            if (declaration != null && !declaration.isNull()) {
                nodeToCheck = declaration;
            }
        } else {
            // Check if the parent is an export statement (for nodes like variable_declarator)
            TSNode parent = node.getParent();
            if (parent != null && !parent.isNull() && "export_statement".equals(parent.getType())) {
                modifiers.append("export ");
                // Check for default export
                if (parent.getChildCount() > 1) {
                    TSNode secondChild = parent.getChild(1);
                    if (secondChild != null && "default".equals(cachedTextSliceStripped(secondChild, src))) {
                        modifiers.append("default ");
                    }
                }
            }
        }

        // Check if the node itself is an ambient declaration
        if ("ambient_declaration".equals(nodeToCheck.getType())) {
            modifiers.append("declare ");

            // Get the declaration inside the ambient declaration for further modifier checks
            for (int i = 0; i < nodeToCheck.getChildCount(); i++) {
                TSNode child = nodeToCheck.getChild(i);
                if (child != null && !child.isNull() && !"declare".equals(cachedTextSliceStripped(child, src))) {
                    nodeToCheck = child;
                    break;
                }
            }
        } else {
            // Check if the immediate parent or grandparent is an ambient declaration (for nodes like
            // variable_declarator)
            // But stop if we hit a body (which means we're nested and shouldn't inherit ambient from outer scope)
            TSNode parentAmbient = node.getParent();
            if (parentAmbient != null && !parentAmbient.isNull()) {
                // First check the immediate parent
                if ("ambient_declaration".equals(parentAmbient.getType())) {
                    modifiers.append("declare ");
                } else if (!("class_body".equals(parentAmbient.getType())
                        || "interface_body".equals(parentAmbient.getType())
                        || "enum_body".equals(parentAmbient.getType())
                        || "namespace_body".equals(parentAmbient.getType()))) {
                    // If parent is not a body and not ambient, check grandparent
                    TSNode grandparent = parentAmbient.getParent();
                    if (grandparent != null
                            && !grandparent.isNull()
                            && "ambient_declaration".equals(grandparent.getType())) {
                        modifiers.append("declare ");
                    }
                }
            }
        }

        // Check if the node or its unwrapped form is a variable declarator, check the parent declaration (lexical/var)
        TSNode parentDecl = nodeToCheck.getParent();
        if ("variable_declarator".equals(nodeToCheck.getType())
                && parentDecl != null
                && ("lexical_declaration".equals(parentDecl.getType())
                        || "variable_declaration".equals(parentDecl.getType()))) {
            nodeToCheck = parentDecl;
        }

        // Look for modifier keywords in the first few children of the declaration
        for (int i = 0; i < Math.min(nodeToCheck.getChildCount(), 6); i++) {
            TSNode child = nodeToCheck.getChild(i);
            if (child != null && !child.isNull()) {
                String childText = cachedTextSliceStripped(child, src);
                if (Set.of("abstract", "static", "readonly", "async", "const", "let", "var")
                        .contains(childText)) {
                    modifiers.append(childText).append(" ");
                } else if ("accessibility_modifier".equals(child.getType())) {
                    modifiers.append(childText).append(" ");
                }
            }
        }

        return modifiers.toString();
    }

    @Override
    protected String bodyPlaceholder() {
        return "{ ... }"; // TypeScript typically uses braces
    }

    @Override
    protected boolean shouldUnwrapExportStatements() {
        return true;
    }

    @Override
    protected boolean needsVariableDeclaratorUnwrapping(TSNode node, SkeletonType skeletonType) {
        return skeletonType == SkeletonType.FIELD_LIKE || skeletonType == SkeletonType.FUNCTION_LIKE;
    }

    @Override
    protected boolean shouldMergeSignaturesForSameFqn() {
        return true;
    }

    @Override
    protected String enhanceFqName(String fqName, String captureName, TSNode definitionNode, String src) {
        var skeletonType = getSkeletonTypeForCapture(captureName);

        // For function-like and field-like nodes in classes, check for modifiers
        if (skeletonType == SkeletonType.FUNCTION_LIKE || skeletonType == SkeletonType.FIELD_LIKE) {
            // Check if this is a method/field inside a class (not top-level)
            TSNode parent = definitionNode.getParent();
            if (parent != null && !parent.isNull()) {
                String parentType = parent.getType();
                // Check if parent is class_body (methods/fields are children of class_body)
                if ("class_body".equals(parentType)) {
                    // Check for accessor keywords (get/set) first, as they're more specific
                    // Handle both concrete methods (method_definition) and abstract methods (abstract_method_signature)
                    String nodeType = definitionNode.getType();
                    if ("method_definition".equals(nodeType) || "abstract_method_signature".equals(nodeType)) {
                        String accessorType = getAccessorType(definitionNode);
                        if ("get".equals(accessorType)) {
                            return fqName + "$get";
                        } else if ("set".equals(accessorType)) {
                            return fqName + "$set";
                        }
                    }

                    // Check for "static" modifier among the children of definitionNode
                    if (hasStaticModifier(definitionNode)) {
                        return fqName + "$static";
                    }
                }
            }
        }

        return fqName;
    }

    /**
     * Checks if a node has a "static" modifier as one of its children.
     *
     * @param node the node to check
     * @return true if the node has a "static" child, false otherwise
     */
    private boolean hasStaticModifier(TSNode node) {
        if (node == null || node.isNull()) {
            return false;
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                if ("static".equals(child.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Determines if a method_definition node is a getter or setter accessor.
     *
     * @param node the method_definition node to check
     * @return "get" if it's a getter, "set" if it's a setter, or empty string if neither
     */
    private String getAccessorType(TSNode node) {
        if (node == null || node.isNull()) {
            return "";
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                String childType = child.getType();
                if ("get".equals(childType)) {
                    return "get";
                } else if ("set".equals(childType)) {
                    return "set";
                }
            }
        }
        return "";
    }

    @Override
    protected boolean isMissingNameCaptureAllowed(String captureName, String nodeType, String fileName) {
        // Suppress DEBUG message for function.definition when name capture is missing
        // - function_declaration: fallback extractSimpleName works correctly
        // - construct_signature: intentionally has no name, uses default "new"
        return "function.definition".equals(captureName)
                && ("function_declaration".equals(nodeType) || "construct_signature".equals(nodeType));
    }

    @Override
    protected boolean shouldSkipNode(TSNode node, String captureName, byte[] srcBytes) {
        // Skip method_definition nodes that are inside object literals
        // This prevents duplicate FQNames when a class has both:
        // - A field with name X
        // - A getter/setter/method with name X inside an object literal assigned to another field
        //
        // Example from VSCode:
        // class ExtHostTerminal {
        //     shellIntegration: TerminalShellIntegration;     // field
        //     value = {
        //         get shellIntegration() { ... }               // getter in object literal - should be skipped
        //     };
        // }
        if ("method_definition".equals(node.getType())) {
            // Walk up the AST to see if we're inside an object literal
            TSNode parent = node.getParent();
            while (parent != null && !parent.isNull()) {
                String parentType = parent.getType();

                // If we hit class_body first, we're a class method - keep it
                if ("class_body".equals(parentType)) {
                    return false;
                }

                // If we hit an object literal, we're inside an object - skip it
                if ("object".equals(parentType)) {
                    return true;
                }

                parent = parent.getParent();
            }
        }

        return false;
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        // Classes, interfaces, enums, modules/namespaces all use '}'
        if (cu.isClass()) { // isClass is true for all CLASS_LIKE CUs
            return "}";
        }
        return "";
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // e.g., @parameters, @return_type_node if they are only for context and not main definitions
        return Set.of(
                "parameters", "return_type_node", "predefined_type_node", "type_identifier_node", "export.keyword");
    }

    @Override
    protected boolean isBenignDuplicate(CodeUnit existing, CodeUnit candidate) {
        // TypeScript supports declaration merging where a function and namespace can share the same name.
        // This is a legitimate language feature, not an error.
        // Example: function foo() {} and namespace foo { export const x = 1; }
        return (existing.isFunction() && candidate.isClass()) || (existing.isClass() && candidate.isFunction());
    }

    @Override
    protected boolean shouldIgnoreDuplicate(CodeUnit existing, CodeUnit candidate, ProjectFile file) {
        // For function+namespace declaration merging, keep the first one (typically the function)
        if (isBenignDuplicate(existing, candidate)) {
            log.trace(
                    "TypeScript declaration merging detected for {} (function + namespace). Keeping {} kind.",
                    existing.fqName(),
                    existing.isFunction() ? "function" : "namespace");
            return true; // Ignore the duplicate (keep first one)
        }

        // Default behavior for other duplicates
        return super.shouldIgnoreDuplicate(existing, candidate, file);
    }

    /**
     * Checks if a function node is inside an ambient declaration context (declare namespace/module). In ambient
     * contexts, function signatures should not include the "function" keyword.
     */
    public boolean isInAmbientContext(TSNode node) {
        return checkAmbientContextDirect(node);
    }

    /**
     * Checks if a function node is inside a namespace/module context where function signatures should not include the
     * "function" keyword. This includes both regular namespaces and functions inside ambient namespaces, but excludes
     * top-level ambient function declarations.
     */
    public boolean isInNamespaceContext(TSNode node) {
        TSNode parent = node.getParent();
        while (parent != null && !parent.isNull()) {
            String parentType = parent.getType();

            // If we find an internal_module (namespace), the function is inside a namespace
            if ("internal_module".equals(parentType)) {
                return true;
            }

            // If we find a statement_block that's inside an internal_module, we're in a namespace
            if ("statement_block".equals(parentType)) {
                TSNode grandParent = parent.getParent();
                if (grandParent != null && "internal_module".equals(grandParent.getType())) {
                    return true;
                }
            }

            parent = parent.getParent();
        }
        return false;
    }

    private boolean checkAmbientContextDirect(TSNode node) {
        TSNode parent = node.getParent();
        while (parent != null && !parent.isNull()) {
            if ("ambient_declaration".equals(parent.getType())) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    @Override
    public Optional<String> getSkeleton(CodeUnit cu) {
        // Find the top-level parent to get the full namespace skeleton
        CodeUnit topLevel = findTopLevelParent(cu);

        // Get skeleton through getSkeletons() which applies TypeScript-specific cleanup
        Map<CodeUnit, String> skeletons = getSkeletons(topLevel.source());
        String skeleton = skeletons.get(topLevel);

        return Optional.ofNullable(skeleton);
    }

    /** Find the top-level parent CodeUnit for a given CodeUnit. If the CodeUnit has no parent, it returns itself. */
    private CodeUnit findTopLevelParent(CodeUnit cu) {
        // Build parent chain without caching
        CodeUnit current = cu;
        CodeUnit parent = findDirectParent(current);
        while (parent != null) {
            current = parent;
            parent = findDirectParent(current);
        }
        return current;
    }

    /** Find direct parent of a CodeUnit by looking in childrenByParent map */
    private @Nullable CodeUnit findDirectParent(CodeUnit cu) {
        for (var entry : withCodeUnitProperties(Map::entrySet)) {
            CodeUnit parent = entry.getKey();
            List<CodeUnit> children = entry.getValue().children();
            if (children.contains(cu)) {
                return parent;
            }
        }
        return null;
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        var skeletons = super.getSkeletons(file);

        // Clean up skeleton content and handle duplicates more carefully
        var cleanedSkeletons = new HashMap<CodeUnit, String>();

        for (var entry : skeletons.entrySet()) {
            CodeUnit cu = entry.getKey();
            String skeleton = entry.getValue();

            // Fix duplicate interface headers within skeleton
            if (skeleton.contains("interface ") && skeleton.contains("export interface ")) {
                // Remove lines that are just "interface Name {" when we already have "export interface Name {"
                var lines = List.of(skeleton.split("\n"));
                var filteredLines = new ArrayList<String>();
                boolean foundExportInterface = false;

                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("export interface ") && trimmed.endsWith(" {")) {
                        foundExportInterface = true;
                        filteredLines.add(line);
                    } else if (foundExportInterface
                            && trimmed.startsWith("interface ")
                            && trimmed.endsWith(" {")
                            && !trimmed.startsWith("export interface ")) {
                        // Skip this duplicate interface header
                    } else {
                        filteredLines.add(line);
                    }
                }
                skeleton = String.join("\n", filteredLines);
            }

            cleanedSkeletons.put(cu, skeleton);
        }

        // Now handle FQN-based deduplication only for class-like entities
        var deduplicatedSkeletons = new HashMap<String, Map.Entry<CodeUnit, String>>();

        for (var entry : cleanedSkeletons.entrySet()) {
            CodeUnit cu = entry.getKey();
            String skeleton = entry.getValue();
            String fqn = cu.fqName();

            // Only deduplicate class-like entities (interfaces, classes, enums, etc.)
            // Don't deduplicate field-like entities as they should be unique
            if (cu.isClass()) {
                // Check if we already have this FQN for class-like entities
                if (deduplicatedSkeletons.containsKey(fqn)) {
                    // Prefer the one with "export" in the skeleton
                    String existingSkeleton = deduplicatedSkeletons.get(fqn).getValue();
                    if (skeleton.startsWith("export") && !existingSkeleton.startsWith("export")) {
                        // Replace with export version
                        deduplicatedSkeletons.put(fqn, Map.entry(cu, skeleton));
                    }
                    // Otherwise keep the existing one
                } else {
                    deduplicatedSkeletons.put(fqn, Map.entry(cu, skeleton));
                }
            } else {
                // For non-class entities (functions, fields), don't deduplicate by FQN
                // Use a unique key to preserve all of them
                String uniqueKey = fqn + "#" + cu.kind() + "#" + System.identityHashCode(cu);
                deduplicatedSkeletons.put(uniqueKey, Map.entry(cu, skeleton));
            }
        }

        // Apply basic cleanup
        var cleaned = new HashMap<CodeUnit, String>(deduplicatedSkeletons.size());
        for (var entry : deduplicatedSkeletons.values()) {
            CodeUnit cu = entry.getKey();
            String skeleton = entry.getValue();

            // Basic cleanup: remove trailing commas in enums and semicolons from type aliases
            skeleton = ENUM_COMMA_CLEANUP.matcher(skeleton).replaceAll("\n$1");

            // Remove semicolons from type alias lines
            var lines = Splitter.on('\n').splitToList(skeleton);
            var skeletonBuilder = new StringBuilder(skeleton.length());
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (TYPE_ALIAS_LINE.matcher(line).find()) {
                    line = TRAILING_SEMICOLON.matcher(line).replaceAll("");
                }
                skeletonBuilder.append(line);
                if (i < lines.size() - 1) {
                    skeletonBuilder.append("\n");
                }
            }
            skeleton = skeletonBuilder.toString();

            cleaned.put(cu, skeleton);
        }

        return cleaned;
    }

    @Override
    @SuppressWarnings("RedundantNullCheck")
    public boolean isTypeAlias(CodeUnit cu) {
        // Check if this field-type CodeUnit represents a type alias
        // We can identify this by checking if there are signatures that contain "type " and " = "
        var sigList = signaturesOf(cu);

        for (var sig : sigList) {
            var hasType = sig.contains("type ") || sig.contains("export type ");
            var hasEquals = sig.contains(" = ");

            if (hasType && hasEquals) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected Optional<String> extractSimpleName(TSNode decl, String src) {
        // Handle constructor signatures which don't have a name field
        if ("construct_signature".equals(decl.getType())) {
            return Optional.of("new");
        }
        return super.extractSimpleName(decl, src);
    }

    @Override
    protected void buildFunctionSkeleton(
            TSNode funcNode,
            Optional<String> providedNameOpt,
            String src,
            String indent,
            List<String> lines,
            String exportPrefix) {
        // Handle variable_declarator containing arrow function
        if ("variable_declarator".equals(funcNode.getType())) {
            TSNode valueNode = funcNode.getChildByFieldName("value");
            if (valueNode != null && !valueNode.isNull() && "arrow_function".equals(valueNode.getType())) {
                // Build the const/let declaration with arrow function
                String fullDeclaration = textSlice(funcNode, src).strip();

                // Replace function body with placeholder
                TSNode bodyNode = valueNode.getChildByFieldName("body");
                if (bodyNode != null && !bodyNode.isNull()) {
                    String beforeBody = textSlice(funcNode.getStartByte(), bodyNode.getStartByte(), src)
                            .strip();
                    String signature = exportPrefix.stripTrailing() + " " + beforeBody + " " + bodyPlaceholder();
                    lines.add(indent + signature.stripLeading());
                } else {
                    lines.add(indent + exportPrefix.stripTrailing() + " " + fullDeclaration);
                }
                return;
            }
        }

        // Handle constructor signatures specially
        if ("construct_signature".equals(funcNode.getType())) {
            TSNode typeNode = funcNode.getChildByFieldName("type");
            if (typeNode != null && !typeNode.isNull()) {
                String typeText = textSlice(typeNode, src);
                String returnTypeText =
                        typeText.startsWith(":") ? typeText.substring(1).strip() : typeText;

                var profile = getLanguageSyntaxProfile();
                String functionName = extractSimpleName(funcNode, src).orElse("new");
                TSNode paramsNode = funcNode.getChildByFieldName(profile.parametersFieldName());
                String paramsText = formatParameterList(paramsNode, src);

                String typeParamsText = "";
                TSNode typeParamsNode = funcNode.getChildByFieldName(profile.typeParametersFieldName());
                if (typeParamsNode != null && !typeParamsNode.isNull()) {
                    typeParamsText = textSlice(typeParamsNode, src);
                }

                String signature = renderFunctionDeclaration(
                        funcNode,
                        src,
                        exportPrefix,
                        "",
                        functionName,
                        typeParamsText,
                        paramsText,
                        returnTypeText,
                        indent);
                if (!signature.isBlank()) {
                    lines.add(signature);
                }
                return;
            }
        }

        // For all other cases, use the parent implementation
        super.buildFunctionSkeleton(funcNode, providedNameOpt, src, indent, lines, exportPrefix);
    }

    @Override
    public Optional<String> extractClassName(String reference) {
        return ClassNameExtractor.extractForJsTs(reference);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterTypescript();
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
            Map<CodeUnit, List<Range>> localSourceRanges) {
        JavascriptAnalyzer.createModulesFromJavaScriptLikeImports(
                file,
                localImportStatements,
                rootNode,
                modulePackageName,
                localCuByFqName,
                localTopLevelCUs,
                localSignatures,
                localSourceRanges);
    }
}
