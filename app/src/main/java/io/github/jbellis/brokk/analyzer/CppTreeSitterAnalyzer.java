package io.github.jbellis.brokk.analyzer;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.IProject;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCpp;

import java.nio.file.Files;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

import static java.util.Optional.*;

/**
 * C++ TreeSitter analyzer using the TreeSitter C parser.
 * Note: This uses the C grammar which can handle basic C++ constructs
 * but may not support advanced C++ features like templates, classes with complex inheritance, etc.
 * For full C++ support, a dedicated C++ grammar would be needed.
 */
public class CppTreeSitterAnalyzer extends TreeSitterAnalyzer {

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
                    namespaceParts.add(textSlice(nameNode, src));
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
                actualParamsText = textSlice(paramsNode, src);
            }
        }

        var signature = indent + exportAndModifierPrefix + templateParams + returnType + functionName + actualParamsText;

        var throwsNode = funcNode.getChildByFieldName("noexcept_specifier");
        if (throwsNode != null) {
            signature += " " + textSlice(throwsNode, src);
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
        // Start with the base skeletons from this file
        Map<CodeUnit, String> baseSkeletons = super.getSkeletons(file);
        Map<CodeUnit, String> resultSkeletons = new HashMap<>(baseSkeletons);

        // 1. Fix global enum skeletons to include their content
        resultSkeletons = fixGlobalEnumSkeletons(resultSkeletons, file);

        // 2. Fix global union skeletons to include their content
        resultSkeletons = fixGlobalUnionSkeletons(resultSkeletons, file);

        // 3. Merge namespace blocks with the same name
        resultSkeletons = mergeNamespaceBlocks(resultSkeletons);

        // 4. Remove individual nested declarations that are already included in merged namespaces
        resultSkeletons = filterNestedDeclarations(resultSkeletons);

        // 5. For header files, also include global functions and variables from corresponding source files
        if (isHeaderFile(file)) {
            ProjectFile correspondingSource = findCorrespondingSourceFile(file);
            if (correspondingSource != null) {
                // Get CodeUnits from the source file
                List<CodeUnit> sourceCUs = getTopLevelDeclarations().getOrDefault(correspondingSource, List.of());

                // Add global functions and variables from the source file that aren't already in the header
                for (CodeUnit sourceCU : sourceCUs) {
                    if (isGlobalFunctionOrVariable(sourceCU)) {
                        // Check if we already have this function/variable from the header
                        boolean alreadyExists = resultSkeletons.keySet().stream()
                            .anyMatch(headerCU -> headerCU.fqName().equals(sourceCU.fqName())
                                                  && headerCU.kind() == sourceCU.kind());

                        if (!alreadyExists) {
                            // Add the global function/variable from source file
                            var sourceSkeletons = super.getSkeletons(correspondingSource);
                            String skeleton = sourceSkeletons.get(sourceCU);
                            if (skeleton != null) {
                                resultSkeletons.put(sourceCU, skeleton);
                            }
                        }
                    }
                }
            }
        }

        return Collections.unmodifiableMap(resultSkeletons);
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
     * Merges namespace blocks with the same name by directly parsing the AST to find all blocks.
     * Since CodeUnit equality causes Map overwrites, we need to go to the source.
     */
    private Map<CodeUnit, String> mergeNamespaceBlocks(Map<CodeUnit, String> skeletons) {
        // If we don't have any namespace skeletons, nothing to merge
        var namespaceEntries = skeletons.entrySet().stream()
            .filter(entry -> entry.getKey().kind() == CodeUnitType.MODULE)
            .toList();

        if (namespaceEntries.isEmpty()) {
            return skeletons;
        }

        // Since we already lost namespace blocks due to CodeUnit equality,
        // we need to re-parse the file to find all namespace blocks
        var result = new HashMap<>(skeletons);

        // For each file that has namespace skeletons, re-parse to get all namespace blocks
        var filesWithNamespaces = namespaceEntries.stream()
            .map(entry -> entry.getKey().source())
            .collect(Collectors.toSet());

        for (var file : filesWithNamespaces) {
            var mergedNamespaces = reParseAndMergeNamespaces(file);

            // Replace existing namespace skeletons with merged ones
            // Remove old namespace entries for this file
            result.entrySet().removeIf(entry ->
                entry.getKey().kind() == CodeUnitType.MODULE &&
                entry.getKey().source().equals(file));

            // Add merged namespace skeletons
            result.putAll(mergedNamespaces);
        }

        return result;
    }

    /**
     * Re-parses a file to find all namespace blocks and merge them by name.
     * This bypasses the CodeUnit equality issue by working directly with the AST.
     */
    private Map<CodeUnit, String> reParseAndMergeNamespaces(ProjectFile file) {
        try {
            var fileContent = readFileContent(file);
            var parser = createParser();
            var tree = parser.parseString(null, fileContent);
            var rootNode = tree.getRootNode();

            // Find all namespace_definition nodes
            var namespaceBlocks = findAllNamespaceBlocks(rootNode, fileContent);

            // Group by namespace name
            var groupedNamespaces = new HashMap<String, List<NamespaceBlock>>();
            for (var block : namespaceBlocks) {
                groupedNamespaces.computeIfAbsent(block.name, k -> new ArrayList<>()).add(block);
            }

            // Create merged CodeUnits and skeletons
            var result = new HashMap<CodeUnit, String>();
            for (var entry : groupedNamespaces.entrySet()) {
                var namespaceName = entry.getKey();
                var blocks = entry.getValue();

                // Create a representative CodeUnit for this namespace
                var codeUnit = new CodeUnit(file, CodeUnitType.MODULE, "", namespaceName);

                // Merge all blocks into one skeleton
                var mergedSkeleton = createMergedNamespaceSkeleton(namespaceName, blocks, fileContent);
                result.put(codeUnit, mergedSkeleton);

            }

            return result;
        } catch (Exception e) {
            log.error("Failed to re-parse namespaces for file {}: {}", file, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Fixes global enum skeletons to include their enum values instead of empty braces.
     * The base TreeSitter analyzer generates empty enum skeletons, but we need to extract
     * the actual enum content from the AST.
     */
    private Map<CodeUnit, String> fixGlobalEnumSkeletons(Map<CodeUnit, String> skeletons, ProjectFile file) {
        var result = new HashMap<>(skeletons);

        try {
            var fileContent = readFileContent(file);
            var parser = createParser();
            var tree = parser.parseString(null, fileContent);
            var rootNode = tree.getRootNode();

            // Find all global enum CodeUnits that need fixing
            var enumsToFix = skeletons.entrySet().stream()
                .filter(entry -> entry.getKey().kind() == CodeUnitType.CLASS)
                .filter(entry -> entry.getKey().packageName().isEmpty()) // global scope only
                .filter(entry -> entry.getValue().startsWith("enum ") && entry.getValue().contains("{\n}"))
                .collect(Collectors.toList());

            for (var enumEntry : enumsToFix) {
                var enumCodeUnit = enumEntry.getKey();
                var enumName = enumCodeUnit.fqName();

                // Find the enum_specifier node in the AST
                var enumNode = findGlobalEnumNode(rootNode, enumName, fileContent);
                if (enumNode != null) {
                    // Extract enum content with only names (no values)
                    var enumSkeleton = extractEnumSkeleton(enumNode, enumName, fileContent);
                    if (!enumSkeleton.isEmpty()) {
                        result.put(enumCodeUnit, enumSkeleton);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fix global enum skeletons for file {}: {}", file, e.getMessage());
        }

        return result;
    }

    /**
     * Fixes global union skeletons to include their field content instead of incomplete skeletons.
     * The base TreeSitter analyzer generates incomplete union skeletons, but we need to extract
     * the actual union content from the AST.
     */
    private Map<CodeUnit, String> fixGlobalUnionSkeletons(Map<CodeUnit, String> skeletons, ProjectFile file) {
        var result = new HashMap<>(skeletons);

        try {
            var fileContent = readFileContent(file);
            var parser = createParser();
            var tree = parser.parseString(null, fileContent);
            var rootNode = tree.getRootNode();

            // Find all global union CodeUnits that need fixing
            var unionsToFix = skeletons.entrySet().stream()
                .filter(entry -> entry.getKey().kind() == CodeUnitType.CLASS)
                .filter(entry -> entry.getKey().packageName().isEmpty()) // global scope only
                .filter(entry -> entry.getValue().startsWith("union ") && !entry.getValue().contains("char*"))
                .collect(Collectors.toList());

            for (var unionEntry : unionsToFix) {
                var unionCodeUnit = unionEntry.getKey();
                var unionName = unionCodeUnit.fqName();

                // Find the union_specifier node in the AST
                var unionNode = findGlobalUnionNode(rootNode, unionName, fileContent);
                if (unionNode != null) {
                    // Extract full union content
                    var unionSkeleton = extractUnionSkeleton(unionNode, unionName, fileContent);
                    if (!unionSkeleton.isEmpty()) {
                        result.put(unionCodeUnit, unionSkeleton);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fix global union skeletons for file {}: {}", file, e.getMessage());
        }

        return result;
    }

    /**
     * Finds a global union node with the given name in the AST.
     */
    private @Nullable TSNode findGlobalUnionNode(TSNode rootNode, String unionName, String fileContent) {
        return findGlobalUnionNodeRecursive(rootNode, unionName, fileContent);
    }

    private @Nullable TSNode findGlobalUnionNodeRecursive(TSNode node, String unionName, String fileContent) {
        if ("union_specifier".equals(node.getType())) {
            var nameNode = node.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
                var nodeUnionName = textSlice(nameNode, fileContent).trim();
                if (unionName.equals(nodeUnionName)) {
                    return node;
                }
            }
        }

        // Recursively search children
        for (int i = 0; i < node.getChildCount(); i++) {
            var child = node.getChild(i);
            if (child != null && !child.isNull()) {
                var result = findGlobalUnionNodeRecursive(child, unionName, fileContent);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * Extracts union skeleton with all field declarations.
     */
    private String extractUnionSkeleton(TSNode unionNode, String unionName, String fileContent) {
        var skeleton = new StringBuilder();
        skeleton.append("union ").append(unionName).append(" {\n");

        // Find the field_declaration_list (union body)
        var bodyNode = unionNode.getChildByFieldName("body");
        if (bodyNode != null && !bodyNode.isNull()) {
            // Extract each field declaration
            for (int i = 0; i < bodyNode.getChildCount(); i++) {
                var child = bodyNode.getChild(i);
                if (child != null && !child.isNull() && "field_declaration".equals(child.getType())) {
                    // Get the full field declaration text
                    var fieldText = textSlice(child, fileContent).trim();
                    if (!fieldText.isEmpty()) {
                        skeleton.append("    ").append(fieldText);
                        if (!fieldText.endsWith(";")) {
                            skeleton.append(";");
                        }
                        skeleton.append("\n");
                    }
                }
            }
        }

        skeleton.append("}");
        return skeleton.toString();
    }

    /**
     * Finds a global enum node with the given name in the AST.
     */
    private @Nullable TSNode findGlobalEnumNode(TSNode rootNode, String enumName, String fileContent) {
        return findGlobalEnumNodeRecursive(rootNode, enumName, fileContent);
    }

    private @Nullable TSNode findGlobalEnumNodeRecursive(TSNode node, String enumName, String fileContent) {
        if ("enum_specifier".equals(node.getType())) {
            var nameNode = node.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
                var nodeEnumName = textSlice(nameNode, fileContent).trim();
                if (enumName.equals(nodeEnumName)) {
                    return node;
                }
            }
        }

        // Recursively search children
        for (int i = 0; i < node.getChildCount(); i++) {
            var child = node.getChild(i);
            if (child != null && !child.isNull()) {
                var result = findGlobalEnumNodeRecursive(child, enumName, fileContent);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * Extracts enum skeleton with only enum value names (no assigned values).
     */
    private String extractEnumSkeleton(TSNode enumNode, String enumName, String fileContent) {
        var skeleton = new StringBuilder();
        skeleton.append("enum ").append(enumName).append(" {\n");

        // Find the enumerator_list (enum body)
        var bodyNode = enumNode.getChildByFieldName("body");
        if (bodyNode != null && !bodyNode.isNull()) {
            var enumValues = new ArrayList<String>();

            // Extract each enumerator name
            for (int i = 0; i < bodyNode.getChildCount(); i++) {
                var child = bodyNode.getChild(i);
                if (child != null && !child.isNull() && "enumerator".equals(child.getType())) {
                    // Get the name of the enumerator (before any '=' assignment)
                    var nameNode = child.getChildByFieldName("name");
                    if (nameNode != null && !nameNode.isNull()) {
                        var enumValueName = textSlice(nameNode, fileContent).trim();
                        if (!enumValueName.isEmpty()) {
                            enumValues.add(enumValueName);
                        }
                    }
                }
            }

            // Add enum values with proper indentation
            for (int i = 0; i < enumValues.size(); i++) {
                skeleton.append("    ").append(enumValues.get(i));
                if (i < enumValues.size() - 1) {
                    skeleton.append(",");
                }
                skeleton.append("\n");
            }
        }

        skeleton.append("}");
        return skeleton.toString();
    }

    /**
     * Extracts enum skeleton from an enum node, showing only names without values.
     */
    private String extractEnumSkeletonFromNode(TSNode enumNode, String fileContent) {
        // Get enum name
        var nameNode = enumNode.getChildByFieldName("name");
        String enumName = "";
        if (nameNode != null && !nameNode.isNull()) {
            enumName = textSlice(nameNode, fileContent).trim();
        }

        // Determine if it's a scoped enum (enum class)
        boolean isScopedEnum = false;
        var enumText = textSlice(enumNode, fileContent).trim();
        if (enumText.startsWith("enum class")) {
            isScopedEnum = true;
        }

        var skeleton = new StringBuilder();
        if (isScopedEnum) {
            skeleton.append("enum class ").append(enumName).append(" {\n");
        } else {
            skeleton.append("enum ").append(enumName).append(" {\n");
        }

        // Find the enumerator_list (enum body)
        var bodyNode = enumNode.getChildByFieldName("body");
        if (bodyNode != null && !bodyNode.isNull()) {
            var enumValues = new ArrayList<String>();

            // Extract each enumerator name
            for (int i = 0; i < bodyNode.getChildCount(); i++) {
                var child = bodyNode.getChild(i);
                if (child != null && !child.isNull() && "enumerator".equals(child.getType())) {
                    // Get the name of the enumerator (before any '=' assignment)
                    var enumeratorNameNode = child.getChildByFieldName("name");
                    if (enumeratorNameNode != null && !enumeratorNameNode.isNull()) {
                        var enumValueName = textSlice(enumeratorNameNode, fileContent).trim();
                        if (!enumValueName.isEmpty()) {
                            enumValues.add(enumValueName);
                        }
                    }
                }
            }

            // Add enum values with proper indentation
            for (int i = 0; i < enumValues.size(); i++) {
                skeleton.append("        ").append(enumValues.get(i));
                if (i < enumValues.size() - 1) {
                    skeleton.append(",");
                }
                skeleton.append("\n");
            }
        }

        skeleton.append("    }");
        return skeleton.toString();
    }

    /**
     * Filters out individual nested declarations that are already included in merged namespace skeletons.
     * This prevents duplication of CodeUnits like 'ui::widgets.ui::widgets$WidgetType' when they're
     * already included in the 'ui::widgets' namespace skeleton.
     */
    private Map<CodeUnit, String> filterNestedDeclarations(Map<CodeUnit, String> skeletons) {
        // Get all namespace names that have merged skeletons
        var namespaceNames = skeletons.entrySet().stream()
            .filter(entry -> entry.getKey().kind() == CodeUnitType.MODULE)
            .map(entry -> entry.getKey().fqName())
            .collect(Collectors.toSet());

        // Filter out CodeUnits that represent individual nested declarations within these namespaces
        var filtered = skeletons.entrySet().stream()
            .filter(entry -> {
                var codeUnit = entry.getKey();
                // Keep namespace CodeUnits
                if (codeUnit.kind() == CodeUnitType.MODULE) {
                    return true;
                }
                // Keep global declarations (no package name)
                if (codeUnit.packageName().isEmpty()) {
                    return true;
                }
                // Remove nested declarations if their namespace has a merged skeleton
                // Example: remove 'ui::widgets.ui::widgets$WidgetType' if 'ui::widgets' namespace exists
                return !namespaceNames.contains(codeUnit.packageName());
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return filtered;
    }

    /**
     * Represents a namespace block found in the AST
     */
    private record NamespaceBlock(String name, TSNode node, int startByte, int endByte) {}

    /**
     * Finds all namespace_definition nodes in the AST
     */
    private List<NamespaceBlock> findAllNamespaceBlocks(TSNode rootNode, String fileContent) {
        var namespaceBlocks = new ArrayList<NamespaceBlock>();
        findNamespaceBlocksRecursive(rootNode, fileContent, namespaceBlocks);
        return namespaceBlocks;
    }

    private void findNamespaceBlocksRecursive(TSNode node, String fileContent, List<NamespaceBlock> blocks) {
        if ("namespace_definition".equals(node.getType())) {
            // Extract namespace name
            var nameNode = node.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
                var namespaceName = textSlice(nameNode, fileContent);
                blocks.add(new NamespaceBlock(namespaceName, node, node.getStartByte(), node.getEndByte()));
            }
        }

        // Recursively search children
        for (int i = 0; i < node.getChildCount(); i++) {
            var child = node.getChild(i);
            if (child != null && !child.isNull()) {
                findNamespaceBlocksRecursive(child, fileContent, blocks);
            }
        }
    }

    /**
     * Creates a merged skeleton from multiple namespace blocks
     */
    private String createMergedNamespaceSkeleton(String namespaceName, List<NamespaceBlock> blocks, String fileContent) {
        var mergedContent = new StringBuilder();
        mergedContent.append("namespace ").append(namespaceName).append(" {\n");

        for (var block : blocks) {
            // Process the body content to extract skeleton signatures
            var bodyNode = block.node.getChildByFieldName("body");
            if (bodyNode != null && !bodyNode.isNull()) {
                var skeletonContent = extractNamespaceBodySkeletons(bodyNode, fileContent);
                for (String line : skeletonContent) {
                    mergedContent.append("  ").append(line).append("\n");
                }
            }
        }

        mergedContent.append("}");
        return mergedContent.toString();
    }

    /**
     * Extracts skeleton-style content from a namespace body (signatures only, not implementations)
     */
    private List<String> extractNamespaceBodySkeletons(TSNode bodyNode, String fileContent) {
        var skeletons = new ArrayList<String>();

        // Process each child in the namespace body
        for (int i = 0; i < bodyNode.getChildCount(); i++) {
            var child = bodyNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            // Handle function definitions - extract signature only
            if ("function_definition".equals(childType)) {
                var signature = extractFunctionSignature(child, fileContent);
                if (!signature.trim().isEmpty()) {
                    skeletons.add(signature + "  {...}");
                }
            }
            // Handle enum declarations - extract only names, not values
            else if ("enum_specifier".equals(childType)) {
                var enumSkeleton = extractEnumSkeletonFromNode(child, fileContent);
                if (!enumSkeleton.isEmpty()) {
                    skeletons.add(enumSkeleton);
                }
            }
            // Handle class declarations
            else if ("class_specifier".equals(childType) || "struct_specifier".equals(childType)) {
                var classDecl = textSlice(child, fileContent).trim();
                if (!classDecl.isEmpty()) {
                    skeletons.add(classDecl);
                }
            }
            // Handle union declarations
            else if ("union_specifier".equals(childType)) {
                var unionDecl = textSlice(child, fileContent).trim();
                if (!unionDecl.isEmpty()) {
                    skeletons.add(unionDecl);
                }
            }
            // Handle type aliases and typedefs
            else if ("type_definition".equals(childType) || "alias_declaration".equals(childType)) {
                var typeDecl = textSlice(child, fileContent).trim();
                if (!typeDecl.isEmpty()) {
                    skeletons.add(typeDecl);
                }
            }
            // Handle other declarations (fields, etc.)
            else if ("declaration".equals(childType) ||
                     "field_declaration".equals(childType) ||
                     "using_declaration".equals(childType) ||
                     "typedef_declaration".equals(childType)) {
                var declaration = textSlice(child, fileContent).trim();
                if (!declaration.isEmpty()) {
                    // Ensure it ends with semicolon if it's a declaration
                    if (!declaration.endsWith(";") && !declaration.endsWith("}")) {
                        declaration += ";";
                    }
                    skeletons.add(declaration);
                }
            }
        }
        return skeletons;
    }

    /**
     * Extracts just the function signature without the body
     */
    private String extractFunctionSignature(TSNode funcNode, String fileContent) {
        // Find the declarator to get the function signature
        var declarator = funcNode.getChildByFieldName("declarator");
        if (declarator == null || declarator.isNull()) {
            return "";
        }

        // Get return type if present
        String returnType = "";
        var typeNode = funcNode.getChildByFieldName("type");
        if (typeNode != null && !typeNode.isNull()) {
            returnType = textSlice(typeNode, fileContent).trim() + " ";
        }

        // Get the function declarator (name and parameters)
        String signature = returnType + textSlice(declarator, fileContent).trim();

        // Handle any trailing specifiers (const, noexcept, etc.)
        for (int i = 0; i < funcNode.getChildCount(); i++) {
            var child = funcNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            if ("noexcept_specifier".equals(childType) ||
                "trailing_return_type".equals(childType) ||
                "virtual_specifier".equals(childType)) {
                signature += " " + textSlice(child, fileContent).trim();
            }
        }

        return signature;
    }

    /**
     * Reads the content of a file
     */
    private String readFileContent(ProjectFile file) throws Exception {
        return Files.readString(file.absPath());
    }

    /**
     * Creates a TreeSitter parser for C++
     */
    private org.treesitter.TSParser createParser() {
        var parser = new org.treesitter.TSParser();
        parser.setLanguage(getTSLanguage());
        return parser;
    }

}