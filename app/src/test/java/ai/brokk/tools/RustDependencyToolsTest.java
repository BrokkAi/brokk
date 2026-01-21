package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RustDependencyToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void importRustCrate_InvalidSpec_ReturnsErrorMessage() throws InterruptedException {
        var project = new TestProject(tempDir, Languages.RUST);
        var cm = new TestContextManager(project);
        var tools = new RustDependencyTools(cm);

        String[] invalidInputs = {"", "  "};

        for (String input : invalidInputs) {
            String result = tools.importRustCrate(input);
            assertTrue(result.contains("Invalid crate specification"), "Should fail for: '" + input + "'");
        }
    }

    @Test
    void importRustCrate_NoCargo_ReturnsErrorMessage() throws InterruptedException {
        var project = new TestProject(tempDir, Languages.RUST);
        var cm = new TestContextManager(project);
        var tools = new RustDependencyTools(cm);

        String result = tools.importRustCrate("serde");
        assertTrue(result.contains("No Rust crates found") || result.contains("not found"),
                   "Should report no crates when not a Cargo project, got: " + result);
    }

    @Test
    void isSupported_RustProject_ReturnsTrue() {
        var project = new TestProject(tempDir, Languages.RUST);
        assertTrue(RustDependencyTools.isSupported(project));
    }

    @Test
    void isSupported_JavaProject_ReturnsFalse() {
        var project = new TestProject(tempDir, Languages.JAVA);
        assertFalse(RustDependencyTools.isSupported(project));
    }

    @Test
    void isSupported_PythonProject_ReturnsFalse() {
        var project = new TestProject(tempDir, Languages.PYTHON);
        assertFalse(RustDependencyTools.isSupported(project));
    }
}
