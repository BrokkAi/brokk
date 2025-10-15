package io.github.jbellis.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class BuildAgentGitIgnoreExpansionTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("testProject");
        Files.createDirectories(projectRoot);

        // Initialize a git repo (required for proper .gitignore handling)
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            Path readme = projectRoot.resolve("README.md");
            Files.writeString(readme, "Test project");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test User", "test@example.com")
                    .setSign(false)
                    .call();
        }
    }

    @Test
    void findIgnoredDirectories_expands_glob_patterns() throws Exception {
        // Create directory structure with multiple .idea directories
        Files.createDirectories(projectRoot.resolve(".idea"));
        Files.createDirectories(projectRoot.resolve("module1/.idea"));
        Files.createDirectories(projectRoot.resolve("module2/.idea"));
        Files.createDirectories(projectRoot.resolve("module2/submodule/.idea"));

        // Create .gitignore with glob pattern
        Path gitignore = projectRoot.resolve(".gitignore");
        Files.writeString(gitignore, "**/.idea/\n");

        // Use reflection to call private method findIgnoredDirectories
        var method = BuildAgent.class.getDeclaredMethod("findIgnoredDirectories", Path.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> ignored = (List<String>) method.invoke(null, projectRoot);

        // Verify all .idea directories are found
        assertTrue(ignored.contains(".idea"), "Should find root .idea");
        assertTrue(ignored.contains("module1/.idea"), "Should find module1/.idea");
        assertTrue(ignored.contains("module2/.idea"), "Should find module2/.idea");
        assertTrue(ignored.contains("module2/submodule/.idea"), "Should find nested .idea");
    }

    @Test
    void findIgnoredDirectories_mixed_patterns() throws Exception {
        // Create directory structure
        Files.createDirectories(projectRoot.resolve("node_modules"));
        Files.createDirectories(projectRoot.resolve("dist"));
        Files.createDirectories(projectRoot.resolve(".idea"));
        Files.createDirectories(projectRoot.resolve("frontend/.idea"));
        Files.createDirectories(projectRoot.resolve("frontend/node_modules"));
        Files.createDirectories(projectRoot.resolve("backend/.idea"));

        // Create .gitignore with mixed patterns
        Path gitignore = projectRoot.resolve(".gitignore");
        Files.writeString(
                gitignore,
                """
                # Glob patterns
                **/.idea/
                **/node_modules/

                # Literal pattern
                dist/
                """);

        var method = BuildAgent.class.getDeclaredMethod("findIgnoredDirectories", Path.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> ignored = (List<String>) method.invoke(null, projectRoot);

        // Verify all directories are found
        assertTrue(ignored.contains(".idea"), "Should find root .idea");
        assertTrue(ignored.contains("frontend/.idea"), "Should find frontend/.idea");
        assertTrue(ignored.contains("backend/.idea"), "Should find backend/.idea");
        assertTrue(ignored.contains("node_modules"), "Should find root node_modules");
        assertTrue(ignored.contains("frontend/node_modules"), "Should find frontend/node_modules");
        assertTrue(ignored.contains("dist"), "Should find dist");
    }

    @Test
    void findIgnoredDirectories_no_gitignore_returns_empty() throws Exception {
        // Don't create .gitignore file
        Files.createDirectories(projectRoot.resolve(".idea"));
        Files.createDirectories(projectRoot.resolve("target"));

        var method = BuildAgent.class.getDeclaredMethod("findIgnoredDirectories", Path.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> ignored = (List<String>) method.invoke(null, projectRoot);

        assertTrue(ignored.isEmpty(), "Should return empty list when .gitignore doesn't exist");
    }

    @Test
    void findIgnoredDirectories_empty_gitignore_returns_empty() throws Exception {
        // Create empty .gitignore
        Path gitignore = projectRoot.resolve(".gitignore");
        Files.writeString(gitignore, "");

        Files.createDirectories(projectRoot.resolve(".idea"));
        Files.createDirectories(projectRoot.resolve("target"));

        var method = BuildAgent.class.getDeclaredMethod("findIgnoredDirectories", Path.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> ignored = (List<String>) method.invoke(null, projectRoot);

        assertTrue(ignored.isEmpty(), "Should return empty list when .gitignore is empty");
    }

    @Test
    void findIgnoredDirectories_respects_negation() throws Exception {
        // Create directory structure
        Files.createDirectories(projectRoot.resolve("build"));
        Files.createDirectories(projectRoot.resolve("build/keep"));

        // Create .gitignore with negation pattern
        Path gitignore = projectRoot.resolve(".gitignore");
        Files.writeString(gitignore, """
                build/
                !build/keep/
                """);

        var method = BuildAgent.class.getDeclaredMethod("findIgnoredDirectories", Path.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> ignored = (List<String>) method.invoke(null, projectRoot);

        // build should be ignored, but build/keep should not be (negated)
        assertTrue(ignored.contains("build"), "Should find build");
        assertFalse(ignored.contains("build/keep"), "Should not find build/keep (negated)");
    }

    @Test
    void findIgnoredDirectories_uses_forward_slashes() throws Exception {
        // Create nested directory structure
        Files.createDirectories(projectRoot.resolve("src/main/target"));
        Files.createDirectories(projectRoot.resolve("src/test/target"));

        // Create .gitignore
        Path gitignore = projectRoot.resolve(".gitignore");
        Files.writeString(gitignore, "**/target/\n");

        var method = BuildAgent.class.getDeclaredMethod("findIgnoredDirectories", Path.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> ignored = (List<String>) method.invoke(null, projectRoot);

        // Verify paths use forward slashes (cross-platform)
        assertTrue(ignored.stream().anyMatch(p -> p.equals("src/main/target")), "Should use forward slashes in paths");
        assertTrue(ignored.stream().anyMatch(p -> p.equals("src/test/target")), "Should use forward slashes in paths");
        // Verify no backslashes on Windows
        assertFalse(ignored.stream().anyMatch(p -> p.contains("\\")), "Paths should not contain backslashes");
    }
}
