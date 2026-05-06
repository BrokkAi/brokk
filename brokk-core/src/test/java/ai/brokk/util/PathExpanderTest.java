package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.BrokkFile;
import ai.brokk.project.CoreProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mirrors {@code ai.brokk.CompletionsTest#testExpandPathMalformedGlobReturnsEmpty} so the
 * standalone-MCP copy of the glob expander stays in lockstep with the in-process copy. If one
 * side regresses the malformed-input handling, the other should still fail loudly here.
 */
class PathExpanderTest {

    @TempDir
    Path tempDir;

    private CoreProject project;

    @AfterEach
    void tearDown() {
        if (project != null) {
            project.close();
        }
    }

    @Test
    void expandPathMalformedGlobReturnsEmpty() throws Exception {
        Files.createDirectories(tempDir.resolve("server/src/bin"));
        Files.writeString(tempDir.resolve("server/src/bin/main.rs"), "fn main() {}");

        project = new CoreProject(tempDir);

        // Branch 1: PatternSyntaxException out of FileSystems.getPathMatcher.
        // basePrefix is empty, baseDir resolves to the project root, isDirectory passes, and
        // execution reaches getPathMatcher with an unclosed '{' group, which throws on every
        // OS regardless of glob/path separator mangling.
        List<BrokkFile> badGlob = PathExpander.expandPath(project, "*{unclosed");
        assertEquals(List.of(), badGlob);

        // Branch 2: InvalidPathException out of Path.resolve while building baseDir. The NUL
        // character is reserved on every OS, so root.resolve(basePrefix) fails before the
        // isDirectory short-circuit runs.
        List<BrokkFile> illegalPathChar = PathExpander.expandPath(project, "bad\0prefix/*.rs");
        assertEquals(List.of(), illegalPathChar);

        // Sanity check: a well-formed glob still resolves the file.
        List<BrokkFile> ok = PathExpander.expandPath(project, "server/src/**/*.rs");
        assertEquals(1, ok.size());
        assertTrue(ok.get(0).toString().endsWith("main.rs"));
    }
}
