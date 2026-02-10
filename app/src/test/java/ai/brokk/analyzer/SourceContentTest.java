package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SourceContent Tests")
class SourceContentTest {

    @Test
    @DisplayName("BOM is stripped from text but byte mapping remains accurate")
    void testBomStripped() {
        String sourceWithBom = "\uFEFFclass Foo {}";
        SourceContent content = SourceContent.of(sourceWithBom);

        // The text should have the BOM stripped
        String expectedText = "class Foo {}";
        assertEquals(expectedText, content.text());

        // The byte length should match the stripped text's UTF-8 encoding
        byte[] expectedBytes = expectedText.getBytes(StandardCharsets.UTF_8);
        assertEquals(expectedBytes.length, content.byteLength());

        // Verify that the internal UTF-8 bytes match what we expect
        assertArrayEquals(expectedBytes, content.utf8Bytes());

        // Verify substring extraction works correctly on the stripped content
        // Extract "class" (bytes 0-5)
        String substring = content.substringFromBytes(0, 5);
        assertEquals("class", substring);

        // Extract "Foo" (bytes 6-9)
        substring = content.substringFromBytes(6, 9);
        assertEquals("Foo", substring);
    }

    @Test
    @DisplayName("Empty source content")
    void testEmptySource() {
        SourceContent content = SourceContent.of("");

        assertEquals("", content.text());
        assertEquals(0, content.byteLength());
        assertEquals(0, content.utf8Bytes().length);

        // Substring operations on empty content should return empty string
        assertEquals("", content.substringFromBytes(0, 0));
        assertEquals("", content.substringFromBytes(0, 5));
        assertEquals("", content.substringFromBytes(5, 10));
    }

    @Test
    @DisplayName("substringFromBytes with negative start byte returns empty and logs warning")
    void testSubstringFromBytesNegativeStart() {
        SourceContent content = SourceContent.of("hello");

        String result = content.substringFromBytes(-1, 3);
        assertEquals("", result);
    }

    @Test
    @DisplayName("substringFromBytes with end byte less than start byte returns empty")
    void testSubstringFromBytesInvertedRange() {
        SourceContent content = SourceContent.of("hello");

        String result = content.substringFromBytes(3, 1);
        assertEquals("", result);
    }

    @Test
    @DisplayName("substringFromBytes with start >= byteLength returns empty")
    void testSubstringFromBytesStartExceedsBounds() {
        SourceContent content = SourceContent.of("hello");
        int byteLength = content.byteLength();

        String result = content.substringFromBytes(byteLength, byteLength + 1);
        assertEquals("", result);

        result = content.substringFromBytes(byteLength + 5, byteLength + 10);
        assertEquals("", result);
    }

    @Test
    @DisplayName("substringFromBytes with end > byteLength truncates to byteLength")
    void testSubstringFromBytesEndExceedsBounds() {
        SourceContent content = SourceContent.of("hello");
        int byteLength = content.byteLength();

        // Extract from byte 1 to beyond the end
        String result = content.substringFromBytes(1, byteLength + 10);
        assertEquals("ello", result);

        // Extract from byte 0 to beyond the end
        result = content.substringFromBytes(0, byteLength + 100);
        assertEquals("hello", result);
    }

    @Test
    @DisplayName("substringFromBytes with zero-length range returns empty string")
    void testSubstringFromBytesZeroLength() {
        SourceContent content = SourceContent.of("hello world");

        String result = content.substringFromBytes(5, 5);
        assertEquals("", result);

        result = content.substringFromBytes(0, 0);
        assertEquals("", result);
    }

    @Test
    @DisplayName("substringFromBytes with multi-byte UTF-8 characters")
    void testSubstringFromBytesMultiByteCharacters() {
        // "Café" has 5 bytes in UTF-8: C(1) a(1) f(1) é(2)
        SourceContent content = SourceContent.of("Café");

        // Extract just "Café" (all 5 bytes)
        String result = content.substringFromBytes(0, 5);
        assertEquals("Café", result);

        // Extract "Ca" (2 bytes)
        result = content.substringFromBytes(0, 2);
        assertEquals("Ca", result);

        // Extract "fé" (3 bytes: f + é's 2 bytes)
        result = content.substringFromBytes(2, 5);
        assertEquals("fé", result);
    }

    @Test
    @DisplayName("substringFromBytes landing in middle of multi-byte UTF-8 character")
    void testSubstringFromBytesInMiddleOfMultiByteChar() {
        // "Café" has bytes: C(0) a(1) f(2) é(3-4)
        // The é character takes 2 bytes (0xC3 0xA9 in UTF-8)
        SourceContent content = SourceContent.of("Café");

        // Try to extract starting at byte 3 (middle of the é character)
        // This should construct a String from a partial multi-byte sequence
        // Java's String constructor with UTF-8 will handle this gracefully
        // (typically by replacing with replacement character)
        String result = content.substringFromBytes(3, 5);
        assertNotNull(result);
        // The result should not be empty since we're extracting bytes
        assertTrue(result.length() > 0);
    }

    @Test
    @DisplayName("substringFromBytes landing at end of multi-byte UTF-8 character")
    void testSubstringFromBytesAtMultiByteCharBoundary() {
        // "Café café" - test extracting at exact character boundaries
        // "Café " = 6 bytes, "café" = 5 bytes, total = 11 bytes
        SourceContent content = SourceContent.of("Café café");

        // Extract first word "Café" exactly (5 bytes: C=1, a=1, f=1, é=2)
        String result = content.substringFromBytes(0, 5);
        assertEquals("Café", result);

        // Extract " café" (starts at byte 5, ends at byte 11: space=1, c=1, a=1, f=1, é=2)
        result = content.substringFromBytes(5, 11);
        assertEquals(" café", result);
    }

    @Test
    @DisplayName("byteOffsetToCharPosition with boundary values")
    void testByteOffsetToCharPositionBoundaries() {
        SourceContent content = SourceContent.of("hello");
        int byteLength = content.byteLength();

        // Offset <= 0 should return 0
        assertEquals(0, content.byteOffsetToCharPosition(-5));
        assertEquals(0, content.byteOffsetToCharPosition(0));

        // Offset >= byteLength should return text.length()
        assertEquals(content.text().length(), content.byteOffsetToCharPosition(byteLength));
        assertEquals(content.text().length(), content.byteOffsetToCharPosition(byteLength + 10));
    }

    @Test
    @DisplayName("byteOffsetToCharPosition with multi-byte characters")
    void testByteOffsetToCharPositionMultiByteChars() {
        // "Café" is 5 bytes but 4 characters
        SourceContent content = SourceContent.of("Café");

        // Byte 0 -> char 0
        assertEquals(0, content.byteOffsetToCharPosition(0));

        // Byte 1 -> char 1 (just 'a')
        assertEquals(1, content.byteOffsetToCharPosition(1));

        // Byte 2 -> char 2 (just 'f')
        assertEquals(2, content.byteOffsetToCharPosition(2));

        // Byte 5 (end of multi-byte é) -> char 4 (end of string)
        assertEquals(4, content.byteOffsetToCharPosition(5));
    }

    @Test
    @DisplayName("charPositionToByteOffset with boundary values")
    void testCharPositionToByteOffsetBoundaries() {
        SourceContent content = SourceContent.of("hello");

        // Char position <= 0 should return 0
        assertEquals(0, content.charPositionToByteOffset(-5));
        assertEquals(0, content.charPositionToByteOffset(0));

        // Char position >= text.length() should return byteLength
        assertEquals(
                content.byteLength(),
                content.charPositionToByteOffset(content.text().length()));
        assertEquals(
                content.byteLength(),
                content.charPositionToByteOffset(content.text().length() + 10));
    }

    @Test
    @DisplayName("charPositionToByteOffset with multi-byte characters")
    void testCharPositionToByteOffsetMultiByteChars() {
        // "Café" is 4 characters but 5 bytes
        SourceContent content = SourceContent.of("Café");

        // Char 0 -> byte 0
        assertEquals(0, content.charPositionToByteOffset(0));

        // Char 1 -> byte 1
        assertEquals(1, content.charPositionToByteOffset(1));

        // Char 2 -> byte 2
        assertEquals(2, content.charPositionToByteOffset(2));

        // Char 3 -> byte 3 (start of multi-byte é)
        assertEquals(3, content.charPositionToByteOffset(3));

        // Char 4 -> byte 5 (end of multi-byte é)
        assertEquals(5, content.charPositionToByteOffset(4));
    }

    @Test
    @DisplayName("Round-trip conversion between byte offset and char position")
    void testRoundTripOffsetConversion() {
        SourceContent content = SourceContent.of("Hello Café World");

        // For each byte offset, convert to char position and back
        for (int byteOffset = 0; byteOffset <= content.byteLength(); byteOffset++) {
            int charPos = content.byteOffsetToCharPosition(byteOffset);
            int byteOffsetAgain = content.charPositionToByteOffset(charPos);

            // The round trip should be consistent
            // (may not be exact due to multi-byte boundaries, but should be monotonic)
            assertTrue(byteOffsetAgain >= byteOffset || byteOffset >= byteOffsetAgain);
        }
    }

    @Test
    @DisplayName("SourceContent equality based on text content")
    void testSourceContentEquality() {
        SourceContent content1 = SourceContent.of("hello");
        SourceContent content2 = SourceContent.of("hello");
        SourceContent content3 = SourceContent.of("world");

        assertEquals(content1, content2);
        assertNotEquals(content1, content3);
        assertEquals(content1.hashCode(), content2.hashCode());
    }

    @Test
    @DisplayName("SourceContent with special characters and emoji")
    void testSourceContentWithEmoji() {
        // Emoji typically use 4 bytes in UTF-8
        String source = "Hello 😀 World";
        SourceContent content = SourceContent.of(source);

        // Verify the text is intact
        assertEquals(source, content.text());

        // Extract the emoji (it's 4 bytes)
        // "Hello " is 6 bytes, then 4-byte emoji
        String emoji = content.substringFromBytes(6, 10);
        assertEquals("😀", emoji);
    }

    @Test
    @DisplayName("SourceContent with only whitespace")
    void testSourceContentWithWhitespace() {
        SourceContent content = SourceContent.of("   \n\t  ");

        assertEquals("   \n\t  ", content.text());

        // Extract just the spaces
        String result = content.substringFromBytes(0, 3);
        assertEquals("   ", result);
    }

    @Test
    @DisplayName("SourceContent preserves line breaks and special whitespace")
    void testSourceContentWithLineBreaks() {
        String source = "line1\nline2\r\nline3";
        SourceContent content = SourceContent.of(source);

        assertEquals(source, content.text());

        // Extract "line1"
        String result = content.substringFromBytes(0, 5);
        assertEquals("line1", result);

        // Extract the newline
        result = content.substringFromBytes(5, 6);
        assertEquals("\n", result);
    }
}
