package io.github.jbellis.brokk.analyzer;

import static io.github.jbellis.brokk.analyzer.cpp.CppTreeSitterNodeTypes.*;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.cpp.NamespaceProcessor;
import io.github.jbellis.brokk.analyzer.cpp.SkeletonGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCpp;

public class CppTreeSitterAnalyzer extends TreeSitterAnalyzer {
    private static final Logger log = LogManager.getLogger(CppTreeSitterAnalyzer.class);

    @Override
    public Optional<String> extractClassName(String reference) {
        return ClassNameExtractor.extractForCpp(reference);
    }

    private final SkeletonGenerator skeletonGenerator;
    private final NamespaceProcessor namespaceProcessor;
    private final Map<ProjectFile, String> fileContentCache = new ConcurrentHashMap<>();
    private final ThreadLocal<TSParser> parserCache;
    private final Map<CodeUnitKey, CodeUnit> codeUnitRegistry = new ConcurrentHashMap<>();

    /** Key for CodeUnit registry to ensure unique instances for logically identical CodeUnits. */
    public record CodeUnitKey(ProjectFile source, CodeUnitType kind, String packageName, String fqName) {}

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
            Set.of(CLASS_SPECIFIER, STRUCT_SPECIFIER, UNION_SPECIFIER, ENUM_SPECIFIER, NAMESPACE_DEFINITION),
            Set.of(
                    FUNCTION_DEFINITION,
                    METHOD_DEFINITION,
                    CONSTRUCTOR_DECLARATION,
                    DESTRUCTOR_DECLARATION,
                    DECLARATION),
            Set.of(FIELD_DECLARATION, PARAMETER_DECLARATION, ENUMERATOR),
            Set.of(ATTRIBUTE_SPECIFIER, ACCESS_SPECIFIER),
            "name",
            "body",
            "parameters",
            "type",
            "template_parameters",
            createCaptureConfiguration(),
            "",
            Set.of(STORAGE_CLASS_SPECIFIER, TYPE_QUALIFIER, ACCESS_SPECIFIER));

    public CppTreeSitterAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Languages.CPP_TREESITTER, excludedFiles);

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

        final String fqName = correctedClassChain.isEmpty() ? simpleName : correctedClassChain + delimiter + simpleName;

        var skeletonType = getSkeletonTypeForCapture(captureName);

        var type =
                switch (skeletonType) {
                    case CLASS_LIKE -> {
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

        return getOrCreateCodeUnit(file, type, packageName, fqName);
    }

    @Override
    protected String buildParentFqName(String packageName, String classChain) {
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
        while (current != null && !current.isNull() && !current.equals(rootNode)) {
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
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        var baseDeclarations = super.getDeclarationsInFile(file);
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
            TSTree tree = getCachedTree(file);
            if (tree == null) {
                // Fallback: parse the file if tree is not cached (should rarely happen)
                log.warn("Tree not found in cache for {}. Parsing on-demand - this may indicate a bug.", file);
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
                        namespaceName -> getOrCreateCodeUnit(file, CodeUnitType.MODULE, "", namespaceName));
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
     * Factory method to get or create a CodeUnit instance, ensuring object identity. This prevents duplicate CodeUnit
     * instances for the same logical entity.
     */
    public CodeUnit getOrCreateCodeUnit(ProjectFile source, CodeUnitType kind, String packageName, String fqName) {
        var registry = getCodeUnitRegistry();
        var key = new CodeUnitKey(source, kind, packageName, fqName);
        return registry.computeIfAbsent(key, k -> new CodeUnit(source, kind, packageName, fqName));
    }

    /**
     * Get the code unit registry, initializing it if necessary. This provides thread-safe lazy initialization as a
     * fallback.
     */
    @SuppressWarnings("RedundantNullCheck")
    private Map<CodeUnitKey, CodeUnit> getCodeUnitRegistry() {
        if (codeUnitRegistry != null) {
            return codeUnitRegistry;
        }

        // This should never happen with field initialization, but provide a fallback
        log.warn("CodeUnit registry was null, creating emergency fallback registry");
        synchronized (this) {
            if (codeUnitRegistry == null) {
                // Use reflection to set the field since it's final
                try {
                    var field = CppTreeSitterAnalyzer.class.getDeclaredField("codeUnitRegistry");
                    field.setAccessible(true);
                    field.set(this, new ConcurrentHashMap<CodeUnitKey, CodeUnit>());
                    log.warn("Emergency registry initialization completed");
                } catch (Exception e) {
                    log.error("Failed to initialize emergency registry", e);
                    // Return a temporary map as last resort
                    return new ConcurrentHashMap<>();
                }
            }
        }
        return codeUnitRegistry;
    }

    @Override
    @SuppressWarnings("RedundantNullCheck")
    public void clearCaches() {
        super.clearCaches(); // Clear cached trees to free memory
        fileContentCache.clear();
        skeletonGenerator.clearCache();
        namespaceProcessor.clearCache();
        if (codeUnitRegistry != null) {
            codeUnitRegistry.clear();
        }
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

    public String getCacheStatistics() {
        return String.format(
                "FileContent: %d, ParsedTrees: %d, SkeletonGen: %d, NamespaceProc: %d",
                fileContentCache.size(),
                super.cacheSize(),
                skeletonGenerator.getCacheSize(),
                namespaceProcessor.getCacheSize());
    }
}
