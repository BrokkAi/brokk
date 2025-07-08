package io.github.jbellis.brokk.difftool.doc;

import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;

public class StringDocument extends AbstractBufferDocument {
    private String content;
    private final boolean readOnly; // Typically strings aren't read-only unless specified

    public StringDocument(String content, String name) {
        this(content, name, true); // Default to not read-only
    }

    public StringDocument(String content, String name, boolean readOnly) {
        super();
        this.content = content;
        this.readOnly = readOnly;
        setName(name); // Set names before reading
        setShortName(name); // Use full name as short name for strings
        initializeAndRead(); // Read content during construction
    }

    @Override
    public int getBufferSize() {
        // Provide a reasonable buffer size based on content length
        return Math.max(100, content.length() + 50);
    }

    @Override
    public Reader getReader() {
        // Return a new reader each time, as readers can be closed
        return new StringReader(this.content);
    }

    @Override
    public Writer getWriter() {
        if (isReadonly()) {
            // Return a writer that does nothing or throws exception for read-only docs
            return new Writer() {
                @Override
                public void write(char[] cbuf, int off, int len) throws java.io.IOException {
                    throw new java.io.IOException("Document is read-only: " + getName());
                }
                @Override
                public void flush() { /* No-op */ }
                @Override
                public void close() { /* No-op */ }
            };
        }

        // Return a StringWriter that updates the internal content on close
        return new StringWriter(getBufferSize()) {
            @Override
            public void close() throws java.io.IOException {
                super.close();
                // Update the StringDocument's content when the writer is closed
                StringDocument.this.content = this.toString();
                // After updating content, we need to re-initialize the underlying PlainDocument
                // and line structures based on the new content.
                StringDocument.this.resetLineCache();
                StringDocument.this.initializeAndRead(); // Re-read the new content
            }
        };
    }

    /**
     * Gets the current string content.
     * Note: This might not reflect changes made through getWriter() until the writer is closed.
     */
    public String getContent() {
        return content;
    }

    /**
     * Sets the string content directly and re-initializes the document.
     * Use this if you need to bypass the Writer mechanism.
     */
    public void setContent(String newContent) {
        if (isReadonly()) {
            System.err.println("Warning: Attempting to set content on a read-only StringDocument: " + getName());
            return; // Or throw an exception
        }
        this.content = newContent;
        // Re-initialize based on the new content
        resetLineCache();
        initializeAndRead();
    }

    @Override
    public boolean isReadonly() {
        return readOnly;
    }

    @Override
    public String toString() {
        return "StringDocument[name=" + getName() + ", readonly=" + readOnly + "]";
    }

    @Override
    public void read() {
        // Re-initialize and read the current string content again
        initializeAndRead();
    }
}
