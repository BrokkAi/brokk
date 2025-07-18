package io.github.jbellis.brokk.difftool.doc;

import javax.swing.text.PlainDocument;
import java.io.Reader;
import java.util.List;

public interface BufferDocumentIF
{
    String ORIGINAL = "Original";
    String REVISED = "Revised";

    String getName();
    String getShortName();

    void addChangeListener(BufferDocumentChangeListenerIF listener);
    void removeChangeListener(BufferDocumentChangeListenerIF listener);

    boolean isChanged();

    PlainDocument getDocument();
    AbstractBufferDocument.Line[] getLines();  // existing method
    int getOffsetForLine(int lineNumber);
    int getLineForOffset(int offset);

    void read() throws Exception;
    void write() throws Exception;
    Reader getReader() throws Exception;
    boolean isReadonly();

    String getLineText(int lineNumber);
    int getNumberOfLines();

    /**
     * Returns the contents of this document as a list of strings, one entry per line.
     */
    List<String> getLineList();
}
