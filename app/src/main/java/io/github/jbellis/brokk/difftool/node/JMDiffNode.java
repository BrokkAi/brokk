package io.github.jbellis.brokk.difftool.node;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.ChangeDelta;
import com.github.difflib.patch.InsertDelta;
import com.github.difflib.patch.DeleteDelta;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.doc.StringDocument;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;
import java.io.File;
import java.util.*;

public class JMDiffNode implements TreeNode
{
    private static final Logger logger = LoggerFactory.getLogger(JMDiffNode.class);

    private final String name;
    @Nullable private String shortName;
    private final List<JMDiffNode> children; // Consider final if populated once
    @Nullable private BufferNode nodeLeft;
    @Nullable private BufferNode nodeRight;
    private final boolean leaf;

    // We now store the diff result here instead of JMRevision.
    private Patch<String> patch = new Patch<>();

    // Whether to ignore blank-line-only differences (default: true)
    private static boolean ignoreBlankLineDiffs = true;
    public static void setIgnoreBlankLineDiffs(boolean ignore) { ignoreBlankLineDiffs = ignore; }
    public static boolean isIgnoreBlankLineDiffs() { return ignoreBlankLineDiffs; }

    // Placeholder for an empty document, used when a side is missing.
    private static final BufferDocumentIF EMPTY_DOC = new StringDocument("", "<empty>", true);

    public JMDiffNode(String name, boolean leaf) {
        this.name = name;
        this.shortName = name;
        this.leaf = leaf;
        this.children = new ArrayList<>(); // Initialize children list
        calculateNames();
    }

    // Consider adding methods to add/remove children and set parent if needed
    // public void setParent(JMDiffNode parent) { this.parent = parent; }
    // public void addChild(JMDiffNode child) { this.children.add(child); child.setParent(this); }

    public void setBufferNodeLeft(@Nullable BufferNode bufferNode) {
        nodeLeft = bufferNode;
    }

    public @Nullable BufferNode getBufferNodeLeft() {
        return nodeLeft;
    }

    public void setBufferNodeRight(@Nullable BufferNode bufferNode) {
        nodeRight = bufferNode;
    }

    public @Nullable BufferNode getBufferNodeRight() {
        return nodeRight;
    }

    /**
     * Core method that runs the diff using java-diff-utils.
     * It now uses assert to ensure documents are non-null after retrieval.
     * Uses a placeholder empty document if a node is missing.
     */
    public void diff() {
        // Get documents, providing an empty placeholder if a node is null
        BufferDocumentIF leftDoc = (nodeLeft != null) ? nodeLeft.getDocument() : EMPTY_DOC;
        BufferDocumentIF rightDoc = (nodeRight != null) ? nodeRight.getDocument() : EMPTY_DOC;

        logger.debug("JMDiffNode.diff() starting for {}: left={}, right={}", name,
                    leftDoc.getName(), rightDoc.getName());

        // MEMORY PROTECTION: Check for huge single-line files that would cause memory explosion
        if (shouldSkipDiffForMemoryProtection(leftDoc, rightDoc)) {
             logger.warn("Skipping diff computation for {} due to memory protection (huge single-line files)", name);
             var computedPatch = computeHeuristicPatch(leftDoc, rightDoc);
            // Ensure callers can rely on a non-null Patch instance
            this.patch = Objects.requireNonNullElseGet(computedPatch, Patch::new);
             return;
         }

        // Get line lists. getLineList() should be safe now due to eager initialization.
        var leftLines = leftDoc.getLineList();
        var rightLines = rightDoc.getLineList();

        // Compute the diff
        this.patch = DiffUtils.diff(leftLines, rightLines);
        if (ignoreBlankLineDiffs) {
            this.patch.getDeltas().removeIf(d -> isBlankChunk(d.getSource()) && isBlankChunk(d.getTarget()));
        }
    }

    /**
     * Check if diff computation should be skipped to prevent memory explosion.
     * Applies memory protection for huge single-line files that would cause
     * excessive memory usage during diff algorithm processing.
     */
    private boolean shouldSkipDiffForMemoryProtection(BufferDocumentIF leftDoc, BufferDocumentIF rightDoc) {
        boolean leftSkip = isHugeSingleLineFile(leftDoc);
        boolean rightSkip = isHugeSingleLineFile(rightDoc);

        if (leftSkip || rightSkip) {
            logger.info("JMDiffNode protection triggered for {}: left={}, right={}",
                        name, leftSkip, rightSkip);
            return true;
        }

        return false;
    }

    /**
     * Detect huge single-line files that would cause memory explosion during diff computation.
     * These files have few lines but each line is extremely long, causing algorithms to
     * fall back to character-level processing which can allocate per-character objects.
     */
    private boolean isHugeSingleLineFile(BufferDocumentIF doc) {
        try {
            int numberOfLines = doc.getNumberOfLines();
            long contentLength = doc.getDocument().getLength();

            // Skip if few lines with huge average line length
            if (numberOfLines <= 3 && contentLength > PerformanceConstants.SINGLE_LINE_THRESHOLD_BYTES) {
                long averageLineLength = contentLength / Math.max(1, numberOfLines);
                if (averageLineLength > PerformanceConstants.MAX_DIFF_LINE_LENGTH_BYTES) {
                    logger.info("JMDiffNode: Detected huge single-line file {}: {} lines, {}KB total, avg {}KB/line - SKIPPING DIFF",
                               doc.getName(), numberOfLines, contentLength / 1024, averageLineLength / 1024);
                    return true;
                }
            }

            logger.debug("JMDiffNode: File {} passed memory checks: {} lines, {}KB total",
                        doc.getName(), numberOfLines, contentLength / 1024);

            return false;
        } catch (Exception e) {
            logger.warn("Error checking file size for memory protection, allowing diff: {}", e.getMessage());
            return false; // When in doubt, allow diff
        }
    }

    /**
      * Compute a lightweight heuristic Patch when a full diff is skipped.
      * Returns {@code null} if heuristics detect no meaningful difference.
      */
     @Nullable
     private Patch<String> computeHeuristicPatch(BufferDocumentIF leftDoc, BufferDocumentIF rightDoc) {
         try {
             long leftLen  = leftDoc.getDocument().getLength();
             long rightLen = rightDoc.getDocument().getLength();

             // Both sides empty -> identical
             if (leftLen == 0 && rightLen == 0) {
                 return null;
             }

             Patch<String> heuristicPatch = new Patch<>();

             // One side empty -> whole file added / removed
             if (leftLen == 0) {
                 heuristicPatch.addDelta(
                     new InsertDelta<>(
                         new Chunk<>(0, List.of()),
                         new Chunk<>(0, List.of("<FILE ADDED - heuristic>"))
                     )
                 );
                 return heuristicPatch;
             }
             if (rightLen == 0) {
                 heuristicPatch.addDelta(
                     new DeleteDelta<>(
                         new Chunk<>(0, List.of("<FILE REMOVED - heuristic>")),
                         new Chunk<>(0, List.of())
                     )
                 );
                 return heuristicPatch;
             }

             // Compare first N bytes
             int prefixLen = (int) Math.min(
                 PerformanceConstants.HEURISTIC_PREFIX_BYTES,
                 Math.min(leftLen, rightLen)
             );
             String leftPrefix  = leftDoc.getDocument().getText(0, prefixLen);
             String rightPrefix = rightDoc.getDocument().getText(0, prefixLen);

             if (!leftPrefix.equals(rightPrefix)) {
                 heuristicPatch.addDelta(
                     new ChangeDelta<>(
                         new Chunk<>(0, List.of("<CONTENT DIFFERS - heuristic>")),
                         new Chunk<>(0, List.of("<CONTENT DIFFERS - heuristic>"))
                     )
                 );
                 return heuristicPatch;
             }
         } catch (Exception e) {
             logger.debug("Failed to build heuristic diff for {}: {}", name, e.getMessage(), e);
         }
         // identical prefix (likely identical files) or error
         return null;
     }

     private static boolean isBlankChunk(Chunk<String> chunk) {
        return chunk.getLines().stream().allMatch(l -> l.trim().isEmpty());
    }

    /**
     * Retrieve the computed Patch. May be null if diff() not called or error occurred.
     */
    @Nullable
    public Patch<String> getPatch() {
        return patch;
    }

    public String getName() {
        return name;
    }

    @Override
    public Enumeration<JMDiffNode> children() {
        return Collections.enumeration(children);
    }

    @Override
    public boolean getAllowsChildren() {
        // A node allows children if it's *not* a leaf.
        return !isLeaf();
    }

    @Override
    public TreeNode getChildAt(int childIndex) {
         if (childIndex < 0 || childIndex >= children.size()) {
             throw new ArrayIndexOutOfBoundsException("childIndex out of bounds");
         }
        return children.get(childIndex);
    }

    @Override
    public int getChildCount() {
        return children.size();
    }

    @Override
    public int getIndex(TreeNode node) {
        if (!(node instanceof JMDiffNode)) {
            return -1; // Or throw an exception, depending on expected usage
        }
        return children.indexOf(node);
    }

    @Override
    public @Nullable TreeNode getParent() {
        return null;
    }

    @Override
    public boolean isLeaf() {
        return leaf;
    }

    private void calculateNames() {
        // Use File.separator for cross-platform compatibility
        int index = name.lastIndexOf(File.separatorChar);
        if (index != -1) {
            shortName = name.substring(index + 1);
        }
    }

    @Override
    public String toString() {
        // Return shortName if available and not empty, otherwise the full name.
        return (shortName != null && !shortName.isEmpty()) ? shortName : name;
    }
}
