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

public class NodeDependencyToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void importNpmPackage_InvalidSpec_ReturnsErrorMessage() throws InterruptedException {
        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var cm = new TestContextManager(project);
        var tools = new NodeDependencyTools(cm);

        String[] invalidInputs = {"", "  "};

        for (String input : invalidInputs) {
            String result = tools.importNpmPackage(input);
            assertTrue(result.contains("Invalid package name"), "Should fail for: '" + input + "'");
        }
    }

    @Test
    void importNpmPackage_NoNodeModules_ReturnsErrorMessage() throws InterruptedException {
        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var cm = new TestContextManager(project);
        var tools = new NodeDependencyTools(cm);

        String result = tools.importNpmPackage("lodash");
        assertTrue(result.contains("No npm packages found"),
                   "Should report no packages when no node_modules exists");
    }

    @Test
    void importNpmPackage_PackageNotFound_ReturnsErrorMessage() throws Exception {
        // Create a node_modules with a different package
        var nodeModules = tempDir.resolve("node_modules");
        var pkgDir = nodeModules.resolve("express");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("package.json"),
                          "{\"name\": \"express\", \"version\": \"4.18.2\"}");
        Files.writeString(pkgDir.resolve("index.js"), "module.exports = {};");

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var cm = new TestContextManager(project);
        var tools = new NodeDependencyTools(cm);

        String result = tools.importNpmPackage("lodash");
        assertTrue(result.contains("not found") || result.contains("npm install"),
                   "Should report package not found, got: " + result);
    }

    @Test
    void isSupported_TypeScriptProject_ReturnsTrue() {
        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        assertTrue(NodeDependencyTools.isSupported(project));
    }

    @Test
    void isSupported_JavaScriptProject_ReturnsTrue() {
        var project = new TestProject(tempDir, Languages.JAVASCRIPT);
        assertTrue(NodeDependencyTools.isSupported(project));
    }

    @Test
    void isSupported_JavaProject_ReturnsFalse() {
        var project = new TestProject(tempDir, Languages.JAVA);
        assertFalse(NodeDependencyTools.isSupported(project));
    }

    @Test
    void isSupported_RustProject_ReturnsFalse() {
        var project = new TestProject(tempDir, Languages.RUST);
        assertFalse(NodeDependencyTools.isSupported(project));
    }

    @Test
    void importNpmPackage_ScopedPackage_HandlesCorrectly() throws Exception {
        // Create a scoped package in node_modules
        var nodeModules = tempDir.resolve("node_modules");
        var scopeDir = nodeModules.resolve("@types");
        var pkgDir = scopeDir.resolve("node");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("package.json"),
                          "{\"name\": \"@types/node\", \"version\": \"20.0.0\"}");
        Files.writeString(pkgDir.resolve("index.d.ts"), "declare module 'node' {}");

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var cm = new TestContextManager(project);
        var tools = new NodeDependencyTools(cm);

        // Looking for a different scoped package that doesn't exist
        String result = tools.importNpmPackage("@types/lodash");
        assertTrue(result.contains("not found") || result.contains("npm install"),
                   "Should report scoped package not found, got: " + result);
    }
}
