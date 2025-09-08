package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.prompts.EditBlockParser;
import org.junit.jupiter.api.Test;

class EditBlockParseAllBlocksTest {

    @Test
    void parse_empty_string_yields_no_ops() {
        var ops = EditBlockParser.instance.parse("");
        assertEquals(0, ops.size());
        var redacted = EditBlockParser.instance.redact("");
        assertEquals("", redacted);
    }

    @Test
    void parse_plain_text_only() {
        String input = "This is just plain text.";
        var ops = EditBlockParser.instance.parse(input);
        assertEquals(0, ops.size());
        var redacted = EditBlockParser.instance.redact(input);
        assertEquals(input, redacted);
    }

    @Test
    void parse_update_with_two_chunks() {
        String input = """
                *** Begin Patch
                *** Update File: build.gradle
                @@ deps
                 dependencies {
                -    implementation("a:b:1.0")
                +    implementation("a:b:2.0")
                 }
                @@ test-deps
                 testImplementation(platform("junit:junit-bom:5.10.0"))
                - testImplementation("junit:junit:4.13")
                + testImplementation("org.junit.jupiter:junit-jupiter")
                *** End Patch
                """;
        var ops = EditBlockParser.instance.parse(input);
        assertEquals(1, ops.size());
        var u = (EditBlock.UpdateFile) ops.getFirst();
        assertEquals("build.gradle", u.path());
        assertEquals(2, u.chunks().size());
        assertEquals("deps", u.chunks().getFirst().anchor());
        assertEquals("test-deps", u.chunks().getLast().anchor());
    }

    @Test
    void first_chunk_may_omit_header() {
        String input = """
                *** Begin Patch
                *** Update File: f.txt
                 A
                -B
                +B2
                 C
                *** End Patch
                """;
        var ops = EditBlockParser.instance.parse(input);
        assertEquals(1, ops.size());
        var u = (EditBlock.UpdateFile) ops.getFirst();
        assertEquals(1, u.chunks().size());
        assertNull(u.chunks().getFirst().anchor());
    }

    @Test
    void missing_header_on_second_chunk_is_error() {
        String input = """
                *** Begin Patch
                *** Update File: t.txt
                 A
                -B
                +B2
                *** End of File
                 C
                -D
                +D2
                *** End Patch
                """;
        assertThrows(EditBlockParser.PatchParseException.class, () -> EditBlockParser.instance.parse(input));
    }
}
