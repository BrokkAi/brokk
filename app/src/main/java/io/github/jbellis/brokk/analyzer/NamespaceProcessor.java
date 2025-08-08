package io.github.jbellis.brokk.analyzer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;
import org.treesitter.TSParser;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Handles C++ namespace processing including merging multiple namespace blocks
 * with the same name and generating combined namespace skeletons.
 */
public class NamespaceProcessor {
    private static final Logger log = LogManager.getLogger(NamespaceProcessor.class);

    // Cache for file content to avoid multiple reads
    private final Map<ProjectFile, String> fileContentCache = new ConcurrentHashMap<>();

    // Thread-local parser to avoid creation overhead
    private final ThreadLocal<TSParser> parserCache;

    public NamespaceProcessor(TSParser templateParser) {
        this.parserCache = ThreadLocal.withInitial(() -> {
            var parser = new TSParser();
            parser.setLanguage(templateParser.getLanguage());
            return parser;
        });
    }

    /**
     * Represents a namespace block found in the AST
     */
    public record NamespaceBlock(String name, TSNode node, int startByte, int endByte) {}

    /**
     * Merges namespace blocks with the same name by directly parsing the AST to find all blocks.
     * Since CodeUnit equality causes Map overwrites, we need to go to the source.
     */
    public Map<CodeUnit, String> mergeNamespaceBlocks(Map<CodeUnit, String> skeletons) {
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
            try {
                var mergedNamespaces = reParseAndMergeNamespaces(file);

                // Replace existing namespace skeletons with merged ones
                // Remove old namespace entries for this file
                result.entrySet().removeIf(entry ->
                    entry.getKey().kind() == CodeUnitType.MODULE &&
                    entry.getKey().source().equals(file));

                // Add merged namespace skeletons
                result.putAll(mergedNamespaces);
            } catch (Exception e) {
                log.error("Failed to merge namespaces for file {}: {}", file, e.getMessage(), e);
            }
        }

        return result;
    }

    /**
     * Re-parses a file to find all namespace blocks and merge them by name.
     * This bypasses the CodeUnit equality issue by working directly with the AST.
     */
    private Map<CodeUnit, String> reParseAndMergeNamespaces(ProjectFile file) throws IOException {
        var fileContent = getCachedFileContent(file);
        var parser = parserCache.get();
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
    }

    /**
     * Finds all namespace_definition nodes in the AST
     */
    private List<NamespaceBlock> findAllNamespaceBlocks(TSNode rootNode, String fileContent) {
        var namespaceNodes = ASTTraversalUtils.findAllNodesByType(rootNode, "namespace_definition");
        var namespaceBlocks = new ArrayList<NamespaceBlock>();

        for (var node : namespaceNodes) {
            // Extract namespace name
            var nameNode = node.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
                var namespaceName = ASTTraversalUtils.extractNodeText(nameNode, fileContent);
                namespaceBlocks.add(new NamespaceBlock(namespaceName, node, node.getStartByte(), node.getEndByte()));
            }
        }

        return namespaceBlocks;
    }

    /**
     * Creates a merged skeleton from multiple namespace blocks
     */
    private String createMergedNamespaceSkeleton(String namespaceName, List<NamespaceBlock> blocks, String fileContent) {
        var mergedContent = new StringBuilder(512); // Pre-size for better performance
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
                var classDecl = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!classDecl.isEmpty()) {
                    skeletons.add(classDecl);
                }
            }
            // Handle union declarations
            else if ("union_specifier".equals(childType)) {
                var unionDecl = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!unionDecl.isEmpty()) {
                    skeletons.add(unionDecl);
                }
            }
            // Handle type aliases and typedefs
            else if ("type_definition".equals(childType) || "alias_declaration".equals(childType)) {
                var typeDecl = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!typeDecl.isEmpty()) {
                    skeletons.add(typeDecl);
                }
            }
            // Handle other declarations (fields, etc.)
            else if ("declaration".equals(childType) ||
                     "field_declaration".equals(childType) ||
                     "using_declaration".equals(childType) ||
                     "typedef_declaration".equals(childType)) {
                var declaration = ASTTraversalUtils.extractNodeText(child, fileContent);
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
     * Extracts enum skeleton from an enum node, showing only names without values.
     */
    private String extractEnumSkeletonFromNode(TSNode enumNode, String fileContent) {
        // Get enum name
        var nameNode = enumNode.getChildByFieldName("name");
        String enumName = "";
        if (nameNode != null && !nameNode.isNull()) {
            enumName = ASTTraversalUtils.extractNodeText(nameNode, fileContent);
        }

        // Determine if it's a scoped enum (enum class)
        boolean isScopedEnum = false;
        var enumText = ASTTraversalUtils.extractNodeText(enumNode, fileContent);
        if (enumText.startsWith("enum class")) {
            isScopedEnum = true;
        }

        var skeleton = new StringBuilder(256);
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
                        var enumValueName = ASTTraversalUtils.extractNodeText(enumeratorNameNode, fileContent);
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
            returnType = ASTTraversalUtils.extractNodeText(typeNode, fileContent) + " ";
        }

        // Get the function declarator (name and parameters)
        String signature = returnType + ASTTraversalUtils.extractNodeText(declarator, fileContent);

        // Handle any trailing specifiers (const, noexcept, etc.)
        for (int i = 0; i < funcNode.getChildCount(); i++) {
            var child = funcNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            if ("noexcept_specifier".equals(childType) ||
                "trailing_return_type".equals(childType) ||
                "virtual_specifier".equals(childType)) {
                signature += " " + ASTTraversalUtils.extractNodeText(child, fileContent);
            }
        }

        return signature;
    }

    /**
     * Filters out individual nested declarations that are already included in merged namespace skeletons.
     * This prevents duplication of CodeUnits like 'ui::widgets.ui::widgets$WidgetType' when they're
     * already included in the 'ui::widgets' namespace skeleton.
     */
    public Map<CodeUnit, String> filterNestedDeclarations(Map<CodeUnit, String> skeletons) {
        // Get all namespace names that have merged skeletons
        var namespaceNames = skeletons.entrySet().stream()
            .filter(entry -> entry.getKey().kind() == CodeUnitType.MODULE)
            .map(entry -> entry.getKey().fqName())
            .collect(Collectors.toSet());

        // Filter out CodeUnits that represent individual nested declarations within these namespaces
        return skeletons.entrySet().stream()
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
    }

    /**
     * Gets cached file content or reads and caches it if not present.
     */
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

    /**
     * Clears the file content cache to free memory.
     * Should be called periodically or when analysis is complete.
     */
    public void clearCache() {
        fileContentCache.clear();
    }

    /**
     * Gets the size of the file content cache for monitoring.
     */
    public int getCacheSize() {
        return fileContentCache.size();
    }
}