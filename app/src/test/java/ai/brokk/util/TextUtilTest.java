package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextUtilTest {
    @Test
    void testCountWords() {
        assertEquals(0, TextUtil.countWords(null));
        assertEquals(0, TextUtil.countWords(""));
        assertEquals(0, TextUtil.countWords("   "));
        assertEquals(1, TextUtil.countWords("Hello"));
        assertEquals(2, TextUtil.countWords("Hello world"));
        assertEquals(2, TextUtil.countWords("  Hello   world  "));
        assertEquals(3, TextUtil.countWords("One\ntwo\tthree"));
    }
}
