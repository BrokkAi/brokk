
package io.github.jbellis.brokk.difftool.doc;


import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class FileDocument
        extends AbstractBufferDocument {
    private static final Logger logger = Logger.getLogger(FileDocument.class.getName());
    private static final int DEFAULT_BUFFER_SIZE = 1024;
    
    // instance variables:
    private final File file;
    private boolean readOnly;

    public FileDocument(File file, String name) {
        super(); // Call AbstractBufferDocument constructor
        this.file = file;
        this.readOnly = !file.canWrite();
        setName(name); // Set names before reading
        setShortName(file.getName());
        initializeAndRead(); // Read content during construction
    }

    @Override
    public boolean isReadonly() {
        return readOnly;
    }

    @Override
    public int getBufferSize() {
        // Return a reasonable default buffer size if file doesn't exist or is empty
        return (file != null && file.exists()) ? (int) Math.max(file.length(), DEFAULT_BUFFER_SIZE) : DEFAULT_BUFFER_SIZE;
    }

    @Override
    public Reader getReader() {
        try {
            if (!file.exists() || !file.canRead()) {
                logger.warning("File does not exist or cannot be read: " + file.getAbsolutePath());
                // Return a reader for an empty string if file is inaccessible
                return new BufferedReader(new InputStreamReader(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            }

            // Always use UTF-8 encoding
            logger.fine("Reading file '" + file.getName() + "' using UTF-8 charset");
            return new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create reader for file: " + file.getName(), ex);
        }
    }


    @Override
    public Writer getWriter() throws IOException {
         if (isReadonly()) {
             throw new IOException("Cannot get writer for read-only file: " + file.getName());
         }
        try {
            // Always use UTF-8 encoding for writing
            FileOutputStream fos = new FileOutputStream(file); // Opens the file for writing (truncates by default)
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            return new BufferedWriter(new OutputStreamWriter(bos, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new RuntimeException("Cannot create FileWriter for file: " + file.getName(), ex);
        }
    }

    @Override
    public void read() {
        // Re-initialize and read the file content again
        initializeAndRead();
    }
}
