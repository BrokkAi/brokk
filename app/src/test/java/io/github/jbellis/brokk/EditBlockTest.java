package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.InMemoryRepo;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.jbellis.brokk.testutil.TestContextManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditBlockTest {

    @Test
    void parse_and_apply_add_and_update(@TempDir Path dir) throws IOException {
        var ctx = new TestContextManager(dir, Set.of("fileA.txt"));
        var existing = dir.resolve("fileA.txt");
        Files.writeString(existing, "A\nB\nC\n");

        var patch = """
                *** Begin Patch
                *** Add File: newFile.txt
                +alpha
                +beta
                *** Update File: fileA.txt
                @@
                 A
                -B
                +B2
                 C
                *** End Patch
                """;

        var ops = EditBlockParser.instance.parse(patch);
        var res = EditBlock.applyEditBlocks(ctx, ctx.getIo(), ops);

        assertTrue(res.failedBlocks().isEmpty());
        assertEquals("A\nB2\nC\n", Files.readString(existing));
        assertEquals("alpha\nbeta\n", Files.readString(dir.resolve("newFile.txt")));
    }

    @Test
    void parse_and_apply_delete(@TempDir Path dir) throws IOException {
        var ctx = new TestContextManager(dir, Set.of("deleteme.txt"));
        var f = dir.resolve("deleteme.txt");
        Files.writeString(f, "content\n");

        var patch = """
                *** Begin Patch
                *** Delete File: deleteme.txt
                *** End Patch
                """;

        var ops = EditBlockParser.instance.parse(patch);
        var res = EditBlock.applyEditBlocks(ctx, ctx.getIo(), ops);

        assertTrue(res.failedBlocks().isEmpty());
        assertFalse(Files.exists(f));
    }

    @Test
    void parse_move_with_update(@TempDir Path dir) throws IOException {
        var ctx = new TestContextManager(dir, Set.of("src/old.txt"));
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/old.txt"), "old\n");

        var patch = """
                *** Begin Patch
                *** Update File: src/old.txt
                *** Move to: src/new.txt
                @@
                -old
                +new
                *** End Patch
                """;

        var ops = EditBlockParser.instance.parse(patch);
        var res = EditBlock.applyEditBlocks(ctx, ctx.getIo(), ops);

        assertTrue(res.failedBlocks().isEmpty());
        assertFalse(Files.exists(dir.resolve("src/old.txt")));
        assertEquals("new\n", Files.readString(dir.resolve("src/new.txt")));
    }

    @Test
    void update_no_match_is_reported(@TempDir Path dir) throws IOException {
        var ctx = new TestContextManager(dir, Set.of("file.txt"));
        Files.writeString(dir.resolve("file.txt"), "One\nTwo\n");

        var patch = """
                *** Begin Patch
                *** Update File: file.txt
                @@
                -Missing
                +New
                *** End Patch
                """;

        var ops = EditBlockParser.instance.parse(patch);
        var res = EditBlock.applyEditBlocks(ctx, ctx.getIo(), ops);
        assertEquals(1, res.failedBlocks().size());
        assertEquals(EditBlock.ParseFailureReason.NO_MATCH, res.failedBlocks().getFirst().reason());
    }

    @Test
    void redact_apply_patch_envelope() {
        String withEnvelope = """
                bla bla intro
                *** Begin Patch
                *** Add File: foo.txt
                +hello
                *** End Patch
                outro
                """;
        var redacted = EditBlockParser.instance.redact(withEnvelope);
        assertTrue(redacted.contains("[elided apply_patch envelope]"));
        assertTrue(redacted.contains("bla bla intro"));
        assertTrue(redacted.contains("outro"));
    }

    @Test
    void first_chunk_without_header_then_second_with_header(@TempDir Path dir) throws IOException {
        var ctx = new TestContextManager(dir, Set.of("file.txt"));
        var f = dir.resolve("file.txt");
        Files.writeString(f, "A\nB\nC\nD\n");

        var patch = """
                *** Begin Patch
                *** Update File: file.txt
                 A
                -B
                +B2
                 C
                @@ tail
                 C
                -D
                +D2
                *** End Patch
                """;

        var ops = EditBlockParser.instance.parse(patch);
        var res = EditBlock.applyEditBlocks(ctx, ctx.getIo(), ops);
        assertTrue(res.failedBlocks().isEmpty());
        assertEquals("A\nB2\nC\nD2\n", Files.readString(f));
    }
}
