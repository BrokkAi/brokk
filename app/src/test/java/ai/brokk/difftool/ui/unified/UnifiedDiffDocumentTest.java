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

        // 4. Verify non-existent lines
        assertEquals(-1, doc.findDocumentLineForSourceLine(99, true));
        assertEquals(-1, doc.findDocumentLineForSourceLine(99, false));
    }
}
