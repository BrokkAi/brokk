package ai.brokk.analyzer;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterTypescript;

public class AbstractGetterNodeTypeTest {

    @Test
    void testAbstractGetterNodeType() {
        var source =
                """
            abstract class Test {
                abstract get name(): string;

                get concreteGetter(): string {
                    return "value";
                }
            }
            """;

        var parser = new TSParser();
        parser.setLanguage(new TreeSitterTypescript());
        var tree = parser.parseString(null, source);
        var root = tree.getRootNode();

        System.out.println("=== AST Structure ===");
        printTree(root, source.getBytes(StandardCharsets.UTF_8), 0);
    }

    private void printTree(TSNode node, byte[] source, int depth) {
        if (node == null || node.isNull()) {
            return;
        }

        String indent = "  ".repeat(depth);
        String nodeType = node.getType();

        // Get node text if it's a leaf or small node
        String text = "";
        if (node.getChildCount() == 0 || node.getEndByte() - node.getStartByte() < 20) {
            text = new String(
                    source, node.getStartByte(), node.getEndByte() - node.getStartByte(), StandardCharsets.UTF_8);
            text = " [" + text.replace("\n", "\\n") + "]";
        }

        System.out.println(indent + nodeType + text);

        // Recurse to children
        for (int i = 0; i < node.getChildCount(); i++) {
            printTree(node.getChild(i), source, depth + 1);
        }
    }
}
