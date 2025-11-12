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
            Map.ofEntries(
                    Map.entry(
                            CaptureNames.TYPE_DEFINITION,
                            SkeletonType.CLASS_LIKE), // Classes, interfaces, enums, namespaces
                    Map.entry(CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE),
                    Map.entry(CaptureNames.ARROW_FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE),
                    Map.entry(CaptureNames.VALUE_DEFINITION, SkeletonType.FIELD_LIKE),
                    Map.entry(CaptureNames.TYPEALIAS_DEFINITION, SkeletonType.ALIAS_LIKE),
                    Map.entry(CaptureNames.DECORATOR_DEFINITION, SkeletonType.UNSUPPORTED),
                    Map.entry("keyword.modifier", SkeletonType.UNSUPPORTED)),
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
        return super.refineSkeletonType(captureName, definitionNode, profile);
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file,
            String captureName,
            String simpleName,
            String packageName,
            String classChain,
            @Nullable TSNode definitionNode,
            SkeletonType skeletonType) {
        // In TypeScript, namespaces appear in BOTH packageName and classChain.
        // To avoid duplication in FQNames, strip the package prefix from classChain.
        String adjustedClassChain = classChain;
        if (!packageName.isBlank() && !classChain.isBlank()) {
            if (classChain.equals(packageName)) {
                // ClassChain is exactly the package - just the namespace, no nesting
                adjustedClassChain = "";
            } else if (classChain.startsWith(packageName)
                    && classChain.length() > packageName.length()
                    && classChain.charAt(packageName.length()) == '.') {
                // ClassChain starts with package - remove the package prefix
                // Optimized to avoid string concatenation in hot path
                adjustedClassChain = classChain.substring(packageName.length() + 1);
            }
        }

        String finalShortName;
        final String shortName = adjustedClassChain.isEmpty() ? simpleName : adjustedClassChain + "." + simpleName;

        switch (skeletonType) {
            case CLASS_LIKE -> {
                finalShortName = shortName;
                return CodeUnit.cls(file, packageName, finalShortName);
            }
            case FUNCTION_LIKE -> {
                if (definitionNode != null && !definitionNode.isNull()) {
                    String nodeType = definitionNode.getType();
                    if ("call_signature".equals(nodeType)) {
                        finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                        return CodeUnit.fn(file, packageName, finalShortName);
                    }
                }
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
                if (definitionNode != null && !definitionNode.isNull()) {
                    String nodeType = definitionNode.getType();
                    if ("index_signature".equals(nodeType)) {
                        // Fields require "Container.field" format; use _module_. when no class container
                        finalShortName = adjustedClassChain.isEmpty()
                                ? "_module_." + simpleName
                                : adjustedClassChain + "." + simpleName;
                        return CodeUnit.field(file, packageName, finalShortName);
                    }
                }
                // Fields require "Container.field" format; use _module_. when no class container
                finalShortName =
                        adjustedClassChain.isEmpty() ? "_module_." + simpleName : adjustedClassChain + "." + simpleName;
                return CodeUnit.field(file, packageName, finalShortName);
            }
            case ALIAS_LIKE -> {
                // Type aliases are fields and require "Container.alias" format; use _module_. when no class container
                finalShortName =
                        adjustedClassChain.isEmpty() ? "_module_." + simpleName : adjustedClassChain + "." + simpleName;
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
        SkeletonType skeletonType = getSkeletonTypeForCapture(captureName);
        return createCodeUnit(file, captureName, simpleName, packageName, classChain, null, skeletonType);
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
    protected String buildParentFqName(CodeUnit cu, String classChain) {
        String packageName = cu.packageName();

        if (!packageName.isBlank() && !classChain.isBlank()) {
            if (classChain.equals(packageName)) {
                return packageName;
            } else if (classChain.startsWith(packageName)
                    && classChain.length() > packageName.length()
                    && classChain.charAt(packageName.length()) == '.') {
                String remainingChain = classChain.substring(packageName.length() + 1);
                return remainingChain.isBlank() ? packageName : packageName + "." + remainingChain;
            }
        }

        return super.buildParentFqName(cu, classChain);
    }

    private Optional<String> extractNamespacePath(TSNode definitionNode, String src) {
        // Optimized: use ArrayDeque for O(1) prepend instead of ArrayList's O(n) addAll(0, ...)
        var namespaces = new java.util.ArrayDeque<String>();
        TSNode current = definitionNode.getParent();
        boolean insideClass = false;

        // Cache the class-like node types Set to avoid repeated getter calls
        var classLikeTypes = getLanguageSyntaxProfile().classLikeNodeTypes();

        while (current != null && !current.isNull()) {
            String nodeType = current.getType();

            if (classLikeTypes.contains(nodeType) && !"internal_module".equals(nodeType)) {
                insideClass = true;
                break;
            }

            if ("internal_module".equals(nodeType)) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
                    String name = cachedTextSliceStripped(nameNode, src);
                    // Manual dot-splitting instead of Splitter (faster, less overhead)
                    // Handles dotted namespace names: "A.B.C" -> ["A", "B", "C"]
                    // Parse dot-separated parts in order, then prepend entire list to deque
                    var parts = new java.util.ArrayList<String>();
                    int start = 0;
                    int dotIndex;
                    while ((dotIndex = name.indexOf('.', start)) >= 0) {
                        if (dotIndex > start) { // Skip empty segments (matches omitEmptyStrings)
                            parts.add(name.substring(start, dotIndex));
                        }
                        start = dotIndex + 1;
                    }
                    // Add last segment
                    if (start < name.length()) {
                        parts.add(name.substring(start));
                    }
                    // Prepend all parts in reverse order to maintain correct namespace hierarchy
                    for (int i = parts.size() - 1; i >= 0; i--) {
                        namespaces.addFirst(parts.get(i));
                    }
                }
            }

            current = current.getParent();
        }

        if (insideClass || namespaces.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(String.join(".", namespaces));
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        var namespacePath = extractNamespacePath(definitionNode, src);
        if (namespacePath.isPresent()) {
            return namespacePath.get();
        }

        var projectRoot = getProject().getRoot();
        var filePath = file.absPath();
        var parentDir = filePath.getParent();

        if (parentDir == null || parentDir.equals(projectRoot)) {
            return "";
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
        TSNode bodyNode =
                funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        boolean hasBody = bodyNode != null && !bodyNode.isNull() && bodyNode.getEndByte() > bodyNode.getStartByte();

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

        if (hasBody) {
            String signature = textSlice(funcNode.getStartByte(), bodyNode.getStartByte(), src)
                    .strip();

            String prefix = exportAndModifierPrefix.stripTrailing();
            if (!prefix.isEmpty() && !signature.startsWith(prefix)) {
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
        // With keyword captures now in the query, this method is only called as a fallback
        // for cases not covered by query patterns (e.g., class/interface member modifiers).
        // The query handles export, default, declare, async, abstract, const, let, var at the top level.
        StringBuilder modifiers = new StringBuilder();
        TSNode nodeToCheck = node;

        // Check if the node or its unwrapped form is a variable declarator, check the parent declaration (lexical/var)
        TSNode parentDecl = nodeToCheck.getParent();
        if ("variable_declarator".equals(nodeToCheck.getType())
                && parentDecl != null
                && ("lexical_declaration".equals(parentDecl.getType())
                        || "variable_declaration".equals(parentDecl.getType()))) {
            nodeToCheck = parentDecl;
        }

        // Look for modifier keywords in the first few children (needed for class/interface members)
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
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (!child.isNull()) {
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
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (!child.isNull()) {
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
        // - call_signature: intentionally has no name, uses default "[call]"
        if (CaptureNames.FUNCTION_DEFINITION.equals(captureName)
                && ("function_declaration".equals(nodeType)
                        || "construct_signature".equals(nodeType)
                        || "call_signature".equals(nodeType))) {
            return true;
        }
        // - index_signature: intentionally has no name, uses default "[index]"
        if (CaptureNames.VALUE_DEFINITION.equals(captureName) && "index_signature".equals(nodeType)) {
            return true;
        }
        return false;
    }

    @Override
    protected boolean shouldSkipNode(TSNode node, String captureName, byte[] srcBytes) {
        // Skip method_definition nodes inside object literals to prevent duplicate FQNames.
        // Example: class field "shellIntegration" vs getter in object literal value.
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
        // TypeScript declaration merging: function overloads, interface merging,
        // function+namespace merging, enum+namespace merging, field+getter pattern.

        // Function overloads are benign
        if (existing.isFunction() && candidate.isFunction()) {
            return true;
        }

        // Class-like entities (interfaces, classes, etc.) can merge through declaration merging
        if (existing.isClass() && candidate.isClass()) {
            return true;
        }

        // Function + Namespace merging (common pattern in TypeScript)
        // Example: function foo() {} and namespace foo { export const x = 1; }
        if ((existing.isFunction() && candidate.isClass()) || (existing.isClass() && candidate.isFunction())) {
            return true;
        }

        // Field + Getter/Setter pattern (common TypeScript encapsulation pattern)
        // Example: private id = 0; and get id() { return id; }
        // Both are captured as field-like entities with same FQN but different signatures
        // Note: We can't use signaturesOf() here because analyzer state isn't fully built yet
        // during duplicate detection. Since these patterns are benign, we allow the duplicate
        // and let base class add both signatures.
        if (existing.isField() && candidate.isField()) {
            log.trace(
                    "TypeScript field+field duplicate detected for {} (existing: {}, candidate: {}). Treating as benign.",
                    existing.fqName(),
                    existing.kind(),
                    candidate.kind());
            return true;
        }

        // Log when field check fails to help debug
        if ((existing.isField() || candidate.isField()) && existing.fqName().equals(candidate.fqName())) {
            log.debug(
                    "TypeScript duplicate {} with at least one field: existing.isField()={} (kind={}), candidate.isField()={} (kind={})",
                    existing.fqName(),
                    existing.isField(),
                    existing.kind(),
                    candidate.isField(),
                    candidate.kind());
        }

        // Note: TypeScript enums are mapped to CLASS type via classLikeNodeTypes in the syntax profile.
        // They are already covered by the class/function merging check above.
        // The enum check that was here was dead code since CodeUnitType enum has no "enum" value.

        return false;
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

        if (skeleton == null) {
            return Optional.empty();
        }
        return Optional.of(skeleton);
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

        // Apply minimal cosmetic cleanup only
        // Note: TypeScript duplicates (field+getter, overloads) are intentional and handled by base class
        var cleaned = new HashMap<CodeUnit, String>(skeletons.size());

        for (var entry : skeletons.entrySet()) {
            CodeUnit cu = entry.getKey();
            String skeleton = entry.getValue();

            // Basic cleanup: remove trailing commas in enums
            skeleton = ENUM_COMMA_CLEANUP.matcher(skeleton).replaceAll("\n$1");

            // Remove semicolons from type alias lines (cosmetic only)
            if (skeleton.contains("type ")) {
                String[] lines = skeleton.split("\n", -1);
                var skeletonBuilder = new StringBuilder(skeleton.length());
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (TYPE_ALIAS_LINE.matcher(line).find()) {
                        line = TRAILING_SEMICOLON.matcher(line).replaceAll("");
                    }
                    skeletonBuilder.append(line);
                    if (i < lines.length - 1) {
                        skeletonBuilder.append('\n');
                    }
                }
                skeleton = skeletonBuilder.toString();
            }

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
        // Provide default names for special TypeScript constructs that don't have explicit names
        String nodeType = decl.getType();
        if ("construct_signature".equals(nodeType)) {
            return Optional.of("new");
        }
        if ("index_signature".equals(nodeType)) {
            return Optional.of("[index]");
        }
        if ("call_signature".equals(nodeType)) {
            return Optional.of("[call]");
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

    @Override
    protected @Nullable String extractSignature(String captureName, TSNode definitionNode, String src) {
        // TypeScript uses signature merging for overloads (shouldMergeSignaturesForSameFqn = true).
        // We should NOT set the signature field on individual CodeUnits because it makes them unequal.
        // Instead, signature information is extracted during skeleton building and stored in
        // CodeUnitProperties.signatures list.
        // Return null to avoid setting CodeUnit.signature field.
        return null;
    }
}
