package ai.brokk.analyzer;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSException;
import org.treesitter.TSNode;

/**
 * Utility class for common AST traversal patterns used across TreeSitter analyzers. Eliminates code duplication in
 * recursive node searching and traversal operations.
 */
public class ASTTraversalUtils {
    /**
     * Returns the Tree-sitter node type, or null for Java nulls and Tree-sitter null-node wrappers.
     */
    public static @Nullable String typeOf(@Nullable TSNode node) {
        if (node == null) {
            return null;
        }
        try {
            return node.getType();
        } catch (TSException e) {
            return null;
        }
    }

    /** Returns true when the node can be safely queried. */
    public static boolean isValid(@Nullable TSNode node) {
        return typeOf(node) != null;
    }

    /** Compares byte ranges after confirming both nodes are valid Tree-sitter nodes. */
    public static boolean sameRange(TSNode left, TSNode right) {
        if (!isValid(left) || !isValid(right)) {
            return false;
        }
        return left.getStartByte() == right.getStartByte() && left.getEndByte() == right.getEndByte();
    }

    /** Recursively finds the first node matching the given predicate. */
    public static @Nullable TSNode findNodeRecursive(@Nullable TSNode rootNode, Predicate<TSNode> predicate) {
        if (!isValid(rootNode)) {
            return null;
        }
        TSNode root = requireNonNull(rootNode);

        if (predicate.test(root)) {
            return root;
        }

        // Recursively search children
        for (int i = 0; i < root.getChildCount(); i++) {
            var child = root.getChild(i);
            if (isValid(child)) {
                var result = findNodeRecursive(child, predicate);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /** Recursively finds all nodes matching the given predicate. */
    public static List<TSNode> findAllNodesRecursive(TSNode rootNode, Predicate<TSNode> predicate) {
        var results = new ArrayList<TSNode>();
        findAllNodesRecursiveInternal(rootNode, predicate, results);
        return results;
    }

    private static void findAllNodesRecursiveInternal(
            @Nullable TSNode node, Predicate<TSNode> predicate, List<TSNode> results) {
        if (!isValid(node)) {
            return;
        }
        TSNode current = requireNonNull(node);

        if (predicate.test(current)) {
            results.add(current);
        }

        // Recursively search children
        for (int i = 0; i < current.getChildCount(); i++) {
            var child = current.getChild(i);
            if (isValid(child)) {
                findAllNodesRecursiveInternal(child, predicate, results);
            }
        }
    }

    /** Finds a node by type and name within the AST. */
    public static @Nullable TSNode findNodeByTypeAndName(
            TSNode rootNode, String nodeType, String nodeName, SourceContent sourceContent) {
        return findNodeRecursive(rootNode, node -> {
            if (!nodeType.equals(typeOf(node))) {
                return false;
            }

            var nameNode = node.getChildByFieldName("name");
            if (!isValid(nameNode)) {
                return false;
            }

            var extractedName = extractNodeText(nameNode, sourceContent);
            return nodeName.equals(extractedName);
        });
    }

    /**
     * Extracts text from a TSNode using the provided SourceContent.
     */
    public static String extractNodeText(@Nullable TSNode node, SourceContent sourceContent) {
        if (!isValid(node)) {
            return "";
        }
        TSNode current = requireNonNull(node);

        int startByte = current.getStartByte();
        int endByte = current.getEndByte();

        if (startByte < 0 || startByte > endByte) {
            return "";
        }

        String result = sourceContent.substringFromBytes(startByte, endByte);
        return result.trim();
    }

    /** Finds all nodes of a specific type within the AST. */
    public static List<TSNode> findAllNodesByType(TSNode rootNode, String nodeType) {
        return findAllNodesRecursive(rootNode, node -> nodeType.equals(typeOf(node)));
    }
}
