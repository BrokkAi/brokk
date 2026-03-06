package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoModulePathTest {

    @Test
    void testGetTestModulesWithProjectFiles() {
        Path root = Path.of("").toAbsolutePath();
        // 1) File in a subdirectory -> should be prefixed with "./"
        ProjectFile subFile = new ProjectFile(root, "callbacks/test.go");
        List<String> subModules = GoAnalyzer.getTestModulesStatic(List.of(subFile));
        assertEquals(List.of("./callbacks"), subModules);

        // 2) File in project root -> should be "."
        ProjectFile rootFile = new ProjectFile(root, "main_test.go");
        List<String> rootModules = GoAnalyzer.getTestModulesStatic(List.of(rootFile));
        assertEquals(List.of("."), rootModules);

        // 3) Mixed files -> results are distinct, sorted
        List<String> mixedModules = GoAnalyzer.getTestModulesStatic(List.of(subFile, rootFile));
        assertEquals(List.of(".", "./callbacks"), mixedModules);
    }

    @Test
    void testGetTestModulesPrefixing() {
        assertEquals(".", GoAnalyzer.formatTestModule(null));
        assertEquals(".", GoAnalyzer.formatTestModule(Path.of(".")));
        assertEquals(".", GoAnalyzer.formatTestModule(Path.of("./")));
        assertEquals(".", GoAnalyzer.formatTestModule(Path.of("/")));
        assertEquals("./subdir", GoAnalyzer.formatTestModule(Path.of("subdir")));
        assertEquals("./a/b/c", GoAnalyzer.formatTestModule(Path.of("a", "b", "c")));
        assertEquals("./leading/slash", GoAnalyzer.formatTestModule(Path.of("/leading/slash")));
        // Windows-style input should be normalized to forward slashes and prefixed with ./
        assertEquals("./x/y", GoAnalyzer.formatTestModule(Path.of("x\\y")));
        assertEquals("./sub/dir", GoAnalyzer.formatTestModule(Path.of("sub\\dir")));
    }

    @Test
    void testCrossPlatformPathNormalization() {
        // Verify that backslashes are normalized to forward slashes with ./ prefix
        assertEquals("./sub/dir", GoAnalyzer.formatTestModule(Path.of("sub\\dir")));
        assertEquals("./callbacks/sub", GoAnalyzer.formatTestModule(Path.of("callbacks\\sub")));
        assertEquals("./a/b/c", GoAnalyzer.formatTestModule(Path.of("a\\b\\c")));

        // Verify that forward slashes are preserved with ./ prefix
        assertEquals("./sub/dir", GoAnalyzer.formatTestModule(Path.of("sub/dir")));
        assertEquals("./callbacks/sub", GoAnalyzer.formatTestModule(Path.of("callbacks/sub")));

        // Verify that mixed slashes are normalized
        assertEquals("./a/b/c", GoAnalyzer.formatTestModule(Path.of("a/b\\c")));
    }

    @Test
    void testLocalDirectoryPrefixing() {
        // Input: Path.of("callbacks") -> Assert Output: "./callbacks"
        assertEquals("./callbacks", GoAnalyzer.formatTestModule(Path.of("callbacks")));
        // Input: Path.of("pkg/sub") -> Assert Output: "./pkg/sub"
        assertEquals("./pkg/sub", GoAnalyzer.formatTestModule(Path.of("pkg/sub")));
    }
}
