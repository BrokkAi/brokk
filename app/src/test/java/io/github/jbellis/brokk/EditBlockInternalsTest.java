package io.github.jbellis.brokk;

import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.InMemoryRepo;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Targeted tests for core apply_patch application semantics: matching, EOF behavior,
 * whitespace/normalization passes, anchors, and ambiguity handling.
 */
class EditBlockInternalsTest {
    
    @Test
    void update_replacement_in_middle(@TempDir Path dir) throws IOException {
        var cm = new CM(dir);
        var file = dir.resolve("f.txt");
        Files.writeString(file, "A\nB\nC\n");

        var ops = List.<EditBlock.FileOperation>of(
                new EditBlock.UpdateFile(
                        "f.txt",
                        null,
                        List.of(new EditBlock.UpdateFileChunk(
                                null,
                                List.of("A"),
                                List.of("B"),
                                List.of("B-REPLACED"),
                                List.of("C"),
                                false))));

        var result = EditBlock.applyEditBlocks(cm, cm.getIo(), ops);
        assertTrue(result.failedBlocks().isEmpty());
        assertEquals("A\nB-REPLACED\nC\n", Files.readString(file));
    }

    @Test
    void insert_at_eof_with_marker(@TempDir Path dir) throws IOException {
        var cm = new CM(dir);
        var file = dir.resolve("g.txt");
        Files.writeString(file, "A\nB\nC\n");

        var ops = List.<EditBlock.FileOperation>of(
                new EditBlock.UpdateFile(
                        "g.txt",
                        null,
                        List.of(new EditBlock.UpdateFileChunk(
                                null,
                                List.of("C"),
                                List.of(),            // pure insert
                                List.of("D"),
                                List.of(),
                                true))));             // *** End of File bias

        var result = EditBlock.applyEditBlocks(cm, cm.getIo(), ops);
        assertTrue(result.failedBlocks().isEmpty());
        assertEquals("A\nB\nC\nD\n", Files.readString(file));
    }

    @Test
    void replace_last_line_at_eof_with_marker(@TempDir Path dir) throws IOException {
        var cm = new CM(dir);
        var file = dir.resolve("h.txt");
        Files.writeString(file, "A\nB\nC\n");

        var ops = List.<EditBlock.FileOperation>of(
                new EditBlock.UpdateFile(
                        "h.txt",
                        null,
                        List.of(new EditBlock.UpdateFileChunk(
                                null,
                                List.of("B"),
                                List.of("C"),        // replace final line
                                List.of("Z"),
                                List.of(),
                                true))));            // EOF marker to bias match at tail

        var result = EditBlock.applyEditBlocks(cm, cm.getIo(), ops);
        assertTrue(result.failedBlocks().isEmpty());
        assertEquals("A\nB\nZ\n", Files.readString(file));
    }

    @Test
    void whitespace_insensitive_match(@TempDir Path dir) throws IOException {
        var cm = new CM(dir);
        var file = dir.resolve("w.txt");
        Files.writeString(file, "A\n  B \nC\n");

        var ops = List.<EditBlock.FileOperation>of(
                new EditBlock.UpdateFile(
                        "w.txt",
                        null,
                        List.of(new EditBlock.UpdateFileChunk(
                                null,
                                List.of(),
                                List.of("B"),        // matches "  B " via TRIM pass
                                List.of("B2"),
                                List.of(),
                                false))));

        var result = EditBlock.applyEditBlocks(cm, cm.getIo(), ops);
        assertTrue(result.failedBlocks().isEmpty());
        assertEquals("A\nB2\nC\n", Files.readString(file));
    }

    @Test
    void unicode_normalization_match(@TempDir Path dir) throws IOException {
        var cm = new CM(dir);
        var file = dir.resolve("n.txt");
        // EN DASH between foo and bar:
        Files.writeString(file, "foo \u2013 bar\n");

        var ops = List.<EditBlock.FileOperation>of(
                new EditBlock.UpdateFile(
                        "n.txt",
                        null,
                        List.of(new EditBlock.UpdateFileChunk(
                                null,
                                List.of(),
                                List.of("foo - bar"),
                                List.of("foo - bar!"),
                                List.of(),
                                false))));

        var result = EditBlock.applyEditBlocks(cm, cm.getIo(), ops);
        assertTrue(result.failedBlocks().isEmpty());
        assertEquals("foo - bar!\n", Files.readString(file));
    }

    @Test
    void ambiguous_match_detected(@TempDir Path dir) throws IOException {
        var cm = new CM(dir);
        var file = dir.resolve("amb.txt");
        Files.writeString(file, "X\nY\nX\n");

        var ops = List.<EditBlock.FileOperation>of(
                new EditBlock.UpdateFile(
                        "amb.txt",
                        null,
                        List.of(new EditBlock.UpdateFileChunk(
                                null,
                                List.of(),
                                List.of("X"),       // appears twice
                                List.of("Z"),
                                List.of(),
                                false))));

        var res = EditBlock.applyEditBlocks(cm, cm.getIo(), ops);
        assertEquals(1, res.failedBlocks().size());
        assertEquals(EditBlock.ParseFailureReason.AMBIGUOUS_MATCH, res.failedBlocks().getFirst().reason());
        assertEquals("X\nY\nX\n", Files.readString(file)); // unchanged
    }

    @Test
    void anchor_advances_cursor(@TempDir Path dir) throws IOException {
        var cm = new CM(dir);
        var file = dir.resolve("sec.txt");
        Files.writeString(file, "SECTION 1\nfoo\nSECTION 2\nfoo\n");

        var chunk = new EditBlock.UpdateFileChunk(
                "SECTION 2",        // anchor line
                List.of(),
                List.of("foo"),
                List.of("bar"),
                List.of(),
                false);

        var ops = List.<EditBlock.FileOperation>of(new EditBlock.UpdateFile("sec.txt", null, List.of(chunk)));

        var res = EditBlock.applyEditBlocks(cm, cm.getIo(), ops);
        assertTrue(res.failedBlocks().isEmpty());
        assertEquals("SECTION 1\nfoo\nSECTION 2\nbar\n", Files.readString(file));
    }
}
