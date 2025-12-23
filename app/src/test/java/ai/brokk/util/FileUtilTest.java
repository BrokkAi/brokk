package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for newline-aware helpers in FileUtil.
 *
 * Validates:
 *  - computeLineStarts handles LF, CRLF, and CR line separators
 *  - computeLineStarts length equals content.split("\\R", -1).length (keeps trailing empty lines)
 *  - findLineIndexForOffset returns correct 0-based line index across offsets, including around separators
 */
public class FileUtilTest {

    @Test
    public void computeLineStarts_handlesLF_CRLF_CR_andTrailingEmpty() {
        // LF only, with blank line and final non-terminated line
        String lf = "a\nb\n\nc";
        // positions:             0 1 2 3 4 5
        // starts at char offsets: [0, 2, 4, 5]
        assertArrayEquals(new int[] {0, 2, 4, 5}, FileUtil.computeLineStarts(lf), "LF: incorrect line starts");
        assertEquals(
                lf.split("\\R", -1).length,
                FileUtil.computeLineStarts(lf).length,
                "LF: split count must equal lineStarts length");

        // CRLF only, final non-terminated line
        String crlf = "ab\r\nc\r\nd";
        // positions:                0 1 2 3 4 5 6 7
        // starts at char offsets: [0, 4, 7]
        assertArrayEquals(new int[] {0, 4, 7}, FileUtil.computeLineStarts(crlf), "CRLF: incorrect line starts");
        assertEquals(
                crlf.split("\\R", -1).length,
                FileUtil.computeLineStarts(crlf).length,
                "CRLF: split count must equal lineStarts length");

        // CR only, final non-terminated line
        String cr = "x\ry\rz";
        // positions:        0 1 2 3 4
        // starts:          [0, 2, 4]
        assertArrayEquals(new int[] {0, 2, 4}, FileUtil.computeLineStarts(cr), "CR: incorrect line starts");
        assertEquals(
                cr.split("\\R", -1).length,
                FileUtil.computeLineStarts(cr).length,
                "CR: split count must equal lineStarts length");

        // Trailing separator should yield a trailing empty line start
        String trailing = "a\r\nb\r\n";
        // positions:           0 1 2 3 4 5
        // starts:             [0, 3, 6]
        assertArrayEquals(
                new int[] {0, 3, 6},
                FileUtil.computeLineStarts(trailing),
                "Trailing CRLF must produce trailing empty line start");
        assertEquals(
                trailing.split("\\R", -1).length,
                FileUtil.computeLineStarts(trailing).length,
                "Trailing CRLF: split count must equal lineStarts length");
    }

    @Test
    public void findLineIndexForOffset_consistentAcrossOffsets() {
        // Mixed: CRLF, LF, CR
        String s = "ab\r\nc\nd\re";
        // positions:           0 1 2 3 4 5 6 7 8
        // chars:               a b \r \n c \n d \r e
        // starts:             [0, 4, 6, 8]
        int[] expectedStarts = new int[] {0, 4, 6, 8};
        assertArrayEquals(expectedStarts, FileUtil.computeLineStarts(s), "Incorrect computed starts");

        int[] starts = FileUtil.computeLineStarts(s);
        for (int offset = 0; offset < s.length(); offset++) {
            int idx = FileUtil.findLineIndexForOffset(starts, offset);
            int expectedIdx = expectedLineIndexLinear(starts, offset);
            assertEquals(expectedIdx, idx, "Mismatch at offset " + offset);
        }

        // Spot-check offsets near separators
        // offset 2 = '\r' in first CRLF -> should still belong to line 0
        assertEquals(0, FileUtil.findLineIndexForOffset(starts, 2), "CR in CRLF should map to previous line");
        // offset 3 = '\n' in first CRLF -> should still belong to line 0
        assertEquals(0, FileUtil.findLineIndexForOffset(starts, 3), "LF in CRLF should map to previous line");
        // offset 5 = '\n' after 'c' -> should belong to line 1
        assertEquals(1, FileUtil.findLineIndexForOffset(starts, 5), "LF should map to previous line");
        // offset 7 = '\r' after 'd' -> should belong to line 2
        assertEquals(2, FileUtil.findLineIndexForOffset(starts, 7), "CR should map to previous line");
    }

    @Test
    public void edgeCases_emptyAndOnlyNewlines() {
        // empty
        String empty = "";
        assertArrayEquals(
                new int[] {0},
                FileUtil.computeLineStarts(empty),
                "Empty string should have a single line starting at 0");
        assertEquals(1, empty.split("\\R", -1).length, "Empty string should split() into a single empty line");

        // only LF
        String onlyLf = "\n\n";
        assertArrayEquals(
                new int[] {0, 1, 2}, FileUtil.computeLineStarts(onlyLf), "Two LFs should produce 3 line starts");
        assertEquals(3, onlyLf.split("\\R", -1).length, "Two LFs should yield 3 lines via split()");

        // only CRLF
        String onlyCrlf = "\r\n\r\n";
        assertArrayEquals(
                new int[] {0, 2, 4}, FileUtil.computeLineStarts(onlyCrlf), "Two CRLFs should produce 3 line starts");
        assertEquals(3, onlyCrlf.split("\\R", -1).length, "Two CRLFs should yield 3 lines via split()");

        // only CR
        String onlyCr = "\r\r";
        assertArrayEquals(
                new int[] {0, 1, 2}, FileUtil.computeLineStarts(onlyCr), "Two CRs should produce 3 line starts");
        assertEquals(3, onlyCr.split("\\R", -1).length, "Two CRs should yield 3 lines via split()");
    }

    /**
     * Reference helper for expected line index: last i where starts[i] <= offset.
     * Linear scan is fine for test purposes.
     */
    private static int expectedLineIndexLinear(int[] starts, int offset) {
        int idx = 0;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] <= offset) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }
}
