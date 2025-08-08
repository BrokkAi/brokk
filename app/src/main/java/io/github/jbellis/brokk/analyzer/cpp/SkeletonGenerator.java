package io.github.jbellis.brokk.analyzer.cpp;

import io.github.jbellis.brokk.analyzer.*;
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
 * Handles skeleton generation for C++ constructs like enums, unions, and classes.
 * Centralizes complex skeleton generation logic to improve maintainability and performance.
 */
public class SkeletonGenerator {
    private static final Logger log = LogManager.getLogger(SkeletonGenerator.class);

    // Cache for file content to avoid multiple reads
    private final Map<ProjectFile, String> fileContentCache = new ConcurrentHashMap<>();

    // Thread-local parser to avoid creation overhead
    private final ThreadLocal<TSParser> parserCache;

    public SkeletonGenerator(TSParser templateParser) {
        this.parserCache = ThreadLocal.withInitial(() -> {
            var parser = new TSParser();
            parser.setLanguage(templateParser.getLanguage());
            return parser;
        });
    }

    /**
     * Fixes global enum skeletons to include their enum values instead of empty braces.
     */
    public Map<CodeUnit, String> fixGlobalEnumSkeletons(Map<CodeUnit, String> skeletons, ProjectFile file) {
        var result = new HashMap<>(skeletons);

        try {
            var fileContent = getCachedFileContent(file);
            var parser = parserCache.get();
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
                var enumNode = ASTTraversalUtils.findNodeByTypeAndName(rootNode, "enum_specifier", enumName, fileContent);
                if (enumNode != null) {
                    // Extract enum content with only names (no values)
                    var enumSkeleton = extractEnumSkeleton(enumNode, enumName, fileContent);
                    if (!enumSkeleton.isEmpty()) {
                        result.put(enumCodeUnit, enumSkeleton);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to read file content for enum skeleton fixing: {}", file, e);
        } catch (Exception e) {
            log.error("Failed to fix global enum skeletons for file {}: {}", file, e.getMessage(), e);
        }

        return result;
    }

    /**
     * Fixes global union skeletons to include their field content instead of incomplete skeletons.
     */
    public Map<CodeUnit, String> fixGlobalUnionSkeletons(Map<CodeUnit, String> skeletons, ProjectFile file) {
        var result = new HashMap<>(skeletons);

        try {
            var fileContent = getCachedFileContent(file);
            var parser = parserCache.get();
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
                var unionNode = ASTTraversalUtils.findNodeByTypeAndName(rootNode, "union_specifier", unionName, fileContent);
                if (unionNode != null) {
                    // Extract full union content
                    var unionSkeleton = extractUnionSkeleton(unionNode, unionName, fileContent);
                    if (!unionSkeleton.isEmpty()) {
                        result.put(unionCodeUnit, unionSkeleton);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to read file content for union skeleton fixing: {}", file, e);
        } catch (Exception e) {
            log.error("Failed to fix global union skeletons for file {}: {}", file, e.getMessage(), e);
        }

        return result;
    }

    /**
     * Extracts union skeleton with all field declarations.
     */
    private String extractUnionSkeleton(TSNode unionNode, String unionName, String fileContent) {
        var skeleton = new StringBuilder(256); // Pre-size for better performance
        skeleton.append("union ").append(unionName).append(" {\n");

        // Find the field_declaration_list (union body)
        var bodyNode = unionNode.getChildByFieldName("body");
        if (bodyNode != null && !bodyNode.isNull()) {
            // Extract each field declaration
            for (int i = 0; i < bodyNode.getChildCount(); i++) {
                var child = bodyNode.getChild(i);
                if (child != null && !child.isNull() && "field_declaration".equals(child.getType())) {
                    // Get the full field declaration text
                    var fieldText = ASTTraversalUtils.extractNodeText(child, fileContent);
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
     * Extracts enum skeleton with only enum value names (no assigned values).
     */
    private String extractEnumSkeleton(TSNode enumNode, String enumName, String fileContent) {
        var skeleton = new StringBuilder(256); // Pre-size for better performance
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
                        var enumValueName = ASTTraversalUtils.extractNodeText(nameNode, fileContent);
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
     * Handles both regular enums and scoped enums (enum class).
     */
    public String extractEnumSkeletonFromNode(TSNode enumNode, String fileContent) {
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