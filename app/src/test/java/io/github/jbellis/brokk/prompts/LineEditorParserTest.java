package io.github.jbellis.brokk.prompts;

import io.github.jbellis.brokk.LineEdit;
import io.github.jbellis.brokk.LineEditor;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
BRK_EDIT_EX src/Main.java
10,12 c
@10| old10
@12| old12
System.out.println("Replaced!");
.
BRK_EDIT_EX_END
Outro
""".stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Expected no parse errors");

        var edOpt = r.parts().stream()
                .filter(p -> p instanceof LineEditorParser.OutputPart.EdBlock)
                .map(p -> (LineEditorParser.OutputPart.EdBlock) p)
                .findFirst();
        assertTrue(edOpt.isPresent(), "Expected one ED block part");
        var ed = edOpt.orElseThrow();
        assertEquals("src/Main.java", ed.path());
        assertFalse(ed.commands().isEmpty());
    }

    @Test
    void parse_deleteSelfClosing() {
        var input = "BRK_EDIT_RM obsolete.txt";
        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Expected no parse errors");
        assertEquals(1, r.parts().size(), "Only a single delete part is expected");
        assertInstanceOf(LineEditorParser.OutputPart.Delete.class, r.parts().get(0));
        var del = (LineEditorParser.OutputPart.Delete) r.parts().get(0);
        assertEquals("obsolete.txt", del.path());
    }

    @Test
    void parse_mixedAndBodyPreserved() {
        var input = """
    Before
    BRK_EDIT_EX x.txt
    0 a
    @0| 
    // Content can include angle brackets: <not a tag>
    .
    BRK_EDIT_EX_END
    BRK_EDIT_RM y.txt
    After
    """.stripIndent();
        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Expected no parse errors");
        assertTrue(
                r.parts().stream().anyMatch(p -> p instanceof LineEditorParser.OutputPart.EdBlock),
                "Expected an ED block");
        assertTrue(
                r.parts().stream().anyMatch(p -> p instanceof LineEditorParser.OutputPart.Delete),
                "Expected a delete block");

        var ed = (LineEditorParser.OutputPart.EdBlock) r.parts().stream()
                .filter(p -> p instanceof LineEditorParser.OutputPart.EdBlock)
                .findFirst()
                .orElseThrow();
        var cmd = ed.commands().stream().findFirst().orElseThrow();
        assertInstanceOf(LineEditorParser.EdCommand.AppendAfter.class, cmd);
        var aa = (LineEditorParser.EdCommand.AppendAfter) cmd;
        assertEquals(0, aa.line(), "0 a should parse with address 0");
        assertTrue(aa.body().getFirst().contains("<not a tag>"),
                   "Body should preserve angle brackets");
    }

    @Test
    void parse_reportsMissingEndFence_withTrailingContent() {
        var input = """
            BRK_EDIT_EX a.txt
            1 c
            body
            .
            BRK_EDIT_STOP
            """.stripIndent();
        var r = LineEditorParser.instance.parse(input);
        assertNotNull(r.parseError(), "Expected a parse error for missing BRK_EDIT_EX_END");
        assertTrue(r.parseError().contains("Missing BRK_EDIT_EX_END"), "Should mention missing END");

        // The malformed block should be preserved as text
        assertTrue(r.parts().get(0) instanceof LineEditorParser.OutputPart.Text,
                   "Malformed block should be preserved as text");
        var text = ((LineEditorParser.OutputPart.Text) r.parts().get(0)).text();
        assertTrue(text.contains("BRK_EDIT_STOP"));
    }

    @Test
    void parse_allowsMissingEndFence_ifResponseEndsImmediatelyAfterValidEdit() {
        var input = """
            BRK_EDIT_EX a.txt
            1 c
            body
            .
            """.stripIndent();
        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Expected no parse error when the response ends immediately after a complete edit");
        assertTrue(r.parts().stream().anyMatch(p -> p instanceof LineEditorParser.OutputPart.EdBlock),
                   "Expected an EdBlock when block ends at EOF after a valid edit");
        var ed = (LineEditorParser.OutputPart.EdBlock) r.parts().stream()
                .filter(p -> p instanceof LineEditorParser.OutputPart.EdBlock)
                .findFirst()
                .orElseThrow();
        assertEquals("a.txt", ed.path());
        assertFalse(ed.commands().isEmpty());
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
        BRK_EDIT_RM gone.txt
        BRK_EDIT_EX foo/bar.txt
        2,3 c
@2| old2
@3| old3
NEW
        .
        BRK_EDIT_EX_END
        """.stripIndent();
        var parsed = LineEditorParser.instance.parse(input);
        var edits = LineEditorParser.instance.materializeEdits(parsed, ctx);
        assertEquals(2, edits.size());
        assertTrue(edits.get(0) instanceof LineEdit.EditFile || edits.get(0) instanceof LineEdit.DeleteFile);

        // ensure both types appear
        assertTrue(edits.stream().anyMatch(e -> e instanceof LineEdit.DeleteFile));
        assertTrue(edits.stream().anyMatch(e -> e instanceof LineEdit.EditFile));

        var edit = (LineEdit.EditFile) edits.stream()
                .filter(e -> e instanceof LineEdit.EditFile)
                .findFirst().orElseThrow();
        assertEquals(2, edit.beginLine());
        assertEquals(3, edit.endLine());
        assertEquals("NEW", edit.content());
        assertTrue(edit.file().toString().endsWith("foo/bar.txt"),
                   "ProjectFile should resolve the given path via the context manager");
    }

    @Test
    void parse_implicitDotAndEscapes() {
        var input = """
    BRK_EDIT_EX a.txt
    0 a
    @0| 
    \\. 
    \\\\
    BRK_EDIT_EX_END
    """.stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "No errors expected for implicit body termination and escapes");
        assertEquals(1, r.parts().size(), "Implicit close should not leave stray text parts like BRK_EDIT_EX_END");

        var ed = (LineEditorParser.OutputPart.EdBlock) r.parts().getFirst();
        var cmd = (LineEditorParser.EdCommand.AppendAfter) ed.commands().getFirst();
        assertEquals(0, cmd.line());
        assertEquals(List.of(".", "\\"), cmd.body(), "Escaped lines must be unescaped in body");
    }

    @Test
    void parse_rmMissingFilename_reportsErrorAndKeepsText() {
        var input = """
        Before
        BRK_EDIT_RM
        After
        """.stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNotNull(r.parseError(), "Expected error for missing filename in BRK_EDIT_RM");
        assertTrue(r.parseError().contains("BRK_EDIT_RM missing filename."));
        assertTrue(r.parts().stream().noneMatch(p -> p instanceof LineEditorParser.OutputPart.Delete),
                   "No Delete part should be produced");
        assertTrue(r.parts().stream().anyMatch(p ->
                                                       p instanceof LineEditorParser.OutputPart.Text
                                                               && ((LineEditorParser.OutputPart.Text) p).text().contains("BRK_EDIT_RM")),
                   "Malformed delete line should be preserved as text");
    }

    @Test
    void parse_edMissingFilename_reportsErrorAndKeepsText() {
        var input = """
        BRK_EDIT_EX
        BRK_EDIT_EX_END
        """.stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNotNull(r.parseError(), "Expected error for missing filename in BRK_EDIT_EX");
        assertTrue(r.parseError().contains("BRK_EDIT_EX missing filename."));
        assertTrue(r.parts().stream().allMatch(p -> p instanceof LineEditorParser.OutputPart.Text),
                   "Malformed block should be preserved as text only");
    }

    @Test
    void parse_changeSingleLineWithoutComma() {
        var input = """
    BRK_EDIT_EX file.txt
    5 c
@5| OLD
X
    .
    BRK_EDIT_EX_END
    """.stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError());

        var ed = (LineEditorParser.OutputPart.EdBlock) r.parts().getFirst();
        var cmd = (LineEditorParser.EdCommand.ChangeRange) ed.commands().getFirst();
        assertEquals(5, cmd.begin());
        assertEquals(5, cmd.end());
        assertEquals(List.of("X"), cmd.body());
    }

    @Test
    void materialize_appendZeroAndApply_createsFile(@TempDir Path dir) throws Exception {
        var ctx = new TestContextManager(dir, new NoOpConsoleIO());
        var input = """
        BRK_EDIT_EX new.txt
        0 a
        @0| 
        A
        B
        .
        BRK_EDIT_EX_END
        """.stripIndent();

        var parsed = LineEditorParser.instance.parse(input);
        assertNull(parsed.parseError());

        var edits = LineEditorParser.materializeEdits(parsed, ctx);
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertTrue(res.failures().isEmpty(), "Creating a new file with 0 a should succeed");
        assertEquals("A\nB\n", Files.readString(dir.resolve("new.txt")));
    }

    // --- CHANGED TEST METHOD: materialize_deleteRangeAndApply ---
    @Test
    void materialize_deleteRangeAndApply(@TempDir Path dir) throws Exception {
        var ctx = new TestContextManager(dir, new NoOpConsoleIO());
        Files.writeString(dir.resolve("a.txt"), "1\n2\n3\n4\n");

        var input = """
BRK_EDIT_EX a.txt
2,3 d
@2| 2
@3| 3
BRK_EDIT_EX_END
""".stripIndent();

        var parsed = LineEditorParser.instance.parse(input);
        assertNull(parsed.parseError());

        var edits = LineEditorParser.instance.materializeEdits(parsed, ctx);
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertTrue(res.failures().isEmpty());
        assertEquals("1\n4\n", Files.readString(dir.resolve("a.txt")));
    }


    @Test
    void parse_dollarAppend_parsesAndUsesSentinel() {
        var input = """
        BRK_EDIT_EX a.txt
        $ a
        @$|
        X
        .
        BRK_EDIT_EX_END
        """.stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError());

        var ed = (LineEditorParser.OutputPart.EdBlock) r.parts().getFirst();
        var cmd = (LineEditorParser.EdCommand.AppendAfter) ed.commands().getFirst();
        assertEquals(Integer.MAX_VALUE, cmd.line(), "Parser should map '$' to sentinel");
        assertEquals(List.of("X"), cmd.body());
    }

    @Test
    void e2e_appendAtEnd_withDollar(@TempDir Path dir) throws Exception {
        var ctx = new TestContextManager(dir, new NoOpConsoleIO());
        Files.writeString(dir.resolve("a.txt"), "A\nB\n");
        var input = """
        BRK_EDIT_EX a.txt
        $ a
        @$|
        X
        .
        BRK_EDIT_EX_END
        """.stripIndent();

        var parsed = LineEditorParser.instance.parse(input);
        var edits = LineEditorParser.materializeEdits(parsed, ctx);
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertTrue(res.failures().isEmpty());
        assertEquals("A\nB\nX\n", Files.readString(dir.resolve("a.txt")));
    }

    @Test
    void e2e_insertAtStart_withZero(@TempDir Path dir) throws Exception {
        var ctx = new TestContextManager(dir, new NoOpConsoleIO());
        Files.writeString(dir.resolve("a.txt"), "B\n");
        var input = """
    BRK_EDIT_EX a.txt
    0 a
    @0| 
    A
    .
    BRK_EDIT_EX_END
    """.stripIndent();

        var parsed = LineEditorParser.instance.parse(input);
        var edits = LineEditorParser.materializeEdits(parsed, ctx);
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertTrue(res.failures().isEmpty());
        assertEquals("A\nB\n", Files.readString(dir.resolve("a.txt")));
    }

    @Test
    void e2e_insertAtEnd_withDollar(@TempDir Path dir) throws Exception {
        var ctx = new TestContextManager(dir, new NoOpConsoleIO());
        Files.writeString(dir.resolve("a.txt"), "A\nB\n");
        var input = """
    BRK_EDIT_EX a.txt
    $ a
    @$|
    Z
    .
    BRK_EDIT_EX_END
    """.stripIndent();

        var parsed = LineEditorParser.instance.parse(input);
        var edits = LineEditorParser.materializeEdits(parsed, ctx);
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertTrue(res.failures().isEmpty());
        assertEquals("A\nB\nZ\n", Files.readString(dir.resolve("a.txt")),
                     "Append at '$' should land at end");
    }

    @Test
    void parse_dollarAppend_allowsNumericAnchor() {
        var input = """
BRK_EDIT_EX a.txt
$ a
@2| LAST
X
.
BRK_EDIT_EX_END
""".stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Parser should accept numeric anchors when using '$' address");

        var ed = (LineEditorParser.OutputPart.EdBlock) r.parts().getFirst();
        var cmd = (LineEditorParser.EdCommand.AppendAfter) ed.commands().getFirst();
        assertEquals(Integer.MAX_VALUE, cmd.line(), "Address '$' should map to sentinel in command");
        assertEquals("2", cmd.anchorLine(), "Numeric anchor line should be preserved");
    }

    @Test
    void e2e_appendAtEnd_withDollarNumericAnchor_withinTolerance(@TempDir Path dir) throws Exception {
        var ctx = new TestContextManager(dir, new NoOpConsoleIO());
        Files.writeString(dir.resolve("a.txt"), "L1\nL2\nL3\n");

        var input = """
BRK_EDIT_EX a.txt
$ a
@2| L3
X
.
BRK_EDIT_EX_END
""".stripIndent();

        var parsed = LineEditorParser.instance.parse(input);
        var edits = LineEditorParser.materializeEdits(parsed, ctx);
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertTrue(res.failures().isEmpty(), "Numeric anchor within ±2 of EOF should be accepted");
        assertEquals("L1\nL2\nL3\nX\n", Files.readString(dir.resolve("a.txt")));
    }

    @Test
    void e2e_appendAtEnd_withDollarNumericAnchor_tooFarFails(@TempDir Path dir) throws Exception {
        var ctx = new TestContextManager(dir, new NoOpConsoleIO());
        Files.writeString(dir.resolve("a.txt"), "L1\nL2\nL3\n");

        var input = """
BRK_EDIT_EX a.txt
$ a
@10| L3
X
.
BRK_EDIT_EX_END
""".stripIndent();

        var parsed = LineEditorParser.instance.parse(input);
        var edits = LineEditorParser.materializeEdits(parsed, ctx);
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertEquals(1, res.failures().size(), "Anchor too far from EOF should fail");
        assertEquals(LineEditor.FailureReason.ANCHOR_MISMATCH, res.failures().getFirst().reason());
        assertEquals("L1\nL2\nL3\n", Files.readString(dir.resolve("a.txt")), "File should remain unchanged");
    }

    @Test
    void materialize_changeZeroToDollarRange_andApply(@TempDir Path dir) throws Exception {
        var ctx = new TestContextManager(dir, new NoOpConsoleIO());
        Files.writeString(dir.resolve("a.txt"), "A\nB\nC\n");

        var input = """
    BRK_EDIT_EX a.txt
    0,$ c
    @0| 
    @$| 
    X
    .
    BRK_EDIT_EX_END
    """.stripIndent();

        var parsed = LineEditorParser.instance.parse(input);
        assertNull(parsed.parseError());

        var edits = LineEditorParser.materializeEdits(parsed, ctx);
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertTrue(res.failures().isEmpty());
        assertEquals("X\n", Files.readString(dir.resolve("a.txt")));
    }

    @Test
    void parse_extraAnchorsAreParseError_changeRange() {
        var input = """
BRK_EDIT_EX a.txt
2,4 c
@2| two
@4| four
@3| three
X
.
BRK_EDIT_EX_END
""".stripIndent();

        var r = LineEditorParser.instance.parse(input);

        assertNotNull(r.parseError(), "Expected parse error for extra anchors");
        assertTrue(r.parseError().contains("Too many anchors"),
                   "Should report too many anchors as a parse error");

        // A valid EdBlock and ChangeRange command should still be produced
        assertTrue(r.parts().getFirst() instanceof LineEditorParser.OutputPart.EdBlock,
                   "Expected an EdBlock part");
        var ed = (LineEditorParser.OutputPart.EdBlock) r.parts().getFirst();
        assertEquals("a.txt", ed.path());

        assertEquals(1, ed.commands().size(), "Expected a single command in the block");
        assertInstanceOf(LineEditorParser.EdCommand.ChangeRange.class, ed.commands().getFirst());
        var cmd = (LineEditorParser.EdCommand.ChangeRange) ed.commands().getFirst();
        assertEquals(2, cmd.begin());
        assertEquals(4, cmd.end());
        assertEquals(List.of("X"), cmd.body(), "Body should be parsed normally");
    }

    @Test
    void parse_implicitClose_whenNextBeginFenceAfterChange() {
        var input = """
BRK_EDIT_EX a.txt
1 c
@1| OLD
NEW
.
BRK_EDIT_EX b.txt
0 a
@0| 
HELLO
.
BRK_EDIT_EX_END
""".stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Should allow implicit close before next BRK_EDIT_EX after a valid change");

        // Expect two EdBlocks for a.txt and b.txt
        var blocks = r.parts().stream().filter(p -> p instanceof LineEditorParser.OutputPart.EdBlock).toList();
        assertEquals(2, blocks.size(), "Expected two EdBlock parts");
        assertEquals("a.txt", ((LineEditorParser.OutputPart.EdBlock) blocks.get(0)).path());
        assertEquals("b.txt", ((LineEditorParser.OutputPart.EdBlock) blocks.get(1)).path());
    }

    @Test
    void parse_implicitClose_whenNextBeginFenceAfterDelete() {
        var input = """
BRK_EDIT_EX a.txt
2 d
@2| two
BRK_EDIT_EX b.txt
0 a
@0| 
X
.
BRK_EDIT_EX_END
""".stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.parseError(), "Should allow implicit close before next BRK_EDIT_EX after a valid delete");

        var blocks = r.parts().stream().filter(p -> p instanceof LineEditorParser.OutputPart.EdBlock).toList();
        assertEquals(2, blocks.size(), "Expected two EdBlock parts");
        assertEquals("a.txt", ((LineEditorParser.OutputPart.EdBlock) blocks.get(0)).path());
        assertEquals("b.txt", ((LineEditorParser.OutputPart.EdBlock) blocks.get(1)).path());
    }
}
