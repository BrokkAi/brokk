package io.github.jbellis.brokk.prompts;

import io.github.jbellis.brokk.LineEdit;
import io.github.jbellis.brokk.LineEditor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodePromptsTest {

    @Test
    void combinedFileSection_containsParseThenApply_inSingleFileTag() {
        var snippet = """
BRK_EDIT_EX a.txt
1 c
@1| OLD
""".stripIndent();
        var pf = new LineEditorParser.ParseFailure(
                LineEditorParser.ParseFailureReason.MISSING_END_FENCE,
                snippet,
                "missing end fence");

        var beginAnchor = new LineEdit.Anchor("1", "OLD");
        var edit = new LineEdit.EditFile("a.txt", 1, 1, "NEW", beginAnchor, beginAnchor);
        var af = new LineEditor.ApplyFailure(edit, LineEditor.ApplyFailureReason.ANCHOR_MISMATCH, "mismatch");

        var msg = CodePrompts.getCombinedEditFailureMessage(
                List.of(pf), null, null, List.of(af), 0);

        // Exactly one file section for a.txt
        assertEquals(1, countOccurrences(msg, "<file name=\"a.txt\">"),
                "Expected a single <file> section per file");

        // Parse before apply inside the same file section
        int idxFile = msg.indexOf("<file name=\"a.txt\">");
        int idxParse = msg.indexOf("<parse_failures>", idxFile);
        int idxApply = msg.indexOf("<apply_failures>", idxFile);
        assertTrue(idxParse >= 0, "parse_failures should be present");
        assertTrue(idxApply >= 0, "apply_failures should be present");
        assertTrue(idxParse < idxApply, "parse_failures should come before apply_failures");

        // Only one <files> wrapper
        assertEquals(1, countOccurrences(msg, "<files>"), "Should emit a single <files> wrapper");
    }

    @Test
    void unattributedParseFailures_areListedSeparately() {
        var pf = new LineEditorParser.ParseFailure(
                LineEditorParser.ParseFailureReason.MISSING_FILENAME,
                "", // no snippet => unattributed
                "no path available");

        var msg = CodePrompts.getCombinedEditFailureMessage(
                List.of(pf), null, null, List.of(), 0);

        assertTrue(msg.contains("<unattributed_parse_failures>"),
                "Expected unattributed parse failures section when snippet has no path");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
