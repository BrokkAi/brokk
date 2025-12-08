package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class TypeInferenceChainedResolutionTest {

    @Test
    public void chainedFieldAndMethodResolution() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                """
                package p;

                public class Leaf {
                    public int value;
                }

                public class Node {
                    public Leaf leaf;
                    public Leaf getLeaf() { return leaf; }
                }

                public class Helper {
                    public Node process() { return new Node(); }
                }

                public class Use {
                    public void test() {
                        Node n = new Node();
                        int a = n.leaf.value;
                        int b = n.getLeaf().value;
                        int c = new Helper().process().getLeaf().value;
                    }
                }
                """,
                "X.java").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var pf = new ProjectFile(project.getRoot(), "X.java");
            var src = pf.read().orElseThrow();

            int idxA = src.indexOf("n.leaf.value");
            assertTrue(idxA >= 0);
            int offA = src.substring(0, idxA).getBytes(StandardCharsets.UTF_8).length + "n.leaf.".length(); // inside 'value'

            int idxB = src.indexOf("n.getLeaf().value");
            assertTrue(idxB >= 0);
            int offB = src.substring(0, idxB).getBytes(StandardCharsets.UTF_8).length + "n.getLeaf().".length();

            int idxC = src.indexOf("new Helper().process().getLeaf().value");
            assertTrue(idxC >= 0);
            int offC = src.substring(0, idxC).getBytes(StandardCharsets.UTF_8).length + "new Helper().process().getLeaf().".length();

            Optional<CodeUnit> r1 = analyzer.inferTypeAt(pf, offA);
            assertTrue(r1.isPresent(), "n.leaf.value should resolve");
            assertEquals("p.Leaf.value", r1.get().fqName());

            Optional<CodeUnit> r2 = analyzer.inferTypeAt(pf, offB);
            assertTrue(r2.isPresent(), "n.getLeaf().value should resolve");
            assertEquals("p.Leaf.value", r2.get().fqName());

            Optional<CodeUnit> r3 = analyzer.inferTypeAt(pf, offC);
            assertTrue(r3.isPresent(), "new Helper().process().getLeaf().value should resolve");
            assertEquals("p.Leaf.value", r3.get().fqName());
        }
    }
}
