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
        // LLM wants to create b/Foo.java, but a/Foo.java also exists
        var lines = new String[] {"b/Foo.java", "<<<<<<< SEARCH"};
        Set<ProjectFile> projectFiles =
                Set.of(new ProjectFile(root, Path.of("a/Foo.java")), new ProjectFile(root, Path.of("b/Foo.java")));

        var result = EditBlockUtils.findFilenameNearby(lines, 1, projectFiles, null);

        // Should return "b/Foo.java" verbatim, NOT "a/Foo.java"
        assertEquals("b/Foo.java", result.replace(File.separator, "/"));
    }

    @Test
    void findFilenameNearby_bareFilenameDoesNotMatchExistingPath() {
        Path root = Path.of("").toAbsolutePath();
        // LLM says just "Foo.java" without path -- not in projectFiles
        var lines = new String[] {"Foo.java", "<<<<<<< SEARCH"};
        Set<ProjectFile> projectFiles = Set.of(new ProjectFile(root, Path.of("src/main/java/com/app/Foo.java")));

        var result = EditBlockUtils.findFilenameNearby(lines, 1, projectFiles, null);

        // Bare filename doesn't exactly match any projectFile, so returns null
        assertNull(result);
    }

    @Test
    void findFilenameNearby_noisyLineDoesNotMatch() {
        Path root = Path.of("").toAbsolutePath();
        var pf = new ProjectFile(root, Path.of("src/main/java/com/app/Foo.java"));
        var lines = new String[] {"Here's the fix for " + pf + ":", "<<<<<<< SEARCH"};
        Set<ProjectFile> projectFiles = Set.of(pf);

        var result = EditBlockUtils.findFilenameNearby(lines, 1, projectFiles, null);
        // Noisy surrounding text means stripFilename won't extract a clean match
        assertNull(result);
    }

    @Test
    void findFilenameNearby_ambiguousFullPathsReturnsNull() {
        Path root = Path.of("").toAbsolutePath();
        var pf1 = new ProjectFile(root, Path.of("a/Foo.java"));
        var pf2 = new ProjectFile(root, Path.of("b/Foo.java"));
        // Raw line mentions two different project files -- stripFilename can't clean this
        var lines = new String[] {"Changes in " + pf1 + " and " + pf2, "<<<<<<< SEARCH"};
        Set<ProjectFile> projectFiles = Set.of(pf1, pf2);

        var result = EditBlockUtils.findFilenameNearby(lines, 1, projectFiles, null);

        // Noisy line doesn't match, no currentPath fallback
        assertNull(result);
    }

    @Test
    void findFilenameNearby_noCandidatesNoFallbackReturnsNull() {
        var lines = new String[] {"just some text with no path", "<<<<<<< SEARCH"};

        var result = EditBlockUtils.findFilenameNearby(lines, 1, Set.of(), null);

        assertNull(result);
    }

    @Test
    void findFilenameNearby_normalizesPaths() {
        // If system separator is backslash (Windows), it should still match forward slash candidate
        String unixCandidate = "src/main/Foo.java";

        Path root = Path.of("").toAbsolutePath();
        // ProjectFile.toString() uses relPath.toString(), which uses system separator
        Set<ProjectFile> projectFiles = Set.of(new ProjectFile(root, Path.of("src", "main", "Foo.java")));

        var lines = new String[] {unixCandidate, "<<<<<<< SEARCH"};
        var result = EditBlockUtils.findFilenameNearby(lines, 1, projectFiles, null);

        assertEquals(unixCandidate, result, "The candidate path should match after normalization regardless of system separators");
    }
}
