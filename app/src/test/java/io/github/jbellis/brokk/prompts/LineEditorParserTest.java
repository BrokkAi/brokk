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
        assertNull(r.error(), "Expected no fatal parse errors");

        assertTrue(r.edits().stream().anyMatch(e -> e instanceof LineEdit.EditFile ef && ef.file().equals("src/Main.java")));
    }

    @Test
    void parse_deleteSelfClosing() {
        var input = "BRK_EDIT_RM obsolete.txt";
        var r = LineEditorParser.instance.parse(input);
        assertNull(r.error(), "Expected no fatal parse errors");
        assertEquals(1, r.edits().size(), "Only a single delete is expected");
        assertInstanceOf(LineEdit.DeleteFile.class, r.edits().get(0));
        var del = (LineEdit.DeleteFile) r.edits().get(0);
        assertEquals("obsolete.txt", del.file());
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
        assertNull(r.error(), "Expected no fatal parse errors");
        assertTrue(r.edits().stream().anyMatch(e -> e instanceof LineEdit.EditFile ef && ef.file().equals("x.txt")),
                "Expected an edit for x.txt");
        assertTrue(r.edits().stream().anyMatch(e -> e instanceof LineEdit.DeleteFile df && df.file().equals("y.txt")),
                "Expected a delete for y.txt");

        var edit = (LineEdit.EditFile) r.edits().stream().filter(e -> e instanceof LineEdit.EditFile).findFirst().orElseThrow();
        assertTrue(edit.content().contains("<not a tag>"), "Body should preserve angle brackets");
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

        assertNotNull(r.error(), "Expected a fatal parse error due to unclosed block");
        assertTrue(r.failures().stream().anyMatch(f -> f.reason() == LineEditorParser.ParseFailureReason.MISSING_ANCHOR),
                "Should record missing first anchor failure");
        assertTrue(r.failures().stream().anyMatch(f -> f.reason() == LineEditorParser.ParseFailureReason.MISSING_END_FENCE),
                "Should record missing end fence");
        assertTrue(r.edits().isEmpty(), "No edits should be included for a malformed block");
    }

    @Test
    void parse_allowsMissingEndFence_ifResponseEndsImmediatelyAfterValidEdit() {
        var input = """
            BRK_EDIT_EX a.txt
            1 c
            @1| OLD
            body
            .
            """.stripIndent();
        var r = LineEditorParser.instance.parse(input);
        assertNull(r.error(), "Expected no fatal error when block ends at EOF after a complete edit");
        assertTrue(r.edits().stream().anyMatch(e -> e instanceof LineEdit.EditFile ef && ef.file().equals("a.txt")));
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
        var edits = parsed.edits();
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
        assertEquals("foo/bar.txt", edit.file(), "Path should be preserved as given");
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
        assertNull(r.error(), "No fatal errors expected for implicit body termination and escapes");

        var ed = (LineEdit.EditFile) r.edits().getFirst();
        assertEquals(1, ed.beginLine());
        assertEquals(0, ed.endLine());
        assertEquals(List.of(".", "\\"), ed.content().lines().toList(), "Escaped lines must be unescaped in body");
    }

    @Test
    void parse_rmMissingFilename_reportsFailure() {
        var input = """
    Before
    BRK_EDIT_RM
    After
    """.stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertTrue(r.failures().stream().anyMatch(f -> f.reason() == LineEditorParser.ParseFailureReason.MISSING_FILENAME));
        assertTrue(r.edits().stream().noneMatch(e -> e instanceof LineEdit.DeleteFile),
                   "No Delete should be produced");
    }

    @Test
    void parse_edMissingFilename_reportsFailure() {
        var input = """
    BRK_EDIT_EX
    BRK_EDIT_EX_END
    """.stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertTrue(r.failures().stream().anyMatch(f -> f.reason() == LineEditorParser.ParseFailureReason.MISSING_FILENAME));
        assertTrue(r.edits().isEmpty(), "No edits should be produced when filename is missing");
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
        assertNull(r.error());

        var ed = (LineEdit.EditFile) r.edits().getFirst();
        assertEquals(5, ed.beginLine());
        assertEquals(5, ed.endLine());
        assertEquals(List.of("X"), ed.content().lines().toList());
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
        assertNull(parsed.error());

        var edits = parsed.edits();
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertTrue(res.failures().isEmpty(), "Creating a new file with 0 a should succeed");
        assertEquals("A\nB\n", Files.readString(dir.resolve("new.txt")));
    }

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
        assertNull(parsed.error());

        var edits = parsed.edits();
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
        assertNull(r.error());

        var ed = (LineEdit.EditFile) r.edits().getFirst();
        assertEquals(Integer.MAX_VALUE, ed.beginLine(), "Parser should map '$' to sentinel (begin=Integer.MAX_VALUE for insert)");
        assertEquals(List.of("X"), ed.content().lines().toList());
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
        var edits = parsed.edits();
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
        var edits = parsed.edits();
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
        var edits = parsed.edits();
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
        assertNull(r.error(), "Parser should accept numeric anchors when using '$' address");

        var ed = (LineEdit.EditFile) r.edits().getFirst();
        assertEquals(Integer.MAX_VALUE, ed.beginLine(), "Address '$' should map to sentinel in command");
        assertEquals("2", ed.beginAnchor().address(), "Numeric anchor line should be preserved");
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
        var edits = parsed.edits();
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
        var edits = parsed.edits();
        var res = LineEditor.applyEdits(ctx, new NoOpConsoleIO(), edits);

        assertEquals(1, res.failures().size(), "Anchor too far from EOF should fail");
        assertEquals(LineEditor.ApplyFailureReason.ANCHOR_MISMATCH, res.failures().getFirst().reason());
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
        assertNull(parsed.error());

        var edits = parsed.edits();
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

        assertTrue(r.failures().stream().anyMatch(f -> f.reason() == LineEditorParser.ParseFailureReason.TOO_MANY_ANCHORS),
                "Should report too many anchors as a parse failure");
        assertTrue(r.edits().isEmpty(), "No edits should be produced after a structural error in the block");
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
        assertNull(r.error(), "Should allow implicit close before next BRK_EDIT_EX after a valid change");

        assertTrue(r.edits().stream().anyMatch(e -> e instanceof LineEdit.EditFile ef && ef.file().equals("a.txt")));
        assertTrue(r.edits().stream().anyMatch(e -> e instanceof LineEdit.EditFile ef && ef.file().equals("b.txt")));
    }

    @Test
    void allow_singleAnchor_for_singletonRange_change() {
        var input = """
BRK_EDIT_EX file.txt
2,2 c
@2| OLD
NEW
.
BRK_EDIT_EX_END
""".stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.error(), "Single-line range with one anchor should be accepted");

        var ed = (LineEdit.EditFile) r.edits().getFirst();
        assertEquals(2, ed.beginLine());
        assertEquals(2, ed.endLine());
        assertEquals(List.of("NEW"), ed.content().lines().toList());
    }

    @Test
    void allow_twoMatchingAnchors_for_singletonRange_change() {
        var input = """
BRK_EDIT_EX file.txt
2,2 c
@2| OLD
@2| OLD
NEW
.
BRK_EDIT_EX_END
""".stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.error(), "Two matching anchors should be accepted for single-line range");

        var ed = (LineEdit.EditFile) r.edits().getFirst();
        assertEquals(2, ed.beginLine());
        assertEquals(2, ed.endLine());
        assertEquals("NEW", ed.content());
    }

    @Test
    void mismatch_twoAnchors_for_singletonRange_reportsFailure() {
        var input = """
BRK_EDIT_EX file.txt
2,2 c
@2| OLD
@2| WRONG
NEW
.
BRK_EDIT_EX_END
""".stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertTrue(r.failures().stream().anyMatch(f -> f.reason() == LineEditorParser.ParseFailureReason.ANCHOR_SYNTAX),
                   "Mismatched second anchor must produce a syntax failure");
        assertTrue(r.edits().isEmpty(), "No edits should be produced after anchor mismatch");
    }

    @Test
    void allow_singleAnchor_for_singletonRange_delete() {
        var input = """
BRK_EDIT_EX file.txt
3,3 d
@3| OLD
BRK_EDIT_EX_END
""".stripIndent();

        var r = LineEditorParser.instance.parse(input);
        assertNull(r.error(), "Single-line range delete with one anchor should be accepted");

        var ed = (LineEdit.EditFile) r.edits().getFirst();
        assertEquals(3, ed.beginLine());
        assertEquals(3, ed.endLine());
        assertEquals("", ed.content(), "Delete should have empty body content");
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
        assertNull(r.error(), "Should allow implicit close before next BRK_EDIT_EX after a valid delete");

        assertTrue(r.edits().stream().anyMatch(e -> e instanceof LineEdit.EditFile ef && ef.file().equals("a.txt")));
        assertTrue(r.edits().stream().anyMatch(e -> e instanceof LineEdit.EditFile ef && ef.file().equals("b.txt")));
    }
}
