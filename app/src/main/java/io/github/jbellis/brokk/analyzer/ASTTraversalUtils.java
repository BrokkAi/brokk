package io.github.jbellis.brokk.analyzer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;

/**
 * Utility class for common AST traversal patterns used across TreeSitter analyzers. Eliminates code duplication in
 * recursive node searching and traversal operations.
 */
public class ASTTraversalUtils {
    private static final Logger log = LogManager.getLogger(ASTTraversalUtils.class);

    /** Recursively finds the first node matching the given predicate. */
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

    /** Recursively finds all nodes matching the given predicate. */
    public static List<TSNode> findAllNodesRecursive(TSNode rootNode, Predicate<TSNode> predicate) {
        var results = new ArrayList<TSNode>();
        findAllNodesRecursiveInternal(rootNode, predicate, results);
        return results;
    }

    private static void findAllNodesRecursiveInternal(
            @Nullable TSNode node, Predicate<TSNode> predicate, List<TSNode> results) {
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

    /** Finds a node by type and name within the AST. */
    public static @Nullable TSNode findNodeByTypeAndName(
            TSNode rootNode, String nodeType, String nodeName, String fileContent) {
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
     * Extracts text from a TSNode using the file content.Properly handles UTF-8 byte offset to character position
     * conversion.
     */
    public static String extractNodeText(@Nullable TSNode node, @Nullable String fileContent) {
        if (node == null || node.isNull() || fileContent == null) {
            return "";
        }

        int startByte = node.getStartByte();
        int endByte = node.getEndByte();

        if (startByte < 0 || startByte > endByte) {
            return "";
        }

        return safeSubstringFromByteOffsets(fileContent, startByte, endByte).trim();
    }

    /**
     * Converts UTF-8 byte offset to Java string character position. This is needed because TreeSitter provides byte
     * offsets but Java strings use character positions.
     */
    public static int byteOffsetToCharPosition(int byteOffset, String source) {
        if (byteOffset <= 0) return 0;

        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        if (byteOffset >= sourceBytes.length) return source.length();

        // Create substring from bytes and get its character length
        String substring = new String(sourceBytes, 0, byteOffset, StandardCharsets.UTF_8);
        return substring.length();
    }

    /**
     * Safely extracts substring using UTF-8 byte offsets converted to character positions. This method should be used
     * instead of direct String.substring() with byte offsets.
     */
    public static String safeSubstringFromByteOffsets(String source, int startByte, int endByte) {
        if (startByte < 0 || endByte < startByte) {
            log.warn(
                    "Requested bytes outside valid range for source text (length: {} bytes): startByte={}, endByte={}",
                    source.getBytes(StandardCharsets.UTF_8).length,
                    startByte,
                    endByte);
            return "";
        }

        // Handle zero-width nodes (same start and end position) - valid case
        if (startByte == endByte) {
            return "";
        }

        // Validate byte offsets against actual source byte length
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        if (startByte >= sourceBytes.length) {
            log.warn("Start byte offset {} exceeds source byte length {}", startByte, sourceBytes.length);
            return "";
        }
        if (endByte > sourceBytes.length) {
            log.warn("End byte offset {} exceeds source byte length {}, truncating", endByte, sourceBytes.length);
            endByte = sourceBytes.length;
        }

        int startChar = byteOffsetToCharPosition(startByte, source);
        int endChar = byteOffsetToCharPosition(endByte, source);

        if (startChar >= source.length()) return "";
        if (endChar > source.length()) endChar = source.length();

        return source.substring(startChar, endChar);
    }

    /** Finds all nodes of a specific type within the AST. */
    public static List<TSNode> findAllNodesByType(TSNode rootNode, String nodeType) {
        return findAllNodesRecursive(rootNode, node -> nodeType.equals(node.getType()));
    }
}
