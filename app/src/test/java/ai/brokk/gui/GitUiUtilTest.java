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

    // ============ validateOwnerRepo tests (stricter validation) ============

    @Test
    void testValidateOwnerRepo_Valid() {
        var result = GitUiUtil.validateOwnerRepo("octocat", "Hello-World");
        assertTrue(result.isEmpty(), "Valid owner/repo should return empty Optional");
    }

    @Test
    void testValidateOwnerRepo_ValidWithHyphen() {
        var result = GitUiUtil.validateOwnerRepo("octo-cat", "hello-world");
        assertTrue(result.isEmpty(), "Valid owner/repo with hyphens should return empty Optional");
    }

    @Test
    void testValidateOwnerRepo_ValidWithUnderscoreDotInRepo() {
        var result = GitUiUtil.validateOwnerRepo("owner", "repo_name.lib");
        assertTrue(result.isEmpty(), "Valid repo with underscore and dot should return empty Optional");
    }

    @Test
    void testValidateOwnerRepo_OwnerLength39() {
        var result = GitUiUtil.validateOwnerRepo("a".repeat(39), "repo");
        assertTrue(result.isEmpty(), "Owner with exactly 39 characters should be valid");
    }

    @Test
    void testValidateOwnerRepo_OwnerLength40() {
        var result = GitUiUtil.validateOwnerRepo("a".repeat(40), "repo");
        assertTrue(result.isPresent(), "Owner with 40 characters should be invalid");
    }

    @Test
    void testValidateOwnerRepo_RepoLength100() {
        var result = GitUiUtil.validateOwnerRepo("owner", "a".repeat(100));
        assertTrue(result.isEmpty(), "Repo with exactly 100 characters should be valid");
    }

    @Test
    void testValidateOwnerRepo_RepoLength101() {
        var result = GitUiUtil.validateOwnerRepo("owner", "a".repeat(101));
        assertTrue(result.isPresent(), "Repo with 101 characters should be invalid");
    }

    @Test
    void testValidateOwnerRepo_OwnerLeadingHyphen() {
        var result = GitUiUtil.validateOwnerRepo("-octocat", "repo");
        assertTrue(result.isPresent(), "Owner with leading hyphen should be invalid");
    }

    @Test
    void testValidateOwnerRepo_OwnerTrailingHyphen() {
        var result = GitUiUtil.validateOwnerRepo("octocat-", "repo");
        assertTrue(result.isPresent(), "Owner with trailing hyphen should be invalid");
    }

    @Test
    void testValidateOwnerRepo_OwnerConsecutiveHyphens() {
        var result = GitUiUtil.validateOwnerRepo("octo--cat", "repo");
        assertTrue(result.isPresent(), "Owner with consecutive hyphens should be invalid");
    }

    @Test
    void testValidateOwnerRepo_OwnerWithUnderscore() {
        var result = GitUiUtil.validateOwnerRepo("octo_cat", "repo");
        assertTrue(result.isPresent(), "Owner with underscore should be invalid (stricter rules)");
    }

    @Test
    void testValidateOwnerRepo_OwnerWithDot() {
        var result = GitUiUtil.validateOwnerRepo("octo.cat", "repo");
        assertTrue(result.isPresent(), "Owner with dot should be invalid (stricter rules)");
    }

    @Test
    void testValidateOwnerRepo_RepoLeadingDot() {
        var result = GitUiUtil.validateOwnerRepo("owner", ".repo");
        assertTrue(result.isPresent(), "Repo with leading dot should be invalid");
    }

    @Test
    void testValidateOwnerRepo_RepoTrailingDot() {
        var result = GitUiUtil.validateOwnerRepo("owner", "repo.");
        assertTrue(result.isPresent(), "Repo with trailing dot should be invalid");
    }

    @Test
    void testValidateOwnerRepo_RepoOnlyDot() {
        var result = GitUiUtil.validateOwnerRepo("owner", ".");
        assertTrue(result.isPresent(), "Repo that is only '.' should be invalid");
    }

    @Test
    void testValidateOwnerRepo_RepoOnlyDoubleDot() {
        var result = GitUiUtil.validateOwnerRepo("owner", "..");
        assertTrue(result.isPresent(), "Repo that is only '..' should be invalid");
    }

    @Test
    void testValidateOwnerRepo_ValidWithSpaceTrimmed() {
        var result = GitUiUtil.validateOwnerRepo("  octocat  ", "  hello-world  ");
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
        assertTrue(result.isEmpty(), "Repo with .git suffix should be accepted and stripped by normalizer");
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

    // ============ normalizeOwnerRepo tests ============

    @Test
    void testNormalizeOwnerRepo_Valid() {
        var result = GitUiUtil.normalizeOwnerRepo("octocat", "hello-world");
        assertEquals("octocat", result.owner());
        assertEquals("hello-world", result.repo());
    }

    @Test
    void testNormalizeOwnerRepo_WithWhitespace() {
        var result = GitUiUtil.normalizeOwnerRepo("  octocat  ", "  hello-world  ");
        assertEquals("octocat", result.owner());
        assertEquals("hello-world", result.repo());
    }

    @Test
    void testNormalizeOwnerRepo_RepoWithGitSuffix() {
        var result = GitUiUtil.normalizeOwnerRepo("octocat", "hello-world.git");
        assertEquals("octocat", result.owner());
        assertEquals("hello-world", result.repo());
    }

    @Test
    void testNormalizeOwnerRepo_RepoWithGitSuffixAndWhitespace() {
        var result = GitUiUtil.normalizeOwnerRepo("  octocat  ", "  hello-world.git  ");
        assertEquals("octocat", result.owner());
        assertEquals("hello-world", result.repo());
    }

    @Test
    void testNormalizeOwnerRepo_InvalidOwner() {
        assertThrows(IllegalArgumentException.class, () -> GitUiUtil.normalizeOwnerRepo("octo@cat", "repo"));
    }

    @Test
    void testNormalizeOwnerRepo_InvalidRepo() {
        assertThrows(IllegalArgumentException.class, () -> GitUiUtil.normalizeOwnerRepo("owner", ".repo"));
    }

    @Test
    void testNormalizeOwnerRepo_Empty() {
        assertThrows(IllegalArgumentException.class, () -> GitUiUtil.normalizeOwnerRepo("", "repo"));
    }

    // ============ buildRepoSlug tests ============

    @Test
    void testBuildRepoSlug_Valid() {
        var result = GitUiUtil.buildRepoSlug("octocat", "hello-world");
        assertEquals("octocat/hello-world", result);
    }

    @Test
    void testBuildRepoSlug_WithWhitespace() {
        var result = GitUiUtil.buildRepoSlug("  octocat  ", "  hello-world  ");
        assertEquals("octocat/hello-world", result);
    }

    @Test
    void testBuildRepoSlug_RepoWithGitSuffix() {
        var result = GitUiUtil.buildRepoSlug("octocat", "hello-world.git");
        assertEquals("octocat/hello-world", result);
    }

    @Test
    void testBuildRepoSlug_InvalidOwner() {
        assertThrows(IllegalArgumentException.class, () -> GitUiUtil.buildRepoSlug("-owner", "repo"));
    }

    @Test
    void testBuildRepoSlug_InvalidRepo() {
        assertThrows(IllegalArgumentException.class, () -> GitUiUtil.buildRepoSlug("owner", "repo."));
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

    // ============ parseOwnerRepoFlexible tests ============

    @Test
    void testParseOwnerRepoFlexible_RawSlugValid() {
        var result = GitUiUtil.parseOwnerRepoFlexible("octocat/hello-world");
        assertTrue(result.isPresent());
        assertEquals("octocat", result.get().owner());
        assertEquals("hello-world", result.get().repo());
    }

    @Test
    void testParseOwnerRepoFlexible_RawSlugWithGitSuffix() {
        var result = GitUiUtil.parseOwnerRepoFlexible("octocat/hello-world.git");
        assertTrue(result.isPresent());
        assertEquals("octocat", result.get().owner());
        assertEquals("hello-world", result.get().repo());
    }

    @Test
    void testParseOwnerRepoFlexible_RawSlugWithWhitespace() {
        var result = GitUiUtil.parseOwnerRepoFlexible("  octocat / hello-world  ");
        assertTrue(result.isPresent());
        assertEquals("octocat", result.get().owner());
        assertEquals("hello-world", result.get().repo());
    }

    @Test
    void testParseOwnerRepoFlexible_HttpsUrl() {
        var result = GitUiUtil.parseOwnerRepoFlexible("https://github.com/octocat/hello-world.git");
        assertTrue(result.isPresent());
        assertEquals("octocat", result.get().owner());
        assertEquals("hello-world", result.get().repo());
    }

    @Test
    void testParseOwnerRepoFlexible_SshUrl() {
        var result = GitUiUtil.parseOwnerRepoFlexible("git@github.com:octocat/hello-world.git");
        assertTrue(result.isPresent());
        assertEquals("octocat", result.get().owner());
        assertEquals("hello-world", result.get().repo());
    }

    @Test
    void testParseOwnerRepoFlexible_SshUrlNoGit() {
        var result = GitUiUtil.parseOwnerRepoFlexible("ssh://github.com/octocat/hello-world/");
        assertTrue(result.isPresent());
        assertEquals("octocat", result.get().owner());
        assertEquals("hello-world", result.get().repo());
    }

    @Test
    void testParseOwnerRepoFlexible_PlainHostUrl() {
        var result = GitUiUtil.parseOwnerRepoFlexible("github.com/octocat/hello-world");
        assertTrue(result.isPresent());
        assertEquals("octocat", result.get().owner());
        assertEquals("hello-world", result.get().repo());
    }

    @Test
    void testParseOwnerRepoFlexible_UrlMissingRepo() {
        var result = GitUiUtil.parseOwnerRepoFlexible("https://github.com/octocat");
        assertTrue(result.isEmpty(), "URL with missing repo should return empty");
    }

    @Test
    void testParseOwnerRepoFlexible_UrlMissingOwnerAndRepo() {
        var result = GitUiUtil.parseOwnerRepoFlexible("https://github.com/");
        assertTrue(result.isEmpty(), "URL with missing owner and repo should return empty");
    }

    @Test
    void testParseOwnerRepoFlexible_NoSlash() {
        var result = GitUiUtil.parseOwnerRepoFlexible("octocat");
        assertTrue(result.isEmpty(), "Input without slash should return empty");
    }

    @Test
    void testParseOwnerRepoFlexible_Null() {
        var result = GitUiUtil.parseOwnerRepoFlexible(null);
        assertTrue(result.isEmpty(), "Null input should return empty");
    }

    @Test
    void testParseOwnerRepoFlexible_Blank() {
        var result = GitUiUtil.parseOwnerRepoFlexible("   ");
        assertTrue(result.isEmpty(), "Blank input should return empty");
    }

    // ============ normalizeGitHubHost tests ============

    @Test
    void testNormalizeGitHubHost_PlainHost() {
        var result = GitUiUtil.normalizeGitHubHost("github.example.com");
        assertTrue(result.isPresent());
        assertEquals("github.example.com", result.get());
    }

    @Test
    void testNormalizeGitHubHost_WithHttpsProtocol() {
        var result = GitUiUtil.normalizeGitHubHost("https://github.example.com");
        assertTrue(result.isPresent());
        assertEquals("github.example.com", result.get());
    }

    @Test
    void testNormalizeGitHubHost_WithHttpProtocol() {
        var result = GitUiUtil.normalizeGitHubHost("http://github.example.com");
        assertTrue(result.isPresent());
        assertEquals("github.example.com", result.get());
    }

    @Test
    void testNormalizeGitHubHost_WithTrailingSlash() {
        var result = GitUiUtil.normalizeGitHubHost("github.example.com/");
        assertTrue(result.isPresent());
        assertEquals("github.example.com", result.get());
    }

    @Test
    void testNormalizeGitHubHost_WithProtocolAndTrailingSlash() {
        var result = GitUiUtil.normalizeGitHubHost("https://github.example.com/");
        assertTrue(result.isPresent());
        assertEquals("github.example.com", result.get());
    }

    @Test
    void testNormalizeGitHubHost_WithPort() {
        var result = GitUiUtil.normalizeGitHubHost("github.example.com:8080");
        assertTrue(result.isPresent());
        assertEquals("github.example.com:8080", result.get());
    }

    @Test
    void testNormalizeGitHubHost_WithProtocolAndPort() {
        var result = GitUiUtil.normalizeGitHubHost("https://github.example.com:8443");
        assertTrue(result.isPresent());
        assertEquals("github.example.com:8443", result.get());
    }

    @Test
    void testNormalizeGitHubHost_Null() {
        var result = GitUiUtil.normalizeGitHubHost(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testNormalizeGitHubHost_Blank() {
        var result = GitUiUtil.normalizeGitHubHost("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void testNormalizeGitHubHost_WithWhitespace() {
        var result = GitUiUtil.normalizeGitHubHost("  github.example.com  ");
        assertTrue(result.isPresent());
        assertEquals("github.example.com", result.get());
    }

    // ============ validateGitHubHost tests ============

    @Test
    void testValidateGitHubHost_Valid() {
        var result = GitUiUtil.validateGitHubHost("github.example.com");
        assertTrue(result.isEmpty());
    }

    @Test
    void testValidateGitHubHost_ValidWithPort() {
        var result = GitUiUtil.validateGitHubHost("github.example.com:8443");
        assertTrue(result.isEmpty());
    }

    @Test
    void testValidateGitHubHost_BlankHost() {
        var result = GitUiUtil.validateGitHubHost("");
        assertTrue(result.isPresent());
    }

    @Test
    void testValidateGitHubHost_HostWithProtocol() {
        var result = GitUiUtil.validateGitHubHost("https://github.example.com");
        assertTrue(result.isPresent());
    }

    @Test
    void testValidateGitHubHost_HostWithPath() {
        var result = GitUiUtil.validateGitHubHost("github.example.com/path");
        assertTrue(result.isPresent());
    }

    @Test
    void testValidateGitHubHost_LabelStartsWithHyphen() {
        var result = GitUiUtil.validateGitHubHost("-github.example.com");
        assertTrue(result.isPresent());
    }

    @Test
    void testValidateGitHubHost_LabelEndsWithHyphen() {
        var result = GitUiUtil.validateGitHubHost("github-.example.com");
        assertTrue(result.isPresent());
    }

    @Test
    void testValidateGitHubHost_LabelWithUnderscore() {
        var result = GitUiUtil.validateGitHubHost("github_example.com");
        assertTrue(result.isPresent());
    }

    @Test
    void testValidateGitHubHost_ConsecutiveDots() {
        var result = GitUiUtil.validateGitHubHost("github..example.com");
        assertTrue(result.isPresent());
    }

    @Test
    void testValidateGitHubHost_InvalidPort() {
        var result = GitUiUtil.validateGitHubHost("github.example.com:abc");
        assertTrue(result.isPresent());
    }

    @Test
    void testValidateGitHubHost_PortOutOfRange() {
        var result = GitUiUtil.validateGitHubHost("github.example.com:99999");
        assertTrue(result.isPresent());
    }

    @Test
    void testValidateGitHubHost_TooLong() {
        var longLabel = "a".repeat(64);
        var result = GitUiUtil.validateGitHubHost(longLabel + ".example.com");
        assertTrue(result.isPresent());
    }

    // ============ Consolidated validator tests ============

    @Test
    void testValidateOwnerRepo_validCases() {
        assertTrue(GitUiUtil.validateOwnerRepo("owner", "repo").isEmpty());
        assertTrue(GitUiUtil.validateOwnerRepo("  owner  ", "  repo  ").isEmpty());
        assertTrue(GitUiUtil.validateOwnerRepo("owner", "my-repo_name").isEmpty());
        assertTrue(GitUiUtil.validateOwnerRepo("owner", "repo.git").isEmpty());
    }

    @Test
    void testValidateOwnerRepo_invalidCases() {
        // Empty parts
        var r1 = GitUiUtil.validateOwnerRepo("", "repo");
        assertTrue(r1.isPresent());
        assertTrue(r1.get().contains("owner/repo"));

        var r2 = GitUiUtil.validateOwnerRepo("owner", "");
        assertTrue(r2.isPresent());
        assertTrue(r2.get().contains("owner/repo"));

        // Owner with '/'
        var r3 = GitUiUtil.validateOwnerRepo("own/er", "repo");
        assertTrue(r3.isPresent());
        assertTrue(r3.get().contains("owner/repo"));

        // Repo with '/'
        var r4 = GitUiUtil.validateOwnerRepo("owner", "rep/o");
        assertTrue(r4.isPresent());
        assertTrue(r4.get().contains("owner/repo"));

        // Whitespace-only values
        var r5 = GitUiUtil.validateOwnerRepo("   ", "repo");
        assertTrue(r5.isPresent());
        assertTrue(r5.get().contains("owner/repo"));

        var r6 = GitUiUtil.validateOwnerRepo("owner", "   ");
        assertTrue(r6.isPresent());
        assertTrue(r6.get().contains("owner/repo"));
    }

    @Test
    void testValidateFullRepoName_consolidatedCases() {
        // Valid
        assertTrue(GitUiUtil.validateFullRepoName("owner/repo").isEmpty());
        // .git suffix on full name is accepted (stripped) and considered valid
        assertTrue(GitUiUtil.validateFullRepoName("owner/repo.git").isEmpty());

        // Invalid
        var i1 = GitUiUtil.validateFullRepoName("owner/repo/extra");
        assertTrue(i1.isPresent());
        assertTrue(i1.get().contains("owner/repo"));

        var i2 = GitUiUtil.validateFullRepoName("owner/");
        assertTrue(i2.isPresent());
        assertTrue(i2.get().contains("owner/repo"));

        var i3 = GitUiUtil.validateFullRepoName("/repo");
        assertTrue(i3.isPresent());
        assertTrue(i3.get().contains("owner/repo"));
    }
}
