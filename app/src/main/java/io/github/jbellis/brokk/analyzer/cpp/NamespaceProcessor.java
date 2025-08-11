package io.github.jbellis.brokk.analyzer.cpp;

import io.github.jbellis.brokk.analyzer.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;
import org.treesitter.TSParser;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class NamespaceProcessor {
    private static final Logger log = LogManager.getLogger(NamespaceProcessor.class);

    private final Map<ProjectFile, String> fileContentCache = new ConcurrentHashMap<>();

    public NamespaceProcessor(TSParser templateParser) {
    }

    public record NamespaceBlock(String name, TSNode node, int startByte, int endByte) {}

    public Map<CodeUnit, String> mergeNamespaceBlocks(Map<CodeUnit, String> skeletons, Map<CodeUnit, List<String>> signatures, ProjectFile file, TSNode rootNode, String fileContent, Function<String, CodeUnit> codeUnitFactory) {
        var namespaceEntries = skeletons.entrySet().stream()
            .filter(entry -> entry.getKey().kind() == CodeUnitType.MODULE)
            .toList();

        if (namespaceEntries.isEmpty()) {
            return skeletons;
        }

        var result = new HashMap<>(skeletons);

        var filesWithNamespaces = namespaceEntries.stream()
            .map(entry -> entry.getKey().source())
            .collect(Collectors.toSet());

        // Only process the single file we have parsed content for
        if (filesWithNamespaces.contains(file)) {
            try {
                var mergedNamespaces = reParseAndMergeNamespaces(file, signatures, rootNode, fileContent, codeUnitFactory);

                result.entrySet().removeIf(entry ->
                    entry.getKey().kind() == CodeUnitType.MODULE &&
                    entry.getKey().source().equals(file));

                result.putAll(mergedNamespaces);
            } catch (Exception e) {
                log.error("Failed to merge namespaces for file {}: {}", file, e.getMessage(), e);
            }
        }

        return result;
    }

    private Map<CodeUnit, String> reParseAndMergeNamespaces(ProjectFile file, Map<CodeUnit, List<String>> signatures, TSNode rootNode, String fileContent, Function<String, CodeUnit> codeUnitFactory) throws IOException {

        var namespaceBlocks = findAllNamespaceBlocks(rootNode, fileContent);
        var groupedNamespaces = new HashMap<String, List<NamespaceBlock>>();
        for (var block : namespaceBlocks) {
            groupedNamespaces.computeIfAbsent(block.name, k -> new ArrayList<>()).add(block);
        }

        var result = new HashMap<CodeUnit, String>();
        for (var entry : groupedNamespaces.entrySet()) {
            var namespaceName = entry.getKey();
            var blocks = entry.getValue();

            CodeUnit existingCodeUnit = null;
            for (var signatureEntry : signatures.entrySet()) {
                var cu = signatureEntry.getKey();
                if (cu.isModule() &&
                    cu.source().equals(file) &&
                    cu.fqName().equals(namespaceName)) {
                    existingCodeUnit = cu;
                    break;
                }
            }

            var codeUnit = existingCodeUnit != null ? existingCodeUnit :
                          codeUnitFactory.apply(namespaceName);
            var mergedSkeleton = createMergedNamespaceSkeleton(namespaceName, blocks, fileContent);
            result.put(codeUnit, mergedSkeleton);
        }

        return result;
    }

    private List<NamespaceBlock> findAllNamespaceBlocks(TSNode rootNode, String fileContent) {
        var namespaceNodes = ASTTraversalUtils.findAllNodesByType(rootNode, "namespace_definition");
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

    private String createMergedNamespaceSkeleton(String namespaceName, List<NamespaceBlock> blocks, String fileContent) {
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

            if ("function_definition".equals(childType)) {
                var signature = extractFunctionSignature(child, fileContent);
                if (!signature.trim().isEmpty()) {
                    skeletons.add(signature + "  {...}");
                }
            }
            else if ("enum_specifier".equals(childType)) {
                var enumSkeleton = extractEnumSkeletonFromNode(child, fileContent);
                if (!enumSkeleton.isEmpty()) {
                    skeletons.add(enumSkeleton);
                }
            }
            else if ("class_specifier".equals(childType) || "struct_specifier".equals(childType)) {
                var classDecl = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!classDecl.isEmpty()) {
                    skeletons.add(classDecl);
                }
            }
            else if ("union_specifier".equals(childType)) {
                var unionDecl = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!unionDecl.isEmpty()) {
                    skeletons.add(unionDecl);
                }
            }
            else if ("type_definition".equals(childType) || "alias_declaration".equals(childType)) {
                var typeDecl = ASTTraversalUtils.extractNodeText(child, fileContent);
                if (!typeDecl.isEmpty()) {
                    skeletons.add(typeDecl);
                }
            }
            else if ("declaration".equals(childType) ||
                     "field_declaration".equals(childType) ||
                     "using_declaration".equals(childType) ||
                     "typedef_declaration".equals(childType)) {
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
            if ("noexcept_specifier".equals(childType) ||
                "trailing_return_type".equals(childType) ||
                "virtual_specifier".equals(childType)) {
                signature += " " + ASTTraversalUtils.extractNodeText(child, fileContent);
            }
        }

        return signature;
    }

    public Map<CodeUnit, String> filterNestedDeclarations(Map<CodeUnit, String> skeletons) {
        var result = new HashMap<>(skeletons);
        return result;
    }


    public void clearCache() {
        fileContentCache.clear();
    }

    public int getCacheSize() {
        return fileContentCache.size();
    }
}