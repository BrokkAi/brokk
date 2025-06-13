package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GitUiUtilTest {
    
    @TempDir
    Path tempDir;
    
    @Test
    void testFormatFileList_NullList() {
        String result = GitUiUtil.formatFileList(null);
        assertEquals("no files", result);
    }
    
    @Test
    void testFormatFileList_EmptyList() {
        String result = GitUiUtil.formatFileList(new ArrayList<>());
        assertEquals("no files", result);
    }
    
    @Test
    void testFormatFileList_SingleFile() {
        List<ProjectFile> files = List.of(
            new ProjectFile(tempDir, "file1.txt")
        );
        String result = GitUiUtil.formatFileList(files);
        assertEquals("file1.txt", result);
    }
    
    @Test
    void testFormatFileList_TwoFiles() {
        List<ProjectFile> files = List.of(
            new ProjectFile(tempDir, "file1.txt"),
            new ProjectFile(tempDir, "file2.java")
        );
        String result = GitUiUtil.formatFileList(files);
        assertEquals("file1.txt, file2.java", result);
    }
    
    @Test
    void testFormatFileList_ThreeFiles() {
        List<ProjectFile> files = List.of(
            new ProjectFile(tempDir, "file1.txt"),
            new ProjectFile(tempDir, "file2.java"),
            new ProjectFile(tempDir, "file3.md")
        );
        String result = GitUiUtil.formatFileList(files);
        assertEquals("file1.txt, file2.java, file3.md", result);
    }
    
    @Test
    void testFormatFileList_FourFiles() {
        List<ProjectFile> files = List.of(
            new ProjectFile(tempDir, "file1.txt"),
            new ProjectFile(tempDir, "file2.java"),
            new ProjectFile(tempDir, "file3.md"),
            new ProjectFile(tempDir, "file4.xml")
        );
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
            new ProjectFile(tempDir, "src/main/java/File.java"),
            new ProjectFile(tempDir, "test/File.java")
        );
        String result = GitUiUtil.formatFileList(files);
        assertEquals("File.java, File.java", result);
    }
    
    @Test
    void testShortenCommitId_NullInput() {
        String result = GitUiUtil.shortenCommitId(null);
        assertNull(result);
    }
    
    @Test
    void testShortenCommitId_EmptyString() {
        String result = GitUiUtil.shortenCommitId("");
        assertEquals("", result);
    }
    
    @Test
    void testShortenCommitId_ShortCommitId() {
        String shortId = "abc123";
        String result = GitUiUtil.shortenCommitId(shortId);
        assertEquals("abc123", result);
    }
    
    @Test
    void testShortenCommitId_ExactlySeven() {
        String sevenChars = "abc1234";
        String result = GitUiUtil.shortenCommitId(sevenChars);
        assertEquals("abc1234", result);
    }
    
    @Test
    void testShortenCommitId_LongCommitId() {
        String longId = "abc123456789abcdef0123456789abcdef01234567";
        String result = GitUiUtil.shortenCommitId(longId);
        assertEquals("abc1234", result);
    }
    
    @Test
    void testShortenCommitId_EightCharacters() {
        String eightChars = "abc12345";
        String result = GitUiUtil.shortenCommitId(eightChars);
        assertEquals("abc1234", result);
    }
    
    @Test
    void testShortenCommitId_TypicalGitHash() {
        String gitHash = "f4a8b2c1d9e7f3a6b5c8d0e2f1a9b7c4d6e8f0a2";
        String result = GitUiUtil.shortenCommitId(gitHash);
        assertEquals("f4a8b2c", result);
    }
}