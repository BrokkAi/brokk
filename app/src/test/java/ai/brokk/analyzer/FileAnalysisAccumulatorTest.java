package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FileAnalysisAccumulatorTest {

    @Test
    void addChild_ignoresSelfParentEdge() {
        Path root = Path.of("").toAbsolutePath();
        ProjectFile file = new ProjectFile(root, "a/A.java");
        CodeUnit cu = CodeUnit.cls(file, "pkg", "A");

        var acc = new FileAnalysisAccumulator();
        acc.addChild(cu, cu);

        assertTrue(acc.getChildren(cu).isEmpty(), "Self-edge must not appear as children");
    }

    @Test
    void addChild_stillRecordsDistinctParentAndChild() {
        Path root = Path.of("").toAbsolutePath();
        ProjectFile file = new ProjectFile(root, "a/A.java");
        CodeUnit parent = CodeUnit.cls(file, "pkg", "Outer");
        CodeUnit child = CodeUnit.fn(file, "pkg", "Outer.method");

        var acc = new FileAnalysisAccumulator();
        acc.addChild(parent, child);

        assertEquals(1, acc.getChildren(parent).size());
        assertTrue(acc.getChildren(parent).contains(child));
        assertFalse(acc.getChildren(child).contains(parent));
    }
}
