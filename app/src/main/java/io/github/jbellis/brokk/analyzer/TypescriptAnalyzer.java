package io.github.jbellis.brokk.analyzer;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.IProject;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterTypescript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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

    // Class keyword mapping for fast lookup
    private static final Map<String, String> CLASS_KEYWORDS = Map.of(
        "interface_declaration", "interface",
        "enum_declaration", "enum",
        "module", "namespace",
        "internal_module", "namespace",
        "ambient_declaration", "namespace",
        "abstract_class_declaration", "abstract class"
    );


    private static final LanguageSyntaxProfile TS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            // classLikeNodeTypes
            Set.of("class_declaration", "interface_declaration", "enum_declaration", "abstract_class_declaration", "module", "internal_module"),
            // functionLikeNodeTypes
            Set.of("function_declaration", "method_definition", "arrow_function", "generator_function_declaration",
                   "function_signature", "method_signature", "abstract_method_signature"), // function_signature for overloads, method_signature for interfaces, abstract_method_signature for abstract classes
            // fieldLikeNodeTypes
            Set.of("variable_declarator", "public_field_definition", "property_signature", "enum_member", "lexical_declaration", "variable_declaration"), // type_alias_declaration will be ALIAS_LIKE
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
        super(project, Language.TYPESCRIPT, excludedFiles);
    }

    public TypescriptAnalyzer(IProject project) {
        this(project, Collections.emptySet());
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
            case CLASS_LIKE -> {
                finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                return CodeUnit.cls(file, packageName, finalShortName);
            }
            case FUNCTION_LIKE -> {
                if (simpleName.equals("anonymous_arrow_function") || simpleName.isEmpty()) {
                    log.warn("Anonymous or unnamed function found for capture {} in file {}. ClassChain: {}. Will use placeholder or rely on extracted name.", captureName, file, classChain);
                    // simpleName might be "anonymous_arrow_function" if #set! "default_name" was used and no var name found
                }
                finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                return CodeUnit.fn(file, packageName, finalShortName);
            }
            case FIELD_LIKE -> {
                finalShortName = classChain.isEmpty() ? "_module_." + simpleName : classChain + "." + simpleName;
                return CodeUnit.field(file, packageName, finalShortName);
            }
            case ALIAS_LIKE -> {
                // Type aliases are top-level or module-level, treated like fields for FQN and CU type.
                finalShortName = classChain.isEmpty() ? "_module_." + simpleName : classChain + "." + simpleName;
                return CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                log.debug("Ignoring capture in TypescriptAnalyzer: {} (mapped to type {}) with name: {} and classChain: {}",
                          captureName, skeletonType, simpleName, classChain);
                throw new UnsupportedOperationException("Unsupported skeleton type: " + skeletonType);
            }
        }
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
    protected String renderFunctionDeclaration(TSNode funcNode, String src,
                                               String exportAndModifierPrefix, String ignoredAsyncPrefix,
                                               String functionName, String typeParamsText, String paramsText, String returnTypeText,
                                               String indent)
    {
        // Use text slicing approach for simpler rendering
        TSNode bodyNode = funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        boolean hasBody = bodyNode != null && !bodyNode.isNull() && bodyNode.getEndByte() > bodyNode.getStartByte();

        // For arrow functions, handle specially
        if ("arrow_function".equals(funcNode.getType())) {
            String prefix = exportAndModifierPrefix.stripTrailing();
            String asyncPart = ignoredAsyncPrefix.isEmpty() ? "" : ignoredAsyncPrefix + " ";
            String returnTypeSuffix = !returnTypeText.isEmpty() ? ": " + returnTypeText.strip() : "";

            String signature = String.format("%s %s%s = %s%s%s =>",
                                              prefix,
                                              functionName,
                                              typeParamsText,
                                              asyncPart,
                                              paramsText,
                                              returnTypeSuffix).stripLeading();
            return indent + signature + " " + bodyPlaceholder();
        }

        // For regular functions, use text slicing when possible
        if (hasBody) {
            String signature = textSlice(funcNode.getStartByte(), bodyNode.getStartByte(), src).strip();

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
        boolean needsSpaceBeforeParams = "construct_signature".equals(funcNode.getType());
        
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
            if (prefix.contains("declare") || // ambient declarations need semicolons
                (isInNamespaceContext(funcNode) && !prefix.contains("declare"))) { // namespace functions need semicolons
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
    protected String formatFieldSignature(TSNode fieldNode, String src, String exportPrefix, String signatureText, String baseIndent, ProjectFile file) {
        String fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();

        // Remove trailing semicolons
        fullSignature = TRAILING_SEMICOLON.matcher(fullSignature).replaceAll("");

        // Special handling for enum members - add comma instead of semicolon
        String suffix = "";
        if (!fieldNode.isNull() && fieldNode.getParent() != null && !fieldNode.getParent().isNull() &&
            "enum_body".equals(fieldNode.getParent().getType()) &&
            ("property_identifier".equals(fieldNode.getType()) || "enum_assignment".equals(fieldNode.getType()))) {
            // Enum members get commas, not semicolons
            suffix = ",";
        }

        return baseIndent + fullSignature + suffix;
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src,
                                       String exportAndModifierPrefix,
                                       String signatureText,
                                       String baseIndent)
    {
        // Use text slicing approach but include export prefix
        TSNode bodyNode = classNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        if (bodyNode != null && !bodyNode.isNull()) {
            String signature = textSlice(classNode.getStartByte(), bodyNode.getStartByte(), src).strip();

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
    public boolean isInAmbientContext(TSNode node) {
        return checkAmbientContextDirect(node);
    }

    /**
     * Checks if a function node is inside a namespace/module context where function signatures
     * should not include the "function" keyword. This includes both regular namespaces and
     * functions inside ambient namespaces, but excludes top-level ambient function declarations.
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
                    } else if (foundExportInterface && trimmed.startsWith("interface ") && trimmed.endsWith(" {") &&
                               !trimmed.startsWith("export interface ")) {
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

    public boolean isTypeAlias(CodeUnit cu) {
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
        // Handle variable_declarator containing arrow function
        if ("variable_declarator".equals(funcNode.getType())) {
            TSNode valueNode = funcNode.getChildByFieldName("value");
            if (valueNode != null && !valueNode.isNull() && "arrow_function".equals(valueNode.getType())) {
                // Build the const/let declaration with arrow function
                String fullDeclaration = textSlice(funcNode, src).strip();
                
                // Replace function body with placeholder
                TSNode bodyNode = valueNode.getChildByFieldName("body");
                if (bodyNode != null && !bodyNode.isNull()) {
                    String beforeBody = textSlice(funcNode.getStartByte(), bodyNode.getStartByte(), src).strip();
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
                String returnTypeText = typeText.startsWith(":") ? typeText.substring(1).strip() : typeText;

                var profile = getLanguageSyntaxProfile();
                String functionName = extractSimpleName(funcNode, src).orElse("new");
                TSNode paramsNode = funcNode.getChildByFieldName(profile.parametersFieldName());
                String paramsText = formatParameterList(paramsNode, src);

                String typeParamsText = "";
                TSNode typeParamsNode = funcNode.getChildByFieldName(profile.typeParametersFieldName());
                if (typeParamsNode != null && !typeParamsNode.isNull()) {
                    typeParamsText = textSlice(typeParamsNode, src);
                }

                String signature = renderFunctionDeclaration(funcNode, src, exportPrefix, "", functionName, typeParamsText, paramsText, returnTypeText, indent);
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
    public Optional<String> getMethodSource(String fqName) {
        Optional<String> result = super.getMethodSource(fqName);

        if (result.isPresent()) {
            String source = result.get();

            // Remove trailing semicolons from arrow function assignments
            if (source.contains("=>") && source.strip().endsWith("};")) {
                source = TRAILING_SEMICOLON.matcher(source).replaceAll("");
            }

            // Remove semicolons from function overload signatures
            List<String> lines = Splitter.on('\n').splitToList(source);
            var cleaned = new StringBuilder(source.length());

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.strip();

                // Remove semicolons from function overload signatures (lines ending with ; that don't have {)
                if (trimmed.startsWith("export function") && trimmed.endsWith(";") && !trimmed.contains("{")) {
                    line = TRAILING_SEMICOLON.matcher(line).replaceAll("");
                }

                cleaned.append(line);
                if (i < lines.size() - 1) {
                    cleaned.append("\n");
                }
            }

            return Optional.of(cleaned.toString());
        }

        return result;
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterTypescript();
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

    /**
     * Find the top-level parent CodeUnit for a given CodeUnit.
     * If the CodeUnit has no parent, it returns itself.
     */
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

    /**
     * Find direct parent of a CodeUnit by looking in childrenByParent map
     */
    private @Nullable CodeUnit findDirectParent(CodeUnit cu) {
        for (var entry : childrenByParent.entrySet()) {
            CodeUnit parent = entry.getKey();
            List<CodeUnit> children = entry.getValue();
            if (children.contains(cu)) {
                return parent;
            }
        }
        return null;
    }

}
