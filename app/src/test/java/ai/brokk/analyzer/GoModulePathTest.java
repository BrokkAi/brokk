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
        assertEquals("./subdir", GoAnalyzer.formatTestModule(Path.of("subdir")));
        assertEquals("./a/b/c", GoAnalyzer.formatTestModule(Path.of("a", "b", "c")));
        // Windows-style input should be normalized
        assertEquals("./x/y", GoAnalyzer.formatTestModule(Path.of("x\\y")));
    }
}
