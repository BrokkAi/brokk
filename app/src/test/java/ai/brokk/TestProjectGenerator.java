package ai.brokk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Generates test projects of various sizes for file watcher benchmarking.
 */
public class TestProjectGenerator {

    private final Random random = new Random(42); // Deterministic for reproducibility

    public enum ProjectSize {
        SMALL(100, 3, 5),
        MEDIUM(10_000, 5, 10),
        LARGE(100_000, 8, 15);

        final int fileCount;
        final int minDepth;
        final int maxDepth;

        ProjectSize(int fileCount, int minDepth, int maxDepth) {
            this.fileCount = fileCount;
            this.minDepth = minDepth;
            this.maxDepth = maxDepth;
        }
    }

    /**
     * Generate a test project with specified characteristics.
     *
     * @param root Root directory for the project
     * @param size Size category of the project
     * @return Path to the generated project root
     */
    public Path generateProject(Path root, ProjectSize size) throws IOException {
        Files.createDirectories(root);

        // Create directory structure
        createDirectoryTree(root, 0, size.maxDepth, size.fileCount);

        return root;
    }

    private void createDirectoryTree(Path parent, int currentDepth, int maxDepth, int remainingFiles)
            throws IOException {
        if (remainingFiles <= 0 || currentDepth >= maxDepth) {
            return;
        }

        // Calculate how many files to create at this level
        int filesAtThisLevel = Math.min(remainingFiles / 4, 50);
        int filesLeft = remainingFiles - filesAtThisLevel;

        // Create files at this level
        for (int i = 0; i < filesAtThisLevel; i++) {
            createFile(parent, "file_" + i, getFileExtension());
        }

        // Create subdirectories
        if (currentDepth < maxDepth - 1 && filesLeft > 0) {
            int subdirCount = Math.min(5, Math.max(1, filesLeft / 20));
            int filesPerSubdir = filesLeft / subdirCount;

            for (int i = 0; i < subdirCount; i++) {
                Path subdir = parent.resolve("dir_" + currentDepth + "_" + i);
                Files.createDirectories(subdir);
                createDirectoryTree(subdir, currentDepth + 1, maxDepth, filesPerSubdir);
            }
        }
    }

    private void createFile(Path parent, String baseName, String extension) throws IOException {
        Path file = parent.resolve(baseName + extension);
        String content = generateFileContent(extension);
        Files.writeString(file, content);
    }

    private String getFileExtension() {
        String[] extensions = {".java", ".ts", ".py", ".js", ".txt", ".md", ".json", ".xml"};
        return extensions[random.nextInt(extensions.length)];
    }

    private String generateFileContent(String extension) {
        return switch (extension) {
            case ".java" -> generateJavaContent();
            case ".ts", ".js" -> generateJsContent();
            case ".py" -> generatePythonContent();
            case ".json" -> generateJsonContent();
            case ".xml" -> generateXmlContent();
            default -> generateTextContent();
        };
    }

    private String generateJavaContent() {
        return """
                package com.example;

                public class TestClass {
                    private String field;

                    public void method() {
                        System.out.println("Test");
                    }
                }
                """;
    }

    private String generateJsContent() {
        return """
                function testFunction() {
                    const x = 42;
                    console.log(x);
                    return x * 2;
                }

                module.exports = { testFunction };
                """;
    }

    private String generatePythonContent() {
        return """
                def test_function():
                    x = 42
                    print(x)
                    return x * 2

                if __name__ == '__main__':
                    test_function()
                """;
    }

    private String generateJsonContent() {
        return """
                {
                    "name": "test",
                    "version": "1.0.0",
                    "description": "Test file",
                    "main": "index.js"
                }
                """;
    }

    private String generateXmlContent() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <name>test</name>
                    <version>1.0.0</version>
                </project>
                """;
    }

    private String generateTextContent() {
        return "Test content line 1\nTest content line 2\nTest content line 3\n";
    }

    /**
     * Create a git-like directory structure for testing git metadata changes.
     */
    public void createGitStructure(Path projectRoot) throws IOException {
        Path gitDir = projectRoot.resolve(".git");
        Files.createDirectories(gitDir);

        // Create typical git metadata files
        Files.createDirectories(gitDir.resolve("objects"));
        Files.createDirectories(gitDir.resolve("refs/heads"));
        Files.createDirectories(gitDir.resolve("refs/remotes/origin"));
        Files.createDirectories(gitDir.resolve("logs"));
        Files.createDirectories(gitDir.resolve("info"));

        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(gitDir.resolve("config"), "[core]\nrepositoryformatversion = 0\n");
        Files.writeString(gitDir.resolve("refs/heads/main"), "abc123\n");
        Files.writeString(gitDir.resolve("refs/remotes/origin/main"), "abc123\n");
        Files.writeString(gitDir.resolve("info/exclude"), "*.tmp\n");
    }

    /**
     * Delete a generated test project.
     */
    public void deleteProject(Path root) throws IOException {
        if (Files.exists(root)) {
            Files.walk(root)
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Best effort cleanup
                        }
                    });
        }
    }
}
