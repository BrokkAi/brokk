package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class CodeQualityToolsMcpTest {

    @Test
    void sanitizeTableCellEscapesPipes() {
        assertEquals("a\\|b", CodeQualityToolsMcp.sanitizeTableCell("a|b"));
    }

    @Test
    void sanitizeTableCellReplacesBackticksWithApostrophes() {
        // Backticks would let attacker-controlled values escape from a Markdown inline-code span.
        String result = CodeQualityToolsMcp.sanitizeTableCell("bad`branch`");
        assertFalse(result.contains("`"), result);
        assertEquals("bad'branch'", result);
    }

    @Test
    void sanitizeTableCellReplacesControlCharsWithSpaces() {
        assertEquals("a b c", CodeQualityToolsMcp.sanitizeTableCell("a\nb\tc"));
        assertEquals("x y", CodeQualityToolsMcp.sanitizeTableCell("x\ry"));
    }

    @Test
    void sanitizeTableCellHandlesEmpty() {
        assertEquals("", CodeQualityToolsMcp.sanitizeTableCell(""));
    }

    @Test
    void sanitizeTableCellLeavesSafeAsciiUnchanged() {
        String safe = "abc 123 / - _ . , : ; @ # $ % ^ & * ( ) [ ] { } < > ? ! = +";
        assertEquals(safe, CodeQualityToolsMcp.sanitizeTableCell(safe));
    }
}
