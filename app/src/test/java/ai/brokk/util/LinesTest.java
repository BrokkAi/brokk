package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LinesTest {
    @Test
    void rangeUsesInclusiveOneBasedEndLine() {
        String content = "a\nb\nc\n";
        assertEquals("1: a\n", Lines.range(content, 1, 1).text());
        assertEquals("2: b\n3: c\n", Lines.range(content, 2, 3).text());
        assertEquals(2, Lines.range(content, 2, 3).lineCount());
    }

    @Test
    void rangeAllowsEndBeyondFileLength() {
        String content = "a\nb\nc";
        var result = Lines.range(content, 2, 99);
        assertEquals("2: b\n3: c\n", result.text());
        assertEquals(2, result.lineCount());
    }

    @Test
    void rangeReturnsEmptyWhenStartIsPastEndOfContent() {
        String content = "a\nb\nc";
        var result = Lines.range(content, 5, 7);
        assertEquals("", result.text());
        assertEquals(0, result.lineCount());
    }

    @Test
    void rangeReturnsEmptyForEmptyContent() {
        var result = Lines.range("", 1, 1);
        assertEquals("", result.text());
        assertEquals(0, result.lineCount());
    }

    @Test
    void rangeTreatsCrLfAsSingleLineBreak() {
        String content = "a\r\nb\r\nc";
        var result = Lines.range(content, 1, 2);
        assertEquals("1: a\n2: b\n", result.text());
        assertEquals(2, result.lineCount());
    }

    @Test
    void rangeRejectsInvalidBounds() {
        IllegalArgumentException lowStart = assertThrows(IllegalArgumentException.class, () -> Lines.range("x", 0, 1));
        assertEquals("oneBasedStartInclusive must be >= 1", lowStart.getMessage());

        IllegalArgumentException reversed = assertThrows(IllegalArgumentException.class, () -> Lines.range("x", 2, 1));
        assertEquals("oneBasedEndInclusive must be >= oneBasedStartInclusive", reversed.getMessage());
    }
}
