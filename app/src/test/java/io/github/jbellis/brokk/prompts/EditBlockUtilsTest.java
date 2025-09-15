package io.github.jbellis.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.ProjectFile;
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
    void findFilenameNearby_usesUniqueRawBasenameMatchWhenCandidatesDontMatch() {
        var lang = "app/src/main/java/io/github/jbellis/brokk/analyzer/Language.java";
        var other = "app/src/main/java/io/github/jbellis/brokk/analyzer/Other.java";
        var lines = new String[] {"Please update Language.java in this section", "<<<<<<< SEARCH"};
        int headIndex = 1;
        Set<ProjectFile> projectFiles = Set.of(
                new ProjectFile(Path.of(System.getProperty("user.dir")), Path.of(lang)),
                new ProjectFile(Path.of(System.getProperty("user.dir")), Path.of(other)));

        var result = EditBlockUtils.findFilenameNearby(lines, headIndex, projectFiles, null);
        assertEquals(lang, result.replace(File.separator, "/"));
    }

    @Test
    void stripFilename_removesMixedDecorations() {
        var line = " // `app/src/Language.java`: ";
        var stripped = EditBlockUtils.stripFilename(line);
        assertEquals("app/src/Language.java", stripped);
    }
}
