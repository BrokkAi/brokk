package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WhitespaceMatchTest {

    @Test
    void testFindAll() {
        String[] whole = {"    line1", "        line2", "line1", "  line2"};
        String[] part = {"line1 ", "    line2"};

        var results = WhitespaceMatch.findAll(whole, part);

        assertEquals(2, results.size());

        assertEquals(0, results.get(0).startLine());
        assertEquals("    line1\n        line2", results.get(0).matchedText());

        assertEquals(2, results.get(1).startLine());
        assertEquals("line1\n  line2", results.get(1).matchedText());
    }

    @Test
    void testNoMatch() {
        String[] whole = {"a", "b"};
        String[] part = {"c"};
        assertTrue(WhitespaceMatch.findAll(whole, part).isEmpty());
    }
}
