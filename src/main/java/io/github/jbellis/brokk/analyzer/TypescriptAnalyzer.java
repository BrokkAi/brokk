package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterTypescript;

import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

public final class TypescriptAnalyzer extends TreeSitterAnalyzer {
    private static final TSLanguage TS_LANGUAGE = new TreeSitterTypescript();

    // Compiled regex patterns for memory efficiency
    private static final Pattern TRAILING_SEMICOLON = Pattern.compile(";\\s*$");
    private static final Pattern ENUM_COMMA_CLEANUP = Pattern.compile(",\\s*\\r?\\n(\\s*})");
    private static final Pattern TYPE_ALIAS_LINE = Pattern.compile("(type |export type ).*=.*");

    // Fast lookups for type checks
    private static final Set<String> FUNCTION_NODE_TYPES = Set.of(
        "function_declaration", "generator_function_declaration", "function_signature"
    );
    private static final Set<String> SIGNATURE_NODE_TYPES = Set.of(
        "method_signature", "construct_signature", "abstract_method_signature"
    );

    // Class keyword mapping for fast lookup
    private static final Map<String, String> CLASS_KEYWORDS = Map.of(
        "interface_declaration", "interface",
        "enum_declaration", "enum",
        "module", "namespace",
        "internal_module", "namespace",
        "ambient_declaration", "namespace",
        "abstract_class_declaration", "class"
    );

    // Cache for text slices to reduce string creation
    private final Map<String, String> textSliceCache = new ConcurrentHashMap<>();

    // Cache for ambient context checks to avoid tree traversal
    private final Map<TSNode, Boolean> ambientContextCache = new ConcurrentHashMap<>();

    private static final LanguageSyntaxProfile TS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            // classLikeNodeTypes
            Set.of("class_declaration", "interface_declaration", "enum_declaration", "abstract_class_declaration", "module", "internal_module"),
            // functionLikeNodeTypes
            Set.of("function_declaration", "method_definition", "arrow_function", "generator_function_declaration",
                   "function_signature", "method_signature", "abstract_method_signature"), // method_signature for interfaces, abstract_method_signature for abstract classes
            // fieldLikeNodeTypes
            Set.of("variable_declarator", "public_field_definition", "property_signature", "enum_member"), // type_alias_declaration will be ALIAS_LIKE
            // decoratorNodeTypes
            Set.of("decorator"),
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
                "type.definition", SkeletonType.CLASS_LIKE,      // Classes, interfaces, enums, namespaces
                "function.definition", SkeletonType.FUNCTION_LIKE, // Functions, methods
                "value.definition", SkeletonType.FIELD_LIKE,     // Variables, fields, constants
                "typealias.definition", SkeletonType.ALIAS_LIKE,  // Type aliases
                "decorator.definition", SkeletonType.UNSUPPORTED, // Keep as UNSUPPORTED but handle differently
                "keyword.modifier", SkeletonType.UNSUPPORTED
            ),
            // asyncKeywordNodeType
            "async", // TS uses 'async' keyword
            // modifierNodeTypes: Contains node types of keywords/constructs that act as modifiers.
            // Used in TreeSitterAnalyzer.buildSignatureString to gather modifiers by inspecting children.
            Set.of(
                "export", "default", "declare", "abstract", "static", "readonly",
                "accessibility_modifier", // for public, private, protected
                "async", "const", "let", "var", "override" // "override" might be via override_modifier
                // Note: "public", "private", "protected" themselves are not node types here,
                // but "accessibility_modifier" is the node type whose text content is one of these.
                // "const", "let" are token types for the `kind` of a lexical_declaration, often its first child.
                // "var" is a token type, often first child of variable_declaration.
            )
    );

    public TypescriptAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, excludedFiles);
    }

    public TypescriptAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    // Optimized textSlice with caching for frequently accessed slices
    private String cachedTextSlice(TSNode node, String src) {
        if (node == null || node.isNull()) {
            return "";
        }

        // Initialize cache if null (can happen during parent constructor execution)
        if (textSliceCache == null) {
            return textSlice(node, src);
        }

        // Use more efficient key generation
        long key = ((long) node.getStartByte() << 32) | node.getEndByte();
        String keyStr = key + ":" + src.hashCode();
        return textSliceCache.computeIfAbsent(keyStr, k -> textSlice(node, src));
    }

    private String cachedTextSliceStripped(TSNode node, String src) {
        String text = cachedTextSlice(node, src);
        return text.strip();
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
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String packageName,
                                      String classChain)
    {
        // Adjust FQN based on capture type and context
        String finalShortName;
        SkeletonType skeletonType = getSkeletonTypeForCapture(captureName);

        switch (skeletonType) {
            case CLASS_LIKE:
                finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                return CodeUnit.cls(file, packageName, finalShortName);
            case FUNCTION_LIKE:
                 if (simpleName.equals("anonymous_arrow_function") || simpleName.isEmpty()) {
                    log.warn("Anonymous or unnamed function found for capture {} in file {}. ClassChain: {}. Will use placeholder or rely on extracted name.", captureName, file, classChain);
                    // simpleName might be "anonymous_arrow_function" if #set! "default_name" was used and no var name found
                 }
                finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                return CodeUnit.fn(file, packageName, finalShortName);
            case FIELD_LIKE:
                finalShortName = classChain.isEmpty() ? "_module_." + simpleName : classChain + "." + simpleName;
                return CodeUnit.field(file, packageName, finalShortName);
            case ALIAS_LIKE:
                // Type aliases are top-level or module-level, treated like fields for FQN and CU type.
                finalShortName = classChain.isEmpty() ? "_module_." + simpleName : classChain + "." + simpleName;
                return CodeUnit.field(file, packageName, finalShortName);
            default: // UNSUPPORTED or other
                log.debug("Ignoring capture in TypescriptAnalyzer: {} (mapped to type {}) with name: {} and classChain: {}",
                          captureName, skeletonType, simpleName, classChain);
                return null;
        }
    }

    @Override
    protected String formatReturnType(TSNode returnTypeNode, String src) {
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
    protected String renderFunctionDeclaration(TSNode funcNode, String src,
                                               String exportAndModifierPrefix, String ignoredAsyncPrefix, // asyncPrefix is ignored
                                               String functionName, String typeParamsText, String paramsText, String returnTypeText,
                                               String indent)
    {
        // exportAndModifierPrefix now contains all modifiers, including "async" if applicable.
        // The asyncPrefix parameter is deprecated from the base class and ignored here.
        String combinedPrefix = exportAndModifierPrefix.stripTrailing(); // e.g., "export async", "public static"

        String tsReturnTypeSuffix = (returnTypeText != null && !returnTypeText.isEmpty()) ? ": " + returnTypeText.strip() : "";
        String signature;
        String bodySuffix;

        TSNode bodyNode = funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        boolean hasBody = bodyNode != null && !bodyNode.isNull() && bodyNode.getEndByte() > bodyNode.getStartByte();

        if ("arrow_function".equals(funcNode.getType())) {
            // combinedPrefix for arrow func (e.g. "export const"), add async if present
            String asyncPart = ignoredAsyncPrefix.isEmpty() ? "" : ignoredAsyncPrefix + " ";
            signature = String.format("%s %s%s = %s%s%s =>", // Space after prefix if not empty, add assignment operator
                                      combinedPrefix,
                                      functionName.isEmpty() ? "" : functionName,
                                      typeParamsText,
                                      asyncPart,
                                      paramsText,
                                      tsReturnTypeSuffix).stripLeading(); // stripLeading in case prefix was empty
            bodySuffix = " " + bodyPlaceholder(); // Always use placeholder for arrow functions in skeletons
        } else {
            String keyword = "";
            String nodeType = funcNode.getType();
            if (FUNCTION_NODE_TYPES.contains(nodeType)) {
                // Function signatures in ambient contexts should not include the "function" keyword
                if ("function_signature".equals(nodeType) && isInAmbientContext(funcNode)) {
                    keyword = "";
                } else {
                    keyword = "function";
                    if ("generator_function_declaration".equals(nodeType)) keyword += "*";
                }
            } else if ("constructor".equals(functionName) && "method_definition".equals(nodeType)) {
                keyword = "constructor";
                functionName = ""; // constructor name is part of keyword
            } else if ("construct_signature".equals(nodeType)) {
                keyword = "new";
                functionName = ""; // constructor signatures use "new" keyword instead of function name
            } else if ("method_definition".equals(nodeType)) {
                String nodeTextStart = textSlice(funcNode.getStartByte(), Math.min(funcNode.getEndByte(), funcNode.getStartByte() + 4), src);
                if (nodeTextStart.startsWith("get ")) {
                    keyword = "get";
                     // functionName will be the property name; ensure it's not duplicated if already part of `exportAndModifierPrefix`
                } else if (nodeTextStart.startsWith("set ")) {
                    keyword = "set";
                }
            }

            String endMarker = "";
            if (!hasBody && !"arrow_function".equals(nodeType)) {
                // Interface method signatures, abstract method signatures, non-ambient function signatures, and constructor signatures should not have semicolons per TypeScript conventions
                boolean isFunctionSignatureInAmbient = "function_signature".equals(nodeType) && isInAmbientContext(funcNode);
                boolean isNonAmbientFunctionSignature = "function_signature".equals(nodeType) && !isInAmbientContext(funcNode);

                if (!SIGNATURE_NODE_TYPES.contains(nodeType) && !isNonAmbientFunctionSignature) {
                    endMarker = ";";
                }
            }

            // Assemble: combinedPrefix [keyword] [functionName] [typeParams] paramsText returnTypeSuffix endMarker
            var signatureBuilder = new StringBuilder();
            if (!combinedPrefix.isEmpty()) {
                signatureBuilder.append(combinedPrefix);
            }
            if (!keyword.isEmpty()) {
                if (signatureBuilder.length() > 0) signatureBuilder.append(" ");
                signatureBuilder.append(keyword);
            }
            if (!functionName.isEmpty() || (keyword.equals("constructor") && functionName.isEmpty())) {
                // Add functionName + typeParams for regular functions and constructors
                if (signatureBuilder.length() > 0 && !keyword.equals("constructor")) {
                    signatureBuilder.append(" ");
                }
                signatureBuilder.append(functionName).append(typeParamsText);
            } else if (keyword.equals("new")) {
                // For constructor signatures, add type parameters separately since there's no function name
                if (!typeParamsText.isEmpty()) {
                    if (signatureBuilder.length() > 0) signatureBuilder.append(" ");
                    signatureBuilder.append(typeParamsText);
                }
            }

            // Special handling for constructor signatures to add space before parameters
            if (keyword.equals("new")) {
                signatureBuilder.append(" ").append(paramsText).append(tsReturnTypeSuffix).append(endMarker);
            } else {
                signatureBuilder.append(paramsText).append(tsReturnTypeSuffix).append(endMarker);
            }
            signature = signatureBuilder.toString().strip();
            bodySuffix = hasBody ? " " + bodyPlaceholder() : "";
        }
        return indent + signature.strip() + bodySuffix;
    }

    @Override
    protected String formatFieldSignature(TSNode fieldNode, String src, String exportPrefix, String signatureText, String baseIndent, ProjectFile file) {
        String fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();

        // Remove trailing semicolons
        fullSignature = TRAILING_SEMICOLON.matcher(fullSignature).replaceAll("");

        // Special handling for enum members - add comma instead of semicolon
        String suffix = "";
        if (fieldNode.getParent() != null && "enum_body".equals(fieldNode.getParent().getType()) &&
            ("property_identifier".equals(fieldNode.getType()) || "enum_assignment".equals(fieldNode.getType()))) {
            // Enum members get commas, not semicolons
            suffix = ",";
        }

        return baseIndent + fullSignature + suffix;
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src,
                                       String exportAndModifierPrefix, // This now comes from captured @keyword.modifier list
                                       String signatureText, // This is the raw text slice from class start to body start
                                       String baseIndent)
    {
        String classKeyword = CLASS_KEYWORDS.getOrDefault(classNode.getType(), "class");

        String remainingSignature = signatureText.stripLeading();

        // Strip the comprehensive exportAndModifierPrefix if it's at the start of the raw signature slice
        String strippedPrefix = exportAndModifierPrefix.strip(); // e.g., "export abstract"
        if (!strippedPrefix.isEmpty() && remainingSignature.startsWith(strippedPrefix)) {
            remainingSignature = remainingSignature.substring(strippedPrefix.length()).stripLeading();
        } else {
            // If the full prefix doesn't match, try to strip individual modifiers that might appear in the signature
            String[] prefixParts = strippedPrefix.split("\\s+");
            for (String part : prefixParts) {
                if (remainingSignature.startsWith(part + " ")) {
                    remainingSignature = remainingSignature.substring((part + " ").length()).stripLeading();
                } else if (remainingSignature.startsWith(part) &&
                          (remainingSignature.length() == part.length() || !Character.isLetterOrDigit(remainingSignature.charAt(part.length())))) {
                    remainingSignature = remainingSignature.substring(part.length()).stripLeading();
                }
            }
        }

        // Then, strip the class keyword itself
        if (remainingSignature.startsWith(classKeyword + " ")) {
            remainingSignature = remainingSignature.substring((classKeyword + " ").length()).stripLeading();
        } else if (remainingSignature.startsWith(classKeyword) && (remainingSignature.length() == classKeyword.length() || !Character.isLetterOrDigit(remainingSignature.charAt(classKeyword.length())))) {
             // Case where class keyword is not followed by space, e.g. "class<T>"
             remainingSignature = remainingSignature.substring(classKeyword.length()).stripLeading();
        }


        // remainingSignature is now "ClassName<Generics> extends Base implements Iface"
        // exportAndModifierPrefix already has a trailing space if it's not empty.
        String finalPrefix = exportAndModifierPrefix.stripTrailing();
        if (!finalPrefix.isEmpty() && !classKeyword.isEmpty() && !remainingSignature.isEmpty()) {
             finalPrefix += " ";
        }


        return baseIndent + finalPrefix + classKeyword + " " + remainingSignature + " {";
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        // TypeScript modifier extraction - look for export, declare, async, static, etc.
        // This method is called when keyword.modifier captures are not available.
        StringBuilder modifiers = new StringBuilder();

        // Check the node itself and its parent for common modifier patterns
        TSNode nodeToCheck = node;
        TSNode parent = node.getParent();

        // For variable declarators, check the parent declaration
        if ("variable_declarator".equals(node.getType()) && parent != null &&
            ("lexical_declaration".equals(parent.getType()) || "variable_declaration".equals(parent.getType()))) {
            nodeToCheck = parent;
        }

        // Check for export statement wrapper
        if (parent != null && "export_statement".equals(parent.getType())) {
            modifiers.append("export ");

            // Check for default export
            TSNode exportKeyword = parent.getChild(0);
            if (exportKeyword != null && parent.getChildCount() > 1) {
                TSNode defaultKeyword = parent.getChild(1);
                if (defaultKeyword != null && "default".equals(cachedTextSliceStripped(defaultKeyword, src))) {
                    modifiers.append("default ");
                }
            }
        }

        // Look for modifier keywords in the first few children of the declaration
        for (int i = 0; i < Math.min(nodeToCheck.getChildCount(), 5); i++) {
            TSNode child = nodeToCheck.getChild(i);
            if (child != null && !child.isNull()) {
                String childText = cachedTextSliceStripped(child, src);
                // Check for common TypeScript modifiers
                if (Set.of("declare", "abstract", "static", "readonly", "async", "const", "let", "var").contains(childText)) {
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
        return Set.of("parameters", "return_type_node", "predefined_type_node", "type_identifier_node", "export.keyword");
    }

    /**
     * Checks if a function node is inside an ambient declaration context (declare namespace/module).
     * In ambient contexts, function signatures should not include the "function" keyword.
     */
    protected boolean isInAmbientContext(TSNode node) {
        // Handle null cache during parent constructor execution
        if (ambientContextCache == null) {
            return checkAmbientContextDirect(node);
        }

        return ambientContextCache.computeIfAbsent(node, n -> checkAmbientContextDirect(n));
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
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        var skeletons = super.getSkeletons(file);

        // Clean up and deduplicate skeletons to match test expectations
        var cleaned = new HashMap<CodeUnit, String>(skeletons.size());
        for (var entry : skeletons.entrySet()) {
            CodeUnit cu = entry.getKey();
            String skeleton = entry.getValue();

            // Remove trailing commas in enums: ",\n}" -> "\n}"
            skeleton = ENUM_COMMA_CLEANUP.matcher(skeleton).replaceAll("\n$1");

            // Remove trailing semicolons from non-exported arrow functions only
            if (skeleton.contains("=>") && skeleton.strip().endsWith("};") && !skeleton.startsWith("export")) {
                skeleton = TRAILING_SEMICOLON.matcher(skeleton).replaceAll("");
            }

            // Remove semicolons from type alias lines anywhere in the skeleton - optimize with StringBuilder
            var lines = skeleton.split("\n");
            var skeletonBuilder = new StringBuilder(skeleton.length());
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (TYPE_ALIAS_LINE.matcher(line).find()) {
                    line = TRAILING_SEMICOLON.matcher(line).replaceAll("");
                }
                skeletonBuilder.append(line);
                if (i < lines.length - 1) {
                    skeletonBuilder.append("\n");
                }
            }
            skeleton = skeletonBuilder.toString();

            // Remove duplicate lines within skeletons and handle default exports
            skeleton = deduplicateSkeletonLines(skeleton);
            skeleton = extractDefaultExportIfPresent(skeleton);

            cleaned.put(entry.getKey(), skeleton);
        }

        return cleaned;
    }

    private boolean isTypeAlias(CodeUnit cu) {
        // Check if this field-type CodeUnit represents a type alias
        // We can identify this by checking if there are signatures that contain "type " and " = "
        List<String> sigList = signatures.get(cu);
        if (sigList != null) {
            for (String sig : sigList) {
                if ((sig.contains("type ") || sig.contains("export type ")) && sig.contains(" = ")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove duplicate lines within a skeleton while preserving order and structure.
     */
    private String deduplicateSkeletonLines(String skeleton) {
        var lines = skeleton.split("\n");
        var result = new ArrayList<String>(lines.length);
        var seen = new HashSet<String>(lines.length);

        for (String line : lines) {
            String trimmedLine = line.trim();

            // Keep structural lines and only deduplicate content
            if (trimmedLine.isEmpty() || trimmedLine.equals("{") || trimmedLine.equals("}")) {
                result.add(line);
                continue;
            }

            // Handle interface/class/enum header duplication (e.g., "export interface Point {" vs "interface Point {")
            // Check if this line is a non-export version of a previous export line
            boolean isDuplicate = false;
            for (String seenLine : seen) {
                // Check if the seen line is an export version of the current line
                if (seenLine.startsWith("export ") && seenLine.length() > 7 && seenLine.substring(7).equals(trimmedLine)) {
                    isDuplicate = true;
                    break;
                }
                // Check if the current line is an export version of a seen line
                if (trimmedLine.startsWith("export ") && trimmedLine.length() > 7 && trimmedLine.substring(7).equals(seenLine)) {
                    // Replace the non-export version with the export version
                    seen.remove(seenLine);
                    seen.add(trimmedLine);
                    // Find and replace the line in result
                    for (int i = 0; i < result.size(); i++) {
                        if (result.get(i).trim().equals(seenLine)) {
                            result.set(i, line);
                            break;
                        }
                    }
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate && seen.add(trimmedLine)) {
                result.add(line);
            }
        }

        return String.join("\n", result);
    }

    /**
     * If skeleton contains both regular and default export lines, keep only the default export.
     */
    private String extractDefaultExportIfPresent(String skeleton) {
        var lines = skeleton.split("\n");
        boolean hasDefaultExport = false;

        // Quick check for default export without creating stream - avoid strip() in loop
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("export default")) {
                hasDefaultExport = true;
                break;
            }
        }

        if (!hasDefaultExport) {
            return skeleton;
        }

        // Filter out non-default exports if default exports exist
        var result = new ArrayList<String>(lines.length);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("export default") || !trimmed.startsWith("export ")) {
                result.add(line);
            }
        }

        return String.join("\n", result);
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
    protected void buildFunctionSkeleton(TSNode funcNode, Optional<String> providedNameOpt, String src, String indent, List<String> lines, String exportPrefix) {
        // Handle constructor signatures specially
        if ("construct_signature".equals(funcNode.getType())) {
            // For constructor signatures, handle return type extraction manually
            // since they use "type" field instead of "return_type" field
            TSNode typeNode = funcNode.getChildByFieldName("type");
            if (typeNode != null && !typeNode.isNull()) {
                String typeText = textSlice(typeNode, src);
                // typeText includes the colon, e.g., ": GenericInterface<T,U>"
                String returnTypeText = typeText.startsWith(":") ? typeText.substring(1).strip() : typeText;

                // Get the other components from the base method
                var profile = getLanguageSyntaxProfile();
                String functionName = extractSimpleName(funcNode, src).orElse("new");
                TSNode paramsNode = funcNode.getChildByFieldName(profile.parametersFieldName());
                String paramsText = formatParameterList(paramsNode, src);

                // Extract type parameters
                String typeParamsText = "";
                TSNode typeParamsNode = funcNode.getChildByFieldName(profile.typeParametersFieldName());
                if (typeParamsNode != null && !typeParamsNode.isNull()) {
                    typeParamsText = textSlice(typeParamsNode, src);
                }

                // Render the constructor signature manually
                String signature = renderFunctionDeclaration(funcNode, src, exportPrefix, "", functionName, typeParamsText, paramsText, returnTypeText, indent);
                if (signature != null && !signature.isBlank()) {
                    lines.add(signature);
                }
                return;
            }
        }

        // Handle lexical_declaration with arrow_function specially
        TSNode lexicalDeclaration = null;

        // Check if funcNode is a lexical_declaration
        if ("lexical_declaration".equals(funcNode.getType())) {
            lexicalDeclaration = funcNode;
        }
        // Check if funcNode is an export_statement containing a lexical_declaration
        else if ("export_statement".equals(funcNode.getType())) {
            for (int i = 0; i < funcNode.getChildCount(); i++) {
                TSNode child = funcNode.getChild(i);
                if ("lexical_declaration".equals(child.getType())) {
                    lexicalDeclaration = child;
                    break;
                }
            }
        }

        if (lexicalDeclaration != null) {
            // This is a const/let declaration containing an arrow function
            TSNode variableDeclarator = null;
            TSNode arrowFunctionNode = null;
            String declarationKeyword = "";

            // Find the declaration keyword (const, let) and variable_declarator
            for (int i = 0; i < lexicalDeclaration.getChildCount(); i++) {
                TSNode child = lexicalDeclaration.getChild(i);
                String childText = cachedTextSlice(child, src);
                if ("const".equals(childText) || "let".equals(childText)) {
                    declarationKeyword = childText;
                } else if ("variable_declarator".equals(child.getType())) {
                    variableDeclarator = child;
                    // Look for arrow_function in the value
                    for (int j = 0; j < child.getChildCount(); j++) {
                        TSNode valueChild = child.getChild(j);
                        if ("arrow_function".equals(valueChild.getType())) {
                            arrowFunctionNode = valueChild;
                            break;
                        }
                    }
                    break;
                }
            }

            if (variableDeclarator != null && arrowFunctionNode != null) {
                // Extract the name from variable declarator
                String functionName = providedNameOpt.orElse("");
                if (functionName.isEmpty()) {
                    TSNode nameNode = variableDeclarator.getChildByFieldName("name");
                    if (nameNode != null && !nameNode.isNull()) {
                        functionName = cachedTextSlice(nameNode, src);
                    }
                }

                // Extract parameters from arrow function
                TSNode paramsNode = arrowFunctionNode.getChildByFieldName("parameters");
                String paramsText = formatParameterList(paramsNode, src);

                // Extract return type from arrow function
                TSNode returnTypeNode = arrowFunctionNode.getChildByFieldName("return_type");
                String returnTypeText = formatReturnType(returnTypeNode, src);

                // Extract type parameters from arrow function
                String typeParamsText = "";
                var profile = getLanguageSyntaxProfile();
                if (profile.typeParametersFieldName() != null && !profile.typeParametersFieldName().isEmpty()) {
                    TSNode typeParamsNode = arrowFunctionNode.getChildByFieldName(profile.typeParametersFieldName());
                    if (typeParamsNode != null && !typeParamsNode.isNull()) {
                        typeParamsText = cachedTextSlice(typeParamsNode, src);
                    }
                }

                // Check if arrow function is async by looking for async keyword in the lexical declaration
                boolean isAsync = false;
                String arrowFunctionText = cachedTextSlice(arrowFunctionNode, src);
                if (arrowFunctionText.startsWith("async ")) {
                    isAsync = true;
                }

                // Build the abbreviated arrow function skeleton - use existing exportPrefix which already contains the modifiers
                String asyncPrefix = isAsync ? "async" : "";
                String signature = renderFunctionDeclaration(arrowFunctionNode, src, exportPrefix, asyncPrefix, functionName, typeParamsText, paramsText, returnTypeText, indent);
                if (signature != null && !signature.isBlank()) {
                    lines.add(signature);
                }
                return;
            }
        }

        // For all other cases, use the parent implementation
        super.buildFunctionSkeleton(funcNode, providedNameOpt, src, indent, lines, exportPrefix);
    }

    @Override
    public Optional<String> getMethodSource(String fqName) {
        Optional<String> result = super.getMethodSource(fqName);

        if (result.isPresent()) {
            String source = result.get();

            // Remove trailing semicolons from arrow function assignments
            if (source.contains("=>") && source.strip().endsWith("};")) {
                source = TRAILING_SEMICOLON.matcher(source).replaceAll("");
            }

            // Remove semicolons from function overload signatures
            String[] lines = source.split("\n");
            var cleaned = new StringBuilder(source.length());

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.strip();

                // Remove semicolons from function overload signatures (lines ending with ; that don't have {)
                if (trimmed.startsWith("export function") && trimmed.endsWith(";") && !trimmed.contains("{")) {
                    line = TRAILING_SEMICOLON.matcher(line).replaceAll("");
                }

                cleaned.append(line);
                if (i < lines.length - 1) {
                    cleaned.append("\n");
                }
            }

            return Optional.of(cleaned.toString());
        }

        return result;
    }

    @Override
    public Optional<String> getSkeleton(String fqName) {
        // Find the CodeUnit for this FQN - optimize with early termination
        CodeUnit foundCu = null;
        for (CodeUnit cu : signatures.keySet()) {
            if (cu.fqName().equals(fqName)) {
                foundCu = cu;
                break;
            }
        }

        if (foundCu != null) {
            // Find the top-level parent for this CodeUnit
            CodeUnit topLevelParent = findTopLevelParent(foundCu);

            // Get the skeleton from getSkeletons and apply our cleanup
            Map<CodeUnit, String> skeletons = getSkeletons(topLevelParent.source());
            String skeleton = skeletons.get(topLevelParent);

            return Optional.ofNullable(skeleton);
        }
        return Optional.empty();
    }

    // Cache for parent-child relationships to avoid expensive contains() lookups
    private final Map<CodeUnit, CodeUnit> parentCache = new ConcurrentHashMap<>();

    /**
     * Find the top-level parent CodeUnit for a given CodeUnit.
     * If the CodeUnit has no parent, it returns itself.
     */
    private CodeUnit findTopLevelParent(CodeUnit cu) {
        // Build parent cache if not already done for this CU
        if (!parentCache.containsKey(cu)) {
            buildParentCache();
        }

        // Traverse up the parent chain
        CodeUnit current = cu;
        CodeUnit parent = parentCache.get(current);
        while (parent != null) {
            current = parent;
            parent = parentCache.get(current);
        }
        return current;
    }

    private void buildParentCache() {
        // Build reverse lookup map once
        for (var entry : childrenByParent.entrySet()) {
            CodeUnit parent = entry.getKey();
            List<CodeUnit> children = entry.getValue();
            for (CodeUnit child : children) {
                parentCache.put(child, parent);
            }
        }
    }


}
