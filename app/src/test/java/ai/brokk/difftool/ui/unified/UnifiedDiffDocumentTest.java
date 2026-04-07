package ai.brokk.difftool.ui.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.difftool.ui.unified.UnifiedDiffDocument.ContextMode;
import ai.brokk.difftool.ui.unified.UnifiedDiffDocument.DiffLine;
import ai.brokk.difftool.ui.unified.UnifiedDiffDocument.LineType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnifiedDiffDocumentTest {

    @Test
    @DisplayName("Verify findDocumentLineForSourceLine correctly maps source lines to document lines")
    void testFindDocumentLineForSourceLine() {
        // Setup a diff:
        // Doc Line 0: @@ -1,3 +1,3 @@ (HEADER)
        // Doc Line 1:  Unchanged       (CONTEXT)  Old: 1, New: 1
        // Doc Line 2: -Removed         (DELETION) Old: 2, New: -1
        // Doc Line 3: +Added           (ADDITION) Old: -1, New: 2
        // Doc Line 4:  Another         (CONTEXT)  Old: 3, New: 3

        List<DiffLine> lines = List.of(
                new DiffLine(LineType.HEADER, "@@ -1,3 +1,3 @@", -1, -1, false),
                new DiffLine(LineType.CONTEXT, " Unchanged", 1, 1, false),
                new DiffLine(LineType.DELETION, "-Removed", 2, -1, true),
                new DiffLine(LineType.ADDITION, "+Added", -1, 2, true),
                new DiffLine(LineType.CONTEXT, " Another", 3, 3, false));

        var doc = new UnifiedDiffDocument(lines, ContextMode.FULL_CONTEXT);

        // 1. Verify CONTEXT lines (exist on both sides)
        assertEquals(1, doc.findDocumentLineForSourceLine(1, false), "Old line 1 should be doc line 1");
        assertEquals(1, doc.findDocumentLineForSourceLine(1, true), "New line 1 should be doc line 1");
        assertEquals(4, doc.findDocumentLineForSourceLine(3, false), "Old line 3 should be doc line 4");
        assertEquals(4, doc.findDocumentLineForSourceLine(3, true), "New line 3 should be doc line 4");

        // 2. Verify DELETION line (Old side only)
        assertEquals(2, doc.findDocumentLineForSourceLine(2, false), "Old line 2 should be doc line 2");

        // 3. Verify ADDITION line (New side only)
        assertEquals(3, doc.findDocumentLineForSourceLine(2, true), "New line 2 should be doc line 3");
        // Note: Searching for sourceLine=2 on the old side finds the DELETION (old line 2), not the ADDITION
        assertEquals(2, doc.findDocumentLineForSourceLine(2, false), "Old line 2 (deletion) should be doc line 2");

        // 4. Verify non-existent lines
        assertEquals(-1, doc.findDocumentLineForSourceLine(99, true));
        assertEquals(-1, doc.findDocumentLineForSourceLine(99, false));
    }

    @Test
    @DisplayName("Verify mapping with multiple hunks and context mode switching")
    void testMultipleHunksAndContextSwitching() {
        // Hunk 1: lines 1-5
        // ... omitted ...
        // Hunk 2: lines 20-25
        List<DiffLine> lines = List.of(
                new DiffLine(LineType.HEADER, "@@ -1,5 +1,5 @@", -1, -1, false),
                new DiffLine(LineType.CONTEXT, " line1", 1, 1, false),
                new DiffLine(LineType.DELETION, "-line2", 2, -1, true),
                new DiffLine(LineType.ADDITION, "+line2-new", -1, 2, true),
                new DiffLine(LineType.CONTEXT, " line3", 3, 3, false),
                new DiffLine(LineType.OMITTED_LINES, "... 10 lines omitted ...", -1, -1, false),
                new DiffLine(LineType.HEADER, "@@ -20,3 +20,3 @@", -1, -1, false),
                new DiffLine(LineType.CONTEXT, " line20", 20, 20, false),
                new DiffLine(LineType.DELETION, "-line21", 21, -1, true),
                new DiffLine(LineType.ADDITION, "+line21-new", -1, 21, true));

        // Test STANDARD_3_LINES mode
        var doc = new UnifiedDiffDocument(lines, ContextMode.STANDARD_3_LINES);
        assertEquals(10, doc.getLineCount());
        assertEquals(7, doc.findDocumentLineForSourceLine(20, true), "Line 20 should be doc line 7");
        assertEquals(5, doc.findDocumentLineForSourceLine(-1, true), "Omitted lines indicator should not map");

        // Test FULL_CONTEXT mode (removes OMITTED_LINES)
        doc.switchContextMode(ContextMode.FULL_CONTEXT);
        assertEquals(9, doc.getLineCount());
        assertEquals(
                6, doc.findDocumentLineForSourceLine(20, true), "Line 20 should shift to doc line 6 after switching");
        assertEquals(8, doc.findDocumentLineForSourceLine(21, true), "Line 21 addition should be doc line 8");
    }

    @Test
    @DisplayName("Verify line mapping for a completely new file")
    void testNewFileMapping() {
        List<DiffLine> lines = List.of(
                new DiffLine(LineType.HEADER, "@@ -0,0 +1,3 @@", -1, -1, false),
                new DiffLine(LineType.ADDITION, "+New Line 1", -1, 1, true),
                new DiffLine(LineType.ADDITION, "+New Line 2", -1, 2, true),
                new DiffLine(LineType.ADDITION, "+New Line 3", -1, 3, true));

        var doc = new UnifiedDiffDocument(lines, ContextMode.FULL_CONTEXT);

        assertEquals(-1, doc.findDocumentLineForSourceLine(1, false), "Old side should have no lines");
        assertEquals(1, doc.findDocumentLineForSourceLine(1, true));
        assertEquals(2, doc.findDocumentLineForSourceLine(2, true));
        assertEquals(3, doc.findDocumentLineForSourceLine(3, true));
    }

    @Test
    @DisplayName("Verify line mapping for a completely deleted file")
    void testDeletedFileMapping() {
        List<DiffLine> lines = List.of(
                new DiffLine(LineType.HEADER, "@@ -1,3 +0,0 @@", -1, -1, false),
                new DiffLine(LineType.DELETION, "-Old Line 1", 1, -1, true),
                new DiffLine(LineType.DELETION, "-Old Line 2", 2, -1, true),
                new DiffLine(LineType.DELETION, "-Old Line 3", 3, -1, true));

        var doc = new UnifiedDiffDocument(lines, ContextMode.FULL_CONTEXT);

        assertEquals(-1, doc.findDocumentLineForSourceLine(1, true), "New side should have no lines");
        assertEquals(1, doc.findDocumentLineForSourceLine(1, false));
        assertEquals(2, doc.findDocumentLineForSourceLine(2, false));
        assertEquals(3, doc.findDocumentLineForSourceLine(3, false));
    }

    @Test
    @DisplayName("Verify original line number retrieval at hunk boundaries")
    void testOriginalLineNumbers() {
        List<DiffLine> lines = List.of(
                new DiffLine(LineType.HEADER, "@@ -10,2 +10,2 @@", -1, -1, false),
                new DiffLine(LineType.CONTEXT, " Context", 10, 10, false),
                new DiffLine(LineType.DELETION, "-Deleted", 11, -1, true),
                new DiffLine(LineType.ADDITION, "+Added", -1, 11, true));

        var doc = new UnifiedDiffDocument(lines, ContextMode.FULL_CONTEXT);

        assertEquals(-1, doc.getOriginalLineNumber(0), "Header should map to -1");
        assertEquals(10, doc.getOriginalLineNumber(1), "Context line should map to Right side line 10");
        assertEquals(11, doc.getOriginalLineNumber(2), "Deletion should map to Left side line 11");
        assertEquals(11, doc.getOriginalLineNumber(3), "Addition should map to Right side line 11");
    }
}
