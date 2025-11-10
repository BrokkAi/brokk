package ai.brokk.analyzer;

import static ai.brokk.analyzer.cpp.CppTreeSitterNodeTypes.*;

import ai.brokk.IProject;
import ai.brokk.analyzer.cpp.NamespaceProcessor;
import ai.brokk.analyzer.cpp.SkeletonGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCpp;

public class CppAnalyzer extends TreeSitterAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(CppAnalyzer.class);

    @Override
    public Optional<String> extractClassName(String reference) {
        return ClassNameExtractor.extractForCpp(reference);
    }

    private final SkeletonGenerator skeletonGenerator;
    private final NamespaceProcessor namespaceProcessor;
    private final Map<ProjectFile, String> fileContentCache = new ConcurrentHashMap<>();
    private final ThreadLocal<TSParser> parserCache;

    private static Map<String, SkeletonType> createCaptureConfiguration() {
        var config = new HashMap<String, SkeletonType>();
        config.put(CaptureNames.NAMESPACE_DEFINITION, SkeletonType.CLASS_LIKE);
        config.put(CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE);
        config.put(CaptureNames.STRUCT_DEFINITION, SkeletonType.CLASS_LIKE);
        config.put(CaptureNames.UNION_DEFINITION, SkeletonType.CLASS_LIKE);
        config.put(CaptureNames.ENUM_DEFINITION, SkeletonType.CLASS_LIKE);
        config.put(CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE);
        config.put(CaptureNames.METHOD_DEFINITION, SkeletonType.FUNCTION_LIKE);
        config.put(CaptureNames.CONSTRUCTOR_DEFINITION, SkeletonType.FUNCTION_LIKE);
        config.put(CaptureNames.DESTRUCTOR_DEFINITION, SkeletonType.FUNCTION_LIKE);
        config.put(CaptureNames.VARIABLE_DEFINITION, SkeletonType.FIELD_LIKE);
        config.put(CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE);
        config.put(CaptureNames.TYPEDEF_DEFINITION, SkeletonType.FIELD_LIKE);
        config.put(CaptureNames.USING_DEFINITION, SkeletonType.FIELD_LIKE);
        config.put("access.specifier", SkeletonType.MODULE_STATEMENT);
        return config;
    }

    private static final LanguageSyntaxProfile CPP_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(CLASS_SPECIFIER, STRUCT_SPECIFIER, UNION_SPECIFIER, ENUM_SPECIFIER, NAMESPACE_DEFINITION),
            Set.of(
                    FUNCTION_DEFINITION,
                    METHOD_DEFINITION,
                    CONSTRUCTOR_DECLARATION,
                    DESTRUCTOR_DECLARATION,
                    DECLARATION),
            Set.of(FIELD_DECLARATION, PARAMETER_DECLARATION, ENUMERATOR),
            Set.of(ATTRIBUTE_SPECIFIER, ACCESS_SPECIFIER),
            IMPORT_DECLARATION,
            "name",
            "body",
            "parameters",
            "type",
            "template_parameters",
            createCaptureConfiguration(),
            "",
            Set.of(STORAGE_CLASS_SPECIFIER, TYPE_QUALIFIER, ACCESS_SPECIFIER));

    public CppAnalyzer(IProject project) {
        super(project, Languages.CPP_TREESITTER);

        this.parserCache = ThreadLocal.withInitial(() -> {
            var parser = new TSParser();
            parser.setLanguage(createTSLanguage());
            return parser;
        });

        var templateParser = parserCache.get();
        this.skeletonGenerator = new SkeletonGenerator(templateParser);
        this.namespaceProcessor = new NamespaceProcessor(templateParser);
    }

    private CppAnalyzer(IProject project, AnalyzerState state) {
        super(project, Languages.CPP_TREESITTER, state);
        this.parserCache = ThreadLocal.withInitial(() -> {
            var parser = new TSParser();
            parser.setLanguage(createTSLanguage());
            return parser;
        });

        var templateParser = parserCache.get();
        this.skeletonGenerator = new SkeletonGenerator(templateParser);
        this.namespaceProcessor = new NamespaceProcessor(templateParser);
    }

    @Override
    protected IAnalyzer newSnapshot(AnalyzerState state) {
        return new CppAnalyzer(getProject(), state);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterCpp();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/cpp.scm";
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return CPP_SYNTAX_PROFILE;
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        final char delimiter =
                Optional.ofNullable(CPP_SYNTAX_PROFILE.captureConfiguration().get(captureName)).stream()
                                .anyMatch(x -> x.equals(SkeletonType.CLASS_LIKE))
                        ? '$'
                        : '.';

        String correctedClassChain = classChain;
        if (!packageName.isEmpty() && classChain.startsWith(packageName + ".")) {
            // Class is nested within the package namespace, strip the package prefix
            correctedClassChain = classChain.substring(packageName.length() + 1);
        } else if (!packageName.isEmpty() && classChain.equals(packageName)) {
            // Free function/class directly in namespace with no nesting, clear classChain
            correctedClassChain = "";
        }

        String fqName = correctedClassChain.isEmpty() ? simpleName : correctedClassChain + delimiter + simpleName;

        var skeletonType = getSkeletonTypeForCapture(captureName);

        var type =
                switch (skeletonType) {
                    case CLASS_LIKE -> {
                        if (CaptureNames.NAMESPACE_DEFINITION.equals(captureName)) {
                            yield CodeUnitType.MODULE;
                        } else {
                            yield CodeUnitType.CLASS;
                        }
                    }
                    case FUNCTION_LIKE -> CodeUnitType.FUNCTION;
                    case FIELD_LIKE -> CodeUnitType.FIELD;
                    case MODULE_STATEMENT -> CodeUnitType.MODULE;
                    default -> {
                        log.warn("Unhandled SkeletonType '{}' for captureName '{}' in C++", skeletonType, captureName);
                        yield CodeUnitType.CLASS;
                    }
                };

        return createCodeUnit(file, type, packageName, fqName);
    }

    /**
     * Factory method to get or create a CodeUnit instance with optional parameter signature.
     * For functions, this is called with an enhanced FQName that includes parameter types for overload distinction.
     */
    public CodeUnit createCodeUnit(ProjectFile source, CodeUnitType kind, String packageName, String fqName) {
        return new CodeUnit(source, kind, packageName, fqName);
    }

    @Override
    protected String buildParentFqName(CodeUnit cu, String classChain) {
        String packageName = cu.packageName();
        String correctedClassChain = classChain;
        if (!packageName.isEmpty() && classChain.equals(packageName)) {
            correctedClassChain = "";
        }

        return correctedClassChain.isEmpty()
                ? packageName
                : (packageName.isEmpty() ? correctedClassChain : packageName + "." + correctedClassChain);
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        var namespaceParts = new ArrayList<String>();

        var current = definitionNode;
        while (!current.isNull() && !current.equals(rootNode)) {
            var parent = current.getParent();
            if (parent == null || parent.isNull()) {
                break;
            }
            current = parent;

            if (NAMESPACE_DEFINITION.equals(current.getType())) {
                var nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
                    namespaceParts.add(ASTTraversalUtils.extractNodeText(nameNode, src));
                }
            }
        }

        Collections.reverse(namespaceParts);
        return String.join("::", namespaceParts);
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        return signatureText + " {";
    }

    @Override
    protected String bodyPlaceholder() {
        return "{...}";
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            String src,
            String exportAndModifierPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        var templateParams = typeParamsText.isEmpty() ? "" : typeParamsText + " ";
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        String actualParamsText = "";
        TSNode declaratorNode = funcNode.getChildByFieldName("declarator");
        if (declaratorNode != null && "function_declarator".equals(declaratorNode.getType())) {
            TSNode paramsNode = declaratorNode.getChildByFieldName("parameters");
            if (paramsNode != null && !paramsNode.isNull()) {
                actualParamsText = ASTTraversalUtils.extractNodeText(paramsNode, src);
            }
        }

        if (functionName.isBlank()) {
            TSNode fallbackDeclaratorNode = funcNode.getChildByFieldName("declarator");
            if (fallbackDeclaratorNode != null && "function_declarator".equals(fallbackDeclaratorNode.getType())) {
                TSNode innerDeclaratorNode = fallbackDeclaratorNode.getChildByFieldName("declarator");
                if (innerDeclaratorNode != null) {
                    String extractedName = ASTTraversalUtils.extractNodeText(innerDeclaratorNode, src);
                    if (!extractedName.isBlank()) {
                        functionName = extractedName;
                    }
                }
            }

            if (functionName.isBlank()) {
                functionName = "<unknown_function>";
            }
        }

        var signature =
                indent + exportAndModifierPrefix + templateParams + returnType + functionName + actualParamsText;

        var throwsNode = funcNode.getChildByFieldName("noexcept_specifier");
        if (throwsNode != null) {
            signature += " " + ASTTraversalUtils.extractNodeText(throwsNode, src);
        }

        // Presentation-only marker: we still append bodyPlaceholder() to the rendered signature for UI clarity.
        // NOTE: Duplicate/definition preference no longer relies on this string marker; it uses the AST-derived
        // hasBody flag stored in CodeUnitProperties (see TreeSitterAnalyzer). Keep this strictly for display.
        TSNode bodyNode =
                funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        boolean hasBody = bodyNode != null && !bodyNode.isNull() && bodyNode.getEndByte() > bodyNode.getStartByte();

        if (hasBody) {
            signature += " " + bodyPlaceholder();
        }

        if (signature.isBlank()) {
            signature = indent + "void " + functionName + "()";
        }

        return signature;
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return "}";
    }

    @Override
    protected boolean requiresSemicolons() {
        return true;
    }

    @Override
    public Set<CodeUnit> getDeclarations(ProjectFile file) {
        var baseDeclarations = super.getDeclarations(file);
        var mergedSkeletons = getSkeletons(file);
        var result = new HashSet<CodeUnit>();
        var namespaceCodeUnits = new HashSet<CodeUnit>();

        for (var cu : mergedSkeletons.keySet()) {
            if (cu.isModule()) {
                namespaceCodeUnits.add(cu);
            }
        }

        for (var cu : baseDeclarations) {
            if (!cu.isModule()) {
                result.add(cu);
            }
        }

        result.addAll(namespaceCodeUnits);
        return result;
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        try {
            Map<CodeUnit, String> resultSkeletons = new HashMap<>(super.getSkeletons(file));

            // Use cached tree to avoid redundant parsing - significant performance improvement
            String fileContent = getCachedFileContent(file);
            TSTree tree = treeOf(file);
            if (tree == null) {
                var parser = getSharedParser();
                tree = Objects.requireNonNull(parser.parseString(null, fileContent), "Failed to parse file: " + file);
            }
            var rootNode = tree.getRootNode();

            resultSkeletons = skeletonGenerator.fixGlobalEnumSkeletons(resultSkeletons, file, rootNode, fileContent);
            resultSkeletons = skeletonGenerator.fixGlobalUnionSkeletons(resultSkeletons, file, rootNode, fileContent);
            final var tempSkeletons = resultSkeletons; // we need an "effectively final" variable for the callback
            resultSkeletons = withCodeUnitProperties(properties -> {
                var signaturesMap = new HashMap<CodeUnit, List<String>>();
                properties.forEach((cu, props) -> signaturesMap.put(cu, props.signatures()));
                return namespaceProcessor.mergeNamespaceBlocks(
                        tempSkeletons,
                        signaturesMap,
                        file,
                        rootNode,
                        fileContent,
                        namespaceName -> createCodeUnit(file, CodeUnitType.MODULE, "", namespaceName));
            });
            if (isHeaderFile(file)) {
                resultSkeletons = addCorrespondingSourceDeclarations(resultSkeletons, file);
            }

            return Collections.unmodifiableMap(resultSkeletons);
        } catch (Exception e) {
            log.error("Failed to generate skeletons for file {}: {}", file, e.getMessage(), e);
            return super.getSkeletons(file);
        }
    }

    private Map<CodeUnit, String> addCorrespondingSourceDeclarations(
            Map<CodeUnit, String> resultSkeletons, ProjectFile file) {
        var result = new HashMap<>(resultSkeletons);

        ProjectFile correspondingSource = findCorrespondingSourceFile(file);
        if (correspondingSource != null) {
            List<CodeUnit> sourceCUs = getTopLevelDeclarations().getOrDefault(correspondingSource, List.of());

            for (CodeUnit sourceCU : sourceCUs) {
                if (isGlobalFunctionOrVariable(sourceCU)) {
                    boolean alreadyExists = result.keySet().stream()
                            .anyMatch(headerCU ->
                                    headerCU.fqName().equals(sourceCU.fqName()) && headerCU.kind() == sourceCU.kind());

                    if (!alreadyExists) {
                        var sourceSkeletons = super.getSkeletons(correspondingSource);
                        String skeleton = sourceSkeletons.get(sourceCU);
                        if (skeleton != null) {
                            result.put(sourceCU, skeleton);
                        }
                    }
                }
            }
        }

        return result;
    }

    private boolean isHeaderFile(ProjectFile file) {
        String fileName = file.absPath().getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".h") || fileName.endsWith(".hpp") || fileName.endsWith(".hxx");
    }

    private @Nullable ProjectFile findCorrespondingSourceFile(ProjectFile headerFile) {
        String headerFileName = headerFile.absPath().getFileName().toString();
        String baseName = headerFileName.substring(0, headerFileName.lastIndexOf('.'));
        String[] sourceExtensions = {".cpp", ".cc", ".cxx", ".c"};

        for (String ext : sourceExtensions) {
            String sourceFileName = baseName + ext;
            var parentPath = headerFile.absPath().getParent();
            if (parentPath != null) {
                var candidatePath = parentPath.resolve(sourceFileName);
                ProjectFile candidateSource = new ProjectFile(
                        headerFile.getRoot(), headerFile.getRoot().relativize(candidatePath));

                if (getTopLevelDeclarations().containsKey(candidateSource)) {
                    return candidateSource;
                }
            }
        }

        return null;
    }

    private boolean isGlobalFunctionOrVariable(CodeUnit cu) {
        return (cu.isFunction() || cu.isField())
                && cu.packageName().isEmpty()
                && !cu.fqName().contains(".");
    }

    private String getCachedFileContent(ProjectFile file) throws IOException {
        return fileContentCache.computeIfAbsent(file, f -> {
            try {
                return Files.readString(f.absPath());
            } catch (IOException e) {
                log.error("Failed to read file content: {}", f, e);
                throw new RuntimeException("Failed to read file: " + f, e);
            }
        });
    }

    private TSParser getSharedParser() {
        return parserCache.get();
    }

    /**
     * Builds a canonical parameter type CSV for C++ function overloads using the AST.
     *
     * This implementation iterates the named children of the `parameters` node (parameter_declaration nodes) and
     * extracts the type portion for each parameter by removing parameter names and default values. It avoids splitting
     * on commas in raw text so template arguments or function-pointer parameter lists are not broken.
     *
     * Note: This method returns only the comma-separated parameter type list (no enclosing parentheses).
     *
     * @param funcOrDeclNode the function definition or declaration node
     * @param src the source code
     * @return normalized parameter types CSV, or empty string if no parameters or extraction fails
     */
    @SuppressWarnings("RedundantNullCheck") // Defensive check for TreeSitter JNI interop
    private String buildCppOverloadSuffix(TSNode funcOrDeclNode, String src) {
        if (funcOrDeclNode == null || funcOrDeclNode.isNull()) return "";

        // Find the function_declarator (descend if necessary)
        TSNode decl = funcOrDeclNode.getChildByFieldName("declarator");
        if (decl == null || decl.isNull() || !"function_declarator".equals(decl.getType())) {
            decl = findFunctionDeclaratorRecursive(funcOrDeclNode);
            if (decl == null) return "";
        }

        TSNode paramsNode = decl.getChildByFieldName("parameters");
        if (paramsNode == null || paramsNode.isNull()) return "";

        var paramTypes = new ArrayList<String>();

        // Iterate named children to avoid naive comma splitting (templates contain commas)
        int namedCount = paramsNode.getNamedChildCount();
        for (int i = 0; i < namedCount; i++) {
            TSNode paramNode = paramsNode.getNamedChild(i);
            if (paramNode == null || paramNode.isNull()) continue;

            String raw = ASTTraversalUtils.extractNodeText(paramNode, src).strip();
            if (raw.isEmpty()) continue;
            if (raw.equals("...")) {
                paramTypes.add("...");
                continue;
            }

            // Remove default value (everything after '=')
            int eqIdx = raw.indexOf('=');
            if (eqIdx >= 0) raw = raw.substring(0, eqIdx).strip();

            // Try to remove parameter name using AST-extracted name nodes
            TSNode nameNode = paramNode.getChildByFieldName("name");
            if (nameNode == null || nameNode.isNull()) {
                // parameter names are sometimes inside a declarator child
                TSNode declChild = paramNode.getChildByFieldName("declarator");
                if (declChild != null && !declChild.isNull()) {
                    TSNode innerName = declChild.getChildByFieldName("declarator");
                    if (innerName == null || innerName.isNull()) innerName = declChild.getChildByFieldName("name");
                    if (innerName != null && !innerName.isNull()) nameNode = innerName;
                }
            }

            if (nameNode != null && !nameNode.isNull()) {
                String nameText =
                        ASTTraversalUtils.extractNodeText(nameNode, src).strip();
                if (!nameText.isEmpty()) {
                    // Remove the identifier token (token-boundary) to avoid clobbering template names
                    raw = raw.replaceAll("\\b" + java.util.regex.Pattern.quote(nameText) + "\\b", "")
                            .strip();
                }
            } else {
                // Fallback heuristic: remove a trailing token that looks like an identifier
                String[] toks = raw.split("\\s+");
                if (toks.length > 1) {
                    String last = toks[toks.length - 1];
                    if (!last.isEmpty() && Character.isJavaIdentifierStart(last.charAt(0))) {
                        raw = String.join(" ", java.util.Arrays.copyOf(toks, toks.length - 1))
                                .strip();
                    }
                }
            }

            // Normalize whitespace and pointer/reference spacing
            raw = raw.replaceAll("\\s+", " ")
                    .replaceAll("\\s*\\*\\s*", "*")
                    .replaceAll("\\s*&\\s*", "&")
                    .strip();

            if (!raw.isEmpty()) paramTypes.add(raw);
        }

        return String.join(",", paramTypes);
    }

    /**
     * Recursively searches for a function_declarator node within a declarator tree.
     *
     * Handles nested declarators found in:
     * - Method definitions with scope resolution (e.g., ClassName::method(...))
     * - Function pointers and complex declarators
     * - Constructors and destructors
     *
     * @param node the root node to search within
     * @return the function_declarator node, or null if not found
     */
    @SuppressWarnings("RedundantNullCheck") // Defensive check for TreeSitter JNI interop
    private @Nullable TSNode findFunctionDeclaratorRecursive(TSNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        // Base case: found the function_declarator
        if ("function_declarator".equals(node.getType())) {
            return node;
        }

        // Recursive case: search all children depth-first
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                TSNode result = findFunctionDeclaratorRecursive(child);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    @Override
    public void clearCaches() {
        super.clearCaches(); // Clear cached trees to free memory
        fileContentCache.clear();
        skeletonGenerator.clearCache();
        namespaceProcessor.clearCache();
    }

    @Override
    protected Optional<String> extractSimpleName(TSNode decl, String src) {
        if (NAMESPACE_DEFINITION.equals(decl.getType())) {
            TSNode nameNode = decl.getChildByFieldName("name");
            if (nameNode == null || nameNode.isNull()) {
                return Optional.of("(anonymous)");
            }
            String name = ASTTraversalUtils.extractNodeText(nameNode, src);
            return Optional.of(name);
        }

        // Handle class-like types (struct, class, union, enum)
        if (STRUCT_SPECIFIER.equals(decl.getType())
                || CLASS_SPECIFIER.equals(decl.getType())
                || UNION_SPECIFIER.equals(decl.getType())
                || ENUM_SPECIFIER.equals(decl.getType())) {
            TSNode nameNode = decl.getChildByFieldName("name");
            if (nameNode == null || nameNode.isNull()) {
                // Anonymous struct/class/union/enum (e.g., anonymous struct in union)
                return Optional.of("(anonymous)");
            }
            String name = ASTTraversalUtils.extractNodeText(nameNode, src);
            if (name.isBlank()) {
                // Name exists but is blank - likely parsing edge case
                return Optional.of("(anonymous)");
            }
            return Optional.of(name);
        }

        if (FUNCTION_DEFINITION.equals(decl.getType())) {
            TSNode declaratorNode = decl.getChildByFieldName("declarator");
            if (declaratorNode != null && "function_declarator".equals(declaratorNode.getType())) {
                TSNode innerDeclaratorNode = declaratorNode.getChildByFieldName("declarator");
                if (innerDeclaratorNode != null) {
                    String name = ASTTraversalUtils.extractNodeText(innerDeclaratorNode, src);
                    if (!name.isBlank()) {
                        return Optional.of(name);
                    }
                }
            }
        }

        if (DECLARATION.equals(decl.getType())
                || METHOD_DEFINITION.equals(decl.getType())
                || CONSTRUCTOR_DECLARATION.equals(decl.getType())
                || DESTRUCTOR_DECLARATION.equals(decl.getType())
                || FIELD_DECLARATION.equals(decl.getType())) {
            TSNode declaratorNode = decl.getChildByFieldName("declarator");
            if (declaratorNode != null) {
                if ("function_declarator".equals(declaratorNode.getType())) {
                    TSNode innerDeclaratorNode = declaratorNode.getChildByFieldName("declarator");
                    if (innerDeclaratorNode != null) {
                        String name = ASTTraversalUtils.extractNodeText(innerDeclaratorNode, src);
                        if (!name.isBlank()) {
                            return Optional.of(name);
                        }
                    }
                } else {
                    String name = ASTTraversalUtils.extractNodeText(declaratorNode, src);
                    if (!name.isBlank()) {
                        return Optional.of(name);
                    }
                }
            }
        }

        return super.extractSimpleName(decl, src);
    }

    @Override
    protected boolean isBlankNameAllowed(String captureName, String simpleName, String nodeType, String file) {
        // C++ allows blank names for complex declaration structures where the parser
        // produces empty identifier nodes (common in flexed/generated C code, function pointers,
        // template specializations, macro expansions)
        return simpleName.isBlank() && isComplexDeclarationStructure(nodeType);
    }

    @Override
    protected boolean isNullNameAllowed(String identifierFieldName, String nodeType, int lineNumber, String file) {
        // C++ allows NULL names for complex declaration structures like function pointers,
        // template specializations, and macro declarations
        return isComplexDeclarationStructure(nodeType);
    }

    @Override
    protected boolean isNullNameExpectedForExtraction(String nodeType) {
        // Suppress logging for common C++ patterns where null names are expected
        return isComplexDeclarationStructure(nodeType);
    }

    private boolean isComplexDeclarationStructure(String nodeType) {
        // Common C++ complex declaration patterns that may not have simple name fields
        return "declaration".equals(nodeType)
                || "function_definition".equals(nodeType)
                || "field_declaration".equals(nodeType)
                || "parameter_declaration".equals(nodeType);
    }

    @Override
    protected String enhanceFqName(String fqName, String captureName, TSNode definitionNode, String src) {
        var skeletonType = getSkeletonTypeForCapture(captureName);

        // For functions, apply name normalization (e.g., destructor tilde) but do NOT append signature
        // Signature is now extracted separately via extractSignature()
        if (skeletonType == SkeletonType.FUNCTION_LIKE) {
            // Special-case: ensure destructors have a leading '~' in the symbol name.
            // Tree-sitter may expose the underlying identifier without the tilde; normalize here.
            if (CaptureNames.DESTRUCTOR_DEFINITION.equals(captureName) && !fqName.startsWith("~")) {
                fqName = "~" + fqName;
            }
            // Return clean fqName without parameters/qualifiers
            return fqName;
        }

        // For non-function types (classes, fields, modules), return unchanged
        return fqName;
    }

    /**
     * Extracts the signature string for a function/method, including parameter types and qualifiers.
     * This signature is used to populate CodeUnit.signature field for overload disambiguation.
     *
     * @param captureName The capture name from the query
     * @param definitionNode The AST node for the function definition
     * @param src The source code string
     * @return The signature string (e.g., "(int)" or "(int) const"), or null for non-functions
     */
    @Override
    protected @Nullable String extractSignature(String captureName, TSNode definitionNode, String src) {
        var skeletonType = getSkeletonTypeForCapture(captureName);

        // Only extract signature for function-like entities
        if (skeletonType != SkeletonType.FUNCTION_LIKE) {
            return null;
        }

        String paramSignature = buildCppOverloadSuffix(definitionNode, src);
        String qualifierSuffix = buildCppQualifierSuffix(definitionNode, src);

        if (!paramSignature.isEmpty()) {
            return "(" + paramSignature + ")" + (qualifierSuffix.isEmpty() ? "" : " " + qualifierSuffix);
        }
        // Empty parameter list: still return "()" for stable function identity, optionally with qualifiers
        return "()" + (qualifierSuffix.isEmpty() ? "" : " " + qualifierSuffix);
    }

    /**
     * Extracts method-level qualifiers (const/volatile/ref/noexcept) that should form part of overload identity.
     * Captures full qualifier identity including complete noexcept expressions.
     *
     * Returns a trimmed suffix such as "const", "volatile", "&", "&&", "noexcept", "noexcept(expr)",
     * or combinations in order ("const volatile && noexcept(true)"), or empty string if none found.
     *
     * The method parses the declarator tail (from parameters end to declarator end) to extract:
     * - const and volatile keywords (using word boundaries to avoid false matches)
     * - reference qualifiers (&& has precedence over &)
     * - complete noexcept clause (including optional parenthesized condition)
     */
    @SuppressWarnings("RedundantNullCheck") // Defensive check for TreeSitter JNI interop
    private String buildCppQualifierSuffix(TSNode funcOrDeclNode, String src) {
        if (funcOrDeclNode == null || funcOrDeclNode.isNull()) return "";

        // Find the function_declarator if present
        TSNode decl = funcOrDeclNode.getChildByFieldName("declarator");
        if (decl == null || decl.isNull() || !"function_declarator".equals(decl.getType())) {
            decl = findFunctionDeclaratorRecursive(funcOrDeclNode);
            if (decl == null) return "";
        }

        // Look for textual qualifiers in the trailing portion after the parameter list.
        // IMPORTANT: In C++, member-function qualifiers (const/volatile/ref/noexcept) may appear AFTER the parameters
        // but OUTSIDE the function_declarator node. So we must scan from params end up to the start of the body (if
        // any),
        // or to the end of the outer node when there is no body.
        TSNode paramsNode = decl.getChildByFieldName("parameters");
        int tailStart = (paramsNode != null && !paramsNode.isNull()) ? paramsNode.getEndByte() : decl.getStartByte();

        // Determine an outer end bound to include qualifiers that may be outside the declarator
        int outerTailEnd;
        TSNode bodyNode = funcOrDeclNode.getChildByFieldName("body");
        if (bodyNode != null && !bodyNode.isNull()) {
            outerTailEnd = bodyNode.getStartByte();
        } else {
            outerTailEnd = funcOrDeclNode.getEndByte();
        }

        // Scan from params end up to the outer bound
        int tailEnd = outerTailEnd;
        if (tailStart >= tailEnd) return "";

        String tail = ASTTraversalUtils.safeSubstringFromByteOffsets(src, tailStart, tailEnd)
                .strip();

        // Augment textual detection with AST-based scanning for robust qualifier extraction
        boolean nodeHasConst = false;
        boolean nodeHasVolatile = false;

        // Scan both the outer node and the function_declarator node for TYPE_QUALIFIER children in the tail region
        nodeHasConst = nodeHasConst || scanForQualifier(funcOrDeclNode, tailStart, tailEnd, src, "const");
        nodeHasVolatile = nodeHasVolatile || scanForQualifier(funcOrDeclNode, tailStart, tailEnd, src, "volatile");
        nodeHasConst = nodeHasConst || scanForQualifier(decl, tailStart, tailEnd, src, "const");
        nodeHasVolatile = nodeHasVolatile || scanForQualifier(decl, tailStart, tailEnd, src, "volatile");

        var quals = new ArrayList<String>();
        var addedQualTypes = new HashSet<String>(); // Track which qualifier types have been added

        // Extract const qualifier (word boundary aware + AST-based)
        if ((tail != null && !tail.isEmpty() && hasKeywordWithBoundary(tail, "const")) || nodeHasConst) {
            if (addedQualTypes.add("const")) {
                quals.add("const");
            }
        }

        // Extract volatile qualifier (word boundary aware + AST-based)
        if ((tail != null && !tail.isEmpty() && hasKeywordWithBoundary(tail, "volatile")) || nodeHasVolatile) {
            if (addedQualTypes.add("volatile")) {
                quals.add("volatile");
            }
        }

        // Extract reference qualifier: && takes precedence over & (check && first)
        if (tail != null && tail.contains("&&")) {
            if (addedQualTypes.add("&&")) {
                quals.add("&&");
            }
        } else if (tail != null && tail.contains("&")) {
            if (addedQualTypes.add("&")) {
                quals.add("&");
            }
        }

        // Extract full noexcept clause (including optional parenthesized condition)
        String noexceptClause = (tail == null || tail.isEmpty()) ? "" : extractNoexceptClause(tail);
        if (!noexceptClause.isEmpty() && addedQualTypes.add("noexcept")) {
            quals.add(noexceptClause);
        }

        if (quals.isEmpty()) {
            return "";
        }

        String result = String.join(" ", quals).strip();
        if (log.isDebugEnabled()) {
            log.debug("Extracted qualifier suffix '{}' from declarator tail: {}", result, (tail == null ? "" : tail));
        }
        return result;
    }

    /**
     * Checks if a keyword exists in text with word boundaries (not as part of a longer identifier).
     * This prevents false matches like "const" matching inside "constexpr".
     *
     * @param text the text to search
     * @param keyword the keyword to find
     * @return true if the keyword is found as a standalone word
     */
    private boolean hasKeywordWithBoundary(String text, String keyword) {
        var pattern = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(keyword) + "\\b");
        return pattern.matcher(text).find();
    }

    /**
     * Extracts the full noexcept clause from a string, including optional parenthesized expressions.
     *
     * Handles patterns:
     * - "noexcept" -> "noexcept"
     * - "noexcept(true)" -> "noexcept(true)"
     * - "noexcept(false)" -> "noexcept(false)"
     * - "noexcept(some_expr)" -> "noexcept(some_expr)"
     * - "noexcept()" -> "noexcept()"
     * - Multiple spaces and formatting variations
     *
     * Uses word-boundary matching to avoid false positives and parenthesis depth tracking
     * to correctly extract nested expressions.
     *
     * @param text the tail string to search for noexcept
     * @return the complete noexcept clause (with normalized spacing), or empty string if not found
     */
    private String extractNoexceptClause(String text) {
        // Use word boundary to find "noexcept" as standalone keyword
        var pattern = java.util.regex.Pattern.compile("\\bnoexcept\\b");
        var matcher = pattern.matcher(text);

        if (!matcher.find()) {
            return "";
        }

        int startIdx = matcher.start();
        int endIdx = matcher.end();

        // Skip any whitespace between "noexcept" and potential opening parenthesis
        while (endIdx < text.length() && Character.isWhitespace(text.charAt(endIdx))) {
            endIdx++;
        }

        // Check if there's a parenthesized expression immediately following
        if (endIdx < text.length() && text.charAt(endIdx) == '(') {
            // Find matching closing parenthesis using depth tracking
            int parenDepth = 0;
            int i = endIdx;
            int closeParenIdx = -1;

            while (i < text.length()) {
                char ch = text.charAt(i);
                if (ch == '(') {
                    parenDepth++;
                } else if (ch == ')') {
                    parenDepth--;
                    if (parenDepth == 0) {
                        closeParenIdx = i;
                        break;
                    }
                }
                i++;
            }

            // If matching closing paren found, include it; otherwise just return "noexcept"
            if (closeParenIdx >= 0) {
                endIdx = closeParenIdx + 1;
            } else {
                // Mismatched parens; just return the keyword
                endIdx = matcher.end();
            }
        } else {
            // No parentheses; just return "noexcept"
            endIdx = matcher.end();
        }

        String clause = text.substring(startIdx, endIdx).strip();

        // Normalize internal spacing (remove spaces around parentheses and inside)
        clause = clause.replaceAll("\\s*\\(\\s*", "(").replaceAll("\\s*\\)\\s*", ")");

        if (log.isDebugEnabled()) {
            log.debug("Extracted noexcept clause '{}' from text", clause);
        }

        return clause;
    }

    /**
     * Scans immediate named children of the given parent node within the [tailStart, tailEnd) byte range
     * for TYPE_QUALIFIER nodes containing the specified qualifier token.
     */
    @SuppressWarnings("RedundantNullCheck") // Defensive check for TreeSitter JNI interop
    private boolean scanForQualifier(TSNode parent, int tailStart, int tailEnd, String src, String qualifier) {
        if (parent == null || parent.isNull()) return false;
        int count = parent.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = parent.getNamedChild(i);
            if (child == null || child.isNull()) continue;
            int sb = child.getStartByte();
            if (sb < tailStart || sb >= tailEnd) continue;
            String t = child.getType();
            if (TYPE_QUALIFIER.equals(t)) {
                String q = ASTTraversalUtils.extractNodeText(child, src).strip();
                if (q.contains(qualifier)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean shouldIgnoreDuplicate(CodeUnit existing, CodeUnit candidate, ProjectFile file) {
        // For C++, we ignore duplicates for classes, fields, and modules
        // BUT NOT for functions, because they might be overloads with different signatures

        if (candidate.isFunction()) {
            // Functions might be overloads - don't treat as duplicates
            // Trade-off: this also keeps forward declarations + definitions
            return false; // Don't ignore - add the candidate
        }

        if (candidate.isClass() || candidate.isField() || candidate.isModule()) {
            // These are true duplicates in C++ (header guards, preprocessor conditionals, etc.)
            return true; // Ignore the duplicate
        }

        // For other types, use default behavior
        return super.shouldIgnoreDuplicate(existing, candidate, file);
    }

    /**
     * Backward-compatibility override for function lookup by fully qualified name.
     *
     * <p>This method provides transitional convenience for callers that previously looked up zero-argument
     * functions by their simple name (without parentheses). For example, a lookup for "MyClass::myFunc"
     * will automatically retry as "MyClass::myFunc()" if the first attempt returns empty.
     *
     * <p>This allows existing code that relies on simple-name lookups to continue working after the
     * analyzer became overload-aware and began requiring parameter signatures in function FQNames.
     *
     * @param fqName the fully qualified name to look up
     * @return an Optional containing the matching CodeUnit, or empty if not found
     */
    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        // First, try the lookup as provided
        var result = super.getDefinition(fqName);
        if (result.isPresent()) {
            return result;
        }

        // If empty and fqName doesn't already contain parentheses, retry with empty parameter list
        if (!fqName.contains("(") && !fqName.contains(")")) {
            return super.getDefinition(fqName + "()");
        }

        return Optional.empty();
    }

    @Override
    protected Comparator<CodeUnit> prioritizingComparator() {
        // Prefer implementations over declarations for C/C++ functions.
        // Short rationale: returning implementation first lets clients reliably "pick first" and get the .c/.cpp body.
        // Priority ordering (lower is better):
        // -2: function with body in .c/.cc/.cpp/.cxx
        // -1: function with body in header (inline/template)
        //  0: everything else (declarations)
        return (cu1, cu2) -> {
            // Non-functions have no preference
            if (!cu1.isFunction() || !cu2.isFunction()) {
                return 0;
            }

            boolean cu1HasBody = withCodeUnitProperties(props -> {
                var p = props.get(cu1);
                return p != null && p.hasBody();
            });

            boolean cu2HasBody = withCodeUnitProperties(props -> {
                var p = props.get(cu2);
                return p != null && p.hasBody();
            });

            // Declarations have no priority advantage
            if (!cu1HasBody && !cu2HasBody) {
                return 0;
            }

            // Prefer definitions (with body) over declarations
            if (cu1HasBody && !cu2HasBody) return -1;
            if (!cu1HasBody && cu2HasBody) return 1;

            // Both have body: prefer source files (.c/.cc/.cpp/.cxx) over headers
            String ext1 = cu1.source().extension().toLowerCase(Locale.ROOT);
            String ext2 = cu2.source().extension().toLowerCase(Locale.ROOT);
            boolean cu1IsSource = ext1.equals(".c") || ext1.equals(".cc") || ext1.equals(".cpp") || ext1.equals(".cxx");
            boolean cu2IsSource = ext2.equals(".c") || ext2.equals(".cc") || ext2.equals(".cpp") || ext2.equals(".cxx");

            if (cu1IsSource && !cu2IsSource) return -1;
            if (!cu1IsSource && cu2IsSource) return 1;

            return 0;
        };
    }

    public String getCacheStatistics() {
        // Count non-null parsed trees in fileState
        int parsedTreeCount = withFileProperties(fileProps -> (int) fileProps.values().stream()
                .filter(fp -> fp.parsedTree() != null)
                .count());
        return String.format(
                "FileContent: %d, ParsedTrees: %d, SkeletonGen: %d, NamespaceProc: %d",
                fileContentCache.size(),
                parsedTreeCount,
                skeletonGenerator.getCacheSize(),
                namespaceProcessor.getCacheSize());
    }
}
