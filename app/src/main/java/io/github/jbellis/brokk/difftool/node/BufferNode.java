
package io.github.jbellis.brokk.difftool.node;


import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;

public interface BufferNode {
    String getName();

    long getSize();

    BufferDocumentIF getDocument();

}
