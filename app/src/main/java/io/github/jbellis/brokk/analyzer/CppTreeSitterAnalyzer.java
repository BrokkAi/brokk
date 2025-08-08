package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.cpp.NamespaceProcessor;
import io.github.jbellis.brokk.analyzer.cpp.SkeletonGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterCpp;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.Optional.*;

/**
 * C++ TreeSitter analyzer using the TreeSitter C parser.
 * Note: This uses the C grammar which can handle basic C++ constructs
 * but may not support advanced C++ features like templates, classes with complex inheritance, etc.
 * For full C++ support, a dedicated C++ grammar would be needed.
 */
public class CppTreeSitterAnalyzer extends TreeSitterAnalyzer {
    private static final Logger log = LogManager.getLogger(CppTreeSitterAnalyzer.class);

    // Specialized processors for complex operations
    private final SkeletonGenerator skeletonGenerator;
    private final NamespaceProcessor namespaceProcessor;

    // Cache for file content to avoid multiple reads
    private final Map<ProjectFile, String> fileContentCache = new ConcurrentHashMap<>();

    // Thread-local parser to avoid creation overhead
    private final ThreadLocal<TSParser> parserCache;

    private static Map<String, SkeletonType> createCaptureConfiguration() {
        var config = new HashMap<String, SkeletonType>();
        config.put("namespace.definition", SkeletonType.CLASS_LIKE);
        config.put("class.definition", SkeletonType.CLASS_LIKE);
        config.put("struct.definition", SkeletonType.CLASS_LIKE);
        config.put("union.definition", SkeletonType.CLASS_LIKE);
        config.put("enum.definition", SkeletonType.CLASS_LIKE);
        config.put("function.definition", SkeletonType.FUNCTION_LIKE);
        config.put("method.definition", SkeletonType.FUNCTION_LIKE);
        config.put("constructor.definition", SkeletonType.FUNCTION_LIKE);
        config.put("destructor.definition", SkeletonType.FUNCTION_LIKE);
        config.put("variable.definition", SkeletonType.FIELD_LIKE);
        config.put("field.definition", SkeletonType.FIELD_LIKE);
        config.put("typedef.definition", SkeletonType.FIELD_LIKE);
        config.put("using.definition", SkeletonType.FIELD_LIKE);
        config.put("access.specifier", SkeletonType.MODULE_STATEMENT);
        return config;
    }

    private static final LanguageSyntaxProfile CPP_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_specifier", "struct_specifier", "union_specifier", "enum_specifier", "namespace_definition"),
            Set.of("function_definition", "method_definition", "constructor_declaration", "destructor_declaration", "declaration"),
            Set.of("field_declaration", "parameter_declaration", "enumerator"),
            Set.of("attribute_specifier", "access_specifier"),
            "name",
            "body",
            "parameters",
            "type",
            "template_parameters",
            createCaptureConfiguration(),
            "",
            Set.of("storage_class_specifier", "type_qualifier", "access_specifier")
    );

    public CppTreeSitterAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Language.CPP_TREESITTER, excludedFiles);

        // Initialize parser cache
        this.parserCache = ThreadLocal.withInitial(() -> {
            var parser = new TSParser();
            parser.setLanguage(createTSLanguage());
            return parser;
        });

        // Initialize specialized processors
        var templateParser = parserCache.get();
        this.skeletonGenerator = new SkeletonGenerator(templateParser);
        this.namespaceProcessor = new NamespaceProcessor(templateParser);
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
    protected @Nullable CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        log.trace("createCodeUnit called: file={}, captureName={}, simpleName={}, packageName={}, classChain={}",
                 file, captureName, simpleName, packageName, classChain);

        final char delimiter = Optional.ofNullable(CPP_SYNTAX_PROFILE.captureConfiguration().get(captureName))
                .stream().anyMatch(x -> x.equals(SkeletonType.CLASS_LIKE)) ? '$' : '.';

        // Fix for C++ namespace-class parent-child relationship:
        // The base analyzer sometimes constructs classChain by incorrectly prepending packageName
        // to the parent's fqName, creating double-prefixed chains like "shapes.shapes" when the
        // namespace is "shapes". This prevents proper parent-child linking.
        String correctedClassChain = classChain;
        if (!packageName.isEmpty() && classChain.startsWith(packageName + ".")) {
            correctedClassChain = classChain.substring(packageName.length() + 1);
        }

        final String fqName = correctedClassChain.isEmpty() ? simpleName : correctedClassChain + delimiter + simpleName;

        var skeletonType = getSkeletonTypeForCapture(captureName);
        log.trace("createCodeUnit: captureName={} -> skeletonType={}", captureName, skeletonType);

        var type = switch (skeletonType) {
            case CLASS_LIKE -> {
                // Distinguish between namespaces and actual classes
                if ("namespace.definition".equals(captureName)) {
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

        var result = new CodeUnit(file, type, packageName, fqName);
        log.trace("createCodeUnit returning: {}", result);
        return result;
    }

    @Override
    protected String buildParentFqName(String packageName, String classChain) {
        // Apply the same correction logic used in createCodeUnit to ensure
        // parent lookup uses the correct FQName format for C++ namespace-class relationships.
        String correctedClassChain = classChain;
        if (!packageName.isEmpty() && classChain.equals(packageName)) {
            correctedClassChain = "";
        }

        return correctedClassChain.isEmpty() ? packageName
            : (packageName.isEmpty() ? correctedClassChain : packageName + "." + correctedClassChain);
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        var namespaceParts = new ArrayList<String>();

        var current = definitionNode;
        while (current != null && !current.isNull() && !current.equals(rootNode)) {
            var parent = current.getParent();
            if (parent == null || parent.isNull()) {
                break;
            }
            current = parent;

            if ("namespace_definition".equals(current.getType())) {
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
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        return signatureText + " {";
    }

    @Override
    protected String bodyPlaceholder() {
        return "{...}";
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportAndModifierPrefix, String asyncPrefix, String functionName, String typeParamsText, String paramsText, String returnTypeText, String indent) {
        log.trace("renderFunctionDeclaration: functionName={}, exportAndModifierPrefix={}, typeParamsText={}, paramsText={}, returnTypeText={}",
                 functionName, exportAndModifierPrefix, typeParamsText, paramsText, returnTypeText);

        var templateParams = typeParamsText.isEmpty() ? "" : typeParamsText + " ";
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        // The base analyzer provides an empty `paramsText` because it cannot find the nested
        // parameter list in the C++ AST. We must find it here.
        String actualParamsText = ""; // Default to empty string if not found
        TSNode declaratorNode = funcNode.getChildByFieldName("declarator");
        if (declaratorNode != null && "function_declarator".equals(declaratorNode.getType())) {
            TSNode paramsNode = declaratorNode.getChildByFieldName("parameters");
            if (paramsNode != null && !paramsNode.isNull()) {
                actualParamsText = ASTTraversalUtils.extractNodeText(paramsNode, src);
            }
        }

        // CRITICAL: Ensure we never return a blank signature by providing fallback
        if (functionName.isBlank()) {
            log.warn("renderFunctionDeclaration: functionName is blank, attempting to extract from AST node");
            // Try to extract the function name directly from the AST node
            TSNode fallbackDeclaratorNode = funcNode.getChildByFieldName("declarator");
            if (fallbackDeclaratorNode != null && "function_declarator".equals(fallbackDeclaratorNode.getType())) {
                TSNode innerDeclaratorNode = fallbackDeclaratorNode.getChildByFieldName("declarator");
                if (innerDeclaratorNode != null) {
                    String extractedName = ASTTraversalUtils.extractNodeText(innerDeclaratorNode, src);
                    if (!extractedName.isBlank()) {
                        functionName = extractedName;
                        log.debug("renderFunctionDeclaration: extracted function name '{}' from AST", functionName);
                    }
                }
            }

            // Last resort: use a placeholder to prevent blank signature
            if (functionName.isBlank()) {
                functionName = "<unknown_function>";
                log.warn("renderFunctionDeclaration: using placeholder function name");
            }
        }

        var signature = indent + exportAndModifierPrefix + templateParams + returnType + functionName + actualParamsText;

        var throwsNode = funcNode.getChildByFieldName("noexcept_specifier");
        if (throwsNode != null) {
            signature += " " + ASTTraversalUtils.extractNodeText(throwsNode, src);
        }

        // Check if function has a body and add placeholder
        TSNode bodyNode = funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        boolean hasBody = bodyNode != null && !bodyNode.isNull() && bodyNode.getEndByte() > bodyNode.getStartByte();

        if (hasBody) {
            signature += " " + bodyPlaceholder();
        }

        // Final safeguard: if signature is still blank, provide a minimal fallback
        if (signature.isBlank()) {
            signature = indent + "void " + functionName + "()";
            log.error("CRITICAL: Using emergency fallback signature for blank signature case");
        }

        log.trace("renderFunctionDeclaration returning signature: [{}]", signature);

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

    /**
     * Override getSkeletons for C++ to:
     * 1. Merge multiple namespace blocks with the same name
     * 2. Include global functions and variables from corresponding source files for header files
     */
    @Override
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        // Get base declarations
        var baseDeclarations = super.getDeclarationsInFile(file);

        // Get merged namespace CodeUnits from skeletons
        var mergedSkeletons = getSkeletons(file);

        // Replace namespace CodeUnits with merged ones, preserve others
        var result = new HashSet<CodeUnit>();
        var namespaceCodeUnits = new HashSet<CodeUnit>();

        // Collect merged namespace CodeUnits
        for (var cu : mergedSkeletons.keySet()) {
            if (cu.kind() == CodeUnitType.MODULE) {
                namespaceCodeUnits.add(cu);
            }
        }

        // Add non-namespace declarations from base
        for (var cu : baseDeclarations) {
            if (cu.kind() != CodeUnitType.MODULE) {
                result.add(cu);
            }
        }

        // Add merged namespace CodeUnits
        result.addAll(namespaceCodeUnits);

        return result;
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        log.trace("CppTreeSitterAnalyzer.getSkeletons() called for file: {}", file);
        try {
            // Start with the base skeletons from this file
            Map<CodeUnit, String> baseSkeletons = super.getSkeletons(file);
            Map<CodeUnit, String> resultSkeletons = new HashMap<>(baseSkeletons);
            log.trace("getSkeletons({}): Step 0 - Base skeletons: {} (sample keys: {})",
                     file, baseSkeletons.size(), getSampleKeys(baseSkeletons, 3));

            // 1. Fix global enum skeletons to include their content
            resultSkeletons = skeletonGenerator.fixGlobalEnumSkeletons(resultSkeletons, file);
            log.trace("getSkeletons({}): Step 1 - After enum fix: {} skeletons",
                     file, resultSkeletons.size());

            // 2. Fix global union skeletons to include their content
            resultSkeletons = skeletonGenerator.fixGlobalUnionSkeletons(resultSkeletons, file);
            log.trace("getSkeletons({}): Step 2 - After union fix: {} skeletons",
                     file, resultSkeletons.size());

            // 3. Merge namespace blocks with the same name
            resultSkeletons = namespaceProcessor.mergeNamespaceBlocks(resultSkeletons, signatures);
            log.trace("getSkeletons({}): Step 3 - After namespace merge: {} skeletons (sample keys: {})",
                     file, resultSkeletons.size(), getSampleKeys(resultSkeletons, 3));

            // 4. Remove individual nested declarations that are already included in merged namespaces
            resultSkeletons = namespaceProcessor.filterNestedDeclarations(resultSkeletons);
            log.trace("getSkeletons({}): Step 4 - After filtering nested: {} skeletons (sample keys: {})",
                     file, resultSkeletons.size(), getSampleKeys(resultSkeletons, 3));

            // 5. For header files, also include global functions and variables from corresponding source files
            if (isHeaderFile(file)) {
                resultSkeletons = addCorrespondingSourceDeclarations(resultSkeletons, file);
                log.trace("getSkeletons({}): Step 5 - After header source addition: {} skeletons",
                         file, resultSkeletons.size());
            }

            // 6. CRITICAL FIX: Canonicalize CodeUnit keys to match original instances in signatures map
            // This fixes the fundamental object identity issue that causes 0 skeletons
            resultSkeletons = canonicalizeSkeletonKeys(resultSkeletons);
            log.trace("getSkeletons({}): Step 6 - After key canonicalization: {} skeletons",
                     file, resultSkeletons.size());

            log.trace("getSkeletons({}): Final result: {} skeletons", file, resultSkeletons.size());
            return Collections.unmodifiableMap(resultSkeletons);
        } catch (Exception e) {
            log.error("Failed to generate skeletons for file {}: {}", file, e.getMessage(), e);
            // Return base skeletons as fallback
            return super.getSkeletons(file);
        }
    }

    /**
     * Adds global functions and variables from corresponding source files for header files.
     */
    private Map<CodeUnit, String> addCorrespondingSourceDeclarations(Map<CodeUnit, String> resultSkeletons, ProjectFile file) {
        var result = new HashMap<>(resultSkeletons);

        ProjectFile correspondingSource = findCorrespondingSourceFile(file);
        if (correspondingSource != null) {
            // Get CodeUnits from the source file
            List<CodeUnit> sourceCUs = getTopLevelDeclarations().getOrDefault(correspondingSource, List.of());

            // Add global functions and variables from the source file that aren't already in the header
            for (CodeUnit sourceCU : sourceCUs) {
                if (isGlobalFunctionOrVariable(sourceCU)) {
                    // Check if we already have this function/variable from the header
                    boolean alreadyExists = result.keySet().stream()
                        .anyMatch(headerCU -> headerCU.fqName().equals(sourceCU.fqName())
                                              && headerCU.kind() == sourceCU.kind());

                    if (!alreadyExists) {
                        // Add the global function/variable from source file
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
                ProjectFile candidateSource = new ProjectFile(headerFile.getRoot(), headerFile.getRoot().relativize(candidatePath));

                if (getTopLevelDeclarations().containsKey(candidateSource)) {
                    return candidateSource;
                }
            }
        }

        return null;
    }

    private boolean isGlobalFunctionOrVariable(CodeUnit cu) {
        // Global functions and variables have empty packageName and no class chain (no dots in fqName except for modules)
        return (cu.isFunction() || cu.isField())
               && cu.packageName().isEmpty()
               && !cu.fqName().contains(".");
    }


    /**
     * Clears caches to free memory. Should be called when analysis is complete.
     */
    public void clearCaches() {
        fileContentCache.clear();
        skeletonGenerator.clearCache();
        namespaceProcessor.clearCache();
    }

    @Override
    protected Optional<String> extractSimpleName(TSNode decl, String src) {
        log.trace("extractSimpleName called for node type: {}", decl.getType());

        // Handle anonymous namespaces specially - they have no name field
        if ("namespace_definition".equals(decl.getType())) {
            TSNode nameNode = decl.getChildByFieldName("name");
            if (nameNode == null || nameNode.isNull()) {
                // This is an anonymous namespace - provide a synthetic name
                log.trace("Found anonymous namespace at line {}, using synthetic name",
                         decl.getStartPoint().getRow() + 1);
                return Optional.of("(anonymous)");
            }
            // For named namespaces, extract the name normally
            String name = ASTTraversalUtils.extractNodeText(nameNode, src);
            log.trace("Extracted namespace name: {}", name);
            return Optional.of(name);
        }

        // Handle C++ function definitions with complex AST structure
        if ("function_definition".equals(decl.getType())) {
            log.trace("Processing function_definition node");
            TSNode declaratorNode = decl.getChildByFieldName("declarator");
            if (declaratorNode != null && "function_declarator".equals(declaratorNode.getType())) {
                TSNode innerDeclaratorNode = declaratorNode.getChildByFieldName("declarator");
                if (innerDeclaratorNode != null) {
                    String name = ASTTraversalUtils.extractNodeText(innerDeclaratorNode, src);
                    if (!name.isBlank()) {
                        log.trace("Extracted C++ function name: {}", name);
                        return Optional.of(name);
                    } else {
                        log.trace("Inner declarator node produced blank name");
                    }
                } else {
                    log.trace("No inner declarator node found in function_declarator");
                }
            } else {
                log.trace("No declarator node found or not function_declarator type. declaratorNode={}, type={}",
                         declaratorNode, declaratorNode != null ? declaratorNode.getType() : "null");
            }
        }

        // Handle other C++ constructs that might have complex naming structures
        if ("declaration".equals(decl.getType()) || "method_definition".equals(decl.getType()) ||
            "constructor_declaration".equals(decl.getType()) || "destructor_declaration".equals(decl.getType())) {
            log.trace("Processing {} node", decl.getType());
            // Try various C++ naming patterns
            TSNode declaratorNode = decl.getChildByFieldName("declarator");
            if (declaratorNode != null) {
                if ("function_declarator".equals(declaratorNode.getType())) {
                    TSNode innerDeclaratorNode = declaratorNode.getChildByFieldName("declarator");
                    if (innerDeclaratorNode != null) {
                        String name = ASTTraversalUtils.extractNodeText(innerDeclaratorNode, src);
                        if (!name.isBlank()) {
                            log.trace("Extracted complex name: {}", name);
                            return Optional.of(name);
                        }
                    }
                } else {
                    // Direct declarator
                    String name = ASTTraversalUtils.extractNodeText(declaratorNode, src);
                    if (!name.isBlank()) {
                        log.trace("Extracted direct declarator name: {}", name);
                        return Optional.of(name);
                    }
                }
            } else {
                log.trace("No declarator node found for {} node", decl.getType());
            }
        }

        // For all other node types, use the default implementation
        log.trace("Using default extractSimpleName for node type: {}", decl.getType());
        Optional<String> defaultName = super.extractSimpleName(decl, src);
        log.trace("Default extractSimpleName returned: {}", defaultName.orElse("empty"));
        return defaultName;
    }

    /**
     * CRITICAL FIX: Canonicalizes skeleton map keys to match original CodeUnit instances in signatures map.
     * This solves the fundamental object identity issue where processing steps create new CodeUnit objects
     * that are logically equivalent but not identical to the original keys in the signatures map.
     * When reconstructSkeletonRecursive() does signatures.get(cu), it fails due to object identity mismatch.
     */
    private Map<CodeUnit, String> canonicalizeSkeletonKeys(Map<CodeUnit, String> skeletons) {
        var canonicalSkeletons = new HashMap<CodeUnit, String>(skeletons.size());
        int canonicalizedCount = 0;
        int preservedCount = 0;

        for (var entry : skeletons.entrySet()) {
            CodeUnit cu = entry.getKey();
            String skeleton = entry.getValue();

            // Find the original CodeUnit instance in signatures map
            CodeUnit originalCU = findOriginalCodeUnit(cu);
            // Check if we found a different object instance (using reference equality intentionally)
            @SuppressWarnings("ReferenceEquality")
            boolean isDifferentInstance = (originalCU != null && originalCU != cu);
            if (isDifferentInstance) {
                // Use the original instance as the key
                canonicalSkeletons.put(originalCU, skeleton);
                canonicalizedCount++;
                log.trace("Canonicalized CodeUnit key: {} -> {} (type: {}, fqName: {})",
                         System.identityHashCode(cu), System.identityHashCode(originalCU),
                         cu.kind(), cu.fqName());
            } else {
                // Keep the current key (it's already canonical or no match found)
                canonicalSkeletons.put(cu, skeleton);
                preservedCount++;
                if (originalCU == null) {
                    log.warn("No original CodeUnit found in signatures map for: {} (type: {}, hashCode: {})",
                            cu.fqName(), cu.kind(), System.identityHashCode(cu));
                }
            }
        }

        log.trace("Key canonicalization complete: {} canonicalized, {} preserved, {} total",
                 canonicalizedCount, preservedCount, canonicalSkeletons.size());
        return canonicalSkeletons;
    }

    /**
     * Finds the original CodeUnit instance in the signatures map that matches the given CodeUnit logically.
     * First tries object identity (direct lookup), then falls back to logical equality.
     */
    private @Nullable CodeUnit findOriginalCodeUnit(CodeUnit cu) {
        // Fast path: direct lookup (object identity)
        if (signatures.containsKey(cu)) {
            return cu;
        }

        // Slow path: search by logical equality
        for (CodeUnit candidateCU : signatures.keySet()) {
            if (candidateCU.equals(cu)) {
                log.trace("Found original CodeUnit by logical equality: {} == {} (fqName: {}, type: {})",
                         System.identityHashCode(candidateCU), System.identityHashCode(cu),
                         cu.fqName(), cu.kind());
                return candidateCU;
            }
        }

        return null;
    }

    /**
     * Helper method to get sample keys from a skeleton map for debugging.
     */
    private String getSampleKeys(Map<CodeUnit, String> skeletons, int maxCount) {
        if (skeletons.isEmpty()) {
            return "[]";
        }
        return skeletons.keySet().stream()
            .limit(maxCount)
            .map(cu -> String.format("%s:%s(%s)", cu.kind(), cu.fqName(), cu.packageName()))
            .collect(Collectors.joining(", ", "[", skeletons.size() > maxCount ? "...]" : "]"));
    }

    /**
     * Gets cache statistics for monitoring.
     */
    public String getCacheStatistics() {
        return String.format("FileContent: %d, SkeletonGen: %d, NamespaceProc: %d",
                           fileContentCache.size(),
                           skeletonGenerator.getCacheSize(),
                           namespaceProcessor.getCacheSize());
    }
}