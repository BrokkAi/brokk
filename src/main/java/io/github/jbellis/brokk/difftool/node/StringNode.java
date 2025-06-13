package io.github.jbellis.brokk.difftool.node;


import io.github.jbellis.brokk.difftool.doc.StringDocument;

public class StringNode implements Comparable<StringNode>, BufferNode {
    private final String name;
    private final String content;
    private final StringDocument document;

    public StringNode(String name, String content) {
        this.name = name;
        this.content = (content != null) ? content : "";

        // Create once:
        this.document = new StringDocument(this.content, name);
    }

    @Override
    public StringDocument getDocument() {
        return document;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getSize() {
        return content.length();
    }

    @Override
    public int compareTo(StringNode other) {
        return name.compareTo(other.getName());
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof StringNode stringNode) && name.equals(stringNode.getName());
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "StringNode[name=" + name + "]";
    }
}
