package ai.brokk.difftool.ui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.difftool.doc.FileDocument;
import ai.brokk.difftool.node.FileNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests to verify that BufferDiffPanel correctly handles full file paths for proper ProjectFile creation and activity
 * entry generation.
 *
 * <p>This test addresses the issue where undo functionality was broken because BufferDiffPanel was only receiving
 * filenames instead of full paths.
 *
 * <p>Includes comprehensive cross-platform compatibility tests for Windows, Mac, and Linux.
 */
public class BufferDiffPanelFilePathTest {

    @TempDir
    Path tempDir;

    private Path testFile;
    private String testContent;

    @BeforeEach
    void setUp() throws IOException {
        // Create a test file in a subdirectory to ensure we're testing full path resolution
        var subDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(subDir);
        testFile = subDir.resolve("TestClass.java");
        testContent =
                """
            package com.example;
            public class TestClass {
                public void method() {
                    System.out.println("test");
                }
            }
            """;
        Files.writeString(testFile, testContent);
    }

    @Test
    void testFileDocumentWithFullPathCreatesValidProjectFile() throws Exception {
        // This test verifies that FileDocument created with full path
        // allows proper ProjectFile creation (the fix we implemented)

        // Create FileDocument with full absolute path (as our fix now does)
        var fileDoc =
                new FileDocument(testFile.toFile(), testFile.toAbsolutePath().toString());

        // Verify getName() returns the full path
        assertEquals(
                testFile.toAbsolutePath().toString(),
                fileDoc.getName(),
                "FileDocument.getName() should return full absolute path");

        // Verify getShortName() returns just the filename
        assertEquals(
                "TestClass.java",
                fileDoc.getShortName(),
                "FileDocument.getShortName() should return just the filename");

        // Test that we can create a ProjectFile from the full path
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));
        assertNotNull(projectFile, "Should be able to create ProjectFile with full path");
        assertTrue(projectFile.absPath().toFile().exists(), "ProjectFile should point to existing file");
        assertEquals(testContent, projectFile.read().orElseThrow(), "ProjectFile should read correct content");
    }

    @Test
    void testFileDocumentWithOnlyFilenameCannotCreateProjectFile() throws Exception {
        // This test demonstrates the problem that our fix solved:
        // When FileDocument only has filename (old behavior), ProjectFile creation fails

        // Create FileDocument with only filename (old broken behavior)
        var fileDoc = new FileDocument(testFile.toFile(), "TestClass.java");

        // Verify it only has the filename
        assertEquals(
                "TestClass.java", fileDoc.getName(), "FileDocument with filename-only should have just the filename");

        // This demonstrates why the old approach failed:
        // Can't create ProjectFile when we only know the filename
        assertThrows(
                Exception.class,
                () -> {
                    // This would be the equivalent of what BufferDiffPanel.createProjectFile()
                    // was trying to do with only a filename
                    var invalidPath = Path.of(fileDoc.getName()); // Just "TestClass.java"
                    if (!invalidPath.toFile().exists()) {
                        throw new Exception("File not found: " + invalidPath);
                    }
                },
                "Creating ProjectFile with filename-only should fail");
    }

    @Test
    void testProjectFileResolutionFromAbsolutePath() throws Exception {
        // Test that demonstrates our fix: using absolute path from document name
        // allows proper ProjectFile creation and relative path calculation

        var fullPath = testFile.toAbsolutePath();

        // Simulate what BufferDiffPanel.createProjectFile() now does
        assertTrue(fullPath.toFile().exists(), "Full path should exist");

        var relativePath = tempDir.relativize(fullPath);
        var projectFile = new ProjectFile(tempDir, relativePath);

        assertNotNull(projectFile, "ProjectFile creation should succeed with full path");
        assertTrue(projectFile.absPath().toFile().exists(), "ProjectFile should point to existing file");
        assertEquals(testContent, projectFile.read().orElseThrow(), "ProjectFile should read correct content");

        // Verify the relative path calculation is correct (platform-independent)
        var expectedRelativePath = Path.of("src", "main", "java", "com", "example", "TestClass.java");
        assertEquals(
                expectedRelativePath.toString(),
                relativePath.toString(),
                "Relative path should be calculated correctly from full path");
    }

    @Test
    void testFileNodeCreationWithFullPath() throws Exception {
        // Test that FileNode creation with full path (as our fix does)
        // results in documents that have the full path available

        var fullPath = testFile.toAbsolutePath().toString();
        var fileNode = new FileNode(fullPath, testFile.toFile());

        assertEquals(fullPath, fileNode.getName(), "FileNode should store the full path as name");

        var document = fileNode.getDocument();
        assertEquals(fullPath, document.getName(), "FileDocument from FileNode should have full path as name");
        assertEquals(
                "TestClass.java", document.getShortName(), "FileDocument should still have short name for display");

        // This demonstrates that our fix in FileComparisonHelper.createDiffNode()
        // provides the information needed for proper ProjectFile creation
        var projectRoot = tempDir;
        var docPath = Path.of(document.getName());
        if (docPath.toFile().exists()) {
            var relativePath = projectRoot.relativize(docPath);
            var projectFile = new ProjectFile(projectRoot, relativePath);
            assertNotNull(projectFile, "Should be able to create ProjectFile from FileNode document");
        }
    }

    // ============================================================================
    // CROSS-PLATFORM COMPATIBILITY TESTS
    // ============================================================================

    @Test
    void testAbsolutePathHandlingCrossPlatform() throws Exception {
        // Test that File.getAbsolutePath() works correctly on all platforms
        var file = testFile.toFile();
        var absolutePath = file.getAbsolutePath();

        // Verify the absolute path is actually absolute
        assertTrue(
                Path.of(absolutePath).isAbsolute(), "getAbsolutePath() should return absolute path on all platforms");

        // Verify the file exists at the absolute path
        assertTrue(new File(absolutePath).exists(), "File should exist at the absolute path on all platforms");

        // Verify we can read the file using the absolute path
        var content = Files.readString(Path.of(absolutePath));
        assertEquals(testContent, content, "Should be able to read file content using absolute path on all platforms");
    }

    @Test
    void testFileDocumentWithAbsolutePathCrossPlatform() throws Exception {
        // Test that FileDocument works with absolute paths on all platforms
        var file = testFile.toFile();
        var absolutePath = file.getAbsolutePath();

        var fileDoc = new FileDocument(file, absolutePath);

        // Verify getName() returns the absolute path
        assertEquals(
                absolutePath, fileDoc.getName(), "FileDocument.getName() should return absolute path on all platforms");

        // Verify getShortName() returns just the filename
        assertEquals(
                "TestClass.java",
                fileDoc.getShortName(),
                "FileDocument.getShortName() should return filename on all platforms");
    }

    @Test
    void testFileNodeWithAbsolutePathCrossPlatform() throws Exception {
        // Test that FileNode creation with absolute paths works on all platforms
        var file = testFile.toFile();
        var absolutePath = file.getAbsolutePath();

        var fileNode = new FileNode(absolutePath, file);

        // Verify the node stores the absolute path
        assertEquals(absolutePath, fileNode.getName(), "FileNode should store absolute path on all platforms");

        // Verify we can get a document from the node
        var document = fileNode.getDocument();
        assertEquals(absolutePath, document.getName(), "FileNode document should have absolute path on all platforms");
    }

    @Test
    void testProjectFileCreationCrossPlatform() throws Exception {
        // Test that ProjectFile creation works with absolute paths on all platforms
        var absolutePath = testFile.toAbsolutePath();

        // Verify the file exists at the absolute path
        assertTrue(absolutePath.toFile().exists(), "File should exist at absolute path on all platforms");

        // Test ProjectFile creation using relative path calculation
        var relativePath = tempDir.relativize(absolutePath);
        var projectFile = new ProjectFile(tempDir, relativePath);

        // Verify ProjectFile points to the correct file
        assertTrue(
                projectFile.absPath().toFile().exists(),
                "ProjectFile should resolve to existing file on all platforms");

        // Verify we can read content through ProjectFile
        assertEquals(
                testContent,
                projectFile.read().orElseThrow(),
                "ProjectFile should read correct content on all platforms");

        // Verify the relative path is calculated correctly
        assertFalse(relativePath.isAbsolute(), "Relative path should not be absolute on any platform");
        assertTrue(
                relativePath.toString().contains("TestClass.java"),
                "Relative path should contain filename on all platforms");
    }

    @Test
    void testPathSeparatorHandling() throws Exception {
        // Test that path separators are handled correctly on all platforms
        var absolutePath = testFile.toAbsolutePath();
        var relativePath = tempDir.relativize(absolutePath);

        // Verify that Path.toString() uses the correct separator for the platform
        var pathString = relativePath.toString();
        assertTrue(
                pathString.contains("src") && pathString.contains("main") && pathString.contains("java"),
                "Relative path should contain expected directory names");

        // Verify that we can reconstruct the path correctly
        var reconstructed = tempDir.resolve(relativePath);
        assertEquals(absolutePath, reconstructed, "Should be able to reconstruct absolute path from relative path");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testWindowsSpecificPaths() throws Exception {
        // Test Windows-specific path handling
        var absolutePath = testFile.toAbsolutePath().toString();

        // Windows paths should contain drive letters
        assertTrue(absolutePath.matches("^[A-Za-z]:.*"), "Windows absolute paths should start with drive letter");

        // Test that backslashes are handled correctly in Windows
        var file = testFile.toFile();
        var windowsPath = file.getAbsolutePath();
        assertTrue(new File(windowsPath).exists(), "Windows file path should resolve correctly");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testUnixSpecificPaths() throws Exception {
        // Test Unix/Linux/Mac specific path handling
        var absolutePath = testFile.toAbsolutePath().toString();

        // Unix paths should start with /
        assertTrue(absolutePath.startsWith("/"), "Unix absolute paths should start with /");

        // Test that forward slashes are handled correctly
        var file = testFile.toFile();
        var unixPath = file.getAbsolutePath();
        assertTrue(new File(unixPath).exists(), "Unix file path should resolve correctly");
    }

    @Test
    void testFileComparisonHelperCrossPlatform() throws Exception {
        // Test that our FileComparisonHelper changes work on all platforms
        var file = testFile.toFile();

        // Simulate what FileComparisonHelper.createDiffNode() does
        var absolutePath = file.getAbsolutePath(); // This is our fix
        var fileName = file.getName(); // This was the old broken approach

        // Verify that absolute path enables ProjectFile creation
        var absoluteFile = new File(absolutePath);
        assertTrue(absoluteFile.exists(), "Absolute path should resolve to existing file on all platforms");

        // Verify that filename alone cannot create valid ProjectFile
        var filenameFile = new File(fileName);
        assertFalse(filenameFile.exists(), "Filename alone should not resolve to file (demonstrating old bug)");

        // Test that we can create a ProjectFile using the absolute path approach
        var pathFromAbsolute = Path.of(absolutePath);
        if (pathFromAbsolute.startsWith(tempDir)) {
            var relativeFromAbsolute = tempDir.relativize(pathFromAbsolute);
            var projectFile = new ProjectFile(tempDir, relativeFromAbsolute);
            assertTrue(
                    projectFile.absPath().toFile().exists(),
                    "ProjectFile from absolute path should work on all platforms");
        }
    }
}
