package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PythonDependencyToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void importPythonPackage_InvalidSpec_ReturnsErrorMessage() throws InterruptedException {
        var project = new TestProject(tempDir, Languages.PYTHON);
        var cm = new TestContextManager(project);
        var tools = new PythonDependencyTools(cm);

        String[] invalidInputs = {"", "  "};

        for (String input : invalidInputs) {
            String result = tools.importPythonPackage(input);
            assertTrue(result.contains("Invalid package specification"), "Should fail for: '" + input + "'");
        }
    }

    @Test
    void importPythonPackage_NoVenv_ReturnsErrorMessage() throws InterruptedException {
        var project = new TestProject(tempDir, Languages.PYTHON);
        var cm = new TestContextManager(project);
        var tools = new PythonDependencyTools(cm);

        String result = tools.importPythonPackage("requests");
        assertTrue(result.contains("No Python packages found"),
                   "Should report no packages when no venv exists");
    }

    @Test
    void importPythonPackage_PackageNotFound_ReturnsErrorMessage() throws Exception {
        // Create a minimal venv structure with a different package
        var venvDir = tempDir.resolve(".venv");
        var libDir = venvDir.resolve("lib/python3.11/site-packages");
        Files.createDirectories(libDir);

        // Create a dummy package dist-info
        var distInfo = libDir.resolve("dummy_pkg-1.0.0.dist-info");
        Files.createDirectories(distInfo);
        Files.writeString(distInfo.resolve("METADATA"), "Name: dummy_pkg\nVersion: 1.0.0\n");
        Files.writeString(distInfo.resolve("RECORD"), "dummy_pkg/__init__.py,sha256=abc,0\n");

        // Create the actual package directory
        var pkgDir = libDir.resolve("dummy_pkg");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("__init__.py"), "# dummy package");

        var project = new TestProject(tempDir, Languages.PYTHON);
        var cm = new TestContextManager(project);
        var tools = new PythonDependencyTools(cm);

        String result = tools.importPythonPackage("requests");
        assertTrue(result.contains("not found") || result.contains("pip install"),
                   "Should report package not found, got: " + result);
    }

    @Test
    void isSupported_PythonProject_ReturnsTrue() {
        var project = new TestProject(tempDir, Languages.PYTHON);
        assertTrue(PythonDependencyTools.isSupported(project));
    }

    @Test
    void isSupported_JavaProject_ReturnsFalse() {
        var project = new TestProject(tempDir, Languages.JAVA);
        assertFalse(PythonDependencyTools.isSupported(project));
    }

    @Test
    void importPythonPackage_WithVersion_SearchesCorrectly() throws Exception {
        // Create a venv with a specific version
        var venvDir = tempDir.resolve(".venv");
        var libDir = venvDir.resolve("lib/python3.11/site-packages");
        Files.createDirectories(libDir);

        var distInfo = libDir.resolve("mylib-2.0.0.dist-info");
        Files.createDirectories(distInfo);
        Files.writeString(distInfo.resolve("METADATA"), "Name: mylib\nVersion: 2.0.0\n");
        Files.writeString(distInfo.resolve("RECORD"), "mylib/__init__.py,sha256=abc,0\n");

        var pkgDir = libDir.resolve("mylib");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("__init__.py"), "# mylib");

        var project = new TestProject(tempDir, Languages.PYTHON);
        var cm = new TestContextManager(project);
        var tools = new PythonDependencyTools(cm);

        // Request a different version that doesn't exist
        String result = tools.importPythonPackage("mylib 1.0.0");
        assertTrue(result.contains("not found") || result.contains("version"),
                   "Should report version mismatch, got: " + result);
    }
}
