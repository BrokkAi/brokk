package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.git.GitRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for detecting whether a project is empty (newly created with no source files).
 */
class ProjectEmptyDetectionTest {

    /**
     * Test that a newly initialized git repository with no files is considered empty.
     */
    @Test
    void testEmptyGitRepo(@TempDir Path tempDir) throws Exception {
        // Initialize a fresh git repo with an empty initial commit
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Create an empty initial commit so the repo has a valid HEAD
            git.commit().setAllowEmpty(true).setMessage("Initial commit").call();
        }

        MainProject project = new MainProject(tempDir);
        try {
            assertTrue(project.isEmptyProject(), "Empty git repo should be detected as empty project");
        } finally {
            project.close();
        }
    }

    /**
     * Test that a git repository containing only .gitignore and .gitattributes is considered empty.
     */
    @Test
    void testRepoWithOnlyConfigFiles(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Add only config files
            Path gitignore = tempDir.resolve(".gitignore");
            Files.writeString(gitignore, "*.log\n");

            Path gitattributes = tempDir.resolve(".gitattributes");
            Files.writeString(gitattributes, "* text=auto\n");

            Path editorconfig = tempDir.resolve(".editorconfig");
            Files.writeString(editorconfig, "root = true\n");

            // Stage and commit config files
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit with config files").call();
        }

        MainProject project = new MainProject(tempDir);
        try {
            assertTrue(project.isEmptyProject(),
                    "Repo with only config files should be detected as empty project");
        } finally {
            project.close();
        }
    }

    /**
     * Test that a git repository containing a .brokk directory is still considered empty
     * if it has no other source files.
     */
    @Test
    void testRepoWithOnlyBrokkDir(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Create .brokk directory with some files
            Path brokkDir = tempDir.resolve(".brokk");
            Files.createDirectory(brokkDir);
            Files.writeString(brokkDir.resolve("config.properties"), "key=value\n");

            // Stage and commit
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit with .brokk").call();
        }

        MainProject project = new MainProject(tempDir);
        try {
            assertTrue(project.isEmptyProject(),
                    "Repo with only .brokk directory should be detected as empty project");
        } finally {
            project.close();
        }
    }

    /**
     * Test that a git repository with a single source file is NOT considered empty.
     */
    @Test
    void testRepoWithSourceFile(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Add a source file
            Path sourceFile = tempDir.resolve("Main.java");
            Files.writeString(sourceFile, "public class Main { }\n");

            // Stage and commit
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit with source").call();
        }

        MainProject project = new MainProject(tempDir);
        try {
            assertFalse(project.isEmptyProject(),
                    "Repo with a source file should NOT be detected as empty project");
        } finally {
            project.close();
        }
    }

    /**
     * Test that a git repository with config files AND a source file is NOT considered empty.
     */
    @Test
    void testRepoWithConfigAndSourceFile(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Add config files
            Files.writeString(tempDir.resolve(".gitignore"), "*.log\n");
            Files.writeString(tempDir.resolve(".gitattributes"), "* text=auto\n");

            // Add source file
            Path sourceFile = tempDir.resolve("App.java");
            Files.writeString(sourceFile, "public class App { }\n");

            // Stage and commit
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
        }

        MainProject project = new MainProject(tempDir);
        try {
            assertFalse(project.isEmptyProject(),
                    "Repo with config files and a source file should NOT be empty");
        } finally {
            project.close();
        }
    }

    /**
     * Test that a non-git directory is not considered empty (returns false).
     */
    @Test
    void testNonGitDirectoryNotEmpty(@TempDir Path tempDir) throws Exception {
        // No git initialization, so not a git repo

        MainProject project = new MainProject(tempDir);
        try {
            assertFalse(project.isEmptyProject(),
                    "Non-git project should not be considered empty");
        } finally {
            project.close();
        }
    }

}
