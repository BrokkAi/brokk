package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
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
                log.warn("Unhandled CodeUnitType for '{}' in C++", skeletonType);
                yield CodeUnitType.CLASS;
            }
        };

        return new CodeUnit(file, type, packageName, fqName);
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
        try {
            // Start with the base skeletons from this file
            Map<CodeUnit, String> baseSkeletons = super.getSkeletons(file);
            Map<CodeUnit, String> resultSkeletons = new HashMap<>(baseSkeletons);
            log.debug("getSkeletons({}): Step 0 - Base skeletons: {} (sample keys: {})",
                     file, baseSkeletons.size(), getSampleKeys(baseSkeletons, 3));

            // 1. Fix global enum skeletons to include their content
            resultSkeletons = skeletonGenerator.fixGlobalEnumSkeletons(resultSkeletons, file);
            log.debug("getSkeletons({}): Step 1 - After enum fix: {} skeletons",
                     file, resultSkeletons.size());

            // 2. Fix global union skeletons to include their content
            resultSkeletons = skeletonGenerator.fixGlobalUnionSkeletons(resultSkeletons, file);
            log.debug("getSkeletons({}): Step 2 - After union fix: {} skeletons",
                     file, resultSkeletons.size());

            // 3. Merge namespace blocks with the same name
            resultSkeletons = namespaceProcessor.mergeNamespaceBlocks(resultSkeletons);
            log.debug("getSkeletons({}): Step 3 - After namespace merge: {} skeletons (sample keys: {})",
                     file, resultSkeletons.size(), getSampleKeys(resultSkeletons, 3));

            // 4. Remove individual nested declarations that are already included in merged namespaces
            resultSkeletons = namespaceProcessor.filterNestedDeclarations(resultSkeletons);
            log.debug("getSkeletons({}): Step 4 - After filtering nested: {} skeletons (sample keys: {})",
                     file, resultSkeletons.size(), getSampleKeys(resultSkeletons, 3));

            // 5. For header files, also include global functions and variables from corresponding source files
            if (isHeaderFile(file)) {
                resultSkeletons = addCorrespondingSourceDeclarations(resultSkeletons, file);
                log.debug("getSkeletons({}): Step 5 - After header source addition: {} skeletons",
                         file, resultSkeletons.size());
            }

            log.debug("getSkeletons({}): Final result: {} skeletons", file, resultSkeletons.size());
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
            return Optional.of(ASTTraversalUtils.extractNodeText(nameNode, src));
        }

        // For all other node types, use the default implementation
        return super.extractSimpleName(decl, src);
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