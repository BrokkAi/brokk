package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ContextAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void filterAnonymousSummaries_removesAnonEntries() {
        ProjectFile file = new ProjectFile(tempDir, "src/pkg/Foo.java");

        CodeUnit regular = CodeUnit.cls(file, "pkg", "Foo");
        CodeUnit anon = CodeUnit.cls(file, "pkg", "Foo$anon$1");

        Map<CodeUnit, String> input = new LinkedHashMap<>();
        input.put(regular, "class Foo {}");
        input.put(anon, "class Foo$anon$1 {}");

        Map<CodeUnit, String> out = ContextAgent.filterAnonymousSummaries(input);

        assertTrue(out.containsKey(regular));
        assertFalse(out.containsKey(anon));
        assertTrue(out.values().stream().noneMatch(s -> s.contains("$anon$")));
        assertEquals(1, out.size());
    }
}
