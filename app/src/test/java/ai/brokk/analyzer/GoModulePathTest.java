package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GoModulePathTest {

    @Test
    void testGetTestModulesPrefixing() {
        assertEquals(".", GoAnalyzer.formatTestModule(null));
        assertEquals("./subdir", GoAnalyzer.formatTestModule(Path.of("subdir")));
        assertEquals("./a/b/c", GoAnalyzer.formatTestModule(Path.of("a", "b", "c")));
        // Windows-style input should be normalized
        assertEquals("./x/y", GoAnalyzer.formatTestModule(Path.of("x\\y")));
    }
}
