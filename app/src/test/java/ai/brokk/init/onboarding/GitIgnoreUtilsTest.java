package ai.brokk.init.onboarding;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for GitIgnoreUtils.
 * Validates .gitignore pattern matching for .brokk directory.
 * Tests both Path-based and ProjectFile-based overloads.
 */
class GitIgnoreUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void testIsBrokkIgnored_FileDoesNotExist() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        assertFalse(GitIgnoreUtils.isBrokkIgnored(gitignorePath), ".gitignore doesn't exist, should return false");
    }

    @Test
    void testIsBrokkIgnored_EmptyFile() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "");
        assertFalse(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "Empty .gitignore should return false");
    }

    @Test
    void testIsBrokkIgnored_ComprehensivePattern_BrokkSlashStarStar() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, ".brokk/**\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath), ".brokk/** pattern should be recognized");
    }

    @Test
    void testIsBrokkIgnored_ComprehensivePattern_BrokkSlash() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, ".brokk/\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath), ".brokk/ pattern should be recognized");
    }

    @Test
    void testIsBrokkIgnored_WithLeadingSlash_BrokkSlashStarStar() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "/.brokk/**\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "/.brokk/** pattern should be recognized");
    }

    @Test
    void testIsBrokkIgnored_WithLeadingSlash_BrokkSlash() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "/.brokk/\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "/.brokk/ pattern should be recognized");
    }

    @Test
    void testIsBrokkIgnored_WithLeadingDotSlash_BrokkSlashStarStar() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "./.brokk/**\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "./.brokk/** pattern should be recognized");
    }

    @Test
    void testIsBrokkIgnored_WithLeadingDotSlash_BrokkSlash() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "./.brokk/\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "./.brokk/ pattern should be recognized");
    }

    @Test
    void testIsBrokkIgnored_WithWhitespace() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "  .brokk/**  \n");
        assertTrue(
                GitIgnoreUtils.isBrokkIgnored(gitignorePath),
                ".brokk/** with surrounding whitespace should be recognized");
    }

    @Test
    void testIsBrokkIgnored_WithWhitespaceAndLeadingSlash() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "  /.brokk/  \n");
        assertTrue(
                GitIgnoreUtils.isBrokkIgnored(gitignorePath),
                "/.brokk/ with surrounding whitespace should be recognized");
    }

    @Test
    void testIsBrokkIgnored_WithComment() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, ".brokk/** # Brokk configuration\n");
        assertTrue(
                GitIgnoreUtils.isBrokkIgnored(gitignorePath), ".brokk/** with trailing comment should be recognized");
    }

    @Test
    void testIsBrokkIgnored_WithCommentAndLeadingSlash() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "/.brokk/  # Brokk config directory\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "/.brokk/ with trailing comment should be recognized");
    }

    @Test
    void testIsBrokkIgnored_PartialPattern_ShouldNotMatch() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, ".brokk/workspace.properties\n");
        assertFalse(
                GitIgnoreUtils.isBrokkIgnored(gitignorePath),
                "Partial pattern .brokk/workspace.properties should NOT be recognized");
    }

    @Test
    void testIsBrokkIgnored_PartialPatternWithLeadingSlash_ShouldNotMatch() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "/.brokk/workspace.properties\n");
        assertFalse(
                GitIgnoreUtils.isBrokkIgnored(gitignorePath),
                "Partial pattern /.brokk/workspace.properties should NOT be recognized");
    }

    @Test
    void testIsBrokkIgnored_MultipleLines_ComprehensivePatternPresent() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(
                gitignorePath,
                """
                node_modules/
                .brokk/workspace.properties
                /.brokk/**
                *.log
                """);
        assertTrue(
                GitIgnoreUtils.isBrokkIgnored(gitignorePath),
                "Should find /.brokk/** among multiple patterns including partial ones");
    }

    @Test
    void testIsBrokkIgnored_MultipleLines_OnlyPartialPatterns() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(
                gitignorePath,
                """
                .brokk/workspace.properties
                .brokk/sessions/
                .brokk/history.zip
                """);
        assertFalse(
                GitIgnoreUtils.isBrokkIgnored(gitignorePath),
                "Should NOT match when only partial .brokk patterns are present");
    }

    @Test
    void testIsBrokkIgnored_MixedWhitespaceAndComments() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(
                gitignorePath,
                """
                # Brokk configuration
                  /.brokk/  # Comprehensive ignore
                """);
        assertTrue(
                GitIgnoreUtils.isBrokkIgnored(gitignorePath),
                "Should recognize pattern with mixed whitespace and comments");
    }

    @Test
    void testIsBrokkIgnored_CommentOnly_ShouldNotMatch() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "# .brokk/**\n");
        assertFalse(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "Commented out pattern should NOT be recognized");
    }

    @Test
    void testIsBrokkIgnored_PatternInMiddleOfLine_ShouldNotMatch() throws IOException {
        Path gitignorePath = tempDir.resolve(".gitignore");
        Files.writeString(gitignorePath, "something/.brokk/**\n");
        assertFalse(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "Pattern in middle of path should NOT be recognized");
    }

    // Tests for ProjectFile overload

    @Test
    void testIsBrokkIgnored_ProjectFile_FileDoesNotExist() throws IOException {
        ProjectFile gitignoreFile = new ProjectFile(tempDir, ".gitignore");
        assertFalse(GitIgnoreUtils.isBrokkIgnored(gitignoreFile), ".gitignore doesn't exist, should return false");
    }

    @Test
    void testIsBrokkIgnored_ProjectFile_EmptyFile() throws IOException {
        ProjectFile gitignoreFile = new ProjectFile(tempDir, ".gitignore");
        Files.writeString(gitignoreFile.absPath(), "");
        assertFalse(GitIgnoreUtils.isBrokkIgnored(gitignoreFile), "Empty .gitignore should return false");
    }

    @Test
    void testIsBrokkIgnored_ProjectFile_ComprehensivePattern() throws IOException {
        ProjectFile gitignoreFile = new ProjectFile(tempDir, ".gitignore");
        Files.writeString(gitignoreFile.absPath(), ".brokk/**\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignoreFile), ".brokk/** pattern should be recognized");
    }

    @Test
    void testIsBrokkIgnored_ProjectFile_WithLeadingSlash() throws IOException {
        ProjectFile gitignoreFile = new ProjectFile(tempDir, ".gitignore");
        Files.writeString(gitignoreFile.absPath(), "/.brokk/**\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignoreFile), "/.brokk/** pattern should be recognized");
    }

    @Test
    void testIsBrokkIgnored_ProjectFile_WithComment() throws IOException {
        ProjectFile gitignoreFile = new ProjectFile(tempDir, ".gitignore");
        Files.writeString(gitignoreFile.absPath(), ".brokk/** # Brokk configuration\n");
        assertTrue(
                GitIgnoreUtils.isBrokkIgnored(gitignoreFile), ".brokk/** with trailing comment should be recognized");
    }

    @Test
    void testIsBrokkIgnored_ProjectFile_PartialPattern_ShouldNotMatch() throws IOException {
        ProjectFile gitignoreFile = new ProjectFile(tempDir, ".gitignore");
        Files.writeString(gitignoreFile.absPath(), ".brokk/workspace.properties\n");
        assertFalse(
                GitIgnoreUtils.isBrokkIgnored(gitignoreFile),
                "Partial pattern .brokk/workspace.properties should NOT be recognized");
    }

    @Test
    void testIsBrokkIgnored_ProjectFile_MultipleLines_ComprehensivePatternPresent() throws IOException {
        ProjectFile gitignoreFile = new ProjectFile(tempDir, ".gitignore");
        Files.writeString(
                gitignoreFile.absPath(),
                """
                node_modules/
                .brokk/workspace.properties
                /.brokk/**
                *.log
                """);
        assertTrue(
                GitIgnoreUtils.isBrokkIgnored(gitignoreFile),
                "Should find /.brokk/** among multiple patterns including partial ones");
    }

    @Test
    void testIsBrokkIgnored_ProjectFile_MultipleLines_OnlyPartialPatterns() throws IOException {
        ProjectFile gitignoreFile = new ProjectFile(tempDir, ".gitignore");
        Files.writeString(
                gitignoreFile.absPath(),
                """
                .brokk/workspace.properties
                .brokk/sessions/
                .brokk/history.zip
                """);
        assertFalse(
                GitIgnoreUtils.isBrokkIgnored(gitignoreFile),
                "Should NOT match when only partial .brokk patterns are present");
    }
}
