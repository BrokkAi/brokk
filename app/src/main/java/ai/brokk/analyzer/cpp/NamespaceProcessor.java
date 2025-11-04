package ai.brokk.analyzer.cpp;

import static ai.brokk.analyzer.cpp.CppTreeSitterNodeTypes.*;

import ai.brokk.analyzer.*;
import ai.brokk.analyzer.ASTTraversalUtils;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.treesitter.TSNode;
import org.treesitter.TSParser;

public class NamespaceProcessor {
    private static final Logger log = LogManager.getLogger(NamespaceProcessor.class);

    private final Map<ProjectFile, String> fileContentCache = new ConcurrentHashMap<>();
    private final Supplier<String> bodyPlaceholderSupplier;

    public NamespaceProcessor(TSParser templateParser) {
        // Default C++ placeholder for presentation purposes only
        this.bodyPlaceholderSupplier = () -> "{...}";
    }

    public NamespaceProcessor(TSParser templateParser, Supplier<String> bodyPlaceholderSupplier) {
        this.bodyPlaceholderSupplier = Objects.requireNonNull(bodyPlaceholderSupplier, "bodyPlaceholderSupplier");
    }

    public record NamespaceBlock(String name, TSNode node, int startByte, int endByte) {}

    public Map<CodeUnit, String> mergeNamespaceBlocks(
            Map<CodeUnit, String> skeletons,
            Map<CodeUnit, List<String>> signatures,
            ProjectFile file,
            TSNode rootNode,
            String fileContent,
            Function<String, CodeUnit> codeUnitFactory) {
        var namespaceEntries = skeletons.entrySet().stream()
                .filter(entry -> entry.getKey().isModule())
                .toList();

        if (namespaceEntries.isEmpty()) {
            return skeletons;
        }

        var result = new HashMap<>(skeletons);

        var filesWithNamespaces =
                namespaceEntries.stream().map(entry -> entry.getKey().source()).collect(Collectors.toSet());

        // Only process the single file we have parsed content for
        if (filesWithNamespaces.contains(file)) {
            try {
                var mergedNamespaces =
                        reParseAndMergeNamespaces(file, signatures, rootNode, fileContent, codeUnitFactory);

                result.entrySet()
                        .removeIf(entry -> entry.getKey().isModule()
                                && entry.getKey().source().equals(file));

                result.putAll(mergedNamespaces);
            } catch (Exception e) {
                log.error("Failed to merge namespaces for file {}: {}", file, e.getMessage(), e);
            }
        }

        return result;
    }

    private Map<CodeUnit, String> reParseAndMergeNamespaces(
            ProjectFile file,
            Map<CodeUnit, List<String>> signatures,
            TSNode rootNode,
            String fileContent,
            Function<String, CodeUnit> codeUnitFactory) {

        var namespaceBlocks = findAllNamespaceBlocks(rootNode, fileContent);
        var groupedNamespaces = new HashMap<String, List<NamespaceBlock>>();
        for (var block : namespaceBlocks) {
            groupedNamespaces
                    .computeIfAbsent(block.name, k -> new ArrayList<>())
                    .add(block);
        }

        var result = new HashMap<CodeUnit, String>();
        for (var entry : groupedNamespaces.entrySet()) {
            var namespaceName = entry.getKey();
            var blocks = entry.getValue();

            CodeUnit existingCodeUnit = null;
            for (var signatureEntry : signatures.entrySet()) {
                var cu = signatureEntry.getKey();
                if (cu.isModule() && cu.source().equals(file) && cu.fqName().equals(namespaceName)) {
                    existingCodeUnit = cu;
                    break;
                }
            }

            var codeUnit = existingCodeUnit != null ? existingCodeUnit : codeUnitFactory.apply(namespaceName);
            var mergedSkeleton = createMergedNamespaceSkeleton(namespaceName, blocks, fileContent);
            result.put(codeUnit, mergedSkeleton);
        }

        return result;
    }

    private List<NamespaceBlock> findAllNamespaceBlocks(TSNode rootNode, String fileContent) {
        var namespaceNodes = ASTTraversalUtils.findAllNodesByType(rootNode, NAMESPACE_DEFINITION);
        var namespaceBlocks = new ArrayList<NamespaceBlock>();

        for (var node : namespaceNodes) {
            var nameNode = node.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
                var namespaceName = ASTTraversalUtils.extractNodeText(nameNode, fileContent);
                namespaceBlocks.add(new NamespaceBlock(namespaceName, node, node.getStartByte(), node.getEndByte()));
            }
        }

        return namespaceBlocks;
    }

    private String createMergedNamespaceSkeleton(
            String namespaceName, List<NamespaceBlock> blocks, String fileContent) {
        var mergedContent = new StringBuilder(512); // Pre-size for better performance
        mergedContent.append("namespace ").append(namespaceName).append(" {\n");

        for (var block : blocks) {
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

    private List<String> extractNamespaceBodySkeletons(TSNode bodyNode, String fileContent) {
        var skeletons = new ArrayList<String>();

        for (int i = 0; i < bodyNode.getChildCount(); i++) {
            var child = bodyNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (FUNCTION_DEFINITION.equals(childType)) {
                var signature = extractFunctionSignature(child, fileContent);
                if (!signature.trim().isEmpty()) {
                    // Presentation-only marker: append a consistent placeholder for functions with bodies.
                    // NOTE: Duplicate handling uses the AST-derived hasBody flag, not this string.
                    skeletons.add(signature + "  " + bodyPlaceholderSupplier.get());
                }
            } else if (ENUM_SPECIFIER.equals(childType)) {
                var enumSkeleton = extractEnumSkeletonFromNode(child, fileContent);
                if (!enumSkeleton.isEmpty()) {
                    skeletons.add(enumSkeleton);
                }
            } else if (CLASS_SPECIFIER.equals(childType) || STRUCT_SPECIFIER.equals(childType)) {
                var classDecl = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!classDecl.isEmpty()) {
                    skeletons.add(classDecl);
                }
            } else if (UNION_SPECIFIER.equals(childType)) {
                var unionDecl = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!unionDecl.isEmpty()) {
                    skeletons.add(unionDecl);
                }
            } else if (TYPE_DEFINITION.equals(childType) || ALIAS_DECLARATION.equals(childType)) {
                var typeDecl = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!typeDecl.isEmpty()) {
                    skeletons.add(typeDecl);
                }
            } else if (DECLARATION.equals(childType)
                    || FIELD_DECLARATION.equals(childType)
                    || USING_DECLARATION.equals(childType)
                    || TYPEDEF_DECLARATION.equals(childType)) {
                var declaration = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!declaration.isEmpty()) {
                    if (!declaration.endsWith(";") && !declaration.endsWith("}")) {
                        declaration += ";";
                    }
                    skeletons.add(declaration);
                }
            }
        }
        return skeletons;
    }

    private String extractEnumSkeletonFromNode(TSNode enumNode, String fileContent) {
        var nameNode = enumNode.getChildByFieldName("name");
        String enumName = "";
        if (nameNode != null && !nameNode.isNull()) {
            enumName = ASTTraversalUtils.extractNodeText(nameNode, fileContent);
        }

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

        var bodyNode = enumNode.getChildByFieldName("body");
        if (bodyNode != null && !bodyNode.isNull()) {
            var enumValues = new ArrayList<String>();

            for (int i = 0; i < bodyNode.getChildCount(); i++) {
                var child = bodyNode.getChild(i);
                if (child != null && !child.isNull() && "enumerator".equals(child.getType())) {
                    var enumeratorNameNode = child.getChildByFieldName("name");
                    if (enumeratorNameNode != null && !enumeratorNameNode.isNull()) {
                        var enumValueName = ASTTraversalUtils.extractNodeText(enumeratorNameNode, fileContent);
                        if (!enumValueName.isEmpty()) {
                            enumValues.add(enumValueName);
                        }
                    }
                }
            }

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

    private String extractFunctionSignature(TSNode funcNode, String fileContent) {
        var declarator = funcNode.getChildByFieldName("declarator");
        if (declarator == null || declarator.isNull()) {
            return "";
        }

        String returnType = "";
        var typeNode = funcNode.getChildByFieldName("type");
        if (typeNode != null && !typeNode.isNull()) {
            returnType = ASTTraversalUtils.extractNodeText(typeNode, fileContent) + " ";
        }

        String signature = returnType + ASTTraversalUtils.extractNodeText(declarator, fileContent);

        for (int i = 0; i < funcNode.getChildCount(); i++) {
            var child = funcNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            if (NOEXCEPT_SPECIFIER.equals(childType)
                    || TRAILING_RETURN_TYPE.equals(childType)
                    || VIRTUAL_SPECIFIER.equals(childType)) {
                signature += " " + ASTTraversalUtils.extractNodeText(child, fileContent);
            }
        }

        return signature;
    }

    public void clearCache() {
        fileContentCache.clear();
    }

    public int getCacheSize() {
        return fileContentCache.size();
    }
}
