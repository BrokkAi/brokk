package io.github.jbellis.brokk.analyzer;

import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Utility class for common AST traversal patterns used across TreeSitter analyzers.
 * Eliminates code duplication in recursive node searching and traversal operations.
 */
public class ASTTraversalUtils {

    /**
     * Recursively finds the first node matching the given predicate.
     */
    public static @Nullable TSNode findNodeRecursive(@Nullable TSNode rootNode, Predicate<TSNode> predicate) {
        if (rootNode == null || rootNode.isNull()) {
            return null;
        }

        if (predicate.test(rootNode)) {
            return rootNode;
        }

        // Recursively search children
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            var child = rootNode.getChild(i);
            if (child != null && !child.isNull()) {
                var result = findNodeRecursive(child, predicate);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * Recursively finds all nodes matching the given predicate.
     */
    public static List<TSNode> findAllNodesRecursive(TSNode rootNode, Predicate<TSNode> predicate) {
        var results = new ArrayList<TSNode>();
        findAllNodesRecursiveInternal(rootNode, predicate, results);
        return results;
    }

    private static void findAllNodesRecursiveInternal(@Nullable TSNode node, Predicate<TSNode> predicate, List<TSNode> results) {
        if (node == null || node.isNull()) {
            return;
        }

        if (predicate.test(node)) {
            results.add(node);
        }

        // Recursively search children
        for (int i = 0; i < node.getChildCount(); i++) {
            var child = node.getChild(i);
            if (child != null && !child.isNull()) {
                findAllNodesRecursiveInternal(child, predicate, results);
            }
        }
    }

    /**
     * Finds a node by type and name within the AST.
     */
    public static @Nullable TSNode findNodeByTypeAndName(TSNode rootNode, String nodeType, String nodeName, String fileContent) {
        return findNodeRecursive(rootNode, node -> {
            if (!nodeType.equals(node.getType())) {
                return false;
            }

            var nameNode = node.getChildByFieldName("name");
            if (nameNode == null || nameNode.isNull()) {
                return false;
            }

            var extractedName = extractNodeText(nameNode, fileContent);
            return nodeName.equals(extractedName);
        });
    }

    /**
     * Extracts text from a TSNode using the file content.
     */
    public static String extractNodeText(@Nullable TSNode node, @Nullable String fileContent) {
        if (node == null || node.isNull() || fileContent == null) {
            return "";
        }

        int startByte = node.getStartByte();
        int endByte = node.getEndByte();

        if (startByte < 0 || endByte > fileContent.length() || startByte > endByte) {
            return "";
        }

        return fileContent.substring(startByte, endByte).trim();
    }

    /**
     * Finds all nodes of a specific type within the AST.
     */
    public static List<TSNode> findAllNodesByType(TSNode rootNode, String nodeType) {
        return findAllNodesRecursive(rootNode, node -> nodeType.equals(node.getType()));
    }
}