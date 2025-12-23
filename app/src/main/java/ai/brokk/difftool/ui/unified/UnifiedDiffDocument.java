package ai.brokk.difftool.ui.unified;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.jetbrains.annotations.Nullable;

/**
 * Custom document model for unified diff display that manages diff lines with context mode switching. This document
 * handles the display of unified diff content with support for both 3-line context and full context modes.
 */
public class UnifiedDiffDocument extends RSyntaxDocument {

    public enum ContextMode {
        STANDARD_3_LINES,
        FULL_CONTEXT
    }

    public enum LineType {
        CONTEXT, // Unchanged line (prefix: space)
        ADDITION, // Added line (prefix: +)
        DELETION, // Deleted line (prefix: -)
        HEADER, // Hunk header (@@)
        OMITTED_LINES // "... N lines omitted ..." indicator
    }

    /** Represents a single line in the unified diff with metadata. */
    public static class DiffLine {
        private final LineType type;
        private final String content;
        private final int leftLineNumber; // -1 if not applicable
        private final int rightLineNumber; // -1 if not applicable
        private final boolean isEditable; // Can this line be edited?

        public DiffLine(LineType type, String content, int leftLineNumber, int rightLineNumber, boolean isEditable) {
            this.type = type;
            this.content = content;
            this.leftLineNumber = leftLineNumber;
            this.rightLineNumber = rightLineNumber;
            this.isEditable = isEditable;
        }

        public LineType getType() {
            return type;
        }

        public String getContent() {
            return content;
        }

        public int getLeftLineNumber() {
            return leftLineNumber;
        }

        public int getRightLineNumber() {
            return rightLineNumber;
        }

        public boolean isEditable() {
            return isEditable;
        }

        @Override
        public String toString() {
            return String.format(
                    "DiffLine{type=%s, content='%s', left=%d, right=%d, editable=%s}",
                    type,
                    content.length() > 20 ? content.substring(0, 20) + "..." : content,
                    leftLineNumber,
                    rightLineNumber,
                    isEditable);
        }
    }

    private final List<DiffLine> allDiffLines;
    private final Map<Integer, Integer> documentLineToOriginalLine;
    private ContextMode contextMode;
    private List<DiffLine> filteredLines; // Lines currently displayed based on context mode

    /**
     * Creates a new unified diff document.
     *
     * @param diffLines All diff lines (including omitted line indicators for standard mode)
     * @param contextMode Initial context mode
     */
    public UnifiedDiffDocument(List<DiffLine> diffLines, ContextMode contextMode) {
        super(SYNTAX_STYLE_NONE);
        this.allDiffLines = new ArrayList<>(diffLines);
        this.contextMode = contextMode;
        this.documentLineToOriginalLine = new HashMap<>();
        this.filteredLines = new ArrayList<>();

        rebuildDocument();
    }

    /**
     * Switch between context modes and rebuild the document content.
     *
     * @param newMode The new context mode
     */
    public void switchContextMode(ContextMode newMode) {
        if (this.contextMode != newMode) {
            this.contextMode = newMode;
            rebuildDocument();
        }
    }

    /** Get the current context mode. */
    public ContextMode getContextMode() {
        return contextMode;
    }

    /** Rebuild the document content based on current context mode. */
    private void rebuildDocument() {
        try {
            // Clear current content
            if (getLength() > 0) {
                remove(0, getLength());
            }

            // Filter lines based on context mode
            filterLines();

            // Build line mapping
            buildLineMapping();

            // Insert new content
            if (!filteredLines.isEmpty()) {
                var content = new StringBuilder();
                for (var line : filteredLines) {
                    content.append(line.getContent());
                    if (!line.getContent().endsWith("\n")) {
                        content.append('\n');
                    }
                }

                if (content.length() > 0) {
                    insertString(0, content.toString(), null);
                }
            }

        } catch (BadLocationException e) {
            throw new RuntimeException("Failed to rebuild unified diff document", e);
        }
    }

    /** Filter lines based on current context mode. */
    private void filterLines() {
        filteredLines.clear();

        if (contextMode == ContextMode.FULL_CONTEXT) {
            // Full context mode: show all lines except omitted line indicators
            for (var line : allDiffLines) {
                if (line.getType() != LineType.OMITTED_LINES) {
                    filteredLines.add(line);
                }
            }
        } else {
            // Standard mode: show all lines including omitted indicators
            filteredLines.addAll(allDiffLines);
        }
    }

    /** Build mapping from document line numbers to original file line numbers. */
    private void buildLineMapping() {
        documentLineToOriginalLine.clear();

        for (int i = 0; i < filteredLines.size(); i++) {
            var line = filteredLines.get(i);

            // Map to right line number for additions and context,
            // left line number for deletions, -1 for headers and omitted lines
            int originalLineNumber =
                    switch (line.getType()) {
                        case ADDITION, CONTEXT -> line.getRightLineNumber();
                        case DELETION -> line.getLeftLineNumber();
                        case HEADER, OMITTED_LINES -> -1;
                    };

            documentLineToOriginalLine.put(i, originalLineNumber);
        }
    }

    /**
     * Get the diff line at the specified document line number.
     *
     * @param documentLineNumber 0-based line number in the document
     * @return The DiffLine or null if invalid line number
     */
    @Nullable
    public DiffLine getDiffLine(int documentLineNumber) {
        if (documentLineNumber >= 0 && documentLineNumber < filteredLines.size()) {
            return filteredLines.get(documentLineNumber);
        }
        return null;
    }

    /**
     * Check if a line is editable.
     *
     * @param documentLineNumber 0-based line number in the document
     * @return true if the line can be edited
     */
    public boolean isLineEditable(int documentLineNumber) {
        var diffLine = getDiffLine(documentLineNumber);
        return diffLine != null && diffLine.isEditable();
    }

    /**
     * Get the original file line number for a document line.
     *
     * @param documentLineNumber 0-based line number in the document
     * @return Original file line number, or -1 if not applicable
     */
    public int getOriginalLineNumber(int documentLineNumber) {
        return documentLineToOriginalLine.getOrDefault(documentLineNumber, -1);
    }

    /** Get all currently filtered lines. */
    public List<DiffLine> getFilteredLines() {
        return new ArrayList<>(filteredLines);
    }

    /** Get the total number of lines in the document. */
    public int getLineCount() {
        return filteredLines.size();
    }

    /**
     * Find all hunk header line numbers in the current filtered view.
     *
     * @return List of 0-based line numbers where hunk headers appear
     */
    public List<Integer> getHunkHeaderLines() {
        var hunkLines = new ArrayList<Integer>();
        for (int i = 0; i < filteredLines.size(); i++) {
            if (filteredLines.get(i).getType() == LineType.HEADER) {
                hunkLines.add(i);
            }
        }
        return hunkLines;
    }
}
