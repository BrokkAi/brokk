package io.github.jbellis.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitIgnoreTest {
    private Path projectRoot;
    private GitRepo repo;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("testRepo");
        Files.createDirectories(projectRoot);

        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            // Create an initial commit
            Path readme = projectRoot.resolve("README.md");
            Files.writeString(readme, "Initial commit file.");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test User", "test@example.com")
                    .setSign(false)
                    .call();
        }

        repo = new GitRepo(projectRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        GitTestCleanupUtil.cleanupGitResources(repo);
    }

    @Test
    void testGetIgnoredPatterns_PreservesTrailingSlashes() throws Exception {
        // Create .gitignore with various patterns including trailing slashes
        Path gitignore = projectRoot.resolve(".gitignore");
        Files.writeString(
                gitignore,
                """
                # Directories with trailing slashes
                **/.idea/
                **/target/
                **/bin/

                # Files without trailing slashes
                *.log
                temp.txt

                # Mixed whitespace (should be preserved for trailing slash but trimmed otherwise)
                  node_modules/
                  *.tmp
                """);

        List<String> patterns = repo.getIgnoredPatterns();

        // Verify trailing slashes are preserved for directory patterns
        assertTrue(patterns.contains("**/.idea/"), "Should preserve trailing slash for **/.idea/");
        assertTrue(patterns.contains("**/target/"), "Should preserve trailing slash for **/target/");
        assertTrue(patterns.contains("**/bin/"), "Should preserve trailing slash for **/bin/");
        assertTrue(patterns.contains("node_modules/"), "Should preserve trailing slash for node_modules/");

        // Verify leading/trailing whitespace is trimmed for non-slash patterns
        assertTrue(patterns.contains("*.log"), "Should include *.log");
        assertTrue(patterns.contains("temp.txt"), "Should include temp.txt");
        assertTrue(patterns.contains("*.tmp"), "Should trim whitespace from *.tmp");

        // Verify comments are excluded
        assertFalse(patterns.stream().anyMatch(p -> p.startsWith("#")), "Should not include comment lines");
    }

    @Test
    void testGetIgnoredPatterns_EmptyFile() throws Exception {
        Path gitignore = projectRoot.resolve(".gitignore");
        Files.writeString(gitignore, "");

        List<String> patterns = repo.getIgnoredPatterns();
        assertTrue(patterns.isEmpty(), "Empty .gitignore should return empty list");
    }

    @Test
    void testGetIgnoredPatterns_OnlyComments() throws Exception {
        Path gitignore = projectRoot.resolve(".gitignore");
        Files.writeString(
                gitignore,
                """
                # This is a comment
                # Another comment
                """);

        List<String> patterns = repo.getIgnoredPatterns();
        assertTrue(patterns.isEmpty(), "File with only comments should return empty list");
    }

    @Test
    void testGetIgnoredPatterns_NoGitignoreFile() throws Exception {
        // Don't create .gitignore file
        List<String> patterns = repo.getIgnoredPatterns();
        assertTrue(patterns.isEmpty(), "Missing .gitignore should return empty list");
    }

    @Test
    void testGetIgnoredPatterns_WithNegation() throws Exception {
        Path gitignore = projectRoot.resolve(".gitignore");
        Files.writeString(
                gitignore,
                """
                *.log
                !important.log
                build/
                !build/keep/
                """);

        List<String> patterns = repo.getIgnoredPatterns();

        // Negation patterns should be preserved
        assertTrue(patterns.contains("*.log"));
        assertTrue(patterns.contains("!important.log"));
        assertTrue(patterns.contains("build/"));
        assertTrue(patterns.contains("!build/keep/"));
    }

    @Test
    void testGetIgnoredPatterns_MixedLineEndings() throws Exception {
        Path gitignore = projectRoot.resolve(".gitignore");
        // Mix of LF and CRLF line endings
        Files.writeString(gitignore, "*.log\r\ntarget/\nbin/\r\n");

        List<String> patterns = repo.getIgnoredPatterns();

        assertTrue(patterns.contains("*.log"));
        assertTrue(patterns.contains("target/"));
        assertTrue(patterns.contains("bin/"));
    }
}
