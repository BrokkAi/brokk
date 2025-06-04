
package io.github.jbellis.brokk.difftool.doc;


import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public class FileDocument
        extends AbstractBufferDocument {
    private static final Logger logger = Logger.getLogger(FileDocument.class.getName());
    private static final int CHARSET_DETECTION_CONFIDENCE_THRESHOLD = 50;
    private static final int DETECTION_BUFFER_SIZE = 1024 * 1024;
    private static final int DEFAULT_BUFFER_SIZE = 1024;
    
    // instance variables:
    private final File file;
    private volatile Charset charset;
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

            // Use try-with-resources for the InputStream
            try (InputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {

                // Detect charset *without* consuming the stream if possible,
                // or reset it if necessary and supported.
                bis.mark(DETECTION_BUFFER_SIZE); // Mark a large enough buffer for detection
                this.charset = detectCharsetSafely(bis);
                bis.reset(); // Reset stream to the beginning

                // MUST return a NEW InputStreamReader each time, as the underlying stream (bis)
                // might be closed by the caller (e.g., DefaultEditorKit.read).
                // We re-open the file here to ensure a fresh stream.
                logger.fine("Reading file '" + file.getName() + "' using charset: " + this.charset.name());
                return new BufferedReader(new InputStreamReader(new FileInputStream(file), this.charset));
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create reader for file: " + file.getName(), ex);
        }
    }

    private Charset detectCharsetSafely(BufferedInputStream bis) {
        try {
            return detectCharset(bis);
        } catch (Exception ex) {
            logger.warning("Charset detection failed for " + file.getName() + ", using UTF-8: " + ex.getMessage());
            return StandardCharsets.UTF_8;
        }
    }

    private Charset detectCharset(BufferedInputStream bis) throws IOException {
        var detector = new CharsetDetector();
        detector.setText(bis);
        return Optional.ofNullable(detector.detect())
                .filter(match -> {
                    return match.getConfidence() >= CHARSET_DETECTION_CONFIDENCE_THRESHOLD;
                })
                .map(CharsetMatch::getName)
                .filter(Charset::isSupported)
                .map(Charset::forName)
                .orElseGet(() -> {
                    logger.info("Using UTF-8 fallback for file: " + file.getName() + " (low confidence or detection failed)");
                    return StandardCharsets.UTF_8;
                });
    }

    @Override
    public Writer getWriter() throws IOException {
         if (isReadonly()) {
             throw new IOException("Cannot get writer for read-only file: " + file.getName());
         }
        try {
            // Ensure the detected or default charset is used for writing
            Charset effectiveCharset = (this.charset != null) ? this.charset : StandardCharsets.UTF_8;
            // Use try-with-resources for the output streams
            FileOutputStream fos = new FileOutputStream(file); // Opens the file for writing (truncates by default)
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            return new BufferedWriter(new OutputStreamWriter(bos, effectiveCharset));
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
