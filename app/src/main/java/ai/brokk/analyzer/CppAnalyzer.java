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
            correctedClassChain = classChain.substring(packageName.length() + 1);
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
                // Fallback: parse the file if tree is not cached
                // This can happen for implementation headers (_impl.h, _inl.h) or when analyzing
                // corresponding source files before the main analysis pass
                log.trace("Tree not found in cache for {}. Parsing on-demand.", file);
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
     * Extracts normalized parameter type signature from a function declarator node.
     * Returns a CSV of parameter types with names removed and whitespace normalized.
     * Example: "int, const char*, std::string" (or empty if no parameters).
     *
     * @param funcDefNode the function_definition or declaration node
     * @param src the source code
     * @return normalized parameter types CSV, or empty string if no parameters or extraction fails
     */
    private String extractNormalizedParameterSignature(TSNode funcDefNode, String src) {
        if (funcDefNode.isNull()) {
            return "";
        }

        // Find the function_declarator node
        TSNode declaratorNode = funcDefNode.getChildByFieldName("declarator");
        if (declaratorNode == null || declaratorNode.isNull()) {
            return "";
        }

        // Navigate to function_declarator if current declarator is nested
        TSNode funcDeclNode = declaratorNode;
        if (!"function_declarator".equals(funcDeclNode.getType())) {
            // Try to find function_declarator as a descendant
            funcDeclNode = findFunctionDeclaratorRecursive(declaratorNode);
            if (funcDeclNode == null) {
                return "";
            }
        }

        // Extract parameters node
        TSNode paramsNode = funcDeclNode.getChildByFieldName("parameters");
        if (paramsNode == null || paramsNode.isNull()) {
            return "";
        }

        // Extract raw parameter text
        String paramsText = ASTTraversalUtils.extractNodeText(paramsNode, src).strip();
        if (paramsText.isEmpty() || paramsText.equals("()")) {
            return "";
        }

        // Remove outer parentheses if present
        if (paramsText.startsWith("(") && paramsText.endsWith(")")) {
            paramsText = paramsText.substring(1, paramsText.length() - 1).strip();
        }

        if (paramsText.isEmpty()) {
            return "";
        }

        // Parse and normalize parameter types
        return normalizeParameterTypes(paramsText);
    }

    /**
     * Recursively searches for a function_declarator node within a declarator tree.
     */
    private @Nullable TSNode findFunctionDeclaratorRecursive(TSNode node) {
        if ("function_declarator".equals(node.getType())) {
            return node;
        }

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

    /**
     * Normalizes a parameter list string by extracting only types (removing names, default values).
     * Normalizes whitespace and punctuation.
     * Example input: "int x, const char* name, std::string s = \"default\""
     * Example output: "int,const char*,std::string"
     */
    private String normalizeParameterTypes(String paramsText) {
        // Split by comma to get individual parameters
        var params = paramsText.split(",");
        var normalizedTypes = new ArrayList<String>();

        for (String param : params) {
            param = param.strip();
            if (param.isEmpty() || param.equals("...")) {
                // Skip empty or variadic marker itself, but handle ... separately
                if (param.equals("...")) {
                    normalizedTypes.add("...");
                }
                continue;
            }

            // Handle variadic parameters
            if (param.equals("...")) {
                normalizedTypes.add("...");
                continue;
            }

            // Remove default values (everything after '=')
            int eqIdx = param.indexOf('=');
            if (eqIdx >= 0) {
                param = param.substring(0, eqIdx).strip();
            }

            // Extract just the type part (before the identifier)
            // Common patterns: "int x", "const char* name", "std::string&& ref"
            String typeOnly = extractTypeFromParameter(param);

            if (!typeOnly.isEmpty()) {
                // Normalize internal whitespace
                typeOnly = typeOnly.replaceAll("\\s+", " ").strip();
                normalizedTypes.add(typeOnly);
            }
        }

        return String.join(",", normalizedTypes);
    }

    /**
     * Extracts the type portion of a parameter declaration, removing the identifier/name.
     * Handles complex types like pointers, references, arrays, templates.
     * Example: "const char* name" -> "const char*"
     * Example: "std::vector<int>& vec" -> "std::vector<int>&"
     */
    private String extractTypeFromParameter(String param) {
        param = param.strip();
        if (param.isEmpty()) {
            return "";
        }

        // Handle trailing reference/pointer qualifiers that might follow identifier
        // e.g., "int* p" -> type is "int*"
        // The type typically ends before the identifier starts (with possible * or & attached to type)

        // Find where the identifier likely starts
        // Identifiers can't start with *, &, or digits, but types can end with them
        // Strategy: work backwards from the end, tracking pointer/reference/const qualifiers

        // First, handle simple case: no spaces (like "int" or "char*")
        if (!param.contains(" ")) {
            // Could be fully qualified like "std::string" or "int*" or "int&"
            return param;
        }

        // Multiple words: need to separate type from identifier
        // Split by whitespace and reconstruct type portion
        var tokens = param.split("\\s+");

        // Build type by collecting tokens until we hit the identifier
        var typeParts = new ArrayList<String>();
        for (int i = 0; i < tokens.length - 1; i++) {
            typeParts.add(tokens[i]);
        }

        // Last token(s) might include identifier and/or trailing pointer/reference markers
        String lastToken = tokens[tokens.length - 1];

        // Check if lastToken has trailing * or & that belongs to type
        // e.g., in "const char* name", last token after split is "name"
        // In "int* p", tokens are ["int*", "p"]
        // In "const char* name", tokens are ["const", "char*", "name"]

        // For safety: if the lastToken starts with a letter/_, it's likely the identifier
        // Attach any leading * or & to the type instead
        if (!lastToken.isEmpty() && (Character.isLetter(lastToken.charAt(0)) || lastToken.charAt(0) == '_')) {
            // lastToken is the identifier, don't include it
            // But check if there are trailing pointer/reference markers to move to type
            int i = lastToken.length() - 1;
            while (i >= 0 && (lastToken.charAt(i) == '*' || lastToken.charAt(i) == '&' || lastToken.charAt(i) == '[' || lastToken.charAt(i) == ']')) {
                i--;
            }
            if (i < lastToken.length() - 1) {
                // There are trailing markers
                String markers = lastToken.substring(i + 1);
                String identifier = lastToken.substring(0, i + 1);
                // Don't include identifier in type
                typeParts.add(markers);
            }
            // else: no trailing markers, identifier is standalone
        } else {
            // Might be part of type (e.g., trailing * or &)
            typeParts.add(lastToken);
        }

        return String.join(" ", typeParts).strip();
    }

    /**
     * Factory method to get or create a CodeUnit instance, ensuring object identity. This prevents duplicate CodeUnit
     * instances for the same logical entity.
     */
    public CodeUnit createCodeUnit(ProjectFile source, CodeUnitType kind, String packageName, String fqName) {
        return new CodeUnit(source, kind, packageName, fqName);
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
        return simpleName != null && simpleName.isBlank() && isComplexDeclarationStructure(captureName, nodeType);
    }

    @Override
    protected boolean isNullNameAllowed(String identifierFieldName, String nodeType, int lineNumber, String file) {
        // C++ allows NULL names for complex declaration structures like function pointers,
        // template specializations, and macro declarations
        return isComplexDeclarationStructure(identifierFieldName, nodeType);
    }

    @Override
    protected boolean isNullNameExpectedForExtraction(String nodeType) {
        // Suppress logging for common C++ patterns where null names are expected
        return isComplexDeclarationStructure(null, nodeType);
    }

    private boolean isComplexDeclarationStructure(String identifierFieldName, String nodeType) {
        // Common C++ complex declaration patterns that may not have simple name fields
        return "declaration".equals(nodeType)
                || "function_definition".equals(nodeType)
                || "field_declaration".equals(nodeType)
                || "parameter_declaration".equals(nodeType);
    }

    @Override
    protected String enhanceFqName(String fqName, String captureName, TSNode definitionNode, String src) {
        var skeletonType = getSkeletonTypeForCapture(captureName);

        // For functions, append normalized parameter signature to FQN
        if (skeletonType == SkeletonType.FUNCTION_LIKE) {
            String paramSignature = extractNormalizedParameterSignature(definitionNode, src);
            if (!paramSignature.isEmpty()) {
                return fqName + "(" + paramSignature + ")";
            }
            // If no parameters, still append empty parens to distinguish from non-function items
            return fqName + "()";
        }

        // For non-function types, return unchanged
        return fqName;
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
