package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo.ModificationType;
import ai.brokk.gui.util.GitUiUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitUiUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void testFormatFileList_EmptyList() {
        String result = GitUiUtil.formatFileList(new ArrayList<>());
        assertEquals("no files", result);
    }

    @Test
    void testFormatFileList_SingleFile() {
        List<ProjectFile> files = List.of(new ProjectFile(tempDir, "file1.txt"));
        String result = GitUiUtil.formatFileList(files);
        assertEquals("file1.txt", result);
    }

    @Test
    void testFormatFileList_TwoFiles() {
        List<ProjectFile> files =
                List.of(new ProjectFile(tempDir, "file1.txt"), new ProjectFile(tempDir, "file2.java"));
        String result = GitUiUtil.formatFileList(files);
        assertEquals("file1.txt, file2.java", result);
    }

    @Test
    void testFormatFileList_ThreeFiles() {
        List<ProjectFile> files = List.of(
                new ProjectFile(tempDir, "file1.txt"),
                new ProjectFile(tempDir, "file2.java"),
                new ProjectFile(tempDir, "file3.md"));
        String result = GitUiUtil.formatFileList(files);
        assertEquals("file1.txt, file2.java, file3.md", result);
    }

    @Test
    void testFormatFileList_FourFiles() {
        List<ProjectFile> files = List.of(
                new ProjectFile(tempDir, "file1.txt"),
                new ProjectFile(tempDir, "file2.java"),
                new ProjectFile(tempDir, "file3.md"),
                new ProjectFile(tempDir, "file4.xml"));
        String result = GitUiUtil.formatFileList(files);
        assertEquals("4 files", result);
    }

    @Test
    void testFormatFileList_ManyFiles() {
        List<ProjectFile> files = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            files.add(new ProjectFile(tempDir, "file" + i + ".txt"));
        }
        String result = GitUiUtil.formatFileList(files);
        assertEquals("10 files", result);
    }

    @Test
    void testFormatFileList_FilesInSubdirectories() {
        List<ProjectFile> files = List.of(
                new ProjectFile(tempDir, "src/main/java/File.java"), new ProjectFile(tempDir, "test/File.java"));
        String result = GitUiUtil.formatFileList(files);
        assertEquals("File.java, File.java", result);
    }

    @Test
    void testFilterTextFiles_MixedFiles() throws IOException {
        // Create actual files so isText() can check their properties
        Files.createFile(tempDir.resolve("Main.java"));
        Files.createFile(tempDir.resolve("image.png"));
        Files.createFile(tempDir.resolve("README.md"));
        Files.createFile(tempDir.resolve("logo.pdf"));

        List<GitRepo.ModifiedFile> modifiedFiles = List.of(
                new GitRepo.ModifiedFile(new ProjectFile(tempDir, "Main.java"), ModificationType.MODIFIED),
                new GitRepo.ModifiedFile(new ProjectFile(tempDir, "image.png"), ModificationType.NEW),
                new GitRepo.ModifiedFile(new ProjectFile(tempDir, "README.md"), ModificationType.MODIFIED),
                new GitRepo.ModifiedFile(new ProjectFile(tempDir, "logo.pdf"), ModificationType.NEW));

        List<ProjectFile> result = GitUiUtil.filterTextFiles(modifiedFiles);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(f -> f.getFileName().equals("Main.java")));
        assertTrue(result.stream().anyMatch(f -> f.getFileName().equals("README.md")));
        assertFalse(result.stream().anyMatch(f -> f.getFileName().equals("image.png")));
        assertFalse(result.stream().anyMatch(f -> f.getFileName().equals("logo.pdf")));
    }

    // ============ validateOwnerRepo tests ============

    @Test
    void testValidateOwnerRepo_Valid() {
        var result = GitUiUtil.validateOwnerRepo("octocat", "Hello-World");
        assertTrue(result.isEmpty(), "Valid owner/repo should return empty Optional");
    }

    @Test
    void testValidateOwnerRepo_ValidWithUnderscoreAndDot() {
        var result = GitUiUtil.validateOwnerRepo("octo_cat", "Hello.World");
        assertTrue(result.isEmpty(), "Valid owner/repo with underscore and dot should return empty Optional");
    }

    @Test
    void testValidateOwnerRepo_ValidWithSpaceTrimmed() {
        var result = GitUiUtil.validateOwnerRepo("  octocat  ", "  Hello-World  ");
        assertTrue(result.isEmpty(), "Owner/repo with spaces should be trimmed and valid");
    }

    @Test
    void testValidateOwnerRepo_EmptyOwner() {
        var result = GitUiUtil.validateOwnerRepo("", "Hello-World");
        assertTrue(result.isPresent(), "Empty owner should be invalid");
        assertTrue(result.get().contains("owner/repo"), "Error message should mention format");
    }

    @Test
    void testValidateOwnerRepo_EmptyRepo() {
        var result = GitUiUtil.validateOwnerRepo("octocat", "");
        assertTrue(result.isPresent(), "Empty repo should be invalid");
    }

    @Test
    void testValidateOwnerRepo_OwnerWithSlash() {
        var result = GitUiUtil.validateOwnerRepo("octo/cat", "Hello-World");
        assertTrue(result.isPresent(), "Owner containing slash should be invalid");
    }

    @Test
    void testValidateOwnerRepo_RepoWithSlash() {
        var result = GitUiUtil.validateOwnerRepo("octocat", "Hello/World");
        assertTrue(result.isPresent(), "Repo containing slash should be invalid");
    }

    @Test
    void testValidateOwnerRepo_RepoWithGitSuffix() {
        var result = GitUiUtil.validateOwnerRepo("octocat", "Hello-World.git");
        assertTrue(result.isEmpty(), "Repo with .git suffix should be stripped and valid");
    }

    @Test
    void testValidateOwnerRepo_OwnerWithGitSuffix() {
        var result = GitUiUtil.validateOwnerRepo("octocat.git", "Hello-World");
        assertTrue(result.isPresent(), "Owner with .git suffix should be invalid");
    }

    @Test
    void testValidateOwnerRepo_NullOwner() {
        var result = GitUiUtil.validateOwnerRepo(null, "Hello-World");
        assertTrue(result.isPresent(), "Null owner should be invalid");
    }

    @Test
    void testValidateOwnerRepo_NullRepo() {
        var result = GitUiUtil.validateOwnerRepo("octocat", null);
        assertTrue(result.isPresent(), "Null repo should be invalid");
    }

    @Test
    void testValidateOwnerRepo_InvalidCharactersInOwner() {
        var result = GitUiUtil.validateOwnerRepo("octo@cat", "Hello-World");
        assertTrue(result.isPresent(), "Owner with invalid character @ should be invalid");
    }

    @Test
    void testValidateOwnerRepo_InvalidCharactersInRepo() {
        var result = GitUiUtil.validateOwnerRepo("octocat", "Hello World");
        assertTrue(result.isPresent(), "Repo with space should be invalid");
    }

    // ============ validateFullRepoName tests ============

    @Test
    void testValidateFullRepoName_Valid() {
        var result = GitUiUtil.validateFullRepoName("octocat/Hello-World");
        assertTrue(result.isEmpty(), "Valid full repo name should return empty Optional");
    }

    @Test
    void testValidateFullRepoName_ValidWithGitSuffix() {
        var result = GitUiUtil.validateFullRepoName("octocat/Hello-World.git");
        assertTrue(result.isEmpty(), "Full repo name with .git suffix should be stripped and valid");
    }

    @Test
    void testValidateFullRepoName_ValidWithSpaces() {
        var result = GitUiUtil.validateFullRepoName("  octocat/Hello-World  ");
        assertTrue(result.isEmpty(), "Full repo name with spaces should be trimmed and valid");
    }

    @Test
    void testValidateFullRepoName_BlankInput() {
        var result = GitUiUtil.validateFullRepoName("   ");
        assertTrue(result.isPresent(), "Blank input should be invalid");
    }

    @Test
    void testValidateFullRepoName_NullInput() {
        var result = GitUiUtil.validateFullRepoName(null);
        assertTrue(result.isPresent(), "Null input should be invalid");
    }

    @Test
    void testValidateFullRepoName_NoSlash() {
        var result = GitUiUtil.validateFullRepoName("octocatHello-World");
        assertTrue(result.isPresent(), "Full repo name without slash should be invalid");
    }

    @Test
    void testValidateFullRepoName_TooManySlashes() {
        var result = GitUiUtil.validateFullRepoName("org/octocat/Hello-World");
        assertTrue(result.isPresent(), "Full repo name with too many slashes should be invalid");
    }

    @Test
    void testValidateFullRepoName_EmptyOwner() {
        var result = GitUiUtil.validateFullRepoName("/Hello-World");
        assertTrue(result.isPresent(), "Full repo name with empty owner should be invalid");
    }

    @Test
    void testValidateFullRepoName_EmptyRepo() {
        var result = GitUiUtil.validateFullRepoName("octocat/");
        assertTrue(result.isPresent(), "Full repo name with empty repo should be invalid");
    }

    @Test
    void testValidateFullRepoName_InvalidCharacters() {
        var result = GitUiUtil.validateFullRepoName("octo@cat/Hello#World");
        assertTrue(result.isPresent(), "Full repo name with invalid characters should be invalid");
    }
}
