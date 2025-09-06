package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.InMemoryRepo;
import io.github.jbellis.brokk.testutil.TestConsoleIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LineEditorTest {

    static class TestContextManager implements IContextManager {
        private final Path root;
        private final IGitRepo repo = new InMemoryRepo();

        TestContextManager(Path root) {
            this.root = root;
        }

        @Override
        public ProjectFile toFile(String relName) {
            return new ProjectFile(root, relName);
        }

        @Override
        public IGitRepo getRepo() {
            return repo;
        }
    }

    @Test
    void replaceSingleLine(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "a.txt");
        Files.writeString(pf.absPath(), "L1\nL2\nL3\n");

        var edits = java.util.List.of(
                new LineEdit.EditFile(
                        "a.txt", 2, 2, "Two",
                        new LineEdit.Anchor("2", "L2"),
                        new LineEdit.Anchor("2", "L2")));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertTrue(res.failures().isEmpty());
        assertEquals("L1\nTwo\nL3\n", Files.readString(pf.absPath()));
        assertEquals("L1\nL2\nL3\n", res.originalContents().get(pf));
    }

    @Test
    void insertAtStartAndEnd(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "a.txt");
        Files.writeString(pf.absPath(), "B\n");

        var edits = java.util.List.of(
                // insert at start (use 0 anchor, omitted validation)
                new LineEdit.EditFile(
                        "a.txt", 1, 0, "A",
                        new LineEdit.Anchor("0", ""), null),
                // append at end using '$' sentinel so we can omit anchor validation
                new LineEdit.EditFile(
                        "a.txt", Integer.MAX_VALUE, Integer.MAX_VALUE - 1, "C",
                        new LineEdit.Anchor("$", ""), null));

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);
        assertTrue(res.failures().isEmpty());
        assertEquals("A\nB\nC\n", Files.readString(pf.absPath()));
    }

    @Test
    void createNewFileWithInsertion(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "new.txt");

        var edits = java.util.List.of(
                new LineEdit.EditFile(
                        "new.txt", 1, 0, "Hello\nWorld",
                        new LineEdit.Anchor("0", ""), null));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertTrue(res.failures().isEmpty());
        assertEquals("Hello\nWorld\n", Files.readString(pf.absPath()));
    }

    @Test
    void invalidRange(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "a.txt");
        Files.writeString(pf.absPath(), "Only\nOne\n");

        var edits = java.util.List.of(
                new LineEdit.EditFile(
                        "a.txt", 3, 4, "X",
                        new LineEdit.Anchor("3", ""), new LineEdit.Anchor("4", "")));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertEquals(1, res.failures().size());
        assertEquals(LineEditor.ApplyFailureReason.INVALID_LINE_RANGE, res.failures().getFirst().reason());
        assertEquals("Only\nOne\n", Files.readString(pf.absPath()));
    }

    @Test
    void replaceNonExistentFile(@TempDir Path dir) {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "missing.txt");

        var edits = java.util.List.of(
                new LineEdit.EditFile(
                        "missing.txt", 1, 1, "X",
                        new LineEdit.Anchor("1", ""), new LineEdit.Anchor("1", "")));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertEquals(1, res.failures().size());
        assertEquals(LineEditor.ApplyFailureReason.FILE_NOT_FOUND, res.failures().getFirst().reason());
    }

    @Test
    void deleteFile(@TempDir Path dir) throws IOException {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "a.txt");
        Files.writeString(pf.absPath(), "data\n");

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), java.util.List.of(new LineEdit.DeleteFile("a.txt")));

        assertTrue(res.failures().isEmpty());
        assertFalse(Files.exists(pf.absPath()));
        assertEquals("data\n", res.originalContents().get(pf));
    }

    @Test
    void insertOutOfBoundsBeyondEnd(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "a.txt");
        Files.writeString(pf.absPath(), "L1\nL2\n");

        // Insert at position 4 (valid positions are 1..3)
        var edits = java.util.List.of(
                new LineEdit.EditFile(
                        "a.txt", 4, 3, "X",
                        new LineEdit.Anchor("3", ""), null));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertEquals(1, res.failures().size());
        assertEquals(LineEditor.ApplyFailureReason.INVALID_LINE_RANGE, res.failures().getFirst().reason());
        assertEquals("L1\nL2\n", Files.readString(pf.absPath()));
    }

    @Test
    void insertionOnMissingFileCapturesEmptyOriginal(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "new.txt");

        var edits = java.util.List.of(
                new LineEdit.EditFile(
                        "new.txt", 1, 0, "Hello",
                        new LineEdit.Anchor("0", ""), null));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertTrue(res.failures().isEmpty());
        assertEquals("", res.originalContents().get(pf), "Original content should be empty for create-via-insert");
        assertEquals("Hello\n", Files.readString(pf.absPath()));
    }

    @Test
    void sortOrderWithinFile_preventsShifts(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "a.txt");
        Files.writeString(pf.absPath(), "A\nB\nC\nD\n");

        // Intentionally provide edits in an order that would be wrong without internal sorting.
        var insertAt2 = new LineEdit.EditFile(
                "a.txt", 2, 1, "I", new LineEdit.Anchor("1", "A"), null);      // insert before line 2; anchor on line 1
        var replace2to3 = new LineEdit.EditFile(
                "a.txt", 2, 3, "X", new LineEdit.Anchor("2", "B"), new LineEdit.Anchor("3", "C"));    // replace lines 2..3 with "X"

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), java.util.List.of(insertAt2, replace2to3));

        assertTrue(res.failures().isEmpty());
        assertEquals("A\nI\nX\nD\n", Files.readString(pf.absPath()),
                     "Replacement should occur before insertion due to descending sort");
    }

    @Test
    void deleteMissingFileReportsFailure(@TempDir Path dir) {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "missing.txt");

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), java.util.List.of(new LineEdit.DeleteFile("missing.txt")));

        assertEquals(1, res.failures().size());
        assertEquals(LineEditor.ApplyFailureReason.FILE_NOT_FOUND, res.failures().getFirst().reason());
    }

    @Test
    void anchorsIgnoreLeadingTrailingWhitespace_onChange(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "a.txt");
        java.nio.file.Files.writeString(pf.absPath(), "A\nB\nC\n");

        var edits = java.util.List.of(
                new LineEdit.EditFile(
                        "a.txt", 2, 2, "B2",
                        new LineEdit.Anchor("2", "  B  "),
                        new LineEdit.Anchor("2", "B  ")));

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);
        assertTrue(res.failures().isEmpty(), "Anchors with surrounding whitespace should validate");
        assertEquals("A\nB2\nC\n", java.nio.file.Files.readString(pf.absPath()));
    }

    @Test
    void anchorsIgnoreLeadingTrailingWhitespace_onInsertion(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "b.txt");
        java.nio.file.Files.writeString(pf.absPath(), "A\nB\n");

        var edits = java.util.List.of(
                // Insert before line 2 (after line 1), validate anchor on line 1 with extra spaces
                new LineEdit.EditFile(
                        "b.txt", 2, 1, "X",
                        new LineEdit.Anchor("1", "  A  "), null));

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);
        assertTrue(res.failures().isEmpty(), "Insertion should succeed with trimmed anchor comparison");
        assertEquals("A\nX\nB\n", java.nio.file.Files.readString(pf.absPath()));
    }

    @Test
    void anchorsStillCheckInternalWhitespace_mismatch(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "c.txt");
        java.nio.file.Files.writeString(pf.absPath(), "hello world\n");

        var edits = java.util.List.of(
                new LineEdit.EditFile(
                        "c.txt", 1, 1, "X",
                        new LineEdit.Anchor("1", "hello  world"), // double space inside should not be ignored
                        new LineEdit.Anchor("1", "hello  world")));

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);
        assertEquals(1, res.failures().size(), "Internal whitespace differences should still fail anchor validation");
        assertEquals(LineEditor.ApplyFailureReason.ANCHOR_MISMATCH, res.failures().getFirst().reason());
        assertEquals("hello world\n", java.nio.file.Files.readString(pf.absPath()),
                     "File should remain unchanged on anchor mismatch");
    }

    @Test
    void offByOneAnchor_isCorrectedAndApplied(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "off1.txt");
        java.nio.file.Files.writeString(pf.absPath(), "A\nB\nC\n");

        // Intent: change line 2 from B -> BB
        // Mistake: command cites line 1, but anchor content matches line 2.
        var edits = java.util.List.of(
                new LineEdit.EditFile(
                        "off1.txt", 1, 1, "BB",
                        new LineEdit.Anchor("1", "B"),  // mismatched at 1, but neighbor (2) matches
                        new LineEdit.Anchor("1", "B")));

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);
        assertTrue(res.failures().isEmpty(), "Off-by-1 anchor should be corrected");
        assertEquals("A\nBB\nC\n", java.nio.file.Files.readString(pf.absPath()),
                     "Edit should be applied at the corrected line");
    }

    @Test
    void overlappingRanges_bothFail_fileUnchanged(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "ov1.txt");
        Files.writeString(pf.absPath(), "A\nB\nC\nD\nE\n");

        var e1 = new LineEdit.EditFile(
                "ov1.txt", 2, 4, "X",
                new LineEdit.Anchor("2", "B"), new LineEdit.Anchor("4", "D"));
        var e2 = new LineEdit.EditFile(
                "ov1.txt", 3, 5, "Y",
                new LineEdit.Anchor("3", "C"), new LineEdit.Anchor("5", "E"));

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), java.util.List.of(e1, e2));

        assertEquals(2, res.failures().size(), "Both overlapping edits should be rejected");
        assertTrue(res.failures().stream().allMatch(f -> f.reason() == LineEditor.ApplyFailureReason.OVERLAPPING_EDITS));
        assertEquals("A\nB\nC\nD\nE\n", Files.readString(pf.absPath()), "File should remain unchanged");
    }

    @Test
    void insertionInsideRange_bothFail_fileUnchanged(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "ov2.txt");
        Files.writeString(pf.absPath(), "A\nB\nC\nD\n");

        var change = new LineEdit.EditFile(
                "ov2.txt", 2, 3, "X",
                new LineEdit.Anchor("2", "B"), new LineEdit.Anchor("3", "C"));
        // Insert after line 2 -> begin=3,end=2; anchor on line 2 content
        var insert = new LineEdit.EditFile(
                "ov2.txt", 3, 2, "I",
                new LineEdit.Anchor("2", "B"), null);

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), java.util.List.of(change, insert));

        assertEquals(2, res.failures().size(), "Insertion overlapping with a range should reject both edits");
        assertTrue(res.failures().stream().allMatch(f -> f.reason() == LineEditor.ApplyFailureReason.OVERLAPPING_EDITS));
        assertEquals("A\nB\nC\nD\n", Files.readString(pf.absPath()), "File should remain unchanged");
    }

    @Test
    void overlappingPairSkipped_nonOverlappingStillApplies(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "ov3.txt");
        Files.writeString(pf.absPath(), "A\nB\nC\nD\nE\n");

        var overlap1 = new LineEdit.EditFile(
                "ov3.txt", 2, 4, "X",
                new LineEdit.Anchor("2", "B"), new LineEdit.Anchor("4", "D"));
        var overlap2 = new LineEdit.EditFile(
                "ov3.txt", 3, 5, "Y",
                new LineEdit.Anchor("3", "C"), new LineEdit.Anchor("5", "E"));
        var nonOverlap = new LineEdit.EditFile(
                "ov3.txt", 1, 1, "AA",
                new LineEdit.Anchor("1", "A"), new LineEdit.Anchor("1", "A"));

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), java.util.List.of(overlap1, overlap2, nonOverlap));

        assertEquals(2, res.failures().size(), "Only the overlapping pair should fail");
        assertTrue(res.failures().stream().allMatch(f -> f.reason() == LineEditor.ApplyFailureReason.OVERLAPPING_EDITS));
        assertEquals("AA\nB\nC\nD\nE\n", Files.readString(pf.absPath()), "Non-overlapping change should apply");
    }
}
