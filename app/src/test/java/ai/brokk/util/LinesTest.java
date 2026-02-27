package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LinesTest {
    @Test
    void rangeUsesInclusiveOneBasedEndLine() {
        String content = "a\nb\nc\n";
        assertEquals("a\n", Lines.range(content, 1, 1));
        assertEquals("b\nc\n", Lines.range(content, 2, 3));
    }

    @Test
    void rangeAllowsEndBeyondFileLength() {
        String content = "a\nb\nc";
        assertEquals("b\nc", Lines.range(content, 2, 99));
    }

    @Test
    void rangeReturnsEmptyWhenStartIsPastEndOfContent() {
        String content = "a\nb\nc";
        assertEquals("", Lines.range(content, 5, 7));
    }

    @Test
    void rangeTreatsCrLfAsSingleLineBreak() {
        String content = "a\r\nb\r\nc";
        assertEquals("a\r\nb\r\n", Lines.range(content, 1, 2));
    }

    @Test
    void rangeReturnsEmptyForEmptyContent() {
        assertEquals("", Lines.range("", 1, 1));
    }

    @Test
    void rangeRejectsInvalidBounds() {
        IllegalArgumentException lowStart = assertThrows(IllegalArgumentException.class, () -> Lines.range("x", 0, 1));
        assertEquals("oneBasedStartInclusive must be >= 1", lowStart.getMessage());

        IllegalArgumentException reversed = assertThrows(IllegalArgumentException.class, () -> Lines.range("x", 2, 1));
        assertEquals("oneBasedEndInclusive must be >= oneBasedStartInclusive", reversed.getMessage());
    }
}
