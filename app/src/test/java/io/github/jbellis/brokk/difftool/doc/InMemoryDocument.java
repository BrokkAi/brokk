package io.github.jbellis.brokk.difftool.doc;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-memory implementation of BufferDocumentIF for testing purposes.
 * Provides a simple way to create test documents without file I/O.
 */
public class InMemoryDocument extends AbstractBufferDocument {
    
    private final String content;
    private boolean readonly = false;

    public InMemoryDocument(String name, String content) {
        super();
        this.content = content;
        super.setName(name);
        super.setShortName(name);
        initializeAndRead();
    }

    @Override
    protected int getBufferSize() {
        return content.length();
    }

    @Override
    public Reader getReader() {
        return new StringReader(content);
    }

    @Override
    public Writer getWriter() throws Exception {
        return new StringWriter();
    }

    @Override
    public void read() throws Exception {
        // No-op for in-memory document - content is already available
    }

    @Override
    public void write() {
        // For testing, just mark as unchanged
        // In a real implementation, this would persist changes
    }

    @Override
    public boolean isReadonly() {
        return readonly;
    }

    @Override
    public List<String> getLineList() {
        try {
            String text = getDocument().getText(0, getDocument().getLength());
            if (text.isEmpty()) {
                return List.of("");
            }
            return Arrays.asList(text.split("\n", -1));
        } catch (Exception e) {
            return List.of("");
        }
    }

    // Utility methods for testing

    public void setReadonly(boolean readonly) {
        this.readonly = readonly;
    }
}