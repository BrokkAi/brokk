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

        var edits = java.util.List.of(new LineEdit.EditFile(pf, 2, 2, "Two"));
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
                new LineEdit.EditFile(pf, 1, 0, "A"),
                // After first edit, file has 2 lines; append at end using begin=n+1=3
                new LineEdit.EditFile(pf, 3, 0, "C"));

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);
        assertTrue(res.failures().isEmpty());
        assertEquals("A\nB\nC\n", Files.readString(pf.absPath()));
    }

    @Test
    void createNewFileWithInsertion(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "new.txt");

        var edits = java.util.List.of(new LineEdit.EditFile(pf, 1, 0, "Hello\nWorld"));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertTrue(res.failures().isEmpty());
        assertEquals("Hello\nWorld\n", Files.readString(pf.absPath()));
    }

    @Test
    void invalidRange(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "a.txt");
        Files.writeString(pf.absPath(), "Only\nOne\n");

        var edits = java.util.List.of(new LineEdit.EditFile(pf, 3, 4, "X"));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertEquals(1, res.failures().size());
        assertEquals(LineEditor.FailureReason.INVALID_LINE_RANGE, res.failures().getFirst().reason());
        assertEquals("Only\nOne\n", Files.readString(pf.absPath()));
    }

    @Test
    void replaceNonExistentFile(@TempDir Path dir) {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "missing.txt");

        var edits = java.util.List.of(new LineEdit.EditFile(pf, 1, 1, "X"));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertEquals(1, res.failures().size());
        assertEquals(LineEditor.FailureReason.FILE_NOT_FOUND, res.failures().getFirst().reason());
    }

    @Test
    void deleteFile(@TempDir Path dir) throws IOException {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "a.txt");
        Files.writeString(pf.absPath(), "data\n");

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), java.util.List.of(new LineEdit.DeleteFile(pf)));

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
        var edits = java.util.List.of(new LineEdit.EditFile(pf, 4, 3, "X"));
        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), edits);

        assertEquals(1, res.failures().size());
        assertEquals(LineEditor.FailureReason.INVALID_LINE_RANGE, res.failures().getFirst().reason());
        assertEquals("L1\nL2\n", Files.readString(pf.absPath()));
    }

    @Test
    void insertionOnMissingFileCapturesEmptyOriginal(@TempDir Path dir) throws Exception {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "new.txt");

        var edits = java.util.List.of(new LineEdit.EditFile(pf, 1, 0, "Hello"));
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
        var insertAt2 = new LineEdit.EditFile(pf, 2, 1, "I");      // insert before line 2
        var replace2to3 = new LineEdit.EditFile(pf, 2, 3, "X");    // replace lines 2..3 with "X"

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), java.util.List.of(insertAt2, replace2to3));

        assertTrue(res.failures().isEmpty());
        assertEquals("A\nI\nX\nD\n", Files.readString(pf.absPath()),
                     "Replacement should occur before insertion due to descending sort");
    }

    @Test
    void deleteMissingFileReportsFailure(@TempDir Path dir) {
        var cm = new TestContextManager(dir);
        var pf = new ProjectFile(dir, "missing.txt");

        var res = LineEditor.applyEdits(cm, new TestConsoleIO(), java.util.List.of(new LineEdit.DeleteFile(pf)));

        assertEquals(1, res.failures().size());
        assertEquals(LineEditor.FailureReason.FILE_NOT_FOUND, res.failures().getFirst().reason());
    }
}
