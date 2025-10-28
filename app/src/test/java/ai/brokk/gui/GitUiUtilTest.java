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
}
