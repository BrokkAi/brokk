package io.github.jbellis.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
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
}
