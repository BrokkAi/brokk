package io.github.jbellis.brokk.difftool.doc;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Element;
import javax.swing.text.BadLocationException;
import javax.swing.text.GapContent;
import javax.swing.text.PlainDocument;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class AbstractBufferDocument implements BufferDocumentIF, DocumentListener {
    private String name;
    private String shortName;
    private Line[] lineArray;
    private int[] lineOffsetArray;
    private PlainDocument document;
    private MyGapContent content;
    private final List<BufferDocumentChangeListenerIF> listeners;

    private boolean changed;
    private int originalLength;
    private int digest;

    protected AbstractBufferDocument() {
        listeners = new ArrayList<>();
    }

    // Called by subclasses after setting name/shortName
    protected void initializeAndRead() {
        if (document != null) {
            document.removeDocumentListener(this);
        }
        content = new MyGapContent(getBufferSize() + 500);
        document = new PlainDocument(content);
        // Ensure reader is available before reading
        try (Reader reader = getReader()) {
            if (reader != null) { // Check if reader could be created
                new DefaultEditorKit().read(reader, document, 0);
            } else {
                // Handle case where reader couldn't be obtained (e.g., file not found)
                // Initialize with empty content or log an error
                System.err.println("Warning: Could not obtain reader for " + getName() + ", initializing empty document.");
            }
        } catch (Exception readEx) {
            // Handle exceptions during the read process specifically
            System.err.println("Error reading content for " + getName() + ": " + readEx.getMessage());
            // Potentially fall back to an empty document state
            document = new PlainDocument(new MyGapContent(10)); // Re-init empty
        }
        document.addDocumentListener(this);
        resetLineCache();
        initLines(); // Initialize lines immediately after reading
        initDigest();
    }

    @Override
    public void addChangeListener(BufferDocumentChangeListenerIF listener) {
        listeners.add(listener);
    }

    @Override
    public void removeChangeListener(BufferDocumentChangeListenerIF listener) {
        listeners.remove(listener);
    }

    protected abstract int getBufferSize();

    @Override
    public abstract Reader getReader();

    public abstract Writer getWriter() throws Exception;

    protected void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    protected void setShortName(String shortName) {
        this.shortName = shortName;
    }

    @Override
    public String getShortName() {
        return shortName;
    }

    @Override
    public PlainDocument getDocument() {
        // Document should be initialized by initializeAndRead()
        assert document != null : "Document accessed before initialization for " + getName();
        return document;
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    @Override
    public Line[] getLines() {
        initLines(); // Ensure lines are initialized if needed
        return lineArray;
    }

    @Override
    public int getOffsetForLine(int lineNumber) {
        if (lineNumber < 0) {
            return -1;
        }
        Line[] la = getLines(); // Ensures lines are initialized
        if (la == null || la.length == 0) { // Handle empty document
            return (lineNumber == 0) ? 0 : -1; // Offset 0 for line 0 in empty doc
        }
        if (lineNumber == 0) {
            return 0;
        }
        // If requesting offset for line *after* the last line, return document length
        if (lineNumber > la.length) {
            return getDocument().getLength();
        }
        // Otherwise, return the start offset of the requested line (end offset of previous)
        return la[lineNumber - 1].getEndOffset(); // Use end offset of previous line
    }


    @Override
    public int getLineForOffset(int offset) {
        if (offset < 0) {
            return 0; // Or handle as error? Conventionally 0 for start.
        }
        // Ensure lines are initialized and document exists
        initLines();
        if (document == null || lineOffsetArray == null || lineOffsetArray.length == 0) {
            return 0; // Line 0 for empty/uninitialized document
        }

        // Handle offset beyond the document length
        if (offset >= document.getLength()) {
            // Return the last line number (0-based index, so length - 1 if lines exist)
            return Math.max(0, lineArray.length - 1);
        }

        // Use binary search on the cached start offsets
        int searchIndex = Arrays.binarySearch(lineOffsetArray, offset);

        if (searchIndex >= 0) {
            // Exact match: the offset is the start of line `searchIndex`
            return searchIndex;
        } else {
            // Not an exact match. insertionPoint = (-searchIndex) - 1.
            // The offset falls within the line *before* the insertion point.
            int insertionPoint = -searchIndex - 1;
            // If insertionPoint is 0, it's before the first line's start (so line 0)
            // Otherwise, it belongs to the line `insertionPoint - 1`
            return Math.max(0, insertionPoint - 1);
        }
    }

    // Renamed from read() to avoid confusion, called internally by subclasses
    // public void read() { initializeAndRead(); } // Keep public facade if needed?

    private void initLines() {
        if (lineArray != null) {
            return;
        }
        // Ensure document is initialized before accessing elements
        if (document == null) {
            System.err.println("Attempted to initLines before document was initialized for " + getName());
            lineArray = new Line[0];
            lineOffsetArray = new int[0];
            return;
        }
        Element paragraph = document.getDefaultRootElement();
        int size = paragraph.getElementCount();
        lineArray = new Line[size];
        lineOffsetArray = new int[lineArray.length];
        for (int i = 0; i < lineArray.length; i++) {
            Element e = paragraph.getElement(i);
            Line line = new Line(e);
            lineArray[i] = line;
            // Store the *start* offset for binary search in getLineForOffset
            lineOffsetArray[i] = line.getStartOffset();
        }
    }

    // Renamed from reset()
    protected void resetLineCache() {
        lineArray = null;
        lineOffsetArray = null;
    }

    @Override
    public void write() {
        // Ensure document exists before writing
        if (document == null) {
            throw new IllegalStateException("Cannot write document, it was not initialized: " + getName());
        }
        try (Writer out = getWriter()) {
            if (out == null) {
                throw new RuntimeException("Cannot get writer for document: " + getName());
            }
            new DefaultEditorKit().write(out, document, 0, document.getLength());
            out.flush();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to write document: " + getName(), ex);
        }
        initDigest(); // Update digest after successful write
    }

    // Inner class GapContent remains the same
    class MyGapContent extends GapContent {
        public MyGapContent(int length) {
            super(length);
        }

        char[] getCharArray() {
            return (char[]) getArray();
        }

        public char charAtOffset(int offset) {
            return charAt(getCharArray(), offset, getGapStart(), getGapEnd());
        }

        public boolean equals(MyGapContent c2, int start1, int end1, int start2) {
            char[] array1 = getCharArray();
            char[] array2 = c2.getCharArray();
            int g1_0 = getGapStart();
            int g1_1 = getGapEnd();
            int g2_0 = c2.getGapStart();
            int g2_1 = c2.getGapEnd();

            int len1 = end1 - start1;
            int current1 = start1;
            int current2 = start2;

            for (int i = 0; i < len1; i++) {
                char c1 = charAt(array1, current1++, g1_0, g1_1);
                char charVal2 = charAt(array2, current2++, g2_0, g2_1); // Renamed local variable
                if (c1 != charVal2) {
                    return false;
                }
            }
            return true;
        }

        private char charAt(char[] array, int index, int gapStart, int gapEnd) {
            if (index < gapStart) {
                return array[index];
            } else {
                return array[index + (gapEnd - gapStart)];
            }
        }

        public int hashCode(int start, int end) {
            char[] array = getCharArray();
            int g0 = getGapStart();
            int g1 = getGapEnd();
            int h = 0;
            int current = start;
            int len = end - start;

            for (int i = 0; i < len; i++) {
                char c = charAt(array, current++, g0, g1);
                h = 31 * h + c;
            }

            if (h == 0 && len > 0) { // Avoid hash 0 for non-empty strings if possible
                h = 1;
            }
            return h;
        }

        public int getDigest() {
            // Ensure document is available
            if (AbstractBufferDocument.this.document == null) return 0;
            return hashCode(0, AbstractBufferDocument.this.document.getLength());
        }
    }

    public class Line implements Comparable<Line> {
        final Element element;

        Line(Element element) {
            this.element = element;
        }

        MyGapContent getContent() {
            // Content should be initialized by initializeAndRead()
            assert content != null : "Line accessed before content was initialized";
            return content;
        }

        public int getStartOffset() {
            return element.getStartOffset();
        }

        public int getEndOffset() {
            return element.getEndOffset();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Line otherLine)) {
                return false;
            }
            Element element2 = otherLine.element;
            int start1 = this.getStartOffset();
            int end1 = this.getEndOffset();
            int start2 = element2.getStartOffset();
            int end2 = element2.getEndOffset();

            if ((end1 - start1) != (end2 - start2)) {
                return false;
            }
            // Ensure content is not null before comparing
            if (this.getContent() == null || otherLine.getContent() == null) {
                return (this.getContent() == null && otherLine.getContent() == null)
                        && ((end1 - start1) == 0); // Both null => equal only if empty lines
            }
            return this.getContent().equals(
                    otherLine.getContent(),
                    start1, end1, start2
            );
        }

        @Override
        public int hashCode() {
            // Ensure content is not null before hashing
            if (this.getContent() == null) {
                return 0; // Hash 0 for uninitialized
            }
            return this.getContent().hashCode(getStartOffset(), getEndOffset());
        }

        /**
         * Returns the text for this line. If {@code getEndOffset()} goes beyond the
         * document length (common for the last line with trailing newline),
         * we clamp the end offset to the doc length before substring retrieval.
         */
        @Override
        public String toString() {
            try {
                int start = getStartOffset();
                int end = getEndOffset();
                int docLen = AbstractBufferDocument.this.document.getLength();

                // Basic sanity checks
                if (start < 0 || end < 0 || start > docLen) {
                    return "<INVALID RANGE>";
                }

                // If end is docLen+1, that usually indicates a final trailing newline in PlainDocument
                if (end > docLen) {
                    if (end == docLen + 1) {
                        // We'll clamp substring to docLen, then manually re-append "\n" if needed
                        int length = Math.max(0, docLen - start);
                        if (length == 0) {
                            // Possibly just the very last trailing newline
                            if (docLen > 0) {
                                char lastChar = getContent().charAtOffset(docLen - 1);
                                if (lastChar == '\n') {
                                    return "\n";
                                }
                            }
                            return "";
                        }
                        // Otherwise substring from [start..docLen], then if last char is newline, append it
                        String text = getContent().getString(start, length);
                        if (docLen > 0) {
                            char lastChar = getContent().charAtOffset(docLen - 1);
                            if (lastChar == '\n') {
                                text += "\n";
                            }
                        }
                        return text;
                    } else {
                        // More than one past docLen => genuinely invalid
                        return "<INVALID RANGE>";
                    }
                }

                // Normal case: offsets are within [0..docLen]
                int length = end - start;
                if (length <= 0) {
                    return "";
                }
                return getContent().getString(start, length);

            } catch (BadLocationException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public int compareTo(Line line) {
            // Basic string comparison
            return toString().compareTo(line.toString());
        }
    }

    // DocumentListener methods
    @Override
    public void changedUpdate(DocumentEvent de) {
        documentChanged(de);
    }

    @Override
    public void insertUpdate(DocumentEvent de) {
        documentChanged(de);
    }

    @Override
    public void removeUpdate(DocumentEvent de) {
        documentChanged(de);
    }

    private void initDigest() {
        originalLength = (document != null) ? document.getLength() : 0;
        digest = (content != null) ? content.getDigest() : 0;
        changed = false;
        // Optionally notify listeners about the initial state if needed
        // fireDocumentChanged(new JMDocumentEvent(this));
    }

    private void documentChanged(DocumentEvent de) {
        JMDocumentEvent jmde = new JMDocumentEvent(this, de);

        // Reset line cache as content structure changed
        resetLineCache();
        // We could try to calculate affected lines here, but deferring to getLines() is safer
        // initLines(); // Re-init lines immediately for line number calculation

        // Calculate affected lines *after* potentially re-initializing lines
        int offset = de.getOffset();

        // Estimate affected lines - this might be complex to get perfect
        // For simplicity, we can just signal a change and let consumers re-query
        // Or, get line numbers before/after based on offset/length
        int startLine = getLineForOffset(offset); // Line where change starts
        int linesAffected = 0; // Placeholder - hard to calculate precisely here

        jmde.setStartLine(startLine);
        jmde.setNumberOfLines(linesAffected); // Indicate change, maybe not exact count

        // Determine if content actually changed based on digest
        boolean newChanged;
        if (document == null || content == null) {
            newChanged = true; // Treat as changed if document/content isn't ready
        } else if (document.getLength() != originalLength) {
            newChanged = true;
        } else {
            int newDigest = content.getDigest();
            newChanged = (newDigest != digest);
        }

        // Only update changed status and fire event if it's a real change
        // or if it was already marked as changed
        if (newChanged || changed) {
            changed = true; // Once changed, stays changed until saved/reverted
            fireDocumentChanged(jmde);
        }
    }

    private void fireDocumentChanged(JMDocumentEvent de) {
        // Create copy of listeners to avoid ConcurrentModificationException
        List<BufferDocumentChangeListenerIF> listenersCopy = new ArrayList<>(listeners);
        for (BufferDocumentChangeListenerIF listener : listenersCopy) {
            listener.documentChanged(de);
        }
    }

    @Override
    public boolean isReadonly() {
        return true; // Default for abstract class, subclasses override
    }

    @Override
    public String getLineText(int lineNumber) {
        Line[] la = getLines(); // Ensures lines are initialized
        if (la == null || lineNumber < 0 || lineNumber >= la.length) {
            System.err.println("getLineText: Invalid line number " + lineNumber + " for document " + getName());
            return "<INVALID LINE>";
        }
        return la[lineNumber].toString();
    }

    @Override
    public int getNumberOfLines() {
        return getLines().length; // Ensures lines are initialized
    }

    /**
     * Returns the entire document as a list of strings, one per line.
     * This is used directly by the diff logic.
     */
    @Override
    public List<String> getLineList() {
        initLines(); // Ensures lines are initialized
        // Handle potentially null lineArray after failed init
        if (lineArray == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(lineArray.length);
        for (Line line : lineArray) {
            // Line.toString() handles internal errors
            result.add(line.toString());
        }
        return result;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[name=" + name + "]";
    }
}
