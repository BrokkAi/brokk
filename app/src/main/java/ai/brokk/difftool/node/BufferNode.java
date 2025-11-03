package ai.brokk.difftool.node;

import ai.brokk.difftool.doc.BufferDocumentIF;

public interface BufferNode {
    String getName();

    long getSize();

    BufferDocumentIF getDocument();
}
