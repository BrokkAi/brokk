package ai.brokk.analyzer;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    /** Returns valid named children in source order. */
    public static List<TSNode> namedChildren(TSNode node) {
        var children = new ArrayList<TSNode>();
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (isValid(child)) {
                children.add(requireNonNull(child));
            }
        }
        return children;
    }

    /** Returns valid children in source order, including anonymous token nodes. */
    public static List<TSNode> children(TSNode node) {
        var children = new ArrayList<TSNode>();
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (isValid(child)) {
                children.add(requireNonNull(child));
            }
        }
        return children;
    }

    /** Finds the first direct named child whose type is in the supplied set. */
    public static @Nullable TSNode directNamedChildOfAnyType(TSNode node, Set<String> types) {
        for (TSNode child : namedChildren(node)) {
            if (types.contains(typeOf(child))) {
                return child;
            }
        }
        return null;
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
        for (TSNode child : children(root)) {
            var result = findNodeRecursive(child, predicate);
            if (result != null) {
                return result;
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
        for (TSNode child : children(current)) {
            findAllNodesRecursiveInternal(child, predicate, results);
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

    /**
     * Converts a Java String character index (UTF-16 code units) to a UTF-8 byte offset without allocating prefix
     * substrings.
     */
    public static int charPositionToUtf8ByteOffset(String text, int charPosition) {
        if (charPosition <= 0) {
            return 0;
        }
        if (charPosition >= text.length()) {
            charPosition = text.length();
        }

        int bytes = 0;
        for (int i = 0; i < charPosition; ) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch) && i + 1 < charPosition && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i += 2;
            } else {
                bytes += utf8ByteLength(ch);
                i++;
            }
        }
        return bytes;
    }

    private static int utf8ByteLength(char ch) {
        if (ch <= 0x7F) {
            return 1;
        }
        if (ch <= 0x7FF) {
            return 2;
        }
        if (Character.isSurrogate(ch)) {
            return 1;
        }
        return 3;
    }
}
