package io.github.jbellis.brokk.difftool.node;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.doc.StringDocument;

import javax.swing.tree.TreeNode;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class JMDiffNode implements TreeNode
{
    private String name;
    private String shortName;
    private String parentName;
    private JMDiffNode parent; // Should be final if set in constructor and never changed
    private List<JMDiffNode> children; // Consider final if populated once
    private BufferNode nodeLeft;
    private BufferNode nodeRight;
    private final boolean leaf;

    // We now store the diff result here instead of JMRevision.
    private Patch<String> patch;

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

    public void setBufferNodeLeft(BufferNode bufferNode) {
        nodeLeft = bufferNode;
    }

    public BufferNode getBufferNodeLeft() {
        return nodeLeft;
    }

    public void setBufferNodeRight(BufferNode bufferNode) {
        nodeRight = bufferNode;
    }

    public BufferNode getBufferNodeRight() {
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

        // Assert that the documents (or placeholders) are non-null
        assert leftDoc != null : "Left document is unexpectedly null after retrieval/placeholder assignment";
        assert rightDoc != null : "Right document is unexpectedly null after retrieval/placeholder assignment";

        // Get line lists. getLineList() should be safe now due to eager initialization.
        var leftLines = leftDoc.getLineList();
        var rightLines = rightDoc.getLineList();

        // Compute the diff
        this.patch = DiffUtils.diff(leftLines, rightLines);
    }

    /**
     * Retrieve the computed Patch. May be null if diff() not called or error occurred.
     */
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
        if (node == null) {
            throw new IllegalArgumentException("argument is null");
        }
        if (!(node instanceof JMDiffNode)) {
            return -1; // Or throw an exception, depending on expected usage
        }
        return children.indexOf(node);
    }

    @Override
    public TreeNode getParent() {
        return parent;
    }

    @Override
    public boolean isLeaf() {
        return leaf;
    }

    private void calculateNames() {
        // Use File.separator for cross-platform compatibility
        int index = name.lastIndexOf(File.separatorChar);
        if (index == -1) {
            parentName = ""; // No parent path component
            // shortName is already set to name
        } else {
            parentName = name.substring(0, index);
            shortName = name.substring(index + 1);
        }
    }

    @Override
    public String toString() {
        // Return shortName if available, otherwise the full name.
        return (shortName != null && !shortName.isEmpty()) ? shortName : name;
    }
}
