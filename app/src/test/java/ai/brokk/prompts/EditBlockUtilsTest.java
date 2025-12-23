package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditBlockUtilsTest {

    @Test
    void findFilenameNearby_acceptsCommentPrefixedFilename() {
        var filename = "app/src/main/java/io/github/jbellis/brokk/analyzer/Language.java";
        var lines =
                ("""
            // %s
            <<<<<<< SEARCH
            some before
            =======
            some after
            >>>>>>> REPLACE
            """)
                        .formatted(filename)
                        .split("\n");
        int headIndex = 1; // index of the "<<<<<<< SEARCH" line
        Set<ProjectFile> projectFiles =
                Set.of(new ProjectFile(Path.of(System.getProperty("user.dir")), Path.of(filename)));

        var result = EditBlockUtils.findFilenameNearby(lines, headIndex, projectFiles, null);
        assertEquals(filename, result.replace(File.separator, "/"));
    }

    @Test
    void stripFilename_stripsDoubleSlashAndTrims() {
        var line = "   //   app/src/main/java/io/github/jbellis/brokk/analyzer/Language.java   ";
        var stripped = EditBlockUtils.stripFilename(line);
        assertEquals("app/src/main/java/io/github/jbellis/brokk/analyzer/Language.java", stripped);
    }

    @Test
    void stripFilename_ignoresFenceAndEllipsis() {
        assertNull(EditBlockUtils.stripFilename("```"));
        assertNull(EditBlockUtils.stripFilename("..."));
    }

    @Test
    void findFilenameNearby_prefersExactProjectPathMatch() {
        var filename = "app/src/main/java/io/github/jbellis/brokk/analyzer/Language.java";
        var lines = new String[] {filename, "<<<<<<< SEARCH"};
        int headIndex = 1;
        Set<ProjectFile> projectFiles =
                Set.of(new ProjectFile(Path.of(System.getProperty("user.dir")), Path.of(filename)));

        var result = EditBlockUtils.findFilenameNearby(lines, headIndex, projectFiles, null);
        assertEquals(filename, result.replace(File.separator, "/"));
    }

    @Test
    void findFilenameNearby_readsFilenameTwoLinesUpWhenFenced() {
        var filename = "app/src/main/java/io/github/jbellis/brokk/analyzer/Language.java";
        var lines = new String[] {filename, "```", "<<<<<<< SEARCH"};
        int headIndex = 2;
        Set<ProjectFile> projectFiles =
                Set.of(new ProjectFile(Path.of(System.getProperty("user.dir")), Path.of(filename)));

        var result = EditBlockUtils.findFilenameNearby(lines, headIndex, projectFiles, null);
        assertEquals(filename, result.replace(File.separator, "/"));
    }

    @Test
    void stripFilename_removesMixedDecorations() {
        var line = " // `app/src/Language.java`: ";
        var stripped = EditBlockUtils.stripFilename(line);
        assertEquals("app/src/Language.java", stripped);
    }

    @Test
    void findFilenameNearby_fallsBackToCurrentPath() {
        var lines = new String[] {"some random text", "<<<<<<< SEARCH"};
        var result = EditBlockUtils.findFilenameNearby(lines, 1, Set.of(), "fallback/File.java");
        assertEquals("fallback/File.java", result);
    }

    @Test
    void findFilenameNearby_doesNotRedirectToExistingFileByBasename() {
        Path root = Path.of("").toAbsolutePath();
        // LLM wants to create b/Foo.java, but a/Foo.java exists
        var lines = new String[] {"b/Foo.java", "<<<<<<< SEARCH"};
        Set<ProjectFile> projectFiles = Set.of(new ProjectFile(root, Path.of("a/Foo.java")));

        var result = EditBlockUtils.findFilenameNearby(lines, 1, projectFiles, null);

        // Should return "b/Foo.java" verbatim, NOT "a/Foo.java"
        assertEquals("b/Foo.java", result);
    }

    @Test
    void findFilenameNearby_bareFilenameDoesNotMatchExistingPath() {
        Path root = Path.of("").toAbsolutePath();
        // LLM says just "Foo.java" without path
        var lines = new String[] {"Foo.java", "<<<<<<< SEARCH"};
        Set<ProjectFile> projectFiles = Set.of(new ProjectFile(root, Path.of("src/main/java/com/app/Foo.java")));

        var result = EditBlockUtils.findFilenameNearby(lines, 1, projectFiles, null);

        // Should return "Foo.java" verbatim (for potential new file at root),
        // NOT redirect to the existing file
        assertEquals("Foo.java", result);
    }

    @Test
    void findFilenameNearby_extractsFullPathFromNoisyLine() {
        Path root = Path.of("").toAbsolutePath();
        var pf = new ProjectFile(root, Path.of("src/main/java/com/app/Foo.java"));
        var lines = new String[] {"Here's the fix for " + pf + ":", "<<<<<<< SEARCH"};
        Set<ProjectFile> projectFiles = Set.of(pf);

        var result = EditBlockUtils.findFilenameNearby(lines, 1, projectFiles, null);
        assertEquals(pf.toString(), result);
    }

    @Test
    void findFilenameNearby_ambiguousFullPathsReturnsNull() {
        Path root = Path.of("").toAbsolutePath();
        var pf1 = new ProjectFile(root, Path.of("a/Foo.java"));
        var pf2 = new ProjectFile(root, Path.of("b/Foo.java"));
        // Raw line mentions two different project files
        var lines = new String[] {"Changes in " + pf1 + " and " + pf2, "<<<<<<< SEARCH"};
        Set<ProjectFile> projectFiles = Set.of(pf1, pf2);

        var result = EditBlockUtils.findFilenameNearby(lines, 1, projectFiles, "fallback/File.java");

        // Ambiguous
        assertNull(result);
    }

    @Test
    void findFilenameNearby_noCandidatesNoFallbackReturnsNull() {
        var lines = new String[] {"just some text with no path", "<<<<<<< SEARCH"};

        var result = EditBlockUtils.findFilenameNearby(lines, 1, Set.of(), null);

        assertNull(result);
    }
}
