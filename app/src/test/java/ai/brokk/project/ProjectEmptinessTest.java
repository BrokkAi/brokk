package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the project emptiness heuristic based on analyzable source files.
 */
class ProjectEmptinessTest {

    @Test
    void isEmptyProject_trulyEmptyDirectory(@TempDir Path tempDir) {
        var project = new TestProject(tempDir);
        assertTrue(project.isEmptyProject(), "Empty directory should be considered an empty project");
    }

    @Test
    void isEmptyProject_onlyConfigFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Style Guide\n");
        Files.writeString(tempDir.resolve(".gitignore"), ".brokk/\n");

        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Files.writeString(brokkDir.resolve("project.properties"), "# properties\n");
        Files.writeString(brokkDir.resolve("review.md"), "# Review guide\n");

        var project = new TestProject(tempDir);
        assertTrue(
                project.isEmptyProject(),
                "Directory with only config files (AGENTS.md, .brokk/**, .gitignore) should be considered empty");
    }

    @Test
    void isEmptyProject_withJavaFile(@TempDir Path tempDir) throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), "public class Main {}\n");

        var project = new TestProject(tempDir);
        assertFalse(project.isEmptyProject(), "Directory with a .java file should NOT be considered empty");
    }

    @Test
    void isEmptyProject_withPythonFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.py"), "print('hello')\n");

        var project = new TestProject(tempDir);
        assertFalse(project.isEmptyProject(), "Directory with a .py file should NOT be considered empty");
    }

    @Test
    void isEmptyProject_withJavaScriptFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("app.js"), "console.log('hello');\n");

        var project = new TestProject(tempDir);
        assertFalse(project.isEmptyProject(), "Directory with a .js file should NOT be considered empty");
    }

    @Test
    void isEmptyProject_withTypeScriptFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("app.ts"), "const x: number = 1;\n");

        var project = new TestProject(tempDir);
        assertFalse(project.isEmptyProject(), "Directory with a .ts file should NOT be considered empty");
    }

    @Test
    void isEmptyProject_configFilesAndSourceFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Style Guide\n");
        Files.writeString(tempDir.resolve(".gitignore"), ".brokk/\n");

        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Files.writeString(brokkDir.resolve("project.properties"), "# properties\n");

        Files.writeString(tempDir.resolve("main.py"), "print('hello')\n");

        var project = new TestProject(tempDir);
        assertFalse(
                project.isEmptyProject(),
                "Directory with config files AND source files should NOT be considered empty");
    }

    @Test
    void isEmptyProject_onlyTextAndMarkdownFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "# Readme\n");
        Files.writeString(tempDir.resolve("notes.txt"), "Some notes\n");
        Files.writeString(tempDir.resolve("config.json"), "{}\n");
        Files.writeString(tempDir.resolve("data.xml"), "<root/>\n");

        var project = new TestProject(tempDir);
        assertTrue(
                project.isEmptyProject(),
                "Directory with only non-analyzable files (md, txt, json, xml) should be considered empty");
    }

    @Test
    void isEmptyProject_nestedSourceFile(@TempDir Path tempDir) throws IOException {
        Path deepDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(deepDir);
        Files.writeString(deepDir.resolve("App.java"), "package com.example;\npublic class App {}\n");

        var project = new TestProject(tempDir);
        assertFalse(project.isEmptyProject(), "Directory with a nested source file should NOT be considered empty");
    }

    @Test
    void isEmptyProject_goFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.go"), "package main\n");

        var project = new TestProject(tempDir);
        assertFalse(project.isEmptyProject(), "Directory with a .go file should NOT be considered empty");
    }

    @Test
    void isEmptyProject_cppFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.cpp"), "int main() { return 0; }\n");

        var project = new TestProject(tempDir);
        assertFalse(project.isEmptyProject(), "Directory with a .cpp file should NOT be considered empty");
    }

    @Test
    void isEmptyProject_rustFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.rs"), "fn main() {}\n");

        var project = new TestProject(tempDir);
        assertFalse(project.isEmptyProject(), "Directory with a .rs file should NOT be considered empty");
    }

    @Test
    void isEmptyProject_interfaceDefaultReturnsFalse() {
        IProject minimalProject = new IProject() {
            @Override
            public Path getRoot() {
                return Path.of("/tmp");
            }

            @Override
            public Path getMasterRootPathForConfig() {
                return Path.of("/tmp");
            }
        };
        assertFalse(
                minimalProject.isEmptyProject(),
                "IProject default implementation should return false (assume not empty)");
    }
}
