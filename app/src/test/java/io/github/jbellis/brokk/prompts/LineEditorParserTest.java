package io.github.jbellis.brokk.prompts;

import io.github.jbellis.brokk.LineEdit;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineEditorParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parse_singleEdit() {
        var input = """
                Intro text
                <brk_edit_file path="src/Main.java" type="replace" beginline=10 endline=12>
                System.out.println("Replaced!");
                </brk_edit_file>
                Outro
                """.stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Expected no parse errors");

        // Find the Edit part
        var editOpt = r.parts().stream()
                .filter(p -> p instanceof LineEditorParser.OutputPart.Edit)
                .map(p -> (LineEditorParser.OutputPart.Edit) p)
                .findFirst();
        assertTrue(editOpt.isPresent(), "Expected one edit part");
        var edit = editOpt.orElseThrow();
        assertEquals("src/Main.java", edit.path());
        assertEquals(10, edit.beginLine());
        assertEquals(12, edit.endLine());
        assertTrue(edit.content().contains("Replaced!"));
    }

    @Test
    void parse_deleteSelfClosing() {
        var input = "<brk_delete_file path='obsolete.txt' />";
        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Expected no parse errors");
        // Expect exactly one Delete part and no extra text
        assertEquals(1, r.parts().size(), "Only a single delete part is expected");
        assertInstanceOf(LineEditorParser.OutputPart.Delete.class, r.parts().get(0));
        var del = (LineEditorParser.OutputPart.Delete) r.parts().get(0);
        assertEquals("obsolete.txt", del.path());
    }

    @Test
    void parse_mixedAndBodyPreserved() {
        var input = """
                Before
                <brk_edit_file path="x.txt" type="insert" beginline=1>
                // Content can include angle brackets: <not a tag>
                </brk_edit_file>
                <brk_delete_file path="y.txt" />
                After
                """.stripIndent();
        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Expected no parse errors");
        assertTrue(
                r.parts().stream().anyMatch(p -> p instanceof LineEditorParser.OutputPart.Edit),
                "Expected an edit part");
        assertTrue(
                r.parts().stream().anyMatch(p -> p instanceof LineEditorParser.OutputPart.Delete),
                "Expected a delete part");

        var edit = (LineEditorParser.OutputPart.Edit) r.parts().stream()
                .filter(p -> p instanceof LineEditorParser.OutputPart.Edit)
                .findFirst()
                .orElseThrow();
        assertTrue(edit.content().contains("<not a tag>"),
                "Edit body should preserve angle brackets that are not closing tags");
    }

    @Test
    void parse_reportsMissingAttributesButKeepsText() {
        var input = """
                <brk_edit_file path="a.txt" type="replace" beginline=5>
                body
                </brk_edit_file>
                """.stripIndent();
        var r = LineEditorParser.instance.parse(input);
        assertNotNull(r.parseError(), "Expected a parse error for missing endline attribute");

        // Ensure the malformed open tag is preserved as text
        assertTrue(r.parts().get(0) instanceof LineEditorParser.OutputPart.Text,
                "Malformed open tag should be preserved as text");
        var concatenated = r.parts().stream()
                .map(p -> p instanceof LineEditorParser.OutputPart.Text t ? t.text() : "")
                .reduce("", (a, b) -> a + b);
        assertTrue(concatenated.contains("<brk_edit_file"),
                "Original malformed tag should be present in the output text");
    }

    @Test
    void parse_rejectsAliasClosingTag() {
        var input = "<brk_edit_file path=\"a.txt\" type=\"replace\" beginline=1 endline=1>x</brk_update_file>";
        var r = LineEditorParser.instance.parse(input);
        assertNotNull(r.parseError(), "Expected a parse error for missing closing tag");
        assertTrue(r.parseError().contains("Missing closing </brk_edit_file> tag"));

        // When the close tag is missing, the parser treats the rest of the content as text
        // attached to the failed open tag.
        var textPart = (LineEditorParser.OutputPart.Text) r.parts().get(0);
        assertTrue(textPart.text().contains("</brk_update_file>"));
    }

    @Test
    void materialize_toLineEdit() {
        var ctx = new TestContextManager(tempDir, new NoOpConsoleIO());
        var input = """
                <brk_delete_file path="gone.txt" />
                <brk_edit_file path="foo/bar.txt" type="replace" beginline=2 endline=3>NEW</brk_edit_file>
                """.stripIndent();
        var parsed = LineEditorParser.instance.parse(input);
        var edits = LineEditorParser.instance.materializeEdits(parsed, ctx);
        assertEquals(2, edits.size());
        assertTrue(edits.get(0) instanceof LineEdit.DeleteFile);
        assertTrue(edits.get(1) instanceof LineEdit.EditFile);

        var edit = (LineEdit.EditFile) edits.get(1);
        assertEquals(2, edit.beginLine());
        assertEquals(3, edit.endLine());
        assertEquals("NEW", edit.content());
        assertTrue(edit.file().toString().endsWith("foo/bar.txt"),
                "ProjectFile should resolve the given path via the context manager");
    }
}
