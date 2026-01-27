package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for filesystem fallback when Git repo exists but has no tracked files.
 */
class EmptyGitRepoTest {

    private MainProject project;

    @AfterEach
    void tearDown() throws Exception {
        if (project != null) {
            project.close();
        }
    }

    @Test
    void testEmptyGitRepoFallsBackToFilesystem(@TempDir Path tempDir) throws Exception {
        // Initialize Git without committing any files
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.commit()
                    .setAllowEmpty(true)
                    .setMessage("Initial empty commit")
                    .setSign(false)
                    .call();
        }

        // Create source files but don't stage them
        Files.writeString(tempDir.resolve("Main.java"), "public class Main {}");
        Files.writeString(tempDir.resolve("app.py"), "print('hello')");

        project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY);
        Set<ProjectFile> allFiles = project.getAllFiles();

        // Verify we have at least the expected files (allows for filtering variations)
        assertTrue(allFiles.size() >= 2, "Should find at least Main.java and app.py");
        assertTrue(
                allFiles.stream().anyMatch(f -> f.getRelPath().equals(Path.of("Main.java"))), "Should find Main.java");
        assertTrue(allFiles.stream().anyMatch(f -> f.getRelPath().equals(Path.of("app.py"))), "Should find app.py");
    }

    @Test
    void testGitRepoWithStagedFilesUsesGit(@TempDir Path tempDir) throws Exception {
        // Initialize Git and stage files
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.commit()
                    .setAllowEmpty(true)
                    .setMessage("Initial commit")
                    .setSign(false)
                    .call();

            Files.writeString(tempDir.resolve("Staged.java"), "public class Staged {}");
            Files.writeString(tempDir.resolve("Unstaged.java"), "public class Unstaged {}");

            git.add().addFilepattern("Staged.java").call();
        }

        project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY);
        Set<ProjectFile> allFiles = project.getAllFiles();

        // Verify presence rather than exact count (Git tracking = staged/committed files)
        assertTrue(
                allFiles.stream().anyMatch(f -> f.getRelPath().equals(Path.of("Staged.java"))),
                "Should find Staged.java");
        assertFalse(
                allFiles.stream().anyMatch(f -> f.getRelPath().equals(Path.of("Unstaged.java"))),
                "Should NOT find unstaged file when using Git");
    }

    @Test
    void testGitRepoWithCommitsUsesGit(@TempDir Path tempDir) throws Exception {
        // Initialize Git and commit files
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.writeString(tempDir.resolve("Committed.java"), "public class Committed {}");
            git.add().addFilepattern("Committed.java").call();
            git.commit()
                    .setMessage("Add Committed.java")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();

            // Add untracked file
            Files.writeString(tempDir.resolve("Untracked.java"), "public class Untracked {}");
        }

        project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY);
        Set<ProjectFile> allFiles = project.getAllFiles();

        // Verify presence rather than exact count (Git tracking includes committed files)
        assertTrue(
                allFiles.stream().anyMatch(f -> f.getRelPath().equals(Path.of("Committed.java"))),
                "Should find Committed.java");
        assertFalse(
                allFiles.stream().anyMatch(f -> f.getRelPath().equals(Path.of("Untracked.java"))),
                "Should NOT find untracked file when using Git");
    }

    @Test
    void testFallbackInvalidatedOnStaging(@TempDir Path tempDir) throws Exception {
        // Initialize Git without staging files
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.commit()
                    .setAllowEmpty(true)
                    .setMessage("Initial commit")
                    .setSign(false)
                    .call();

            Files.writeString(tempDir.resolve("File1.java"), "public class File1 {}");
            Files.writeString(tempDir.resolve("File2.java"), "public class File2 {}");
        }

        project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY);

        // First call should use filesystem fallback
        Set<ProjectFile> filesBeforeStaging = project.getAllFiles();
        assertTrue(filesBeforeStaging.size() >= 2, "Should find at least File1 and File2 via fallback");

        // Stage one file
        try (Git git = Git.open(tempDir.toFile())) {
            git.add().addFilepattern("File1.java").call();
        }

        // Invalidate cache to simulate refresh
        project.invalidateAllFiles();
        assertTrue(project.getRepo() instanceof GitRepo, "Should be using GitRepo");
        var gitRepo = (GitRepo) project.getRepo();
        gitRepo.invalidateCaches();

        // Second call should use Git tracking (only staged files visible)
        Set<ProjectFile> filesAfterStaging = project.getAllFiles();
        assertTrue(
                filesAfterStaging.stream().anyMatch(f -> f.getRelPath().equals(Path.of("File1.java"))),
                "Should find File1.java");
        assertFalse(
                filesAfterStaging.stream().anyMatch(f -> f.getRelPath().equals(Path.of("File2.java"))),
                "Should NOT find File2.java (not staged)");
    }

    @Test
    void testEmptyGitRepoWithNoFiles(@TempDir Path tempDir) throws Exception {
        // Initialize Git with no files at all
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.commit()
                    .setAllowEmpty(true)
                    .setMessage("Initial commit")
                    .setSign(false)
                    .call();
        }

        project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY);
        Set<ProjectFile> allFiles = project.getAllFiles();

        assertEquals(0, allFiles.size(), "Empty Git repo with no files should return empty set");
    }

    @Test
    void testGitRepoWithOnlyGitIgnoredFiles(@TempDir Path tempDir) throws Exception {
        // Initialize Git and create .gitignore
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.commit()
                    .setAllowEmpty(true)
                    .setMessage("Initial commit")
                    .setSign(false)
                    .call();

            Files.writeString(tempDir.resolve(".gitignore"), "*.ignored\n");
            Files.writeString(tempDir.resolve("file.ignored"), "ignored content");
        }

        project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY);
        Set<ProjectFile> allFiles = project.getAllFiles();

        // .gitignore filtering should apply to fallback files too
        assertTrue(
                allFiles.stream().noneMatch(f -> f.getRelPath().toString().endsWith(".ignored")),
                "Gitignored files should be filtered even with filesystem fallback");
    }

    @Test
    void testNoDoubleWalkForLocalFileRepo(@TempDir Path tempDir) throws Exception {
        // Non-Git directory (no git init)
        Files.writeString(tempDir.resolve("Main.java"), "public class Main {}");

        project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY);
        Set<ProjectFile> allFiles = project.getAllFiles();

        assertTrue(allFiles.size() >= 1, "Should find at least Main.java");
        assertTrue(
                allFiles.stream().anyMatch(f -> f.getRelPath().equals(Path.of("Main.java"))), "Should find Main.java");

        // Verify it's using LocalFileRepo (not GitRepo)
        assertFalse(project.getRepo() instanceof GitRepo, "Should use LocalFileRepo for non-Git directory");
    }
}
